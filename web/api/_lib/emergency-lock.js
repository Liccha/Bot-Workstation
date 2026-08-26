const { getStore } = require('./storage');

const LOCK_KEY = 'security/emergency-write-lock.json';
const CACHE_MS = 3000;
let cached = { at: 0, locked: false, reason: '' };

function environmentLocked() {
  return /^(1|true|locked|on)$/i.test(String(process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK || '').trim());
}

async function state(options = {}) {
  if (environmentLocked()) return { locked: true, reason: 'environment' };
  const now = Date.now();
  if (!options.fresh && now - cached.at < CACHE_MS) return { locked: cached.locked, reason: cached.reason };
  try {
    const object = await getStore().get(LOCK_KEY);
    if (!object) {
      cached = { at: now, locked: false, reason: '' };
      return { locked: false, reason: '' };
    }
    const value = JSON.parse(object.body.toString('utf8'));
    const locked = value.locked === true || /^(locked|deny|disabled)$/i.test(String(value.mode || ''));
    cached = { at: now, locked, reason: locked ? String(value.reason || 'emergency').slice(0, 120) : '' };
    return { locked: cached.locked, reason: cached.reason };
  } catch (error) {
    // A write must never continue when the emergency policy cannot be read.
    const unavailable = new Error('write policy unavailable');
    unavailable.statusCode = 503;
    throw unavailable;
  }
}

async function assertWriteAllowed() {
  const current = await state();
  if (!current.locked) return;
  const error = new Error('cloud writes are emergency locked');
  error.statusCode = 423;
  throw error;
}

module.exports = { LOCK_KEY, state, assertWriteAllowed };
