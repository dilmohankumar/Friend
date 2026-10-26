import { Injectable, Logger } from '@nestjs/common';
import * as fs from 'fs';
import * as path from 'path';
import { DeleteObjectCommand, GetObjectCommand, PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { config } from '../config';
import { hmac, safeEqual } from '../common/security';

/**
 * Private photo storage. Objects are never public:
 *  - s3:    private bucket, short-lived presigned GET URLs
 *  - local: files on disk, served by /media with HMAC-signed expiring URLs (development)
 */
@Injectable()
export class StorageService {
  private readonly log = new Logger(StorageService.name);
  private readonly s3?: S3Client;
  /** Signs URLs with the hostname phones can reach (can differ from the internal endpoint, e.g. Docker). */
  private readonly s3Signer?: S3Client;
  private readonly urlTtlSec = 60 * 60;

  constructor() {
    if (config.STORAGE_DRIVER === 's3') {
      if (!config.S3_BUCKET) throw new Error('S3_BUCKET is required when STORAGE_DRIVER=s3');
      const base = { region: config.S3_REGION, forcePathStyle: config.S3_FORCE_PATH_STYLE };
      this.s3 = new S3Client({ ...base, endpoint: config.S3_ENDPOINT });
      this.s3Signer = config.S3_PUBLIC_ENDPOINT ? new S3Client({ ...base, endpoint: config.S3_PUBLIC_ENDPOINT }) : this.s3;
    } else {
      fs.mkdirSync(config.UPLOAD_DIR, { recursive: true });
    }
    this.log.log(`Photo storage: ${config.STORAGE_DRIVER}`);
  }

  async put(key: string, body: Buffer, contentType: string): Promise<void> {
    if (this.s3) {
      await this.s3.send(new PutObjectCommand({
        Bucket: config.S3_BUCKET, Key: key, Body: body, ContentType: contentType, ServerSideEncryption: 'AES256',
      }));
    } else {
      await fs.promises.writeFile(path.join(config.UPLOAD_DIR, path.basename(key)), body);
    }
  }

  async remove(key: string): Promise<void> {
    try {
      if (this.s3) await this.s3.send(new DeleteObjectCommand({ Bucket: config.S3_BUCKET, Key: key }));
      else await fs.promises.rm(path.join(config.UPLOAD_DIR, path.basename(key)), { force: true });
    } catch (e) { this.log.warn(`delete failed for ${key}: ${e}`); }
  }

  async url(key: string): Promise<string> {
    if (this.s3Signer) {
      return getSignedUrl(this.s3Signer, new GetObjectCommand({ Bucket: config.S3_BUCKET, Key: key }), { expiresIn: this.urlTtlSec });
    }
    const exp = Date.now() + this.urlTtlSec * 1000;
    const file = path.basename(key);
    return `${config.PUBLIC_BASE_URL}/media/${encodeURIComponent(file)}?exp=${exp}&sig=${hmac(`${file}:${exp}`)}`;
  }

  // ---- local driver helpers ----
  verifyLocal(file: string, exp: string, sig: string): boolean {
    if (!exp || !sig || Number(exp) < Date.now()) return false;
    return safeEqual(hmac(`${file}:${exp}`), sig);
  }
  localPath(file: string): string {
    return path.join(config.UPLOAD_DIR, path.basename(file));
  }
}
