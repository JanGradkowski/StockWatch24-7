(() => {
    'use strict';

    // Indicators on the stock chart, built from the server's indicator catalog: the browser
    // (search, categories, favourites), the settings dialog, one Lightweight Charts pane per panel
    // indicator with a shared crosshair, legends with live values, fills, markers and shapes, the
    // data window, and the saved layout and templates.

    const i18n = window.StockWatchI18n;
    const LAYOUT_SCOPE = 'stock';
    const BROWSER_KEY = 'stockwatch:indicator-browser:v1';
    // Pane weights: a pane's share of the chart height, in pixels at the chart's usual size.
    const PRICE_PANE_WEIGHT = 480;
    const DEFAULT_PANE_WEIGHT = 150;
    const VOLUME_PANE_WEIGHT = 110;
    const MIN_PANE_WEIGHT = 24;
    // However many panes are open, the price pane keeps this share of the chart.
    const PRICE_MIN_SHARE = 0.4;
    const UP = '#26a69a';
    const DOWN = '#ef5350';
    const SIGN_MOMENTUM = ['#26a69a', '#b2dfdb', '#ffcdd2', '#ff5252'];
    const finite = value => typeof value === 'number' && Number.isFinite(value);
    // Indicators with a learning-mode explanation (js.glossary.<term>); the list marks their names.
    const GLOSSARY_TERMS = {
        SMA: 'sma', EMA: 'ema', BOLLINGER: 'bollinger', VWAP: 'vwap', SUPPORT_RESISTANCE: 'supportResistance', DONCHIAN: 'donchian',
        KELTNER: 'keltner', VOLUME_PROFILE: 'volumeProfile', KDE_VOLUME_PROFILE: 'kde', RSI: 'rsi', MACD: 'macd', CCI: 'cci', ATR: 'atr',
        RELATIVE_VOLUME: 'relativeVolume', ADX_DMI: 'adx', STOCHASTIC: 'stochastic', STOCHASTIC_RSI: 'stochasticRsi', OBV: 'obv',
        MFI: 'mfi', TREND: 'trendState'
    };

    function element(tag, options = {}) {
        const node = document.createElement(tag);
        if (options.className) node.className = options.className;
        if (options.text !== undefined) node.textContent = options.text;
        Object.entries(options.attrs || {}).forEach(([name, value]) => {
            if (value !== undefined && value !== null && value !== false) node.setAttribute(name, value === true ? '' : String(value));
        });
        Object.entries(options.on || {}).forEach(([name, handler]) => node.addEventListener(name, handler));
        (options.children || []).forEach(child => child && node.append(child));
        return node;
    }

    function iconButton(label, icon, onClick, className = 'ind-icon-button') {
        const node = element('button', {className, attrs: {type: 'button', title: label, 'aria-label': label}, on: {click: onClick}});
        if (window.StockWatchDrawingIcons) node.append(window.StockWatchDrawingIcons.icon(icon, 15));
        else node.textContent = label.slice(0, 1);
        return node;
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return token && header ? {[header]: token} : {};
    }

    function readLocal(key, fallback) {
        try { const raw = window.localStorage.getItem(key); return raw ? JSON.parse(raw) : fallback; } catch (error) { return fallback; }
    }

    function writeLocal(key, value) {
        try { window.localStorage.setItem(key, JSON.stringify(value)); } catch (error) { /* storage unavailable */ }
    }

    function chartDate(seconds) {
        return new Date(seconds * 1000).toISOString().slice(0, 10);
    }

    function withAlpha(color, alpha) {
        return window.StockWatchDrawing ? window.StockWatchDrawing.withAlpha(color, alpha) : color;
    }

    /**
     * The colour for the current theme. The chart adapter makes translated colours nearly opaque so thin
     * lines stay visible on white; a translucent fill must keep its transparency, so its base is translated here.
     */
    function themed(color) {
        const charts = window.StockWatchCharts;
        if (!charts?.color || typeof color !== 'string') return color;
        const rgba = color.replace(/\s/g, '').match(/^rgba?\((\d+),(\d+),(\d+)(?:,([\d.]+))?\)$/i);
        if (!rgba) return charts.color(color);
        const hex = `#${rgba.slice(1, 4).map(value => Number(value).toString(16).padStart(2, '0')).join('')}`;
        const translated = charts.color(hex);
        return translated === hex ? color : withAlpha(translated, rgba[4] ?? 1);
    }

    /** "2", "0.5", "2.25": up to two decimals without trailing zeros. */
    function trimmedNumber(value) {
        const text = i18n.number(Number(value), 2);
        return /[.,]\d*$/.test(text) ? text.replace(/0+$/, '').replace(/[.,]$/, '') : text;
    }

    /** Values for legends: compact for large numbers, enough decimals for small ones. */
    function formatValue(value, priceFormatter) {
        if (!finite(value)) return '—';
        if (priceFormatter) return priceFormatter(value);
        const absolute = Math.abs(value);
        if (absolute >= 1e9) return `${i18n.number(value / 1e9, 2)}B`;
        if (absolute >= 1e6) return `${i18n.number(value / 1e6, 2)}M`;
        if (absolute >= 1e4) return `${i18n.number(value / 1e3, 1)}K`;
        if (absolute >= 100) return i18n.number(value, 2);
        if (absolute >= 1) return i18n.number(value, 3);
        return i18n.number(value, 4);
    }

    /** Band items merged with the main series' bars, each carrying that bar's close as `anchor`. */
    function anchoredBand(items, anchors) {
        const byTime = new Map();
        (anchors || []).forEach(anchor => { if (finite(anchor.close)) byTime.set(anchor.time, {time: anchor.time, anchor: anchor.close}); });
        items.forEach(item => byTime.set(item.time, {...(byTime.get(item.time) || {}), ...item}));
        return [...byTime.values()].sort((a, b) => (a.time < b.time ? -1 : (a.time > b.time ? 1 : 0)));
    }

    // ------------------------------------------------------------------ custom series: band fill

    /** Fills between two values per bar; a cloud swaps colour where the second value is on top. */
    class BandFillView {
        constructor() {
            this.data = null;
            this.options = null;
        }

        renderer() {
            return {draw: (target, priceToCoordinate) => this.draw(target, priceToCoordinate)};
        }

        update(data, options) {
            this.data = data;
            this.options = options;
        }

        /** On the main price scale items also carry the main series' close as their last value; see AnchoredLineView. */
        priceValueBuilder(item) {
            const values = finite(item.upper) && finite(item.lower) ? [item.upper, item.lower] : [];
            if (finite(item.anchor)) values.push(item.anchor);
            return values;
        }

        isWhitespace(item) {
            return !(finite(item.upper) && finite(item.lower)) && !finite(item.anchor);
        }

        defaultOptions() {
            return {...window.LightweightCharts.customSeriesDefaultOptions, color: 'rgba(0,0,0,0)', fillColor: 'rgba(96,165,250,0.1)',
                alternateColor: '', priceLineVisible: false, lastValueVisible: false};
        }

        draw(target, toY) {
            const data = this.data;
            if (!data || !data.visibleRange) return;
            const bars = data.bars;
            const options = this.options;
            target.useMediaCoordinateSpace(({context}) => {
                const from = Math.max(0, data.visibleRange.from - 1);
                const to = Math.min(bars.length - 1, data.visibleRange.to + 1);
                // Each run of neighbouring bars with one colour is a single polygon, out along the first values and back
                // along the second: one fill per run and no seams. The area between two bars takes the first bar's colour.
                const xs = [];
                const uppers = [];
                const lowers = [];
                let colour = null;
                const add = (x, upper, lower) => { xs.push(x); uppers.push(upper); lowers.push(lower); };
                const flush = () => {
                    if (xs.length > 1) {
                        context.fillStyle = colour;
                        context.beginPath();
                        context.moveTo(xs[0], uppers[0]);
                        for (let index = 1; index < xs.length; index++) context.lineTo(xs[index], uppers[index]);
                        for (let index = xs.length - 1; index >= 0; index--) context.lineTo(xs[index], lowers[index]);
                        context.closePath();
                        context.fill();
                    }
                    xs.length = 0;
                    uppers.length = 0;
                    lowers.length = 0;
                };
                for (let index = from; index <= to; index++) {
                    const bar = bars[index];
                    const item = bar?.originalData;
                    const upper = item && finite(item.upper) ? toY(item.upper) : null;
                    const lower = item && finite(item.lower) ? toY(item.lower) : null;
                    if (!finite(upper) || !finite(lower)) {
                        flush();
                        colour = null;
                        continue;
                    }
                    add(bar.x, upper, lower);
                    const next = options.alternateColor && item.upper < item.lower ? options.alternateColor : options.fillColor;
                    if (next === colour) continue;
                    if (colour !== null) {
                        // This bar ends the previous run and starts the next one.
                        flush();
                        add(bar.x, upper, lower);
                    }
                    colour = next;
                }
                flush();
            });
        }
    }

    // ------------------------------------------------------------------ primitive: indicator shapes

    /** Draws the shapes an indicator returns (levels, segments, polylines, labels, boxes, profiles, tables). */
    class ShapesPrimitive {
        constructor(manager, indicator, shapes, color) {
            this.manager = manager;
            this.indicator = indicator;
            this.shapes = shapes;
            this.color = color;
            this.requestUpdate = null;
            const renderer = {draw: target => this.draw(target)};
            this.views = [{zOrder: () => 'normal', renderer: () => renderer}];
        }

        attached(parameters) { this.requestUpdate = parameters.requestUpdate; this.series = parameters.series; }

        detached() { this.requestUpdate = null; }

        paneViews() { return this.views; }

        x(seconds) {
            const mapper = this.manager.mapper;
            const logical = mapper ? mapper.logical(seconds) : null;
            return logical === null ? null : this.manager.chart.timeScale().logicalToCoordinate(logical);
        }

        draw(target) {
            if (!this.series) return;
            target.useMediaCoordinateSpace(({context: ctx, mediaSize}) => {
                const width = mediaSize.width;
                const roleColor = role => themed({
                    resistance: '#ff647c', support: '#64e8bd', pivot: '#ffbd59', fib: '#7ea6ff', fibTrend: '#94a3b8', zigzag: '#ffbd59',
                    median: this.color, upper: this.color, lower: this.color, base: this.color, handle: '#94a3b8', channel: this.color,
                    pivotHigh: '#ff647c', pivotLow: '#64e8bd'
                }[role] || this.color);
                this.shapes.forEach(shape => {
                    const points = shape.points.map(point => ({x: this.x(point.timestamp), y: this.series.priceToCoordinate(point.price)}));
                    const color = roleColor(shape.role);
                    ctx.save();
                    ctx.strokeStyle = color;
                    ctx.fillStyle = color;
                    ctx.lineWidth = 1;
                    ctx.font = '600 11px "Geist", sans-serif';
                    switch (shape.kind) {
                        case 'segment':
                        case 'level': this.line(ctx, points, shape, width, color); break;
                        case 'polyline': this.polyline(ctx, points, color); break;
                        case 'label': this.label(ctx, points[0], shape, color); break;
                        case 'fill': this.fill(ctx, points, color); break;
                        case 'box': this.box(ctx, points, color); break;
                        case 'profile': this.profile(ctx, shape, points, width); break;
                        case 'table': this.table(ctx, shape, width); break;
                        default: break;
                    }
                    ctx.restore();
                });
            });
        }

        line(ctx, points, shape, width, color) {
            let [a, b] = points;
            if (!a || !b || !finite(a.y) || !finite(b.y)) return;
            if (!finite(a.x)) a = {x: 0, y: a.y};
            if (!finite(b.x)) b = {x: width, y: b.y};
            const extend = shape.extend || 'none';
            let start = a;
            let end = b;
            const slope = b.x === a.x ? 0 : (b.y - a.y) / (b.x - a.x);
            if (extend === 'right' || extend === 'both') end = {x: width, y: a.y + slope * (width - a.x)};
            if (extend === 'left' || extend === 'both') start = {x: 0, y: a.y + slope * (0 - a.x)};
            ctx.setLineDash(shape.role === 'fibTrend' || shape.role === 'handle' ? [4, 4] : []);
            ctx.beginPath();
            ctx.moveTo(start.x, start.y);
            ctx.lineTo(end.x, end.y);
            ctx.stroke();
            ctx.setLineDash([]);
            if (shape.text) {
                const text = shape.role === 'fib' ? `${shape.text} (${this.manager.formatPrice(shape.points[0].price)})` : shape.text;
                ctx.textBaseline = 'bottom';
                ctx.textAlign = shape.kind === 'level' && shape.role !== 'fib' ? 'left' : 'right';
                const x = shape.kind === 'level' && shape.role !== 'fib' ? Math.max(2, start.x + 2) : Math.min(width - 4, end.x - 4);
                ctx.fillText(text, x, start.y - 2);
            }
        }

        polyline(ctx, points, color) {
            const valid = points.filter(point => finite(point.x) && finite(point.y));
            if (valid.length < 2) return;
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            ctx.moveTo(valid[0].x, valid[0].y);
            valid.slice(1).forEach(point => ctx.lineTo(point.x, point.y));
            ctx.stroke();
            void color;
        }

        label(ctx, point, shape, color) {
            if (!point || !finite(point.x) || !finite(point.y)) return;
            const data = shape.data || {};
            const above = shape.role !== 'pivotLow';
            const parts = [];
            if (finite(data.price)) parts.push(this.manager.formatPrice(data.price));
            if (finite(data.percent)) parts.push(`${data.percent > 0 ? '+' : ''}${i18n.number(data.percent, 2)}%`);
            const text = shape.text || parts.join(' ');
            if (!text) return;
            const width = ctx.measureText(text).width + 8;
            const y = above ? point.y - 22 : point.y + 6;
            ctx.fillStyle = withAlpha(color, 0.85);
            ctx.fillRect(point.x - width / 2, y, width, 16);
            ctx.fillStyle = '#101418';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(text, point.x, y + 8);
        }

        fill(ctx, points, color) {
            const valid = points.filter(point => finite(point.x) && finite(point.y));
            if (valid.length < 3) return;
            ctx.fillStyle = withAlpha(color, 0.08);
            ctx.beginPath();
            ctx.moveTo(valid[0].x, valid[0].y);
            valid.slice(1).forEach(point => ctx.lineTo(point.x, point.y));
            ctx.closePath();
            ctx.fill();
        }

        box(ctx, points, color) {
            const [a, b] = points;
            if (!a || !b || ![a.x, a.y, b.x, b.y].every(finite)) return;
            ctx.fillStyle = withAlpha(color, 0.1);
            ctx.fillRect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y));
        }

        profile(ctx, shape, points, width) {
            const data = shape.data || {};
            const rows = data.rows || [];
            if (!rows.length) return;
            const [from, to] = points;
            let left;
            let span;
            if (data.placement === 'right') {
                span = width * (data.width || 30) / 100;
                left = width - span;
            } else {
                if (!finite(from.x) || !finite(to.x)) return;
                left = Math.min(from.x, to.x);
                span = Math.max(4, Math.abs(to.x - from.x));
            }
            const largest = Math.max(...rows.map(row => row.up + row.down)) || 1;
            rows.forEach(row => {
                const top = this.series.priceToCoordinate(row.high);
                const bottom = this.series.priceToCoordinate(row.low);
                if (!finite(top) || !finite(bottom)) return;
                const height = Math.max(1, Math.abs(bottom - top) - 1);
                const y = Math.min(top, bottom);
                const upWidth = span * row.up / largest;
                const downWidth = span * row.down / largest;
                const alpha = row.valueArea ? 0.55 : 0.25;
                if (data.placement === 'right') {
                    ctx.fillStyle = withAlpha(UP, alpha);
                    ctx.fillRect(width - upWidth - downWidth, y, upWidth, height);
                    ctx.fillStyle = withAlpha(DOWN, alpha);
                    ctx.fillRect(width - downWidth, y, downWidth, height);
                } else {
                    ctx.fillStyle = withAlpha(UP, alpha);
                    ctx.fillRect(left, y, upWidth, height);
                    ctx.fillStyle = withAlpha(DOWN, alpha);
                    ctx.fillRect(left + upWidth, y, downWidth, height);
                }
            });
            const poc = this.series.priceToCoordinate(data.poc);
            if (finite(poc)) {
                ctx.strokeStyle = themed('#ffbd59');
                ctx.lineWidth = 1.5;
                ctx.beginPath();
                ctx.moveTo(left, poc);
                ctx.lineTo(left + span, poc);
                ctx.stroke();
            }
        }

        table(ctx, shape, width) {
            const data = shape.data || {};
            const rows = (data.rows || []).map(row => this.manager.tableRow(data.table, row));
            if (!rows.length) return;
            const header = this.manager.tableHeader(data.table);
            const all = [header, ...rows];
            const widths = header.map((unused, column) => Math.max(...all.map(row => ctx.measureText(String(row[column] ?? '')).width)) + 14);
            const total = widths.reduce((sum, value) => sum + value, 0);
            const rowHeight = 18;
            const left = width - total - 8;
            const top = 30;
            ctx.fillStyle = themed('rgba(21, 28, 38, 0.9)');
            ctx.fillRect(left, top, total, rowHeight * all.length);
            all.forEach((row, rowIndex) => {
                let x = left;
                row.forEach((cell, column) => {
                    const value = String(cell ?? '');
                    ctx.fillStyle = rowIndex === 0 ? themed('#94a3b8') : (/^[+]/.test(value) ? themed('#64e8bd') : (/^[-−]/.test(value) ? themed('#ff647c') : themed('#e8edf3')));
                    ctx.textAlign = column === 0 ? 'left' : 'right';
                    ctx.textBaseline = 'middle';
                    ctx.fillText(value, column === 0 ? x + 6 : x + widths[column] - 6, top + rowIndex * rowHeight + rowHeight / 2);
                    x += widths[column];
                });
            });
        }
    }

    // ------------------------------------------------------------------ the manager

    class IndicatorManager {
        /**
         * @param options.chart the price chart (panes are added to it)
         * @param options.priceSeries main price series (pane 0)
         * @param options.stage element containing the chart (legends are positioned inside it)
         * @param options.symbol, options.interval
         * @param options.candles function returning loaded candles [{time: 'YYYY-MM-DD', timestamp, open, high, low, close}]
         * @param options.volume function returning loaded volume [{time, value}]
         * @param options.priceFormatter function formatting prices like the chart axis
         * @param options.onPanesChanged called after panes are added, removed or resized
         */
        constructor(options) {
            this.chart = options.chart;
            this.priceSeries = options.priceSeries;
            this.stage = options.stage;
            this.symbol = options.symbol;
            this.interval = options.interval;
            this.candles = options.candles;
            // The main series' bars as {time, close}; lines and fills on its scale are measured from them.
            this.anchors = options.anchors || (() => (this.candles() || []).map(candle => ({time: candle.time, close: candle.close})));
            this.volumeData = options.volume || (() => []);
            this.priceFormatter = options.priceFormatter || null;
            this.onPanesChanged = options.onPanesChanged || (() => {});
            this.drawings = options.drawings || null;
            this.catalog = new Map();
            this.indicators = [];
            this.visuals = new Map();
            this.views = new Map();
            this.slots = ['price'];
            this.paneHeights = {};
            this.requestSequence = 0;
            this.visibleSequence = 0;
            this.refreshTimer = null;
            this.saveTimer = null;
            this.legendHost = element('div', {className: 'ind-legends', attrs: {'aria-live': 'off'}});
            this.stage.append(this.legendHost);
            this.legends = new Map();
            this.crosshair = null;
            this.legendCollapsed = false;
            this.dataWindow = null;
            this.volumeSeries = null;
            this.mapper = null;
            this.ready = this.init();
            this.onCrosshair = param => { this.crosshair = param?.time ? param : null; this.updateLegendValues(); };
            this.chart.subscribeCrosshairMove(this.onCrosshair);
            this.onVisibleRange = () => this.scheduleVisibleRefresh();
            this.chart.timeScale().subscribeVisibleLogicalRangeChange(this.onVisibleRange);
            this.resizeObserver = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => this.positionLegends());
            this.resizeObserver?.observe(this.stage);
            this.onPaneResizeEnd = () => window.setTimeout(() => this.capturePaneHeights(), 0);
            this.chart.chartElement().addEventListener('pointerup', this.onPaneResizeEnd);
            // Fills keep their transparency only when drawn again with this theme's colours.
            this.onTheme = () => { this.rerenderAll(); this.updateDataWindow(); };
            window.addEventListener('stockwatch:themechange', this.onTheme);
            if (this.drawings) this.drawings.objectTreeProvider = () => this.objectTreeItems();
        }

        async init() {
            try {
                const response = await fetch('/api/chart-indicators/catalog', {credentials: 'same-origin'});
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const body = await response.json();
                (body.indicators || []).forEach(definition => this.catalog.set(definition.type, definition));
                this.formulaFunctions = body.formulaFunctions || [];
                this.formulaSeries = body.formulaSeries || [];
            } catch (error) {
                console.warn('Indicator catalog unavailable', error);
            }
            await this.loadLayout();
        }

        definition(type) { return this.catalog.get(type) || null; }

        // ---- labels

        name(type) {
            return i18n.has(`ind.${type}.name`) ? i18n.t(`ind.${type}.name`) : (this.definition(type)?.shortName || type);
        }

        shortName(type) {
            return i18n.has(`ind.${type}.short`) ? i18n.t(`ind.${type}.short`) : (this.definition(type)?.shortName || type);
        }

        inputLabel(type, key) {
            if (i18n.has(`ind.${type}.input.${key}`)) return i18n.t(`ind.${type}.input.${key}`);
            return i18n.has(`ind.input.${key}`) ? i18n.t(`ind.input.${key}`) : key;
        }

        outputLabel(type, key) {
            if (i18n.has(`ind.${type}.output.${key}`)) return i18n.t(`ind.${type}.output.${key}`);
            // A single plotted line is best called by the indicator's own short name ("RSI", not "Value").
            if (key === 'value') return this.shortName(type);
            return i18n.has(`ind.output.${key}`) ? i18n.t(`ind.output.${key}`) : key;
        }

        choiceLabel(set, value) {
            return i18n.has(`ind.choice.${set}.${value}`) ? i18n.t(`ind.choice.${set}.${value}`) : value;
        }

        /** "EMA 20", "BB 20 2 · W" — the short name and the inputs a reader would recognise. */
        label(config) {
            const definition = this.definition(config.type);
            if (!definition) return config.type;
            const values = this.inputValues(config);
            const parts = [this.shortName(config.type)];
            // Levels are drawing settings, and a smoothing length means nothing while smoothing is off.
            const zoneKeys = definition.zones ? [definition.zones.upperInput, definition.zones.lowerInput] : [];
            const smoothingOff = values.maType === 'NONE';
            (definition.inputs || []).forEach(input => {
                const value = values[input.key];
                if (zoneKeys.includes(input.key) || (smoothingOff && input.key === 'maLength')) return;
                switch (input.kind) {
                    case 'INTEGER': if (!(input.key === 'offset' && value === 0)) parts.push(String(value)); break;
                    case 'DECIMAL': parts.push(trimmedNumber(value)); break;
                    case 'SYMBOL': parts.push(String(value)); break;
                    case 'TIMEFRAME': if (value !== 'chart') parts.push(this.choiceLabel('timeframe', value)); break;
                    case 'SOURCE':
                        if (String(value).startsWith('ind:')) parts.push(`(${this.sourceLabel(value)})`);
                        else if (value !== input.defaultValue) parts.push(this.choiceLabel('source', value));
                        break;
                    case 'TEXT': parts.push(String(value)); break;
                    default: break;
                }
            });
            return parts.join(' ');
        }

        sourceLabel(value) {
            if (!String(value).startsWith('ind:')) return this.choiceLabel('source', value);
            const [, id, key] = String(value).split(':');
            const source = this.indicators.find(item => item.id === id);
            return source ? this.outputName(source, key, this.shortName(source.type)) : value;
        }

        /** "EMA 20" for a single-line indicator, "MACD 12 26 9 › Signal" for one of several outputs. */
        outputName(config, key, name = this.label(config)) {
            return key === 'value' ? name : `${name} › ${this.outputLabel(config.type, key)}`;
        }

        inputValues(config) {
            const definition = this.definition(config.type);
            const values = {};
            (definition?.inputs || []).forEach(input => {
                values[input.key] = config.inputs && config.inputs[input.key] !== undefined ? config.inputs[input.key] : input.defaultValue;
            });
            return values;
        }

        formatPrice(value) {
            if (this.priceFormatter) return this.priceFormatter(value);
            try { return this.priceSeries.priceFormatter().format(value); } catch (error) { return i18n.number(value, 2); }
        }

        tableHeader(table) {
            return table === 'seasonality'
                ? [i18n.t('ind.table.month'), i18n.t('ind.table.average'), i18n.t('ind.table.positive'), i18n.t('ind.table.years')]
                : [i18n.t('ind.table.period'), i18n.t('ind.table.change')];
        }

        tableRow(table, row) {
            const percent = value => (finite(value) ? `${value > 0 ? '+' : ''}${i18n.number(value, 2)}%` : '—');
            if (table === 'seasonality') {
                const month = i18n.date(Date.UTC(2024, row[0] - 1, 1), {month: 'short', timeZone: 'UTC'});
                return [month, percent(row[1]), finite(row[2]) ? `${i18n.number(row[2], 0)}%` : '—', String(row[3] ?? 0)];
            }
            return [i18n.has(`ind.table.period.${row[0]}`) ? i18n.t(`ind.table.period.${row[0]}`) : row[0], percent(row[1])];
        }

        // ---- configuration

        add(type, overrides = {}) {
            const definition = this.definition(type);
            if (!definition) return null;
            const defaults = this.typeDefaults[type] || {};
            const config = {
                id: `${type.toLowerCase().replace(/_/g, '-')}-${Math.random().toString(36).slice(2, 9)}`,
                type,
                inputs: {...(defaults.inputs || {}), ...(overrides.inputs || {})},
                styles: {...(defaults.styles || {}), ...(overrides.styles || {})},
                fills: {...(defaults.fills || {}), ...(overrides.fills || {})},
                visible: true,
                intervals: null,
                pane: definition.placement === 'PANEL' ? 'own' : (definition.placement === 'VOLUME' ? 'volume' : 'price')
            };
            if (this.indicators.length >= 30) {
                this.announce(i18n.t('ind.status.limit'));
                return null;
            }
            this.indicators.push(config);
            this.rememberRecent(type);
            this.changed();
            this.refresh(0);
            return config;
        }

        remove(id) {
            this.indicators.filter(item => item.inputs && Object.values(item.inputs).some(value => String(value).startsWith(`ind:${id}:`)))
                .forEach(dependent => { Object.keys(dependent.inputs).forEach(key => { if (String(dependent.inputs[key]).startsWith(`ind:${id}:`)) dependent.inputs[key] = 'close'; }); });
            this.indicators = this.indicators.filter(item => item.id !== id);
            this.clearVisuals(id);
            this.views.delete(id);
            this.syncPanes();
            this.changed();
            this.refresh(0);
        }

        find(id) { return this.indicators.find(item => item.id === id) || null; }

        update(id, mutator, refetch = true) {
            const config = this.find(id);
            if (!config) return;
            mutator(config);
            this.changed();
            if (refetch) this.refresh(0); else this.renderIndicator(config);
        }

        toggle(id) {
            const config = this.find(id);
            if (!config) return;
            config.visible = !config.visible;
            this.changed();
            this.syncPanes();
            this.renderIndicator(config);
            // Hidden indicators are not recalculated, so a shown one fetches fresh values.
            if (config.visible) this.refresh(0);
        }

        visibleOnInterval(config) {
            return config.visible && (!Array.isArray(config.intervals) || !config.intervals.length || config.intervals.includes(this.interval));
        }

        changed() {
            this.renderLegends();
            this.scheduleSave();
            this.drawings?.emit?.('indicators');
        }

        /** Panes stay while the new interval's values load, so the chart does not jump; the next answer settles them. */
        setInterval(interval) {
            this.interval = interval;
            this.indicators.forEach(config => this.clearVisuals(config.id));
            this.views.clear();
            this.renderLegends();
        }

        /** Called after the page loads or prepends candles. */
        dataChanged() {
            const times = (this.candles() || []).map(candle => candle.timestamp ?? window.StockWatchDrawing?.toSeconds(candle.time)).filter(finite);
            this.mapper = window.StockWatchDrawing ? new window.StockWatchDrawing.TimeMapper(times) : null;
            this.refresh(80);
        }

        // ---- server requests

        refresh(delay = 150) {
            window.clearTimeout(this.refreshTimer);
            this.refreshTimer = window.setTimeout(() => this.fetchIndicators(false), delay);
        }

        /** Panning moves no pane, so legends stay where they are; only visible-range indicators recalculate. */
        scheduleVisibleRefresh() {
            if (!this.indicators.some(config => this.definition(config.type)?.visibleRangeDependent && this.visibleOnInterval(config))) return;
            window.clearTimeout(this.visibleTimer);
            this.visibleTimer = window.setTimeout(() => this.fetchIndicators(true), 400);
        }

        visibleTimes() {
            const range = this.chart.timeScale().getVisibleLogicalRange();
            const candles = this.candles() || [];
            if (!range || !candles.length) return {from: null, to: null};
            const first = candles[Math.max(0, Math.min(candles.length - 1, Math.ceil(range.from)))];
            const last = candles[Math.max(0, Math.min(candles.length - 1, Math.floor(range.to)))];
            return {from: first?.timestamp ?? null, to: last?.timestamp ?? null};
        }

        async fetchIndicators(visibleOnly) {
            await this.ready;
            const candles = this.candles() || [];
            if (!candles.length) return;
            const configs = this.indicators.filter(config => this.visibleOnInterval(config) && this.definition(config.type)
                && (!visibleOnly || this.definition(config.type).visibleRangeDependent));
            if (!configs.length) {
                this.syncPanes();
                this.renderLegends();
                return;
            }
            // Indicators that read another indicator need it in the same request.
            const needed = new Set(configs.map(config => config.id));
            let added = true;
            while (added) {
                added = false;
                this.indicators.forEach(config => {
                    if (!needed.has(config.id)) return;
                    Object.values(config.inputs || {}).forEach(value => {
                        const match = String(value).match(/^ind:([^:]+):/);
                        if (match && !needed.has(match[1]) && this.find(match[1])) { needed.add(match[1]); added = true; }
                    });
                });
            }
            const requested = this.indicators.filter(config => needed.has(config.id));
            const sequence = visibleOnly ? ++this.visibleSequence : ++this.requestSequence;
            const interval = this.interval;
            const visible = this.visibleTimes();
            this.loading = true;
            this.renderLegends();
            try {
                const response = await fetch(`/api/stocks/${encodeURIComponent(this.symbol)}/chart-indicators?interval=${encodeURIComponent(interval)}`, {
                    method: 'POST', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json', ...csrfHeaders()},
                    body: JSON.stringify({
                        from: candles[0].timestamp, to: candles[candles.length - 1].timestamp,
                        visibleFrom: visible.from, visibleTo: visible.to,
                        indicators: requested.map(config => ({id: config.id, type: config.type, parameters: this.inputValues(config)}))
                    })
                });
                const body = await response.json().catch(() => ({}));
                if (sequence !== (visibleOnly ? this.visibleSequence : this.requestSequence) || interval !== this.interval) return;
                if (!response.ok) {
                    const message = body.error || body.message || body.detail || i18n.t('ind.status.failed');
                    requested.forEach(config => { config.error = message; });
                    this.loading = false;
                    this.renderLegends();
                    return;
                }
                (body.indicators || []).forEach(view => {
                    const config = this.find(view.id);
                    if (!config) return;
                    config.error = view.error || null;
                    this.views.set(view.id, view);
                });
                this.loading = false;
                this.syncPanes();
                requested.forEach(config => this.renderIndicator(config));
            } catch (error) {
                this.loading = false;
                requested.forEach(config => { config.error = i18n.t('ind.status.failed'); });
            }
            this.renderLegends();
        }

        // ---- panes

        /** Pane slot an indicator draws in. */
        slotOf(config) {
            if (config.pane === 'own') return config.id;
            if (config.pane === 'volume') return this.slots.includes('volume') ? 'volume' : 'price';
            return 'price';
        }

        paneIndex(slot) {
            return this.slots.indexOf(slot);
        }

        intervalMatches(config) {
            return !Array.isArray(config.intervals) || !config.intervals.length || config.intervals.includes(this.interval);
        }

        /** Own-pane indicators keep their pane while hidden, so the legend can show them again. */
        wantsPane(config) {
            if (config.pane !== 'own' || !this.intervalMatches(config)) return false;
            if (!config.visible) return this.slots.includes(config.id);
            const view = this.views.get(config.id);
            return Boolean(view) && !view.error;
        }

        /** Adds or removes panes so there is one per visible own-pane indicator, plus the volume pane. */
        syncPanes() {
            const wanted = ['price'];
            if (this.volumeWanted) wanted.push('volume');
            this.indicators.forEach(config => { if (this.wantsPane(config)) wanted.push(config.id); });
            // Remove panes no longer wanted (from the bottom up so indexes stay valid).
            for (let index = this.slots.length - 1; index >= 1; index--) {
                const slot = this.slots[index];
                if (wanted.includes(slot)) continue;
                if (slot !== 'volume') this.clearVisuals(slot);
                else this.removeVolumeSeries();
                try { this.chart.removePane(index); } catch (error) { /* already gone */ }
                this.slots.splice(index, 1);
            }
            wanted.forEach(slot => {
                if (this.slots.includes(slot)) return;
                const pane = this.chart.addPane(true);
                pane.setPreserveEmptyPane(true);
                this.slots.push(slot);
                if (slot === 'volume' && this.slots.indexOf('volume') !== 1) {
                    pane.moveTo(1);
                    this.slots.splice(this.slots.indexOf('volume'), 1);
                    this.slots.splice(1, 0, 'volume');
                }
            });
            if (this.volumeWanted && !this.volumeSeries) this.createVolumeSeries();
            this.applyPaneHeights();
            this.onPanesChanged();
            this.positionLegends();
        }

        paneWeight(slot) {
            const saved = this.paneHeights[slot];
            if (finite(saved) && saved >= MIN_PANE_WEIGHT) return saved;
            if (slot === 'price') return PRICE_PANE_WEIGHT;
            return slot === 'volume' ? VOLUME_PANE_WEIGHT : DEFAULT_PANE_WEIGHT;
        }

        /**
         * The chart keeps its height, as on TradingView: panes share it by weight, so a new pane makes the others
         * smaller instead of making the chart taller, and the GPU never repaints more than the chart's area. The
         * price pane keeps at least PRICE_MIN_SHARE of it.
         */
        paneWeights() {
            const lower = this.slots.slice(1).map(slot => this.paneWeight(slot));
            const lowerSum = lower.reduce((sum, value) => sum + value, 0);
            return [Math.max(this.paneWeight('price'), lowerSum * PRICE_MIN_SHARE / (1 - PRICE_MIN_SHARE)), ...lower];
        }

        applyPaneHeights() {
            const panes = this.chart.panes();
            if (!panes.length || this.maximized || panes.length !== this.slots.length) return;
            this.appliedWeights = this.paneWeights();
            panes.forEach((pane, index) => pane.setStretchFactor(this.appliedWeights[index]));
        }

        /**
         * After the user drags a pane separator: the chart moved weight between the two panes beside it and kept
         * their sum, so the new weights are remembered as they are.
         */
        capturePaneHeights() {
            const panes = this.chart.panes();
            if (panes.length !== this.slots.length || panes.length < 2 || this.maximized || !this.appliedWeights) return;
            let changed = false;
            panes.forEach((pane, index) => {
                const weight = pane.getStretchFactor();
                const applied = this.appliedWeights[index];
                if (!finite(weight) || !finite(applied) || Math.abs(weight - applied) < 1) return;
                this.paneHeights[this.slots[index]] = Math.max(MIN_PANE_WEIGHT, Math.round(weight));
                changed = true;
            });
            if (changed) {
                this.applyPaneHeights();
                this.positionLegends();
                this.scheduleSave();
            }
        }

        movePane(id, direction) {
            const index = this.slots.indexOf(id);
            const target = index + direction;
            if (index < 1 || target < 1 || target >= this.slots.length) return;
            this.chart.swapPanes(index, target);
            [this.slots[index], this.slots[target]] = [this.slots[target], this.slots[index]];
            const order = this.slots.slice(1);
            this.indicators.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
            this.applyPaneHeights();
            this.positionLegends();
            this.changed();
        }

        maximizePane(id) {
            const slot = this.slotOf(this.find(id));
            this.maximized = this.maximized === slot ? null : slot;
            const panes = this.chart.panes();
            panes.forEach((pane, index) => {
                const current = this.slots[index];
                if (this.maximized) pane.setStretchFactor(current === this.maximized ? 1000 : 1);
            });
            if (!this.maximized) this.applyPaneHeights();
            this.positionLegends();
            this.renderLegends();
        }

        setPricePane(id, own) {
            this.update(id, config => { config.pane = own ? 'own' : 'price'; }, false);
            this.clearVisuals(id);
            this.syncPanes();
            this.renderIndicator(this.find(id));
            this.renderLegends();
        }

        // ---- volume

        setVolume(visible, options = {}) {
            this.volumeWanted = Boolean(visible);
            this.volumeOptions = options;
            this.syncPanes();
            this.renderLegends();
        }

        createVolumeSeries() {
            const index = this.paneIndex('volume');
            if (index < 0) return;
            this.volumeSeries = this.chart.addSeries(window.LightweightCharts.HistogramSeries, {
                priceFormat: {type: 'volume'}, priceLineVisible: false, lastValueVisible: true
            }, index);
            this.volumeSeries.setData(this.volumeData());
            this.volumeOptions?.onCreate?.(this.volumeSeries);
        }

        removeVolumeSeries() {
            if (!this.volumeSeries) return;
            try { this.chart.removeSeries(this.volumeSeries); } catch (error) { /* chart removed */ }
            this.volumeSeries = null;
            this.volumeOptions?.onRemove?.();
        }

        refreshVolume() {
            this.volumeSeries?.setData(this.volumeData());
        }

        // ---- rendering

        outputStyle(config, spec) {
            const saved = (config.styles || {})[spec.key] || {};
            return {
                visible: saved.visible ?? spec.visible !== false,
                color: saved.color || spec.color,
                width: saved.width || spec.width || 2,
                lineStyle: saved.lineStyle ?? spec.lineStyle ?? 0
            };
        }

        fillStyle(config, fill, index) {
            const saved = (config.fills || {})[index] || {};
            return {visible: saved.visible ?? true, color: saved.color || fill.color, alternateColor: saved.alternateColor || fill.alternateColor};
        }

        clearVisuals(id) {
            const visuals = this.visuals.get(id);
            if (!visuals) return;
            visuals.series.forEach(series => { try { this.chart.removeSeries(series); } catch (error) { /* removed with its pane */ } });
            visuals.primitives.forEach(({series, primitive}) => { try { series.detachPrimitive(primitive); } catch (error) { /* removed */ } });
            (visuals.markerPlugins || []).forEach(plugin => { try { plugin.detach?.(); } catch (error) { /* removed with its series */ } });
            if (visuals.paneKey && this.drawings) this.drawings.unregisterPane(visuals.paneKey);
            this.visuals.delete(id);
        }

        /** The page replaced its main series (another chart type); shapes drawn on it move along. */
        setPriceSeries(series) {
            this.priceSeries = series;
            this.rerenderAll();
        }

        rerenderAll() {
            this.indicators.forEach(config => this.renderIndicator(config));
            this.refreshVolume();
            this.renderLegends();
        }

        renderIndicator(config) {
            if (!config) return;
            this.clearVisuals(config.id);
            const view = this.views.get(config.id);
            const definition = this.definition(config.type);
            if (!view || !definition || view.error || !this.visibleOnInterval(config)) return;
            const slot = this.slotOf(config);
            const paneIndex = this.paneIndex(slot);
            if (paneIndex < 0) return;
            const LWC = window.LightweightCharts;
            const visuals = {series: [], outputs: new Map(), primitives: [], markerPlugins: [], paneKey: null};
            this.visuals.set(config.id, visuals);
            const candleTimes = (this.candles() || []).map(candle => candle.time);
            const byKey = new Map((view.series || []).map(series => [series.key, series]));
            const overlayScale = slot === 'price' && definition.placement === 'PANEL';
            const scaleOptions = overlayScale ? {priceScaleId: `ind-${config.id}`} : {};
            // On the main price scale lines and fills are measured from the candles' base in percentage and indexed
            // scales, which plain line series are not (see AnchoredLineView).
            const anchored = slot === 'price' && !overlayScale;
            const addSeries = (type, options) => {
                const series = this.chart.addSeries(type, {...options, ...scaleOptions}, paneIndex);
                if (overlayScale) series.priceScale().applyOptions({scaleMargins: {top: 0.72, bottom: 0.02}});
                visuals.series.push(series);
                return series;
            };
            const addLine = options => {
                if (!anchored) return addSeries(LWC.LineSeries, options);
                const series = window.StockWatchChartTypes.addAnchoredLine(this.chart, options, this.anchors, paneIndex);
                visuals.series.push(series);
                return series;
            };

            // Fills first, so lines draw on top of them.
            (definition.fills || []).forEach((fill, index) => {
                const style = this.fillStyle(config, fill, index);
                const upper = byKey.get(fill.upper);
                const lower = byKey.get(fill.lower);
                if (!style.visible || !upper || !lower) return;
                if (this.outputStyle(config, definition.outputs.find(output => output.key === fill.upper) || {}).visible === false) return;
                const lowerByTime = new Map(lower.points.map(point => [point.timestamp, point.value]));
                const data = upper.points.filter(point => lowerByTime.has(point.timestamp))
                    .map(point => ({time: chartDate(point.timestamp), upper: point.value, lower: lowerByTime.get(point.timestamp)}));
                if (data.length < 2) return;
                const series = this.chart.addCustomSeries(new BandFillView(), {
                    fillColor: themed(style.color), alternateColor: style.alternateColor ? themed(style.alternateColor) : '',
                    priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, ...scaleOptions
                }, paneIndex);
                visuals.series.push(series);
                series.setData(anchored ? anchoredBand(data, this.anchors()) : data);
            });

            const zones = definition.zones;
            if (zones) {
                const values = this.inputValues(config);
                const upperZone = Number(values[zones.upperInput]);
                const lowerZone = Number(values[zones.lowerInput]);
                const valueSeries = byKey.get(definition.outputs[0].key);
                if (valueSeries && finite(upperZone) && finite(lowerZone) && config.zoneBackground !== false) {
                    const band = this.chart.addCustomSeries(new BandFillView(), {
                        fillColor: themed('rgba(167,139,250,0.08)'), priceLineVisible: false, lastValueVisible: false, ...scaleOptions
                    }, paneIndex);
                    visuals.series.push(band);
                    band.setData(valueSeries.points.map(point => ({time: chartDate(point.timestamp), upper: upperZone, lower: lowerZone})));
                }
            }

            let firstSeries = null;
            (definition.outputs || []).forEach(spec => {
                const style = this.outputStyle(config, spec);
                if (spec.style === 'MARKERS') {
                    if (style.visible) this.renderMarkers(config, spec, view, visuals, addSeries, style, anchored);
                    return;
                }
                const data = byKey.get(spec.key);
                if (!data || !style.visible) return;
                const color = themed(style.color);
                const common = {priceLineVisible: false, lastValueVisible: true, title: '', crosshairMarkerVisible: true};
                let series;
                switch (spec.style) {
                    case 'HISTOGRAM':
                    case 'COLUMNS':
                        series = addSeries(LWC.HistogramSeries, {...common, color, base: 0});
                        break;
                    case 'AREA':
                        series = addSeries(LWC.AreaSeries, {...common, lineColor: color, topColor: withAlpha(color, 0.28), bottomColor: withAlpha(color, 0.02),
                            lineWidth: style.width});
                        break;
                    case 'DOTS':
                        series = addLine({...common, color, lineVisible: false, pointMarkersVisible: true, pointMarkersRadius: 2,
                            crosshairMarkerVisible: false});
                        break;
                    default:
                        series = addLine({...common, color, lineWidth: style.width, lineStyle: style.lineStyle,
                            lineType: spec.style === 'STEP' ? LWC.LineType.WithSteps : LWC.LineType.Simple});
                }
                series.setData(this.seriesData(config, spec, data, style, candleTimes));
                visuals.outputs.set(spec.key, series);
                if (!firstSeries) firstSeries = series;
            });

            const references = view.references || [];
            if (firstSeries && config.showReferences !== false) {
                [...new Set(references)].forEach(reference => firstSeries.createPriceLine({
                    price: reference, color: themed('rgba(150,160,154,0.55)'), lineWidth: 1,
                    lineStyle: LWC.LineStyle.Dashed, axisLabelVisible: true, title: ''
                }));
            }

            if ((view.shapes || []).length) {
                const host = firstSeries || (slot === 'price' ? this.priceSeries : null);
                if (host) {
                    const primitive = new ShapesPrimitive(this, config, view.shapes, themed(definition.outputs?.[0]?.color || '#7ea6ff'));
                    host.attachPrimitive(primitive);
                    visuals.primitives.push({series: host, primitive});
                }
            }

            if (slot !== 'price' && slot !== 'volume' && firstSeries && this.drawings) {
                visuals.paneKey = config.id;
                this.drawings.registerPane(config.id, firstSeries);
            }
        }

        /** Line, histogram and dot data with per-point colours and gaps where the output has none. */
        seriesData(config, spec, data, style, candleTimes) {
            const values = this.inputValues(config);
            const definition = this.definition(config.type);
            const points = data.points.map(point => ({time: chartDate(point.timestamp), value: point.value, tone: point.tone}));
            const palette = (spec.palette || []).map(themed);
            const zones = definition.zones && spec.key === definition.outputs[0].key
                ? {upper: Number(values[definition.zones.upperInput]), lower: Number(values[definition.zones.lowerInput])} : null;
            const colored = points.map((point, index) => {
                const item = {time: point.time, value: point.value};
                const previous = index > 0 ? points[index - 1].value : point.value;
                switch (spec.colorMode) {
                    case 'SIGN': item.color = themed(point.value >= 0 ? UP : DOWN); break;
                    case 'MOMENTUM': item.color = themed(point.value >= previous ? UP : DOWN); break;
                    case 'SIGN_MOMENTUM':
                        item.color = themed(point.value >= 0 ? (point.value >= previous ? SIGN_MOMENTUM[0] : SIGN_MOMENTUM[1])
                            : (point.value >= previous ? SIGN_MOMENTUM[2] : SIGN_MOMENTUM[3]));
                        break;
                    case 'TONE': if (palette.length && finite(point.tone)) item.color = palette[point.tone % palette.length]; break;
                    default: break;
                }
                if (zones && finite(zones.upper) && finite(zones.lower)) {
                    if (point.value > zones.upper) item.color = themed(config.zoneUpperColor || DOWN);
                    else if (point.value < zones.lower) item.color = themed(config.zoneLowerColor || UP);
                }
                return item;
            });
            if (!spec.breaks || colored.length < 2) return colored;
            const present = new Map(colored.map(item => [item.time, item]));
            const first = colored[0].time;
            const last = colored[colored.length - 1].time;
            const result = [];
            candleTimes.forEach(time => {
                if (time < first || time > last) return;
                result.push(present.get(time) || {time});
            });
            colored.forEach(item => { if (item.time > (candleTimes[candleTimes.length - 1] || '')) result.push(item); });
            return result;
        }

        /** Markers on the price pane sit on the main series, so they follow its scale; elsewhere on a hidden holder. */
        renderMarkers(config, spec, view, visuals, addSeries, style, onMainSeries) {
            const markers = (view.markers || []).filter(marker => marker.key === spec.key);
            if (!markers.length) return;
            const LWC = window.LightweightCharts;
            const unique = new Map();
            markers.forEach(marker => unique.set(marker.timestamp, marker));
            const sorted = [...unique.values()].sort((a, b) => a.timestamp - b.timestamp);
            let holder = onMainSeries ? this.priceSeries : null;
            if (!holder) {
                holder = addSeries(LWC.LineSeries, {color: 'rgba(0,0,0,0)', lineVisible: false, lastValueVisible: false, priceLineVisible: false,
                    crosshairMarkerVisible: false, pointMarkersVisible: false});
                holder.setData(sorted.map(marker => ({time: chartDate(marker.timestamp), value: marker.price})));
            }
            const position = spec.position === 'atPrice' ? 'atPriceMiddle' : (spec.position || 'aboveBar');
            const color = themed(style.color);
            const plugin = LWC.createSeriesMarkers(holder, sorted.map(marker => ({
                time: chartDate(marker.timestamp),
                position,
                price: position.startsWith('atPrice') ? marker.price : undefined,
                shape: spec.shape === 'circle' && marker.text === 'down' ? 'circle' : (spec.shape || 'circle'),
                color: marker.text === 'down' ? themed(DOWN) : color,
                size: spec.shape === 'circle' ? 0.6 : 1,
                text: marker.text === '{price}' ? this.formatPrice(marker.price) : ''
            })), {autoScale: true});
            visuals.markerPlugins.push(plugin);
        }

        // ---- legends

        renderLegends() {
            const panes = this.chart.panes();
            const wanted = new Set();
            this.slots.forEach((slot, index) => {
                if (!panes[index]) return;
                wanted.add(slot);
                let legend = this.legends.get(slot);
                if (!legend) {
                    legend = element('div', {className: 'ind-legend', attrs: {'data-slot': slot}});
                    this.legendHost.append(legend);
                    this.legends.set(slot, legend);
                }
                const rows = [];
                if (slot === 'price' && this.priceInfo) rows.push(this.priceRow());
                if (slot === 'volume') rows.push(this.volumeRow());
                this.indicators.forEach(config => {
                    if (this.legendSlot(config) !== slot || !this.intervalMatches(config)) return;
                    rows.push(this.legendRow(config));
                });
                if (slot === 'price') rows.push(...this.overlayRows());
                if (slot === 'price' && rows.length > (this.priceInfo ? 3 : 2)) {
                    // Under the symbol row, so it stays in view when the legend is cut off at the bottom of its pane.
                    rows.splice(this.priceInfo ? 1 : 0, 0, element('button', {
                        className: 'ind-legend-collapse', text: i18n.t(this.legendCollapsed ? 'ind.legend.expand' : 'ind.legend.collapse'),
                        attrs: {type: 'button', 'aria-expanded': String(!this.legendCollapsed)},
                        on: {click: () => { this.legendCollapsed = !this.legendCollapsed; this.renderLegends(); }}
                    }));
                }
                legend.classList.toggle('collapsed', slot === 'price' && this.legendCollapsed);
                legend.replaceChildren(...rows);
            });
            this.legends.forEach((legend, slot) => {
                if (!wanted.has(slot)) { legend.remove(); this.legends.delete(slot); }
            });
            this.updateLegendValues();
            this.positionLegends();
        }

        /** The legend row an indicator appears in: its pane's, or the price pane's while it has none (errors). */
        legendSlot(config) {
            const slot = this.slotOf(config);
            return this.slots.includes(slot) ? slot : 'price';
        }

        /** The symbol row: {@code provider(crosshair)} returns {title, values: [{label, value, color}]}. */
        setPriceInfo(provider) {
            this.priceInfo = provider;
            this.renderLegends();
        }

        priceRow() {
            return element('div', {className: 'ind-legend-row ind-legend-symbol', children: [
                element('span', {className: 'ind-legend-name', attrs: {'data-price-title': ''}}),
                element('span', {className: 'ind-legend-values', attrs: {'data-price-values': ''}})
            ]});
        }

        updatePriceRow(param) {
            const title = this.legendHost.querySelector('[data-price-title]');
            const values = this.legendHost.querySelector('[data-price-values]');
            if (!this.priceInfo || !title || !values) return;
            const info = this.priceInfo(param) || {};
            title.textContent = info.title || '';
            values.replaceChildren(...(info.values || []).map(item => {
                const node = element('span', {className: 'ind-legend-value', children: [
                    item.label ? element('span', {className: 'ind-legend-key', text: item.label}) : null,
                    element('span', {text: item.value})]});
                if (item.color) node.lastChild.style.color = item.color;
                return node;
            }));
        }

        /** Page overlays shown with the indicators, each removable: [{label, removeLabel, onRemove}]. */
        setOverlays(items) {
            this.overlays = Array.isArray(items) ? items : [];
            this.renderLegends();
        }

        overlayRows() {
            return (this.overlays || []).map(item => element('div', {className: 'ind-legend-row ind-legend-overlay', children: [
                element('span', {className: 'ind-legend-name', text: item.label}),
                element('span', {className: 'ind-legend-controls', children: [
                    iconButton(item.removeLabel || i18n.t('ind.action.remove'), 'close', event => { event.stopPropagation(); item.onRemove(); })]})
            ]}));
        }

        volumeRow() {
            const row = element('div', {className: 'ind-legend-row ind-legend-volume'});
            row.append(element('span', {className: 'ind-legend-name', text: i18n.t('ind.legend.volume')}),
                element('span', {className: 'ind-legend-values', attrs: {'data-volume-value': ''}}));
            return row;
        }

        legendRow(config) {
            const row = element('div', {className: `ind-legend-row${config.visible ? '' : ' hidden-indicator'}${config.error ? ' has-error' : ''}`,
                attrs: {'data-indicator': config.id}});
            const name = element('button', {className: 'ind-legend-name', text: this.label(config),
                attrs: {type: 'button', title: this.name(config.type)}, on: {click: () => this.openSettings(config.id)}});
            const values = element('span', {className: 'ind-legend-values'});
            const controls = element('span', {className: 'ind-legend-controls'});
            controls.append(
                iconButton(i18n.t(config.visible ? 'ind.action.hide' : 'ind.action.show'), config.visible ? 'eye' : 'eyeOff', () => this.toggle(config.id)),
                iconButton(i18n.t('ind.action.settings'), 'gear', () => this.openSettings(config.id)),
                iconButton(i18n.t('ind.action.more'), 'more', event => this.openRowMenu(config, event.currentTarget)),
                iconButton(i18n.t('ind.action.remove'), 'close', () => this.remove(config.id))
            );
            row.append(name, values, controls);
            if (config.error) row.append(element('span', {className: 'ind-legend-error', text: config.error}));
            else if (this.loading && !this.views.has(config.id)) row.append(element('span', {className: 'ind-legend-loading', text: i18n.t('ind.status.loading')}));
            return row;
        }

        updateLegendValues() {
            const param = this.crosshair;
            this.updatePriceRow(param);
            this.indicators.forEach(config => {
                const row = [...this.legendHost.querySelectorAll('[data-indicator]')]
                    .find(node => node.dataset.indicator === config.id)?.querySelector('.ind-legend-values');
                const visuals = this.visuals.get(config.id);
                if (!row) return;
                if (!visuals || !config.visible) { row.replaceChildren(); return; }
                const definition = this.definition(config.type);
                const spans = [];
                (definition?.outputs || []).forEach(spec => {
                    const series = visuals.outputs.get(spec.key);
                    if (!series || spec.style === 'MARKERS') return;
                    const value = this.seriesValue(series, param);
                    const priced = this.slotOf(config) === 'price' && definition.placement !== 'PANEL';
                    spans.push(element('span', {className: 'ind-legend-value', text: formatValue(value, priced ? v => this.formatPrice(v) : null),
                        attrs: {title: this.outputLabel(config.type, spec.key)}}));
                    spans[spans.length - 1].style.color = themed(this.outputStyle(config, spec).color);
                });
                row.replaceChildren(...spans);
            });
            const volume = this.legendHost.querySelector('[data-volume-value]');
            if (volume && this.volumeSeries) volume.textContent = formatValue(this.seriesValue(this.volumeSeries, param));
            this.onLegendValues?.(param);
            // Also when data or indicators change, so a data window opened from the saved layout is filled at once.
            this.updateDataWindow();
        }

        seriesValue(series, param) {
            if (param?.seriesData) {
                const item = param.seriesData.get(series);
                if (item) return item.value ?? item.close;
            }
            const data = series.data();
            for (let index = data.length - 1; index >= 0; index--) {
                const value = data[index].value ?? data[index].close;
                if (finite(value)) return value;
            }
            return NaN;
        }

        positionLegends() {
            const panes = this.chart.panes();
            const stageRect = this.stage.getBoundingClientRect();
            this.slots.forEach((slot, index) => {
                const legend = this.legends.get(slot);
                const paneElement = panes[index]?.getHTMLElement();
                if (!legend || !paneElement) return;
                const rect = paneElement.getBoundingClientRect();
                legend.style.left = `${rect.left - stageRect.left + (slot === 'price' ? this.priceLegendOffset || 6 : 6)}px`;
                legend.style.top = `${rect.top - stageRect.top + 4}px`;
                legend.style.maxWidth = `${Math.max(120, rect.width - 80)}px`;
                // Short panes show what fits; the rest of the legend stays inside its own pane.
                legend.style.maxHeight = `${Math.max(0, rect.height - 6)}px`;
                legend.hidden = rect.height < 24;
            });
        }

        openRowMenu(config, anchor) {
            const definition = this.definition(config.type);
            const slot = this.slotOf(config);
            const items = [
                {label: i18n.t('ind.action.settings'), action: () => this.openSettings(config.id)},
                slot !== 'price' ? {label: i18n.t('ind.action.moveUp'), action: () => this.movePane(config.id, -1)} : null,
                slot !== 'price' ? {label: i18n.t('ind.action.moveDown'), action: () => this.movePane(config.id, 1)} : null,
                definition?.placement === 'PANEL' && config.pane === 'own' ? {label: i18n.t('ind.action.toPricePane'), action: () => this.setPricePane(config.id, false)} : null,
                definition?.placement === 'PANEL' && config.pane !== 'own' ? {label: i18n.t('ind.action.toOwnPane'), action: () => this.setPricePane(config.id, true)} : null,
                slot !== 'price' ? {label: i18n.t(this.maximized === slot ? 'ind.action.restore' : 'ind.action.maximize'), action: () => this.maximizePane(config.id)} : null,
                this.onAlertRequest ? {label: i18n.t('ind.action.alert'), action: () => this.onAlertRequest(config)} : null,
                {label: i18n.t('ind.action.saveDefault'), action: () => this.saveTypeDefault(config)},
                {label: i18n.t('ind.action.resetDefault'), action: () => this.resetTypeDefault(config)},
                {label: i18n.t('ind.action.remove'), danger: true, action: () => this.remove(config.id)}
            ];
            this.menu(items.filter(Boolean), anchor);
        }

        menu(items, anchor) {
            this.closeMenu();
            const list = element('div', {className: 'draw-context-menu ind-menu', attrs: {role: 'menu'}});
            items.forEach(item => list.append(element('button', {
                className: `draw-menu-item${item.danger ? ' danger' : ''}`, text: item.label, attrs: {type: 'button', role: 'menuitem'},
                on: {click: () => { this.closeMenu(); item.action(); }}
            })));
            // In full screen only the full-screen element is shown.
            (document.fullscreenElement || document.body).append(list);
            const rect = anchor.getBoundingClientRect();
            const box = list.getBoundingClientRect();
            list.style.left = `${Math.min(rect.left, window.innerWidth - box.width - 8)}px`;
            list.style.top = `${Math.min(rect.bottom + 4, window.innerHeight - box.height - 8)}px`;
            this.openMenuNode = list;
            list.querySelector('button')?.focus();
            this.menuCloser = event => { if (!list.contains(event.target)) this.closeMenu(); };
            this.menuKeys = event => { if (event.key === 'Escape') this.closeMenu(); };
            window.setTimeout(() => {
                document.addEventListener('pointerdown', this.menuCloser, true);
                document.addEventListener('keydown', this.menuKeys);
            }, 0);
        }

        closeMenu() {
            this.openMenuNode?.remove();
            this.openMenuNode = null;
            document.removeEventListener('pointerdown', this.menuCloser, true);
            document.removeEventListener('keydown', this.menuKeys);
        }

        announce(message) {
            this.onStatus?.(message);
        }

        // ---- data window

        toggleDataWindow(force) {
            const open = force ?? !this.dataWindow;
            if (!open) {
                this.dataWindow?.remove();
                this.dataWindow = null;
                return false;
            }
            if (this.dataWindow) return true;
            this.dataWindow = element('aside', {className: 'ind-data-window', attrs: {'aria-label': i18n.t('ind.dataWindow.title')}});
            this.stage.append(this.dataWindow);
            this.updateDataWindow();
            return true;
        }

        /** Keeps the data window clear of the price scale, whose width follows its labels. */
        positionDataWindow() {
            let width = null;
            try { width = this.chart.priceScale('right').width(); } catch (error) { width = null; }
            if (finite(width)) this.dataWindow.style.right = `${width + 8}px`;
        }

        updateDataWindow() {
            if (!this.dataWindow) return;
            this.positionDataWindow();
            const param = this.crosshair;
            const candles = this.candles() || [];
            let candle = null;
            if (param?.time) {
                const time = typeof param.time === 'string' ? param.time : chartDate(window.StockWatchDrawing?.toSeconds(param.time) || 0);
                candle = candles.find(item => item.time === time) || null;
            }
            candle = candle || candles[candles.length - 1];
            const rows = [element('h3', {text: i18n.t('ind.dataWindow.title')})];
            const row = (label, value, color) => {
                const node = element('div', {className: 'ind-data-row', children: [element('span', {text: label}), element('strong', {text: value})]});
                if (color) node.lastChild.style.color = color;
                return node;
            };
            if (candle) {
                rows.push(row(i18n.t('ind.dataWindow.date'), i18n.date(Date.parse(`${candle.time}T00:00:00Z`), {year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'})));
                rows.push(row(i18n.t('ind.dataWindow.open'), this.formatPrice(candle.open)));
                rows.push(row(i18n.t('ind.dataWindow.high'), this.formatPrice(candle.high)));
                rows.push(row(i18n.t('ind.dataWindow.low'), this.formatPrice(candle.low)));
                rows.push(row(i18n.t('ind.dataWindow.close'), this.formatPrice(candle.close)));
                const index = candles.indexOf(candle);
                const previous = index > 0 ? candles[index - 1].close : null;
                if (finite(previous) && previous) {
                    const change = (candle.close - previous) / previous * 100;
                    rows.push(row(i18n.t('ind.dataWindow.change'), `${change > 0 ? '+' : ''}${i18n.number(change, 2)}%`, themed(change >= 0 ? UP : DOWN)));
                }
                const volume = (this.volumeData() || []).find(item => item.time === candle.time);
                if (volume) rows.push(row(i18n.t('ind.legend.volume'), formatValue(volume.value)));
            }
            this.indicators.forEach(config => {
                const visuals = this.visuals.get(config.id);
                if (!visuals || !config.visible) return;
                rows.push(element('div', {className: 'ind-data-heading', text: this.label(config)}));
                visuals.outputs.forEach((series, key) => {
                    rows.push(row(this.outputLabel(config.type, key), formatValue(this.seriesValue(series, param))));
                });
            });
            this.dataWindow.replaceChildren(...rows);
        }

        // ---- browser

        browserPreferences() {
            const stored = readLocal(BROWSER_KEY, {}) || {};
            return {favorites: Array.isArray(stored.favorites) ? stored.favorites : [], recent: Array.isArray(stored.recent) ? stored.recent : []};
        }

        rememberRecent(type) {
            const preferences = this.browserPreferences();
            preferences.recent = [type, ...preferences.recent.filter(item => item !== type)].slice(0, 12);
            writeLocal(BROWSER_KEY, preferences);
        }

        async openBrowser() {
            await this.ready;
            const dialog = element('dialog', {className: 'draw-dialog ind-browser', attrs: {'aria-labelledby': 'indicatorBrowserTitle'}});
            const content = element('div', {className: 'draw-dialog-content'});
            const close = iconButton(i18n.t('common.close'), 'close', () => dialog.close(), 'draw-dialog-close');
            const search = element('input', {attrs: {type: 'search', placeholder: i18n.t('ind.browser.search'), 'aria-label': i18n.t('ind.browser.search'),
                autocomplete: 'off'}});
            const categories = element('div', {className: 'ind-browser-categories', attrs: {role: 'tablist', 'aria-orientation': 'vertical'}});
            const list = element('div', {className: 'ind-browser-list', attrs: {role: 'list'}});
            const all = [...this.catalog.values()];
            const groups = ['ALL', 'FAVORITES', 'RECENT', ...new Set(all.map(definition => definition.category))];
            let current = 'ALL';
            const renderList = () => {
                const query = search.value.trim().toLowerCase();
                const preferences = this.browserPreferences();
                let items = all;
                if (current === 'FAVORITES') items = all.filter(definition => preferences.favorites.includes(definition.type));
                else if (current === 'RECENT') items = preferences.recent.map(type => this.definition(type)).filter(Boolean);
                else if (current !== 'ALL') items = all.filter(definition => definition.category === current);
                if (query) {
                    items = items.filter(definition => [this.name(definition.type), this.shortName(definition.type), definition.type]
                        .some(text => String(text).toLowerCase().includes(query)));
                }
                if (current === 'ALL' || query) items = [...items].sort((a, b) => this.name(a.type).localeCompare(this.name(b.type), i18n.tag));
                list.replaceChildren(...(items.length ? items.map(definition => {
                    const favorite = preferences.favorites.includes(definition.type);
                    const star = iconButton(i18n.t(favorite ? 'ind.browser.unfavorite' : 'ind.browser.favorite', this.name(definition.type)), 'star', event => {
                        event.stopPropagation();
                        const next = this.browserPreferences();
                        next.favorites = favorite ? next.favorites.filter(type => type !== definition.type) : [...next.favorites, definition.type];
                        writeLocal(BROWSER_KEY, next);
                        renderList();
                    }, `ind-icon-button ind-browser-star${favorite ? ' active' : ''}`);
                    const add = element('button', {className: 'ind-browser-item', attrs: {type: 'button'},
                        on: {click: () => {
                            const config = this.add(definition.type);
                            dialog.close();
                            if (config && (definition.inputs || []).some(input => input.kind === 'TEXT')) this.openSettings(config.id);
                        }},
                        children: [element('span', {className: `ind-browser-name${GLOSSARY_TERMS[definition.type] ? ' learn-term' : ''}`,
                            text: this.name(definition.type), attrs: {'data-term': GLOSSARY_TERMS[definition.type]}}),
                            element('span', {className: 'ind-browser-meta', text: `${this.shortName(definition.type)} · ${i18n.t(`ind.category.${definition.category}`)}`}),
                            i18n.has(`ind.${definition.type}.description`) ? element('span', {className: 'ind-browser-description', text: i18n.t(`ind.${definition.type}.description`)}) : null]});
                    return element('div', {className: 'ind-browser-row', attrs: {role: 'listitem'}, children: [add, star]});
                }) : [element('p', {className: 'ind-browser-empty', text: i18n.t('ind.browser.empty')})]));
            };
            groups.forEach(group => {
                const label = group === 'ALL' ? i18n.t('ind.browser.all') : (group === 'FAVORITES' ? i18n.t('ind.browser.favorites')
                    : (group === 'RECENT' ? i18n.t('ind.browser.recent') : i18n.t(`ind.category.${group}`)));
                categories.append(element('button', {className: `ind-browser-category${group === current ? ' active' : ''}`, text: label,
                    attrs: {type: 'button', role: 'tab', 'aria-selected': String(group === current)},
                    on: {click: event => {
                        current = group;
                        categories.querySelectorAll('button').forEach(button => {
                            button.classList.toggle('active', button === event.currentTarget);
                            button.setAttribute('aria-selected', String(button === event.currentTarget));
                        });
                        renderList();
                    }}}));
            });
            search.addEventListener('input', renderList);
            search.addEventListener('keydown', event => {
                if (event.key === 'Enter') list.querySelector('.ind-browser-item')?.click();
            });
            const footer = element('div', {className: 'ind-browser-footer', children: [
                element('button', {className: 'btn-secondary', text: i18n.t('ind.template.save'), attrs: {type: 'button'},
                    on: {click: () => this.saveIndicatorSet()}}),
                element('button', {className: 'btn-secondary', text: i18n.t('ind.template.load'), attrs: {type: 'button'},
                    on: {click: event => this.chooseIndicatorSet(event.currentTarget, () => dialog.close())}})
            ]});
            content.append(element('div', {className: 'draw-dialog-heading', children: [
                element('h2', {className: 'draw-dialog-title', text: i18n.t('ind.browser.title'), attrs: {id: 'indicatorBrowserTitle'}}), close]}),
                search, element('div', {className: 'ind-browser-body', children: [categories, list]}), footer);
            dialog.append(content);
            dialog.addEventListener('close', () => dialog.remove());
            document.body.append(dialog);
            renderList();
            dialog.showModal();
            search.focus();
        }

        // ---- settings

        openSettings(id) {
            const config = this.find(id);
            const definition = config && this.definition(config.type);
            if (!definition) return;
            const original = JSON.parse(JSON.stringify(config));
            const draft = JSON.parse(JSON.stringify(config));
            const dialog = element('dialog', {className: 'draw-dialog ind-settings', attrs: {'aria-labelledby': 'indicatorSettingsTitle'}});
            const content = element('form', {className: 'draw-dialog-content', attrs: {method: 'dialog', novalidate: true}});
            const error = element('p', {className: 'ind-settings-error', attrs: {role: 'alert'}});
            const tabs = [{key: 'inputs', build: () => this.inputFields(definition, draft)}, {key: 'style', build: () => this.styleFields(definition, draft)},
                {key: 'visibility', build: () => this.visibilityFields(draft)}];
            if (!(definition.inputs || []).length) tabs.shift();
            const tabList = element('div', {className: 'draw-tabs', attrs: {role: 'tablist'}});
            const panel = element('div', {className: 'draw-tab-panel', attrs: {role: 'tabpanel'}});
            const select = key => {
                [...tabList.children].forEach(tab => {
                    const active = tab.dataset.tab === key;
                    tab.classList.toggle('active', active);
                    tab.setAttribute('aria-selected', String(active));
                });
                panel.replaceChildren(...tabs.find(tab => tab.key === key).build());
            };
            tabs.forEach(tab => tabList.append(element('button', {className: 'draw-tab', text: i18n.t(`ind.tab.${tab.key}`),
                attrs: {type: 'button', role: 'tab', 'data-tab': tab.key}, on: {click: () => select(tab.key)}})));
            const apply = () => {
                const problem = this.validate(definition, draft);
                if (problem) { error.textContent = problem; return false; }
                Object.assign(config, draft);
                this.changed();
                this.syncPanes();
                this.refresh(0);
                return true;
            };
            content.append(
                element('div', {className: 'draw-dialog-heading', children: [
                    element('h2', {className: 'draw-dialog-title', text: this.name(config.type), attrs: {id: 'indicatorSettingsTitle'}}),
                    iconButton(i18n.t('common.close'), 'close', () => dialog.close(), 'draw-dialog-close')]}),
                i18n.has(`ind.${config.type}.description`) ? element('p', {className: 'ind-settings-description', text: i18n.t(`ind.${config.type}.description`)}) : null,
                tabList, panel, error,
                element('div', {className: 'draw-dialog-actions', children: [
                    element('button', {className: 'btn-secondary', text: i18n.t('ind.action.defaults'), attrs: {type: 'button'}, on: {click: event => this.menu([
                        {label: i18n.t('ind.action.saveDefault'), action: () => this.saveTypeDefault(draft)},
                        {label: i18n.t('ind.action.resetDefault'), action: () => {
                            draft.inputs = {};
                            draft.styles = {};
                            draft.fills = {};
                            select(tabList.querySelector('.active')?.dataset.tab || tabs[0].key);
                        }}
                    ], event.currentTarget)}}),
                    element('span', {className: 'draw-dialog-spacer'}),
                    element('button', {className: 'btn-secondary', text: i18n.t('common.cancel'), attrs: {type: 'button'}, on: {click: () => dialog.close()}}),
                    element('button', {className: 'btn-primary', text: i18n.t('ind.action.ok'), attrs: {type: 'submit'}})
                ]})
            );
            content.addEventListener('submit', event => {
                event.preventDefault();
                if (apply()) dialog.close();
            });
            dialog.addEventListener('close', () => { dialog.remove(); void original; });
            dialog.append(content);
            document.body.append(dialog);
            select(tabs[0].key);
            dialog.showModal();
        }

        validate(definition, draft) {
            for (const input of definition.inputs || []) {
                const value = draft.inputs?.[input.key] ?? input.defaultValue;
                if ((input.kind === 'INTEGER' || input.kind === 'DECIMAL') && (!finite(Number(value)) || Number(value) < input.min || Number(value) > input.max
                    || (input.kind === 'INTEGER' && !Number.isInteger(Number(value))))) {
                    return i18n.t('ind.settings.range', this.inputLabel(definition.type, input.key), i18n.number(input.min, 0), i18n.number(input.max, 0));
                }
                if (input.kind === 'SYMBOL' && !/^[A-Za-z0-9.^=\-]{1,20}$/.test(String(value))) return i18n.t('ind.settings.symbol');
                if (input.kind === 'TEXT' && !String(value).trim()) return i18n.t('ind.settings.formulaRequired');
            }
            return null;
        }

        inputFields(definition, draft) {
            draft.inputs = draft.inputs || {};
            const value = key => draft.inputs[key] ?? (definition.inputs.find(input => input.key === key)?.defaultValue);
            const set = (key, next) => { draft.inputs[key] = next; };
            const fields = (definition.inputs || []).map(input => {
                const label = this.inputLabel(definition.type, input.key);
                const wrap = (control, inline = true) => element('label', {className: `draw-field${inline ? ' inline' : ''}`, children: [
                    element('span', {className: 'draw-field-label', text: label}), control]});
                switch (input.kind) {
                    case 'INTEGER':
                    case 'DECIMAL': {
                        const field = element('input', {attrs: {type: 'number', min: input.min, max: input.max, step: input.step ?? 'any',
                            inputmode: input.kind === 'INTEGER' ? 'numeric' : 'decimal'}, on: {input: event => set(input.key, Number(event.target.value))}});
                        field.value = String(value(input.key));
                        return wrap(field);
                    }
                    case 'BOOLEAN': {
                        const field = element('input', {attrs: {type: 'checkbox'}, on: {change: event => set(input.key, event.target.checked)}});
                        field.checked = Boolean(value(input.key));
                        return element('label', {className: 'draw-field draw-check', children: [field, element('span', {text: label})]});
                    }
                    case 'CHOICE':
                    case 'TIMEFRAME': {
                        const field = element('select', {on: {change: event => set(input.key, event.target.value)}});
                        const order = ['1d', '1wk', '1mo'];
                        (input.options || []).filter(option => input.kind !== 'TIMEFRAME' || option === 'chart' || order.indexOf(option) > order.indexOf(this.interval))
                            .forEach(option => field.append(element('option', {text: this.choiceLabel(input.choiceSet || input.key, option), attrs: {value: option}})));
                        field.value = value(input.key);
                        return wrap(field);
                    }
                    case 'SOURCE': {
                        const field = element('select', {on: {change: event => set(input.key, event.target.value)}});
                        (input.options || []).forEach(option => field.append(element('option', {text: this.choiceLabel('source', option), attrs: {value: option}})));
                        this.indicators.filter(other => other.id !== draft.id).forEach(other => {
                            const otherDefinition = this.definition(other.type);
                            (otherDefinition?.outputs || []).filter(output => output.style !== 'MARKERS').forEach(output => {
                                const key = `ind:${other.id}:${output.key}`;
                                field.append(element('option', {text: this.outputName(other, output.key), attrs: {value: key}}));
                            });
                        });
                        field.value = value(input.key);
                        return wrap(field);
                    }
                    case 'SYMBOL': {
                        const field = element('input', {attrs: {type: 'text', maxlength: 20, autocapitalize: 'characters', spellcheck: 'false'},
                            on: {input: event => set(input.key, event.target.value.trim().toUpperCase())}});
                        field.value = value(input.key);
                        return wrap(field);
                    }
                    case 'TIMESTAMP': {
                        const field = element('input', {attrs: {type: 'date'}, on: {change: event => {
                            set(input.key, event.target.value ? Date.parse(`${event.target.value}T00:00:00Z`) / 1000 : 0);
                        }}});
                        const current = Number(value(input.key));
                        if (current > 0) field.value = chartDate(current);
                        return wrap(field);
                    }
                    case 'TEXT': {
                        const field = element('textarea', {attrs: {rows: 3, maxlength: input.max || 240, spellcheck: 'false'},
                            on: {input: event => set(input.key, event.target.value)}});
                        field.value = value(input.key);
                        const help = element('p', {className: 'draw-field-hint', text: i18n.t('ind.settings.formulaHelp',
                            (this.formulaSeries || []).join(', '), (this.formulaFunctions || []).join(', '))});
                        return element('div', {className: 'draw-field', children: [element('span', {className: 'draw-field-label', text: label}), field, help]});
                    }
                    default:
                        return element('span');
                }
            });
            return fields.length ? fields : [element('p', {className: 'draw-field-hint', text: i18n.t('ind.settings.noInputs')})];
        }

        styleFields(definition, draft) {
            draft.styles = draft.styles || {};
            draft.fills = draft.fills || {};
            const rows = [];
            (definition.outputs || []).forEach(spec => {
                const style = this.outputStyle(draft, spec);
                const save = mutator => { draft.styles[spec.key] = {...(draft.styles[spec.key] || {}), ...mutator}; };
                const visible = element('input', {attrs: {type: 'checkbox', 'aria-label': i18n.t('ind.style.visible', this.outputLabel(definition.type, spec.key))},
                    on: {change: event => save({visible: event.target.checked})}});
                visible.checked = style.visible;
                const color = element('input', {attrs: {type: 'color', 'aria-label': i18n.t('ind.style.color', this.outputLabel(definition.type, spec.key))},
                    on: {input: event => save({color: event.target.value})}});
                color.value = /^#[0-9a-f]{6}$/i.test(style.color) ? style.color : '#60a5fa';
                const row = [visible, element('span', {className: 'ind-style-name', text: this.outputLabel(definition.type, spec.key)}), color];
                if (['LINE', 'STEP', 'AREA'].includes(spec.style)) {
                    const width = element('select', {attrs: {'aria-label': i18n.t('ind.style.width')}, on: {change: event => save({width: Number(event.target.value)})}});
                    [1, 2, 3, 4].forEach(value => width.append(element('option', {text: `${value}px`, attrs: {value}})));
                    width.value = String(style.width);
                    const lineStyle = element('select', {attrs: {'aria-label': i18n.t('ind.style.lineStyle')}, on: {change: event => save({lineStyle: Number(event.target.value)})}});
                    [[0, 'solid'], [2, 'dashed'], [1, 'dotted']].forEach(([value, name]) => lineStyle.append(element('option', {text: i18n.t(`draw.option.lineStyle.${name}`), attrs: {value}})));
                    lineStyle.value = String(style.lineStyle);
                    row.push(width, lineStyle);
                }
                rows.push(element('div', {className: 'ind-style-row', children: row}));
            });
            (definition.fills || []).forEach((fill, index) => {
                const style = this.fillStyle(draft, fill, index);
                const visible = element('input', {attrs: {type: 'checkbox'}, on: {change: event => {
                    draft.fills[index] = {...(draft.fills[index] || {}), visible: event.target.checked};
                }}});
                visible.checked = style.visible;
                rows.push(element('label', {className: 'draw-field draw-check', children: [visible,
                    element('span', {text: i18n.t('ind.style.fill', this.outputLabel(definition.type, fill.upper), this.outputLabel(definition.type, fill.lower))})]}));
            });
            if ((definition.references || []).length || definition.zones) {
                const references = element('input', {attrs: {type: 'checkbox'}, on: {change: event => { draft.showReferences = event.target.checked; }}});
                references.checked = draft.showReferences !== false;
                rows.push(element('label', {className: 'draw-field draw-check', children: [references, element('span', {text: i18n.t('ind.style.references')})]}));
            }
            if (definition.zones) {
                const background = element('input', {attrs: {type: 'checkbox'}, on: {change: event => { draft.zoneBackground = event.target.checked; }}});
                background.checked = draft.zoneBackground !== false;
                const upper = element('input', {attrs: {type: 'color', 'aria-label': i18n.t('ind.style.upperZone')}, on: {input: event => { draft.zoneUpperColor = event.target.value; }}});
                upper.value = draft.zoneUpperColor || DOWN;
                const lower = element('input', {attrs: {type: 'color', 'aria-label': i18n.t('ind.style.lowerZone')}, on: {input: event => { draft.zoneLowerColor = event.target.value; }}});
                lower.value = draft.zoneLowerColor || UP;
                rows.push(element('label', {className: 'draw-field draw-check', children: [background, element('span', {text: i18n.t('ind.style.zoneBackground')})]}),
                    element('div', {className: 'ind-style-row', children: [element('span', {className: 'ind-style-name', text: i18n.t('ind.style.upperZone')}), upper]}),
                    element('div', {className: 'ind-style-row', children: [element('span', {className: 'ind-style-name', text: i18n.t('ind.style.lowerZone')}), lower]}));
            }
            return rows.length ? rows : [element('p', {className: 'draw-field-hint', text: i18n.t('ind.settings.noStyle')})];
        }

        visibilityFields(draft) {
            const intervals = ['1d', '1wk', '1mo'];
            const boxes = intervals.map(interval => {
                const input = element('input', {attrs: {type: 'checkbox'}, on: {change: () => {
                    const chosen = intervals.filter((candidate, index) => boxes[index].querySelector('input').checked);
                    draft.intervals = chosen.length === intervals.length ? null : chosen;
                }}});
                input.checked = !Array.isArray(draft.intervals) || !draft.intervals.length || draft.intervals.includes(interval);
                return element('label', {className: 'draw-field draw-check', children: [input, element('span', {text: i18n.t(`draw.interval.${interval}`)})]});
            });
            return [element('p', {className: 'draw-field-hint', text: i18n.t('ind.settings.intervals')}), ...boxes];
        }

        // ---- defaults, templates and layout

        get typeDefaults() {
            if (!this.layoutState) this.layoutState = {};
            if (!this.layoutState.typeDefaults) this.layoutState.typeDefaults = {};
            return this.layoutState.typeDefaults;
        }

        saveTypeDefault(config) {
            this.typeDefaults[config.type] = {inputs: {...(config.inputs || {})}, styles: {...(config.styles || {})}, fills: {...(config.fills || {})}};
            this.scheduleSave();
            this.announce(i18n.t('ind.status.defaultSaved', this.name(config.type)));
        }

        resetTypeDefault(config) {
            delete this.typeDefaults[config.type];
            this.update(config.id, item => { item.inputs = {}; item.styles = {}; item.fills = {}; });
        }

        snapshot() {
            return {
                version: 1,
                indicators: this.indicators.map(({error, ...config}) => config),
                paneHeights: this.paneHeights,
                typeDefaults: this.typeDefaults,
                extras: this.extras || {}
            };
        }

        scheduleSave() {
            window.clearTimeout(this.saveTimer);
            this.saveTimer = window.setTimeout(() => this.saveLayout(), 900);
        }

        async saveLayout() {
            const payload = JSON.stringify(this.snapshot());
            writeLocal(`stockwatch:chart-layout:v1:${LAYOUT_SCOPE}`, JSON.parse(payload));
            try {
                await fetch(`/api/chart-state/layouts/${LAYOUT_SCOPE}`, {method: 'PUT', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json', ...csrfHeaders()}, body: payload});
            } catch (error) { /* kept in this browser */ }
        }

        async loadLayout() {
            let layout = null;
            try {
                const response = await fetch(`/api/chart-state/layouts/${LAYOUT_SCOPE}`, {credentials: 'same-origin'});
                if (response.ok && response.status !== 204) layout = (await response.json()).payload;
            } catch (error) { /* fall back to this browser */ }
            if (!layout) layout = readLocal(`stockwatch:chart-layout:v1:${LAYOUT_SCOPE}`, null);
            this.layoutState = layout || {};
            this.paneHeights = layout?.paneHeights || {};
            this.extras = layout?.extras || {};
            this.indicators = (layout?.indicators || []).filter(config => config && this.definition(config.type)).slice(0, 30);
            this.renderLegends();
            this.onLayoutLoaded?.(this.extras);
        }

        setExtra(key, value) {
            this.extras = {...(this.extras || {}), [key]: value};
            this.scheduleSave();
        }

        async saveIndicatorSet() {
            this.promptName(i18n.t('ind.template.name'), async value => {
                try {
                    const response = await fetch(`/api/chart-state/templates?kind=INDICATOR_SET&subject=stock&name=${encodeURIComponent(value)}`, {
                        method: 'PUT', credentials: 'same-origin', headers: {'Content-Type': 'application/json', ...csrfHeaders()},
                        body: JSON.stringify({indicators: this.indicators.map(({error, ...config}) => config)})
                    });
                    if (!response.ok) throw new Error('unavailable');
                    this.announce(i18n.t('ind.template.saved', value));
                } catch (error) {
                    this.announce(i18n.t('ind.status.failed'));
                }
            });
        }

        async chooseIndicatorSet(anchor, after) {
            let templates = [];
            try {
                const response = await fetch('/api/chart-state/templates?kind=INDICATOR_SET&subject=stock', {credentials: 'same-origin'});
                if (response.ok) templates = (await response.json()).templates || [];
            } catch (error) { templates = []; }
            if (!templates.length) {
                this.announce(i18n.t('ind.template.none'));
                return;
            }
            this.menu([
                ...templates.map(template => ({label: template.name, action: () => {
                    this.indicators.forEach(config => this.clearVisuals(config.id));
                    this.views.clear();
                    this.indicators = (template.payload?.indicators || []).filter(config => this.definition(config.type)).map(config => ({...config}));
                    this.syncPanes();
                    this.changed();
                    this.refresh(0);
                    after?.();
                }})),
                ...templates.map(template => ({label: i18n.t('ind.template.delete', template.name), danger: true, action: async () => {
                    await fetch(`/api/chart-state/templates/${template.id}`, {method: 'DELETE', credentials: 'same-origin', headers: csrfHeaders()}).catch(() => null);
                }}))
            ], anchor);
        }

        promptName(title, onSubmit) {
            const dialog = element('dialog', {className: 'draw-dialog draw-prompt-dialog', attrs: {'aria-label': title}});
            const form = element('form', {className: 'draw-dialog-content', attrs: {method: 'dialog'}});
            const input = element('input', {attrs: {type: 'text', maxlength: 80, required: true, 'aria-label': title}});
            form.append(element('h2', {className: 'draw-dialog-title', text: title}), input, element('div', {className: 'draw-dialog-actions', children: [
                element('button', {className: 'btn-secondary', text: i18n.t('common.cancel'), attrs: {type: 'button'}, on: {click: () => dialog.close()}}),
                element('button', {className: 'btn-primary', text: i18n.t('ind.action.ok'), attrs: {type: 'submit'}})]}));
            form.addEventListener('submit', event => {
                event.preventDefault();
                const value = input.value.trim();
                if (!value) return;
                dialog.close();
                onSubmit(value);
            });
            dialog.addEventListener('close', () => dialog.remove());
            dialog.append(form);
            document.body.append(dialog);
            dialog.showModal();
            input.focus();
        }

        objectTreeItems() {
            return this.indicators.map(config => ({
                label: this.label(config),
                visible: config.visible,
                onToggle: () => this.toggle(config.id),
                onRemove: () => this.remove(config.id),
                onSettings: () => this.openSettings(config.id)
            }));
        }

        destroy() {
            this.chart.unsubscribeCrosshairMove(this.onCrosshair);
            this.chart.timeScale().unsubscribeVisibleLogicalRangeChange(this.onVisibleRange);
            this.resizeObserver?.disconnect();
            window.removeEventListener('stockwatch:themechange', this.onTheme);
            this.legendHost.remove();
            this.dataWindow?.remove();
            this.closeMenu();
        }
    }

    window.StockWatchIndicatorManager = IndicatorManager;
    window.StockWatchBandFillView = BandFillView;
})();
