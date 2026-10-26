import 'dotenv/config';
import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';

const isProd = process.env.NODE_ENV === 'production';
/**
 * One switch between demo and real: the moment real SMS credentials exist (or NODE_ENV=production) the server is
 * LIVE — fake OTPs, the mock identity provider and demo profiles all turn off by themselves. Without SMS
 * credentials it runs in DEMO mode so the app can be tried end-to-end on an emulator.
 */
const smsConfigured = !!(process.env.MSG91_AUTH_KEY && process.env.MSG91_TEMPLATE_ID);
const live = isProd || smsConfigured;
const DATA_DIR = process.env.DATA_DIR || path.join(process.cwd(), '.data');

// Dev fallback: random secrets persisted locally so sessions survive restarts.
const devSecretFile = path.join(DATA_DIR, 'dev-secrets.json');
let devSecrets: Record<string, string> = {};
try { devSecrets = JSON.parse(fs.readFileSync(devSecretFile, 'utf8')); } catch { /* first run */ }

function secret(name: string): string {
  const v = process.env[name];
  if (v) return v;
  if (live) throw new Error(`${name} must be set when running live (production or real SMS configured)`);
  if (!devSecrets[name]) {
    devSecrets[name] = crypto.randomBytes(32).toString('hex');
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(devSecretFile, JSON.stringify(devSecrets, null, 2), { mode: 0o600 });
  }
  return devSecrets[name];
}

const flag = (name: string, def: boolean) => {
  const v = process.env[name];
  return v === undefined ? def : v === 'true' || v === '1';
};

// Fail closed: demo switches must never be on while live (OTP leaked in API responses, mock identity
// verification that passes anyone, fake demo profiles). Leave them unset: they follow the mode automatically.
if (live) {
  const demoOnly = ['DEV_SHOW_OTP', 'MOCK_KYC', 'SEED_DEMO'].filter((n) => flag(n, false));
  if (demoOnly.length) throw new Error(`${demoOnly.join(', ')} must not be enabled when running live — remove them from .env`);
  if (!process.env.S3_BUCKET || (process.env.STORAGE_DRIVER && process.env.STORAGE_DRIVER !== 's3')) {
    throw new Error('Running live requires S3 photo storage: set S3_BUCKET (local-disk photos are not durable)');
  }
}

export const config = {
  isProd,
  live,
  demoMode: !live,
  PORT: Number(process.env.PORT || 3000),
  PUBLIC_BASE_URL: (process.env.PUBLIC_BASE_URL || `http://localhost:${process.env.PORT || 3000}`).replace(/\/$/, ''),
  DATABASE_URL: process.env.DATABASE_URL || 'postgresql://situationship:situationship@localhost:5432/situationship',
  REDIS_URL: process.env.REDIS_URL || 'redis://localhost:6379',

  JWT_SECRET: secret('JWT_SECRET'),
  APP_SECRET: secret('APP_SECRET'),
  KYC_PROVIDER_SECRET: secret('KYC_PROVIDER_SECRET'),
  ACCESS_TTL_SEC: Number(process.env.ACCESS_TTL_SEC || 15 * 60),
  REFRESH_TTL_MS: 30 * 24 * 60 * 60 * 1000,
  OTP_TTL_MS: 5 * 60 * 1000,

  // DEV ONLY: OTP returned in API response because no SMS gateway is wired yet.
  DEV_SHOW_OTP: flag('DEV_SHOW_OTP', !live),
  MOCK_KYC: flag('MOCK_KYC', !live),
  SEED_DEMO: flag('SEED_DEMO', !live),
  // Deep link the Android app registers to receive the KYC result
  APP_DEEP_LINK: process.env.APP_DEEP_LINK || 'situationship://kyc/return',

  // Storage: "local" (dev) or "s3" (AWS S3 / any S3-compatible e.g. MinIO, R2)
  STORAGE_DRIVER: (process.env.STORAGE_DRIVER || (process.env.S3_BUCKET ? 's3' : 'local')) as 'local' | 's3',
  UPLOAD_DIR: process.env.UPLOAD_DIR || path.join(DATA_DIR, 'uploads'),
  S3_BUCKET: process.env.S3_BUCKET || '',
  S3_REGION: process.env.S3_REGION || 'ap-south-1',
  S3_ENDPOINT: process.env.S3_ENDPOINT || undefined,
  S3_PUBLIC_ENDPOINT: process.env.S3_PUBLIC_ENDPOINT || undefined,
  S3_FORCE_PATH_STYLE: flag('S3_FORCE_PATH_STYLE', false),

  // Firebase Admin (FCM). Path to service-account JSON, or the JSON itself.
  FIREBASE_SERVICE_ACCOUNT: process.env.FIREBASE_SERVICE_ACCOUNT || '',
  SENTRY_DSN: process.env.SENTRY_DSN || '',

  ICE_SERVERS: process.env.ICE_SERVERS
    ? JSON.parse(process.env.ICE_SERVERS)
    : [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }],
  MIN_AGE: 18,
};
