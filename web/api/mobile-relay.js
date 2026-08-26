const crypto = require('node:crypto');
const { getStore } = require('./_lib/storage');
const security = require('./_lib/security');
const emergency = require('./_lib/emergency-lock');
const repo = require('./_lib/repository');

const DEVICES_KEY = 'security/mobile-devices.json';
const QUEUE_KEY = 'mobile-relay/queue.json';
const REQUEST_TTL_MS = 30 * 60 * 1000;
const RESPONSE_TTL_MS = 60 * 60 * 1000;
const CLAIM_MS = 15 * 60 * 1000;
const MAX_DEVICES = 50;
const MAX_QUEUE = 500;

function json(res, status, value) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.end(JSON.stringify(value));
}

function body(req) {
  const value = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw badRequest();
  return value;
}

function query(req, key) {
  const value = req.query?.[key];
  return Array.isArray(value) ? value[0] : value;
}

function now() { return new Date().toISOString(); }
function badRequest(message = 'invalid request') { const error = new Error(message); error.statusCode = 400; return error; }
function unauthorized() { const error = new Error('not authorized'); error.statusCode = 401; return error; }
function notFound() { const error = new Error('not found'); error.statusCode = 404; return error; }
function conflict(message = 'conflict') { const error = new Error(message); error.statusCode = 409; return error; }
function rateLimited() { const error = new Error('rate limited'); error.statusCode = 429; return error; }
function hashSecret(secret) { return crypto.createHash('sha256').update(String(secret || '')).digest('hex'); }
function safeId(value) { return /^[a-f0-9-]{36}$/.test(String(value || '')) ? String(value) : ''; }

async function readJson(key, fallback) {
  const object = await getStore().get(key);
  if (!object) return fallback;
  const value = JSON.parse(object.body.toString('utf8'));
  return value && typeof value === 'object' ? value : fallback;
}

async function readDevices() {
  const value = await readJson(DEVICES_KEY, { schema: 1, devices: [] });
  return { schema: 1, devices: Array.isArray(value.devices) ? value.devices : [] };
}

async function readQueue() {
  const value = await readJson(QUEUE_KEY, { schema: 1, items: [] });
  return { schema: 1, items: Array.isArray(value.items) ? value.items : [] };
}

function cleanQueue(document) {
  const time = Date.now();
  document.items = document.items.filter(item => {
    if (item.state === 'complete') return Date.parse(item.completedAt || 0) + RESPONSE_TTL_MS > time;
    return Date.parse(item.expiresAt || 0) > time;
  });
  if (document.items.length > MAX_QUEUE) document.items = document.items.slice(-MAX_QUEUE);
  return document;
}

async function deviceFromRequest(req) {
  const header = String(req.headers.authorization || '');
  if (!header.startsWith('Device ')) return null;
  const token = header.slice(7).trim();
  const split = token.indexOf('.');
  if (split < 1) return null;
  const id = safeId(token.slice(0, split));
  const secret = token.slice(split + 1);
  if (!id || !/^[A-Za-z0-9_-]{32,128}$/.test(secret)) return null;
  const document = await readDevices();
  const device = document.devices.find(item => item.id === id && item.status !== 'revoked');
  if (!device || !security.safeEqual(device.secretHash, hashSecret(secret))) return null;
  return device;
}

function cleanName(value) {
  const text = String(value || '手机设备').normalize('NFC').replace(/[\x00-\x1f\x7f]/g, '').trim();
  return Array.from(text || '手机设备').slice(0, 48).join('');
}

function cleanRelayRequest(input) {
  const method = String(input.method || '').toUpperCase();
  const path = String(input.path || '');
  const queryValue = input.query && typeof input.query === 'object' && !Array.isArray(input.query) ? input.query : {};
  const requestBody = input.body && typeof input.body === 'object' && !Array.isArray(input.body) ? input.body : {};
  const allowed = new Map([
    ['GET /api/status', true], ['GET /api/update', true], ['GET /api/songs', true],
    ['GET /api/stable', true], ['POST /api/song', true], ['POST /api/stable', true],
    ['POST /api/action', true], ['POST /api/song-asset', true]
  ]);
  if (!allowed.has(`${method} ${path}`)) throw badRequest();
  if (path === '/api/action' && !['songbot.start', 'songbot.stop', 'napcat.start', 'napcat.stop', 'update.install']
    .includes(String(requestBody.action || ''))) throw badRequest();
  const cleanQuery = {};
  for (const key of ['q', 'offset', 'limit']) if (queryValue[key] != null) cleanQuery[key] = String(queryValue[key]).slice(0, key === 'q' ? 160 : 12);
  return { method, path, query: cleanQuery, body: requestBody };
}

