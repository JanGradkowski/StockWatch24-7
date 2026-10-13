(() => {
    'use strict';

    // Shapes, freehand tools and annotations.
    const {registerTool, Draw, Geometry} = window.StockWatchDrawing;
    const K = window.StockWatchDrawingKit;
    const {P} = K;
    const {withAlpha, contrastText} = Geometry;

    // Last drawn bounds of text-based drawings, for hit testing. Kept outside the drawing so it is never saved.
    const boxes = new WeakMap();

    const SHAPE_DEFAULTS = {width: 2, lineStyle: 'solid', fill: true, fillOpacity: 0.15, ...K.TEXT_DEFAULTS};
    const SHAPE_PROPERTIES = [...K.LINE_PROPERTIES, P.fill, P.fillColor, P.fillOpacity, ...K.TEXT_PROPERTIES];

    function centerText(ctx, c, center) {
        if (!c.style.text || !center) return;
        Draw.label(ctx, c.style.text, center.x, center.y, {
            align: 'center', textAlign: 'center', color: K.textColor(c), size: c.style.fontSize || 13,
            weight: c.style.bold ? 700 : 500, italic: c.style.italic
        });
    }

    function corners(a, b) {
        return [a, {x: b.x, y: a.y}, b, {x: a.x, y: b.y}];
    }

    registerTool({
        type: 'rectangle',
        group: 'shapes',
        shortcut: 'alt+shift+r',
        points: 2,
        constrainShift: false,
        defaults: {...SHAPE_DEFAULTS, extendLeft: false, extendRight: false, showMiddle: false},
        properties: [...SHAPE_PROPERTIES, P.extendLeft, P.extendRight, P.showMiddle],
        box(c) {
            const [a, b] = c.points;
            const left = c.style.extendLeft ? -10 : Math.min(a.x, b.x);
            const right = c.style.extendRight ? c.env.width + 10 : Math.max(a.x, b.x);
            return {left, right, top: Math.min(a.y, b.y), bottom: Math.max(a.y, b.y)};
        },
        render(ctx, c) {
            const box = this.box(c);
            Draw.rect(ctx, box.left, box.top, box.right - box.left, box.bottom - box.top, K.stroke(c), c.style.fill ? K.fillColor(c.style) : null);
            if (c.style.showMiddle) {
                const middle = (box.top + box.bottom) / 2;
                Draw.line(ctx, {x: box.left, y: middle}, {x: box.right, y: middle}, {color: c.style.color, width: 1, lineStyle: 'dashed'});
            }
            centerText(ctx, c, {x: (Math.max(box.left, 0) + Math.min(box.right, c.env.width)) / 2, y: (box.top + box.bottom) / 2});
        },
        handles(c) {
            const [a, b] = c.points;
            return [a, b];
        },
        hitTest(c, x, y, tolerance) {
            const box = this.box(c);
            const outline = [{x: box.left, y: box.top}, {x: box.right, y: box.top}, {x: box.right, y: box.bottom}, {x: box.left, y: box.bottom}];
            if (K.hitPolyline(outline, x, y, tolerance, true)) return {part: 'move'};
            return c.style.fill && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom ? {part: 'move'} : null;
        }
    });

    function rotatedCorners(c) {
        const [a, b, third] = c.points;
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const length = Math.hypot(dx, dy) || 1;
        const normal = {x: -dy / length, y: dx / length};
        const offset = (third.x - a.x) * normal.x + (third.y - a.y) * normal.y;
        return [a, b, {x: b.x + normal.x * offset, y: b.y + normal.y * offset}, {x: a.x + normal.x * offset, y: a.y + normal.y * offset}];
    }

    registerTool({
        type: 'rotatedRectangle',
        group: 'shapes',
        points: 3,
        constrainShift: false,
        defaults: {...SHAPE_DEFAULTS},
        properties: SHAPE_PROPERTIES,
        render(ctx, c) {
            const polygon = rotatedCorners(c);
            if (c.style.fill) Draw.fill(ctx, polygon, K.fillColor(c.style));
            Draw.polyline(ctx, polygon, K.stroke(c), true);
            const center = polygon.reduce((sum, point) => ({x: sum.x + point.x / 4, y: sum.y + point.y / 4}), {x: 0, y: 0});
            centerText(ctx, c, center);
        },
        handles(c) {
            const polygon = rotatedCorners(c);
            return [polygon[0], polygon[1], {x: (polygon[2].x + polygon[3].x) / 2, y: (polygon[2].y + polygon[3].y) / 2}];
        },
        hitTest(c, x, y, tolerance) {
            const polygon = rotatedCorners(c);
            return K.hitPolyline(polygon, x, y, tolerance, true) || (c.style.fill && K.hitInside(polygon, x, y)) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'circle',
        group: 'shapes',
        points: 2,
        constrainShift: false,
        defaults: {...SHAPE_DEFAULTS},
        properties: SHAPE_PROPERTIES,
        render(ctx, c) {
            const [center, edge] = c.points;
            Draw.circle(ctx, center, Math.hypot(edge.x - center.x, edge.y - center.y), K.stroke(c), c.style.fill ? K.fillColor(c.style) : null);
            centerText(ctx, c, center);
        },
        hitTest(c, x, y, tolerance) {
            const [center, edge] = c.points;
            const radius = Math.hypot(edge.x - center.x, edge.y - center.y);
            const fromCenter = Math.hypot(x - center.x, y - center.y);
            return Math.abs(fromCenter - radius) <= tolerance || (c.style.fill && fromCenter <= radius) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'ellipse',
        group: 'shapes',
        points: 2,
        constrainShift: false,
        defaults: {...SHAPE_DEFAULTS},
        properties: SHAPE_PROPERTIES,
        render(ctx, c) {
            const [a, b] = c.points;
            const center = {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2};
            Draw.ellipse(ctx, center, Math.abs(b.x - a.x) / 2, Math.abs(b.y - a.y) / 2, 0, K.stroke(c), c.style.fill ? K.fillColor(c.style) : null);
            centerText(ctx, c, center);
        },
        hitTest(c, x, y, tolerance) {
            const [a, b] = c.points;
            const rx = Math.abs(b.x - a.x) / 2;
            const ry = Math.abs(b.y - a.y) / 2;
            if (rx < 1 || ry < 1) return null;
            const center = {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2};
            const value = ((x - center.x) / rx) ** 2 + ((y - center.y) / ry) ** 2;
            const edge = Math.abs(Math.sqrt(value) - 1) * Math.min(rx, ry);
            return edge <= tolerance || (c.style.fill && value <= 1) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'triangle',
        group: 'shapes',
        points: 3,
        constrainShift: false,
        defaults: {...SHAPE_DEFAULTS},
        properties: SHAPE_PROPERTIES,
        render(ctx, c) {
            if (c.style.fill) Draw.fill(ctx, c.points, K.fillColor(c.style));
            Draw.polyline(ctx, c.points, K.stroke(c), true);
            centerText(ctx, c, c.points.reduce((sum, point) => ({x: sum.x + point.x / 3, y: sum.y + point.y / 3}), {x: 0, y: 0}));
        },
        hitTest(c, x, y, tolerance) {
            return K.hitPolyline(c.points, x, y, tolerance, true) || (c.style.fill && K.hitInside(c.points, x, y)) ? {part: 'move'} : null;
        }
    });

    function quadraticPoints(a, control, b, steps = 40) {
        const points = [];
        for (let step = 0; step <= steps; step++) {
            const t = step / steps;
            points.push({
                x: (1 - t) ** 2 * a.x + 2 * (1 - t) * t * control.x + t * t * b.x,
                y: (1 - t) ** 2 * a.y + 2 * (1 - t) * t * control.y + t * t * b.y
            });
        }
        return points;
    }

    function cubicPoints(a, first, second, b, steps = 50) {
        const points = [];
        for (let step = 0; step <= steps; step++) {
            const t = step / steps;
            const u = 1 - t;
            points.push({
                x: u ** 3 * a.x + 3 * u * u * t * first.x + 3 * u * t * t * second.x + t ** 3 * b.x,
                y: u ** 3 * a.y + 3 * u * u * t * first.y + 3 * u * t * t * second.y + t ** 3 * b.y
            });
        }
        return points;
    }

    registerTool({
        type: 'arc',
        group: 'shapes',
        points: 3,
        constrainShift: false,
        defaults: {...SHAPE_DEFAULTS},
        properties: SHAPE_PROPERTIES,
        curve(c) {
            const [a, b, through] = c.points;
            const control = {x: 2 * through.x - (a.x + b.x) / 2, y: 2 * through.y - (a.y + b.y) / 2};
            return quadraticPoints(a, control, b);
        },
        render(ctx, c) {
            const curve = this.curve(c);
            if (c.style.fill) Draw.fill(ctx, curve, K.fillColor(c.style));
            Draw.polyline(ctx, curve, K.stroke(c));
        },
        handles: c => c.points,
        hitTest(c, x, y, tolerance) {
            const curve = this.curve(c);
            return K.hitPolyline(curve, x, y, tolerance) || (c.style.fill && K.hitInside(curve, x, y)) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'curve',
        group: 'shapes',
        points: 3,
        constrainShift: false,
        defaults: {width: 2, lineStyle: 'solid', arrowEnd: false},
        properties: [...K.LINE_PROPERTIES, P.arrowEnd],
        curve(c) {
            const [a, b, through] = c.points;
            return quadraticPoints(a, {x: 2 * through.x - (a.x + b.x) / 2, y: 2 * through.y - (a.y + b.y) / 2}, b);
        },
        render(ctx, c) {
            const curve = this.curve(c);
            Draw.polyline(ctx, curve, K.stroke(c));
            if (c.style.arrowEnd) Draw.arrowHead(ctx, curve[curve.length - 2], curve[curve.length - 1], c.style.color, 10);
        },
        hitTest(c, x, y, tolerance) { return K.hitPolyline(this.curve(c), x, y, tolerance) ? {part: 'move'} : null; }
    });

    registerTool({
        type: 'doubleCurve',
        group: 'shapes',
        points: 4,
        constrainShift: false,
        defaults: {width: 2, lineStyle: 'solid', arrowEnd: false},
        properties: [...K.LINE_PROPERTIES, P.arrowEnd],
        curve(c) {
            const [a, b, first, second] = c.points;
            return cubicPoints(a, first, second, b);
        },
        render(ctx, c) {
            const curve = this.curve(c);
            Draw.polyline(ctx, curve, K.stroke(c));
            if (c.style.arrowEnd) Draw.arrowHead(ctx, curve[curve.length - 2], curve[curve.length - 1], c.style.color, 10);
            if (c.selected) {
                Draw.line(ctx, c.points[0], c.points[2], {color: withAlpha(c.style.color, 0.4), width: 1, lineStyle: 'dotted'});
                Draw.line(ctx, c.points[1], c.points[3], {color: withAlpha(c.style.color, 0.4), width: 1, lineStyle: 'dotted'});
            }
        },
        hitTest(c, x, y, tolerance) { return K.hitPolyline(this.curve(c), x, y, tolerance) ? {part: 'move'} : null; }
    });

    registerTool({
        type: 'path',
        group: 'shapes',
        placement: 'path',
        points: 2,
        maxPoints: 80,
        constrainShift: false,
        defaults: {width: 2, lineStyle: 'solid', arrowEnd: true},
        properties: [...K.LINE_PROPERTIES, P.arrowEnd],
        render(ctx, c) {
            Draw.polyline(ctx, c.points, K.stroke(c));
            const valid = c.points.filter(Boolean);
            if (c.style.arrowEnd && valid.length > 1) Draw.arrowHead(ctx, valid[valid.length - 2], valid[valid.length - 1], c.style.color, 10);
        },
        hitTest(c, x, y, tolerance) { return K.hitPolyline(c.points, x, y, tolerance) ? {part: 'move'} : null; }
    });

    registerTool({
        type: 'polyline',
        group: 'shapes',
        placement: 'path',
        points: 2,
        maxPoints: 80,
        constrainShift: false,
        defaults: {...SHAPE_DEFAULTS, closed: true},
        properties: [...K.LINE_PROPERTIES, P.fill, P.fillColor, P.fillOpacity, {key: 'closed', type: 'bool', tab: 'style'}],
        render(ctx, c) {
            if (c.style.fill && c.style.closed && c.points.length > 2) Draw.fill(ctx, c.points, K.fillColor(c.style));
            Draw.polyline(ctx, c.points, K.stroke(c), c.style.closed);
        },
        hitTest(c, x, y, tolerance) {
            return K.hitPolyline(c.points, x, y, tolerance, c.style.closed)
                || (c.style.fill && c.style.closed && K.hitInside(c.points, x, y)) ? {part: 'move'} : null;
        }
    });

    function freehand(type, defaults) {
        registerTool({
            type,
            group: 'shapes',
            placement: 'freehand',
            points: 2,
            axisLabels: 'none',
            defaults,
            properties: [P.color, P.width],
            handles: () => [],
            render(ctx, c) {
                const valid = c.points.filter(Boolean);
                if (valid.length < 2) return;
                ctx.save();
                ctx.strokeStyle = type === 'highlighter' ? withAlpha(c.style.color, 0.35) : c.style.color;
                ctx.lineWidth = c.style.width;
                ctx.lineCap = 'round';
                ctx.lineJoin = 'round';
                ctx.beginPath();
                ctx.moveTo(valid[0].x, valid[0].y);
                for (let index = 1; index < valid.length - 1; index++) {
                    const middle = {x: (valid[index].x + valid[index + 1].x) / 2, y: (valid[index].y + valid[index + 1].y) / 2};
                    ctx.quadraticCurveTo(valid[index].x, valid[index].y, middle.x, middle.y);
                }
                ctx.lineTo(valid[valid.length - 1].x, valid[valid.length - 1].y);
                ctx.stroke();
                if (c.selected) {
                    const xs = valid.map(point => point.x);
                    const ys = valid.map(point => point.y);
                    Draw.rect(ctx, Math.min(...xs) - 4, Math.min(...ys) - 4, Math.max(...xs) - Math.min(...xs) + 8, Math.max(...ys) - Math.min(...ys) + 8,
                        {color: c.env.palette.selection, width: 1, lineStyle: 'dashed'});
                }
                ctx.restore();
            },
            hitTest(c, x, y, tolerance) { return K.hitPolyline(c.points, x, y, tolerance + c.style.width / 2) ? {part: 'move'} : null; }
        });
    }

    freehand('brush', {width: 2});
    freehand('highlighter', {color: '#f6df69', width: 14});

    function arrowShape(tail, head, width) {
        const dx = head.x - tail.x;
        const dy = head.y - tail.y;
        const length = Math.hypot(dx, dy) || 1;
        const ux = dx / length;
        const uy = dy / length;
        const nx = -uy;
        const ny = ux;
        const headLength = Math.min(length * 0.45, width * 2.4);
        const shaft = width / 2;
        const neck = {x: head.x - ux * headLength, y: head.y - uy * headLength};
        return [
            {x: tail.x + nx * shaft, y: tail.y + ny * shaft}, {x: neck.x + nx * shaft, y: neck.y + ny * shaft},
            {x: neck.x + nx * width, y: neck.y + ny * width}, head, {x: neck.x - nx * width, y: neck.y - ny * width},
            {x: neck.x - nx * shaft, y: neck.y - ny * shaft}, {x: tail.x - nx * shaft, y: tail.y - ny * shaft}
        ];
    }

    registerTool({
        type: 'arrowMarker',
        group: 'shapes',
        points: 2,
        constrainShift: false,
        defaults: {color: '#2962ff', size: 14, ...K.TEXT_DEFAULTS},
        properties: [P.color, {key: 'size', type: 'number', min: 6, max: 40, step: 1, tab: 'style'}, ...K.TEXT_PROPERTIES],
        render(ctx, c) {
            const [tail, head] = c.points;
            const polygon = arrowShape(tail, head, c.style.size || 14);
            Draw.fill(ctx, polygon, c.style.color);
            if (c.style.text) {
                Draw.label(ctx, c.style.text, tail.x, tail.y + (tail.y >= head.y ? 10 : -10), {
                    align: 'center', baseline: tail.y >= head.y ? 'top' : 'bottom', color: K.textColor(c), size: c.style.fontSize, weight: c.style.bold ? 700 : 500
                });
            }
        },
        hitTest(c, x, y) { return K.hitInside(arrowShape(c.points[0], c.points[1], c.style.size || 14), x, y) ? {part: 'move'} : null; }
    });

    function markTool(type, up) {
        registerTool({
            type,
            group: 'shapes',
            points: 1,
            placement: 'single',
            keepDefaultColor: true,
            axisLabels: 'none',
            defaults: {color: up ? '#26a69a' : '#ef5350', size: 14, ...K.TEXT_DEFAULTS},
            properties: [P.color, {key: 'size', type: 'number', min: 6, max: 40, step: 1, tab: 'style'}, ...K.TEXT_PROPERTIES],
            shape(c) {
                const point = c.points[0];
                const size = c.style.size || 14;
                const direction = up ? 1 : -1;
                const head = {x: point.x, y: point.y + direction * 2};
                const tail = {x: point.x, y: point.y + direction * (size * 2.4)};
                return arrowShape(tail, head, size * 0.8);
            },
            render(ctx, c) {
                Draw.fill(ctx, this.shape(c), c.style.color);
                if (c.style.text) {
                    const point = c.points[0];
                    const size = c.style.size || 14;
                    Draw.label(ctx, c.style.text, point.x, point.y + (up ? 1 : -1) * (size * 2.6 + 4), {
                        align: 'center', baseline: up ? 'top' : 'bottom', color: K.textColor(c), size: c.style.fontSize
                    });
                }
            },
            hitTest(c, x, y) { return K.hitInside(this.shape(c), x, y) ? {part: 'move'} : null; }
        });
    }

    markTool('arrowMarkUp', true);
    markTool('arrowMarkDown', false);

    // ---- annotations

    const TEXT_BOX_PROPERTIES = [P.textColor, P.fontSize, P.bold, P.italic, P.textBackground, P.textBackgroundColor, P.textBorder, P.color];

    function textBox(ctx, c, point, options = {}) {
        const text = c.style.text || (c.draft ? '' : window.StockWatchI18n.t('draw.label.addText'));
        const background = c.style.textBackground ? withAlpha(c.style.textBackgroundColor || c.style.color, 0.9) : null;
        return Draw.label(ctx, text, point.x, point.y, {
            baseline: options.baseline || 'middle', align: options.align || 'left',
            color: c.style.textColor || (background ? contrastText(c.style.textBackgroundColor || c.style.color) : c.style.color),
            background, border: c.style.textBorder ? c.style.color : null, size: c.style.fontSize || 14,
            weight: c.style.bold ? 700 : 500, italic: c.style.italic, padding: background || c.style.textBorder ? 6 : 0
        });
    }

    function textTool(type, screenAnchored) {
        registerTool({
            type,
            group: 'annotations',
            points: 1,
            placement: 'single',
            screenAnchored,
            editText: true,
            axisLabels: screenAnchored ? 'none' : undefined,
            defaults: {color: '#2962ff', text: '', textColor: '', fontSize: 14, bold: false, italic: false, textBackground: false,
                textBackgroundColor: '#2962ff', textBorder: false},
            properties: [P.text, ...TEXT_BOX_PROPERTIES],
            render(ctx, c) {
                boxes.set(c.drawing, textBox(ctx, c, c.points[0], {baseline: 'bottom'}));
            },
            handles: c => [c.points[0]],
            hitTest(c, x, y, tolerance) {
                const box = boxes.get(c.drawing);
                if (box && x >= box.x - tolerance && x <= box.x + box.width + tolerance && y >= box.y - tolerance && y <= box.y + box.height + tolerance) {
                    return {part: 'move'};
                }
                return Math.hypot(x - c.points[0].x, y - c.points[0].y) <= tolerance ? {part: 'move'} : null;
            }
        });
    }

    textTool('text', false);
    textTool('anchoredText', true);

    function noteTool(type, screenAnchored) {
        registerTool({
            type,
            group: 'annotations',
            points: 1,
            placement: 'single',
            screenAnchored,
            editText: true,
            axisLabels: screenAnchored ? 'none' : undefined,
            defaults: {color: '#2962ff', text: '', textColor: '', fontSize: 13, bold: false, italic: false, textBackground: true,
                textBackgroundColor: '#1c2633', textBorder: true},
            properties: [P.text, ...TEXT_BOX_PROPERTIES],
            render(ctx, c) {
                const point = c.points[0];
                Draw.circle(ctx, point, 6, {color: c.style.color, width: 2}, withAlpha(c.style.color, 0.85));
                Draw.label(ctx, '✎', point.x, point.y, {align: 'center', color: contrastText(c.style.color), size: 9});
                if (c.selected || c.hovered || c.style.alwaysShow !== false) {
                    boxes.set(c.drawing, textBox(ctx, c, {x: point.x + 12, y: point.y - 12}, {baseline: 'bottom'}));
                }
            },
            handles: c => [c.points[0]],
            hitTest(c, x, y, tolerance) {
                if (Math.hypot(x - c.points[0].x, y - c.points[0].y) <= tolerance + 4) return {part: 'move'};
                const box = boxes.get(c.drawing);
                return box && x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height ? {part: 'move'} : null;
            }
        });
    }

    noteTool('note', false);
    noteTool('anchoredNote', true);

    registerTool({
        type: 'callout',
        group: 'annotations',
        points: 2,
        editText: true,
        constrainShift: false,
        defaults: {color: '#2962ff', text: '', textColor: '', fontSize: 13, bold: false, italic: false, textBackground: true,
            textBackgroundColor: '#2962ff', textBorder: false},
        properties: [P.text, ...TEXT_BOX_PROPERTIES],
        render(ctx, c) {
            const [target, anchor] = c.points;
            const box = textBox(ctx, c, anchor, {baseline: 'middle', align: 'center'});
            if (!box) return;
            boxes.set(c.drawing, box);
            const center = {x: box.x + box.width / 2, y: box.y + box.height / 2};
            const base = Math.min(12, box.width / 3);
            const angle = Math.atan2(target.y - center.y, target.x - center.x);
            Draw.fill(ctx, [target, {x: center.x + Math.cos(angle + Math.PI / 2) * base, y: center.y + Math.sin(angle + Math.PI / 2) * base},
                {x: center.x - Math.cos(angle + Math.PI / 2) * base, y: center.y - Math.sin(angle + Math.PI / 2) * base}],
                c.style.textBackground ? withAlpha(c.style.textBackgroundColor || c.style.color, 0.9) : withAlpha(c.style.color, 0.4));
            textBox(ctx, c, anchor, {baseline: 'middle', align: 'center'});
        },
        hitTest(c, x, y, tolerance) {
            const box = boxes.get(c.drawing);
            if (box && x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height) return {part: 'move'};
            return K.hitSegment(c.points[0], c.points[1], x, y, tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'comment',
        group: 'annotations',
        points: 1,
        placement: 'single',
        editText: true,
        defaults: {color: '#2962ff', text: '', textColor: '', fontSize: 13, bold: false, italic: false, textBackground: true,
            textBackgroundColor: '#2962ff', textBorder: false},
        properties: [P.text, ...TEXT_BOX_PROPERTIES],
        render(ctx, c) {
            const point = c.points[0];
            const box = textBox(ctx, c, {x: point.x + 8, y: point.y - 14}, {baseline: 'bottom'});
            if (!box) return;
            boxes.set(c.drawing, box);
            Draw.fill(ctx, [point, {x: box.x + 6, y: box.y + box.height}, {x: box.x + 20, y: box.y + box.height}],
                c.style.textBackground ? withAlpha(c.style.textBackgroundColor || c.style.color, 0.9) : withAlpha(c.style.color, 0.4));
        },
        handles: c => [c.points[0]],
        hitTest(c, x, y, tolerance) {
            const box = boxes.get(c.drawing);
            if (box && x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height + 14) return {part: 'move'};
            return Math.hypot(x - c.points[0].x, y - c.points[0].y) <= tolerance ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'priceLabel',
        group: 'annotations',
        points: 1,
        placement: 'single',
        axisLabels: 'always',
        defaults: {color: '#2962ff', fontSize: 12},
        properties: [P.color, P.fontSize],
        render(ctx, c) {
            const point = c.points[0];
            const text = c.env.format.price(c.drawing.points[0].price);
            const box = Draw.label(ctx, text, point.x + 14, point.y - 14, {
                baseline: 'bottom', background: c.style.color, color: contrastText(c.style.color), size: c.style.fontSize || 12, weight: 700
            });
            boxes.set(c.drawing, box);
            if (box) Draw.fill(ctx, [point, {x: box.x, y: box.y + box.height - 8}, {x: box.x + 10, y: box.y + box.height}], c.style.color);
        },
        handles: c => [c.points[0]],
        hitTest(c, x, y, tolerance) {
            const box = boxes.get(c.drawing);
            if (box && x >= box.x - tolerance && x <= box.x + box.width && y >= box.y && y <= box.y + box.height + 14) return {part: 'move'};
            return Math.hypot(x - c.points[0].x, y - c.points[0].y) <= tolerance ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'priceNote',
        group: 'annotations',
        points: 2,
        editText: true,
        constrainShift: false,
        defaults: {color: '#2962ff', text: '', textColor: '', fontSize: 12, bold: false, italic: false, textBackground: true,
            textBackgroundColor: '#2962ff', textBorder: false},
        properties: [P.text, ...TEXT_BOX_PROPERTIES],
        axisPrices: drawing => [drawing.points[0].price],
        render(ctx, c) {
            const [point, anchor] = c.points;
            Draw.line(ctx, point, anchor, {color: c.style.color, width: 1});
            Draw.circle(ctx, point, 3, {color: c.style.color, width: 2}, c.env.palette.handleFill);
            const price = c.env.format.price(c.drawing.points[0].price);
            const original = c.style.text;
            c.style.text = original ? `${price}\n${original}` : price;
            boxes.set(c.drawing, textBox(ctx, c, anchor, {baseline: 'middle', align: anchor.x >= point.x ? 'left' : 'right'}));
            c.style.text = original;
        },
        hitTest(c, x, y, tolerance) {
            const box = boxes.get(c.drawing);
            if (box && x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height) return {part: 'move'};
            return K.hitSegment(c.points[0], c.points[1], x, y, tolerance) ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'signpost',
        group: 'annotations',
        points: 1,
        placement: 'single',
        editText: true,
        axisLabels: 'none',
        defaults: {color: '#2962ff', text: '', textColor: '', fontSize: 12, bold: false, italic: false, position: 'above', textBackground: true,
            textBackgroundColor: '#2962ff', textBorder: false},
        properties: [P.text, ...TEXT_BOX_PROPERTIES, {key: 'position', type: 'select', options: ['above', 'below'], tab: 'style'}],
        create(drawing, env) {
            const bar = env.bar(drawing.points[0].time);
            if (bar) drawing.points[0].price = drawing.style.position === 'below' ? bar.low : bar.high;
        },
        render(ctx, c) {
            const point = c.points[0];
            const above = c.style.position !== 'below';
            const end = {x: point.x, y: point.y + (above ? -46 : 46)};
            Draw.line(ctx, point, end, {color: c.style.color, width: 2});
            Draw.circle(ctx, end, 5, {color: c.style.color, width: 2}, c.style.color);
            boxes.set(c.drawing, textBox(ctx, c, {x: end.x, y: end.y + (above ? -8 : 8)}, {baseline: above ? 'bottom' : 'top', align: 'center'}));
        },
        hitTest(c, x, y, tolerance) {
            const point = c.points[0];
            const above = c.style.position !== 'below';
            if (K.hitSegment(point, {x: point.x, y: point.y + (above ? -46 : 46)}, x, y, tolerance)) return {part: 'move'};
            const box = boxes.get(c.drawing);
            return box && x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'flagMark',
        group: 'annotations',
        points: 1,
        placement: 'single',
        axisLabels: 'none',
        defaults: {color: '#ef5350'},
        properties: [P.color],
        shape(c) {
            const point = c.points[0];
            return [{x: point.x, y: point.y - 26}, {x: point.x + 18, y: point.y - 21}, {x: point.x, y: point.y - 15}];
        },
        render(ctx, c) {
            const point = c.points[0];
            Draw.line(ctx, point, {x: point.x, y: point.y - 26}, {color: c.style.color, width: 2});
            Draw.fill(ctx, this.shape(c), c.style.color);
        },
        hitTest(c, x, y, tolerance) {
            const point = c.points[0];
            return x >= point.x - tolerance && x <= point.x + 20 && y >= point.y - 28 && y <= point.y + tolerance ? {part: 'move'} : null;
        }
    });

    registerTool({
        type: 'table',
        group: 'annotations',
        points: 1,
        placement: 'single',
        editText: true,
        axisLabels: 'none',
        defaults: {color: '#2962ff', text: '', fontSize: 12, textColor: '', textBackgroundColor: '#1c2633'},
        properties: [{key: 'text', type: 'text', tab: 'text', hint: 'draw.prop.tableHint'}, P.textColor, P.fontSize, P.color, P.textBackgroundColor],
        render(ctx, c) {
            const point = c.points[0];
            const rows = String(c.style.text || window.StockWatchI18n.t('draw.label.tableExample')).split('\n').slice(0, 20)
                .map(row => row.split('|').map(cell => cell.trim()).slice(0, 8));
            const size = c.style.fontSize || 12;
            ctx.save();
            ctx.font = Draw.font(size, 500);
            const columns = Math.max(...rows.map(row => row.length));
            const widths = Array.from({length: columns}, (unused, column) => Math.max(30, ...rows.map(row => ctx.measureText(row[column] || '').width + 14)));
            const rowHeight = size + 10;
            const width = widths.reduce((sum, value) => sum + value, 0);
            const height = rowHeight * rows.length;
            ctx.fillStyle = withAlpha(c.style.textBackgroundColor || '#1c2633', 0.92);
            ctx.fillRect(point.x, point.y, width, height);
            ctx.strokeStyle = withAlpha(c.style.color, 0.8);
            ctx.lineWidth = 1;
            ctx.strokeRect(point.x, point.y, width, height);
            let x = point.x;
            widths.forEach((columnWidth, column) => {
                if (column > 0) { ctx.beginPath(); ctx.moveTo(x, point.y); ctx.lineTo(x, point.y + height); ctx.stroke(); }
                rows.forEach((row, rowIndex) => {
                    if (rowIndex > 0 && column === 0) {
                        ctx.beginPath();
                        ctx.moveTo(point.x, point.y + rowIndex * rowHeight);
                        ctx.lineTo(point.x + width, point.y + rowIndex * rowHeight);
                        ctx.stroke();
                    }
                    ctx.fillStyle = c.style.textColor || c.env.palette.text;
                    ctx.font = Draw.font(size, rowIndex === 0 ? 700 : 500);
                    ctx.textBaseline = 'middle';
                    ctx.fillText(row[column] || '', x + 7, point.y + rowIndex * rowHeight + rowHeight / 2);
                });
                x += columnWidth;
            });
            ctx.restore();
            boxes.set(c.drawing, {x: point.x, y: point.y, width, height});
        },
        handles: c => [c.points[0]],
        hitTest(c, x, y) {
            const box = boxes.get(c.drawing);
            return box && x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height ? {part: 'move'} : null;
        }
    });

    const EMOJIS = ['🚀', '🔥', '⭐', '✅', '❌', '⚠️', '💡', '📈', '📉', '🎯', '🐂', '🐻', '💰', '⏰', '❓', '👀'];

    registerTool({
        type: 'emoji',
        group: 'annotations',
        points: 1,
        placement: 'single',
        axisLabels: 'none',
        keepDefaultColor: true,
        defaults: {emoji: '🚀', size: 28},
        properties: [{key: 'emoji', type: 'select', options: EMOJIS, literalOptions: true, tab: 'style'},
            {key: 'size', type: 'number', min: 12, max: 96, step: 2, tab: 'style'}],
        render(ctx, c) {
            const point = c.points[0];
            const size = c.style.size || 28;
            ctx.save();
            ctx.font = `${size}px "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", sans-serif`;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(EMOJIS.includes(c.style.emoji) ? c.style.emoji : EMOJIS[0], point.x, point.y);
            ctx.restore();
        },
        hitTest(c, x, y) {
            const size = (c.style.size || 28) / 2 + 4;
            return Math.abs(x - c.points[0].x) <= size && Math.abs(y - c.points[0].y) <= size ? {part: 'move'} : null;
        }
    });

    window.StockWatchDrawingEmojis = EMOJIS;
})();
