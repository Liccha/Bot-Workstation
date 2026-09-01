const test = require('node:test');
const assert = require('node:assert/strict');

process.env.ANNOUNCEMENT_STORAGE = 'local';
process.env.ANNOUNCEMENT_ADMIN_SALT = 'storage-test-salt';
process.env.ANNOUNCEMENT_ADMIN_HASH = 'storage-test-hash';
process.env.ANNOUNCEMENT_SESSION_SECRET = 'storage-test-session';
process.env.ANNOUNCEMENT_BOT_TOKEN = 'storage-test-bot';
process.env.ANNOUNCEMENT_DESKTOP_TOKEN = 'storage-test-desktop';
process.env.ANNOUNCEMENT_HIDDEN_GROUP_ID = 'storage-test-group';

const { OssStore } = require('../api/_lib/storage');

test('OSS snapshot reads retry one transient cross-region failure without retrying writes', async () => {
  const store = Object.create(OssStore.prototype);
  let attempts = 0;
  store.readClient = {
    async get() {
      attempts += 1;
      if (attempts === 1) {
        const error = new Error('read ECONNRESET');
        error.code = 'RequestError';
        throw error;
      }
      return { content: Buffer.from('snapshot'), res: { headers: { etag: '"etag"' } } };
    }
  };
  store.client = store.readClient;
  const result = await store.get('mobile-library/songs/current.json.gz');
  assert.equal(result.body.toString('utf8'), 'snapshot');
  assert.equal(attempts, 2);
});

