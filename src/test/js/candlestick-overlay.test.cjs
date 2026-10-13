const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { StockWatchI18n } = require('./i18n-stub.cjs');

function workspace() {
  const nodes = new Map(), pending = [];
  function element() {
    const classes = new Set();
    return {children: [], attributes: {}, listeners: {}, style: {},
      append(...items) { this.children.push(...items); }, replaceChildren(...items) { this.children = items; },
      setAttribute(key, value) { this.attributes[key] = value; },
      addEventListener(key, callback) { this.listeners[key] = callback; },
      click() { this.listeners.click?.({stopPropagation() {}}); }, focus() { this.focused = true; },
      classList: {toggle(key, active) {active ? classes.add(key) : classes.delete(key);}, add(key) {classes.add(key);}, remove(...keys) {keys.forEach(key => classes.delete(key));}}
    };
  }
  const get = id => {if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id);};
  const series = {data: [1], setData(data) {this.data = data;}};
  const context = {document: {createElement: element, getElementById: get}, console, i18n: StockWatchI18n,
    historicalCandlestickOverlayEnabled: false, historicalCandlestickOverlayRequestSequence: 0,
    historicalCandlestickOverlayHistory: null, historicalCandlestickOverlaySignals: [],
    historicalCandlestickTrendSeries: series, historicalCandlestickFormationSeries: series,
    currentInterval: '1d', encodedTicker: 'AAPL', globalCandleData: [],
    patternRangeLoader: null,
    loadPatternRange() {return new Promise(resolve => pending.push(resolve));},
    // The indicator manager shows the overlay as a removable row next to the indicators.
    indicatorManager: {overlays: [], setOverlays(items) {this.overlays = items;}},
    chartType: 'candles', StockWatchChartTypes: {isOhlc: type => ['candles', 'hollow', 'bars'].includes(type)},
    setChartType(type) {context.chartType = type;},
    hideHistoricalCandlestickCard() {context.cardHidden = true;},
    rebuildHistoricalCandlestickHitTargets() {context.rebuilt = true;},
    showHistoricalCandlestickLookbackPicker() {context.pickerOpened = true;},
    fetch() {return new Promise(resolve => pending.push(data => resolve({ok:true, json: async () => data})));}
  };
  vm.createContext(context);
  const source = fs.readFileSync('src/main/resources/static/js/stock-workspace.js', 'utf8');
  for (const name of ['renderChartOverlayChips', 'showHistoricalCandlestickViewPicker',
    'enableHistoricalCandlestickOverlay', 'disableHistoricalCandlestickOverlay',
    'updateHistoricalCandlestickOverlayToggle', 'refreshHistoricalCandlestickOverlay',
    'apiIntervalEnum', 'updateHistoricalCandlestickOverlayStatus',
    'clearHistoricalCandlestickOverlayVisuals']) {
    const declaration = source.match(new RegExp('^  (?:async )?function '+name+'\\([\\s\\S]*?^  }', 'm'));
    assert.ok(declaration, name);
    vm.runInContext(declaration[0], context);
  }
  return {context, get, pending, series};
}

test('overlay appears in the indicator legend with a remove button that clears every visual', async () => {
  const {context:c, get, pending, series} = workspace();
  const enable = c.enableHistoricalCandlestickOverlay();
  pending.shift()({signals:[{signalTimestamp:100}], completedCandlesLoaded:30}); await enable;
  assert.equal(c.indicatorManager.overlays.length, 1);
  assert.equal(c.indicatorManager.overlays[0].label, 'Candlestick signals');
  c.indicatorManager.overlays[0].onRemove();
  assert.equal(c.historicalCandlestickOverlayEnabled, false);
  assert.equal(get('candlestickOverlayToggle').attributes['aria-pressed'], 'false');
  assert.equal(get('candlestickOverlayToggle').focused, true);
  assert.equal(c.indicatorManager.overlays.length, 0);
  assert.equal(series.data.length, 0);
  assert.equal(c.cardHidden, true);
  assert.equal(get('candlestickOverlayStatus').textContent, '');
});

test('candlestick signals switch a line chart back to candles', async () => {
  const {context:c, pending} = workspace();
  c.chartType = 'area';
  const enable = c.enableHistoricalCandlestickOverlay();
  assert.equal(c.chartType, 'candles');
  pending.shift()({signals:[], completedCandlesLoaded:30}); await enable;
});

test('removing while loading prevents a late response from restoring markers or status', async () => {
  const {context:c, get, pending} = workspace();
  const enable = c.enableHistoricalCandlestickOverlay();
  c.indicatorManager.overlays[0].onRemove();
  pending.shift()({signals:[{signalTimestamp:100}]}); await enable;
  assert.equal(c.historicalCandlestickOverlayHistory, null);
  assert.equal(c.historicalCandlestickOverlaySignals.length, 0);
  assert.equal(c.rebuilt, undefined);
  assert.equal(get('candlestickOverlayStatus').textContent, '');
});

test('menu toggles off and a new activation ignores the previous request', async () => {
  const {context:c, pending} = workspace();
  const first = c.enableHistoricalCandlestickOverlay();
  c.showHistoricalCandlestickViewPicker();
  assert.equal(c.historicalCandlestickOverlayEnabled, false);
  c.showHistoricalCandlestickViewPicker();
  assert.equal(c.pickerOpened, true);
  const second = c.enableHistoricalCandlestickOverlay();
  pending.pop()({signals:[{signalTimestamp:200}], completedCandlesLoaded:40}); await second;
  pending.shift()({signals:[{signalTimestamp:100}], completedCandlesLoaded:30}); await first;
  assert.equal(c.historicalCandlestickOverlaySignals[0].signalTimestamp, 200);
});
