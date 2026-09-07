  const encodedTicker = encodeURIComponent(ticker);
  const csrfToken = document.querySelector('meta[name="_csrf"]').content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
  const stockPageLoader = document.getElementById('stockPageLoader');
  const stockLoaderStatus = document.getElementById('stockLoaderStatus');
  const stockWorkspace = document.querySelector('.stock-page');

  function tradeSignalDisplayLabel(value, lowercase = false) {
    const normalized = String(value || '').toUpperCase();
    if (normalized === 'SELL') return lowercase ? 'sell/short' : 'SELL/SHORT';
    if (normalized === 'BUY') return lowercase ? 'buy' : 'BUY';
    return String(value || '');
  }

  document.querySelectorAll('[data-alert-signal="SELL"]').forEach(input => {
    const label = input.closest('label');
    const copy = label?.querySelector('.alert-eye-label');
    if (copy && copy.textContent.trim().toLowerCase() === 'sell') copy.textContent = 'Sell/Short';
    const ariaLabel = input.getAttribute('aria-label');
    if (ariaLabel) input.setAttribute('aria-label', ariaLabel.replace(/sell signals/gi, 'sell/short signals'));
  });
  let stockLoaderFinished = false;
  const stockLoaderSafetyTimer = window.setTimeout(() => {
    finishStockPageLoading('Workspace opened. Some market data may still be arriving.');
  }, 25000);

  function updateStockLoadingStatus(message) {
    if (!stockLoaderFinished && stockLoaderStatus) {
      stockLoaderStatus.textContent = message;
    }
  }

  function finishStockPageLoading(message) {
    if (stockLoaderFinished) return;
    stockLoaderFinished = true;
    window.clearTimeout(stockLoaderSafetyTimer);
    if (stockLoaderStatus) {
      stockLoaderStatus.textContent = message;
    }
    if (stockWorkspace) {
      stockWorkspace.setAttribute('aria-busy', 'false');
    }
    document.body.classList.remove('stock-is-loading');
    if (stockPageLoader) {
      stockPageLoader.classList.add('is-complete');
      window.setTimeout(() => stockPageLoader.remove(), 700);
    }
  }

  function secureJsonHeaders() {
    return { 'Content-Type': 'application/json', [csrfHeader]: csrfToken };
  }

  let priceChart, volumeChart, rsiChart;
  let fibonacciDrawingTool = null;
  const INITIAL_CANDLE_LIMIT = 1000;
  const INITIAL_VISIBLE_CANDLES = 150;
  const HISTORY_PAGE_LIMIT = 500;
  const HISTORY_PREFETCH_THRESHOLD = 120;

  let lineSeries, candleSeries, volumeSeries, elliottConfirmationSeries, rsiSeries;
  let rsiGuideLines = [];
  let historicalElliottSeries = [];
  let historicalElliottGroups = new Map();
  let harmonicFormationSeries = [];
  let harmonicFormationOverlays = [];
  let harmonicHitTargetFrame = null;
  let harmonicCardHovered = false;
  let harmonicCardPinned = false;
  let harmonicCardHideTimer = null;
  let elliottHierarchyOverlay = null;
  let elliottSignalCards = new Map();
  let activeElliottSegment = null;
  let elliottCardPinned = false;
  let elliottCardHovered = false;
  let elliottCardHideTimer = null;
  let elliottHitTargetFrame = null;
  let historicalCandlestickOverlayEnabled = false;
  let historicalCandlestickOverlayHistory = null;
  let historicalCandlestickOverlaySignals = [];
  let historicalCandlestickOverlayRequestSequence = 0;
  let historicalCandlestickHitTargetFrame = null;
  let historicalCandlestickTrendSeries = null;
  let historicalCandlestickFormationSeries = null;
  let activeHistoricalCandlestickSignals = [];
  let activeHistoricalCandlestickSignalIndex = 0;
  let historicalCandlestickCardPinned = false;
  let historicalCandlestickCardHovered = false;
  let historicalCandlestickCardHideTimer = null;
  let currentMode = 'candle';
  let currentInterval = '1d';
  let pendingStockDemoTradeSide = 'BUY';
  let stockDemoTradeSizeMode = 'none';
  let pendingStockDemoTradeRequestId = null;
  let pendingStockDemoTradeInterval = '1d';
  let elliottOverlaysEnabled = true;
  let elliottSubwavesEnabled = false;
  let harmonicOverlaysEnabled = false;
  let chartLoadController = null;
  let chartRequestSequence = 0;
  let elliottRequestSequence = 0;
  let harmonicRequestSequence = 0;
  let historyRetryTimer = null;
  let historyRetryAttempts = 0;
  let anchoredVolumeProfileEnabled = false;
  let anchoredVolumeProfile = null;
  let anchoredVolumeProfileAnchorTimestamp = null;
  let anchoredVolumeProfileRequestSequence = 0;
  let anchoredVolumeProfileRenderFrame = null;
  const ANCHORED_PROFILE_RIGHT_OFFSET_BARS = 30;
  let rsiOverlayEnabled = false;
  let rsiPeriod = 14;
  let rsiOverboughtBoundary = 70;
  let rsiOversoldBoundary = 30;
  let volumeChartEnabled = true;
  let chartResizeObserver = null;
  let generalChartResizeFrame = null;
  const activeChartIndicators = new Map();
  const chartIndicatorPriceSeries = new Map();
  const chartIndicatorPanels = new Map();
  let pendingChartIndicatorType = null;
  let chartIndicatorRequestSequence = 0;
  let chartIndicatorRefreshTimer = null;

  const CHART_INDICATOR_COLORS = [
    '#b7f34a', '#64e8bd', '#9a8cff', '#ffb55f', '#55b8ff', '#ff647c',
    '#f6df69', '#46d3c4', '#d993ff', '#ff8f70', '#7ea6ff', '#c2e970'
  ];

  const CHART_INDICATOR_SPECS = {
    EMA: indicatorSpec('EMA', 'Exponential moving average on the price chart.', [periodField(20)]),
    SMA: indicatorSpec('SMA', 'Simple moving average on the price chart.', [periodField(200)]),
    BOLLINGER: indicatorSpec('Bollinger Bands', 'Population-standard-deviation envelope used by Automated Technical Analysis.', [periodField(20), numberField('deviation', 'Deviation multiplier', 2, 0.1, 10, 0.1)]),
    VWAP: indicatorSpec('Rolling VWAP', 'Rolling typical-price volume-weighted average.', [periodField(20)]),
    SUPPORT_RESISTANCE: indicatorSpec('Support / resistance', 'Rolling lowest low and highest high.', [periodField(20)]),
    MACD: indicatorSpec('MACD', 'MACD line, signal line, and histogram in a synchronized panel.', [periodField(12, 'fast', 'Fast period'), periodField(26, 'slow', 'Slow period'), periodField(9, 'signal', 'Signal period')]),
    CCI: indicatorSpec('CCI', 'Commodity Channel Index with ±100 guides.', [periodField(20)]),
    ATR: indicatorSpec('ATR', 'Average True Range volatility panel.', [periodField(14)]),
    RELATIVE_VOLUME: indicatorSpec('Relative volume', 'Signed candle volume divided by its rolling average.', [periodField(20)]),
    ADX_DMI: indicatorSpec('ADX / DMI', 'ADX trend strength together with +DI and -DI.', [periodField(14)]),
    STOCHASTIC: indicatorSpec('Stochastic oscillator', '%K and %D with 20/80 guides.', [periodField(14)]),
    STOCHASTIC_RSI: indicatorSpec('Stochastic RSI', 'Stochastic RSI with 20/80 guides.', [periodField(14)]),
    OBV: indicatorSpec('On-balance volume', 'Cumulative on-balance volume.', []),
    MFI: indicatorSpec('Money Flow Index', 'Price-and-volume oscillator with 20/80 guides.', [periodField(14)]),
    DONCHIAN: indicatorSpec('Donchian Channel', 'Rolling high/low channel on the price chart.', [periodField(20)]),
    KELTNER: indicatorSpec('Keltner Channel', 'EMA and ATR volatility channel on the price chart.', [periodField(20), periodField(14, 'atrPeriod', 'ATR period'), numberField('multiplier', 'ATR multiplier', 2, 0.1, 10, 0.1)]),
    TREND: indicatorSpec('TA4J trend state', 'Independent uptrend/downtrend state in a synchronized panel.', [periodField(20)]),
    VOLUME_PROFILE: indicatorSpec('Rolling volume profile', 'Rolling OHLCV approximation of VAL, POC, and VAH.', [periodField(60), numberField('valueArea', 'Value area fraction', 0.7, 0.5, 0.95, 0.01)]),
    KDE_VOLUME_PROFILE: indicatorSpec('KDE volume-profile mode', 'TA4J causal kernel-density volume-profile mode.', [periodField(60)])
  };

  function indicatorSpec(label, description, fields) {
    return { label, description, fields };
  }

  function periodField(value, key = 'period', label = 'Period') {
    return numberField(key, label, value, 2, 500, 1, 'Whole number from 2 to 500.');
  }

  function numberField(key, label, value, min, max, step, help = '') {
    return { key, label, value, min, max, step, help };
  }

  let isFetching = false;
  let isLoadingOlder = false;
  let hasMoreHistory = true;
  let oldestTimestamp = null;
  let latestCandle = null;
  let stockCurrency = 'USD';

  let globalCandleData = [];
  let globalLineData = [];
  let globalVolumeData = [];
  let seenDates = new Set();

  const persistedAlertState = new Map();
  let alertStateLoaded = false;
  let alertSaveInProgress = false;
  let alertSavePromise = null;
  let outlookFollowStateLoaded = false;
  let outlookFollowSaveInProgress = false;
  let technicalFollowAllBusy = false;
  let trackedAlertSummary = '';
  let pendingNavigationAction = null;
  let pageExitAllowed = false;
  let alertBeforeUnloadRegistered = false;
  let congressionalActivityEligible = false;
  let congressionalFollowing = false;
  let congressionalActivityBusy = false;
  let insiderActivityEligible = false;
  let insiderFollowing = false;
  let insiderActivityBusy = false;
  let historicalCandlestickRequestController = null;
  let selectedHistoricalCandlestickInterval = null;
  const HISTORICAL_CANDLESTICK_LOOKBACK_MIN = 1;
  const HISTORICAL_CANDLESTICK_LOOKBACK_MAX = 750;
  const HISTORICAL_CANDLESTICK_DEFAULT_LOOKBACKS = {
    '1d': 60,
    '1wk': 104,
    '1mo': 120
  };

  function chartThemeOptions(theme = document.documentElement.dataset.theme) {
    const light = theme === 'light';
    return {
      layout: {
        textColor: light ? '#526158' : '#858c82',
        fontFamily: 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif',
        background: { type: 'solid', color: 'transparent' }
      },
      grid: {
        vertLines: { color: light ? 'rgba(31, 48, 37, 0.09)' : 'rgba(255, 255, 255, 0.035)' },
        horzLines: { color: light ? 'rgba(31, 48, 37, 0.09)' : 'rgba(255, 255, 255, 0.035)' }
      },
      crosshair: { mode: 0 },
      rightPriceScale: {
        borderColor: light ? 'rgba(27, 43, 33, 0.14)' : 'rgba(255, 255, 255, 0.07)'
      },
      timeScale: {
        borderColor: light ? 'rgba(27, 43, 33, 0.14)' : 'rgba(255, 255, 255, 0.07)'
      }
    };
  }

  function chartSeriesThemeOptions(theme = document.documentElement.dataset.theme) {
    const light = theme === 'light';
    return {
      candles: {
        upColor: light ? '#078568' : '#64e8bd',
        downColor: light ? '#d23f59' : '#ff647c',
        wickUpColor: light ? '#078568' : '#64e8bd',
        wickDownColor: light ? '#d23f59' : '#ff647c',
        borderVisible: false
      },
      line: {
        color: light ? '#477a12' : '#b7f34a',
        lineWidth: 2,
        crosshairMarkerVisible: true
      },
      volumeUp: light ? 'rgba(7, 133, 104, 0.64)' : 'rgba(100, 232, 189, 0.72)',
      volumeDown: light ? 'rgba(210, 63, 89, 0.58)' : 'rgba(255, 100, 124, 0.72)'
    };
  }

  function themedVolumeData(theme = document.documentElement.dataset.theme) {
    const seriesTheme = chartSeriesThemeOptions(theme);
    return globalVolumeData.map(item => ({
      time: item.time,
      value: item.value,
      color: item.isUp ? seriesTheme.volumeUp : seriesTheme.volumeDown
    }));
  }

  function rsiSeriesThemeOptions(theme = document.documentElement.dataset.theme) {
    const light = theme === 'light';
    return {
      line: light ? '#5b50c9' : '#9a8cff',
      overbought: light ? '#c93452' : '#ff647c',
      overboughtGuide: light ? 'rgba(201, 52, 82, 0.58)' : 'rgba(255, 100, 124, 0.58)',
      midpoint: light ? 'rgba(82, 97, 88, 0.42)' : 'rgba(180, 189, 180, 0.34)',
      oversold: light ? '#00785d' : '#64e8bd',
      oversoldGuide: light ? 'rgba(0, 120, 93, 0.58)' : 'rgba(100, 232, 189, 0.58)'
    };
  }

  function themedRsiData(points,
                         overboughtBoundary = 70,
                         oversoldBoundary = 30,
                         theme = document.documentElement.dataset.theme) {
    const colors = rsiSeriesThemeOptions(theme);
    return points.map(point => {
      if (!Number.isFinite(point.value)) return { ...point };
      const color = point.value > overboughtBoundary
              ? colors.overbought
              : (point.value < oversoldBoundary ? colors.oversold : colors.line);
      return { ...point, color };
    });
  }

  function formatRsiBoundary(value) {
    return Number.isInteger(value)
            ? String(value)
            : value.toFixed(2).replace(/\.?0+$/, '');
  }

  const chartOptions = chartThemeOptions();

  async function initCharts() {
    let completionMessage = 'Workspace ready';
    try {
      updateStockLoadingStatus('Building the price and volume charts...');
      priceChart = LightweightCharts.createChart(document.getElementById('priceChartContainer'), chartOptions);
      volumeChart = LightweightCharts.createChart(document.getElementById('volumeChartContainer'), {
        ...chartOptions,
        timeScale: { visible: false }
      });

      priceChart.timeScale().subscribeVisibleLogicalRangeChange(range => {
        maybeLoadOlderForVisibleRange(range);
        if (range) {
          volumeChart.timeScale().setVisibleLogicalRange(range);
          if (rsiChart && rsiOverlayEnabled) {
            rsiChart.timeScale().setVisibleLogicalRange(range);
          }
          chartIndicatorPanels.forEach(panel => {
            panel.chart.timeScale().setVisibleLogicalRange(range);
          });
        }
        scheduleAnchoredVolumeProfileRender();
        scheduleElliottHitTargetPositioning();
        scheduleHistoricalCandlestickHitTargetPositioning();
        scheduleHarmonicHitTargetPositioning();
      });
      priceChart.subscribeClick(handleAnchoredVolumeProfileChartClick);
      priceChart.subscribeClick(handleElliottWaveChartClick);

      const elliottCard = document.getElementById('elliottWaveHoverCard');
      elliottCard.addEventListener('mouseenter', () => {
        elliottCardHovered = true;
        clearTimeout(elliottCardHideTimer);
      });
      elliottCard.addEventListener('mouseleave', () => {
        elliottCardHovered = false;
        if (!elliottCardPinned) scheduleElliottCardHide();
      });
      document.getElementById('elliottWaveCardClose').addEventListener('click', hideElliottWaveCard);
      const harmonicCard = document.getElementById('harmonicFormationHoverCard');
      harmonicCard.addEventListener('mouseenter', () => {
        harmonicCardHovered = true;
        clearTimeout(harmonicCardHideTimer);
      });
      harmonicCard.addEventListener('mouseleave', () => {
        harmonicCardHovered = false;
        if (!harmonicCardPinned) scheduleHarmonicFormationCardHide();
      });
      document.getElementById('harmonicFormationCardClose')
              .addEventListener('click', hideHarmonicFormationCard);
      document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && activeElliottSegment) hideElliottWaveCard();
        if (event.key === 'Escape' && !harmonicCard.hidden) hideHarmonicFormationCard();
      });

      const seriesTheme = chartSeriesThemeOptions();
      candleSeries = priceChart.addCandlestickSeries(seriesTheme.candles);
      lineSeries = priceChart.addLineSeries(seriesTheme.line);
      if (typeof StockWatchElliottHierarchyOverlay !== 'undefined') {
        elliottHierarchyOverlay = new StockWatchElliottHierarchyOverlay({
          chart: priceChart,
          container: document.getElementById('priceChartStage'),
          symbol: ticker,
          interval: currentInterval,
          color: ELLIOTT_SUBWAVE_COLOR,
          renderTime: timestampToChartDate,
          statusElement: document.getElementById('elliottOverlayStatus'),
          onWaveClick: showElliottHierarchyWaveCard
        });
      }
      elliottConfirmationSeries = priceChart.addLineSeries({
        // This series exists only to anchor confirmation markers. Lightweight
        // Charts 4 ignores lineVisible on a line series, so use a fully
        // transparent color to prevent it connecting sparse confirmations.
        color: 'rgba(0, 0, 0, 0)',
        crosshairMarkerVisible: false,
        lastValueVisible: false,
        priceLineVisible: false
      });
      volumeSeries = volumeChart.addHistogramSeries({ priceFormat: { type: 'volume' } });
      if (typeof StockWatchFibonacciDrawingTool !== 'undefined') {
        fibonacciDrawingTool = new StockWatchFibonacciDrawingTool({
          chart: priceChart,
          series: candleSeries,
          container: document.getElementById('priceChartStage'),
          storageKey: `stock:${ticker}:${currentInterval}`
        });
      }

      // Load currency metadata first
      updateStockLoadingStatus('Resolving instrument and market details...');
      try {
        const micQuery = selectedMic ? `?micCode=${encodeURIComponent(selectedMic)}` : '';
        const metaRes = await fetch(`/api/stocks/${encodedTicker}/meta${micQuery}`);
        if (metaRes.ok) {
          const metaJson = await metaRes.json();
          const instrumentType = String(metaJson.instrumentType || 'EQUITY').toUpperCase();
          const instrumentTypeDisplay = document.getElementById('instrumentTypeDisplay');
          instrumentTypeDisplay.dataset.instrumentType = instrumentType;
          instrumentTypeDisplay.textContent = instrumentType === 'INDEX'
                  ? 'Index'
                  : (instrumentType === 'ETF' ? 'ETF' : 'Stock');
          updateTickerAlertsEligibility(instrumentType);
          await Promise.all([
            configureCongressionalActivity(instrumentType),
            configureInsiderActivity(instrumentType)
          ]);
          if (metaJson.currency) {
            stockCurrency = metaJson.currency;
            priceChart.applyOptions({
              localization: { priceFormatter: price => `${price.toFixed(2)} ${stockCurrency}` }
            });
          }
        }
      } catch (e) { console.warn("Currency fallback to USD"); }

      updateStockLoadingStatus('Loading completed daily candles and technical context...');
      await loadChartData('1d');
      updateStockLoadingStatus('Loading monitoring preferences...');
      await Promise.all([loadAlertState(), loadOutlookFollowState()]);

      setChartMode('candle');
      updateStockLoadingStatus('Finalizing overlays and historical results...');
      await reopenHistoricalCandlestickResultsFromUrl();

    } catch (err) {
      console.error("Initialization error:", err);
      completionMessage = 'Workspace opened with limited data';
    } finally {
      finishStockPageLoading(completionMessage);
    }
  }

  async function loadChartData(interval) {
    const requestId = ++chartRequestSequence;
    if (chartLoadController) chartLoadController.abort();
    chartLoadController = new AbortController();
    isFetching = true;

    globalCandleData = [];
    globalLineData = [];
    globalVolumeData = [];
    seenDates.clear();
    hasMoreHistory = true;
    oldestTimestamp = null;
    clearTimeout(historyRetryTimer);
    historyRetryTimer = null;
    historyRetryAttempts = 0;
    elliottRequestSequence++;
    clearElliottOverlays();
    updateElliottOverlayToggle();
    harmonicRequestSequence++;
    clearHarmonicOverlays();
    updateHarmonicOverlayToggle();
    historicalCandlestickOverlayRequestSequence++;
    clearHistoricalCandlestickOverlayVisuals();
    chartIndicatorRequestSequence++;
    clearTimeout(chartIndicatorRefreshTimer);
    activeChartIndicators.forEach((config, id) => removeChartIndicatorVisuals(id));
    anchoredVolumeProfileRequestSequence++;
    anchoredVolumeProfile = null;
    anchoredVolumeProfileAnchorTimestamp = null;
    clearAnchoredVolumeProfileCanvas();
    updateAnchoredVolumeProfileToggle();

    try {
      document.querySelector('.ticker-name').nextElementSibling.innerText =
              `Loading ${historicalCandlestickIntervalLabel(interval).toLowerCase()} data...`;

      const response = await fetch(`/api/stocks/${encodedTicker}/candles?interval=${encodeURIComponent(interval)}&limit=${INITIAL_CANDLE_LIMIT}`, {
        signal: chartLoadController.signal
      });
      if (!response.ok) throw new Error("HTTP error");
      const page = normaliseCandlePage(await response.json());
      if (requestId !== chartRequestSequence || currentInterval !== interval) return;
      const data = page.candles;

      if (!data || data.length === 0) {
        document.querySelector('.ticker-name').nextElementSibling.innerText = page.failureMessage || 'No historical data found.';
        hasMoreHistory = false;
        return;
      }

      oldestTimestamp = page.nextCursor ?? data[0].timestamp;
      hasMoreHistory = pageMayHaveMore(page, data, INITIAL_CANDLE_LIMIT);
      processAndSetData(data, false);
      showNewestCandles(INITIAL_VISIBLE_CANDLES);
      updatePriceHeaderFromLatestCandle();

      const intervalNames = {'1d': 'Daily Interval', '1wk': 'Weekly Interval', '1mo': 'Monthly Interval'};
      document.querySelector('.ticker-name').nextElementSibling.innerText = intervalNames[interval];

      // Candle pagination must become available as soon as the candle series is
      // rendered. Elliott analysis is a separate request and must not suppress
      // left-scroll events while it is still running.
      isFetching = false;
      chartLoadController = null;
      scheduleViewportHistoryFill();

      await Promise.all([
        refreshHistoricalElliottOverlays(interval),
        refreshHistoricalCandlestickOverlay(interval),
        refreshHarmonicOverlays(interval)
      ]);
      if (anchoredVolumeProfileEnabled && globalCandleData.length > 0) {
        await requestAnchoredVolumeProfile(
                globalCandleData[globalCandleData.length - 1].timestamp);
      }

    } catch (err) {
      if (err.name === 'AbortError') return;
      console.error("Data load error:", err);
      document.querySelector('.ticker-name').nextElementSibling.innerText = `Failed to load chart data`;
    } finally {
      if (requestId === chartRequestSequence) {
        isFetching = false;
        chartLoadController = null;
      }
    }
  }

  async function loadOlderData() {
    if (isLoadingOlder || !oldestTimestamp || !hasMoreHistory) return;
    isLoadingOlder = true;
    const requestedInterval = currentInterval;
    const requestedCursor = oldestTimestamp;
    let pageAdvanced = false;

    try {
      const response = await fetch(`/api/stocks/${encodedTicker}/candles?interval=${encodeURIComponent(requestedInterval)}&before=${encodeURIComponent(requestedCursor)}&limit=${HISTORY_PAGE_LIMIT}`);
      if (!response.ok) throw new Error("HTTP error");
      const page = normaliseCandlePage(await response.json());
      if (currentInterval !== requestedInterval || oldestTimestamp !== requestedCursor) return;
      const data = page.candles;

      if (data && data.length > 0) {
        clearTimeout(historyRetryTimer);
        historyRetryTimer = null;
        historyRetryAttempts = 0;
        const nextCursor = page.nextCursor ?? data[0].timestamp;
        if (!Number.isFinite(nextCursor) || nextCursor >= requestedCursor) {
          throw new Error("Historical candle cursor did not move backwards");
        }
        oldestTimestamp = nextCursor;
        pageAdvanced = true;
        hasMoreHistory = pageMayHaveMore(page, data, HISTORY_PAGE_LIMIT);
        processAndSetData(data, true);
        await Promise.all([
          refreshHistoricalElliottOverlays(requestedInterval),
          refreshHistoricalCandlestickOverlay(requestedInterval),
          refreshHarmonicOverlays(requestedInterval)
        ]);
      } else {
        hasMoreHistory = Boolean(page.hasMore);
        if (hasMoreHistory && page.source === 'CACHE_REFRESH_IN_PROGRESS') {
          scheduleHistoryRetry(requestedInterval, requestedCursor);
        }
      }
    } catch(e) {
      console.error("Pagination error:", e);
    } finally {
      isLoadingOlder = false;
      if (pageAdvanced && hasMoreHistory) {
        scheduleViewportHistoryFill();
      }
    }
  }

  function processAndSetData(rawData, isPrepend) {
    // Logical coordinates retain whitespace outside the first loaded candle.
    // A time range is clamped to the old first date and would hide every page
    // just prepended to the chart.
    const visibleLogicalRange = isPrepend
            ? priceChart.timeScale().getVisibleLogicalRange()
            : null;
    // A full replacement contains dates from the current page, so rebuild the
    // deduplication state instead of filtering those candles out as duplicates.
    if (!isPrepend) {
      seenDates.clear();
    }

    rawData.sort((a, b) => a.timestamp - b.timestamp);

    const newCandles = [];
    const newLines = [];
    const newVols = [];

    rawData.forEach(d => {
      // FIX: Force UTC processing to prevent timezone shift duplicates
      const dateObj = new Date(d.timestamp * 1000);
      const yyyy = dateObj.getUTCFullYear();
      const mm = String(dateObj.getUTCMonth() + 1).padStart(2, '0');
      const dd = String(dateObj.getUTCDate()).padStart(2, '0');
      const timeString = `${yyyy}-${mm}-${dd}`;

      if (seenDates.has(timeString)) return;
      seenDates.add(timeString);

      // Provide robust fallbacks if the market data provider returns null fields
      const open = d.openPrice || 0;
      const high = d.highPrice || open;
      const low = d.lowPrice || open;
      const close = d.closePrice || open;
      const volume = d.volume || 0;

      newCandles.push({
        time: timeString,
        timestamp: d.timestamp,
        open: open,
        high: high,
        low: low,
        close: close
      });
      newLines.push({ time: timeString, value: close });
      newVols.push({ time: timeString, value: volume, isUp: close >= open });
    });

    if (isPrepend) {
      globalCandleData = [...newCandles, ...globalCandleData];
      globalLineData = [...newLines, ...globalLineData];
      globalVolumeData = [...newVols, ...globalVolumeData];
    } else {
      globalCandleData = newCandles;
      globalLineData = newLines;
      globalVolumeData = newVols;
    }

    globalCandleData.sort((a, b) => a.time.localeCompare(b.time));
    globalLineData.sort((a, b) => a.time.localeCompare(b.time));
    globalVolumeData.sort((a, b) => a.time.localeCompare(b.time));

    if (globalCandleData.length > 0) {
      latestCandle = { ...globalCandleData[globalCandleData.length - 1] };
    }
    updateAnchoredVolumeProfileToggle();

    try {
      // Lightweight Charts normalises string dates in-place. Give the library
      // copies so our pagination arrays keep one stable YYYY-MM-DD type across
      // every prepend and can still be sorted/deduplicated reliably.
      candleSeries.setData(globalCandleData.map(item => ({
        time: item.time,
        open: item.open,
        high: item.high,
        low: item.low,
        close: item.close
      })));
      lineSeries.setData(globalLineData.map(item => ({ ...item })));
      volumeSeries.setData(themedVolumeData());
      updateRsiOverlay();
      scheduleChartIndicatorRefresh();
      fibonacciDrawingTool?.scheduleRender();
    } catch (err) {
      console.error("TradingView setData Crash:", err);
    }

    if (visibleLogicalRange && newCandles.length > 0) {
      priceChart.timeScale().setVisibleLogicalRange({
        from: visibleLogicalRange.from + newCandles.length,
        to: visibleLogicalRange.to + newCandles.length
      });
    }
    scheduleAnchoredVolumeProfileRender();
    if (historicalCandlestickOverlayEnabled) {
      rebuildHistoricalCandlestickHitTargets();
    }
  }

  function calculateWilderRsi(candles, period) {
    const points = candles.map(candle => ({ time: candle.time }));
    if (!Number.isSafeInteger(period) || period < 1 || candles.length <= period) {
      return points;
    }
    const closes = candles.map(candle => Number(candle.close));
    if (closes.some(close => !Number.isFinite(close))) {
      return points;
    }

    let averageGain = 0;
    let averageLoss = 0;
    for (let index = 1; index <= period; index++) {
      const change = closes[index] - closes[index - 1];
      averageGain += Math.max(change, 0);
      averageLoss += Math.max(-change, 0);
    }
    averageGain /= period;
    averageLoss /= period;
    points[period] = { time: candles[period].time, value: rsiValue(averageGain, averageLoss) };

    for (let index = period + 1; index < candles.length; index++) {
      const change = closes[index] - closes[index - 1];
      const gain = Math.max(change, 0);
      const loss = Math.max(-change, 0);
      averageGain = ((averageGain * (period - 1)) + gain) / period;
      averageLoss = ((averageLoss * (period - 1)) + loss) / period;
      points[index] = { time: candles[index].time, value: rsiValue(averageGain, averageLoss) };
    }
    return points;
  }

  function rsiValue(averageGain, averageLoss) {
    if (averageGain === 0 && averageLoss === 0) return 50;
    if (averageLoss === 0) return 100;
    if (averageGain === 0) return 0;
    return 100 - (100 / (1 + (averageGain / averageLoss)));
  }

  function ensureRsiChart() {
    if (rsiChart) return;
    const container = document.getElementById('rsiChartContainer');
    const options = chartThemeOptions();
    const theme = rsiSeriesThemeOptions();
    rsiChart = LightweightCharts.createChart(container, {
      ...options,
      // Keep the RSI pane aligned with the price chart, but give it its own
      // visible date axis so the crosshair date is readable in this pane too.
      timeScale: { ...options.timeScale, visible: true },
      handleScroll: false,
      handleScale: false
    });
    rsiSeries = rsiChart.addLineSeries({
      color: theme.line,
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: true,
      crosshairMarkerVisible: true,
      priceFormat: {
        type: 'custom',
        formatter: value => value.toFixed(0)
      },
      autoscaleInfoProvider: () => ({
        priceRange: { minValue: 0, maxValue: 100 }
      })
    });
    rsiGuideLines = [
      rsiSeries.createPriceLine({
        price: rsiOverboughtBoundary,
        color: theme.overboughtGuide,
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dashed,
        axisLabelVisible: true,
        title: formatRsiBoundary(rsiOverboughtBoundary)
      }),
      rsiSeries.createPriceLine({
        price: 50,
        color: theme.midpoint,
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dotted,
        axisLabelVisible: false
      }),
      rsiSeries.createPriceLine({
        price: rsiOversoldBoundary,
        color: theme.oversoldGuide,
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dashed,
        axisLabelVisible: true,
        title: formatRsiBoundary(rsiOversoldBoundary)
      })
    ];
  }

  function updateRsiOverlay() {
    if (!rsiOverlayEnabled || !rsiSeries) return;
    const points = calculateWilderRsi(globalCandleData, rsiPeriod);
    rsiSeries.setData(themedRsiData(
            points,
            rsiOverboughtBoundary,
            rsiOversoldBoundary
    ));
    const upperLabel = formatRsiBoundary(rsiOverboughtBoundary);
    const lowerLabel = formatRsiBoundary(rsiOversoldBoundary);
    rsiGuideLines[0]?.applyOptions({
      price: rsiOverboughtBoundary,
      title: upperLabel
    });
    rsiGuideLines[2]?.applyOptions({
      price: rsiOversoldBoundary,
      title: lowerLabel
    });
    document.getElementById('rsiChartTitle').textContent = `RSI (${rsiPeriod})`;
    document.getElementById('rsiChartMeta').textContent =
            `Wilder RSI · ${upperLabel} / ${lowerLabel} reference levels`;

    const status = document.getElementById('rsiChartStatus');
    status.classList.remove('overbought', 'oversold');
    let latestPoint = null;
    for (let index = points.length - 1; index >= 0; index--) {
      if (Number.isFinite(points[index].value)) {
        latestPoint = points[index];
        break;
      }
    }
    if (!latestPoint) {
      status.textContent = `RSI (${rsiPeriod}) needs at least ${rsiPeriod + 1} loaded candles.`;
      return;
    }
    const zone = latestPoint.value > rsiOverboughtBoundary
            ? 'Overbought range'
            : (latestPoint.value < rsiOversoldBoundary ? 'Oversold range' : 'Neutral range');
    if (latestPoint.value > rsiOverboughtBoundary) status.classList.add('overbought');
    if (latestPoint.value < rsiOversoldBoundary) status.classList.add('oversold');
    status.textContent = `Latest RSI (${rsiPeriod}): ${latestPoint.value.toFixed(2)} · ${zone}`;
  }

  function updateRsiOverlayToggle() {
    const button = document.getElementById('rsiOverlayToggle');
    button.classList.toggle('active', rsiOverlayEnabled);
    button.setAttribute('aria-pressed', String(rsiOverlayEnabled));
    button.textContent = rsiOverlayEnabled
            ? `RSI · ${rsiPeriod} · ${formatRsiBoundary(rsiOverboughtBoundary)}/${formatRsiBoundary(rsiOversoldBoundary)}`
            : 'RSI overlay';
  }

  function openRsiPeriodDialog() {
    const dialog = document.getElementById('rsiPeriodDialog');
    const input = document.getElementById('rsiPeriodInput');
    document.getElementById('rsiPeriodError').textContent = '';
    input.value = String(rsiPeriod);
    document.getElementById('rsiOverboughtInput').value = formatRsiBoundary(rsiOverboughtBoundary);
    document.getElementById('rsiOversoldInput').value = formatRsiBoundary(rsiOversoldBoundary);
    if (!dialog.open) dialog.showModal();
    window.setTimeout(() => {
      input.focus();
      input.select();
    }, 0);
  }

  function closeRsiPeriodDialog() {
    const dialog = document.getElementById('rsiPeriodDialog');
    if (dialog.open) dialog.close();
  }

  function enableRsiOverlay(period, overboughtBoundary, oversoldBoundary) {
    rsiPeriod = period;
    rsiOverboughtBoundary = overboughtBoundary;
    rsiOversoldBoundary = oversoldBoundary;
    rsiOverlayEnabled = true;
    const panel = document.getElementById('rsiChartPanel');
    panel.hidden = false;
    ensureRsiChart();
    updateRsiOverlayToggle();
    updateRsiOverlay();
    window.requestAnimationFrame(() => {
      const container = document.getElementById('rsiChartContainer');
      rsiChart.resize(container.clientWidth, container.clientHeight);
      const range = priceChart?.timeScale().getVisibleLogicalRange();
      if (range) rsiChart.timeScale().setVisibleLogicalRange(range);
    });
  }

  function disableRsiOverlay() {
    rsiOverlayEnabled = false;
    document.getElementById('rsiChartPanel').hidden = true;
    updateRsiOverlayToggle();
  }

  function toggleRsiOverlay() {
    if (rsiOverlayEnabled) {
      disableRsiOverlay();
      return;
    }
    openRsiPeriodDialog();
  }

  function updateVolumeChartToggle() {
    const button = document.getElementById('volumeChartToggle');
    button.classList.toggle('active', volumeChartEnabled);
    button.setAttribute('aria-pressed', String(volumeChartEnabled));
    button.textContent = volumeChartEnabled ? 'Volume: on' : 'Volume';
  }

  function toggleVolumeChart() {
    volumeChartEnabled = !volumeChartEnabled;
    const panel = document.getElementById('volumeChartPanel');
    panel.hidden = !volumeChartEnabled;
    updateVolumeChartToggle();
    if (!volumeChartEnabled) return;
    window.requestAnimationFrame(() => {
      scheduleGeneralWorkspaceChartResize();
      const range = priceChart?.timeScale().getVisibleLogicalRange();
      if (range && volumeChart) volumeChart.timeScale().setVisibleLogicalRange(range);
    });
  }

  function submitRsiPeriod(event) {
    event.preventDefault();
    const input = document.getElementById('rsiPeriodInput');
    const period = Number(input.value);
    if (!Number.isSafeInteger(period) || period < 1) {
      document.getElementById('rsiPeriodError').textContent =
              'Enter a positive whole number, such as 14.';
      input.focus();
      return;
    }
    const overboughtInput = document.getElementById('rsiOverboughtInput');
    const oversoldInput = document.getElementById('rsiOversoldInput');
    const overboughtBoundary = Number(overboughtInput.value);
    const oversoldBoundary = Number(oversoldInput.value);
    if (!Number.isFinite(overboughtBoundary) || !Number.isFinite(oversoldBoundary)
            || oversoldBoundary <= 0 || overboughtBoundary >= 100
            || oversoldBoundary >= overboughtBoundary) {
      document.getElementById('rsiPeriodError').textContent =
              'Choose boundaries inside 0–100, with the upper boundary greater than the lower boundary.';
      if (!Number.isFinite(overboughtBoundary) || overboughtBoundary >= 100) {
        overboughtInput.focus();
      } else {
        oversoldInput.focus();
      }
      return;
    }
    closeRsiPeriodDialog();
    enableRsiOverlay(period, overboughtBoundary, oversoldBoundary);
  }

  function openChartIndicatorDialog(type) {
    const spec = CHART_INDICATOR_SPECS[type];
    if (!spec) return;
    pendingChartIndicatorType = type;
    document.getElementById('chartIndicatorDialogTitle').textContent = `Add ${spec.label}`;
    document.getElementById('chartIndicatorDialogDescription').textContent = spec.description;
    document.getElementById('chartIndicatorError').textContent = '';
    const fields = document.getElementById('chartIndicatorFields');
    fields.replaceChildren();
    spec.fields.forEach(field => {
      const wrapper = document.createElement('label');
      wrapper.className = 'chart-indicator-field';
      const label = document.createElement('span');
      label.textContent = field.label;
      const input = document.createElement('input');
      input.type = 'number';
      input.name = field.key;
      input.value = String(field.value);
      input.min = String(field.min);
      input.max = String(field.max);
      input.step = String(field.step);
      input.inputMode = field.step === 1 ? 'numeric' : 'decimal';
      input.required = true;
      wrapper.append(label, input);
      if (field.help) {
        const help = document.createElement('small');
        help.textContent = field.help;
        wrapper.append(help);
      }
      fields.append(wrapper);
    });
    const dialog = document.getElementById('chartIndicatorDialog');
    if (!dialog.open) dialog.showModal();
    window.setTimeout(() => fields.querySelector('input')?.focus(), 0);
  }

  function closeChartIndicatorDialog() {
    const dialog = document.getElementById('chartIndicatorDialog');
    if (dialog.open) dialog.close();
    pendingChartIndicatorType = null;
  }

  function submitChartIndicator(event) {
    event.preventDefault();
    const type = pendingChartIndicatorType;
    const spec = CHART_INDICATOR_SPECS[type];
    if (!spec) return;
    const parameters = {};
    for (const field of spec.fields) {
      const input = event.currentTarget.elements.namedItem(field.key);
      const value = Number(input?.value);
      if (!Number.isFinite(value) || value < field.min || value > field.max
              || (field.step === 1 && !Number.isSafeInteger(value))) {
        document.getElementById('chartIndicatorError').textContent =
                `${field.label} must be between ${field.min} and ${field.max}${field.step === 1 ? ' and must be a whole number' : ''}.`;
        input?.focus();
        return;
      }
      parameters[field.key] = value;
    }
    if (type === 'MACD' && parameters.fast >= parameters.slow) {
      document.getElementById('chartIndicatorError').textContent =
              'The MACD fast period must be below the slow period.';
      event.currentTarget.elements.namedItem('fast')?.focus();
      return;
    }
    const id = `${type.toLowerCase()}-${crypto.randomUUID()}`;
    activeChartIndicators.set(id, { id, type, parameters, displayLabel: configuredIndicatorLabel(type, parameters) });
    closeChartIndicatorDialog();
    renderActiveChartIndicatorControls();
    scheduleChartIndicatorRefresh(0);
  }

  function configuredIndicatorLabel(type, parameters) {
    const spec = CHART_INDICATOR_SPECS[type];
    const values = Object.values(parameters);
    if (!values.length) return spec.label;
    if (type === 'MACD') return `MACD ${parameters.fast}/${parameters.slow}/${parameters.signal}`;
    if (type === 'BOLLINGER') return `Bollinger ${parameters.period} / ${parameters.deviation}σ`;
    if (type === 'KELTNER') return `Keltner ${parameters.period} / ATR ${parameters.atrPeriod} × ${parameters.multiplier}`;
    if (type === 'VOLUME_PROFILE') return `Rolling profile ${parameters.period}`;
    return `${spec.label} ${values[0]}`;
  }

  function removeChartIndicator(id) {
    activeChartIndicators.delete(id);
    removeChartIndicatorVisuals(id);
    renderActiveChartIndicatorControls();
  }

  function removeChartIndicatorVisuals(id) {
    const priceSeries = chartIndicatorPriceSeries.get(id) || [];
    priceSeries.forEach(series => {
      try { priceChart?.removeSeries(series); } catch (ignored) { }
    });
    chartIndicatorPriceSeries.delete(id);
    const panel = chartIndicatorPanels.get(id);
    if (panel) {
      panel.chart?.remove();
      panel.element?.remove();
      chartIndicatorPanels.delete(id);
    }
    renderChartIndicatorOverlayLegend();
  }

  function renderActiveChartIndicatorControls() {
    const container = document.getElementById('activeChartIndicatorList');
    container.replaceChildren();
    activeChartIndicators.forEach(config => {
      const chip = document.createElement('span');
      chip.className = 'active-chart-indicator-chip';
      const copy = document.createElement('span');
      copy.textContent = config.displayLabel;
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.textContent = '×';
      remove.setAttribute('aria-label', `Remove ${config.displayLabel}`);
      remove.addEventListener('click', event => {
        event.stopPropagation();
        removeChartIndicator(config.id);
      });
      chip.append(copy, remove);
      container.append(chip);
    });
  }

  function renderChartIndicatorOverlayLegend() {
    const legend = document.getElementById('chartIndicatorOverlayLegend');
    legend.replaceChildren();
    activeChartIndicators.forEach(config => {
      if (!chartIndicatorPriceSeries.has(config.id)) return;
      const chip = document.createElement('span');
      chip.className = 'chart-indicator-overlay-chip';
      chip.style.borderColor = indicatorColor(config.id, 0);
      const label = document.createElement('span');
      label.textContent = config.displayLabel;
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.textContent = '×';
      remove.setAttribute('aria-label', `Remove ${config.displayLabel}`);
      remove.addEventListener('click', () => removeChartIndicator(config.id));
      chip.append(label, remove);
      legend.append(chip);
    });
  }

  function indicatorColor(id, seriesIndex) {
    let hash = 0;
    for (let index = 0; index < id.length; index++) hash = ((hash << 5) - hash + id.charCodeAt(index)) | 0;
    const colorIndex = (Math.abs(hash) + seriesIndex * 3) % CHART_INDICATOR_COLORS.length;
    return CHART_INDICATOR_COLORS.at(colorIndex);
  }

  function scheduleChartIndicatorRefresh(delay = 120) {
    clearTimeout(chartIndicatorRefreshTimer);
    if (!activeChartIndicators.size || !globalCandleData.length) return;
    chartIndicatorRefreshTimer = window.setTimeout(refreshChartIndicators, delay);
  }

  async function refreshChartIndicators() {
    if (!activeChartIndicators.size || !globalCandleData.length) return;
    const requestId = ++chartIndicatorRequestSequence;
    const requestedInterval = currentInterval;
    const first = globalCandleData[0]?.timestamp;
    const last = globalCandleData[globalCandleData.length - 1]?.timestamp;
    try {
      const response = await fetch(
              `/api/stocks/${encodedTicker}/chart-indicators?interval=${encodeURIComponent(requestedInterval)}`,
              {
                method: 'POST',
                headers: secureJsonHeaders(),
                body: JSON.stringify({
                  from: first,
                  to: last,
                  indicators: [...activeChartIndicators.values()].map(({ id, type, parameters }) => ({ id, type, parameters }))
                })
              });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || body.detail || body.error || 'Indicator calculation failed.');
      }
      const batch = await response.json();
      if (requestId !== chartIndicatorRequestSequence || requestedInterval !== currentInterval) return;
      const returned = new Set();
      (batch.indicators || []).forEach(indicator => {
        if (!activeChartIndicators.has(indicator.id)) return;
        returned.add(indicator.id);
        removeChartIndicatorVisuals(indicator.id);
        const config = activeChartIndicators.get(indicator.id);
        config.displayLabel = indicator.label || config.displayLabel;
        if (indicator.placement === 'PRICE') {
          renderPriceChartIndicator(indicator);
        } else {
          renderIndicatorPanel(indicator);
        }
      });
      activeChartIndicators.forEach((config, id) => {
        if (!returned.has(id)) removeChartIndicatorVisuals(id);
      });
      renderActiveChartIndicatorControls();
      renderChartIndicatorOverlayLegend();
    } catch (error) {
      if (requestId !== chartIndicatorRequestSequence) return;
      console.warn('Chart indicators unavailable', error);
      const legend = document.getElementById('chartIndicatorOverlayLegend');
      legend.textContent = error.message || 'Indicators could not be calculated.';
    }
  }

  function indicatorPoints(points) {
    return (points || []).map(point => ({
      time: new Date(point.timestamp * 1000).toISOString().slice(0, 10),
      value: Number(point.value)
    })).filter(point => Number.isFinite(point.value));
  }

  function renderPriceChartIndicator(indicator) {
    const created = [];
    (indicator.series || []).forEach((definition, index) => {
      const series = priceChart.addLineSeries({
        color: indicatorColor(indicator.id, index),
        lineWidth: definition.key === 'middle' || definition.key === 'poc' ? 2 : 1,
        lineStyle: definition.key === 'middle' || definition.key === 'poc'
                ? LightweightCharts.LineStyle.Dashed
                : LightweightCharts.LineStyle.Solid,
        title: `${indicator.label} · ${definition.label}`,
        priceLineVisible: false,
        lastValueVisible: true,
        crosshairMarkerVisible: false
      });
      series.setData(indicatorPoints(definition.points));
      created.push(series);
    });
    chartIndicatorPriceSeries.set(indicator.id, created);
  }

  function renderIndicatorPanel(indicator) {
    const panelElement = document.createElement('section');
    panelElement.className = 'chart-wrapper resizable-chart-panel dynamic-indicator-panel';
    panelElement.dataset.indicatorId = indicator.id;
    const heading = document.createElement('div');
    heading.className = 'chart-panel-head';
    const title = document.createElement('h2');
    title.className = 'chart-panel-title';
    title.textContent = indicator.label;
    const actions = document.createElement('div');
    actions.className = 'dynamic-indicator-panel-actions';
    const meta = document.createElement('span');
    meta.className = 'chart-panel-meta';
    meta.textContent = 'Synchronized with price history';
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'dynamic-indicator-panel-remove';
    remove.textContent = 'Remove';
    remove.addEventListener('click', () => removeChartIndicator(indicator.id));
    actions.append(meta, remove);
    heading.append(title, actions);
    const container = document.createElement('div');
    container.className = 'dynamic-indicator-chart';
    panelElement.append(heading, container);
    document.getElementById('dynamicIndicatorPanels').append(panelElement);

    const options = chartThemeOptions();
    const chart = LightweightCharts.createChart(container, {
      ...options,
      timeScale: { ...options.timeScale, visible: true },
      handleScroll: false,
      handleScale: false
    });
    const created = [];
    (indicator.series || []).forEach((definition, index) => {
      const color = indicatorColor(indicator.id, index);
      const series = definition.style === 'HISTOGRAM'
              ? chart.addHistogramSeries({
                priceLineVisible: false,
                lastValueVisible: true,
                color,
                base: 0
              })
              : chart.addLineSeries({
                color,
                lineWidth: 2,
                priceLineVisible: false,
                lastValueVisible: true,
                title: definition.label
              });
      const points = indicatorPoints(definition.points);
      series.setData(definition.style === 'HISTOGRAM'
              ? points.map(point => ({ ...point, color: point.value >= 0 ? '#64e8bd' : '#ff647c' }))
              : points);
      created.push(series);
    });
    if (created.length) {
      (indicator.references || []).forEach(reference => created[0].createPriceLine({
        price: reference,
        color: 'rgba(150, 160, 154, 0.45)',
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dashed,
        axisLabelVisible: true,
        title: String(reference)
      }));
    }
    chartIndicatorPanels.set(indicator.id, { chart, element: panelElement, container, series: created });
    const range = priceChart?.timeScale().getVisibleLogicalRange();
    if (range) chart.timeScale().setVisibleLogicalRange(range);
  }

  function maybeLoadOlderForVisibleRange(range) {
    if (!range || isFetching || isLoadingOlder || !hasMoreHistory || !candleSeries) return;

    // This is Lightweight Charts' supported infinite-history signal. Unlike
    // comparing raw logical indexes, barsBefore remains correct when another
    // series changes the chart's shared time scale.
    const barsInfo = candleSeries.barsInLogicalRange(range);
    const barsBefore = barsInfo?.barsBefore;
    if ((Number.isFinite(barsBefore) && barsBefore < HISTORY_PREFETCH_THRESHOLD)
            || (!Number.isFinite(barsBefore) && range.from < HISTORY_PREFETCH_THRESHOLD)) {
      void loadOlderData();
    }
  }

  function pageMayHaveMore(page, data, pageLimit) {
    // A full page is enough evidence to try the next cursor even if an older
    // server or stale cache omitted/incorrectly reported the hasMore flag. One
    // final empty request safely switches pagination off at the true beginning.
    return Boolean(page.hasMore) || data.length >= pageLimit;
  }

  function scheduleViewportHistoryFill() {
    requestAnimationFrame(() => {
      if (!priceChart) return;
      maybeLoadOlderForVisibleRange(priceChart.timeScale().getVisibleLogicalRange());
    });
  }

  function scheduleHistoryRetry(interval, cursor) {
    if (historyRetryAttempts >= 4) return;
    clearTimeout(historyRetryTimer);
    const delay = 500 * (2 ** historyRetryAttempts);
    historyRetryAttempts++;
    historyRetryTimer = setTimeout(() => {
      historyRetryTimer = null;
      if (currentInterval === interval && oldestTimestamp === cursor && hasMoreHistory) {
        loadOlderData();
      }
    }, delay);
  }

  function showNewestCandles(count) {
    if (!priceChart || globalCandleData.length === 0) return;
    const lastIndex = globalCandleData.length - 1;
    priceChart.timeScale().setVisibleLogicalRange({
      from: Math.max(0, globalCandleData.length - count),
      to: lastIndex + 4
    });
  }

  async function refreshHistoricalElliottOverlays(interval) {
    const requestId = ++elliottRequestSequence;
    const status = document.getElementById('elliottOverlayStatus');
    status.classList.remove('error', 'warning');
    clearElliottOverlays();
    const nativeEnabled = elliottOverlaysEnabled && ['1d', '1wk', '1mo'].includes(interval);
    const subwavesEnabled = elliottSubwavesEnabled && ['1d', '1wk'].includes(interval);
    if (!nativeEnabled && !subwavesEnabled) {
      status.textContent = '';
      return;
    }

    status.textContent = nativeEnabled && subwavesEnabled
            ? 'Analysing independent Elliott structures and Monthly-derived fractal subwaves...'
            : (nativeEnabled
                    ? 'Analysing independent Elliott structures detected on this interval...'
                    : 'Building Monthly-derived fractal subwaves...');
    try {
      const fromQuery = Number.isSafeInteger(oldestTimestamp) && oldestTimestamp > 0
              ? `&from=${encodeURIComponent(oldestTimestamp)}`
              : '';
      const cardInterval = interval === '1d' ? 'DAILY' : (interval === '1wk' ? 'WEEKLY' : 'MONTHLY');
      const hierarchyPromise = subwavesEnabled
              ? elliottHierarchyOverlay?.showInterval(interval) || Promise.resolve(0)
              : Promise.resolve(0);
      const [response, cardResponse, subwaveCount] = await Promise.all([
        nativeEnabled
                ? fetch(`/api/stocks/${encodedTicker}/elliott-waves/history?interval=${encodeURIComponent(interval)}${fromQuery}`)
                : Promise.resolve(null),
        nativeEnabled
                ? fetch(`/api/alerts/${encodedTicker}/elliott-cards?interval=${encodeURIComponent(cardInterval)}`)
                        .catch(() => null)
                : Promise.resolve(null),
        hierarchyPromise
      ]);
      if (response && !response.ok) throw new Error('Independent Elliott structure request failed');
      const history = response ? await response.json() : { structures: [] };
      const cards = cardResponse?.ok ? await cardResponse.json() : [];
      if (requestId !== elliottRequestSequence || currentInterval !== interval) return;

      elliottSignalCards = new Map((Array.isArray(cards) ? cards : []).map(card => [
        elliottSignalCardKey(card.cycleKey, card.stage),
        card
      ]));

      const uniqueStructures = Array.from(new Map((history.structures || []).map(structure => [
        structure.structureId || elliottStructureFallbackId(structure),
        structure
      ])).values());
      uniqueStructures.forEach(structure => renderHistoricalElliottStructure(structure));
      setElliottConfirmationMarkers(uniqueStructures);
      rebuildElliottHitTargets();

      if (uniqueStructures.length === 0) {
        if (Number(subwaveCount) > 0) {
          status.textContent = `Showing ${subwaveCount} validated ${interval === '1d' ? 'Daily' : 'Weekly'} fractal subwaves derived from the Monthly hierarchy${nativeEnabled ? '; no independent structure was detected on this interval' : ''}.`;
          return;
        }
        if (!nativeEnabled) {
          status.textContent = 'No validated fractal subwaves are available from the Monthly hierarchy.';
          return;
        }
        status.textContent = 'No complete I–V or I–V, A–B–C Elliott structure detected in the loaded range.';
        return;
      }

      const warnedStructureCount = uniqueStructures
              .filter(structure => elliottQualityWarnings(structure).length > 0).length;
      const qualityScores = uniqueStructures
              .map(structure => Number(structure.qualityScore))
              .filter(Number.isFinite);
      const qualityMin = qualityScores.length ? Math.min(...qualityScores) : null;
      const qualityMax = qualityScores.length ? Math.max(...qualityScores) : null;
      const qualityNotice = qualityMin === null
              ? ''
              : (qualityMin === qualityMax
                      ? ` Quality ${qualityMin}/100.`
                      : ` Quality range ${qualityMin}–${qualityMax}/100.`);
      const warningNotice = warnedStructureCount > 0
              ? ` ${warnedStructureCount} ${warnedStructureCount === 1 ? 'has' : 'have'} reduced-confidence guideline warnings.`
              : '';
      if (warnedStructureCount > 0) status.classList.add('warning');
      const subwaveNotice = subwavesEnabled && Number(subwaveCount) > 0
              ? ` Plus ${subwaveCount} validated ${interval === '1d' ? 'Daily' : 'Weekly'} fractal subwaves from the Monthly hierarchy.`
              : '';
      status.textContent = `Showing ${uniqueStructures.length} independent Elliott Wave structure${uniqueStructures.length === 1 ? '' : 's'} in ${globalCandleData.length} loaded candles.${subwaveNotice}${qualityNotice}${warningNotice}`;
    } catch (error) {
      if (requestId === elliottRequestSequence && currentInterval === interval) {
        status.classList.add('error');
        status.textContent = 'Elliott Wave overlay unavailable.';
      }
      console.warn('Elliott Wave overlay failed', error);
    }
  }

  function normaliseCandlePage(payload) {
    if (Array.isArray(payload)) {
      return {
        candles: payload,
        nextCursor: payload.length ? payload[0].timestamp : null,
        hasMore: payload.length > 0,
        source: 'CACHE',
        failureMessage: null
      };
    }
    return {
      candles: Array.isArray(payload?.candles) ? payload.candles : [],
      nextCursor: payload?.nextCursor ?? null,
      hasMore: Boolean(payload?.hasMore),
      source: payload?.source || 'CACHE',
      failureMessage: payload?.failureMessage || null
    };
  }

  function elliottStructureFallbackId(structure) {
    const points = structure.points || [];
    const first = points[0] || {};
    const last = points[points.length - 1] || {};
    return `${structure.direction || ''}:${first.timestamp || ''}:${last.label || ''}:${last.timestamp || ''}`;
  }

  function clearElliottOverlays() {
    clearElliottSubwaveOverlays();
    clearIndependentElliottOverlays();
  }

  function clearElliottSubwaveOverlays() {
    elliottHierarchyOverlay?.clear();
  }

  function clearIndependentElliottOverlays() {
    hideElliottWaveCard();
    elliottSignalCards = new Map();
    clearHistoricalElliottSeries();
    if (elliottConfirmationSeries) {
      elliottConfirmationSeries.setData([]);
      elliottConfirmationSeries.setMarkers([]);
    }
  }

  function updateElliottOverlayToggle() {
    const button = document.getElementById('elliottOverlayToggle');
    const supported = ['1d', '1wk', '1mo'].includes(currentInterval);
    button.disabled = !supported;
    button.classList.toggle('active', supported && elliottOverlaysEnabled);
    button.setAttribute('aria-pressed', String(supported && elliottOverlaysEnabled));
    button.textContent = supported
            ? `Independent Elliott structures: ${elliottOverlaysEnabled ? 'on' : 'off'}`
            : 'Independent Elliott structures';
    updateElliottSubwaveToggle();
  }

  function updateElliottSubwaveToggle() {
    const button = document.getElementById('elliottSubwaveToggle');
    const supported = ['1d', '1wk'].includes(currentInterval);
    button.disabled = !supported;
    button.classList.toggle('active', supported && elliottSubwavesEnabled);
    button.setAttribute('aria-pressed', String(supported && elliottSubwavesEnabled));
    button.textContent = supported
            ? `Fractal subwaves: ${elliottSubwavesEnabled ? 'on' : 'off'}`
            : 'Fractal subwaves';
  }

  async function toggleElliottOverlays() {
    if (!['1d', '1wk', '1mo'].includes(currentInterval)) return;
    elliottOverlaysEnabled = !elliottOverlaysEnabled;
    updateElliottOverlayToggle();
    await refreshHistoricalElliottOverlays(currentInterval);
  }

  async function toggleElliottSubwaves() {
    if (!['1d', '1wk'].includes(currentInterval)) return;
    elliottSubwavesEnabled = !elliottSubwavesEnabled;
    updateElliottSubwaveToggle();
    await refreshHistoricalElliottOverlays(currentInterval);
  }

  function harmonicOverlayColors(direction) {
    return {
      line: HARMONIC_FORMATION_COLOR,
      marker: HARMONIC_FORMATION_COLOR
    };
  }

  function updateHarmonicOverlayToggle() {
    const button = document.getElementById('harmonicOverlayToggle');
    const supported = ['1d', '1wk', '1mo'].includes(currentInterval);
    button.disabled = !supported;
    button.classList.toggle('active', supported && harmonicOverlaysEnabled);
    button.setAttribute('aria-pressed', String(supported && harmonicOverlaysEnabled));
    button.textContent = supported && harmonicOverlaysEnabled
            ? 'Harmonic formations overlay: on'
            : 'Harmonic formations overlay';
  }

  async function toggleHarmonicOverlays() {
    if (!['1d', '1wk', '1mo'].includes(currentInterval)) return;
    harmonicOverlaysEnabled = !harmonicOverlaysEnabled;
    updateHarmonicOverlayToggle();
    await refreshHarmonicOverlays(currentInterval);
  }

  async function refreshHarmonicOverlays(interval) {
    const requestId = ++harmonicRequestSequence;
    const status = document.getElementById('harmonicOverlayStatus');
    status.classList.remove('error', 'warning');
    clearHarmonicOverlays();
    if (!harmonicOverlaysEnabled || !['1d', '1wk', '1mo'].includes(interval)) {
      status.textContent = '';
      return;
    }
    status.textContent = 'Scanning confirmed pivots for harmonic formations...';
    try {
      const fromQuery = Number.isSafeInteger(oldestTimestamp) && oldestTimestamp > 0
              ? `&from=${encodeURIComponent(oldestTimestamp)}`
              : '';
      const response = await fetch(
              `/api/stocks/${encodedTicker}/harmonic-formations/history?interval=${encodeURIComponent(interval)}${fromQuery}`
      );
      if (!response.ok) throw new Error('Harmonic formation request failed');
      const history = await response.json();
      if (requestId !== harmonicRequestSequence || currentInterval !== interval || !harmonicOverlaysEnabled) return;
      const formations = Array.isArray(history.formations) ? history.formations : [];
      formations.forEach(renderHarmonicFormation);
      rebuildHarmonicHitTargets();
      if (formations.length === 0) {
        status.textContent = 'No confirmed Gartley, Bat, Butterfly, Crab, Shark, or Cypher formation was found in the loaded range.';
        return;
      }
      const bullish = formations.filter(formation => formation.direction === 'BULLISH').length;
      const bearish = formations.length - bullish;
      const minimumQuality = Math.min(...formations.map(formation => Number(formation.qualityScore) || 0));
      const maximumQuality = Math.max(...formations.map(formation => Number(formation.qualityScore) || 0));
      status.textContent = `Showing ${formations.length} confirmed harmonic formation${formations.length === 1 ? '' : 's'} `
              + `(${bullish} bullish, ${bearish} bearish). Quality ${minimumQuality}-${maximumQuality}/100.`;
    } catch (error) {
      if (requestId === harmonicRequestSequence && currentInterval === interval) {
        status.classList.add('error');
        status.textContent = 'Harmonic formations overlay unavailable.';
      }
      console.warn('Harmonic formations overlay failed', error);
    }
  }

  function renderHarmonicFormation(formation) {
    const points = Array.isArray(formation.points) ? formation.points : [];
    if (points.length !== 5) return;
    const colors = harmonicOverlayColors(formation.direction);
    const series = priceChart.addLineSeries({
      color: colors.line,
      lineWidth: 2,
      lineStyle: 2,
      crosshairMarkerVisible: false,
      lastValueVisible: false,
      priceLineVisible: false
    });
    series.setData(points.map(point => ({
      time: timestampToChartDate(Number(point.timestamp)),
      value: Number(point.price)
    })));
    const directionLabel = formation.direction === 'BEARISH' ? 'Bearish' : 'Bullish';
    const patternLabel = String(formation.pattern || '')
            .toLowerCase()
            .replaceAll('_', ' ')
            .replace(/\b\w/g, letter => letter.toUpperCase());
    series.setMarkers(points.map((point, index) => ({
      time: timestampToChartDate(Number(point.timestamp)),
      position: point.pivotType === 'HIGH' ? 'aboveBar' : 'belowBar',
      color: colors.marker,
      shape: index === points.length - 1 ? (formation.direction === 'BEARISH' ? 'arrowDown' : 'arrowUp') : 'circle',
      text: index === points.length - 1
              ? `${point.label} · ${directionLabel} ${patternLabel} · Q${formation.qualityScore}`
              : point.label
    })));
    harmonicFormationSeries.push(series);
    harmonicFormationOverlays.push({ series, formation });
  }

  function clearHarmonicOverlays() {
    if (!priceChart) return;
    hideHarmonicFormationCard();
    harmonicFormationSeries.forEach(series => priceChart.removeSeries(series));
    harmonicFormationSeries = [];
    harmonicFormationOverlays = [];
    document.getElementById('harmonicFormationHitTargets')?.replaceChildren();
  }

  function rebuildHarmonicHitTargets() {
    const layer = document.getElementById('harmonicFormationHitTargets');
    if (!layer) return;
    layer.replaceChildren();
    harmonicFormationOverlays.forEach(overlay => {
      const endpoint = overlay.formation.points?.at(-1);
      if (!endpoint) return;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `elliott-wave-hit-target harmonic-formation-hit-target ${overlay.formation.direction === 'BEARISH' ? 'sell' : 'buy'}`;
      button.style.borderColor = HARMONIC_FORMATION_COLOR;
      button.style.color = HARMONIC_FORMATION_COLOR;
      button.setAttribute('aria-label', `${endpoint.label} point, ${overlay.formation.direction} ${overlay.formation.pattern} harmonic formation`);
      button.title = 'Open harmonic signal summary';
      button._harmonicOverlay = overlay;
      button.addEventListener('mouseenter', () => showHarmonicFormationCard(overlay, false));
      button.addEventListener('mouseleave', scheduleHarmonicFormationCardHide);
      button.addEventListener('click', event => {
        event.stopPropagation();
        showHarmonicFormationCard(overlay, true);
      });
      layer.appendChild(button);
    });
    scheduleHarmonicHitTargetPositioning();
  }

  function scheduleHarmonicHitTargetPositioning() {
    if (harmonicHitTargetFrame !== null) return;
    harmonicHitTargetFrame = requestAnimationFrame(() => {
      harmonicHitTargetFrame = null;
      const stage = document.getElementById('priceChartStage');
      document.querySelectorAll('.harmonic-formation-hit-target').forEach(button => {
        const overlay = button._harmonicOverlay;
        const endpoint = overlay?.formation?.points?.at(-1);
        const x = endpoint ? priceChart.timeScale().timeToCoordinate(timestampToChartDate(endpoint.timestamp)) : null;
        const y = endpoint ? overlay.series.priceToCoordinate(Number(endpoint.price)) : null;
        const visible = Number.isFinite(x) && Number.isFinite(y)
                && x >= 0 && x <= stage.clientWidth && y >= 0 && y <= stage.clientHeight;
        button.hidden = !visible;
        if (visible) {
          button.style.left = `${x}px`;
          button.style.top = `${y}px`;
        }
      });
    });
  }

  function showHarmonicFormationCard(overlay, pinned) {
    if (!overlay) return;
    hideElliottWaveCard();
    hideHistoricalCandlestickCard();
    clearTimeout(harmonicCardHideTimer);
    harmonicCardPinned = pinned;
    harmonicFormationOverlays.forEach(item => item.series.applyOptions({
      lineWidth: item === overlay ? 4 : 2
    }));
    const formation = overlay.formation;
    const endpoint = formation.points.at(-1);
    const title = String(formation.pattern || '').toLowerCase()
            .replaceAll('_', ' ').replace(/\b\w/g, letter => letter.toUpperCase());
    document.getElementById('harmonicFormationCardTitle').textContent = `${formation.direction === 'BEARISH' ? 'Bearish' : 'Bullish'} ${title}`;
    document.getElementById('harmonicFormationCardStage').textContent =
            `${tradeSignalDisplayLabel(formation.tradeSignal)} signal Â· ${formatHistoricalElliottPeriod(formation.confirmationTimestamp)} Â· score ${formation.qualityScore}/100`;
    const confirmationIndex = globalCandleData.findIndex(
            candle => Number(candle.timestamp) === Number(formation.confirmationTimestamp));
    const signalClose = confirmationIndex < 0 ? NaN : Number(globalCandleData[confirmationIndex].close);
    const forward = confirmationIndex < 0 ? [] : globalCandleData.slice(confirmationIndex + 1, confirmationIndex + 11);
    const available = Number.isFinite(signalClose) && signalClose > 0 && forward.length >= 10;
    const outcome = document.getElementById('harmonicFormationCardOutcome');
    const message = document.getElementById('harmonicFormationCardMessage');
    if (available) {
      const returns = forward.map(candle => {
        const raw = (Number(candle.close) - signalClose) / signalClose * 100;
        return formation.tradeSignal === 'SELL' ? -raw : raw;
      });
      outcome.hidden = false;
      message.hidden = true;
      document.getElementById('harmonicFormationCardOutcomeLabel').textContent =
              formation.tradeSignal === 'SELL' ? 'Largest close-based loss avoided' : 'Best close-based return';
      const best = Math.max(...returns);
      const value = document.getElementById('harmonicFormationCardOutcomeValue');
      value.textContent = formatSignedPercent(best);
      value.classList.toggle('positive', best >= 0);
      value.classList.toggle('negative', best < 0);
      document.getElementById('harmonicFormationCardWindowEnd').textContent =
              `At candle 10: ${formatSignedPercent(returns[9])}`;
    } else {
      outcome.hidden = true;
      message.hidden = false;
      const count = confirmationIndex < 0 ? 0 : globalCandleData.length - confirmationIndex - 1;
      message.textContent = confirmationIndex < 0
              ? 'The confirmation candle is outside the currently loaded chart range.'
              : `Waiting for ${Math.max(0, 10 - count)} more completed candle${10 - count === 1 ? '' : 's'}.`;
    }
    document.getElementById('harmonicFormationCardNote').textContent =
            `${endpoint.label} completed at ${Number(endpoint.price).toFixed(4)}. Hard rules passed; only tolerated soft-ratio deviations reduce the score.`;
    document.getElementById('harmonicFormationCardLink').href =
            `/stock/${encodedTicker}/harmonic-formations/${encodeURIComponent(currentInterval)}/${encodeURIComponent(formation.pattern)}/${encodeURIComponent(endpoint.timestamp)}`;
    const card = document.getElementById('harmonicFormationHoverCard');
    card.hidden = false;
    positionHarmonicFormationCard(overlay);
  }

  function positionHarmonicFormationCard(overlay) {
    const stage = document.getElementById('priceChartStage');
    const card = document.getElementById('harmonicFormationHoverCard');
    const endpoint = overlay.formation.points.at(-1);
    const anchor = {
      x: priceChart.timeScale().timeToCoordinate(timestampToChartDate(endpoint.timestamp)),
      y: overlay.series.priceToCoordinate(Number(endpoint.price))
    };
    const gap = 14;
    const x = Number.isFinite(anchor.x) ? anchor.x : stage.clientWidth / 2;
    const y = Number.isFinite(anchor.y) ? anchor.y : stage.clientHeight / 2;
    const left = x + gap + card.offsetWidth > stage.clientWidth ? x - card.offsetWidth - gap : x + gap;
    const top = y + gap + card.offsetHeight > stage.clientHeight ? y - card.offsetHeight - gap : y + gap;
    card.style.left = `${Math.max(8, Math.min(stage.clientWidth - card.offsetWidth - 8, left))}px`;
    card.style.top = `${Math.max(8, Math.min(stage.clientHeight - card.offsetHeight - 8, top))}px`;
  }

  function scheduleHarmonicFormationCardHide() {
    clearTimeout(harmonicCardHideTimer);
    harmonicCardHideTimer = setTimeout(() => {
      if (!harmonicCardPinned && !harmonicCardHovered) hideHarmonicFormationCard();
    }, 120);
  }

  function hideHarmonicFormationCard() {
    clearTimeout(harmonicCardHideTimer);
    harmonicCardPinned = false;
    harmonicCardHovered = false;
    harmonicFormationOverlays.forEach(item => item.series.applyOptions({ lineWidth: 2 }));
    const card = document.getElementById('harmonicFormationHoverCard');
    if (card) card.hidden = true;
  }

  async function toggleAnchoredVolumeProfile() {
    if (anchoredVolumeProfileEnabled) {
      disableAnchoredVolumeProfile();
      return;
    }
    if (globalCandleData.length === 0) return;

    setChartMode('candle');
    anchoredVolumeProfileEnabled = true;
    document.getElementById('priceChartStage')
            .classList.add('anchor-selection-active');
    applyAnchoredVolumeProfileChartSpacing();
    updateAnchoredVolumeProfileToggle();
    const latest = globalCandleData[globalCandleData.length - 1];
    showAnchoredVolumeProfileLoading(latest.timestamp);
    await requestAnchoredVolumeProfile(latest.timestamp);
  }

  function disableAnchoredVolumeProfile() {
    anchoredVolumeProfileEnabled = false;
    anchoredVolumeProfileRequestSequence++;
    anchoredVolumeProfile = null;
    anchoredVolumeProfileAnchorTimestamp = null;
    document.getElementById('priceChartStage')
            .classList.remove('anchor-selection-active');
    applyAnchoredVolumeProfileChartSpacing();
    document.getElementById('anchoredVolumeProfileSummary')
            .classList.remove('active');
    const status = document.getElementById('anchoredVolumeProfileStatus');
    status.textContent = '';
    status.classList.remove('error', 'locked');
    clearAnchoredVolumeProfileCanvas();
    updateAnchoredVolumeProfileToggle();
  }

  function updateAnchoredVolumeProfileToggle() {
    const button = document.getElementById('anchoredVolumeProfileToggle');
    const hasCandles = globalCandleData.length > 0;
    button.disabled = !hasCandles;
    button.classList.toggle('active', anchoredVolumeProfileEnabled && hasCandles);
    button.setAttribute(
            'aria-pressed',
            String(anchoredVolumeProfileEnabled && hasCandles));
    button.textContent = anchoredVolumeProfileEnabled
            ? 'Anchored volume profile: on'
            : 'Anchored volume profile';
  }

  function handleAnchoredVolumeProfileChartClick(parameter) {
    if (!anchoredVolumeProfileEnabled || !parameter?.time) return;
    const timeKey = chartTimeKey(parameter.time);
    const selected = globalCandleData.find(candle => candle.time === timeKey);
    if (!selected || !Number.isFinite(Number(selected.timestamp))) return;
    void requestAnchoredVolumeProfile(Number(selected.timestamp));
  }

  function chartTimeKey(time) {
    if (typeof time === 'string') return time;
    if (typeof time === 'number') {
      return new Date(time * 1000).toISOString().slice(0, 10);
    }
    if (time && Number.isInteger(time.year)
            && Number.isInteger(time.month)
            && Number.isInteger(time.day)) {
      return `${time.year}-${String(time.month).padStart(2, '0')}`
              + `-${String(time.day).padStart(2, '0')}`;
    }
    return '';
  }

  async function requestAnchoredVolumeProfile(anchorTimestamp) {
    if (!anchoredVolumeProfileEnabled
            || !Number.isFinite(Number(anchorTimestamp))) return;
    const requestedInterval = currentInterval;
    const requestedAnchor = Number(anchorTimestamp);
    const requestId = ++anchoredVolumeProfileRequestSequence;
    const status = document.getElementById('anchoredVolumeProfileStatus');
    status.classList.remove('error', 'locked');
    showAnchoredVolumeProfileLoading(requestedAnchor);

    try {
      const response = await fetch(
              `/api/stocks/${encodedTicker}/anchored-volume-profile`
              + `?interval=${encodeURIComponent(requestedInterval)}`
              + `&anchor=${encodeURIComponent(requestedAnchor)}`);
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(payload.error || 'Anchored volume profile request failed');
      }
      if (requestId !== anchoredVolumeProfileRequestSequence
              || !anchoredVolumeProfileEnabled
              || currentInterval !== requestedInterval) return;

      anchoredVolumeProfile = payload;
      anchoredVolumeProfileAnchorTimestamp = requestedAnchor;
      updateAnchoredVolumeProfileSummary(payload);
      scheduleAnchoredVolumeProfileRender();
    } catch (error) {
      if (requestId !== anchoredVolumeProfileRequestSequence) return;
      anchoredVolumeProfile = null;
      anchoredVolumeProfileAnchorTimestamp = null;
      clearAnchoredVolumeProfileCanvas();
      document.getElementById('anchoredVolumeProfileSummary')
              .classList.add('active');
      status.classList.add('error');
      status.textContent = error.message || 'Anchored volume profile unavailable.';
      console.warn('Anchored volume profile failed', error);
    }
  }

  function showAnchoredVolumeProfileLoading(anchorTimestamp) {
    document.getElementById('anchoredVolumeProfileSummary')
            .classList.add('active');
    document.getElementById('anchoredVolumeProfileRange').textContent =
            `Anchored ${formatProfileDate(anchorTimestamp)} · Loading profile…`;
    document.getElementById('anchoredVolumeProfilePoc').textContent = '—';
    document.getElementById('anchoredVolumeProfileVah').textContent = '—';
    document.getElementById('anchoredVolumeProfileVal').textContent = '—';
    const status = document.getElementById('anchoredVolumeProfileStatus');
    status.classList.remove('error', 'locked');
    status.textContent = 'Using cached market data where available.';
  }

  function updateAnchoredVolumeProfileSummary(profile) {
    const anchor = formatProfileDate(profile.anchorTimestamp);
    const end = formatProfileDate(profile.endTimestamp);
    const range = anchor === end ? `Anchored ${anchor}` : `${anchor} → ${end}`;
    const source = formatProfileCalculationInterval(profile.calculationInterval);
    const candleCount = `${profile.candlesIncluded} candle`
            + `${profile.candlesIncluded === 1 ? '' : 's'}`;
    document.getElementById('anchoredVolumeProfileSummary')
            .classList.add('active');
    document.getElementById('anchoredVolumeProfileRange').textContent =
            `${range} · ${candleCount} · ${source}`;
    document.getElementById('anchoredVolumeProfilePoc').textContent =
            formatProfilePrice(profile.pointOfControl);
    document.getElementById('anchoredVolumeProfileVah').textContent =
            formatProfilePrice(profile.valueAreaHigh);
    document.getElementById('anchoredVolumeProfileVal').textContent =
            formatProfilePrice(profile.valueAreaLow);

    const status = document.getElementById('anchoredVolumeProfileStatus');
    status.classList.toggle('locked', Boolean(profile.liveRefreshLocked));
    if (profile.liveRefreshLocked) {
      const unlock = profile.liveRefreshAvailableAt
              ? ` until ${formatProfileDateTime(profile.liveRefreshAvailableAt)}`
              : '';
      status.textContent = `Live updates paused${unlock}. The saved snapshot remains visible.`;
      return;
    }
    const updated = profile.liveDataRefreshedAt
            ? `Updated ${formatProfileDateTime(profile.liveDataRefreshedAt)}`
            : 'Using cached candles';
    status.textContent = `${updated} · ${profile.liveRefreshesRemaining} live refresh`
            + `${profile.liveRefreshesRemaining === 1 ? '' : 'es'} remaining`;
  }

  function formatProfileCalculationInterval(interval) {
    if (interval === '15min') return '15-minute source';
    if (interval === '1d+15min') return 'daily + active-day 15-minute source';
    if (interval === '1wk') return 'weekly source';
    if (interval === '1mo') return 'monthly source';
    return 'daily source';
  }

  function formatProfileDate(timestamp) {
    if (!timestamp) return 'latest';
    const date = new Date(Number(timestamp) * 1000);
    return new Intl.DateTimeFormat(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      timeZone: 'UTC'
    }).format(date);
  }

  function formatProfileDateTime(timestamp) {
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short'
    }).format(date);
  }

  function formatProfilePrice(price) {
    const numeric = Number(price);
    return Number.isFinite(numeric)
            ? `${numeric.toFixed(2)} ${stockCurrency}`
            : 'n/a';
  }

  function scheduleAnchoredVolumeProfileRender() {
    if (anchoredVolumeProfileRenderFrame !== null) {
      cancelAnimationFrame(anchoredVolumeProfileRenderFrame);
    }
    anchoredVolumeProfileRenderFrame = requestAnimationFrame(() => {
      anchoredVolumeProfileRenderFrame = null;
      renderAnchoredVolumeProfile();
    });
  }

  function applyAnchoredVolumeProfileChartSpacing() {
    requestAnimationFrame(() => {
      if (!priceChart) return;
      priceChart.timeScale().applyOptions({
        rightOffset: anchoredVolumeProfileEnabled
                ? ANCHORED_PROFILE_RIGHT_OFFSET_BARS
                : 0
      });
      if (anchoredVolumeProfileEnabled) {
        priceChart.timeScale().scrollToRealTime();
      }
      scheduleAnchoredVolumeProfileRender();
    });
  }

  function renderAnchoredVolumeProfile() {
    const canvas = document.getElementById('anchoredVolumeProfileCanvas');
    const container = document.getElementById('priceChartContainer');
    if (!anchoredVolumeProfileEnabled
            || !anchoredVolumeProfile
            || !priceChart
            || !candleSeries
            || !canvas
            || !container) {
      clearAnchoredVolumeProfileCanvas();
      return;
    }

    const width = container.clientWidth;
    const height = container.clientHeight;
    if (width <= 0 || height <= 0) return;
    const pixelRatio = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);

    const context = canvas.getContext('2d');
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    context.clearRect(0, 0, width, height);

    const profile = anchoredVolumeProfile;
    const lightTheme = document.documentElement.dataset.theme === 'light';
    let priceScaleWidth = 72;
    try {
      const measuredPriceScaleWidth = priceChart.priceScale('right').width();
      if (Number.isFinite(measuredPriceScaleWidth)) {
        priceScaleWidth = measuredPriceScaleWidth;
      }
    } catch (ignored) {
      // Lightweight Charts 4 exposes this in normal operation; keep a safe fallback.
    }
    const profileRight = Math.max(100, width - priceScaleWidth - 10);
    const preferredWidth = Math.max(150, Math.min(250, width * 0.19));
    const latestCoordinate = priceChart.timeScale().timeToCoordinate(
            globalCandleData[globalCandleData.length - 1]?.time);
    const candleSafeLeft = Number.isFinite(latestCoordinate)
            ? latestCoordinate + 20
            : profileRight - preferredWidth;
    const profileLeft = Math.max(
            20,
            profileRight - preferredWidth,
            candleSafeLeft);
    const maximumWidth = profileRight - profileLeft;
    if (maximumWidth < 24) {
      drawAnchoredProfileAnchor(context, height);
      return;
    }

    const profileBackground = context.createLinearGradient(
            profileLeft - 16, 0, profileRight, 0);
    profileBackground.addColorStop(
            0,
            lightTheme ? 'rgba(255, 255, 255, 0)' : 'rgba(10, 14, 11, 0)');
    profileBackground.addColorStop(
            1,
            lightTheme ? 'rgba(255, 255, 255, 0.58)' : 'rgba(10, 14, 11, 0.34)');
    context.fillStyle = profileBackground;
    context.fillRect(profileLeft - 16, 0, profileRight - profileLeft + 16, height);

    (profile.bins || []).forEach(bin => {
      const topCoordinate = candleSeries.priceToCoordinate(Number(bin.priceHigh));
      const bottomCoordinate = candleSeries.priceToCoordinate(Number(bin.priceLow));
      if (!Number.isFinite(topCoordinate) || !Number.isFinite(bottomCoordinate)) return;

      const top = Math.min(topCoordinate, bottomCoordinate);
      const barHeight = Math.max(1, Math.abs(bottomCoordinate - topCoordinate) - 0.5);
      const totalWidth = Math.max(
              0,
              Math.min(maximumWidth, Number(bin.relativeVolume || 0) * maximumWidth));
      if (totalWidth <= 0) return;
      const barLeft = profileRight - totalWidth;
      context.fillStyle = bin.pointOfControl
              ? (lightTheme ? 'rgba(167, 101, 8, 0.82)' : 'rgba(255, 189, 89, 0.9)')
              : (bin.inValueArea
                      ? (lightTheme ? 'rgba(8, 121, 93, 0.5)' : 'rgba(100, 232, 189, 0.62)')
                      : (lightTheme ? 'rgba(73, 91, 80, 0.24)' : 'rgba(132, 151, 139, 0.3)'));
      context.fillRect(barLeft, top, totalWidth, barHeight);

      if (bin.pointOfControl) {
        context.fillStyle = lightTheme
                ? 'rgba(167, 101, 8, 0.84)'
                : 'rgba(255, 220, 155, 0.95)';
        context.fillRect(profileLeft, top, profileRight - profileLeft, Math.max(1.5, barHeight));
      }
    });

    drawAnchoredProfileLevel(
            context,
            profileLeft,
            profileRight,
            profile.pointOfControl,
            lightTheme ? '#a76508' : '#ffbd59',
            false);
    drawAnchoredProfileLevel(
            context,
            profileLeft,
            profileRight,
            profile.valueAreaHigh,
            lightTheme ? '#5c861b' : '#b7f34a',
            true);
    drawAnchoredProfileLevel(
            context,
            profileLeft,
            profileRight,
            profile.valueAreaLow,
            lightTheme ? '#5c861b' : '#b7f34a',
            true);

    drawAnchoredProfileAnchor(context, height, lightTheme);
  }

  function drawAnchoredProfileLevel(
          context,
          profileLeft,
          profileRight,
          price,
          color,
          dashed) {
    const coordinate = candleSeries.priceToCoordinate(Number(price));
    if (!Number.isFinite(coordinate)) return;
    context.save();
    context.setLineDash(dashed ? [4, 4] : []);
    context.strokeStyle = color;
    context.globalAlpha = dashed ? 0.55 : 0.9;
    context.lineWidth = dashed ? 1 : 1.2;
    context.beginPath();
    context.moveTo(profileLeft, coordinate);
    context.lineTo(profileRight, coordinate);
    context.stroke();
    context.restore();
  }

  function drawAnchoredProfileAnchor(
          context,
          height,
          lightTheme = document.documentElement.dataset.theme === 'light') {
    const anchorCoordinate = priceChart.timeScale().timeToCoordinate(
            timestampToChartDate(anchoredVolumeProfile.anchorTimestamp));
    if (!Number.isFinite(anchorCoordinate) || anchorCoordinate < 0) return;
    context.save();
    context.setLineDash([4, 5]);
    context.strokeStyle = lightTheme
            ? 'rgba(92, 134, 27, 0.52)'
            : 'rgba(183, 243, 74, 0.48)';
    context.lineWidth = 1;
    context.beginPath();
    context.moveTo(anchorCoordinate, 24);
    context.lineTo(anchorCoordinate, height - 24);
    context.stroke();
    context.setLineDash([]);
    context.font = '600 10px Inter, sans-serif';
    const labelWidth = context.measureText('ANCHOR').width + 12;
    const labelLeft = Math.max(4, Math.min(
            anchorCoordinate + 6,
            document.getElementById('priceChartContainer').clientWidth - labelWidth - 6));
    context.fillStyle = lightTheme
            ? 'rgba(255, 255, 255, 0.94)'
            : 'rgba(18, 24, 20, 0.9)';
    context.fillRect(labelLeft, 9, labelWidth, 19);
    context.strokeStyle = lightTheme
            ? 'rgba(92, 134, 27, 0.34)'
            : 'rgba(183, 243, 74, 0.35)';
    context.strokeRect(labelLeft + 0.5, 9.5, labelWidth - 1, 18);
    context.fillStyle = lightTheme
            ? 'rgba(70, 107, 13, 0.95)'
            : 'rgba(207, 239, 156, 0.9)';
    context.fillText('ANCHOR', labelLeft + 6, 22);
    context.restore();
  }

  function clearAnchoredVolumeProfileCanvas() {
    const canvas = document.getElementById('anchoredVolumeProfileCanvas');
    if (!canvas) return;
    const context = canvas.getContext('2d');
    context.clearRect(0, 0, canvas.width, canvas.height);
  }

  function renderHistoricalElliottStructure(structure) {
    const groupId = structure.structureId || elliottStructureFallbackId(structure);
    historicalElliottGroups.set(groupId, { structure, segments: [] });
    const motivePoints = structure.points.slice(0, 6);
    renderHistoricalElliottSegment(
            structure,
            motivePoints,
            ELLIOTT_MOTIVE_COLOR,
            point => !isCorrectiveElliottPoint(point),
            'WAVE_V_END',
            groupId
    );
    if (structure.points.length > 6) {
      renderHistoricalElliottSegment(
              structure,
              structure.points.slice(5),
              ELLIOTT_CORRECTIVE_COLOR,
              isCorrectiveElliottPoint,
              'CORRECTION_END',
              groupId
      );
    }
  }

  function renderHistoricalElliottSegment(structure, points, color, includeMarker, stage, groupId) {
    if (points.length < 2) return;
    const series = priceChart.addLineSeries({
      color,
      lineWidth: 2,
      crosshairMarkerVisible: false,
      lastValueVisible: false,
      priceLineVisible: false
    });
    series.setData(points.map(point => ({
      time: timestampToChartDate(point.timestamp),
      value: point.price
    })));
    series.setMarkers(points
            .filter(point => point.label && includeMarker(point))
            .map(point => ({
              time: timestampToChartDate(point.timestamp),
              position: point.pivotType === 'HIGH' ? 'aboveBar' : 'belowBar',
              color: elliottPointWarningLabel(structure, point) ? '#ffbd59' : color,
              shape: 'circle',
              text: elliottPointMarkerText(structure, point)
            })));
    historicalElliottSeries.push(series);
    historicalElliottGroups.get(groupId)?.segments.push({
      structure,
      groupId,
      stage,
      points,
      color,
      series
    });
  }

  function isCorrectiveElliottPoint(point) {
    return ['A', 'B', 'C'].includes(String(point.label || '').toUpperCase());
  }

  function formatWaveTwoRetracement(retracement) {
    const percentage = Number(retracement) * 100;
    return Number.isFinite(percentage) ? `${percentage.toFixed(1)}%` : '';
  }

  function elliottQualityWarnings(structure) {
    if (Array.isArray(structure.qualityWarnings) && structure.qualityWarnings.length > 0) {
      return structure.qualityWarnings;
    }
    return structure.deepWaveTwo
            ? [`Deep Wave II ${formatWaveTwoRetracement(structure.waveTwoRetracement)} — reduced confidence`]
            : [];
  }

  function elliottPointWarningLabel(structure, point) {
    const label = point.label.toUpperCase();
    if (label === 'II' && (structure.waveTwoRetracement < 0.236 || structure.waveTwoRetracement > 0.786)) {
      return structure.waveTwoRetracement > 0.786 ? 'deep' : 'shallow';
    }
    if (label === 'IV' && (structure.waveFourRetracement < 0.146 || structure.waveFourRetracement > 0.618)) {
      return structure.waveFourRetracement > 0.618 ? 'deep' : 'shallow';
    }
    if (label === 'V' && structure.impulseVariant === 'TRUNCATED_FIFTH') return 'truncated';
    if (label === 'C' && structure.correctionVariant === 'EXPANDED_FLAT') return 'expanded flat';
    if (label === 'C' && structure.correctionVariant === 'RUNNING_FLAT') return 'running flat';
    return '';
  }

  function elliottPointMarkerText(structure, point) {
    const warningLabel = elliottPointWarningLabel(structure, point);
    return warningLabel ? `${point.label} · ${warningLabel}` : point.label;
  }

  function setElliottConfirmationMarkers(structures) {
    const confirmationsByTime = new Map();
    structures
            .filter(structure => structure.confirmationTimestamp)
            .forEach(structure => {
              const time = timestampToChartDate(structure.confirmationTimestamp);
              const candle = globalCandleData.find(item => item.time === time);
              if (!candle) return;
              const buyConfirmation = structure.correctionComplete
                      ? structure.direction === 'BULLISH'
                      : structure.direction === 'BEARISH';
              const endpointLabel = structure.correctionComplete
                      ? (currentInterval === '1wk' ? 'c' : 'C')
                      : (currentInterval === '1wk' ? 'v' : 'V');
              confirmationsByTime.set(time, {
                time,
                value: candle.close,
                marker: {
                  time,
                  position: buyConfirmation ? 'belowBar' : 'aboveBar',
                  color: structure.correctionComplete
                          ? ELLIOTT_CORRECTIVE_COLOR
                          : ELLIOTT_MOTIVE_COLOR,
                  shape: buyConfirmation ? 'arrowUp' : 'arrowDown',
                  text: `${endpointLabel} confirmed`
                }
              });
            });
    const confirmations = Array.from(confirmationsByTime.values())
            .sort((left, right) => left.time.localeCompare(right.time));
    elliottConfirmationSeries.setData(confirmations.map(item => ({ time: item.time, value: item.value })));
    elliottConfirmationSeries.setMarkers(confirmations.map(item => item.marker));
  }

  function elliottSignalCardKey(cycleKey, stage) {
    return `${cycleKey || ''}:${stage || ''}`;
  }

  function trackedElliottCard(segment) {
    return elliottSignalCards.get(elliottSignalCardKey(
            segment.structure.cycleKey,
            segment.stage
    )) || null;
  }

  function rebuildElliottHitTargets() {
    const targetLayer = document.getElementById('elliottWaveHitTargets');
    if (!targetLayer) return;
    targetLayer.replaceChildren();
    historicalElliottGroups.forEach(group => group.segments.forEach(segment => {
      const card = trackedElliottCard(segment);
      elliottInteractivePoints(segment).forEach(point => {
        const button = document.createElement('button');
        const pointLabel = String(point.label || 'origin').toUpperCase();
        button.type = 'button';
        button.className = 'elliott-wave-hit-target';
        button.setAttribute('aria-label', `${pointLabel} point, ${segment.structure.direction || ''} Elliott Wave, ${elliottStageLabel(segment.stage)}, ${card?.status || 'historical signal'}`);
        button.title = `Open ${elliottStageLabel(segment.stage)} details`;
        button._elliottSegment = segment;
        button._elliottPoint = point;
        button.addEventListener('click', event => {
          event.stopPropagation();
          showElliottWaveCard(segment, elliottPointCoordinate(segment, point), true);
        });
        targetLayer.appendChild(button);
      });
    }));
    scheduleElliottHitTargetPositioning();
  }

  function elliottInteractivePoints(segment) {
    if (segment.stage !== 'CORRECTION_END') return segment.points;
    return segment.points.filter(point => isCorrectiveElliottPoint(point));
  }

  function scheduleElliottHitTargetPositioning() {
    if (elliottHitTargetFrame !== null) return;
    elliottHitTargetFrame = requestAnimationFrame(() => {
      elliottHitTargetFrame = null;
      positionElliottHitTargets();
    });
  }

  function positionElliottHitTargets() {
    if (!priceChart) return;
    const stage = document.getElementById('priceChartStage');
    document.querySelectorAll('.elliott-wave-hit-target').forEach(button => {
      const segment = button._elliottSegment;
      const point = button._elliottPoint;
      const x = point
              ? priceChart.timeScale().timeToCoordinate(timestampToChartDate(point.timestamp))
              : null;
      const y = point ? segment.series.priceToCoordinate(Number(point.price)) : null;
      const visible = Number.isFinite(x) && Number.isFinite(y)
              && x >= 0 && x <= stage.clientWidth && y >= 0 && y <= stage.clientHeight;
      button.hidden = !visible;
      if (visible) {
        button.style.left = `${x}px`;
        button.style.top = `${y}px`;
      }
    });
  }

  function handleElliottWaveChartClick(param) {
    if (anchoredVolumeProfileEnabled || !param?.point || !elliottOverlaysEnabled) return;
    const leg = nearestElliottLeg(param.point);
    if (leg) {
      showElliottWaveCard(leg.segment, param.point, true);
    } else {
      hideElliottWaveCard();
    }
  }

  function nearestElliottLeg(point) {
    let nearest = null;
    let nearestDistance = 18;
    historicalElliottGroups.forEach(group => group.segments.forEach(segment => {
      for (let index = 1; index < segment.points.length; index++) {
        const startPoint = segment.points[index - 1];
        const endPoint = segment.points[index];
        const start = elliottPointCoordinate(segment, startPoint);
        const end = elliottPointCoordinate(segment, endPoint);
        if (!start || !end || !endPoint.label) continue;
        const distance = pointToLineSegmentDistance(point, start, end);
        if (distance < nearestDistance) {
          nearestDistance = distance;
          nearest = { segment, start: startPoint, end: endPoint, label: endPoint.label };
        }
      }
    }));
    return nearest;
  }

  function nearestElliottSegment(point) {
    let nearest = null;
    let nearestDistance = 18;
    historicalElliottGroups.forEach(group => group.segments.forEach(segment => {
      for (let index = 1; index < segment.points.length; index++) {
        const start = elliottPointCoordinate(segment, segment.points[index - 1]);
        const end = elliottPointCoordinate(segment, segment.points[index]);
        if (!start || !end) continue;
        const distance = pointToLineSegmentDistance(point, start, end);
        if (distance < nearestDistance) {
          nearestDistance = distance;
          nearest = segment;
        }
      }
    }));
    return nearest;
  }

  function elliottPointCoordinate(segment, point) {
    const x = priceChart.timeScale().timeToCoordinate(timestampToChartDate(point.timestamp));
    const y = segment.series.priceToCoordinate(Number(point.price));
    return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
  }

  function pointToLineSegmentDistance(point, start, end) {
    const dx = end.x - start.x;
    const dy = end.y - start.y;
    if (dx === 0 && dy === 0) return Math.hypot(point.x - start.x, point.y - start.y);
    const progress = Math.max(0, Math.min(1,
            ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)));
    return Math.hypot(point.x - (start.x + progress * dx), point.y - (start.y + progress * dy));
  }

  function showElliottWaveCard(segment, point, pinned) {
    hideHarmonicFormationCard();
    hideHistoricalCandlestickCard();
    clearTimeout(elliottCardHideTimer);
    elliottCardPinned = pinned;
    setActiveElliottSegment(segment);
    renderElliottWaveCard(segment);
    const card = document.getElementById('elliottWaveHoverCard');
    card.hidden = false;
    positionElliottWaveCard(segment, point);
  }

  function showElliottHierarchyWaveCard(wave, point) {
    hideHarmonicFormationCard();
    hideHistoricalCandlestickCard();
    clearTimeout(elliottCardHideTimer);
    elliottCardPinned = true;
    setActiveElliottSegment(null);
    activeElliottSegment = { hierarchyWave: wave };

    const direction = Number(wave.endPrice) >= Number(wave.startPrice) ? 'Bullish' : 'Bearish';
    const childCount = Array.isArray(wave.subwaves) ? wave.subwaves.length : 0;
    document.getElementById('elliottWaveCardEyebrow').textContent =
            `${String(wave.timeframe || '').toUpperCase()} · ${String(wave.nature || '').toUpperCase()}`;
    document.getElementById('elliottWaveCardTitle').textContent = `Wave ${wave.degreeLabel}`;
    document.getElementById('elliottWaveCardStage').textContent =
            `${direction} · ${formatHierarchyWavePeriod(wave.startTime)} → ${formatHierarchyWavePeriod(wave.endTime)}`;

    const status = document.getElementById('elliottWaveCardStatus');
    status.className = 'elliott-wave-card-status confirmed';
    status.textContent = 'Validated';
    document.getElementById('elliottWaveCardOutcome').hidden = true;
    const message = document.getElementById('elliottWaveCardMessage');
    message.hidden = false;
    message.textContent = `${Number(wave.startPrice).toFixed(2)} → ${Number(wave.endPrice).toFixed(2)}`
            + (childCount > 0 ? ` · ${childCount} validated lower-timeframe subwaves` : '');
    document.getElementById('elliottWaveCardNote').textContent =
            'Top-down hierarchy validated inside the parent time boundary using the Elliott retracement, wave 3, and overlap rules.';
    document.getElementById('elliottWaveCardLink').hidden = true;

    const card = document.getElementById('elliottWaveHoverCard');
    card.hidden = false;
    positionElliottWaveCardAt(point);
  }

  function formatHierarchyWavePeriod(timestamp) {
    if (!timestamp) return 'unavailable';
    return new Date(Number(timestamp) * 1000).toLocaleDateString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'
    });
  }

  function renderElliottWaveCard(segment) {
    const structure = segment.structure;
    const tracked = trackedElliottCard(segment);
    const cardData = tracked || historicalElliottCard(segment);
    const titleDirection = structure.direction === 'BEARISH' ? 'Bearish' : 'Bullish';
    document.getElementById('elliottWaveCardEyebrow').textContent = 'ELLIOTT WAVE';
    document.getElementById('elliottWaveCardTitle').textContent = `${titleDirection} structure`;
    document.getElementById('elliottWaveCardStage').textContent =
            `${cardData.stageLabel} · ${tradeSignalDisplayLabel(cardData.tradeSignal)} signal · ${cardData.signalPeriodLabel}`;

    const status = document.getElementById('elliottWaveCardStatus');
    status.className = `elliott-wave-card-status ${cardData.statusClass}`;
    status.textContent = formatElliottStatus(cardData.status);

    const outcome = document.getElementById('elliottWaveCardOutcome');
    const message = document.getElementById('elliottWaveCardMessage');
    const note = document.getElementById('elliottWaveCardNote');
    const link = document.getElementById('elliottWaveCardLink');
    link.hidden = !cardData.detailUrl;
    if (cardData.detailUrl) {
      link.href = cardData.detailUrl;
      link.textContent = tracked ? 'Open signal details' : 'Open historical signal details';
    }
    note.textContent = `${cardData.outcomeNote
            || 'Hypothetical hindsight using completed cached candle closes.'} Measured from the confirmation candle close on ${cardData.signalPeriodLabel}.`;
    if (cardData.outcomeAvailable) {
      outcome.hidden = false;
      message.hidden = true;
      document.getElementById('elliottWaveCardOutcomeLabel').textContent = cardData.outcomeLabel;
      const outcomeValue = document.getElementById('elliottWaveCardOutcomeValue');
      outcomeValue.textContent = formatSignedPercent(cardData.bestDirectionalReturnPercent);
      outcomeValue.classList.toggle('positive', Number(cardData.bestDirectionalReturnPercent) >= 0);
      outcomeValue.classList.toggle('negative', Number(cardData.bestDirectionalReturnPercent) < 0);
      document.getElementById('elliottWaveCardWindowEnd').textContent = `At candle 10: ${formatSignedPercent(cardData.windowEndDirectionalReturnPercent)}`;
    } else {
      outcome.hidden = true;
      message.hidden = false;
      message.textContent = cardData.unavailableReason
              || 'Ten completed candles are required before a result can be shown.';
    }
  }

  function historicalElliottCard(segment) {
    const structure = segment.structure;
    const tradeSignal = historicalElliottTradeSignal(structure.direction, segment.stage);
    const signalTimestamp = historicalElliottConfirmationTimestamp(segment);
    const signalIndex = signalTimestamp === null
            ? -1
            : globalCandleData.findIndex(candle => Number(candle.timestamp) === Number(signalTimestamp));
    const forwardCandles = signalIndex < 0
            ? []
            : globalCandleData.slice(signalIndex + 1, signalIndex + 11);
    const signalClose = signalIndex < 0 ? NaN : Number(globalCandleData[signalIndex].close);
    const outcomeAvailable = Number.isFinite(signalClose)
            && signalClose > 0
            && forwardCandles.length >= 10;
    let bestDirectionalReturnPercent = null;
    let windowEndDirectionalReturnPercent = null;
    if (outcomeAvailable) {
      const returns = forwardCandles.map(candle => {
        const rawReturn = (Number(candle.close) - signalClose) / signalClose * 100;
        return tradeSignal === 'SELL' ? -rawReturn : rawReturn;
      });
      bestDirectionalReturnPercent = Math.max(...returns);
      windowEndDirectionalReturnPercent = returns[9];
    }

    const availableForwardCandles = signalIndex < 0
            ? 0
            : Math.min(10, globalCandleData.length - signalIndex - 1);
    const endpoint = segment.points[segment.points.length - 1];
    const historicalDetailUrl = endpoint?.timestamp
            ? `/stock/${encodedTicker}/elliott-waves/${encodeURIComponent(currentInterval)}/${encodeURIComponent(segment.stage)}/${encodeURIComponent(endpoint.timestamp)}`
              + `?cycleKey=${encodeURIComponent(structure.cycleKey || '')}`
            : null;
    const unavailableReason = signalTimestamp === null
            ? 'A confirmation candle is not available for this historical wave stage.'
            : (signalIndex < 0
                    ? 'The historical confirmation candle is outside the candles currently loaded on this chart.'
                    : `Waiting for ${10 - availableForwardCandles} more completed candle${10 - availableForwardCandles === 1 ? '' : 's'}.`);
    return {
      stageLabel: elliottStageLabel(segment.stage),
      tradeSignal,
      status: signalTimestamp === null ? 'DETECTED' : 'CONFIRMED',
      statusClass: signalTimestamp === null ? 'detected' : 'confirmed',
      signalTimestamp,
      signalPeriodLabel: signalTimestamp === null
              ? formatHistoricalElliottPeriod(segment.points[segment.points.length - 1]?.timestamp)
              : formatHistoricalElliottPeriod(signalTimestamp),
      outcomeAvailable,
      bestDirectionalReturnPercent,
      windowEndDirectionalReturnPercent,
      outcomeLabel: tradeSignal === 'SELL'
              ? 'Largest close-based loss avoided'
              : 'Best close-based return',
      outcomeNote: 'Historical reconstruction · hypothetical hindsight using the first 10 completed cached candle closes.',
      unavailableReason,
      detailUrl: historicalDetailUrl
    };
  }

  function historicalElliottTradeSignal(direction, stage) {
    const bullishStructure = direction === 'BULLISH';
    if (stage === 'CORRECTION_END') return bullishStructure ? 'BUY' : 'SELL';
    return bullishStructure ? 'SELL' : 'BUY';
  }

  function historicalElliottConfirmationTimestamp(segment) {
    const structure = segment.structure;
    const structureStage = structure.correctionComplete ? 'CORRECTION_END' : 'WAVE_V_END';
    if (structureStage === segment.stage && structure.confirmationTimestamp) {
      return Number(structure.confirmationTimestamp);
    }

    const endpoint = segment.points[segment.points.length - 1];
    const endpointIndex = globalCandleData.findIndex(
            candle => Number(candle.timestamp) === Number(endpoint?.timestamp));
    if (endpointIndex < 0) return null;
    const bullishRebound = historicalElliottTradeSignal(structure.direction, segment.stage) === 'BUY';
    const lastCandidateIndex = Math.min(globalCandleData.length - 1, endpointIndex + 3);
    for (let index = endpointIndex + 1; index <= lastCandidateIndex; index++) {
      const current = globalCandleData[index];
      const previous = globalCandleData[index - 1];
      const confirmed = bullishRebound
              ? Number(current.close) > Number(previous.high)
              : Number(current.close) < Number(previous.low);
      if (confirmed) return Number(current.timestamp);
    }
    return null;
  }

  function formatHistoricalElliottPeriod(timestamp) {
    if (!timestamp) return 'unavailable';
    const date = new Date(Number(timestamp) * 1000);
    if (currentInterval === '1mo') {
      return date.toLocaleDateString(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' });
    }
    if (currentInterval === '1d') {
      return date.toLocaleDateString(undefined, {
        year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'
      });
    }
    return `week of ${date.toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      timeZone: 'UTC'
    })}`;
  }

  function elliottStageLabel(stage) {
    return stage === 'CORRECTION_END' ? 'ABC correction ending' : 'Wave V ending';
  }

  function formatElliottStatus(status) {
    const value = String(status || 'DETECTED').toLowerCase();
    return value.charAt(0).toUpperCase() + value.slice(1);
  }

  function formatSignedPercent(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return '—';
    return `${numeric > 0 ? '+' : ''}${numeric.toFixed(2)}%`;
  }

  function positionElliottWaveCard(segment, point) {
    const stage = document.getElementById('priceChartStage');
    const endpoint = segment.points[segment.points.length - 1];
    const anchor = point || elliottPointCoordinate(segment, endpoint) || {
      x: stage.clientWidth / 2,
      y: stage.clientHeight / 2
    };
    positionElliottWaveCardAt(anchor);
  }

  function positionElliottWaveCardAt(anchor) {
    const stage = document.getElementById('priceChartStage');
    const card = document.getElementById('elliottWaveHoverCard');
    const resolvedAnchor = anchor || { x: stage.clientWidth / 2, y: stage.clientHeight / 2 };
    const gap = 14;
    const maxLeft = Math.max(8, stage.clientWidth - card.offsetWidth - 8);
    const maxTop = Math.max(8, stage.clientHeight - card.offsetHeight - 8);
    const preferredLeft = resolvedAnchor.x + gap + card.offsetWidth > stage.clientWidth
            ? resolvedAnchor.x - card.offsetWidth - gap
            : resolvedAnchor.x + gap;
    const preferredTop = resolvedAnchor.y + gap + card.offsetHeight > stage.clientHeight
            ? resolvedAnchor.y - card.offsetHeight - gap
            : resolvedAnchor.y + gap;
    card.style.left = `${Math.max(8, Math.min(maxLeft, preferredLeft))}px`;
    card.style.top = `${Math.max(8, Math.min(maxTop, preferredTop))}px`;
  }

  function setActiveElliottSegment(segment) {
    if (activeElliottSegment?.groupId === segment?.groupId) {
      activeElliottSegment = segment;
      return;
    }
    historicalElliottGroups.forEach(group => group.segments.forEach(item => {
      item.series.applyOptions({ lineWidth: item.groupId === segment?.groupId ? 4 : 2 });
    }));
    activeElliottSegment = segment;
  }

  function scheduleElliottCardHide() {
    clearTimeout(elliottCardHideTimer);
    elliottCardHideTimer = setTimeout(() => {
      if (!elliottCardPinned && !elliottCardHovered) hideElliottWaveCard();
    }, 120);
  }

  function hideElliottWaveCard() {
    clearTimeout(elliottCardHideTimer);
    elliottCardPinned = false;
    elliottCardHovered = false;
    historicalElliottGroups.forEach(group => group.segments.forEach(item => {
      item.series.applyOptions({ lineWidth: 2 });
    }));
    activeElliottSegment = null;
    const card = document.getElementById('elliottWaveHoverCard');
    if (card) card.hidden = true;
  }

  function clearHistoricalElliottSeries() {
    if (!priceChart) return;
    historicalElliottSeries.forEach(series => priceChart.removeSeries(series));
    historicalElliottSeries = [];
    historicalElliottGroups = new Map();
    const targetLayer = document.getElementById('elliottWaveHitTargets');
    if (targetLayer) targetLayer.replaceChildren();
  }

  function timestampToChartDate(timestamp) {
    const date = new Date(timestamp * 1000);
    const year = date.getUTCFullYear();
    const month = String(date.getUTCMonth() + 1).padStart(2, '0');
    const day = String(date.getUTCDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  function updatePriceHeaderFromLatestCandle() {
    if (!latestCandle) return;

    document.getElementById('livePriceDisplay').innerText = `${latestCandle.close.toFixed(2)} ${stockCurrency}`;

    const previous = globalCandleData.length > 1 ? globalCandleData[globalCandleData.length - 2].close : latestCandle.open;
    const changePercent = previous > 0 ? ((latestCandle.close - previous) / previous) * 100 : 0;
    const changeEl = document.getElementById('liveChangeDisplay');
    changeEl.innerText = `${changePercent > 0 ? '+' : ''}${changePercent.toFixed(2)}%`;
    changeEl.className = 'price-change ' + (changePercent >= 0 ? 'positive' : 'negative');
  }

  async function changeInterval(newInterval) {
    if (currentInterval === newInterval) return;
    currentInterval = newInterval;
    fibonacciDrawingTool?.setContext(`stock:${ticker}:${newInterval}`);

    document.getElementById('int-1d').classList.remove('active');
    document.getElementById('int-1wk').classList.remove('active');
    document.getElementById('int-1mo').classList.remove('active');
    document.getElementById(`int-${newInterval}`).classList.add('active');

    await loadChartData(newInterval);
  }

  function setChartMode(mode) {
    if (mode === 'line' && anchoredVolumeProfileEnabled) {
      disableAnchoredVolumeProfile();
    }
    currentMode = mode;
    document.getElementById('lineBtn').classList.toggle('active', mode === 'line');
    document.getElementById('candleBtn').classList.toggle('active', mode === 'candle');

    if (mode === 'line') {
      lineSeries.applyOptions({ visible: true });
      candleSeries.applyOptions({ visible: false });
    } else {
      lineSeries.applyOptions({ visible: false });
      candleSeries.applyOptions({ visible: true });
    }
  }

  async function fetchLivePrice() {
    try {
      const response = await fetch(`/api/stocks/${encodedTicker}/live`);
      const update = await response.json();

      if (update && update.price !== undefined) {
        document.getElementById('livePriceDisplay').innerText = `${update.price.toFixed(2)} ${stockCurrency}`;

        const changeEl = document.getElementById('liveChangeDisplay');
        changeEl.innerText = `${update.changePercent > 0 ? '+' : ''}${update.changePercent.toFixed(2)}%`;
        changeEl.className = 'price-change ' + (update.changePercent >= 0 ? 'positive' : 'negative');

        if (latestCandle) {
          const newPrice = update.price;
          latestCandle.close = newPrice;
          if (newPrice > latestCandle.high) latestCandle.high = newPrice;
          if (newPrice < latestCandle.low) latestCandle.low = newPrice;

          candleSeries.update({
            time: latestCandle.time,
            open: latestCandle.open,
            high: latestCandle.high,
            low: latestCandle.low,
            close: latestCandle.close
          });
          lineSeries.update({ time: latestCandle.time, value: newPrice });
          const latestLoadedCandle = globalCandleData[globalCandleData.length - 1];
          if (latestLoadedCandle?.time === latestCandle.time) {
            latestLoadedCandle.close = latestCandle.close;
            latestLoadedCandle.high = latestCandle.high;
            latestLoadedCandle.low = latestCandle.low;
            const latestLoadedLine = globalLineData[globalLineData.length - 1];
            if (latestLoadedLine?.time === latestCandle.time) {
              latestLoadedLine.value = latestCandle.close;
            }
            updateRsiOverlay();
          }
        }
      }
    } catch (err) {
      console.warn("Live fetch failed", err);
    }
  }

  async function loadAlertState() {
    const status = document.getElementById('alertStatus');
    try {
      const response = await fetch(`/api/alerts/${encodedTicker}`);
      if (!response.ok) throw new Error("Alert state request failed");
      const state = await response.json();
      hydrateAlertDraft(state);

    } catch (err) {
      alertStateLoaded = false;
      updateAlertDraftUi();
      updateTechnicalFollowAllButton();
      status.innerText = 'Alerts unavailable';
      console.warn("Alert state failed", err);
    }
  }

  function outlookFollowInputs() {
    return Array.from(document.querySelectorAll('[data-outlook-follow-interval]'));
  }

  function setOutlookFollowInputsDisabled(disabled) {
    outlookFollowInputs().forEach(input => {
      input.disabled = disabled;
    });
  }

  function hydrateOutlookFollowState(state) {
    outlookFollowInputs().forEach(input => {
      input.checked = Boolean(state.intervals?.[input.dataset.outlookFollowInterval]);
      input.dataset.persisted = String(input.checked);
      if (!input.dataset.listenerBound) {
        input.addEventListener('change', () => updateOutlookFollow(input));
        input.dataset.listenerBound = 'true';
      }
    });
    outlookFollowStateLoaded = true;
    updateTechnicalFollowAllButton();
  }

  async function loadOutlookFollowState() {
    const status = document.getElementById('outlookFollowStatus');
    try {
      const response = await fetch(`/api/stocks/${encodedTicker}/technical-outlook/subscriptions`);
      if (!response.ok) throw new Error('Outlook follow state request failed');
      const state = await response.json();
      hydrateOutlookFollowState(state);
      setOutlookFollowInputsDisabled(technicalFollowAllBusy);
      status.textContent = 'Outlook monitoring ready';
    } catch (error) {
      outlookFollowStateLoaded = false;
      status.textContent = 'Outlook monitoring unavailable';
      outlookFollowInputs().forEach(input => input.disabled = true);
      updateTechnicalFollowAllButton();
      console.warn('Outlook follow state failed', error);
    }
  }

  async function updateOutlookFollow(changedInput) {
    const status = document.getElementById('outlookFollowStatus');
    const previous = changedInput.dataset.persisted === 'true';
    outlookFollowSaveInProgress = true;
    setOutlookFollowInputsDisabled(true);
    updateTechnicalFollowAllButton();
    status.textContent = changedInput.checked
            ? `Establishing ${changedInput.dataset.outlookFollowInterval.toLowerCase()} baseline…`
            : 'Stopping this outlook interval…';
    try {
      const response = await fetch(`/api/stocks/${encodedTicker}/technical-outlook/subscriptions`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({
          interval: changedInput.dataset.outlookFollowInterval,
          active: changedInput.checked
        })
      });
      const state = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(state.error || 'Outlook follow could not be updated.');
      hydrateOutlookFollowState(state);
      status.textContent = changedInput.checked
              ? 'Baseline saved. Future classification changes will be reported.'
              : 'Outlook interval is no longer followed.';
    } catch (error) {
      changedInput.checked = previous;
      status.textContent = error.message || 'Outlook follow could not be updated.';
    } finally {
      outlookFollowSaveInProgress = false;
      setOutlookFollowInputsDisabled(technicalFollowAllBusy || !outlookFollowStateLoaded);
      updateTechnicalFollowAllButton();
    }
  }

  async function configureCongressionalActivity(instrumentType) {
    const panel = document.getElementById('congressionalActivityPanel');
    congressionalActivityEligible = instrumentType === 'EQUITY';
    panel.hidden = !congressionalActivityEligible;
    if (!congressionalActivityEligible) return;
    await loadCongressionalActivityState();
  }

  function updateTickerAlertsEligibility(instrumentType) {
    const isEligible = instrumentType === 'EQUITY';
    const notice = document.getElementById('tickerAlertsEligibilityNotice');
    notice.hidden = isEligible;
    document.getElementById('tickerAlertsWorkspaceTab').dataset.instrumentEligible = String(isEligible);
  }

  async function loadCongressionalActivityState() {
    const status = document.getElementById('congressionalActivityStatus');
    setCongressionalControlsDisabled(true);
    try {
      const response = await fetch(`/api/congressional-activity/${encodedTicker}/state`);
      const state = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(state.error || 'Congressional activity is unavailable');
      applyCongressionalActivityState(state);
    } catch (error) {
      status.textContent = error.message || 'Congressional activity is unavailable';
      console.warn('Congressional activity state failed', error);
    } finally {
      setCongressionalControlsDisabled(false);
    }
  }

  function applyCongressionalActivityState(state) {
    congressionalFollowing = Boolean(state.following);
    const followButton = document.getElementById('followCongressionalActivityBtn');
    followButton.setAttribute('aria-pressed', String(congressionalFollowing));
    followButton.classList.toggle('following', congressionalFollowing);
    followButton.textContent = congressionalFollowing
            ? 'Stop following activity'
            : 'Follow congressional activity';
    const status = document.getElementById('congressionalActivityStatus');
    status.textContent = state.baselinePending
            ? 'Following is active · Preparing a no-alert historical baseline'
            : `${state.followedStocks}/${state.maximumFollowedStocks} congressional stocks followed`;
    if (state.relevanceNotice) {
      document.getElementById('congressionalRelevanceNotice').textContent = state.relevanceNotice;
    }
    if (state.alertBaselineNotice) {
      document.getElementById('congressionalBaselineNotice').textContent = state.alertBaselineNotice;
    }
  }

  function setCongressionalControlsDisabled(disabled) {
    congressionalActivityBusy = disabled;
    document.getElementById('followCongressionalActivityBtn').disabled = disabled;
    document.getElementById('showCongressionalHistoryBtn').disabled = disabled;
  }

  async function configureInsiderActivity(instrumentType) {
    const panel = document.getElementById('insiderActivityPanel');
    insiderActivityEligible = instrumentType === 'EQUITY';
    panel.hidden = !insiderActivityEligible;
    if (!insiderActivityEligible) return;
    await loadInsiderActivityState();
  }

  async function loadInsiderActivityState() {
    const status = document.getElementById('insiderActivityStatus');
    setInsiderControlsDisabled(true);
    try {
      const response = await fetch(`/api/insider-activity/${encodedTicker}/state`);
      const state = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(state.error || 'Insider activity is unavailable');
      applyInsiderActivityState(state);
    } catch (error) {
      status.textContent = error.message || 'Insider activity is unavailable';
      console.warn('Insider activity state failed', error);
    } finally {
      setInsiderControlsDisabled(false);
    }
  }

  function applyInsiderActivityState(state) {
    insiderFollowing = Boolean(state.following);
    const followButton = document.getElementById('followInsiderActivityBtn');
    followButton.setAttribute('aria-pressed', String(insiderFollowing));
    followButton.classList.toggle('following', insiderFollowing);
    followButton.textContent = insiderFollowing
            ? 'Stop following insider activity'
            : 'Follow insider activity';
    document.getElementById('insiderActivityStatus').textContent = state.baselinePending
            ? 'Following is active · Preparing a no-alert filing baseline'
            : `${state.followedStocks}/${state.maximumFollowedStocks} insider stocks followed`;
  }

  function showHistoricalCandlestickViewPicker() {
    const resultsDialog = document.getElementById('historicalCandlestickResultsDialog');
    if (resultsDialog.open) resultsDialog.close();
    const lookbackDialog = document.getElementById('historicalCandlestickLookbackDialog');
    if (lookbackDialog.open) lookbackDialog.close();
    const intervalDialog = document.getElementById('historicalCandlestickIntervalDialog');
    if (intervalDialog.open) intervalDialog.close();
    const viewDialog = document.getElementById('historicalCandlestickViewDialog');
    if (!viewDialog.open) viewDialog.showModal();
  }

  function chooseHistoricalCandlestickView(view) {
    const viewDialog = document.getElementById('historicalCandlestickViewDialog');
    if (viewDialog.open) viewDialog.close();
    if (view === 'list') {
      disableHistoricalCandlestickOverlay();
      showHistoricalCandlestickIntervalPicker();
      return;
    }
    if (view === 'graphical') {
      void enableHistoricalCandlestickOverlay();
    }
  }

  async function enableHistoricalCandlestickOverlay() {
    historicalCandlestickOverlayEnabled = true;
    setChartMode('candle');
    updateHistoricalCandlestickOverlayToggle();
    await refreshHistoricalCandlestickOverlay(currentInterval);
    document.getElementById('candlestickOverlayToggle').focus({ preventScroll: true });
  }

  function disableHistoricalCandlestickOverlay() {
    historicalCandlestickOverlayEnabled = false;
    historicalCandlestickOverlayRequestSequence++;
    historicalCandlestickOverlayHistory = null;
    historicalCandlestickOverlaySignals = [];
    clearHistoricalCandlestickOverlayVisuals();
    document.getElementById('candlestickOverlayStatus').textContent = '';
    updateHistoricalCandlestickOverlayToggle();
  }

  function updateHistoricalCandlestickOverlayToggle() {
    const button = document.getElementById('candlestickOverlayToggle');
    button.classList.toggle('active', historicalCandlestickOverlayEnabled);
    button.setAttribute('aria-pressed', String(historicalCandlestickOverlayEnabled));
    button.textContent = historicalCandlestickOverlayEnabled
            ? 'Candlestick signals: on'
            : 'Candlestick signals';
  }

  async function refreshHistoricalCandlestickOverlay(interval) {
    if (!historicalCandlestickOverlayEnabled) return;
    const requestId = ++historicalCandlestickOverlayRequestSequence;
    const status = document.getElementById('candlestickOverlayStatus');
    historicalCandlestickOverlayHistory = null;
    historicalCandlestickOverlaySignals = [];
    clearHistoricalCandlestickOverlayVisuals();
    status.classList.remove('error', 'warning');
    status.textContent = `Calculating every ${historicalCandlestickIntervalLabel(interval).toLowerCase()} pattern in the stored archive...`;
    try {
      const response = await fetch(
              `/api/stocks/${encodedTicker}/candlestick-patterns/history?interval=${encodeURIComponent(interval)}`
              + '&fullHistory=true',
              { cache: 'no-store', headers: {'Cache-Control': 'no-cache'} }
      );
      const history = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(history.error || 'Historical candlestick overlay could not be calculated');
      }
      if (requestId !== historicalCandlestickOverlayRequestSequence
              || !historicalCandlestickOverlayEnabled
              || currentInterval !== interval) return;
      historicalCandlestickOverlayHistory = history;
      historicalCandlestickOverlaySignals = Array.isArray(history.signals) ? history.signals : [];
      rebuildHistoricalCandlestickHitTargets();
      updateHistoricalCandlestickOverlayStatus();
    } catch (error) {
      if (requestId !== historicalCandlestickOverlayRequestSequence) return;
      status.classList.add('error');
      status.textContent = error.message || 'Historical candlestick overlay could not be calculated';
      console.warn('Historical candlestick overlay failed', error);
    }
  }

  function historicalCandlestickIntervalLabel(interval) {
    return {'1d': 'Daily', '1wk': 'Weekly', '1mo': 'Monthly'}[interval] || 'Historical';
  }

  function updateHistoricalCandlestickOverlayStatus() {
    if (!historicalCandlestickOverlayEnabled || !historicalCandlestickOverlayHistory) return;
    const loadedTimestamps = new Set(globalCandleData.map(candle => Number(candle.timestamp)));
    const loadedSignalCount = historicalCandlestickOverlaySignals
            .filter(signal => loadedTimestamps.has(Number(signal.signalTimestamp))).length;
    const total = historicalCandlestickOverlaySignals.length;
    const archiveCandles = Number(historicalCandlestickOverlayHistory.completedCandlesLoaded || 0);
    const olderNotice = loadedSignalCount < total
            ? ` ${loadedSignalCount} are currently inside the loaded chart; scroll left to reveal older candles.`
            : ' Every signal is currently inside the loaded chart.';
    document.getElementById('candlestickOverlayStatus').textContent = total === 0
            ? `No valid ${historicalCandlestickIntervalLabel(currentInterval).toLowerCase()} candlestick signals were found across ${archiveCandles} completed candles.`
            : `Showing ${total} historical signal${total === 1 ? '' : 's'} across all ${archiveCandles} completed candles.${olderNotice}`;
  }

  function clearHistoricalCandlestickOverlayVisuals() {
    const targetLayer = document.getElementById('historicalCandlestickHitTargets');
    if (targetLayer) targetLayer.replaceChildren();
    hideHistoricalCandlestickCard();
    historicalCandlestickTrendSeries?.setData([]);
    historicalCandlestickFormationSeries?.setData([]);
  }

  function rebuildHistoricalCandlestickHitTargets() {
    const targetLayer = document.getElementById('historicalCandlestickHitTargets');
    if (!targetLayer) return;
    targetLayer.replaceChildren();
    if (!historicalCandlestickOverlayEnabled) return;
    const loadedTimestamps = new Set(globalCandleData.map(candle => Number(candle.timestamp)));
    historicalCandlestickOverlaySignals
            .filter(signal => loadedTimestamps.has(Number(signal.signalTimestamp)))
            .forEach(signal => {
              const button = document.createElement('button');
              const direction = String(signal.tradeSignal || '').toLowerCase();
              button.type = 'button';
              button.className = `historical-candlestick-hit-target ${direction}`;
              button.textContent = direction === 'sell' ? 'S/S' : 'B';
              button._historicalCandlestickSignal = signal;
              button._historicalCandlestickCluster = [signal];
              button.setAttribute('aria-label', `${signal.patternLabel}, ${signal.typeLabel}, ${signal.signalPeriodLabel}`);
              button.addEventListener('mouseenter', () => showHistoricalCandlestickCardFromTarget(button, false));
              button.addEventListener('mouseleave', () => {
                if (!historicalCandlestickCardPinned) scheduleHistoricalCandlestickCardHide();
              });
              button.addEventListener('focus', () => showHistoricalCandlestickCardFromTarget(button, false));
              button.addEventListener('blur', () => {
                if (!historicalCandlestickCardPinned) scheduleHistoricalCandlestickCardHide();
              });
              button.addEventListener('click', event => {
                event.stopPropagation();
                showHistoricalCandlestickCardFromTarget(button, true);
              });
              targetLayer.append(button);
            });
    scheduleHistoricalCandlestickHitTargetPositioning();
    updateHistoricalCandlestickOverlayStatus();
  }

  function scheduleHistoricalCandlestickHitTargetPositioning() {
    if (historicalCandlestickHitTargetFrame !== null) return;
    historicalCandlestickHitTargetFrame = requestAnimationFrame(() => {
      historicalCandlestickHitTargetFrame = null;
      positionHistoricalCandlestickHitTargets();
    });
  }

  function positionHistoricalCandlestickHitTargets() {
    if (!priceChart || !candleSeries || !historicalCandlestickOverlayEnabled) return;
    const stage = document.getElementById('priceChartStage');
    const candleByTimestamp = new Map(globalCandleData.map(candle => [Number(candle.timestamp), candle]));
    const candidates = [];
    document.querySelectorAll('.historical-candlestick-hit-target').forEach(button => {
      button.hidden = true;
      const signal = button._historicalCandlestickSignal;
      const candle = candleByTimestamp.get(Number(signal?.signalTimestamp));
      if (!signal || !candle) return;
      const x = priceChart.timeScale().timeToCoordinate(candle.time);
      const buy = String(signal.tradeSignal).toUpperCase() === 'BUY';
      const candlePrice = buy ? Number(candle.low) : Number(candle.high);
      const candleY = candleSeries.priceToCoordinate(candlePrice);
      const y = Number(candleY) + (buy ? 22 : -22);
      if (!Number.isFinite(x) || !Number.isFinite(y)
              || x < 0 || x > stage.clientWidth || y < 0 || y > stage.clientHeight) return;
      candidates.push({button, signal, x, y, buy});
    });
    candidates.sort((left, right) => left.x - right.x || left.y - right.y);
    const clusters = [];
    for (const candidate of candidates) {
      const previous = clusters[clusters.length - 1];
      const joinsPrevious = previous
              && previous.buy === candidate.buy
              && Math.abs(candidate.x - previous.lastX) < 28;
      if (joinsPrevious) {
        previous.items.push(candidate);
        previous.lastX = candidate.x;
      } else {
        clusters.push({buy: candidate.buy, lastX: candidate.x, items: [candidate]});
      }
    }
    clusters.forEach(cluster => {
      const representative = cluster.items[0];
      const signals = cluster.items.map(item => item.signal);
      const averageX = cluster.items.reduce((sum, item) => sum + item.x, 0) / cluster.items.length;
      const edgeY = cluster.buy
              ? Math.max(...cluster.items.map(item => item.y))
              : Math.min(...cluster.items.map(item => item.y));
      representative.button.hidden = false;
      representative.button._historicalCandlestickCluster = signals;
      representative.button.textContent = signals.length > 1
              ? String(signals.length)
              : (cluster.buy ? 'B' : 'S');
      representative.button.classList.toggle('cluster', signals.length > 1);
      representative.button.title = signals.length > 1
              ? `${signals.length} nearby ${cluster.buy ? 'buy' : 'sell/short'} signals - open and cycle through them`
              : `Open ${signals[0].patternLabel} details`;
      representative.button.style.left = `${averageX}px`;
      representative.button.style.top = `${edgeY}px`;
    });
  }

  function showHistoricalCandlestickCardFromTarget(button, pinned) {
    const signals = button._historicalCandlestickCluster || [button._historicalCandlestickSignal];
    const point = {
      x: Number.parseFloat(button.style.left),
      y: Number.parseFloat(button.style.top)
    };
    showHistoricalCandlestickCard(signals, 0, point, pinned);
  }

  function showHistoricalCandlestickCard(signals, index, point, pinned) {
    if (!signals?.length) return;
    hideHarmonicFormationCard();
    hideElliottWaveCard();
    clearTimeout(historicalCandlestickCardHideTimer);
    historicalCandlestickCardPinned = pinned;
    activeHistoricalCandlestickSignals = signals;
    activeHistoricalCandlestickSignalIndex = Math.max(0, Math.min(signals.length - 1, index));
    renderActiveHistoricalCandlestickCard();
    const card = document.getElementById('historicalCandlestickHoverCard');
    card.hidden = false;
    positionHistoricalCandlestickCard(point || historicalCandlestickSignalCoordinate(
            activeHistoricalCandlestickSignals[activeHistoricalCandlestickSignalIndex]));
  }

  function renderActiveHistoricalCandlestickCard() {
    const signal = activeHistoricalCandlestickSignals[activeHistoricalCandlestickSignalIndex];
    if (!signal) return;
    document.getElementById('historicalCandlestickCardTitle').textContent = signal.patternLabel || signal.pattern;
    document.getElementById('historicalCandlestickCardStage').textContent =
            `${signal.signalPeriodLabel} - ${signal.typeLabel || tradeSignalDisplayLabel(signal.tradeSignal)} - ${signal.formationLabel}`;
    const status = document.getElementById('historicalCandlestickCardStatus');
    status.className = `elliott-wave-card-status ${signal.statusClass || 'pending'}`;
    status.textContent = signal.statusLabel || 'Awaiting outcome';
    const outcome = historicalCandlestickCardOutcome(signal);
    const outcomeValue = document.getElementById('historicalCandlestickCardOutcomeValue');
    outcomeValue.textContent = outcome.value;
    outcomeValue.className = outcome.valueClass;
    document.getElementById('historicalCandlestickCardOutcomeLabel').textContent = outcome.label;
    document.getElementById('historicalCandlestickCardOutcomeWindow').textContent = outcome.detail;
    document.getElementById('historicalCandlestickCardTrend').textContent =
            `${signal.trendLabel || (String(signal.tradeSignal).toUpperCase() === 'BUY' ? 'Required downtrend' : 'Required uptrend')} before the ${signal.formationLabel || 'pattern formation'}.`;
    document.getElementById('historicalCandlestickCardNote').textContent =
            signal.outcomeSummary || 'The trade outcome is calculated from completed cached candle closes.';
    document.getElementById('historicalCandlestickCardLink').href =
            historicalCandlestickDetailUrl(signal, null, true);
    const navigation = document.getElementById('historicalCandlestickCardClusterNavigation');
    navigation.hidden = activeHistoricalCandlestickSignals.length <= 1;
    document.getElementById('historicalCandlestickCardClusterPosition').textContent =
            `${activeHistoricalCandlestickSignalIndex + 1} of ${activeHistoricalCandlestickSignals.length} nearby signals`;
    highlightHistoricalCandlestickSignal(signal);
  }

  function historicalCandlestickCardOutcome(signal) {
    const lifecycleStatus = String(signal.status || signal.statusLabel || '').toUpperCase();
    const entry = optionalFiniteNumber(signal.tradeEntryPrice);
    const stop = optionalFiniteNumber(signal.stopLossPrice);
    const target = optionalFiniteNumber(signal.profitTargetPrice);
    const exit = optionalFiniteNumber(signal.tradeExitPrice ?? signal.evaluationClose);
    const targetReturn = historicalCandlestickDirectionalReturn(signal, target);
    const stopReturn = historicalCandlestickDirectionalReturn(signal, stop);
    const recordedReturn = optionalFiniteNumber(signal.tradeReturnPercent)
            ?? historicalCandlestickDirectionalReturn(signal, exit);
    const configuredTimeStop = optionalFiniteNumber(signal.timeStopCandles);
    const configuredRewardRisk = optionalFiniteNumber(signal.rewardRiskRatio);
    const timeStopCandles = configuredTimeStop ?? 8;
    const rewardRiskRatio = configuredRewardRisk == null
            ? null
            : configuredRewardRisk.toFixed(0);
    const entryLabel = entry == null ? 'n/a' : formatProfilePrice(entry);

    if (lifecycleStatus === 'CONFIRMED') {
      return {
        label: 'Return at sell target',
        value: targetReturn == null ? 'Target reached' : formatSignedPercent(targetReturn),
        valueClass: targetReturn == null ? 'positive' : candlestickReturnClass(targetReturn),
        detail: `Sold at ${target == null ? 'the profit target' : formatProfilePrice(target)} from entry ${entryLabel}${rewardRiskRatio == null ? '' : ` - 1:${rewardRiskRatio} R:R`}`
      };
    }
    if (lifecycleStatus === 'INVALIDATED') {
      const stopPriceLabel = stop == null ? 'Stop reached' : formatProfilePrice(stop);
      return {
        label: 'Stop loss reached',
        value: stopReturn == null ? stopPriceLabel : `${stopPriceLabel} (${formatSignedPercent(stopReturn)})`,
        valueClass: 'negative',
        detail: `Trade closed at the configured stop loss from entry ${entryLabel}`
      };
    }
    if (lifecycleStatus === 'EXPIRED') {
      return {
        label: `Return at candle ${timeStopCandles} time stop`,
        value: recordedReturn == null ? 'Unavailable' : formatSignedPercent(recordedReturn),
        valueClass: recordedReturn == null ? '' : candlestickReturnClass(recordedReturn),
        detail: `Trade closed at ${exit == null ? 'the completed time-stop close' : formatProfilePrice(exit)}${signal.evaluationPeriodLabel ? ` on ${signal.evaluationPeriodLabel}` : ''}`
      };
    }
    if (lifecycleStatus === 'DETECTED') {
      return {
        label: 'Potential return at sell target',
        value: targetReturn == null ? 'Target pending' : formatSignedPercent(targetReturn),
        valueClass: targetReturn == null ? '' : candlestickReturnClass(targetReturn),
        detail: `Entry ${entryLabel} - target ${target == null ? 'n/a' : formatProfilePrice(target)} - stop ${stop == null ? 'n/a' : formatProfilePrice(stop)}${rewardRiskRatio == null ? '' : ` - 1:${rewardRiskRatio} R:R`} - candle ${timeStopCandles} time stop`
      };
    }
    if (lifecycleStatus === 'REJECTED') {
      return {
        label: 'Rejected candidate',
        value: 'No trade',
        valueClass: '',
        detail: 'The mandatory next-candle gate failed, so no entry, stop, or return applies.'
      };
    }
    return {
      label: 'Potential candidate',
      value: 'Not opened',
      valueClass: '',
      detail: `Awaiting the mandatory next-candle detection gate - ${Number(signal.setupScore || 0)}/100 setup score`
    };
  }

  function historicalCandlestickDirectionalReturn(signal, exitPrice) {
    const entry = optionalFiniteNumber(signal.tradeEntryPrice);
    const exit = optionalFiniteNumber(exitPrice);
    if (entry == null || exit == null || entry === 0) return null;
    const marketReturn = ((exit - entry) / entry) * 100;
    return String(signal.tradeSignal).toUpperCase() === 'SELL' ? -marketReturn : marketReturn;
  }

  function optionalFiniteNumber(value) {
    if (value === null || value === undefined || value === '') return null;
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric : null;
  }

  function candlestickReturnClass(value) {
    return Number(value) >= 0 ? 'positive' : 'negative';
  }

  function moveHistoricalCandlestickCard(direction) {
    if (activeHistoricalCandlestickSignals.length <= 1) return;
    activeHistoricalCandlestickSignalIndex = (
            activeHistoricalCandlestickSignalIndex + direction + activeHistoricalCandlestickSignals.length
    ) % activeHistoricalCandlestickSignals.length;
    renderActiveHistoricalCandlestickCard();
    positionHistoricalCandlestickCard(historicalCandlestickSignalCoordinate(
            activeHistoricalCandlestickSignals[activeHistoricalCandlestickSignalIndex]));
  }

  function historicalCandlestickSignalCoordinate(signal) {
    const candle = globalCandleData.find(item => Number(item.timestamp) === Number(signal?.signalTimestamp));
    if (!candle || !priceChart || !candleSeries) return null;
    const x = priceChart.timeScale().timeToCoordinate(candle.time);
    const buy = String(signal.tradeSignal).toUpperCase() === 'BUY';
    const y = candleSeries.priceToCoordinate(buy ? Number(candle.low) : Number(candle.high));
    return Number.isFinite(x) && Number.isFinite(y) ? {x, y} : null;
  }

  function ensureHistoricalCandlestickHighlightSeries() {
    if (!historicalCandlestickTrendSeries) {
      historicalCandlestickTrendSeries = priceChart.addLineSeries({
        color: '#ffbd59',
        lineWidth: 3,
        crosshairMarkerVisible: false,
        lastValueVisible: false,
        priceLineVisible: false
      });
    }
    if (!historicalCandlestickFormationSeries) {
      historicalCandlestickFormationSeries = priceChart.addLineSeries({
        color: '#b7f34a',
        lineWidth: 4,
        crosshairMarkerVisible: false,
        lastValueVisible: false,
        priceLineVisible: false
      });
    }
  }

  function highlightHistoricalCandlestickSignal(signal) {
    if (!priceChart || !globalCandleData.length) return;
    ensureHistoricalCandlestickHighlightSeries();
    const signalIndex = globalCandleData.findIndex(
            candle => Number(candle.timestamp) === Number(signal.signalTimestamp));
    if (signalIndex < 0) return;
    let trendStartIndex = globalCandleData.findIndex(
            candle => Number(candle.timestamp) === Number(signal.trendStartTimestamp));
    let patternStartIndex = globalCandleData.findIndex(
            candle => Number(candle.timestamp) === Number(signal.patternStartTimestamp));
    if (patternStartIndex < 0) {
      patternStartIndex = Math.max(0, signalIndex - Number(signal.formationCandles || 1) + 1);
    }
    if (trendStartIndex < 0) trendStartIndex = Math.max(0, patternStartIndex - 5);
    const trendCandles = globalCandleData.slice(trendStartIndex, patternStartIndex + 1);
    const formationCandles = globalCandleData.slice(patternStartIndex, signalIndex + 1);
    historicalCandlestickTrendSeries.setData(trendCandles.map(candle => ({
      time: candle.time,
      value: Number(candle.close)
    })));
    historicalCandlestickFormationSeries.applyOptions({
      color: String(signal.tradeSignal).toUpperCase() === 'BUY' ? '#64e8bd' : '#ff647c'
    });
    historicalCandlestickFormationSeries.setData(formationCandles.map(candle => ({
      time: candle.time,
      value: Number(candle.close)
    })));
    historicalCandlestickTrendSeries.setMarkers(trendCandles.length ? [{
      time: trendCandles[0].time,
      position: String(signal.tradeSignal).toUpperCase() === 'BUY' ? 'belowBar' : 'aboveBar',
      color: '#ffbd59',
      shape: 'circle',
      text: signal.trendLabel || 'Required trend'
    }] : []);
    historicalCandlestickFormationSeries.setMarkers(formationCandles.length ? [{
      time: formationCandles[formationCandles.length - 1].time,
      position: String(signal.tradeSignal).toUpperCase() === 'BUY' ? 'belowBar' : 'aboveBar',
      color: String(signal.tradeSignal).toUpperCase() === 'BUY' ? '#64e8bd' : '#ff647c',
      shape: String(signal.tradeSignal).toUpperCase() === 'BUY' ? 'arrowUp' : 'arrowDown',
      text: signal.patternLabel
    }] : []);
  }

  function positionHistoricalCandlestickCard(point) {
    const stage = document.getElementById('priceChartStage');
    const card = document.getElementById('historicalCandlestickHoverCard');
    const anchor = point && Number.isFinite(point.x) && Number.isFinite(point.y)
            ? point
            : {x: stage.clientWidth / 2, y: stage.clientHeight / 2};
    const gap = 14;
    const maxLeft = Math.max(8, stage.clientWidth - card.offsetWidth - 8);
    const maxTop = Math.max(8, stage.clientHeight - card.offsetHeight - 8);
    const preferredLeft = anchor.x + gap + card.offsetWidth > stage.clientWidth
            ? anchor.x - card.offsetWidth - gap
            : anchor.x + gap;
    const preferredTop = anchor.y + gap + card.offsetHeight > stage.clientHeight
            ? anchor.y - card.offsetHeight - gap
            : anchor.y + gap;
    card.style.left = `${Math.max(8, Math.min(maxLeft, preferredLeft))}px`;
    card.style.top = `${Math.max(8, Math.min(maxTop, preferredTop))}px`;
  }

  function scheduleHistoricalCandlestickCardHide() {
    clearTimeout(historicalCandlestickCardHideTimer);
    historicalCandlestickCardHideTimer = setTimeout(() => {
      if (!historicalCandlestickCardPinned && !historicalCandlestickCardHovered) {
        hideHistoricalCandlestickCard();
      }
    }, 120);
  }

  function hideHistoricalCandlestickCard() {
    clearTimeout(historicalCandlestickCardHideTimer);
    historicalCandlestickCardPinned = false;
    historicalCandlestickCardHovered = false;
    activeHistoricalCandlestickSignals = [];
    activeHistoricalCandlestickSignalIndex = 0;
    const card = document.getElementById('historicalCandlestickHoverCard');
    if (card) card.hidden = true;
    historicalCandlestickTrendSeries?.setData([]);
    historicalCandlestickTrendSeries?.setMarkers([]);
    historicalCandlestickFormationSeries?.setData([]);
    historicalCandlestickFormationSeries?.setMarkers([]);
  }

  function showHistoricalCandlestickIntervalPicker() {
    const viewDialog = document.getElementById('historicalCandlestickViewDialog');
    if (viewDialog.open) viewDialog.close();
    const resultsDialog = document.getElementById('historicalCandlestickResultsDialog');
    if (resultsDialog.open) resultsDialog.close();
    const lookbackDialog = document.getElementById('historicalCandlestickLookbackDialog');
    if (lookbackDialog.open) lookbackDialog.close();
    const intervalDialog = document.getElementById('historicalCandlestickIntervalDialog');
    if (!intervalDialog.open) intervalDialog.showModal();
  }

  function showHistoricalCandlestickLookbackPicker(interval, initialLookback = null) {
    if (!Object.hasOwn(HISTORICAL_CANDLESTICK_DEFAULT_LOOKBACKS, interval)) return;
    selectedHistoricalCandlestickInterval = interval;
    const intervalDialog = document.getElementById('historicalCandlestickIntervalDialog');
    if (intervalDialog.open) intervalDialog.close();
    const labels = {'1d': 'Daily interval', '1wk': 'Weekly interval', '1mo': 'Monthly interval'};
    document.getElementById('historicalCandlestickLookbackInterval').textContent = labels[interval];
    const input = document.getElementById('historicalCandlestickLookbackInput');
    input.value = String(initialLookback ?? HISTORICAL_CANDLESTICK_DEFAULT_LOOKBACKS[interval]);
    updateHistoricalCandlestickLookbackPreview();
    const lookbackDialog = document.getElementById('historicalCandlestickLookbackDialog');
    if (!lookbackDialog.open) lookbackDialog.showModal();
    input.focus();
    input.select();
  }

  function selectedHistoricalCandlestickLookback() {
    const input = document.getElementById('historicalCandlestickLookbackInput');
    const lookback = Number(input.value);
    return Number.isInteger(lookback)
            && lookback >= HISTORICAL_CANDLESTICK_LOOKBACK_MIN
            && lookback <= HISTORICAL_CANDLESTICK_LOOKBACK_MAX
            ? lookback
            : null;
  }

  function updateHistoricalCandlestickLookbackPreview() {
    const input = document.getElementById('historicalCandlestickLookbackInput');
    const error = document.getElementById('historicalCandlestickLookbackError');
    const preview = document.getElementById('historicalCandlestickLookbackDate');
    const lookback = selectedHistoricalCandlestickLookback();
    const valid = lookback !== null;
    input.setCustomValidity(valid ? '' : 'Enter a whole number from 1 to 750.');
    error.textContent = input.value && !valid ? 'Enter a whole number from 1 to 750.' : '';
    preview.textContent = valid && selectedHistoricalCandlestickInterval
            ? formatHistoricalCandlestickLookbackDate(selectedHistoricalCandlestickInterval, lookback)
            : 'Enter a candle count';
  }

  function formatHistoricalCandlestickLookbackDate(interval, lookback) {
    const estimatedDate = new Date();
    estimatedDate.setHours(12, 0, 0, 0);
    if (interval === '1mo') {
      estimatedDate.setDate(1);
      estimatedDate.setMonth(estimatedDate.getMonth() - lookback);
      return `Around ${new Intl.DateTimeFormat(undefined, {
        month: 'long',
        year: 'numeric'
      }).format(estimatedDate)}`;
    }
    if (interval === '1wk') {
      estimatedDate.setDate(estimatedDate.getDate() - lookback * 7);
    } else {
      let sessionsRemaining = lookback;
      while (sessionsRemaining > 0) {
        estimatedDate.setDate(estimatedDate.getDate() - 1);
        const day = estimatedDate.getDay();
        if (day !== 0 && day !== 6) sessionsRemaining--;
      }
    }
    return `Around ${new Intl.DateTimeFormat(undefined, {
      day: 'numeric',
      month: 'short',
      year: 'numeric'
    }).format(estimatedDate)}`;
  }

  async function submitHistoricalCandlestickLookback(event) {
    event.preventDefault();
    updateHistoricalCandlestickLookbackPreview();
    const input = document.getElementById('historicalCandlestickLookbackInput');
    const lookback = selectedHistoricalCandlestickLookback();
    if (!selectedHistoricalCandlestickInterval || lookback === null) {
      input.reportValidity();
      return;
    }
    await loadHistoricalCandlestickPatterns(selectedHistoricalCandlestickInterval, lookback);
  }

  async function loadHistoricalCandlestickPatterns(interval, lookbackCandles) {
    if (!Object.hasOwn(HISTORICAL_CANDLESTICK_DEFAULT_LOOKBACKS, interval)
            || !Number.isInteger(lookbackCandles)
            || lookbackCandles < HISTORICAL_CANDLESTICK_LOOKBACK_MIN
            || lookbackCandles > HISTORICAL_CANDLESTICK_LOOKBACK_MAX) {
      showHistoricalCandlestickLookbackPicker(
              interval,
              HISTORICAL_CANDLESTICK_DEFAULT_LOOKBACKS[interval]
      );
      return;
    }
    const intervalDialog = document.getElementById('historicalCandlestickIntervalDialog');
    if (intervalDialog.open) intervalDialog.close();
    const lookbackDialog = document.getElementById('historicalCandlestickLookbackDialog');
    if (lookbackDialog.open) lookbackDialog.close();
    const resultsDialog = document.getElementById('historicalCandlestickResultsDialog');
    const status = document.getElementById('historicalCandlestickResultsStatus');
    const rows = document.getElementById('historicalCandlestickResultsRows');
    const empty = document.getElementById('historicalCandlestickResultsEmpty');
    rows.replaceChildren();
    empty.hidden = true;
    status.textContent = 'Calculating patterns from completed candles…';
    if (!resultsDialog.open) resultsDialog.showModal();

    if (historicalCandlestickRequestController) {
      historicalCandlestickRequestController.abort();
    }
    historicalCandlestickRequestController = new AbortController();
    try {
      const response = await fetch(
              `/api/stocks/${encodedTicker}/candlestick-patterns/history?interval=${encodeURIComponent(interval)}`
              + `&lookbackCandles=${encodeURIComponent(lookbackCandles)}`,
              {
                cache: 'no-store',
                headers: {'Cache-Control': 'no-cache'},
                signal: historicalCandlestickRequestController.signal
              }
      );
      const history = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(history.error || 'Historical candle patterns could not be calculated');
      selectedHistoricalCandlestickInterval = interval;
      renderHistoricalCandlestickResults(history);
    } catch (error) {
      if (error.name === 'AbortError') return;
      status.textContent = error.message || 'Historical candle patterns could not be calculated';
      console.warn('Historical candlestick scan failed', error);
    } finally {
      historicalCandlestickRequestController = null;
    }
  }

  function renderHistoricalCandlestickResults(history) {
    const rows = document.getElementById('historicalCandlestickResultsRows');
    const empty = document.getElementById('historicalCandlestickResultsEmpty');
    const signals = Array.isArray(history.signals) ? history.signals : [];
    rows.replaceChildren(...signals.map(signal => createHistoricalCandlestickRow(
            signal,
            Number(history.lookbackCandles)
    )));
    empty.hidden = signals.length > 0;
    document.getElementById('historicalCandlestickResultsTitle').textContent =
            `${history.intervalLabel || 'Historical'} candle patterns`;
    document.getElementById('historicalCandlestickResultsWindow').textContent =
            `${history.lookbackLabel || 'Recent completed candles'} · `
            + `8-candle close-based time stop · `
            + `1:${history.interval === '1wk' || history.interval === '1mo' ? 3 : 2} R:R`;
    document.getElementById('historicalCandlestickResultsStatus').textContent =
            `${signals.length} signal${signals.length === 1 ? '' : 's'} calculated now · `
            + 'No signal results were cached';
  }

  function createHistoricalCandlestickRow(signal, lookbackCandles) {
    const row = document.createElement('tr');
    row.className = 'historical-candlestick-result-row';
    const detailUrl = historicalCandlestickDetailUrl(signal, lookbackCandles);
    row.tabIndex = 0;
    row.setAttribute('role', 'link');
    row.addEventListener('click', event => {
      if (event.target instanceof Element && event.target.closest('a')) return;
      window.requestStockWatchNavigation(detailUrl);
    });
    row.addEventListener('keydown', event => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      window.requestStockWatchNavigation(detailUrl);
    });
    row.append(
            historyCell('Signal date', signal.signalPeriodLabel),
            historicalCandlestickTypeCell(signal),
            historicalCandlestickStatusCell(signal),
            historyCell('Confidence', `${signal.setupScore}/100`, signal.setupStrengthLabel),
            historyCell('Formation', signal.patternLabel, signal.formationLabel),
            historicalCandlestickImpactCell(signal),
            historicalCandlestickDetailCell(detailUrl)
    );
    return row;
  }

  function historicalCandlestickTypeCell(signal) {
    const cell = document.createElement('td');
    cell.dataset.label = 'Type';
    const badge = document.createElement('span');
    badge.className = `historical-candlestick-type ${String(signal.tradeSignal || '').toLowerCase()}`;
    badge.textContent = signal.typeLabel || tradeSignalDisplayLabel(signal.tradeSignal);
    cell.append(badge);
    return cell;
  }

  function historicalCandlestickStatusCell(signal) {
    const cell = document.createElement('td');
    cell.dataset.label = 'Status';
    const badge = document.createElement('span');
    badge.className = `historical-candlestick-status ${signal.statusClass || 'pending'}`;
    badge.textContent = signal.statusLabel || 'Awaiting outcome';
    cell.append(badge);
    return cell;
  }

  function historicalCandlestickImpactCell(signal) {
    const cell = document.createElement('td');
    cell.dataset.label = 'Trade return';
    const strong = document.createElement('strong');
    const value = Number(signal.directionalReturnPercent);
    strong.textContent = Number.isFinite(value)
            ? `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
            : 'Pending';
    strong.className = Number.isFinite(value) ? (value >= 0 ? 'positive' : 'negative') : '';
    const detail = document.createElement('span');
    detail.textContent = signal.impactLabel || 'Trade return pending';
    const basis = document.createElement('small');
    basis.textContent = signal.tradeEntryPrice == null
            ? 'Trade has not opened'
            : `From trade entry at ${Number(signal.tradeEntryPrice).toFixed(4)}`;
    cell.append(strong, detail, basis);
    return cell;
  }

  function historicalCandlestickDetailCell(detailUrl) {
    const cell = document.createElement('td');
    cell.dataset.label = 'Details';
    const link = document.createElement('a');
    link.href = detailUrl;
    link.className = 'historical-candlestick-detail-link';
    link.textContent = 'View signal';
    cell.append(link);
    return cell;
  }

  function historicalCandlestickDetailUrl(signal, lookbackCandles, fullHistory = false) {
    const baseUrl = `/stock/${encodedTicker}/candlestick-patterns/`
            + `${encodeURIComponent(signal.interval)}/${encodeURIComponent(signal.signalTimestamp)}/`
            + `${encodeURIComponent(signal.pattern)}`;
    return fullHistory
            ? `${baseUrl}?fullHistory=true`
            : `${baseUrl}?lookbackCandles=${encodeURIComponent(lookbackCandles)}`;
  }

  async function reopenHistoricalCandlestickResultsFromUrl() {
    const url = new URL(window.location.href);
    const requestedView = url.searchParams.get('historicalCandles');
    if (requestedView !== 'true' && requestedView !== 'graphical') return;
    const interval = url.searchParams.get('historicalInterval');
    if (!['1d', '1wk', '1mo'].includes(interval)) return;
    const requestedLookback = Number(url.searchParams.get('lookbackCandles'));
    const lookback = Number.isInteger(requestedLookback)
            && requestedLookback >= HISTORICAL_CANDLESTICK_LOOKBACK_MIN
            && requestedLookback <= HISTORICAL_CANDLESTICK_LOOKBACK_MAX
            ? requestedLookback
            : HISTORICAL_CANDLESTICK_DEFAULT_LOOKBACKS[interval];
    url.searchParams.delete('historicalCandles');
    url.searchParams.delete('historicalInterval');
    url.searchParams.delete('lookbackCandles');
    window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`);
    if (requestedView === 'graphical') {
      if (currentInterval !== interval) await changeInterval(interval);
      await enableHistoricalCandlestickOverlay();
      return;
    }
    await loadHistoricalCandlestickPatterns(interval, lookback);
  }

  async function toggleCongressionalActivityFollow() {
    if (!congressionalActivityEligible || congressionalActivityBusy) return;
    const desiredState = !congressionalFollowing;
    const status = document.getElementById('congressionalActivityStatus');
    setCongressionalControlsDisabled(true);
    status.textContent = desiredState
            ? 'Preparing the historical baseline and enabling alerts…'
            : 'Stopping congressional activity alerts…';
    try {
      const response = await fetch(`/api/congressional-activity/${encodedTicker}/subscription`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({ active: desiredState })
      });
      const state = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(state.error || 'The follow setting could not be changed');
      applyCongressionalActivityState(state);
    } catch (error) {
      status.textContent = error.message || 'The follow setting could not be changed';
      console.warn('Congressional activity follow update failed', error);
    } finally {
      setCongressionalControlsDisabled(false);
    }
  }

  async function toggleInsiderActivityFollow() {
    if (!insiderActivityEligible || insiderActivityBusy) return;
    const desiredState = !insiderFollowing;
    const status = document.getElementById('insiderActivityStatus');
    setInsiderControlsDisabled(true);
    status.textContent = desiredState
            ? 'Preparing the filing baseline and enabling daily checks…'
            : 'Stopping insider activity alerts…';
    try {
      const response = await fetch(`/api/insider-activity/${encodedTicker}/subscription`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({ active: desiredState })
      });
      const state = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(state.error || 'The follow setting could not be changed');
      applyInsiderActivityState(state);
    } catch (error) {
      status.textContent = error.message || 'The follow setting could not be changed';
      console.warn('Insider activity follow update failed', error);
    } finally {
      setInsiderControlsDisabled(false);
    }
  }

  function setInsiderControlsDisabled(disabled) {
    insiderActivityBusy = disabled;
    document.getElementById('followInsiderActivityBtn').disabled = disabled;
    document.getElementById('showInsiderHistoryBtn').disabled = disabled;
  }

  async function showInsiderHistory() {
    if (!insiderActivityEligible || insiderActivityBusy) return;
    const dialog = document.getElementById('insiderHistoryDialog');
    const status = document.getElementById('insiderHistoryStatus');
    const rows = document.getElementById('insiderHistoryRows');
    const empty = document.getElementById('insiderHistoryEmpty');
    rows.replaceChildren();
    empty.hidden = true;
    status.textContent = 'Loading the stored insider archive…';
    if (!dialog.open) dialog.showModal();
    setInsiderControlsDisabled(true);
    let hasCachedTrades = false;
    try {
      const cacheResponse = await fetch(`/api/insider-activity/${encodedTicker}/history`);
      const cachedHistory = await cacheResponse.json().catch(() => ({}));
      if (!cacheResponse.ok) {
        throw new Error(cachedHistory.error || 'The stored insider archive could not be loaded');
      }
      renderInsiderHistory(cachedHistory);
      applyInsiderActivityState(cachedHistory);
      hasCachedTrades = Array.isArray(cachedHistory.trades) && cachedHistory.trades.length > 0;
      status.textContent = hasCachedTrades
              ? 'Stored archive loaded · Checking API Ninjas for newly observed rows…'
              : 'No stored observations yet · Checking API Ninjas latest rows…';

      const refreshResponse = await fetch(
              `/api/insider-activity/${encodedTicker}/history/refresh`,
              {
                method: 'POST',
                headers: secureJsonHeaders()
              });
      const refreshedHistory = await refreshResponse.json().catch(() => ({}));
      if (!refreshResponse.ok) {
        throw new Error(refreshedHistory.error || 'New insider rows could not be loaded');
      }
      renderInsiderHistory(refreshedHistory);
      applyInsiderActivityState(refreshedHistory);
    } catch (error) {
      status.textContent = error.message || 'Insider history could not be loaded';
      status.dataset.state = 'unavailable';
      if (!hasCachedTrades) {
        empty.hidden = false;
        empty.textContent = 'No cached insider history is available. '
                + 'Check the provider access message above.';
      }
      console.warn('Insider activity history failed', error);
    } finally {
      setInsiderControlsDisabled(false);
    }
  }

  function renderInsiderHistory(history) {
    const trades = Array.isArray(history.trades) ? history.trades : [];
    document.getElementById('insiderHistoryStatus').removeAttribute('data-state');
    const empty = document.getElementById('insiderHistoryEmpty');
    empty.textContent =
            'No relevant purchase or sale disclosures were found for this stock in the current window.';
    document.getElementById('insiderHistoryRows')
            .replaceChildren(...trades.map(createInsiderHistoryRow));
    empty.hidden = trades.length > 0;
    const cacheLabels = {
      CACHE: 'Loaded the stored StockWatch insider archive',
      REFRESHED: 'Merged newly observed API Ninjas rows into the stored archive',
      STALE: 'API Ninjas is temporarily unavailable · Showing the last successful cache'
    };
    const cachedDate = history.cachedAt ? ` · Cached ${formatDateTime(history.cachedAt)}` : '';
    document.getElementById('insiderHistoryStatus').textContent =
            (cacheLabels[history.refreshStatus] || 'Insider history loaded') + cachedDate;
    document.getElementById('insiderHistoryWindow').textContent =
            `Stored purchase and sale observations from ${formatCongressionalDate(history.windowStart)} `
            + `through ${formatCongressionalDate(history.windowEnd)}. Each refresh checks the latest 10 rows.`;
    document.getElementById('insiderHistoryDisclaimer').textContent =
            `${history.returnMethodology || ''} ${history.alertBaselineNotice || ''}`.trim();
  }

  function createInsiderHistoryRow(trade) {
    const row = document.createElement('tr');
    const detailUrl = activityHistoryDetailUrl(trade, 'insider');
    row.append(
            historyCell('Insider', trade.insiderName, trade.ownerRole || 'Role not reported'),
            historyActivityCell(trade),
            historyCell(
                    'Shares / price',
                    formatInsiderShares(trade.shares),
                    trade.transactionPrice == null
                            ? 'Filed price unavailable'
                            : `at ${formatInsiderMoney(trade.transactionPrice)}`),
            historyCell('Trade value', formatInsiderMoney(trade.transactionValue)),
            historyCell(
                    'Effective date*',
                    formatCongressionalDate(trade.transactionDate),
                    'API Ninjas free tier: SEC filing date fallback'),
            historyCell('Filed', formatCongressionalDate(trade.filingDate)),
            insiderReturnCell(trade),
            historySourceCell(trade, detailUrl)
    );
    makeActivityHistoryRowInteractive(row, detailUrl, trade.insiderName);
    return row;
  }

  function insiderReturnCell(trade) {
    const cell = document.createElement('td');
    cell.dataset.label = 'Directional return';
    if (trade.returnPercent == null) {
      const unavailable = document.createElement('span');
      unavailable.className = 'insider-return unavailable';
      unavailable.textContent = 'Awaiting completed price data';
      cell.append(unavailable);
      return cell;
    }
    const value = Number(trade.returnPercent);
    const label = document.createElement('strong');
    label.className = `insider-return ${value >= 0 ? 'positive' : 'negative'}`;
    label.textContent = `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
    cell.append(label);
    if (trade.returnAsOf) {
      const asOf = document.createElement('span');
      asOf.textContent = `from filed price on ${formatCongressionalDate(trade.transactionDate)} · through ${formatCongressionalDate(trade.returnAsOf)}`;
      cell.append(asOf);
    }
    return cell;
  }

  function formatInsiderShares(value) {
    if (value == null) return 'Shares unavailable';
    return `${new Intl.NumberFormat(undefined, {maximumFractionDigits: 2}).format(Number(value))} shares`;
  }

  function formatInsiderMoney(value) {
    if (value == null) return 'Unavailable';
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: stockCurrency || 'USD',
      maximumFractionDigits: 2
    }).format(Number(value));
  }

  async function showCongressionalHistory() {
    if (!congressionalActivityEligible || congressionalActivityBusy) return;
    const dialog = document.getElementById('congressionalHistoryDialog');
    const status = document.getElementById('congressionalHistoryStatus');
    const rows = document.getElementById('congressionalHistoryRows');
    const empty = document.getElementById('congressionalHistoryEmpty');
    rows.replaceChildren();
    empty.hidden = true;
    status.textContent = 'Checking the database cache…';
    if (!dialog.open) dialog.showModal();
    setCongressionalControlsDisabled(true);
    try {
      const response = await fetch(`/api/congressional-activity/${encodedTicker}/history`);
      const history = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(history.error || 'Congressional history could not be loaded');
      renderCongressionalHistory(history);
      applyCongressionalActivityState(history);
    } catch (error) {
      status.textContent = error.message || 'Congressional history could not be loaded';
      console.warn('Congressional activity history failed', error);
    } finally {
      setCongressionalControlsDisabled(false);
    }
  }

  function renderCongressionalHistory(history) {
    const rows = document.getElementById('congressionalHistoryRows');
    const empty = document.getElementById('congressionalHistoryEmpty');
    const trades = Array.isArray(history.trades) ? history.trades : [];
    rows.replaceChildren(...trades.map(createCongressionalHistoryRow));
    empty.hidden = trades.length > 0;

    const cacheLabels = {
      CACHE: 'Loaded from the StockWatch database cache',
      REFRESHED: 'Fetched from CongressInvests and cached in StockWatch',
      REFRESHING: 'A shared refresh is running · Showing the current database cache',
      COOLDOWN: 'This ticker was already refreshed today · Showing the database cache until midnight UTC',
      STALE: 'CongressInvests is temporarily unavailable · Showing the last successful cache'
    };
    const cachedDate = history.cachedAt ? ` · Cached ${formatDateTime(history.cachedAt)}` : '';
    document.getElementById('congressionalHistoryStatus').textContent =
            (cacheLabels[history.cacheStatus] || 'History loaded') + cachedDate;
    document.getElementById('congressionalHistoryWindow').textContent =
            history.relevanceNotice || `Relevant purchase and sale disclosures from the last ${history.historyDays || 365} days.`;
    if (history.attribution?.disclaimer) {
      document.getElementById('congressionalHistoryDisclaimer').textContent =
              `${history.attribution.disclaimer} ${history.alertBaselineNotice || ''}`.trim();
    }
  }

  function createCongressionalHistoryRow(trade) {
    const row = document.createElement('tr');
    const detailUrl = activityHistoryDetailUrl(trade, 'congressional');
    row.append(
            historyCell('Member', trade.memberName, trade.chamber),
            historyActivityCell(trade),
            historyCell('Reported value', trade.amountRange),
            historyCell('Transaction date', formatCongressionalDate(trade.transactionDate)),
            historyCell('Disclosure date', formatCongressionalDate(trade.disclosureDate)),
            historySourceCell(trade, detailUrl)
    );
    makeActivityHistoryRowInteractive(row, detailUrl, trade.memberName);
    return row;
  }

  function activityHistoryDetailUrl(trade, source) {
    if (trade?.notificationId != null) {
      return `/activity-signals/${source}/${encodeURIComponent(trade.notificationId)}`;
    }
    if (trade?.id != null) {
      return `/activity-signals/${source}/trades/${encodeURIComponent(trade.id)}`;
    }
    return null;
  }

  function makeActivityHistoryRowInteractive(row, detailUrl, actorName) {
    if (!detailUrl) return;
    row.classList.add('activity-history-row-link');
    row.tabIndex = 0;
    row.setAttribute('role', 'link');
    row.setAttribute('aria-label', `View activity signal for ${actorName || 'this trade'}`);
    const navigate = () => window.requestStockWatchNavigation(detailUrl);
    row.addEventListener('click', event => {
      if (event.target.closest('a, button')) return;
      navigate();
    });
    row.addEventListener('keydown', event => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      navigate();
    });
  }

  function historyCell(label, primary, secondary) {
    const cell = document.createElement('td');
    cell.dataset.label = label;
    const strong = document.createElement('strong');
    strong.textContent = primary || 'Unavailable';
    cell.append(strong);
    if (secondary) {
      const detail = document.createElement('span');
      detail.textContent = secondary;
      cell.append(detail);
    }
    return cell;
  }

  function historyActivityCell(trade) {
    const cell = document.createElement('td');
    cell.dataset.label = 'Activity';
    const badge = document.createElement('span');
    badge.className = `congressional-history-type ${trade.transactionType === 'PURCHASE' ? 'purchase' : 'sale'}`;
    badge.textContent = trade.transactionTypeLabel || trade.transactionType || 'Activity';
    cell.append(badge);
    return cell;
  }

  function historySourceCell(trade, detailUrl) {
    const cell = document.createElement('td');
    cell.dataset.label = 'Filing';
    if (detailUrl) {
      const signalLink = document.createElement('a');
      signalLink.href = detailUrl;
      signalLink.className = 'history-signal-detail-link';
      signalLink.textContent = 'View signal';
      cell.append(signalLink);
    }
    const sourceUrl = trade?.sourceUrl;
    if (typeof sourceUrl === 'string' && /^https?:\/\//i.test(sourceUrl)) {
      const link = document.createElement('a');
      link.href = sourceUrl;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = 'Official filing';
      cell.append(link);
    } else if (!detailUrl) {
      cell.textContent = 'Unavailable';
    }
    return cell;
  }

  function formatCongressionalDate(value) {
    if (!value) return 'Unavailable';
    const date = new Date(`${value}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat(undefined, {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC'
    }).format(date);
  }

  function formatDateTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat(undefined, {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    }).format(date);
  }

  function alertInputs() {
    return Array.from(document.querySelectorAll('[data-alert-interval][data-alert-signal]'));
  }

  function alertControlKey(input) {
    return `${input.dataset.alertFamily || 'CANDLESTICK'}|${input.dataset.alertInterval}|${input.dataset.alertSignal}`;
  }

  function alertStateValue(state, input) {
    const family = input.dataset.alertFamily || 'CANDLESTICK';
    const interval = input.dataset.alertInterval;
    const signal = input.dataset.alertSignal;
    const legacyChecked = family === 'CANDLESTICK' ? state.intervals?.[interval]?.[signal] : false;
    return Boolean(state.families?.[family]?.[interval]?.[signal] ?? legacyChecked);
  }

  function hydrateAlertDraft(state) {
    persistedAlertState.clear();
    alertInputs().forEach(input => {
      const checked = alertStateValue(state, input);
      input.checked = checked;
      persistedAlertState.set(alertControlKey(input), checked);
      if (!input.dataset.listenerBound) {
        input.addEventListener('change', updateAlertDraftUi);
        input.dataset.listenerBound = 'true';
      }
    });
    trackedAlertSummary = `${state.trackedStocks}/${state.maxTrackedStocks} tracked`;
    document.getElementById('alertStatus').innerText = trackedAlertSummary;
    alertStateLoaded = true;
    updateAlertDraftUi();
    updateTechnicalFollowAllButton();
  }

  function changedAlertInputs() {
    if (!alertStateLoaded) return [];
    return alertInputs().filter(input =>
            persistedAlertState.get(alertControlKey(input)) !== input.checked);
  }

  function hasUnappliedAlertChanges() {
    return changedAlertInputs().length > 0;
  }

  function updateAlertDraftUi() {
    const changedCount = changedAlertInputs().length;
    const applyButton = document.getElementById('applyAlertChangesBtn');
    const draftStatus = document.getElementById('alertDraftStatus');
    const alertPanel = document.querySelector('.alert-panel');

    applyButton.disabled = !alertStateLoaded || alertSaveInProgress
            || technicalFollowAllBusy || changedCount === 0;
    alertPanel.classList.toggle('has-unapplied-changes', changedCount > 0);
    setAlertBeforeUnloadGuard(!pageExitAllowed && changedCount > 0);
    if (alertSaveInProgress) {
      applyButton.textContent = 'Applying...';
      draftStatus.textContent = 'Saving your changes';
      updateTechnicalFollowAllButton();
      return;
    }
    applyButton.textContent = 'Apply changes';
    draftStatus.textContent = changedCount === 0
            ? 'No unapplied changes'
            : `${changedCount} unapplied change${changedCount === 1 ? '' : 's'}`;
    updateTechnicalFollowAllButton();
  }

  function setAlertInputsDisabled(disabled) {
    alertInputs().forEach(input => {
      input.disabled = disabled;
    });
  }

  function alertChangePayload(input) {
    return {
      interval: input.dataset.alertInterval,
      signal: input.dataset.alertSignal,
      patternFamily: input.dataset.alertFamily || 'CANDLESTICK',
      active: input.checked
    };
  }

  function persistAlertDraft() {
    if (alertSavePromise) return alertSavePromise;
    alertSavePromise = performAlertDraftSave().finally(() => {
      alertSavePromise = null;
    });
    return alertSavePromise;
  }

  async function performAlertDraftSave() {
    const changedInputs = changedAlertInputs();
    if (changedInputs.length === 0) return true;

    const status = document.getElementById('alertStatus');
    alertSaveInProgress = true;
    setAlertInputsDisabled(true);
    status.innerText = 'Applying alert changes...';
    updateAlertDraftUi();

    try {
      const response = await fetch(`/api/alerts/${encodedTicker}`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({ changes: changedInputs.map(alertChangePayload) })
      });

      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        status.innerText = payload.error || 'Could not apply alert changes';
        return false;
      }

      hydrateAlertDraft(payload);
      status.innerText = `${trackedAlertSummary} · Changes applied`;
      return true;
    } catch (err) {
      status.innerText = 'Could not apply alert changes';
      console.warn("Alert batch update failed", err);
      return false;
    } finally {
      alertSaveInProgress = false;
      setAlertInputsDisabled(technicalFollowAllBusy || !alertStateLoaded);
      updateAlertDraftUi();
    }
  }

  function hasAnyPersistedTechnicalFollow() {
    const patternFollowed = Array.from(persistedAlertState.values()).some(Boolean);
    const outlookFollowed = outlookFollowInputs()
            .some(input => input.dataset.persisted === 'true');
    return patternFollowed || outlookFollowed;
  }

  function updateTechnicalFollowAllButton() {
    const button = document.getElementById('toggleAllTechnicalMonitoringBtn');
    if (!button) return;
    const stateReady = alertStateLoaded && outlookFollowStateLoaded;
    const anyFollowed = stateReady && hasAnyPersistedTechnicalFollow();
    button.disabled = !stateReady || alertSaveInProgress
            || outlookFollowSaveInProgress || technicalFollowAllBusy;
    button.setAttribute('aria-pressed', String(anyFollowed));
    button.classList.toggle('following', anyFollowed);
    button.textContent = technicalFollowAllBusy
            ? (anyFollowed ? 'Unfollowing all...' : 'Following all...')
            : (!stateReady ? 'Loading follow state...' : (anyFollowed ? 'Unfollow all' : 'Follow all'));
  }

  async function setEveryOutlookSubscription(active) {
    const status = document.getElementById('outlookFollowStatus');
    for (const input of outlookFollowInputs()) {
      if ((input.dataset.persisted === 'true') === active) continue;
      status.textContent = active
              ? `Establishing ${input.dataset.outlookFollowInterval.toLowerCase()} baseline...`
              : `Stopping ${input.dataset.outlookFollowInterval.toLowerCase()} outlook...`;
      const response = await fetch(`/api/stocks/${encodedTicker}/technical-outlook/subscriptions`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({
          interval: input.dataset.outlookFollowInterval,
          active
        })
      });
      const state = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(state.error || 'The Automated Technical Outlook follows could not be updated.');
      }
      hydrateOutlookFollowState(state);
    }
  }

  async function toggleAllTechnicalMonitoring() {
    if (technicalFollowAllBusy || !alertStateLoaded || !outlookFollowStateLoaded) return;
    const followEverything = !hasAnyPersistedTechnicalFollow();
    const status = document.getElementById('alertStatus');
    let outcomeMessage = '';
    technicalFollowAllBusy = true;
    setAlertInputsDisabled(true);
    setOutlookFollowInputsDisabled(true);
    updateAlertDraftUi();
    updateTechnicalFollowAllButton();
    status.textContent = followEverything
            ? 'Following every technical rule...'
            : 'Unfollowing every technical rule...';

    try {
      alertInputs().forEach(input => {
        input.checked = followEverything;
      });
      updateAlertDraftUi();
      if (!await persistAlertDraft()) {
        throw new Error('The pattern follows could not be updated.');
      }
      await setEveryOutlookSubscription(followEverything);
      outcomeMessage = followEverything
              ? 'All technical rules and outlook intervals are now followed.'
              : 'All technical rules and outlook intervals are now unfollowed.';
    } catch (error) {
      outcomeMessage = error.message || 'All technical monitoring could not be updated.';
      console.warn('Follow-all technical monitoring update failed', error);
    } finally {
      await Promise.all([loadAlertState(), loadOutlookFollowState()]);
      technicalFollowAllBusy = false;
      setAlertInputsDisabled(!alertStateLoaded);
      setOutlookFollowInputsDisabled(!outlookFollowStateLoaded);
      updateAlertDraftUi();
      updateTechnicalFollowAllButton();
      if (alertStateLoaded) {
        status.textContent = `${trackedAlertSummary} · ${outcomeMessage}`;
      }
    }
  }

  function closeUnsavedAlertDialog() {
    const dialog = document.getElementById('unsavedAlertDialog');
    if (dialog.open) dialog.close();
    document.getElementById('unsavedAlertDialogStatus').textContent = '';
  }

  function requestGuardedNavigation(action) {
    if (!hasUnappliedAlertChanges()) {
      pageExitAllowed = true;
      setAlertBeforeUnloadGuard(false);
      action();
      return;
    }
    pendingNavigationAction = action;
    const dialog = document.getElementById('unsavedAlertDialog');
    document.getElementById('unsavedAlertDialogStatus').textContent = '';
    if (!dialog.open) dialog.showModal();
  }

  function continuePendingNavigation() {
    const action = pendingNavigationAction;
    pendingNavigationAction = null;
    closeUnsavedAlertDialog();
    if (!action) return;
    pageExitAllowed = true;
    setAlertBeforeUnloadGuard(false);
    action();
  }

  function keepEditingAlertDraft() {
    pendingNavigationAction = null;
    closeUnsavedAlertDialog();
  }

  async function saveAlertDraftAndLeave() {
    const saveButton = document.getElementById('saveAlertChangesBeforeLeaveBtn');
    const dialogStatus = document.getElementById('unsavedAlertDialogStatus');
    saveButton.disabled = true;
    saveButton.textContent = 'Saving...';
    dialogStatus.textContent = 'Applying your alert changes...';
    const saved = await persistAlertDraft();
    saveButton.disabled = false;
    saveButton.textContent = 'Save and leave';
    if (saved) {
      continuePendingNavigation();
      return;
    }
    dialogStatus.textContent = 'The changes could not be saved. Your draft is still available.';
  }

  window.requestStockWatchNavigation = destination => {
    requestGuardedNavigation(() => window.location.assign(destination));
  };

  function handleUnsavedAlertBeforeUnload(event) {
    if (pageExitAllowed || !hasUnappliedAlertChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  function setAlertBeforeUnloadGuard(enabled) {
    if (enabled === alertBeforeUnloadRegistered) return;
    if (enabled) {
      window.addEventListener('beforeunload', handleUnsavedAlertBeforeUnload);
    } else {
      window.removeEventListener('beforeunload', handleUnsavedAlertBeforeUnload);
    }
    alertBeforeUnloadRegistered = enabled;
  }

  function bindUnsavedAlertGuards() {
    document.getElementById('applyAlertChangesBtn').addEventListener('click', persistAlertDraft);
    document.getElementById('toggleAllTechnicalMonitoringBtn')
            .addEventListener('click', toggleAllTechnicalMonitoring);
    document.getElementById('keepEditingAlertsBtn').addEventListener('click', keepEditingAlertDraft);
    document.getElementById('discardAlertChangesBtn').addEventListener('click', continuePendingNavigation);
    document.getElementById('saveAlertChangesBeforeLeaveBtn').addEventListener('click', saveAlertDraftAndLeave);
    document.getElementById('unsavedAlertDialog').addEventListener('cancel', event => {
      event.preventDefault();
      keepEditingAlertDraft();
    });

    document.addEventListener('click', event => {
      if (pageExitAllowed || !hasUnappliedAlertChanges() || event.defaultPrevented
              || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return;
      }
      const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null;
      if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) return;
      const rawHref = anchor.getAttribute('href');
      if (!rawHref || rawHref.startsWith('#')) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      requestGuardedNavigation(() => {
        if (anchor.hasAttribute('data-history-back')
                && typeof window.requestStockWatchHistoryBack === 'function') {
          window.requestStockWatchHistoryBack(anchor.href);
          return;
        }
        window.location.assign(anchor.href);
      });
    }, true);

    document.addEventListener('submit', event => {
      if (pageExitAllowed || !hasUnappliedAlertChanges() || event.defaultPrevented) return;
      const form = event.target;
      const submitter = event.submitter;
      event.preventDefault();
      event.stopImmediatePropagation();
      requestGuardedNavigation(() => {
        if (submitter) {
          form.requestSubmit(submitter);
        } else {
          HTMLFormElement.prototype.submit.call(form);
        }
      });
    }, true);

  }

  const STOCK_WORKSPACE_SECTIONS = ['general', 'technical-analysis', 'ticker-alerts'];

  function stockWorkspaceSectionFromHash() {
    const requestedSection = window.location.hash.slice(1).toLowerCase();
    return STOCK_WORKSPACE_SECTIONS.includes(requestedSection) ? requestedSection : 'general';
  }

  function scheduleGeneralWorkspaceChartResize() {
    if (generalChartResizeFrame !== null) return;
    generalChartResizeFrame = window.requestAnimationFrame(() => {
      generalChartResizeFrame = null;
      resizeGeneralWorkspaceCharts();
    });
  }

  function resizeGeneralWorkspaceCharts() {
    if (!priceChart) return;
    const priceContainer = document.getElementById('priceChartContainer');
    const volumeContainer = document.getElementById('volumeChartContainer');
    if (priceContainer.clientWidth && priceContainer.clientHeight) {
      priceChart.resize(priceContainer.clientWidth, priceContainer.clientHeight);
    }
    if (volumeChart && volumeChartEnabled
            && volumeContainer.clientWidth && volumeContainer.clientHeight) {
      volumeChart.resize(volumeContainer.clientWidth, volumeContainer.clientHeight);
    }
    if (rsiChart && rsiOverlayEnabled) {
      const rsiContainer = document.getElementById('rsiChartContainer');
      if (rsiContainer.clientWidth && rsiContainer.clientHeight) {
        rsiChart.resize(rsiContainer.clientWidth, rsiContainer.clientHeight);
      }
    }
    chartIndicatorPanels.forEach(panel => {
      if (panel.container.clientWidth && panel.container.clientHeight) {
        panel.chart.resize(panel.container.clientWidth, panel.container.clientHeight);
      }
    });
    scheduleAnchoredVolumeProfileRender();
    scheduleElliottHitTargetPositioning();
    scheduleHistoricalCandlestickHitTargetPositioning();
  }

  function setResizableChartHeight(handle, requestedHeight) {
    const target = document.getElementById(handle.dataset.chartResizeTarget);
    if (!target) return;
    const minimum = Number(handle.dataset.chartResizeMin);
    const maximum = Number(handle.dataset.chartResizeMax);
    const nextHeight = Math.round(Math.min(maximum, Math.max(minimum, requestedHeight)));
    target.style.height = `${nextHeight}px`;
    handle.setAttribute('aria-valuenow', String(nextHeight));
    scheduleGeneralWorkspaceChartResize();
  }

  function initializeResizableCharts() {
    const handles = [...document.querySelectorAll('[data-chart-resize-target]')];
    if (!handles.length) return;

    if (typeof ResizeObserver !== 'undefined') {
      chartResizeObserver?.disconnect();
      chartResizeObserver = new ResizeObserver(entries => {
        entries.forEach(entry => {
          if (!entry.contentRect.height) return;
          const handle = handles.find(candidate =>
                  candidate.dataset.chartResizeTarget === entry.target.id);
          handle?.setAttribute('aria-valuenow', String(Math.round(entry.contentRect.height)));
        });
        scheduleGeneralWorkspaceChartResize();
      });
    }

    handles.forEach(handle => {
      const target = document.getElementById(handle.dataset.chartResizeTarget);
      if (!target) return;
      chartResizeObserver?.observe(target);
      const initialHeight = target.getBoundingClientRect().height
              || Number(handle.dataset.chartResizeDefault);
      handle.setAttribute('aria-valuenow', String(Math.round(initialHeight)));

      let activePointerId = null;
      let startY = 0;
      let startHeight = 0;

      const finishResize = () => {
        if (activePointerId === null) return;
        activePointerId = null;
        handle.classList.remove('is-resizing');
        document.body.classList.remove('chart-panel-resizing');
      };

      handle.addEventListener('pointerdown', event => {
        if (event.button !== 0) return;
        activePointerId = event.pointerId;
        startY = event.clientY;
        startHeight = target.getBoundingClientRect().height;
        handle.setPointerCapture(event.pointerId);
        handle.classList.add('is-resizing');
        document.body.classList.add('chart-panel-resizing');
        event.preventDefault();
      });

      handle.addEventListener('pointermove', event => {
        if (event.pointerId !== activePointerId) return;
        setResizableChartHeight(handle, startHeight + event.clientY - startY);
      });
      handle.addEventListener('pointerup', finishResize);
      handle.addEventListener('pointercancel', finishResize);
      handle.addEventListener('lostpointercapture', finishResize);

      handle.addEventListener('keydown', event => {
        const currentHeight = target.getBoundingClientRect().height;
        const step = event.shiftKey ? 50 : 10;
        let nextHeight = null;
        if (event.key === 'ArrowUp') nextHeight = currentHeight - step;
        if (event.key === 'ArrowDown') nextHeight = currentHeight + step;
        if (event.key === 'Home') nextHeight = Number(handle.dataset.chartResizeMin);
        if (event.key === 'End') nextHeight = Number(handle.dataset.chartResizeMax);
        if (nextHeight === null) return;
        event.preventDefault();
        setResizableChartHeight(handle, nextHeight);
      });

      handle.addEventListener('dblclick', () => {
        setResizableChartHeight(handle, Number(handle.dataset.chartResizeDefault));
      });
    });
  }

  function activateStockWorkspace(section, updateHash = true, moveFocus = false) {
    const nextSection = STOCK_WORKSPACE_SECTIONS.includes(section) ? section : 'general';
    const tabs = [...document.querySelectorAll('[data-stock-workspace-tab]')];

    tabs.forEach(tab => {
      const isActive = tab.dataset.stockWorkspaceTab === nextSection;
      tab.classList.toggle('active', isActive);
      tab.setAttribute('aria-selected', String(isActive));
      tab.tabIndex = isActive ? 0 : -1;
      if (isActive && moveFocus) tab.focus();
    });

    document.querySelectorAll('[data-stock-workspace-panel]').forEach(panel => {
      const isActive = panel.dataset.stockWorkspacePanel === nextSection;
      panel.hidden = !isActive;
      panel.classList.toggle('active', isActive);
    });

    if (updateHash && window.location.hash !== `#${nextSection}`) {
      window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}#${nextSection}`);
    }

    if (nextSection === 'general') {
      window.requestAnimationFrame(() => window.requestAnimationFrame(resizeGeneralWorkspaceCharts));
    }
  }

  function initializeStockWorkspaceTabs() {
    const tabs = [...document.querySelectorAll('[data-stock-workspace-tab]')];
    tabs.forEach((tab, index) => {
      tab.addEventListener('click', () => activateStockWorkspace(tab.dataset.stockWorkspaceTab));
      tab.addEventListener('keydown', event => {
        let nextIndex = null;
        if (event.key === 'ArrowRight') nextIndex = (index + 1) % tabs.length;
        if (event.key === 'ArrowLeft') nextIndex = (index - 1 + tabs.length) % tabs.length;
        if (event.key === 'Home') nextIndex = 0;
        if (event.key === 'End') nextIndex = tabs.length - 1;
        if (nextIndex === null) return;
        event.preventDefault();
        activateStockWorkspace(tabs[nextIndex].dataset.stockWorkspaceTab, true, true);
      });
    });

    window.addEventListener('hashchange', () => {
      activateStockWorkspace(stockWorkspaceSectionFromHash(), false);
    });
    activateStockWorkspace(stockWorkspaceSectionFromHash(), false);
  }

  function initializeStockDemoTrading() {
    const dialog = document.getElementById('stockDemoTradeDialog');
    const status = document.getElementById('stockDemoTradeStatus');
    const sizeInput = document.getElementById('stockDemoTradeSizeInput');
    const sizeField = document.getElementById('stockDemoTradeSizeField');
    const confirmButton = document.getElementById('confirmStockDemoTrade');
    const intervalLabel = interval => ({ '1d': 'Daily', '1wk': 'Weekly', '1mo': 'Monthly' })[interval] || interval;

    document.querySelectorAll('[data-stock-demo-trade]').forEach(button => {
      button.addEventListener('click', () => {
        pendingStockDemoTradeSide = button.dataset.stockDemoTrade;
        pendingStockDemoTradeInterval = currentInterval;
        pendingStockDemoTradeRequestId = crypto.randomUUID();
        stockDemoTradeSizeMode = 'none';
        document.querySelectorAll('[data-stock-demo-size-mode]').forEach(option => {
          option.classList.toggle('active', option.dataset.stockDemoSizeMode === 'none');
        });
        sizeField.hidden = true;
        sizeInput.value = '';
        document.getElementById('stockDemoTradeDialogTitle').textContent =
                pendingStockDemoTradeSide === 'BUY' ? `Virtual Buy ${ticker}` : `Virtual Sell ${ticker}`;
        document.getElementById('stockDemoTradeExplanation').textContent = pendingStockDemoTradeSide === 'BUY'
                ? 'This tracks the return from hypothetically buying at the captured current quote.'
                : 'This tracks avoided loss if price falls and missed upside if it rises. It is not a short sale.';
        status.textContent = `${intervalLabel(pendingStockDemoTradeInterval)} technical state will be frozen at entry.`;
        confirmButton.disabled = false;
        confirmButton.textContent = 'Create demo trade';
        dialog.showModal();
      });
    });

    document.getElementById('cancelStockDemoTrade').addEventListener('click', () => dialog.close());
    document.querySelectorAll('[data-stock-demo-size-mode]').forEach(button => {
      button.addEventListener('click', () => {
        stockDemoTradeSizeMode = button.dataset.stockDemoSizeMode;
        document.querySelectorAll('[data-stock-demo-size-mode]').forEach(option => {
          option.classList.toggle('active', option === button);
        });
        sizeField.hidden = stockDemoTradeSizeMode === 'none';
        document.getElementById('stockDemoTradeSizeLabel').textContent =
                stockDemoTradeSizeMode === 'quantity' ? 'Number of shares' : 'Virtual investment amount';
        document.getElementById('stockDemoTradeSizeHelp').textContent = stockDemoTradeSizeMode === 'quantity'
                ? 'The virtual amount will be calculated from the captured price.'
                : 'The virtual share quantity will be calculated from the captured price.';
      });
    });

    confirmButton.addEventListener('click', async () => {
      const size = sizeInput.value;
      if (stockDemoTradeSizeMode !== 'none' && (!size || Number(size) <= 0)) {
        status.textContent = 'Enter a positive virtual size.';
        return;
      }
      confirmButton.disabled = true;
      confirmButton.textContent = 'Capturing quote...';
      try {
        const response = await fetch(`/api/virtual-trades/${encodedTicker}`, {
          method: 'POST',
          headers: secureJsonHeaders(),
          body: JSON.stringify({
            side: pendingStockDemoTradeSide,
            interval: pendingStockDemoTradeInterval,
            quantity: stockDemoTradeSizeMode === 'quantity' ? size : null,
            notionalValue: stockDemoTradeSizeMode === 'notional' ? size : null,
            clientRequestId: pendingStockDemoTradeRequestId
          })
        });
        if (!response.ok) {
          const error = await response.json().catch(() => ({}));
          throw new Error(error.message || error.detail || error.error || 'The demo trade could not be created.');
        }
        const trade = await response.json();
        window.location.assign(trade.detailUrl);
      } catch (error) {
        status.textContent = error.message || 'The demo trade could not be created.';
        confirmButton.disabled = false;
        confirmButton.textContent = 'Create demo trade';
      }
    });
  }

  window.addEventListener('resize', () => {
    scheduleGeneralWorkspaceChartResize();
  });

  window.addEventListener('stockwatch:themechange', event => {
    if (!priceChart || !volumeChart) return;
    const theme = event.detail?.theme;
    const options = chartThemeOptions(theme);
    const seriesTheme = chartSeriesThemeOptions(theme);
    priceChart.applyOptions(options);
    volumeChart.applyOptions(options);
    candleSeries.applyOptions(seriesTheme.candles);
    lineSeries.applyOptions(seriesTheme.line);
    volumeSeries.setData(themedVolumeData(theme));
    if (rsiChart) {
      const rsiTheme = rsiSeriesThemeOptions(theme);
      rsiChart.applyOptions({
        ...options,
        timeScale: { ...options.timeScale, visible: true },
        handleScroll: false,
        handleScale: false
      });
      rsiSeries.applyOptions({ color: rsiTheme.line });
      rsiGuideLines[0]?.applyOptions({ color: rsiTheme.overboughtGuide });
      rsiGuideLines[1]?.applyOptions({ color: rsiTheme.midpoint });
      rsiGuideLines[2]?.applyOptions({ color: rsiTheme.oversoldGuide });
      updateRsiOverlay();
    }
    chartIndicatorPanels.forEach(panel => {
      panel.chart.applyOptions({
        ...options,
        timeScale: { ...options.timeScale, visible: true },
        handleScroll: false,
        handleScale: false
      });
    });
    scheduleAnchoredVolumeProfileRender();
  });

  document.querySelectorAll('[data-chart-mode]').forEach(button => {
    button.addEventListener('click', () => setChartMode(button.dataset.chartMode));
  });
  document.querySelectorAll('[data-chart-interval]').forEach(button => {
    button.addEventListener('click', () => changeInterval(button.dataset.chartInterval));
  });
  document.getElementById('elliottOverlayToggle').addEventListener('click', toggleElliottOverlays);
  document.getElementById('elliottSubwaveToggle').addEventListener('click', toggleElliottSubwaves);
  document.getElementById('harmonicOverlayToggle').addEventListener('click', toggleHarmonicOverlays);
  document.getElementById('candlestickOverlayToggle')
          .addEventListener('click', showHistoricalCandlestickViewPicker);
  document.getElementById('anchoredVolumeProfileToggle')
          .addEventListener('click', toggleAnchoredVolumeProfile);
  document.getElementById('rsiOverlayToggle')
          .addEventListener('click', toggleRsiOverlay);
  document.getElementById('volumeChartToggle')
          .addEventListener('click', toggleVolumeChart);
  document.querySelectorAll('[data-add-chart-indicator]').forEach(button => {
    button.addEventListener('click', () => openChartIndicatorDialog(button.dataset.addChartIndicator));
  });
  document.querySelectorAll('.chart-control-menu .chart-control-menu-item').forEach(button => {
    button.addEventListener('click', () => button.closest('.chart-control-menu')?.removeAttribute('open'));
  });
  document.addEventListener('click', event => {
    document.querySelectorAll('.chart-control-menu[open]').forEach(menu => {
      if (!menu.contains(event.target)) menu.removeAttribute('open');
    });
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    document.querySelectorAll('.chart-control-menu[open]').forEach(menu => menu.removeAttribute('open'));
  });
  document.getElementById('rsiPeriodForm')
          .addEventListener('submit', submitRsiPeriod);
  document.getElementById('closeRsiPeriodDialogBtn')
          .addEventListener('click', closeRsiPeriodDialog);
  document.getElementById('cancelRsiPeriodBtn')
          .addEventListener('click', closeRsiPeriodDialog);
  document.getElementById('chartIndicatorForm')
          .addEventListener('submit', submitChartIndicator);
  document.getElementById('closeChartIndicatorDialogBtn')
          .addEventListener('click', closeChartIndicatorDialog);
  document.getElementById('cancelChartIndicatorBtn')
          .addEventListener('click', closeChartIndicatorDialog);
  document.getElementById('followCongressionalActivityBtn')
          .addEventListener('click', toggleCongressionalActivityFollow);
  document.getElementById('showCongressionalHistoryBtn')
          .addEventListener('click', showCongressionalHistory);
  document.getElementById('closeCongressionalHistoryBtn')
          .addEventListener('click', () => document.getElementById('congressionalHistoryDialog').close());
  document.getElementById('followInsiderActivityBtn')
          .addEventListener('click', toggleInsiderActivityFollow);
  document.getElementById('showInsiderHistoryBtn')
          .addEventListener('click', showInsiderHistory);
  document.getElementById('closeInsiderHistoryBtn')
          .addEventListener('click', () => document.getElementById('insiderHistoryDialog').close());
  document.getElementById('closeHistoricalCandlestickViewBtn')
          .addEventListener('click', () => document.getElementById('historicalCandlestickViewDialog').close());
  document.querySelectorAll('[data-historical-candlestick-view]').forEach(button => {
    button.addEventListener('click', () => chooseHistoricalCandlestickView(
            button.dataset.historicalCandlestickView
    ));
  });
  document.getElementById('closeHistoricalCandlestickIntervalBtn')
          .addEventListener('click', () => document.getElementById('historicalCandlestickIntervalDialog').close());
  document.getElementById('closeHistoricalCandlestickLookbackBtn')
          .addEventListener('click', () => document.getElementById('historicalCandlestickLookbackDialog').close());
  document.getElementById('backToHistoricalCandlestickIntervalBtn')
          .addEventListener('click', showHistoricalCandlestickIntervalPicker);
  document.getElementById('historicalCandlestickLookbackInput')
          .addEventListener('input', updateHistoricalCandlestickLookbackPreview);
  document.getElementById('historicalCandlestickLookbackForm')
          .addEventListener('submit', submitHistoricalCandlestickLookback);
  document.getElementById('closeHistoricalCandlestickResultsBtn')
          .addEventListener('click', () => document.getElementById('historicalCandlestickResultsDialog').close());
  document.getElementById('changeHistoricalCandlestickIntervalBtn')
          .addEventListener('click', showHistoricalCandlestickIntervalPicker);
  document.querySelectorAll('[data-historical-candlestick-interval]').forEach(button => {
    button.addEventListener('click', () => showHistoricalCandlestickLookbackPicker(
            button.dataset.historicalCandlestickInterval
    ));
  });
  const historicalCandlestickCard = document.getElementById('historicalCandlestickHoverCard');
  historicalCandlestickCard.addEventListener('mouseenter', () => {
    historicalCandlestickCardHovered = true;
    clearTimeout(historicalCandlestickCardHideTimer);
  });
  historicalCandlestickCard.addEventListener('mouseleave', () => {
    historicalCandlestickCardHovered = false;
    if (!historicalCandlestickCardPinned) scheduleHistoricalCandlestickCardHide();
  });
  document.getElementById('historicalCandlestickCardClose')
          .addEventListener('click', hideHistoricalCandlestickCard);
  document.getElementById('previousHistoricalCandlestickSignal')
          .addEventListener('click', () => moveHistoricalCandlestickCard(-1));
  document.getElementById('nextHistoricalCandlestickSignal')
          .addEventListener('click', () => moveHistoricalCandlestickCard(1));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && activeHistoricalCandlestickSignals.length) {
      hideHistoricalCandlestickCard();
    }
  });
  bindUnsavedAlertGuards();

  window.onload = async () => {
    await initCharts();
    initializeResizableCharts();
    initializeStockWorkspaceTabs();
    initializeStockDemoTrading();
  };
