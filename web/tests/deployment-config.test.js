const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

process.env.ANNOUNCEMENT_ADMIN_SALT = 'config-test-salt';
process.env.ANNOUNCEMENT_ADMIN_HASH = 'config-test-hash';
process.env.ANNOUNCEMENT_SESSION_SECRET = 'config-test-session';
process.env.ANNOUNCEMENT_BOT_TOKEN = 'config-test-bot';
process.env.ANNOUNCEMENT_DESKTOP_TOKEN = 'config-test-desktop';
process.env.ANNOUNCEMENT_HIDDEN_GROUP_ID = 'config-test-group';
process.env.ALI_OSS_REGION = 'oss-cn-beijing';
process.env.ALI_OSS_BUCKET = 'config-test-bucket';
process.env.ALI_OSS_ACCESS_KEY_ID = 'config-test-id';
process.env.ALI_OSS_ACCESS_KEY_SECRET = 'config-test-secret';

const { config } = require('../api/_lib/config');

test('serverless functions avoid the failing Hong Kong to Beijing storage route', () => {
  const vercel = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'vercel.json'), 'utf8'));
  assert.deepEqual(vercel.regions, ['hnd1']);
});

test('OSS calls use a bounded timeout instead of the SDK 60 second default', () => {
  const value = config();
  assert.equal(value.local, false);
  assert.ok(Number.isInteger(value.oss.timeout));
  assert.ok(value.oss.timeout >= 3000 && value.oss.timeout <= 15000,
    `OSS timeout remains excessive: ${value.oss.timeout}`);
  assert.equal(value.oss.retryMax, 0, 'the SDK must not silently repeat a timed-out full write');
});
