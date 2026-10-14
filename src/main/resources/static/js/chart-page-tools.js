(() => {
    'use strict';

    // The stock page's drawing toolset on the other candlestick charts (signal and history details, the
    // technical outlook and its change reports, demo trades): the tool rail in its own column beside the
    // chart, drawings stored with the account per symbol (the same set the stock page shows), alerts on
    // drawings, demo trades from long and short positions, and bar volume for the volume tools.

    const D = window.StockWatchDrawing;
    const i18n = window.StockWatchI18n;
    const INTERVALS = {'1d': '1d', '1wk': '1wk', '1mo': '1mo', DAILY: '1d', WEEKLY: '1wk', MONTHLY: '1mo'};
    const API_INTERVALS = {'1d': 'DAILY', '1wk': 'WEEKLY', '1mo': 'MONTHLY'};
    const VOLUME_PAGES = 5;

    let managers = [];
    let current = null;
    let alertsSymbol = null;
    let demo = null;

    function normalizeInterval(value) {
        const text = String(value || '').trim();
        return INTERVALS[text] || INTERVALS[text.toUpperCase()] || INTERVALS[text.toLowerCase()] || '1d';
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return token && header ? {[header]: token} : {};
    }

    /** The chart the user worked with last, or another one still on the page. */
    function live() {
        const active = D.active();
        if (active && !active.destroyed && managers.includes(active)) current = active;
        if (current && !current.destroyed) return current;
        current = [...managers].reverse().find(manager => !manager.destroyed) || null;
        return current;
    }

    // ---- volume: the pages' candles carry none, so the volume tools load it when first needed

    const dayKey = seconds => new Date(seconds * 1000).toISOString().slice(0, 10);

    function needsVolume(type) {
        return D.tool(type)?.group === 'volume' || type === 'dateRange' || type === 'datePriceRange';
    }

    function seriesSeconds(series) {
        let data;
        try { data = series.data() || []; } catch (error) { data = []; }
        return data.map(item => D.toSeconds(item.time)).filter(Number.isFinite).sort((a, b) => a - b);
    }

    function withVolume(series, volumes) {
        if (!volumes.size) return null;
        let data;
        try { data = series.data() || []; } catch (error) { return null; }
        return data.map(item => ({time: item.time, open: item.open, high: item.high, low: item.low, close: item.close ?? item.value,
            volume: volumes.get(dayKey(D.toSeconds(item.time)))}));
    }

    /** Pages of stored candles back from the chart's last candle until its first one is covered. */
    async function fetchVolume(state, symbol, interval, series) {
        const times = seriesSeconds(series);
        if (!times.length || (state.from !== null && times[0] >= state.from)) return false;
        let before = state.from ?? times[times.length - 1] + 1;
        let limit = Math.min(1000, times.filter(time => time < before).length + 2);
        try {
            for (let page = 0; page < VOLUME_PAGES; page++) {
                const response = await fetch(`/api/stocks/${encodeURIComponent(symbol)}/candles?interval=${encodeURIComponent(interval)}`
                    + `&before=${before}&limit=${limit}`, {credentials: 'same-origin', headers: {Accept: 'application/json'}});
                if (!response.ok) break;
                const rows = (await response.json())?.candles || [];
                rows.forEach(row => {
                    const volume = Number(row.volume);
                    if (Number.isFinite(Number(row.timestamp)) && Number.isFinite(volume)) state.volumes.set(dayKey(Number(row.timestamp)), volume);
                });
                if (!rows.length) break;
                before = Math.min(...rows.map(row => Number(row.timestamp)));
                if (before <= times[0]) break;
                limit = 1000;
            }
        } catch (error) {
            // The tools draw without volume; the next change tries again only for candles not tried yet.
        }
        state.from = Math.min(state.from ?? Infinity, times[0]);
        return true;
    }

    function followVolume(manager, symbol, series) {
        const state = {volumes: new Map(), from: null, loading: null, wanted: false};
        const load = () => {
            state.wanted = true;
            if (state.loading) return;
            state.loading = fetchVolume(state, symbol, manager.interval, series).then(loaded => {
                state.loading = null;
                if (loaded && !manager.destroyed) manager.refreshBars();
            });
        };
        manager.on('mode', mode => { if (needsVolume(mode)) load(); });
        manager.on('change', drawings => { if (drawings.some(drawing => needsVolume(drawing.type))) load(); });
        return () => {
            // Older candles loaded by scrolling back need their volume too.
            if (state.wanted && !state.loading) {
                const first = seriesSeconds(series)[0];
                if (Number.isFinite(first) && state.from !== null && first < state.from) window.setTimeout(load, 0);
            }
            return withVolume(series, state.volumes);
        };
    }

    // ---- demo trades from long and short positions (pages with the shared dialog)

    function demoDialog() {
        return document.getElementById('chartDemoTradeDialog');
    }

    function setDemoSizeMode(mode) {
        demo.sizeMode = mode;
        document.querySelectorAll('[data-chart-demo-size-mode]').forEach(option => {
            option.classList.toggle('active', option.dataset.chartDemoSizeMode === mode);
        });
        document.getElementById('chartDemoTradeSizeField').hidden = mode === 'none';
        document.getElementById('chartDemoTradeSizeLabel').textContent = i18n.t(mode === 'quantity' ? 'outlook.numberOfShares' : 'outlook.virtualAmount');
        document.getElementById('chartDemoTradeSizeHelp').textContent = i18n.t(mode === 'quantity' ? 'outlook.amountFromPrice' : 'outlook.quantityFromPrice');
    }

    function wireDemoDialog(dialog) {
        const status = document.getElementById('chartDemoTradeStatus');
        const sizeInput = document.getElementById('chartDemoTradeSizeInput');
        const confirm = document.getElementById('confirmChartDemoTrade');
        document.getElementById('cancelChartDemoTrade').addEventListener('click', () => dialog.close());
        document.querySelectorAll('[data-chart-demo-size-mode]').forEach(option => {
            option.addEventListener('click', () => setDemoSizeMode(option.dataset.chartDemoSizeMode));
        });
        confirm.addEventListener('click', async () => {
            const size = sizeInput.value;
            if (demo.sizeMode !== 'none' && (!size || Number(size) <= 0)) {
                status.textContent = i18n.t('outlook.positiveSize');
                return;
            }
            confirm.disabled = true;
            confirm.textContent = i18n.t('outlook.capturingQuote');
            try {
                const response = await fetch(`/api/virtual-trades/${encodeURIComponent(demo.symbol)}`, {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json', ...csrfHeaders()},
                    body: JSON.stringify({
                        side: demo.side,
                        interval: demo.interval,
                        quantity: demo.sizeMode === 'quantity' ? size : null,
                        notionalValue: demo.sizeMode === 'notional' ? size : null,
                        clientRequestId: demo.requestId
                    })
                });
                if (!response.ok) {
                    const error = await response.json().catch(() => ({}));
                    throw new Error(error.message || error.detail || error.error || i18n.t('outlook.createFailed'));
                }
                const trade = await response.json();
                window.location.assign(trade.detailUrl);
            } catch (error) {
                status.textContent = error.message || i18n.t('outlook.createFailed');
                confirm.disabled = false;
                confirm.textContent = i18n.t('outlook.createDemoTrade');
            }
        });
    }

    /** As on the stock page: a demo trade at the current quote; the drawn entry, target and stop stay on the chart. */
    function openDemoTrade(symbol, side, interval) {
        const dialog = demoDialog();
        if (!dialog) return;
        if (!demo) {
            demo = {};
            wireDemoDialog(dialog);
        }
        Object.assign(demo, {symbol, side: side === 'SELL' ? 'SELL' : 'BUY', interval, requestId: crypto.randomUUID()});
        setDemoSizeMode('none');
        document.getElementById('chartDemoTradeSizeInput').value = '';
        document.getElementById('chartDemoTradeTitle').textContent = i18n.t(demo.side === 'BUY' ? 'outlook.demoBuyTitle' : 'outlook.demoSellTitle', symbol);
        document.getElementById('chartDemoTradeExplanation').textContent = i18n.t(demo.side === 'BUY' ? 'outlook.demoBuyText' : 'outlook.demoSellText');
        document.getElementById('chartDemoTradeStatus').textContent =
            `${i18n.t(`sw.demo.analysisSaved.${API_INTERVALS[interval] || 'DAILY'}`)} ${i18n.t('sw.demo.fromPosition')}`;
        const confirm = document.getElementById('confirmChartDemoTrade');
        confirm.disabled = false;
        confirm.textContent = i18n.t('outlook.createDemoTrade');
        dialog.showModal();
    }

    // ---- attaching

    /**
     * Opens the drawing toolset on a chart. Its rail goes into the {@code [data-drawing-rail-host]} element
     * beside the chart (and folds into "More tools" when the chart is shorter than it).
     *
     * @param options.chart, options.series, options.container the chart, its candles and its element
     * @param options.symbol drawings and alerts are kept with the account under this symbol
     * @param options.interval '1d', '1wk' or '1mo' (DAILY, WEEKLY and MONTHLY are accepted)
     * @param options.bars optional function returning the page's candles with volume
     * @param options.legacyKey the browser storage key this chart kept its drawings under, imported once
     * @param options.onDemoTrade optional function(side) for a page with its own demo trade dialog
     * @returns the DrawingManager, or null without the drawing scripts
     */
    function attach(options = {}) {
        if (!D || !options.chart || !options.series || !options.container) return null;
        const symbol = String(options.symbol || '').trim();
        const interval = normalizeInterval(options.interval);
        const railHost = options.container.parentElement?.querySelector(':scope > [data-drawing-rail-host]') || null;
        const alerts = Boolean(symbol && window.StockWatchChartAlerts);
        let volumeBars = null;
        const manager = new D.DrawingManager({
            chart: options.chart,
            series: options.series,
            container: options.container,
            store: symbol ? D.ServerDrawingStore.shared(symbol, options.legacyKey ? [{key: options.legacyKey, interval}] : []) : undefined,
            storageKey: options.legacyKey,
            interval,
            bars: options.bars || (symbol ? () => volumeBars?.() ?? null : undefined),
            features: {alerts, demoTrade: Boolean(symbol && (options.onDemoTrade || demoDialog()))},
            toolbarHost: railHost || undefined,
            foldRail: Boolean(railHost)
        });
        if (!options.bars && symbol) {
            volumeBars = followVolume(manager, symbol, options.series);
        }
        // A chart the page sized once (not autoSize) still has the width it had before the rail took its column.
        if (railHost && !options.chart.options?.().autoSize && options.container.clientWidth) {
            options.chart.applyOptions({width: options.container.clientWidth});
        }
        managers = managers.filter(item => !item.destroyed);
        managers.push(manager);
        current = manager;

        if (alerts) {
            if (alertsSymbol !== symbol) {
                alertsSymbol = symbol;
                void window.StockWatchChartAlerts.attach({
                    symbol,
                    interval: () => live()?.interval || interval,
                    get drawings() { return live(); },
                    onStatus: message => live()?.setStatus(message)
                });
            } else {
                window.StockWatchChartAlerts.follow(manager);
            }
            manager.on('alert-request', drawing => {
                current = manager;
                window.StockWatchChartAlerts.openForDrawing(drawing);
            });
        }
        manager.on('demo-trade', ({side}) => {
            current = manager;
            if (options.onDemoTrade) options.onDemoTrade(side);
            else openDemoTrade(symbol, side, manager.interval || interval);
        });
        return manager;
    }

    window.StockWatchChartTools = Object.freeze({attach, normalizeInterval});
})();
