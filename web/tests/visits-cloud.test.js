const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'songbot-visits-test-'));
process.env.ANNOUNCEMENT_STORAGE = 'local';
process.env.ANNOUNCEMENT_LOCAL_DIR = root;
process.env.ANNOUNCEMENT_ADMIN_SALT = 'test-salt';
process.env.ANNOUNCEMENT_ADMIN_HASH = 'unused';
process.env.ANNOUNCEMENT_SESSION_SECRET = 'visit-fingerprint-secret-for-tests';
process.env.ANNOUNCEMENT_BOT_TOKEN = 'unused-bot-token';
process.env.ANNOUNCEMENT_DESKTOP_TOKEN = 'unused-desktop-token';
process.env.ANNOUNCEMENT_HIDDEN_GROUP_ID = 'unused-group';

const visitHandler = require('../api/visit');
const { readDocument } = require('../api/_lib/visits');

function call({ ip = '203.0.113.10', device = 'visit-device-0123456789abcdef', origin = 'https://editor.teacharm.moe' } = {}) {
  return new Promise((resolve, reject) => {
    const req = {
      method: 'POST',
      headers: { host: 'editor.teacharm.moe', origin, 'x-forwarded-for': ip, 'x-visit-device': device },
      socket: { remoteAddress: ip }
    };
    const res = {
      statusCode: 200,
      setHeader() {},
      end(payload) { try { resolve({ status: this.statusCode, body: JSON.parse(payload || '{}') }); } catch (error) { reject(error); } }
    };
    Promise.resolve(visitHandler(req, res)).catch(reject);
  });
}

test('records one privacy-preserving visit and suppresses an immediate duplicate', async () => {
  assert.deepEqual((await call()).body, { ok: true, recorded: true });
  assert.deepEqual((await call()).body, { ok: true, recorded: false });
  const document = await readDocument();
  assert.equal(document.total, 1);
  assert.doesNotMatch(JSON.stringify(document), /203\.0\.113\.10/);
});

test('a different device records independently', async () => {
  const result = await call({ device: 'visit-device-fedcba9876543210' });
  assert.equal(result.body.recorded, true);
  assert.equal((await readDocument()).total, 2);
});

test('cross-origin visit writes are rejected', async () => {
  assert.equal((await call({ device: 'visit-device-cross-origin-0001', origin: 'https://evil.example' })).status, 403);
  assert.equal((await readDocument()).total, 2);
});
