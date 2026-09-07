'use strict';

function testWritesEnabled() {
  return process.env.NODE_ENV === 'test'
    && process.env.PORTFOLIO_TEST_WRITES === '1';
}

function rejectMutation(req, res, json) {
  const method = String(req?.method || 'GET').toUpperCase();
  if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS' || testWritesEnabled()) return false;
  json(res, 403, { error: 'portfolio showcase is read-only' });
  return true;
}

module.exports = { rejectMutation };
