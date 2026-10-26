import {
  CanActivate, createParamDecorator, ExecutionContext, ForbiddenException, HttpException, Injectable, UnauthorizedException,
} from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { RateLimiter } from '../redis/redis.module';
import { verifyAccess } from './security';
import type { User } from '../generated/prisma/client';

/** Authenticates the bearer token and loads the user. The backend is always the authority on user state. */
@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private readonly prisma: PrismaService, private readonly limiter: RateLimiter) {}

  async canActivate(ctx: ExecutionContext): Promise<boolean> {
    const req = ctx.switchToHttp().getRequest();
    const h: string = req.headers.authorization || '';
    const token = h.startsWith('Bearer ') ? h.slice(7) : null;
    if (!token) throw new UnauthorizedException({ error: 'auth_required' });
    let userId: string;
    try { userId = verifyAccess(token); } catch { throw new UnauthorizedException({ error: 'token_expired' }); }
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw new UnauthorizedException({ error: 'user_not_found' });
    if (user.status === 'SUSPENDED') throw new ForbiddenException({ error: 'account_suspended' });
    // Blanket per-user budget for every authenticated HTTP call (chat runs over the socket, not here)
    if (!(await this.limiter.allow(`api:${user.id}`, 120, 60_000))) throw new HttpException({ error: 'too_many_requests' }, 429);
    req.user = user;
    return true;
  }
}

/** Discovery / chat / calls gate — re-validated server-side on every request. */
@Injectable()
export class ActiveGuard implements CanActivate {
  canActivate(ctx: ExecutionContext): boolean {
    const u: User = ctx.switchToHttp().getRequest().user;
    if (!u || u.status !== 'ACTIVE' || u.kycStatus !== 'verified' || !u.ageEligible) {
      throw new ForbiddenException({ error: 'not_eligible', status: u?.status });
    }
    return true;
  }
}

export const CurrentUser = createParamDecorator((_: unknown, ctx: ExecutionContext): User =>
  ctx.switchToHttp().getRequest().user);
