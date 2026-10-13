(() => {
    'use strict';

    // Price chart types (candles, Heikin Ashi, bars, lines, area, baseline, columns, high-low) and the
    // date-range presets. These keep one bar per candle, so indicators, drawings and panes line up; the
    // price-only types further down (Renko, Line break, Kagi, Point & Figure) get a chart of their own.

    const DAY = 86400;
    const TYPES = Object.freeze(['candles', 'hollow', 'heikinAshi', 'bars', 'line', 'lineMarkers', 'step', 'area', 'baseline', 'columns', 'highLow',
        'renko', 'lineBreak', 'kagi', 'pointFigure']);
    // Types that draw each candle's own open, high, low and close (candlestick signals sit on these).
    const OHLC = new Set(['candles', 'hollow', 'bars']);
    const PRESETS = Object.freeze(['1M', '3M', '6M', 'YTD', '1Y', '5Y', 'ALL']);

    const finite = value => typeof value === 'number' && Number.isFinite(value);

    function heikinAshi(candles) {
        const result = [];
        candles.forEach((candle, index) => {
            const close = (candle.open + candle.high + candle.low + candle.close) / 4;
            const previous = result[index - 1];
            const open = previous ? (previous.open + previous.close) / 2 : (candle.open + candle.close) / 2;
            result.push({time: candle.time, open, high: Math.max(candle.high, open, close), low: Math.min(candle.low, open, close), close});
        });
        return result;
    }

    /** Series options for a type; {@code colors} has up, down, line and neutral. */
    function options(type, colors) {
        const candles = {upColor: colors.up, downColor: colors.down, wickUpColor: colors.up, wickDownColor: colors.down, borderVisible: false};
        switch (type) {
            case 'hollow':
                return {...candles, upColor: 'rgba(0,0,0,0)', borderVisible: true, borderUpColor: colors.up, borderDownColor: colors.down};
            case 'bars':
                return {upColor: colors.up, downColor: colors.down, thinBars: false};
            case 'line':
                return {color: colors.line, lineWidth: 2};
            case 'lineMarkers':
                return {color: colors.line, lineWidth: 2, pointMarkersVisible: true, pointMarkersRadius: 2.5};
            case 'step':
                return {color: colors.line, lineWidth: 2, lineType: 1};
            case 'area':
                return {lineColor: colors.line, topColor: withAlpha(colors.line, 0.32), bottomColor: withAlpha(colors.line, 0.02), lineWidth: 2};
            case 'baseline':
                return {baseValue: {type: 'price', price: 0}, topLineColor: colors.up, bottomLineColor: colors.down,
                    topFillColor1: withAlpha(colors.up, 0.28), topFillColor2: withAlpha(colors.up, 0.04),
                    bottomFillColor1: withAlpha(colors.down, 0.04), bottomFillColor2: withAlpha(colors.down, 0.28), lineWidth: 2};
            case 'columns':
                return {color: colors.up, base: 0};
            case 'highLow':
                return {upColor: withAlpha(colors.neutral, 0.55), downColor: withAlpha(colors.neutral, 0.55), borderVisible: true,
                    borderUpColor: colors.neutral, borderDownColor: colors.neutral, wickVisible: false};
            default:
                return candles;
        }
    }

    function definition(type, library) {
        switch (type) {
            case 'bars': return library.BarSeries;
            case 'line':
            case 'lineMarkers':
            case 'step': return library.LineSeries;
            case 'area': return library.AreaSeries;
            case 'baseline': return library.BaselineSeries;
            case 'columns': return library.HistogramSeries;
            default: return library.CandlestickSeries;
        }
    }

    /** Series data for a type from candles shaped {time, open, high, low, close}. */
    function data(type, candles, colors) {
        switch (type) {
            case 'heikinAshi': return heikinAshi(candles);
            case 'line':
            case 'lineMarkers':
            case 'step':
            case 'area':
            case 'baseline': return candles.map(candle => ({time: candle.time, value: candle.close}));
            case 'columns': return candles.map((candle, index) => ({
                time: candle.time, value: candle.close,
                color: withAlpha(candle.close >= (index ? candles[index - 1].close : candle.open) ? colors.up : colors.down, 0.75)
            }));
            // A box from the low to the high: open = low, close = high.
            case 'highLow': return candles.map(candle => ({time: candle.time, open: candle.low, high: candle.high, low: candle.low, close: candle.high}));
            default: return candles.map(candle => ({time: candle.time, open: candle.open, high: candle.high, low: candle.low, close: candle.close}));
        }
    }

    function create(chart, type, colors, extra = {}) {
        const library = window.LightweightCharts;
        return chart.addSeries(definition(type, library), {...options(type, colors), ...extra}, 0);
    }

    /** First second shown by a date-range preset ending at {@code last}; null shows everything. */
    function rangeStart(preset, last) {
        if (!finite(last)) return null;
        const end = new Date(last * 1000);
        const shifted = months => Date.UTC(end.getUTCFullYear(), end.getUTCMonth() - months, end.getUTCDate()) / 1000;
        switch (preset) {
            case '1M': return shifted(1);
            case '3M': return shifted(3);
            case '6M': return shifted(6);
            case 'YTD': return Date.UTC(end.getUTCFullYear(), 0, 1) / 1000;
            case '1Y': return shifted(12);
            case '5Y': return shifted(60);
            default: return null;
        }
    }

    /** Index of the candle closest to {@code seconds} (candles sorted by timestamp). */
    function nearestIndex(timestamps, seconds) {
        if (!timestamps.length || !finite(seconds)) return -1;
        let low = 0;
        let high = timestamps.length - 1;
        while (low < high) {
            const middle = (low + high) >> 1;
            if (timestamps[middle] < seconds) low = middle + 1; else high = middle;
        }
        if (low > 0 && Math.abs(timestamps[low - 1] - seconds) <= Math.abs(timestamps[low] - seconds)) return low - 1;
        return low;
    }

    // ---- price-only views: Renko, Line break, Kagi, Point & Figure
    //
    // These draw a new brick, line or column only when price moves far enough, whatever time passes, so
    // they cannot share the time axis with indicators and drawings. They get their own chart, whose
    // positions are numbered and labelled with the date of the candle that completed each element.

    const PRICE_ONLY = new Set(['renko', 'lineBreak', 'kagi', 'pointFigure']);
    const ORIGIN = 946684800; // 2000-01-01: element n is drawn at ORIGIN + n days
    const MAX_ELEMENTS = 5000;

    /** Average true range of the last {@code period} candles, rounded to two significant digits. */
    function autoBox(candles, period = 14) {
        if (candles.length < 2) return null;
        const start = Math.max(1, candles.length - period);
        let sum = 0;
        for (let index = start; index < candles.length; index++) {
            const candle = candles[index];
            const previous = candles[index - 1].close;
            sum += Math.max(candle.high - candle.low, Math.abs(candle.high - previous), Math.abs(candle.low - previous));
        }
        const atr = sum / (candles.length - start);
        return atr > 0 ? Number(atr.toPrecision(2)) : null;
    }

    /** A box no smaller than the price range allows (at most MAX_ELEMENTS bricks or boxes). */
    function usableBox(candles, requested) {
        let low = Infinity;
        let high = -Infinity;
        candles.forEach(candle => { low = Math.min(low, candle.low); high = Math.max(high, candle.high); });
        const floor = finite(high - low) && high > low ? (high - low) / MAX_ELEMENTS : 0;
        const box = finite(requested) && requested > 0 ? requested : autoBox(candles);
        return finite(box) ? Math.max(box, floor) : null;
    }

    /** Classic close-based Renko: a reversal needs two boxes against the last brick. */
    function renko(candles, box) {
        const bricks = [];
        if (!candles.length || !(box > 0)) return bricks;
        const anchor = Math.floor(candles[0].close / box) * box;
        for (const candle of candles) {
            const close = candle.close;
            const last = bricks[bricks.length - 1];
            // Bricks continue from the last close, or reverse from the last open (two boxes from its far end).
            let from = anchor;
            let direction = 0;
            if (!last) {
                direction = close >= anchor + box ? 1 : (close <= anchor - box ? -1 : 0);
            } else if (last.close > last.open) {
                if (close >= last.close + box) { from = last.close; direction = 1; } else if (close <= last.open - box) { from = last.open; direction = -1; }
            } else if (close <= last.close - box) {
                from = last.close;
                direction = -1;
            } else if (close >= last.open + box) {
                from = last.open;
                direction = 1;
            }
            while (direction && (direction > 0 ? close >= from + box : close <= from - box) && bricks.length <= MAX_ELEMENTS) {
                const to = from + direction * box;
                bricks.push({open: from, close: to, high: Math.max(from, to), low: Math.min(from, to), timestamp: candle.timestamp});
                from = to;
            }
        }
        return bricks;
    }

    /** Line break: a new line continues past the last close; a reversal must break the last {@code count} lines. */
    function lineBreak(candles, count = 3) {
        const lines = [];
        if (candles.length < 2) return lines;
        let reference = candles[0].close;
        for (const candle of candles.slice(1)) {
            const close = candle.close;
            const last = lines[lines.length - 1];
            const line = (open, value) => lines.push({open, close: value, high: Math.max(open, value), low: Math.min(open, value),
                timestamp: candle.timestamp});
            if (!last) {
                if (close !== reference) line(reference, close);
                continue;
            }
            const recent = lines.slice(-count);
            const rising = last.close > last.open;
            if (rising && close > last.close) line(last.close, close);
            else if (!rising && close < last.close) line(last.close, close);
            else if (rising && close < Math.min(...recent.map(item => item.low))) line(last.open, close);
            else if (!rising && close > Math.max(...recent.map(item => item.high))) line(last.open, close);
            reference = close;
        }
        return lines;
    }

    /**
     * Kagi: the line turns when price reverses by {@code reversal}; it becomes thick (yang) above the last
     * shoulder and thin (yin) below the last waist. {@code change} is the price where the thickness changed.
     */
    function kagi(candles, reversal) {
        const lines = [];
        if (!candles.length || !(reversal > 0)) return lines;
        const start = candles[0].close;
        let thick = true;
        let shoulder = Infinity;
        let waist = -Infinity;
        for (const candle of candles) {
            const close = candle.close;
            const last = lines[lines.length - 1];
            if (!last) {
                if (Math.abs(close - start) >= reversal) {
                    thick = close > start;
                    lines.push({open: start, close, thick, change: null, timestamp: candle.timestamp});
                }
                continue;
            }
            const rising = last.close > last.open;
            if ((rising && close > last.close) || (!rising && close < last.close)) {
                last.close = close;
                last.timestamp = candle.timestamp;
            } else if (Math.abs(close - last.close) >= reversal) {
                if (rising) shoulder = last.close; else waist = last.close;
                lines.push({open: last.close, close, thick, change: null, timestamp: candle.timestamp});
            } else {
                continue;
            }
            const current = lines[lines.length - 1];
            const up = current.close > current.open;
            if (up && !current.thick && current.close > shoulder) {
                current.change = shoulder;
                current.thick = true;
            } else if (!up && current.thick && current.close < waist) {
                current.change = waist;
                current.thick = false;
            }
            thick = current.thick;
            if (lines.length > MAX_ELEMENTS) break;
        }
        return lines;
    }

    /** Point & Figure from closes: X columns rise, O columns fall; a new column needs {@code reversal} boxes. */
    function pointFigure(candles, box, reversal = 3) {
        const columns = [];
        if (!candles.length || !(box > 0)) return columns;
        const reference = Math.floor(candles[0].close / box) * box;
        for (const candle of candles) {
            const close = candle.close;
            const last = columns[columns.length - 1];
            if (!last) {
                if (close >= reference + box) columns.push({kind: 'X', low: reference + box, high: Math.floor(close / box) * box, timestamp: candle.timestamp});
                else if (close <= reference - box) columns.push({kind: 'O', high: reference - box, low: Math.ceil(close / box) * box, timestamp: candle.timestamp});
                continue;
            }
            if (last.kind === 'X') {
                if (close >= last.high + box) {
                    last.high = Math.floor(close / box) * box;
                    last.timestamp = candle.timestamp;
                } else if (close <= last.high - reversal * box) {
                    columns.push({kind: 'O', high: last.high - box, low: Math.ceil(close / box) * box, timestamp: candle.timestamp});
                }
            } else if (close <= last.low - box) {
                last.low = Math.ceil(close / box) * box;
                last.timestamp = candle.timestamp;
            } else if (close >= last.low + reversal * box) {
                columns.push({kind: 'X', low: last.low + box, high: Math.floor(close / box) * box, timestamp: candle.timestamp});
            }
            if (columns.length > MAX_ELEMENTS) break;
        }
        return columns;
    }

    /**
     * The elements of a price-only view and their chart data. Each element keeps the timestamp of the
     * candle that completed it, so positions can be labelled with real dates.
     */
    function priceOnly(type, candles, settings = {}) {
        const box = type === 'lineBreak' ? null : usableBox(candles, settings.box);
        let items;
        switch (type) {
            case 'renko': items = renko(candles, box); break;
            case 'lineBreak': items = lineBreak(candles, Math.min(10, Math.max(1, Math.round(settings.lines || 3)))); break;
            case 'kagi': items = kagi(candles, box); break;
            default: items = pointFigure(candles, box, 3);
        }
        const time = index => ORIGIN + index * DAY;
        const data = items.map((item, index) => (type === 'pointFigure'
            ? {time: time(index), low: item.low, high: item.high, kind: item.kind, box}
            : (type === 'kagi' ? {time: time(index), open: item.open, close: item.close, thick: item.thick, change: item.change}
                : {time: time(index), open: item.open, high: item.high, low: item.low, close: item.close})));
        return {items, data, box, series: type === 'kagi' || type === 'pointFigure' ? type : 'candles'};
    }

    /** The element drawn at a price-only chart time. */
    function elementIndex(time) {
        return Math.round((Number(time) - ORIGIN) / DAY);
    }

    /** Kagi: vertical lines joined by horizontal shoulders and waists, thick while yang. */
    class KagiView {
        renderer() { return {draw: (target, toY) => this.draw(target, toY)}; }

        update(data, options) { this.data = data; this.options = options; }

        priceValueBuilder(item) { return [item.open, item.close]; }

        isWhitespace(item) { return !finite(item.open) || !finite(item.close); }

        defaultOptions() {
            return {...window.LightweightCharts.customSeriesDefaultOptions, upColor: '#26a69a', downColor: '#ef5350', lineWidth: 1};
        }

        draw(target, toY) {
            const data = this.data;
            if (!data?.visibleRange) return;
            target.useMediaCoordinateSpace(({context}) => {
                const bars = data.bars;
                const segment = (x1, y1, x2, y2, thick) => {
                    context.strokeStyle = thick ? this.options.upColor : this.options.downColor;
                    context.lineWidth = thick ? 3 : 1.25;
                    context.beginPath();
                    context.moveTo(x1, y1);
                    context.lineTo(x2, y2);
                    context.stroke();
                };
                const from = Math.max(0, data.visibleRange.from - 1);
                const to = Math.min(bars.length - 1, data.visibleRange.to + 1);
                for (let index = from; index <= to; index++) {
                    const bar = bars[index];
                    const item = bar.originalData;
                    const open = toY(item.open);
                    const close = toY(item.close);
                    if (!finite(open) || !finite(close)) continue;
                    if (finite(item.change)) {
                        const change = toY(item.change);
                        segment(bar.x, open, bar.x, change, !item.thick);
                        segment(bar.x, change, bar.x, close, item.thick);
                    } else {
                        segment(bar.x, open, bar.x, close, item.thick);
                    }
                    const next = bars[index + 1];
                    if (next) segment(bar.x, close, next.x, close, item.thick);
                }
            });
        }
    }

    /** Point & Figure: a column of X's (rising) or O's (falling), one per box. */
    class PointFigureView {
        renderer() { return {draw: (target, toY) => this.draw(target, toY)}; }

        update(data, options) { this.data = data; this.options = options; }

        priceValueBuilder(item) { return [item.low, item.high, item.kind === 'X' ? item.high : item.low]; }

        isWhitespace(item) { return !finite(item.low) || !finite(item.high); }

        defaultOptions() {
            return {...window.LightweightCharts.customSeriesDefaultOptions, upColor: '#26a69a', downColor: '#ef5350'};
        }

        draw(target, toY) {
            const data = this.data;
            if (!data?.visibleRange) return;
            target.useMediaCoordinateSpace(({context}) => {
                const half = Math.max(2, Math.min(data.barSpacing * 0.4, 9));
                for (let index = data.visibleRange.from; index < data.visibleRange.to; index++) {
                    const bar = data.bars[index];
                    if (!bar) continue;
                    const item = bar.originalData;
                    const boxes = Math.round((item.high - item.low) / item.box);
                    context.strokeStyle = item.kind === 'X' ? this.options.upColor : this.options.downColor;
                    context.lineWidth = 1.4;
                    for (let step = 0; step <= boxes && step < 400; step++) {
                        const y = toY(item.low + step * item.box);
                        if (!finite(y)) continue;
                        const size = Math.max(2, Math.min(half, Math.abs(toY(item.low) - toY(item.low + item.box)) * 0.45));
                        context.beginPath();
                        if (item.kind === 'X') {
                            context.moveTo(bar.x - size, y - size);
                            context.lineTo(bar.x + size, y + size);
                            context.moveTo(bar.x + size, y - size);
                            context.lineTo(bar.x - size, y + size);
                        } else {
                            context.arc(bar.x, y, size, 0, Math.PI * 2);
                        }
                        context.stroke();
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ lines that share the price scale

    // Lightweight Charts dash patterns, in multiples of the line width.
    const DASHES = {0: [], 1: [1, 1], 2: [2, 2], 3: [6, 6], 4: [1, 4]};
    const timeKey = time => (time && typeof time === 'object' ? Date.UTC(time.year, time.month - 1, time.day) / 1000 : time);

    /**
     * Items for an AnchoredLineView: one per bar of the main series carrying that bar's close as `anchor`, with the
     * line's own points merged in. A point without a value is a gap; bars between two values are not. Each item knows
     * the nearest value at or before it (`previous`) and after it (`following`), so drawing skips the bars between
     * the points of a sparse line, such as an Elliott wave. Items after the line's last value are left out, so the
     * price axis label shows that value.
     */
    function anchoredLineData(points, anchors) {
        const byTime = new Map();
        (anchors || []).forEach(anchor => {
            if (finite(anchor.close)) byTime.set(timeKey(anchor.time), {time: anchor.time, anchor: anchor.close});
        });
        (points || []).forEach(point => {
            const key = timeKey(point.time);
            const item = byTime.get(key) || {time: point.time};
            if (finite(point.value)) {
                item.value = point.value;
                if (point.color) item.color = point.color;
            } else {
                item.gap = true;
            }
            byTime.set(key, item);
        });
        const items = [...byTime.entries()].sort(([a], [b]) => (a < b ? -1 : (a > b ? 1 : 0))).map(([, item]) => item);
        let last = -1;
        items.forEach((item, index) => { if (finite(item.value)) last = index; });
        const result = [];
        let lastValue = -1;
        let gap = false;
        items.slice(0, last + 1).forEach(item => {
            const hasValue = finite(item.value);
            if (item.gap) gap = true;
            // Without a value or an anchor an item is whitespace, which the chart drops from the bars it draws.
            if (!hasValue && !finite(item.anchor)) return;
            const entry = {time: item.time, value: hasValue ? item.value : NaN, previous: lastValue, following: -1};
            if (finite(item.anchor)) entry.anchor = item.anchor;
            if (item.color) entry.color = item.color;
            const index = result.length;
            result.push(entry);
            if (!hasValue) return;
            if (lastValue >= 0) result[lastValue].joined = !gap;
            lastValue = index;
            entry.previous = index;
            gap = false;
        });
        for (let index = result.length - 1, next = -1; index >= 0; index--) {
            result[index].following = next;
            if (finite(result[index].value)) next = index;
        }
        if (result.length) result[result.length - 1].end = true;
        return result;
    }

    /**
     * A line on the main price scale that stays with the candles in every scale mode. In percentage and indexed
     * scales Lightweight Charts measures each series from its own first visible value; this series reports the main
     * series' close of every bar as its last value (the one used for that), so it is measured from the same base as
     * the candles. Only the final item reports the line's own value last, for the price axis label.
     */
    class AnchoredLineView {
        renderer() { return {draw: (target, toY) => this.draw(target, toY)}; }

        update(data, options) { this.data = data; this.options = options; }

        priceValueBuilder(item) {
            const values = finite(item.value) ? [item.value] : [];
            if (finite(item.anchor)) {
                if (item.end && values.length) values.unshift(item.anchor);
                else values.push(item.anchor);
            }
            return values;
        }

        isWhitespace(item) { return !finite(item.value) && !finite(item.anchor); }

        defaultOptions() {
            return {...window.LightweightCharts.customSeriesDefaultOptions, color: '#2962ff', lineWidth: 2, lineStyle: 0, lineType: 0,
                lineVisible: true, pointMarkersVisible: false, pointMarkersRadius: 2};
        }

        draw(target, toY) {
            const data = this.data;
            const options = this.options;
            if (!data?.visibleRange || !data.bars.length || (options.lineVisible === false && !options.pointMarkersVisible)) return;
            const bars = data.bars;
            const from = Math.max(0, data.visibleRange.from - 1);
            const to = Math.min(bars.length - 1, data.visibleRange.to);
            const start = bars[from].originalData;
            // From the last value before the visible bars to the first one after them, jumping over bars without one.
            let index = start.previous >= 0 ? start.previous : start.following;
            const points = [];
            while (index >= 0) {
                const bar = bars[index];
                const item = bar?.originalData;
                if (!item) break;
                const y = toY(item.value);
                points.push({x: bar.x, y: finite(y) ? y : null, color: item.color || options.color, joined: item.joined});
                if (index > to) break;
                index = item.following;
            }
            if (!points.length) return;
            target.useMediaCoordinateSpace(({context}) => {
                const width = Math.max(1, options.lineWidth || 1);
                if (options.lineVisible !== false && points.length > 1) {
                    context.lineWidth = width;
                    context.lineJoin = 'round';
                    context.lineCap = 'butt';
                    context.setLineDash((DASHES[options.lineStyle] || []).map(step => step * width));
                    const steps = options.lineType === 1;
                    // One path per run of joined segments in one colour; a segment takes the colour of its end point.
                    let colour = null;
                    let pathEnd = -1;
                    for (let at = 1; at < points.length; at++) {
                        const a = points[at - 1];
                        const b = points[at];
                        if (!a.joined || a.y === null || b.y === null) continue;
                        if (pathEnd !== at - 1 || b.color !== colour) {
                            if (colour !== null) context.stroke();
                            colour = b.color;
                            context.strokeStyle = colour;
                            context.beginPath();
                            context.moveTo(a.x, a.y);
                        }
                        if (steps) context.lineTo(b.x, a.y);
                        context.lineTo(b.x, b.y);
                        pathEnd = at;
                    }
                    if (colour !== null) context.stroke();
                    context.setLineDash([]);
                }
                if (options.pointMarkersVisible) {
                    const radius = options.pointMarkersRadius || 2;
                    points.forEach(point => {
                        if (point.y === null) return;
                        context.fillStyle = point.color;
                        context.beginPath();
                        context.arc(point.x, point.y, radius, 0, Math.PI * 2);
                        context.fill();
                    });
                }
            });
        }
    }

    /**
     * Adds an AnchoredLineView series. `anchors()` returns the main series' bars as {time, close}; setData takes the
     * line's points ({time, value, color?}) and merges them with the anchors, and reanchor() does it again when the
     * main series changes.
     */
    function addAnchoredLine(chart, options = {}, anchors = () => [], paneIndex = 0) {
        const series = chart.addCustomSeries(new AnchoredLineView(), options, paneIndex);
        const write = series.setData.bind(series);
        let points = [];
        series.setData = data => {
            points = Array.isArray(data) ? data : [];
            write(anchoredLineData(points, anchors()));
        };
        series.reanchor = () => write(anchoredLineData(points, anchors()));
        return series;
    }

    function withAlpha(color, alpha) {
        if (typeof color !== 'string') return color;
        const hex = color.match(/^#([0-9a-f]{6})$/i);
        if (hex) {
            const value = parseInt(hex[1], 16);
            return `rgba(${(value >> 16) & 255},${(value >> 8) & 255},${value & 255},${alpha})`;
        }
        const rgba = color.match(/^rgba?\(([^,]+),([^,]+),([^,)]+)/i);
        return rgba ? `rgba(${rgba[1].trim()},${rgba[2].trim()},${rgba[3].trim()},${alpha})` : color;
    }

    window.StockWatchChartTypes = Object.freeze({TYPES, PRESETS, DAY, isOhlc: type => OHLC.has(type), isPriceOnly: type => PRICE_ONLY.has(type),
        heikinAshi, options, data, create, rangeStart, nearestIndex, withAlpha, autoBox, renko, lineBreak, kagi, pointFigure, priceOnly,
        elementIndex, KagiView, PointFigureView, AnchoredLineView, anchoredLineData, addAnchoredLine});
})();
