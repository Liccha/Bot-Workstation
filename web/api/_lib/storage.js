const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const OSS = require('ali-oss');
const { config } = require('./config');

class LocalStore {
  constructor(root) { this.root = root; }
  resolve(key) {
    const normalized = String(key || '').replace(/\\/g, '/');
    if (!normalized || normalized.includes('..') || normalized.startsWith('/')) throw new Error('unsafe object key');
    return path.join(this.root, ...normalized.split('/'));
  }
  async get(key) {
    try {
      const file = this.resolve(key);
      const body = await fs.readFile(file);
      return { body, etag: crypto.createHash('sha256').update(body).digest('hex') };
    } catch (error) {
      if (error.code === 'ENOENT') return null;
      throw error;
    }
  }
  async put(key, body, options = {}) {
    const file = this.resolve(key);
    const current = await this.get(key);
    if (options.ifMatch && (!current || current.etag !== cleanEtag(options.ifMatch))) {
      const error = new Error('precondition failed'); error.code = 'PreconditionFailed'; throw error;
    }
    if (options.ifNoneMatch && current) {
      const error = new Error('precondition failed'); error.code = 'PreconditionFailed'; throw error;
    }
    if (options.forbidOverwrite && current) {
      const error = new Error('object already exists'); error.code = 'FileAlreadyExists'; error.status = 409; throw error;
    }
    await fs.mkdir(path.dirname(file), { recursive: true });
    if (options.ifNoneMatch || options.forbidOverwrite) {
      const exclusiveTmp = `${file}.${crypto.randomUUID()}.tmp`;
      try {
        await fs.writeFile(exclusiveTmp, body, { flag: 'wx' });
        await fs.link(exclusiveTmp, file);
        const saved = await this.get(key);
        return { etag: saved.etag };
      } catch (error) {
        if (error.code === 'EEXIST') {
          const conflict = new Error('object already exists'); conflict.code = 'FileAlreadyExists'; conflict.status = 409; throw conflict;
        }
        throw error;
      } finally {
        await fs.rm(exclusiveTmp, { force: true }).catch(() => {});
      }
    }
    const tmp = `${file}.${crypto.randomUUID()}.tmp`;
    await fs.writeFile(tmp, body);
    await fs.rename(tmp, file);
    const saved = await this.get(key);
    return { etag: saved.etag };
  }
  async delete(key) { await fs.rm(this.resolve(key), { force: true }); }
  async deletePrefix(prefix) {
    const normalized = String(prefix || '').replace(/\\/g, '/');
    if (!normalized.endsWith('/')) throw new Error('unsafe object prefix');
    await fs.rm(this.resolve(normalized), { recursive: true, force: true });
  }
  async copy(source, target) {
    const src = this.resolve(source); const dst = this.resolve(target);
    await fs.mkdir(path.dirname(dst), { recursive: true });
    await fs.copyFile(src, dst);
  }
  async head(key) {
    try {
      const body = await fs.readFile(this.resolve(key));
      return { size: body.length, etag: crypto.createHash('sha256').update(body).digest('hex') };
    }
    catch (error) { if (error.code === 'ENOENT') return null; throw error; }
  }
  async signedPutUrl(key) { return `/api/announcement-cloud?action=local-upload&key=${encodeURIComponent(key)}`; }
  async signedGetUrl(key) { return `/api/announcement-cloud?action=local-file&key=${encodeURIComponent(key)}`; }
}

