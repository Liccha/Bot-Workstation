const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'songbot-announcement-test-'));
const password = 'testsecreteditor';
const salt = 'test-salt';
process.env.ANNOUNCEMENT_STORAGE = 'local';
process.env.ANNOUNCEMENT_LOCAL_DIR = root;
process.env.ANNOUNCEMENT_ADMIN_SALT = salt;
process.env.ANNOUNCEMENT_ADMIN_HASH = crypto.scryptSync(password, salt, 32).toString('hex');
process.env.ANNOUNCEMENT_SESSION_SECRET = 'session-secret-for-tests-only';
process.env.ANNOUNCEMENT_BOT_TOKEN = 'bot-token-for-tests-only';
process.env.ANNOUNCEMENT_DESKTOP_TOKEN = 'desktop-token-for-tests-only';
process.env.ANNOUNCEMENT_HIDDEN_GROUP_ID = 'hidden-test-group';

const handler = require('../api/announcement-cloud');
const { getStore } = require('../api/_lib/storage');

function call(method, action, { body, headers = {}, query = {} } = {}) {
  return new Promise((resolve, reject) => {
    const req = { method, query: { action, ...query }, headers, body, socket: { remoteAddress: '127.0.0.1' } };
    const responseHeaders = {};
    const res = {
      statusCode: 200,
      setHeader(name, value) { responseHeaders[name.toLowerCase()] = value; },
      end(payload) {
        try { resolve({ status: this.statusCode, headers: responseHeaders, body: payload ? JSON.parse(payload) : null }); }
        catch (error) { reject(error); }
      }
    };
    Promise.resolve(handler(req, res)).catch(reject);
  });
}

test.before(async () => {
  await getStore().put('security/admin-devices.json', Buffer.from(JSON.stringify({ devices: [{ id: 'legacy-device', source: 'legacy' }] })));
});

test('legacy administrator is silently upgraded to a signed session', async () => {
  const result = await call('GET', 'admin-check', { headers: { 'x-admin-device': 'legacy-device' } });
  assert.equal(result.status, 200);
  assert.equal(result.body.admin, true);
  assert.match(result.headers['set-cookie'], /^sb_ann_session=[^;]+;.*HttpOnly; Secure; SameSite=Strict$/);
});

test('desktop editor has isolated token auth and writes the same cloud document', async () => {
  const desktopHeaders = { authorization: 'Desktop desktop-token-for-tests-only', 'x-admin-device': 'mczmaker-test' };
  const denied = await call('GET', 'list', { headers: { authorization: 'Bot bot-token-for-tests-only' } });
  assert.equal(denied.status, 401);
  const created = await call('POST', 'announcement', { headers: desktopHeaders, body: {
    groupId: '2000000004', title: '桌面端', content: '桌面端公告', time: '2026-08-27 10:00'
  } });
  assert.equal(created.status, 201);
  const listed = await call('GET', 'list', { headers: desktopHeaders });
  assert.ok(listed.body.some(item => item.id === created.body.id));
  assert.equal((await call('DELETE', 'announcement', { headers: desktopHeaders, query: { id: created.body.id, revision: created.body.revision } })).status, 200);
});

test('desktop password grant permanently trusts only the edge-observed IP fingerprint', async () => {
  const desktopHeaders = {
    authorization: 'Desktop desktop-token-for-tests-only',
    'x-admin-device': 'workstation-ip-test',
    'x-vercel-forwarded-for': '203.0.113.9',
    'x-forwarded-for': '198.51.100.77'
  };
  const initial = await call('GET', 'desktop-ip-check', { headers: desktopHeaders });
  assert.equal(initial.status, 200);
  assert.equal(initial.body.trusted, false);
  const granted = await call('POST', 'desktop-ip-grant', { headers: desktopHeaders, body: {} });
  assert.deepEqual(granted.body, { trusted: true });
  assert.equal((await call('GET', 'desktop-ip-check', { headers: desktopHeaders })).body.trusted, true);

  const spoofed = { ...desktopHeaders, 'x-forwarded-for': '203.0.113.9', 'x-vercel-forwarded-for': '203.0.113.10' };
  assert.equal((await call('GET', 'desktop-ip-check', { headers: spoofed })).body.trusted, false);
  const missingEdgeIdentity = { ...desktopHeaders };
  delete missingEdgeIdentity['x-vercel-forwarded-for'];
  assert.equal((await call('GET', 'desktop-ip-check', { headers: missingEdgeIdentity })).status, 400);
  assert.equal((await call('GET', 'desktop-ip-check', { headers: { ...desktopHeaders, authorization: 'Desktop wrong' } })).status, 401);

  const stored = await getStore().get('security/admin-ips.json');
  const text = stored.body.toString('utf8');
  assert.doesNotMatch(text, /203\.0\.113\.9|198\.51\.100\.77/);
  const document = JSON.parse(text);
  assert.match(document.fingerprints[0].fingerprint, /^[a-f0-9]{64}$/);
});