test('OSS reads use a signed native HTTP request instead of the failing SDK path', async () => {
  const store = Object.create(OssStore.prototype);
  let sdkAttempts = 0;
  let httpAttempts = 0;
  store.readClient = {
    async get() {
      sdkAttempts += 1;
      const error = new Error('connect ETIMEDOUT');
      error.code = 'RequestError';
      throw error;
    },
  };
  store.client = {
    signatureUrl(key, options) {
      assert.equal(key, 'security/mobile-devices.json');
      assert.equal(options.method, 'GET');
      return 'https://example.oss-cn-beijing.aliyuncs.com/security/mobile-devices.json?signature=redacted';
    },
  };
  const originalFetch = global.fetch;
  global.fetch = async (url, options) => {
    httpAttempts += 1;
    assert.match(String(url), /^https:\/\/example\.oss-cn-beijing\.aliyuncs\.com\//);
    assert.ok(options.signal, 'native fallback must be abortable');
    return {
      ok: true,
      status: 200,
      headers: { get: name => name.toLowerCase() === 'etag' ? '"native-etag"' : null },
      arrayBuffer: async () => Buffer.from('{"devices":[]}'),
    };
  };
  try {
    const result = await store.get('security/mobile-devices.json');
    assert.equal(result.body.toString('utf8'), '{"devices":[]}');
    assert.equal(result.etag, 'native-etag');
    assert.equal(sdkAttempts, 0);
    assert.equal(httpAttempts, 1);
  } finally {
    global.fetch = originalFetch;
  }
});

test('OSS read falls back to the SDK when the signed HTTP route times out', async () => {
  const store = Object.create(OssStore.prototype);
  let sdkAttempts = 0;
  store.readClient = {
    async get() {
      sdkAttempts += 1;
      return { content: Buffer.from('sdk snapshot'), res: { headers: { etag: '"sdk-etag"' } } };
    },
  };
  store.client = {
    signatureUrl() {
      return 'https://example.oss-cn-beijing.aliyuncs.com/current.json.gz?signature=redacted';
    },
  };
  const originalFetch = global.fetch;
  global.fetch = async () => {
    const error = new Error('signed route timed out');
    error.name = 'TimeoutError';
    throw error;
  };
  try {
    const result = await store.get('mobile-library/songs/current.json.gz');
    assert.equal(result.body.toString('utf8'), 'sdk snapshot');
    assert.equal(result.etag, 'sdk-etag');
    assert.equal(sdkAttempts, 1);
  } finally {
    global.fetch = originalFetch;
  }
});

test('OSS metadata reads use the signed native HTTP path needed by library status', async () => {
  const store = Object.create(OssStore.prototype);
  store.readClient = {
    async head() {
      throw new Error('SDK HEAD must not run when native HTTP is available');
    },
  };
  store.client = {
    signatureUrl(key, options) {
      assert.equal(key, 'mobile-library/songs/current.json.gz');
      assert.equal(options.method, 'HEAD');
      return 'https://example.oss-cn-beijing.aliyuncs.com/mobile-library/songs/current.json.gz?signature=redacted';
    },
  };
  const originalFetch = global.fetch;
  global.fetch = async (_url, options) => {
    assert.equal(options.method, 'HEAD');
    return {
      ok: true,
      status: 200,
      headers: {
        get(name) {
          if (name.toLowerCase() === 'etag') return '"snapshot-etag"';
          if (name.toLowerCase() === 'content-length') return '91234';
          return null;
        },
      },
    };
  };
  try {
    assert.deepEqual(
      await store.head('mobile-library/songs/current.json.gz'),
      { size: 91234, etag: 'snapshot-etag' },
    );
  } finally {
    global.fetch = originalFetch;
  }
});

test('OSS metadata writes use one signed native HTTP PUT with conditional headers', async () => {
  const store = Object.create(OssStore.prototype);
  let sdkAttempts = 0;
  let httpAttempts = 0;
  store.client = {
    signatureUrl(key, options) {
      assert.equal(key, 'mobile-library/songs/current.json.gz');
      assert.equal(options.method, 'PUT');
      assert.equal(options['x-oss-forbid-overwrite'], 'true');
      return 'https://example.oss-cn-beijing.aliyuncs.com/mobile-library/songs/current.json.gz?signature=redacted';
    },
    async put() {
      sdkAttempts += 1;
      throw new Error('SDK PUT must not run when native HTTP is available');
    },
  };
  const body = Buffer.from('compressed snapshot');
  const originalFetch = global.fetch;
  global.fetch = async (url, options) => {
    httpAttempts += 1;
    assert.match(String(url), /^https:\/\/example\.oss-cn-beijing\.aliyuncs\.com\//);
    assert.equal(options.method, 'PUT');
    assert.equal(options.headers['x-oss-forbid-overwrite'], 'true');
    assert.equal(options.headers['If-None-Match'], undefined);
    assert.deepEqual(options.body, body);
    assert.ok(options.signal, 'native write must have a bounded deadline');
    return {
      ok: true,
      status: 200,
      headers: { get: name => name.toLowerCase() === 'etag' ? '"written-etag"' : null },
    };
  };
  try {
    assert.deepEqual(
      await store.put('mobile-library/songs/current.json.gz', body, { forbidOverwrite: true }),
      { etag: 'written-etag' },
    );
    assert.equal(sdkAttempts, 0);
    assert.equal(httpAttempts, 1);
  } finally {
    global.fetch = originalFetch;
  }
});

test('OSS native reads leave time for the SDK fallback inside the serverless deadline', async () => {
  const store = Object.create(OssStore.prototype);
  store.readClient = { async get() { throw new Error('native HTTP should satisfy this read'); } };
  store.client = {
    signatureUrl() { return 'https://example.oss-cn-beijing.aliyuncs.com/current.json.gz?signature=redacted'; },
  };
  const originalFetch = global.fetch;
  const originalTimeout = AbortSignal.timeout;
  let deadline = 0;
  AbortSignal.timeout = milliseconds => {
    deadline = milliseconds;
    return new AbortController().signal;
  };
  global.fetch = async () => ({
    ok: true,
    status: 200,
    headers: { get: () => null },
    arrayBuffer: async () => Buffer.from('snapshot'),
  });
  try {
    await store.get('mobile-library/songs/current.json.gz');
    assert.equal(deadline, 2_500);
  } finally {
    AbortSignal.timeout = originalTimeout;
    global.fetch = originalFetch;
  }
});

test('OSS lock cleanup uses signed native HTTP DELETE instead of the SDK path', async () => {
  const store = Object.create(OssStore.prototype);
  let sdkAttempts = 0;
  store.client = {
    signatureUrl(key, options) {
      assert.equal(key, 'locks/mobile-library-songs.json');
      assert.equal(options.method, 'DELETE');
      return 'https://example.oss-cn-beijing.aliyuncs.com/locks/mobile-library-songs.json?signature=redacted';
    },
    async delete() { sdkAttempts += 1; },
  };
  const originalFetch = global.fetch;
  global.fetch = async (_url, options) => {
    assert.equal(options.method, 'DELETE');
    assert.ok(options.signal, 'native delete must have a bounded deadline');
    return { ok: true, status: 204, headers: { get: () => null } };
  };
  try {
    await store.delete('locks/mobile-library-songs.json');
    assert.equal(sdkAttempts, 0);
  } finally {
    global.fetch = originalFetch;
  }
});

test('OSS in-bucket asset copies use one signed native copy request', async () => {
  const store = Object.create(OssStore.prototype);
  let sdkAttempts = 0;
  store.client = {
    options: { bucket: 'example-bucket' },
    signatureUrl(key, options) {
      assert.equal(key, 'mobile-library/assets/image/1278/cover.jpg');
      assert.equal(options.method, 'PUT');
      assert.equal(options['x-oss-copy-source'], '/example-bucket/mobile-library%2Fstaging%2Fcover.jpg');
      return 'https://example.oss-cn-beijing.aliyuncs.com/mobile-library/assets/image/1278/cover.jpg?signature=redacted';
    },
    async copy() { sdkAttempts += 1; },
  };
  const originalFetch = global.fetch;
  global.fetch = async (_url, options) => {
    assert.equal(options.method, 'PUT');
    assert.equal(options.headers['x-oss-copy-source'], '/example-bucket/mobile-library%2Fstaging%2Fcover.jpg');
    assert.equal(options.body, undefined);
    return { ok: true, status: 200, headers: { get: () => null } };
  };
  try {
    await store.copy('mobile-library/staging/cover.jpg', 'mobile-library/assets/image/1278/cover.jpg');
    assert.equal(sdkAttempts, 0);
  } finally {
    global.fetch = originalFetch;
  }
});

test('OSS write transport failures become retryable service errors without SDK replay', async () => {
  const store = Object.create(OssStore.prototype);
  let sdkAttempts = 0;
  store.client = {
    signatureUrl() { return 'https://example.oss-cn-beijing.aliyuncs.com/revision.json?signature=redacted'; },
    async put() { sdkAttempts += 1; },
  };
  const originalFetch = global.fetch;
  global.fetch = async () => {
    const failure = new Error('connect ETIMEDOUT');
    failure.code = 'ETIMEDOUT';
    throw failure;
  };
  try {
    await assert.rejects(
      store.put('mobile-library/songs/revision.json', Buffer.from('{}')),
      error => error.statusCode === 503 && error.publicCode === 'store_busy' && error.cause?.code === 'ETIMEDOUT',
    );
    assert.equal(sdkAttempts, 0, 'an uncertain write must never be replayed through the SDK');
  } finally {
    global.fetch = originalFetch;
  }
});
