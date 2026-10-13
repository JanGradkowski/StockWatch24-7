const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const {StockWatchI18n} = require('./i18n-stub.cjs');

const SCRIPTS = ['chart-drawing-core.js', 'chart-drawing-icons.js', 'chart-types.js', 'chart-indicators.js', 'chart-alerts.js'];
const DAY = 86400;

const CATALOG = {
    formulaFunctions: ['sma', 'ema'],
    formulaSeries: ['close'],
    indicators: [
        {type: 'RSI', shortName: 'RSI', category: 'MOMENTUM', placement: 'PANEL', visibleRangeDependent: false, shapes: false,
            inputs: [{key: 'period', kind: 'INTEGER', defaultValue: 14, min: 2, max: 500, step: 1},
                {key: 'source', kind: 'SOURCE', defaultValue: 'close', choiceSet: 'source', options: ['close', 'open']},
                {key: 'upper', kind: 'DECIMAL', defaultValue: 70, min: 0, max: 100, step: 1},
                {key: 'lower', kind: 'DECIMAL', defaultValue: 30, min: 0, max: 100, step: 1},
                {key: 'maType', kind: 'CHOICE', defaultValue: 'NONE', choiceSet: 'smoothing', options: ['NONE', 'SMA']},
                {key: 'maLength', kind: 'INTEGER', defaultValue: 14, min: 1, max: 500, step: 1}],
            outputs: [{key: 'value', style: 'LINE', color: '#a78bfa', width: 2, lineStyle: 0, visible: true},
                {key: 'ma', style: 'LINE', color: '#ffbd59', width: 1, lineStyle: 0, visible: true}],
            fills: [], references: [50], zones: {upperInput: 'upper', lowerInput: 'lower'}},
        {type: 'EMA', shortName: 'EMA', category: 'MOVING_AVERAGES', placement: 'PRICE', visibleRangeDependent: false, shapes: false,
            inputs: [{key: 'period', kind: 'INTEGER', defaultValue: 20, min: 2, max: 500, step: 1},
                {key: 'source', kind: 'SOURCE', defaultValue: 'close', choiceSet: 'source', options: ['close', 'open']},
                {key: 'offset', kind: 'INTEGER', defaultValue: 0, min: -500, max: 500, step: 1}],
            outputs: [{key: 'value', style: 'LINE', color: '#60a5fa', width: 2, lineStyle: 0, visible: true}], fills: [], references: []},
        {type: 'BOLLINGER', shortName: 'BB', category: 'VOLATILITY', placement: 'PRICE', visibleRangeDependent: false, shapes: false,
            inputs: [{key: 'period', kind: 'INTEGER', defaultValue: 20, min: 2, max: 500, step: 1},
                {key: 'deviation', kind: 'DECIMAL', defaultValue: 2.5, min: 0.1, max: 10, step: 0.1}],
            outputs: ['lower', 'middle', 'upper'].map(key => ({key, style: 'LINE', color: '#60a5fa', width: 1, lineStyle: 0, visible: true})),
            fills: [{upper: 'upper', lower: 'lower', color: 'rgba(96,165,250,0.1)'}], references: []},
        {type: 'MACD', shortName: 'MACD', category: 'MOMENTUM', placement: 'PANEL', visibleRangeDependent: false, shapes: false,
            inputs: [{key: 'fast', kind: 'INTEGER', defaultValue: 12, min: 1, max: 500, step: 1},
                {key: 'slow', kind: 'INTEGER', defaultValue: 26, min: 1, max: 500, step: 1},
                {key: 'signal', kind: 'INTEGER', defaultValue: 9, min: 1, max: 500, step: 1}],
            outputs: [{key: 'macd', style: 'LINE', color: '#60a5fa', width: 2, lineStyle: 0, visible: true},
                {key: 'signal', style: 'LINE', color: '#ffbd59', width: 2, lineStyle: 0, visible: true},
                {key: 'histogram', style: 'HISTOGRAM', color: '#26a69a', width: 1, lineStyle: 0, visible: true, colorMode: 'SIGN_MOMENTUM'}],
            fills: [], references: [0]}
    ]
};

function weekdays(count) {
    const result = [];
    for (let day = Date.UTC(2026, 0, 5) / 1000; result.length < count; day += DAY) {
        const weekday = new Date(day * 1000).getUTCDay();
        if (weekday !== 0 && weekday !== 6) result.push(day);
    }
    return result;
}

