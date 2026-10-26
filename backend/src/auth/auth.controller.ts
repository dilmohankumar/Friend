import { BadRequestException, Body, Controller, ForbiddenException, HttpCode, HttpException, Logger, Post, Req, UnauthorizedException } from '@nestjs/common';
import * as crypto from 'crypto';
import type { Request } from 'express';
import { PrismaService } from '../prisma/prisma.service';
import { RateLimiter } from '../redis/redis.module';
import { config } from '../config';
import { hmac, newOpaqueToken, safeEqual, sha256, signAccess } from '../common/security';
import { ProfilePresenter } from '../users/profile.presenter';
import { SmsService } from './sms.service';

const PHONE_RE = /^\+91[6-9]\d{9}$/; // Indian mobile numbers

function normalizePhone(p: unknown): string {
  let s = String(p ?? '').replace(/[\s-]/g, '');
  if (/^[6-9]\d{9}$/.test(s)) s = '+91' + s;
  return s;
}

const REFRESH_REUSE_GRACE_MS = 10_000;
const tooMany = (error = 'too_many_requests') => new HttpException({ error }, 429);

@Controller('api/auth')
export class AuthController {
  private readonly log = new Logger('Auth');
  constructor(
    private readonly prisma: PrismaService,
    private readonly limiter: RateLimiter,
    private readonly presenter: ProfilePresenter,
    private readonly sms: SmsService,
  ) {}

  private async issueSession(userId: string, familyId?: string) {
    const refresh = newOpaqueToken();
    await this.prisma.refreshToken.create({
      data: { tokenHash: sha256(refresh), userId, ...(familyId ? { familyId } : {}), expiresAt: new Date(Date.now() + config.REFRESH_TTL_MS) },
    });
    return { accessToken: signAccess(userId), refreshToken: refresh, expiresIn: config.ACCESS_TTL_SEC };
  }

  @Post('otp/request')
  @HttpCode(200)
  async requestOtp(@Body() body: { phone?: string }, @Req() req: Request) {
    const phone = normalizePhone(body?.phone);
    if (!PHONE_RE.test(phone)) throw new BadRequestException({ error: 'invalid_phone' });
    // Per-IP limit is generous on purpose: Indian mobile carriers put thousands of users behind one NAT address.
    // The per-phone limit is what stops SMS bombing of a single victim.
    if (!(await this.limiter.allow(`otp:${phone}`, 5, 15 * 60_000)) || !(await this.limiter.allow(`otpip:${req.ip}`, 300, 15 * 60_000))) {
      throw tooMany();
    }
    const code = String(crypto.randomInt(100000, 1000000));
    const data = { codeHash: hmac(`${phone}:${code}`), expiresAt: new Date(Date.now() + config.OTP_TTL_MS), attempts: 0 };
    await this.prisma.otp.upsert({ where: { phone }, create: { phone, ...data }, update: data });
    await this.sms.sendOtp(phone, code);
    return { ok: true, phone, expiresInSec: config.OTP_TTL_MS / 1000, ...(config.DEV_SHOW_OTP ? { devOtp: code } : {}) };
  }

  @Post('otp/verify')
  @HttpCode(200)
  async verifyOtp(@Body() body: { phone?: string; code?: string }, @Req() req: Request) {
    const phone = normalizePhone(body?.phone);
    if (!PHONE_RE.test(phone)) throw new BadRequestException({ error: 'invalid_phone' });
    const code = String(body?.code ?? '');
    // Guess budget per phone per window, independent of how many OTPs were requested (requesting a new OTP must not reset it)
    if (!(await this.limiter.allow(`otpv:${phone}`, 5, 15 * 60_000)) || !(await this.limiter.allow(`otpvip:${req.ip}`, 600, 15 * 60_000))) {
      throw tooMany('too_many_attempts');
    }
    const row = await this.prisma.otp.findUnique({ where: { phone } });
    if (!row || row.expiresAt.getTime() < Date.now()) throw new BadRequestException({ error: 'otp_expired' });
    if (row.attempts >= 5) throw tooMany('too_many_attempts');
    if (!safeEqual(row.codeHash, hmac(`${phone}:${code}`))) {
      await this.prisma.otp.update({ where: { phone }, data: { attempts: { increment: 1 } } });
      throw new BadRequestException({ error: 'otp_invalid' });
    }
    await this.prisma.otp.delete({ where: { phone } });
    const user = await this.prisma.user.upsert({ where: { phone }, create: { phone }, update: {} });
    if (user.status === 'SUSPENDED') throw new ForbiddenException({ error: 'account_suspended' });
    return { ...(await this.issueSession(user.id)), user: await this.presenter.self(user) };
  }

  /**
   * Rotating refresh with reuse detection: the presented token is marked used and a new one issued in the same
   * family. Presenting an already-used token (outside a short grace window for client retries) means it leaked, so
   * every token of that login is revoked.
   */
  @Post('refresh')
  @HttpCode(200)
  async refresh(@Body() body: { refreshToken?: string }) {
    const hash = sha256(String(body?.refreshToken ?? ''));
    const row = await this.prisma.refreshToken.findUnique({ where: { tokenHash: hash } });
    if (!row || row.expiresAt.getTime() < Date.now()) throw new UnauthorizedException({ error: 'refresh_invalid' });
    if (row.usedAt) {
      if (Date.now() - row.usedAt.getTime() > REFRESH_REUSE_GRACE_MS) {
        await this.prisma.refreshToken.deleteMany({ where: { familyId: row.familyId } });
        this.log.warn(`refresh token reuse detected for user ${row.userId}; family revoked`);
      }
      throw new UnauthorizedException({ error: 'refresh_invalid' });
    }
    // updateMany makes consumption atomic: two concurrent refreshes can't both succeed
    const { count } = await this.prisma.refreshToken.updateMany({ where: { tokenHash: hash, usedAt: null }, data: { usedAt: new Date() } });
    if (count !== 1) throw new UnauthorizedException({ error: 'refresh_invalid' });
    const user = await this.prisma.user.findUnique({ where: { id: row.userId } });
    if (!user || user.status === 'SUSPENDED') throw new UnauthorizedException({ error: 'refresh_invalid' });
    return this.issueSession(user.id, row.familyId);
  }

  @Post('logout')
  @HttpCode(200)
  async logout(@Body() body: { refreshToken?: string; deviceToken?: string }) {
    const row = await this.prisma.refreshToken.findUnique({ where: { tokenHash: sha256(String(body?.refreshToken ?? '')) } });
    if (row) await this.prisma.refreshToken.deleteMany({ where: { familyId: row.familyId } }); // sign out this login everywhere
    if (body?.deviceToken) await this.prisma.deviceToken.deleteMany({ where: { token: String(body.deviceToken) } });
    return { ok: true };
  }
}
