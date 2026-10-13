(() => {
    'use strict';

    // Position, measurement, forecasting and volume-based tools.
    const {registerTool, Draw, Geometry} = window.StockWatchDrawing;
    const K = window.StockWatchDrawingKit;
    const {withAlpha, contrastText} = Geometry;
    const i18n = window.StockWatchI18n;
    const finite = K.finite;
    const PROFIT = '#26a69a';
    const RISK = '#ef5350';

    // ---- long and short positions

    function averageRange(bars, count = 14) {
        const recent = bars.slice(-count);
        if (!recent.length) return null;
        const total = recent.reduce((sum, bar) => sum + (bar.high - bar.low), 0);
        return total / recent.length || null;
    }

    function positionQuantity(style, entry, stop) {
        const risk = Math.abs(entry - stop);
        const account = Number(style.accountSize);
        const riskValue = Number(style.risk);
        if (!(risk > 0) || !(account > 0) || !(riskValue > 0)) return null;
        const amount = style.riskMode === 'amount' ? riskValue : account * riskValue / 100;
        const lot = Number(style.lotSize) > 0 ? Number(style.lotSize) : 1;
        const quantity = Math.floor(amount / risk / lot) * lot;
        return quantity > 0 ? quantity : null;
    }

    /** First touch of the target or stop after entry; the stop wins when both are hit in one bar. */
    function positionOutcome(c, long) {
        const [entry, target, stop] = c.drawing.points;
        const bars = c.env.bars;
        let last = null;
        for (const bar of bars) {
            if (bar.time <= entry.time) continue;
            if (bar.time > target.time) break;
            last = bar;
            const stopHit = long ? bar.low <= stop.price : bar.high >= stop.price;
            const targetHit = long ? bar.high >= target.price : bar.low <= target.price;
            if (stopHit) return {kind: 'stop', time: bar.time, price: stop.price};
            if (targetHit) return {kind: 'target', time: bar.time, price: target.price};
        }
        if (!last) return null;
        const ended = bars.length && bars[bars.length - 1].time >= target.time;
        return {kind: ended ? 'closed' : 'open', time: last.time, price: last.close};
    }

    function positionTool(type, long) {
        registerTool({
            type,
            group: 'positions',
            points: 3,
            placement: 'single',
            keepDefaultColor: true,
            axisLabels: 'selected',
            axisPrices: drawing => drawing.points.map(point => point.price),
            axisTimes: drawing => [drawing.points[0].time, drawing.points[1].time],
            defaults: {color: long ? PROFIT : RISK, accountSize: 10000, riskMode: 'percent', risk: 1, lotSize: 1, showOutcome: true,
                profitColor: PROFIT, stopColor: RISK},
            properties: [
                {key: 'accountSize', type: 'number', min: 0, max: 1e12, step: 100, tab: 'inputs'},
                {key: 'riskMode', type: 'select', options: ['percent', 'amount'], tab: 'inputs'},
                {key: 'risk', type: 'number', min: 0, max: 1e9, step: 0.1, tab: 'inputs'},
                {key: 'lotSize', type: 'number', min: 0, max: 1e9, step: 1, tab: 'inputs'},
                {key: 'showOutcome', type: 'bool', tab: 'inputs'},
                {key: 'profitColor', type: 'color', tab: 'style'},
                {key: 'stopColor', type: 'color', tab: 'style'}
            ],
            create(drawing, env) {
                const entry = drawing.points[0];
                const range = averageRange(env.bars) || Math.abs(entry.price) * 0.01;
                const logical = env.mapper.logical(entry.time) ?? 0;
                const end = env.mapper.seconds(logical + 25);
                const direction = long ? 1 : -1;
                drawing.points = [entry, {time: end, price: entry.price + direction * range * 3}, {time: end, price: entry.price - direction * range * 1.5}];
            },
            movePoint(drawing, index, point) {
                const [entry, target, stop] = drawing.points;
                const epsilon = Math.max(Math.abs(entry.price) * 1e-6, 1e-8);
                if (index === 0) {
                    const price = long ? Math.min(Math.max(point.price, stop.price + epsilon), target.price - epsilon)
                        : Math.min(Math.max(point.price, target.price + epsilon), stop.price - epsilon);
                    drawing.points[0] = {time: point.time < target.time ? point.time : entry.time, price};
                    return;
                }
                const time = point.time > entry.time ? point.time : target.time;
                if (index === 1) {
                    const price = long ? Math.max(point.price, entry.price + epsilon) : Math.min(point.price, entry.price - epsilon);
                    drawing.points[1] = {time, price};
                    drawing.points[2] = {time, price: stop.price};
                } else {
                    const price = long ? Math.min(point.price, entry.price - epsilon) : Math.max(point.price, entry.price + epsilon);
                    drawing.points[2] = {time, price};
                    drawing.points[1] = {time, price: target.price};
                }
            },
            afterMove(drawing) {
                drawing.points[2] = {time: drawing.points[1].time, price: drawing.points[2].price};
            },
            render(ctx, c) {
                const [entry, target, stop] = c.points;
                const [entryPoint, targetPoint, stopPoint] = c.drawing.points;
                const left = Math.min(entry.x, target.x);
                const right = Math.max(entry.x, target.x);
                const width = Math.max(1, right - left);
                const profitColor = c.style.profitColor || PROFIT;
                const stopColor = c.style.stopColor || RISK;
                Draw.rect(ctx, left, Math.min(entry.y, target.y), width, Math.abs(target.y - entry.y), null, withAlpha(profitColor, c.selected ? 0.24 : 0.17));
                Draw.rect(ctx, left, Math.min(entry.y, stop.y), width, Math.abs(stop.y - entry.y), null, withAlpha(stopColor, c.selected ? 0.24 : 0.17));
                if (c.style.showOutcome && !c.draft) {
                    const outcome = positionOutcome(c, long);
                    if (outcome) {
                        const x = c.env.x(outcome.time);
                        const y = c.env.y(outcome.price);
                        if (finite(x) && finite(y)) {
                            const color = (long ? outcome.price >= entryPoint.price : outcome.price <= entryPoint.price) ? profitColor : stopColor;
                            Draw.rect(ctx, left, Math.min(entry.y, y), Math.max(1, Math.min(x, right) - left), Math.abs(y - entry.y), null, withAlpha(color, 0.25));
                            Draw.line(ctx, entry, {x, y}, {color, width: 1, lineStyle: 'dashed'});
                            const change = (outcome.price - entryPoint.price) / entryPoint.price * 100 * (long ? 1 : -1);
                            Draw.label(ctx, i18n.t(`draw.position.outcome.${outcome.kind}`, c.env.format.percent(change)), x + 4, y,
                                {background: color, color: contrastText(color), size: 11, bounds: c.env});
                        }
                    }
                }
                Draw.line(ctx, {x: left, y: target.y}, {x: right, y: target.y}, {color: profitColor, width: c.selected ? 1.7 : 1.2});
                Draw.line(ctx, {x: left, y: entry.y}, {x: right, y: entry.y}, {color: c.env.palette.text, width: 1, lineStyle: 'dashed'});
                Draw.line(ctx, {x: left, y: stop.y}, {x: right, y: stop.y}, {color: stopColor, width: c.selected ? 1.7 : 1.2});
                const quantity = positionQuantity(c.style, entryPoint.price, stopPoint.price);
                const reward = Math.abs(targetPoint.price - entryPoint.price);
                const risk = Math.abs(entryPoint.price - stopPoint.price);
                const ratio = risk > 0 ? reward / risk : NaN;
                const targetPercent = reward / Math.abs(entryPoint.price) * 100;
                const stopPercent = risk / Math.abs(entryPoint.price) * 100;
                const amount = value => (quantity ? `, ${c.env.format.number(value * quantity, 2)}` : '');
                const labelOptions = color => ({align: 'center', background: color, color: contrastText(color), size: 11, bounds: c.env});
                Draw.label(ctx, i18n.t('draw.position.target', c.env.format.price(targetPoint.price), c.env.format.number(targetPercent, 2), amount(reward)),
                    (left + right) / 2, target.y + (long ? -12 : 12), labelOptions(profitColor));
                Draw.label(ctx, i18n.t('draw.position.stop', c.env.format.price(stopPoint.price), c.env.format.number(stopPercent, 2), amount(risk)),
                    (left + right) / 2, stop.y + (long ? 12 : -12), labelOptions(stopColor));
                const summary = [i18n.t(long ? 'draw.position.long' : 'draw.position.short', finite(ratio) ? `1:${i18n.number(ratio, 2)}` : '—')];
                if (quantity) summary.push(i18n.t('draw.position.quantity', c.env.format.number(quantity, quantity % 1 ? 2 : 0)));
                Draw.label(ctx, summary.join(' · '), (left + right) / 2, entry.y + (long ? 12 : -12), {
                    align: 'center', background: c.env.palette.labelBackground, color: c.env.palette.text, accent: long ? profitColor : stopColor,
                    size: 11, weight: 700, bounds: c.env
                });
            },
            hitTest(c, x, y, tolerance) {
                const [entry, target, stop] = c.points;
                const left = Math.min(entry.x, target.x) - tolerance;
                const right = Math.max(entry.x, target.x) + tolerance;
                if (x < left || x > right) return null;
                const top = Math.min(target.y, stop.y) - tolerance;
                const bottom = Math.max(target.y, stop.y) + tolerance;
                return y >= top && y <= bottom ? {part: 'move'} : null;
            }
        });
    }

    positionTool('longPosition', true);
    positionTool('shortPosition', false);

    // ---- ranges

    function rangeLabel(c, from, to, kind) {
        const env = c.env;
        const lines = [];
        if (kind !== 'date') {
            const change = to.price - from.price;
            lines.push(`${env.format.price(change)} (${env.format.percent(from.price ? change / from.price * 100 : 0)})`);
        }
        if (kind !== 'price') {
            const bars = (env.mapper.logical(to.time) ?? 0) - (env.mapper.logical(from.time) ?? 0);
            lines.push(`${env.format.bars(bars)}, ${env.format.days(to.time - from.time)}`);
            const volume = K.barsBetween(c, from.time, to.time).reduce((sum, bar) => sum + (finite(bar.volume) ? bar.volume : 0), 0);
            if (volume > 0) lines.push(i18n.t('draw.label.volume', env.format.volume(volume)));
        }
        return lines.join('\n');
    }

    function rangeTool(type, kind) {
        registerTool({
            type,
            group: 'measure',
            points: 2,
            constrainShift: false,
            defaults: {color: '#2962ff', fillOpacity: 0.16},
            properties: [K.P.color, K.P.fillOpacity],
            render(ctx, c) {
                const [a, b] = c.points;
                const [from, to] = c.drawing.points;
                const negative = kind === 'date' ? to.time < from.time : to.price < from.price;
                const color = negative && kind !== 'date' ? RISK : c.style.color;
                Draw.rect(ctx, Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y), null, withAlpha(color, c.style.fillOpacity ?? 0.16));
                const middleX = (a.x + b.x) / 2;
                const middleY = (a.y + b.y) / 2;
                if (kind !== 'date') {
                    Draw.line(ctx, {x: middleX, y: a.y}, {x: middleX, y: b.y}, {color, width: 1});
                    Draw.arrowHead(ctx, {x: middleX, y: a.y}, {x: middleX, y: b.y}, color, 8);
                }
                if (kind !== 'price') {
                    Draw.line(ctx, {x: a.x, y: middleY}, {x: b.x, y: middleY}, {color, width: 1});
                    Draw.arrowHead(ctx, {x: a.x, y: middleY}, {x: b.x, y: middleY}, color, 8);
                }
                Draw.label(ctx, rangeLabel(c, from, to, kind), middleX, Math.max(a.y, b.y) + 8, {
                    align: 'center', baseline: 'top', textAlign: 'center', background: color, color: contrastText(color), size: 12, bounds: c.env
                });
            },
            hitTest(c, x, y, tolerance) {
                const [a, b] = c.points;
                return x >= Math.min(a.x, b.x) - tolerance && x <= Math.max(a.x, b.x) + tolerance
                    && y >= Math.min(a.y, b.y) - tolerance && y <= Math.max(a.y, b.y) + tolerance ? {part: 'move'} : null;
            }
        });
    }

    rangeTool('priceRange', 'price');
    rangeTool('dateRange', 'date');
    rangeTool('datePriceRange', 'both');

    // ---- forecasting

    registerTool({
        type: 'forecast',
        group: 'measure',
        points: 2,
        constrainShift: false,
        defaults: {color: '#2962ff', width: 2, lineStyle: 'solid'},
        properties: [...K.LINE_PROPERTIES],
        outcome(c) {
            const [from, to] = c.drawing.points;
            const rising = to.price >= from.price;
            for (const bar of c.env.bars) {
                if (bar.time <= from.time) continue;
                if (bar.time > to.time) break;
                if (rising ? bar.high >= to.price : bar.low <= to.price) return 'success';
            }
            const ended = c.env.bars.length && c.env.bars[c.env.bars.length - 1].time >= to.time;
            return ended ? 'failure' : 'pending';
        },
        render(ctx, c) {
            const [a, b] = c.points;
            const [from, to] = c.drawing.points;
            Draw.line(ctx, a, b, K.stroke(c));
            Draw.arrowHead(ctx, a, b, c.style.color, 10);
            Draw.circle(ctx, a, 4, {color: c.style.color, width: 2}, c.env.palette.handleFill);
            const outcome = this.outcome(c);
            const color = outcome === 'success' ? PROFIT : (outcome === 'failure' ? RISK : c.style.color);
            const change = to.price - from.price;
            const lines = [`${c.env.format.price(change)} (${c.env.format.percent(from.price ? change / from.price * 100 : 0)})`,
                c.env.format.date(to.time), i18n.t(`draw.forecast.${outcome}`)];
            Draw.label(ctx, lines.join('\n'), b.x + 10, b.y, {background: color, color: contrastText(color), size: 11, bounds: c.env});
        },
        hitTest(c, x, y, tolerance) { return K.hitSegment(c.points[0], c.points[1], x, y, tolerance) ? {part: 'move'} : null; }
    });

    function sourceBars(c) {
        const [from, to] = c.drawing.points;
        return K.barsBetween(c, from.time, to.time);
    }

    /** Copied bars positioned from the third point (bar times as offsets from it, in bars). */
    function projectedBars(c) {
        const bars = sourceBars(c);
        const anchor = c.drawing.points[2];
        if (!bars.length || !anchor) return [];
        const start = c.env.mapper.logical(anchor.time);
        const base = bars[0].open;
        const shift = anchor.price - base;
        const ordered = c.style.mirror ? [...bars].reverse() : bars;
        return ordered.map((bar, index) => {
            const transform = price => (c.style.flip ? base - (price - base) : price) + shift;
            const open = transform(c.style.mirror ? bar.close : bar.open);
            const close = transform(c.style.mirror ? bar.open : bar.close);
            const high = Math.max(transform(bar.high), transform(bar.low));
            const low = Math.min(transform(bar.high), transform(bar.low));
            return {time: c.env.mapper.seconds(start + index), open, high, low, close};
        });
    }

    function drawGhostBars(ctx, c, bars, alpha = 0.6) {
        const halfWidth = Math.max(1, c.env.barSpacing * 0.35);
        const box = {left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity};
        bars.forEach(bar => {
            const x = c.env.x(bar.time);
            const open = c.env.y(bar.open);
            const close = c.env.y(bar.close);
            const high = c.env.y(bar.high);
            const low = c.env.y(bar.low);
            if (![x, open, close, high, low].every(finite)) return;
            const color = withAlpha(bar.close >= bar.open ? PROFIT : RISK, alpha);
            Draw.line(ctx, {x, y: high}, {x, y: low}, {color, width: 1});
            if (c.style.style === 'line') return;
            Draw.rect(ctx, x - halfWidth, Math.min(open, close), halfWidth * 2, Math.max(1, Math.abs(close - open)), {color, width: 1},
                bar.close >= bar.open ? null : withAlpha(RISK, alpha * 0.4));
            box.left = Math.min(box.left, x - halfWidth);
            box.right = Math.max(box.right, x + halfWidth);
            box.top = Math.min(box.top, high);
            box.bottom = Math.max(box.bottom, low);
        });
        if (c.style.style === 'line') {
            Draw.polyline(ctx, bars.map(bar => c.env.point({time: bar.time, price: bar.close})), {color: withAlpha(c.style.color, alpha), width: 2});
        }
        return box;
    }

    registerTool({
        type: 'barsPattern',
        group: 'measure',
        points: 3,
        constrainShift: false,
        defaults: {color: '#2962ff', mirror: false, flip: false, style: 'candles'},
        properties: [K.P.color, {key: 'mirror', type: 'bool', tab: 'style'}, {key: 'flip', type: 'bool', tab: 'style'},
            {key: 'style', type: 'select', options: ['candles', 'line'], tab: 'style'}],
        render(ctx, c) {
            const [a, b] = c.points;
            Draw.rect(ctx, Math.min(a.x, b.x), 0, Math.abs(b.x - a.x), c.env.height, null, withAlpha(c.style.color, 0.05));
            Draw.line(ctx, a, b, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            drawGhostBars(ctx, c, projectedBars(c));
        },
        hitTest(c, x, y, tolerance) {
            if (K.hitSegment(c.points[0], c.points[1], x, y, tolerance)) return {part: 'move'};
            const bars = projectedBars(c);
            if (!bars.length) return null;
            const xs = bars.map(bar => c.env.x(bar.time)).filter(finite);
            const ys = bars.flatMap(bar => [c.env.y(bar.high), c.env.y(bar.low)]).filter(finite);
            return xs.length && x >= Math.min(...xs) - tolerance && x <= Math.max(...xs) + tolerance
                && y >= Math.min(...ys) - tolerance && y <= Math.max(...ys) + tolerance ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'ghostFeed',
        group: 'measure',
        placement: 'path',
        points: 2,
        maxPoints: 30,
        defaults: {color: '#2962ff', style: 'candles'},
        properties: [K.P.color],
        bars(c) {
            const range = averageRange(c.env.bars, 20) || 1;
            const result = [];
            const points = c.drawing.points;
            for (let index = 1; index < points.length; index++) {
                const start = c.env.mapper.logical(points[index - 1].time);
                const end = c.env.mapper.logical(points[index].time);
                const count = Math.max(1, Math.round(end - start));
                for (let step = 1; step <= count; step++) {
                    const open = result.length ? result[result.length - 1].close : points[0].price;
                    const close = points[index - 1].price + (points[index].price - points[index - 1].price) * step / count;
                    result.push({time: c.env.mapper.seconds(start + step), open, close,
                        high: Math.max(open, close) + range * 0.25, low: Math.min(open, close) - range * 0.25});
                }
            }
            return result;
        },
        render(ctx, c) {
            Draw.polyline(ctx, c.points, {color: withAlpha(c.style.color, 0.4), width: 1, lineStyle: 'dotted'});
            drawGhostBars(ctx, c, this.bars(c), 0.7);
        },
        hitTest(c, x, y, tolerance) { return K.hitPolyline(c.points, x, y, tolerance + 4) ? {part: 'move'} : null; }
    });

    registerTool({
        type: 'projection',
        group: 'measure',
        points: 3,
        constrainShift: false,
        defaults: {color: '#2962ff', width: 2, lineStyle: 'solid'},
        properties: [...K.LINE_PROPERTIES],
        target(c) {
            const [a, b, third] = c.drawing.points;
            const bars = (c.env.mapper.logical(b.time) ?? 0) - (c.env.mapper.logical(a.time) ?? 0);
            return {time: c.env.mapper.seconds((c.env.mapper.logical(third.time) ?? 0) + bars), price: third.price + (b.price - a.price)};
        },
        render(ctx, c) {
            const [a, b, third] = c.points;
            const target = this.target(c);
            const d = c.env.point(target);
            Draw.line(ctx, a, b, K.stroke(c));
            Draw.line(ctx, b, third, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            if (!d) return;
            Draw.line(ctx, third, d, {...K.stroke(c), lineStyle: 'dashed'});
            Draw.arrowHead(ctx, third, d, c.style.color, 10);
            const origin = c.drawing.points[2].price;
            Draw.label(ctx, `${c.env.format.price(target.price)} (${c.env.format.percent(origin ? (target.price - origin) / origin * 100 : 0)})`,
                d.x + 8, d.y, {background: c.style.color, color: contrastText(c.style.color), size: 11, bounds: c.env});
        },
        hitTest(c, x, y, tolerance) {
            const d = c.env.point(this.target(c));
            return K.hitPolyline([...c.points, d], x, y, tolerance) ? {part: 'move'} : null;
        }
    });

    // ---- volume-based tools (need bar volume)

    registerTool({
        type: 'anchoredVwap',
        group: 'volume',
        points: 1,
        placement: 'single',
        axisLabels: 'none',
        keepDefaultColor: true,
        defaults: {color: '#f472b6', width: 2, lineStyle: 'solid', bands: true, bandMultiplier: 1},
        properties: [...K.LINE_PROPERTIES, {key: 'bands', type: 'bool', tab: 'style'},
            {key: 'bandMultiplier', type: 'number', min: 0.1, max: 5, step: 0.1, tab: 'inputs'}],
        series(c) { return K.anchoredVwap(c.env.bars, c.drawing.points[0].time); },
        render(ctx, c) {
            const anchor = c.points[0];
            Draw.line(ctx, {x: anchor.x, y: anchor.y - 8}, {x: anchor.x, y: anchor.y + 8}, {color: c.style.color, width: 2});
            if (!c.manager.hasVolume()) { K.noVolumeLabel(ctx, c, anchor); return; }
            const values = this.series(c);
            const line = values.map(value => c.env.point({time: value.time, price: value.vwap}));
            Draw.polyline(ctx, line, K.stroke(c));
            if (c.style.bands) {
                const multiplier = Number(c.style.bandMultiplier) || 1;
                Draw.polyline(ctx, values.map(value => c.env.point({time: value.time, price: value.vwap + multiplier * value.deviation})),
                    {color: withAlpha(c.style.color, 0.6), width: 1, lineStyle: 'dashed'});
                Draw.polyline(ctx, values.map(value => c.env.point({time: value.time, price: value.vwap - multiplier * value.deviation})),
                    {color: withAlpha(c.style.color, 0.6), width: 1, lineStyle: 'dashed'});
            }
            const last = line.filter(Boolean).pop();
            if (last) Draw.label(ctx, 'AVWAP', last.x + 4, last.y, {color: c.style.color, size: 11, weight: 700});
        },
        hitTest(c, x, y, tolerance) {
            if (Math.hypot(x - c.points[0].x, y - c.points[0].y) <= tolerance + 4) return {part: 'move'};
            const line = this.series(c).map(value => c.env.point({time: value.time, price: value.vwap}));
            return K.hitPolyline(line, x, y, tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'fixedRangeVolumeProfile',
        group: 'volume',
        points: 2,
        constrainShift: false,
        axisLabels: 'none',
        keepDefaultColor: true,
        defaults: {color: '#787b86', rows: 24, valueArea: 0.7, widthPercent: 30, extendPoc: false},
        properties: [{key: 'rows', type: 'number', min: 6, max: 200, step: 1, tab: 'inputs'},
            {key: 'valueArea', type: 'number', min: 0.5, max: 0.95, step: 0.01, tab: 'inputs'},
            {key: 'widthPercent', type: 'number', min: 5, max: 100, step: 1, tab: 'style'},
            {key: 'extendPoc', type: 'bool', tab: 'style'}],
        render(ctx, c) {
            const [a, b] = c.points;
            const left = Math.min(a.x, b.x);
            const right = Math.max(a.x, b.x);
            if (!c.manager.hasVolume()) { K.noVolumeLabel(ctx, c, a); return; }
            const [from, to] = c.drawing.points;
            const profile = K.volumeProfile(K.barsBetween(c, from.time, to.time), Math.round(c.style.rows || 24), Number(c.style.valueArea) || 0.7);
            if (!profile) return;
            const top = c.env.y(profile.rows[profile.rows.length - 1].high);
            const bottom = c.env.y(profile.rows[0].low);
            Draw.rect(ctx, left, Math.min(top, bottom), right - left, Math.abs(bottom - top), {color: withAlpha(c.style.color, 0.6), width: 1, lineStyle: 'dashed'},
                withAlpha(c.style.color, 0.04));
            K.renderProfile(ctx, c, profile, left, left + (right - left) * (Number(c.style.widthPercent) || 30) / 100,
                {pocEnd: c.style.extendPoc ? c.env.width : right});
        },
        hitTest(c, x, y, tolerance) {
            const [a, b] = c.points;
            return x >= Math.min(a.x, b.x) - tolerance && x <= Math.max(a.x, b.x) + tolerance ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'anchoredVolumeProfile',
        group: 'volume',
        points: 1,
        placement: 'single',
        axisLabels: 'none',
        keepDefaultColor: true,
        defaults: {color: '#787b86', rows: 24, valueArea: 0.7, widthPercent: 25},
        properties: [{key: 'rows', type: 'number', min: 6, max: 200, step: 1, tab: 'inputs'},
            {key: 'valueArea', type: 'number', min: 0.5, max: 0.95, step: 0.01, tab: 'inputs'},
            {key: 'widthPercent', type: 'number', min: 5, max: 60, step: 1, tab: 'style'}],
        render(ctx, c) {
            const anchor = c.points[0];
            Draw.line(ctx, {x: anchor.x, y: 0}, {x: anchor.x, y: c.env.height}, {color: withAlpha(c.style.color, 0.6), width: 1, lineStyle: 'dashed'});
            if (!c.manager.hasVolume()) { K.noVolumeLabel(ctx, c, anchor); return; }
            const bars = c.env.bars.filter(bar => bar.time >= c.drawing.points[0].time);
            const profile = K.volumeProfile(bars, Math.round(c.style.rows || 24), Number(c.style.valueArea) || 0.7);
            const width = c.env.width * (Number(c.style.widthPercent) || 25) / 100;
            K.renderProfile(ctx, c, profile, anchor.x, anchor.x + width, {pocEnd: c.env.width});
        },
        hitTest(c, x, y, tolerance) { return Math.abs(x - c.points[0].x) <= tolerance + 2 ? {part: 'move', cursor: 'ew-resize'} : null; }
    });
})();