function fakeChart(window) {
    const panes = [];
    const series = [];
    const makePane = () => {
        const node = window.document.createElement('div');
        node.getBoundingClientRect = () => ({left: 0, top: 0, width: 800, height: 120});
        const pane = {
            stretch: 1,
            setStretchFactor(value) { this.stretch = value; },
            getStretchFactor() { return this.stretch; },
            setPreserveEmptyPane() {},
            getHeight() { return Math.round(this.stretch); },
            getHTMLElement: () => node,
            moveTo(index) { panes.splice(panes.indexOf(pane), 1); panes.splice(index, 0, pane); }
        };
        return pane;
    };
    const makeSeries = (definition, options, pane) => ({
        definition, options: {...options}, pane, points: [], lines: [], primitives: [],
        setData(data) { this.points = data; },
        data() { return this.points; },
        applyOptions(next) { Object.assign(this.options, next); },
        createPriceLine(line) { this.lines.push(line); return {applyOptions() {}}; },
        attachPrimitive(primitive) { this.primitives.push(primitive); primitive.attached?.({requestUpdate() {}, series: this}); },
        detachPrimitive(primitive) { this.primitives = this.primitives.filter(item => item !== primitive); },
        priceScale: () => ({applyOptions() {}}),
        priceFormatter: () => ({format: value => Number(value).toFixed(2)}),
        priceToCoordinate: price => 500 - price
    });
    panes.push(makePane());
    const element = window.document.createElement('div');
    Object.defineProperty(element, 'clientHeight', {value: 826});
    const chart = {
        series,
        panes: () => [...panes],
        addPane() { const pane = makePane(); panes.push(pane); return pane; },
        removePane(index) {
            const pane = panes[index];
            [...series].filter(item => item.pane === pane).forEach(item => chart.removeSeries(item));
            panes.splice(index, 1);
        },
        swapPanes(first, second) { [panes[first], panes[second]] = [panes[second], panes[first]]; },
        addSeries(definition, options, index = 0) { const item = makeSeries(definition, options, panes[index]); series.push(item); return item; },
        addCustomSeries(view, options, index = 0) { const item = makeSeries('Custom', options, panes[index]); series.push(item); return item; },
        removeSeries(item) { const index = series.indexOf(item); if (index >= 0) series.splice(index, 1); },
        subscribeCrosshairMove() {},
        unsubscribeCrosshairMove() {},
        chartElement: () => element,
        timeScale: () => ({
            height: () => 26,
            getVisibleLogicalRange: () => ({from: 0, to: 59}),
            subscribeVisibleLogicalRangeChange() {},
            unsubscribeVisibleLogicalRangeChange() {},
            logicalToCoordinate: logical => logical * 10
        })
    };
    return chart;
}

/** The server's batch answer: a value per candle for every output; period 999 is a setting it rejects. */
function batch(body) {
    return {indicators: body.indicators.map(config => {
        if (config.parameters.period === 999) return {id: config.id, type: config.type, error: 'period must be a whole number from 2 to 500.'};
        const definition = CATALOG.indicators.find(item => item.type === config.type);
        const series = definition.outputs.map((output, outputIndex) => ({key: output.key, label: output.key, style: output.style,
            points: weekdays(60).map((timestamp, index) => ({timestamp, value: 50 + index + outputIndex}))}));
        return {id: config.id, type: config.type, label: config.type, placement: definition.placement, parameters: config.parameters,
            series, references: definition.references, markers: [], shapes: []};
    })};
}

