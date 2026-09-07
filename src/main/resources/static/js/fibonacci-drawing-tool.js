(() => {
    'use strict';

    const LEVELS = [
        {value: -0.618, color: '#9c27b0'},
        {value: -0.272, color: '#ab47bc'},
        {value: 0, color: '#787b86'},
        {value: 0.236, color: '#f23645'},
        {value: 0.382, color: '#ff9800'},
        {value: 0.5, color: '#f6c344'},
        {value: 0.618, color: '#089981'},
        {value: 0.786, color: '#2962ff'},
        {value: 1, color: '#7b61ff'},
        {value: 1.272, color: '#536dfe'},
        {value: 1.618, color: '#00acc1'},
        {value: 2, color: '#26a69a'},
        {value: 2.618, color: '#43a047'}
    ];
    const MAX_DRAWINGS = 25;
    const HIT_DISTANCE = 9;

    class FibonacciDrawingTool {
        constructor({chart, series, container, storageKey, priceFormatter} = {}) {
            if (!chart || !series || !container) {
                throw new Error('A chart, price series, and chart container are required.');
            }
            this.chart = chart;
            this.series = series;
            this.container = container;
            this.priceFormatter = typeof priceFormatter === 'function' ? priceFormatter : formatPrice;
            this.storageKey = storageKey || window.location.pathname;
            this.drawings = [];
            this.selectedId = null;
            this.mode = 'navigate';
            this.draft = null;
            this.drag = null;
            this.clearArmed = false;
            this.clearTimer = null;
            this.destroyed = false;
            this.frame = null;

            this.canvas = document.createElement('canvas');
            this.canvas.className = 'fibonacci-drawing-canvas';
            this.canvas.setAttribute('aria-hidden', 'true');
            this.toolbar = this.buildToolbar();
            this.container.classList.add('fibonacci-drawing-host');
            this.container.append(this.canvas, this.toolbar);

            this.onPointerDown = event => this.handlePointerDown(event);
            this.onPointerMove = event => this.handlePointerMove(event);
            this.onPointerUp = event => this.handlePointerUp(event);
            this.onContextMenu = event => this.handleContextMenu(event);
            this.onKeyDown = event => this.handleKeyDown(event);
            this.onChartInteraction = () => this.scheduleRender();
            this.onThemeChange = () => this.scheduleRender();
            this.canvas.addEventListener('pointerdown', this.onPointerDown);
            this.canvas.addEventListener('pointermove', this.onPointerMove);
            this.canvas.addEventListener('pointerup', this.onPointerUp);
            this.canvas.addEventListener('pointercancel', this.onPointerUp);
            this.container.addEventListener('contextmenu', this.onContextMenu);
            this.container.addEventListener('wheel', this.onChartInteraction, {passive: true});
            this.container.addEventListener('pointermove', this.onChartInteraction, {passive: true});
            document.addEventListener('keydown', this.onKeyDown);
            window.addEventListener('stockwatch:themechange', this.onThemeChange);
            this.chart.timeScale().subscribeVisibleLogicalRangeChange(this.onChartInteraction);
            this.resizeObserver = typeof ResizeObserver === 'undefined'
                ? null
                : new ResizeObserver(() => this.scheduleRender());
            this.resizeObserver?.observe(this.container);

            this.load();
            this.updateToolbar();
            this.scheduleRender();
        }

        buildToolbar() {
            const toolbar = document.createElement('div');
            toolbar.className = 'fibonacci-drawing-toolbar';
            toolbar.setAttribute('role', 'toolbar');
            toolbar.setAttribute('aria-label', 'Chart drawing tools');

            this.navigateButton = createButton('Navigate', 'Move and zoom the chart');
            this.navigateButton.dataset.fibonacciAction = 'navigate';
            this.fibonacciButton = createButton('Fib', 'Draw or edit Fibonacci retracements and extensions. Right-click to stop drawing.');
            this.fibonacciButton.dataset.fibonacciAction = 'draw';
            this.longButton = createButton('Long', 'Draw a long position with target, stop, and risk/reward');
            this.longButton.dataset.fibonacciAction = 'long';
            this.shortButton = createButton('Short', 'Draw a short position with target, stop, and risk/reward');
            this.shortButton.dataset.fibonacciAction = 'short';
            this.deleteButton = createButton('Delete', 'Delete the selected drawing');
            this.deleteButton.dataset.fibonacciAction = 'delete';
            this.clearButton = createButton('Clear all', 'Remove every drawing from this chart');
            this.clearButton.dataset.fibonacciAction = 'clear';
            this.status = document.createElement('span');
            this.status.className = 'fibonacci-drawing-status';
            this.status.setAttribute('role', 'status');
            this.status.textContent = 'Drawing tools';

            this.navigateButton.addEventListener('click', () => this.setMode('navigate'));
            this.fibonacciButton.addEventListener('click', () => this.setMode('fibonacci'));
            this.longButton.addEventListener('click', () => this.setMode('long'));
            this.shortButton.addEventListener('click', () => this.setMode('short'));
            this.deleteButton.addEventListener('click', () => this.deleteSelected());
            this.clearButton.addEventListener('click', () => this.clearAll());
            toolbar.append(
                this.navigateButton,
                this.fibonacciButton,
                this.longButton,
                this.shortButton,
                this.deleteButton,
                this.clearButton,
                this.status);
            return toolbar;
        }

        setMode(mode) {
            this.mode = ['fibonacci', 'long', 'short'].includes(mode) ? mode : 'navigate';
            this.draft = null;
            this.drag = null;
            if (this.mode === 'navigate') {
                this.status.textContent = this.drawings.length ? 'Drawings saved' : 'Drawing tools';
            } else if (this.mode === 'fibonacci') {
                this.status.textContent = this.selectedId
                    ? 'Drag a drawing or click empty space for a new Fibonacci'
                    : 'Fibonacci: click the first anchor';
            } else {
                this.status.textContent = `${capitalize(this.mode)} position: click entry`;
            }
            this.updateToolbar();
            this.scheduleRender();
        }

        setContext(storageKey) {
            const nextKey = storageKey || window.location.pathname;
            if (nextKey === this.storageKey) return;
            this.save();
            this.storageKey = nextKey;
            this.selectedId = null;
            this.draft = null;
            this.drag = null;
            this.load();
            this.setMode('navigate');
        }

        handlePointerDown(event) {
            if (!isDrawingMode(this.mode) || event.button !== 0) return;
            const point = this.pointFromEvent(event);
            if (!point) return;
            event.preventDefault();

            if (this.draft) {
                if (drawingType(this.draft) === 'fibonacci') {
                    this.draft.end = stripCoordinates(point);
                    this.completeDraft();
                } else {
                    this.advancePositionDraft(point);
                }
                return;
            }

            const hit = this.hitTest(point.x, point.y);
            if (hit) {
                this.selectedId = hit.drawing.id;
                const start = this.screenPoints(hit.drawing);
                this.drag = {
                    pointerId: event.pointerId,
                    type: hit.type,
                    drawing: hit.drawing,
                    pointer: {x: point.x, y: point.y},
                    start,
                    prices: isPositionDrawing(hit.drawing) ? {
                        entry: Number(hit.drawing.entryPrice),
                        target: Number(hit.drawing.targetPrice),
                        stop: Number(hit.drawing.stopPrice)
                    } : null
                };
                this.canvas.setPointerCapture(event.pointerId);
                this.status.textContent = hit.type === 'move' ? 'Move the drawing' : 'Adjust the selected level';
            } else {
                this.selectedId = null;
                if (this.mode === 'fibonacci') {
                    this.draft = {
                        type: 'fibonacci',
                        start: stripCoordinates(point),
                        end: stripCoordinates(point)
                    };
                    this.status.textContent = 'Fibonacci: click the second anchor';
                } else {
                    this.draft = {
                        type: this.mode,
                        start: positionAnchor(point, point.price),
                        end: positionAnchor(point, point.price),
                        entryPrice: point.price,
                        targetPrice: point.price,
                        stopPrice: point.price,
                        stage: 'target'
                    };
                    this.status.textContent = `${capitalize(this.mode)} position: click the profit target`;
                }
            }
            this.updateToolbar();
            this.scheduleRender();
        }

        advancePositionDraft(point) {
            const type = drawingType(this.draft);
            if (this.draft.stage === 'target') {
                if (!validTarget(type, this.draft.entryPrice, point.price)) {
                    this.status.textContent = type === 'long'
                        ? 'Long target must be above entry'
                        : 'Short target must be below entry';
                    return;
                }
                this.draft.targetPrice = point.price;
                this.draft.end = positionAnchor(point, this.draft.entryPrice);
                this.draft.stage = 'stop';
                this.status.textContent = `${capitalize(type)} position: click the stop loss`;
                this.scheduleRender();
                return;
            }
            if (!validStop(type, this.draft.entryPrice, point.price)) {
                this.status.textContent = type === 'long'
                    ? 'Long stop must be below entry'
                    : 'Short stop must be above entry';
                return;
            }
            this.draft.stopPrice = point.price;
            this.completeDraft();
        }

        completeDraft() {
            const {stage, ...draft} = this.draft;
            const drawing = {...draft, id: createId()};
            this.drawings.push(drawing);
            if (this.drawings.length > MAX_DRAWINGS) this.drawings.shift();
            this.selectedId = drawing.id;
            this.draft = null;
            this.status.textContent = isPositionDrawing(drawing)
                ? `${capitalize(drawing.type)} saved · R:R ${riskRewardLabel(drawing)}`
                : 'Fibonacci saved · drag a drawing or click empty space for another';
            this.save();
            this.updateToolbar();
            this.scheduleRender();
        }

        handlePointerMove(event) {
            if (!isDrawingMode(this.mode)) return;
            const point = this.pointFromEvent(event);
            if (!point) return;
            if (this.draft) {
                if (drawingType(this.draft) === 'fibonacci') {
                    this.draft.end = stripCoordinates(point);
                } else if (this.draft.stage === 'target') {
                    this.draft.targetPrice = point.price;
                    this.draft.end = positionAnchor(point, this.draft.entryPrice);
                } else {
                    this.draft.stopPrice = point.price;
                }
                this.scheduleRender();
                return;
            }
            if (!this.drag) {
                this.canvas.style.cursor = this.hitTest(point.x, point.y) ? 'move' : 'crosshair';
                return;
            }
            event.preventDefault();
            if (isPositionDrawing(this.drag.drawing)) {
                this.dragPosition(point);
            } else if (this.drag.type === 'start' || this.drag.type === 'end') {
                this.drag.drawing[this.drag.type] = stripCoordinates(point);
            } else {
                this.moveDrawing(point);
            }
            this.scheduleRender();
        }

        dragPosition(point) {
            const drawing = this.drag.drawing;
            if (this.drag.type === 'position-entry') {
                const entry = constrainedPositionPrice(drawing, 'entry', point.price);
                drawing.entryPrice = entry;
                drawing.start = positionAnchor(point, entry);
                drawing.end = {...drawing.end, price: entry};
            } else if (this.drag.type === 'position-target') {
                drawing.targetPrice = constrainedPositionPrice(drawing, 'target', point.price);
                drawing.end = positionAnchor(point, drawing.entryPrice);
            } else if (this.drag.type === 'position-stop') {
                drawing.stopPrice = constrainedPositionPrice(drawing, 'stop', point.price);
                drawing.end = positionAnchor(point, drawing.entryPrice);
            } else {
                this.moveDrawing(point);
            }
        }

        moveDrawing(point) {
            const dx = point.x - this.drag.pointer.x;
            const dy = point.y - this.drag.pointer.y;
            const start = this.pointFromCoordinates(this.drag.start.start.x + dx, this.drag.start.start.y + dy);
            const end = this.pointFromCoordinates(this.drag.start.end.x + dx, this.drag.start.end.y + dy);
            if (!start || !end) return;
            if (isPositionDrawing(this.drag.drawing)) {
                const priceDelta = start.price - this.drag.prices.entry;
                const entry = this.drag.prices.entry + priceDelta;
                this.drag.drawing.entryPrice = entry;
                this.drag.drawing.targetPrice = this.drag.prices.target + priceDelta;
                this.drag.drawing.stopPrice = this.drag.prices.stop + priceDelta;
                this.drag.drawing.start = positionAnchor(start, entry);
                this.drag.drawing.end = positionAnchor(end, entry);
            } else {
                this.drag.drawing.start = stripCoordinates(start);
                this.drag.drawing.end = stripCoordinates(end);
            }
        }

        handlePointerUp(event) {
            if (!this.drag) return;
            if (this.canvas.hasPointerCapture(event.pointerId)) this.canvas.releasePointerCapture(event.pointerId);
            const drawing = this.drag.drawing;
            this.drag = null;
            this.status.textContent = isPositionDrawing(drawing)
                ? `${capitalize(drawing.type)} position · R:R ${riskRewardLabel(drawing)}`
                : 'Fibonacci drawing selected';
            this.save();
            this.scheduleRender();
        }

        handleContextMenu(event) {
            if (!isDrawingMode(this.mode)) return;
            event.preventDefault();
            event.stopPropagation();
            // Finish an existing edit before leaving drawing mode; discard only an unfinished draft.
            if (this.drag) this.handlePointerUp({pointerId: this.drag.pointerId});
            this.setMode('navigate');
        }

        handleKeyDown(event) {
            if (!this.isVisible() || event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return;
            if (event.key === 'Escape') {
                if (this.draft) {
                    this.draft = null;
                    this.status.textContent = this.mode === 'fibonacci'
                        ? 'Fibonacci: click the first anchor'
                        : `${capitalize(this.mode)} position: click entry`;
                    this.scheduleRender();
                } else if (isDrawingMode(this.mode)) {
                    this.setMode('navigate');
                }
            }
            if ((event.key === 'Delete' || event.key === 'Backspace') && this.selectedId) {
                event.preventDefault();
                this.deleteSelected();
            }
        }

        deleteSelected() {
            if (!this.selectedId) return;
            this.drawings = this.drawings.filter(drawing => drawing.id !== this.selectedId);
            this.selectedId = null;
            this.status.textContent = this.drawings.length ? 'Drawing deleted' : 'No drawings';
            this.save();
            this.updateToolbar();
            this.scheduleRender();
        }

        clearAll() {
            if (!this.drawings.length) return;
            if (!this.clearArmed) {
                this.clearArmed = true;
                this.clearButton.textContent = 'Confirm clear';
                this.status.textContent = 'Click again to clear every drawing';
                window.clearTimeout(this.clearTimer);
                this.clearTimer = window.setTimeout(() => {
                    this.clearArmed = false;
                    this.clearButton.textContent = 'Clear all';
                    this.updateToolbar();
                }, 3000);
                return;
            }
            window.clearTimeout(this.clearTimer);
            this.clearArmed = false;
            this.clearButton.textContent = 'Clear all';
            this.drawings = [];
            this.selectedId = null;
            this.status.textContent = 'All drawings cleared';
            this.save();
            this.updateToolbar();
            this.scheduleRender();
        }

        updateToolbar() {
            const drawing = isDrawingMode(this.mode);
            this.container.classList.toggle('fibonacci-drawing-active', drawing);
            this.navigateButton.classList.toggle('active', !drawing);
            this.fibonacciButton.classList.toggle('active', this.mode === 'fibonacci');
            this.longButton.classList.toggle('active', this.mode === 'long');
            this.shortButton.classList.toggle('active', this.mode === 'short');
            this.navigateButton.setAttribute('aria-pressed', String(!drawing));
            this.fibonacciButton.setAttribute('aria-pressed', String(this.mode === 'fibonacci'));
            this.longButton.setAttribute('aria-pressed', String(this.mode === 'long'));
            this.shortButton.setAttribute('aria-pressed', String(this.mode === 'short'));
            this.deleteButton.disabled = !this.selectedId;
            this.clearButton.disabled = this.drawings.length === 0;
        }

        pointFromEvent(event) {
            const bounds = this.container.getBoundingClientRect();
            return this.pointFromCoordinates(event.clientX - bounds.left, event.clientY - bounds.top);
        }

        pointFromCoordinates(x, y) {
            const timeScale = this.chart.timeScale();
            const time = normalizeTime(timeScale.coordinateToTime(x));
            const logical = normalizeLogical(timeScale.coordinateToLogical(x));
            const price = Number(this.series.coordinateToPrice(y));
            if ((time === null && logical === null) || !Number.isFinite(price)) return null;
            return {time, logical, price, x, y};
        }

        screenPoints(drawing) {
            return {
                start: this.screenPoint(drawing.start),
                end: this.screenPoint(drawing.end)
            };
        }

        screenPoint(point) {
            const timeScale = this.chart.timeScale();
            const time = normalizeTime(point.time);
            let x = time === null ? null : timeScale.timeToCoordinate(time);
            const logical = normalizeLogical(point.logical);
            if (!Number.isFinite(x) && logical !== null) {
                x = timeScale.logicalToCoordinate(logical);
            }
            return {
                x,
                y: this.series.priceToCoordinate(point.price)
            };
        }

        hitTest(x, y) {
            for (let index = this.drawings.length - 1; index >= 0; index -= 1) {
                const drawing = this.drawings[index];
                const points = this.screenPoints(drawing);
                if (!validScreenPoint(points.start) || !validScreenPoint(points.end)) continue;
                if (isPositionDrawing(drawing)) {
                    const entry = {x: points.start.x, y: this.series.priceToCoordinate(drawing.entryPrice)};
                    const target = {x: points.end.x, y: this.series.priceToCoordinate(drawing.targetPrice)};
                    const stop = {x: points.end.x, y: this.series.priceToCoordinate(drawing.stopPrice)};
                    if (![entry, target, stop].every(validScreenPoint)) continue;
                    if (distance(x, y, entry.x, entry.y) <= HIT_DISTANCE + 3) {
                        return {drawing, type: 'position-entry'};
                    }
                    if (distance(x, y, target.x, target.y) <= HIT_DISTANCE + 3) {
                        return {drawing, type: 'position-target'};
                    }
                    if (distance(x, y, stop.x, stop.y) <= HIT_DISTANCE + 3) {
                        return {drawing, type: 'position-stop'};
                    }
                    const left = Math.min(points.start.x, points.end.x) - HIT_DISTANCE;
                    const right = Math.max(points.start.x, points.end.x) + HIT_DISTANCE;
                    if (x < left || x > right) continue;
                    if (Math.abs(y - entry.y) <= HIT_DISTANCE) return {drawing, type: 'position-entry'};
                    if (Math.abs(y - target.y) <= HIT_DISTANCE) return {drawing, type: 'position-target'};
                    if (Math.abs(y - stop.y) <= HIT_DISTANCE) return {drawing, type: 'position-stop'};
                    const top = Math.min(entry.y, target.y, stop.y);
                    const bottom = Math.max(entry.y, target.y, stop.y);
                    if (y >= top && y <= bottom) return {drawing, type: 'move'};
                    continue;
                }
                if (distance(x, y, points.start.x, points.start.y) <= HIT_DISTANCE + 3) {
                    return {drawing, type: 'start'};
                }
                if (distance(x, y, points.end.x, points.end.y) <= HIT_DISTANCE + 3) {
                    return {drawing, type: 'end'};
                }
                const left = Math.min(points.start.x, points.end.x) - HIT_DISTANCE;
                const right = Math.max(points.start.x, points.end.x) + HIT_DISTANCE;
                if (x < left || x > right) continue;
                const nearLevel = LEVELS.some(level => {
                    const price = levelPrice(drawing, level.value);
                    const levelY = this.series.priceToCoordinate(price);
                    return levelY !== null && Math.abs(y - levelY) <= HIT_DISTANCE;
                });
                if (nearLevel || distanceToSegment(x, y, points.start, points.end) <= HIT_DISTANCE) {
                    return {drawing, type: 'move'};
                }
            }
            return null;
        }

        scheduleRender() {
            if (this.destroyed || this.frame !== null) return;
            this.frame = window.requestAnimationFrame(() => {
                this.frame = null;
                this.render();
            });
        }

        render() {
            const width = this.container.clientWidth;
            const height = this.container.clientHeight;
            if (!width || !height) return;
            const ratio = window.devicePixelRatio || 1;
            if (this.canvas.width !== Math.round(width * ratio) || this.canvas.height !== Math.round(height * ratio)) {
                this.canvas.width = Math.round(width * ratio);
                this.canvas.height = Math.round(height * ratio);
            }
            const context = this.canvas.getContext('2d');
            context.setTransform(ratio, 0, 0, ratio, 0, 0);
            context.clearRect(0, 0, width, height);
            this.drawings.forEach(drawing => this.renderDrawing(context, drawing, drawing.id === this.selectedId));
            if (this.draft) this.renderDrawing(context, this.draft, true, true);
        }

        renderDrawing(context, drawing, selected, draft = false) {
            if (isPositionDrawing(drawing)) {
                this.renderPositionDrawing(context, drawing, selected, draft);
                return;
            }
            const points = this.screenPoints(drawing);
            if (!validScreenPoint(points.start) || !validScreenPoint(points.end)) return;
            const left = Math.min(points.start.x, points.end.x);
            const right = Math.max(points.start.x, points.end.x);
            const bandWidth = Math.max(1, right - left);
            const levelRows = LEVELS.map(level => ({
                ...level,
                price: levelPrice(drawing, level.value),
                y: this.series.priceToCoordinate(levelPrice(drawing, level.value))
            })).filter(level => level.y !== null);

            context.save();
            levelRows.slice(0, -1).forEach((level, index) => {
                const next = levelRows[index + 1];
                context.fillStyle = withAlpha(level.color, selected ? 0.1 : 0.055);
                context.fillRect(left, Math.min(level.y, next.y), bandWidth, Math.abs(next.y - level.y));
            });
            levelRows.forEach(level => {
                context.strokeStyle = withAlpha(level.color, draft ? 0.7 : 0.95);
                context.lineWidth = selected ? 1.5 : 1;
                context.setLineDash([]);
                context.beginPath();
                context.moveTo(left, level.y + 0.5);
                context.lineTo(right, level.y + 0.5);
                context.stroke();
                this.renderLabel(context, right, level.y, `${trimLevel(level.value)} (${this.priceFormatter(level.price)})`, level.color);
            });
            context.strokeStyle = selected ? themeColor('--fib-selection', '#d7ff78') : 'rgba(180, 190, 184, .56)';
            context.lineWidth = selected ? 1.5 : 1;
            context.setLineDash([5, 5]);
            context.beginPath();
            context.moveTo(points.start.x, points.start.y);
            context.lineTo(points.end.x, points.end.y);
            context.stroke();
            context.setLineDash([]);
            if (selected || draft) {
                this.renderHandle(context, points.start, selected);
                this.renderHandle(context, points.end, selected);
            }
            context.restore();
        }

        renderPositionDrawing(context, drawing, selected, draft = false) {
            const points = this.screenPoints(drawing);
            if (!validScreenPoint(points.start) || !validScreenPoint(points.end)) return;
            const entryY = this.series.priceToCoordinate(drawing.entryPrice);
            const targetY = this.series.priceToCoordinate(drawing.targetPrice);
            const stopY = this.series.priceToCoordinate(drawing.stopPrice);
            if (![entryY, targetY, stopY].every(Number.isFinite)) return;

            const left = Math.min(points.start.x, points.end.x);
            const right = Math.max(points.start.x, points.end.x);
            const width = Math.max(1, right - left);
            const profitColor = '#26a69a';
            const riskColor = '#ef5350';
            const entryColor = themeColor('--fib-label-text', '#edf3ee');
            const rewardPercent = positionPercent(drawing.entryPrice, drawing.targetPrice);
            const riskPercent = positionPercent(drawing.entryPrice, drawing.stopPrice);

            context.save();
            context.fillStyle = withAlpha(profitColor, selected ? 0.24 : 0.18);
            context.fillRect(left, Math.min(entryY, targetY), width, Math.abs(targetY - entryY));
            context.fillStyle = withAlpha(riskColor, selected ? 0.24 : 0.18);
            context.fillRect(left, Math.min(entryY, stopY), width, Math.abs(stopY - entryY));

            this.renderPositionLine(context, left, right, targetY, profitColor, selected, false);
            this.renderPositionLine(context, left, right, entryY, entryColor, selected, true);
            this.renderPositionLine(context, left, right, stopY, riskColor, selected, false);
            this.renderLabel(context, right, targetY,
                `Target ${this.priceFormatter(drawing.targetPrice)} (+${formatPercent(rewardPercent)})`, profitColor);
            this.renderLabel(context, right, entryY,
                `Entry ${this.priceFormatter(drawing.entryPrice)}`, entryColor);
            this.renderLabel(context, right, stopY,
                `Stop ${this.priceFormatter(drawing.stopPrice)} (-${formatPercent(riskPercent)})`, riskColor);
            this.renderPositionBadge(context, left, right, entryY,
                `${drawingType(drawing).toUpperCase()} · R:R ${riskRewardLabel(drawing)}`,
                drawingType(drawing) === 'long' ? profitColor : riskColor);

            if (selected || draft) {
                this.renderHandle(context, {x: points.start.x, y: entryY}, selected);
                this.renderHandle(context, {x: points.end.x, y: targetY}, selected);
                this.renderHandle(context, {x: points.end.x, y: stopY}, selected);
            }
            context.restore();
        }

        renderPositionLine(context, left, right, y, color, selected, dashed) {
            context.strokeStyle = withAlpha(color, 0.96);
            context.lineWidth = selected ? 1.7 : 1.2;
            context.setLineDash(dashed ? [5, 4] : []);
            context.beginPath();
            context.moveTo(left, y + 0.5);
            context.lineTo(right, y + 0.5);
            context.stroke();
            context.setLineDash([]);
        }

        renderPositionBadge(context, left, right, entryY, text, color) {
            context.font = '700 11px Inter, ui-sans-serif, sans-serif';
            const padding = 7;
            const width = context.measureText(text).width + padding * 2;
            const center = left + (right - left) / 2;
            const badgeLeft = Math.max(2, Math.min(this.container.clientWidth - width - 2, center - width / 2));
            const badgeTop = Math.max(2, Math.min(this.container.clientHeight - 21, entryY - 27));
            context.fillStyle = themeColor('--fib-label-bg', 'rgba(20, 25, 22, .9)');
            context.fillRect(badgeLeft, badgeTop, width, 20);
            context.fillStyle = color;
            context.fillRect(badgeLeft, badgeTop, 3, 20);
            context.fillStyle = themeColor('--fib-label-text', '#edf3ee');
            context.fillText(text, badgeLeft + padding, badgeTop + 14);
        }

        renderLabel(context, right, y, text, color) {
            context.font = '600 11px Inter, ui-sans-serif, sans-serif';
            const padding = 5;
            const width = context.measureText(text).width + padding * 2;
            const maxLeft = Math.max(2, this.container.clientWidth - width - 58);
            const left = Math.min(right + 4, maxLeft);
            const top = Math.max(1, Math.min(this.container.clientHeight - 19, y - 9));
            context.fillStyle = themeColor('--fib-label-bg', 'rgba(20, 25, 22, .88)');
            context.fillRect(left, top, width, 18);
            context.fillStyle = color;
            context.fillRect(left, top, 3, 18);
            context.fillStyle = themeColor('--fib-label-text', '#edf3ee');
            context.fillText(text, left + padding + 2, top + 13);
        }

        renderHandle(context, point, selected) {
            context.beginPath();
            context.arc(point.x, point.y, selected ? 5 : 4, 0, Math.PI * 2);
            context.fillStyle = themeColor('--fib-handle-fill', '#101612');
            context.fill();
            context.lineWidth = 2;
            context.strokeStyle = themeColor('--fib-selection', '#d7ff78');
            context.stroke();
        }

        load() {
            try {
                const parsed = JSON.parse(window.localStorage.getItem(this.persistedKey()) || '[]');
                this.drawings = Array.isArray(parsed)
                    ? parsed.filter(validDrawing).slice(-MAX_DRAWINGS)
                    : [];
            } catch (error) {
                this.drawings = [];
            }
        }

        save() {
            try {
                window.localStorage.setItem(this.persistedKey(), JSON.stringify(this.drawings));
            } catch (error) {
                // Drawing remains usable for the current page when storage is unavailable.
            }
        }

        persistedKey() {
            return `stockwatch:fibonacci:v1:${this.storageKey}`;
        }

        isVisible() {
            return !this.container.hidden && this.container.getClientRects().length > 0;
        }

        destroy() {
            if (this.destroyed) return;
            this.destroyed = true;
            this.save();
            window.clearTimeout(this.clearTimer);
            if (this.frame !== null) window.cancelAnimationFrame(this.frame);
            this.resizeObserver?.disconnect();
            this.chart.timeScale().unsubscribeVisibleLogicalRangeChange(this.onChartInteraction);
            this.canvas.removeEventListener('pointerdown', this.onPointerDown);
            this.canvas.removeEventListener('pointermove', this.onPointerMove);
            this.canvas.removeEventListener('pointerup', this.onPointerUp);
            this.canvas.removeEventListener('pointercancel', this.onPointerUp);
            this.container.removeEventListener('contextmenu', this.onContextMenu);
            this.container.removeEventListener('wheel', this.onChartInteraction);
            this.container.removeEventListener('pointermove', this.onChartInteraction);
            document.removeEventListener('keydown', this.onKeyDown);
            window.removeEventListener('stockwatch:themechange', this.onThemeChange);
            this.canvas.remove();
            this.toolbar.remove();
            this.container.classList.remove('fibonacci-drawing-active', 'fibonacci-drawing-host');
        }
    }

    function createButton(text, title) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'fibonacci-tool-button';
        button.textContent = text;
        button.title = title;
        return button;
    }

    function createId() {
        return typeof window.crypto?.randomUUID === 'function'
            ? window.crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    }

    function normalizeTime(time) {
        if (typeof time === 'number' && Number.isFinite(time)) return time;
        if (typeof time === 'string' && time) return time;
        if (time && Number.isInteger(time.year) && Number.isInteger(time.month) && Number.isInteger(time.day)) {
            return `${time.year}-${String(time.month).padStart(2, '0')}-${String(time.day).padStart(2, '0')}`;
        }
        return null;
    }

    function normalizeLogical(logical) {
        return typeof logical === 'number' && Number.isFinite(logical) ? logical : null;
    }

    function stripCoordinates(point) {
        const anchor = {price: point.price};
        const time = normalizeTime(point.time);
        const logical = normalizeLogical(point.logical);
        if (time !== null) anchor.time = time;
        if (logical !== null) anchor.logical = logical;
        return anchor;
    }

    function positionAnchor(point, price) {
        const anchor = stripCoordinates(point);
        anchor.price = Number(price);
        return anchor;
    }

    function drawingType(drawing) {
        return drawing?.type === 'long' || drawing?.type === 'short'
            ? drawing.type
            : 'fibonacci';
    }

    function isPositionDrawing(drawing) {
        const type = drawingType(drawing);
        return type === 'long' || type === 'short';
    }

    function isDrawingMode(mode) {
        return mode === 'fibonacci' || mode === 'long' || mode === 'short';
    }

    function capitalize(value) {
        const text = String(value || '');
        return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
    }

    function validTarget(type, entry, target) {
        const entryPrice = Number(entry);
        const targetPrice = Number(target);
        return Number.isFinite(entryPrice) && Number.isFinite(targetPrice)
            && (type === 'long' ? targetPrice > entryPrice : targetPrice < entryPrice);
    }

    function validStop(type, entry, stop) {
        const entryPrice = Number(entry);
        const stopPrice = Number(stop);
        return Number.isFinite(entryPrice) && Number.isFinite(stopPrice)
            && (type === 'long' ? stopPrice < entryPrice : stopPrice > entryPrice);
    }

    function constrainedPositionPrice(drawing, role, price) {
        const type = drawingType(drawing);
        const entry = Number(drawing.entryPrice);
        const target = Number(drawing.targetPrice);
        const stop = Number(drawing.stopPrice);
        const candidate = Number(price);
        const epsilon = Math.max(Math.abs(entry) * 0.000001, 0.00000001);
        if (!Number.isFinite(candidate)) return role === 'target' ? target : (role === 'stop' ? stop : entry);
        if (role === 'target') {
            return type === 'long' ? Math.max(candidate, entry + epsilon) : Math.min(candidate, entry - epsilon);
        }
        if (role === 'stop') {
            return type === 'long' ? Math.min(candidate, entry - epsilon) : Math.max(candidate, entry + epsilon);
        }
        return type === 'long'
            ? Math.max(stop + epsilon, Math.min(candidate, target - epsilon))
            : Math.max(target + epsilon, Math.min(candidate, stop - epsilon));
    }

    function riskRewardLabel(drawing) {
        const reward = Math.abs(Number(drawing.targetPrice) - Number(drawing.entryPrice));
        const risk = Math.abs(Number(drawing.entryPrice) - Number(drawing.stopPrice));
        const ratio = reward / risk;
        return Number.isFinite(ratio) && risk > 0 ? `1:${ratio.toFixed(2)}` : '--';
    }

    function positionPercent(entry, other) {
        const entryPrice = Math.abs(Number(entry));
        if (!Number.isFinite(entryPrice) || entryPrice === 0) return 0;
        return Math.abs(Number(other) - Number(entry)) / entryPrice * 100;
    }

    function formatPercent(value) {
        const percent = Number(value);
        if (!Number.isFinite(percent)) return '0.00%';
        return `${percent.toFixed(percent >= 100 ? 1 : 2)}%`;
    }

    function validDrawing(drawing) {
        if (!drawing || typeof drawing.id !== 'string'
            || !validAnchor(drawing.start) || !validAnchor(drawing.end)) return false;
        if (!isPositionDrawing(drawing)) return true;
        const type = drawingType(drawing);
        return validTarget(type, drawing.entryPrice, drawing.targetPrice)
            && validStop(type, drawing.entryPrice, drawing.stopPrice);
    }

    function validAnchor(anchor) {
        return anchor
            && (normalizeTime(anchor.time) !== null || normalizeLogical(anchor.logical) !== null)
            && Number.isFinite(Number(anchor.price));
    }

    function validScreenPoint(point) {
        return point && Number.isFinite(point.x) && Number.isFinite(point.y);
    }

    function levelPrice(drawing, level) {
        return Number(drawing.start.price) + (Number(drawing.end.price) - Number(drawing.start.price)) * level;
    }

    function trimLevel(level) {
        return Number(level).toFixed(3).replace(/0+$/, '').replace(/\.$/, '');
    }

    function formatPrice(price) {
        const absolute = Math.abs(Number(price));
        const digits = absolute >= 1000 ? 2 : (absolute >= 1 ? 4 : 6);
        return Number(price).toLocaleString(undefined, {maximumFractionDigits: digits});
    }

    function distance(x1, y1, x2, y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    function distanceToSegment(x, y, start, end) {
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        if (dx === 0 && dy === 0) return distance(x, y, start.x, start.y);
        const ratio = Math.max(0, Math.min(1, ((x - start.x) * dx + (y - start.y) * dy) / (dx * dx + dy * dy)));
        return distance(x, y, start.x + ratio * dx, start.y + ratio * dy);
    }

    function withAlpha(color, alpha) {
        const normalized = color.replace('#', '');
        if (!/^[0-9a-f]{6}$/i.test(normalized)) return color;
        const red = parseInt(normalized.slice(0, 2), 16);
        const green = parseInt(normalized.slice(2, 4), 16);
        const blue = parseInt(normalized.slice(4, 6), 16);
        return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
    }

    function themeColor(name, fallback) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
    }

    window.StockWatchFibonacciDrawingTool = FibonacciDrawingTool;
})();
