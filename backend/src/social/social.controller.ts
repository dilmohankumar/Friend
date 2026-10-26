import {
  BadRequestException, Body, Controller, Delete, ForbiddenException, Get, HttpCode, HttpException, NotFoundException, Param, Post, Query, UseGuards,
} from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { RateLimiter } from '../redis/redis.module';
import { ActiveGuard, AuthGuard, CurrentUser } from '../common/auth.guard';
import { ProfilePresenter } from '../users/profile.presenter';
import { RealtimeBus } from '../realtime/realtime.bus';
import { NotificationsService } from '../notifications/notifications.service';
import { DemoService } from '../demo/demo.service';
import { orderedPair, serializeMessage, SocialService } from './social.service';
import type { User } from '../generated/prisma/client';

const UUID_RE = /^[0-9a-f-]{36}$/i;
const REPORT_REASONS = ['fake_profile', 'harassment', 'inappropriate_content', 'scam', 'underage', 'other'];

@Controller('api')
@UseGuards(AuthGuard, ActiveGuard)
export class SocialController {
  constructor(
    private readonly prisma: PrismaService,
    private readonly social: SocialService,
    private readonly presenter: ProfilePresenter,
    private readonly bus: RealtimeBus,
    private readonly limiter: RateLimiter,
    private readonly push: NotificationsService,
    private readonly demo: DemoService,
  ) {}

  /** Discovery: eligibility + mutual gender/age preferences + blocks, enforced on the server. */
  @Get('discover')
  async discover(@CurrentUser() me: User, @Query('limit') limitQ?: string) {
    const year = new Date().getFullYear();
    const myAge = year - (me.birthYear ?? year);
    const limit = Math.min(20, Math.max(1, Math.trunc(Number(limitQ)) || 10));
    const rows = await this.prisma.user.findMany({
      where: {
        id: { not: me.id },
        status: 'ACTIVE', kycStatus: 'verified', ageEligible: true,
        gender: me.interestedIn!, interestedIn: me.gender!,
        birthYear: { gte: year - me.maxAge, lte: year - me.minAge },
        minAge: { lte: myAge }, maxAge: { gte: myAge },
        swipesGot: { none: { swiperId: me.id } },
        blocksMade: { none: { blockedId: me.id } },
        blocksGot: { none: { blockerId: me.id } },
      },
      orderBy: [{ lastSeen: { sort: 'desc', nulls: 'last' } }, { createdAt: 'desc' }],
      take: limit,
    });
    return { profiles: await Promise.all(rows.map((u) => this.presenter.public(u))) };
  }

