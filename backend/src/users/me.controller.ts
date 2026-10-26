import {
  BadRequestException, Body, Controller, Delete, ForbiddenException, Get, HttpCode, HttpException, Param, Post, Put, UploadedFile, UseGuards, UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import * as crypto from 'crypto';
import sharp from 'sharp';
import { PrismaService } from '../prisma/prisma.service';
import { RateLimiter } from '../redis/redis.module';
import { AuthGuard, CurrentUser } from '../common/auth.guard';
import { StorageService } from '../storage/storage.service';
import { ProfilePresenter } from './profile.presenter';
import { RealtimeBus } from '../realtime/realtime.bus';
import type { Gender, User } from '../generated/prisma/client';

const MAX_PHOTOS = 6;
const GENDERS = ['male', 'female'];
/** Only real photo formats; SVG and other vector/exotic inputs are rejected before they reach the decoder. */
const PHOTO_FORMATS = ['jpeg', 'png', 'webp', 'heif'];
/** Free-text profile fields must be strings — String({}) would otherwise store "[object Object]". */
const text = (v: unknown, fallback: string | null | undefined, max: number) => {
  if (v === undefined || v === null) return String(fallback ?? '').trim().slice(0, max);
  if (typeof v !== 'string') throw new BadRequestException({ error: 'invalid_profile' });
  return v.trim().slice(0, max);
};

@Controller('api/me')
@UseGuards(AuthGuard)
export class MeController {
  constructor(
    private readonly prisma: PrismaService,
    private readonly storage: StorageService,
    private readonly presenter: ProfilePresenter,
    private readonly bus: RealtimeBus,
    private readonly limiter: RateLimiter,
  ) {}

  /** PROFILE_INCOMPLETE <-> ACTIVE transitions are decided by the server only. */
  private async promote(userId: string) {
    const u = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    const complete = !!(u.name && u.gender && u.interestedIn && u.photos.length >= 1);
    if (u.status === 'PROFILE_INCOMPLETE' && complete && u.kycStatus === 'verified' && u.ageEligible) {
      return this.prisma.user.update({ where: { id: userId }, data: { status: 'ACTIVE' } });
    }
    if (u.status === 'ACTIVE' && !complete) {
      return this.prisma.user.update({ where: { id: userId }, data: { status: 'PROFILE_INCOMPLETE' } });
    }
    return u;
  }

  private requireVerified(u: User) {
    if (u.kycStatus !== 'verified' || !u.ageEligible) throw new ForbiddenException({ error: 'verification_required' });
  }

  @Get()
  async me(@CurrentUser() u: User) {
    return { user: await this.presenter.self(u) };
  }

  @Put('profile')
  async updateProfile(@CurrentUser() u: User, @Body() b: Record<string, unknown>) {
    this.requireVerified(u);
    const name = text(b.name, u.name, 40);
    const gender = text(b.gender, u.gender, 10);
    const interestedIn = text(b.interestedIn, u.interestedIn, 10);
    const minAge = Math.max(18, Math.min(99, Number(b.minAge ?? u.minAge) || 18));
    const maxAge = Math.max(minAge, Math.min(99, Number(b.maxAge ?? u.maxAge) || 60));
    if (name.length < 2) throw new BadRequestException({ error: 'name_required' });
    if (!GENDERS.includes(gender)) throw new BadRequestException({ error: 'invalid_gender' });
    if (!GENDERS.includes(interestedIn)) throw new BadRequestException({ error: 'invalid_interest' });
    await this.prisma.user.update({
      where: { id: u.id },
      data: {
        name, gender: gender as Gender, interestedIn: interestedIn as Gender, minAge, maxAge,
        bio: text(b.bio, u.bio, 300),
        city: text(b.city, u.city, 40),
      },
    });
    return { user: await this.presenter.self(await this.promote(u.id)) };
  }

  @Post('photos')
  @UseInterceptors(FileInterceptor('photo', { limits: { fileSize: 8 * 1024 * 1024, files: 1 } }))
  async addPhoto(@CurrentUser() u: User, @UploadedFile() file?: Express.Multer.File) {
    this.requireVerified(u);
    if (u.photos.length >= MAX_PHOTOS) throw new BadRequestException({ error: 'max_photos' });
    if (!file?.buffer?.length) throw new BadRequestException({ error: 'invalid_image' });
    if (!(await this.limiter.allow(`upload:${u.id}`, 20, 60 * 60_000))) throw new HttpException({ error: 'too_many_requests' }, 429);
    let jpeg: Buffer;
    try {
      const meta = await sharp(file.buffer, { limitInputPixels: 50_000_000 }).metadata();
      if (!meta.format || !PHOTO_FORMATS.includes(meta.format)) throw new Error('unsupported format');
      // Decode + re-encode: rejects non-images, fixes rotation, strips EXIF/GPS metadata
      jpeg = await sharp(file.buffer, { limitInputPixels: 50_000_000 })
        .rotate().resize(1440, 1440, { fit: 'inside', withoutEnlargement: true })
        .jpeg({ quality: 85, mozjpeg: true }).toBuffer();
    } catch {
      throw new BadRequestException({ error: 'invalid_image' });
    }
    const key = `photos/${u.id}/${crypto.randomBytes(16).toString('hex')}.jpg`;
    await this.storage.put(key, jpeg, 'image/jpeg');
    await this.prisma.user.update({ where: { id: u.id }, data: { photos: { push: key } } });
    return { user: await this.presenter.self(await this.promote(u.id)) };
  }

  @Delete('photos/:idx')
  async deletePhoto(@CurrentUser() u: User, @Param('idx') idx: string) {
    const i = Number(idx);
    if (!(Number.isInteger(i) && i >= 0 && i < u.photos.length)) throw new BadRequestException({ error: 'invalid_index' });
    const photos = [...u.photos];
    const [key] = photos.splice(i, 1);
    await this.prisma.user.update({ where: { id: u.id }, data: { photos } });
    await this.storage.remove(key);
    return { user: await this.presenter.self(await this.promote(u.id)) };
  }

  @Get('blocked')
  async blocked(@CurrentUser() u: User) {
    const rows = await this.prisma.block.findMany({ where: { blockerId: u.id }, include: { blocked: true }, orderBy: { createdAt: 'desc' } });
    return { users: await Promise.all(rows.map((r) => this.presenter.public(r.blocked))) };
  }

  /** FCM device token registration */
  @Post('devices')
  @HttpCode(200)
  async registerDevice(@CurrentUser() u: User, @Body() b: { token?: string; platform?: string }) {
    const token = String(b?.token ?? '').slice(0, 4096);
    if (token.length < 20) throw new BadRequestException({ error: 'invalid_token' });
    await this.prisma.deviceToken.upsert({
      where: { token }, create: { token, userId: u.id, platform: b.platform === 'ios' ? 'ios' : 'android' },
      update: { userId: u.id },
    });
    return { ok: true };
  }

  /** In-app account deletion (Google Play requirement): user, photos, swipes, matches, messages. */
  @Delete()
  async deleteAccount(@CurrentUser() u: User) {
    await this.prisma.user.delete({ where: { id: u.id } }); // FK cascades
    await Promise.all(u.photos.map((k) => this.storage.remove(k)));
    this.bus.disconnectUser(u.id);
    return { ok: true };
  }
}