class OssStore {
  constructor(options) {
    this.client = new OSS(options);
    // Read traffic crosses regions (Vercel Hong Kong -> OSS Beijing). Keep each
    // read attempt short enough that a single transient socket failure can be
    // retried inside the serverless request deadline. Mutations use one signed
    // native HTTP request and are never replayed implicitly.
    this.readClient = new OSS({ ...options, timeout: Math.min(Number(options.timeout || 8_000), 2_500), retryMax: 0 });
  }
  async get(key) {
    try {
      const native = await nativeSignedRequest(this.client, key, 'GET');
      if (native) return native;
      const result = await readWithRetry(() => (this.readClient || this.client).get(key));
      return { body: Buffer.from(result.content), etag: cleanEtag(result.res.headers.etag) };
    } catch (error) {
      if (error.status === 404 || error.code === 'NoSuchKey') return null;
      throw error;
    }
  }
  async put(key, body, options = {}) {
    const headers = {};
    if (options.ifMatch) headers['If-Match'] = quoteEtag(options.ifMatch);
    if (options.ifNoneMatch) headers['If-None-Match'] = '*';
    if (options.forbidOverwrite) headers['x-oss-forbid-overwrite'] = 'true';
    const native = await nativeSignedPut(this.client, key, body, headers);
    if (native) return native;
    const result = await this.client.put(key, body, { headers });
    return { etag: cleanEtag(result.res.headers.etag) };
  }
  async delete(key) {
    if (await nativeSignedDelete(this.client, key)) return;
    await this.client.delete(key);
  }
  async deletePrefix(prefix) {
    const normalized = String(prefix || '').replace(/\\/g, '/');
    if (!normalized || normalized.includes('..') || normalized.startsWith('/') || !normalized.endsWith('/')) {
      throw new Error('unsafe object prefix');
    }
    for (let page = 0; page < 1000; page += 1) {
      const listed = await this.client.listV2({ prefix: normalized, 'max-keys': 1000 });
      const names = (listed.objects || []).map(item => item.name).filter(Boolean);
      if (names.length === 0) return;
      await this.client.deleteMulti(names, { quiet: true });
    }
    throw new Error('object prefix deletion did not converge');
  }
  async copy(source, target) {
    const bucket = String(this.client?.options?.bucket || '').trim();
    const normalized = String(source || '').replace(/^\/+/, '');
    if (bucket && normalized && !normalized.includes('..')) {
      const headers = { 'x-oss-copy-source': `/${bucket}/${encodeURIComponent(normalized)}` };
      const native = await nativeSignedPut(this.client, target, undefined, headers);
      if (native) return;
    }
    await this.client.copy(target, source);
  }
  async head(key) {
    try {
      const native = await nativeSignedRequest(this.client, key, 'HEAD');
      if (native) return native;
      const result = await readWithRetry(() => (this.readClient || this.client).head(key));
      return { size: Number(result.res.headers['content-length'] || 0), etag: cleanEtag(result.res.headers.etag) };
    } catch (error) {
      if (error.status === 404 || error.code === 'NoSuchKey') return null;
      throw error;
    }
  }
  async signedPutUrl(key, contentType) {
    return this.client.signatureUrl(key, { method: 'PUT', expires: 300, 'Content-Type': contentType || 'application/octet-stream' });
  }
  async signedGetUrl(key) { return this.client.signatureUrl(key, { method: 'GET', expires: 600 }); }
}

async function nativeSignedRequest(client, key, method) {
  if (!client || typeof client.signatureUrl !== 'function' || typeof fetch !== 'function') return null;
  const url = client.signatureUrl(key, { method, expires: 60 });
  let response;
  try {
    // Leave enough of Vercel's request budget for the independent SDK path.
    // A dead cross-region route must fail over instead of consuming the whole
    // serverless invocation before readWithRetry() gets a chance to run.
    response = await fetch(url, { method, signal: AbortSignal.timeout(2_500) });
  } catch (error) {
    if (transientReadError(error)) return null;
    throw error;
  }
  if (response.status === 404) {
    const error = new Error('object not found'); error.status = 404; error.code = 'NoSuchKey'; throw error;
  }
  if (!response.ok) {
    const error = new Error(`OSS HTTP ${response.status}`); error.status = response.status; error.code = 'ResponseError'; throw error;
  }
  const etag = cleanEtag(response.headers.get('etag'));
  if (method === 'HEAD') {
    return { size: Number(response.headers.get('content-length') || 0), etag };
  }
  const declared = Number(response.headers.get('content-length') || 0);
  if (declared > 32 * 1024 * 1024) throw new Error('OSS object exceeds read limit');
  const body = Buffer.from(await response.arrayBuffer());
  if (body.length > 32 * 1024 * 1024) throw new Error('OSS object exceeds read limit');
  return { body, etag };
}

