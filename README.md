# Situationship — Android (Kotlin) + Backend (NestJS)

Built on the agreed tech stack:

| Layer | Technology |
|---|---|
| Android | **Kotlin + Jetpack Compose + Material 3**, MVVM, **Hilt**, Coroutines/Flow, **Retrofit + OkHttp**, Kotlin Serialization, **Room**, **DataStore + Android Keystore**, **Coil**, **Socket.IO client**, **WebRTC** (Stream build), **Firebase Cloud Messaging**, Crashlytics, Chrome Custom Tabs |
| Backend | **Node.js 22 + NestJS 11 + TypeScript**, **PostgreSQL + Prisma 7**, **Redis** (rate limits, call state, Socket.IO adapter), **Socket.IO**, **BullMQ** (push queue, call timeouts), **Firebase Admin**, **AWS S3** (or any S3-compatible), JWT + rotating refresh tokens, Sentry, Docker |

```
situationship-app/
├── backend/            NestJS API + realtime gateway
│   ├── prisma/         schema.prisma + SQL migration
│   ├── src/            auth, kyc, users, social, realtime (chat + calls), notifications, storage, demo
│   └── test/api.e2e.js 90 end-to-end checks
├── android/            Android Studio project (Kotlin, Compose)
└── docker-compose.yml  PostgreSQL + Redis + MinIO(S3) + API
```

---

## 1. Backend chalao

### Option A — Docker (sabse aasaan)
```bash
docker compose up --build
```
API: `http://localhost:3000` (PostgreSQL, Redis, MinIO sab saath mein start ho jaate hain, migration apne aap chal jaata hai).

### Option B — bina Docker
Requirements: Node.js 22, PostgreSQL 16, Redis 7.
```bash
cd backend
cp .env.example .env            # DATABASE_URL / REDIS_URL check karo
npm install
npx prisma migrate deploy       # tables banata hai
npm run build && npm start
```

Dev mode mein:
- **OTP** SMS nahi jaata — app ki screen par dikhta hai (aur server log mein).
- **Identity verification** ek *mock provider* page se hoti hai (koi bhi DOB; 18+ = eligible).
- **10 demo profiles** seed hote hain — like karo to wapas like karte hain, chat mein auto-reply.

Tests (server chalte hue):
```bash
npm run test:e2e                 # 89 checks; SLOW=1 se call-timeout check bhi (90)
```

---

## 2. Android app chalao

1. **Android Studio** (Ladybug 2024.2 ya naya) install karo.
2. `File → Open` → `situationship-app/android` folder choose karo. Gradle sync apne aap hoga (pehli baar 5–10 min).
3. Backend URL:
   - **Emulator**: kuch nahi karna — default `http://10.0.2.2:3000/` (emulator ka "aapka PC").
   - **Real phone (same WiFi)**: `android/local.properties` mein add karo
     `api.baseUrl=http://192.168.x.x:3000/` (apne PC ka IP), aur backend `.env` mein `PUBLIC_BASE_URL=http://192.168.x.x:3000`.
4. ▶ **Run** dabao.

Video call test karne ke liye 2 devices chahiye (2 emulators ya emulator + phone) — dono par alag account banao (ek Man, ek Woman), ek doosre ko like karo, chat kholo, 📞 / 🎥 dabao. Emulator ka camera ek fake scene dikhata hai — real camera ke liye phone use karo.

### Firebase (push notifications + Crashlytics)
1. https://console.firebase.google.com → project banao → Android app add karo, package `com.situationship.app`.
2. `google-services.json` download karke `android/app/` mein rakho — build apne aap FCM + Crashlytics enable kar dega.
3. Firebase → Project settings → Service accounts → *Generate new private key* → us JSON ka path backend `.env` mein `FIREBASE_SERVICE_ACCOUNT=/path/key.json`.

Bina is file ke bhi app poora chalta hai — bas app band hone par notification nahi aayega.

### Release build (Play Store)
```bash
keytool -genkey -v -keystore situationship.jks -keyalg RSA -keysize 2048 -validity 10000 -alias situationship
```
`android/keystore.properties`:
```
storeFile=situationship.jks
storePassword=****
keyAlias=situationship
keyPassword=****
```
`android/local.properties` mein `api.releaseBaseUrl=https://api.yourdomain.com/` — phir
`Build → Generate Signed App Bundle` → `.aab` Play Console par upload.

---

## 3. Features

