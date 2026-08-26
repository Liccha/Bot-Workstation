const visits = require('./_lib/visits');
const { browserAllowed, json, safeError } = require('./_lib/public-api');

module.exports = async function handler(req, res) {
  if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
  if (!browserAllowed(req)) return json(res, 403, { error: 'origin not allowed' });
  try { return json(res, 200, await visits.record(req)); }
  catch (error) { return safeError(res, error); }
};
