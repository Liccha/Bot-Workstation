const crypto = require('node:crypto');
const { getStore } = require('./storage');
const security = require('./security');

const DEVICES_KEY = 'security/mobile-devices.json';

function hashSecret(secret) {
  return crypto.createHash('sha256').update(String(secret || '')).digest('hex');
}

function safeId(value) {
  return /^[a-f0-9-]{36}$/.test(String(value || '')) ? String(value) : '';
}

async function readDevices() {
  const object = await getStore().get(DEVICES_KEY);
  if (!object) return { schema: 1, devices: [] };
  const value = JSON.parse(object.body.toString('utf8'));
  return { schema: 1, devices: Array.isArray(value?.devices) ? value.devices : [] };
}

async function deviceFromRequest(req) {
  const header = String(req.headers.authorization || '');
  if (!header.startsWith('Device ')) return null;
  const token = header.slice(7).trim();
  const split = token.indexOf('.');
  if (split < 1) return null;
  const id = safeId(token.slice(0, split));
  const secret = token.slice(split + 1);
  if (!id || !/^[A-Za-z0-9_-]{32,128}$/.test(secret)) return null;
  const document = await readDevices();
  const device = document.devices.find(item => item.id === id && item.status !== 'revoked');
  if (!device || !security.safeEqual(device.secretHash, hashSecret(secret))) return null;
  return device;
}

module.exports = { DEVICES_KEY, deviceFromRequest, hashSecret, readDevices, safeId };