test('clean workstation unlocks through cloud password without a bundled secret file', async () => {
  const headers = {
    'x-admin-device': 'clean-workstation',
    'x-vercel-forwarded-for': '203.0.113.41'
  };
  assert.deepEqual((await call('GET', 'workstation-admin-check', { headers })).body, { admin: false });
  assert.deepEqual((await call('POST', 'workstation-admin-grant', {
    headers, body: { d: 'clean-workstation', p: 'wrongpassword' }
  })).body, { admin: false });
  assert.deepEqual((await call('POST', 'workstation-admin-grant', {
    headers, body: { d: 'clean-workstation', p: password }
  })).body, { admin: true });
  assert.deepEqual((await call('GET', 'workstation-admin-check', { headers })).body, { admin: true });
  assert.deepEqual((await call('GET', 'workstation-admin-check', { headers: {
    'x-admin-device': 'same-network-new-install',
    'x-vercel-forwarded-for': '203.0.113.41'
  } })).body, { admin: true });
});

test('Unicode attachment names survive ticketing, persistence, and legacy recovery', async () => {
  const desktopHeaders = { authorization: 'Desktop desktop-token-for-tests-only', 'x-admin-device': 'filename-test' };
  const ticket = await call('POST', 'upload-ticket', { headers: desktopHeaders, body: {
    session: 'ann_filename_test', type: 'attach', name: '次元音符 第6期（终稿）.pdf', size: 12, contentType: 'application/pdf'
  } });
  assert.equal(ticket.status, 200);
  assert.equal(ticket.body.name, '次元音符 第6期（终稿）.pdf');
  assert.match(ticket.body.token, /-次元音符 第6期（终稿）\.pdf$/);
  await getStore().put(ticket.body.token, Buffer.from('test-content'));

  const created = await call('POST', 'announcement', { headers: desktopHeaders, body: {
    groupId: '2000000004', title: '附件名测试', content: '附件名测试', time: '2026-08-27 11:00',
    attach: ticket.body.token, attachmentNames: JSON.stringify(['하루.txt'])
  } });
  assert.equal(created.status, 201);
  assert.deepEqual(created.body.attachmentNames, ['하루.txt']);
  assert.equal((await call('DELETE', 'announcement', {
    headers: desktopHeaders, query: { id: created.body.id, revision: created.body.revision }
  })).status, 200);

  const repo = require('../api/_lib/repository');
  assert.deepEqual(repo.normalizeAttachmentNames([], [
    'uploads/ann_old/attach/7883dd3c-0349-4998-839f-4d0acddaa355-旧公告附件.zip'
  ]), ['旧公告附件.zip']);
  assert.equal(repo.sanitizeAttachmentName('../坏名?.zip', ''), '_坏名_.zip');
});

test('wrong hidden passphrase is an ordinary search and never changes devices', async () => {
  const before = await getStore().get('security/admin-devices.json');
  const result = await call('POST', 'admin-grant', { headers: { 'x-admin-device': 'ordinary-searcher' }, body: { d: 'ordinary-searcher', p: 'englishsongname' } });
  const after = await getStore().get('security/admin-devices.json');
  assert.deepEqual(result.body, { admin: false });
  assert.equal(after.body.toString(), before.body.toString());
});

test('announcement lifecycle uses per-record revisions and soft delete', async () => {
  const grant = await call('POST', 'admin-grant', { headers: { 'x-admin-device': 'new-device' }, body: { d: 'new-device', p: password } });
  const headers = { cookie: grant.headers['set-cookie'].split(';')[0] };
  const created = await call('POST', 'announcement', { headers, body: { groupId: '2000000004', title: '测试', content: '测试公告', time: '2026-08-25 10:00', pin: 'false', confirm: 'false' } });
  assert.equal(created.status, 201);
  assert.equal(created.body.revision, 1);
  const stale = { ...created.body, content: '过期修改', revision: 0 };
  assert.equal((await call('PATCH', 'announcement', { headers, query: { id: created.body.id }, body: stale })).status, 409);
  const updated = await call('PATCH', 'announcement', { headers, query: { id: created.body.id }, body: { ...created.body, content: '新内容' } });
  assert.equal(updated.status, 200);
  assert.equal(updated.body.revision, 2);
  const removed = await call('DELETE', 'announcement', { headers, query: { id: created.body.id, revision: 2 } });
  assert.equal(removed.status, 200);
  const list = await call('GET', 'list', { headers });
  assert.deepEqual(list.body, []);
});

