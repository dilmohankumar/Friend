/**
 * Identity verification (KYC).
 *
 * Flow: app -> POST /api/kyc/start -> opens provider URL in a Chrome Custom Tab -> provider redirects to
 * GET /api/kyc/callback with an HMAC-SIGNED result -> backend verifies signature, updates user ->
 * redirects to the app deep link (situationship://kyc/return?result=...) -> app re-reads /api/kyc/status.
 * The app can never mark itself verified.
 *
 * A MOCK provider (/mock-kyc) is included so the flow works end-to-end in development. Replace it with an
 * authorised, legally permitted verification provider before real users. Never store Aadhaar numbers.
 */
import {
  BadRequestException, Body, Controller, Get, HttpCode, HttpException, Logger, NotFoundException, Param, Post, Query, Req, Res, UseGuards,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import { PrismaService } from '../prisma/prisma.service';
import { RateLimiter } from '../redis/redis.module';
import { AuthGuard, CurrentUser } from '../common/auth.guard';
import { config } from '../config';
import { hmac, safeEqual, uuid } from '../common/security';
import { KycProviders } from './kyc-provider';
import { DigiLockerProvider } from './digilocker.provider';
import type { User } from '../generated/prisma/client';

interface ProviderResult {
  sessionId: string; result: 'verified' | 'failed'; ageEligible: boolean; birthYear: number | null; providerRef: string; identityHash?: string; ts: number;
}

@Controller('api/kyc')
export class KycController {
  private readonly log = new Logger('Kyc');
  constructor(
    private readonly prisma: PrismaService,
    private readonly limiter: RateLimiter,
    private readonly providers: KycProviders,
    private readonly digilocker: DigiLockerProvider,
  ) {}

  @Post('consent')
  @HttpCode(200)
  @UseGuards(AuthGuard)
  async consent(@CurrentUser() user: User, @Body() body: { accept?: boolean }) {
    if (body?.accept !== true) throw new BadRequestException({ error: 'consent_required' });
    await this.prisma.user.update({
      where: { id: user.id },
      data: { consentAt: new Date(), ...(user.status === 'CONSENT_PENDING' ? { status: 'IDENTITY_PENDING' } : {}) },
    });
    return { ok: true, status: 'IDENTITY_PENDING' };
  }

  @Post('start')
  @HttpCode(200)
  @UseGuards(AuthGuard)
  async start(@CurrentUser() user: User) {
    if (!user.consentAt) throw new BadRequestException({ error: 'consent_required' });
    if (user.kycStatus === 'verified') throw new BadRequestException({ error: 'already_verified' });
    if (!(await this.limiter.allow(`kyc:${user.id}`, 10, 60 * 60_000))) throw new HttpException({ error: 'too_many_requests' }, 429);
    // LIVE mode has no mock provider: with none configured, refuse clearly instead of letting anyone self-verify.
    const provider = this.providers.active();
    if (!provider) throw new HttpException({ error: 'kyc_provider_not_configured' }, 503);
    const session = await this.prisma.kycSession.create({ data: { userId: user.id } });
    let redirectUrl: string;
    try {
      ({ redirectUrl } = await provider.start(user, session.id));
    } catch {
      await this.prisma.kycSession.update({ where: { id: session.id }, data: { status: 'failed' } });
      throw new HttpException({ error: 'kyc_provider_unavailable' }, 503);
    }
    await this.prisma.user.update({ where: { id: user.id }, data: { kycStatus: 'pending' } });
    return { sessionId: session.id, redirectUrl };
  }

  @Get('status')
  @UseGuards(AuthGuard)
  status(@CurrentUser() u: User) {
    return {
      verificationStatus: u.kycStatus,
      ageEligible: u.ageEligible,
      profileSetupRequired: u.status === 'PROFILE_INCOMPLETE',
      status: u.status,
    };
  }

  /** Provider redirects the browser here with a signed payload. */
  @Get('callback')
  async callback(@Query('payload') payload: string, @Query('sig') sig: string, @Res() res: Response) {
    if (!payload || !sig || !safeEqual(hmac(payload, config.KYC_PROVIDER_SECRET), sig)) {
      return res.status(400).send('Invalid provider signature');
    }
    let data: ProviderResult;
    try { data = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')); } catch { return res.status(400).send('Bad payload'); }
    if (data.ts < Date.now() - 10 * 60_000) return res.status(400).send('Expired result');

    const result = await this.applyResult(data);
    if (!result) return res.status(400).send('Unknown or used session');
    return res.redirect(302, `${config.APP_DEEP_LINK}?result=${result}`);
  }

  /**
   * DigiLocker redirects the user's browser here after consent. The one-time `state` (bound to our KYC session and a
   * PKCE verifier kept in Redis) proves this response answers a request WE started, so nothing here is forgeable.
   */
  @Get('digilocker/callback')
  async digilockerCallback(
    @Query('code') code: string | undefined, @Query('state') state: string | undefined, @Query('error') error: string | undefined,
    @Req() req: Request, @Res() res: Response,
  ) {
    if (!(await this.limiter.allow(`dlcb:${req.ip}`, 120, 60_000))) return res.status(429).send('Too many requests');
    const pending = await this.digilocker.consume(String(state ?? ''));
    if (!pending) return res.status(400).send('Unknown or expired verification request');
    const base = { sessionId: pending.sessionId, ageEligible: false, birthYear: null, providerRef: 'DL', ts: Date.now() };
    let data: ProviderResult = { ...base, result: 'failed' }; // denied, errored or unverifiable => not verified
    if (!error && code) {
      try {
        const id = await this.digilocker.identityFromCode(String(code), pending.verifier);
        if (id.aadhaarBacked || !this.digilockerAadhaarRequired()) {
          data = {
            ...base, result: 'verified', ageEligible: id.ageEligible, birthYear: id.birthYear, providerRef: id.reference,
            identityHash: DigiLockerProvider.fingerprint(id.digilockerId),
          };
        }
      } catch (e) {
        this.log.warn(`DigiLocker verification failed: ${(e as Error).message}`); // never log tokens / personal data
      }
    }
    const result = await this.applyResult(data);
    if (!result) return res.status(400).send('Unknown or used session');
    return res.redirect(302, `${config.APP_DEEP_LINK}?result=${result}`);
  }

  private digilockerAadhaarRequired() { return String(process.env.DIGILOCKER_REQUIRE_EAADHAAR ?? '').trim() !== 'false'; }

  /** Single completion path for every provider: consumes the KYC session once and records the verified facts. */
  private async applyResult(data: ProviderResult): Promise<string | null> {
    // Atomically consume the session (single use)
    const { count } = await this.prisma.kycSession.updateMany({ where: { id: data.sessionId, status: 'started' }, data: { status: 'completed' } });
    if (count !== 1) return null;
    const session = await this.prisma.kycSession.findUniqueOrThrow({ where: { id: data.sessionId } });
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: session.userId } });

    let result: string;
    if (data.result === 'verified') {
      const eligible = data.ageEligible === true;
      const moveOn = user.status === 'CONSENT_PENDING' || user.status === 'IDENTITY_PENDING';
      const fingerprint = typeof data.identityHash === 'string' && data.identityHash ? data.identityHash.slice(0, 128) : null;
      // One real person = one account: an identity already verified on another account cannot be reused.
      const taken = fingerprint ? await this.prisma.user.findFirst({ where: { kycFingerprint: fingerprint, id: { not: user.id } }, select: { id: true } }) : null;
      if (taken) {
        await this.prisma.user.update({ where: { id: user.id }, data: { kycStatus: 'failed' } });
        result = 'duplicate';
      } else {
        try {
          await this.prisma.user.update({
            where: { id: user.id },
            data: {
              kycStatus: 'verified', kycRef: data.providerRef, kycFingerprint: fingerprint, ageEligible: eligible, birthYear: data.birthYear,
              ...(moveOn ? { status: eligible ? 'PROFILE_INCOMPLETE' : 'IDENTITY_PENDING' } : {}),
            },
          });
          result = eligible ? 'verified' : 'underage';
        } catch (e) {
          // lost a race against another account claiming the same identity (unique constraint)
          if ((e as { code?: string }).code !== 'P2002') throw e;
          await this.prisma.user.update({ where: { id: user.id }, data: { kycStatus: 'failed' } });
          result = 'duplicate';
        }
      }
    } else {
      await this.prisma.user.update({ where: { id: user.id }, data: { kycStatus: 'failed' } });
      result = 'failed';
    }
    return result;
  }
}

