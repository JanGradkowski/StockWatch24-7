(() => {
    'use strict';

    // Shared building blocks for the drawing tool modules: property descriptors for the settings
    // dialog, common rendering (extended lines, text along lines, statistics), hit helpers, and the
    // calculations several tools reuse (Fibonacci levels, volume profiles, VWAP).

    const {Draw, Geometry} = window.StockWatchDrawing;
    const i18n = window.StockWatchI18n;
    const {segmentDistance, extendSegment, withAlpha, insidePolygon} = Geometry;
    const finite = value => typeof value === 'number' && Number.isFinite(value);

    // ---- property descriptors (label key: draw.prop.<key>)

    const prop = (key, type, extra = {}) => Object.freeze({key, type, tab: 'style', ...extra});
    const P = {
        color: prop('color', 'color'),
        width: prop('width', 'width'),
        lineStyle: prop('lineStyle', 'lineStyle'),
        fill: prop('fill', 'bool'),
        fillColor: prop('fillColor', 'color'),
        fillOpacity: prop('fillOpacity', 'opacity'),
        extendLeft: prop('extendLeft', 'bool'),
        extendRight: prop('extendRight', 'bool'),
        arrowStart: prop('arrowStart', 'bool'),
        arrowEnd: prop('arrowEnd', 'bool'),
        showStats: prop('showStats', 'bool'),
        showMiddle: prop('showMiddle', 'bool'),
        showLabels: prop('showLabels', 'bool'),
        showPrices: prop('showPrices', 'bool'),
        reverse: prop('reverse', 'bool'),
        background: prop('background', 'bool'),
        text: prop('text', 'text', {tab: 'text'}),
        textColor: prop('textColor', 'color', {tab: 'text'}),
        fontSize: prop('fontSize', 'fontSize', {tab: 'text'}),
        bold: prop('bold', 'bool', {tab: 'text'}),
        italic: prop('italic', 'bool', {tab: 'text'}),
        textBackground: prop('textBackground', 'bool', {tab: 'text'}),
        textBackgroundColor: prop('textBackgroundColor', 'color', {tab: 'text'}),
        textBorder: prop('textBorder', 'bool', {tab: 'text'}),
        textPosition: prop('textPosition', 'select', {tab: 'text', options: ['top', 'middle', 'bottom']}),
        textAlign: prop('textAlign', 'select', {tab: 'text', options: ['left', 'center', 'right']}),
        levels: prop('levels', 'levels', {tab: 'inputs'}),
        labelsPosition: prop('labelsPosition', 'select', {options: ['left', 'right']}),
        levelFormat: prop('levelFormat', 'select', {options: ['values', 'percent']})
    };

    const LINE_PROPERTIES = [P.color, P.width, P.lineStyle];
    const TEXT_PROPERTIES = [P.text, P.textColor, P.fontSize, P.bold, P.italic];

    const LINE_DEFAULTS = Object.freeze({width: 2, lineStyle: 'solid'});
    const TEXT_DEFAULTS = Object.freeze({text: '', textColor: '', fontSize: 13, bold: false, italic: false});

    // ---- styles

    function fillColor(style, fallbackOpacity = 0.15) {
        const color = style.fillColor || style.color || '#60a5fa';
        return withAlpha(color, finite(style.fillOpacity) ? style.fillOpacity : fallbackOpacity);
    }

    function textColor(c) {
        return c.style.textColor || c.style.color || c.env.palette.text;
    }

    function font(style) {
        return Draw.font(style.fontSize || 13, style.bold ? 700 : 500, style.italic);
    }

    /** Line style for a drawing's main stroke, emphasised while it is selected or hovered. */
    function stroke(c, overrides = {}) {
        const width = (overrides.width ?? c.style.width ?? 1) + (c.hovered && !c.selected ? 0.5 : 0);
        return {color: overrides.color ?? c.style.color, width, lineStyle: overrides.lineStyle ?? c.style.lineStyle};
    }

    // ---- geometry

    function extended(c, a, b, left = c.style.extendLeft, right = c.style.extendRight) {
        return extendSegment(a, b, left, right, c.env.width, c.env.height);
    }

    /** A ray from a through b to far off-screen. */
    function ray(c, a, b) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const length = Math.hypot(dx, dy) || 1;
        const far = Math.max(c.env.width, c.env.height) * 4 + 2000;
        return {x: a.x + dx / length * far, y: a.y + dy / length * far};
    }

    function hitSegment(a, b, x, y, tolerance) {
        return a && b && segmentDistance({x, y}, a, b) <= tolerance;
    }

    function hitPolyline(points, x, y, tolerance, closed = false) {
        const valid = points.filter(Boolean);
        for (let index = 1; index < valid.length; index++) if (hitSegment(valid[index - 1], valid[index], x, y, tolerance)) return true;
        return closed && valid.length > 2 && hitSegment(valid[valid.length - 1], valid[0], x, y, tolerance);
    }

    function hitInside(points, x, y) {
        const valid = points.filter(Boolean);
        return valid.length > 2 && insidePolygon({x, y}, valid);
    }

    /** y on the line through a and b at x. */
    function lineY(a, b, x) {
        if (b.x === a.x) return a.y;
        return a.y + (x - a.x) * (b.y - a.y) / (b.x - a.x);
    }

    // ---- text

    /** Text drawn along a line (rotated with it), above, on or below it. */
    function lineText(ctx, c, a, b) {
        const text = c.style.text;
        if (!text || !a || !b) return;
        let angle = Math.atan2(b.y - a.y, b.x - a.x);
        if (angle > Math.PI / 2) angle -= Math.PI;
        if (angle < -Math.PI / 2) angle += Math.PI;
        const align = c.style.textAlign || 'center';
        const anchor = align === 'left' ? (a.x <= b.x ? a : b) : (align === 'right' ? (a.x <= b.x ? b : a) : {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2});
        const offset = {top: -8, middle: 0, bottom: 8}[c.style.textPosition || 'top'];
        ctx.save();
        ctx.translate(anchor.x, anchor.y);
        ctx.rotate(angle);
        ctx.font = font(c.style);
        ctx.fillStyle = textColor(c);
        ctx.textAlign = align === 'left' ? 'left' : (align === 'right' ? 'right' : 'center');
        ctx.textBaseline = offset < 0 ? 'bottom' : (offset > 0 ? 'top' : 'middle');
        if (offset === 0) {
            const width = ctx.measureText(text).width + 8;
            ctx.fillStyle = c.env.palette.labelBackground;
            const left = ctx.textAlign === 'center' ? -width / 2 : (ctx.textAlign === 'left' ? -4 : -width + 4);
            ctx.fillRect(left, -(c.style.fontSize || 13) * 0.7, width, (c.style.fontSize || 13) * 1.4);
            ctx.fillStyle = textColor(c);
        }
        String(text).split('\n').forEach((line, index, lines) => {
            const lineHeight = (c.style.fontSize || 13) * 1.25;
            const y = offset + (offset < 0 ? -(lines.length - 1 - index) * lineHeight : index * lineHeight);
            ctx.fillText(line, 0, y);
        });
        ctx.restore();
    }

    /** Price change, percent, bars, days and angle between two chart points. */
    function statsText(c, from, to, screenA, screenB) {
        const env = c.env;
        const change = to.price - from.price;
        const percent = from.price ? change / from.price * 100 : 0;
        const bars = (env.mapper.logical(to.time) ?? 0) - (env.mapper.logical(from.time) ?? 0);
        const parts = [`${env.format.price(change)} (${env.format.percent(percent)})`,
            `${env.format.bars(bars)}, ${env.format.days(to.time - from.time)}`];
        if (screenA && screenB) {
            const angle = Math.atan2(screenA.y - screenB.y, screenB.x - screenA.x) * 180 / Math.PI;
            parts.push(`${i18n.number(angle, 1)}°`);
        }
        return parts.join('\n');
    }

    function statsLabel(ctx, c, a, b) {
        const [from, to] = c.drawing.points;
        const text = statsText(c, from, to, a, b);
        const right = a.x <= b.x ? b : a;
        Draw.label(ctx, text, right.x + 10, right.y, {
            background: c.env.palette.labelBackground, color: c.env.palette.text, border: withAlpha(c.style.color, 0.6),
            size: 12, bounds: c.env
        });
    }

    function pointLabel(ctx, c, point, text, above = true, color) {
        if (!point || !text) return;
        Draw.label(ctx, text, point.x, point.y + (above ? -10 : 10), {
            align: 'center', baseline: above ? 'bottom' : 'top', size: 12, weight: 700,
            color: color || c.style.textColor || c.style.color, background: c.env.palette.labelBackground, padding: 3
        });
    }

    // ---- Fibonacci

    const FIB_COLORS = ['#787b86', '#f23645', '#ff9800', '#f6c344', '#089981', '#2962ff', '#7b61ff', '#536dfe', '#00acc1',
        '#26a69a', '#43a047', '#9c27b0', '#ab47bc'];

    function fibLevels(values, visible = values.map(() => true)) {
        return values.map((value, index) => ({value, color: FIB_COLORS[index % FIB_COLORS.length], visible: visible[index]}));
    }

    /** A price between two prices at a ratio, in log space when the price scale is logarithmic. */
    function interpolatePrice(c, start, end, ratio) {
        if (c.env.logScale && start > 0 && end > 0) return Math.exp(Math.log(start) + (Math.log(end) - Math.log(start)) * ratio);
        return start + (end - start) * ratio;
    }

    function levelText(c, level, price) {
        const value = c.style.levelFormat === 'percent' ? `${i18n.number(level.value * 100, 1)}%` : trimNumber(level.value);
        return c.style.showPrices === false ? value : `${value} (${c.env.format.price(price)})`;
    }

    function trimNumber(value) {
        return Number(value).toLocaleString(i18n.tag, {maximumFractionDigits: 3});
    }

    // ---- bar calculations

    /** Bars whose time lies between two times (inclusive), using the manager's loaded bars. */
    function barsBetween(c, fromTime, toTime) {
        const low = Math.min(fromTime, toTime);
        const high = Math.max(fromTime, toTime);
        return c.env.bars.filter(bar => bar.time >= low && bar.time <= high);
    }

    /** Volume by price for bars, split into up and down volume, with point of control and value area. */
    function volumeProfile(bars, rows = 24, valueArea = 0.7) {
        const usable = bars.filter(bar => finite(bar.volume) && bar.volume > 0 && finite(bar.high) && finite(bar.low));
        if (!usable.length) return null;
        const minimum = Math.min(...usable.map(bar => bar.low));
        const maximum = Math.max(...usable.map(bar => bar.high));
        if (!(maximum > minimum)) return null;
        const height = (maximum - minimum) / rows;
        const up = new Array(rows).fill(0);
        const down = new Array(rows).fill(0);
        let total = 0;
        usable.forEach(bar => {
            const rising = bar.close >= bar.open;
            total += bar.volume;
            if (bar.high <= bar.low) {
                const row = Math.min(rows - 1, Math.max(0, Math.floor((bar.low - minimum) / height)));
                (rising ? up : down)[row] += bar.volume;
                return;
            }
            const first = Math.min(rows - 1, Math.max(0, Math.floor((bar.low - minimum) / height)));
            const last = Math.min(rows - 1, Math.max(0, Math.floor((bar.high - minimum) / height)));
            for (let row = first; row <= last; row++) {
                const rowLow = minimum + row * height;
                const overlap = Math.min(bar.high, rowLow + height) - Math.max(bar.low, rowLow);
                if (overlap > 0) (rising ? up : down)[row] += bar.volume * overlap / (bar.high - bar.low);
            }
        });
        let mode = 0;
        for (let row = 1; row < rows; row++) if (up[row] + down[row] > up[mode] + down[mode]) mode = row;
        let lower = mode;
        let upper = mode;
        let included = up[mode] + down[mode];
        while (included < total * valueArea && (lower > 0 || upper < rows - 1)) {
            const below = lower > 0 ? up[lower - 1] + down[lower - 1] : -1;
            const above = upper < rows - 1 ? up[upper + 1] + down[upper + 1] : -1;
            if (above > below) { upper++; included += up[upper] + down[upper]; } else { lower--; included += up[lower] + down[lower]; }
        }
        const largest = Math.max(...up.map((value, row) => value + down[row]));
        return {
            rows: up.map((value, row) => ({low: minimum + row * height, high: minimum + (row + 1) * height, up: value, down: down[row],
                valueArea: row >= lower && row <= upper})),
            poc: minimum + (mode + 0.5) * height,
            vah: minimum + (upper + 1) * height,
            val: minimum + lower * height,
            largest,
            total
        };
    }

    /** Draws a volume profile as horizontal bars growing from x0 towards x1. */
    function renderProfile(ctx, c, profile, x0, x1, options = {}) {
        if (!profile) return;
        const span = x1 - x0;
        const upColor = options.upColor || withAlpha('#26a69a', 0.55);
        const downColor = options.downColor || withAlpha('#ef5350', 0.5);
        const outside = options.outsideAlpha ?? 0.45;
        profile.rows.forEach(row => {
            const top = c.env.y(row.high);
            const bottom = c.env.y(row.low);
            if (!finite(top) || !finite(bottom)) return;
            const height = Math.max(1, Math.abs(bottom - top) - 1);
            const y = Math.min(top, bottom);
            const upWidth = span * row.up / (profile.largest || 1);
            const downWidth = span * row.down / (profile.largest || 1);
            ctx.globalAlpha = row.valueArea ? 1 : outside;
            ctx.fillStyle = upColor;
            ctx.fillRect(x0, y, upWidth, height);
            ctx.fillStyle = downColor;
            ctx.fillRect(x0 + upWidth, y, downWidth, height);
            ctx.globalAlpha = 1;
        });
        const pocY = c.env.y(profile.poc);
        if (finite(pocY)) Draw.line(ctx, {x: x0, y: pocY}, {x: options.pocEnd ?? x1, y: pocY}, {color: options.pocColor || '#ffbd59', width: 2});
        if (options.valueAreaLines !== false) {
            [profile.vah, profile.val].forEach(price => {
                const y = c.env.y(price);
                if (finite(y)) Draw.line(ctx, {x: x0, y}, {x: x1, y}, {color: withAlpha('#b7f34a', 0.7), width: 1, lineStyle: 'dashed'});
            });
        }
    }

    /** Volume-weighted average price from the bar at or after the anchor, with standard deviation. */
    function anchoredVwap(bars, anchorTime) {
        const result = [];
        let sumVolume = 0;
        let sumPrice = 0;
        let sumSquares = 0;
        bars.forEach(bar => {
            if (bar.time < anchorTime || !finite(bar.volume)) return;
            const price = (bar.high + bar.low + bar.close) / 3;
            sumVolume += bar.volume;
            sumPrice += price * bar.volume;
            sumSquares += price * price * bar.volume;
            if (sumVolume <= 0) return;
            const mean = sumPrice / sumVolume;
            result.push({time: bar.time, vwap: mean, deviation: Math.sqrt(Math.max(0, sumSquares / sumVolume - mean * mean))});
        });
        return result;
    }

    function noVolumeLabel(ctx, c, point) {
        Draw.label(ctx, i18n.t('draw.label.noVolume'), point.x + 8, point.y, {
            background: c.env.palette.labelBackground, color: c.env.palette.text, size: 11, bounds: c.env
        });
    }

    window.StockWatchDrawingKit = Object.freeze({
        P, LINE_PROPERTIES, TEXT_PROPERTIES, LINE_DEFAULTS, TEXT_DEFAULTS, FIB_COLORS,
        finite, fillColor, textColor, font, stroke, extended, ray, hitSegment, hitPolyline, hitInside, lineY,
        lineText, statsText, statsLabel, pointLabel, fibLevels, interpolatePrice, levelText, trimNumber,
        barsBetween, volumeProfile, renderProfile, anchoredVwap, noVolumeLabel
    });
})();
