const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { StockWatchI18n } = require('./i18n-stub.cjs');

// MARA 1d, June 2026: a Bearish Crab and a standalone Bearish AB=CD both finish on the 22 Jun high.
const D = {label: 'D', timestamp: 1782086400, price: 16.43, pivotType: 'HIGH'};
const point = (label, timestamp, price, pivotType) => ({label, timestamp, price, pivotType});
const abcd = {pattern: 'AB_CD', direction: 'BEARISH', tradeSignal: 'SELL', qualityScore: 90,
  confirmationTimestamp: 1782259200, points: [point('A', 1781136000, 12.5, 'LOW'),
    point('B', 1781568000, 15.26, 'HIGH'), point('C', 1781740800, 13.71, 'LOW'), D]};
const crab = {pattern: 'CRAB', direction: 'BEARISH', tradeSignal: 'SELL', qualityScore: 89,
  confirmationTimestamp: 1782259200, points: [point('X', 1765238400, 12.87, 'HIGH'),
    point('A', 1770249600, 6.66, 'LOW'), point('B', 1773360000, 10.03, 'HIGH'),
    point('C', 1774915200, 7.63, 'LOW'), D]};
const shark = (b) => ({pattern: 'SHARK', direction: 'BULLISH', tradeSignal: 'BUY', qualityScore: 97,
  confirmationTimestamp: 1781049600, points: [point('X', 1779148800, 11.53, 'LOW'),
    point('A', 1779753600, 14.87, 'HIGH'), point('B', b, 13.58, 'LOW'),
    point('C', 1780272000, 15.32, 'HIGH'), point('D', 1780617600, 11.84, 'LOW')]});

function workspace() {
  const nodes = new Map();
  function element() {
    return {children: [], attributes: {}, listeners: {}, style: {}, hidden: true, textContent: '',
      classList: {toggle() {}},
      appendChild(item) { this.children.push(item); }, replaceChildren(...items) { this.children = items; },
      setAttribute(key, value) { this.attributes[key] = value; },
      addEventListener(key, callback) { this.listeners[key] = callback; }};
  }
  const get = id => {if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id);};
  const context = {document: {createElement: element, getElementById: get}, i18n: StockWatchI18n, clearTimeout,
    HARMONIC_FORMATION_COLOR: '#22c55e', encodedTicker: 'MARA', currentInterval: '1d', globalCandleData: [],
    harmonicFormationSeries: [], harmonicFormationOverlays: [], activeHarmonicOverlays: [],
    activeHarmonicOverlayIndex: 0, harmonicCardPinned: false, harmonicCardHideTimer: null,
    addPriceOverlayLine() {
      return {width: 2, setData(data) { this.data = data; }, setMarkers(markers) { this.markers = markers; },
        applyOptions(options) { this.width = options.lineWidth; }};
    },
    timestampToChartDate: timestamp => timestamp, harmonicOverlayColors: () => ({line: '#22c55e', marker: '#22c55e'}),
    hideElliottWaveCard() {}, hideHistoricalCandlestickCard() {}, positionHarmonicFormationCard() {},
    scheduleHarmonicHitTargetPositioning() {}, scheduleHarmonicFormationCardHide() {},
    tradeSignalDisplayLabel: signal => signal, formatHistoricalElliottPeriod: () => '24 Jun 2026',
    formatSignedPercent: value => `${value}%`};
  vm.createContext(context);
  const source = fs.readFileSync('src/main/resources/static/js/stock-workspace.js', 'utf8');
  for (const name of ['harmonicFormationGroups', 'compareHarmonicFormations', 'renderHarmonicFormationGroup',
    'harmonicEndpointText', 'renderHarmonicFormation', 'rebuildHarmonicHitTargets', 'showHarmonicFormationCard',
    'moveHarmonicFormationCard', 'renderActiveHarmonicFormationCard', 'harmonicPatternName', 'harmonicDisplayName']) {
    const declaration = source.match(new RegExp('^  function ' + name + '\\([\\s\\S]*?^  }', 'm'));
    assert.ok(declaration, name);
    vm.runInContext(declaration[0], context);
  }
  const render = formations => {
    context.harmonicFormationGroups(formations).forEach(context.renderHarmonicFormationGroup);
    context.rebuildHarmonicHitTargets();
  };
  return {context, get, render};
}

test('formations sharing a D point get one target that opens the named five-point formation first', () => {
  const {context: c, get, render} = workspace();
  render([abcd, crab]);
  const targets = get('harmonicFormationHitTargets').children;
  assert.equal(targets.length, 1);
  assert.equal(targets[0].attributes['aria-label'], 'D point, harmonic formations: Bearish Crab, Bearish AB=CD');
  const endpointLabels = c.harmonicFormationSeries.map(series => series.markers.at(-1).text);
  assert.deepEqual(endpointLabels, ['D · Bearish Crab Q89 + AB=CD Q90', '']);

  targets[0].listeners.click({stopPropagation() {}});
  assert.equal(get('harmonicFormationCardTitle').textContent, 'Bearish Crab');
  assert.equal(get('harmonicFormationCardNavigation').hidden, false);
  assert.equal(get('harmonicFormationCardPosition').textContent, '1 of 2 formations ending at this point');
  assert.match(get('harmonicFormationCardLink').href, /\/harmonic-formations\/1d\/CRAB\/1782086400$/);
  assert.deepEqual(c.harmonicFormationSeries.map(series => series.width), [4, 2]);

  c.moveHarmonicFormationCard(1);
  assert.equal(get('harmonicFormationCardTitle').textContent, 'Bearish AB=CD');
  assert.equal(get('harmonicFormationCardPosition').textContent, '2 of 2 formations ending at this point');
  assert.match(get('harmonicFormationCardLink').href, /\/AB_CD\/1782086400$/);
  assert.deepEqual(c.harmonicFormationSeries.map(series => series.width), [2, 4]);
  c.moveHarmonicFormationCard(1);
  assert.equal(get('harmonicFormationCardTitle').textContent, 'Bearish Crab');
});

test('a lone formation keeps its own label and shows no switcher', () => {
  const {context: c, get, render} = workspace();
  render([abcd]);
  assert.equal(c.harmonicFormationSeries[0].markers.at(-1).text, 'D · Bearish AB=CD · Q90');
  const [target] = get('harmonicFormationHitTargets').children;
  assert.equal(target.attributes['aria-label'], 'D point, Bearish AB=CD harmonic formation');
  target.listeners.click({stopPropagation() {}});
  assert.equal(get('harmonicFormationCardTitle').textContent, 'Bearish AB=CD');
  assert.equal(get('harmonicFormationCardNavigation').hidden, true);
});

test('near-duplicates of one pattern on the same D are drawn but listed once', () => {
  const {context: c, get, render} = workspace();
  render([shark(1779926400), shark(1780012800)]);
  assert.equal(c.harmonicFormationSeries.length, 2);
  assert.deepEqual(c.harmonicFormationSeries.map(series => series.markers.at(-1).text),
    ['D · Bullish Shark · Q97', '']);
  const targets = get('harmonicFormationHitTargets').children;
  assert.equal(targets.length, 1);
  targets[0].listeners.click({stopPropagation() {}});
  assert.equal(get('harmonicFormationCardNavigation').hidden, true);
});
