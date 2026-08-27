const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'songbot-mobile-data-test-'));
process.env.ANNOUNCEMENT_STORAGE = 'local';
process.env.ANNOUNCEMENT_LOCAL_DIR = root;
process.env.ANNOUNCEMENT_ADMIN_SALT = 'mobile-data-test-salt';
process.env.ANNOUNCEMENT_ADMIN_HASH = crypto.scryptSync('password', 'mobile-data-test-salt', 32).toString('hex');
process.env.ANNOUNCEMENT_SESSION_SECRET = 'mobile-data-session-secret';
process.env.ANNOUNCEMENT_BOT_TOKEN = 'mobile-data-bot-token';
process.env.ANNOUNCEMENT_DESKTOP_TOKEN = 'mobile-data-desktop-token';
process.env.ANNOUNCEMENT_HIDDEN_GROUP_ID = 'mobile-data-group';

const relay = require('../api/mobile-relay');
const data = require('../api/mobile-data');
const desktop = { authorization: 'Desktop mobile-data-desktop-token' };

function call(handler, method, action, { body, headers = {}, query = {} } = {}) {
  return new Promise((resolve, reject) => {
    const req = { method, query: { action, ...query }, headers, body, socket: { remoteAddress: '203.0.113.4' } };
    const res = { statusCode: 200, setHeader() {}, end(payload) {
      try { resolve({ status: this.statusCode, body: payload ? JSON.parse(payload) : null }); }
      catch (error) { reject(error); }
    } };
    Promise.resolve(handler(req, res)).catch(reject);
  });
}

test('paired device reads and edits cloud data with no desktop poll', async () => {
  const songs = await call(data, 'POST', 'bootstrap-songs', { headers: desktop, body: {
    columns: ['id', 'song_name', 'author', '4k_ez'],
    items: [
      { id: '1', song_name: '第一首', author: 'A', '4k_ez': '1-100' },
      { id: '3', song_name: '第三首', author: 'B', '4k_ez': '0-0' }
    ]
  } });
  assert.equal(songs.status, 201);
  const stable = await call(data, 'POST', 'bootstrap-stable', { headers: desktop, body: {
    columns: ['sid', 'title', 'artist', 'bpm', 'length', 'creator', 'update_time', 'cover'],
    items: [{ sid: '22', title: 'Stable', artist: 'C', bpm: '120', length: '90', creator: 'D', update_time: '2026-08-27', cover: 'AUTO' }]
  } });
  assert.equal(stable.status, 201);

  const registered = await call(relay, 'POST', 'register-device', { headers: desktop, body: { name: '独立手机' } });
  const headers = { authorization: `Device ${registered.body.token}` };
  const listed = await call(data, 'GET', 'songs', { headers, query: { q: '第三', offset: '0', limit: '200' } });
  assert.equal(listed.status, 200);
  assert.equal(listed.body.total, 1);
  assert.equal(listed.body.items[0].id, '3');

  const updated = await call(data, 'POST', 'song', { headers, body: { id: '3', values: { song_name: '离线电脑也能修改', id: '999' } } });
  assert.equal(updated.status, 200);
  const changes = await call(data, 'GET', 'changes', {
    headers: desktop,
    query: { dataset: 'songs', after: '1', limit: '100' }
  });
  assert.equal(changes.status, 200, 'workstation agent must be able to pull cloud edits');
  assert.equal(changes.body.items.length, 1);
  assert.equal(changes.body.items[0].id, '3');
  assert.equal(changes.body.items[0].values.song_name, '离线电脑也能修改');
  assert.equal(changes.body.revision, updated.body.revision);
  assert.equal((await call(data, 'GET', 'changes', {
    headers, query: { dataset: 'songs', after: '1' }
  })).status, 401, 'change feed must remain workstation-only');
  const after = await call(data, 'GET', 'songs', { headers, query: { q: '离线电脑', offset: '0', limit: '200' } });
  assert.equal(after.body.items[0].id, '3');
  assert.equal(after.body.items[0].song_name, '离线电脑也能修改');
  assert.equal((await call(data, 'GET', 'status', { headers })).body.cloudIndependent, true);
});

test('revocation and emergency write lock apply to direct cloud data', async () => {
  const registered = await call(relay, 'POST', 'register-device', { headers: desktop, body: { name: '可撤销手机' } });
  const headers = { authorization: `Device ${registered.body.token}` };
  process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK = '1';
  try {
    assert.equal((await call(data, 'POST', 'stable', { headers, body: { sid: '22', values: { title: 'blocked' } } })).status, 423);
  } finally { delete process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK; }
  await call(relay, 'POST', 'revoke-device', { headers: desktop, body: { id: registered.body.id } });
  assert.equal((await call(data, 'GET', 'songs', { headers })).status, 401);
});
