import { Injectable, Logger, ServiceUnavailableException } from '@nestjs/common';
import { config } from '../config';

/**
 * SMS gateway. Wire your provider here (MSG91 / Twilio / Gupshup) before production.
 * In development the code is logged and also returned to the app (DEV_SHOW_OTP).
 */
@Injectable()
export class SmsService {
  private readonly log = new Logger('SMS');

  async sendOtp(phone: string, code: string): Promise<void> {
    if (process.env.MSG91_AUTH_KEY && process.env.MSG91_TEMPLATE_ID) {
      // MSG91 OTP API (DLT-registered template required in India)
      const url = `https://control.msg91.com/api/v5/otp?template_id=${encodeURIComponent(process.env.MSG91_TEMPLATE_ID)}` +
        `&mobile=${encodeURIComponent(phone.replace('+', ''))}&otp=${code}`;
      const r = await fetch(url, { method: 'POST', headers: { authkey: process.env.MSG91_AUTH_KEY } });
      if (!r.ok) {
        this.log.error(`MSG91 failed: ${r.status} ${await r.text()}`);
        throw new ServiceUnavailableException({ error: 'sms_unavailable' });
      }
      return;
    }
    if (!config.demoMode) {
      this.log.error('No SMS provider configured (set MSG91_AUTH_KEY + MSG91_TEMPLATE_ID)');
      throw new ServiceUnavailableException({ error: 'sms_unavailable' });
    }
    this.log.log(`[DEV OTP] ${phone} -> ${code}`);
  }
}
