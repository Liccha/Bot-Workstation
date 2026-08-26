const crypto = require('node:crypto');
const { getStore } = require('./_lib/storage');
const security = require('./_lib/security');
const emergency = require('./_lib/emergency-lock');
const repo = require('./_lib/repository');

const DEVICES_KEY = 'security/mobile-devices.json';
const INBOX_PREFIX = 'mobile-relay/inboxes/';
const CLAIM_PREFIX = 'mobile-relay/claims/';
const REQUEST_TTL_MS = 30 * 60 * 1000;
const RESPONSE_TTL_MS = 60 * 60 * 1000;
const CLAIM_MS = 15 * 60 * 1000;
const MAX_DEVICES = 50;
const DEVICE_SLOTS = 8;

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

function slotKey(deviceId, index) { return `${INBOX_PREFIX}${deviceId}/${index}.json`; }
function claimKey(id) { return `${CLAIM_PREFIX}${id}.json`; }
function objectExists(error) {
  return Number(error?.status || error?.statusCode || 0) === 409
    || ['FileAlreadyExists', 'ObjectAlreadyExists'].includes(String(error?.code || ''));
}
async function acquireClaim(id, inboxKey) {
  const key = claimKey(id);
  for (let attempt = 0; attempt < 2; attempt++) {
    const token = crypto.randomUUID();
    const expiresAt = new Date(Date.now() + CLAIM_MS).toISOString();
    try {
      await getStore().put(key, Buffer.from(JSON.stringify({ token, expiresAt, inboxKey })), { forbidOverwrite: true });
      return { token, expiresAt };
    } catch (error) {
      if (!objectExists(error)) throw error;
      const existing = await readJson(key, null);
      if (existing && Date.parse(existing.expiresAt || 0) > Date.now()) return null;
      await getStore().delete(key).catch(() => {});
    }
  }
  return null;
}
async function readSlots(deviceId) {
  return Promise.all(Array.from({ length: DEVICE_SLOTS }, async (_, index) => {
    const key = slotKey(deviceId, index);
    return { key, item: await readJson(key, null) };
  }));
}
function expired(item) {
  return !item || Date.parse(item.expiresAt || 0) <= Date.now();
}
async function placeInSlot(item) {
  for (let index = 0; index < DEVICE_SLOTS; index++) {
    const key = slotKey(item.deviceId, index);
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        await getStore().put(key, Buffer.from(JSON.stringify(item)), { forbidOverwrite: true });
        return true;
      } catch (error) {
        if (!objectExists(error)) throw error;
        const existing = await readJson(key, null);
        if (!expired(existing)) break;
        await getStore().delete(key).catch(() => {});
      }
    }
  }
  return false;
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
      if (!(await placeInSlot(item))) throw rateLimited();
      return json(res, 202, { id: item.id, state: item.state });
    }

    if (action === 'result' && req.method === 'GET') {
      const device = await deviceFromRequest(req); if (!device) throw unauthorized();
      const id = safeId(query(req, 'id')); if (!id) throw badRequest();
      const record = (await readSlots(device.id)).map(slot => slot.item).find(item => item?.id === id);
      if (!record || expired(record)) throw notFound();
      return json(res, record.state === 'complete' ? 200 : 202,
        record.state === 'complete' ? { id, state: 'complete', response: record.response } : { id, state: 'pending' });
    }

    if (action === 'desktop-poll' && req.method === 'GET') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const claimed = [];
      const devices = (await readDevices()).devices.filter(device => device.status !== 'revoked');
      const slots = (await Promise.all(devices.map(device => readSlots(device.id)))).flat();
      for (const { key, item } of slots) {
        if (claimed.length >= 5) break;
        if (!item || !safeId(item.id)) continue;
        if (expired(item)) {
          await Promise.all([getStore().delete(key).catch(() => {}), getStore().delete(claimKey(item.id)).catch(() => {})]);
          continue;
        }
        if (item.state === 'complete') continue;
        const claim = await acquireClaim(item.id, key);
        if (!claim) continue;
        const copy = { ...JSON.parse(JSON.stringify(item)), state: 'claimed', claimToken: claim.token, claimExpiresAt: claim.expiresAt };
        if (copy.payload?.path === '/api/song-asset' && copy.payload.body?.key) {
          copy.payload.body.downloadUrl = await getStore().signedGetUrl(copy.payload.body.key);
        }
        claimed.push(copy);
      }
      return json(res, 200, { items: claimed });
    }

    if (action === 'desktop-complete' && req.method === 'POST') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const input = body(req); const id = safeId(input.id); const claimToken = safeId(input.claimToken);
      if (!id || !claimToken) throw badRequest();
      const claim = await readJson(claimKey(id), null);
      if (!claim || claim.token !== claimToken || !String(claim.inboxKey || '').startsWith(INBOX_PREFIX)) throw conflict();
      const item = await readJson(claim.inboxKey, null);
      if (!item || item.id !== id) throw conflict();
      const completedAssetKey = item.payload?.path === '/api/song-asset' ? String(item.payload.body?.key || '') : '';
      const completedAt = now();
      const completed = {
        id, deviceId: item.deviceId, state: 'complete', completedAt,
        expiresAt: new Date(Date.now() + RESPONSE_TTL_MS).toISOString(),
        response: { status: Math.max(100, Math.min(599, Number(input.status || 500))), body: input.body && typeof input.body === 'object' ? input.body : {} }
      };
      await getStore().put(claim.inboxKey, Buffer.from(JSON.stringify(completed)));
      await getStore().delete(claimKey(id)).catch(() => {});
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
