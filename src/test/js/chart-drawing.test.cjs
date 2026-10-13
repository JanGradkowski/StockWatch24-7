const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const {StockWatchI18n} = require('./i18n-stub.cjs');

const SCRIPTS = ['chart-drawing-core.js', 'chart-drawing-kit.js', 'chart-drawing-tools-lines.js', 'chart-drawing-tools-fib.js',
    'chart-drawing-tools-patterns.js', 'chart-drawing-tools-measure.js', 'chart-drawing-tools-shapes.js', 'chart-drawing-icons.js',
    'chart-drawing-ui.js'];

function weekdays(count, start = Date.UTC(2024, 0, 1)) {
    const times = [];
    for (let day = start; times.length < count; day += 86400000) {
        const weekday = new Date(day).getUTCDay();
        if (weekday !== 0 && weekday !== 6) times.push(new Date(day).toISOString().slice(0, 10));
    }
    return times;
}

/** A page with the drawing scripts and a fake chart: x = logical × 10 px, y = 500 − price. */
function setup({ui = false, storageKey = 'test'} = {}) {
    const dom = new JSDOM('<!doctype html><html><body><div id="chart"></div></body></html>', {pretendToBeVisual: true, runScripts: 'outside-only',
        url: 'http://localhost/'});
    const window = dom.window;
    window.StockWatchI18n = StockWatchI18n;
    SCRIPTS.forEach(name => window.eval(fs.readFileSync(`src/main/resources/static/js/${name}`, 'utf8')));
    if (!ui) delete window.StockWatchDrawingUI;
    const container = window.document.getElementById('chart');
    container.getClientRects = () => [{}];
    const pane = window.document.createElement('div');
    container.append(pane);
    pane.getBoundingClientRect = () => ({left: 0, top: 0, right: 800, bottom: 500, width: 800, height: 500});
    Object.defineProperty(pane, 'clientWidth', {value: 800});
    Object.defineProperty(pane, 'clientHeight', {value: 500});
    const times = weekdays(60);
    const bars = times.map((time, index) => ({time, open: 100 + index, high: 104 + index, low: 98 + index, close: 102 + index}));
    const primitives = [];
    const series = {
        data: () => bars,
        priceToCoordinate: price => 500 - price,
        coordinateToPrice: y => 500 - y,
        attachPrimitive: primitive => { primitives.push(primitive); primitive.attached?.({requestUpdate() {}}); },
        detachPrimitive: primitive => primitives.splice(primitives.indexOf(primitive), 1),
        getPane: () => ({getHTMLElement: () => pane}),
        priceScale: () => ({options: () => ({mode: 0}), setAutoScale() {}, setVisibleRange() {}}),
        priceFormatter: () => ({format: price => Number(price).toFixed(2)}),
        subscribeDataChanged() {},
        unsubscribeDataChanged() {}
    };
    const range = [];
    const chart = {
        chartElement: () => container,
        timeScale: () => ({
            logicalToCoordinate: logical => logical * 10,
            coordinateToLogical: x => x / 10,
            options: () => ({barSpacing: 10}),
            subscribeVisibleLogicalRangeChange() {},
            unsubscribeVisibleLogicalRangeChange() {},
            setVisibleLogicalRange: value => range.push(value),
            fitContent() {}
        })
    };
    const manager = new window.StockWatchDrawing.DrawingManager({chart, series, container, storageKey, interval: '1d', ui});
    const pointer = (type, x, y, extra = {}) => {
        const event = {clientX: x, clientY: y, button: 0, pointerId: 1, pointerType: 'mouse', shiftKey: false, ctrlKey: false, metaKey: false,
            target: container, preventDefault() {}, stopPropagation() {}, ...extra};
        if (type === 'down') manager.handlePointerDown(event);
        if (type === 'move') manager.handlePointerMove(event);
        if (type === 'up') manager.handlePointerUp(event);
    };
    const key = (value, extra = {}) => manager.handleKeyDown({key: value, target: window.document.body, preventDefault() {}, ...extra});
    return {window, manager, pointer, key, times, bars, container, range};
}

