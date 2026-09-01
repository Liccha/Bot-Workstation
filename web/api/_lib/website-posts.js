const crypto = require('node:crypto');
const { getStore } = require('./storage');
const announcementRepo = require('./repository');

const CURRENT_KEY = 'website/teacharm.moe/posts/current.json';
const REVISION_KEY = 'website/teacharm.moe/posts/revision.json';
const MAX_POST_BYTES = 4 * 1024 * 1024;
let cachedDocument = null;

function now() { return new Date().toISOString(); }
function clone(value) { return JSON.parse(JSON.stringify(value)); }
function sha256(content) { return crypto.createHash('sha256').update(content, 'utf8').digest('hex'); }
function normalizeName(value) {
  const name = String(value || '').normalize('NFC').trim();
  if (!name || Buffer.byteLength(name, 'utf8') > 240 || !name.toLowerCase().endsWith('.md') || /[\\/\u0000-\u001f]/.test(name)) {
    const error = new Error('invalid post name'); error.statusCode = 400; throw error;
  }
  return name;
}
function normalizeContent(value) {
  const content = String(value == null ? '' : value);
  if (Buffer.byteLength(content, 'utf8') > MAX_POST_BYTES) {
    const error = new Error('post is too large'); error.statusCode = 413; throw error;
  }
  return content;
}
async function readDocument() {
  const marker = await readRevisionMarker();
  if (marker && cachedDocument && cachedDocument.revision === marker.revision) {
    return { document: clone(cachedDocument.document), etag: cachedDocument.etag };
  }
  const object = await getStore().get(CURRENT_KEY);
  if (!object) return { document: { schema: 1, site: 'teacharm.moe', revision: 0, updatedAt: now(), posts: [] }, etag: null };
  const document = JSON.parse(object.body.toString('utf8'));
  if (!Array.isArray(document.posts)) throw new Error('website post document is invalid');
  if (!marker || marker.revision !== Number(document.revision || 0)) await writeRevisionMarker(document);
  cachedDocument = { revision: Number(document.revision || 0), document: clone(document), etag: object.etag };
  return { document, etag: object.etag };
}
async function writeDocument(document, audit) {
  const store = getStore(); const updatedAt = now();
  const next = { ...document, schema: 1, site: 'teacharm.moe', revision: Number(document.revision || 0) + 1, updatedAt };
  const current = await store.get(CURRENT_KEY);
  if (current) await store.put(`website/teacharm.moe/posts/revisions/${updatedAt.replace(/[:.]/g, '-')}-${crypto.randomUUID()}.json`, current.body);
  const saved = await store.put(CURRENT_KEY, Buffer.from(JSON.stringify(next)));
  await writeRevisionMarker(next);
  cachedDocument = { revision: Number(next.revision || 0), document: clone(next), etag: saved.etag };
  await announcementRepo.writeAudit({ ...audit, site: 'teacharm.moe', documentRevision: next.revision });
  return { document: next, etag: saved.etag };
}
function metadata(post) {
  return { id: post.id, name: post.name, size: post.size, modified: post.modified, revision: post.revision, sha256: post.sha256 };
}
function revisionMarker(document) {
  const total = Array.isArray(document?.posts)
    ? document.posts.filter(post => !post.deletedAt).length
    : Number(document?.total);
  return {
    schema: 1,
    site: 'teacharm.moe',
    revision: Math.max(0, Number(document?.revision || 0)),
    updatedAt: String(document?.updatedAt || now()),
    total: Number.isSafeInteger(total) && total >= 0 ? total : 0
  };
}
async function readRevisionMarker() {
  const object = await getStore().get(REVISION_KEY);
  if (!object) return null;
  const marker = JSON.parse(object.body.toString('utf8'));
  if (marker?.site !== 'teacharm.moe' || !Number.isSafeInteger(Number(marker.revision)) || Number(marker.revision) < 0) return null;
  return revisionMarker(marker);
}
async function writeRevisionMarker(document) {
  const marker = revisionMarker(document);
  await getStore().put(REVISION_KEY, Buffer.from(JSON.stringify(marker)));
  return marker;
}
async function list() {
  const { document } = await readDocument();
  return document.posts.filter(post => !post.deletedAt).map(metadata).sort((a, b) => Number(b.modified || 0) - Number(a.modified || 0));
}
async function read(name) {
  const safeName = normalizeName(name); const { document } = await readDocument();
  const post = document.posts.find(item => item.name === safeName && !item.deletedAt);
  if (!post) { const error = new Error('post not found'); error.statusCode = 404; throw error; }
  return clone(post);
}
async function syncSnapshot(requestedAfter) {
  const after = Math.max(0, Math.min(Number.MAX_SAFE_INTEGER, Number(requestedAfter) || 0));
  const marker = await readRevisionMarker();
  if (marker && after >= marker.revision) return { ...marker, unchanged: true, posts: [] };
  const { document } = await readDocument();
  const current = revisionMarker(document);
  return {
    ...current,
    unchanged: false,
    posts: document.posts.filter(post => !post.deletedAt).map(clone)
  };
}
async function save(raw, actor) {
  return announcementRepo.withLock('website-teacharm-posts', async () => {
    const name = normalizeName(raw.name); const content = normalizeContent(raw.content); const state = await readDocument();
    const index = state.document.posts.findIndex(item => item.name === name && !item.deletedAt); const existing = index >= 0 ? state.document.posts[index] : null;
    if (existing && raw.revision == null) { const error = new Error('existing post requires revision'); error.statusCode = 409; throw error; }
    if (existing && Number(raw.revision) !== Number(existing.revision)) { const error = new Error('post changed; reload required'); error.statusCode = 409; throw error; }
    const post = {
      id: existing ? existing.id : crypto.randomUUID(), name, content,
      size: Buffer.byteLength(content, 'utf8'), sha256: sha256(content),
      modified: Date.now(), createdAt: existing ? existing.createdAt : now(), updatedAt: now(),
      revision: Number(existing?.revision || 0) + 1
    };
    if (existing) state.document.posts[index] = post; else state.document.posts.push(post);
    await writeDocument(state.document, { event: existing ? 'WEBSITE_POST_UPDATED' : 'WEBSITE_POST_CREATED', actor, name, before: existing ? metadata(existing) : null, after: metadata(post) });
    return clone(post);
  });
}
async function softDelete(name, expectedRevision, actor) {
  return announcementRepo.withLock('website-teacharm-posts', async () => {
    const safeName = normalizeName(name); const state = await readDocument();
    const post = state.document.posts.find(item => item.name === safeName && !item.deletedAt);
    if (!post) { const error = new Error('post not found'); error.statusCode = 404; throw error; }
    if (expectedRevision != null && Number(expectedRevision) !== Number(post.revision)) { const error = new Error('post changed; reload required'); error.statusCode = 409; throw error; }
    const before = metadata(post); post.deletedAt = now(); post.updatedAt = now(); post.revision = Number(post.revision || 0) + 1;
    await writeDocument(state.document, { event: 'WEBSITE_POST_DELETED', actor, name: safeName, before });
    return { ok: true };
  });
}

module.exports = {
  CURRENT_KEY, REVISION_KEY, MAX_POST_BYTES,
  list, read, save, softDelete, readDocument, syncSnapshot
};
