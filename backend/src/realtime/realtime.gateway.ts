/**
 * Socket.IO gateway: authenticated chat, delivery/read receipts, typing, presence and WebRTC call signaling.
 * Every event is re-authorised against PostgreSQL (active match, no block, both users ACTIVE).
 */
import { Logger } from '@nestjs/common';
import {
  ConnectedSocket, MessageBody, OnGatewayConnection, OnGatewayDisconnect, OnGatewayInit, SubscribeMessage, WebSocketGateway,
} from '@nestjs/websockets';
import type { Server, Socket } from 'socket.io';
import { PrismaService } from '../prisma/prisma.service';
import { RateLimiter } from '../redis/redis.module';
import { verifyAccess } from '../common/security';
import { config } from '../config';
import { RealtimeBus } from './realtime.bus';
import { CallService } from './call.service';
import { serializeMessage, SocialService } from '../social/social.service';
import { ProfilePresenter } from '../users/profile.presenter';
import { NotificationsService } from '../notifications/notifications.service';
import { DemoService } from '../demo/demo.service';

type Ack = { ok?: true; error?: string; [k: string]: unknown };
const err = (error: string): Ack => ({ error });

@WebSocketGateway({ cors: { origin: config.isProd ? false : '*' } })
export class RealtimeGateway implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect {
  private readonly log = new Logger('Realtime');
  private server: Server;
  private beats = new Map<string, NodeJS.Timeout>();

  constructor(
    private readonly prisma: PrismaService,
    private readonly limiter: RateLimiter,
    private readonly bus: RealtimeBus,
    private readonly calls: CallService,
    private readonly social: SocialService,
    private readonly presenter: ProfilePresenter,
    private readonly push: NotificationsService,
    private readonly demo: DemoService,
  ) {}

  afterInit(server: Server) {
    this.server = server;
    this.bus.attach(server);
    server.use(async (socket, next) => {
      try {
        const uid = verifyAccess(String(socket.handshake.auth?.token ?? ''));
        const u = await this.prisma.user.findUnique({ where: { id: uid } });
        if (!u || u.status !== 'ACTIVE') return next(new Error('not_eligible'));
        socket.data.userId = uid;
        next();
      } catch {
        next(new Error('auth_failed'));
      }
    });
  }

  private touch(uid: string) {
    return this.prisma.user.update({ where: { id: uid }, data: { lastSeen: new Date() } }).catch(() => undefined);
  }

  async handleConnection(socket: Socket) {
    const me: string = socket.data.userId;
    if (!me) return socket.disconnect(true);
    await socket.join(RealtimeBus.room(me));
    await this.touch(me);
    this.beats.set(socket.id, setInterval(() => this.touch(me), 30_000));

    // Deliver messages that arrived while offline -> mark delivered, notify senders
    const pending = await this.prisma.message.findMany({
      where: { deliveredAt: null, senderId: { not: me }, match: { OR: [{ userAId: me }, { userBId: me }] } },
      select: { id: true, matchId: true, senderId: true },
    });
    if (pending.length) {
      const t = new Date();
      await this.prisma.message.updateMany({ where: { id: { in: pending.map((p) => p.id) } }, data: { deliveredAt: t } });
      for (const m of pending) this.bus.emitToUser(m.senderId, 'message:status', { id: m.id, matchId: m.matchId, deliveredAt: t.getTime() });
    }

    // App opened from an incoming-call push: re-send the ringing call
    const ringing = await this.calls.ringingFor(me);
    if (ringing) {
      const caller = await this.prisma.user.findUnique({ where: { id: ringing.callerId } });
      if (caller) socket.emit('call:incoming', { callId: ringing.callId, matchId: ringing.matchId, kind: ringing.kind, from: await this.presenter.public(caller) });
    }
  }

  async handleDisconnect(socket: Socket) {
    const me: string | undefined = socket.data.userId;
    clearInterval(this.beats.get(socket.id));
    this.beats.delete(socket.id);
    if (!me) return;
    await this.touch(me);
    if (await this.bus.isOnline(me)) return; // still connected on another device
    // Give the app a moment to reconnect (network switch) before dropping an active call
    setTimeout(async () => {
      if (!(await this.bus.isOnline(me))) await this.calls.endAllFor(me, 'disconnected');
    }, 8000);
  }

