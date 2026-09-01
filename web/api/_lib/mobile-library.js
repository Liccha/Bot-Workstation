const crypto = require('node:crypto');
const zlib = require('node:zlib');
const { getStore } = require('./storage');
const repo = require('./repository');

const DATASETS = {
  songs: { key: 'mobile-library/songs/current.json', compactKey: 'mobile-library/songs/current.json.gz', id: 'id', maxItems: 5000 },
  stable: { key: 'mobile-library/stable/current.json', compactKey: 'mobile-library/stable/current.json.gz', id: 'sid', maxItems: 5000 }
};
const MAX_CHANGE_LOG = 4000;
const MAX_DOCUMENT_BYTES = 32 * 1024 * 1024;
const MANAGED_SONG_ASSET_FIELDS = new Set(['album_image_path', 'image_path', 'audio_path']);
const caches = new Map();

function now() { return new Date().toISOString(); }
function error(statusCode, message, code = '') {
  const value = new Error(message);
  value.statusCode = statusCode;
  if (code) value.publicCode = code;
  return value;
}
function spec(name) { const value = DATASETS[name]; if (!value) throw error(400, 'invalid dataset'); return value; }
function revisionKey(dataset) { spec(dataset); return `mobile-library/${dataset}/revision.json`; }
function revisionMarker(dataset, document, snapshotEtag = '') {
  const total = Array.isArray(document?.items) ? document.items.length : Number(document?.total);
  const etag = String(snapshotEtag || document?.snapshotEtag || '').trim();
  return {
    schema: 1,
    dataset,
    revision: Math.max(0, Number(document?.revision || 0)),
    updatedAt: String(document?.updatedAt || now()),
    total: Number.isSafeInteger(total) && total >= 0 ? total : null,
    ...(etag ? { snapshotEtag: etag } : {})
  };
}
async function writeRevisionMarker(dataset, document, snapshotEtag = '') {
  const marker = revisionMarker(dataset, document, snapshotEtag);
  await getStore().put(revisionKey(dataset), Buffer.from(JSON.stringify(marker)));
  return marker;
}
async function readRevisionMarker(dataset) {
  const object = await getStore().get(revisionKey(dataset));
  if (!object) return null;
  const marker = JSON.parse(object.body.toString('utf8'));
  if (marker?.dataset !== dataset || !Number.isSafeInteger(Number(marker.revision)) || Number(marker.revision) < 0) {
    return null;
  }
  return revisionMarker(dataset, marker, marker.snapshotEtag);
}
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

function gzip(body) { return zlib.gzipSync(body, { level: 9 }); }
function gunzip(body) { return zlib.gunzipSync(body, { maxOutputLength: MAX_DOCUMENT_BYTES }); }
async function readStoredDocument(dataset, definition) {
  const store = getStore();
  const compact = await store.get(definition.compactKey);
  if (compact) {
    try {
      return { document: JSON.parse(gunzip(compact.body).toString('utf8')), snapshotEtag: compact.etag };
    } catch (_) {
      // Once present, the compact object is authoritative. Falling back to a
      // stale legacy snapshot would silently roll back committed edits.
      throw error(503, 'cloud library compact snapshot is invalid', 'dataset_unavailable');
    }
  }
  const marker = await readRevisionMarker(dataset);
  if (marker?.snapshotEtag) {
    // A marker carrying an etag proves this dataset already migrated. The raw
    // file is intentionally frozen for old clients and is no longer current.
    throw error(503, 'cloud library compact snapshot is missing', 'dataset_unavailable');
  }
  const legacy = await store.get(definition.key);
  if (!legacy) return null;
  if (legacy.body.length > MAX_DOCUMENT_BYTES) throw new Error('cloud library document is too large');
  const document = JSON.parse(legacy.body.toString('utf8'));
  const compactBody = gzip(legacy.body);
  let saved;
  try {
    saved = await store.put(definition.compactKey, compactBody, { forbidOverwrite: true });
  } catch (writeError) {
    if (!['PreconditionFailed', 'FileAlreadyExists', 'ObjectAlreadyExists'].includes(String(writeError?.code || ''))
      && Number(writeError?.status || writeError?.statusCode || 0) !== 409) throw writeError;
    // Another request completed the one-time migration. Read its object so a
    // stale raw snapshot cannot win the race.
    const migrated = await store.get(definition.compactKey);
    if (!migrated) throw writeError;
    return { document: JSON.parse(gunzip(migrated.body).toString('utf8')), snapshotEtag: migrated.etag };
  }
  return { document, snapshotEtag: saved.etag };
}
async function writeInitialSnapshots(definition, bytes, options = {}) {
  const store = getStore();
  const [, compact] = await Promise.all([
    store.put(definition.key, bytes, options),
    store.put(definition.compactKey, gzip(bytes), options)
  ]);
  return compact;
}
async function writeCompactSnapshot(definition, bytes) {
  return getStore().put(definition.compactKey, gzip(bytes));
}

