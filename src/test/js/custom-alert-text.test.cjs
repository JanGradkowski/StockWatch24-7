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
  assert.match(text.operandLabel({ kind: 'INDICATOR', type: 'MACD', parameters: {}, output: 'signal' }, definitions), /^MACD › .+ \(12\)$/);
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
  assert.match(oversold, /and a bullish candlestick pattern/);
  dom.window.close();
});
