const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
function setup() {
  const dom = new JSDOM('<body></body>', { runScripts: 'outside-only', url: 'https://watchlist.test' });
  dom.window.eval(fs.readFileSync('src/main/resources/static/js/watchlist-picker.js', 'utf8'));
  dom.window.eval(fs.readFileSync('src/main/resources/static/js/watchlist-settings.js', 'utf8'));
  let changes = 0;
  const settings = dom.window.StockWatchListSettings.create(() => changes++);
  dom.window.document.body.append(settings.root);
  return { dom, settings, changes: () => changes,
    click: label => settings.root.querySelector(`[aria-label="${label}"]`).click(),
    button: name => [...settings.root.querySelectorAll('button')].find(b => b.textContent === name).click() };
}

test('all types are allowed by default without enabling any follows', () => {
  const s = setup();
  assert.equal(s.settings.allowedTypes().length, 6);
  assert.equal(s.settings.read().some(value => value.watch), false);
  assert.equal(s.settings.restrictionsChanged(), false);
  s.dom.window.close();
});

test('disallowing a type previews removed follows without replacing individual preferences', () => {
  const s = setup();
  s.settings.set({ selections: [{ type: 'CANDLESTICK', interval: 'DAILY', direction: 'BUY', watch: true, email: false }], mixed: true, followCounts: { CANDLESTICK: 12 } });
  s.click('Allow Candlestick');
  assert.equal(s.settings.restrictionsChanged(), true);
  assert.equal(s.settings.monitoringChanged(), false);
  assert.equal(s.settings.read().some(value => value.type === 'CANDLESTICK' && value.watch), false);
  assert.match(s.settings.restrictionSummary(), /Remove 12 existing follows/);
  s.click('Toggle all intervals for Candlestick buy');
  assert.equal(s.settings.read().some(value => value.type === 'CANDLESTICK' && value.watch), false);
  s.button('Watch all technical signals');
  assert.equal(s.settings.read().filter(value => value.watch).length, 15);
  assert.equal(s.settings.read().some(value => value.type === 'CONGRESS' && value.watch), false);
  s.dom.window.close();
});

test('stored restrictions survive reload and allowing a type does not start following it', () => {
  const s = setup();
  s.settings.set({ allowedTypes: ['CONGRESS'], selections: [] });
  assert.equal(s.settings.root.querySelector('[aria-label="Watch Candlestick buy daily"]').disabled, true);
  s.click('Allow Candlestick');
  assert.equal(s.settings.root.querySelector('[aria-label="Watch Candlestick buy daily"]').disabled, false);
  assert.equal(s.settings.read().some(value => value.watch), false);
  assert.equal(s.settings.monitoringChanged(), false);
  assert.equal(s.settings.restrictionsChanged(), true);
  s.dom.window.close();
});
test('quick technical selection covers all types and intervals without enabling ticker alerts or email', () => {
  const s = setup();
  assert.equal(s.settings.read().filter(v => v.watch || v.email).length, 0);
  s.button('Watch all technical signals');
  assert.equal(s.settings.read().filter(v => v.watch).length, 21);
  assert.equal(s.settings.read().filter(v => v.email).length, 0);
  s.click('Enable email notifications');
  assert.equal(s.settings.read().filter(v => v.email).length, 23);
  s.button('Clear technical signals');
  assert.equal(s.settings.read().filter(v => v.watch).length, 0);
  s.click('Enable email notifications');
  assert.equal(s.settings.changed(), false);
  assert.equal(s.changes(), 4); s.dom.window.close();
});
test('row, interval and individual controls are independent', () => {
  const s = setup();
  s.click('Toggle all intervals for Candlestick buy');
  assert.equal(s.settings.read().filter(v => v.watch).length, 3);
  s.click('Toggle all weekly technical follows');
  assert.equal(s.settings.read().filter(v => v.watch).length, 9);
  s.click('Watch Harmonic sell monthly');
  assert.equal(s.settings.read().filter(v => v.watch).length, 10);
  s.click('Toggle all weekly technical follows');
  assert.equal(s.settings.read().filter(v => v.watch).length, 3);
  s.click('Watch Congressional trades');
  assert.equal(s.settings.read().find(v => v.type === 'CONGRESS').watch, true);
  assert.equal(s.settings.read().find(v => v.type === 'INSIDER').watch, false);
  s.dom.window.close();
});
test('saved settings round trip and email-only changes preserve monitoring baseline', () => {
  const s = setup();
  s.settings.set({ selections: [{ type: 'OUTLOOK', interval: 'DAILY', direction: 'ANY', watch: true, email: false }], mixed: true });
  assert.equal(s.settings.changed(), false);
  assert.equal(s.settings.root.querySelector('.wl-status').hidden, false);
  s.click('Enable email notifications');
  assert.equal(s.settings.changed(), true);
  assert.equal(s.settings.monitoringChanged(), false);
  assert.equal(s.settings.read().find(v => v.type === 'INSIDER').watch, false);
  const saved = s.settings.read(); s.settings.set({ selections: saved });
  assert.equal(s.settings.changed(), false);
  assert.equal(s.settings.root.querySelector('.wl-status').hidden, true);
  s.click('Watch Automated technical outlook daily');
  assert.equal(s.settings.monitoringChanged(), true); s.dom.window.close();
});

test('disabling emails leaves every website follow enabled and has only one delivery control', () => {
  const s = setup();
  s.settings.set({ selections: [{ type: 'CANDLESTICK', interval: 'WEEKLY', direction: 'BUY', watch: true, email: true }] });
  assert.equal(s.settings.root.querySelectorAll('[aria-label^="Email "]').length, 0);
  assert.equal(s.settings.root.querySelectorAll('[aria-label="Enable email notifications"]').length, 1);
  s.click('Enable email notifications');
  assert.equal(s.settings.read().filter(v => v.email).length, 0);
  assert.equal(s.settings.read().filter(v => v.watch).length, 1);
  assert.equal(s.settings.monitoringChanged(), false);
  assert.equal(s.settings.changed(), true);
  s.dom.window.close();
});
