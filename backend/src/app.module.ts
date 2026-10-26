import { Controller, Get, Global, Inject, Module, NotFoundException, Param, Query, Res } from '@nestjs/common';
import Redis from 'ioredis';
import { BullModule } from '@nestjs/bullmq';
import type { Response } from 'express';
import * as fs from 'fs';
import { config } from './config';
import { PrismaModule } from './prisma/prisma.service';
import { REDIS, RedisModule } from './redis/redis.module';
import { PrismaService } from './prisma/prisma.service';
import { MaintenanceService } from './common/maintenance.service';
import { AdminController, AdminGuard } from './admin/admin.controller';
import { StorageService } from './storage/storage.service';
import { ProfilePresenter } from './users/profile.presenter';
import { AuthGuard, ActiveGuard } from './common/auth.guard';
import { AuthController } from './auth/auth.controller';
import { SmsService } from './auth/sms.service';
import { KycController, MockKycProviderController } from './kyc/kyc.controller';
import { KycProviders } from './kyc/kyc-provider';
import { DigiLockerProvider } from './kyc/digilocker.provider';
import { MeController } from './users/me.controller';
import { SocialController } from './social/social.controller';
import { SocialService } from './social/social.service';
import { RealtimeBus } from './realtime/realtime.bus';
import { RealtimeGateway } from './realtime/realtime.gateway';
import { CALLS_QUEUE, CallService, CallTimeoutProcessor } from './realtime/call.service';
import { NotificationsService, PUSH_QUEUE, PushProcessor } from './notifications/notifications.service';
import { DemoService } from './demo/demo.service';

@Controller()
class HealthController {
  constructor(
    private readonly storage: StorageService,
    private readonly prisma: PrismaService,
    @Inject(REDIS) private readonly redis: Redis,
  ) {}

  /** Load-balancer health: only healthy when the database and Redis actually answer. */
  @Get('api/health')
  async health(@Res({ passthrough: true }) res: Response) {
    try {
      await Promise.all([this.prisma.$queryRaw`SELECT 1`, this.redis.ping()]);
      return { ok: true };
    } catch {
      res.status(503);
      return { ok: false };
    }
  }

  @Get('api/config')
  cfg() {
    return { iceServers: config.ICE_SERVERS, ...(config.live ? {} : { devOtp: config.DEV_SHOW_OTP, mockKyc: config.MOCK_KYC, demo: config.SEED_DEMO }) };
  }

  /** Local-storage photos (dev). In production with S3, the app loads presigned S3 URLs directly. */
  @Get('media/:file')
  media(@Param('file') file: string, @Query('exp') exp: string, @Query('sig') sig: string, @Res() res: Response) {
    if (!this.storage.verifyLocal(file, exp, sig)) return res.status(403).end();
    const p = this.storage.localPath(file);
    if (!fs.existsSync(p)) throw new NotFoundException();
    res.setHeader('Content-Type', 'image/jpeg');
    res.setHeader('Cache-Control', 'private, max-age=3600');
    fs.createReadStream(p).pipe(res);
  }
}

@Global()
@Module({
  providers: [StorageService, ProfilePresenter, AuthGuard, ActiveGuard, RealtimeBus, SocialService, NotificationsService, DemoService],
  exports: [StorageService, ProfilePresenter, AuthGuard, ActiveGuard, RealtimeBus, SocialService, NotificationsService, DemoService],
  imports: [BullModule.registerQueue({ name: PUSH_QUEUE }, { name: CALLS_QUEUE })],
})
class CoreModule {}

@Module({
  imports: [
    PrismaModule,
    RedisModule,
    BullModule.forRoot({ connection: { url: config.REDIS_URL } }),
    BullModule.registerQueue({ name: PUSH_QUEUE }, { name: CALLS_QUEUE }),
    CoreModule,
  ],
  controllers: [HealthController, AdminController, AuthController, KycController, MockKycProviderController, MeController, SocialController],
  providers: [SmsService, MaintenanceService, AdminGuard, KycProviders, DigiLockerProvider, RealtimeGateway, CallService, CallTimeoutProcessor, PushProcessor],
})
export class AppModule {}