async function read(dataset, options = {}) {
  const definition = spec(dataset);
  const cached = caches.get(dataset);
  if (!options.fresh && cached && Date.now() - cached.at < 15_000) return clone(cached.document);
  const stored = await readStoredDocument(dataset, definition);
  if (!stored) return null;
  const { document, snapshotEtag } = stored;
  if (!Array.isArray(document.columns) || !Array.isArray(document.items)) throw new Error('cloud library document is invalid');
  caches.set(dataset, { at: Date.now(), document, snapshotEtag });
  return clone(document);
}

function cachedSnapshotEtag(dataset) {
  return String(caches.get(dataset)?.snapshotEtag || '');
}

async function bootstrap(dataset, raw, actor) {
  const definition = spec(dataset);
  return repo.withLock(`mobile-library-${dataset}`, async () => {
    if (await getStore().get(definition.key)) throw error(409, 'dataset already initialized', 'dataset_initialized');
    const columns = cleanColumns(raw.columns);
    const idColumn = columns.find(column => column.toLowerCase() === definition.id);
    if (!idColumn) throw error(400, `missing ${definition.id}`);
    const items = cleanItems(raw.items, columns, dataset);
    const document = { schema: 2, dataset, revision: 1, columns, items, changes: [], updatedAt: now() };
    const bytes = Buffer.from(JSON.stringify(document));
    await getStore().put(`mobile-library/${dataset}/baseline-1.json`, bytes, { forbidOverwrite: true });
    const compact = await writeInitialSnapshots(definition, bytes, { forbidOverwrite: true });
    await writeRevisionMarker(dataset, document, compact.etag);
    caches.set(dataset, { at: Date.now(), document, snapshotEtag: compact.etag });
    await repo.writeAudit({ event: 'MOBILE_LIBRARY_BOOTSTRAPPED', actor, dataset, rows: items.length });
    return { revision: document.revision, total: items.length };
  });
}