function publicDevice(item) {
  return { id: item.id, name: item.name, status: item.status || 'active', createdAt: item.createdAt, revokedAt: item.revokedAt || null };
}

module.exports = async function handler(req, res) {
  const action = String(query(req, 'action') || 'health');
  try {
    if (action === 'health' && req.method === 'GET') {
      const policy = await emergency.state();
      return json(res, 200, { ok: true, writeLocked: policy.locked });
    }

    const desktop = security.desktopAuthorized(req);
    if (action === 'register-device' && req.method === 'POST') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const input = body(req);
      const id = crypto.randomUUID();
      const secret = crypto.randomBytes(32).toString('base64url');
      await repo.withLock('mobile-devices', async () => {
        const document = await readDevices();
        if (document.devices.filter(item => item.status !== 'revoked').length >= MAX_DEVICES) throw conflict('device limit reached');
        document.devices.push({ id, name: cleanName(input.name), secretHash: hashSecret(secret), status: 'active', createdAt: now() });
        await getStore().put(DEVICES_KEY, Buffer.from(JSON.stringify({ ...document, updatedAt: now() })));
      });
      await repo.writeAudit({ event: 'MOBILE_DEVICE_REGISTERED', actor: { kind: 'workstation' }, device: id });
      return json(res, 201, { id, token: `${id}.${secret}`, name: cleanName(input.name) });
    }

    if (action === 'devices' && req.method === 'GET') {
      if (!desktop) throw unauthorized();
      const document = await readDevices();
      return json(res, 200, { items: document.devices.map(publicDevice) });
    }

    if (action === 'revoke-device' && req.method === 'POST') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const id = safeId(body(req).id); if (!id) throw badRequest();
      await repo.withLock('mobile-devices', async () => {
        const document = await readDevices();
        const item = document.devices.find(candidate => candidate.id === id);
        if (!item) throw notFound();
        item.status = 'revoked'; item.revokedAt = now();
        await getStore().put(DEVICES_KEY, Buffer.from(JSON.stringify({ ...document, updatedAt: now() })));
      });
      await repo.writeAudit({ event: 'MOBILE_DEVICE_REVOKED', actor: { kind: 'workstation' }, device: id });
      return json(res, 200, { ok: true });
    }

    if (action === 'asset-ticket' && req.method === 'POST') {
      const device = await deviceFromRequest(req); if (!device) throw unauthorized();
      await emergency.assertWriteAllowed();
      const input = body(req); const type = input.type === 'image' ? 'image' : input.type === 'audio' ? 'audio' : '';
      const size = Number(input.size || 0); const limit = type === 'image' ? 20 * 1024 * 1024 : 100 * 1024 * 1024;
      const extension = String(input.extension || '').toLowerCase();
      const allowedExtensions = type === 'image' ? ['.jpg', '.jpeg', '.png', '.webp'] : ['.mp3', '.wav', '.flac', '.m4a', '.ogg'];
      if (!type || !Number.isSafeInteger(size) || size < 1 || size > limit || !allowedExtensions.includes(extension)) throw badRequest();
      await repo.withLock('mobile-devices', async () => {
        const document = await readDevices(); const stored = document.devices.find(item => item.id === device.id && item.status !== 'revoked');
        if (!stored) throw unauthorized();
        const day = now().slice(0, 10); const last = Date.parse(stored.lastAssetTicketAt || 0);
        if (Number.isFinite(last) && Date.now() - last < 5000) throw rateLimited();
        if (stored.assetTicketDay !== day) { stored.assetTicketDay = day; stored.assetTicketCount = 0; }
        if (Number(stored.assetTicketCount || 0) >= 30) throw rateLimited();
        stored.assetTicketCount = Number(stored.assetTicketCount || 0) + 1; stored.lastAssetTicketAt = now();
        await getStore().put(DEVICES_KEY, Buffer.from(JSON.stringify({ ...document, updatedAt: now() })));
      });
      const key = `mobile-assets/${device.id}/${crypto.randomUUID()}${extension}`;
      const uploadUrl = await getStore().signedPutUrl(key, String(input.contentType || 'application/octet-stream'));
      return json(res, 200, { key, uploadUrl, method: 'PUT', headers: { 'Content-Type': String(input.contentType || 'application/octet-stream') } });
    }

    if (action === 'submit' && req.method === 'POST') {
      const device = await deviceFromRequest(req); if (!device) throw unauthorized();
      await emergency.assertWriteAllowed();
      const payload = cleanRelayRequest(body(req));
      if (payload.path === '/api/song-asset') {
        const key = String(payload.body.key || '');
        if (!key.startsWith(`mobile-assets/${device.id}/`)) throw badRequest();
      }
      const item = { id: crypto.randomUUID(), deviceId: device.id, createdAt: now(),
        expiresAt: new Date(Date.now() + REQUEST_TTL_MS).toISOString(), state: 'pending', payload };
      await repo.withLock('mobile-relay-queue', async () => {
        const document = cleanQueue(await readQueue());
        document.items.push(item);
        await getStore().put(QUEUE_KEY, Buffer.from(JSON.stringify({ ...document, updatedAt: now() })));
      });
      return json(res, 202, { id: item.id, state: item.state });
    }

    if (action === 'result' && req.method === 'GET') {
      const device = await deviceFromRequest(req); if (!device) throw unauthorized();
      const id = safeId(query(req, 'id')); if (!id) throw badRequest();
      const document = cleanQueue(await readQueue());
      const item = document.items.find(candidate => candidate.id === id && candidate.deviceId === device.id);
      if (!item) throw notFound();
      return json(res, item.state === 'complete' ? 200 : 202,
        item.state === 'complete' ? { id, state: item.state, response: item.response } : { id, state: item.state });
    }

    if (action === 'desktop-poll' && req.method === 'GET') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const claimed = [];
      await repo.withLock('mobile-relay-queue', async () => {
        const document = cleanQueue(await readQueue()); const time = Date.now();
        for (const item of document.items) {
          if (claimed.length >= 5) break;
          if (item.state === 'claimed' && Date.parse(item.claimExpiresAt || 0) > time) continue;
          if (item.state !== 'pending' && item.state !== 'claimed') continue;
          item.state = 'claimed'; item.claimToken = crypto.randomUUID(); item.claimExpiresAt = new Date(time + CLAIM_MS).toISOString();
          const copy = JSON.parse(JSON.stringify(item));
          if (copy.payload?.path === '/api/song-asset' && copy.payload.body?.key) {
            copy.payload.body.downloadUrl = await getStore().signedGetUrl(copy.payload.body.key);
          }
          claimed.push(copy);
        }
        await getStore().put(QUEUE_KEY, Buffer.from(JSON.stringify({ ...document, updatedAt: now() })));
      });
      return json(res, 200, { items: claimed });
    }

    if (action === 'desktop-complete' && req.method === 'POST') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const input = body(req); const id = safeId(input.id); const claimToken = safeId(input.claimToken);
      if (!id || !claimToken) throw badRequest();
      let completedAssetKey = '';
      await repo.withLock('mobile-relay-queue', async () => {
        const document = cleanQueue(await readQueue());
        const item = document.items.find(candidate => candidate.id === id);
        if (!item || item.state !== 'claimed' || item.claimToken !== claimToken) throw conflict();
        if (item.payload?.path === '/api/song-asset') completedAssetKey = String(item.payload.body?.key || '');
        item.state = 'complete'; item.completedAt = now();
        item.response = { status: Math.max(100, Math.min(599, Number(input.status || 500))), body: input.body && typeof input.body === 'object' ? input.body : {} };
        delete item.claimToken; delete item.claimExpiresAt; delete item.payload;
        await getStore().put(QUEUE_KEY, Buffer.from(JSON.stringify({ ...document, updatedAt: now() })));
      });
      if (completedAssetKey.startsWith('mobile-assets/')) await getStore().delete(completedAssetKey).catch(() => {});
      return json(res, 200, { ok: true });
    }

    return json(res, 405, { error: 'unsupported action or method' });
  } catch (error) {
    const status = Number(error.statusCode || 500);
    if (status >= 500) console.error('mobile-relay', action, { name: String(error.name || 'Error'), status });
    return json(res, status >= 400 && status < 600 ? status : 500, {
      error: status === 423 ? 'cloud writes temporarily locked'
        : status === 429 ? 'rate limited'
        : status === 401 || status === 403 ? 'not authorized'
          : status === 404 ? 'not found' : status === 409 ? 'conflict'
            : status === 400 ? 'invalid request' : 'internal'
    });
  }
};
