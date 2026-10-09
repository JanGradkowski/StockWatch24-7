(function (root) {
  'use strict';

  class PatternRangeLoader {
    constructor(ticker, request = (...args) => fetch(...args)) {
      this.ticker = ticker;
      this.request = request;
      this.cache = new Map();
      this.pending = new Map();
    }

    ranges(interval, from, to) {
      const span = ({'1d': 128, '1wk': 448, '1mo': 992}[interval] || 128) * 86400;
      const ranges = [];
      for (let start = Math.floor(from / span) * span; start <= to; start += span) {
        ranges.push({from: Math.max(1, start), to: start + span - 1});
      }
      return ranges;
    }

    cancel(family) {
      for (const [key, entry] of this.pending) {
        if (!family || entry.family === family) {
          entry.controller.abort();
          this.pending.delete(key);
        }
      }
    }

    reset() {
      this.cancel();
      this.cache.clear();
    }

    async load(family, interval, range) {
      if (!range) return {};
      const definitions = {
        candles: ['candlestick-patterns/history/range', 'signals'],
        waves: ['elliott-waves/history/range', 'structures'],
        candidates: ['elliott-waves/candidates/range', 'candidates'],
        harmonics: ['harmonic-formations/history/range', 'formations']
      };
      const [path, field] = definitions[family];
      const chunks = this.ranges(interval, range.from, range.to);
      const keys = new Set(chunks.map(chunk => `${family}:${interval}:${chunk.from}:${chunk.to}`));
      for (const [key, entry] of this.pending) {
        if (entry.family === family && !keys.has(key)) {
          entry.controller.abort();
          this.pending.delete(key);
        }
      }
      const pages = await Promise.all(chunks.map(chunk => {
        const key = `${family}:${interval}:${chunk.from}:${chunk.to}`;
        const saved = this.cache.get(key);
        if (saved && Date.now() - saved.at < 60000) return saved.data;
        const pending = this.pending.get(key);
        if (pending) return pending.promise;
        const controller = new AbortController();
        const entry = {family, controller};
        entry.promise = this.request(`/api/stocks/${this.ticker}/${path}?interval=${encodeURIComponent(interval)}`
          + `&from=${chunk.from}&to=${chunk.to}`, {signal: controller.signal, cache: 'no-store'})
          .then(async response => {
            if (!response.ok) throw new Error('Pattern range could not be loaded');
            const data = await response.json();
            if (!controller.signal.aborted) {
              this.cache.delete(key);
              this.cache.set(key, {data, at: Date.now()});
              while (this.cache.size > 96) this.cache.delete(this.cache.keys().next().value);
            }
            return data;
          }).finally(() => {
            if (this.pending.get(key) === entry) this.pending.delete(key);
          });
        this.pending.set(key, entry);
        return entry.promise;
      }));
      const unique = new Map();
      for (const page of pages) for (const item of page[field] || []) {
        const points = item.points || [];
        const start = Number(item.signalTimestamp ?? points[0]?.timestamp);
        const end = Number(item.signalTimestamp ?? points[points.length - 1]?.timestamp);
        if (end < range.from || start > range.to) continue;
        const key = item.structureId || JSON.stringify([item.pattern, item.tradeSignal, item.direction,
          item.structureLabel, item.signalTimestamp, points]);
        unique.set(key, item);
      }
      return {...pages[0], [field]: [...unique.values()],
        completedCandlesLoaded: pages.reduce((count, page) => count + (page.completedCandlesLoaded || 0), 0)};
    }
  }

  if (typeof module !== 'undefined' && module.exports) module.exports = PatternRangeLoader;
  else root.PatternRangeLoader = PatternRangeLoader;
})(typeof window === 'undefined' ? globalThis : window);
