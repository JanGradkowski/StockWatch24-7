const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { JSDOM } = require('jsdom');
const source = fs.readFileSync('src/main/resources/static/js/mark-read-on-view.js', 'utf8');

function run(t, html) {
  const dom = new JSDOM(html);
  t.after(() => dom.window.close());
  const requests = [];
  vm.runInNewContext(source, {
    document: dom.window.document,
    fetch: (url, options) => { requests.push({ url, options }); return Promise.resolve({ ok: true }); }
  });
  return requests;
}

test('a detail page marks itself read with one CSRF-protected POST', t => {
  const requests = run(t, '<span id="accountThemeSync" data-csrf-token="csrf-test"></span>'
    + '<main data-mark-read-endpoint="/api/alerts/signals/42/read"></main>');

  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/alerts/signals/42/read');
  assert.equal(requests[0].options.method, 'POST');
  assert.equal(requests[0].options.headers['X-CSRF-TOKEN'], 'csrf-test');
});

test('pages without an endpoint, a token, or with a foreign endpoint send nothing', t => {
  assert.equal(run(t, '<span id="accountThemeSync" data-csrf-token="csrf-test"></span><main></main>').length, 0);
  assert.equal(run(t, '<main data-mark-read-endpoint="/api/alerts/signals/42/read"></main>').length, 0);
  assert.equal(run(t, '<span id="accountThemeSync" data-csrf-token="csrf-test"></span>'
    + '<main data-mark-read-endpoint="https://evil.example/steal"></main>').length, 0);
});
