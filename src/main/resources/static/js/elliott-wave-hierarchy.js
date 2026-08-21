(() => {
    'use strict';

    const TIMEFRAME_BY_INTERVAL = { '1mo': 'MONTHLY', '1wk': 'WEEKLY', '1d': 'DAILY' };

    class ElliottWaveHierarchyOverlay {
        constructor({ chart, container, symbol, interval = '1mo', color = '#F59E0B',
                        asOfTimestamp, statusElement, renderTime, onWaveClick } = {}) {
            if (!chart || !container || !symbol) {
                throw new Error('An Elliott chart, container, and symbol are required.');
            }
            this.chart = chart;
            this.container = container;
            this.symbol = symbol;
            this.interval = normalizeInterval(interval);
            this.color = color;
            this.asOfTimestamp = finiteTimestamp(asOfTimestamp);
            this.statusElement = statusElement || null;
            this.renderTime = typeof renderTime === 'function' ? renderTime : value => Number(value);
            this.onWaveClick = typeof onWaveClick === 'function' ? onWaveClick : null;
            this.hierarchy = null;
            this.series = [];
            this.segments = [];
            this.request = null;
            this.destroyed = false;
            this.clickHandler = event => this.handleChartClick(event);
            if (this.onWaveClick && typeof this.chart.subscribeClick === 'function') {
                this.chart.subscribeClick(this.clickHandler);
            }
        }

        async showInterval(interval, { asOfTimestamp } = {}) {
            this.interval = normalizeInterval(interval);
            const nextAsOf = finiteTimestamp(asOfTimestamp);
            if (nextAsOf && nextAsOf !== this.asOfTimestamp) {
                this.asOfTimestamp = nextAsOf;
                this.hierarchy = null;
            }
            if (!this.hierarchy) await this.load();
            if (this.destroyed) return 0;
            return this.render();
        }

        async load() {
            this.request?.abort();
            const controller = new AbortController();
            this.request = controller;
            const query = this.asOfTimestamp
                ? `?asOfExclusive=${encodeURIComponent(this.asOfTimestamp)}` : '';
            this.setStatus('Building top-down Elliott hierarchy…');
            try {
                const response = await fetch(
                    `/api/stocks/${encodeURIComponent(this.symbol)}/elliott-waves/hierarchy${query}`,
                    { signal: controller.signal, headers: { Accept: 'application/json' } }
                );
                if (!response.ok) throw new Error(`Hierarchy request failed (${response.status})`);
                this.hierarchy = await response.json();
                if (!this.hierarchy?.available) {
                    this.setStatus(this.hierarchy?.unavailableReason || 'No fully validated Elliott hierarchy is available.');
                }
            } catch (error) {
                if (error.name === 'AbortError') return;
                this.hierarchy = { available: false, waves: [], unavailableReason: error.message };
                this.setStatus(error.message || 'Elliott hierarchy could not be loaded.');
            } finally {
                if (this.request === controller) this.request = null;
            }
        }

        render() {
            this.clear();
            if (!this.hierarchy?.available) return 0;
            const timeframe = TIMEFRAME_BY_INTERVAL[this.interval];
            const groups = groupsAtTimeframe(this.hierarchy.waves, timeframe);
            groups.forEach(group => this.renderGroup(group));
            const waveCount = groups.reduce((total, group) => total + group.length, 0);
            this.setStatus(waveCount > 0
                ? `${waveCount} validated ${this.intervalName().toLowerCase()} subwaves from the monthly hierarchy`
                : `No validated ${this.intervalName().toLowerCase()} branch exists in the current hierarchy.`);
            return waveCount;
        }

        renderGroup(group) {
            if (!Array.isArray(group) || group.length === 0) return;
            const data = [{ time: this.renderTime(group[0].startTime), value: Number(group[0].startPrice) }];
            group.forEach(wave => data.push({
                time: this.renderTime(wave.endTime),
                value: Number(wave.endPrice)
            }));
            if (data.some(point => point.time === null || point.time === undefined
                || !Number.isFinite(point.value))) return;
            const line = this.chart.addLineSeries({
                color: this.color,
                lineWidth: 2,
                priceLineVisible: false,
                lastValueVisible: false,
                crosshairMarkerVisible: false
            });
            line.setData(uniqueTimes(data));
            line.setMarkers(group.map(wave => ({
                time: this.renderTime(wave.endTime),
                position: Number(wave.endPrice) >= Number(wave.startPrice) ? 'aboveBar' : 'belowBar',
                color: this.color,
                shape: 'circle',
                text: String(wave.degreeLabel || '')
            })));
            this.series.push(line);
            group.forEach(wave => this.segments.push({ wave, series: line }));
        }

        clear() {
            this.request?.abort();
            this.request = null;
            this.series.forEach(series => {
                try { this.chart.removeSeries(series); } catch (_) { /* chart already disposed */ }
            });
            this.series = [];
            this.segments = [];
        }

        destroy() {
            this.destroyed = true;
            if (this.onWaveClick && typeof this.chart.unsubscribeClick === 'function') {
                this.chart.unsubscribeClick(this.clickHandler);
            }
            this.clear();
        }

        handleChartClick(event) {
            if (!event?.point || this.segments.length === 0) return;
            let nearest = null;
            let nearestDistance = 18;
            this.segments.forEach(segment => {
                const start = this.waveCoordinate(segment, 'start');
                const end = this.waveCoordinate(segment, 'end');
                if (!start || !end) return;
                const distance = pointToLineSegmentDistance(event.point, start, end);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = segment.wave;
                }
            });
            if (nearest) this.onWaveClick(nearest, event.point);
        }

        waveCoordinate(segment, endpoint) {
            const wave = segment.wave;
            const time = endpoint === 'start' ? wave.startTime : wave.endTime;
            const price = endpoint === 'start' ? wave.startPrice : wave.endPrice;
            const x = this.chart.timeScale().timeToCoordinate(this.renderTime(time));
            const y = segment.series.priceToCoordinate(Number(price));
            return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
        }

        setStatus(message) {
            if (this.statusElement) this.statusElement.textContent = message || '';
        }

        intervalName() {
            return { '1mo': 'Monthly', '1wk': 'Weekly', '1d': 'Daily' }[this.interval] || 'Selected';
        }
    }

    function groupsAtTimeframe(roots, timeframe) {
        if (!Array.isArray(roots)) return [];
        if (timeframe === 'MONTHLY') return groupByCycle(roots);
        if (timeframe === 'WEEKLY') {
            return roots.map(wave => wave.subwaves).filter(group => Array.isArray(group) && group.length);
        }
        if (timeframe === 'DAILY') {
            return roots.flatMap(monthly => Array.isArray(monthly.subwaves) ? monthly.subwaves : [])
                .map(weekly => weekly.subwaves)
                .filter(group => Array.isArray(group) && group.length);
        }
        return [];
    }

    function groupByCycle(waves) {
        const cycles = new Map();
        waves.forEach(wave => {
            const key = wave.cycleKey || `${wave.startTime}:${wave.endTime}`;
            if (!cycles.has(key)) cycles.set(key, []);
            cycles.get(key).push(wave);
        });
        return [...cycles.values()].filter(group => group.length);
    }

    function uniqueTimes(points) {
        const byTime = new Map();
        points.forEach(point => byTime.set(point.time, point));
        return [...byTime.values()].sort((first, second) => compareTime(first.time, second.time));
    }

    function compareTime(first, second) {
        if (typeof first === 'number' && typeof second === 'number') return first - second;
        return String(first).localeCompare(String(second));
    }

    function pointToLineSegmentDistance(point, start, end) {
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        if (dx === 0 && dy === 0) return Math.hypot(point.x - start.x, point.y - start.y);
        const progress = Math.max(0, Math.min(1,
            ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(point.x - (start.x + progress * dx),
            point.y - (start.y + progress * dy));
    }

    function normalizeInterval(interval) {
        const value = String(interval || '').toLowerCase();
        if (value === 'monthly') return '1mo';
        if (value === 'weekly') return '1wk';
        if (value === 'daily') return '1d';
        return Object.hasOwn(TIMEFRAME_BY_INTERVAL, value) ? value : '1mo';
    }

    function finiteTimestamp(value) {
        const parsed = Number(value);
        return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : null;
    }

    window.StockWatchElliottHierarchyOverlay = ElliottWaveHierarchyOverlay;
})();
