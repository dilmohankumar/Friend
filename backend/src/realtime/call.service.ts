import { InjectQueue, Processor, WorkerHost } from '@nestjs/bullmq';
import { Inject, Injectable } from '@nestjs/common';
import type { Job, Queue } from 'bullmq';
import Redis from 'ioredis';
import { REDIS } from '../redis/redis.module';
import { PrismaService } from '../prisma/prisma.service';
import { RealtimeBus } from './realtime.bus';
import type { CallKind, CallStatus } from '../generated/prisma/client';

export const CALLS_QUEUE = 'calls';
export const RING_TIMEOUT_MS = 35_000;

export interface CallState {
  callId: string; matchId: string; callerId: string; calleeId: string; kind: CallKind; accepted: boolean; createdAt: number;
}

/**
 * Call state lives in Redis (shared by all API instances); ring timeouts are BullMQ delayed jobs,
 * so a call is cleaned up even if the instance that started it goes away.
 */
@Injectable()
export class CallService {
  constructor(
    @Inject(REDIS) private readonly redis: Redis,
    @InjectQueue(CALLS_QUEUE) private readonly queue: Queue,
    private readonly prisma: PrismaService,
    private readonly bus: RealtimeBus,
  ) {}

  private key = (id: string) => `call:${id}`;
  private busyKey = (uid: string) => `busy:${uid}`;
  private ringKey = (uid: string) => `ringing:${uid}`;

  async get(callId: string): Promise<CallState | null> {
    const raw = await this.redis.get(this.key(String(callId)));
    return raw ? (JSON.parse(raw) as CallState) : null;
  }

  async isBusy(uid: string) { return (await this.redis.exists(this.busyKey(uid))) === 1; }

  /** Returns the call currently ringing for a user (used when the callee's app connects after a push). */
  async ringingFor(uid: string): Promise<CallState | null> {
    const id = await this.redis.get(this.ringKey(uid));
    if (!id) return null;
    const c = await this.get(id);
    return c && !c.accepted ? c : null;
  }

  async create(matchId: string, callerId: string, calleeId: string, kind: CallKind): Promise<CallState | null> {
    const row = await this.prisma.call.create({ data: { matchId, callerId, calleeId, kind, status: 'ringing' } });
    const c: CallState = { callId: row.id, matchId, callerId, calleeId, kind, accepted: false, createdAt: Date.now() };
    // Claim both participants atomically-ish: NX on each busy key
    const okCaller = await this.redis.set(this.busyKey(callerId), c.callId, 'EX', 3600, 'NX');
    const okCallee = okCaller ? await this.redis.set(this.busyKey(calleeId), c.callId, 'EX', 3600, 'NX') : null;
    if (!okCaller || !okCallee) {
      if (okCaller) await this.redis.del(this.busyKey(callerId));
      await this.prisma.call.update({ where: { id: c.callId }, data: { status: 'missed', endedAt: new Date() } });
      return null;
    }
    await this.redis.multi()
      .set(this.key(c.callId), JSON.stringify(c), 'EX', 3600)
      .set(this.ringKey(calleeId), c.callId, 'PX', RING_TIMEOUT_MS)
      .exec();
    await this.queue.add('ring-timeout', { callId: c.callId }, { jobId: `ring-${c.callId}`, delay: RING_TIMEOUT_MS, removeOnComplete: true, removeOnFail: true });
    return c;
  }

  async accept(c: CallState) {
    c.accepted = true;
    await this.redis.multi().set(this.key(c.callId), JSON.stringify(c), 'EX', 4 * 3600).del(this.ringKey(c.calleeId)).exec();
    await this.queue.remove(`ring-${c.callId}`).catch(() => undefined);
    await this.prisma.call.update({ where: { id: c.callId }, data: { status: 'accepted' } });
  }

  async end(callId: string, status: CallStatus, reason: string) {
    const c = await this.get(callId);
    if (!c) return;
    // Only the first ender wins
    const deleted = await this.redis.del(this.key(callId));
    if (!deleted) return;
    const multi = this.redis.multi();
    for (const uid of [c.callerId, c.calleeId]) {
      // only clear busy/ringing markers that still belong to this call
      multi.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0", 1, this.busyKey(uid), callId);
    }
    multi.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0", 1, this.ringKey(c.calleeId), callId);
    await multi.exec();
    await this.queue.remove(`ring-${callId}`).catch(() => undefined);
    await this.prisma.call.update({ where: { id: callId }, data: { status, endedAt: new Date() } }).catch(() => undefined);
    this.bus.emitToUser(c.callerId, 'call:ended', { callId, reason });
    this.bus.emitToUser(c.calleeId, 'call:ended', { callId, reason });
  }

  async endAllFor(uid: string, reason: string) {
    const id = await this.redis.get(this.busyKey(uid));
    if (!id) return;
    const c = await this.get(id);
    if (c) await this.end(id, c.accepted ? 'ended' : 'missed', reason);
    else await this.redis.del(this.busyKey(uid));
  }
}

@Processor(CALLS_QUEUE)
export class CallTimeoutProcessor extends WorkerHost {
  constructor(private readonly calls: CallService) { super(); }

  async process(job: Job<{ callId: string }>) {
    const c = await this.calls.get(job.data.callId);
    if (c && !c.accepted) await this.calls.end(c.callId, 'missed', 'no_answer');
  }
}