function listDocument(document, query, requestedOffset, requestedLimit) {
  if (!document) throw error(503, 'dataset not initialized', 'dataset_not_initialized');
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

async function item(dataset, rawId) {
  const definition = spec(dataset);
  const id = String(rawId || '').trim();
  if (!/^[0-9]{1,12}$/.test(id)) throw error(400, 'invalid id');
  const document = await read(dataset, { fresh: true });
  if (!document) throw error(503, 'dataset not initialized', 'dataset_not_initialized');
  const idColumn = document.columns.find(column => column.toLowerCase() === definition.id);
  const found = document.items.find(candidate => String(candidate[idColumn] || '').trim() === id);
  return found ? clone(found) : null;
}

async function synchronizedRevisionMarker(dataset) {
  const definition = spec(dataset);
  const store = getStore();
  const [marker, compact] = await Promise.all([
    readRevisionMarker(dataset),
    store.head(definition.compactKey)
  ]);
  if (marker && Number.isSafeInteger(marker.total) && marker.total >= 0
    && marker.snapshotEtag && compact?.etag && marker.snapshotEtag === compact.etag) {
    return marker;
  }
  const document = await read(dataset, { fresh: true });
  if (!document) return null;
  return writeRevisionMarker(dataset, document, cachedSnapshotEtag(dataset));
}

async function reconstructChanges(dataset, document) {
  const definition = spec(dataset);
  const baselineObject = await getStore().get(`mobile-library/${dataset}/baseline-1.json`);
  if (!baselineObject) throw error(409, 'baseline unavailable', 'baseline_unavailable');
  const baseline = JSON.parse(baselineObject.body.toString('utf8'));
  const idColumn = document.columns.find(column => column.toLowerCase() === definition.id);
  const baselineById = new Map((baseline.items || []).map(item => [String(item[idColumn] || '').trim(), item]));
  const revision = Number(document.revision || 0);
  const reconstructed = [];
  for (const item of document.items) {
    const id = String(item[idColumn] || '').trim();
    const existed = baselineById.has(id);
    const original = baselineById.get(id) || {};
    const values = {};
    for (const column of document.columns) {
      if (column.toLowerCase() === definition.id) continue;
      if (String(item[column] || '') !== String(original[column] || '')) values[column] = String(item[column] || '');
    }
    if (Object.keys(values).length > 0) reconstructed.push({ revision, id, values, created: !existed, reconstructed: true });
  }
  const currentIds = new Set(document.items.map(item => String(item[idColumn] || '').trim()));
  for (const [id] of baselineById) {
    if (!currentIds.has(id)) reconstructed.push({ revision, id, values: {}, deleted: true, reconstructed: true });
  }
  return reconstructed;
}

async function create(dataset, rawId, rawValues, actor) {
  const definition = spec(dataset);
  const id = String(rawId || '').trim();
  if (!/^[0-9]{1,12}$/.test(id)) throw error(400, 'invalid id');
  if (!rawValues || typeof rawValues !== 'object' || Array.isArray(rawValues)) throw error(400, 'invalid values');
  return repo.withLock(`mobile-library-${dataset}`, async () => {
    const document = await read(dataset, { fresh: true });
    if (!document) throw error(503, 'dataset not initialized', 'dataset_not_initialized');
    const allowed = new Map(document.columns.map(column => [column.toLowerCase(), column]));
    const idColumn = allowed.get(definition.id);
    const existing = document.items.find(candidate => String(candidate[idColumn] || '').trim() === id);
    if (existing) {
      const sameSubmission = Object.entries(rawValues).every(([key, input]) => {
        const actual = allowed.get(String(key).toLowerCase());
        if (!actual || actual.toLowerCase() === definition.id) return true;
        if (dataset === 'songs' && MANAGED_SONG_ASSET_FIELDS.has(actual.toLowerCase())) return true;
        return String(existing[actual] || '') === cleanText(input);
      });
      if (!sameSubmission) throw error(409, 'record already exists', 'record_exists');
      await writeRevisionMarker(dataset, document, cachedSnapshotEtag(dataset));
      return { ok: true, created: false, resumed: true, revision: Number(document.revision || 0),
        updatedAt: String(document.updatedAt || '') };
    }
    if (document.items.length >= definition.maxItems) throw error(409, 'dataset item limit reached', 'dataset_limit');
    const item = {};
    for (const column of document.columns) item[column] = '';
    item[idColumn] = id;
    const values = {};
    for (const [key, input] of Object.entries(rawValues)) {
      const actual = allowed.get(String(key).toLowerCase());
      if (!actual || actual.toLowerCase() === definition.id) continue;
      if (dataset === 'songs' && MANAGED_SONG_ASSET_FIELDS.has(actual.toLowerCase())) continue;
      const next = cleanText(input);
      item[actual] = next;
      values[actual] = next;
    }
    document.items.push(item);
    document.items.sort((left, right) => Number(left[idColumn]) - Number(right[idColumn]));
    document.revision = Number(document.revision || 0) + 1;
    document.updatedAt = now();
    const change = { schema: 1, dataset, revision: document.revision, id, values, before: {}, created: true,
      at: document.updatedAt, actor: { kind: actor.kind, id: actor.id || '' } };
    if (!Array.isArray(document.changes)) document.changes = await reconstructChanges(dataset, document);
    else document.changes.push({ revision: change.revision, id, values, created: true, at: change.at });
    if (document.changes.length > MAX_CHANGE_LOG) {
      document.changes = document.changes.slice(document.changes.length - MAX_CHANGE_LOG);
    }
    document.schema = 2;
    const stamp = String(document.revision).padStart(12, '0');
    const bytes = Buffer.from(JSON.stringify(document));
    await getStore().put(`mobile-library/${dataset}/changes/${stamp}-${crypto.randomUUID()}.json`, Buffer.from(JSON.stringify(change)), { forbidOverwrite: true });
    const compact = await writeCompactSnapshot(definition, bytes);
    await writeRevisionMarker(dataset, document, compact.etag);
    caches.set(dataset, { at: Date.now(), document, snapshotEtag: compact.etag });
    return { ok: true, created: true, revision: document.revision, updatedAt: document.updatedAt };
  });
}

async function update(dataset, rawId, rawValues, actor, options = {}) {
  const definition = spec(dataset);
  const id = String(rawId || '').trim();
  if (!/^[0-9]{1,12}$/.test(id)) throw error(400, 'invalid id');
  if (!rawValues || typeof rawValues !== 'object' || Array.isArray(rawValues)) throw error(400, 'invalid values');
  return repo.withLock(`mobile-library-${dataset}`, async () => {
    const document = await read(dataset, { fresh: true });
    if (!document) throw error(503, 'dataset not initialized', 'dataset_not_initialized');
    const allowed = new Map(document.columns.map(column => [column.toLowerCase(), column]));
    const idColumn = allowed.get(definition.id);
    const item = document.items.find(candidate => String(candidate[idColumn] || '').trim() === id);
    if (!item) throw error(404, 'record not found');
    const before = {};
    const values = {};
    for (const [key, input] of Object.entries(rawValues)) {
      const actual = allowed.get(String(key).toLowerCase());
      if (!actual || actual.toLowerCase() === definition.id) continue;
      if (dataset === 'songs' && MANAGED_SONG_ASSET_FIELDS.has(actual.toLowerCase()) && options.managedAssets !== true) continue;
      const next = cleanText(input);
      if (String(item[actual] || '') === next) continue;
      before[actual] = String(item[actual] || '');
      item[actual] = next;
      values[actual] = next;
    }
    if (Object.keys(values).length === 0) {
      // Repair a missing or stale derived marker after a partially completed
      // write without rewriting the full library snapshot.
      await writeRevisionMarker(dataset, document, cachedSnapshotEtag(dataset));
      return { ok: true, revision: Number(document.revision || 0), unchanged: true };
    }
    document.revision = Number(document.revision || 0) + 1;
    document.updatedAt = now();
    const change = { schema: 1, dataset, revision: document.revision, id, values, before,
      at: document.updatedAt, actor: { kind: actor.kind, id: actor.id || '' } };
    if (!Array.isArray(document.changes)) {
      // Upgrade an existing v1 snapshot without losing edits made before the
      // background agent/change feed was introduced.
      document.changes = await reconstructChanges(dataset, document);
    } else {
      document.changes.push({ revision: change.revision, id: change.id, values: change.values, at: change.at });
    }
    if (document.changes.length > MAX_CHANGE_LOG) {
      document.changes = document.changes.slice(document.changes.length - MAX_CHANGE_LOG);
    }
    document.schema = 2;
    const stamp = String(document.revision).padStart(12, '0');
    const bytes = Buffer.from(JSON.stringify(document));
    await getStore().put(`mobile-library/${dataset}/changes/${stamp}-${crypto.randomUUID()}.json`, Buffer.from(JSON.stringify(change)), { forbidOverwrite: true });
    const compact = await writeCompactSnapshot(definition, bytes);
    await writeRevisionMarker(dataset, document, compact.etag);
    caches.set(dataset, { at: Date.now(), document, snapshotEtag: compact.etag });
    return { ok: true, revision: document.revision, updatedAt: document.updatedAt };
  });
}

async function remove(dataset, rawId, actor) {
  const definition = spec(dataset);
  const id = String(rawId || '').trim();
  if (!/^[0-9]{1,12}$/.test(id)) throw error(400, 'invalid id');
  return repo.withLock(`mobile-library-${dataset}`, async () => {
    const document = await read(dataset, { fresh: true });
    if (!document) throw error(503, 'dataset not initialized', 'dataset_not_initialized');
    const idColumn = document.columns.find(column => column.toLowerCase() === definition.id);
    const index = document.items.findIndex(candidate => String(candidate[idColumn] || '').trim() === id);
    if (index < 0) throw error(404, 'record not found');
    const before = clone(document.items[index]);
    document.items.splice(index, 1);
    document.revision = Number(document.revision || 0) + 1;
    document.updatedAt = now();
    const change = { schema: 1, dataset, revision: document.revision, id, values: {}, before, deleted: true,
      at: document.updatedAt, actor: { kind: actor.kind, id: actor.id || '' } };
    if (!Array.isArray(document.changes)) document.changes = await reconstructChanges(dataset, document);
    else document.changes.push({ revision: change.revision, id, values: {}, deleted: true, at: change.at });
    if (document.changes.length > MAX_CHANGE_LOG) {
      document.changes = document.changes.slice(document.changes.length - MAX_CHANGE_LOG);
    }
    document.schema = 2;
    const stamp = String(document.revision).padStart(12, '0');
    const bytes = Buffer.from(JSON.stringify(document));
    await getStore().put(`mobile-library/${dataset}/changes/${stamp}-${crypto.randomUUID()}.json`, Buffer.from(JSON.stringify(change)), { forbidOverwrite: true });
    const compact = await writeCompactSnapshot(definition, bytes);
    await writeRevisionMarker(dataset, document, compact.etag);
    caches.set(dataset, { at: Date.now(), document, snapshotEtag: compact.etag });
    return { ok: true, deleted: true, revision: document.revision, updatedAt: document.updatedAt };
  });
}

async function changes(dataset, requestedAfter, requestedLimit) {
  spec(dataset);
  const after = Math.max(0, Math.min(Number.MAX_SAFE_INTEGER, Number(requestedAfter) || 0));
  const limit = Math.max(1, Math.min(500, Number(requestedLimit) || 100));
  // HEAD is enough to verify that the tiny marker still points at the current
  // compact snapshot. A snapshot committed just before a marker timeout is
  // therefore discovered without periodically downloading the whole library.
  const marker = await synchronizedRevisionMarker(dataset);
  if (marker && after >= marker.revision) {
    return { items: [], revision: marker.revision, nextRevision: marker.revision, hasMore: false };
  }
  const document = await read(dataset, { fresh: true });
  if (!document) throw error(503, 'dataset not initialized', 'dataset_not_initialized');
  if (!marker) await writeRevisionMarker(dataset, document, cachedSnapshotEtag(dataset));
  const revision = Number(document.revision || 0);
  if (after >= revision) return { items: [], revision, nextRevision: revision, hasMore: false };

  const log = Array.isArray(document.changes) ? document.changes
    .filter(item => Number(item?.revision || 0) > after)
    .sort((left, right) => Number(left.revision || 0) - Number(right.revision || 0)) : [];
  if (log.length > 0) {
    const cutoff = Number(log[Math.min(limit, log.length) - 1].revision || 0);
    // Never split one revision: advancing a revision-only cursor halfway
    // through a batch would permanently skip the remaining records.
    const page = log.filter(item => Number(item.revision || 0) <= cutoff).map(item => ({
      revision: Number(item.revision || 0),
      id: cleanText(item.id, 24).trim(),
      created: item.created === true,
      deleted: item.deleted === true,
      values: item.values && typeof item.values === 'object' && !Array.isArray(item.values)
        ? clone(item.values) : {}
    }));
    const nextRevision = Number(page.at(-1)?.revision || after);
    return { items: page, revision, nextRevision, hasMore: nextRevision < revision };
  }

  // Version 1 cloud snapshots predate the compact change log. Reconstruct the
  // exact mobile edits by comparing them with the immutable initial baseline,
  // instead of overwriting every local field with a possibly stale snapshot.
  const reconstructed = await reconstructChanges(dataset, document);
  return { items: reconstructed, revision, nextRevision: revision, hasMore: false, reconstructed: true };
}

async function status() {
  const [songs, stable] = await Promise.all([
    synchronizedRevisionMarker('songs'),
    synchronizedRevisionMarker('stable')
  ]);
  return { ok: true, cloudIndependent: true,
    songs: songs ? { total: songs.total, revision: songs.revision, updatedAt: songs.updatedAt } : null,
    stable: stable ? { total: stable.total, revision: stable.revision, updatedAt: stable.updatedAt } : null };
}

module.exports = { bootstrap, changes, create, item, list, read, remove, status, update };
