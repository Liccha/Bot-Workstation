const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');

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
const { getStore } = require('../api/_lib/storage');
const repo = require('../api/_lib/repository');
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
    columns: ['id', 'song_name', 'author', '4k_ez', 'album_ids', 'album_image_path', 'image_path', 'audio_path'],
    items: [
      { id: '1', song_name: '第一首', author: 'A', '4k_ez': '1-100', album_ids: '100' },
      { id: '3', song_name: '第三首', author: 'B', '4k_ez': '0-0', album_ids: '200' }
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

test('album_ids updates are visible through the exact song endpoint', async () => {
  const updated = await call(data, 'POST', 'song', {
    headers: desktop,
    body: { id: '3', values: { album_ids: '209' } }
  });
  assert.equal(updated.status, 200);
  const exact = await call(data, 'GET', 'song-item', {
    headers: desktop,
    query: { id: '3' }
  });
  assert.equal(exact.status, 200);
  assert.equal(exact.body.id, '3');
  assert.equal(exact.body.album_ids, '209');
});

test('workstation can obtain a short-lived download URL for managed song media', async () => {
  const key = 'mobile-library/assets/image/3/11111111-2222-4333-8444-555555555555.jpg';
  const store = getStore();
  const originalHead = store.head.bind(store);
  store.head = async () => { throw new Error('object store temporarily unavailable'); };
  let response;
  try {
    response = await call(data, 'GET', 'asset-download', {
      headers: desktop,
      query: { key }
    });
  } finally {
    store.head = originalHead;
  }
  assert.equal(response.status, 200);
  assert.equal(response.body.key, key);
  assert.match(response.body.url, /announcement-cloud/);
  assert.equal('size' in response.body, false, 'issuing a signed URL must not make a paid OSS HEAD request');

  assert.equal((await call(data, 'GET', 'asset-download', {
    query: { key }
  })).status, 401, 'song media download tickets must require workstation authentication');
  assert.equal((await call(data, 'GET', 'asset-download', {
    headers: desktop,
    query: { key: 'mobile-library/assets/image/3/../../secret.jpg' }
  })).status, 400, 'unsafe object keys must be rejected before signing');
});

test('authenticated clients can bypass a failed server-side snapshot read with a signed compact snapshot ticket', async () => {
  const store = getStore();
  const originalGet = store.get.bind(store);
  store.get = async key => {
    if (key === 'mobile-library/songs/current.json.gz') throw new Error('cross-region read timed out');
    return originalGet(key);
  };
  let ticket;
  try {
    ticket = await call(data, 'GET', 'snapshot-ticket', {
      headers: desktop,
      query: { dataset: 'songs' }
    });
  } finally {
    store.get = originalGet;
  }
  assert.equal(ticket.status, 200);
  assert.equal(ticket.body.dataset, 'songs');
  assert.equal(ticket.body.encoding, 'gzip-json');
  assert.match(ticket.body.url, /announcement-cloud/);
  assert.equal((await call(data, 'GET', 'snapshot-ticket', {
    query: { dataset: 'songs' }
  })).status, 401, 'snapshot tickets must remain authenticated');
  assert.equal((await call(data, 'GET', 'snapshot-ticket', {
    headers: desktop,
    query: { dataset: 'unknown' }
  })).status, 400, 'only known snapshots may be signed');
});

test('a contended song write reports write_busy instead of pretending data is uninitialized', async () => {
  let release;
  const held = repo.withLock('mobile-library-songs', () => new Promise(resolve => { release = resolve; }));
  const store = getStore();
  for (let attempt = 0; attempt < 50 && !await store.get('locks/mobile-library-songs.json'); attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2));
  }
  const originalSetTimeout = global.setTimeout;
  global.setTimeout = (callback, delay, ...args) => originalSetTimeout(callback, Math.min(Number(delay) || 0, 1), ...args);
  let response;
  try {
    response = await call(data, 'POST', 'song', {
      headers: desktop,
      body: { id: '3', values: { album_ids: '210' } }
    });
  } finally {
    global.setTimeout = originalSetTimeout;
    release();
    await held;
  }
  assert.equal(response.status, 503);
  assert.equal(response.body.code, 'write_busy');
  assert.equal(response.body.error, 'cloud data busy');
});

