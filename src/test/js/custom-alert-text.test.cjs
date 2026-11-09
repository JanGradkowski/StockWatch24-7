const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const { StockWatchI18n } = require('./i18n-stub.cjs');
const source = fs.readFileSync('src/main/resources/static/js/custom-alert-text.js', 'utf8');

const definitions = new Map([
  ['RSI', { type: 'RSI', shortName: 'RSI', inputs: [{ key: 'period', kind: 'INTEGER', defaultValue: 14 }, { key: 'upper', kind: 'DECIMAL', defaultValue: 70 },
    { key: 'lower', kind: 'DECIMAL', defaultValue: 30 }], outputs: ['value', 'ma'], zones: { upperInput: 'upper', lowerInput: 'lower' } }],
  ['MACD', { type: 'MACD', shortName: 'MACD', inputs: [{ key: 'fast', kind: 'INTEGER', defaultValue: 12 }, { key: 'slow', kind: 'INTEGER', defaultValue: 26 }],
    outputs: ['macd', 'signal', 'histogram'] }],
  ['EMA', { type: 'EMA', shortName: 'EMA', inputs: [{ key: 'period', kind: 'INTEGER', defaultValue: 9 }], outputs: ['value'] }]
]);

function load() {
  const dom = new JSDOM('<body></body>', { runScripts: 'outside-only', url: 'https://stockwatch.test/custom-alerts' });
  dom.window.StockWatchI18n = StockWatchI18n;
  dom.window.eval(source);
  return { dom, text: dom.window.StockWatchCustomAlerts };
}

test('indicator names show their length but not chart-only zone levels or the main line', () => {
  const { dom, text } = load();
  const rsi = parameters => ({ kind: 'INDICATOR', type: 'RSI', parameters, output: 'value' });
  assert.equal(text.operandLabel(rsi({}), definitions), 'RSI (14)');
  assert.equal(text.operandLabel(rsi({ period: 7, upper: 80 }), definitions), 'RSI (7)');
  assert.equal(text.operandLabel(rsi({ period: 14, upper: 70, lower: 30 }), definitions), 'RSI (14)', 'saved rules carry every default');
  assert.doesNotMatch(text.operandLabel({ ...rsi({}), output: 'ma' }, definitions), /^RSI \(14\)$/);
  assert.equal(text.operandLabel({ kind: 'INDICATOR', type: 'MACD', parameters: {}, output: 'macd' }, definitions), 'MACD (12)');
  assert.match(text.operandLabel({ kind: 'INDICATOR', type: 'MACD', parameters: {}, output: 'signal' }, definitions), /^MACD [a-z].* \(12\)$/, 'another line is named in words after the indicator');
  assert.equal(text.operandLabel({ kind: 'INDICATOR', type: 'EMA', parameters: { period: 200 }, output: 'value' }, definitions), 'EMA (200)');
  assert.match(text.operandLabel(rsi({ period: 14, upper: 70, lower: 30, maLength: 14 })), /^\S.* \(14\)$/, 'without the catalog only the length');
  dom.window.close();
});

test('dollar amounts are grouped so large totals stay readable', () => {
  const { dom, text } = load();
  assert.equal(text.money(250000), '$250,000');
  const sentence = text.describe({ id: 'c1', kind: 'INSIDER', negate: false, transaction: 'PURCHASE', days: 30, minCount: 2, minTotal: 250000, roles: [] }, definitions);
  assert.match(sentence, /\$250,000/);
  dom.window.close();
});

test('every starting point describes itself in words with existing translations', () => {
  const { dom, text } = load();
  assert.ok(text.templates.length >= 8);
  for (const template of text.templates) {
    const alert = text.fromTemplate(template.id);
    assert.ok(alert.name && alert.description, template.id);
    for (const group of alert.definition.groups) {
      for (const condition of group.conditions) {
        const sentence = text.describe(condition, definitions);
        assert.ok(sentence && !/undefined|NaN|\{\d\}/.test(sentence), `${template.id}: ${sentence}`);
      }
    }
    const summary = text.summary(alert, definitions);
    assert.ok(summary && !/undefined|NaN/.test(summary), `${template.id}: ${summary}`);
    const chips = text.chips(alert.definition, definitions);
    assert.ok(chips.querySelectorAll('.ca-chip').length >= 1, template.id);
  }
  dom.window.close();
});

test('the summary reads as one sentence: our own phrases are lowercased, indicator names keep their capitals', () => {
  const { dom, text } = load();
  const alert = text.fromTemplate('insider-cluster');
  const summary = text.summary(alert, definitions);
  assert.match(summary, /Triggers when insiders bought/);
  assert.match(summary, /and it is not true that insiders sold/);
  const oversold = text.summary(text.fromTemplate('oversold-bounce'), definitions);
  assert.match(oversold, /Triggers when RSI \(14\)/);
  assert.match(oversold, /and a bullish candlestick pattern confirmed in the last 5 candles/);
  dom.window.close();
});

