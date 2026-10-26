import { Global, Inject, Injectable, Module, OnModuleDestroy } from '@nestjs/common';
import Redis from 'ioredis';
import { config } from '../config';

export const REDIS = Symbol('REDIS');

/** Fixed-window rate limiter backed by Redis (works across multiple API instances). */
@Injectable()
export class RateLimiter {
  constructor(@Inject(REDIS) private readonly redis: Redis) {}

  async allow(key: string, limit: number, windowMs: number): Promise<boolean> {
    const k = `rl:${key}`;
    const res = await this.redis.multi().incr(k).pexpire(k, windowMs, 'NX').exec();
    const count = Number(res?.[0]?.[1] ?? 0);
    return count <= limit;
  }
}

@Injectable()
class RedisCloser implements OnModuleDestroy {
  constructor(@Inject(REDIS) private readonly redis: Redis) {}
  async onModuleDestroy() { await this.redis.quit().catch(() => undefined); }
}

@Global()
@Module({
  providers: [
    { provide: REDIS, useFactory: () => new Redis(config.REDIS_URL, { maxRetriesPerRequest: null }) },
    RateLimiter,
    RedisCloser,
  ],
  exports: [REDIS, RateLimiter],
})
export class RedisModule {}
