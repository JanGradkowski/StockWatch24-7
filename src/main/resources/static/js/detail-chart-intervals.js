(() => {
    'use strict';

    const controls = document.querySelector('[data-detail-interval-controls]');
    const alternateContainer = document.querySelector('[data-alternate-detail-chart]');
    if (!controls || !alternateContainer || typeof LightweightCharts === 'undefined') {
        return;
    }

    const buttons = [...controls.querySelectorAll('[data-detail-interval]')];
    const nativeChart = document.querySelector('[data-native-detail-chart]');
    const nativeUnavailable = document.querySelector('[data-native-chart-unavailable]');
    const nativeLegend = document.querySelector('[data-native-signal-legend]');
    const intervalNotice = document.querySelector('[data-native-interval-notice]');
    const returnNativeButton = document.querySelector('[data-return-native-interval]');
    const intervalLabel = document.querySelector('[data-detail-chart-interval-label]');
    const summary = document.querySelector('[data-detail-chart-summary]');
    const status = document.querySelector('[data-detail-chart-status]');
    const intervalError = document.querySelector('[data-detail-interval-error]');
    const intervalErrorMessage = document.querySelector('[data-detail-interval-error-message]');
    const nativeInterval = controls.dataset.nativeInterval || '1d';
    const nativeLabel = controls.dataset.nativeLabel || intervalName(nativeInterval);
    const chartKind = controls.dataset.chartKind || 'technical';
    const symbol = controls.dataset.symbol || '';
    const initialSummary = summary?.textContent || '';
    const initialStatus = status?.textContent || '';
    const candleCache = new Map();
    let selectedInterval = nativeInterval;
    let requestSequence = 0;
    let requestController = null;
    let alternateChart = null;
    let alternateSeries = null;
    let alternatePriceLine = null;
    let alternateFibonacciTool = null;
    let alternateElliottOverlay = null;

    function intervalName(interval) {
        return { '1d': 'Daily', '1wk': 'Weekly', '1mo': 'Monthly' }[interval] || 'Selected';
    }

    function chartColors(theme = document.documentElement.dataset.theme) {
        const light = theme === 'light';
        return {
            text: light ? '#526158' : '#929b91',
            grid: light ? 'rgba(31, 48, 37, 0.09)' : 'rgba(255, 255, 255, 0.045)',
            border: light ? 'rgba(27, 43, 33, 0.14)' : 'rgba(255, 255, 255, 0.08)',
            up: light ? '#078568' : '#64e8bd',
            down: light ? '#d23f59' : '#ff647c',
            signal: light ? '#477a12' : '#b7f34a',
            entry: light ? '#6557d6' : '#9a8cff'
        };
    }

    function setSelectedButton(interval, loading = false) {
        buttons.forEach(button => {
            const selected = button.dataset.detailInterval === interval;
            button.classList.toggle('active', selected);
            button.classList.toggle('loading', selected && loading);
            button.setAttribute('aria-pressed', String(selected));
            button.setAttribute('aria-busy', String(selected && loading));
        });
    }

    function setIntervalCopy(interval, native) {
        const label = intervalName(interval);
        if (intervalLabel) {
            intervalLabel.textContent = label.toLowerCase();
        }
        if (native) {
            if (summary) summary.textContent = initialSummary;
            if (status) status.textContent = initialStatus;
            return;
        }
        if (chartKind === 'activity') {
            if (summary) {
                summary.textContent = `The transaction remains marked on the completed ${label.toLowerCase()} candle that contains its reported date.`;
            }
        } else if (summary) {
            summary.textContent = controls.dataset.signalFamily === 'ELLIOTT_WAVE'
                ? `${label} candles show the validated ${label.toLowerCase()} branch of the top-down Elliott hierarchy.`
                : `${controls.dataset.signalLabel || 'This signal'} was detected on ${nativeLabel}. Price is shown on ${label.toLowerCase()} candles without signal annotations.`;
        }
        if (status) status.textContent = `Completed ${label.toLowerCase()} candles`;
    }

    function showNativeChart() {
        requestSequence += 1;
        requestController?.abort();
        requestController = null;
        selectedInterval = nativeInterval;
        setSelectedButton(nativeInterval);
        setIntervalCopy(nativeInterval, true);
        alternateContainer.hidden = true;
        if (nativeChart) nativeChart.hidden = false;
        if (nativeUnavailable) nativeUnavailable.hidden = false;
        if (nativeLegend) nativeLegend.hidden = false;
        if (intervalNotice) intervalNotice.hidden = true;
        if (intervalError) intervalError.hidden = true;
        window.setTimeout(() => window.dispatchEvent(new Event('resize')), 0);
    }

    function showAlternateState(interval) {
        selectedInterval = interval;
        if (nativeChart) nativeChart.hidden = true;
        if (nativeUnavailable) nativeUnavailable.hidden = true;
        if (intervalError) intervalError.hidden = true;
        alternateContainer.hidden = false;
        if (nativeLegend) nativeLegend.hidden = chartKind === 'technical';
        if (intervalNotice) {
            intervalNotice.hidden = chartKind !== 'technical';
            const title = intervalNotice.querySelector('[data-native-interval-title]');
            const message = intervalNotice.querySelector('[data-native-interval-message]');
            const elliott = controls.dataset.signalFamily === 'ELLIOTT_WAVE';
            if (title) {
                title.textContent = elliott
                    ? 'Top-down Elliott degree'
                    : 'Signal annotations are interval-specific';
            }
            if (message) {
                message.textContent = elliott
                    ? `${intervalName(interval)} displays its matching validated level: monthly parents, weekly children, or daily grandchildren.`
                    : `This signal was detected on ${nativeLabel}. Its pattern, required trend, and detection marker are hidden on ${intervalName(interval).toLowerCase()} candles.`;
            }
            if (returnNativeButton) {
                returnNativeButton.textContent = `Return to ${nativeLabel}`;
            }
        }
        setIntervalCopy(interval, false);
    }

    function normalizeCandles(payload) {
        const rows = Array.isArray(payload?.candles) ? payload.candles : [];
        return rows.map(candle => {
            const values = [candle.timestamp, candle.openPrice, candle.highPrice, candle.lowPrice, candle.closePrice];
            if (values.some(value => value === null || value === undefined || value === '')) return null;
            return {
                time: Number(candle.timestamp),
                open: Number(candle.openPrice),
                high: Number(candle.highPrice),
                low: Number(candle.lowPrice),
                close: Number(candle.closePrice)
            };
        }).filter(candle => candle && Object.values(candle).every(Number.isFinite) && candle.time > 0)
            .sort((first, second) => first.time - second.time)
            .filter((candle, index, all) => index === 0 || candle.time !== all[index - 1].time);
    }

    function transactionTimestamp() {
        const date = controls.dataset.transactionDate;
        const parsed = Date.parse(`${date || ''}T12:00:00Z`);
        return Number.isFinite(parsed) ? Math.floor(parsed / 1000) : NaN;
    }

    function markerCandle(candles, interval) {
        const transactionTime = transactionTimestamp();
        const nativeSignalTime = Number(nativeChart?.dataset.signalTime);
        const targetTime = Number.isFinite(transactionTime) ? transactionTime : nativeSignalTime;
        if (!Number.isFinite(targetTime) || candles.length === 0) return null;
        const targetDate = new Date(targetTime * 1000);
        if (interval === '1mo') {
            const sameMonth = candles.find(candle => {
                const date = new Date(candle.time * 1000);
                return date.getUTCFullYear() === targetDate.getUTCFullYear()
                    && date.getUTCMonth() === targetDate.getUTCMonth();
            });
            if (sameMonth) return sameMonth;
        }
        if (interval === '1wk') {
            const containingWeek = candles.find(candle => candle.time <= targetTime && targetTime < candle.time + 7 * 86_400);
            if (containingWeek) return containingWeek;
        }
        return candles.reduce((closest, candle) => {
            if (!closest) return candle;
            return Math.abs(candle.time - targetTime) < Math.abs(closest.time - targetTime) ? candle : closest;
        }, null);
    }

    function renderAlternateChart(candles, interval) {
        alternateElliottOverlay?.destroy();
        alternateElliottOverlay = null;
        alternateFibonacciTool?.destroy();
        alternateFibonacciTool = null;
        alternateChart?.remove();
        alternateChart = null;
        alternateSeries = null;
        alternatePriceLine = null;
        alternateContainer.replaceChildren();
        alternateContainer.hidden = false;

        const colors = chartColors();
        alternateChart = LightweightCharts.createChart(alternateContainer, {
            autoSize: true,
            layout: {
                textColor: colors.text,
                fontFamily: 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif',
                background: { type: 'solid', color: 'transparent' }
            },
            grid: { vertLines: { color: colors.grid }, horzLines: { color: colors.grid } },
            crosshair: { mode: 0 },
            rightPriceScale: { borderColor: colors.border },
            timeScale: { borderColor: colors.border, timeVisible: true, rightOffset: 8 },
            handleScroll: true,
            handleScale: true
        });
        alternateSeries = alternateChart.addCandlestickSeries({
            upColor: colors.up,
            downColor: colors.down,
            wickUpColor: colors.up,
            wickDownColor: colors.down,
            borderVisible: false
        });
        alternateSeries.setData(candles);

        const contextCandle = markerCandle(candles, interval);
        if (chartKind === 'activity' && contextCandle) {
            const direction = controls.dataset.direction || 'BUY';
            const actionLabel = controls.dataset.actionLabel || 'Transaction';
            const rawEntryPrice = controls.dataset.entryPrice;
            const entryPrice = rawEntryPrice && rawEntryPrice.trim() !== ''
                ? Number(rawEntryPrice)
                : NaN;
            alternateSeries.setMarkers([{
                time: contextCandle.time,
                position: direction === 'SELL' ? 'aboveBar' : 'belowBar',
                color: colors.signal,
                shape: direction === 'SELL' ? 'arrowDown' : 'arrowUp',
                text: Number.isFinite(entryPrice) ? `${actionLabel} · ${entryPrice.toFixed(2)}` : actionLabel
            }]);
            if (Number.isFinite(entryPrice)) {
                alternatePriceLine = alternateSeries.createPriceLine({
                    price: entryPrice,
                    color: colors.entry,
                    lineWidth: 2,
                    lineStyle: LightweightCharts.LineStyle.Dashed,
                    axisLabelVisible: true,
                    title: 'Entry'
                });
            }
        }

        if (contextCandle) {
            const index = candles.findIndex(candle => candle.time === contextCandle.time);
            alternateChart.timeScale().setVisibleLogicalRange({
                from: Math.max(-0.5, index - 18),
                to: index + 8
            });
        } else {
            alternateChart.timeScale().fitContent();
        }
        if (typeof StockWatchFibonacciDrawingTool !== 'undefined') {
            alternateFibonacciTool = new StockWatchFibonacciDrawingTool({
                chart: alternateChart,
                series: alternateSeries,
                container: alternateContainer,
                storageKey: `${window.location.pathname}:alternate:${interval}`
            });
        }
        if (chartKind === 'technical'
            && controls.dataset.signalFamily === 'ELLIOTT_WAVE'
            && typeof StockWatchElliottHierarchyOverlay !== 'undefined') {
            alternateElliottOverlay = new StockWatchElliottHierarchyOverlay({
                chart: alternateChart,
                container: alternateContainer,
                symbol,
                interval,
                asOfTimestamp: Number(nativeChart?.dataset.signalTime),
                color: controls.dataset.elliottSubwaveColor || '#F59E0B'
            });
            alternateElliottOverlay.showInterval(interval);
        }
    }

    async function selectInterval(interval) {
        if (!['1d', '1wk', '1mo'].includes(interval)) return;
        if (interval === nativeInterval) {
            showNativeChart();
            return;
        }
        showAlternateState(interval);
        setSelectedButton(interval, true);
        const requestId = ++requestSequence;
        requestController?.abort();
        requestController = new AbortController();
        try {
            let candles = candleCache.get(interval);
            if (!candles) {
                const response = await fetch(
                    `/api/stocks/${encodeURIComponent(symbol)}/candles?interval=${encodeURIComponent(interval)}&limit=1000`,
                    { signal: requestController.signal, headers: { Accept: 'application/json' } }
                );
                if (!response.ok) throw new Error(`Chart request failed (${response.status})`);
                const payload = await response.json();
                candles = normalizeCandles(payload);
                if (candles.length === 0) {
                    throw new Error(payload.failureMessage || `No completed ${intervalName(interval).toLowerCase()} candles are available.`);
                }
                candleCache.set(interval, candles);
            }
            if (requestId !== requestSequence || selectedInterval !== interval) return;
            renderAlternateChart(candles, interval);
            setSelectedButton(interval);
        } catch (error) {
            if (error.name === 'AbortError' || requestId !== requestSequence) return;
            alternateContainer.hidden = true;
            if (intervalError) intervalError.hidden = false;
            if (intervalErrorMessage) {
                intervalErrorMessage.textContent = error.message || 'Completed candles could not be loaded for this interval.';
            }
            if (status) status.textContent = `${intervalName(interval)} chart unavailable`;
            setSelectedButton(interval);
        } finally {
            if (requestId === requestSequence) requestController = null;
        }
    }

    buttons.forEach(button => button.addEventListener('click', () => {
        const interval = button.dataset.detailInterval;
        if (interval !== nativeInterval || selectedInterval !== nativeInterval) {
            selectInterval(interval);
        }
    }));
    returnNativeButton?.addEventListener('click', showNativeChart);
    window.addEventListener('stockwatch:themechange', event => {
        if (!alternateChart || !alternateSeries) return;
        const colors = chartColors(event.detail.theme);
        alternateChart.applyOptions({
            layout: { textColor: colors.text, background: { type: 'solid', color: 'transparent' } },
            grid: { vertLines: { color: colors.grid }, horzLines: { color: colors.grid } },
            rightPriceScale: { borderColor: colors.border },
            timeScale: { borderColor: colors.border }
        });
        alternateSeries.applyOptions({
            upColor: colors.up,
            downColor: colors.down,
            wickUpColor: colors.up,
            wickDownColor: colors.down
        });
        alternatePriceLine?.applyOptions({ color: colors.entry });
    });
})();
