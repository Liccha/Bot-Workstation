const crypto = require('node:crypto');
const { config } = require('./config');
const { getStore } = require('./storage');
const { clientIp } = require('./security');

const CURRENT_KEY = 'announcements/site-likes.json';
const LOCK_KEY = 'locks/site-likes.json';
const MAX_LIKES_PER_VISITOR_PER_DAY = 500;

function now() { return new Date().toISOString(); }
function shanghaiDay() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(new Date());
  const value = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}`;
}
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
function visitorKey(req) {
  const device = String(req.headers['x-like-device'] || '').trim();
  const identity = /^[A-Za-z0-9_-]{16,100}$/.test(device)
    ? `device:${device}`
    : `ip:${clientIp(req)}`;
  return crypto.createHmac('sha256', config().sessionSecret)
    .update(`song-like:${identity}`)
    .digest('base64url');
}
function emptyDocument() {
  return { schema: 1, revision: 0, updatedAt: now(), counts: {}, days: {} };
}
function normalizeDocument(value) {
  const document = value && typeof value === 'object' ? value : emptyDocument();
  if (!document.counts || typeof document.counts !== 'object' || Array.isArray(document.counts)) document.counts = {};
  if (!document.days || typeof document.days !== 'object' || Array.isArray(document.days)) document.days = {};
  document.schema = 1;
  document.revision = Number(document.revision || 0);
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
  document.updatedAt = now();
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
      try {
        const current = await store.get(LOCK_KEY);
        const lock = current ? JSON.parse(current.body.toString('utf8')) : null;
        if (!lock || String(lock.expiresAt || '') < now()) await store.delete(LOCK_KEY);
      } catch (readError) {
        if (!(readError.status === 404 || readError.code === 'NoSuchKey')) throw readError;
      }
      if (!acquired) await sleep(120 + Math.floor(Math.random() * 100));
    }
  }
  if (!acquired) { const error = new Error('like store is busy'); error.statusCode = 503; throw error; }
  try {
    return await operation();
  } finally {
    try {
      const current = await store.get(LOCK_KEY);
      const lock = current ? JSON.parse(current.body.toString('utf8')) : null;
      if (lock && lock.token === token) await store.delete(LOCK_KEY);
    } catch (_) {}
  }
}
function likedFor(document, day, visitor) {
  const values = document.days?.[day]?.[visitor];
  return Array.isArray(values) ? values.filter(Number.isInteger) : [];
}
async function meta(req) {
  const document = await readDocument();
  const day = shanghaiDay();
  return { likes: document.counts, likedToday: likedFor(document, day, visitorKey(req)) };
}
async function setLike(req, songId, shouldLike) {
  return withLock(async () => {
    const document = await readDocument();
    const day = shanghaiDay();
    const visitor = visitorKey(req);

    // Only today's per-visitor state is needed. Aggregate counts remain permanent.
    document.days = document.days?.[day] ? { [day]: document.days[day] } : { [day]: {} };
    const visitors = document.days[day];
    const songs = new Set(likedFor(document, day, visitor));
    const hadLike = songs.has(songId);
    if (shouldLike && !hadLike) {
      if (songs.size >= MAX_LIKES_PER_VISITOR_PER_DAY) {
        const error = new Error('daily like limit reached'); error.statusCode = 429; throw error;
      }
      songs.add(songId);
      document.counts[String(songId)] = Number(document.counts[String(songId)] || 0) + 1;
    } else if (!shouldLike && hadLike) {
      songs.delete(songId);
      document.counts[String(songId)] = Math.max(0, Number(document.counts[String(songId)] || 0) - 1);
    }
    if (songs.size) visitors[visitor] = Array.from(songs).sort((a, b) => a - b);
    else delete visitors[visitor];
    if (shouldLike !== hadLike) await writeDocument(document);
    return { id: songId, count: Number(document.counts[String(songId)] || 0), liked: shouldLike };
  });
}

module.exports = { CURRENT_KEY, meta, setLike, readDocument };