async function setup({layout = null} = {}) {
    const dom = new JSDOM('<!doctype html><html><body><div id="stage"></div></body></html>', {pretendToBeVisual: true, runScripts: 'outside-only',
        url: 'http://localhost/'});
    const window = dom.window;
    const requests = [];
    window.StockWatchI18n = StockWatchI18n;
    window.LightweightCharts = {LineSeries: 'Line', HistogramSeries: 'Histogram', AreaSeries: 'Area', CandlestickSeries: 'Candlestick',
        BarSeries: 'Bar', BaselineSeries: 'Baseline', LineType: {Simple: 0, WithSteps: 1}, LineStyle: {Dashed: 2},
        customSeriesDefaultOptions: {}, createSeriesMarkers: () => ({setMarkers() {}})};
    window.fetch = async (url, options = {}) => {
        requests.push({url, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null});
        if (url === '/api/chart-indicators/catalog') return {ok: true, status: 200, json: async () => CATALOG};
        if (url.startsWith('/api/chart-state/layouts/') && (options.method || 'GET') === 'GET') {
            return layout ? {ok: true, status: 200, json: async () => ({payload: layout})} : {ok: true, status: 204, json: async () => ({})};
        }
        if (url.includes('/chart-indicators?interval=')) return {ok: true, status: 200, json: async () => batch(JSON.parse(options.body))};
        return {ok: true, status: 200, json: async () => ({})};
    };
    SCRIPTS.forEach(name => window.eval(fs.readFileSync(`src/main/resources/static/js/${name}`, 'utf8')));
    const times = weekdays(60);
    const candles = times.map((timestamp, index) => ({time: new Date(timestamp * 1000).toISOString().slice(0, 10), timestamp,
        open: 100 + index, high: 102 + index, low: 99 + index, close: 101 + index}));
    const chart = fakeChart(window);
    const stage = window.document.getElementById('stage');
    stage.getBoundingClientRect = () => ({left: 0, top: 0, width: 800, height: 826});
    const loaded = [];
    const manager = new window.StockWatchIndicatorManager({chart, priceSeries: chart.addSeries('Candlestick', {}, 0), stage, symbol: 'AAPL',
        interval: '1d', candles: () => candles, volume: () => candles.map(candle => ({time: candle.time, value: 1000})), onPanesChanged() {}});
    manager.onLayoutLoaded = extras => loaded.push(extras);
    await manager.ready;
    return {window, manager, chart, requests, candles, loaded};
}

const settle = () => new Promise(resolve => setTimeout(resolve, 30));
// Values made in the page's window have that window's prototypes; compare them as plain data.
const plain = value => JSON.parse(JSON.stringify(value));

test('panel indicators get their own pane below the volume pane; overlays stay on the price', async () => {
    const {manager, chart} = await setup();
    manager.setVolume(true);
    manager.dataChanged();
    const rsi = manager.add('RSI');
    const ema = manager.add('EMA');
    await settle();
    assert.deepEqual(plain(manager.slots), ['price', 'volume', rsi.id]);
    assert.equal(chart.panes().length, 3);
    const emaLine = chart.series.find(item => item.definition === 'Custom' && item.pane === chart.panes()[0]);
    assert.ok(emaLine, 'EMA is drawn in the price pane');
    // It carries the candles' closes, so percentage and indexed scales measure it from the candles' base.
    assert.ok(emaLine.points.every(item => Number.isFinite(item.anchor)));
    assert.equal(emaLine.points[0].anchor, 101);
    assert.ok(chart.series.some(item => item.pane === chart.panes()[2]), 'RSI is drawn in its pane');
    // RSI zones: a band between the levels and the 50 reference line.
    assert.ok(chart.series.some(item => item.definition === 'Custom' && item.pane === chart.panes()[2]));
    manager.remove(rsi.id);
    assert.deepEqual(plain(manager.slots), ['price', 'volume']);
    assert.equal(chart.panes().length, 2);
    assert.ok(manager.find(ema.id));
});

test('legend labels show the settings that change the calculation', async () => {
    const {manager} = await setup();
    const rsi = manager.add('RSI');
    const bands = manager.add('BOLLINGER');
    const macd = manager.add('MACD');
    const ema = manager.add('EMA', {inputs: {source: `ind:${rsi.id}:value`}});
    assert.equal(manager.label(rsi), 'RSI 14');
    assert.equal(manager.label(bands), 'BB 20 2.5');
    assert.equal(manager.label(macd), 'MACD 12 26 9');
    assert.equal(manager.label(ema), 'EMA 20 (RSI)');
    manager.update(rsi.id, config => { config.inputs.maType = 'SMA'; config.inputs.source = 'open'; }, false);
    assert.equal(manager.label(rsi), 'RSI 14 Open 14');
});

test('a rejected setting marks only that indicator and gets no pane', async () => {
    const {manager, window} = await setup();
    manager.dataChanged();
    const broken = manager.add('RSI', {inputs: {period: 999}});
    const working = manager.add('MACD');
    await settle();
    assert.match(broken.error, /2 to 500/);
    assert.equal(working.error, null);
    assert.deepEqual(plain(manager.slots), ['price', working.id]);
    const row = [...window.document.querySelectorAll('[data-indicator]')].find(node => node.dataset.indicator === broken.id);
    assert.match(row.textContent, /2 to 500/);
});

