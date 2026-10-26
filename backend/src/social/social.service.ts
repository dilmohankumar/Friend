import { Injectable } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import type { Match, Message, User } from '../generated/prisma/client';

export const orderedPair = (a: string, b: string): [string, string] => (a < b ? [a, b] : [b, a]);

export interface MatchContext { match: Match; otherId: string; other: User }

export function serializeMessage(m: Message) {
  return {
    id: m.id,
    matchId: m.matchId,
    senderId: m.senderId,
    clientMessageId: m.clientMessageId,
    text: m.text,
    createdAt: m.createdAt.getTime(),
    deliveredAt: m.deliveredAt?.getTime() ?? null,
    readAt: m.readAt?.getTime() ?? null,
  };
}
export type MessageDto = ReturnType<typeof serializeMessage>;

@Injectable()
export class SocialService {
  constructor(private readonly prisma: PrismaService) {}

  async isBlockedEitherWay(a: string, b: string): Promise<boolean> {
    const n = await this.prisma.block.count({
      where: { OR: [{ blockerId: a, blockedId: b }, { blockerId: b, blockedId: a }] },
    });
    return n > 0;
  }

  /**
   * The single authorisation check for chat & calls: match exists and is active, `uid` is part of it,
   * nobody blocked anybody, and the other user is still ACTIVE.
   */
  async activeMatchFor(matchId: string, uid: string): Promise<MatchContext | null> {
    if (!/^[0-9a-f-]{36}$/i.test(matchId)) return null;
    const match = await this.prisma.match.findFirst({ where: { id: matchId, active: true, OR: [{ userAId: uid }, { userBId: uid }] } });
    if (!match) return null;
    const otherId = match.userAId === uid ? match.userBId : match.userAId;
    if (await this.isBlockedEitherWay(uid, otherId)) return null;
    const other = await this.prisma.user.findFirst({ where: { id: otherId, status: 'ACTIVE' } });
    if (!other) return null;
    return { match, otherId, other };
  }
}
