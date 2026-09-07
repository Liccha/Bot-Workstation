const test = require('node:test');
const assert = require('node:assert/strict');

const handlers = [
  require('../api/announcement-cloud'),
  require('../api/mobile-data'),
  require('../api/mobile-relay'),
  require('../api/like'),
  require('../api/visit'),
];

function response() {
  const headers = {};
  return {
    statusCode: 0,
    body: '',
    setHeader(name, value) { headers[name] = value; },
    end(value = '') { this.body = String(value); },
  };
}

test('downloaded portfolio handlers reject every content mutation by default', async () => {
  const oldNodeEnv = process.env.NODE_ENV;
  const oldTestWrites = process.env.PORTFOLIO_TEST_WRITES;
  delete process.env.NODE_ENV;
  delete process.env.PORTFOLIO_TEST_WRITES;
  try {
    for (const handler of handlers) {
      const res = response();
      await handler({ method: 'POST', headers: {}, query: {}, body: '{}' }, res);
      assert.equal(res.statusCode, 403);
      assert.equal(JSON.parse(res.body).error, 'portfolio showcase is read-only');
    }
  } finally {
    if (oldNodeEnv === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = oldNodeEnv;
    if (oldTestWrites === undefined) delete process.env.PORTFOLIO_TEST_WRITES;
    else process.env.PORTFOLIO_TEST_WRITES = oldTestWrites;
  }
});