test('panes share the chart height by weight and the price pane keeps 40% however many there are', async () => {
    const {manager, chart} = await setup();
    manager.setVolume(true);
    manager.dataChanged();
    manager.add('MACD');
    await settle();
    let [price, volume, macd] = chart.panes();
    assert.deepEqual([price.stretch, volume.stretch, macd.stretch], [480, 110, 150]);

    // Five more panes: the lower panes weigh 1,010, so the price pane gets 40% of the chart, not 480 / 1,490.
    for (let count = 0; count < 5; count++) manager.add('RSI');
    await settle();
    const stretches = chart.panes().map(pane => pane.stretch);
    const total = stretches.reduce((sum, value) => sum + value, 0);
    assert.equal(stretches.length, 8);
    assert.equal(Math.round(1000 * stretches[0] / total) / 1000, 0.4);
    assert.deepEqual(stretches.slice(1), [110, 150, 150, 150, 150, 150, 150]);

    // Dragging the separator under the volume pane moves weight between its two neighbours only.
    [price, volume, macd] = chart.panes();
    volume.stretch = 160;
    macd.stretch = 100;
    manager.capturePaneHeights();
    assert.equal(manager.paneHeights.volume, 160);
    assert.equal(manager.paneHeights[manager.slots[2]], 100);
    assert.equal(manager.paneHeights.price, undefined);
    assert.deepEqual(chart.panes().slice(1, 3).map(pane => pane.stretch), [160, 100]);
});

test('the layout is saved without errors and restored with its chart settings', async () => {
    const {manager, requests} = await setup();
    const rsi = manager.add('RSI', {inputs: {period: 21}});
    rsi.error = 'stale';
    manager.setExtra('chartType', 'area');
    const snapshot = manager.snapshot();
    assert.equal(snapshot.indicators[0].inputs.period, 21);
    assert.equal(snapshot.indicators[0].error, undefined);
    await manager.saveLayout();
    assert.ok(requests.some(request => request.method === 'PUT' && request.url === '/api/chart-state/layouts/stock'));

    const restored = await setup({layout: {indicators: [...snapshot.indicators, {id: 'gone', type: 'NOT_IN_CATALOG'}], extras: snapshot.extras}});
    assert.deepEqual(plain(restored.manager.indicators.map(config => config.type)), ['RSI']);
    assert.deepEqual(plain(restored.loaded), [{chartType: 'area'}]);
});

test('alerts follow the drawn shape, including the parallel line of a channel', async () => {
    const {window, candles} = await setup();
    const alerts = window.StockWatchChartAlerts;
    const mapper = new window.StockWatchDrawing.TimeMapper(candles.map(candle => candle.timestamp));
    const t = index => candles[index].timestamp;
    assert.deepEqual(plain(alerts.geometry({type: 'horizontalRay', points: [{time: t(3), price: 120}]}, mapper)),
        {geometry: 'HORIZONTAL', price: 120, from: t(3)});
    assert.deepEqual(plain(alerts.geometry({type: 'ray', points: [{time: t(1), price: 100}, {time: t(5), price: 104}], style: {}}, mapper)),
        {geometry: 'LINE', a: {time: t(1), price: 100}, b: {time: t(5), price: 104}, extendLeft: false, extendRight: true});
    const channel = alerts.geometry({type: 'parallelChannel', points: [{time: t(0), price: 100}, {time: t(10), price: 110}, {time: t(5), price: 95}],
        style: {extendRight: true}}, mapper);
    // The line is at 105 at the third point, so the parallel line sits 10 lower.
    assert.deepEqual(plain(channel.lower), {a: {time: t(0), price: 90}, b: {time: t(10), price: 100}});
    assert.deepEqual(plain(channel.upper), {a: {time: t(0), price: 100}, b: {time: t(10), price: 110}});
    assert.equal(channel.extendRight, true);
    assert.deepEqual(plain(alerts.geometry({type: 'rectangle', points: [{time: t(8), price: 90}, {time: t(2), price: 99}], style: {}}, mapper)),
        {geometry: 'BOX', from: t(2), to: t(8), top: 99, bottom: 90, extendLeft: false, extendRight: false});
    assert.equal(alerts.geometry({type: 'fibRetracement', points: []}, mapper), null);
});