  // ------------------------------- CHAT -------------------------------
  @SubscribeMessage('message:send')
  async onMessage(@ConnectedSocket() socket: Socket, @MessageBody() p: { matchId?: string; clientMessageId?: string; text?: string }): Promise<Ack> {
    const me: string = socket.data.userId;
    const text = String(p?.text ?? '').trim();
    const clientMessageId = String(p?.clientMessageId ?? '').slice(0, 64);
    if (!text || text.length > 2000 || !clientMessageId) return err('invalid_message');
    if (!(await this.limiter.allow(`msg:${me}`, 60, 60_000))) return err('rate_limited');
    const ctx = await this.social.activeMatchFor(String(p.matchId), me);
    if (!ctx) return err('match_unavailable');

    // Idempotency: a retry with the same clientMessageId returns the stored message
    const existing = await this.prisma.message.findUnique({ where: { senderId_clientMessageId: { senderId: me, clientMessageId } } });
    if (existing) return { ok: true, message: serializeMessage(existing) };

    const online = await this.bus.isOnline(ctx.otherId);
    let row;
    try {
      row = await this.prisma.message.create({
        data: { matchId: ctx.match.id, senderId: me, clientMessageId, text, deliveredAt: online ? new Date() : null },
      });
    } catch {
      // concurrent duplicate retry hit the unique constraint
      const dup = await this.prisma.message.findUnique({ where: { senderId_clientMessageId: { senderId: me, clientMessageId } } });
      return dup ? { ok: true, message: serializeMessage(dup) } : err('server_error');
    }
    const msg = serializeMessage(row);
    this.bus.emitToUser(ctx.otherId, 'message:new', msg);
    socket.to(RealtimeBus.room(me)).emit('message:new', msg); // my other devices
    if (!online) {
      const sender = await this.prisma.user.findUnique({ where: { id: me }, select: { name: true } });
      await this.push.push({
        userId: ctx.otherId,
        data: { type: 'message', matchId: ctx.match.id, title: sender?.name || 'New message', body: text.slice(0, 120) },
      });
    }
    this.demo.onMessage(me, ctx);
    return { ok: true, message: msg };
  }

  @SubscribeMessage('message:read')
  async onRead(@ConnectedSocket() socket: Socket, @MessageBody() p: { matchId?: string }) {
    const me: string = socket.data.userId;
    const ctx = await this.social.activeMatchFor(String(p?.matchId), me);
    if (!ctx) return;
    const t = new Date();
    const r = await this.prisma.message.updateMany({
      where: { matchId: ctx.match.id, senderId: { not: me }, readAt: null },
      data: { readAt: t },
    });
    await this.prisma.message.updateMany({ where: { matchId: ctx.match.id, senderId: { not: me }, deliveredAt: null }, data: { deliveredAt: t } });
    if (r.count) this.bus.emitToUser(ctx.otherId, 'message:read', { matchId: ctx.match.id, readAt: t.getTime() });
    socket.to(RealtimeBus.room(me)).emit('match:read', { matchId: ctx.match.id }); // clear badge on my other devices
  }

  @SubscribeMessage('typing')
  async onTyping(@ConnectedSocket() socket: Socket, @MessageBody() p: { matchId?: string; isTyping?: boolean }) {
    const me: string = socket.data.userId;
    if (!(await this.limiter.allow(`typing:${me}`, 40, 60_000))) return;
    const ctx = await this.social.activeMatchFor(String(p?.matchId), me);
    if (ctx) this.bus.emitToUser(ctx.otherId, 'typing', { matchId: ctx.match.id, isTyping: !!p.isTyping });
  }

  @SubscribeMessage('presence:get')
  async onPresence(@ConnectedSocket() socket: Socket, @MessageBody() p: { matchId?: string }): Promise<Ack> {
    const ctx = await this.social.activeMatchFor(String(p?.matchId), socket.data.userId);
    return { ok: true, online: !!ctx && (await this.bus.isOnline(ctx.otherId)) };
  }

