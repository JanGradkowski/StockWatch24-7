(() => {
    'use strict';

    // Fibonacci and Gann tools.
    const {registerTool, Draw, Geometry} = window.StockWatchDrawing;
    const K = window.StockWatchDrawingKit;
    const {P} = K;
    const {withAlpha} = Geometry;
    const i18n = window.StockWatchI18n;
    const finite = K.finite;

    // The original retracement levels: 0 at the first point, 1 at the second, with extensions both ways.
    const RETRACEMENT = K.fibLevels([-0.618, -0.272, 0, 0.236, 0.382, 0.5, 0.618, 0.786, 1, 1.272, 1.618, 2, 2.618]);
    RETRACEMENT.forEach((level, index) => {
        level.color = ['#9c27b0', '#ab47bc', '#787b86', '#f23645', '#ff9800', '#f6c344', '#089981', '#2962ff', '#7b61ff', '#536dfe',
            '#00acc1', '#26a69a', '#43a047'][index];
    });
    const EXTENSION = K.fibLevels([0, 0.236, 0.382, 0.5, 0.618, 0.786, 1, 1.272, 1.414, 1.618, 2, 2.618, 3.618, 4.236],
        [true, true, true, true, true, true, true, false, false, true, false, true, false, false]);
    const CHANNEL = K.fibLevels([0, 0.236, 0.382, 0.5, 0.618, 0.786, 1, 1.618, 2.618, 3.618, 4.236],
        [true, true, true, true, true, true, true, true, true, false, false]);
    const ARCS = K.fibLevels([0.236, 0.382, 0.5, 0.618, 0.786, 1, 1.618, 2.618]);
    const FAN = K.fibLevels([0, 0.25, 0.382, 0.5, 0.618, 0.75, 1]);
    const TIME_ZONES = [0, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233];
    const TIME_RATIOS = K.fibLevels([0, 0.382, 0.5, 0.618, 1, 1.382, 1.618, 2, 2.382, 2.618, 3, 3.618, 4.236],
        [true, true, true, true, true, true, true, true, false, true, false, false, false]);

    const visibleLevels = c => (c.style.levels || []).filter(level => level.visible !== false && finite(Number(level.value)));
    const fibProperties = [P.levels, P.width, P.extendLeft, P.extendRight, P.reverse, P.showPrices, P.levelFormat, P.labelsPosition, P.background,
        {key: 'showTrendLine', type: 'bool', tab: 'style'}];

    /** Horizontal Fibonacci levels between two x positions, with fills between consecutive levels. */
    function drawLevels(ctx, c, rows, left, right) {
        const ordered = [...rows].sort((a, b) => a.y - b.y);
        if (c.style.background !== false) {
            for (let index = 1; index < ordered.length; index++) {
                Draw.rect(ctx, left, ordered[index - 1].y, right - left, ordered[index].y - ordered[index - 1].y, null,
                    withAlpha(ordered[index].level.color, c.selected ? 0.1 : 0.06));
            }
        }
        rows.forEach(row => {
            const [start, end] = K.extended(c, {x: left, y: row.y}, {x: right, y: row.y});
            Draw.line(ctx, start, end, {color: row.level.color, width: c.style.width || 1});
            const labelRight = c.style.labelsPosition !== 'left';
            Draw.label(ctx, K.levelText(c, row.level, row.price), labelRight ? Math.min(end.x, c.env.width) - 4 : Math.max(start.x, 0) + 4, row.y - 1, {
                align: labelRight ? 'right' : 'left', baseline: 'bottom', color: row.level.color, size: 11, weight: 600
            });
        });
    }

    registerTool({
        type: 'fibRetracement',
        group: 'fibonacci',
        shortcut: 'alt+f',
        points: 2,
        keepDefaultColor: true,
        defaults: {color: '#787b86', width: 1, levels: RETRACEMENT, extendLeft: false, extendRight: false, reverse: false, showPrices: true,
            levelFormat: 'values', labelsPosition: 'right', background: true, showTrendLine: true},
        properties: fibProperties,
        rows(c) {
            const [from, to] = c.drawing.points;
            const start = c.style.reverse ? to.price : from.price;
            const end = c.style.reverse ? from.price : to.price;
            return visibleLevels(c).map(level => {
                const price = K.interpolatePrice(c, start, end, Number(level.value));
                return {level, price, y: c.env.y(price)};
            }).filter(row => finite(row.y));
        },
        render(ctx, c) {
            const [a, b] = c.points;
            const left = Math.min(a.x, b.x);
            const right = Math.max(a.x, b.x);
            drawLevels(ctx, c, this.rows(c), left, right);
            if (c.style.showTrendLine !== false) Draw.line(ctx, a, b, {color: c.selected ? c.env.palette.selection : withAlpha('#b4beb8', 0.6), width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) {
            const [a, b] = c.points;
            const [left, right] = K.extended(c, {x: Math.min(a.x, b.x), y: 0}, {x: Math.max(a.x, b.x), y: 0}).map(point => point.x);
            if (K.hitSegment(a, b, x, y, tolerance)) return {part: 'move'};
            if (x < left - tolerance || x > right + tolerance) return null;
            return this.rows(c).some(row => Math.abs(row.y - y) <= tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'fibExtension',
        group: 'fibonacci',
        points: 3,
        keepDefaultColor: true,
        defaults: {color: '#787b86', width: 1, levels: EXTENSION, extendLeft: false, extendRight: false, reverse: false, showPrices: true,
            levelFormat: 'values', labelsPosition: 'right', background: true, showTrendLine: true},
        properties: fibProperties,
        rows(c) {
            const [a, b, third] = c.drawing.points;
            const move = b.price - a.price;
            return visibleLevels(c).map(level => {
                const ratio = Number(level.value);
                const price = c.env.logScale && a.price > 0 && b.price > 0 && third.price > 0
                    ? third.price * Math.pow(b.price / a.price, ratio)
                    : third.price + move * ratio * (c.style.reverse ? -1 : 1);
                return {level, price, y: c.env.y(price)};
            }).filter(row => finite(row.y));
        },
        render(ctx, c) {
            const [a, b, third] = c.points;
            const left = third.x;
            const right = Math.max(third.x + Math.abs(b.x - a.x), Math.max(a.x, b.x, third.x));
            drawLevels(ctx, c, this.rows(c), left, right);
            if (c.style.showTrendLine !== false) Draw.polyline(ctx, [a, b, third], {color: withAlpha('#b4beb8', 0.6), width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) {
            const [a, b, third] = c.points;
            if (K.hitPolyline([a, b, third], x, y, tolerance)) return {part: 'move'};
            const right = Math.max(third.x + Math.abs(b.x - a.x), Math.max(a.x, b.x, third.x));
            if (x < third.x - tolerance || (!c.style.extendRight && x > right + tolerance)) return null;
            return this.rows(c).some(row => Math.abs(row.y - y) <= tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'fibChannel',
        group: 'fibonacci',
        points: 3,
        keepDefaultColor: true,
        defaults: {color: '#787b86', width: 1, levels: CHANNEL, extendLeft: false, extendRight: false, showPrices: false, levelFormat: 'values',
            labelsPosition: 'right', background: true},
        properties: [P.levels, P.width, P.extendLeft, P.extendRight, P.levelFormat, P.background],
        render(ctx, c) {
            const [a, b, third] = c.points;
            const offset = third.y - K.lineY(a, b, third.x);
            const rows = visibleLevels(c).map(level => ({level, a: {x: a.x, y: a.y + offset * level.value}, b: {x: b.x, y: b.y + offset * level.value}}));
            if (c.style.background !== false) {
                const sorted = [...rows].sort((first, second) => first.level.value - second.level.value);
                for (let index = 1; index < sorted.length; index++) {
                    Draw.fill(ctx, [sorted[index - 1].a, sorted[index - 1].b, sorted[index].b, sorted[index].a], withAlpha(sorted[index].level.color, 0.06));
                }
            }
            rows.forEach(row => {
                const [start, end] = K.extended(c, row.a, row.b);
                Draw.line(ctx, start, end, {color: row.level.color, width: c.style.width || 1});
                Draw.label(ctx, c.style.levelFormat === 'percent' ? `${i18n.number(row.level.value * 100, 1)}%` : K.trimNumber(row.level.value),
                    end.x - 4, end.y - 2, {align: 'right', baseline: 'bottom', color: row.level.color, size: 11});
            });
        },
        handles(c) {
            const [a, b, third] = c.points;
            const offset = third.y - K.lineY(a, b, third.x);
            return [a, b, {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 + offset}];
        },
        hitTest(c, x, y, tolerance) {
            const [a, b, third] = c.points;
            const offset = third.y - K.lineY(a, b, third.x);
            return visibleLevels(c).some(level => K.hitSegment(...K.extended(c, {x: a.x, y: a.y + offset * level.value},
                {x: b.x, y: b.y + offset * level.value}), x, y, tolerance)) ? {part: 'move'} : null;
        }
    });

    function verticalTimeLines(ctx, c, lines) {
        lines.forEach(line => {
            if (!finite(line.x)) return;
            Draw.line(ctx, {x: line.x, y: 0}, {x: line.x, y: c.env.height}, {color: line.color, width: c.style.width || 1});
            Draw.label(ctx, line.text, line.x + 3, 6, {baseline: 'top', color: line.color, size: 11});
        });
    }

    registerTool({
        type: 'fibTimeZone',
        group: 'fibonacci',
        points: 2,
        constrainShift: false,
        keepDefaultColor: true,
        defaults: {color: '#2962ff', width: 1},
        properties: [P.color, P.width],
        lines(c) {
            const [a, b] = c.drawing.points;
            const start = c.env.mapper.logical(a.time);
            const unit = c.env.mapper.logical(b.time) - start;
            if (!finite(unit) || Math.abs(unit) < 0.5) return [];
            return TIME_ZONES.map((zone, index) => ({
                x: c.env.x(c.env.mapper.seconds(start + unit * zone)),
                text: String(zone),
                color: K.FIB_COLORS[index % K.FIB_COLORS.length]
            })).filter(line => finite(line.x) && line.x <= c.env.width + 20);
        },
        render(ctx, c) { verticalTimeLines(ctx, c, this.lines(c)); },
        hitTest(c, x, y, tolerance) { return this.lines(c).some(line => Math.abs(line.x - x) <= tolerance) ? {part: 'move', cursor: 'ew-resize'} : null; }
    });

    registerTool({
        type: 'trendBasedFibTime',
        group: 'fibonacci',
        points: 3,
        keepDefaultColor: true,
        defaults: {color: '#2962ff', width: 1, levels: TIME_RATIOS},
        properties: [P.levels, P.width],
        lines(c) {
            const [a, b, third] = c.drawing.points;
            const unit = c.env.mapper.logical(b.time) - c.env.mapper.logical(a.time);
            const start = c.env.mapper.logical(third.time);
            if (!finite(unit) || !finite(start)) return [];
            return visibleLevels(c).map(level => ({
                x: c.env.x(c.env.mapper.seconds(start + unit * Number(level.value))),
                text: K.trimNumber(level.value),
                color: level.color
            })).filter(line => finite(line.x));
        },
        render(ctx, c) {
            verticalTimeLines(ctx, c, this.lines(c));
            Draw.polyline(ctx, c.points, {color: withAlpha('#b4beb8', 0.6), width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) {
            if (K.hitPolyline(c.points, x, y, tolerance)) return {part: 'move'};
            return this.lines(c).some(line => Math.abs(line.x - x) <= tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'fibSpeedResistanceFan',
        group: 'fibonacci',
        points: 2,
        keepDefaultColor: true,
        defaults: {color: '#2962ff', width: 1, levels: FAN, showGrid: true, background: true},
        properties: [P.levels, P.width, {key: 'showGrid', type: 'bool', tab: 'style'}, P.background],
        render(ctx, c) {
            const [a, b] = c.points;
            const levels = visibleLevels(c);
            if (c.style.showGrid) {
                levels.forEach(level => {
                    const x = a.x + (b.x - a.x) * level.value;
                    const y = a.y + (b.y - a.y) * level.value;
                    Draw.line(ctx, {x, y: a.y}, {x, y: b.y}, {color: withAlpha(level.color, 0.4), width: 1});
                    Draw.line(ctx, {x: a.x, y}, {x: b.x, y}, {color: withAlpha(level.color, 0.4), width: 1});
                });
            }
            levels.forEach(level => {
                const priceTarget = {x: b.x, y: a.y + (b.y - a.y) * level.value};
                const timeTarget = {x: a.x + (b.x - a.x) * level.value, y: b.y};
                Draw.line(ctx, a, K.ray(c, a, priceTarget), {color: level.color, width: c.style.width});
                if (level.value !== 1 && level.value !== 0) Draw.line(ctx, a, K.ray(c, a, timeTarget), {color: level.color, width: c.style.width});
            });
        },
        hitTest(c, x, y, tolerance) {
            const [a, b] = c.points;
            return visibleLevels(c).some(level => K.hitSegment(a, K.ray(c, a, {x: b.x, y: a.y + (b.y - a.y) * level.value}), x, y, tolerance)
                || K.hitSegment(a, K.ray(c, a, {x: a.x + (b.x - a.x) * level.value, y: b.y}), x, y, tolerance)) ? {part: 'move'} : null;
        }
    });

    function arcRows(c) {
        const [a, b] = c.points;
        const radius = Math.hypot(b.x - a.x, b.y - a.y);
        return visibleLevels(c).map(level => ({level, radius: radius * level.value}));
    }

    registerTool({
        type: 'fibSpeedResistanceArcs',
        group: 'fibonacci',
        points: 2,
        keepDefaultColor: true,
        defaults: {color: '#2962ff', width: 1, levels: ARCS, fullCircles: false},
        properties: [P.levels, P.width, {key: 'fullCircles', type: 'bool', tab: 'style'}],
        render(ctx, c) {
            const [a, b] = c.points;
            const upward = b.y < a.y;
            arcRows(c).forEach(row => {
                ctx.save();
                Draw.stroke(ctx, {color: row.level.color, width: c.style.width});
                ctx.beginPath();
                if (c.style.fullCircles) ctx.arc(b.x, b.y, row.radius, 0, Math.PI * 2);
                else ctx.arc(b.x, b.y, row.radius, upward ? 0 : Math.PI, upward ? Math.PI : 0);
                ctx.stroke();
                ctx.restore();
                Draw.label(ctx, K.trimNumber(row.level.value), b.x, b.y + (upward ? row.radius : -row.radius), {align: 'center', color: row.level.color, size: 10});
            });
            Draw.line(ctx, a, b, {color: withAlpha('#b4beb8', 0.6), width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) {
            const b = c.points[1];
            const fromCenter = Math.hypot(x - b.x, y - b.y);
            return arcRows(c).some(row => Math.abs(fromCenter - row.radius) <= tolerance) || K.hitSegment(c.points[0], b, x, y, tolerance)
                ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'fibCircles',
        group: 'fibonacci',
        points: 2,
        keepDefaultColor: true,
        defaults: {color: '#2962ff', width: 1, levels: ARCS},
        properties: [P.levels, P.width],
        render(ctx, c) {
            const [a, b] = c.points;
            const center = {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2};
            const radius = Math.hypot(b.x - a.x, b.y - a.y) / 2;
            visibleLevels(c).forEach(level => Draw.circle(ctx, center, radius * level.value, {color: level.color, width: c.style.width}));
            Draw.line(ctx, a, b, {color: withAlpha('#b4beb8', 0.6), width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) {
            const [a, b] = c.points;
            const center = {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2};
            const radius = Math.hypot(b.x - a.x, b.y - a.y) / 2;
            const fromCenter = Math.hypot(x - center.x, y - center.y);
            return visibleLevels(c).some(level => Math.abs(fromCenter - radius * level.value) <= tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'fibSpiral',
        group: 'fibonacci',
        points: 2,
        defaults: {width: 1, lineStyle: 'solid', counterclockwise: false},
        properties: [...K.LINE_PROPERTIES, {key: 'counterclockwise', type: 'bool', tab: 'style'}],
        curve(c) {
            const [a, b] = c.points;
            const start = Math.hypot(b.x - a.x, b.y - a.y);
            if (start < 2) return [];
            const growth = Math.log((1 + Math.sqrt(5)) / 2) / (Math.PI / 2);
            const base = Math.atan2(b.y - a.y, b.x - a.x);
            const direction = c.style.counterclockwise ? -1 : 1;
            const points = [];
            for (let theta = -6 * Math.PI; theta <= 2.5 * Math.PI; theta += 0.06) {
                const radius = start * Math.exp(growth * theta);
                if (radius > Math.max(c.env.width, c.env.height) * 3) break;
                points.push({x: a.x + radius * Math.cos(base + direction * theta), y: a.y + radius * Math.sin(base + direction * theta)});
            }
            return points;
        },
        render(ctx, c) {
            Draw.polyline(ctx, this.curve(c), K.stroke(c));
            Draw.line(ctx, c.points[0], c.points[1], {color: c.style.color, width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) { return K.hitPolyline(this.curve(c), x, y, tolerance) ? {part: 'move'} : null; }
    });

    registerTool({
        type: 'fibWedge',
        group: 'fibonacci',
        points: 3,
        keepDefaultColor: true,
        defaults: {color: '#2962ff', width: 1, levels: ARCS, background: true},
        properties: [P.levels, P.width, P.background],
        render(ctx, c) {
            const [center, b, third] = c.points;
            const radius = Math.hypot(b.x - center.x, b.y - center.y);
            const first = Math.atan2(b.y - center.y, b.x - center.x);
            const second = Math.atan2(third.y - center.y, third.x - center.x);
            Draw.line(ctx, center, b, {color: c.style.color, width: c.style.width});
            Draw.line(ctx, center, {x: center.x + radius * Math.cos(second), y: center.y + radius * Math.sin(second)}, {color: c.style.color, width: c.style.width});
            visibleLevels(c).forEach(level => {
                ctx.save();
                Draw.stroke(ctx, {color: level.color, width: c.style.width});
                ctx.beginPath();
                ctx.arc(center.x, center.y, radius * level.value, first, second, ((second - first + Math.PI * 2) % (Math.PI * 2)) > Math.PI);
                ctx.stroke();
                ctx.restore();
            });
        },
        hitTest(c, x, y, tolerance) {
            const [center, b] = c.points;
            const radius = Math.hypot(b.x - center.x, b.y - center.y);
            const fromCenter = Math.hypot(x - center.x, y - center.y);
            return visibleLevels(c).some(level => Math.abs(fromCenter - radius * level.value) <= tolerance) || K.hitSegment(center, b, x, y, tolerance)
                ? {part: 'move'} : null;
        }
    });

    // ---- Gann

    const GANN_LEVELS = [0.25, 0.382, 0.5, 0.618, 0.75];

    registerTool({
        type: 'gannBox',
        group: 'gann',
        points: 2,
        keepDefaultColor: true,
        defaults: {color: '#ff9800', width: 1, background: true, showAngles: true},
        properties: [P.color, P.width, P.background, {key: 'showAngles', type: 'bool', tab: 'style'}],
        render(ctx, c) {
            const [a, b] = c.points;
            const left = Math.min(a.x, b.x);
            const top = Math.min(a.y, b.y);
            const width = Math.abs(b.x - a.x);
            const height = Math.abs(b.y - a.y);
            Draw.rect(ctx, left, top, width, height, {color: c.style.color, width: c.style.width}, c.style.background ? withAlpha(c.style.color, 0.05) : null);
            GANN_LEVELS.forEach((level, index) => {
                const color = withAlpha(K.FIB_COLORS[(index + 2) % K.FIB_COLORS.length], 0.8);
                Draw.line(ctx, {x: left + width * level, y: top}, {x: left + width * level, y: top + height}, {color, width: 1});
                Draw.line(ctx, {x: left, y: top + height * level}, {x: left + width, y: top + height * level}, {color, width: 1});
                Draw.label(ctx, K.trimNumber(level), left + width * level, top - 2, {align: 'center', baseline: 'bottom', color, size: 10});
                Draw.label(ctx, K.trimNumber(level), left - 3, top + height * level, {align: 'right', color, size: 10});
            });
            if (c.style.showAngles) {
                Draw.line(ctx, a, b, {color: c.style.color, width: 1, lineStyle: 'dashed'});
                Draw.line(ctx, {x: a.x, y: b.y}, {x: b.x, y: a.y}, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            }
        },
        hitTest(c, x, y, tolerance) {
            const [a, b] = c.points;
            const corners = [a, {x: b.x, y: a.y}, b, {x: a.x, y: b.y}];
            return K.hitPolyline(corners, x, y, tolerance, true) || (c.style.background && K.hitInside(corners, x, y)) ? {part: 'move'} : null;
        }
    });

    function gannSquare(type, fixed) {
        registerTool({
            type,
            group: 'gann',
            points: 2,
            keepDefaultColor: true,
            defaults: {color: '#26a69a', width: 1, background: true, divisions: 8, showFans: true, showArcs: !fixed},
            properties: [P.color, P.width, P.background, {key: 'divisions', type: 'number', min: 2, max: 16, step: 1, tab: 'inputs'},
                {key: 'showFans', type: 'bool', tab: 'style'}, {key: 'showArcs', type: 'bool', tab: 'style'}],
            /** The fixed square keeps equal width and height on screen, whatever the second point's price. */
            corner(c) {
                const [a, raw] = c.points;
                if (!fixed) return raw;
                const size = Math.max(Math.abs(raw.x - a.x), Math.abs(raw.y - a.y));
                return {x: a.x + Math.sign(raw.x - a.x || 1) * size, y: a.y + Math.sign(raw.y - a.y || 1) * size};
            },
            handles(c) { return [c.points[0], this.corner(c)]; },
            render(ctx, c) {
                const a = c.points[0];
                const b = this.corner(c);
                const left = Math.min(a.x, b.x);
                const top = Math.min(a.y, b.y);
                const width = Math.abs(b.x - a.x);
                const height = Math.abs(b.y - a.y);
                const divisions = Math.max(2, Math.round(c.style.divisions || 8));
                Draw.rect(ctx, left, top, width, height, {color: c.style.color, width: c.style.width}, c.style.background ? withAlpha(c.style.color, 0.05) : null);
                for (let step = 1; step < divisions; step++) {
                    const fraction = step / divisions;
                    Draw.line(ctx, {x: left + width * fraction, y: top}, {x: left + width * fraction, y: top + height}, {color: withAlpha(c.style.color, 0.35), width: 1});
                    Draw.line(ctx, {x: left, y: top + height * fraction}, {x: left + width, y: top + height * fraction}, {color: withAlpha(c.style.color, 0.35), width: 1});
                }
                if (c.style.showFans) {
                    [[1, 1], [1, 2], [2, 1], [1, 3], [3, 1], [1, 4], [4, 1], [1, 8], [8, 1]].forEach(([time, price]) => {
                        const target = {x: a.x + (b.x - a.x) * Math.min(1, time / price), y: a.y + (b.y - a.y) * Math.min(1, price / time)};
                        Draw.line(ctx, a, target, {color: withAlpha(c.style.color, time === price ? 0.95 : 0.6), width: 1});
                    });
                }
                if (c.style.showArcs) {
                    [0.25, 0.5, 0.75, 1].forEach(fraction => {
                        ctx.save();
                        ctx.beginPath();
                        ctx.rect(left, top, width, height);
                        ctx.clip();
                        Draw.ellipse(ctx, a, width * fraction, height * fraction, 0, {color: withAlpha(c.style.color, 0.5), width: 1});
                        ctx.restore();
                    });
                }
            },
            hitTest(c, x, y, tolerance) {
                const a = c.points[0];
                const b = this.corner(c);
                const corners = [a, {x: b.x, y: a.y}, b, {x: a.x, y: b.y}];
                return K.hitPolyline(corners, x, y, tolerance, true) || (c.style.background && K.hitInside(corners, x, y)) ? {part: 'move'} : null;
            }
        });
    }

    gannSquare('gannSquareFixed', true);
    gannSquare('gannSquare', false);

    registerTool({
        type: 'gannFan',
        group: 'gann',
        points: 2,
        keepDefaultColor: true,
        defaults: {color: '#26a69a', width: 1, showLabels: true, background: true},
        properties: [P.color, P.width, P.showLabels, P.background],
        ratios: [[1, 8], [1, 4], [1, 3], [1, 2], [1, 1], [2, 1], [3, 1], [4, 1], [8, 1]],
        /** The 1x1 line runs through the second point; a price:time line rises price units per time units of it. */
        rays(c) {
            const [a, b] = c.points;
            const dx = b.x - a.x;
            const dy = b.y - a.y;
            return this.ratios.map(([price, time]) => ({
                ratio: `${price}x${time}`,
                end: K.ray(c, a, {x: a.x + dx * time, y: a.y + dy * price}),
                one: price === time
            }));
        },
        render(ctx, c) {
            const rays = this.rays(c);
            const a = c.points[0];
            if (c.style.background) {
                for (let index = 1; index < rays.length; index++) {
                    Draw.fill(ctx, [a, rays[index - 1].end, rays[index].end], withAlpha(K.FIB_COLORS[index % K.FIB_COLORS.length], 0.05));
                }
            }
            rays.forEach(ray => {
                Draw.line(ctx, a, ray.end, {color: c.style.color, width: ray.one ? c.style.width + 1 : c.style.width});
                if (c.style.showLabels) {
                    const labelPoint = {x: a.x + (ray.end.x - a.x) * 0.06, y: a.y + (ray.end.y - a.y) * 0.06};
                    Draw.label(ctx, ray.ratio, labelPoint.x, labelPoint.y, {color: c.style.color, size: 10, align: 'center'});
                }
            });
        },
        hitTest(c, x, y, tolerance) {
            const a = c.points[0];
            return this.rays(c).some(ray => K.hitSegment(a, ray.end, x, y, tolerance)) ? {part: 'move'} : null;
        }
    });
})();