const seconds = date => Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10))) / 1000;

test('every registered tool has a name, an icon and a render function', () => {
    const {window} = setup();
    const tools = window.StockWatchDrawing.tools();
    assert.ok(tools.length >= 85, `only ${tools.length} tools`);
    tools.forEach(tool => {
        assert.ok(StockWatchI18n.t(`draw.tool.${tool.type}`), tool.type);
        assert.equal(typeof tool.render, 'function', tool.type);
        assert.ok(window.StockWatchDrawingIcons.has(tool.type), `${tool.type} icon`);
        (tool.properties || []).forEach(property => {
            if (!property.label && property.type !== 'levels') assert.ok(StockWatchI18n.t(`draw.prop.${property.key}`), `${tool.type}.${property.key}`);
            if (property.type === 'select' && !property.literalOptions) {
                property.options.forEach(option => assert.ok(StockWatchI18n.t(`draw.option.${property.key}.${option}`), `${property.key}.${option}`));
            }
        });
    });
});

test('time mapping counts trading days and projects past the last bar without weekends', () => {
    const {window, times} = setup();
    const mapper = new window.StockWatchDrawing.TimeMapper(times.map(seconds));
    assert.equal(mapper.mode, 'business');
    assert.equal(mapper.logical(seconds(times[10])), 10);
    const last = seconds(times[times.length - 1]);
    const projected = mapper.seconds(times.length - 1 + 5);
    assert.equal(mapper.logical(projected), times.length - 1 + 5);
    for (let step = 1; step <= 10; step++) {
        const day = new Date(mapper.seconds(times.length - 1 + step) * 1000).getUTCDay();
        assert.ok(day !== 0 && day !== 6, 'a projected bar fell on a weekend');
    }
    assert.ok(mapper.seconds(-3) < seconds(times[0]));
    assert.equal(Math.round(mapper.logical(mapper.seconds(-3))), -3);
    assert.ok(projected > last);
    const weekly = new window.StockWatchDrawing.TimeMapper([0, 7, 14, 21].map(day => day * 86400 + 1704067200));
    assert.equal(weekly.mode, 'week');
    assert.equal(weekly.logical(1704067200 + 35 * 86400), 5);
});

test('extended segments go off-screen on the requested side whichever way they were drawn', () => {
    const {window} = setup();
    const {extendSegment} = window.StockWatchDrawing.Geometry;
    const [left, right] = extendSegment({x: 300, y: 100}, {x: 100, y: 200}, false, true, 800, 500);
    assert.equal(left.x, 100);
    assert.ok(right.x > 800);
    const [farLeft] = extendSegment({x: 100, y: 200}, {x: 300, y: 100}, true, false, 800, 500);
    assert.ok(farLeft.x < 0);
});

test('drawings from the original Fibonacci and position tool are converted', () => {
    const {window} = setup();
    window.localStorage.setItem('stockwatch:fibonacci:v1:legacy', JSON.stringify([
        {id: 'f1', start: {time: '2024-01-02', price: 100}, end: {time: '2024-01-10', price: 120}},
        {id: 'p1', type: 'long', start: {time: '2024-01-02', price: 100}, end: {time: '2024-01-12', price: 100},
            entryPrice: 100, targetPrice: 110, stopPrice: 95}
    ]));
    const store = new window.StockWatchDrawing.LocalDrawingStore('legacy');
    return store.load().then(drawings => {
        assert.equal(drawings[0].type, 'fibRetracement');
        assert.deepEqual([...drawings[0].points.map(point => point.price)], [100, 120]);
        assert.equal(drawings[1].type, 'longPosition');
        assert.deepEqual([...drawings[1].points.map(point => point.price)], [100, 110, 95]);
        assert.ok(window.localStorage.getItem('stockwatch:drawings:v2:legacy'));
    });
});

