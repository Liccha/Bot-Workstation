const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');

test('the portfolio Editor keeps public reads but cannot reach the production control plane', () => {
  const index = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
  const core = fs.readFileSync(path.join(root, 'assets', 'js', 'core.js'), 'utf8');
  assert.match(index, /PORTFOLIO_READ_ONLY=true/);
  assert.match(index, /SONGBOT_API_BASE="https:\/\/portfolio\.invalid"/);
  assert.match(index, /SONG_LIBRARY_PRIMARY_DATA_URL="https:\/\/assets\.teacharm\.moe\/data\/songs\.json"/);
  assert.match(core, /window\.SONGBOT_API_BASE/);
  assert.doesNotMatch(index, /\.cn-beijing\.fcapp\.run/);
});
