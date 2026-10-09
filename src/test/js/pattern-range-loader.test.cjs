const {test} = require('node:test');
const assert = require('node:assert/strict');
const PatternRangeLoader = require('../../main/resources/static/js/pattern-range-loader.js');

test('visible range follows candle dates even when long overlays shift logical indexes', () => {
  const fs = require('node:fs');
  const vm = require('node:vm');
  const source = fs.readFileSync('src/main/resources/static/js/stock-workspace.js', 'utf8');
  const candles = Array.from({length: 200}, (_, index) => {
    const timestamp = 1700000000 + index * 86400;
    return {timestamp, time: new Date(timestamp * 1000).toISOString().slice(0, 10)};
  });
  const context = {globalCandleData: candles, priceChart: {timeScale: () => ({
    getVisibleLogicalRange: () => ({from: 850, to: 890}),
    getVisibleRange: () => ({from: candles[70].time, to: candles[100].time})
  })}};
  vm.createContext(context);
  for (const name of ['chartTimeKey', 'visiblePatternRange']) {
    vm.runInContext(source.match(new RegExp('^  function ' + name + '\\([\\s\\S]*?^  }', 'm'))[0], context);
  }
  const range = context.visiblePatternRange();
  assert.equal(range.from, candles[50].timestamp);
  assert.equal(range.to, candles[120].timestamp);
});

test('overlapping viewports fetch only missing batches and keep formations crossing the left edge', async () => {
  const calls = [];
  const span = 128 * 86400;
  const formation = {pattern: 'BAT', direction: 'BULLISH', points: [{timestamp: span / 2}, {timestamp: span * 2}]};
  const loader = new PatternRangeLoader('AAPL', async url => {
    calls.push(url);
    return {ok: true, json: async () => ({formations: [formation]})};
  });
  const first = await loader.load('harmonics', '1d', {from: span + 100, to: span * 2 + 100});
  assert.equal(calls.length, 2);
  assert.deepEqual(first.formations, [formation]);
  const second = await loader.load('harmonics', '1d', {from: span + 200, to: span * 2 + 200});
  assert.equal(calls.length, 2);
  assert.deepEqual(second.formations, [formation]);
  await loader.load('harmonics', '1d', {from: span - 100, to: span * 2 + 200});
  assert.equal(calls.length, 3);
  assert.ok(calls.every(url => url.includes('/history/range?') && url.includes('&to=')));
});

test('concurrent requests share a batch; obsolete batches are aborted and cannot refill the cache', async () => {
  const pending = [];
  const loader = new PatternRangeLoader('AAPL', (url, options) => new Promise(resolve => pending.push({url, options, resolve})));
  const span = 128 * 86400;
  const first = loader.load('candles', '1d', {from: span + 1, to: span + 2});
  const second = loader.load('candles', '1d', {from: span + 1, to: span + 3});
  assert.equal(pending.length, 1);
  const third = loader.load('candles', '1d', {from: 3 * span + 1, to: 3 * span + 3});
  assert.equal(pending.length, 2);
  assert.equal(pending[0].options.signal.aborted, true);
  pending[0].resolve({ok: true, json: async () => ({signals: []})});
  pending[1].resolve({ok: true, json: async () => ({signals: []})});
  await Promise.all([first, second, third]);
  assert.equal(loader.cache.size, 1);
  loader.reset();
  assert.equal(loader.cache.size, 0);
});

test('failed batches are retryable and families and intervals use separate cache identities', async () => {
  let requests = 0;
  const loader = new PatternRangeLoader('AAPL', async () => {
    requests++;
    return {ok: requests !== 1, json: async () => ({signals: [], structures: []})};
  });
  const range = {from: 1700000000, to: 1700000001};
  await assert.rejects(loader.load('candles', '1d', range));
  await loader.load('candles', '1d', range);
  await loader.load('waves', '1d', range);
  await loader.load('candles', '1wk', range);
  assert.equal(requests, 4);
});