test('ordinary updates write only the compact snapshot and preserve the legacy raw fallback', async () => {
  const store = getStore();
  const rawKey = 'mobile-library/songs/current.json';
  const compactKey = 'mobile-library/songs/current.json.gz';
  const rawBefore = await store.get(rawKey);
  const writes = [];
  const originalPut = store.put.bind(store);
  store.put = async (key, ...args) => {
    writes.push(key);
    return originalPut(key, ...args);
  };
  try {
    const response = await call(data, 'POST', 'song', {
      headers: desktop,
      body: { id: '3', values: { album_ids: '210' } }
    });
    assert.equal(response.status, 200);
  } finally {
    store.put = originalPut;
  }
  const rawAfter = await store.get(rawKey);
  assert.deepEqual(rawAfter.body, rawBefore.body, 'an ordinary field edit rewrote the 1 MB legacy snapshot');
  assert.ok(writes.includes(compactKey), 'the authoritative compact snapshot was not written');
  assert.ok(!writes.includes(rawKey), 'the legacy raw snapshot must not be uploaded on every edit');
  assert.ok(!writes.some(key => key.startsWith('audit/')),
    'a committed library edit must not fail later on a duplicate audit upload');
});

test('status repairs a revision marker left behind by a completed compact snapshot write', async () => {
  const store = getStore();
  const compact = await store.get('mobile-library/songs/current.json.gz');
  const document = JSON.parse(zlib.gunzipSync(compact.body).toString('utf8'));
  await store.put('mobile-library/songs/revision.json', Buffer.from(JSON.stringify({
    schema: 1,
    dataset: 'songs',
    revision: Math.max(0, Number(document.revision || 0) - 1),
    updatedAt: '2000-01-01T00:00:00.000Z',
    total: document.items.length,
    snapshotEtag: 'stale-etag'
  })));
  const response = await call(data, 'GET', 'status', { headers: desktop });
  assert.equal(response.status, 200);
  assert.equal(response.body.songs.revision, document.revision);
  const repaired = JSON.parse((await store.get('mobile-library/songs/revision.json')).body.toString('utf8'));
  assert.equal(repaired.revision, document.revision);
  assert.equal(repaired.snapshotEtag, compact.etag);
});