test('an indicator alert sends the indicators it reads first', async () => {
    const {window, manager} = await setup();
    const rsi = manager.add('RSI');
    const ema = manager.add('EMA', {inputs: {source: `ind:${rsi.id}:value`}});
    const configurations = window.StockWatchChartAlerts.indicatorConfigurations(manager, ema);
    assert.deepEqual(plain(configurations.map(item => item.id)), [rsi.id, ema.id]);
    assert.equal(configurations[1].parameters.source, `ind:${rsi.id}:value`);
    assert.equal(configurations[0].parameters.period, 14);
});

test('chart types transform candles and date presets count back from the last candle', async () => {
    const {window} = await setup();
    const types = window.StockWatchChartTypes;
    const candles = [{time: 'a', open: 10, high: 14, low: 8, close: 12}, {time: 'b', open: 12, high: 16, low: 11, close: 15}];
    const ashi = types.heikinAshi(candles);
    assert.deepEqual(plain(ashi[0]), {time: 'a', open: 11, high: 14, low: 8, close: 11});
    assert.equal(ashi[1].open, 11);
    assert.equal(ashi[1].close, 13.5);
    assert.deepEqual(plain(types.data('highLow', candles, {})[1]), {time: 'b', open: 11, high: 16, low: 11, close: 16});
    const last = Date.UTC(2026, 9, 9) / 1000;
    assert.equal(types.rangeStart('YTD', last), Date.UTC(2026, 0, 1) / 1000);
    assert.equal(types.rangeStart('3M', last), Date.UTC(2026, 6, 9) / 1000);
    assert.equal(types.rangeStart('ALL', last), null);
    assert.equal(types.nearestIndex([10, 20, 30, 40], 26), 2);
    assert.equal(types.nearestIndex([10, 20, 30, 40], 99), 3);
    assert.equal(types.isOhlc('hollow'), true);
    assert.equal(types.isOhlc('area'), false);
});

test('price-only types draw bricks, lines, Kagi turns and Point & Figure columns from closes', async () => {
    const {window} = await setup();
    const types = window.StockWatchChartTypes;
    const series = closes => closes.map((close, index) => ({timestamp: 1000 + index, open: close, high: close + 0.5, low: close - 0.5, close}));

    // Renko, box 1: up from 10 to 13, then a reversal needs a close two boxes below the last top.
    const bricks = types.renko(series([10, 11.2, 13.4, 12.5, 11.9, 9.6]), 1);
    assert.deepEqual(plain(bricks.map(brick => [brick.open, brick.close])), [[10, 11], [11, 12], [12, 13], [12, 11], [11, 10]]);
    assert.equal(bricks[3].timestamp, 1005, 'closes of 12.5 and 11.9 are not two boxes under the top of 13');

    // Three-line break: a reversal must pass the low of the last three lines.
    const lines = types.lineBreak(series([10, 11, 12, 13, 12.5, 9.5]), 3);
    assert.deepEqual(plain(lines.map(line => [line.open, line.close])), [[10, 11], [11, 12], [12, 13], [12, 9.5]]);

    // Kagi with a reversal of 2: the line turns thin below the last waist and thick above the last shoulder.
    // Moves smaller than the reversal (13 → 11.5, 10.5 → 12) extend nothing and turn nothing.
    const kagi = types.kagi(series([10, 13, 11.5, 10.5, 12, 14, 11.8, 9]), 2);
    assert.deepEqual(plain(kagi.map(line => [line.open, line.close, line.thick, line.change])),
        [[10, 13, true, null], [13, 10.5, true, null], [10.5, 14, true, null], [14, 9, false, 10.5]]);
    const turns = types.kagi(series([10, 14, 11, 15, 12, 9]), 2);
    assert.deepEqual(plain(turns.map(line => [line.open, line.close, line.thick, line.change])),
        [[10, 14, true, null], [14, 11, true, null], [11, 15, true, null], [15, 9, false, 11]]);

    // Point & Figure, box 1, three-box reversal: after the O column ends at 10, 12.9 is short of 13 and 13.2 turns.
    const columns = types.pointFigure(series([10, 12.3, 13.1, 11.5, 9.7, 12.9, 13.2]), 1, 3);
    assert.deepEqual(plain(columns.map(column => [column.kind, column.low, column.high])), [['X', 11, 13], ['O', 10, 12], ['X', 11, 13]]);

    const view = types.priceOnly('renko', series([10, 11.2, 13.4]), {box: 1});
    assert.equal(view.series, 'candles');
    assert.equal(view.box, 1);
    assert.equal(types.elementIndex(view.data[2].time), 2);
    assert.equal(types.isPriceOnly('kagi'), true);
    assert.equal(types.isPriceOnly('candles'), false);
});

