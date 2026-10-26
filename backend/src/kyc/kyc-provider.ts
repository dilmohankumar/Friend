import { Injectable } from '@nestjs/common';
import { config } from '../config';
import { DigiLockerProvider } from './digilocker.provider';
import type { User } from '../generated/prisma/client';

/**
 * Identity-verification provider seam. The rest of the app only ever does two things with a provider:
 *   1. start(): open a verification session and get the URL of the provider's hosted flow, and
 *   2. receive its result at GET /api/kyc/callback as an HMAC-signed payload (KYC_PROVIDER_SECRET).
 * A user is only ever marked verified by that signed callback — never by the app or by the user's own input.
 *
 * Real provider: DigiLocker (select with KYC_PROVIDER=digilocker + its client id/secret, see digilocker.provider.ts).
 * Any other provider: implement KycProvider, return it from KycProviders.active(), and complete verification through
 * KycController.applyResult() — `ageEligible` and `birthYear` must come from the provider's verified data and
 * `identityHash` must be a stable one-way hash of the verified identity (one person = one account).
 */
export interface KycProvider {
  readonly name: string;
  start(user: User, sessionId: string): Promise<{ redirectUrl: string }>;
}

/** Development only: simulated provider at /mock-kyc. Enabled only in DEMO mode. */
const mock: KycProvider = {
  name: 'mock',
  async start(_user, sessionId) { return { redirectUrl: `${config.PUBLIC_BASE_URL}/mock-kyc/${sessionId}` }; },
};

/** Picks the provider for this deployment: the mock in DEMO mode, otherwise the one named by KYC_PROVIDER. */
@Injectable()
export class KycProviders {
  constructor(private readonly digilocker: DigiLockerProvider) {}

  active(): KycProvider | null {
    if (config.MOCK_KYC) return mock;
    if (String(process.env.KYC_PROVIDER ?? '') === 'digilocker' && this.digilocker.configured) return this.digilocker;
    return null; // LIVE mode with no provider configured: nobody can be verified
  }
}
