const crypto = require('node:crypto');
const { getStore } = require('./storage');
const repo = require('./repository');
const security = require('./security');

const EDITORS_KEY = 'security/library-editors.json';
const MAX_ACTIVE_EDITORS = 5000;
const MAX_EDITORS_PER_EDGE_IP = 20;
const DAILY_MUTATION_LIMIT = 1000;

function now() { return new Date().toISOString(); }
function error(statusCode, message) { const value = new Error(message); value.statusCode = statusCode; return value; }
function hash(value) { return crypto.createHash('sha256').update(String(value || '')).digest('hex'); }
function validUuid(value) { return /^[a-f0-9-]{36}$/.test(String(value || '')) ? String(value) : ''; }
function validSecret(value) { return /^[A-Za-z0-9_-]{43,128}$/.test(String(value || '')) ? String(value) : ''; }
function cleanName(value) {
  const name = String(value || 'Bot工作站').normalize('NFC').replace(/[\u0000-\u001f\u007f]/g, '').trim();
  return Array.from(name || 'Bot工作站').slice(0, 60).join('');
}
function edgeFingerprint(req) {
  const trusted = security.trustedEdgeIp(req);
  return trusted ? hash(`library-editor-ip\0${trusted}`) : '';
}

async function readEditors() {
  const object = await getStore().get(EDITORS_KEY);
  if (!object) return { schema: 1, editors: [] };
  const value = JSON.parse(object.body.toString('utf8'));
  return { schema: 1, editors: Array.isArray(value?.editors) ? value.editors : [] };
}

async function enroll(req, input) {
  const installationId = validUuid(input?.installationId);
  const secret = validSecret(input?.secret);
  if (!installationId || !secret) throw error(400, 'invalid installation identity');
  const installationHash = hash(`installation\0${installationId}`);
  const secretHash = hashSecret(secret);
  const fingerprint = edgeFingerprint(req);
  return repo.withLock('library-editors', async () => {
    const document = await readEditors();
    let editor = document.editors.find(item => item.installationHash === installationHash && item.status !== 'revoked');
    if (editor) {
      if (!security.safeEqual(editor.secretHash, secretHash)) throw error(409, 'installation already enrolled');
    } else {
      if (document.editors.filter(item => item.status !== 'revoked').length >= MAX_ACTIVE_EDITORS) {
        throw error(409, 'editor limit reached');
      }
      if (fingerprint && document.editors.filter(item => item.status !== 'revoked' && item.registrationFingerprint === fingerprint).length >= MAX_EDITORS_PER_EDGE_IP) {
        throw error(429, 'registration rate limited');
      }
      editor = {
        id: crypto.randomUUID(),
        name: cleanName(input?.name),
        installationHash,
        secretHash,
        registrationFingerprint: fingerprint,
        scope: 'library-editor',
        status: 'active',
        createdAt: now()
      };
      document.editors.push(editor);
      await getStore().put(EDITORS_KEY, Buffer.from(JSON.stringify({ ...document, updatedAt: now() })));
      await repo.writeAudit({ event: 'LIBRARY_EDITOR_ENROLLED', actor: { kind: 'self-service-installation', id: editor.id } });
    }
    return { id: editor.id, token: `${editor.id}.${secret}`, name: editor.name, scope: editor.scope };
  });
}

function hashSecret(secret) { return hash(`library-editor-secret\0${secret}`); }

async function editorFromRequest(req) {
  const header = String(req.headers.authorization || '');
  if (!header.startsWith('Device ')) return null;
  const token = header.slice(7).trim();
  const split = token.indexOf('.');
  if (split < 1) return null;
  const id = validUuid(token.slice(0, split));
  const secret = validSecret(token.slice(split + 1));
  if (!id || !secret) return null;
  const document = await readEditors();
  const editor = document.editors.find(item => item.id === id && item.status !== 'revoked' && item.scope === 'library-editor');
  if (!editor || !security.safeEqual(editor.secretHash, hashSecret(secret))) return null;
  return editor;
}

async function assertMutationAllowed(editor) {
  if (!editor?.id) throw error(401, 'not authorized');
  const day = now().slice(0, 10);
  const key = `security/library-editor-usage/${editor.id}/${day}.json`;
  // Alibaba OSS rejects If-Match / If-None-Match on ordinary PUT requests.
  // Serialize only this editor's tiny counter instead; unrelated installations
  // never share this lock and the actual library mutation remains independent.
  return repo.withLock(`library-editor-usage-${editor.id}-${day}`, async () => {
    const store = getStore();
    const current = await store.get(key);
    let count = 0;
    if (current) {
      const value = JSON.parse(current.body.toString('utf8'));
      count = Math.max(0, Number(value?.count || 0));
    }
    if (count >= DAILY_MUTATION_LIMIT) throw error(429, 'daily mutation limit reached');
    await store.put(key, Buffer.from(JSON.stringify({ schema: 1, editorId: editor.id,
      day, count: count + 1, updatedAt: now() })));
  });
}

module.exports = { EDITORS_KEY, assertMutationAllowed, editorFromRequest, enroll, hashSecret, readEditors };
