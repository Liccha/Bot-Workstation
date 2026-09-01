const { getStore } = require('./_lib/storage');
const security = require('./_lib/security');
const emergency = require('./_lib/emergency-lock');
const mobileAuth = require('./_lib/mobile-auth');
const editorAuth = require('./_lib/library-editor-auth');
const library = require('./_lib/mobile-library');

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
  if (!value || typeof value !== 'object' || Array.isArray(value)) { const error = new Error(); error.statusCode = 400; throw error; }
  return value;
}
function query(req, key) { const value = req.query?.[key]; return Array.isArray(value) ? value[0] : value; }
function badRequest() { const error = new Error(); error.statusCode = 400; throw error; }
function unauthorized() { const error = new Error(); error.statusCode = 401; throw error; }
function managedAssetKey(value) {
  const key = String(value || '').replace(/\\/g, '/');
  if (!key || key.includes('..')) return null;
  const match = /^mobile-library\/assets\/(image|audio)\/([0-9]{1,12})\/([A-Za-z0-9][A-Za-z0-9._-]{0,127})$/.exec(key);
  if (!match) return null;
  const extension = match[3].slice(match[3].lastIndexOf('.')).toLowerCase();
  const allowed = match[1] === 'image'
    ? ['.jpg', '.jpeg', '.png', '.webp']
    : ['.mp3', '.wav', '.flac', '.m4a', '.ogg'];
  return allowed.includes(extension) ? { key, type: match[1] } : null;
}

