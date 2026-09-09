const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function render(snapshot, report = {}) {
    class Node extends EventTarget {
        constructor(tagName) { super(); this.tagName = tagName; this.children = []; this.dataset = {}; this.attributes = {}; }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { this.children = nodes; }
        setAttribute(key, value) { this.attributes[key] = value; }
        getAttribute(key) { return this.attributes[key]; }
        querySelectorAll() { return []; }
        get clientWidth() { return 800; }
    }
    const nodes = new Map();
    const document = {
        createElement: tag => new Node(tag), createElementNS: (_, tag) => new Node(tag), querySelectorAll: () => [],
        getElementById(id) { if (!nodes.has(id)) nodes.set(id, Object.assign(new Node('div'), {id})); return nodes.get(id); }
    };
    const charts = [];
    const context = {document, Event, ResizeObserver: class { observe() {} }, LightweightCharts: {
        createChart(host) {
            const chart = {host, series: [], fits: 0, applyOptions() {}, timeScale() { return {fitContent: () => chart.fits++}; }};
            const add = options => {
                const series = {options, dataWrites: 0, setData(data) { this.data = data; this.dataWrites++; },
                    applyOptions(next) { Object.assign(this.options, next); }, setMarkers(markers) { this.markers = markers; }};
                chart.series.push(series); return series;
            };
            chart.addLineSeries = add; chart.addCandlestickSeries = add;
            charts.push(chart); return chart;
        }
    }};
    context.window = context;
    context.StockWatchCharts = {...context.LightweightCharts, color: value => value};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/chart-legend.js', 'utf8'), context);
    const template = fs.readFileSync('src/main/resources/templates/technical-outlook-change.html', 'utf8');
    const script = template.match(/<script th:inline="javascript"[^>]*>([\s\S]*?)<\/script>/)[1]
        .replace(/const currentSnapshot = .*;/, 'const currentSnapshot = ' + JSON.stringify(snapshot) + ';')
        .replace(/const changeReport = .*;/, 'const changeReport = ' + JSON.stringify(report) + ';');
    vm.runInContext(script, context);
    function all(node) { return [node, ...node.children.flatMap(all)]; }
    const descendants = id => all(nodes.get(id));
    return {charts, nodes,
        inputs: id => descendants(id).filter(node => node.tagName === 'input'),
        option: key => descendants('outlookChangeOverlayLegend').find(node => node.dataset.legendKey === key),
        bulk(id, text) { descendants(id).find(node => node.tagName === 'button' && node.textContent === text).dispatchEvent(new Event('click')); },
        toggle(input, visible) { input.checked = visible; input.dispatchEvent(new Event('change')); },
        descendants};
}

const snapshot = {
    indicatorSettings: {fastEmaPeriod: 12, slowEmaPeriod: 26, longSmaPeriod: 200, bollingerPeriod: 18, bollingerDeviation: 2.5, vwapPeriod: 22, volumeProfilePeriod: 40},
    candles: [1, 2, 3].map(timestamp => ({timestamp, open: 100, high: 110, low: 90, close: 105,
        fastEma: timestamp === 1 ? null : 101, slowEma: 102, longSma: null,
        bollingerLower: 90, bollingerMiddle: 100, bollingerUpper: 110, vwap: 103, pointOfControl: 104})),
    indicators: [{key: 'rsi', label: 'RSI (14)', classification: 'NEUTRAL', currentValue: 50,
        series: [{timestamp: 1, value: null}, {timestamp: 2, value: 0}, {timestamp: 3, value: 50}], referenceLines: [30, 70]}]
};
const overlayHost = 'outlookChangeOverlayLegend', panelHost = 'outlookChangePanelLegend';

test('legend uses saved periods and exact series colours/styles, without plotting missing values at zero', () => {
    const app = render(snapshot);
    const labels = app.descendants(overlayHost).filter(node => node.tagName === 'span').map(node => node.textContent);
    assert.ok(labels.includes('Fast EMA (12)'));
    assert.ok(labels.includes('Bollinger (18) 2.5σ · Upper'));
    assert.ok(labels.includes('Long SMA (200) · No data'));
    const series = app.charts[0].series;
    assert.equal(series[1].data.length, 2);
    assert.equal(series[3].data.length, 0);
    assert.equal(app.option('longSma').disabled, true);
    assert.equal(series[4].options.lineStyle, 2);
    assert.equal(series[6].options.lineStyle, 1);
    const swatches = app.descendants(overlayHost).filter(node => node.tagName === 'path');
    assert.equal(swatches[0].attributes.stroke, series[1].options.color);
    assert.equal(swatches[3].attributes['stroke-dasharray'], '6 3');
    assert.equal(app.charts[1].series[0].data[0].value, 0); // A real zero remains valid.
});

test('individual toggles and bulk actions preserve candles, snapshots and chart zoom', () => {
    const before = JSON.stringify(snapshot);
    const app = render(snapshot);
    app.toggle(app.option('fastEma'), false);
    assert.equal(app.charts[0].series[1].options.visible, false);
    assert.equal(app.charts[0].series[2].options.visible, undefined);
    app.bulk(overlayHost, 'Hide all');
    app.charts[0].series.filter((series, i) => i > 0 && i !== 3).forEach(series => assert.equal(series.options.visible, false));
    assert.equal(app.charts[0].series[0].options.visible, undefined);
    app.bulk(overlayHost, 'Show all');
    assert.equal(app.charts[0].series[1].options.visible, true);
    assert.equal(app.option('longSma').checked, false);
    assert.equal(app.charts[0].fits, 1);
    app.charts[0].series.forEach(series => assert.equal(series.dataWrites, 1));
    assert.equal(JSON.stringify(snapshot), before);
});

test('indicator panels and signal marker groups toggle independently', () => {
    const app = render(snapshot, {signalEvidence: [
        {timestamp: 2, direction: 'BUY', alignment: 'SUPPORTS', label: 'Buy signal'},
        {timestamp: 3, direction: 'SELL', alignment: 'CONTRADICTS', label: 'Sell signal'}
    ]});
    app.toggle(app.option('markers-#3ddc97'), false);
    assert.equal(app.charts[0].series[0].markers.length, 1);
    assert.equal(app.charts[0].series[0].markers[0].text, 'Sell signal');
    app.bulk(panelHost, 'Hide all');
    const card = app.nodes.get('outlookChangeIndicatorCharts').children[0];
    assert.equal(card.hidden, true);
    app.bulk(panelHost, 'Show all');
    assert.equal(card.hidden, false);
    assert.equal(app.charts[0].series[0].markers.length, 1);
    assert.ok(card.children.some(node => node.textContent === 'Dashed lines: reference levels 30 / 70'));
});

test('older and empty snapshots do not invent periods or enable unavailable overlays', () => {
    const app = render({candles: [{timestamp: 1, open: 1, high: 2, low: 1, close: 2}]});
    assert.ok(app.inputs(overlayHost).every(input => input.disabled));
    assert.equal(app.inputs(panelHost).length, 0);
    const labels = app.descendants(overlayHost).filter(node => node.tagName === 'span').map(node => node.textContent);
    assert.ok(labels.includes('Fast EMA · No data'));
    app.bulk(overlayHost, 'Hide all');
    app.bulk(overlayHost, 'Show all');
});
