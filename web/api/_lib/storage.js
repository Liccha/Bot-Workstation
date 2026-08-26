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
  async copy(source, target) {
    const src = this.resolve(source); const dst = this.resolve(target);
    await fs.mkdir(path.dirname(dst), { recursive: true });
    await fs.copyFile(src, dst);
  }
  async head(key) {
    try { const stat = await fs.stat(this.resolve(key)); return { size: stat.size }; }
    catch (error) { if (error.code === 'ENOENT') return null; throw error; }
  }
  async signedPutUrl(key) { return `/api/announcement-cloud?action=local-upload&key=${encodeURIComponent(key)}`; }
  async signedGetUrl(key) { return `/api/announcement-cloud?action=local-file&key=${encodeURIComponent(key)}`; }
}

class OssStore {
  constructor(options) { this.client = new OSS(options); }
  async get(key) {
    try {
      const result = await this.client.get(key);
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
    const result = await this.client.put(key, body, { headers });
    return { etag: cleanEtag(result.res.headers.etag) };
  }
  async delete(key) { await this.client.delete(key); }
  async copy(source, target) { await this.client.copy(target, source); }
  async head(key) {
    try {
      const result = await this.client.head(key);
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
