const test = require('node:test');
const assert = require('node:assert/strict');

const { handleEvent } = require('../fc/index');
const security = require('../api/_lib/security');

function event(overrides = {}) {
  return {
    rawPath: '/api/mobile-data',
    headers: { Origin: 'https://editor.teacharm.moe', 'Content-Type': 'application/json' },
    queryParameters: { action: 'song' },
    body: '{"id":"1278"}',
    isBase64Encoded: false,
    requestContext: { http: { method: 'POST', path: '/api/mobile-data', sourceIp: '203.0.113.8' } },
    ...overrides,
  };
}

test('FC adapter preserves the existing Vercel handler contract and trusted source IP', async () => {
  let observed;
  const response = await handleEvent(event({
    headers: {
      Origin: 'https://editor.teacharm.moe',
      Authorization: 'Device redacted',
      'Content-Type': 'application/json',
      'X-Fc-Source-Ip': '198.51.100.44',
    },
  }), {
    '/api/mobile-data': async (req, res) => {
      observed = req;
      res.statusCode = 201;
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ ok: true }));
    },
  });
  assert.equal(observed.method, 'POST');
  assert.equal(observed.headers.authorization, 'Device redacted');
  assert.equal(observed.headers['x-fc-source-ip'], '203.0.113.8');
  assert.deepEqual(observed.query, { action: 'song' });
  assert.equal(observed.body, '{"id":"1278"}');
  assert.equal(security.trustedEdgeIp(observed), '203.0.113.8');
  assert.equal(response.statusCode, 201);
  assert.equal(response.headers['Access-Control-Allow-Origin'], undefined);
});

test('FC adapter permits only approved Editor origins during preflight', async () => {
  const allowed = await handleEvent(event({
    headers: { Origin: 'https://editor.teacharm.moe' },
    requestContext: { http: { method: 'OPTIONS', path: '/api/mobile-data', sourceIp: '203.0.113.8' } },
  }));
  assert.equal(allowed.statusCode, 204);
  assert.deepEqual(allowed.headers, {});

  const denied = await handleEvent(event({
    headers: { Origin: 'https://evil.example' },
    requestContext: { http: { method: 'OPTIONS', path: '/api/mobile-data', sourceIp: '203.0.113.8' } },
  }));
  assert.equal(denied.statusCode, 403);
  assert.equal(denied.headers?.['Access-Control-Allow-Origin'], undefined);
});
