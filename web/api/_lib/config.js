const path = require('node:path');

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function boundedNumber(name, fallback, minimum, maximum) {
  const parsed = Number(process.env[name]);
  const value = Number.isFinite(parsed) ? parsed : fallback;
  return Math.max(minimum, Math.min(maximum, Math.round(value)));
}

function config() {
  const local = process.env.ANNOUNCEMENT_STORAGE === 'local';
  return {
    local,
    localDir: process.env.ANNOUNCEMENT_LOCAL_DIR || path.join(process.cwd(), '.announcement-local'),
    oss: local ? null : {
      region: required('ALI_OSS_REGION'),
      bucket: required('ALI_OSS_BUCKET'),
      accessKeyId: required('ALI_OSS_ACCESS_KEY_ID'),
      accessKeySecret: required('ALI_OSS_ACCESS_KEY_SECRET'),
      secure: true,
      timeout: boundedNumber('ALI_OSS_TIMEOUT_MS', 8_000, 3_000, 15_000),
      retryMax: 0
    },
    adminSalt: required('ANNOUNCEMENT_ADMIN_SALT'),
    adminHash: required('ANNOUNCEMENT_ADMIN_HASH'),
    sessionSecret: required('ANNOUNCEMENT_SESSION_SECRET'),
    botToken: required('ANNOUNCEMENT_BOT_TOKEN'),
    desktopToken: required('ANNOUNCEMENT_DESKTOP_TOKEN'),
    emergencyWriteLock: process.env.ANNOUNCEMENT_EMERGENCY_WRITE_LOCK || '',
    hiddenGroupId: required('ANNOUNCEMENT_HIDDEN_GROUP_ID'),
    maxAttachmentBytes: Number(process.env.ANNOUNCEMENT_MAX_ATTACHMENT_BYTES || 100 * 1024 * 1024),
    maxImageBytes: Number(process.env.ANNOUNCEMENT_MAX_IMAGE_BYTES || 2 * 1024 * 1024)
  };
}

module.exports = { config };