test('concurrent duplicate creates collapse to one scheduled announcement and keep the richer attachment', async () => {
  const desktopHeaders = { authorization: 'Desktop desktop-token-for-tests-only', 'x-admin-device': 'duplicate-create-test' };
  const attachment = 'uploads/ann_duplicate_test/attach/00000000-0000-4000-8000-000000000001-package.mcb';
  await getStore().put(attachment, Buffer.from('package'));
  const base = {
    groupId: '2000000004',
    title: '幂等公告',
    content: '幂等公告\n同一正文',
    time: '2026-09-01 15:00',
    pin: 'false',
    confirm: 'false'
  };

  const results = await Promise.all([
    call('POST', 'announcement', { headers: desktopHeaders, body: base }),
    call('POST', 'announcement', { headers: desktopHeaders, body: {
      ...base,
      attach: attachment,
      attachmentNames: ['package.mcb']
    } })
  ]);

  assert.deepEqual(results.map(result => result.status).sort(), [200, 201]);
  assert.equal(results[0].body.id, results[1].body.id);
  const list = await call('GET', 'list', { headers: desktopHeaders });
  const matches = list.body.filter(item => item.time === base.time && item.groupId === base.groupId && item.content === base.content);
  assert.equal(matches.length, 1);
  assert.equal(matches[0].attach, attachment);
  assert.deepEqual(matches[0].attachmentNames, ['package.mcb']);
  assert.equal((await call('DELETE', 'announcement', {
    headers: desktopHeaders,
    query: { id: matches[0].id, revision: matches[0].revision }
  })).status, 200);
});

test('bot claim is leased and completion prevents a second send', async () => {
  const check = await call('GET', 'admin-check', { headers: { 'x-admin-device': 'legacy-device' } });
  const headers = { cookie: check.headers['set-cookie'].split(';')[0] };
  const created = await call('POST', 'announcement', { headers, body: { groupId: '2000000004', title: '到期公告', content: '到期公告', time: '2026-08-20 10:00' } });
  const botHeaders = { authorization: 'Bot bot-token-for-tests-only' };
  const due = await call('GET', 'bot-due', { headers: botHeaders, query: { before: '2026-08-25 10:00' } });
  assert.ok(due.body.items.some(item => item.id === created.body.id));
  const claimed = await call('POST', 'bot-claim', { headers: botHeaders, body: { id: created.body.id, botId: 'test-bot' } });
  assert.equal(claimed.status, 200);
  assert.equal((await call('POST', 'bot-claim', { headers: botHeaders, body: { id: created.body.id, botId: 'other-bot' } })).status, 409);
  assert.equal((await call('POST', 'bot-complete', { headers: botHeaders, body: { id: created.body.id, claimToken: claimed.body.claimToken, messageId: '123' } })).status, 200);
  assert.equal((await call('POST', 'bot-claim', { headers: botHeaders, body: { id: created.body.id } })).status, 409);
});

test('future tasks cannot be claimed and uncertain sends never auto-retry', async () => {
  const check = await call('GET', 'admin-check', { headers: { 'x-admin-device': 'legacy-device' } });
  const headers = { cookie: check.headers['set-cookie'].split(';')[0] };
  const future = await call('POST', 'announcement', { headers, body: { groupId: '2000000004', title: '未来公告', content: '未来公告', time: '2099-01-01 00:00' } });
  const botHeaders = { authorization: 'Bot bot-token-for-tests-only' };
  assert.equal((await call('POST', 'bot-claim', { headers: botHeaders, body: { id: future.body.id } })).status, 409);

  const due = await call('POST', 'announcement', { headers, body: { groupId: '2000000004', title: '不确定公告', content: '不确定公告', time: '2026-08-20 10:00' } });
  const claimed = await call('POST', 'bot-claim', { headers: botHeaders, body: { id: due.body.id } });
  assert.equal((await call('POST', 'bot-fail', { headers: botHeaders, body: { id: due.body.id, claimToken: claimed.body.claimToken, error: 'NapCat timeout', uncertain: true } })).status, 200);
  const pending = await call('GET', 'bot-due', { headers: botHeaders, query: { before: '2099-01-02 00:00' } });
  assert.ok(!pending.body.items.some(item => item.id === due.body.id));
});