async function nativeSignedPut(client, key, body, headers) {
  if (!client || typeof client.signatureUrl !== 'function' || typeof fetch !== 'function') return null;
  // signatureUrl v1 reads Content-Type and x-oss-* values from the top-level
  // options object. Send the exact same values with the request so conditional
  // lock/CAS semantics remain part of the OSS signature.
  const url = client.signatureUrl(key, { method: 'PUT', expires: 60, ...headers });
  let response;
  try {
    response = await fetch(url, {
      method: 'PUT',
      headers,
      body,
      signal: AbortSignal.timeout(8_000),
    });
  } catch (cause) {
    throw mutationUnavailable(cause);
  }
  if (!response.ok) {
    if ([408, 429].includes(response.status) || response.status >= 500) {
      throw mutationUnavailable(Object.assign(new Error(`OSS HTTP ${response.status}`), { status: response.status }));
    }
    const error = new Error(`OSS HTTP ${response.status}`);
    error.status = response.status;
    error.statusCode = response.status;
    error.code = response.status === 409 ? 'FileAlreadyExists'
      : response.status === 412 ? 'PreconditionFailed' : 'ResponseError';
    throw error;
  }
  return { etag: cleanEtag(response.headers.get('etag')) };
}

async function nativeSignedDelete(client, key) {
  if (!client || typeof client.signatureUrl !== 'function' || typeof fetch !== 'function') return false;
  const url = client.signatureUrl(key, { method: 'DELETE', expires: 60 });
  let response;
  try {
    response = await fetch(url, {
      method: 'DELETE',
      signal: AbortSignal.timeout(8_000),
    });
  } catch (cause) {
    throw mutationUnavailable(cause);
  }
  // OSS deletion is idempotent. Treat an already absent lock/staging object as
  // complete instead of leaving a stale client-side failure behind.
  if (response.status === 404) return true;
  if (!response.ok) {
    if ([408, 429].includes(response.status) || response.status >= 500) {
      throw mutationUnavailable(Object.assign(new Error(`OSS HTTP ${response.status}`), { status: response.status }));
    }
    const error = new Error(`OSS HTTP ${response.status}`);
    error.status = response.status;
    error.statusCode = response.status;
    error.code = 'ResponseError';
    throw error;
  }
  return true;
}

function mutationUnavailable(cause) {
  const error = new Error('OSS mutation transport unavailable');
  error.status = 503;
  error.statusCode = 503;
  error.publicCode = 'store_busy';
  error.code = 'StorageUnavailable';
  error.cause = cause;
  return error;
}

async function readWithRetry(operation) {
  let last;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try { return await operation(); }
    catch (error) {
      last = error;
      if (attempt > 0 || !transientReadError(error)) throw error;
      await new Promise(resolve => setTimeout(resolve, 75));
    }
  }
  throw last;
}

function transientReadError(error) {
  const status = Number(error?.status || error?.statusCode || 0);
  if (status === 408 || status === 429 || status >= 500) return true;
  const code = String(error?.code || error?.name || '');
  return ['RequestError', 'ConnectionTimeoutError', 'SocketTimeoutError', 'ECONNRESET',
    'ECONNREFUSED', 'EPIPE', 'ETIMEDOUT', 'EAI_AGAIN', 'TimeoutError', 'AbortError'].includes(code);
}

function cleanEtag(value) { return String(value || '').replace(/^W\//, '').replace(/^"|"$/g, ''); }
function quoteEtag(value) { return `"${cleanEtag(value)}"`; }
let singleton;
function getStore() {
  if (singleton) return singleton;
  const cfg = config();
  singleton = cfg.local ? new LocalStore(cfg.localDir) : new OssStore(cfg.oss);
  return singleton;
}

module.exports = { LocalStore, OssStore, getStore, cleanEtag, quoteEtag };
