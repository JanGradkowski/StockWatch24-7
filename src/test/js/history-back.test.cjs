const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const source = fs.readFileSync('src/main/resources/static/js/history-back.js', 'utf8');

function page(t, body) {
  const dom = new JSDOM(`<!doctype html><body>${body}</body>`, {
    url: 'https://stockwatch.example/settings', runScripts: 'outside-only'
  });
  t.after(() => dom.window.close());
  dom.window.eval(source);
  return dom.window;
}

function savedControls(window) {
  const keys = Object.keys(window.sessionStorage).filter(key => key.startsWith('stockwatch:page-state:'));
  assert.equal(keys.length, 1);
  return JSON.parse(window.sessionStorage.getItem(keys[0])).controls.map(control => control.id);
}

test('codes, confirmation phrases and anything inside an ignored form are never written to sessionStorage', t => {
  const window = page(t, `
    <input id="search" name="q" value="AAPL">
    <input id="passwordCode" name="code" value="12345678" inputmode="numeric">
    <input id="mfaLoginCode" name="otp" value="123456">
    <input id="recoveryEntry" name="entry" value="ABCDE-12345" autocomplete="one-time-code">
    <input id="deleteConfirmation" name="confirmation" value="DELETE">
    <input id="cardNumber" name="number" value="4242" autocomplete="cc-number">
    <form data-history-ignore><input id="anything" name="anything" value="private"></form>
    <select id="interval"><option selected>WEEKLY</option></select>`);

  window.dispatchEvent(new window.Event('pagehide'));

  assert.deepEqual(savedControls(window), ['search', 'interval']);
  const stored = Object.values(window.sessionStorage).join(' ');
  for (const secret of ['12345678', '123456', 'ABCDE-12345', 'DELETE', '4242', 'private']) {
    assert.ok(!stored.includes(secret), `${secret} must not be stored`);
  }
});

test('signing out removes every saved page state and does not save the page being left', t => {
  const window = page(t, `
    <input id="search" name="q" value="AAPL">
    <form id="logout" action="/logout" method="post"><button>Sign out</button></form>`);
  window.sessionStorage.setItem('stockwatch:page-state:older-entry', '{"controls":[]}');
  window.sessionStorage.setItem('unrelated', 'kept');
  const form = window.document.getElementById('logout');
  form.addEventListener('submit', event => event.preventDefault());

  form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  window.dispatchEvent(new window.Event('pagehide'));

  assert.deepEqual(Object.keys(window.sessionStorage), ['unrelated']);
});
