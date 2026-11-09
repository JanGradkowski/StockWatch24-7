const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const { StockWatchI18n } = require('./i18n-stub.cjs');

// The custom alert builder on a page with the controls of custom-alert-builder.html, against a stubbed server: how
// sequences ("in order" groups) are built, moved, switched and timed.
const CATALOG = {
  indicators: [{ type: 'RSI', shortName: 'RSI', category: 'MOMENTUM', inputs: [{ key: 'period', kind: 'INTEGER', defaultValue: 14 },
    { key: 'upper', kind: 'DECIMAL', defaultValue: 70 }, { key: 'lower', kind: 'DECIMAL', defaultValue: 30 }], outputs: ['value'],
    zones: { upperInput: 'upper', lowerInput: 'lower' } }],
  patterns: { CANDLESTICK: ['BULLISH_ENGULFING', 'HAMMER'], ELLIOTT_WAVE: ['WAVE_II_END'], HARMONIC_FORMATION: ['GARTLEY'] },
  maxConditions: 12, maxGroups: 4, maxIndicators: 8, maxWindow: 60, maximumActive: 25, maxSteps: 4, maxSpan: 120
};

const PAGE = `<body><main class="ca-builder-page" data-start-template="TEMPLATE"><form data-ca-builder>
  <input data-ca-name><input data-ca-description>
  <div data-ca-intervals><input type="checkbox" value="DAILY" checked><input type="checkbox" value="WEEKLY"><input type="checkbox" value="MONTHLY"></div>
  <div data-ca-groups></div><button type="button" data-ca-add-condition>+ Add condition</button><button type="button" data-ca-add-group>+ Add group</button>
  <input type="radio" name="triggerMode" value="BECOMES_TRUE" checked><input type="radio" name="triggerMode" value="ONCE">
  <input type="number" data-ca-quiet value="0"><input type="number" data-ca-outcome value="10">
  <div data-ca-targets></div><input type="checkbox" data-ca-notify-existing><input type="checkbox" checked data-ca-email>
  <div data-ca-start></div><p data-ca-summary></p><input data-ca-test-symbol><button type="button" data-ca-test>Test</button><div data-ca-test-interval hidden></div>
  <div data-ca-result></div><div data-ca-coverage></div><p data-ca-status></p><button type="submit" data-ca-save>Save</button>
</form></main></body>`;

const settle = () => new Promise(resolve => setTimeout(resolve, 40));

