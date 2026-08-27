const { getStore } = require('./_lib/storage');
const security = require('./_lib/security');
const emergency = require('./_lib/emergency-lock');
const mobileAuth = require('./_lib/mobile-auth');
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

module.exports = async function handler(req, res) {
  const action = String(query(req, 'action') || 'status');
  try {
    const desktop = security.desktopAuthorized(req);
    const device = desktop ? null : await mobileAuth.deviceFromRequest(req);
    if (!desktop && !device) throw unauthorized();
    const actor = desktop ? { kind: 'workstation' } : { kind: 'mobile-device', id: device.id };

    if (action === 'status' && req.method === 'GET') {
      const policy = await emergency.state();
      return json(res, 200, { ...(await library.status()), writeLocked: policy.locked });
    }
    if ((action === 'songs' || action === 'stable') && req.method === 'GET') {
      return json(res, 200, await library.list(action, query(req, 'q'), query(req, 'offset'), query(req, 'limit')));
    }
    if ((action === 'song' || action === 'stable') && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      const input = body(req);
      const dataset = action === 'song' ? 'songs' : 'stable';
      const id = action === 'song' ? input.id : input.sid;
      return json(res, 200, await library.update(dataset, id, input.values, actor));
    }
    if ((action === 'bootstrap-songs' || action === 'bootstrap-stable') && req.method === 'POST') {
      if (!desktop) throw unauthorized();
      await emergency.assertWriteAllowed();
      const dataset = action === 'bootstrap-songs' ? 'songs' : 'stable';
      return json(res, 201, await library.bootstrap(dataset, body(req), actor));
    }
    if (action === 'asset-ticket' && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      const input = body(req);
      const type = input.type === 'image' ? 'image' : input.type === 'audio' ? 'audio' : '';
      const size = Number(input.size || 0);
      const limit = type === 'image' ? 20 * 1024 * 1024 : 100 * 1024 * 1024;
      const extension = String(input.extension || '').toLowerCase();
      const allowed = type === 'image' ? ['.jpg', '.jpeg', '.png', '.webp'] : ['.mp3', '.wav', '.flac', '.m4a', '.ogg'];
      if (!device || !type || !Number.isSafeInteger(size) || size < 1 || size > limit || !allowed.includes(extension)) badRequest();
      const key = `mobile-library/uploads/${device.id}/${require('node:crypto').randomUUID()}${extension}`;
      const uploadUrl = await getStore().signedPutUrl(key, String(input.contentType || 'application/octet-stream'));
      return json(res, 200, { key, uploadUrl, method: 'PUT' });
    }
    if (action === 'song-asset' && req.method === 'POST') {
      await emergency.assertWriteAllowed();
      if (!device) throw unauthorized();
      const input = body(req);
      const id = String(input.id || '').trim();
      const type = input.type === 'image' ? 'image' : input.type === 'audio' ? 'audio' : '';
      const source = String(input.key || '');
      if (!/^[0-9]{1,12}$/.test(id) || !type || !source.startsWith(`mobile-library/uploads/${device.id}/`)) badRequest();
      const head = await getStore().head(source);
      if (!head || head.size < 1 || head.size > (type === 'image' ? 20 * 1024 * 1024 : 100 * 1024 * 1024)) badRequest();
      const extension = source.slice(source.lastIndexOf('.')).toLowerCase();
      if (type === 'image' ? !['.jpg', '.jpeg', '.png', '.webp'].includes(extension)
        : !['.mp3', '.wav', '.flac', '.m4a', '.ogg'].includes(extension)) badRequest();
      const target = `mobile-library/assets/${type}/${id}/${require('node:crypto').randomUUID()}${extension}`;
      await getStore().copy(source, target);
      const result = await library.update('songs', id, {
        [type === 'image' ? 'image_path' : 'audio_path']: `cloud-object:${target}`
      }, actor);
      await getStore().delete(source).catch(() => {});
      return json(res, 200, { ...result, stored: true });
    }
    return json(res, 405, { error: 'unsupported action or method' });
  } catch (error) {
    const status = Number(error.statusCode || 500);
    if (status >= 500) console.error('mobile-data', action, { name: String(error.name || 'Error'), status });
    return json(res, status >= 400 && status < 600 ? status : 500, {
      error: status === 423 ? 'cloud writes temporarily locked'
        : status === 401 || status === 403 ? 'not authorized'
          : status === 404 ? 'not found' : status === 409 ? 'already initialized'
            : status === 400 ? 'invalid request' : status === 503 ? 'cloud data not initialized' : 'internal'
    });
  }
};
