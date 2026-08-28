const crypto = require('node:crypto');
const { getStore } = require('./_lib/storage');
const security = require('./_lib/security');
const emergency = require('./_lib/emergency-lock');
const repo = require('./_lib/repository');
const mobileAuth = require('./_lib/mobile-auth');

const DEVICES_KEY = mobileAuth.DEVICES_KEY;
const INBOX_PREFIX = 'mobile-relay/inboxes/';
const CLAIM_PREFIX = 'mobile-relay/claims/';
const PENDING_KEY = 'mobile-relay/pending.json';
const PRESENCE_KEY = 'mobile-relay/desktop-presence.json';
const REQUEST_TTL_MS = 30 * 60 * 1000;
const RESPONSE_TTL_MS = 60 * 60 * 1000;
const CLAIM_MS = 15 * 60 * 1000;
const RESULT_REUSE_GRACE_MS = 30 * 1000;
const MAX_DEVICES = 50;
const DEVICE_SLOTS = 8;
const PRESENCE_TTL_MS = 75 * 1000;

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
const hashSecret = mobileAuth.hashSecret;
const safeId = mobileAuth.safeId;

async function readJson(key, fallback) {
  const object = await getStore().get(key);
  if (!object) return fallback;
  const value = JSON.parse(object.body.toString('utf8'));
  return value && typeof value === 'object' ? value : fallback;
}

async function readDevices() {
  return mobileAuth.readDevices();
}