test('external editor mutations do not rewrite or globally lock the editor registry', async () => {
  const installationId = crypto.randomUUID();
  const secret = crypto.randomBytes(32).toString('base64url');
  const enrolled = await call(data, 'POST', 'enroll-editor', {
    headers: { 'x-vercel-forwarded-for': '198.51.100.101' },
    body: { installationId, secret, name: '独立计数编辑器' }
  });
  assert.equal(enrolled.status, 201);
  const store = getStore();
  const writes = [];
  const usageWriteOptions = [];
  const originalPut = store.put.bind(store);
  store.put = async (key, ...args) => {
    writes.push(key);
    if (key.startsWith(`security/library-editor-usage/${enrolled.body.id}/`)) {
      usageWriteOptions.push(args[1] || {});
    }
    return originalPut(key, ...args);
  };
  try {
    const updated = await call(data, 'POST', 'song', {
      headers: { authorization: `Device ${enrolled.body.token}` },
      body: { id: '3', values: { album_ids: '211' } }
    });
    assert.equal(updated.status, 200);
  } finally {
    store.put = originalPut;
  }
  assert.ok(!writes.includes('security/library-editors.json'), 'every edit rewrote the global editor registry');
  assert.ok(!writes.includes('locks/library-editors.json'), 'unrelated editors still share one mutation lock');
  assert.ok(writes.some(key => key.startsWith(`locks/library-editor-usage-${enrolled.body.id}-`)),
    'the mutation counter is not isolated to this editor');
  assert.ok(writes.some(key => key.startsWith(`security/library-editor-usage/${enrolled.body.id}/`)),
    'the lightweight per-editor mutation guard was not recorded');
  assert.ok(usageWriteOptions.every(options => !options.ifMatch && !options.ifNoneMatch),
    'OSS does not implement conditional headers on ordinary PUT requests');
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

test('self-enrolled workstation editor can edit library data but cannot use the control relay', async () => {
  const installationId = crypto.randomUUID();
  const secret = crypto.randomBytes(32).toString('base64url');
  const enrolled = await call(data, 'POST', 'enroll-editor', {
    headers: { 'x-vercel-forwarded-for': '198.51.100.77' },
    body: { installationId, secret, name: '外部工作站' }
  });
  assert.equal(enrolled.status, 201);
  assert.equal(enrolled.body.scope, 'library-editor');
  assert.equal(enrolled.body.token.endsWith(`.${secret}`), true);
  const stored = (await getStore().get('security/library-editors.json')).body.toString('utf8');
  assert.doesNotMatch(stored, new RegExp(secret));

  const headers = { authorization: `Device ${enrolled.body.token}` };
  const listed = await call(data, 'GET', 'songs', { headers, query: { q: '第一', limit: '200' } });
  assert.equal(listed.status, 200);
  assert.equal(listed.body.items[0].id, '1');
  const updated = await call(data, 'POST', 'song', {
    headers,
    body: { id: '1', values: { song_name: '外部工作站修改' } }
  });
  assert.equal(updated.status, 200);
  const created = await call(data, 'POST', 'song-create', {
    headers,
    body: { id: '1273', values: { song_name: '云端新歌', author: '新作者', '4k_ez': '3-100',
      image_path: 'C:\\Users\\editor\\Desktop\\合集\\1273.jpg',
      audio_path: 'C:\\Users\\editor\\Desktop\\preview\\1273.mp3' } }
  });
  assert.equal(created.status, 201);
  assert.equal(created.body.created, true);
  const createdList = await call(data, 'GET', 'songs', { headers, query: { q: '1273', limit: '200' } });
  assert.equal(createdList.body.total, 1);
  assert.equal(createdList.body.items[0].song_name, '云端新歌');
  assert.equal(createdList.body.items[0].image_path, '', 'a workstation image path leaked into cloud metadata');
  assert.equal(createdList.body.items[0].audio_path, '', 'a workstation audio path leaked into cloud metadata');
  await call(data, 'POST', 'song', { headers, body: { id: '1273', values: {
    audio_path: 'C:\\Users\\editor\\Desktop\\preview\\1273.mp3'
  } } });
  const pathUpdateList = await call(data, 'GET', 'songs', { headers, query: { q: '1273', limit: '200' } });
  assert.equal(pathUpdateList.body.items[0].audio_path, '', 'a workstation path leaked through a normal metadata update');
  const partialUpload = `mobile-library/uploads/${enrolled.body.id}/partial.webp`;
  await getStore().put(partialUpload, Buffer.from('partial-cover'));
  const partialAsset = await call(data, 'POST', 'song-asset', {
    headers, body: { id: '1273', type: 'image', key: partialUpload }
  });
  assert.equal(partialAsset.status, 200);
  const partialList = await call(data, 'GET', 'songs', { headers, query: { q: '1273', limit: '200' } });
  assert.equal(partialList.body.items[0].image_path,
    'cloud-object:mobile-library/assets/image/1273/partial.webp', 'asset pointer was not committed deterministically');
  const resumed = await call(data, 'POST', 'song-create', {
    headers,
    body: { id: '1273', values: { song_name: '云端新歌', author: '新作者', '4k_ez': '3-100',
      image_path: 'C:\\Users\\editor\\Desktop\\合集\\1273.jpg',
      audio_path: 'C:\\Users\\editor\\Desktop\\preview\\1273.mp3' } }
  });
  assert.equal(resumed.status, 200, 'a retry after a partial asset upload must resume idempotently');
  assert.equal(resumed.body.resumed, true);
  const replayedAsset = await call(data, 'POST', 'song-asset', {
    headers, body: { id: '1273', type: 'image', key: partialUpload }
  });
  assert.equal(replayedAsset.status, 200,
    `a lost asset response must be safely replayable after its upload source is deleted: ${JSON.stringify(replayedAsset.body)}`);
  assert.equal(replayedAsset.body.replayed, true);
  const conflicting = await call(data, 'POST', 'song-create', {
    headers, body: { id: '1273', values: { song_name: '重复' } }
  });
  assert.equal(conflicting.status, 409, 'duplicate cloud song IDs must be rejected without overwriting');
  assert.equal(conflicting.body.error, 'record already exists');
  await getStore().put('mobile-library/assets/image/1273/old.webp', Buffer.from('old-cover'));
  await getStore().put('mobile-library/assets/image/1273/current.webp', Buffer.from('current-cover'));
  await getStore().put('mobile-library/assets/audio/1273/current.mp3', Buffer.from('current-audio'));
  const removed = await call(data, 'POST', 'song-delete', { headers, body: { id: '1273' } });
  assert.equal(removed.status, 200);
  assert.equal(removed.body.deleted, true);
  assert.equal(await getStore().get('mobile-library/assets/image/1273/old.webp'), null,
    'deleting a song left an old cloud cover behind');
  assert.equal(await getStore().get('mobile-library/assets/image/1273/current.webp'), null,
    'deleting a song left its current cloud cover behind');
  assert.equal(await getStore().get('mobile-library/assets/audio/1273/current.mp3'), null,
    'deleting a song left its cloud audio behind');
  assert.equal((await call(data, 'GET', 'songs', { headers, query: { q: '1273', limit: '200' } })).body.total, 0,
    'deleted song ID remained occupied');
  assert.equal((await call(data, 'POST', 'song-create', {
    headers, body: { id: '1273', values: { song_name: '释放后复用', author: '新作者' } }
  })).status, 201, 'released cloud song ID could not be reused');
  const relayAttempt = await call(relay, 'GET', 'presence', { headers });
  assert.equal(relayAttempt.status, 401, 'library-only installation must not control SongBot or NapCat');
});

test('self enrollment is idempotent for one installation secret and emergency lock still blocks it', async () => {
  const installationId = crypto.randomUUID();
  const secret = crypto.randomBytes(32).toString('base64url');
  const request = {
    headers: { 'x-vercel-forwarded-for': '198.51.100.88' },
    body: { installationId, secret, name: '重复安装' }
  };
  const first = await call(data, 'POST', 'enroll-editor', request);
  const second = await call(data, 'POST', 'enroll-editor', request);
  assert.equal(first.status, 201);
  assert.equal(second.status, 201);
  assert.equal(first.body.id, second.body.id);
  process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK = '1';
  try {
    const blocked = await call(data, 'POST', 'song', {
      headers: { authorization: `Device ${first.body.token}` },
      body: { id: '1', values: { song_name: '不应写入' } }
    });
    assert.equal(blocked.status, 423);
  } finally { delete process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK; }
});

test('an unchanged desktop change cursor never downloads the full library snapshot', async () => {
  const store = getStore();
  const currentKey = 'mobile-library/songs/current.json';
  const marker = await store.get('mobile-library/songs/revision.json');
  const revision = JSON.parse(marker.body.toString('utf8')).revision;
  const originalGet = store.get.bind(store);
  let snapshotReads = 0;
  let snapshotBytes = 0;
  store.get = async key => {
    const result = await originalGet(key);
    if (key === currentKey && result) {
      snapshotReads++;
      snapshotBytes += result.body.length;
    }
    return result;
  };
  try {
    for (let index = 0; index < 5; index++) {
      const response = await call(data, 'GET', 'changes', {
        headers: desktop,
        query: { dataset: 'songs', after: String(revision), limit: '100' }
      });
      assert.equal(response.status, 200);
      assert.deepEqual(response.body.items, []);
    }
  } finally {
    store.get = originalGet;
  }
  assert.equal(snapshotReads, 0,
    `idle synchronization downloaded the full snapshot ${snapshotReads} times (${snapshotBytes} bytes)`);
});

test('repeated cloud status checks never download full library snapshots', async () => {
  const store = getStore();
  const libraryModule = require.resolve('../api/_lib/mobile-library');
  delete require.cache[libraryModule];
  const coldLibrary = require('../api/_lib/mobile-library');
  const snapshotKeys = new Set([
    'mobile-library/songs/current.json',
    'mobile-library/stable/current.json'
  ]);
  const originalGet = store.get.bind(store);
  let snapshotReads = 0;
  let snapshotBytes = 0;
  store.get = async key => {
    const result = await originalGet(key);
    if (snapshotKeys.has(key) && result) {
      snapshotReads++;
      snapshotBytes += result.body.length;
    }
    return result;
  };
  try {
    for (let index = 0; index < 5; index++) {
      const response = await coldLibrary.status();
      assert.equal(response.songs.total, 3);
      assert.equal(response.stable.total, 1);
    }
  } finally {
    store.get = originalGet;
  }
  assert.equal(snapshotReads, 0,
    `status checks downloaded snapshots ${snapshotReads} times (${snapshotBytes} bytes)`);
});

test('cold library reads migrate to and prefer a compact gzip snapshot', async () => {
  const store = getStore();
  const rawKey = 'mobile-library/songs/current.json';
  const compactKey = 'mobile-library/songs/current.json.gz';
  const markerKey = 'mobile-library/songs/revision.json';
  // Recreate a genuine pre-migration layout: one current raw snapshot and a
  // marker that predates compact-etag tracking.
  const authoritative = await store.get(compactKey);
  await store.put(rawKey, zlib.gunzipSync(authoritative.body));
  const marker = JSON.parse((await store.get(markerKey)).body.toString('utf8'));
  delete marker.snapshotEtag;
  await store.put(markerKey, Buffer.from(JSON.stringify(marker)));
  await store.delete(compactKey);

  const libraryModule = require.resolve('../api/_lib/mobile-library');
  delete require.cache[libraryModule];
  const migratingLibrary = require('../api/_lib/mobile-library');
  const first = await migratingLibrary.list('songs', '', 0, 100);
  assert.equal(first.total, 3);
  const raw = await store.get(rawKey);
  const compact = await store.get(compactKey);
  assert.ok(compact, 'first legacy read should create the compact snapshot');
  assert.ok(compact.body.length < raw.body.length,
    `compact snapshot is not smaller (${compact.body.length} >= ${raw.body.length})`);

  delete require.cache[libraryModule];
  const coldLibrary = require('../api/_lib/mobile-library');
  const originalGet = store.get.bind(store);
  let rawReads = 0;
  store.get = async key => {
    if (key === rawKey) rawReads++;
    return originalGet(key);
  };
  try {
    const second = await coldLibrary.list('songs', '离线电脑', 0, 100);
    assert.equal(second.total, 1);
  } finally {
    store.get = originalGet;
  }
  assert.equal(rawReads, 0, 'cold reads must not download the legacy full snapshot');
});
