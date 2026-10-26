// End-to-end test of the API + realtime layer against a running server (PostgreSQL + Redis).
// Usage: npm run start:dev (in another terminal), then: npm run test:e2e
const { io } = require('socket.io-client');
const B = process.env.API_URL || 'http://localhost:3000';
let passed = 0;
const assert = (c, m) => { if (!c) { console.error('FAIL:', m); process.exit(1); } passed++; console.log('ok -', m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const TAG = Math.random().toString(36).slice(2, 6);
const RAHUL = 'Rahul-' + TAG, PRIYA = 'Priya-' + TAG, NEHA = 'Neha-' + TAG; // unique per run
const rnd = () => String(Math.floor(6000000000 + Math.random() * 3999999999));
// small valid PNG (8x8 red)
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQSACj/P8Fu7N9hAAAAAElFTkSuQmCC', 'base64');

async function api(path, { method = 'GET', body, token, form } = {}) {
  const headers = token ? { authorization: 'Bearer ' + token } : {};
  if (body) headers['content-type'] = 'application/json';
  const r = await fetch(B + path, { method, redirect: 'manual', headers, body: form || (body ? JSON.stringify(body) : undefined) });
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: r.status, json, headers: r.headers };
}
const photoForm = (buf, type = 'image/png', name = 'p.png') => { const f = new FormData(); f.append('photo', new Blob([buf], { type }), name); return f; };

async function signup(name, dob) {
  const phone = rnd();
  let r = await api('/api/auth/otp/request', { method: 'POST', body: { phone } });
  assert(r.json.devOtp, `${name}: OTP requested`);
  r = await api('/api/auth/otp/verify', { method: 'POST', body: { phone, code: '000000' } });
  assert(r.status === 400 && r.json.error === 'otp_invalid', `${name}: wrong OTP rejected`);
  r = await api('/api/auth/otp/request', { method: 'POST', body: { phone } });
  r = await api('/api/auth/otp/verify', { method: 'POST', body: { phone, code: r.json.devOtp } });
  assert(r.json.accessToken && r.json.user.status === 'CONSENT_PENDING', `${name}: logged in -> CONSENT_PENDING`);
  const t = r.json.accessToken, refresh = r.json.refreshToken;
  r = await api('/api/discover', { token: t });
  assert(r.status === 403, `${name}: discovery blocked before verification`);
  r = await api('/api/kyc/start', { method: 'POST', token: t });
  assert(r.json.error === 'consent_required', `${name}: KYC needs consent first`);
  await api('/api/kyc/consent', { method: 'POST', token: t, body: { accept: true } });
  r = await api('/api/kyc/start', { method: 'POST', token: t });
  const sid = r.json.sessionId;
  const forged = Buffer.from(JSON.stringify({ sessionId: sid, result: 'verified', ageEligible: true, birthYear: 1990, ts: Date.now() })).toString('base64url');
  r = await api(`/api/kyc/callback?payload=${forged}&sig=deadbeef`);
  assert(r.status === 400, `${name}: forged KYC callback rejected`);
  const f = await fetch(`${B}/mock-kyc/${sid}/submit`, {
    method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: `dob=${dob}&holder=on&action=verify`,
  });
  const cb = f.headers.get('location');
  const f2 = await fetch(B + cb, { redirect: 'manual' });
  const deep = f2.headers.get('location');
  assert(/^situationship:\/\/kyc\/return\?result=(verified|underage)$/.test(deep), `${name}: callback -> app deep link ${deep}`);
  const replay = await fetch(B + cb, { redirect: 'manual' });
  assert(replay.status === 400, `${name}: KYC result cannot be replayed`);
  r = await api('/api/kyc/status', { token: t });
  return { t, refresh, kyc: r.json };
}

async function activate(u, name, gender, interestedIn) {
  let r = await api('/api/me/profile', { method: 'PUT', token: u.t, body: { name, gender, interestedIn, bio: 'hi', city: 'Delhi' } });
  assert(r.json.user.status === 'PROFILE_INCOMPLETE', `${name}: incomplete until a photo is added`);
  r = await api('/api/me/photos', { method: 'POST', token: u.t, form: photoForm(Buffer.from('<script>alert(1)</script>')) });
  assert(r.status === 400 && r.json.error === 'invalid_image', `${name}: non-image upload rejected`);
  r = await api('/api/me/photos', { method: 'POST', token: u.t, form: photoForm(PNG) });
  assert(r.json.user.status === 'ACTIVE', `${name}: ACTIVE after photo`);
  return r.json.user;
}

const connect = (token) => new Promise((ok, bad) => {
  const s = io(B, { auth: { token }, transports: ['websocket'], reconnection: false });
  s.on('connect', () => ok(s)); s.on('connect_error', bad);
});

(async () => {
  assert((await api('/api/health')).json.ok, 'health');

  // Underage
  const kid = await signup('Minor', `${new Date().getFullYear() - 16}-06-01`);
  assert(kid.kyc.ageEligible === false && kid.kyc.status === 'IDENTITY_PENDING', 'underage: not eligible');
  let r = await api('/api/me/profile', { method: 'PUT', token: kid.t, body: { name: 'Kid', gender: 'male', interestedIn: 'female' } });
  assert(r.status === 403, 'underage cannot create a profile');

  const A = await signup(RAHUL, '1998-05-10');
  const Bu = await signup(PRIYA, '1999-07-20');
  const C = await signup(NEHA, '2000-02-02');
  assert(A.kyc.profileSetupRequired, 'Rahul: PROFILE_INCOMPLETE after KYC');
  const aUser = await activate(A, RAHUL, 'male', 'female');
  const photo = aUser.photos[0];
  const img = await fetch(photo);
  assert(img.status === 200 && img.headers.get('content-type') === 'image/jpeg', 'photo served via signed URL (re-encoded JPEG)');
  if (photo.includes('/media/')) assert((await fetch(photo.replace(/sig=\w+/, 'sig=00'))).status === 403, 'tampered photo URL rejected');
  await activate(Bu, PRIYA, 'female', 'male');
  await activate(C, NEHA, 'female', 'male');

  // Discovery rules
  r = await api('/api/discover?limit=20', { token: A.t });
  const names = r.json.profiles.map((p) => p.name);
  assert(r.json.profiles.every((p) => p.gender === 'female'), `Rahul sees only women (${names.join(', ')})`);
  assert(names.includes(PRIYA) && names.includes(NEHA), 'real verified users discoverable');
  assert(!r.json.profiles.some((p) => p.phone), 'no phone numbers leaked in discovery');
  const priyaId = r.json.profiles.find((p) => p.name === PRIYA).id;
  const nehaId = r.json.profiles.find((p) => p.name === NEHA).id;
  const aanya = r.json.profiles.find((p) => p.name === 'Aanya');
  r = await api('/api/discover?limit=20', { token: Bu.t });
  assert(r.json.profiles.every((p) => p.gender === 'male'), 'Priya sees only men');
  const rahulId = r.json.profiles.find((p) => p.name === RAHUL).id;
  r = await api('/api/swipes', { method: 'POST', token: Bu.t, body: { targetId: nehaId, action: 'like' } });
  assert(r.status === 403, 'cannot swipe someone outside your preferences');

  const sa = await connect(A.t), sb = await connect(Bu.t);
  assert(true, 'sockets connected (JWT auth)');
  try { await connect(kid.t); assert(false, 'x'); } catch (e) { assert(e.message === 'not_eligible', 'ineligible user cannot open a socket'); }
  const ev = { a: [], b: [] };
  sa.onAny((e, d) => ev.a.push([e, d])); sb.onAny((e, d) => ev.b.push([e, d]));

  // Demo like-back
  await api('/api/swipes', { method: 'POST', token: A.t, body: { targetId: aanya.id, action: 'like' } });
  await sleep(1700);
  assert(ev.a.some(([e, d]) => e === 'match:new' && d.user.name === 'Aanya'), 'demo profile liked back -> match:new');

  // Concurrent mutual likes: exactly one match
  const [x, y] = await Promise.all([
    api('/api/swipes', { method: 'POST', token: A.t, body: { targetId: priyaId, action: 'like' } }),
    api('/api/swipes', { method: 'POST', token: Bu.t, body: { targetId: rahulId, action: 'like' } }),
  ]);
  const winners = [x, y].filter((q) => q.json.matched);
  assert(winners.length >= 1, 'simultaneous mutual likes -> matched');
  const matchId = winners[0].json.matchId;
  assert(winners.every((w) => w.json.matchId === matchId), 'both requests see the same single match');
  r = await api('/api/swipes', { method: 'POST', token: Bu.t, body: { targetId: rahulId, action: 'like' } });
  assert(r.json.matchId === matchId, 'duplicate swipe is idempotent');
  r = await api('/api/matches', { token: A.t });
  assert(r.json.matches.filter((m) => m.user.name === PRIYA).length === 1, 'exactly one Priya match in list');

  // Likes tab
  await api('/api/swipes', { method: 'POST', token: C.t, body: { targetId: rahulId, action: 'like' } });
  r = await api('/api/likes', { token: A.t });
  assert(r.json.profiles.some((p) => p.name === NEHA), 'Rahul sees Neha in Likes');

  // Chat
  const ack = await sa.emitWithAck('message:send', { matchId, clientMessageId: 'c1', text: 'Hello Priya!' });
  assert(ack.ok && ack.message.deliveredAt, 'message accepted + delivered (recipient online)');
  const ack2 = await sa.emitWithAck('message:send', { matchId, clientMessageId: 'c1', text: 'Hello Priya!' });
  assert(ack2.message.id === ack.message.id, 'retry with same clientMessageId is not duplicated');
  await sleep(200);
  assert(ev.b.some(([e, d]) => e === 'message:new' && d.text === 'Hello Priya!'), 'Priya received message in real time');
  sb.emit('typing', { matchId, isTyping: true });
  await sleep(200);
  assert(ev.a.some(([e, d]) => e === 'typing' && d.isTyping), 'typing indicator relayed');
  sb.emit('message:read', { matchId });
  await sleep(250);
  assert(ev.a.some(([e]) => e === 'message:read'), 'read receipt delivered');
  const bad = await sa.emitWithAck('message:send', { matchId: '00000000-0000-0000-0000-000000000000', clientMessageId: 'c2', text: 'x' });
  assert(bad.error === 'match_unavailable', 'cannot message without a match');
  r = await api(`/api/matches/${matchId}/messages`, { token: Bu.t });
  assert(r.json.messages.length === 1 && r.json.messages[0].readAt, 'history persisted with read state');

  // Offline delivery + FCM queue
  r = await api('/api/me/devices', { method: 'POST', token: Bu.t, body: { token: 'fcm-test-token-0123456789abcdef', platform: 'android' } });
  assert(r.json.ok, 'FCM device token registered');
  sb.disconnect();
  await sleep(300);
  const off = await sa.emitWithAck('message:send', { matchId, clientMessageId: 'c3', text: 'You there?' });
  assert(off.ok && !off.message.deliveredAt, 'offline recipient -> stored, not yet delivered');
  await sleep(500);
  const sb2 = await connect(Bu.t);
  ev.b2 = []; sb2.onAny((e, d) => ev.b2.push([e, d]));
  await sleep(400);
  assert(ev.a.some(([e, d]) => e === 'message:status' && d.id === off.message.id && d.deliveredAt), 'delivered receipt sent when recipient reconnects');

  // Calls
  const inv = await sa.emitWithAck('call:invite', { matchId, kind: 'video' });
  assert(inv.ok && inv.iceServers, 'call invite ok (ICE servers returned)');
  const busy = await sb2.emitWithAck('call:invite', { matchId, kind: 'audio' });
  assert(busy.error === 'busy', 'second call while ringing -> busy');
  await sleep(200);
  const inc = ev.b2.find(([e]) => e === 'call:incoming');
  assert(inc && inc[1].from.name === RAHUL && inc[1].kind === 'video', 'callee gets incoming call');
  const acc = await sb2.emitWithAck('call:accept', { callId: inv.callId });
  assert(acc.ok, 'call accepted');
  sa.emit('call:offer', { callId: inv.callId, sdp: { type: 'offer', sdp: 'v=0' } });
  sb2.emit('call:answer', { callId: inv.callId, sdp: { type: 'answer', sdp: 'v=0' } });
  sa.emit('call:ice', { callId: inv.callId, candidate: { candidate: 'c', sdpMid: '0', sdpMLineIndex: 0 } });
  await sleep(300);
  assert(ev.b2.some(([e]) => e === 'call:offer') && ev.a.some(([e]) => e === 'call:answer') && ev.b2.some(([e]) => e === 'call:ice'), 'SDP offer/answer/ICE relayed');
  sa.emit('call:end', { callId: inv.callId });
  await sleep(300);
  assert(ev.b2.some(([e, d]) => e === 'call:ended' && d.callId === inv.callId), 'call ended on both sides');

  // Call to an offline user: rings via push, re-delivered when their app connects
  sb2.disconnect();
  await sleep(300);
  const inv2 = await sa.emitWithAck('call:invite', { matchId, kind: 'audio' });
  assert(inv2.ok, 'calling offline user still rings (push sent)');
  const sb3 = await connect(Bu.t);
  const got = await new Promise((ok) => { sb3.on('call:incoming', ok); setTimeout(() => ok(null), 1500); });
  assert(got && got.callId === inv2.callId, 'ringing call re-delivered when app opens from push');
  sb3.emit('call:reject', { callId: inv2.callId });
  await sleep(300);
  assert(ev.a.some(([e, d]) => e === 'call:ended' && d.callId === inv2.callId && d.reason === 'rejected'), 'declined call -> caller notified');

  // Unanswered call -> BullMQ delayed job ends it after 35s (SLOW=1 to run)
  if (process.env.SLOW) {
    const inv3 = await sa.emitWithAck('call:invite', { matchId, kind: 'video' });
    const ended = await new Promise((ok) => {
      sa.on('call:ended', (d) => d.callId === inv3.callId && ok(d));
      setTimeout(() => ok(null), 40_000);
    });
    assert(ended && ended.reason === 'no_answer', 'unanswered call times out via BullMQ delayed job');
  }

  // Block
  await api('/api/blocks', { method: 'POST', token: Bu.t, body: { userId: rahulId } });
  await sleep(300);
  assert(ev.a.some(([e]) => e === 'match:removed'), 'block removes match in real time');
  const blocked = await sa.emitWithAck('message:send', { matchId, clientMessageId: 'c9', text: 'hey?' });
  assert(blocked.error === 'match_unavailable', 'blocked user cannot message');
  r = await api('/api/discover?limit=20', { token: A.t });
  assert(!r.json.profiles.some((p) => p.name === PRIYA), 'blocker hidden from discovery');
  r = await api('/api/me/blocked', { token: Bu.t });
  assert(r.json.users.some((u) => u.name === RAHUL), 'blocked list');
  r = await api('/api/reports', { method: 'POST', token: Bu.t, body: { userId: rahulId, reason: 'harassment', details: 'test' } });
  assert(r.json.ok, 'report submitted');

  // Tokens
  r = await api('/api/auth/refresh', { method: 'POST', body: { refreshToken: A.refresh } });
  assert(r.json.accessToken && r.json.refreshToken !== A.refresh, 'refresh rotates tokens');
  r = await api('/api/auth/refresh', { method: 'POST', body: { refreshToken: A.refresh } });
  assert(r.status === 401, 'used refresh token cannot be reused');

  // Account deletion
  r = await api('/api/me', { method: 'DELETE', token: Bu.t });
  assert(r.json.ok, 'account deleted');
  assert((await api('/api/me', { token: Bu.t })).status === 401, 'deleted account token invalid');
  assert((await fetch(photo)).status === 200, 'other users\' photos unaffected');

  [sa, sb3].forEach((s) => s.close());
  console.log(`\nALL ${passed} E2E CHECKS PASSED`);
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
