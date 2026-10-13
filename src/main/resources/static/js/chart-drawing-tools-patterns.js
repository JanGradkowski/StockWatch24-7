(() => {
    'use strict';

    // Chart pattern and Elliott wave tools. Hand-drawn harmonic and Elliott structures are checked
    // against the standard ratio windows and wave rules, so the drawing shows which pattern the
    // points form and which rules they break.
    const {registerTool, Draw, Geometry} = window.StockWatchDrawing;
    const K = window.StockWatchDrawingKit;
    const {P} = K;
    const {withAlpha} = Geometry;
    const i18n = window.StockWatchI18n;
    const finite = K.finite;

    const ratio = (a, b) => (b === 0 ? NaN : Math.abs(a) / Math.abs(b));
    const leg = (points, from, to) => points[to].price - points[from].price;
    const formatRatio = value => (finite(value) ? i18n.number(value, 3) : '—');

    /** Whether a point is a local high, so its label goes above it. */
    function isHigh(points, index) {
        const previous = points[index - 1]?.price;
        const next = points[index + 1]?.price;
        const price = points[index].price;
        if (finite(previous) && finite(next)) return price >= previous && price >= next ? true : (price <= previous && price <= next ? false : price >= previous);
        if (finite(next)) return price >= next;
        if (finite(previous)) return price >= previous;
        return true;
    }

    function labels(ctx, c, names) {
        names.forEach((name, index) => {
            if (!name || !c.points[index]) return;
            K.pointLabel(ctx, c, c.points[index], name, isHigh(c.drawing.points, index), c.style.textColor || c.style.color);
        });
    }

    function ratioLine(ctx, c, from, to, value) {
        const a = c.points[from];
        const b = c.points[to];
        if (!a || !b) return;
        Draw.line(ctx, a, b, {color: withAlpha(c.style.color, 0.7), width: 1, lineStyle: 'dashed'});
        Draw.label(ctx, formatRatio(value), (a.x + b.x) / 2, (a.y + b.y) / 2, {
            align: 'center', background: c.env.palette.labelBackground, color: c.style.color, size: 11, padding: 3
        });
    }

    function badge(ctx, c, point, lines, ok) {
        if (!point || !lines.length) return;
        Draw.label(ctx, lines.join('\n'), point.x + 12, point.y, {
            background: c.env.palette.labelBackground, color: c.env.palette.text, border: ok ? '#26a69a' : '#ef5350',
            accent: ok ? '#26a69a' : '#ef5350', size: 11, bounds: c.env
        });
    }

    function patternTool(definition) {
        const {defaults, extraProperties, ...rest} = definition;
        registerTool({
            group: 'patterns',
            constrainShift: false,
            hitTest(c, x, y, tolerance) {
                if (K.hitPolyline(c.points, x, y, tolerance)) return {part: 'move'};
                return (definition.triangles || []).some(triangle => c.style.fill && K.hitInside(triangle.map(index => c.points[index]), x, y))
                    ? {part: 'move'} : null;
            },
            ...rest,
            defaults: {width: 2, lineStyle: 'solid', fill: true, fillOpacity: 0.12, textColor: '', showRatios: true, validate: true, ...defaults},
            properties: [...K.LINE_PROPERTIES, P.fill, P.fillColor, P.fillOpacity, P.textColor,
                {key: 'showRatios', type: 'bool', tab: 'style'}, {key: 'validate', type: 'bool', tab: 'style'}, ...(extraProperties || [])]
        });
    }

    // ---- harmonic patterns

    const HARMONICS = [
        {name: 'gartley', ab: [0.58, 0.66], bc: [0.382, 0.886], cd: [1.13, 1.618], xd: [0.75, 0.82]},
        {name: 'bat', ab: [0.382, 0.5], bc: [0.382, 0.886], cd: [1.618, 2.618], xd: [0.85, 0.92]},
        {name: 'altBat', ab: [0.3, 0.382], bc: [0.382, 0.886], cd: [2.0, 3.618], xd: [1.09, 1.17]},
        {name: 'butterfly', ab: [0.75, 0.82], bc: [0.382, 0.886], cd: [1.618, 2.24], xd: [1.27, 1.618]},
        {name: 'crab', ab: [0.382, 0.618], bc: [0.382, 0.886], cd: [2.24, 3.618], xd: [1.58, 1.66]},
        {name: 'deepCrab', ab: [0.85, 0.92], bc: [0.382, 0.886], cd: [2.0, 3.618], xd: [1.58, 1.66]}
    ];
    const within = (value, [low, high]) => finite(value) && value >= low && value <= high;

    patternTool({
        type: 'xabcd',
        points: 5,
        triangles: [[0, 1, 2], [2, 3, 4]],
        render(ctx, c) {
            const p = c.drawing.points;
            if (c.style.fill) {
                Draw.fill(ctx, [c.points[0], c.points[1], c.points[2]], K.fillColor(c.style));
                Draw.fill(ctx, [c.points[2], c.points[3], c.points[4]], K.fillColor(c.style));
            }
            Draw.polyline(ctx, c.points, K.stroke(c));
            const values = {
                ab: ratio(leg(p, 1, 2), leg(p, 0, 1)),
                bc: ratio(leg(p, 2, 3), leg(p, 1, 2)),
                cd: ratio(leg(p, 3, 4), leg(p, 2, 3)),
                xd: ratio(leg(p, 1, 4), leg(p, 0, 1))
            };
            if (c.style.showRatios) {
                ratioLine(ctx, c, 0, 2, values.ab);
                ratioLine(ctx, c, 1, 3, values.bc);
                ratioLine(ctx, c, 2, 4, values.cd);
                ratioLine(ctx, c, 0, 4, values.xd);
            }
            labels(ctx, c, ['X', 'A', 'B', 'C', 'D']);
            if (c.style.validate && !c.draft) {
                const match = HARMONICS.find(pattern => within(values.ab, pattern.ab) && within(values.bc, pattern.bc)
                    && within(values.cd, pattern.cd) && within(values.xd, pattern.xd));
                badge(ctx, c, c.points[4], [match ? i18n.t('draw.pattern.matches', i18n.t(`draw.harmonic.${match.name}`))
                    : i18n.t('draw.pattern.noHarmonic')], Boolean(match));
            }
        }
    });

    patternTool({
        type: 'cypher',
        points: 5,
        triangles: [[0, 1, 2], [2, 3, 4]],
        render(ctx, c) {
            const p = c.drawing.points;
            if (c.style.fill) {
                Draw.fill(ctx, [c.points[0], c.points[1], c.points[2]], K.fillColor(c.style));
                Draw.fill(ctx, [c.points[2], c.points[3], c.points[4]], K.fillColor(c.style));
            }
            Draw.polyline(ctx, c.points, K.stroke(c));
            const ab = ratio(leg(p, 1, 2), leg(p, 0, 1));
            const xc = ratio(leg(p, 0, 3), leg(p, 0, 1));
            const xd = ratio(leg(p, 3, 4), leg(p, 0, 3));
            if (c.style.showRatios) {
                ratioLine(ctx, c, 0, 2, ab);
                ratioLine(ctx, c, 0, 3, xc);
                ratioLine(ctx, c, 0, 4, xd);
            }
            labels(ctx, c, ['X', 'A', 'B', 'C', 'D']);
            if (c.style.validate && !c.draft) {
                const ok = within(ab, [0.382, 0.618]) && within(xc, [1.13, 1.414]) && within(xd, [0.75, 0.82]);
                badge(ctx, c, c.points[4], [i18n.t(ok ? 'draw.pattern.matches' : 'draw.pattern.notMatching', i18n.t('draw.harmonic.cypher'))], ok);
            }
        }
    });

    patternTool({
        type: 'abcd',
        points: 4,
        render(ctx, c) {
            const p = c.drawing.points;
            Draw.polyline(ctx, c.points, K.stroke(c));
            const bc = ratio(leg(p, 1, 2), leg(p, 0, 1));
            const cd = ratio(leg(p, 2, 3), leg(p, 1, 2));
            if (c.style.showRatios) {
                ratioLine(ctx, c, 0, 2, bc);
                ratioLine(ctx, c, 1, 3, cd);
            }
            labels(ctx, c, ['A', 'B', 'C', 'D']);
            if (c.style.validate && !c.draft) {
                const ok = within(bc, [0.382, 0.886]) && within(cd, [1.13, 2.618]);
                badge(ctx, c, c.points[3], [i18n.t(ok ? 'draw.pattern.matches' : 'draw.pattern.notMatching', i18n.t('draw.harmonic.abcd'))], ok);
            }
        }
    });

    patternTool({
        type: 'threeDrives',
        points: 6,
        render(ctx, c) {
            const p = c.drawing.points;
            Draw.polyline(ctx, c.points, K.stroke(c));
            const firstRetracement = ratio(leg(p, 1, 2), leg(p, 0, 1));
            const secondDrive = ratio(leg(p, 2, 3), leg(p, 1, 2));
            const secondRetracement = ratio(leg(p, 3, 4), leg(p, 2, 3));
            const thirdDrive = ratio(leg(p, 4, 5), leg(p, 3, 4));
            if (c.style.showRatios) {
                ratioLine(ctx, c, 0, 2, firstRetracement);
                ratioLine(ctx, c, 1, 3, secondDrive);
                ratioLine(ctx, c, 2, 4, secondRetracement);
                ratioLine(ctx, c, 3, 5, thirdDrive);
            }
            labels(ctx, c, ['', '1', 'A', '2', 'B', '3']);
        }
    });

    patternTool({
        type: 'headAndShoulders',
        points: 7,
        triangles: [[0, 1, 2], [2, 3, 4], [4, 5, 6]],
        defaults: {fillOpacity: 0.1, extendNeckline: true},
        extraProperties: [{key: 'extendNeckline', type: 'bool', tab: 'style'}],
        render(ctx, c) {
            const pts = c.points;
            if (c.style.fill) [[0, 1, 2], [2, 3, 4], [4, 5, 6]].forEach(t => Draw.fill(ctx, t.map(index => pts[index]), K.fillColor(c.style, 0.1)));
            Draw.polyline(ctx, pts, K.stroke(c));
            const [start, end] = K.extended(c, pts[2], pts[4], false, c.style.extendNeckline);
            Draw.line(ctx, start, end, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            const names = ['', i18n.t('draw.pattern.leftShoulder'), '', i18n.t('draw.pattern.head'), '', i18n.t('draw.pattern.rightShoulder'), ''];
            labels(ctx, c, names);
            if (c.style.validate && !c.draft) {
                const p = c.drawing.points;
                const top = isHigh(p, 3);
                const headExtreme = top ? p[3].price > Math.max(p[1].price, p[5].price) : p[3].price < Math.min(p[1].price, p[5].price);
                badge(ctx, c, pts[6], [i18n.t(headExtreme ? 'draw.pattern.headOk' : 'draw.pattern.headNotExtreme')], headExtreme);
            }
        }
    });

    function intersection(a, b, c2, d) {
        const denominator = (a.x - b.x) * (c2.y - d.y) - (a.y - b.y) * (c2.x - d.x);
        if (Math.abs(denominator) < 1e-9) return null;
        const t = ((a.x - c2.x) * (c2.y - d.y) - (a.y - c2.y) * (c2.x - d.x)) / denominator;
        return {x: a.x + t * (b.x - a.x), y: a.y + t * (b.y - a.y)};
    }

    patternTool({
        type: 'trianglePattern',
        points: 4,
        render(ctx, c) {
            const [a, b, third, d] = c.points;
            const apex = intersection(a, third, b, d);
            const reachable = apex && apex.x > Math.max(third.x, d.x) && apex.x < c.env.width * 3;
            const upperEnd = reachable ? apex : third;
            const lowerEnd = reachable ? apex : d;
            if (c.style.fill) Draw.fill(ctx, [a, upperEnd, lowerEnd, b], K.fillColor(c.style, 0.1));
            Draw.polyline(ctx, c.points, K.stroke(c));
            Draw.line(ctx, a, upperEnd, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            Draw.line(ctx, b, lowerEnd, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            labels(ctx, c, ['A', 'B', 'C', 'D']);
        }
    });

    // ---- Elliott waves

    const DEGREE_STYLES = ['numeric', 'parenthesized', 'circled', 'roman', 'romanUpper', 'bracketed'];
    const CIRCLED_DIGITS = ['⓪', '①', '②', '③', '④', '⑤'];
    const CIRCLED_LETTERS = {A: 'Ⓐ', B: 'Ⓑ', C: 'Ⓒ', D: 'Ⓓ', E: 'Ⓔ', W: 'Ⓦ', X: 'Ⓧ', Y: 'Ⓨ', Z: 'Ⓩ'};
    const ROMAN = ['', 'i', 'ii', 'iii', 'iv', 'v'];

    function waveLabel(name, style) {
        const numeric = /^\d$/.test(name);
        switch (style) {
            case 'parenthesized': return `(${name})`;
            case 'circled': return numeric ? CIRCLED_DIGITS[Number(name)] : (CIRCLED_LETTERS[name] || name);
            case 'roman': return numeric ? ROMAN[Number(name)] : name.toLowerCase();
            case 'romanUpper': return numeric ? ROMAN[Number(name)].toUpperCase() : name;
            case 'bracketed': return `[${name}]`;
            default: return name;
        }
    }

    function impulseRules(p) {
        const up = p[1].price > p[0].price;
        const sign = up ? 1 : -1;
        const broken = [];
        if (sign * (p[2].price - p[0].price) <= 0) broken.push('draw.elliott.rule.wave2');
        const lengths = [Math.abs(leg(p, 0, 1)), Math.abs(leg(p, 2, 3)), Math.abs(leg(p, 4, 5))];
        if (lengths[1] < lengths[0] && lengths[1] < lengths[2]) broken.push('draw.elliott.rule.wave3Shortest');
        if (sign * (p[3].price - p[1].price) <= 0) broken.push('draw.elliott.rule.wave3Beyond');
        if (sign * (p[4].price - p[1].price) <= 0) broken.push('draw.elliott.rule.wave4Overlap');
        return broken;
    }

    function elliottTool(type, names, check) {
        registerTool({
            type,
            group: 'elliott',
            points: names.length,
            constrainShift: false,
            defaults: {width: 2, lineStyle: 'solid', degree: 'parenthesized', validate: true, textColor: ''},
            properties: [...K.LINE_PROPERTIES, P.textColor, {key: 'degree', type: 'select', options: DEGREE_STYLES, tab: 'style'},
                {key: 'validate', type: 'bool', tab: 'style'}],
            render(ctx, c) {
                Draw.polyline(ctx, c.points, K.stroke(c));
                labels(ctx, c, names.map(name => (name ? waveLabel(name, c.style.degree) : '')));
                if (check && c.style.validate && !c.draft) {
                    const broken = check(c.drawing.points);
                    badge(ctx, c, c.points[c.points.length - 1],
                        broken.length ? broken.map(key => `✗ ${i18n.t(key)}`) : [`✓ ${i18n.t('draw.elliott.rulesMet')}`], !broken.length);
                }
            },
            hitTest(c, x, y, tolerance) { return K.hitPolyline(c.points, x, y, tolerance) ? {part: 'move'} : null; }
        });
    }

    elliottTool('elliottImpulse', ['0', '1', '2', '3', '4', '5'].map((name, index) => (index === 0 ? '' : name)), impulseRules);
    elliottTool('elliottCorrection', ['', 'A', 'B', 'C'], points => {
        const broken = [];
        const b = ratio(leg(points, 1, 2), leg(points, 0, 1));
        if (b >= 1.382) broken.push('draw.elliott.rule.bTooDeep');
        return broken;
    });
    elliottTool('elliottTriangle', ['', 'A', 'B', 'C', 'D', 'E'], points => {
        const lengths = [1, 2, 3, 4, 5].map(index => Math.abs(leg(points, index - 1, index)));
        const contracting = lengths.slice(1).every((length, index) => length <= lengths[index] * 1.05);
        return contracting ? [] : ['draw.elliott.rule.triangleContracting'];
    });
    elliottTool('elliottDoubleCombo', ['', 'W', 'X', 'Y'], null);
    elliottTool('elliottTripleCombo', ['', 'W', 'X', 'Y', 'X', 'Z'], null);
})();