  // --------------------------- CALL SIGNALING ---------------------------
  @SubscribeMessage('call:invite')
  async onInvite(@ConnectedSocket() socket: Socket, @MessageBody() p: { matchId?: string; kind?: string }): Promise<Ack> {
    const me: string = socket.data.userId;
    const kind = p?.kind === 'audio' ? 'audio' : 'video';
    const ctx = await this.social.activeMatchFor(String(p?.matchId), me);
    if (!ctx) return err('match_unavailable');
    if (!(await this.limiter.allow(`call:${me}`, 10, 60_000))) return err('rate_limited');
    if (await this.calls.isBusy(ctx.otherId)) return err('busy');
    const c = await this.calls.create(ctx.match.id, me, ctx.otherId, kind);
    if (!c) return err('busy');
    const caller = await this.prisma.user.findUniqueOrThrow({ where: { id: me } });
    const from = await this.presenter.public(caller);
    this.bus.emitToUser(ctx.otherId, 'call:incoming', { callId: c.callId, matchId: c.matchId, kind, from });
    // Wake the callee's phone (full-screen incoming call notification) if the app isn't connected
    if (!(await this.bus.isOnline(ctx.otherId))) {
      await this.push.push({
        userId: ctx.otherId, ttlSec: 35,
        data: { type: 'call', callId: c.callId, matchId: c.matchId, kind, title: caller.name || 'Incoming call', body: `Incoming ${kind} call` },
      });
    }
    return { ok: true, callId: c.callId, iceServers: config.ICE_SERVERS };
  }

  @SubscribeMessage('call:accept')
  async onAccept(@ConnectedSocket() socket: Socket, @MessageBody() p: { callId?: string }): Promise<Ack> {
    const me: string = socket.data.userId;
    const c = await this.calls.get(String(p?.callId));
    if (!c || c.calleeId !== me || c.accepted) return err('call_unavailable');
    if (!(await this.social.activeMatchFor(c.matchId, me))) return err('match_unavailable');
    await this.calls.accept(c);
    socket.to(RealtimeBus.room(me)).emit('call:ended', { callId: c.callId, reason: 'answered_elsewhere' });
    this.bus.emitToUser(c.callerId, 'call:accepted', { callId: c.callId });
    return { ok: true, iceServers: config.ICE_SERVERS };
  }

  @SubscribeMessage('call:reject')
  async onReject(@ConnectedSocket() socket: Socket, @MessageBody() p: { callId?: string }) {
    const c = await this.calls.get(String(p?.callId));
    if (c && c.calleeId === socket.data.userId) await this.calls.end(c.callId, 'rejected', 'rejected');
  }

  @SubscribeMessage('call:end')
  async onEnd(@ConnectedSocket() socket: Socket, @MessageBody() p: { callId?: string }) {
    const me = socket.data.userId;
    const c = await this.calls.get(String(p?.callId));
    if (c && (c.callerId === me || c.calleeId === me)) await this.calls.end(c.callId, c.accepted ? 'ended' : 'missed', 'hangup');
  }

  /** SDP offer/answer + ICE candidates are relayed only between the two participants of an accepted call. */
  private async relay(event: string, socket: Socket, p: { callId?: string; sdp?: unknown; candidate?: unknown }) {
    const me = socket.data.userId;
    const c = await this.calls.get(String(p?.callId));
    if (!c || !c.accepted || (c.callerId !== me && c.calleeId !== me)) return;
    const peer = c.callerId === me ? c.calleeId : c.callerId;
    this.bus.emitToUser(peer, event, { callId: c.callId, sdp: p.sdp, candidate: p.candidate });
  }
  @SubscribeMessage('call:offer') offer(@ConnectedSocket() s: Socket, @MessageBody() p: object) { return this.relay('call:offer', s, p); }
  @SubscribeMessage('call:answer') answer(@ConnectedSocket() s: Socket, @MessageBody() p: object) { return this.relay('call:answer', s, p); }
  @SubscribeMessage('call:ice') ice(@ConnectedSocket() s: Socket, @MessageBody() p: object) { return this.relay('call:ice', s, p); }
}
