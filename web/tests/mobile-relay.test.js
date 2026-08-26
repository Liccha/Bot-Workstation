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

test('completed requests release their slots after the device receives the result', async () => {
  const registered = await call('POST', 'register-device', { headers: desktop, body: { name: '连续翻页设备' } });
  const headers = { authorization: `Device ${registered.body.token}` };
  for (let page = 0; page < 9; page++) {
    const submitted = await call('POST', 'submit', { headers, body: {
      method: 'GET', path: '/api/songs', query: { offset: String(page * 200), limit: '200' }
    } });
    assert.equal(submitted.status, 202, `第 ${page + 1} 次请求不应触发 rate limited`);
    const polled = await call('GET', 'desktop-poll', { headers: desktop });
    const work = polled.body.items.find(item => item.id === submitted.body.id);
    assert.ok(work, `第 ${page + 1} 次请求应被电脑端取走`);
    assert.equal((await call('POST', 'desktop-complete', { headers: desktop, body: {
      id: work.id, claimToken: work.claimToken, status: 200, body: { items: [], total: 1257 }
    } })).status, 200);
    assert.equal((await call('GET', 'result', { headers, query: { id: submitted.body.id } })).status, 200);
  }
  const concurrent = await Promise.all([9, 10].map(page => call('POST', 'submit', { headers, body: {
    method: 'GET', path: '/api/songs', query: { offset: String(page * 200), limit: '200' }
  } })));
  assert.deepEqual(concurrent.map(result => result.status), [202, 202]);
  const polled = await call('GET', 'desktop-poll', { headers: desktop });
  assert.deepEqual(new Set(polled.body.items.map(item => item.id)), new Set(concurrent.map(result => result.body.id)));
});

test('empty desktop poll does not take or wait for the queue lock', async () => {
  const lockKey = 'locks/mobile-relay-queue.json';
  await getStore().put(lockKey, Buffer.from(JSON.stringify({
    token: crypto.randomUUID(), expiresAt: new Date(Date.now() + 30_000).toISOString()
  })));
  const started = Date.now();
  try {
    const polled = await call('GET', 'desktop-poll', { headers: desktop });
    assert.equal(polled.status, 200);
    assert.deepEqual(polled.body.items, []);
    assert.ok(Date.now() - started < 1_000, 'empty poll waited for the queue lock');
  } finally {
    await getStore().delete(lockKey);
  }
});

test('independent request objects preserve concurrent mobile submissions', async () => {
  const registered = await call('POST', 'register-device', { headers: desktop, body: { name: '并发设备' } });
  const headers = { authorization: `Device ${registered.body.token}` };
  const [first, second] = await Promise.all([
    call('POST', 'submit', { headers, body: { method: 'GET', path: '/api/status' } }),
    call('POST', 'submit', { headers, body: { method: 'GET', path: '/api/update' } })
  ]);
  assert.equal(first.status, 202);
  assert.equal(second.status, 202);
  const polled = await call('GET', 'desktop-poll', { headers: desktop });
  assert.deepEqual(new Set(polled.body.items.map(item => item.id)), new Set([first.body.id, second.body.id]));
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