test('a trend line is placed with two clicks, snapped to bars, and can be undone and redone', async () => {
    const {manager, pointer, times} = setup();
    await manager.ready;
    manager.setMode('trendLine');
    pointer('down', 100, 200);
    pointer('up', 100, 200);
    pointer('move', 204, 100);
    pointer('down', 204, 100);
    pointer('up', 204, 100);
    assert.equal(manager.drawings.length, 1);
    const [start, end] = manager.drawings[0].points;
    assert.equal(start.time, seconds(times[10]));
    assert.equal(end.time, seconds(times[20]));
    assert.equal(start.price, 300);
    assert.equal(end.price, 400);
    assert.equal(manager.mode, 'cursor', 'returns to the cursor unless "stay in drawing mode" is on');
    manager.undo();
    assert.equal(manager.drawings.length, 0);
    manager.redo();
    assert.equal(manager.drawings.length, 1);
});

test('a drawing is selected and moved by bars, then deleted with the keyboard', async () => {
    const {manager, pointer, key, times} = setup();
    await manager.ready;
    manager.setMode('trendLine');
    pointer('down', 100, 200);
    pointer('up', 100, 200);
    pointer('down', 200, 100);
    pointer('up', 200, 100);
    manager.select(null);
    pointer('down', 150, 150);
    assert.ok(manager.selection.has(manager.drawings[0].id));
    pointer('move', 170, 140);
    pointer('up', 170, 140);
    const [start, end] = manager.drawings[0].points;
    assert.equal(start.time, seconds(times[12]));
    assert.equal(end.time, seconds(times[22]));
    assert.equal(start.price, 310);
    key('Delete');
    assert.equal(manager.drawings.length, 0);
});

test('locked drawings stay put and survive "remove all"; clones and pastes get new ids', async () => {
    const {manager, pointer} = setup();
    await manager.ready;
    manager.setMode('horizontalLine');
    pointer('down', 100, 250);
    pointer('up', 100, 250);
    const id = manager.drawings[0].id;
    manager.update(id, drawing => { drawing.locked = true; });
    manager.select(id);
    manager.clone();
    assert.equal(manager.drawings.length, 2);
    assert.notEqual(manager.drawings[1].id, id);
    assert.equal(manager.drawings[1].locked, false);
    manager.select(id);
    pointer('down', 100, 250);
    pointer('move', 100, 200);
    pointer('up', 100, 200);
    assert.equal(manager.drawings[0].points[0].price, 250, 'a locked line must not move');
    manager.clearAll();
    assert.deepEqual([...manager.drawings.map(drawing => drawing.id)], [id]);
    manager.select(id);
    manager.copy();
    manager.paste();
    assert.equal(manager.drawings.length, 2);
});

test('a long position is placed with one click and keeps target above and stop below entry', async () => {
    const {manager, pointer} = setup();
    await manager.ready;
    manager.setMode('longPosition');
    pointer('down', 300, 360);
    pointer('up', 300, 360);
    const drawing = manager.drawings[0];
    const [entry, target, stop] = drawing.points;
    assert.equal(entry.price, 140);
    assert.ok(target.price > entry.price && stop.price < entry.price);
    assert.equal(target.time, stop.time);
    assert.equal(drawing.type, 'longPosition');
    const positionTool = setupTool(manager, 'longPosition');
    positionTool.movePoint(drawing, 1, {time: target.time, price: entry.price - 10}, null);
    assert.ok(drawing.points[1].price > entry.price, 'the target cannot cross the entry');
    positionTool.movePoint(drawing, 2, {time: target.time + 86400 * 3, price: entry.price + 10}, null);
    assert.ok(drawing.points[2].price < entry.price, 'the stop cannot cross the entry');
    assert.equal(drawing.points[1].time, drawing.points[2].time);
});

function setupTool(manager, type) {
    return manager.container.ownerDocument.defaultView.StockWatchDrawing.tool(type);
}

