(() => {
    'use strict';

    const LEVELS = [
        {value: 0, color: '#787b86'},
        {value: 0.236, color: '#f23645'},
        {value: 0.382, color: '#ff9800'},
        {value: 0.5, color: '#f6c344'},
        {value: 0.618, color: '#089981'},
        {value: 0.786, color: '#2962ff'},
        {value: 1, color: '#7b61ff'}
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
            this.onKeyDown = event => this.handleKeyDown(event);
            this.onChartInteraction = () => this.scheduleRender();
            this.onThemeChange = () => this.scheduleRender();
            this.canvas.addEventListener('pointerdown', this.onPointerDown);
            this.canvas.addEventListener('pointermove', this.onPointerMove);
            this.canvas.addEventListener('pointerup', this.onPointerUp);
            this.canvas.addEventListener('pointercancel', this.onPointerUp);
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
            this.fibonacciButton = createButton('Fib', 'Draw or edit Fibonacci retracements');
            this.fibonacciButton.dataset.fibonacciAction = 'draw';
            this.deleteButton = createButton('Delete', 'Delete the selected Fibonacci drawing');
            this.deleteButton.dataset.fibonacciAction = 'delete';
            this.clearButton = createButton('Clear all', 'Remove every Fibonacci drawing from this chart');
            this.clearButton.dataset.fibonacciAction = 'clear';
            this.status = document.createElement('span');
            this.status.className = 'fibonacci-drawing-status';
            this.status.setAttribute('role', 'status');
            this.status.textContent = 'Drawing tools';

            this.navigateButton.addEventListener('click', () => this.setMode('navigate'));
            this.fibonacciButton.addEventListener('click', () => this.setMode('fibonacci'));
            this.deleteButton.addEventListener('click', () => this.deleteSelected());
            this.clearButton.addEventListener('click', () => this.clearAll());
            toolbar.append(this.navigateButton, this.fibonacciButton, this.deleteButton, this.clearButton, this.status);
            return toolbar;
        }

        setMode(mode) {
            this.mode = mode === 'fibonacci' ? 'fibonacci' : 'navigate';
            if (this.mode === 'navigate') {
                this.draft = null;
                this.drag = null;
                this.status.textContent = this.drawings.length ? 'Drawings saved' : 'Drawing tools';
            } else {
                this.status.textContent = this.selectedId
                    ? 'Drag an anchor or level; click empty space to draw another'
                    : 'Click the first anchor';
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
            if (this.mode !== 'fibonacci' || event.button !== 0) return;
            const point = this.pointFromEvent(event);
            if (!point) return;
            event.preventDefault();

            if (this.draft) {
                this.draft.end = point;
                const drawing = {...this.draft, id: createId()};
                this.drawings.push(drawing);
                if (this.drawings.length > MAX_DRAWINGS) this.drawings.shift();
                this.selectedId = drawing.id;
                this.draft = null;
                this.status.textContent = 'Drag an anchor or level; click empty space to draw another';
                this.save();
                this.updateToolbar();
                this.scheduleRender();
                return;
            }

            const hit = this.hitTest(point.x, point.y);
            if (hit) {
                this.selectedId = hit.drawing.id;
                const start = this.screenPoints(hit.drawing);
                this.drag = {
                    type: hit.type,
                    drawing: hit.drawing,
                    pointer: {x: point.x, y: point.y},
                    start
                };
                this.canvas.setPointerCapture(event.pointerId);
                this.status.textContent = hit.type === 'move' ? 'Move the retracement' : 'Move the anchor';
            } else {
                this.selectedId = null;
                this.draft = {start: point, end: point};
                this.status.textContent = 'Click the second anchor';
            }
            this.updateToolbar();
            this.scheduleRender();
        }

        handlePointerMove(event) {
            if (this.mode !== 'fibonacci') return;
            const point = this.pointFromEvent(event);
            if (!point) return;
            if (this.draft) {
                this.draft.end = point;
                this.scheduleRender();
                return;
            }
            if (!this.drag) {
                this.canvas.style.cursor = this.hitTest(point.x, point.y) ? 'move' : 'crosshair';
                return;
            }
            event.preventDefault();
            if (this.drag.type === 'start' || this.drag.type === 'end') {
                this.drag.drawing[this.drag.type] = stripCoordinates(point);
            } else {
                const dx = point.x - this.drag.pointer.x;
                const dy = point.y - this.drag.pointer.y;
                const start = this.pointFromCoordinates(this.drag.start.start.x + dx, this.drag.start.start.y + dy);
                const end = this.pointFromCoordinates(this.drag.start.end.x + dx, this.drag.start.end.y + dy);
                if (start && end) {
                    this.drag.drawing.start = stripCoordinates(start);
                    this.drag.drawing.end = stripCoordinates(end);
                }
            }
            this.scheduleRender();
        }

        handlePointerUp(event) {
            if (!this.drag) return;
            if (this.canvas.hasPointerCapture(event.pointerId)) this.canvas.releasePointerCapture(event.pointerId);
            this.drag = null;
            this.status.textContent = 'Drag an anchor or level; click empty space to draw another';
            this.save();
            this.scheduleRender();
        }

        handleKeyDown(event) {
            if (!this.isVisible() || event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return;
            if (event.key === 'Escape') {
                if (this.draft) {
                    this.draft = null;
                    this.status.textContent = 'Click the first anchor';
                    this.scheduleRender();
                } else if (this.mode === 'fibonacci') {
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
            this.status.textContent = this.drawings.length ? 'Drawing deleted' : 'No Fibonacci drawings';
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
            this.status.textContent = 'All Fibonacci drawings cleared';
            this.save();
            this.updateToolbar();
            this.scheduleRender();
        }

        updateToolbar() {
            const drawing = this.mode === 'fibonacci';
            this.container.classList.toggle('fibonacci-drawing-active', drawing);
            this.navigateButton.classList.toggle('active', !drawing);
            this.fibonacciButton.classList.toggle('active', drawing);
            this.navigateButton.setAttribute('aria-pressed', String(!drawing));
            this.fibonacciButton.setAttribute('aria-pressed', String(drawing));
            this.deleteButton.disabled = !this.selectedId;
            this.clearButton.disabled = this.drawings.length === 0;
        }

        pointFromEvent(event) {
            const bounds = this.container.getBoundingClientRect();
            return this.pointFromCoordinates(event.clientX - bounds.left, event.clientY - bounds.top);
        }

        pointFromCoordinates(x, y) {
            const time = normalizeTime(this.chart.timeScale().coordinateToTime(x));
            const price = Number(this.series.coordinateToPrice(y));
            if (time === null || !Number.isFinite(price)) return null;
            return {time, price, x, y};
        }

        screenPoints(drawing) {
            return {
                start: this.screenPoint(drawing.start),
                end: this.screenPoint(drawing.end)
            };
        }

        screenPoint(point) {
            return {
                x: this.chart.timeScale().timeToCoordinate(point.time),
                y: this.series.priceToCoordinate(point.price)
            };
        }

        hitTest(x, y) {
            for (let index = this.drawings.length - 1; index >= 0; index -= 1) {
                const drawing = this.drawings[index];
                const points = this.screenPoints(drawing);
                if (!validScreenPoint(points.start) || !validScreenPoint(points.end)) continue;
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

    function stripCoordinates(point) {
        return {time: point.time, price: point.price};
    }

    function validDrawing(drawing) {
        return drawing && typeof drawing.id === 'string'
            && validAnchor(drawing.start) && validAnchor(drawing.end);
    }

    function validAnchor(anchor) {
        return anchor && normalizeTime(anchor.time) !== null && Number.isFinite(Number(anchor.price));
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
