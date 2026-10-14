const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const {StockWatchI18n} = require('./i18n-stub.cjs');

const SCRIPTS = ['chart-drawing-core.js', 'chart-drawing-kit.js', 'chart-drawing-tools-lines.js', 'chart-drawing-tools-fib.js',
    'chart-drawing-tools-patterns.js', 'chart-drawing-tools-measure.js', 'chart-drawing-tools-shapes.js', 'chart-drawing-icons.js',
    'chart-drawing-ui.js', 'chart-alerts.js', 'chart-page-tools.js'];
// The real dialog markup, so the script and the fragment cannot drift apart.
const DEMO_DIALOG = fs.readFileSync('src/main/resources/templates/fragments/chart-workbench.html', 'utf8')
    .match(/<dialog th:fragment="demoTradeDialog"[\s\S]*?<\/dialog>/)[0];

function weekdays(count, start = Date.UTC(2024, 0, 1)) {
    const times = [];
    for (let day = start; times.length < count; day += 86400000) {
        const weekday = new Date(day).getUTCDay();
        if (weekday !== 0 && weekday !== 6) times.push(day / 1000);
    }
    return times;
}

const tick = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * A detail page with two chart frames (a signal chart and its other-interval chart). The rail is
 * {@code railHeight} px tall; each visible rail child takes 32 px (the separator 7 px).
 */
function page({railHeight = 1000, demoDialog = false, storage = {}} = {}) {
    const dom = new JSDOM(`<!doctype html><html><body>
        <div class="chart-tools-frame"><div class="chart-tools-rail" data-drawing-rail-host></div><div id="first"></div></div>
        <div class="chart-tools-frame"><div class="chart-tools-rail" data-drawing-rail-host></div><div id="second"></div></div>
        ${demoDialog ? DEMO_DIALOG : ''}</body></html>`,
    {pretendToBeVisual: true, runScripts: 'outside-only', url: 'http://localhost/signals/1'});
    const window = dom.window;
    window.StockWatchI18n = StockWatchI18n;
    Object.entries(storage).forEach(([key, value]) => window.localStorage.setItem(key, JSON.stringify(value)));
    const rail = node => node.classList?.contains('draw-rail');
    Object.defineProperty(window.HTMLElement.prototype, 'clientHeight', {configurable: true, get() { return rail(this) ? railHeight : 0; }});
    Object.defineProperty(window.HTMLElement.prototype, 'scrollHeight', {configurable: true, get() {
        if (!rail(this)) return 0;
        return [...this.children].filter(child => !child.hidden)
            .reduce((sum, child) => sum + (child.classList.contains('draw-rail-separator') ? 7 : 32), 0);
    }});
    window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
    window.HTMLDialogElement.prototype.close = function () { this.open = false; };
    const requests = [];
    const times = weekdays(40);
    window.fetch = async (url, options = {}) => {
        requests.push({url, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null});
        const json = body => ({ok: true, status: 200, json: async () => body});
        if (url.startsWith('/api/chart-state/drawings?')) return json({drawings: []});
        if (url.startsWith('/api/chart-state/drawings/import')) return json({});
        if (url.startsWith('/api/chart-state/drawings/')) return json({version: 1});
        if (url.startsWith('/api/chart-alerts')) return json({alerts: []});
        if (url.startsWith('/api/stocks/AAPL/candles')) {
            return json({candles: times.map((timestamp, index) => ({timestamp, volume: 1000 + index}))});
        }
        if (url.startsWith('/api/virtual-trades/')) return json({detailUrl: '#trade'});
        return {ok: false, status: 404, json: async () => ({})};
    };
    SCRIPTS.forEach(name => window.eval(fs.readFileSync(`src/main/resources/static/js/${name}`, 'utf8')));
    const chart = id => fakeChart(window, window.document.getElementById(id), times);
    return {window, requests, times, chart};
}

function fakeChart(window, container, times) {
    container.getClientRects = () => [{}];
    const pane = window.document.createElement('div');
    container.append(pane);
    pane.getBoundingClientRect = () => ({left: 0, top: 0, right: 800, bottom: 500, width: 800, height: 500});
    const bars = times.map((time, index) => ({time, open: 100 + index, high: 104 + index, low: 98 + index, close: 102 + index}));
    const series = {
        data: () => bars,
        priceToCoordinate: price => 500 - price,
        coordinateToPrice: y => 500 - y,
        attachPrimitive: primitive => primitive.attached?.({requestUpdate() {}}),
        detachPrimitive() {},
        getPane: () => ({getHTMLElement: () => pane}),
        priceScale: () => ({options: () => ({mode: 0}), setAutoScale() {}, setVisibleRange() {}}),
        priceFormatter: () => ({format: price => Number(price).toFixed(2)}),
        subscribeDataChanged() {},
        unsubscribeDataChanged() {}
    };
    const chart = {
        chartElement: () => container,
        timeScale: () => ({
            logicalToCoordinate: logical => logical * 10,
            coordinateToLogical: x => x / 10,
            options: () => ({barSpacing: 10}),
            subscribeVisibleLogicalRangeChange() {},
            unsubscribeVisibleLogicalRangeChange() {},
            setVisibleLogicalRange() {},
            fitContent() {}
        })
    };
    return {chart, series, container};
}

test('the rail goes into the column beside its chart and the tools match the stock page', async () => {
    const {window, chart} = page({demoDialog: true});
    const manager = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: 'DAILY'});
    await manager.ready;
    const host = window.document.querySelector('[data-drawing-rail-host]');
    assert.ok(host.querySelector('.draw-rail'));
    assert.equal(host.querySelector('.draw-rail').classList.contains('draw-rail-overlay'), false);
    assert.equal(window.document.getElementById('first').querySelector('.draw-rail'), null, 'not over the candles');
    assert.equal(manager.interval, '1d');
    assert.ok(manager.store instanceof window.StockWatchDrawing.ServerDrawingStore);
    assert.deepEqual({...manager.features}, {alerts: true, demoTrade: true});
});

