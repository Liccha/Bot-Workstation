const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'songbot-mobile-relay-test-'));
process.env.ANNOUNCEMENT_STORAGE = 'local';
process.env.ANNOUNCEMENT_LOCAL_DIR = root;
process.env.ANNOUNCEMENT_ADMIN_SALT = 'relay-test-salt';
process.env.ANNOUNCEMENT_ADMIN_HASH = crypto.scryptSync('relaytestpassword', 'relay-test-salt', 32).toString('hex');
process.env.ANNOUNCEMENT_SESSION_SECRET = 'relay-session-secret-for-tests-only';
process.env.ANNOUNCEMENT_BOT_TOKEN = 'relay-bot-token-for-tests-only';
process.env.ANNOUNCEMENT_DESKTOP_TOKEN = 'relay-desktop-token-for-tests-only';
process.env.ANNOUNCEMENT_HIDDEN_GROUP_ID = 'relay-hidden-group';

const handler = require('../api/mobile-relay');
const { getStore } = require('../api/_lib/storage');
const desktop = { authorization: 'Desktop relay-desktop-token-for-tests-only' };

function call(method, action, { body, headers = {}, query = {}, ip = '203.0.113.10' } = {}) {
  return new Promise((resolve, reject) => {
    const req = { method, query: { action, ...query }, headers, body, socket: { remoteAddress: ip } };
    const res = { statusCode: 200, setHeader() {}, end(payload) {
      try { resolve({ status: this.statusCode, body: payload ? JSON.parse(payload) : null }); }
      catch (error) { reject(error); }
    } };
    Promise.resolve(handler(req, res)).catch(reject);
  });
}

test('pairing creates a revocable hidden device account without storing its secret', async () => {
  const registered = await call('POST', 'register-device', { headers: desktop, body: { name: '测试手机' } });
  assert.equal(registered.status, 201);
  assert.match(registered.body.token, /^[a-f0-9-]{36}\.[A-Za-z0-9_-]{32,}$/);
  const stored = (await getStore().get('security/mobile-devices.json')).body.toString('utf8');
  assert.doesNotMatch(stored, new RegExp(registered.body.token.split('.')[1]));
  const listed = await call('GET', 'devices', { headers: desktop });
  assert.equal(listed.body.items[0].name, '测试手机');
  assert.equal(Object.hasOwn(listed.body.items[0], 'secretHash'), false);
});

test('device request crosses networks, is executed once, and returns only to its account', async () => {
  const registered = await call('POST', 'register-device', { headers: desktop, body: { name: '跨网设备' } });
  const deviceHeaders = { authorization: `Device ${registered.body.token}` };
  const submitted = await call('POST', 'submit', { headers: deviceHeaders, ip: '198.51.100.1', body: {
    method: 'GET', path: '/api/songs', query: { q: 'test', offset: '0', limit: '200' }
  } });
  assert.equal(submitted.status, 202);
  const polled = await call('GET', 'desktop-poll', { headers: desktop });
  assert.equal(polled.body.items.length, 1);
  assert.equal(polled.body.items[0].payload.path, '/api/songs');
  assert.equal((await call('GET', 'desktop-poll', { headers: desktop })).body.items.length, 0);
  const work = polled.body.items[0];
  assert.equal((await call('POST', 'desktop-complete', { headers: desktop, body: {
    id: work.id, claimToken: work.claimToken, status: 200, body: { items: [{ id: '1' }], total: 1 }
  } })).status, 200);
  const result = await call('GET', 'result', { headers: deviceHeaders, ip: '198.51.100.99', query: { id: submitted.body.id } });
  assert.equal(result.status, 200);
  assert.equal(result.body.response.body.items[0].id, '1');
  const other = await call('POST', 'register-device', { headers: desktop, body: { name: '其他设备' } });
  assert.equal((await call('GET', 'result', { headers: { authorization: `Device ${other.body.token}` }, query: { id: submitted.body.id } })).status, 404);
});

test('relay rejects arbitrary paths and revoked accounts immediately', async () => {
  const registered = await call('POST', 'register-device', { headers: desktop, body: { name: '待撤销' } });
  const headers = { authorization: `Device ${registered.body.token}` };
  assert.equal((await call('POST', 'submit', { headers, body: { method: 'POST', path: '/api/announcement-cloud', body: {} } })).status, 400);
  assert.equal((await call('POST', 'revoke-device', { headers: desktop, body: { id: registered.body.id } })).status, 200);
  assert.equal((await call('POST', 'submit', { headers, body: { method: 'GET', path: '/api/status' } })).status, 401);
});

test('asset tickets are scoped to the device and rate limited', async () => {
  const registered = await call('POST', 'register-device', { headers: desktop, body: { name: '资源上传设备' } });
  const headers = { authorization: `Device ${registered.body.token}` };
  const ticket = await call('POST', 'asset-ticket', { headers, body: {
    type: 'image', size: 1024, extension: '.png', contentType: 'image/png'
  } });
  assert.equal(ticket.status, 200);
  assert.match(ticket.body.key, new RegExp(`^mobile-assets/${registered.body.id}/`));
  assert.equal((await call('POST', 'asset-ticket', { headers, body: {
    type: 'image', size: 1024, extension: '.png', contentType: 'image/png'
  } })).status, 429);
  const other = await call('POST', 'register-device', { headers: desktop, body: { name: '其他资源设备' } });
  assert.equal((await call('POST', 'submit', { headers: { authorization: `Device ${other.body.token}` }, body: {
    method: 'POST', path: '/api/song-asset', body: { id: '1', type: 'image', key: ticket.body.key }
  } })).status, 400);
});

test('emergency lock blocks device creation and queued operations', async () => {
  const registered = await call('POST', 'register-device', { headers: desktop, body: { name: '锁定测试' } });
  process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK = '1';
  try {
    assert.equal((await call('POST', 'register-device', { headers: desktop, body: { name: '禁止创建' } })).status, 423);
    assert.equal((await call('POST', 'submit', { headers: { authorization: `Device ${registered.body.token}` }, body: {
      method: 'GET', path: '/api/status'
    } })).status, 423);
  } finally { delete process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK; }
});
