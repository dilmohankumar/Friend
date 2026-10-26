/**
 * Moderation API for the operations team (reports queue, suspend / unsuspend, resolve).
 * Protected by a static key in ADMIN_API_KEY (>= 32 chars, send as `x-admin-key`). With no key configured the whole
 * API answers 404, so it cannot be reached by accident. Moderators see phone numbers: keep the key out of the app
 * and out of logs, and rotate it when a moderator leaves.
 */
import {
  BadRequestException, Body, CanActivate, Controller, ExecutionContext, Get, HttpCode, HttpException, Injectable, Logger,
  NotFoundException, Param, Post, Query, UseGuards,
} from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { RateLimiter } from '../redis/redis.module';
import { safeEqual } from '../common/security';
import { ProfilePresenter } from '../users/profile.presenter';
import { RealtimeBus } from '../realtime/realtime.bus';
import { CallService } from '../realtime/call.service';

const UUID_RE = /^[0-9a-f-]{36}$/i;
const adminKey = () => String(process.env.ADMIN_API_KEY ?? '');

@Injectable()
export class AdminGuard implements CanActivate {
  constructor(private readonly limiter: RateLimiter) {}

  async canActivate(ctx: ExecutionContext): Promise<boolean> {
    const key = adminKey();
    if (key.length < 32) throw new NotFoundException(); // disabled until a strong key is configured
    const req = ctx.switchToHttp().getRequest();
    if (!(await this.limiter.allow(`admin:${req.ip}`, 120, 60_000))) throw new HttpException({ error: 'too_many_requests' }, 429);
    if (!safeEqual(String(req.headers['x-admin-key'] ?? ''), key)) throw new HttpException({ error: 'unauthorized' }, 401);
    return true;
  }
}

@Controller('api/admin')
@UseGuards(AdminGuard)
export class AdminController {
  private readonly log = new Logger('Admin');
  constructor(
    private readonly prisma: PrismaService,
    private readonly presenter: ProfilePresenter,
    private readonly bus: RealtimeBus,
    private readonly calls: CallService,
  ) {}

  private uuid(id: string) {
    if (!UUID_RE.test(id)) throw new BadRequestException({ error: 'invalid_id' });
    return id;
  }

  /** Reports queue, oldest first, with who reported whom and how many distinct people reported the same user. */
  @Get('reports')
  async reports(@Query('status') status = 'open', @Query('limit') limitQ?: string) {
    if (!['open', 'dismissed', 'actioned'].includes(status)) throw new BadRequestException({ error: 'invalid_status' });
    const limit = Math.min(100, Math.max(1, Math.trunc(Number(limitQ)) || 50));
    const rows = await this.prisma.report.findMany({ where: { status }, orderBy: { createdAt: 'asc' }, take: limit });
    const ids = [...new Set(rows.flatMap((r) => [r.reporterId, r.reportedId]))];
    const [users, counts] = await Promise.all([
      this.prisma.user.findMany({ where: { id: { in: ids } }, select: { id: true, name: true, phone: true, status: true } }),
      this.prisma.report.groupBy({ by: ['reportedId'], where: { reportedId: { in: ids }, status: 'open' }, _count: { _all: true } }),
    ]);
    const byId = new Map(users.map((u) => [u.id, u]));
    const open = new Map(counts.map((c) => [c.reportedId, c._count._all]));
    return {
      reports: rows.map((r) => ({
        id: r.id, reason: r.reason, details: r.details, status: r.status, createdAt: r.createdAt.getTime(),
        reporter: byId.get(r.reporterId) ?? { id: r.reporterId, deleted: true },
        reported: byId.get(r.reportedId) ?? { id: r.reportedId, deleted: true },
        openReportsAgainstReported: open.get(r.reportedId) ?? 0,
      })),
    };
  }

  /** Full view of one account for a decision: status, verification, bio and (signed) photo URLs. */
  @Get('users/:id')
  async user(@Param('id') id: string) {
    const u = await this.prisma.user.findUnique({ where: { id: this.uuid(id) } });
    if (!u) throw new NotFoundException({ error: 'user_not_found' });
    const [against, made] = await Promise.all([
      this.prisma.report.count({ where: { reportedId: u.id } }),
      this.prisma.report.count({ where: { reporterId: u.id } }),
    ]);
    return {
      profile: await this.presenter.public(u),
      account: {
        phone: u.phone, status: u.status, kycStatus: u.kycStatus, ageEligible: u.ageEligible, createdAt: u.createdAt.getTime(),
        suspendedAt: u.suspendedAt?.getTime() ?? null, suspendReason: u.suspendReason,
      },
      reportsAgainst: against, reportsMade: made,
    };
  }

  /** Suspend: blocks login, drops live sockets and calls, hides the profile from everyone, ends all sessions. */
  @Post('users/:id/suspend')
  @HttpCode(200)
  async suspend(@Param('id') id: string, @Body() body: { reason?: string }) {
    const reason = typeof body?.reason === 'string' ? body.reason.trim().slice(0, 500) : '';
    if (reason.length < 3) throw new BadRequestException({ error: 'reason_required' });
    const u = await this.prisma.user.findUnique({ where: { id: this.uuid(id) } });
    if (!u) throw new NotFoundException({ error: 'user_not_found' });
    if (u.status !== 'SUSPENDED') {
      await this.prisma.$transaction([
        this.prisma.user.update({ where: { id: u.id }, data: { status: 'SUSPENDED', suspendedAt: new Date(), suspendReason: reason } }),
        this.prisma.refreshToken.deleteMany({ where: { userId: u.id } }),
        this.prisma.report.updateMany({ where: { reportedId: u.id, status: 'open' }, data: { status: 'actioned', resolvedAt: new Date() } }),
      ]);
      this.bus.disconnectUser(u.id);
      await this.calls.endAllFor(u.id, 'suspended').catch(() => undefined);
      this.log.warn(`user ${u.id} suspended: ${reason}`);
    }
    return { ok: true, status: 'SUSPENDED' };
  }

  /** Restores the account to whatever state its profile/verification supports (never straight to ACTIVE if incomplete). */
  @Post('users/:id/unsuspend')
  @HttpCode(200)
  async unsuspend(@Param('id') id: string) {
    const u = await this.prisma.user.findUnique({ where: { id: this.uuid(id) } });
    if (!u) throw new NotFoundException({ error: 'user_not_found' });
    if (u.status !== 'SUSPENDED') return { ok: true, status: u.status };
    const verified = u.kycStatus === 'verified' && u.ageEligible;
    const complete = !!(u.name && u.gender && u.interestedIn && u.photos.length >= 1);
    const status = !verified ? 'IDENTITY_PENDING' : complete ? 'ACTIVE' : 'PROFILE_INCOMPLETE';
    await this.prisma.user.update({ where: { id: u.id }, data: { status, suspendedAt: null, suspendReason: null } });
    this.log.warn(`user ${u.id} unsuspended -> ${status}`);
    return { ok: true, status };
  }

  @Post('reports/:id/resolve')
  @HttpCode(200)
  async resolve(@Param('id') id: string, @Body() body: { action?: string }) {
    if (body?.action !== 'dismissed' && body?.action !== 'actioned') throw new BadRequestException({ error: 'invalid_action' });
    const { count } = await this.prisma.report.updateMany({ where: { id: this.uuid(id), status: 'open' }, data: { status: body.action, resolvedAt: new Date() } });
    if (!count) throw new NotFoundException({ error: 'report_not_open' });
    return { ok: true };
  }
}