// ------------------------- MOCK PROVIDER (development only) -------------------------
const page = (body: string) => `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Mock Identity Provider</title>
<style>
 body{font-family:system-ui,sans-serif;background:#f3f4f6;margin:0;padding:16px;color:#111}
 .card{max-width:420px;margin:24px auto;background:#fff;border-radius:14px;padding:22px;box-shadow:0 4px 20px #0001}
 .tag{display:inline-block;background:#fef3c7;color:#92400e;font-size:12px;padding:4px 8px;border-radius:6px;font-weight:600}
 h1{font-size:20px;margin:12px 0 4px} p{color:#555;font-size:14px;line-height:1.5}
 label{display:block;font-size:13px;font-weight:600;margin:14px 0 6px}
 input{width:100%;box-sizing:border-box;padding:12px;border:1px solid #ddd;border-radius:10px;font-size:15px}
 .row{display:flex;gap:8px;align-items:flex-start;margin-top:14px;font-size:13px;color:#444}.row input{width:auto;margin-top:2px}
 button{width:100%;margin-top:18px;padding:14px;border:0;border-radius:10px;font-size:15px;font-weight:600}
 .ok{background:#2563eb;color:#fff}.no{background:#eee;color:#333;margin-top:8px}
</style></head><body><div class="card">${body}</div></body></html>`;

