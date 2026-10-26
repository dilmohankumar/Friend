import 'reflect-metadata';
import * as Sentry from '@sentry/node';
import { config } from './config';

if (config.SENTRY_DSN) Sentry.init({ dsn: config.SENTRY_DSN, tracesSampleRate: 0.1, environment: process.env.NODE_ENV });

import { INestApplicationContext, Logger } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { IoAdapter } from '@nestjs/platform-socket.io';
import { createAdapter } from '@socket.io/redis-adapter';
import Redis from 'ioredis';
import helmet from 'helmet';
import type { ServerOptions } from 'socket.io';
import { AppModule } from './app.module';

/** Socket.IO with the Redis adapter, so rooms/emits work across many API instances. */
class RedisIoAdapter extends IoAdapter {
  private adapterCtor?: ReturnType<typeof createAdapter>;
  constructor(app: INestApplicationContext) { super(app); }

  connectToRedis() {
    const pub = new Redis(config.REDIS_URL);
    const sub = pub.duplicate();
    this.adapterCtor = createAdapter(pub, sub);
  }

  createIOServer(port: number, options?: ServerOptions) {
    const server = super.createIOServer(port, { ...options, pingInterval: 20_000, pingTimeout: 20_000 });
    if (this.adapterCtor) server.adapter(this.adapterCtor);
    return server;
  }
}

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule, { bufferLogs: false });
  app.set('trust proxy', 1);
  app.disable('x-powered-by');
  app.use(helmet({ crossOriginResourcePolicy: { policy: 'same-site' } }));
  app.useBodyParser('json', { limit: '256kb' });
  if (!config.isProd) app.enableCors();
  app.enableShutdownHooks();

  const io = new RedisIoAdapter(app);
  io.connectToRedis();
  app.useWebSocketAdapter(io);

  await app.listen(config.PORT, '0.0.0.0');
  const log = new Logger('Bootstrap');
  log.log(`Situationship API on ${config.PUBLIC_BASE_URL} (port ${config.PORT})`);
  if (config.demoMode) log.warn('DEMO MODE (no real SMS credentials): fake OTPs, mock identity check, demo profiles. Set MSG91_AUTH_KEY + MSG91_TEMPLATE_ID to go live.');
  else log.log('LIVE MODE: real SMS, no demo data, no mock identity check');
  if (config.DEV_SHOW_OTP) log.warn('DEV: OTP codes are returned by the API and logged');
  if (config.MOCK_KYC) log.warn('DEV: identity verification uses the MOCK provider');
}

bootstrap();
