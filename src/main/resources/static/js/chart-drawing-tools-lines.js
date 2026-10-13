(() => {
    'use strict';

    // Lines, channels, pitchforks and cycle tools.
    const {registerTool, Draw, Geometry} = window.StockWatchDrawing;
    const K = window.StockWatchDrawingKit;
    const {P} = K;
    const {withAlpha, segmentDistance} = Geometry;
    const finite = K.finite;

    function arrows(ctx, c, a, b) {
        if (c.style.arrowEnd) Draw.arrowHead(ctx, a, b, c.style.color, 8 + c.style.width * 2);
        if (c.style.arrowStart) Draw.arrowHead(ctx, b, a, c.style.color, 8 + c.style.width * 2);
    }

    function lineTool(type, overrides) {
        const base = {
            type,
            group: 'lines',
            points: 2,
            defaults: {...K.LINE_DEFAULTS, extendLeft: false, extendRight: false, arrowStart: false, arrowEnd: false, showStats: false,
                ...K.TEXT_DEFAULTS, textPosition: 'top', textAlign: 'center'},
            properties: [...K.LINE_PROPERTIES, P.extendLeft, P.extendRight, P.arrowStart, P.arrowEnd, P.showStats,
                ...K.TEXT_PROPERTIES, P.textPosition, P.textAlign],
            render(ctx, c) {
                const [a, b] = c.points;
                const [start, end] = K.extended(c, a, b);
                Draw.line(ctx, start, end, K.stroke(c));
                arrows(ctx, c, a, b);
                K.lineText(ctx, c, a, b);
                if (c.style.showStats || c.selected && overrides?.statsWhenSelected) K.statsLabel(ctx, c, a, b);
            },
            hitTest(c, x, y, tolerance) {
                const [start, end] = K.extended(c, c.points[0], c.points[1]);
                return K.hitSegment(start, end, x, y, tolerance) ? {part: 'move'} : null;
            }
        };
        registerTool({...base, ...overrides});
    }

    lineTool('trendLine', {shortcut: 'alt+t'});

    lineTool('ray', {
        defaults: {...K.LINE_DEFAULTS, showStats: false, ...K.TEXT_DEFAULTS, textPosition: 'top', textAlign: 'center'},
        properties: [...K.LINE_PROPERTIES, P.showStats, ...K.TEXT_PROPERTIES, P.textPosition],
        render(ctx, c) {
            const [a, b] = c.points;
            Draw.line(ctx, a, K.ray(c, a, b), K.stroke(c));
            K.lineText(ctx, c, a, b);
            if (c.style.showStats) K.statsLabel(ctx, c, a, b);
        },
        hitTest(c, x, y, tolerance) {
            const [a, b] = c.points;
            return K.hitSegment(a, K.ray(c, a, b), x, y, tolerance) ? {part: 'move'} : null;
        }
    });

    lineTool('infoLine', {
        defaults: {...K.LINE_DEFAULTS, extendLeft: false, extendRight: false, arrowStart: false, arrowEnd: false, showStats: true,
            ...K.TEXT_DEFAULTS, textPosition: 'top', textAlign: 'center'}
    });

    lineTool('extendedLine', {
        defaults: {...K.LINE_DEFAULTS, extendLeft: true, extendRight: true, arrowStart: false, arrowEnd: false, showStats: false,
            ...K.TEXT_DEFAULTS, textPosition: 'top', textAlign: 'center'}
    });

    lineTool('arrowLine', {
        group: 'lines',
        defaults: {...K.LINE_DEFAULTS, extendLeft: false, extendRight: false, arrowStart: false, arrowEnd: true, showStats: false,
            ...K.TEXT_DEFAULTS, textPosition: 'top', textAlign: 'center'}
    });

    registerTool({
        type: 'trendAngle',
        group: 'lines',
        points: 2,
        defaults: {...K.LINE_DEFAULTS, extendRight: false},
        properties: [...K.LINE_PROPERTIES, P.extendRight],
        render(ctx, c) {
            const [a, b] = c.points;
            const [start, end] = K.extended(c, a, b, false, c.style.extendRight);
            Draw.line(ctx, start, end, K.stroke(c));
            const radius = Math.min(60, Math.max(24, Math.hypot(b.x - a.x, b.y - a.y) / 3));
            Draw.line(ctx, a, {x: a.x + radius + 14, y: a.y}, {color: c.style.color, width: 1, lineStyle: 'dotted'});
            const angle = Math.atan2(b.y - a.y, b.x - a.x);
            ctx.save();
            Draw.stroke(ctx, {color: c.style.color, width: 1});
            ctx.beginPath();
            ctx.arc(a.x, a.y, radius, Math.min(0, angle), Math.max(0, angle));
            ctx.stroke();
            ctx.restore();
            const degrees = -angle * 180 / Math.PI;
            Draw.label(ctx, `${window.StockWatchI18n.number(degrees, 1)}°`, a.x + radius + 6, a.y + (degrees > 0 ? -10 : 10),
                {color: c.style.color, size: 12, weight: 700});
        },
        hitTest(c, x, y, tolerance) {
            const [start, end] = K.extended(c, c.points[0], c.points[1], false, c.style.extendRight);
            return K.hitSegment(start, end, x, y, tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'horizontalLine',
        group: 'lines',
        shortcut: 'alt+h',
        points: 1,
        placement: 'single',
        axisLabels: 'always',
        defaults: {...K.LINE_DEFAULTS, ...K.TEXT_DEFAULTS, textPosition: 'top', textAlign: 'right'},
        properties: [...K.LINE_PROPERTIES, ...K.TEXT_PROPERTIES, P.textPosition, P.textAlign],
        render(ctx, c) {
            const y = c.points[0].y;
            const a = {x: 0, y};
            const b = {x: c.env.width, y};
            Draw.line(ctx, a, b, K.stroke(c));
            K.lineText(ctx, c, {x: 8, y}, {x: c.env.width - 8, y});
        },
        handles: c => [{x: Math.max(c.points[0].x, 12), y: c.points[0].y}],
        movePoint(drawing, index, point) { drawing.points[0] = {time: drawing.points[0].time, price: point.price}; },
        hitTest(c, x, y, tolerance) { return Math.abs(y - c.points[0].y) <= tolerance ? {part: 'move', cursor: 'ns-resize'} : null; }
    });

    registerTool({
        type: 'horizontalRay',
        group: 'lines',
        shortcut: 'alt+j',
        points: 1,
        placement: 'single',
        axisLabels: 'always',
        defaults: {...K.LINE_DEFAULTS, ...K.TEXT_DEFAULTS, textPosition: 'top', textAlign: 'center'},
        properties: [...K.LINE_PROPERTIES, ...K.TEXT_PROPERTIES, P.textPosition, P.textAlign],
        render(ctx, c) {
            const a = c.points[0];
            const b = {x: c.env.width, y: a.y};
            Draw.line(ctx, a, b, K.stroke(c));
            K.lineText(ctx, c, a, b);
        },
        hitTest(c, x, y, tolerance) { return x >= c.points[0].x - tolerance && Math.abs(y - c.points[0].y) <= tolerance ? {part: 'move'} : null; }
    });

    registerTool({
        type: 'verticalLine',
        group: 'lines',
        shortcut: 'alt+v',
        points: 1,
        placement: 'single',
        axisLabels: 'none',
        axisTimes: drawing => [drawing.points[0].time],
        defaults: {...K.LINE_DEFAULTS, ...K.TEXT_DEFAULTS},
        properties: [...K.LINE_PROPERTIES, ...K.TEXT_PROPERTIES],
        render(ctx, c) {
            const x = c.points[0].x;
            Draw.line(ctx, {x, y: 0}, {x, y: c.env.height}, K.stroke(c));
            if (c.style.text) {
                Draw.label(ctx, c.style.text, x + 6, 10, {baseline: 'top', color: K.textColor(c), size: c.style.fontSize, weight: c.style.bold ? 700 : 500});
            }
            if (c.selected) Draw.label(ctx, c.env.format.date(c.drawing.points[0].time), x, c.env.height - 4,
                {align: 'center', baseline: 'bottom', background: c.style.color, color: Geometry.contrastText(c.style.color), size: 11});
        },
        handles: c => [{x: c.points[0].x, y: Math.min(c.env.height - 12, Math.max(12, c.points[0].y))}],
        movePoint(drawing, index, point) { drawing.points[0] = {time: point.time, price: drawing.points[0].price}; },
        hitTest(c, x, y, tolerance) { return Math.abs(x - c.points[0].x) <= tolerance ? {part: 'move', cursor: 'ew-resize'} : null; }
    });

    registerTool({
        type: 'crossLine',
        group: 'lines',
        shortcut: 'alt+c',
        points: 1,
        placement: 'single',
        axisLabels: 'always',
        defaults: {...K.LINE_DEFAULTS},
        properties: [...K.LINE_PROPERTIES],
        render(ctx, c) {
            const {x, y} = c.points[0];
            Draw.line(ctx, {x: 0, y}, {x: c.env.width, y}, K.stroke(c));
            Draw.line(ctx, {x, y: 0}, {x, y: c.env.height}, K.stroke(c));
        },
        hitTest(c, x, y, tolerance) {
            return Math.abs(x - c.points[0].x) <= tolerance || Math.abs(y - c.points[0].y) <= tolerance ? {part: 'move'} : null;
        }
    });

    // ---- channels

    /** The parallel line through the third point, as screen points at the first two points' x. */
    function parallel(a, b, c) {
        const offset = c.y - K.lineY(a, b, c.x);
        return [{x: a.x, y: a.y + offset}, {x: b.x, y: b.y + offset}];
    }

    registerTool({
        type: 'parallelChannel',
        group: 'channels',
        points: 3,
        defaults: {...K.LINE_DEFAULTS, fill: true, fillOpacity: 0.12, showMiddle: true, extendLeft: false, extendRight: false},
        properties: [...K.LINE_PROPERTIES, P.fill, P.fillColor, P.fillOpacity, P.showMiddle, P.extendLeft, P.extendRight],
        draftPoints(drawing, next) {
            if (next === 2) drawing.points[2] = {...drawing.points[1]};
        },
        render(ctx, c) {
            const [a, b, third] = c.points;
            const [a2, b2] = parallel(a, b, third);
            const [s1, e1] = K.extended(c, a, b);
            const [s2, e2] = K.extended(c, a2, b2);
            if (c.style.fill) Draw.fill(ctx, [s1, e1, e2, s2], K.fillColor(c.style, 0.12));
            Draw.line(ctx, s1, e1, K.stroke(c));
            Draw.line(ctx, s2, e2, K.stroke(c));
            if (c.style.showMiddle) {
                const [sm, em] = K.extended(c, {x: a.x, y: (a.y + a2.y) / 2}, {x: b.x, y: (b.y + b2.y) / 2});
                Draw.line(ctx, sm, em, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            }
        },
        handles(c) {
            const [a, b, third] = c.points;
            const [a2, b2] = parallel(a, b, third);
            return [a, b, {x: (a2.x + b2.x) / 2, y: (a2.y + b2.y) / 2}];
        },
        hitTest(c, x, y, tolerance) {
            const [a, b, third] = c.points;
            const [a2, b2] = parallel(a, b, third);
            const [s1, e1] = K.extended(c, a, b);
            const [s2, e2] = K.extended(c, a2, b2);
            if (K.hitSegment(s1, e1, x, y, tolerance) || K.hitSegment(s2, e2, x, y, tolerance)) return {part: 'move'};
            return c.style.fill && K.hitInside([a, b, b2, a2], x, y) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'regressionTrend',
        group: 'channels',
        points: 2,
        defaults: {...K.LINE_DEFAULTS, upperDeviation: 2, lowerDeviation: 2, fill: true, fillOpacity: 0.1, extendRight: false, showPearson: true},
        properties: [...K.LINE_PROPERTIES, {key: 'upperDeviation', type: 'number', min: 0, max: 10, step: 0.1, tab: 'inputs'},
            {key: 'lowerDeviation', type: 'number', min: 0, max: 10, step: 0.1, tab: 'inputs'}, P.fill, P.fillOpacity, P.extendRight,
            {key: 'showPearson', type: 'bool', tab: 'style'}],
        axisLabels: 'none',
        regression(c) {
            const [from, to] = c.drawing.points;
            const bars = K.barsBetween(c, from.time, to.time);
            if (bars.length < 3) return null;
            const n = bars.length;
            let sumX = 0, sumY = 0, sumXY = 0, sumXX = 0, sumYY = 0;
            bars.forEach((bar, index) => {
                sumX += index; sumY += bar.close; sumXY += index * bar.close; sumXX += index * index; sumYY += bar.close * bar.close;
            });
            const denominator = n * sumXX - sumX * sumX;
            const slope = denominator ? (n * sumXY - sumX * sumY) / denominator : 0;
            const intercept = (sumY - slope * sumX) / n;
            let residuals = 0;
            bars.forEach((bar, index) => { residuals += (bar.close - (intercept + slope * index)) ** 2; });
            const deviation = Math.sqrt(residuals / n);
            const pearsonDenominator = Math.sqrt((n * sumXX - sumX * sumX) * (n * sumYY - sumY * sumY));
            return {
                start: {time: bars[0].time, price: intercept},
                end: {time: bars[n - 1].time, price: intercept + slope * (n - 1)},
                deviation,
                pearson: pearsonDenominator ? (n * sumXY - sumX * sumY) / pearsonDenominator : 0
            };
        },
        lines(c) {
            const regression = this.regression(c);
            if (!regression) return null;
            const env = c.env;
            const point = (time, price) => {
                const x = env.x(time);
                const y = env.y(price);
                return finite(x) && finite(y) ? {x, y} : null;
            };
            const up = c.style.upperDeviation * regression.deviation;
            const down = c.style.lowerDeviation * regression.deviation;
            return {
                regression,
                base: [point(regression.start.time, regression.start.price), point(regression.end.time, regression.end.price)],
                upper: [point(regression.start.time, regression.start.price + up), point(regression.end.time, regression.end.price + up)],
                lower: [point(regression.start.time, regression.start.price - down), point(regression.end.time, regression.end.price - down)]
            };
        },
        render(ctx, c) {
            const lines = this.lines(c);
            if (!lines || lines.base.some(point => !point)) {
                Draw.line(ctx, c.points[0], c.points[1], {color: c.style.color, width: 1, lineStyle: 'dashed'});
                return;
            }
            const extend = segment => K.extended(c, segment[0], segment[1], false, c.style.extendRight);
            const upper = extend(lines.upper);
            const lower = extend(lines.lower);
            if (c.style.fill && lines.upper.every(Boolean) && lines.lower.every(Boolean)) {
                Draw.fill(ctx, [upper[0], upper[1], lower[1], lower[0]], K.fillColor(c.style, 0.1));
            }
            Draw.line(ctx, ...extend(lines.base), {color: c.style.color, width: c.style.width, lineStyle: 'dashed'});
            Draw.line(ctx, ...upper, K.stroke(c));
            Draw.line(ctx, ...lower, K.stroke(c));
            if (c.style.showPearson) {
                Draw.label(ctx, `R ${window.StockWatchI18n.number(lines.regression.pearson, 3)}`, lines.lower[0].x, lines.lower[0].y + 12,
                    {baseline: 'top', color: c.style.color, size: 11});
            }
        },
        handles(c) {
            const lines = this.lines(c);
            return lines && lines.base.every(Boolean) ? lines.base : c.points;
        },
        hitTest(c, x, y, tolerance) {
            const lines = this.lines(c);
            if (!lines) return K.hitSegment(c.points[0], c.points[1], x, y, tolerance) ? {part: 'move'} : null;
            for (const segment of [lines.base, lines.upper, lines.lower]) {
                if (segment.every(Boolean) && K.hitSegment(segment[0], segment[1], x, y, tolerance)) return {part: 'move'};
            }
            return null;
        }
    });

    registerTool({
        type: 'flatTopBottom',
        group: 'channels',
        points: 3,
        defaults: {...K.LINE_DEFAULTS, fill: true, fillOpacity: 0.12, extendLeft: false, extendRight: false},
        properties: [...K.LINE_PROPERTIES, P.fill, P.fillColor, P.fillOpacity, P.extendLeft, P.extendRight],
        render(ctx, c) {
            const [a, b, level] = c.points;
            const flatA = {x: a.x, y: level.y};
            const flatB = {x: b.x, y: level.y};
            const [s1, e1] = K.extended(c, a, b);
            const [s2, e2] = K.extended(c, flatA, flatB);
            if (c.style.fill) Draw.fill(ctx, [a, b, flatB, flatA], K.fillColor(c.style, 0.12));
            Draw.line(ctx, s1, e1, K.stroke(c));
            Draw.line(ctx, s2, e2, K.stroke(c));
        },
        handles: c => [c.points[0], c.points[1], {x: (c.points[0].x + c.points[1].x) / 2, y: c.points[2].y}],
        movePoint(drawing, index, point) {
            if (index === 2) drawing.points[2] = {time: drawing.points[2].time, price: point.price};
            else drawing.points[index] = point;
        },
        hitTest(c, x, y, tolerance) {
            const [a, b, level] = c.points;
            if (K.hitSegment(...K.extended(c, a, b), x, y, tolerance)) return {part: 'move'};
            if (K.hitSegment(...K.extended(c, {x: a.x, y: level.y}, {x: b.x, y: level.y}), x, y, tolerance)) return {part: 'move'};
            return c.style.fill && K.hitInside([a, b, {x: b.x, y: level.y}, {x: a.x, y: level.y}], x, y) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'disjointChannel',
        group: 'channels',
        points: 3,
        defaults: {...K.LINE_DEFAULTS, fill: true, fillOpacity: 0.12, extendLeft: false, extendRight: false},
        properties: [...K.LINE_PROPERTIES, P.fill, P.fillColor, P.fillOpacity, P.extendLeft, P.extendRight],
        mirrored(c) {
            const [a, b, start] = c.points;
            return {x: start.x + (b.x - a.x), y: start.y - (b.y - a.y)};
        },
        render(ctx, c) {
            const [a, b, start] = c.points;
            const end = this.mirrored(c);
            if (c.style.fill) Draw.fill(ctx, [a, b, end, start], K.fillColor(c.style, 0.12));
            Draw.line(ctx, ...K.extended(c, a, b), K.stroke(c));
            Draw.line(ctx, ...K.extended(c, start, end), K.stroke(c));
        },
        handles(c) { return [c.points[0], c.points[1], c.points[2], this.mirrored(c)]; },
        movePoint(drawing, index, point) { if (index < 3) drawing.points[index] = point; },
        hitTest(c, x, y, tolerance) {
            const [a, b, start] = c.points;
            const end = this.mirrored(c);
            if (K.hitSegment(...K.extended(c, a, b), x, y, tolerance) || K.hitSegment(...K.extended(c, start, end), x, y, tolerance)) return {part: 'move'};
            return c.style.fill && K.hitInside([a, b, end, start], x, y) ? {part: 'move'} : null;
        }
    });

    // ---- pitchforks

    function pitchforkStart(kind, p1, p2) {
        if (kind === 'schiff') return {x: p1.x, y: (p1.y + p2.y) / 2};
        if (kind === 'modifiedSchiff' || kind === 'inside') return {x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2};
        return p1;
    }

    function pitchforkTool(type, kind) {
        registerTool({
            type,
            group: 'pitchforks',
            points: 3,
            defaults: {...K.LINE_DEFAULTS, fill: true, fillOpacity: 0.08, showLevels: true},
            properties: [...K.LINE_PROPERTIES, P.fill, P.fillColor, P.fillOpacity, {key: 'showLevels', type: 'bool', tab: 'style'}],
            lines(c) {
                const [p1, p2, p3] = c.points;
                const start = pitchforkStart(kind, p1, p2);
                const middle = {x: (p2.x + p3.x) / 2, y: (p2.y + p3.y) / 2};
                const direction = {x: middle.x - start.x, y: middle.y - start.y};
                const through = point => ({x: point.x + direction.x, y: point.y + direction.y});
                return {start, middle, p2, p3, median: [start, K.ray(c, start, middle)], upper: [p2, K.ray(c, p2, through(p2))],
                    lower: [p3, K.ray(c, p3, through(p3))], direction, through};
            },
            render(ctx, c) {
                const lines = this.lines(c);
                if (c.style.fill) Draw.fill(ctx, [lines.upper[0], lines.upper[1], lines.lower[1], lines.lower[0]], K.fillColor(c.style, 0.08));
                Draw.line(ctx, ...lines.median, K.stroke(c));
                Draw.line(ctx, ...lines.upper, K.stroke(c));
                Draw.line(ctx, ...lines.lower, K.stroke(c));
                Draw.line(ctx, lines.p2, lines.p3, {color: c.style.color, width: 1});
                if (kind !== 'original') Draw.line(ctx, c.points[0], lines.start, {color: c.style.color, width: 1, lineStyle: 'dotted'});
                if (c.style.showLevels) {
                    // Half-way lines between the median and each outer prong.
                    [lines.p2, lines.p3].forEach(prong => {
                        const point = {x: (lines.middle.x + prong.x) / 2, y: (lines.middle.y + prong.y) / 2};
                        Draw.line(ctx, point, K.ray(c, point, lines.through(point)), {color: withAlpha(c.style.color, 0.55), width: 1, lineStyle: 'dashed'});
                    });
                }
            },
            hitTest(c, x, y, tolerance) {
                const lines = this.lines(c);
                for (const segment of [lines.median, lines.upper, lines.lower, [lines.p2, lines.p3]]) {
                    if (K.hitSegment(segment[0], segment[1], x, y, tolerance)) return {part: 'move'};
                }
                return null;
            }
        });
    }

    pitchforkTool('pitchfork', 'original');
    pitchforkTool('schiffPitchfork', 'schiff');
    pitchforkTool('modifiedSchiffPitchfork', 'modifiedSchiff');
    pitchforkTool('insidePitchfork', 'inside');

    registerTool({
        type: 'pitchfan',
        group: 'pitchforks',
        points: 3,
        defaults: {...K.LINE_DEFAULTS, width: 1, fill: true, fillOpacity: 0.06},
        properties: [...K.LINE_PROPERTIES, P.fill, P.fillOpacity],
        ratios: [0, 0.25, 0.382, 0.5, 0.618, 0.75, 1],
        render(ctx, c) {
            const [origin, p2, p3] = c.points;
            const rays = this.ratios.map(ratio => {
                const target = {x: p2.x + (p3.x - p2.x) * ratio, y: p2.y + (p3.y - p2.y) * ratio};
                return [origin, K.ray(c, origin, target), ratio];
            });
            if (c.style.fill) Draw.fill(ctx, [origin, rays[0][1], rays[rays.length - 1][1]], K.fillColor(c.style, 0.06));
            rays.forEach(([from, to, ratio]) => Draw.line(ctx, from, to, {...K.stroke(c), lineStyle: ratio === 0.5 ? 'solid' : c.style.lineStyle}));
            Draw.line(ctx, p2, p3, {color: c.style.color, width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) {
            const [origin, p2, p3] = c.points;
            for (const ratio of this.ratios) {
                const target = {x: p2.x + (p3.x - p2.x) * ratio, y: p2.y + (p3.y - p2.y) * ratio};
                if (K.hitSegment(origin, K.ray(c, origin, target), x, y, tolerance)) return {part: 'move'};
            }
            return null;
        }
    });

    // ---- cycles

    function cycleBars(c) {
        const [from, to] = c.drawing.points;
        const start = c.env.mapper.logical(from.time);
        const end = c.env.mapper.logical(to.time);
        return finite(start) && finite(end) ? {start, period: end - start} : null;
    }

    /** x of every cycle boundary from the first point onwards, up to the right edge of the pane. */
    function cycleXs(c) {
        const cycle = cycleBars(c);
        if (!cycle || Math.abs(cycle.period) < 0.5) return [];
        const xs = [];
        const step = Math.abs(cycle.period);
        for (let index = 0; index < 400; index++) {
            const x = c.env.x(c.env.mapper.seconds(cycle.start + index * step));
            if (!finite(x)) continue;
            if (x > c.env.width + 50) break;
            xs.push(x);
        }
        return xs;
    }

    registerTool({
        type: 'cyclicLines',
        group: 'cycles',
        points: 2,
        constrainShift: false,
        defaults: {...K.LINE_DEFAULTS, width: 1},
        properties: [...K.LINE_PROPERTIES],
        render(ctx, c) {
            cycleXs(c).forEach(x => Draw.line(ctx, {x, y: 0}, {x, y: c.env.height}, K.stroke(c)));
            Draw.line(ctx, c.points[0], c.points[1], {color: c.style.color, width: 1, lineStyle: 'dashed'});
        },
        hitTest(c, x, y, tolerance) {
            return cycleXs(c).some(lineX => Math.abs(lineX - x) <= tolerance) ? {part: 'move', cursor: 'ew-resize'} : null;
        }
    });

    registerTool({
        type: 'timeCycles',
        group: 'cycles',
        points: 2,
        defaults: {...K.LINE_DEFAULTS, width: 1, fill: true, fillOpacity: 0.08},
        properties: [...K.LINE_PROPERTIES, P.fill, P.fillOpacity],
        render(ctx, c) {
            const xs = cycleXs(c);
            const baseline = c.points[0].y;
            for (let index = 1; index < xs.length; index++) {
                const center = {x: (xs[index - 1] + xs[index]) / 2, y: baseline};
                const radius = Math.abs(xs[index] - xs[index - 1]) / 2;
                ctx.save();
                ctx.beginPath();
                ctx.arc(center.x, center.y, radius, Math.PI, 0);
                if (c.style.fill) { ctx.fillStyle = K.fillColor(c.style, 0.08); ctx.fill(); }
                Draw.stroke(ctx, K.stroke(c));
                ctx.stroke();
                ctx.restore();
            }
        },
        hitTest(c, x, y, tolerance) {
            const xs = cycleXs(c);
            const baseline = c.points[0].y;
            for (let index = 1; index < xs.length; index++) {
                const center = {x: (xs[index - 1] + xs[index]) / 2, y: baseline};
                const radius = Math.abs(xs[index] - xs[index - 1]) / 2;
                if (y <= baseline + tolerance && Math.abs(Math.hypot(x - center.x, y - center.y) - radius) <= tolerance) return {part: 'move'};
            }
            return null;
        }
    });

    registerTool({
        type: 'sineLine',
        group: 'cycles',
        points: 2,
        defaults: {...K.LINE_DEFAULTS},
        properties: [...K.LINE_PROPERTIES],
        curve(c) {
            const [a, b] = c.points;
            const half = b.x - a.x;
            if (Math.abs(half) < 2) return [];
            const middle = (a.y + b.y) / 2;
            const amplitude = (a.y - b.y) / 2;
            const points = [];
            for (let x = -10; x <= c.env.width + 10; x += 3) points.push({x, y: middle + amplitude * Math.cos(Math.PI * (x - a.x) / half)});
            return points;
        },
        render(ctx, c) { Draw.polyline(ctx, this.curve(c), K.stroke(c)); },
        hitTest(c, x, y, tolerance) { return K.hitPolyline(this.curve(c), x, y, tolerance) ? {part: 'move'} : null; }
    });
})();
