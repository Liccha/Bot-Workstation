const crypto = require('node:crypto');
const { getStore } = require('./storage');
const { config } = require('./config');

const CURRENT_KEY = 'announcements/current.json';
const DEVICES_KEY = 'security/admin-devices.json';
const ADMIN_IPS_KEY = 'security/admin-ips.json';

function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
async function withLock(name, operation) {
  const store = getStore();
  const key = `locks/${name}.json`;
  const token = crypto.randomUUID();
  let acquired = false;
  for (let attempt = 0; attempt < 20 && !acquired; attempt++) {
    try {
      await store.put(key, Buffer.from(JSON.stringify({ token, expiresAt: new Date(Date.now() + 45_000).toISOString() })), { forbidOverwrite: true });
      acquired = true;
    } catch (error) {
      if (!(error.code === 'FileAlreadyExists' || error.code === 'ObjectAlreadyExists' || error.status === 409)) throw error;
      try {
        const current = await store.get(key);
        const lock = current ? JSON.parse(current.body.toString('utf8')) : null;
        if (!lock || String(lock.expiresAt || '') < now()) await store.delete(key);
      } catch (readError) {
        if (!(readError.status === 404 || readError.code === 'NoSuchKey')) throw readError;
      }
      if (!acquired) await sleep(200 + Math.floor(Math.random() * 150));
    }
  }
  if (!acquired) { const error = new Error('announcement store is busy'); error.statusCode = 503; throw error; }
  try {
    return await operation();
  } finally {
    try {
      const current = await store.get(key);
      const lock = current ? JSON.parse(current.body.toString('utf8')) : null;
      if (lock && lock.token === token) await store.delete(key);
    } catch (_) {}
  }
}

