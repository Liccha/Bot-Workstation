const crypto = require('node:crypto');
const { config } = require('./config');

function b64url(input) { return Buffer.from(input).toString('base64url'); }
function unb64url(input) { return Buffer.from(input, 'base64url').toString('utf8'); }
function safeEqual(a, b) {
  const aa = Buffer.from(String(a || '')); const bb = Buffer.from(String(b || ''));
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}
function passwordHash(password, salt) {
  return crypto.scryptSync(String(password || ''), String(salt || ''), 32).toString('hex');
}
function passwordMatches(password) {
  const cfg = config();
  return safeEqual(passwordHash(password, cfg.adminSalt), cfg.adminHash);
}
function signSession(device, ttlSeconds = 30 * 24 * 3600) {
  const cfg = config();
  const payload = b64url(JSON.stringify({ sub: String(device), role: 'announcement-admin', exp: Math.floor(Date.now() / 1000) + ttlSeconds }));
  const signature = crypto.createHmac('sha256', cfg.sessionSecret).update(payload).digest('base64url');
  return `${payload}.${signature}`;
}
function verifySession(token) {
  try {
    const cfg = config(); const [payload, signature] = String(token || '').split('.');
    if (!payload || !signature) return null;
    const expected = crypto.createHmac('sha256', cfg.sessionSecret).update(payload).digest('base64url');
    if (!safeEqual(signature, expected)) return null;
    const data = JSON.parse(unb64url(payload));
    if (data.role !== 'announcement-admin' || Number(data.exp) < Math.floor(Date.now() / 1000)) return null;
    return data;
  } catch (_) { return null; }
}
function cookie(req, name) {
  const raw = String(req.headers.cookie || '');
  for (const part of raw.split(';')) {
    const index = part.indexOf('=');
    if (index > 0 && part.slice(0, index).trim() === name) {
      try { return decodeURIComponent(part.slice(index + 1).trim()); }
      catch (_) { return ''; }
    }
  }
  return '';
}
// Administrator sessions only travel in an HttpOnly cookie. Keeping them out of
// JavaScript, localStorage and Authorization headers reduces accidental leakage.
function sessionFromRequest(req) { return verifySession(cookie(req, 'sb_ann_session')); }
function botAuthorized(req) {
  const cfg = config();
  const value = String(req.headers.authorization || '');
  return value.startsWith('Bot ') && safeEqual(value.slice(4), cfg.botToken);
}
function desktopAuthorized(req) {
  const cfg = config();
  const value = String(req.headers.authorization || '');
  return value.startsWith('Desktop ') && safeEqual(value.slice(8), cfg.desktopToken);
}
function normalizeIp(value) {
  let ip = String(value || '').split(',')[0].trim().toLowerCase();
  if (!ip) return '';
  if (ip.startsWith('[') && ip.includes(']')) ip = ip.slice(1, ip.indexOf(']'));
  if (ip.startsWith('::ffff:')) ip = ip.slice(7);
  const zone = ip.indexOf('%');
  if (zone >= 0) ip = ip.slice(0, zone);
  return ip;
}
function clientIp(req) {
  // Vercel overwrites x-vercel-forwarded-for at the trusted edge. Prefer it so a
  // caller-supplied x-forwarded-for value cannot choose the identity we trust.
  return normalizeIp(req.headers['x-vercel-forwarded-for'] || req.headers['x-forwarded-for']
    || req.headers['x-real-ip'] || req.socket?.remoteAddress || '');
}
function trustedEdgeIp(req) {
  return normalizeIp(req.headers['x-vercel-forwarded-for']);
}
function ipFingerprint(req) {
  // Never fall back to caller-controlled forwarding headers for a value that
  // becomes an authorization factor. Vercel injects this header at its edge.
  const ip = trustedEdgeIp(req);
  if (!ip) return '';
  return crypto.createHmac('sha256', config().sessionSecret).update(`admin-ip\0${ip}`).digest('hex');
}

module.exports = { passwordHash, passwordMatches, signSession, verifySession, sessionFromRequest,
  botAuthorized, desktopAuthorized, clientIp, trustedEdgeIp, ipFingerprint, normalizeIp, safeEqual };
