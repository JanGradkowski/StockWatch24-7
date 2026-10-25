(() => {
    'use strict';

    // Drawing engine for every chart page: a tool registry, a time mapping that keeps drawings on
    // the right bars across Daily/Weekly/Monthly charts, rendering through Lightweight Charts
    // primitives (so drawings are clipped to their pane and get axis labels), pointer interaction,
    // undo/redo and persistence. Tools are registered by chart-drawing-tools.js and the toolbar,
    // dialogs and object tree live in chart-drawing-ui.js.

    const i18n = window.StockWatchI18n;
    const DAY = 86400;
    const HIT = 7;
    const HANDLE = 5;
    const MAX_UNDO = 100;
    const FONT = '"Geist", ui-sans-serif, -apple-system, "Segoe UI", sans-serif';
    const tools = new Map();
    const managers = new Set();
    let activeManager = null;

    // ------------------------------------------------------------------ utilities

    const clamp = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));
    const finite = value => typeof value === 'number' && Number.isFinite(value);
    const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);

    function segmentDistance(point, a, b) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        if (dx === 0 && dy === 0) return distance(point, a);
        const t = clamp(((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy), 0, 1);
        return Math.hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy));
    }

    function lineDistance(point, a, b) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const length = Math.hypot(dx, dy);
        if (length === 0) return distance(point, a);
        return Math.abs(dy * point.x - dx * point.y + b.x * a.y - b.y * a.x) / length;
    }

    function insidePolygon(point, polygon) {
        let inside = false;
        for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            const a = polygon[i];
            const b = polygon[j];
            if ((a.y > point.y) !== (b.y > point.y)
                && point.x < (b.x - a.x) * (point.y - a.y) / ((b.y - a.y) || 1e-9) + a.x) inside = !inside;
        }
        return inside;
    }

    /** The segment a–b extended past its left and/or right end on screen (whichever way it was drawn). */
    function extendSegment(a, b, extendLeft, extendRight, width, height) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        if (dx === 0 && dy === 0) return [a, b];
        const [left, right] = a.x <= b.x ? [a, b] : [b, a];
        const length = Math.hypot(dx, dy);
        const ux = (right.x - left.x) / length;
        const uy = (right.y - left.y) / length;
        const far = Math.max(width, height) * 4 + 2000;
        return [
            extendLeft ? {x: left.x - ux * far, y: left.y - uy * far} : left,
            extendRight ? {x: right.x + ux * far, y: right.y + uy * far} : right
        ];
    }

    function withAlpha(color, alpha) {
        if (typeof color !== 'string') return color;
        const hex = color.trim().replace('#', '');
        if (/^[0-9a-f]{6}$/i.test(hex)) {
            return `rgba(${parseInt(hex.slice(0, 2), 16)}, ${parseInt(hex.slice(2, 4), 16)}, ${parseInt(hex.slice(4, 6), 16)}, ${alpha})`;
        }
        if (/^[0-9a-f]{3}$/i.test(hex)) {
            return `rgba(${parseInt(hex[0] + hex[0], 16)}, ${parseInt(hex[1] + hex[1], 16)}, ${parseInt(hex[2] + hex[2], 16)}, ${alpha})`;
        }
        const rgba = color.replace(/\s/g, '').match(/^rgba?\((\d+),(\d+),(\d+)/i);
        return rgba ? `rgba(${rgba[1]}, ${rgba[2]}, ${rgba[3]}, ${alpha})` : color;
    }

    /** Black or white text, whichever reads better on the colour. */
    function contrastText(color) {
        const hex = String(color || '').replace('#', '');
        if (!/^[0-9a-f]{6}$/i.test(hex)) return '#ffffff';
        const r = parseInt(hex.slice(0, 2), 16);
        const g = parseInt(hex.slice(2, 4), 16);
        const b = parseInt(hex.slice(4, 6), 16);
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150 ? '#101418' : '#ffffff';
    }

    function themeValue(name, fallback) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
    }

    // Every pane asks for the palette on every frame; reading computed styles that often is slow.
    let paletteCache = null;
    window.addEventListener('stockwatch:themechange', () => { paletteCache = null; });

    function themePalette() {
        const theme = document.documentElement.dataset.theme || '';
        if (paletteCache?.theme !== theme) paletteCache = {theme, palette: Object.freeze(readPalette(theme === 'light'))};
        return paletteCache.palette;
    }

    function readPalette(light) {
        return {
            light,
            text: themeValue('--draw-label-text', light ? '#182230' : '#edf3ee'),
            labelBackground: themeValue('--draw-label-bg', light ? 'rgba(255, 255, 255, 0.94)' : 'rgba(18, 24, 32, 0.92)'),
            handleFill: themeValue('--draw-handle-fill', light ? '#ffffff' : '#101612'),
            selection: themeValue('--draw-selection', light ? '#2962ff' : '#d7ff78'),
            accent: light ? '#2962ff' : '#60a5fa',
            muted: light ? 'rgba(24, 34, 48, 0.55)' : 'rgba(232, 237, 243, 0.55)'
        };
    }

    function createId() {
        return typeof window.crypto?.randomUUID === 'function'
            ? window.crypto.randomUUID()
            : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
    }

    const clone = value => JSON.parse(JSON.stringify(value));

    // ------------------------------------------------------------------ time mapping

    /** Seconds since the epoch for any Lightweight Charts time value. */
    function toSeconds(time) {
        if (typeof time === 'number' && Number.isFinite(time)) return time;
        if (typeof time === 'string' && /^\d{4}-\d{2}-\d{2}/.test(time)) {
            return Date.UTC(Number(time.slice(0, 4)), Number(time.slice(5, 7)) - 1, Number(time.slice(8, 10))) / 1000;
        }
        if (time && Number.isInteger(time.year)) return Date.UTC(time.year, time.month - 1, time.day) / 1000;
        return null;
    }

    function isWeekend(seconds) {
        const day = new Date(seconds * 1000).getUTCDay();
        return day === 0 || day === 6;
    }

    function addMonths(seconds, months) {
        const date = new Date(seconds * 1000);
        const whole = Math.trunc(months);
        const shifted = Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + whole, Math.min(date.getUTCDate(), 28)) / 1000;
        const fraction = months - whole;
        return shifted + fraction * 30.44 * DAY;
    }

    /**
     * Converts between bar positions (Lightweight Charts logical indexes) and time, including
     * before the first and after the last loaded bar. Drawings store time, so they keep their place
     * when older history is loaded or when the interval changes.
     */
    class TimeMapper {
        constructor(times) {
            this.times = times;
            this.mode = 'fixed';
            this.step = DAY;
            if (times.length < 2) return;
            const gaps = [];
            for (let index = Math.max(1, times.length - 120); index < times.length; index++) gaps.push(times[index] - times[index - 1]);
            gaps.sort((a, b) => a - b);
            const median = gaps[Math.floor(gaps.length / 2)];
            if (median <= 1.5 * DAY) {
                this.mode = gaps.some(gap => gap >= 2.5 * DAY && gap <= 4.5 * DAY) ? 'business' : 'fixed';
                this.step = median;
            } else if (median >= 5.5 * DAY && median <= 8.5 * DAY) {
                this.mode = 'week';
            } else if (median >= 26 * DAY && median <= 33 * DAY) {
                this.mode = 'month';
            } else {
                this.step = median;
            }
        }

        get length() { return this.times.length; }

        /** Bars from {@code from} to {@code to} (to ≥ from), counting only trading days on a daily chart. */
        steps(from, to) {
            if (to <= from) return 0;
            switch (this.mode) {
                case 'business': {
                    const days = Math.floor((to - from) / DAY);
                    let count = Math.floor(days / 7) * 5;
                    let cursor = from + Math.floor(days / 7) * 7 * DAY;
                    while (cursor + DAY <= to) {
                        cursor += DAY;
                        if (!isWeekend(cursor)) count++;
                    }
                    return count + ((to - from) % DAY) / DAY;
                }
                case 'week': return (to - from) / (7 * DAY);
                case 'month': {
                    const a = new Date(from * 1000);
                    const b = new Date(to * 1000);
                    return (b.getUTCFullYear() - a.getUTCFullYear()) * 12 + (b.getUTCMonth() - a.getUTCMonth())
                        + (b.getUTCDate() - a.getUTCDate()) / 30.44;
                }
                default: return (to - from) / this.step;
            }
        }

        /** Time {@code count} bars after (or before, if negative) the given time. */
        advance(seconds, count) {
            if (count === 0) return seconds;
            switch (this.mode) {
                case 'business': {
                    const direction = Math.sign(count);
                    let whole = Math.floor(Math.abs(count));
                    const fraction = Math.abs(count) - whole;
                    let cursor = seconds;
                    while (whole > 0) {
                        cursor += direction * DAY;
                        if (!isWeekend(cursor)) whole--;
                    }
                    return cursor + direction * fraction * DAY;
                }
                case 'week': return seconds + count * 7 * DAY;
                case 'month': return addMonths(seconds, count);
                default: return seconds + count * this.step;
            }
        }

        logical(seconds) {
            const times = this.times;
            if (!times.length || !finite(seconds)) return null;
            const last = times.length - 1;
            if (seconds <= times[0]) return seconds === times[0] ? 0 : -this.steps(seconds, times[0]);
            if (seconds >= times[last]) return last + this.steps(times[last], seconds);
            let low = 0;
            let high = last;
            while (high - low > 1) {
                const middle = (low + high) >> 1;
                if (times[middle] <= seconds) low = middle; else high = middle;
            }
            const span = times[high] - times[low];
            return low + (span > 0 ? (seconds - times[low]) / span : 0);
        }

        seconds(logical) {
            const times = this.times;
            if (!times.length || !finite(logical)) return null;
            const last = times.length - 1;
            if (logical >= 0 && logical <= last) {
                const index = Math.floor(logical);
                const fraction = logical - index;
                if (fraction === 0 || index === last) return times[index];
                return times[index] + fraction * (times[index + 1] - times[index]);
            }
            return logical > last ? this.advance(times[last], logical - last) : this.advance(times[0], logical);
        }

        /** Index of the bar containing the time (or nearest bar), clamped to loaded bars. */
        barIndex(seconds) {
            const logical = this.logical(seconds);
            if (logical === null) return -1;
            return clamp(Math.floor(logical + 1e-9), 0, this.times.length - 1);
        }
    }

    // ------------------------------------------------------------------ canvas helpers for tools

    const LINE_DASH = {solid: [], dashed: [6, 4], dotted: [1.5, 3], largeDashed: [10, 6], sparseDotted: [1.5, 7]};

    const Draw = {
        font(size = 12, weight = 600, italic = false) {
            return `${italic ? 'italic ' : ''}${weight} ${size}px ${FONT}`;
        },
        stroke(ctx, style = {}) {
            ctx.strokeStyle = style.color || '#60a5fa';
            ctx.lineWidth = style.width || 1;
            ctx.setLineDash(LINE_DASH[style.lineStyle] || []);
            ctx.lineCap = 'round';
            ctx.lineJoin = 'round';
        },
        line(ctx, a, b, style) {
            if (!a || !b) return;
            ctx.save();
            Draw.stroke(ctx, style);
            ctx.beginPath();
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(b.x, b.y);
            ctx.stroke();
            ctx.restore();
        },
        polyline(ctx, points, style, closed = false) {
            const valid = points.filter(Boolean);
            if (valid.length < 2) return;
            ctx.save();
            Draw.stroke(ctx, style);
            ctx.beginPath();
            ctx.moveTo(valid[0].x, valid[0].y);
            for (let index = 1; index < valid.length; index++) ctx.lineTo(valid[index].x, valid[index].y);
            if (closed) ctx.closePath();
            ctx.stroke();
            ctx.restore();
        },
        fill(ctx, points, color) {
            const valid = points.filter(Boolean);
            if (valid.length < 3 || !color) return;
            ctx.save();
            ctx.fillStyle = color;
            ctx.beginPath();
            ctx.moveTo(valid[0].x, valid[0].y);
            for (let index = 1; index < valid.length; index++) ctx.lineTo(valid[index].x, valid[index].y);
            ctx.closePath();
            ctx.fill();
            ctx.restore();
        },
        rect(ctx, x, y, width, height, stroke, fill) {
            ctx.save();
            if (fill) { ctx.fillStyle = fill; ctx.fillRect(x, y, width, height); }
            if (stroke) { Draw.stroke(ctx, stroke); ctx.strokeRect(x, y, width, height); }
            ctx.restore();
        },
        circle(ctx, center, radius, stroke, fill) {
            if (!center || !(radius > 0)) return;
            ctx.save();
            ctx.beginPath();
            ctx.arc(center.x, center.y, radius, 0, Math.PI * 2);
            if (fill) { ctx.fillStyle = fill; ctx.fill(); }
            if (stroke) { Draw.stroke(ctx, stroke); ctx.stroke(); }
            ctx.restore();
        },
        ellipse(ctx, center, radiusX, radiusY, rotation, stroke, fill) {
            if (!center || !(radiusX > 0) || !(radiusY > 0)) return;
            ctx.save();
            ctx.beginPath();
            ctx.ellipse(center.x, center.y, radiusX, radiusY, rotation || 0, 0, Math.PI * 2);
            if (fill) { ctx.fillStyle = fill; ctx.fill(); }
            if (stroke) { Draw.stroke(ctx, stroke); ctx.stroke(); }
            ctx.restore();
        },
        arrowHead(ctx, from, to, color, size = 9) {
            if (!from || !to) return;
            const angle = Math.atan2(to.y - from.y, to.x - from.x);
            ctx.save();
            ctx.fillStyle = color;
            ctx.beginPath();
            ctx.moveTo(to.x, to.y);
            ctx.lineTo(to.x - size * Math.cos(angle - 0.42), to.y - size * Math.sin(angle - 0.42));
            ctx.lineTo(to.x - size * Math.cos(angle + 0.42), to.y - size * Math.sin(angle + 0.42));
            ctx.closePath();
            ctx.fill();
            ctx.restore();
        },
        /** A text label with an optional background box; returns its bounding box. */
        label(ctx, text, x, y, options = {}) {
            if (text === null || text === undefined || text === '') return null;
            const size = options.size || 12;
            const lines = String(text).split('\n');
            ctx.save();
            ctx.font = Draw.font(size, options.weight || 600, options.italic);
            const padding = options.padding ?? (options.background ? 5 : 0);
            const lineHeight = Math.round(size * 1.3);
            const width = Math.max(...lines.map(line => ctx.measureText(line).width)) + padding * 2;
            const height = lines.length * lineHeight + padding * 2 - (lineHeight - size) + 2;
            const align = options.align || 'left';
            const baseline = options.baseline || 'middle';
            let left = align === 'center' ? x - width / 2 : (align === 'right' ? x - width : x);
            let top = baseline === 'middle' ? y - height / 2 : (baseline === 'bottom' ? y - height : y);
            if (options.bounds) {
                left = clamp(left, 2, Math.max(2, options.bounds.width - width - 2));
                top = clamp(top, 2, Math.max(2, options.bounds.height - height - 2));
            }
            if (options.background) {
                ctx.fillStyle = options.background;
                Draw.roundRect(ctx, left, top, width, height, options.radius ?? 4);
                ctx.fill();
            }
            if (options.border) {
                ctx.strokeStyle = options.border;
                ctx.lineWidth = 1;
                ctx.setLineDash([]);
                Draw.roundRect(ctx, left, top, width, height, options.radius ?? 4);
                ctx.stroke();
            }
            if (options.accent) {
                ctx.fillStyle = options.accent;
                ctx.fillRect(left, top, 3, height);
            }
            ctx.fillStyle = options.color || '#ffffff';
            ctx.textBaseline = 'top';
            ctx.textAlign = 'left';
            lines.forEach((line, index) => {
                const lineWidth = ctx.measureText(line).width;
                const offset = options.textAlign === 'center' ? (width - padding * 2 - lineWidth) / 2
                    : (options.textAlign === 'right' ? width - padding * 2 - lineWidth : 0);
                ctx.fillText(line, left + padding + (options.accent ? 2 : 0) + offset, top + padding + index * lineHeight + 1);
            });
            ctx.restore();
            return {x: left, y: top, width, height};
        },
        roundRect(ctx, x, y, width, height, radius) {
            const r = Math.min(radius, width / 2, height / 2);
            ctx.beginPath();
            ctx.moveTo(x + r, y);
            ctx.arcTo(x + width, y, x + width, y + height, r);
            ctx.arcTo(x + width, y + height, x, y + height, r);
            ctx.arcTo(x, y + height, x, y, r);
            ctx.arcTo(x, y, x + width, y, r);
            ctx.closePath();
        },
        handle(ctx, point, palette, active = false) {
            if (!point) return;
            ctx.save();
            ctx.beginPath();
            ctx.arc(point.x, point.y, active ? HANDLE + 1 : HANDLE, 0, Math.PI * 2);
            ctx.fillStyle = palette.handleFill;
            ctx.fill();
            ctx.lineWidth = 2;
            ctx.setLineDash([]);
            ctx.strokeStyle = palette.selection;
            ctx.stroke();
            ctx.restore();
        }
    };

    const Geometry = {clamp, distance, segmentDistance, lineDistance, insidePolygon, extendSegment, withAlpha, contrastText};

    // ------------------------------------------------------------------ registry

    /**
     * Registers a tool. A tool declares how it is placed (points, single, freehand, path), its
     * default style, the properties its settings dialog edits, and render/hitTest functions that
     * receive screen coordinates for its points.
     */
    function registerTool(definition) {
        if (!definition?.type) throw new Error('A drawing tool needs a type.');
        tools.set(definition.type, Object.freeze({
            group: 'lines',
            placement: 'points',
            points: 2,
            defaults: {},
            properties: [],
            ...definition
        }));
    }

    function tool(type) {
        return tools.get(type) || null;
    }

    // ------------------------------------------------------------------ persistence

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return token && header ? {[header]: token} : {};
    }

    function readLocal(key, fallback) {
        try {
            const raw = window.localStorage.getItem(key);
            return raw ? JSON.parse(raw) : fallback;
        } catch (error) {
            return fallback;
        }
    }

    function writeLocal(key, value) {
        try {
            if (value === undefined || value === null) window.localStorage.removeItem(key);
            else window.localStorage.setItem(key, JSON.stringify(value));
            return true;
        } catch (error) {
            return false;
        }
    }

    /** Converts a drawing saved by the original Fibonacci/position tool (v1) to the current format. */
    function migrateLegacyDrawing(old) {
        if (!old || typeof old.id !== 'string' || !old.start || !old.end) return null;
        const startTime = toSeconds(old.start.time);
        const endTime = toSeconds(old.end.time);
        if (!finite(startTime) || !finite(endTime)) return null;
        if (old.type === 'long' || old.type === 'short') {
            return {
                id: old.id, type: old.type === 'long' ? 'longPosition' : 'shortPosition', version: 2,
                points: [{time: startTime, price: Number(old.entryPrice)}, {time: endTime, price: Number(old.targetPrice)},
                    {time: endTime, price: Number(old.stopPrice)}],
                style: {}
            };
        }
        return {
            id: old.id, type: 'fibRetracement', version: 2,
            points: [{time: startTime, price: Number(old.start.price)}, {time: endTime, price: Number(old.end.price)}],
            style: {}
        };
    }

    /** Drawings kept in this browser only (pages without a symbol-wide server store). */
    class LocalDrawingStore {
        constructor(key) {
            this.key = key;
        }

        storageKey() { return `stockwatch:drawings:v2:${this.key}`; }

        async load() {
            const stored = readLocal(this.storageKey(), null);
            if (Array.isArray(stored)) return stored;
            const legacy = readLocal(`stockwatch:fibonacci:v1:${this.key}`, []);
            const migrated = Array.isArray(legacy) ? legacy.map(migrateLegacyDrawing).filter(Boolean) : [];
            if (migrated.length) writeLocal(this.storageKey(), migrated);
            return migrated;
        }

        async persist(drawings) {
            if (!writeLocal(this.storageKey(), drawings)) return {status: 'unavailable'};
            return {status: 'saved'};
        }
    }

    const sharedStores = new Map();

    /**
     * Drawings stored with the account for one symbol, so they follow the user across devices.
     * Changes are sent per drawing; failed writes are kept and retried, and a copy is cached in
     * this browser so the chart still opens with drawings when the server is unreachable.
     */
    class ServerDrawingStore {
        constructor(symbol, legacyKeys = []) {
            this.symbol = symbol;
            this.legacyKeys = legacyKeys;
            this.versions = new Map();
            this.sent = new Map();
            this.pending = readLocal(this.pendingKey(), {}) || {};
            this.retryTimer = null;
            this.loading = null;
            this.subscribers = new Set();
            window.addEventListener('online', () => this.flush());
        }

        /**
         * The page's one store for a symbol. Every chart of that symbol on the page (a signal chart and
         * its other-interval chart) uses it, so they share what was sent and see what the others save.
         */
        static shared(symbol, legacyKeys = []) {
            let store = sharedStores.get(symbol);
            if (!store) {
                store = new ServerDrawingStore(symbol, []);
                sharedStores.set(symbol, store);
            }
            legacyKeys.forEach(entry => {
                if (!store.legacyKeys.some(known => known.key === entry.key)) store.legacyKeys.push(entry);
            });
            return store;
        }

        cacheKey() { return `stockwatch:drawings:v2:server-cache:${this.symbol}`; }

        pendingKey() { return `stockwatch:drawings:v2:pending:${this.symbol}`; }

        /** Calls {@code listener(drawings, origin)} after another chart saved; returns the unsubscribe function. */
        subscribe(listener) {
            this.subscribers.add(listener);
            return () => this.subscribers.delete(listener);
        }

        /** Charts created together share one request, so older browser drawings are imported only once. */
        load() {
            if (!this.loading) this.loading = this.fetchDrawings().finally(() => { this.loading = null; });
            return this.loading.then(clone);
        }

        async fetchDrawings() {
            let drawings;
            try {
                const response = await fetch(`/api/chart-state/drawings?symbol=${encodeURIComponent(this.symbol)}`, {credentials: 'same-origin'});
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const body = await response.json();
                drawings = [];
                (body.drawings || []).forEach(item => {
                    this.versions.set(item.id, item.version);
                    if (item.payload && item.payload.type) drawings.push(item.payload);
                });
                await this.importLegacy(drawings);
                drawings.forEach(drawing => this.sent.set(drawing.id, JSON.stringify(drawing)));
                writeLocal(this.cacheKey(), drawings);
                this.offline = false;
            } catch (error) {
                drawings = readLocal(this.cacheKey(), []) || [];
                this.offline = true;
            }
            Object.entries(this.pending).forEach(([id, value]) => {
                const index = drawings.findIndex(drawing => drawing.id === id);
                if (value === null) { if (index >= 0) drawings.splice(index, 1); }
                else if (index >= 0) drawings[index] = value; else drawings.push(value);
            });
            this.flush();
            return drawings;
        }

        /**
         * Brings drawings kept only in this browser into the account: per interval by the stock page before
         * account storage existed, and per page by the other chart pages (current format, or the original
         * Fibonacci tool's).
         */
        async importLegacy(drawings) {
            const imported = [];
            for (const {key, interval} of this.legacyKeys) {
                const current = readLocal(`stockwatch:drawings:v2:${key}`, null);
                const original = readLocal(`stockwatch:fibonacci:v1:${key}`, null);
                const found = [...(Array.isArray(current) ? current.filter(valid) : []),
                    ...(Array.isArray(original) ? original.map(migrateLegacyDrawing).filter(Boolean) : [])];
                found.forEach(drawing => {
                    if (drawings.some(existing => existing.id === drawing.id) || imported.some(item => item.id === drawing.id)) return;
                    drawing.intervals = interval ? [interval] : null;
                    imported.push(drawing);
                });
            }
            if (!imported.length) return;
            const response = await fetch(`/api/chart-state/drawings/import?symbol=${encodeURIComponent(this.symbol)}`, {
                method: 'POST', credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', ...csrfHeaders()},
                body: JSON.stringify({drawings: imported.slice(0, 200)})
            });
            if (!response.ok) return;
            imported.forEach(drawing => { drawings.push(drawing); this.versions.set(drawing.id, 1); });
            this.legacyKeys.forEach(({key}) => {
                writeLocal(`stockwatch:drawings:v2:${key}`, null);
                writeLocal(`stockwatch:fibonacci:v1:${key}`, null);
            });
        }

        /** {@code origin}: the manager saving, so the other charts of the symbol take its drawings. */
        async persist(drawings, origin) {
            const current = new Map(drawings.map(drawing => [drawing.id, JSON.stringify(drawing)]));
            let changed = false;
            current.forEach((json, id) => { if (this.sent.get(id) !== json) { this.pending[id] = JSON.parse(json); changed = true; } });
            this.sent.forEach((json, id) => { if (!current.has(id)) { this.pending[id] = null; changed = true; } });
            this.sent = current;
            writeLocal(this.cacheKey(), drawings);
            writeLocal(this.pendingKey(), this.pending);
            if (changed) this.subscribers.forEach(listener => listener(drawings, origin));
            return this.flush();
        }

        async flush() {
            const entries = Object.entries(this.pending);
            if (!entries.length) return {status: 'saved'};
            let failed = false;
            for (const [id, value] of entries) {
                try {
                    const base = `/api/chart-state/drawings/${encodeURIComponent(id)}?symbol=${encodeURIComponent(this.symbol)}`;
                    let response;
                    if (value === null) {
                        response = await fetch(base, {method: 'DELETE', credentials: 'same-origin', headers: csrfHeaders()});
                    } else {
                        const version = this.versions.get(id);
                        response = await fetch(version ? `${base}&version=${version}` : base, {
                            method: 'PUT', credentials: 'same-origin',
                            headers: {'Content-Type': 'application/json', ...csrfHeaders()},
                            body: JSON.stringify(value)
                        });
                        if (response.status === 409) {
                            // Changed on another device: the server copy wins for this drawing.
                            delete this.pending[id];
                            this.conflict = true;
                            continue;
                        }
                        if (response.ok) {
                            const saved = await response.json();
                            this.versions.set(id, saved.version);
                        }
                    }
                    if (response.ok || response.status === 404) delete this.pending[id];
                    else if (response.status === 400) delete this.pending[id];
                    else failed = true;
                } catch (error) {
                    failed = true;
                }
            }
            writeLocal(this.pendingKey(), this.pending);
            if (failed) {
                window.clearTimeout(this.retryTimer);
                this.retryTimer = window.setTimeout(() => this.flush(), 30000);
                return {status: 'pending'};
            }
            if (this.conflict) {
                this.conflict = false;
                return {status: 'conflict'};
            }
            return {status: 'saved'};
        }
    }

    // ------------------------------------------------------------------ rendering primitive

    const EMPTY = Object.freeze([]);

    /** One per pane: paints the drawings that belong to it and labels the axes for the selection. */
    class PaneLayer {
        constructor(manager, key) {
            this.manager = manager;
            this.key = key;
            this.requestUpdate = null;
            this.priceViews = EMPTY;
            this.timeViews = EMPTY;
            const base = {draw: target => manager.paint(key, target, false)};
            const top = {draw: target => manager.paint(key, target, true)};
            this.views = [
                {zOrder: () => 'normal', renderer: () => base},
                {zOrder: () => 'top', renderer: () => top}
            ];
        }

        attached(parameters) { this.requestUpdate = parameters.requestUpdate; }

        detached() { this.requestUpdate = null; }

        updateAllViews() {
            this.priceViews = this.manager.priceAxisViews(this.key);
            this.timeViews = this.key === 'price' ? this.manager.timeAxisViews() : EMPTY;
        }

        paneViews() { return this.views; }

        priceAxisViews() { return this.priceViews; }

        timeAxisViews() { return this.timeViews; }

        hitTest(x, y) { return this.manager.primitiveHit(this.key, x, y); }
    }

    /** "22 May '26", matching the chart's own crosshair date label. */
    function axisDate(seconds) {
        const date = new Date(seconds * 1000);
        const month = i18n.date(date, {month: 'short', timeZone: 'UTC'});
        return `${date.getUTCDate()} ${month} '${String(date.getUTCFullYear()).slice(-2)}`;
    }

    function axisView(coordinate, text, background) {
        const textColor = contrastText(background);
        return {
            coordinate: () => coordinate,
            text: () => text,
            textColor: () => textColor,
            backColor: () => background,
            visible: () => true,
            tickVisible: () => true
        };
    }

    // ------------------------------------------------------------------ manager

    class DrawingManager {
        /**
         * @param options.chart Lightweight Charts chart
         * @param options.series the main price series (candles or line)
         * @param options.container element containing the chart
         * @param options.store a LocalDrawingStore or ServerDrawingStore
         * @param options.interval '1d', '1wk' or '1mo' (drawings can be limited to intervals)
         * @param options.bars optional function returning [{time (seconds), open, high, low, close, volume}]
         */
        constructor(options = {}) {
            if (!options.chart || !options.series || !options.container) {
                throw new Error('A chart, price series, and chart container are required.');
            }
            this.chart = options.chart;
            this.series = options.series;
            this.container = options.container;
            this.store = options.store || new LocalDrawingStore(options.storageKey || window.location.pathname);
            this.unsubscribeStore = this.store.subscribe?.((drawings, origin) => { if (origin !== this) this.adopt(drawings); }) || null;
            this.interval = options.interval || null;
            this.barsProvider = typeof options.bars === 'function' ? options.bars : null;
            this.priceFormatter = typeof options.priceFormatter === 'function' ? options.priceFormatter : null;
            this.features = {alerts: false, demoTrade: false, ...options.features};
            this.drawings = [];
            this.selection = new Set();
            this.mode = 'cursor';
            this.draft = null;
            this.drag = null;
            this.hover = null;
            this.measure = null;
            this.magnet = 'off';
            this.stayInMode = false;
            this.lockedAll = false;
            this.hiddenAll = false;
            this.undoStack = [];
            this.redoStack = [];
            this.listeners = new Map();
            this.panes = new Map();
            this.mapper = new TimeMapper([]);
            this.bars = [];
            this.destroyed = false;
            this.persistTimer = null;
            this.renderFrame = null;
            this.repaintPending = false;
            this.consumePointer = false;
            this.status = '';

            const preferences = readLocal('stockwatch:drawing-preferences:v1', {}) || {};
            this.magnet = ['off', 'weak', 'strong'].includes(preferences.magnet) ? preferences.magnet : 'off';
            this.stayInMode = Boolean(preferences.stayInMode);

            this.registerPane('price', this.series);
            this.onDataChanged = () => this.refreshBars();
            this.series.subscribeDataChanged?.(this.onDataChanged);
            // The chart repaints its primitives itself while panning and zooming; asking it to update them as well would
            // force a full relayout of every pane on each frame. Listeners such as the selection toolbar still follow.
            this.onRange = () => this.requestRender(false);
            this.chart.timeScale().subscribeVisibleLogicalRangeChange(this.onRange);
            this.bindPointer();
            this.refreshBars();
            managers.add(this);
            activeManager = activeManager || this;
            this.ready = this.reload();
            if (window.StockWatchDrawingUI && options.ui !== false) {
                this.ui = new window.StockWatchDrawingUI(this, options);
            }
        }

        // ---- events

        on(name, listener) {
            if (!this.listeners.has(name)) this.listeners.set(name, new Set());
            this.listeners.get(name).add(listener);
            return () => this.listeners.get(name)?.delete(listener);
        }

        emit(name, detail) {
            this.listeners.get(name)?.forEach(listener => {
                try { listener(detail); } catch (error) { console.error(error); }
            });
        }

        setStatus(message) {
            this.status = message || '';
            this.emit('status', this.status);
        }

        // ---- panes

        /** Lets drawings be placed on another pane (e.g. RSI); {@code series} is that pane's first series. */
        registerPane(key, series) {
            if (this.panes.has(key)) this.unregisterPane(key);
            const layer = new PaneLayer(this, key);
            series.attachPrimitive(layer);
            this.panes.set(key, {series, layer});
            this.requestRender();
        }

        unregisterPane(key) {
            const pane = this.panes.get(key);
            if (!pane) return;
            try { pane.series.detachPrimitive(pane.layer); } catch (error) { /* series already removed */ }
            this.panes.delete(key);
            this.requestRender();
        }

        paneElement(key) {
            const pane = this.panes.get(key);
            try { return pane?.series.getPane().getHTMLElement() || null; } catch (error) { return null; }
        }

        /** The pane under a client coordinate and the coordinate within it. */
        locate(clientX, clientY) {
            for (const [key] of this.panes) {
                const element = this.paneElement(key);
                if (!element) continue;
                const rect = element.getBoundingClientRect();
                if (clientX >= rect.left && clientX <= rect.right && clientY >= rect.top && clientY <= rect.bottom) {
                    return {pane: key, x: clientX - rect.left, y: clientY - rect.top, width: rect.width, height: rect.height};
                }
            }
            return null;
        }

        // ---- data

        refreshBars() {
            let data = [];
            try { data = this.series.data() || []; } catch (error) { data = []; }
            const provided = this.barsProvider ? (this.barsProvider() || []) : null;
            if (provided && provided.length) {
                // The page's own candles: the series may show transformed values (Heikin Ashi, a line).
                this.bars = provided.map(bar => ({
                    time: toSeconds(bar.time ?? bar.timestamp), open: Number(bar.open ?? bar.close), high: Number(bar.high ?? bar.close),
                    low: Number(bar.low ?? bar.close), close: Number(bar.close), volume: Number(bar.volume ?? NaN)
                })).filter(bar => finite(bar.time) && finite(bar.close));
                this.mapper = new TimeMapper(this.bars.map(bar => bar.time));
                this.requestRender();
                this.emit('bars', this.bars);
                return;
            }
            const byTime = new Map();
            (provided || []).forEach(bar => {
                const seconds = toSeconds(bar.time ?? bar.timestamp);
                if (finite(seconds)) byTime.set(seconds, bar);
            });
            this.bars = data.map(item => {
                const seconds = toSeconds(item.time);
                const extra = byTime.get(seconds);
                const close = Number(item.close ?? item.value);
                return {
                    time: seconds,
                    open: Number(item.open ?? extra?.open ?? close),
                    high: Number(item.high ?? extra?.high ?? close),
                    low: Number(item.low ?? extra?.low ?? close),
                    close,
                    volume: Number(extra?.volume ?? item.volume ?? NaN)
                };
            }).filter(bar => finite(bar.time) && finite(bar.close));
            this.mapper = new TimeMapper(this.bars.map(bar => bar.time));
            this.requestRender();
            this.emit('bars', this.bars);
        }

        hasVolume() { return this.bars.some(bar => finite(bar.volume) && bar.volume > 0); }

        // ---- coordinate environment

        environment(paneKey, width, height) {
            const pane = this.panes.get(paneKey) || this.panes.get('price');
            const series = pane.series;
            const timeScale = this.chart.timeScale();
            const mapper = this.mapper;
            const manager = this;
            let logScale = false;
            try { logScale = series.priceScale().options().mode === 1; } catch (error) { logScale = false; }
            return {
                pane: paneKey,
                width,
                height,
                series,
                bars: this.bars,
                mapper,
                logScale,
                palette: themePalette(),
                barSpacing: timeScale.options().barSpacing || 6,
                x(seconds) {
                    const logical = mapper.logical(seconds);
                    return logical === null ? null : timeScale.logicalToCoordinate(logical);
                },
                y(price) {
                    const value = series.priceToCoordinate(price);
                    return finite(value) ? value : null;
                },
                point(point) {
                    if (point && finite(point.rx) && finite(point.ry)) return {x: point.rx * width, y: point.ry * height};
                    const x = this.x(point.time);
                    const y = this.y(point.price);
                    return finite(x) && finite(y) ? {x, y} : null;
                },
                time(x, snap = true) {
                    const logical = timeScale.coordinateToLogical(x);
                    if (!finite(logical)) return null;
                    return mapper.seconds(snap ? Math.round(logical) : logical);
                },
                price(y) {
                    const value = series.coordinateToPrice(y);
                    return finite(value) ? value : null;
                },
                bar(seconds) {
                    const index = mapper.barIndex(seconds);
                    return index >= 0 ? manager.bars[index] : null;
                },
                format: manager.formatters(series)
            };
        }

        formatters(series) {
            const priceFormatter = this.priceFormatter;
            return {
                price(value) {
                    if (!finite(value)) return '';
                    if (priceFormatter) return priceFormatter(value);
                    try { return series.priceFormatter().format(value); } catch (error) { return i18n.number(value, 2); }
                },
                percent(value, digits = 2) {
                    return finite(value) ? `${value > 0 ? '+' : ''}${i18n.number(value, digits)}%` : '';
                },
                number(value, digits = 2) { return finite(value) ? i18n.number(value, digits) : ''; },
                volume(value) {
                    if (!finite(value)) return '';
                    const absolute = Math.abs(value);
                    if (absolute >= 1e9) return `${i18n.number(value / 1e9, 2)}B`;
                    if (absolute >= 1e6) return `${i18n.number(value / 1e6, 2)}M`;
                    if (absolute >= 1e3) return `${i18n.number(value / 1e3, 1)}K`;
                    return i18n.number(value, 0);
                },
                date(seconds) {
                    if (!finite(seconds)) return '';
                    return i18n.date(seconds * 1000, {year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'});
                },
                days(seconds) {
                    return i18n.plural('draw.format.days', Math.round(Math.abs(seconds) / DAY), Math.round(Math.abs(seconds) / DAY));
                },
                bars(count) {
                    return i18n.plural('draw.format.bars', Math.round(count), Math.round(count));
                }
            };
        }

        // ---- rendering

        requestRender(repaint = true) {
            if (this.destroyed) return;
            this.repaintPending = this.repaintPending || repaint;
            if (this.renderFrame !== null) return;
            this.renderFrame = window.requestAnimationFrame(() => {
                this.renderFrame = null;
                if (this.repaintPending) {
                    this.repaintPending = false;
                    this.panes.forEach(pane => {
                        try { pane.layer.updateAllViews(); } catch (error) { /* chart removed */ }
                        pane.layer.requestUpdate?.();
                    });
                }
                this.emit('render');
            });
        }

        visible(drawing) {
            if (this.hiddenAll || drawing.hidden) return false;
            if (Array.isArray(drawing.intervals) && drawing.intervals.length && this.interval
                && !drawing.intervals.includes(this.interval)) return false;
            return true;
        }

        paneOf(drawing) {
            return drawing.pane && this.panes.has(drawing.pane) ? drawing.pane : (drawing.pane ? null : 'price');
        }

        context(drawing, env, flags = {}) {
            const definition = tool(drawing.type);
            const points = (drawing.points || []).map(point => env.point(point));
            return {
                drawing,
                definition,
                style: {...(definition?.defaults || {}), ...(drawing.style || {})},
                points,
                env,
                selected: Boolean(flags.selected),
                hovered: Boolean(flags.hovered),
                draft: Boolean(flags.draft),
                manager: this
            };
        }

        paint(paneKey, target, topLayer) {
            target.useMediaCoordinateSpace(({context: ctx, mediaSize}) => {
                const env = this.environment(paneKey, mediaSize.width, mediaSize.height);
                if (!topLayer) {
                    this.drawings.forEach(drawing => {
                        if (!this.visible(drawing) || this.paneOf(drawing) !== paneKey) return;
                        this.renderDrawing(ctx, drawing, env, {
                            selected: this.selection.has(drawing.id),
                            hovered: this.hover?.id === drawing.id
                        });
                    });
                    if (this.draft && this.draft.pane === paneKey) this.renderDrawing(ctx, this.draft.drawing, env, {draft: true, selected: true});
                    return;
                }
                this.drawings.forEach(drawing => {
                    if (!this.selection.has(drawing.id) || !this.visible(drawing) || this.paneOf(drawing) !== paneKey) return;
                    this.renderHandles(ctx, drawing, env);
                });
                if (this.draft && this.draft.pane === paneKey) this.renderHandles(ctx, this.draft.drawing, env, true);
                if (this.measure && this.measure.pane === paneKey) this.renderMeasure(ctx, env);
                if (this.zoomBox && this.zoomBox.pane === paneKey) this.renderZoomBox(ctx, env);
            });
        }

        renderDrawing(ctx, drawing, env, flags) {
            const definition = tool(drawing.type);
            if (!definition?.render) return;
            const context = this.context(drawing, env, flags);
            if (!definition.screenAnchored && context.points.some(point => point === null)) {
                if (!definition.tolerateMissing) return;
            }
            ctx.save();
            try {
                definition.render(ctx, context);
            } catch (error) {
                console.warn('Drawing could not be rendered', drawing.type, error);
            }
            ctx.restore();
        }

        renderHandles(ctx, drawing, env, draft = false) {
            const definition = tool(drawing.type);
            if (!definition) return;
            const context = this.context(drawing, env, {selected: true, draft});
            const handles = definition.handles ? definition.handles(context) : context.points;
            const palette = env.palette;
            handles.forEach((point, index) => {
                if (!point) return;
                Draw.handle(ctx, point, palette, this.drag?.id === drawing.id && this.drag?.index === index);
            });
            if (drawing.locked || this.lockedAll) {
                const first = handles.find(Boolean);
                if (first) Draw.label(ctx, '🔒', first.x + 8, first.y - 12, {size: 11, color: palette.text});
            }
        }

        renderMeasure(ctx, env) {
            const a = env.point(this.measure.start);
            const b = env.point(this.measure.end);
            if (!a || !b) return;
            const up = this.measure.end.price >= this.measure.start.price;
            const color = up ? '#2962ff' : '#ef5350';
            Draw.rect(ctx, Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y), null, withAlpha(color, 0.16));
            Draw.line(ctx, {x: (a.x + b.x) / 2, y: a.y}, {x: (a.x + b.x) / 2, y: b.y}, {color, width: 1});
            Draw.arrowHead(ctx, {x: (a.x + b.x) / 2, y: a.y}, {x: (a.x + b.x) / 2, y: b.y}, color, 8);
            Draw.line(ctx, {x: a.x, y: (a.y + b.y) / 2}, {x: b.x, y: (a.y + b.y) / 2}, {color, width: 1});
            const change = this.measure.end.price - this.measure.start.price;
            const percent = this.measure.start.price ? change / this.measure.start.price * 100 : 0;
            const bars = env.mapper.logical(this.measure.end.time) - env.mapper.logical(this.measure.start.time);
            const lines = [`${env.format.price(change)} (${env.format.percent(percent)})`,
                `${env.format.bars(bars)}, ${env.format.days(this.measure.end.time - this.measure.start.time)}`];
            Draw.label(ctx, lines.join('\n'), (a.x + b.x) / 2, Math.max(a.y, b.y) + 8,
                {align: 'center', baseline: 'top', background: color, color: '#ffffff', size: 12, bounds: env});
        }

        renderZoomBox(ctx, env) {
            const box = this.zoomBox;
            Draw.rect(ctx, Math.min(box.x0, box.x1), Math.min(box.y0, box.y1), Math.abs(box.x1 - box.x0), Math.abs(box.y1 - box.y0),
                {color: env.palette.selection, width: 1, lineStyle: 'dashed'}, withAlpha('#7ea6ff', 0.1));
        }

        priceAxisViews(paneKey) {
            const views = [];
            const pane = this.panes.get(paneKey);
            if (!pane) return EMPTY;
            const series = pane.series;
            const formatter = this.formatters(series);
            const label = (price, color) => {
                const y = series.priceToCoordinate(price);
                if (finite(y)) views.push(axisView(y, formatter.price(price), color || '#2962ff'));
            };
            this.drawings.forEach(drawing => {
                const definition = tool(drawing.type);
                const labelled = definition?.axisLabels === 'always' || (definition?.axisLabels !== 'none' && this.selection.has(drawing.id));
                if (!labelled || !this.visible(drawing) || this.paneOf(drawing) !== paneKey) return;
                const color = drawing.style?.color ?? definition?.defaults?.color;
                const prices = definition?.axisPrices ? definition.axisPrices(drawing) : (drawing.points || []).map(point => point.price);
                prices.forEach(price => { if (finite(price)) label(price, color); });
            });
            if (this.draft && this.draft.pane === paneKey) {
                (this.draft.drawing.points || []).forEach(point => { if (finite(point.price)) label(point.price, '#2962ff'); });
            }
            return views.slice(0, 40);
        }

        timeAxisViews() {
            const views = [];
            const timeScale = this.chart.timeScale();
            const label = (seconds, color) => {
                const logical = this.mapper.logical(seconds);
                const x = logical === null ? null : timeScale.logicalToCoordinate(logical);
                if (finite(x)) views.push(axisView(x, axisDate(seconds), color));
            };
            this.drawings.forEach(drawing => {
                if (!this.selection.has(drawing.id) || !this.visible(drawing)) return;
                const definition = tool(drawing.type);
                if (definition?.axisLabels === 'none' || definition?.screenAnchored) return;
                const style = {...(definition?.defaults || {}), ...(drawing.style || {})};
                const times = definition?.axisTimes ? definition.axisTimes(drawing) : (drawing.points || []).map(point => point.time);
                [...new Set(times)].forEach(seconds => { if (finite(seconds)) label(seconds, style.color || '#2962ff'); });
            });
            if (this.draft) (this.draft.drawing.points || []).forEach(point => { if (finite(point.time)) label(point.time, '#2962ff'); });
            return views.slice(0, 20);
        }

        // ---- hit testing

        hitTest(paneKey, x, y, tolerance = HIT) {
            const env = this.environmentFor(paneKey);
            if (!env) return null;
            for (let index = this.drawings.length - 1; index >= 0; index--) {
                const drawing = this.drawings[index];
                if (!this.visible(drawing) || this.paneOf(drawing) !== paneKey) continue;
                const definition = tool(drawing.type);
                if (!definition) continue;
                const context = this.context(drawing, env, {selected: this.selection.has(drawing.id)});
                if (context.selected) {
                    const handles = definition.handles ? definition.handles(context) : context.points;
                    for (let handle = 0; handle < handles.length; handle++) {
                        if (handles[handle] && distance({x, y}, handles[handle]) <= tolerance + 3) {
                            return {drawing, part: 'point', index: handle, cursor: 'grab'};
                        }
                    }
                }
                const hit = definition.hitTest ? definition.hitTest(context, x, y, tolerance) : null;
                if (hit) return {drawing, part: hit.part || 'move', index: hit.index, cursor: hit.cursor || 'move'};
            }
            return null;
        }

        environmentFor(paneKey) {
            const element = this.paneElement(paneKey);
            if (!element) return null;
            return this.environment(paneKey, element.clientWidth, element.clientHeight);
        }

        primitiveHit(paneKey, x, y) {
            if (this.mode !== 'cursor' || this.drag) return null;
            const hit = this.hitTest(paneKey, x, y);
            return hit ? {externalId: hit.drawing.id, zOrder: 'normal', cursorStyle: hit.drawing.locked || this.lockedAll ? 'pointer' : hit.cursor} : null;
        }

        // ---- pointer interaction

        bindPointer() {
            const host = this.chart.chartElement?.() || this.container;
            this.pointerHost = host;
            this.onPointerDown = event => this.handlePointerDown(event);
            this.onPointerMove = event => this.handlePointerMove(event);
            this.onPointerUp = event => this.handlePointerUp(event);
            this.onBlockMouse = event => {
                if (this.consumePointer) { event.stopPropagation(); if (event.cancelable && event.type.startsWith('touch')) event.preventDefault(); }
            };
            this.onDoubleClick = event => this.handleDoubleClick(event);
            this.onContextMenu = event => this.handleContextMenu(event);
            this.onKeyDown = event => this.handleKeyDown(event);
            this.onEnter = () => { activeManager = this; };
            host.addEventListener('pointerdown', this.onPointerDown, true);
            host.addEventListener('mousedown', this.onBlockMouse, true);
            host.addEventListener('touchstart', this.onBlockMouse, {capture: true, passive: false});
            host.addEventListener('touchmove', this.onBlockMouse, {capture: true, passive: false});
            host.addEventListener('mousemove', this.onBlockMouse, true);
            host.addEventListener('dblclick', this.onDoubleClick, true);
            host.addEventListener('contextmenu', this.onContextMenu, true);
            host.addEventListener('pointerenter', this.onEnter);
            window.addEventListener('pointermove', this.onPointerMove, true);
            window.addEventListener('pointerup', this.onPointerUp, true);
            window.addEventListener('pointercancel', this.onPointerUp, true);
            document.addEventListener('keydown', this.onKeyDown);
        }

        /** The chart point under the pointer, snapped to bars and, with the magnet on, to prices. */
        pointerPoint(location, event, forceMagnet = false) {
            const env = this.environment(location.pane, location.width, location.height);
            const time = env.time(location.x, true);
            let price = env.price(location.y);
            if (!finite(time) || !finite(price)) return null;
            const magnet = forceMagnet ? 'strong' : (event?.ctrlKey || event?.metaKey ? (this.magnet === 'off' ? 'strong' : 'off') : this.magnet);
            if (magnet !== 'off' && location.pane === 'price') {
                const bar = env.bar(time);
                if (bar) {
                    const candidates = [bar.open, bar.high, bar.low, bar.close].filter(finite);
                    let best = null;
                    candidates.forEach(candidate => {
                        const candidateY = env.y(candidate);
                        if (!finite(candidateY)) return;
                        const distanceY = Math.abs(candidateY - location.y);
                        if (best === null || distanceY < best.distance) best = {price: candidate, distance: distanceY};
                    });
                    if (best && (magnet === 'strong' || best.distance <= 14)) price = best.price;
                }
            }
            return {time, price, x: location.x, y: location.y, env};
        }

        handlePointerDown(event) {
            this.consumePointer = false;
            if (this.destroyed || event.button === 2) return;
            activeManager = this;
            const location = this.locate(event.clientX, event.clientY);
            if (!location) return;
            const touch = event.pointerType === 'touch';
            const tolerance = touch ? HIT + 8 : HIT;

            if (this.mode === 'zoom') {
                this.consume(event);
                this.zoomBox = {pane: location.pane, x0: location.x, y0: location.y, x1: location.x, y1: location.y, pointerId: event.pointerId};
                return;
            }

            if (this.mode === 'eraser') {
                const hit = this.hitTest(location.pane, location.x, location.y, tolerance);
                this.consume(event);
                if (hit && !hit.drawing.locked && !this.lockedAll) {
                    this.change(() => { this.drawings = this.drawings.filter(drawing => drawing.id !== hit.drawing.id); });
                    this.selection.delete(hit.drawing.id);
                    this.emit('selection', this.selectedDrawings());
                }
                return;
            }

            if (this.mode === 'measure' || (this.mode === 'cursor' && event.shiftKey && event.button === 0)) {
                const point = this.pointerPoint(location, event);
                if (!point) return;
                this.consume(event);
                this.measure = {pane: location.pane, start: {time: point.time, price: point.price}, end: {time: point.time, price: point.price},
                    pointerId: event.pointerId, active: true};
                this.requestRender();
                return;
            }
            if (this.measure && !this.measure.active) {
                this.measure = null;
                this.requestRender();
            }

            if (this.mode !== 'cursor') {
                if (this.lockedAll) {
                    this.setStatus(i18n.t('draw.status.unlockFirst'));
                    return;
                }
                const point = this.pointerPoint(location, event);
                if (!point) return;
                this.consume(event);
                this.placePoint(location, point, event);
                return;
            }

            const hit = this.hitTest(location.pane, location.x, location.y, tolerance);
            if (!hit) {
                if (this.selection.size && !event.ctrlKey && !event.metaKey) {
                    this.selection.clear();
                    this.emit('selection', []);
                    this.requestRender();
                }
                return;
            }
            this.consume(event);
            const drawing = hit.drawing;
            if (event.ctrlKey || event.metaKey || (event.shiftKey && this.selection.size)) {
                if (this.selection.has(drawing.id)) this.selection.delete(drawing.id); else this.selection.add(drawing.id);
            } else if (!this.selection.has(drawing.id)) {
                this.selection.clear();
                this.selection.add(drawing.id);
            }
            this.emit('selection', this.selectedDrawings());
            this.requestRender();
            if (drawing.locked || this.lockedAll) return;
            const point = this.pointerPoint(location, event, false);
            if (!point) return;
            const env = point.env;
            this.drag = {
                id: drawing.id,
                part: hit.part,
                index: hit.index,
                pointerId: event.pointerId,
                pane: location.pane,
                startX: location.x,
                startY: location.y,
                ids: [...this.selection],
                originals: new Map([...this.selection].map(id => [id, clone(this.find(id))]).filter(([, value]) => value)),
                before: this.snapshot(),
                moved: false,
                env
            };
            try { this.pointerHost.setPointerCapture(event.pointerId); } catch (error) { /* not capturable */ }
        }

        consume(event) {
            this.consumePointer = true;
            event.preventDefault();
            event.stopPropagation();
        }

        handlePointerMove(event) {
            if (this.destroyed) return;
            if (this.zoomBox && event.pointerId === this.zoomBox.pointerId) {
                const location = this.locate(event.clientX, event.clientY) || this.lastLocation;
                if (location && location.pane === this.zoomBox.pane) {
                    this.zoomBox.x1 = location.x;
                    this.zoomBox.y1 = location.y;
                    this.requestRender();
                }
                return;
            }
            const location = this.locate(event.clientX, event.clientY);
            if (location) this.lastLocation = location;
            if (this.measure?.active && event.pointerId === this.measure.pointerId) {
                if (!location || location.pane !== this.measure.pane) return;
                const point = this.pointerPoint(location, event);
                if (point) { this.measure.end = {time: point.time, price: point.price}; this.requestRender(); }
                return;
            }
            if (this.draft) {
                if (!location || location.pane !== this.draft.pane) return;
                const point = this.pointerPoint(location, event);
                if (!point) return;
                this.updateDraft(point, event);
                return;
            }
            if (!this.drag || event.pointerId !== this.drag.pointerId) {
                if (this.mode === 'cursor' && location) {
                    const hit = this.hitTest(location.pane, location.x, location.y);
                    const id = hit?.drawing.id || null;
                    if ((this.hover?.id || null) !== id) {
                        this.hover = hit ? {id} : null;
                        this.requestRender();
                    }
                }
                return;
            }
            event.preventDefault();
            const drag = this.drag;
            const env = drag.env;
            const dx = (location ? location.x : this.lastLocation?.x ?? drag.startX) - drag.startX;
            const dy = (location ? location.y : this.lastLocation?.y ?? drag.startY) - drag.startY;
            if (!drag.moved && Math.hypot(dx, dy) < 3) return;
            drag.moved = true;
            const drawing = this.find(drag.id);
            if (!drawing) return;
            if (drag.part === 'point') {
                const point = location && location.pane === drag.pane ? this.pointerPoint(location, event) : null;
                if (!point) return;
                const definition = tool(drawing.type);
                const original = drag.originals.get(drawing.id);
                const next = definition?.screenAnchored
                    ? {rx: clamp(point.x / env.width, 0, 1), ry: clamp(point.y / env.height, 0, 1)}
                    : {time: point.time, price: point.price};
                if (definition?.movePoint) definition.movePoint(drawing, drag.index, next, original, env);
                else drawing.points[drag.index] = next;
            } else {
                const barShift = Math.round(dx / env.barSpacing);
                drag.ids.forEach(id => {
                    const target = this.find(id);
                    const original = drag.originals.get(id);
                    if (!target || !original || target.locked) return;
                    const definition = tool(target.type);
                    target.points = original.points.map(point => {
                        if (finite(point.rx)) {
                            return {rx: clamp(point.rx + dx / env.width, 0, 1), ry: clamp(point.ry + dy / env.height, 0, 1)};
                        }
                        const y = env.y(point.price);
                        const price = finite(y) ? env.price(y + dy) : null;
                        const logical = env.mapper.logical(point.time);
                        return {
                            time: logical === null ? point.time : env.mapper.seconds(logical + barShift),
                            price: finite(price) ? price : point.price
                        };
                    });
                    definition?.afterMove?.(target, original);
                });
            }
            this.requestRender();
            this.emit('drag', drawing);
        }

        handlePointerUp(event) {
            if (this.zoomBox && event.pointerId === this.zoomBox.pointerId) {
                this.finishZoom();
                return;
            }
            if (this.measure?.active && event.pointerId === this.measure.pointerId) {
                this.measure.active = false;
                if (this.mode === 'measure' && !this.stayInMode) this.setMode('cursor');
                this.consumePointer = false;
                return;
            }
            if (this.draft && this.draft.dragPlacement && event.pointerId === this.draft.pointerId) {
                const location = this.locate(event.clientX, event.clientY);
                const point = location && location.pane === this.draft.pane ? this.pointerPoint(location, event) : null;
                const definition = tool(this.draft.drawing.type);
                if (definition.placement === 'freehand') {
                    this.completeDraft();
                } else if (point && Math.hypot(point.x - this.draft.downX, point.y - this.draft.downY) > 6 && definition.points === 2
                    && this.draft.next === 1) {
                    this.placePoint(location, point, event);
                }
                this.draft && (this.draft.dragPlacement = false);
                this.consumePointer = false;
                return;
            }
            if (!this.drag || event.pointerId !== this.drag.pointerId) {
                this.consumePointer = false;
                return;
            }
            try { this.pointerHost.releasePointerCapture(event.pointerId); } catch (error) { /* already released */ }
            const drag = this.drag;
            this.drag = null;
            this.consumePointer = false;
            if (drag.moved) this.commit(drag.before);
            this.requestRender();
        }

        finishZoom() {
            const box = this.zoomBox;
            this.zoomBox = null;
            this.consumePointer = false;
            if (Math.abs(box.x1 - box.x0) > 8) {
                const timeScale = this.chart.timeScale();
                const from = timeScale.coordinateToLogical(Math.min(box.x0, box.x1));
                const to = timeScale.coordinateToLogical(Math.max(box.x0, box.x1));
                if (finite(from) && finite(to)) timeScale.setVisibleLogicalRange({from, to});
                const pane = this.panes.get(box.pane);
                if (pane && Math.abs(box.y1 - box.y0) > 8) {
                    const top = pane.series.coordinateToPrice(Math.min(box.y0, box.y1));
                    const bottom = pane.series.coordinateToPrice(Math.max(box.y0, box.y1));
                    if (finite(top) && finite(bottom)) {
                        try {
                            pane.series.priceScale().setAutoScale(false);
                            pane.series.priceScale().setVisibleRange({from: Math.min(top, bottom), to: Math.max(top, bottom)});
                        } catch (error) { /* scale cannot be set */ }
                    }
                }
            }
            if (!this.stayInMode) this.setMode('cursor');
            this.requestRender();
        }

        resetZoom() {
            this.panes.forEach(pane => { try { pane.series.priceScale().setAutoScale(true); } catch (error) { /* ignore */ } });
            this.chart.timeScale().fitContent();
        }

        handleDoubleClick(event) {
            const location = this.locate(event.clientX, event.clientY);
            if (!location) return;
            if (this.draft && tool(this.draft.drawing.type)?.placement === 'path') {
                event.preventDefault();
                event.stopPropagation();
                this.completeDraft();
                return;
            }
            if (this.mode !== 'cursor') return;
            const hit = this.hitTest(location.pane, location.x, location.y);
            if (!hit) return;
            event.preventDefault();
            event.stopPropagation();
            this.emit('edit', hit.drawing);
        }

        handleContextMenu(event) {
            const location = this.locate(event.clientX, event.clientY);
            if (this.draft) {
                event.preventDefault();
                event.stopPropagation();
                if (tool(this.draft.drawing.type)?.placement === 'path' && this.draft.drawing.points.length > 2) {
                    this.draft.drawing.points.pop();
                    this.completeDraft();
                } else {
                    this.cancelDraft();
                }
                return;
            }
            if (this.mode !== 'cursor') {
                event.preventDefault();
                event.stopPropagation();
                this.setMode('cursor');
                return;
            }
            if (!location) return;
            const hit = this.hitTest(location.pane, location.x, location.y);
            if (!hit) return;
            event.preventDefault();
            event.stopPropagation();
            if (!this.selection.has(hit.drawing.id)) {
                this.selection.clear();
                this.selection.add(hit.drawing.id);
                this.emit('selection', this.selectedDrawings());
                this.requestRender();
            }
            this.emit('contextmenu', {drawing: hit.drawing, clientX: event.clientX, clientY: event.clientY});
        }

        handleKeyDown(event) {
            if (activeManager !== this || this.destroyed) return;
            const target = event.target;
            if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement
                || target?.isContentEditable) return;
            if (!this.isVisible()) return;
            const key = event.key;
            const modifier = event.ctrlKey || event.metaKey;
            if (key === 'Escape') {
                if (this.draft) { this.cancelDraft(); event.preventDefault(); return; }
                if (this.measure) { this.measure = null; this.requestRender(); }
                if (this.mode !== 'cursor') { this.setMode('cursor'); event.preventDefault(); return; }
                if (this.selection.size) { this.selection.clear(); this.emit('selection', []); this.requestRender(); }
                return;
            }
            if (key === 'Enter' && this.draft && tool(this.draft.drawing.type)?.placement === 'path') {
                event.preventDefault();
                this.completeDraft();
                return;
            }
            if ((key === 'Delete' || key === 'Backspace') && this.selection.size) {
                event.preventDefault();
                this.deleteSelected();
                return;
            }
            if (modifier && !event.altKey) {
                const lower = key.toLowerCase();
                if (lower === 'z' && !event.shiftKey) { event.preventDefault(); this.undo(); return; }
                if (lower === 'y' || (lower === 'z' && event.shiftKey)) { event.preventDefault(); this.redo(); return; }
                if (lower === 'c' && this.selection.size) { event.preventDefault(); this.copy(); return; }
                if (lower === 'v') { event.preventDefault(); this.paste(); return; }
                if (lower === 'd' && this.selection.size) { event.preventDefault(); this.clone(); return; }
                if (lower === 'a' && this.drawings.length) { event.preventDefault(); this.selectAll(); return; }
            }
            if (event.altKey && !modifier) {
                const shortcut = shortcutKey(event);
                const match = [...tools.values()].find(candidate => candidate.shortcut === shortcut);
                if (match) {
                    event.preventDefault();
                    this.setMode(match.type);
                }
            }
        }

        isVisible() {
            return this.container.isConnected && !this.container.closest('[hidden]') && this.container.getClientRects().length > 0;
        }

        // ---- placement

        setMode(mode) {
            if (this.draft) this.cancelDraft(false);
            const next = mode === 'cursor' || mode === 'eraser' || mode === 'measure' || mode === 'zoom' || tools.has(mode) ? mode : 'cursor';
            this.mode = next;
            if (next !== 'cursor' && next !== 'eraser') {
                this.selection.clear();
                this.emit('selection', []);
            }
            this.container.classList.toggle('drawing-mode-active', next !== 'cursor');
            const definition = tool(next);
            if (definition) this.setStatus(i18n.t('draw.status.place', i18n.t(`draw.tool.${next}`)));
            else if (next === 'eraser') this.setStatus(i18n.t('draw.status.eraser'));
            else if (next === 'measure') this.setStatus(i18n.t('draw.status.measure'));
            else if (next === 'zoom') this.setStatus(i18n.t('draw.status.zoom'));
            else this.setStatus('');
            this.emit('mode', next);
            this.requestRender();
        }

        placePoint(location, point, event) {
            const definition = tool(this.mode);
            if (!definition) return;
            const anchor = definition.screenAnchored
                ? {rx: clamp(point.x / location.width, 0, 1), ry: clamp(point.y / location.height, 0, 1)}
                : {time: point.time, price: point.price};
            if (!this.draft) {
                const drawing = {
                    id: createId(),
                    type: definition.type,
                    version: 2,
                    points: [anchor],
                    style: this.defaultStyle(definition),
                    pane: location.pane === 'price' ? undefined : location.pane
                };
                if (definition.create) definition.create(drawing, point.env);
                this.draft = {drawing, pane: location.pane, next: 1, pointerId: event.pointerId, dragPlacement: true,
                    downX: point.x, downY: point.y};
                if (definition.placement === 'single' || definition.points === 1) {
                    this.completeDraft();
                    return;
                }
                if (definition.placement !== 'freehand') this.fillDraft(anchor);
                this.setStatus(i18n.t('draw.status.nextPoint', 2, definition.placement === 'path' ? '…' : definition.points));
                this.requestRender();
                return;
            }
            const drawing = this.draft.drawing;
            if (definition.placement === 'path') {
                drawing.points[this.draft.next] = anchor;
                this.draft.next += 1;
                drawing.points.length = this.draft.next;
                this.fillDraft(anchor);
                if (drawing.points.length >= (definition.maxPoints || 60)) this.completeDraft();
                this.requestRender();
                return;
            }
            if (definition.validatePoint && !definition.validatePoint(drawing, this.draft.next, anchor)) {
                this.setStatus(i18n.t(definition.invalidPointMessage || 'draw.status.invalidPoint'));
                return;
            }
            drawing.points[this.draft.next] = anchor;
            this.draft.next += 1;
            if (this.draft.next >= definition.points) {
                this.completeDraft();
                return;
            }
            this.fillDraft(anchor);
            this.setStatus(i18n.t('draw.status.nextPoint', this.draft.next + 1, definition.points));
            this.requestRender();
        }

        /** Points not placed yet follow the pointer so the drawing previews while it is placed. */
        fillDraft(anchor) {
            const definition = tool(this.draft.drawing.type);
            const points = this.draft.drawing.points;
            const total = definition.placement === 'path' ? this.draft.next + 1 : definition.points;
            for (let index = this.draft.next; index < total; index++) points[index] = {...anchor};
            if (definition.placement === 'path') points.length = total;
            definition.draftPoints?.(this.draft.drawing, this.draft.next);
        }

        updateDraft(point, event) {
            const draft = this.draft;
            const definition = tool(draft.drawing.type);
            const anchor = definition.screenAnchored
                ? {rx: clamp(point.x / point.env.width, 0, 1), ry: clamp(point.y / point.env.height, 0, 1)}
                : {time: point.time, price: point.price};
            if (definition.placement === 'freehand') {
                if (!draft.dragPlacement) return;
                const points = draft.drawing.points;
                const last = point.env.point(points[points.length - 1]);
                if (!last || Math.hypot(last.x - point.x, last.y - point.y) >= 3) {
                    const free = {time: point.env.time(point.x, false), price: point.price};
                    if (finite(free.time)) points.push(free);
                }
                this.requestRender();
                return;
            }
            if (event.shiftKey && !definition.screenAnchored && draft.next > 0 && definition.constrainShift !== false) {
                anchor.price = draft.drawing.points[draft.next - 1].price;
            }
            const total = definition.placement === 'path' ? draft.next + 1 : definition.points;
            for (let index = draft.next; index < total; index++) draft.drawing.points[index] = {...anchor};
            definition.draftPoints?.(draft.drawing, draft.next);
            this.requestRender();
        }

        completeDraft() {
            const draft = this.draft;
            if (!draft) return;
            const definition = tool(draft.drawing.type);
            const drawing = draft.drawing;
            if (definition.placement === 'path') {
                drawing.points.length = Math.max(2, draft.next);
                // A double-click to finish also places its two clicks; drop repeated points.
                drawing.points = drawing.points.filter((point, index, all) => index === 0
                    || point.time !== all[index - 1].time || point.price !== all[index - 1].price);
            }
            if ((definition.placement === 'freehand' || definition.placement === 'path') && drawing.points.length < 2) {
                this.cancelDraft();
                return;
            }
            definition.finish?.(drawing);
            this.draft = null;
            const before = this.snapshot();
            this.drawings.push(drawing);
            this.commit(before);
            this.selection.clear();
            this.selection.add(drawing.id);
            this.emit('selection', this.selectedDrawings());
            this.emit('created', drawing);
            this.setStatus(i18n.t('draw.status.created', i18n.t(`draw.tool.${drawing.type}`)));
            if (!this.stayInMode) {
                this.mode = 'cursor';
                this.container.classList.remove('drawing-mode-active');
                this.emit('mode', 'cursor');
            }
            if (definition.editText) this.emit('edit-text', drawing);
            this.requestRender();
        }

        cancelDraft(announce = true) {
            this.draft = null;
            this.consumePointer = false;
            if (announce) this.setStatus(i18n.t('draw.status.cancelled'));
            this.requestRender();
        }

        defaultStyle(definition) {
            const saved = (readLocal('stockwatch:drawing-defaults:v1', {}) || {})[definition.type];
            const style = {...(definition.defaults || {}), ...(saved || {})};
            if (!saved && !definition.keepDefaultColor && definition.defaults?.color === undefined) style.color = themePalette().accent;
            return style;
        }

        // ---- mutations, undo and persistence

        find(id) { return this.drawings.find(drawing => drawing.id === id) || null; }

        selectedDrawings() { return [...this.selection].map(id => this.find(id)).filter(Boolean); }

        snapshot() { return JSON.stringify(this.drawings); }

        change(mutator) {
            const before = this.snapshot();
            mutator();
            this.commit(before);
        }

        commit(before) {
            const after = this.snapshot();
            if (after === before) return;
            this.undoStack.push(before);
            if (this.undoStack.length > MAX_UNDO) this.undoStack.shift();
            this.redoStack = [];
            this.afterChange();
        }

        afterChange() {
            this.selection.forEach(id => { if (!this.find(id)) this.selection.delete(id); });
            this.schedulePersist();
            this.requestRender();
            this.emit('change', this.drawings);
        }

        undo() {
            if (!this.undoStack.length) return;
            this.redoStack.push(this.snapshot());
            this.drawings = JSON.parse(this.undoStack.pop());
            this.afterChange();
            this.emit('selection', this.selectedDrawings());
            this.setStatus(i18n.t('draw.status.undone'));
        }

        redo() {
            if (!this.redoStack.length) return;
            this.undoStack.push(this.snapshot());
            this.drawings = JSON.parse(this.redoStack.pop());
            this.afterChange();
            this.emit('selection', this.selectedDrawings());
            this.setStatus(i18n.t('draw.status.redone'));
        }

        update(id, mutator) {
            const drawing = this.find(id);
            if (!drawing) return;
            this.change(() => mutator(drawing));
        }

        deleteSelected() {
            const ids = [...this.selection].filter(id => !this.find(id)?.locked);
            if (!ids.length) {
                this.setStatus(i18n.t('draw.status.locked'));
                return;
            }
            this.change(() => { this.drawings = this.drawings.filter(drawing => !ids.includes(drawing.id)); });
            ids.forEach(id => this.selection.delete(id));
            this.emit('selection', this.selectedDrawings());
            this.setStatus(i18n.plural('draw.status.deleted', ids.length, ids.length));
        }

        remove(id) {
            this.change(() => { this.drawings = this.drawings.filter(drawing => drawing.id !== id); });
            this.selection.delete(id);
            this.emit('selection', this.selectedDrawings());
        }

        clearAll() {
            if (!this.drawings.length) return;
            this.change(() => { this.drawings = this.drawings.filter(drawing => drawing.locked); });
            this.selection.clear();
            this.emit('selection', []);
            this.setStatus(i18n.t('draw.status.cleared'));
        }

        selectAll() {
            this.drawings.forEach(drawing => { if (this.visible(drawing)) this.selection.add(drawing.id); });
            this.emit('selection', this.selectedDrawings());
            this.requestRender();
        }

        select(id, additive = false) {
            if (!additive) this.selection.clear();
            if (id) this.selection.add(id);
            this.emit('selection', this.selectedDrawings());
            this.requestRender();
        }

        bringToFront(ids = [...this.selection]) {
            this.change(() => {
                const moving = this.drawings.filter(drawing => ids.includes(drawing.id));
                this.drawings = [...this.drawings.filter(drawing => !ids.includes(drawing.id)), ...moving];
            });
        }

        sendToBack(ids = [...this.selection]) {
            this.change(() => {
                const moving = this.drawings.filter(drawing => ids.includes(drawing.id));
                this.drawings = [...moving, ...this.drawings.filter(drawing => !ids.includes(drawing.id))];
            });
        }

        move(id, offset) {
            this.change(() => {
                const index = this.drawings.findIndex(drawing => drawing.id === id);
                const target = clamp(index + offset, 0, this.drawings.length - 1);
                if (index < 0 || target === index) return;
                const [drawing] = this.drawings.splice(index, 1);
                this.drawings.splice(target, 0, drawing);
            });
        }

        copy() {
            const selected = this.selectedDrawings();
            if (!selected.length) return;
            writeLocal('stockwatch:drawing-clipboard:v1', {copiedAt: Date.now(), drawings: clone(selected)});
            this.setStatus(i18n.plural('draw.status.copied', selected.length, selected.length));
        }

        paste() {
            const clipboard = readLocal('stockwatch:drawing-clipboard:v1', null);
            if (!clipboard?.drawings?.length) return;
            const offset = clipboard.drawings.some(drawing => this.find(drawing.id)) ? 5 : 0;
            this.insertCopies(clipboard.drawings, offset);
        }

        clone() {
            this.insertCopies(this.selectedDrawings(), 3);
        }

        insertCopies(source, barOffset) {
            const copies = source.map(original => {
                const copy = clone(original);
                copy.id = createId();
                copy.locked = false;
                if (barOffset) {
                    copy.points = copy.points.map(point => finite(point.rx) ? {rx: clamp(point.rx + 0.02, 0, 1), ry: point.ry}
                        : {time: this.mapper.seconds((this.mapper.logical(point.time) ?? 0) + barOffset) ?? point.time, price: point.price});
                }
                return copy;
            });
            this.change(() => { this.drawings.push(...copies); });
            this.selection.clear();
            copies.forEach(copy => this.selection.add(copy.id));
            this.emit('selection', this.selectedDrawings());
            this.setStatus(i18n.plural('draw.status.pasted', copies.length, copies.length));
        }

        setMagnet(mode) {
            this.magnet = ['off', 'weak', 'strong'].includes(mode) ? mode : 'off';
            this.savePreferences();
            this.emit('preferences');
        }

        setStayInMode(value) {
            this.stayInMode = Boolean(value);
            this.savePreferences();
            this.emit('preferences');
        }

        setLockedAll(value) {
            this.lockedAll = Boolean(value);
            this.emit('preferences');
            this.requestRender();
        }

        setHiddenAll(value) {
            this.hiddenAll = Boolean(value);
            if (this.hiddenAll) { this.selection.clear(); this.emit('selection', []); }
            this.emit('preferences');
            this.requestRender();
        }

        savePreferences() {
            writeLocal('stockwatch:drawing-preferences:v1', {magnet: this.magnet, stayInMode: this.stayInMode});
        }

        saveDefaultStyle(type, style) {
            const defaults = readLocal('stockwatch:drawing-defaults:v1', {}) || {};
            defaults[type] = style;
            writeLocal('stockwatch:drawing-defaults:v1', defaults);
        }

        schedulePersist() {
            window.clearTimeout(this.persistTimer);
            this.persistTimer = window.setTimeout(() => this.persist(), 350);
        }

        async persist() {
            window.clearTimeout(this.persistTimer);
            const result = await this.store.persist(clone(this.drawings), this);
            if (result?.status === 'pending') this.setStatus(i18n.t('draw.status.savedLocally'));
            if (result?.status === 'unavailable') this.setStatus(i18n.t('draw.status.storageUnavailable'));
            if (result?.status === 'conflict') {
                this.setStatus(i18n.t('draw.status.conflict'));
                this.reload();
            }
            this.emit('persisted', result);
        }

        async reload() {
            try {
                const drawings = await this.store.load();
                this.drawings = (Array.isArray(drawings) ? drawings : []).filter(valid);
            } catch (error) {
                this.drawings = [];
            }
            if (this.store instanceof LocalDrawingStore === false && this.store.offline) {
                this.setStatus(i18n.t('draw.status.offline'));
            }
            this.undoStack = [];
            this.redoStack = [];
            this.selection.clear();
            this.emit('selection', []);
            this.emit('change', this.drawings);
            this.requestRender();
        }

        /** Takes the drawings another chart of the same symbol on this page saved, so both show the same set. */
        adopt(drawings) {
            if (this.destroyed) return;
            if (this.draft) this.cancelDraft(false);
            this.drawings = clone(drawings).filter(valid);
            // This chart's undo steps describe a set that no longer exists.
            this.undoStack = [];
            this.redoStack = [];
            this.selection.forEach(id => { if (!this.find(id)) this.selection.delete(id); });
            this.emit('selection', this.selectedDrawings());
            this.emit('change', this.drawings);
            this.requestRender();
        }

        /** Shows the drawings that apply to the interval now on the chart. */
        setInterval(interval) {
            this.interval = interval;
            this.selection.clear();
            this.emit('selection', []);
            this.requestRender();
        }

        /** Switches to another local drawing set (older pages key drawings by page). */
        setContext(storageKey) {
            if (!(this.store instanceof LocalDrawingStore)) return;
            if (storageKey === this.store.key) return;
            this.persist();
            this.store = new LocalDrawingStore(storageKey);
            this.reload();
        }

        scheduleRender() { this.requestRender(); }

        /** Moves the price-pane drawings onto a new main series (the page changed chart type). */
        setSeries(series) {
            if (!series || series === this.series) return;
            this.series.unsubscribeDataChanged?.(this.onDataChanged);
            this.series = series;
            this.registerPane('price', series);
            this.series.subscribeDataChanged?.(this.onDataChanged);
            this.refreshBars();
        }

        destroy() {
            if (this.destroyed) return;
            this.destroyed = true;
            this.persist();
            this.unsubscribeStore?.();
            window.cancelAnimationFrame(this.renderFrame);
            this.series.unsubscribeDataChanged?.(this.onDataChanged);
            try { this.chart.timeScale().unsubscribeVisibleLogicalRangeChange(this.onRange); } catch (error) { /* chart removed */ }
            const host = this.pointerHost;
            host.removeEventListener('pointerdown', this.onPointerDown, true);
            host.removeEventListener('mousedown', this.onBlockMouse, true);
            host.removeEventListener('touchstart', this.onBlockMouse, {capture: true});
            host.removeEventListener('touchmove', this.onBlockMouse, {capture: true});
            host.removeEventListener('mousemove', this.onBlockMouse, true);
            host.removeEventListener('dblclick', this.onDoubleClick, true);
            host.removeEventListener('contextmenu', this.onContextMenu, true);
            host.removeEventListener('pointerenter', this.onEnter);
            window.removeEventListener('pointermove', this.onPointerMove, true);
            window.removeEventListener('pointerup', this.onPointerUp, true);
            window.removeEventListener('pointercancel', this.onPointerUp, true);
            document.removeEventListener('keydown', this.onKeyDown);
            [...this.panes.keys()].forEach(key => this.unregisterPane(key));
            this.ui?.destroy();
            managers.delete(this);
            if (activeManager === this) activeManager = managers.values().next().value || null;
            this.container.classList.remove('drawing-mode-active');
        }
    }

    function valid(drawing) {
        return drawing && typeof drawing.id === 'string' && typeof drawing.type === 'string' && Array.isArray(drawing.points)
            && drawing.points.every(point => (finite(point?.time) && finite(point?.price)) || (finite(point?.rx) && finite(point?.ry)));
    }

    function shortcutKey(event) {
        return `alt+${event.shiftKey ? 'shift+' : ''}${(event.code?.startsWith('Key') ? event.code.slice(3) : event.key).toLowerCase()}`;
    }

    window.StockWatchDrawing = Object.freeze({
        registerTool,
        tool,
        tools: () => [...tools.values()],
        Draw,
        Geometry,
        TimeMapper,
        DrawingManager,
        LocalDrawingStore,
        ServerDrawingStore,
        migrateLegacyDrawing,
        toSeconds,
        withAlpha,
        themePalette,
        readLocal,
        writeLocal,
        createId,
        managers: () => [...managers],
        active: () => activeManager
    });
})();
