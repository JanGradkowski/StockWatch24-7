(() => {
    'use strict';

    const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
    const DEFAULT_HIT_DISTANCE = 16;
    const HIDE_DELAY_MS = 35;

    class ElliottSubwaveOverlay {
        constructor({chart, candleSeries, container, symbol, interval, points = [], asOfTimestamp,
                        color = '#F59E0B', renderTime, autoSubscribe = false,
                        hitDistance = DEFAULT_HIT_DISTANCE} = {}) {
            if (!chart || !candleSeries || !container || !symbol) {
                throw new Error('An Elliott chart, candle series, container, and symbol are required.');
            }
            this.chart = chart;
            this.candleSeries = candleSeries;
            this.container = container;
            this.symbol = symbol;
            this.interval = normalizeInterval(interval);
            this.points = points.map(normalizePoint).filter(Boolean);
            this.asOfTimestamp = Number(asOfTimestamp);
            this.color = color;
            this.renderTime = typeof renderTime === 'function' ? renderTime : value => Number(value);
            this.hitDistance = hitDistance;
            this.cache = new Map();
            this.activeKey = null;
            this.activeFrame = null;
            this.desiredKey = null;
            this.pendingKey = null;
            this.requestController = null;
            this.hideTimer = null;
            this.renderFrame = null;
            this.destroyed = false;
            this.overlay = createSvg('svg', 'elliott-subwave-svg');
            this.overlay.setAttribute('aria-hidden', 'true');
            this.overlay.hidden = true;
            this.container.appendChild(this.overlay);

            this.onCrosshair = parameter => this.handleCrosshair(parameter);
            this.onVisibleRangeChange = () => this.scheduleRender();
            this.onContainerLeave = () => this.hide(true);
            this.autoSubscribe = Boolean(autoSubscribe);
            if (this.autoSubscribe) this.chart.subscribeCrosshairMove(this.onCrosshair);
            this.chart.timeScale().subscribeVisibleLogicalRangeChange(this.onVisibleRangeChange);
            this.container.addEventListener('mouseleave', this.onContainerLeave);
            this.resizeObserver = typeof ResizeObserver === 'undefined'
                ? null : new ResizeObserver(() => this.scheduleRender());
            this.resizeObserver?.observe(this.container);
        }

        setContext({interval, points, asOfTimestamp} = {}) {
            this.hide(true);
            if (interval) this.interval = normalizeInterval(interval);
            if (Array.isArray(points)) this.points = points.map(normalizePoint).filter(Boolean);
            if (Number.isFinite(Number(asOfTimestamp))) this.asOfTimestamp = Number(asOfTimestamp);
        }

        async show({start, end, label, interval, asOfTimestamp} = {}) {
            window.clearTimeout(this.hideTimer);
            if (this.destroyed || this.isDrawingFibonacci()) return;
            const parentInterval = normalizeInterval(interval || this.interval);
            const normalizedStart = normalizePoint(start);
            const normalizedEnd = normalizePoint(end);
            if (!normalizedStart || !normalizedEnd || !label
                    || (parentInterval !== '1mo' && parentInterval !== '1wk')) {
                this.hide();
                return;
            }

            const asOf = Number.isFinite(Number(asOfTimestamp))
                ? Number(asOfTimestamp)
                : (Number.isFinite(this.asOfTimestamp) ? this.asOfTimestamp : normalizedEnd.timestamp);
            const asOfExclusive = periodEndExclusive(asOf, parentInterval);
            const key = [parentInterval, label, normalizedStart.timestamp, normalizedEnd.timestamp,
                normalizedStart.price, normalizedEnd.price, asOfExclusive].join(':');
            const context = {start: normalizedStart, end: normalizedEnd};
            this.desiredKey = key;
            if (this.activeKey === key && this.activeFrame) return;
            if (this.pendingKey === key) return;

            const cached = this.cache.get(key);
            if (cached) {
                this.render(key, cached, context);
                return;
            }

            this.clearOverlay();
            this.requestController?.abort();
            const controller = new AbortController();
            this.requestController = controller;
            this.pendingKey = key;
            const query = new URLSearchParams({
                parentInterval,
                parentLabel: String(label),
                parentStart: String(normalizedStart.timestamp),
                parentEnd: String(normalizedEnd.timestamp),
                parentStartPrice: String(normalizedStart.price),
                parentEndPrice: String(normalizedEnd.price),
                asOfExclusive: String(asOfExclusive)
            });
            try {
                const response = await fetch(`/api/stocks/${encodeURIComponent(this.symbol)}/elliott-waves/drilldown?${query}`,
                    {headers: {'Accept': 'application/json'}, signal: controller.signal});
                const payload = await response.json().catch(() => ({}));
                if (!response.ok) throw new Error(payload.error || 'The subwave request failed.');
                const normalized = {
                    available: payload.available === true,
                    validated: payload.validated !== false,
                    points: (payload.points || []).map(normalizePoint).filter(Boolean)
                };
                this.cache.set(key, normalized);
                if (!this.destroyed && this.desiredKey === key) this.render(key, normalized, context);
            } catch (error) {
                if (error.name !== 'AbortError' && !this.destroyed) {
                    console.warn('Elliott subwaves are unavailable', error);
                }
            } finally {
                if (this.requestController === controller) {
                    this.requestController = null;
                    this.pendingKey = null;
                }
            }
        }

        hide(immediate = false) {
            this.desiredKey = null;
            window.clearTimeout(this.hideTimer);
            const remove = () => {
                if (this.desiredKey === null) this.clearOverlay();
            };
            if (immediate) remove();
            else this.hideTimer = window.setTimeout(remove, HIDE_DELAY_MS);
        }

        destroy() {
            if (this.destroyed) return;
            this.destroyed = true;
            window.clearTimeout(this.hideTimer);
            window.cancelAnimationFrame(this.renderFrame);
            this.requestController?.abort();
            if (this.autoSubscribe) this.chart.unsubscribeCrosshairMove(this.onCrosshair);
            this.chart.timeScale().unsubscribeVisibleLogicalRangeChange(this.onVisibleRangeChange);
            this.container.removeEventListener('mouseleave', this.onContainerLeave);
            this.resizeObserver?.disconnect();
            this.overlay.remove();
            this.cache.clear();
        }

        handleCrosshair(parameter) {
            if (this.isDrawingFibonacci()) {
                this.hide(true);
                return;
            }
            const segment = this.nearestSegment(parameter?.point);
            if (segment) this.show(segment);
            else this.hide();
        }

        nearestSegment(point) {
            if (!point || !Number.isFinite(point.x) || !Number.isFinite(point.y)) return null;
            let nearest = null;
            for (let index = 1; index < this.points.length; index += 1) {
                const start = this.points[index - 1];
                const end = this.points[index];
                if (!end.label) continue;
                const startScreen = this.screenPoint(start);
                const endScreen = this.screenPoint(end);
                if (!startScreen || !endScreen) continue;
                const distance = distanceToSegment(point, startScreen, endScreen);
                if (distance <= this.hitDistance && (!nearest || distance < nearest.distance)) {
                    nearest = {
                        start, end, label: end.label, interval: this.interval,
                        asOfTimestamp: this.asOfTimestamp, distance
                    };
                }
            }
            return nearest;
        }

        screenPoint(point) {
            const x = this.chart.timeScale().timeToCoordinate(this.renderTime(point.timestamp));
            const y = this.candleSeries.priceToCoordinate(point.price);
            return Number.isFinite(x) && Number.isFinite(y) ? {x, y} : null;
        }

        render(key, payload, context) {
            this.clearOverlay();
            if (!payload.available || payload.points.length < 2 || this.desiredKey !== key) return;
            this.activeKey = key;
            this.activeFrame = {
                start: context.start,
                end: context.end,
                points: payload.points,
                validated: payload.validated
            };
            this.renderActiveFrame();
        }

        scheduleRender() {
            if (!this.activeFrame || this.renderFrame !== null) return;
            this.renderFrame = window.requestAnimationFrame(() => {
                this.renderFrame = null;
                this.renderActiveFrame();
            });
        }

        renderActiveFrame() {
            if (!this.activeFrame || this.destroyed) return;
            const startScreen = this.screenPoint(this.activeFrame.start);
            const endScreen = this.screenPoint(this.activeFrame.end);
            if (!startScreen || !endScreen) {
                this.overlay.hidden = true;
                return;
            }
            const startTimestamp = this.activeFrame.start.timestamp;
            const duration = Math.max(1, this.activeFrame.end.timestamp - startTimestamp);
            const renderedPoints = this.activeFrame.points.map(point => ({
                ...point,
                x: startScreen.x + (endScreen.x - startScreen.x)
                    * clamp((point.timestamp - startTimestamp) / duration, 0, 1),
                y: this.candleSeries.priceToCoordinate(point.price)
            })).filter(point => Number.isFinite(point.x) && Number.isFinite(point.y));
            if (renderedPoints.length < 2) {
                this.overlay.hidden = true;
                return;
            }

            const path = createSvg('polyline', this.activeFrame.validated
                ? 'elliott-subwave-path' : 'elliott-subwave-path provisional');
            path.setAttribute('points', renderedPoints.map(point => `${point.x},${point.y}`).join(' '));
            path.setAttribute('stroke', this.color);
            this.overlay.replaceChildren(path);
            renderedPoints.forEach(point => {
                const marker = createSvg('circle', 'elliott-subwave-point');
                marker.setAttribute('cx', String(point.x));
                marker.setAttribute('cy', String(point.y));
                marker.setAttribute('r', '4.5');
                marker.setAttribute('fill', this.color);
                marker.setAttribute('stroke', this.color);
                this.overlay.appendChild(marker);
                if (!point.label) return;
                const label = createSvg('text', 'elliott-subwave-label');
                label.setAttribute('x', String(point.x));
                label.setAttribute('y', String(point.y + (point.pivotType === 'HIGH' ? -11 : 18)));
                label.setAttribute('fill', this.color);
                label.textContent = String(point.label).toLowerCase();
                this.overlay.appendChild(label);
            });
            this.overlay.hidden = false;
        }

        clearOverlay() {
            window.cancelAnimationFrame(this.renderFrame);
            this.renderFrame = null;
            this.activeKey = null;
            this.activeFrame = null;
            this.overlay.replaceChildren();
            this.overlay.hidden = true;
        }

        isDrawingFibonacci() {
            return this.container.classList.contains('fibonacci-drawing-active');
        }
    }

    function createSvg(tagName, className) {
        const element = document.createElementNS(SVG_NAMESPACE, tagName);
        element.setAttribute('class', className);
        return element;
    }

    function normalizePoint(point) {
        const normalized = {
            label: String(point?.label || ''),
            timestamp: Number(point?.timestamp ?? point?.time),
            price: Number(point?.price ?? point?.value),
            pivotType: String(point?.pivotType || '')
        };
        return Number.isFinite(normalized.timestamp) && Number.isFinite(normalized.price)
            ? normalized : null;
    }

    function normalizeInterval(interval) {
        const value = String(interval || '').toUpperCase();
        if (value === 'MONTHLY') return '1mo';
        if (value === 'WEEKLY') return '1wk';
        if (value === 'DAILY') return '1d';
        return String(interval || '');
    }

    function periodEndExclusive(timestamp, interval) {
        if (!Number.isFinite(timestamp)) return Math.floor(Date.now() / 1000);
        const date = new Date(timestamp * 1000);
        if (interval === '1mo') date.setUTCMonth(date.getUTCMonth() + 1, 1);
        else if (interval === '1wk') date.setUTCDate(date.getUTCDate() + 7);
        date.setUTCHours(0, 0, 0, 0);
        return Math.floor(date.getTime() / 1000);
    }

    function distanceToSegment(point, start, end) {
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        if (dx === 0 && dy === 0) return Math.hypot(point.x - start.x, point.y - start.y);
        const amount = clamp(
            ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy), 0, 1);
        return Math.hypot(point.x - (start.x + amount * dx), point.y - (start.y + amount * dy));
    }

    function clamp(value, minimum, maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    window.StockWatchElliottSubwaveOverlay = ElliottSubwaveOverlay;
})();