| Area | Kya hai |
|---|---|
| Login | +91 OTP (hashed, 5 min expiry, 5 attempts, Redis rate-limit), 15-min JWT + rotating refresh tokens; Android par Keystore-encrypted storage |
| Onboarding | `CONSENT_PENDING → IDENTITY_PENDING → PROFILE_INCOMPLETE → ACTIVE` — server decide karta hai, app sirf follow karta hai |
| Identity | Provider ka page Chrome Custom Tab mein → **HMAC-signed callback** backend verify karta hai → `situationship://kyc/return` deep link se app mein wapas. Aadhaar number kabhi store nahi hota |
| Profile | 1–6 photos (Android photo picker, resize + EXIF strip; server par `sharp` se re-encode), naam, gender, show-me, age range, city, bio |
| Discover | Men ↔ Women + dono ki age preference + blocks — server par enforce. Drag-to-swipe cards, photo carousel |
| Matching | Mutual like = match. PostgreSQL advisory lock + unique constraint → simultaneous likes par bhi ek hi match |
| Chat | Socket.IO real-time; Room cache + offline outbox; client message ID (duplicate nahi); ✓ sent / ✓✓ delivered / Read; typing; online status |
| Calls | WebRTC audio + video, mute, camera on/off, flip, speaker/earpiece/Bluetooth routing, foreground service, ringtone; camera/mic sirf accept ke baad |
| Push | FCM via BullMQ queue: offline message, new match, **full-screen incoming call** (lock screen par bhi); call app khulne par phir se deliver hoti hai |
| Safety | Unmatch, Block, Report (6 reasons), blocked list, safety tips |
| Privacy | Private S3 bucket + presigned URLs; phone number masked; in-app account deletion; backups disabled for tokens/chat |

---

## 4. Production se pehle (zaroori)

**Demo vs Live apne aap badalta hai:** `MSG91_AUTH_KEY` + `MSG91_TEMPLATE_ID` na hon to server **DEMO** mode mein chalta hai (OTP app par dikhta hai, mock identity page, 10 demo profiles). Keys daalte hi **LIVE** mode: ye sab band, aur S3 + secrets zaroori. `DEV_SHOW_OTP` / `MOCK_KYC` / `SEED_DEMO` `.env` mein mat daalo — live mein server unke saath start hi nahi hota.

1. **SMS**: MSG91 (DLT template) — `MSG91_AUTH_KEY`, `MSG91_TEMPLATE_ID`.
2. **Identity verification — DigiLocker** (Govt. of India, "Meri Pehchaan" requester API): API Setu / DigiLocker partner portal par requester register karo (company India mein registered honi chahiye; approval aur fee unki taraf se). Phir `.env`: `KYC_PROVIDER=digilocker`, `DIGILOCKER_CLIENT_ID`, `DIGILOCKER_CLIENT_SECRET`, aur partner portal mein redirect URI register karo: `<PUBLIC_BASE_URL>/api/kyc/digilocker/callback` (server start par log mein dikhta hai). Sirf Aadhaar-backed DigiLocker accounts ki DOB maani jaati hai. Sirf birth *year*, age flag aur DigiLocker id ka hash store hota hai — naam, poori DOB, token ya Aadhaar number kabhi nahi. Ek DigiLocker account = ek app account. Koi provider configured na ho to LIVE mein koi verify nahi hota (`kyc_provider_not_configured`). Legal review zaroor karao.
3. `JWT_SECRET`, `APP_SECRET`, `KYC_PROVIDER_SECRET` (`openssl rand -hex 32`).
4. **TURN server** (coturn / Metered / Twilio) → `ICE_SERVERS` — mobile data par calls ke liye zaroori.
5. **S3**: `S3_BUCKET` set karte hi storage s3 ho jata hai; private bucket, IAM role.
6. HTTPS domain (Nginx/Caddy), DB backups.
7. **Moderation**: `ADMIN_API_KEY` (>= 32 chars) set karo, phir `x-admin-key` header ke saath: `GET /api/admin/reports`, `GET /api/admin/users/:id`, `POST /api/admin/users/:id/suspend {reason}`, `.../unsuspend`, `POST /api/admin/reports/:id/resolve {action: dismissed|actioned}`. Key na ho to admin API band (404).
8. Play Console: privacy policy URL, Data Safety form, dating-app & UGC policies, account-deletion URL.

**Jo verify nahi hua:** DigiLocker ka koi test environment nahi hota (unki spec ke hisaab se), isliye integration unke v2.3 spec par bane simulator ke against test hua hai, asli DigiLocker ke against nahi. Pehli real verification kisi apne account se staging par karke dekho. Real SMS, S3, push aur phone par calls bhi real env mein hi pehli baar chalenge — pehle 10–20 logon ke saath try karo.

## 5. Kya test hua hai
- **Backend**: PostgreSQL 16 + Redis 7 par 90/90 end-to-end checks pass — local disk aur S3 (emulator) dono storage ke saath. Simultaneous likes, offline delivery, push queue, call ringing/timeout sab included.
- **Android**: is environment mein Android SDK/Google Maven blocked hai, isliye APK yahan build nahi hua. Kotlin syntax compiler se check hua (0 errors), aur Socket.IO + WebRTC APIs unke source se verify kiye gaye. Pehli baar Android Studio mein build karte waqt koi error aaye to error text bhejo — turant fix kar dunga.