  /** Swipe: idempotent. A per-pair advisory lock serialises concurrent mutual likes so exactly one match is created. */
  @Post('swipes')
  @HttpCode(200)
  async swipe(@CurrentUser() me: User, @Body() body: { targetId?: string; action?: string }) {
    const action = body?.action;
    if (action !== 'like' && action !== 'pass') throw new BadRequestException({ error: 'invalid_action' });
    if (!(await this.limiter.allow(`swipe:${me.id}`, 300, 60 * 60_000))) throw new HttpException({ error: 'too_many_requests' }, 429);
    const targetId = String(body.targetId ?? '');
    const target = /^[0-9a-f-]{36}$/i.test(targetId)
      ? await this.prisma.user.findFirst({ where: { id: targetId, status: 'ACTIVE' } }) : null;
    if (!target || target.id === me.id) throw new NotFoundException({ error: 'profile_unavailable' });
    if (target.gender !== me.interestedIn || target.interestedIn !== me.gender) throw new ForbiddenException({ error: 'not_eligible' });
    if (await this.social.isBlockedEitherWay(me.id, target.id)) throw new NotFoundException({ error: 'profile_unavailable' });

    const [a, b] = orderedPair(me.id, target.id);
    const result = await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(${a + ':' + b}, 0))) l`;
      const existing = await tx.swipe.findUnique({ where: { swiperId_targetId: { swiperId: me.id, targetId: target.id } } });
      if (!existing) await tx.swipe.create({ data: { swiperId: me.id, targetId: target.id, action } });
      const finalAction = existing?.action ?? action;
      if (finalAction !== 'like') return { matched: false as const };
      const reverse = await tx.swipe.findFirst({ where: { swiperId: target.id, targetId: me.id, action: 'like' } });
      if (!reverse) return { matched: false as const };
      const created = await tx.match.createMany({ data: [{ userAId: a, userBId: b }], skipDuplicates: true });
      const match = await tx.match.findUniqueOrThrow({ where: { userAId_userBId: { userAId: a, userBId: b } } });
      if (!match.active) return { matched: false as const }; // previously unmatched: don't resurrect
      return { matched: true as const, matchId: match.id, isNew: created.count === 1 };
    });

    if (result.matched && result.isNew) {
      const meProfile = await this.presenter.public(me);
      this.bus.emitToUser(target.id, 'match:new', { matchId: result.matchId, user: meProfile });
      await this.push.push({ userId: target.id, data: { type: 'match', matchId: result.matchId, title: 'New match! 💘', body: `You and ${me.name} liked each other` } });
    }
    if (action === 'like') this.demo.onLike(me, target);
    return {
      matched: result.matched,
      matchId: result.matched ? result.matchId : null,
      ...(result.matched ? { user: await this.presenter.public(target) } : {}),
    };
  }

  /** People who liked me and whom I haven't swiped yet. */
  @Get('likes')
  async likes(@CurrentUser() me: User) {
    const rows = await this.prisma.swipe.findMany({
      where: {
        targetId: me.id, action: 'like',
        swiper: {
          status: 'ACTIVE',
          swipesGot: { none: { swiperId: me.id } },
          blocksMade: { none: { blockedId: me.id } },
          blocksGot: { none: { blockerId: me.id } },
        },
      },
      include: { swiper: true },
      orderBy: { createdAt: 'desc' },
      take: 100,
    });
    return { profiles: await Promise.all(rows.map((r) => this.presenter.public(r.swiper))) };
  }

  @Get('matches')
  async matches(@CurrentUser() me: User) {
    const rows = await this.prisma.$queryRaw<Array<{
      id: string; created_at: Date; other_id: string; last_text: string | null; last_sender: string | null; last_at: Date | null; unread: number;
    }>>`
      SELECT m.id, m.created_at,
             CASE WHEN m.user_a_id = ${me.id}::uuid THEN m.user_b_id ELSE m.user_a_id END AS other_id,
             lm.text AS last_text, lm.sender_id AS last_sender, lm.created_at AS last_at,
             (SELECT COUNT(*)::int FROM messages x WHERE x.match_id = m.id AND x.sender_id <> ${me.id}::uuid AND x.read_at IS NULL) AS unread
      FROM matches m
      LEFT JOIN LATERAL (SELECT text, sender_id, created_at FROM messages WHERE match_id = m.id ORDER BY created_at DESC LIMIT 1) lm ON true
      WHERE (m.user_a_id = ${me.id}::uuid OR m.user_b_id = ${me.id}::uuid) AND m.active
      ORDER BY COALESCE(lm.created_at, m.created_at) DESC`;
    const others = await this.prisma.user.findMany({
      where: {
        id: { in: rows.map((r) => r.other_id) }, status: 'ACTIVE',
        blocksMade: { none: { blockedId: me.id } }, blocksGot: { none: { blockerId: me.id } },
      },
    });
    const byId = new Map(others.map((u) => [u.id, u]));
    const out = [];
    for (const r of rows) {
      const u = byId.get(r.other_id);
      if (!u) continue;
      out.push({
        matchId: r.id,
        matchedAt: r.created_at.getTime(),
        user: await this.presenter.public(u),
        lastMessage: r.last_text !== null ? { text: r.last_text, fromMe: r.last_sender === me.id, at: r.last_at!.getTime() } : null,
        unread: r.unread,
      });
    }
    return { matches: out };
  }

  @Get('matches/:id/messages')
  async messages(@CurrentUser() me: User, @Param('id') id: string, @Query('before') before?: string) {
    const ctx = await this.social.activeMatchFor(id, me.id);
    if (!ctx) throw new NotFoundException({ error: 'match_unavailable' });
    const rows = await this.prisma.message.findMany({
      where: { matchId: ctx.match.id, ...(Number.isFinite(Number(before)) && before ? { createdAt: { lt: new Date(Number(before)) } } : {}) },
      orderBy: { createdAt: 'desc' },
      take: 50,
    });
    return { user: await this.presenter.public(ctx.other), messages: rows.reverse().map(serializeMessage) };
  }

  @Post('matches/:id/unmatch')
  @HttpCode(200)
  async unmatch(@CurrentUser() me: User, @Param('id') id: string) {
    const ctx = await this.social.activeMatchFor(id, me.id);
    if (!ctx) throw new NotFoundException({ error: 'match_unavailable' });
    await this.prisma.match.update({ where: { id: ctx.match.id }, data: { active: false } });
    this.bus.emitToUser(ctx.otherId, 'match:removed', { matchId: ctx.match.id });
    return { ok: true };
  }

  @Post('blocks')
  @HttpCode(200)
  async block(@CurrentUser() me: User, @Body() body: { userId?: string }) {
    const targetId = String(body?.userId ?? '');
    if (!/^[0-9a-f-]{36}$/i.test(targetId) || targetId === me.id) throw new BadRequestException({ error: 'invalid_user' });
    if (!(await this.prisma.user.findUnique({ where: { id: targetId } }))) throw new BadRequestException({ error: 'invalid_user' });
    await this.prisma.block.createMany({ data: [{ blockerId: me.id, blockedId: targetId }], skipDuplicates: true });
    const [a, b] = orderedPair(me.id, targetId);
    const m = await this.prisma.match.findUnique({ where: { userAId_userBId: { userAId: a, userBId: b } } });
    if (m?.active) {
      await this.prisma.match.update({ where: { id: m.id }, data: { active: false } });
      this.bus.emitToUser(targetId, 'match:removed', { matchId: m.id });
    }
    return { ok: true };
  }

  @Delete('blocks/:userId')
  async unblock(@CurrentUser() me: User, @Param('userId') userId: string) {
    if (!UUID_RE.test(userId)) throw new BadRequestException({ error: 'invalid_user' });
    await this.prisma.block.deleteMany({ where: { blockerId: me.id, blockedId: userId } });
    return { ok: true };
  }

  @Post('reports')
  @HttpCode(200)
  async report(@CurrentUser() me: User, @Body() body: { userId?: string; reason?: string; details?: string }) {
    if (!body?.userId || !/^[0-9a-f-]{36}$/i.test(body.userId) || !REPORT_REASONS.includes(String(body.reason))) {
      throw new BadRequestException({ error: 'invalid_report' });
    }
    if (!(await this.limiter.allow(`report:${me.id}`, 20, 60 * 60_000))) throw new HttpException({ error: 'too_many_requests' }, 429);
    await this.prisma.report.create({
      data: { reporterId: me.id, reportedId: body.userId, reason: String(body.reason), details: String(body.details ?? '').slice(0, 1000) },
    });
    return { ok: true };
  }
}
