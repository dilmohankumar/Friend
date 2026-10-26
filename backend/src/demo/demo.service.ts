/**
 * DEVELOPMENT ONLY: seeds fictional verified profiles so one tester can try discovery, matching and chat
 * without a second phone. Demo profiles like you back and auto-reply. Disable with SEED_DEMO=false.
 */
import { Injectable, Logger, OnApplicationBootstrap } from '@nestjs/common';
import * as crypto from 'crypto';
import sharp from 'sharp';
import { PrismaService } from '../prisma/prisma.service';
import { StorageService } from '../storage/storage.service';
import { RealtimeBus } from '../realtime/realtime.bus';
import { ProfilePresenter } from '../users/profile.presenter';
import { config } from '../config';
import { orderedPair, serializeMessage, type MatchContext } from '../social/social.service';
import type { Gender, User } from '../generated/prisma/client';

const PEOPLE: Array<[string, Gender, number, string, string, [string, string]]> = [
  ['Aanya', 'female', 1999, 'Mumbai', 'Chai over coffee, always. Weekend trekker 🏔️', ['#f472b6', '#fb923c']],
  ['Diya', 'female', 2001, 'Pune', 'Bookshop hopper. Ask me about my playlist.', ['#a78bfa', '#f472b6']],
  ['Meera', 'female', 1997, 'Bengaluru', 'Product designer. Dog person. Great at eating.', ['#34d399', '#3b82f6']],
  ['Ishita', 'female', 2000, 'Delhi', 'Dance, street food and long drives.', ['#fbbf24', '#f87171']],
  ['Kavya', 'female', 1998, 'Hyderabad', 'Biryani loyalist. Learning guitar.', ['#22d3ee', '#a78bfa']],
  ['Arjun', 'male', 1998, 'Mumbai', 'Cricket on Sundays, startups on weekdays.', ['#3b82f6', '#06b6d4']],
  ['Rohan', 'male', 1996, 'Bengaluru', 'Backend engineer, amateur photographer 📷', ['#6366f1', '#22d3ee']],
  ['Kabir', 'male', 2000, 'Delhi', 'Gym, anime and momos.', ['#f97316', '#ef4444']],
  ['Vihaan', 'male', 1999, 'Pune', 'Mountains > beaches. Fight me.', ['#10b981', '#84cc16']],
  ['Aditya', 'male', 1997, 'Chennai', 'Filter coffee and film nerd.', ['#8b5cf6', '#ec4899']],
];

const REPLIES = [
  'Hey! Glad we matched 😊',
  "Haha that's fun. What do you do on weekends?",
  'Same here! Where are you from originally?',
  'That sounds amazing. Coffee or chai?',
  'Nice 😄 Tell me something nobody knows about you.',
  "I'm a demo profile, but you're doing great at this 😉",
];

function avatarSvg(initial: string, [c1, c2]: [string, string]) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c1}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs>
<rect width="600" height="800" fill="url(#g)"/><circle cx="300" cy="300" r="120" fill="#fff" fill-opacity=".28"/>
<path d="M120 700c0-110 80-190 180-190s180 80 180 190z" fill="#fff" fill-opacity=".28"/>
<text x="300" y="342" font-family="Arial,Helvetica,sans-serif" font-size="120" font-weight="700" fill="#fff" text-anchor="middle">${initial}</text></svg>`;
}

@Injectable()
export class DemoService implements OnApplicationBootstrap {
  private readonly log = new Logger('Demo');
  private readonly replyIdx = new Map<string, number>();

  constructor(
    private readonly prisma: PrismaService,
    private readonly storage: StorageService,
    private readonly bus: RealtimeBus,
    private readonly presenter: ProfilePresenter,
  ) {}

  async onApplicationBootstrap() {
    if (config.live) return this.purgeDemoData();
    if (!config.SEED_DEMO) return;
    if (await this.prisma.user.count({ where: { isDemo: true } })) return;
    for (const [i, [name, gender, year, city, bio, colors]] of PEOPLE.entries()) {
      const key = `photos/demo/${name.toLowerCase()}-${crypto.randomBytes(4).toString('hex')}.jpg`;
      const jpg = await sharp(Buffer.from(avatarSvg(name[0], colors))).jpeg({ quality: 85 }).toBuffer();
      await this.storage.put(key, jpg, 'image/jpeg');
      await this.prisma.user.create({
        data: {
          phone: `+00demo${i}`, status: 'ACTIVE', consentAt: new Date(), kycStatus: 'verified', kycRef: 'DEMO', ageEligible: true,
          name, gender, interestedIn: gender === 'male' ? 'female' : 'male', birthYear: year, bio, city, photos: [key], isDemo: true,
          createdAt: new Date(Date.now() - i * 1000),
        },
      });
    }
    this.log.log(`Seeded ${PEOPLE.length} demo profiles`);
  }

  /** LIVE mode: fictional profiles left over from demo runs must never reach real users. Only is_demo rows are touched. */
  private async purgeDemoData() {
    const demo = await this.prisma.user.findMany({ where: { isDemo: true }, select: { id: true, photos: true } });
    if (!demo.length) return;
    await this.prisma.user.deleteMany({ where: { isDemo: true } }); // FK cascades swipes/matches/messages
    await Promise.all(demo.flatMap((u) => u.photos.map((k) => this.storage.remove(k))));
    this.log.warn(`LIVE mode: removed ${demo.length} leftover demo profiles`);
  }

  onLike(me: User, target: User) {
    if (!config.SEED_DEMO || !target.isDemo) return;
    setTimeout(async () => {
      try {
        const already = await this.prisma.swipe.findUnique({ where: { swiperId_targetId: { swiperId: target.id, targetId: me.id } } });
        if (already) return;
        await this.prisma.swipe.create({ data: { swiperId: target.id, targetId: me.id, action: 'like' } });
        const [a, b] = orderedPair(me.id, target.id);
        const created = await this.prisma.match.createMany({ data: [{ userAId: a, userBId: b }], skipDuplicates: true });
        if (!created.count) return;
        const m = await this.prisma.match.findUniqueOrThrow({ where: { userAId_userBId: { userAId: a, userBId: b } } });
        this.bus.emitToUser(me.id, 'match:new', { matchId: m.id, user: await this.presenter.public(target) });
      } catch (e) { this.log.warn(String(e)); }
    }, 1200);
  }

  onMessage(senderId: string, ctx: MatchContext) {
    if (!config.SEED_DEMO || !ctx.other.isDemo) return;
    const matchId = ctx.match.id;
    setTimeout(async () => {
      const t = new Date();
      await this.prisma.message.updateMany({ where: { matchId, senderId, readAt: null }, data: { readAt: t, deliveredAt: t } });
      this.bus.emitToUser(senderId, 'message:read', { matchId, readAt: t.getTime() });
      this.bus.emitToUser(senderId, 'typing', { matchId, isTyping: true });
    }, 800);
    setTimeout(async () => {
      const i = this.replyIdx.get(matchId) || 0;
      this.replyIdx.set(matchId, i + 1);
      const row = await this.prisma.message.create({
        data: { matchId, senderId: ctx.other.id, clientMessageId: crypto.randomUUID(), text: REPLIES[i % REPLIES.length], deliveredAt: new Date() },
      });
      this.bus.emitToUser(senderId, 'typing', { matchId, isTyping: false });
      this.bus.emitToUser(senderId, 'message:new', serializeMessage(row));
    }, 2400);
  }
}
