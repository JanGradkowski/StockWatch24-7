const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const { StockWatchI18n } = require('./i18n-stub.cjs');
function setup() {
  const dom = new JSDOM('<body></body>', { runScripts: 'outside-only', url: 'https://watchlist.test' });
  dom.window.StockWatchI18n = StockWatchI18n;
  dom.window.eval(fs.readFileSync('src/main/resources/static/js/watchlist-picker.js', 'utf8'));
  dom.window.eval(fs.readFileSync('src/main/resources/static/js/watchlist-settings.js', 'utf8'));
  let changes = 0;
  const settings = dom.window.StockWatchListSettings.create(() => changes++);
  dom.window.document.body.append(settings.root);
  return { dom, settings, changes: () => changes,
    click: label => settings.root.querySelector(`[aria-label="${label}"]`).click(),
    // Quick actions show a short label ("Select all") and carry the full one as their accessible name.
    button: name => [...settings.root.querySelectorAll('button')].find(b => (b.getAttribute('aria-label') || b.textContent) === name).click() };
}

test('all types are allowed by default without enabling any follows', () => {
  const s = setup();
  assert.equal(s.settings.allowedTypes().length, 7);
  // Saving sends the allowed list, so a type missing here would be switched off on every save.
  assert.ok(s.settings.allowedTypes().includes('CUSTOM'));
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

test('the allowed signal types section stays closed until a type is restricted, and restricted rows say why', () => {
  const s = setup();
  const advanced = s.settings.root.querySelector('details.wl-advanced');
  assert.equal(advanced.open, false);
  assert.equal(advanced.querySelector('.wl-advanced-hint').textContent, '7 of 7 allowed');
  s.settings.set({ allowedTypes: ['CANDLESTICK', 'OUTLOOK', 'CONGRESS', 'INSIDER', 'CUSTOM'], selections: [] });
  assert.equal(advanced.open, true);
  assert.equal(advanced.querySelector('.wl-advanced-hint').textContent, '5 of 7 allowed');
  const row = s.settings.root.querySelector('[aria-label="Watch Elliott Wave buy daily"]').closest('tr');
  assert.ok(row.classList.contains('is-disallowed'));
  assert.ok(!s.settings.root.querySelector('[aria-label="Watch Candlestick buy daily"]').closest('tr').classList.contains('is-disallowed'));
  s.dom.window.close();
});

test('grid cells carry no repeated labels and email is a single switch', () => {
  const s = setup();
  const cells = s.settings.root.querySelectorAll('.wl-matrix-cell');
  assert.equal(cells.length, 21);
  assert.ok([...cells].every(cell => cell.textContent.trim() === ''), 'Each cell is a bare checkbox with an accessible name');
  const email = s.settings.root.querySelector('[aria-label="Enable email notifications"]');
  assert.equal(email.getAttribute('role'), 'switch');
  s.dom.window.close();
});
