const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');

test('the public Editor sends data APIs to the Beijing gateway', () => {
  const index = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
  const core = fs.readFileSync(path.join(root, 'assets', 'js', 'core.js'), 'utf8');
  assert.match(index, /SONGBOT_API_BASE="https:\/\/[^"/]+\.cn-beijing\.fcapp\.run"/);
  assert.match(core, /window\.SONGBOT_API_BASE/);
  assert.doesNotMatch(core, /var API_BASE = ''/);
});