@Controller('mock-kyc')
export class MockKycProviderController {
  constructor(private readonly prisma: PrismaService) {}

  private async session(sid: string) {
    if (!config.MOCK_KYC) throw new NotFoundException();
    if (!/^[0-9a-f-]{36}$/.test(sid)) return null;
    const s = await this.prisma.kycSession.findUnique({ where: { id: sid } });
    return s && s.status === 'started' ? s : null;
  }

  @Get(':sid')
  async form(@Param('sid') sid: string, @Res() res: Response) {
    const s = await this.session(sid);
    if (!s) return res.status(400).send(page('<h1>Session expired</h1>'));
    res.send(page(`
      <span class="tag">DEMO — simulated provider, no real ID is checked</span>
      <h1>Identity verification</h1>
      <p>In production this screen is replaced by an authorised verification provider's hosted flow.
         Only a pass/fail result and age eligibility are sent back — never your ID number.</p>
      <form method="post" action="/mock-kyc/${s.id}/submit">
        <label>Date of birth (as on your ID)</label>
        <input type="date" name="dob" required max="${new Date().toISOString().slice(0, 10)}">
        <div class="row"><input type="checkbox" name="holder" required id="h">
          <label for="h" style="margin:0;font-weight:400">I confirm I am the holder of this identity and consent to verification.</label></div>
        <button class="ok" name="action" value="verify">Verify</button>
        <button class="no" name="action" value="fail" formnovalidate>Simulate failure</button>
      </form>`));
  }

  @Post(':sid/submit')
  async submit(@Param('sid') sid: string, @Body() rawBody: Record<string, string> | undefined, @Res() res: Response) {
    const body = rawBody ?? {};
    const s = await this.session(sid);
    if (!s) return res.status(400).send(page('<h1>Session expired</h1>'));
    let result: 'verified' | 'failed' = 'failed', ageEligible = false, birthYear: number | null = null;
    if (body.action === 'verify' && body.dob && body.holder) {
      const dob = new Date(body.dob + 'T00:00:00Z');
      if (!isNaN(dob.getTime())) {
        const t = new Date();
        let age = t.getUTCFullYear() - dob.getUTCFullYear();
        const m = t.getUTCMonth() - dob.getUTCMonth();
        if (m < 0 || (m === 0 && t.getUTCDate() < dob.getUTCDate())) age--;
        result = 'verified'; ageEligible = age >= config.MIN_AGE; birthYear = dob.getUTCFullYear(); // exact DOB -> exact check
      }
    }
    const payload = Buffer.from(JSON.stringify({
      sessionId: s.id, result, ageEligible, birthYear, providerRef: 'MOCK-' + uuid().slice(0, 8), ts: Date.now(),
    } satisfies ProviderResult)).toString('base64url');
    res.redirect(302, `/api/kyc/callback?payload=${payload}&sig=${hmac(payload, config.KYC_PROVIDER_SECRET)}`);
  }
}