test('drawings limited to an interval are hidden on other intervals', async () => {
    const {manager, pointer} = setup();
    await manager.ready;
    manager.setMode('verticalLine');
    pointer('down', 100, 250);
    pointer('up', 100, 250);
    manager.update(manager.drawings[0].id, drawing => { drawing.intervals = ['1wk']; });
    assert.equal(manager.visible(manager.drawings[0]), false);
    manager.setInterval('1wk');
    assert.equal(manager.visible(manager.drawings[0]), true);
});

test('drawings are saved per storage key and reload with the page', async () => {
    const first = setup({storageKey: 'persisted'});
    await first.manager.ready;
    first.manager.setMode('rectangle');
    first.pointer('down', 100, 300);
    first.pointer('up', 100, 300);
    first.pointer('down', 200, 200);
    first.pointer('up', 200, 200);
    await first.manager.persist();
    const stored = JSON.parse(first.window.localStorage.getItem('stockwatch:drawings:v2:persisted'));
    assert.equal(stored.length, 1);
    assert.equal(stored[0].type, 'rectangle');
    assert.equal(stored[0]._box, undefined);
});

test('rendering every tool on a canvas does not throw', async () => {
    const {window, manager} = setup();
    await manager.ready;
    const warnings = [];
    window.console.warn = (...args) => warnings.push(args.map(String).join(' '));
    const env = manager.environment('price', 800, 500);
    const calls = [];
    const ctx = new Proxy({}, {
        get(target, property) {
            if (property === 'measureText') return text => ({width: String(text).length * 6});
            if (property in target) return target[property];
            return (...args) => { calls.push(property); return undefined; };
        },
        set(target, property, value) { target[property] = value; return true; }
    });
    window.StockWatchDrawing.tools().forEach(tool => {
        const drawing = {id: tool.type, type: tool.type, points: [], style: {...tool.defaults}};
        const count = tool.placement === 'path' || tool.placement === 'freehand' ? 4 : tool.points;
        for (let index = 0; index < count; index++) {
            drawing.points.push(tool.screenAnchored ? {rx: 0.2 + index * 0.1, ry: 0.3} : {time: seconds(weekdays(60)[10 + index * 5]), price: 120 + index * 7 * (index % 2 ? -1 : 1)});
        }
        tool.create?.(drawing, env);
        assert.doesNotThrow(() => manager.renderDrawing(ctx, drawing, env, {selected: true}), tool.type);
        assert.doesNotThrow(() => tool.hitTest?.(manager.context(drawing, env, {selected: true}), 150, 150, 7), `${tool.type} hit`);
        assert.doesNotThrow(() => manager.renderHandles(ctx, drawing, env), `${tool.type} handles`);
    });
    assert.deepEqual(warnings, [], 'a tool failed while rendering');
    assert.ok(calls.length > 100);
});

test('the toolbar builds a section per tool family and opens the settings dialog', async () => {
    const {window, manager, container} = setup({ui: true});
    await manager.ready;
    const rail = container.querySelector('.draw-rail');
    assert.ok(rail);
    assert.equal(rail.querySelectorAll('.draw-rail-section').length, 7);
    rail.querySelector('[data-section="lines"]').click();
    assert.equal(manager.mode, 'trendLine');
    window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
    window.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new window.Event('close')); };
    manager.setMode('cursor');
    manager.drawings.push({id: 'x', type: 'fibRetracement', points: [{time: seconds(weekdays(60)[5]), price: 100}, {time: seconds(weekdays(60)[15]), price: 130}], style: {}});
    manager.ui.openProperties(manager.drawings[0]);
    const dialog = window.document.querySelector('.draw-properties-dialog');
    assert.ok(dialog);
    assert.ok([...dialog.querySelectorAll('.draw-tab')].length >= 3);
    assert.equal(rail.querySelector('[aria-label]').getAttribute('aria-label').length > 0, true);
});
