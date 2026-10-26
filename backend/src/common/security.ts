import * as crypto from 'crypto';
import * as jwt from 'jsonwebtoken';
import { config } from '../config';

export const sha256 = (s: string) => crypto.createHash('sha256').update(s).digest('hex');
export const hmac = (s: string, key = config.APP_SECRET) =>
  crypto.createHmac('sha256', key).update(s).digest('hex');

export function safeEqual(a: string, b: string): boolean {
  const A = Buffer.from(String(a));
  const B = Buffer.from(String(b));
  return A.length === B.length && crypto.timingSafeEqual(A, B);
}

export function signAccess(userId: string): string {
  return jwt.sign({ sub: userId, typ: 'access' }, config.JWT_SECRET, { expiresIn: config.ACCESS_TTL_SEC });
}

export function verifyAccess(token: string): string {
  const p = jwt.verify(token, config.JWT_SECRET) as jwt.JwtPayload;
  if (p.typ !== 'access' || typeof p.sub !== 'string') throw new Error('bad token');
  return p.sub;
}

export const newOpaqueToken = () => crypto.randomBytes(32).toString('base64url');
export const uuid = () => crypto.randomUUID();