async function builder(template) {
  const dom = new JSDOM(PAGE.replace('TEMPLATE', template), { runScripts: 'outside-only', url: 'https://stockwatch.test/custom-alerts/new' });
  const window = dom.window;
  window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  window.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new window.Event('close')); };
  window.CSS = { escape: value => String(value).replace(/["\\]/g, '\\$&') };
  window.Element.prototype.scrollIntoView = () => {};
  window.fetch = async url => {
    const value = url.includes('/catalog') ? CATALOG : url.includes('/preview')
      ? { available: false, unavailableReason: 'Choose a stock to test the rule on.', coverage: null, conditions: [], groups: [] } : [];
    return { ok: true, status: 200, json: async () => JSON.parse(JSON.stringify(value)) };
  };
  window.StockWatchI18n = StockWatchI18n;
  for (const file of ['watchlist-picker', 'custom-alert-text', 'custom-alert-builder']) {
    window.eval(fs.readFileSync(`src/main/resources/static/js/${file}.js`, 'utf8'));
  }
  await settle();
  const doc = window.document;
  const groups = () => [...doc.querySelectorAll('[data-ca-groups] > .ca-group')];
  const cards = group => [...group.querySelectorAll('.ca-condition')];
  const sentences = () => groups().map(group => cards(group).map(card => card.querySelector('.ca-condition-sentence').textContent));
  const button = (root, label) => [...root.querySelectorAll('button')].find(item => item.textContent === label || item.getAttribute('aria-label') === label);
  const field = (card, label) => [...card.querySelectorAll('.ca-field')].find(item => item.querySelector('.ca-field-label')?.textContent.startsWith(label));
  const type = (input, value) => { input.value = String(value); input.dispatchEvent(new window.Event('input')); };
  return { dom, window, doc, groups, cards, sentences, button, field, type, summary: () => doc.querySelector('[data-ca-summary]').textContent };
}

test('↑ under a sequence makes the condition its last step, and ↓ on that step takes it out again', async () => {
  const page = await builder('oversold-recovery');
  assert.deepEqual(page.sentences(), [['RSI (14) is below 30 for at least 5 candles in a row', 'RSI (14) is above 30'],
    ['A bullish candlestick pattern confirmed in the last 3 candles']]);
  page.button(page.cards(page.groups()[1])[0], 'Move up').click();
  await page.window.Promise.resolve();
  assert.deepEqual(page.sentences(), [['RSI (14) is below 30 for at least 5 candles in a row', 'RSI (14) is above 30',
    'A bullish candlestick pattern confirmed']], 'one group: the candle is step 3, after both RSI steps');
  assert.match(page.summary(), /then within 3 candles RSI \(14\) is above 30, then within 5 candles a bullish candlestick pattern confirmed/);
  assert.equal(page.doc.activeElement.getAttribute('aria-label'), 'Actions for this condition (Candlestick pattern)',
    'the keyboard stays on the moved condition, on its menu');

  const steps = page.cards(page.groups()[0]);
  assert.equal(page.button(steps[2], 'Move down').disabled, false, 'the last step can leave the sequence');
  page.button(steps[2], 'Move down').click();
  assert.deepEqual(page.sentences(), [['RSI (14) is below 30 for at least 5 candles in a row', 'RSI (14) is above 30'],
    ['A bullish candlestick pattern confirmed in the last 3 candles']], 'back in a group of its own, with a window again');
  page.dom.window.close();
});

test('"Then…" refuses to change what an "any" group means, and keeps the meaning of an "all" group', async () => {
  const page = await builder('oversold-bounce');
  page.button(page.groups()[0].querySelector('.ca-group-header'), 'Any of these').click();
  const before = page.summary();
  page.button(page.cards(page.groups()[0])[0], 'Start a sequence').click();
  assert.equal(page.summary(), before, 'the rule is unchanged');
  assert.match(page.groups()[0].querySelector('.ca-sequence-note').textContent, /Only in an “All of these” group/);

  page.button(page.groups()[0].querySelector('.ca-group-header'), 'All of these').click();
  page.button(page.cards(page.groups()[0])[0], 'Start a sequence').click();
  assert.equal(page.groups()[1].querySelector('.ca-sequence-note').textContent,
    'Steps are timed by the sequence, so their own time windows were removed.', 'RSI had "at least once in the last 5 candles"');
  const picker = page.doc.querySelector('dialog.ca-picker[open]');
  assert.equal(picker.querySelector('h2').textContent, 'Add a step');
  picker.querySelector('.ca-picker-rsi').click();
  assert.equal(page.groups().length, 2);
  assert.equal(page.groups()[1].querySelector('.ca-sequence-note').textContent,
    'Steps are timed by the sequence, so their own time windows were removed.', 'the note stays once step 2 is added');
  assert.match(page.summary(), /^Checked after every daily candle\. Triggers when a bullish candlestick pattern confirmed in the last 5 candles and \(RSI/,
    'the two groups are joined with "and", as the conditions were');
  page.dom.window.close();
});

test('a sequence switched to "all" and back keeps its timing', async () => {
  const page = await builder('oversold-recovery');
  const header = () => page.groups()[0].querySelector('.ca-group-header');
  page.button(header(), 'All of these').click();
  assert.match(page.sentences()[0][0], /on each of the last 5 candles/);
  page.button(header(), 'In order').click();
  assert.equal(page.doc.activeElement.textContent, 'In order', 'the keyboard stays on the switch');
  const sequence = page.groups()[0];
  assert.equal(sequence.querySelector('.ca-step-connector input').value, '3');
  assert.equal(page.field(sequence, 'All steps within').querySelector('input').value, '20');
  assert.equal(page.button(sequence.querySelector('.ca-sequence-completed'), 'Last few candles').getAttribute('aria-pressed'), 'true');
  assert.equal(sequence.querySelector('.ca-sequence-completed input').value, '3');
  page.dom.window.close();
});

test('steps that need more than the longest span say so, and the span stays within it', async () => {
  const page = await builder('oversold-recovery-then-candle');
  const sequence = page.groups()[0];
  page.type(page.field(page.cards(sequence)[0], 'For at least').querySelector('input'), 60);
  page.type(page.field(page.cards(sequence)[1], 'For at least').querySelector('input'), 60);
  assert.equal(page.field(sequence, 'All steps within').querySelector('input').value, '120');
  assert.equal(sequence.querySelector('.ca-span-warning').textContent,
    'Together the steps need 121 candles, more than the 120 a sequence may span. Shorten a step.');
  page.type(page.field(page.cards(sequence)[1], 'For at least').querySelector('input'), 10);
  assert.equal(sequence.querySelector('.ca-span-warning').textContent, '');
  page.dom.window.close();
});

test('a crossing step has no "in a row", a cleared number shows the value in use, and a full sequence says so', async () => {
  const page = await builder('oversold-recovery-then-candle');
  const second = () => page.cards(page.groups()[0])[1];
  const operator = [...second().querySelectorAll('select')].find(select => [...select.options].some(option => option.value === 'CROSSES_ABOVE'));
  operator.value = 'CROSSES_ABOVE';
  operator.dispatchEvent(new page.window.Event('change'));
  assert.equal(page.field(second(), 'For at least'), undefined);

  const within = page.groups()[0].querySelector('.ca-step-connector input');
  assert.equal(within.value, '3');
  page.type(within, '');
  within.dispatchEvent(new page.window.Event('change'));
  assert.equal(within.value, '3', 'a cleared box keeps the value it had, not a default');
  assert.match(page.summary(), /then within 3 candles RSI/);
  page.type(within, 1);
  assert.equal(within.nextElementSibling.textContent, 'candle', 'the unit follows the number');

  page.button(page.cards(page.groups()[0])[0], 'Duplicate condition').click();
  assert.equal(page.cards(page.groups()[0]).length, 4);
  page.doc.querySelector('[data-ca-add-condition]').click();
  page.doc.querySelector('dialog.ca-picker[open] .ca-picker-outlook').click();
  assert.equal(page.groups().length, 2, 'after a sequence, "+ Add condition" starts a plain group');
  page.button(page.cards(page.groups()[1])[0], 'Move up').click();
  assert.match(page.groups()[0].querySelector('.ca-sequence-note').textContent, /can't be steps/);
  page.dom.window.close();
});

test('a rise that loses its "on each of the last N candles" in a sequence says so', async () => {
  const page = await builder('');
  const pick = key => {
    page.doc.querySelector('[data-ca-add-condition]').click();
    page.doc.querySelector(`dialog.ca-picker[open] .ca-picker-${key}`).click();
  };
  pick('rsi');
  const select = (card, value) => [...card.querySelectorAll('select')].find(item => [...item.options].some(option => option.value === value));
  const operator = select(page.cards(page.groups()[0])[0], 'RISING');
  operator.value = 'RISING';
  operator.dispatchEvent(new page.window.Event('change'));
  const when = select(page.cards(page.groups()[0])[0], 'EVERY');
  when.value = 'EVERY';
  when.dispatchEvent(new page.window.Event('change'));
  pick('rsi');
  page.button(page.groups()[0].querySelector('.ca-group-header'), 'In order').click();
  assert.equal(page.groups()[0].querySelector('.ca-sequence-note').textContent,
    'Steps are timed by the sequence, so their own time windows were removed.');
  assert.equal(page.field(page.cards(page.groups()[0])[0], 'For at least'), undefined, 'a rise counts its own candles');
  page.dom.window.close();
});

test('a step number is read out as "Step n", and a full sequence takes no fifth step', async () => {
  const page = await builder('oversold-recovery');
  const badge = page.cards(page.groups()[0])[1].querySelector('.ca-step-badge');
  assert.equal(badge.querySelector('[aria-hidden="true"]').textContent, '2');
  assert.equal(badge.querySelector('.visually-hidden').textContent, 'Step 2');
  const first = page.cards(page.groups()[0])[0];
  page.button(first, 'Duplicate condition').click();
  page.button(page.cards(page.groups()[0])[0], 'Duplicate condition').click();
  assert.equal(page.cards(page.groups()[0]).length, 4);
  page.button(page.cards(page.groups()[1])[0], 'Move up').click();
  assert.equal(page.groups()[0].querySelector('.ca-sequence-note').textContent, 'A sequence has at most 4 steps.');
  assert.equal(page.cards(page.groups()[1]).length, 1, 'the candle stays where it was');
  page.dom.window.close();
});

test('several intervals can be ticked, one always stays, and the preview tests the one chosen', async () => {
  const page = await builder('oversold-bounce');
  const bodies = [];
  const fetch = page.window.fetch;
  page.window.fetch = async (url, options) => {
    if (url.includes('/preview')) bodies.push(JSON.parse(options.body));
    return fetch(url, options);
  };
  const box = value => page.doc.querySelector(`[data-ca-intervals] input[value="${value}"]`);
  const toggle = page.doc.querySelector('[data-ca-test-interval]');
  assert.equal(toggle.hidden, true, 'one interval: nothing to choose');

  box('WEEKLY').checked = true;
  box('WEEKLY').dispatchEvent(new page.window.Event('change'));
  assert.equal(toggle.hidden, false);
  assert.deepEqual([...toggle.querySelectorAll('button')].map(item => [item.textContent, item.getAttribute('aria-pressed')]),
    [['Daily', 'true'], ['Weekly', 'false']]);
  assert.match(page.summary(), /^Checked after every daily and weekly candle\./);

  [...toggle.querySelectorAll('button')][1].click();
  await settle();
  assert.deepEqual({ interval: bodies.at(-1).interval, intervals: bodies.at(-1).intervals }, { interval: 'WEEKLY', intervals: ['DAILY', 'WEEKLY'] });

  box('DAILY').checked = false;
  box('DAILY').dispatchEvent(new page.window.Event('change'));
  box('WEEKLY').checked = false;
  box('WEEKLY').dispatchEvent(new page.window.Event('change'));
  assert.equal(box('WEEKLY').checked, true, 'the last ticked interval cannot be cleared');
  assert.equal(toggle.hidden, true);
  page.dom.window.close();
});
