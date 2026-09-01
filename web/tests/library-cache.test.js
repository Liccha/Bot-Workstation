const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');

test('the website prefers the release pointer and keeps a bounded domestic index fallback', () => {
  const index = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
  const library = fs.readFileSync(path.join(root, 'assets', 'js', 'library.js'), 'utf8');

  assert.match(index, /SONG_LIBRARY_RELEASE_POINTER_URL/,
    'index.html must publish the compact release pointer URL');
  assert.match(index, /SONG_LIBRARY_PRIMARY_DATA_URL="https:\/\/assets\.teacharm\.moe\/data\/songs\.json"/,
    'a domestic mutable index must remain available when the tiny pointer stalls');
  assert.match(index, /library\.js\?v=20260901-cn-api/,
    'the deployment must invalidate the old loader cache');
  assert.ok(library.includes('data\\/releases\\/songs-[a-f0-9]{16}'),
    'the loader must validate and consume a content-addressed release');
  assert.match(library, /'force-cache'/,
    'immutable releases must use the browser HTTP cache');
  assert.match(library, /_fetchJsonResponse\(_LIBRARY_RELEASE_POINTER,1800/,
    'the pointer route must fail over before the page appears frozen');
});
