const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'songbot-likes-test-'));
process.env.ANNOUNCEMENT_STORAGE = 'local';
process.env.ANNOUNCEMENT_LOCAL_DIR = root;
process.env.ANNOUNCEMENT_ADMIN_SALT = 'test-salt';
process.env.ANNOUNCEMENT_ADMIN_HASH = 'unused-in-like-tests';
process.env.ANNOUNCEMENT_SESSION_SECRET = 'like-fingerprint-secret-for-tests';
process.env.ANNOUNCEMENT_BOT_TOKEN = 'unused-bot-token';
process.env.ANNOUNCEMENT_DESKTOP_TOKEN = 'unused-desktop-token';
process.env.ANNOUNCEMENT_HIDDEN_GROUP_ID = 'unused-group';

const metaHandler = require('../api/meta');
const likeHandler = require('../api/like');
const { getStore } = require('../api/_lib/storage');
const { CURRENT_KEY } = require('../api/_lib/likes');

function call(handler, method, { ip = '203.0.113.10', device, body, host = 'editor.teacharm.moe', origin = 'https://editor.teacharm.moe' } = {}) {
  return new Promise((resolve, reject) => {
    const headers = { host, origin, 'x-forwarded-for': ip };
    if (device) headers['x-like-device'] = device;
    const req = {
      method,
      headers,
      body: body || {},
      socket: { remoteAddress: ip }
    };
    const responseHeaders = {};
    const res = {
      statusCode: 200,
      setHeader(name, value) { responseHeaders[name.toLowerCase()] = value; },
      end(payload) {
        try { resolve({ status: this.statusCode, headers: responseHeaders, body: JSON.parse(payload || '{}') }); }
        catch (error) { reject(error); }
      }
    };
    Promise.resolve(handler(req, res)).catch(reject);
  });
}

test.before(async () => {
  await getStore().put(CURRENT_KEY, Buffer.from(JSON.stringify({
    schema: 1, revision: 1, counts: { 27: 1, 1087: 2 }, days: {}
  })));
});

test('cloud metadata starts with migrated aggregate counts', async () => {
  const result = await call(metaHandler, 'GET');
  assert.equal(result.status, 200);
  assert.deepEqual(result.body.likes, { 27: 1, 1087: 2 });
  assert.deepEqual(result.body.likedToday, []);
  assert.equal(result.headers['cache-control'], 'no-store');
});

test('same IP can like once, sees red-state metadata, and can unlike', async () => {
  const first = await call(likeHandler, 'POST', { body: { id: 27 } });
  assert.deepEqual(first.body, { id: 27, count: 2, liked: true });

  const duplicate = await call(likeHandler, 'POST', { body: { id: 27 } });
  assert.deepEqual(duplicate.body, { id: 27, count: 2, liked: true });

  const meta = await call(metaHandler, 'GET');
  assert.deepEqual(meta.body.likedToday, [27]);

  const removed = await call(likeHandler, 'DELETE', { body: { id: 27 } });
  assert.deepEqual(removed.body, { id: 27, count: 1, liked: false });
  assert.deepEqual((await call(metaHandler, 'GET')).body.likedToday, []);
});

test('different IPs increment independently and concurrent writes are serialized', async () => {
  const [a, b] = await Promise.all([
    call(likeHandler, 'POST', { ip: '203.0.113.20', body: { id: 1087 } }),
    call(likeHandler, 'POST', { ip: '203.0.113.21', body: { id: 1087 } })
  ]);
  assert.equal(a.status, 200);
  assert.equal(b.status, 200);
  assert.equal((await call(metaHandler, 'GET', { ip: '203.0.113.22' })).body.likes['1087'], 4);
});

test('anonymous browser identity keeps liked state when its network IP changes', async () => {
  const device = 'anonymous-device-0123456789abcdef';
  const liked = await call(likeHandler, 'POST', { ip: '198.51.100.40', device, body: { id: 371 } });
  assert.equal(liked.body.liked, true);
  const afterNetworkChange = await call(metaHandler, 'GET', { ip: '198.51.100.41', device });
  assert.ok(afterNetworkChange.body.likedToday.includes(371));
});

test('invalid song ids and cross-origin writes are rejected', async () => {
  assert.equal((await call(likeHandler, 'POST', { body: { id: '../bad' } })).status, 400);
  assert.equal((await call(likeHandler, 'POST', { body: { id: 12 }, origin: 'https://evil.example' })).status, 403);
});

test('main-card liked selector overrides the designer color', () => {
  const css = fs.readFileSync(path.join(__dirname, '..', 'assets', 'css', 'app.css'), 'utf8');
  assert.match(css, /#libGrid \.lib-card \.heart\.liked\s*\{\s*color:#ef4444/);
});
