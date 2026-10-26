/**
 * DigiLocker (NeGD, Government of India) "Meri Pehchaan" requester integration — OAuth 2.0 authorization-code
 * flow with PKCE, per "Requester - Meri Pehchaan API Specification" v2.3.
 *
 *   start()  -> builds the DigiLocker authorize URL (state + PKCE verifier kept server-side in Redis, 15 min, single use)
 *   GET /api/kyc/digilocker/callback?code&state  -> exchange the code at /oauth2/1/token (client secret stays on the
 *             server), read name / dob (DDMMYYYY) / gender / eaadhaar / digilockerid, revoke the token, and hand the
 *             verified facts to the shared KYC completion in KycController.
 *
 * Privacy: only the birth YEAR, the age-eligible flag, a one-way hash of the DigiLocker id (one person = one
 * account) and a short reference are stored. The name, full date of birth and the access token are never persisted
 * or logged. No Aadhaar number is ever received.
 *
 * Onboarding (done by you, not code): register as a requester on API Setu / DigiLocker partner portal, get
 * DIGILOCKER_CLIENT_ID + DIGILOCKER_CLIENT_SECRET, and register the redirect URI printed at startup.
 */
import { Inject, Injectable, Logger } from '@nestjs/common';
import * as crypto from 'crypto';
import Redis from 'ioredis';
import { config } from '../config';
import { newOpaqueToken, sha256 } from '../common/security';
import { REDIS } from '../redis/redis.module';
import type { User } from '../generated/prisma/client';
import type { KycProvider } from './kyc-provider';

const STATE_TTL_SEC = 15 * 60;
const HTTP_TIMEOUT_MS = 10_000;

export interface DigiLockerIdentity {
  digilockerId: string;
  birthYear: number;
  ageEligible: boolean;
  aadhaarBacked: boolean;
  reference: string;
}

const env = (k: string) => String(process.env[k] ?? '').trim();

@Injectable()
export class DigiLockerProvider implements KycProvider {
  readonly name = 'digilocker';
  private readonly log = new Logger('DigiLocker');

  constructor(@Inject(REDIS) private readonly redis: Redis) {
    if (this.configured) this.log.log(`DigiLocker enabled. Register this redirect URI in the partner portal: ${this.redirectUri}`);
  }

  get configured() { return !!(env('DIGILOCKER_CLIENT_ID') && env('DIGILOCKER_CLIENT_SECRET')); }
  get redirectUri() { return `${config.PUBLIC_BASE_URL}/api/kyc/digilocker/callback`; }
  private get base() { return (env('DIGILOCKER_BASE_URL') || 'https://digilocker.meripehchaan.gov.in/public/oauth2').replace(/\/$/, ''); }
  /** DigiLocker's DOB comes from the user's own account; only trust it when the account is Aadhaar-backed (eaadhaar=Y). */
  private get requireAadhaar() { return env('DIGILOCKER_REQUIRE_EAADHAAR') !== 'false'; }

  async start(_user: User, sessionId: string): Promise<{ redirectUrl: string }> {
    const state = newOpaqueToken();
    const verifier = crypto.randomBytes(48).toString('base64url'); // 64 chars, within the spec's 43-128
    const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
    await this.redis.set(`dl:state:${state}`, JSON.stringify({ sessionId, verifier }), 'EX', STATE_TTL_SEC);
    const q = new URLSearchParams({
      response_type: 'code', client_id: env('DIGILOCKER_CLIENT_ID'), redirect_uri: this.redirectUri, state,
      code_challenge: challenge, code_challenge_method: 'S256',
    });
    const scope = process.env.DIGILOCKER_SCOPE === undefined ? 'openid' : env('DIGILOCKER_SCOPE'); // spec: always "openid" when sent
    if (scope) q.set('scope', scope);
    return { redirectUrl: `${this.base}/1/authorize?${q}` };
  }

  /** Single-use: returns the pending request for this state exactly once, else null. */
  async consume(state: string): Promise<{ sessionId: string; verifier: string } | null> {
    if (!/^[A-Za-z0-9_-]{20,128}$/.test(state)) return null;
    const raw = await this.redis.getdel(`dl:state:${state}`);
    if (!raw) return null;
    try { return JSON.parse(raw); } catch { return null; }
  }

  /** Exchanges the authorization code and returns only the verified facts we need. Throws on any provider problem. */
  async identityFromCode(code: string, verifier: string): Promise<DigiLockerIdentity> {
    const res = await fetch(`${this.base}/1/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
      body: new URLSearchParams({
        grant_type: 'authorization_code', code, client_id: env('DIGILOCKER_CLIENT_ID'), client_secret: env('DIGILOCKER_CLIENT_SECRET'),
        redirect_uri: this.redirectUri, code_verifier: verifier,
      }),
      signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
    });
    if (!res.ok) throw new Error(`token endpoint answered ${res.status}`);
    const t = (await res.json()) as Record<string, unknown>;
    void this.revoke(String(t.access_token ?? '')); // best effort: we need nothing else from DigiLocker
    return this.parse(t);
  }

  parse(t: Record<string, unknown>): DigiLockerIdentity {
    const id = String(t.digilockerid ?? '');
    const m = /^(\d{2})(\d{2})(\d{4})$/.exec(String(t.dob ?? ''));
    if (!id || !m) throw new Error('token response is missing digilockerid / dob');
    const [d, mo, y] = [Number(m[1]), Number(m[2]), Number(m[3])];
    const dob = new Date(Date.UTC(y, mo - 1, d));
    if (dob.getUTCFullYear() !== y || dob.getUTCMonth() !== mo - 1 || dob.getUTCDate() !== d) throw new Error('invalid dob');
    const now = new Date();
    let age = now.getUTCFullYear() - y;
    if (now.getUTCMonth() < mo - 1 || (now.getUTCMonth() === mo - 1 && now.getUTCDate() < d)) age--;
    const aadhaarBacked = t.eaadhaar === 'Y';
    return {
      digilockerId: id,
      birthYear: y,
      ageEligible: age >= config.MIN_AGE && (aadhaarBacked || !this.requireAadhaar),
      aadhaarBacked,
      reference: 'DL-' + String(t.reference_key ?? '').slice(0, 16),
    };
  }

  /** One-way fingerprint of the DigiLocker id (a random UUID) so the same person can't open a second account. */
  static fingerprint(digilockerId: string) { return sha256('digilocker:' + digilockerId); }

  private async revoke(token: string) {
    if (!token) return;
    try {
      const basic = Buffer.from(`${env('DIGILOCKER_CLIENT_ID')}:${env('DIGILOCKER_CLIENT_SECRET')}`).toString('base64');
      await fetch(`${this.base}/1/revoke`, {
        method: 'POST', headers: { authorization: `Basic ${basic}`, 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ token, token_type_hint: 'access_token' }), signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
      });
    } catch { /* not critical */ }
  }
}