function cleanPending(document) {
  const items = Array.isArray(document?.items) ? document.items : [];
  return {
    schema: 1,
    initialized: document?.initialized === true,
    items: items.filter(item => safeId(item?.id) && safeId(item?.deviceId)
      && String(item?.inboxKey || '').startsWith(INBOX_PREFIX)).slice(0, MAX_DEVICES * DEVICE_SLOTS)
  };
}
async function readPending() {
  return cleanPending(await readJson(PENDING_KEY, null));
}
async function mutatePending(mutator) {
  return repo.withLock('mobile-relay-pending', async () => {
    const pending = await readPending();
    await mutator(pending);
    await getStore().put(PENDING_KEY, Buffer.from(JSON.stringify(pending)));
    return pending;
  });
}
async function enqueuePending(item, inboxKey) {
  await mutatePending(pending => {
    pending.items = pending.items.filter(entry => entry.id !== item.id && entry.inboxKey !== inboxKey);
    pending.items.push({ id: item.id, deviceId: item.deviceId, inboxKey, nextAttemptAt: null });
  });
}
async function updatePending(finishedIds, deferred) {
  if (finishedIds.length === 0 && deferred.size === 0) return;
  const finished = new Set(finishedIds);
  await mutatePending(pending => {
    pending.items = pending.items.filter(item => !finished.has(item.id)).map(item => deferred.has(item.id)
      ? { ...item, nextAttemptAt: deferred.get(item.id) } : item);
  });
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
async function readSlot(key) {
  const object = await getStore().get(key);
  if (!object) return { key, item: null };
  const value = JSON.parse(object.body.toString('utf8'));
  return { key, item: value && typeof value === 'object' ? value : null };
}
async function readSlots(deviceId) {
  return Promise.all(Array.from({ length: DEVICE_SLOTS }, (_, index) => readSlot(slotKey(deviceId, index))));
}
function expired(item) {
  return !item || Date.parse(item.expiresAt || 0) <= Date.now();
}
function reusable(item) {
  if (expired(item)) return true;
  if (item?.state !== 'complete') return false;
  if (item.deliveredAt) return true;
  return Date.parse(item.completedAt || 0) <= Date.now() - RESULT_REUSE_GRACE_MS;
}
async function placeInSlot(item) {
  const bytes = Buffer.from(JSON.stringify(item));
  for (let index = 0; index < DEVICE_SLOTS; index++) {
    const key = slotKey(item.deviceId, index);
    try {
      await getStore().put(key, bytes, { forbidOverwrite: true });
      return key;
    } catch (error) {
      if (!objectExists(error)) throw error;
    }
  }
  return repo.withLock(`mobile-relay-submit-${item.deviceId}`, async () => {
    const available = (await readSlots(item.deviceId)).find(slot => reusable(slot.item));
    if (!available) return null;
    await getStore().put(available.key, bytes);
    return available.key;
  });
}

async function initializePending() {
  return repo.withLock('mobile-relay-pending', async () => {
    const pending = await readPending();
    if (pending.initialized) return pending;
    pending.initialized = true;
    await getStore().put(PENDING_KEY, Buffer.from(JSON.stringify(pending)));
    return pending;
  });
}

const deviceFromRequest = mobileAuth.deviceFromRequest;

function cleanName(value) {
  const text = String(value || '手机设备').normalize('NFC').replace(/[\x00-\x1f\x7f]/g, '').trim();
  return Array.from(text || '手机设备').slice(0, 48).join('');
}

function cleanServiceState(value) {
  const state = String(value || '').toLowerCase();
  return ['running', 'stopped', 'degraded', 'unknown'].includes(state) ? state : 'unknown';
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
  if (path === '/api/action' && !['songbot.start', 'songbot.stop', 'napcat.start', 'napcat.stop', 'update.install',
    'daily.automation.enable', 'daily.automation.disable']
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
    if (action === 'desktop-heartbeat' && req.method === 'POST') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const input = body(req);
      const presence = {
        updatedAt: now(),
        workstation: cleanName(req.headers?.['x-admin-device'] || 'Bot工作站'),
        songBot: cleanServiceState(input.songBot),
        napCat: cleanServiceState(input.napCat),
        dailyAutomation: input.dailyAutomation === true,
      };
      await getStore().put(PRESENCE_KEY, Buffer.from(JSON.stringify(presence)));
      return json(res, 200, { ok: true, updatedAt: presence.updatedAt });
    }

    if (action === 'presence' && req.method === 'GET') {
      const device = await deviceFromRequest(req); if (!device) throw unauthorized();
      const presence = await readJson(PRESENCE_KEY, null);
      const updated = Date.parse(presence?.updatedAt || 0);
      const online = Number.isFinite(updated) && Date.now() - updated <= PRESENCE_TTL_MS;
      return json(res, 200, {
        workstationOnline: online,
        songBot: online ? cleanServiceState(presence?.songBot) : 'offline',
        napCat: online ? cleanServiceState(presence?.napCat) : 'offline',
        dailyAutomation: presence?.dailyAutomation === true,
        updatedAt: presence?.updatedAt || null,
      });
    }

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
      const inboxKey = await placeInSlot(item);
      if (!inboxKey) throw rateLimited();
      try { await enqueuePending(item, inboxKey); }
      catch (error) { await getStore().delete(inboxKey).catch(() => {}); throw error; }
      return json(res, 202, { id: item.id, state: item.state });
    }

    if (action === 'result' && req.method === 'GET') {
      const device = await deviceFromRequest(req); if (!device) throw unauthorized();
      const id = safeId(query(req, 'id')); if (!id) throw badRequest();
      const slot = (await readSlots(device.id)).find(candidate => candidate.item?.id === id);
      const record = slot?.item;
      if (!record || expired(record)) throw notFound();
      if (record.state === 'complete' && !record.deliveredAt) {
        await Promise.all([
          getStore().delete(slot.key).catch(() => {}),
          getStore().delete(claimKey(id)).catch(() => {}),
          updatePending([id], new Map()).catch(() => {})
        ]);
      }
      return json(res, record.state === 'complete' ? 200 : 202,
        record.state === 'complete' ? { id, state: 'complete', response: record.response } : { id, state: 'pending' });
    }

    if (action === 'desktop-poll' && req.method === 'GET') {
      if (!desktop) throw unauthorized();
      let pending = await readPending();
      if (!pending.initialized) pending = await initializePending();
      const ready = pending.items.filter(item => !item.nextAttemptAt || Date.parse(item.nextAttemptAt) <= Date.now());
      if (ready.length === 0) return json(res, 200, { items: [] });
      await emergency.assertWriteAllowed();
      const claimed = [];
      const finished = [];
      const deferred = new Map();
      for (const entry of ready) {
        if (claimed.length >= 5) break;
        const { key, item } = await readSlot(entry.inboxKey);
        if (!item || !safeId(item.id) || item.id !== entry.id) {
          finished.push(entry.id);
          continue;
        }
        if (expired(item)) {
          finished.push(entry.id);
          await Promise.all([getStore().delete(key).catch(() => {}), getStore().delete(claimKey(item.id)).catch(() => {})]);
          continue;
        }
        if (item.state === 'complete') { finished.push(entry.id); continue; }
        const claim = await acquireClaim(item.id, key);
        if (!claim) {
          deferred.set(entry.id, item.claimExpiresAt || new Date(Date.now() + CLAIM_MS).toISOString());
          continue;
        }
        const copy = { ...JSON.parse(JSON.stringify(item)), state: 'claimed', claimToken: claim.token, claimExpiresAt: claim.expiresAt };
        if (copy.payload?.path === '/api/song-asset' && copy.payload.body?.key) {
          copy.payload.body.downloadUrl = await getStore().signedGetUrl(copy.payload.body.key);
        }
        claimed.push(copy);
        deferred.set(entry.id, claim.expiresAt);
      }
      await updatePending(finished, deferred);
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
      await updatePending([id], new Map());
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
