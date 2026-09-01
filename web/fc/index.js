'use strict';

const ROUTES = {
  '/api/announcement-cloud': require('../api/announcement-cloud'),
  '/api/mobile-data': require('../api/mobile-data'),
  '/api/mobile-relay': require('../api/mobile-relay'),
  '/api/meta': require('../api/meta'),
  '/api/like': require('../api/like'),
  '/api/visit': require('../api/visit'),
};

const ALLOWED_ORIGINS = new Set([
  'https://editor.teacharm.moe',
  'https://bot-editor.vercel.app',
]);

function lowerCaseHeaders(input) {
  const result = {};
  for (const [name, value] of Object.entries(input || {})) {
    result[String(name).toLowerCase()] = String(value ?? '');
  }
  return result;
}

function allowedOrigin(value) {
  try {
    const origin = new URL(String(value || '')).origin;
    if (ALLOWED_ORIGINS.has(origin)) return origin;
    if (/^https:\/\/bot-editor-[a-z0-9-]+-licchas-projects\.vercel\.app$/.test(origin)) return origin;
  } catch (_) {}
  return '';
}

function responseCapture() {
  const headers = {};
  let body = '';
  return {
    response: {
      statusCode: 200,
      setHeader(name, value) { headers[name] = value; },
      end(value = '') { body = value; },
    },
    result() {
      const binary = Buffer.isBuffer(body);
      return {
        statusCode: this.response.statusCode,
        headers,
        body: binary ? body.toString('base64') : String(body ?? ''),
        isBase64Encoded: binary,
      };
    },
  };
}

async function handleEvent(rawEvent, routes = ROUTES) {
  const event = typeof rawEvent === 'string' || Buffer.isBuffer(rawEvent)
    ? JSON.parse(rawEvent.toString()) : (rawEvent || {});
  const headers = lowerCaseHeaders(event.headers);
  const origin = allowedOrigin(headers.origin);
  const method = String(event.requestContext?.http?.method || 'GET').toUpperCase();
  if (method === 'OPTIONS') {
    return origin
      ? { statusCode: 204, headers: {}, body: '' }
      : { statusCode: 403, headers: {}, body: '' };
  }

  const path = String(event.requestContext?.http?.path || event.rawPath || '');
  const route = routes[path];
  if (!route) return { statusCode: 404, headers: {}, body: '{"error":"not found"}' };

  // FC supplies sourceIp from its trusted request context. Always overwrite the
  // similarly named incoming header so callers cannot forge an IP identity.
  const sourceIp = String(event.requestContext?.http?.sourceIp || '');
  headers['x-fc-source-ip'] = sourceIp;
  const contentType = String(headers['content-type'] || '').toLowerCase();
  const encodedBody = event.body == null ? '' : String(event.body);
  const requestBody = event.isBase64Encoded
    ? Buffer.from(encodedBody, 'base64')
    : contentType.includes('application/json') ? encodedBody : encodedBody;
  const req = {
    method,
    headers,
    query: event.queryParameters || {},
    body: requestBody,
    socket: { remoteAddress: sourceIp },
  };
  const capture = responseCapture();
  await route(req, capture.response);
  return capture.result();
}

exports.handler = async function handler(event) {
  process.env.SONGBOT_RUNTIME = 'aliyun-fc';
  try {
    return await handleEvent(event);
  } catch (error) {
    console.error('fc-adapter', { name: String(error?.name || 'Error'), code: String(error?.code || 'internal') });
    return { statusCode: 500, headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: '{"error":"internal"}' };
  }
};

exports.handleEvent = handleEvent;
