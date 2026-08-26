const likes = require('./_lib/likes');
const { browserAllowed, json, safeError } = require('./_lib/public-api');

function body(req) {
  if (typeof req.body === 'string') return JSON.parse(req.body || '{}');
  return req.body || {};
}

module.exports = async function handler(req, res) {
  if (req.method !== 'POST' && req.method !== 'DELETE') return json(res, 405, { error: 'method not allowed' });
  if (!browserAllowed(req)) return json(res, 403, { error: 'origin not allowed' });
  try {
    const id = Number(body(req).id);
    if (!Number.isInteger(id) || id < 1 || id > 10_000_000) return json(res, 400, { error: 'invalid song id' });
    return json(res, 200, await likes.setLike(req, id, req.method === 'POST'));
  } catch (error) { return safeError(res, error); }
};
