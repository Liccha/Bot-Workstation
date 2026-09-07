const test = require('node:test');
const assert = require('node:assert/strict');

const publicApi = require('../api/_lib/public-api');

test('public browser APIs accept only Editor origins in FC runtime', () => {
  const previous = process.env.SONGBOT_RUNTIME;
  process.env.SONGBOT_RUNTIME = 'aliyun-fc';
  try {
    const request = (origin) => ({
      method: 'POST',
      headers: { host: 'songbot.example.fcapp.run', origin },
    });
    assert.equal(publicApi.browserAllowed(request('https://portfolio.invalid')), true);
    assert.equal(publicApi.browserAllowed(request('https://example.com')), false);
    assert.equal(publicApi.browserAllowed(request('https://evil.example')), false);
  } finally {
    if (previous == null) delete process.env.SONGBOT_RUNTIME;
    else process.env.SONGBOT_RUNTIME = previous;
  }
});
