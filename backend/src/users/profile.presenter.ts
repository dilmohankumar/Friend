import { Injectable } from '@nestjs/common';
import type { User } from '../generated/prisma/client';
import { StorageService } from '../storage/storage.service';

export interface PublicProfile {
  id: string; name: string | null; age: number | null; gender: string | null; bio: string; city: string;
  photos: string[]; verified: boolean; online: boolean;
}

/** Converts DB users into API shapes. Other users never see phone numbers or KYC details. */
@Injectable()
export class ProfilePresenter {
  constructor(private readonly storage: StorageService) {}

  static age(birthYear: number | null): number | null {
    return birthYear ? new Date().getFullYear() - birthYear : null;
  }

  async public(u: User): Promise<PublicProfile> {
    return {
      id: u.id,
      name: u.name,
      age: ProfilePresenter.age(u.birthYear),
      gender: u.gender,
      bio: u.bio || '',
      city: u.city || '',
      photos: await Promise.all(u.photos.map((k) => this.storage.url(k))),
      verified: u.kycStatus === 'verified',
      online: !!u.lastSeen && Date.now() - u.lastSeen.getTime() < 60_000,
    };
  }

  async self(u: User) {
    return {
      ...(await this.public(u)),
      phone: u.phone.replace(/^(\+?\d{2})\d+(\d{2})$/, '$1••••••$2'),
      status: u.status,
      kycStatus: u.kycStatus,
      ageEligible: u.ageEligible,
      interestedIn: u.interestedIn,
      birthYear: u.birthYear,
      minAge: u.minAge,
      maxAge: u.maxAge,
    };
  }
}
