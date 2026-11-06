const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const { StockWatchI18n } = require('./i18n-stub.cjs');
const source = fs.readFileSync('src/main/resources/static/js/custom-alert-trigger-chart.js', 'utf8');

// Without a chart library the script only offers its decisions, which is what these tests read.
function load() {
  const dom = new JSDOM('<body></body>', { runScripts: 'outside-only', url: 'https://stockwatch.test/custom-alerts/triggers/1' });
  dom.window.StockWatchI18n = StockWatchI18n;
  dom.window.eval(source);
  return { dom, chart: dom.window.StockWatchTriggerChart };
}

const value = (operator, extra = {}) => ({ kind: 'VALUE', operator, ...extra });

test('a threshold test is decided on each candle as the alert decided it', () => {
  const { dom, chart } = load();
  const rsi = [25, 31, 29, 45];
  const thirty = rsi.map(() => 30);
  assert.deepEqual(rsi.map((_, at) => chart.holds(value('ABOVE'), rsi, thirty, at)), [false, true, false, true]);
  assert.deepEqual(rsi.map((_, at) => chart.holds(value('BELOW'), rsi, thirty, at)), [true, false, true, false]);
  // A cross needs the candle before on the other side (or touching).
  assert.deepEqual(rsi.map((_, at) => chart.holds(value('CROSSES_ABOVE'), rsi, thirty, at)), [false, true, false, true]);
  assert.deepEqual(rsi.map((_, at) => chart.holds(value('CROSSES_BELOW'), rsi, thirty, at)), [false, false, true, false]);
  assert.equal(chart.holds(value('BETWEEN', { lower: 28, upper: 32 }), rsi, null, 1), true);
  assert.equal(chart.holds(value('OUTSIDE', { lower: 28, upper: 32 }), rsi, null, 3), true);
  assert.equal(chart.holds(value('ABOVE'), [NaN, 40], thirty, 0), false, 'an unknown value never passes');
  dom.window.close();
});

test('runs, changes and new extremes look back the configured number of candles', () => {
  const { dom, chart } = load();
  const closes = [10, 11, 12, 11, 13, 14];
  assert.equal(chart.holds(value('RISING', { candles: 2 }), closes, null, 2), true);
  assert.equal(chart.holds(value('RISING', { candles: 2 }), closes, null, 3), false);
  assert.equal(chart.holds(value('RISING', { candles: 2 }), closes, null, 1), false, 'not enough candles before');
  assert.equal(chart.holds(value('FALLING', { candles: 1 }), closes, null, 3), true);
  assert.equal(chart.holds(value('CHANGE_ABOVE', { candles: 5, percent: 40 }), closes, null, 5), true);
  assert.equal(chart.holds(value('CHANGE_ABOVE', { candles: 5, percent: 41 }), closes, null, 5), false);
  assert.equal(chart.holds(value('CHANGE_BELOW', { candles: 1, percent: 5 }), closes, null, 3), true);
  assert.equal(chart.holds(value('NEW_HIGH', { candles: 3 }), closes, null, 4), true);
  assert.equal(chart.holds(value('NEW_HIGH', { candles: 3 }), closes, null, 3), false);
  assert.equal(chart.holds(value('NEW_LOW', { candles: 2 }), [5, 4, 3], null, 2), true);
  dom.window.close();
});

test('a rising run is traced over all its candles, and one-candle tests are left to markers', () => {
  const { dom, chart } = load();
  const closes = [10, 11, 12, 11, 13];
  assert.deepEqual([...chart.passing(value('RISING', { candles: 2 }), closes, null)], [true, true, true, false, false]);
  assert.deepEqual([...chart.passing(value('ABOVE'), closes, closes.map(() => 11.5), null)], [false, false, true, false, true]);
  assert.ok(chart.passing(value('CROSSES_ABOVE'), closes, closes.map(() => 11.5)).every(item => !item));
  assert.ok(chart.passing(value('NEW_HIGH', { candles: 2 }), closes, null).every(item => !item));
  dom.window.close();
});