test('a sequence reads as steps joined with "then", with its span, and its chips are joined by THEN', () => {
  const { dom, text } = load();
  const around = text.summary(text.fromTemplate('oversold-recovery'), definitions);
  assert.match(around, /Triggers when \(RSI \(14\) is below 30 for at least 5 candles in a row, then within 3 candles RSI \(14\) is above 30, all within 20 candles, completed in the last 3 candles\) and a bullish candlestick pattern confirmed in the last 3 candles\./);
  assert.doesNotMatch(around, /\)\)/, 'no parentheses inside parentheses');
  const after = text.summary(text.fromTemplate('oversold-recovery-then-candle'), definitions);
  assert.match(after, /then within 5 candles a bullish candlestick pattern confirmed, all within 25 candles\./);

  const chips = text.chips(text.fromTemplate('oversold-recovery-then-candle').definition, definitions, 6);
  assert.deepEqual([...chips.querySelectorAll('.ca-chip')].map(chip => chip.textContent), [
    'RSI (14) is below 30 · 5 in a row', 'RSI (14) is above 30 · within 3', 'A bullish candlestick pattern confirmed · within 5']);
  assert.deepEqual([...chips.querySelectorAll('.ca-joiner')].map(joiner => joiner.className), ['ca-joiner is-then', 'ca-joiner is-then']);

  const step = { id: 'c1', kind: 'VALUE', negate: true, subject: { kind: 'PRICE', field: 'CLOSE' }, operator: 'ABOVE',
    compare: { kind: 'VALUE', value: 100 }, window: { mode: 'EVERY', candles: 4 }, step: { candles: 3 } };
  assert.equal(text.describeStep(step, definitions), 'Not true: Close price is above 100 on each of at least 3 candles in a row',
    'its own window is not read, and the reversal holds candle by candle');
  assert.equal(text.defaultSpan({ conditions: [step, { ...step, step: { candles: 1, within: 40 } }] }), 44);
  const summary = text.summary({ interval: 'DAILY', definition: { version: 1, match: 'ALL', groups: [{ id: 'g1', match: 'THEN', span: 20,
    conditions: [step, { ...step, id: 'c2', negate: false, step: { candles: 1, within: 2 } }] }] } }, definitions);
  assert.match(summary, /Triggers when it is not true that close price is above 100 on each of at least 3 candles in a row, then within 2 candles close price is above 100, all within 20 candles\./);

  // A cross happens on one candle and a rise counts its own candles: neither takes "in a row".
  const cross = { ...step, negate: false, operator: 'CROSSES_ABOVE', step: { candles: 3 } };
  assert.equal(text.stepCandles(cross), 1);
  assert.equal(text.describeStep(cross, definitions), 'Close price crosses above 100');
  assert.equal(text.stepCandles({ ...cross, operator: 'RISING', candles: 2 }), 1);
  assert.equal(text.progressText({ done: 1, steps: 3, candlesLeft: 1 }), 'Step 1 of 3 done · step 2 due within 1 candle');
  dom.window.close();
});

test('a candlestick condition names its status; rules from before the status read as they worked then', () => {
  const { dom, text } = load();
  const pattern = extra => text.describe({ id: 'c1', kind: 'PATTERN', family: 'CANDLESTICK', direction: 'BULLISH', patterns: [],
    window: { mode: 'ANY', candles: 3 }, ...extra });
  assert.equal(pattern({ status: 'CONFIRMED' }), 'A bullish candlestick pattern confirmed in the last 3 candles');
  assert.equal(pattern({ status: 'DETECTED' }), 'A bullish candlestick pattern detected in the last 3 candles');
  assert.equal(pattern({}), 'A bullish candlestick pattern detected in the last 3 candles');
  assert.equal(pattern({ confirmedOnly: true }), 'A bullish candlestick pattern detected in the last 3 candles (confirmed only)');
  dom.window.close();
});

test('an alert on several intervals names them all, and a state names only the intervals it holds on', () => {
  const { dom, text } = load();
  assert.equal(text.intervalsLabel(['DAILY']), 'Daily');
  assert.equal(text.intervalsLabel(['DAILY', 'WEEKLY']), 'Daily and weekly');
  assert.equal(text.intervalsLabel(['DAILY', 'WEEKLY', 'MONTHLY']), 'Daily, weekly and monthly');
  const alert = { ...text.fromTemplate('oversold-bounce'), intervals: ['DAILY', 'WEEKLY'] };
  assert.match(text.summary(alert, definitions), /^Checked after every daily and weekly candle\./);
  assert.equal(text.stateLabel('MATCHING', ['WEEKLY'], ['DAILY', 'WEEKLY']), 'Matches now · Weekly');
  assert.equal(text.stateLabel('MATCHING', ['DAILY', 'WEEKLY'], ['DAILY', 'WEEKLY']), 'Matches now', 'true on every interval');
  assert.equal(text.stateLabel('MATCHING', ['DAILY'], ['DAILY']), 'Matches now');
  dom.window.close();
});
