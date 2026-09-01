const crypto = require('node:crypto');
const { config } = require('./_lib/config');
const { getStore } = require('./_lib/storage');
const repo = require('./_lib/repository');
const websitePosts = require('./_lib/website-posts');
const security = require('./_lib/security');
const emergency = require('./_lib/emergency-lock');
const grantWindows = new Map();

function json(res, status, value, headers = {}) {
  res.statusCode = status; res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  for (const [key, val] of Object.entries(headers)) res.setHeader(key, val);
  res.end(JSON.stringify(value));
}
function body(req) { return typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {}); }
function query(req, key) { const value = req.query?.[key]; return Array.isArray(value) ? value[0] : value; }
function device(req, payload) { return String(req.headers['x-admin-device'] || payload?.d || '').slice(0, 160); }
function actor(req, dev, kind = 'admin') { return { kind, device: dev, ip: security.clientIp(req) }; }
async function admin(req, desktop = false) {
  if (desktop) return { sub: device(req) || 'mczmaker', desktop: true };
  const session = security.sessionFromRequest(req);
  if (session) return session;
  const dev = device(req);
  if (dev && await repo.deviceAllowed(dev)) return { sub: dev, legacy: true };
  return null;
}
function browserAllowed(req, cfg) {
  if (cfg.local) return true;
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(':')[0].toLowerCase();
  const fcRuntime = process.env.SONGBOT_RUNTIME === 'aliyun-fc';
  const allowedHost = fcRuntime || host === 'editor.teacharm.moe' || host === 'bot-editor.vercel.app' || /^bot-editor-[a-z0-9-]+-licchas-projects\.vercel\.app$/.test(host);
  if (!allowedHost) return false;
  const origin = String(req.headers.origin || '').toLowerCase();
  if (!origin) return req.method === 'GET' || String(req.headers['sec-fetch-site'] || '') === 'same-origin';
  try {
    const originHost = new URL(origin).hostname.toLowerCase();
    return fcRuntime
      ? originHost === 'editor.teacharm.moe' || originHost === 'bot-editor.vercel.app'
        || /^bot-editor-[a-z0-9-]+-licchas-projects\.vercel\.app$/.test(originHost)
      : originHost === host;
  } catch (_) { return false; }
}
function sessionCookie(token) {
  return `sb_ann_session=${encodeURIComponent(token)}; Path=/api/announcement-cloud; Max-Age=${30 * 24 * 3600}; HttpOnly; Secure; SameSite=Strict`;
}
function grantAttemptAllowed(req) {
  const minute = Math.floor(Date.now() / 60000); const key = `${security.clientIp(req)}:${minute}`;
  const count = Number(grantWindows.get(key) || 0) + 1; grantWindows.set(key, count);
  if (grantWindows.size > 2000) for (const oldKey of grantWindows.keys()) if (!oldKey.endsWith(`:${minute}`)) grantWindows.delete(oldKey);
  return count <= 30;
}
function sanitizeName(name) {
  return repo.sanitizeAttachmentName(name, 'file.bin');
}

