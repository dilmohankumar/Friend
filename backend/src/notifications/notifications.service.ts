import { InjectQueue, Processor, WorkerHost } from '@nestjs/bullmq';
import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import type { Job, Queue } from 'bullmq';
import * as fs from 'fs';
import * as admin from 'firebase-admin';
import { config } from '../config';
import { PrismaService } from '../prisma/prisma.service';

export const PUSH_QUEUE = 'push';

export interface PushJob {
  userId: string;
  /** Data-only payload; the Android app builds the notification itself (incl. full-screen call UI). */
  data: Record<string, string>;
  /** "high" for messages & incoming calls */
  priority?: 'high' | 'normal';
  ttlSec?: number;
}

/** Producer: other services enqueue push notifications; delivery happens in the background worker. */
@Injectable()
export class NotificationsService {
  constructor(@InjectQueue(PUSH_QUEUE) private readonly queue: Queue<PushJob>) {}

  async push(job: PushJob) {
    await this.queue.add('send', job, { attempts: 3, backoff: { type: 'exponential', delay: 2000 }, removeOnComplete: 1000, removeOnFail: 1000 });
  }
}

/** BullMQ worker that delivers pushes through Firebase Cloud Messaging. */
@Processor(PUSH_QUEUE)
export class PushProcessor extends WorkerHost implements OnModuleInit {
  private readonly log = new Logger('Push');
  private messaging?: admin.messaging.Messaging;

  constructor(private readonly prisma: PrismaService) { super(); }

  onModuleInit() {
    const sa = config.FIREBASE_SERVICE_ACCOUNT;
    if (!sa) { this.log.warn('FIREBASE_SERVICE_ACCOUNT not set — push notifications are logged, not sent'); return; }
    const json = sa.trim().startsWith('{') ? JSON.parse(sa) : JSON.parse(fs.readFileSync(sa, 'utf8'));
    const app = admin.apps.length ? admin.app() : admin.initializeApp({ credential: admin.credential.cert(json) });
    this.messaging = app.messaging();
    this.log.log('Firebase Cloud Messaging enabled');
  }

  async process(job: Job<PushJob>): Promise<{ sent: number }> {
    const { userId, data, priority = 'high', ttlSec = 3600 } = job.data;
    const devices = await this.prisma.deviceToken.findMany({ where: { userId } });
    if (!devices.length) return { sent: 0 };
    if (!this.messaging) {
      this.log.log(`[DEV PUSH] -> ${userId} (${devices.length} device) ${JSON.stringify(data)}`);
      return { sent: 0 };
    }
    const res = await this.messaging.sendEachForMulticast({
      tokens: devices.map((d) => d.token),
      data,
      android: { priority, ttl: ttlSec * 1000 },
    });
    // Clean up tokens for uninstalled apps
    const dead = res.responses
      .map((r, i) => (!r.success && /registration-token-not-registered|invalid-registration-token|invalid-argument/.test(r.error?.code || '') ? devices[i].token : null))
      .filter((t): t is string => !!t);
    if (dead.length) await this.prisma.deviceToken.deleteMany({ where: { token: { in: dead } } });
    return { sent: res.successCount };
  }
}