module.exports = async function handler(req, res) {
  const action = String(query(req, 'action') || 'status');
  try {
    if (action === 'enroll-editor' && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      return json(res, 201, await editorAuth.enroll(req, body(req)));
    }
    const desktop = security.desktopAuthorized(req);
    const device = desktop ? null : await mobileAuth.deviceFromRequest(req);
    const editor = desktop || device ? null : await editorAuth.editorFromRequest(req);
    if (!desktop && !device && !editor) throw unauthorized();
    const actor = desktop ? { kind: 'workstation' }
      : device ? { kind: 'mobile-device', id: device.id }
        : { kind: 'library-editor', id: editor.id };

    if (action === 'status' && req.method === 'GET') {
      const policy = await emergency.state();
      return json(res, 200, { ...(await library.status()), writeLocked: policy.locked });
    }
    if ((action === 'songs' || action === 'stable') && req.method === 'GET') {
      return json(res, 200, await library.list(action, query(req, 'q'), query(req, 'offset'), query(req, 'limit')));
    }
    if (action === 'snapshot-ticket' && req.method === 'GET') {
      const dataset = String(query(req, 'dataset') || '');
      if (dataset !== 'songs' && dataset !== 'stable') badRequest();
      const key = `mobile-library/${dataset}/current.json.gz`;
      return json(res, 200, {
        dataset,
        encoding: 'gzip-json',
        url: await getStore().signedGetUrl(key)
      });
    }
    if (action === 'song-item' && req.method === 'GET') {
      const item = await library.item('songs', query(req, 'id'));
      if (!item) { const missing = new Error('record not found'); missing.statusCode = 404; throw missing; }
      return json(res, 200, item);
    }
    if (action === 'asset-download' && req.method === 'GET') {
      // SongBot alone materializes managed media into its persistent local cache.
      // Group queries continue to read local files and never contact OSS.
      if (!desktop) throw unauthorized();
      const asset = managedAssetKey(query(req, 'key'));
      if (!asset) badRequest();
      const store = getStore();
      return json(res, 200, {
        key: asset.key,
        url: await store.signedGetUrl(asset.key)
      });
    }
    if (action === 'changes' && req.method === 'GET') {
      if (!desktop) throw unauthorized();
      const dataset = String(query(req, 'dataset') || '');
      if (dataset !== 'songs' && dataset !== 'stable') badRequest();
      return json(res, 200, await library.changes(dataset, query(req, 'after'), query(req, 'limit')));
    }
    if ((action === 'song' || action === 'stable') && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      if (editor) await editorAuth.assertMutationAllowed(editor);
      const input = body(req);
      const dataset = action === 'song' ? 'songs' : 'stable';
      const id = action === 'song' ? input.id : input.sid;
      return json(res, 200, await library.update(dataset, id, input.values, actor));
    }
    if (action === 'song-create' && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      if (editor) await editorAuth.assertMutationAllowed(editor);
      const input = body(req);
      const result = await library.create('songs', input.id, input.values, actor);
      return json(res, result.created === false ? 200 : 201, result);
    }
    if (action === 'song-delete' && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      if (editor) await editorAuth.assertMutationAllowed(editor);
      const input = body(req);
      const id = String(input.id || '').trim();
      if (!/^[0-9]{1,12}$/.test(id)) badRequest();
      const result = await library.remove('songs', id, actor);
      const store = getStore();
      await Promise.all([
        store.deletePrefix(`mobile-library/assets/image/${id}/`),
        store.deletePrefix(`mobile-library/assets/audio/${id}/`)
      ]);
      return json(res, 200, result);
    }
    if ((action === 'bootstrap-songs' || action === 'bootstrap-stable') && req.method === 'POST') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const dataset = action === 'bootstrap-songs' ? 'songs' : 'stable';
      return json(res, 201, await library.bootstrap(dataset, body(req), actor));
    }
    if (action === 'asset-ticket' && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      if (editor) await editorAuth.assertMutationAllowed(editor);
      const input = body(req);
      const type = input.type === 'image' ? 'image' : input.type === 'audio' ? 'audio' : '';
      const size = Number(input.size || 0);
      const limit = type === 'image' ? 20 * 1024 * 1024 : 100 * 1024 * 1024;
      const extension = String(input.extension || '').toLowerCase();
      const allowed = type === 'image' ? ['.jpg', '.jpeg', '.png', '.webp'] : ['.mp3', '.wav', '.flac', '.m4a', '.ogg'];
      const uploader = device || editor;
      if (!uploader || !type || !Number.isSafeInteger(size) || size < 1 || size > limit || !allowed.includes(extension)) badRequest();
      const key = `mobile-library/uploads/${uploader.id}/${require('node:crypto').randomUUID()}${extension}`;
      const uploadUrl = await getStore().signedPutUrl(key, String(input.contentType || 'application/octet-stream'));
      return json(res, 200, { key, uploadUrl, method: 'PUT' });
    }
    if (action === 'song-asset' && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      const uploader = device || editor;
      if (!uploader) throw unauthorized();
      const input = body(req);
      const id = String(input.id || '').trim();
      const type = input.type === 'image' ? 'image' : input.type === 'audio' ? 'audio' : '';
      const source = String(input.key || '');
      if (!/^[0-9]{1,12}$/.test(id) || !type || !source.startsWith(`mobile-library/uploads/${uploader.id}/`)) badRequest();
      const extension = source.slice(source.lastIndexOf('.')).toLowerCase();
      if (type === 'image' ? !['.jpg', '.jpeg', '.png', '.webp'].includes(extension)
        : !['.mp3', '.wav', '.flac', '.m4a', '.ogg'].includes(extension)) badRequest();
      const fileName = source.slice(source.lastIndexOf('/') + 1);
      const target = `mobile-library/assets/${type}/${id}/${fileName}`;
      const field = type === 'image' ? 'image_path' : 'audio_path';
      const pointer = `cloud-object:${target}`;
      const current = await library.item('songs', id);
      if (current && String(current[field] || '') === pointer) {
        await getStore().delete(source).catch(() => {});
        return json(res, 200, { ok: true, stored: true, replayed: true });
      }
      const head = await getStore().head(source);
      if (!head || head.size < 1 || head.size > (type === 'image' ? 20 * 1024 * 1024 : 100 * 1024 * 1024)) badRequest();
      await getStore().copy(source, target);
      const result = await library.update('songs', id, { [field]: pointer }, actor, { managedAssets: true });
      await getStore().delete(source).catch(() => {});
      const previous = String(current?.[field] || '');
      const assetPrefix = `cloud-object:mobile-library/assets/${type}/${id}/`;
      if (previous.startsWith(assetPrefix) && previous !== pointer) {
        await getStore().delete(previous.slice('cloud-object:'.length)).catch(() => {});
      }
      return json(res, 200, { ...result, stored: true });
    }
    return json(res, 405, { error: 'unsupported action or method' });
  } catch (error) {
    const status = Number(error.statusCode || 500);
    if (status >= 500) console.error('mobile-data', action, { name: String(error.name || 'Error'), status });
    const code = String(error.publicCode || '');
    const conflictMessage = code === 'record_exists' ? 'record already exists'
      : code === 'dataset_initialized' ? 'already initialized'
        : code === 'dataset_limit' ? 'dataset item limit reached'
          : code === 'baseline_unavailable' ? 'baseline unavailable' : 'conflict';
    if (status === 503 && code === 'write_busy') res.setHeader('Retry-After', '1');
    return json(res, status >= 400 && status < 600 ? status : 500, {
      error: status === 423 ? 'cloud writes temporarily locked'
        : status === 401 || status === 403 ? 'not authorized'
          : status === 404 ? 'not found' : status === 409 ? conflictMessage
            : status === 400 ? 'invalid request'
              : status === 503 && code === 'write_busy' ? 'cloud data busy'
                : status === 503 && code === 'dataset_not_initialized' ? 'cloud data not initialized'
                  : status === 503 ? 'cloud storage unavailable' : 'internal',
      ...((status === 409 || status === 503) && code ? { code } : {})
    });
  }
};