test('windows end on the trigger candle and never start before the first candle', () => {
  const { dom, chart } = load();
  assert.deepEqual([...chart.windowOf({ window: { mode: 'LATEST', candles: 1 } }, 50)], [50, 50]);
  assert.deepEqual([...chart.windowOf({ window: { mode: 'ANY', candles: 5 } }, 50)], [46, 50]);
  assert.deepEqual([...chart.windowOf({ window: { mode: 'EVERY', candles: 5 } }, 2)], [0, 2]);
  assert.deepEqual([...chart.windowOf({}, 7)], [7, 7], 'conditions without a window decide on the trigger candle');
  dom.window.close();
});

test('a date falls on the candle that contains it', () => {
  const { dom, chart } = load();
  const weeks = [100, 200, 300];
  assert.equal(chart.atOrBefore(weeks, 250), 1);
  assert.equal(chart.atOrBefore(weeks, 300), 2);
  assert.equal(chart.atOrBefore(weeks, 50), -1);
  assert.equal(chart.atOrBefore(weeks, 999), 2);
  dom.window.close();
});

test('lines of one indicator share a pane, price lines sit on the candles, and panes stop at three', () => {
  const { dom, chart } = load();
  const indicator = (type, parameters, output = 'value') => ({ kind: 'INDICATOR', type, parameters, output });
  const conditions = [
    { id: 'a', kind: 'VALUE', subject: indicator('RSI', { period: 14 }), operator: 'ABOVE', compare: { kind: 'VALUE', value: 30 } },
    { id: 'b', kind: 'VALUE', subject: indicator('RSI', { period: 14 }, 'ma'), operator: 'RISING', candles: 2 },
    { id: 'c', kind: 'VALUE', subject: { kind: 'PRICE', field: 'CLOSE' }, operator: 'ABOVE', compare: indicator('SMA', { period: 200 }) },
    { id: 'd', kind: 'VALUE', subject: indicator('MACD', {}, 'macd'), operator: 'CROSSES_ABOVE', compare: indicator('MACD', {}, 'signal') },
    { id: 'e', kind: 'VALUE', subject: indicator('CCI', { period: 20 }), operator: 'BELOW', compare: { kind: 'VALUE', value: -100 } },
    { id: 'f', kind: 'VALUE', subject: indicator('MFI', { period: 14 }), operator: 'BELOW', compare: { kind: 'VALUE', value: 20 } }
  ];
  const views = { a: { subject: 's0' }, b: { subject: 's1' }, c: { compare: 's2' }, d: { subject: 's3', compare: 's4' },
    e: { subject: 's5' }, f: { subject: 's6' } };
  const series = new Map([['s0', 'PANEL'], ['s1', 'PANEL'], ['s2', 'PRICE'], ['s3', 'PANEL'], ['s4', 'PANEL'], ['s5', 'PANEL'], ['s6', 'PANEL']]
    .map(([id, placement]) => [id, { id, placement }]));
  const plan = chart.panePlan(conditions, views, series);
  assert.equal(plan.volume, false);
  assert.equal(plan.paneOf.get('s0'), plan.paneOf.get('s1'), 'RSI and its moving average share a pane');
  assert.equal(plan.paneOf.get('s2'), 0, 'a moving average is drawn over the candles');
  assert.equal(plan.paneOf.get('s3'), plan.paneOf.get('s4'));
  assert.notEqual(plan.paneOf.get('s0'), plan.paneOf.get('s3'));
  assert.equal(plan.panes.length, 4, 'price and three indicator panes');
  assert.equal(plan.paneOf.get('s6'), -1, 'the fourth indicator pane is left out');
  assert.deepEqual([...plan.hidden], ['s6']);
  dom.window.close();
});

test('a volume condition adds the volume pane right under the price', () => {
  const { dom, chart } = load();
  const conditions = [{ id: 'a', kind: 'VALUE', subject: { kind: 'PRICE', field: 'VOLUME' }, operator: 'ABOVE', compare: { kind: 'VALUE', value: 1e6 } }];
  const plan = chart.panePlan(conditions, {}, new Map());
  assert.equal(plan.volume, true);
  assert.deepEqual([...plan.panes], ['price', 'volume']);
  dom.window.close();
});
