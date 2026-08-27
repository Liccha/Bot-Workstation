const crypto = require('node:crypto');
const { getStore } = require('./storage');
const repo = require('./repository');

const DATASETS = {
  songs: { key: 'mobile-library/songs/current.json', id: 'id', maxItems: 5000 },
  stable: { key: 'mobile-library/stable/current.json', id: 'sid', maxItems: 5000 }
};
const caches = new Map();

function now() { return new Date().toISOString(); }
function error(statusCode, message) { const value = new Error(message); value.statusCode = statusCode; return value; }
function spec(name) { const value = DATASETS[name]; if (!value) throw error(400, 'invalid dataset'); return value; }
function clone(value) { return JSON.parse(JSON.stringify(value)); }
function cleanText(value, limit = 8192) {
  const text = String(value == null ? '' : value).normalize('NFC').replace(/\u0000/g, '');
  if (text.length > limit) throw error(400, 'field too long');
  return text;
}
function cleanColumns(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 128) throw error(400, 'invalid columns');
  const columns = value.map(item => cleanText(item, 80).trim());
  if (columns.some(item => !item) || new Set(columns.map(item => item.toLowerCase())).size !== columns.length) {
    throw error(400, 'invalid columns');
  }
  return columns;
}
function cleanItems(value, columns, dataset) {
  const definition = spec(dataset);
  if (!Array.isArray(value) || value.length < 1 || value.length > definition.maxItems) throw error(400, 'invalid items');
  const allowed = new Map(columns.map(column => [column.toLowerCase(), column]));
  const ids = new Set();
  return value.map(raw => {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw error(400, 'invalid item');
    const item = {};
    for (const [key, input] of Object.entries(raw)) {
      const actual = allowed.get(String(key).toLowerCase());
      if (actual) item[actual] = cleanText(input);
    }
    for (const column of columns) if (!Object.hasOwn(item, column)) item[column] = '';
    const idColumn = allowed.get(definition.id);
    const id = String(item[idColumn] || '').trim();
    if (!/^[0-9]{1,12}$/.test(id) || ids.has(id)) throw error(400, 'invalid or duplicate id');
    ids.add(id);
    return item;
  });
}

async function read(dataset, options = {}) {
  const definition = spec(dataset);
  const cached = caches.get(dataset);
  if (!options.fresh && cached && Date.now() - cached.at < 15_000) return clone(cached.document);
  const object = await getStore().get(definition.key);
  if (!object) return null;
  const document = JSON.parse(object.body.toString('utf8'));
  if (!Array.isArray(document.columns) || !Array.isArray(document.items)) throw new Error('cloud library document is invalid');
  caches.set(dataset, { at: Date.now(), document });
  return clone(document);
}

async function bootstrap(dataset, raw, actor) {
  const definition = spec(dataset);
  return repo.withLock(`mobile-library-${dataset}`, async () => {
    if (await getStore().get(definition.key)) throw error(409, 'dataset already initialized');
    const columns = cleanColumns(raw.columns);
    const idColumn = columns.find(column => column.toLowerCase() === definition.id);
    if (!idColumn) throw error(400, `missing ${definition.id}`);
    const items = cleanItems(raw.items, columns, dataset);
    const document = { schema: 1, dataset, revision: 1, columns, items, updatedAt: now() };
    const bytes = Buffer.from(JSON.stringify(document));
    await getStore().put(`mobile-library/${dataset}/baseline-1.json`, bytes, { forbidOverwrite: true });
    await getStore().put(definition.key, bytes, { forbidOverwrite: true });
    caches.set(dataset, { at: Date.now(), document });
    await repo.writeAudit({ event: 'MOBILE_LIBRARY_BOOTSTRAPPED', actor, dataset, rows: items.length });
    return { revision: document.revision, total: items.length };
  });
}

function listDocument(document, query, requestedOffset, requestedLimit) {
  if (!document) throw error(503, 'dataset not initialized');
  const needle = cleanText(query || '', 160).trim().toLocaleLowerCase();
  const offset = Math.max(0, Math.min(1_000_000, Number(requestedOffset) || 0));
  const limit = Math.max(1, Math.min(200, Number(requestedLimit) || 100));
  const matched = needle ? document.items.filter(item => Object.values(item)
    .some(value => String(value).toLocaleLowerCase().includes(needle))) : document.items;
  const items = matched.slice(offset, offset + limit);
  const nextOffset = offset + items.length;
  return { items, columns: document.columns, offset, limit, total: matched.length,
    revision: Number(document.revision || 0), hasMore: nextOffset < matched.length, nextOffset };
}

async function list(dataset, query, offset, limit) {
  return listDocument(await read(dataset), query, offset, limit);
}

async function update(dataset, rawId, rawValues, actor) {
  const definition = spec(dataset);
  const id = String(rawId || '').trim();
  if (!/^[0-9]{1,12}$/.test(id)) throw error(400, 'invalid id');
  if (!rawValues || typeof rawValues !== 'object' || Array.isArray(rawValues)) throw error(400, 'invalid values');
  return repo.withLock(`mobile-library-${dataset}`, async () => {
    const document = await read(dataset, { fresh: true });
    if (!document) throw error(503, 'dataset not initialized');
    const allowed = new Map(document.columns.map(column => [column.toLowerCase(), column]));
    const idColumn = allowed.get(definition.id);
    const item = document.items.find(candidate => String(candidate[idColumn] || '').trim() === id);
    if (!item) throw error(404, 'record not found');
    const before = {};
    const values = {};
    for (const [key, input] of Object.entries(rawValues)) {
      const actual = allowed.get(String(key).toLowerCase());
      if (!actual || actual.toLowerCase() === definition.id) continue;
      const next = cleanText(input);
      if (String(item[actual] || '') === next) continue;
      before[actual] = String(item[actual] || '');
      item[actual] = next;
      values[actual] = next;
    }
    if (Object.keys(values).length === 0) return { ok: true, revision: Number(document.revision || 0), unchanged: true };
    document.revision = Number(document.revision || 0) + 1;
    document.updatedAt = now();
    const change = { schema: 1, dataset, revision: document.revision, id, values, before,
      at: document.updatedAt, actor: { kind: actor.kind, id: actor.id || '' } };
    const stamp = String(document.revision).padStart(12, '0');
    await getStore().put(`mobile-library/${dataset}/changes/${stamp}-${crypto.randomUUID()}.json`, Buffer.from(JSON.stringify(change)), { forbidOverwrite: true });
    await getStore().put(definition.key, Buffer.from(JSON.stringify(document)));
    caches.set(dataset, { at: Date.now(), document });
    await repo.writeAudit({ event: dataset === 'songs' ? 'MOBILE_SONG_UPDATED' : 'MOBILE_STABLE_UPDATED', actor, id, values, revision: document.revision });
    return { ok: true, revision: document.revision, updatedAt: document.updatedAt };
  });
}

async function status() {
  const [songs, stable] = await Promise.all([read('songs'), read('stable')]);
  return { ok: true, cloudIndependent: true,
    songs: songs ? { total: songs.items.length, revision: songs.revision, updatedAt: songs.updatedAt } : null,
    stable: stable ? { total: stable.items.length, revision: stable.revision, updatedAt: stable.updatedAt } : null };
}

module.exports = { bootstrap, list, read, status, update };