test('two charts of a symbol share one request and each other\'s drawings', async () => {
    const {window, requests, chart, times} = page();
    const first = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: '1d'});
    const second = window.StockWatchChartTools.attach({...chart('second'), symbol: 'AAPL', interval: '1wk'});
    await Promise.all([first.ready, second.ready]);
    assert.equal(requests.filter(request => request.url.startsWith('/api/chart-state/drawings?')).length, 1);
    assert.equal(first.store, second.store);
    first.change(() => first.drawings.push({id: 'line', type: 'horizontalLine', points: [{time: times[5], price: 120}], style: {}}));
    await first.persist();
    assert.deepEqual([...second.drawings.map(drawing => drawing.id)], ['line']);
    assert.notEqual(second.drawings[0], first.drawings[0], 'each chart edits its own copy');
    assert.equal(second.undoStack.length, 0);
    assert.ok(requests.some(request => request.method === 'PUT' && request.url.startsWith('/api/chart-state/drawings/line?symbol=AAPL')));
});

test('drawings a page kept in this browser move into the account once, on that interval', async () => {
    const key = '/signals/1:native:DAILY';
    const drawing = {id: 'kept', type: 'trendLine', version: 2, points: [{time: 1704153600, price: 100}, {time: 1704758400, price: 110}], style: {}};
    const {window, requests, chart} = page({storage: {[`stockwatch:drawings:v2:${key}`]: [drawing]}});
    const manager = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: 'DAILY', legacyKey: key});
    await manager.ready;
    const imported = requests.find(request => request.url.startsWith('/api/chart-state/drawings/import?symbol=AAPL'));
    assert.deepEqual(imported.body.drawings.map(item => [item.id, item.intervals]), [['kept', ['1d']]]);
    assert.deepEqual([...manager.drawings.map(item => item.id)], ['kept']);
    assert.equal(window.localStorage.getItem(`stockwatch:drawings:v2:${key}`), null);
});

test('a chart shorter than the rail folds the least-used buttons into More tools', async () => {
    const {window, chart} = page({railHeight: 430});
    const manager = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: '1d'});
    await manager.ready;
    const ui = manager.ui;
    // 7 sections and the separator (231 px) and More tools (32 px) leave room for five of the eleven buttons.
    assert.deepEqual([...ui.folded], ['favorites', 'stay', 'lock', 'hide', 'tree', 'remove']);
    assert.equal(ui.overflowButton.hidden, false);
    assert.equal(ui.utilityButtons.undo.hidden, false);
    ui.overflowButton.click();
    const items = [...window.document.querySelectorAll('.draw-flyout .draw-flyout-item')];
    assert.equal(items.length, 6);
    assert.equal(ui.overflowButton.getAttribute('aria-expanded'), 'true');
    items.find(item => item.textContent === StockWatchI18n.t('draw.rail.lockAll')).click();
    assert.equal(manager.lockedAll, true);
    assert.equal(window.document.querySelector('.draw-flyout'), null);
    assert.equal(ui.overflowButton.getAttribute('aria-expanded'), 'false');
});

test('a tall chart shows every button and no More tools', async () => {
    const {window, chart} = page({railHeight: 700});
    const manager = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: '1d'});
    await manager.ready;
    assert.deepEqual([...manager.ui.folded], []);
    assert.equal(manager.ui.overflowButton.hidden, true);
});

test('volume tools load the volume of the chart\'s candles', async () => {
    const {window, requests, chart, times} = page();
    const manager = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: '1d'});
    await manager.ready;
    assert.equal(requests.some(request => request.url.startsWith('/api/stocks/AAPL/candles')), false, 'not before a volume tool is used');
    manager.setMode('fixedRangeVolumeProfile');
    await tick();
    await tick();
    const request = requests.find(item => item.url.startsWith('/api/stocks/AAPL/candles'));
    assert.ok(request.url.includes(`before=${times[times.length - 1] + 1}`));
    assert.equal(manager.bars[0].volume, 1000);
    assert.equal(manager.bars[39].volume, 1039);
});

test('a drawn position opens the demo trade dialog, which records a trade at the current quote', async () => {
    const {window, requests, chart} = page({demoDialog: true});
    const manager = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: 'WEEKLY'});
    await manager.ready;
    manager.emit('demo-trade', {side: 'SELL'});
    const dialog = window.document.getElementById('chartDemoTradeDialog');
    assert.equal(dialog.open, true);
    assert.equal(window.document.getElementById('chartDemoTradeTitle').textContent, StockWatchI18n.t('outlook.demoSellTitle', 'AAPL'));
    assert.ok(window.document.getElementById('chartDemoTradeStatus').textContent.includes(StockWatchI18n.t('sw.demo.fromPosition')));
    window.document.getElementById('confirmChartDemoTrade').click();
    await tick();
    const trade = requests.find(request => request.url === '/api/virtual-trades/AAPL');
    assert.equal(trade.method, 'POST');
    assert.equal(trade.body.side, 'SELL');
    assert.equal(trade.body.interval, '1wk');
    assert.equal(trade.body.quantity, null);
});

test('a page with its own demo trade dialog opens that one instead', async () => {
    const {window, chart} = page();
    const sides = [];
    const manager = window.StockWatchChartTools.attach({...chart('first'), symbol: 'AAPL', interval: '1d', onDemoTrade: side => sides.push(side)});
    await manager.ready;
    assert.equal(manager.features.demoTrade, true);
    manager.emit('demo-trade', {side: 'BUY'});
    assert.deepEqual(sides, ['BUY']);
});
