const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { JSDOM } = require('jsdom');
const source = fs.readFileSync('src/main/resources/static/js/notifications-read.js', 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

function setup(t, attributes = '', response = { ok: true }) {
  const dom = new JSDOM('<div id="accountThemeSync" data-csrf-token="csrf-test"></div><div id="actions"></div>');
  t.after(() => dom.window.close());
  const requests = []; let reloads = 0;
  let finish;
  const pending = new Promise(resolve => { finish = resolve; });
  vm.runInNewContext(source, { document: dom.window.document, URLSearchParams,
    window: { location: { reload: () => { reloads++; } } },
    fetch: async (url, options) => { requests.push({ url, options }); await pending; return response; } });
  // Watchlist buttons are inserted after the shared script initializes.
  dom.window.document.getElementById('actions').innerHTML = `<button data-notifications-read-all ${attributes}>Mark all as read</button><span data-notifications-read-status role="status"></span>`;
  return { dom, requests, reloads: () => reloads, finish,
    button: dom.window.document.querySelector('button'), status: dom.window.document.querySelector('[role=status]') };
}

test('account, activity and watchlist buttons send their exact scope with CSRF and refresh after success', async t => {
  for (const [attributes, expected] of [
    ['', 'scope=ALL'], ['data-read-scope="ACTIVITY"', 'scope=ACTIVITY'],
    ['data-read-watchlist="17"', 'scope=ALL&watchlistId=17']
  ]) {
    const ui = setup(t, attributes);
    ui.button.click(); ui.button.click();
    assert.equal(ui.requests.length, 1);
    assert.equal(ui.requests[0].url, `/api/notifications/read-all?${expected}`);
    assert.equal(ui.requests[0].options.method, 'POST');
    assert.equal(ui.requests[0].options.headers['X-CSRF-TOKEN'], 'csrf-test');
    assert.equal(ui.button.disabled, true); assert.equal(ui.reloads(), 0);
    ui.finish(); await settle();
    assert.equal(ui.reloads(), 1);
  }
});

test('failure and expired sessions keep the page intact and restore a retryable button', async t => {
  for (const response of [{ ok: false }, { ok: true, redirected: true }]) {
    const ui = setup(t, '', response);
    ui.button.click(); ui.finish(); await settle();
    assert.equal(ui.reloads(), 0);
    assert.equal(ui.button.disabled, false);
    assert.equal(ui.button.textContent, 'Mark all as read');
    assert.ok(ui.status.textContent.length > 0);
    assert.equal(ui.button.hasAttribute('aria-busy'), false);
  }
});

test('missing CSRF is reported without issuing a mutation request', async t => {
  const ui = setup(t);
  ui.dom.window.document.getElementById('accountThemeSync').remove();
  ui.button.click(); await settle();
  assert.equal(ui.requests.length, 0);
  assert.match(ui.status.textContent, /refresh/);
  assert.equal(ui.button.disabled, false);
});