test('teacharm website posts use the cloud store with revisions and safe names', async () => {
  const desktopHeaders = { authorization: 'Desktop desktop-token-for-tests-only', 'x-admin-device': 'website-editor-test' };
  const name = '云端文章.md';
  const created = await call('POST', 'website-save', {
    headers: desktopHeaders,
    body: { name, content: '---\ntitle: 云端文章\n---\n第一版\n' }
  });
  assert.equal(created.status, 200);
  assert.equal(created.body.revision, 1);

  const listed = await call('GET', 'website-list', { headers: desktopHeaders });
  assert.equal(listed.status, 200);
  const summary = listed.body.find(item => item.name === name);
  assert.ok(summary);
  assert.equal(Object.hasOwn(summary, 'content'), false);

  const read = await call('GET', 'website-read', { headers: desktopHeaders, query: { name } });
  assert.equal(read.status, 200);
  assert.equal(read.body.content, created.body.content);

  const stale = await call('POST', 'website-save', {
    headers: desktopHeaders,
    body: { name, content: '错误覆盖', revision: 0 }
  });
  assert.equal(stale.status, 409);

  const updated = await call('POST', 'website-save', {
    headers: desktopHeaders,
    body: { name, content: '第二版', revision: created.body.revision }
  });
  assert.equal(updated.status, 200);
  assert.equal(updated.body.revision, 2);
  assert.equal((await call('POST', 'website-save', {
    headers: desktopHeaders,
    body: { name: '../越界.md', content: 'x' }
  })).status, 400);

  const removed = await call('DELETE', 'website-delete', {
    headers: desktopHeaders,
    query: { name, revision: updated.body.revision }
  });
  assert.equal(removed.status, 200);
  assert.equal((await call('GET', 'website-read', { headers: desktopHeaders, query: { name } })).status, 404);
});

test('unchanged website mirror checks use only the tiny revision marker', async () => {
  const desktopHeaders = { authorization: 'Desktop desktop-token-for-tests-only', 'x-admin-device': 'website-sync-test' };
  const created = await call('POST', 'website-save', {
    headers: desktopHeaders,
    body: { name: '同步快照.md', content: '只应在版本变化时读取正文' }
  });
  assert.equal(created.status, 200);

  const initial = await call('GET', 'website-sync', { headers: desktopHeaders, query: { after: 0 } });
  assert.equal(initial.status, 200);
  assert.equal(initial.body.unchanged, false);
  assert.ok(initial.body.posts.some(post => post.name === '同步快照.md'));

  const store = getStore();
  const originalGet = store.get.bind(store);
  let fullDocumentReads = 0;
  store.get = async key => {
    if (key === 'website/teacharm.moe/posts/current.json') fullDocumentReads++;
    return originalGet(key);
  };
  try {
    const unchanged = await call('GET', 'website-sync', {
      headers: desktopHeaders,
      query: { after: initial.body.revision }
    });
    assert.equal(unchanged.status, 200);
    assert.equal(unchanged.body.revision, initial.body.revision);
    assert.equal(unchanged.body.updatedAt, initial.body.updatedAt);
    assert.equal(unchanged.body.unchanged, true);
    assert.deepEqual(unchanged.body.posts, []);
    assert.equal(fullDocumentReads, 0);
  } finally {
    store.get = originalGet;
  }
});

test('website post APIs reject unauthenticated callers', async () => {
  assert.equal((await call('GET', 'website-list')).status, 401);
  assert.equal((await call('POST', 'website-save', { body: { name: '未授权.md', content: 'x' } })).status, 401);
});

test('emergency lock keeps reads available and blocks every cloud mutation entry', async () => {
  const desktopHeaders = { authorization: 'Desktop desktop-token-for-tests-only', 'x-admin-device': 'lock-test' };
  const botHeaders = { authorization: 'Bot bot-token-for-tests-only' };
  process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK = '1';
  try {
    const health = await call('GET', 'health');
    assert.equal(health.status, 200);
    assert.equal(health.body.writeLocked, true);
    assert.equal((await call('GET', 'list', { headers: desktopHeaders })).status, 200);
    assert.equal((await call('POST', 'announcement', { headers: desktopHeaders, body: {
      groupId: '2000000004', title: '应被阻断', content: '应被阻断', time: '2026-08-30 10:00'
    } })).status, 423);
    assert.equal((await call('POST', 'website-save', { headers: desktopHeaders, body: {
      name: 'blocked.md', content: 'blocked'
    } })).status, 423);
    assert.equal((await call('POST', 'upload-ticket', { headers: desktopHeaders, body: {
      session: 'ann_blocked', type: 'attach', name: 'blocked.txt', size: 1
    } })).status, 423);
    assert.equal((await call('POST', 'desktop-ip-grant', { headers: {
      ...desktopHeaders, 'x-vercel-forwarded-for': '203.0.113.30'
    }, body: {} })).status, 423);
    assert.equal((await call('POST', 'bot-claim', { headers: botHeaders, body: {
      id: 'blocked', botId: 'test-bot'
    } })).status, 423);
  } finally {
    delete process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK;
  }
});
