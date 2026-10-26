import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';

/** Keeps short-lived auth tables from growing forever. Idempotent, so it is safe to run on every API instance. */
const EVERY_MS = 30 * 60_000;
/** An account that never completed identity verification does not stay on the server. */
const UNVERIFIED_ACCOUNT_TTL_MS = 7 * 24 * 60 * 60_000;
/** Under-18 results are deleted after a day, long enough for the app to show the reason. */
const UNDERAGE_ACCOUNT_TTL_MS = 24 * 60 * 60_000;
/** Consumed refresh tokens are kept this long so a stolen, already-rotated token is still recognised as reuse. */
const USED_TOKEN_RETENTION_MS = 2 * 60 * 60_000;

@Injectable()
export class MaintenanceService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('Maintenance');
  private timer?: NodeJS.Timeout;

  constructor(private readonly prisma: PrismaService) {}

  onModuleInit() {
    setTimeout(() => void this.sweep(), 15_000).unref();
    this.timer = setInterval(() => void this.sweep(), EVERY_MS);
    this.timer.unref();
  }

  onModuleDestroy() { clearInterval(this.timer); }

  /** Never-verified and under-18 accounts are deleted (FK cascades). They own no photos: uploading needs verification. */
  async purgeUnverifiedAccounts(): Promise<number> {
    const [unverified, underage] = await Promise.all([
      this.prisma.user.deleteMany({
        where: {
          isDemo: false, kycStatus: { not: 'verified' }, status: { in: ['CONSENT_PENDING', 'IDENTITY_PENDING'] },
          createdAt: { lt: new Date(Date.now() - UNVERIFIED_ACCOUNT_TTL_MS) },
          kycSessions: { none: { createdAt: { gt: new Date(Date.now() - UNVERIFIED_ACCOUNT_TTL_MS) } } },
        },
      }),
      this.prisma.user.deleteMany({
        where: {
          isDemo: false, kycStatus: 'verified', ageEligible: false,
          kycSessions: { none: { createdAt: { gt: new Date(Date.now() - UNDERAGE_ACCOUNT_TTL_MS) } } },
        },
      }),
    ]);
    return unverified.count + underage.count;
  }

  async sweep() {
    try {
      const now = new Date();
      const [otps, tokens] = await Promise.all([
        this.prisma.otp.deleteMany({ where: { expiresAt: { lt: now } } }),
        this.prisma.refreshToken.deleteMany({
          where: { OR: [{ expiresAt: { lt: now } }, { usedAt: { lt: new Date(Date.now() - USED_TOKEN_RETENTION_MS) } }] },
        }),
      ]);
      if (otps.count || tokens.count) this.log.log(`purged ${otps.count} expired OTPs, ${tokens.count} stale refresh tokens`);
      const accounts = await this.purgeUnverifiedAccounts();
      if (accounts) this.log.log(`purged ${accounts} unverified/underage accounts`);
    } catch (e) { this.log.warn(`sweep failed: ${e}`); }
  }
}