test('a band fill paints one polygon per colour run and breaks at gaps', async () => {
    const {window} = await setup();
    const view = new window.StockWatchBandFillView();
    const values = [[12, 10], [13, 11], [11, 12], [10, 13], [null, null], [14, 12], [15, 13]];
    const bars = values.map(([upper, lower], index) => ({x: index * 10, originalData: {upper, lower}}));
    view.update({bars, visibleRange: {from: 0, to: values.length - 1}}, {fillColor: 'blue', alternateColor: 'red'});
    const calls = [];
    let polygon = null;
    const context = {
        set fillStyle(value) { polygon = {colour: value, points: []}; },
        beginPath() {},
        moveTo(x, y) { polygon.points.push([x, y]); },
        lineTo(x, y) { polygon.points.push([x, y]); },
        closePath() {},
        fill() { calls.push(polygon); }
    };
    view.draw({useMediaCoordinateSpace: paint => paint({context})}, price => price * 2);
    assert.deepEqual(plain(calls), [
        // Bars 0 and 1 are blue, so the blue run reaches bar 2, where the second value moves on top.
        {colour: 'blue', points: [[0, 24], [10, 26], [20, 22], [20, 24], [10, 22], [0, 20]]},
        {colour: 'red', points: [[20, 22], [30, 20], [30, 26], [20, 24]]},
        // The missing bar ends the red run; the band starts again after it.
        {colour: 'blue', points: [[50, 28], [60, 30], [60, 26], [50, 24]]}
    ]);
});

test('a line on the price scale reports the candles\' closes for its base and joins sparse points', async () => {
    const {window} = await setup();
    const types = window.StockWatchChartTypes;
    const anchors = ['2026-01-05', '2026-01-06', '2026-01-07', '2026-01-08', '2026-01-09'].map((time, index) => ({time, close: 10 + index}));
    const items = types.anchoredLineData([{time: '2026-01-06', value: 20}, {time: '2026-01-08', value: 30}], anchors);
    // One item per candle up to the line's last value; each knows the values around it.
    assert.deepEqual(plain(items.map(item => [item.time, item.value, item.anchor, item.previous, item.following])), [
        ['2026-01-05', null, 10, -1, 1], ['2026-01-06', 20, 11, 1, 3], ['2026-01-07', null, 12, 1, 3], ['2026-01-08', 30, 13, 3, -1]]);
    const view = new types.AnchoredLineView();
    assert.deepEqual(plain(view.priceValueBuilder(items[0])), [10]);
    // The close comes last (the base of percentage scales), except on the final item, whose label shows the line.
    assert.deepEqual(plain(view.priceValueBuilder(items[1])), [20, 11]);
    assert.deepEqual(plain(view.priceValueBuilder(items[3])), [13, 30]);

    const draw = (data, visibleRange) => {
        const calls = [];
        const context = {setLineDash() {}, beginPath() {}, stroke() {}, fill() {}, arc() {},
            moveTo: (x, y) => calls.push(['move', x, y]), lineTo: (x, y) => calls.push(['line', x, y])};
        view.update({bars: data.map((item, index) => ({x: index * 10, originalData: item})), visibleRange}, {...view.defaultOptions(), color: 'blue'});
        view.draw({useMediaCoordinateSpace: paint => paint({context})}, price => price * 2);
        return calls;
    };
    // Only the candle between the two points is visible: the segment crossing it is still drawn.
    assert.deepEqual(draw(items, {from: 2, to: 3}), [['move', 10, 40], ['line', 30, 60]]);
    // A point without a value is a gap.
    const gapped = types.anchoredLineData([{time: '2026-01-06', value: 20}, {time: '2026-01-07'}, {time: '2026-01-08', value: 30}], anchors);
    assert.deepEqual(draw(gapped, {from: 0, to: 3}), []);
});