function now() { return new Date().toISOString(); }
function shanghaiMinute() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date());
  const value = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day} ${value.hour}:${value.minute}`;
}
function clone(value) { return JSON.parse(JSON.stringify(value)); }
function visible(item, hiddenGroupId) {
  const copy = clone(item);
  if (copy.groupId === hiddenGroupId) copy.groupId = '****';
  delete copy.claim;
  delete copy.deletedAt;
  return copy;
}
function normalizeBool(value) { return value === true || value === 'true'; }
function attachmentNameFromToken(token) {
  const name = String(token || '').replace(/\\/g, '/').split('/').pop() || '';
  return name.replace(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-/i, '') || 'file.bin';
}
function sanitizeAttachmentName(value, fallback) {
  let name = String(value || '').normalize('NFC').replace(/[\\/:*?"<>|\x00-\x1f\x7f]/g, '_').replace(/^[. ]+|[. ]+$/g, '');
  if (!name) name = String(fallback || '').normalize('NFC').replace(/[\\/:*?"<>|\x00-\x1f\x7f]/g, '_').replace(/^[. ]+|[. ]+$/g, '');
  if (!name) name = 'file.bin';
  return Array.from(name).slice(0, 160).join('');
}
function parseAttachmentNames(raw) {
  if (Array.isArray(raw)) return raw;
  if (typeof raw === 'string' && raw.trim().startsWith('[')) {
    try { const parsed = JSON.parse(raw); return Array.isArray(parsed) ? parsed : []; } catch (_) {}
  }
  return [];
}
function normalizeAttachmentNames(raw, tokens) {
  const supplied = parseAttachmentNames(raw);
  return tokens.map((token, index) => sanitizeAttachmentName(supplied[index], attachmentNameFromToken(token)));
}
function normalizeItem(raw, existing, hiddenGroupId) {
  const old = existing || {};
  const item = { ...old };
  item.id = old.id || String(raw.id || crypto.randomUUID());
  item.groupId = raw.groupId === '****' ? hiddenGroupId : String(raw.groupId || old.groupId || '');
  item.title = String(raw.title || String(raw.content || '').split('\n')[0] || '').slice(0, 240);
  item.content = String(raw.content || '').slice(0, 600);
  item.time = String(raw.time || old.time || '').slice(0, 16);
  item.pin = normalizeBool(raw.pin) ? 'true' : 'false';
  item.confirm = normalizeBool(raw.confirm) ? 'true' : 'false';
  item.image = String(raw.image || '');
  item.attach = String(raw.attach || raw.files || '');
  const attachmentTokens = item.attach.split('|').filter(Boolean);
  const rawAttachmentNames = Object.prototype.hasOwnProperty.call(raw, 'attachmentNames') ? raw.attachmentNames : old.attachmentNames;
  item.attachmentNames = normalizeAttachmentNames(rawAttachmentNames, attachmentTokens);
  item.sent = old.sent === 'true' ? 'true' : (raw.sent === 'true' ? 'true' : 'false');
  item.status = item.sent === 'true' ? 'sent' : 'scheduled';
  if (item.sent !== 'true') { delete item.claim; delete item.nextAttemptAt; delete item.lastSendError; }
  item.createdAt = old.createdAt || now();
  item.updatedAt = now();
  item.revision = Number(old.revision || 0) + 1;
  if (!item.groupId || !item.title || !item.content || !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(item.time)) {
    const error = new Error('invalid announcement'); error.statusCode = 400; throw error;
  }
  return item;
}
async function validateFiles(item) {
  const cfg = config();
  const entries = [];
  if (item.image) entries.push({ key: item.image, limit: cfg.maxImageBytes });
  for (const key of String(item.attach || '').split('|').filter(Boolean)) entries.push({ key, limit: cfg.maxAttachmentBytes });
  for (const entry of entries) {
    if (!/^uploads\/ann_[a-zA-Z0-9_-]+\/(image|attach)\/[^/]+$/.test(entry.key)) {
      const error = new Error('invalid attachment token'); error.statusCode = 400; throw error;
    }
    const head = await getStore().head(entry.key);
    if (!head) { const error = new Error('referenced attachment does not exist'); error.statusCode = 400; throw error; }
    if (head.size <= 0 || head.size > entry.limit) { const error = new Error('referenced attachment size is invalid'); error.statusCode = 400; throw error; }
  }
}

async function readDocument() {
  const store = getStore(); const object = await store.get(CURRENT_KEY);
  if (!object) return { document: { schema: 2, revision: 0, updatedAt: now(), items: [] }, etag: null };
  const document = JSON.parse(object.body.toString('utf8'));
  if (!Array.isArray(document.items)) throw new Error('cloud announcement document is invalid');
  return { document, etag: object.etag };
}
async function writeDocument(document, audit) {
  const store = getStore();
  const next = { ...document, schema: 2, revision: Number(document.revision || 0) + 1, updatedAt: now() };
  const body = Buffer.from(JSON.stringify(next));
  try {
    const stamp = next.updatedAt.replace(/[:.]/g, '-');
    await store.put(`announcements/revisions/${stamp}-${crypto.randomUUID()}.json`, body);
    const saved = await store.put(CURRENT_KEY, body);
    await writeAudit({ ...audit, documentRevision: next.revision });
    await writeDispatchIndex(next.items);
    return { document: next, etag: saved.etag };
  } catch (error) {
    throw error;
  }
}
async function writeAudit(event) {
  const store = getStore(); const at = now(); const day = at.slice(0, 10).replace(/-/g, '/');
  const payload = { at, requestId: crypto.randomUUID(), ...event };
  await store.put(`audit/${day}/${at.replace(/[:.]/g, '-')}-${payload.requestId}.json`, Buffer.from(JSON.stringify(payload)));
}
async function writeDispatchIndex(items) {
  const pending = items.filter(i => !i.deletedAt && i.sent !== 'true' && ['scheduled', 'failed', 'claimed'].includes(i.status || 'scheduled'))
    .map(i => ({
      id: i.id,
      time: i.time,
      revision: i.revision,
      status: i.status || 'scheduled',
      claimExpiresAt: i.claim?.expiresAt || null,
      nextAttemptAt: i.nextAttemptAt || null
    }))
    .sort((a, b) => a.time.localeCompare(b.time));
  await getStore().put('dispatch/index.json', Buffer.from(JSON.stringify({ updatedAt: now(), items: pending })));
}
async function list(hiddenGroupId) {
  const { document, etag } = await readDocument();
  return { items: document.items.filter(i => !i.deletedAt).map(i => visible(i, hiddenGroupId)), etag, revision: document.revision };
}
async function create(raw, actor, hiddenGroupId) {
  return withLock('announcements-current', async () => {
    const state = await readDocument(); const item = normalizeItem(raw, null, hiddenGroupId);
    await validateFiles(item);
    state.document.items.push(item);
    const saved = await writeDocument(state.document, { event: 'ANNOUNCEMENT_CREATED', actor, after: item });
    return { item: visible(item, hiddenGroupId), etag: saved.etag };
  });
}
async function update(id, raw, expectedRevision, actor, hiddenGroupId) {
  return withLock('announcements-current', async () => {
    const state = await readDocument(); const index = state.document.items.findIndex(i => i.id === id && !i.deletedAt);
    if (index < 0) { const error = new Error('announcement not found'); error.statusCode = 404; throw error; }
    const before = clone(state.document.items[index]);
    if (before.status === 'claimed' && before.claim && before.claim.expiresAt >= now()) {
      const error = new Error('announcement is currently being sent'); error.statusCode = 409; throw error;
    }
    if (expectedRevision != null && Number(expectedRevision) !== Number(before.revision)) {
      const error = new Error('announcement changed; reload required'); error.statusCode = 409; throw error;
    }
    const item = normalizeItem(raw, before, hiddenGroupId); state.document.items[index] = item;
    await validateFiles(item);
    const saved = await writeDocument(state.document, { event: 'ANNOUNCEMENT_UPDATED', actor, before, after: item });
    return { item: visible(item, hiddenGroupId), etag: saved.etag };
  });
}
async function softDelete(id, expectedRevision, actor) {
  return withLock('announcements-current', async () => {
    const state = await readDocument(); const item = state.document.items.find(i => i.id === id && !i.deletedAt);
    if (!item) { const error = new Error('announcement not found'); error.statusCode = 404; throw error; }
    if (item.status === 'claimed' && item.claim && item.claim.expiresAt >= now()) {
      const error = new Error('announcement is currently being sent'); error.statusCode = 409; throw error;
    }
    if (expectedRevision != null && Number(expectedRevision) !== Number(item.revision)) {
      const error = new Error('announcement changed; reload required'); error.statusCode = 409; throw error;
    }
    const before = clone(item); item.deletedAt = now(); item.status = 'deleted'; item.updatedAt = now(); item.revision = Number(item.revision || 0) + 1;
    const saved = await writeDocument(state.document, { event: 'ANNOUNCEMENT_DELETED', actor, before });
    return { etag: saved.etag };
  });
}
async function due(before, limit = 10) {
  const indexed = await getStore().get('dispatch/index.json');
  if (indexed) {
    const parsed = JSON.parse(indexed.body.toString('utf8'));
    if (Array.isArray(parsed.items)) return parsed.items
      .filter(i => i.time <= before && (!i.claimExpiresAt || i.claimExpiresAt < now()) && (!i.nextAttemptAt || i.nextAttemptAt <= now()))
      .sort((a, b) => a.time.localeCompare(b.time)).slice(0, limit)
      .map(i => ({ id: i.id, time: i.time, revision: i.revision, status: i.status || 'scheduled' }));
  }
  // Compatibility fallback for an empty or pre-index migration.
  const state = await readDocument();
  return state.document.items.filter(i => !i.deletedAt && i.sent !== 'true' && ['scheduled', 'failed', 'claimed'].includes(i.status || 'scheduled'))
    .filter(i => i.time <= before && (!i.claim || i.claim.expiresAt < now()) && (!i.nextAttemptAt || i.nextAttemptAt <= now())).sort((a, b) => a.time.localeCompare(b.time)).slice(0, limit)
    .map(i => ({ id: i.id, time: i.time, revision: i.revision, status: i.status || 'scheduled' }));
}
async function claim(id, botId) {
  return withLock('announcements-current', async () => {
    const state = await readDocument(); const item = state.document.items.find(i => i.id === id && !i.deletedAt);
    if (!item || item.sent === 'true') { const error = new Error('not claimable'); error.statusCode = 409; throw error; }
    if (item.time > shanghaiMinute() || (item.nextAttemptAt && item.nextAttemptAt > now())) { const error = new Error('not due'); error.statusCode = 409; throw error; }
    if (item.claim && item.claim.expiresAt >= now()) { const error = new Error('already claimed'); error.statusCode = 409; throw error; }
    const claimToken = crypto.randomUUID(); const before = clone(item);
    item.status = 'claimed'; item.claim = { token: claimToken, by: String(botId || 'songbot'), at: now(), expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString() };
    item.lastSendAttemptAt = now(); item.sendAttempts = Number(item.sendAttempts || 0) + 1; item.revision = Number(item.revision || 0) + 1;
    await writeDocument(state.document, { event: 'SCHEDULE_SEND_CLAIMED', actor: { kind: 'bot', device: botId }, before, after: item });
    const result = clone(item); result.claimToken = claimToken; delete result.claim;
    return result;
  });
}
async function finish(id, claimToken, result) {
  return withLock('announcements-current', async () => {
    const state = await readDocument(); const item = state.document.items.find(i => i.id === id && !i.deletedAt);
    if (!item || !item.claim || item.claim.token !== claimToken) { const error = new Error('claim token mismatch'); error.statusCode = 409; throw error; }
    const before = clone(item); delete item.claim; item.revision = Number(item.revision || 0) + 1; item.updatedAt = now();
    if (result.success) {
      item.status = 'sent'; item.sent = 'true'; item.sentAt = result.sentAt || now(); item.napcatMessageId = String(result.messageId || ''); delete item.lastSendError; delete item.nextAttemptAt;
    } else {
      const attempts = Number(item.sendAttempts || 1);
      item.status = result.uncertain ? 'uncertain' : (attempts >= 5 ? 'failed_manual' : 'failed');
      item.lastSendError = String(result.error || 'unknown send failure').slice(0, 1000);
      if (!result.uncertain && attempts < 5) item.nextAttemptAt = new Date(Date.now() + Math.min(60, 5 * Math.pow(2, attempts - 1)) * 60 * 1000).toISOString();
    }
    await writeDocument(state.document, { event: result.success ? 'SCHEDULE_SEND_SUCCESS' : (result.uncertain ? 'SCHEDULE_SEND_UNCERTAIN' : 'SCHEDULE_SEND_FAILED'), actor: { kind: 'bot' }, before, after: item });
    return item;
  });
}
async function readDevices() {
  const object = await getStore().get(DEVICES_KEY);
  if (!object) return { devices: [], etag: null };
  const data = JSON.parse(object.body.toString('utf8'));
  return { devices: Array.isArray(data.devices) ? data.devices : [], etag: object.etag };
}
async function deviceAllowed(device) { const state = await readDevices(); return state.devices.some(d => (typeof d === 'string' ? d : d.id) === device); }
async function addDevice(device, actor) {
  return withLock('admin-devices', async () => {
    const state = await readDevices();
    if (!state.devices.some(d => (typeof d === 'string' ? d : d.id) === device)) state.devices.push({ id: device, createdAt: now(), source: 'password' });
    await getStore().put(DEVICES_KEY, Buffer.from(JSON.stringify({ schema: 1, devices: state.devices, updatedAt: now() })));
    await writeAudit({ event: 'ADMIN_DEVICE_GRANTED', actor, device });
  });
}

async function readTrustedIps() {
  const object = await getStore().get(ADMIN_IPS_KEY);
  if (!object) return { fingerprints: [] };
  const data = JSON.parse(object.body.toString('utf8'));
  return { fingerprints: Array.isArray(data.fingerprints) ? data.fingerprints : [] };
}
async function trustedIpAllowed(fingerprint) {
  if (!/^[a-f0-9]{64}$/.test(String(fingerprint || ''))) return false;
  const state = await readTrustedIps();
  return state.fingerprints.some(entry => (typeof entry === 'string' ? entry : entry.fingerprint) === fingerprint);
}
async function addTrustedIp(fingerprint, actor) {
  if (!/^[a-f0-9]{64}$/.test(String(fingerprint || ''))) {
    const error = new Error('invalid IP fingerprint'); error.statusCode = 400; throw error;
  }
  return withLock('admin-ips', async () => {
    const state = await readTrustedIps();
    if (!state.fingerprints.some(entry => (typeof entry === 'string' ? entry : entry.fingerprint) === fingerprint)) {
      state.fingerprints.push({ fingerprint, createdAt: now(), source: 'desktop-password' });
      await getStore().put(ADMIN_IPS_KEY, Buffer.from(JSON.stringify({
        schema: 1, fingerprints: state.fingerprints, updatedAt: now()
      })));
      await writeAudit({ event: 'ADMIN_IP_GRANTED', actor, fingerprint });
    }
    return true;
  });
}

module.exports = {
  list, create, update, softDelete, due, claim, finish, readDevices, deviceAllowed, addDevice, writeAudit, withLock,
  readTrustedIps, trustedIpAllowed, addTrustedIp,
  attachmentNameFromToken, sanitizeAttachmentName, parseAttachmentNames, normalizeAttachmentNames
};
