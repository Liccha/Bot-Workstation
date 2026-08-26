const crypto = require('node:crypto');
const { config } = require('./config');
const { getStore } = require('./storage');
const { clientIp } = require('./security');

const CURRENT_KEY = 'announcements/site-visits.json';
const LOCK_KEY = 'locks/site-visits.json';
const MIN_VISIT_INTERVAL_MS = 60_000;
const MAX_VISITS_PER_VISITOR_PER_DAY = 60;
const MAX_RECORDED_VISITS_PER_HOUR = 5_000;
const MAX_RECORDED_VISITS_PER_DAY = 20_000;
const warmRateLimit = new Map();

function isoNow() { return new Date().toISOString(); }
function shanghaiParts(date = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23'
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  const day = `${values.year}-${values.month}-${values.day}`;
  return { day, hour: `${day}T${values.hour}` };
}
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
function visitorKey(req) {
  const device = String(req.headers['x-visit-device'] || '').trim();
  const identity = /^[A-Za-z0-9_-]{16,100}$/.test(device)
    ? `device:${device}`
    : `ip:${clientIp(req)}`;
  return crypto.createHmac('sha256', config().sessionSecret)
    .update(`site-visit:${identity}`)
    .digest('base64url');
}
function emptyDocument() {
  return { schema: 1, revision: 0, updatedAt: isoNow(), total: 0, days: {}, hours: {}, activeDay: '' };
}
function normalizeDocument(value) {
  const document = value && typeof value === 'object' ? value : emptyDocument();
  document.schema = 1;
  document.revision = Number(document.revision || 0);
  document.total = Number(document.total || 0);
  if (!document.days || typeof document.days !== 'object' || Array.isArray(document.days)) document.days = {};
  if (!document.hours || typeof document.hours !== 'object' || Array.isArray(document.hours)) document.hours = {};
  return document;
}
async function readDocument() {
  const object = await getStore().get(CURRENT_KEY);
  if (!object) return emptyDocument();
  return normalizeDocument(JSON.parse(object.body.toString('utf8')));
}
async function writeDocument(document) {
  document.schema = 1;
  document.revision = Number(document.revision || 0) + 1;
  document.updatedAt = isoNow();
  await getStore().put(CURRENT_KEY, Buffer.from(JSON.stringify(document)));
}
async function withLock(operation) {
  const store = getStore();
  const token = crypto.randomUUID();
  let acquired = false;
  for (let attempt = 0; attempt < 20 && !acquired; attempt++) {
    try {
      await store.put(LOCK_KEY, Buffer.from(JSON.stringify({ token, expiresAt: new Date(Date.now() + 30_000).toISOString() })), { forbidOverwrite: true });
      acquired = true;
    } catch (error) {
      if (!(error.code === 'FileAlreadyExists' || error.code === 'ObjectAlreadyExists' || error.status === 409)) throw error;
      const current = await store.get(LOCK_KEY).catch(() => null);
      const lock = current ? JSON.parse(current.body.toString('utf8')) : null;
      if (!lock || String(lock.expiresAt || '') < isoNow()) await store.delete(LOCK_KEY).catch(() => {});
      if (!acquired) await sleep(120 + Math.floor(Math.random() * 100));
    }
  }
  if (!acquired) { const error = new Error('visit store is busy'); error.statusCode = 503; throw error; }
  try { return await operation(); }
  finally {
    const current = await store.get(LOCK_KEY).catch(() => null);
    const lock = current ? JSON.parse(current.body.toString('utf8')) : null;
    if (lock && lock.token === token) await store.delete(LOCK_KEY).catch(() => {});
  }
}
function warmLimited(visitor, nowMs) {
  const last = Number(warmRateLimit.get(visitor) || 0);
  warmRateLimit.set(visitor, nowMs);
  if (warmRateLimit.size > 5_000) {
    for (const [key, value] of warmRateLimit) if (nowMs - value > 3_600_000) warmRateLimit.delete(key);
  }
  return nowMs - last < MIN_VISIT_INTERVAL_MS;
}
async function record(req) {
  const nowMs = Date.now();
  const visitor = visitorKey(req);
  if (warmLimited(visitor, nowMs)) return { ok: true, recorded: false };
  return withLock(async () => {
    const document = await readDocument();
    const { day, hour } = shanghaiParts(new Date(nowMs));
    const dayData = document.days[day] && typeof document.days[day] === 'object'
      ? document.days[day]
      : { views: 0, unique: 0, visitors: {} };
    if (!dayData.visitors || typeof dayData.visitors !== 'object') dayData.visitors = {};
    const previous = dayData.visitors[visitor] || { count: 0, lastAt: 0 };
    const hourCount = Number(document.hours[hour] || 0);
    if (nowMs - Number(previous.lastAt || 0) < MIN_VISIT_INTERVAL_MS
        || Number(previous.count || 0) >= MAX_VISITS_PER_VISITOR_PER_DAY
        || Number(dayData.views || 0) >= MAX_RECORDED_VISITS_PER_DAY
        || hourCount >= MAX_RECORDED_VISITS_PER_HOUR) {
      return { ok: true, recorded: false };
    }
    if (!previous.count) dayData.unique = Number(dayData.unique || 0) + 1;
    previous.count = Number(previous.count || 0) + 1;
    previous.lastAt = nowMs;
    dayData.visitors[visitor] = previous;
    dayData.views = Number(dayData.views || 0) + 1;
    document.total += 1;
    document.days[day] = dayData;
    document.hours = { [hour]: hourCount + 1 };
    document.activeDay = day;
    // Keep aggregate history, but retain pseudonymous visitor hashes for today only.
    for (const [storedDay, stored] of Object.entries(document.days)) {
      if (storedDay !== day && stored && typeof stored === 'object') delete stored.visitors;
    }
    await writeDocument(document);
    return { ok: true, recorded: true };
  });
}

module.exports = { CURRENT_KEY, record, readDocument, visitorKey };
