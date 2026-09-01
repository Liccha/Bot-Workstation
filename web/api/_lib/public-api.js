function browserAllowed(req) {
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(':')[0].toLowerCase();
  const fcRuntime = process.env.SONGBOT_RUNTIME === 'aliyun-fc';
  const allowedHost = fcRuntime || host === 'editor.teacharm.moe'
    || host === 'bot-editor.vercel.app'
    || /^bot-editor-[a-z0-9-]+-licchas-projects\.vercel\.app$/.test(host)
    || host === 'localhost'
    || host === '127.0.0.1';
  if (!allowedHost) return false;
  const origin = String(req.headers.origin || '').toLowerCase();
  if (!origin) return req.method === 'GET' || String(req.headers['sec-fetch-site'] || '') === 'same-origin';
  try {
    const originHost = new URL(origin).hostname.toLowerCase();
    return fcRuntime
      ? originHost === 'editor.teacharm.moe' || originHost === 'bot-editor.vercel.app'
        || /^bot-editor-[a-z0-9-]+-licchas-projects\.vercel\.app$/.test(originHost)
      : originHost === host;
  }
  catch (_) { return false; }
}
function json(res, status, value) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.end(JSON.stringify(value));
}
function safeError(res, error) {
  const status = Number(error.statusCode || error.status || 500);
  const safeStatus = status >= 400 && status < 600 ? status : 500;
  if (safeStatus >= 500) console.error('public-cloud-api', {
    name: String(error.name || 'Error').slice(0, 80),
    code: String(error.code || 'internal').slice(0, 80),
    status: safeStatus
  });
  json(res, safeStatus, { error: safeStatus === 429 ? 'rate limited' : safeStatus === 400 ? 'invalid request' : 'internal' });
}

module.exports = { browserAllowed, json, safeError };