module.exports = async function handler(req, res) {
  const action = String(query(req, 'action') || 'health');
  try {
    const cfg = config();
    if (action === 'health') {
      const policy = await emergency.state();
      return json(res, 200, { ok: true, writeLocked: policy.locked });
    }

    const desktop = security.desktopAuthorized(req);
    const workstationAdmin = action === 'workstation-admin-check' || action === 'workstation-admin-grant';
    if (!action.startsWith('bot-') && !desktop && !workstationAdmin && !browserAllowed(req, cfg)) return json(res, 403, { error: 'origin not allowed' });

    if (action === 'workstation-admin-check' && req.method === 'GET') {
      const dev = device(req);
      if (!dev) return json(res, 400, { error: 'device required' });
      const fingerprint = security.ipFingerprint(req);
      const allowed = await repo.deviceAllowed(dev)
        || Boolean(fingerprint && await repo.trustedIpAllowed(fingerprint));
      return json(res, 200, { admin: allowed });
    }
    if (action === 'workstation-admin-grant' && req.method === 'POST') {
      const input = body(req); const dev = device(req, input);
      if (!dev || !grantAttemptAllowed(req) || !/^[a-zA-Z]{6,40}$/.test(String(input.p || '')) || !security.passwordMatches(input.p)) {
        return json(res, 200, { admin: false });
      }
      await emergency.assertWriteAllowed();
      await repo.addDevice(dev, actor(req, dev, 'workstation'));
      const fingerprint = security.ipFingerprint(req);
      if (fingerprint) await repo.addTrustedIp(fingerprint, { kind: 'workstation', device: dev });
      return json(res, 200, { admin: true });
    }

    if (action === 'desktop-ip-check' && req.method === 'GET') {
      if (!desktop) return json(res, 401, { error: 'desktop authorization required' });
      const fingerprint = security.ipFingerprint(req);
      if (!fingerprint) return json(res, 400, { error: 'client address unavailable' });
      return json(res, 200, { trusted: await repo.trustedIpAllowed(fingerprint) });
    }
    if (action === 'desktop-ip-grant' && req.method === 'POST') {
      if (!desktop) return json(res, 401, { error: 'desktop authorization required' });
      await emergency.assertWriteAllowed();
      const fingerprint = security.ipFingerprint(req);
      if (!fingerprint) return json(res, 400, { error: 'client address unavailable' });
      const dev = device(req) || 'bot-workstation';
      await repo.addTrustedIp(fingerprint, { kind: 'workstation', device: dev });
      return json(res, 200, { trusted: true });
    }

    if (action === 'admin-check' && req.method === 'GET') {
      const session = await admin(req); const dev = device(req);
      return session
        ? json(res, 200, { admin: true }, { 'Set-Cookie': sessionCookie(security.signSession(session.sub || dev)) })
        : json(res, 200, { admin: false });
    }
    if (action === 'admin-grant' && req.method === 'POST') {
      const input = body(req); const dev = device(req, input);
      if (!dev || !grantAttemptAllowed(req) || !/^[a-zA-Z]{6,40}$/.test(String(input.p || '')) || !security.passwordMatches(input.p)) {
        // Hidden passphrases are entered in the song search box. A mismatch is an ordinary search,
        // not an authentication attack: do not block, delay, or mutate device state.
        return json(res, 200, { admin: false });
      }
      await emergency.assertWriteAllowed();
      await repo.addDevice(dev, actor(req, dev));
      return json(res, 200, { admin: true }, { 'Set-Cookie': sessionCookie(security.signSession(dev)) });
    }

    if (action === 'list' && req.method === 'GET') {
      const session = await admin(req, desktop); if (!session) return json(res, 401, { error: 'admin authorization required' });
      const result = await repo.list(cfg.hiddenGroupId);
      return json(res, 200, result.items, { ETag: `"${result.etag || result.revision}"` });
    }
    if (action === 'announcement') {
      const session = await admin(req, desktop); if (!session) return json(res, 401, { error: 'admin authorization required' });
      const dev = session.sub || device(req); const auditActor = actor(req, dev, session.desktop ? 'mczmaker' : 'admin'); const id = String(query(req, 'id') || '');
      if (req.method === 'POST') {
        await emergency.assertWriteAllowed();
        const result = await repo.create(body(req), auditActor, cfg.hiddenGroupId);
        return json(res, result.created === false ? 200 : 201, result.item, { ETag: `"${result.etag}"` });
      }
      if (req.method === 'PATCH' || req.method === 'PUT') {
        await emergency.assertWriteAllowed();
        const input = body(req); const result = await repo.update(id, input, input.revision, auditActor, cfg.hiddenGroupId);
        return json(res, 200, result.item, { ETag: `"${result.etag}"` });
      }
      if (req.method === 'DELETE') {
        await emergency.assertWriteAllowed();
        await repo.softDelete(id, query(req, 'revision'), auditActor);
        return json(res, 200, { ok: true });
      }
    }
    if (action.startsWith('website-')) {
      const session = await admin(req, desktop); if (!session) return json(res, 401, { error: 'admin authorization required' });
      const auditActor = actor(req, session.sub || device(req), session.desktop ? 'mczmaker' : 'admin');
      if (action === 'website-sync' && req.method === 'GET') {
        return json(res, 200, await websitePosts.syncSnapshot(query(req, 'after')));
      }
      if (action === 'website-list' && req.method === 'GET') return json(res, 200, await websitePosts.list());
      if (action === 'website-read' && req.method === 'GET') return json(res, 200, await websitePosts.read(String(query(req, 'name') || '')));
      if (action === 'website-save' && (req.method === 'POST' || req.method === 'PUT')) {
        await emergency.assertWriteAllowed();
        return json(res, 200, await websitePosts.save(body(req), auditActor));
      }
      if (action === 'website-delete' && req.method === 'DELETE') {
        await emergency.assertWriteAllowed();
        return json(res, 200, await websitePosts.softDelete(String(query(req, 'name') || ''), query(req, 'revision'), auditActor));
      }
    }
    if (action === 'upload-ticket' && req.method === 'POST') {
      const session = await admin(req, desktop); if (!session) return json(res, 401, { error: 'admin authorization required' });
      await emergency.assertWriteAllowed();
      const input = body(req); const type = input.type === 'image' ? 'image' : 'attach';
      const size = Number(input.size || 0); const limit = type === 'image' ? cfg.maxImageBytes : cfg.maxAttachmentBytes;
      if (!size || size > limit) return json(res, 400, { error: `file size must be between 1 and ${limit}` });
      const sessionId = /^ann_[a-zA-Z0-9_-]+$/.test(String(input.session || '')) ? input.session : `ann_${Date.now()}`;
      const originalName = sanitizeName(input.name);
      const key = `uploads/${sessionId}/${type}/${crypto.randomUUID()}-${originalName}`;
      const uploadUrl = await getStore().signedPutUrl(key, input.contentType);
      await repo.writeAudit({ event: 'ATTACHMENT_UPLOAD_TICKET', actor: actor(req, session.sub, session.desktop ? 'mczmaker' : 'admin'), key, size, type });
      return json(res, 200, { token: key, name: originalName, uploadUrl, method: 'PUT', headers: { 'Content-Type': input.contentType || 'application/octet-stream' } });
    }
    if (action === 'delete-file' && req.method === 'DELETE') {
      const session = await admin(req, desktop); if (!session) return json(res, 401, { error: 'admin authorization required' });
      await emergency.assertWriteAllowed();
      const key = String(query(req, 'key') || '');
      if (!/^uploads\/ann_[a-zA-Z0-9_-]+\/(image|attach)\//.test(key)) return json(res, 400, { error: 'invalid object key' });
      const trash = `trash/${new Date().toISOString().slice(0, 10)}/${crypto.randomUUID()}-${key.split('/').pop()}`;
      const head = await getStore().head(key);
      // Archive instead of hard-deleting. The runtime identity deliberately has
      // no DeleteObject permission, so a leaked credential cannot empty storage.
      if (head) await getStore().copy(key, trash);
      await repo.writeAudit({ event: 'ATTACHMENT_ARCHIVED', actor: actor(req, session.sub, session.desktop ? 'mczmaker' : 'admin'), key, trash });
      return json(res, 200, { ok: true });
    }
    if (action === 'local-upload' && cfg.local && req.method === 'PUT') {
      await emergency.assertWriteAllowed();
      const key = String(query(req, 'key') || ''); await getStore().put(key, Buffer.isBuffer(req.body) ? req.body : Buffer.from(req.body || ''));
      return json(res, 200, { ok: true });
    }

    if (action.startsWith('bot-')) {
      if (!security.botAuthorized(req)) return json(res, 401, { error: 'bot authorization required' });
      const input = req.method === 'GET' ? {} : body(req); const id = String(input.id || query(req, 'id') || '');
      if (action === 'bot-due' && req.method === 'GET') return json(res, 200, { items: await repo.due(String(query(req, 'before') || '').slice(0, 16), 10) });
      if (action === 'bot-claim' && req.method === 'POST') {
        await emergency.assertWriteAllowed();
        const item = await repo.claim(id, input.botId || 'songbot');
        if (item.image) item.imageUrl = await getStore().signedGetUrl(item.image);
        if (item.attach) {
          item.attachmentUrls = {};
          for (const key of item.attach.split('|').filter(Boolean)) item.attachmentUrls[key] = await getStore().signedGetUrl(key);
        }
        return json(res, 200, item);
      }
      if (action === 'bot-complete' && req.method === 'POST') {
        await emergency.assertWriteAllowed();
        return json(res, 200, await repo.finish(id, input.claimToken, { success: true, messageId: input.messageId, sentAt: input.sentAt }));
      }
      if (action === 'bot-fail' && req.method === 'POST') {
        await emergency.assertWriteAllowed();
        return json(res, 200, await repo.finish(id, input.claimToken, { success: false, error: input.error, uncertain: Boolean(input.uncertain) }));
      }
    }
    return json(res, 405, { error: 'unsupported action or method' });
  } catch (error) {
    const status = Number(error.statusCode || error.status || 500);
    // Never hand the cloud SDK error object or its request metadata to logs/clients.
    if (status >= 500) console.error('announcement-cloud', action, {
      name: String(error.name || 'Error').slice(0, 80),
      code: String(error.code || 'internal').slice(0, 80),
      status
    });
    const safeStatus = status >= 400 && status < 600 ? status : 500;
    const publicError = safeStatus === 423 ? 'cloud writes temporarily locked'
      : safeStatus === 413 ? 'content too large'
      : safeStatus === 409 ? 'conflict'
      : safeStatus === 404 ? 'not found'
        : safeStatus === 400 ? 'invalid request'
          : safeStatus === 401 || safeStatus === 403 ? 'not authorized'
            : 'internal';
    return json(res, safeStatus, { error: publicError });
  }
};
