  const i18n = window.StockWatchI18n;
  const encodedTicker = encodeURIComponent(ticker);
  const csrfToken = document.querySelector('meta[name="_csrf"]').content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
  const stockPageLoader = document.getElementById('stockPageLoader');
  const stockLoaderStatus = document.getElementById('stockLoaderStatus');
  const stockWorkspace = document.querySelector('.stock-page');
  const stockLoaderBackground = stockPageLoader
    ? Array.from(document.body.children).filter(element => element !== stockPageLoader
      && !['SCRIPT', 'STYLE', 'LINK'].includes(element.tagName) && !element.inert)
    : [];
  stockLoaderBackground.forEach(element => { element.inert = true; });

  // Elliott structure labels stay English in the API (the script compares them); this shows their name.
  const ELLIOTT_STRUCTURE_KEYS = {
    'Motive 1-2-3-4-5': 'motive',
    'Motive diagonal 1-2-3-4-5': 'diagonal',
    'Motive leading diagonal 1-2-3-4-5': 'leadingDiagonal',
    'Motive ending diagonal 1-2-3-4-5': 'endingDiagonal',
    'Corrective zigzag A-B-C': 'zigzag',
    'Corrective flat A-B-C': 'flat',
    'Double zigzag W-X-Y': 'doubleZigzag',
    'Double-three combination W-X-Y': 'doubleThree',
    'Triple zigzag W-X-Y-X-Z': 'tripleZigzag',
    'Triple-three combination W-X-Y-X-Z': 'tripleThree',
    'Contracting triangle A-B-C-D-E': 'contractingTriangle',
    'Barrier triangle A-B-C-D-E': 'barrierTriangle',
    'Expanding triangle A-B-C-D-E': 'expandingTriangle',
    'Provisional motive 1-2-3-4-5': 'provisionalMotive',
    'Provisional corrective A-B-C': 'provisionalCorrective'
  };

  function structureName(label) {
    const key = ELLIOTT_STRUCTURE_KEYS[label];
    return key ? i18n.t(`sw.structure.${key}`) : label;
  }

  // Message-key suffix for a chart interval ('1d', '1wk', '1mo').
  function apiIntervalEnum(interval) {
    return ({ '1wk': 'WEEKLY', '1mo': 'MONTHLY' })[interval] || 'DAILY';
  }

  function tradeSignalDisplayLabel(value, lowercase = false) {
    const normalized = String(value || '').toUpperCase();
    if (normalized === 'SELL' || normalized === 'BUY') return i18n.t(lowercase ? `signalSideLower.${normalized}` : `signalSide.${normalized}`);
    return String(value || '');
  }

  document.querySelectorAll('[data-alert-signal="SELL"]').forEach(input => {
    const label = input.closest('label');
    const copy = label?.querySelector('.alert-eye-label');
    if (copy && copy.textContent.trim().toLowerCase() === i18n.t('sw.sellWord').toLowerCase()) copy.textContent = i18n.t('sw.sellShortWord');
    const ariaLabel = input.getAttribute('aria-label');
    if (ariaLabel) input.setAttribute('aria-label', ariaLabel.replace(new RegExp(i18n.t('sw.sellSignals'), 'gi'), i18n.t('sw.sellShortSignals')));
  });
  let stockLoaderFinished = false;
  const stockLoaderSafetyTimer = window.setTimeout(() => {
    finishStockPageLoading(i18n.t('sw.slowData'));
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
    stockLoaderBackground.forEach(element => { element.inert = false; });
    if (stockPageLoader) {
      stockPageLoader.classList.add('is-complete');
      window.setTimeout(() => stockPageLoader.remove(), 700);
    }
  }

  function secureJsonHeaders() {
    return { 'Content-Type': 'application/json', [csrfHeader]: csrfToken };
  }

  let priceChart;
  let drawingManager = null;
  let indicatorManager = null;
  const INITIAL_CANDLE_LIMIT = 1000;
  const INITIAL_VISIBLE_CANDLES = 150;
  const HISTORY_PAGE_LIMIT = 500;
  const HISTORY_PREFETCH_THRESHOLD = 120;

  let priceSeries, volumeSeries, elliottConfirmationSeries;
  let historicalElliottSeries = [];
  let historicalElliottGroups = new Map();
  const ELLIOTT_CANDIDATE_COLOR = '#94a3b8';
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
  let patternRangeLoader = null;
  let patternRangeTimer = null;
  let patternRangeSignature = '';

  function visiblePatternRange(paddingCandles = 20) {
    if (!globalCandleData.length) return null;
    const visible = priceChart?.timeScale().getVisibleLogicalRange();
    const times = priceChart?.timeScale().getVisibleRange?.();
    const boundary = (time, inclusive) => {
      const date = chartTimeKey(time);
      let low = 0;
      let high = globalCandleData.length;
      while (low < high) {
        const middle = (low + high) >>> 1;
        const candleDate = chartTimeKey(globalCandleData[middle].time);
        if (candleDate < date || inclusive && candleDate === date) low = middle + 1;
        else high = middle;
      }
      return low;
    };
    const first = Math.max(0, (times ? boundary(times.from, false) : Math.floor(visible?.from ?? 0)) - paddingCandles);
    const last = Math.min(globalCandleData.length - 1,
            (times ? boundary(times.to, true) - 1 : Math.ceil(visible?.to ?? globalCandleData.length - 1)) + paddingCandles);
    if (first > last) return null;
    return {from: Number(globalCandleData[first].timestamp), to: Number(globalCandleData[last].timestamp)};
  }

  function loadPatternRange(family, interval) {
    patternRangeLoader ||= new PatternRangeLoader(encodedTicker);
    return patternRangeLoader.load(family, interval, visiblePatternRange());
  }

  function schedulePatternRangeRefresh() {
    clearTimeout(patternRangeTimer);
    patternRangeTimer = setTimeout(() => {
      const range = visiblePatternRange();
      if (!range || isFetching) return;
      const signature = `${currentInterval}:${range.from}:${range.to}`;
      if (signature === patternRangeSignature) return;
      patternRangeSignature = signature;
      void Promise.all([
        refreshHistoricalElliottOverlays(currentInterval),
        refreshHistoricalCandlestickOverlay(currentInterval),
        refreshHarmonicOverlays(currentInterval)
      ]);
    }, 180);
  }
  let historyRetryTimer = null;
  let historyRetryAttempts = 0;
  let anchoredVolumeProfileEnabled = false;
  let anchoredVolumeProfile = null;
  let anchoredVolumeProfileAnchorTimestamp = null;
  let anchoredVolumeProfileRequestSequence = 0;
  let anchoredVolumeProfileRenderFrame = null;
  const ANCHORED_PROFILE_RIGHT_OFFSET_BARS = 30;
  let volumeChartEnabled = true;
  let chartType = 'candles';
  // The main series' type. A price-only type (Renko, Line break, Kagi, Point & Figure) shows its own
  // chart over the time-based one and leaves the main series, its indicators and drawings as they are.
  let seriesType = 'candles';
  let priceOnlyChart = null;
  let priceOnlySeries = null;
  let priceOnlyKind = null;
  let priceOnlyItems = [];
  let priceOnlyFitPending = false;
  const priceOnlySettings = { box: null, lines: 3 };
  const priceScaleSettings = { mode: 'normal', invert: false, auto: true };
  let splitMarkersEnabled = true;
  let splitData = [];
  // The whole chart's height when the user set one with the resize handle; otherwise it fits the window.
  // Panes share it, so adding indicators never makes the chart taller (TradingView works the same way).
  let chosenChartHeight = null;
  let fittedWindowHeight = null;
  const CHART_HEIGHT_MAX_DEFAULT = 900;
  let baselineFrame = null;
  let chartResizeObserver = null;
  let generalChartResizeFrame = null;

  let isFetching = false;
  let isLoadingOlder = false;
  let hasMoreHistory = true;
  let oldestTimestamp = null;
  let latestCandle = null;
  let stockCurrency = 'USD';

  let globalCandleData = [];
  let globalVolumeData = [];
  let seenDates = new Set();

  const persistedAlertState = new Map();
  let alertStateLoaded = false;
  let alertSaveInProgress = false;
  let alertSavePromise = null;
  let outlookFollowStateLoaded = false;
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
        fontFamily: '"Geist", ui-sans-serif, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif',
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

  const chartOptions = chartThemeOptions();

  const PRICE_SCALE_MODES = { normal: 'Normal', log: 'Logarithmic', percent: 'Percentage', indexed: 'IndexedTo100' };

  function priceColors(theme = document.documentElement.dataset.theme) {
    const seriesTheme = chartSeriesThemeOptions(theme);
    return {
      up: seriesTheme.candles.upColor,
      down: seriesTheme.candles.downColor,
      line: seriesTheme.line.color,
      neutral: theme === 'light' ? '#2869bc' : '#60a5fa'
    };
  }

  function formatPlainPrice(price) {
    const value = Number(price);
    return i18n.number(value, Math.abs(value) < 1 ? 4 : 2);
  }

  /** Price-pane height in pixels; overlays positioned over the price pane stay inside it. */
  function pricePaneHeight() {
    try {
      const height = priceChart.panes()[0].getHeight();
      if (Number.isFinite(height) && height > 0) return height;
    } catch (ignored) {
      // Before the first layout the stage height is the best estimate.
    }
    return document.getElementById('priceChartStage').clientHeight;
  }

  function showChartStatus(message) {
    const status = document.getElementById('chartStatus');
    if (!status) return;
    status.textContent = message || '';
    clearTimeout(showChartStatus.timer);
    if (message) showChartStatus.timer = setTimeout(() => { status.textContent = ''; }, 6000);
  }

  // ---- main price series and chart types

  function setPriceSeriesData() {
    if (!priceSeries) return;
    priceSeries.setData(StockWatchChartTypes.data(seriesType, globalCandleData, priceColors()));
    if (seriesType === 'baseline') scheduleBaselineUpdate();
    reanchorPriceOverlays();
    renderPriceOnlyChart();
  }

  /** Live quotes move only the newest bar; Heikin Ashi needs the whole history to stay consistent. */
  /** The price axis shows the currency; volume and indicator panes keep their own number formats. */
  function priceSeriesFormat() {
    // Phones need the axis width for the chart, so the currency is left off there.
    const narrow = window.matchMedia?.('(max-width: 600px)');
    return {
      type: 'custom',
      minMove: 0.01,
      formatter: price => (narrow?.matches ? i18n.number(price, 2) : `${i18n.number(price, 2)} ${stockCurrency}`)
    };
  }

  function createPriceSeries() {
    const series = StockWatchChartTypes.create(priceChart, seriesType, priceColors(), { priceFormat: priceSeriesFormat() });
    // Indicators and overlays draw above the price.
    series.setSeriesOrder?.(0);
    series.attachPrimitive?.(priceRepaintHook);
    return series;
  }

  // ---- overlays that follow the price scale

  // Elliott and harmonic points, candlestick hit areas and the volume profile are HTML or their own canvas. The price
  // pane draws this view on every repaint, scale changes (invert, log, percentage) included, and they follow.
  const PRICE_REPAINT_VIEWS = [{ zOrder: () => 'bottom', renderer: () => ({ draw: () => followPriceRepaint() }) }];
  const priceRepaintHook = { paneViews: () => PRICE_REPAINT_VIEWS };
  let priceRepaintQueued = false;

  function followPriceRepaint() {
    if (priceRepaintQueued) return;
    priceRepaintQueued = true;
    queueMicrotask(() => {
      priceRepaintQueued = false;
      scheduleElliottHitTargetPositioning();
      scheduleHarmonicHitTargetPositioning();
      scheduleHistoricalCandlestickHitTargetPositioning();
      if (anchoredVolumeProfileEnabled) scheduleAnchoredVolumeProfileRender();
    });
  }

  const anchoredPriceOverlays = new Set();
  let priceAnchorCache = { candles: null, length: -1, type: null, lastClose: null, anchors: [] };

  /** The main series' bars as {time, close}: what overlay lines on its scale are measured from. */
  function priceAnchors() {
    const cache = priceAnchorCache;
    const lastClose = globalCandleData[globalCandleData.length - 1]?.close ?? null;
    if (cache.candles !== globalCandleData || cache.length !== globalCandleData.length || cache.type !== seriesType
            || cache.lastClose !== lastClose) {
      const points = StockWatchChartTypes.data(seriesType, globalCandleData, priceColors());
      priceAnchorCache = {
        candles: globalCandleData, length: globalCandleData.length, type: seriesType, lastClose,
        anchors: points.map(point => ({ time: point.time, close: Number.isFinite(point.close) ? point.close : point.value }))
      };
    }
    return priceAnchorCache.anchors;
  }

  /**
   * A line on the price scale (Elliott waves, harmonic patterns, candlestick trends) that stays with the candles in
   * every scale mode; a plain line series is measured from its own first point in percentage and indexed scales.
   */
  function addPriceOverlayLine(options) {
    const series = StockWatchChartTypes.addAnchoredLine(priceChart, options, priceAnchors);
    anchoredPriceOverlays.add(series);
    return series;
  }

  function reanchorPriceOverlays() {
    anchoredPriceOverlays.forEach(series => {
      try { series.reanchor(); } catch (error) { anchoredPriceOverlays.delete(series); }
    });
  }

  function setChartType(type, persist = true) {
    const next = StockWatchChartTypes.TYPES.includes(type) ? type : 'candles';
    chartType = next;
    document.querySelectorAll('[data-chart-type]').forEach(button => {
      const active = button.dataset.chartType === next;
      // Not "active": that class marks menus with an overlay switched on.
      button.classList.toggle('is-selected', active);
      button.setAttribute('aria-pressed', String(active));
    });
    document.getElementById('chartTypeLabel').textContent = i18n.t(`sw.chartType.${next}`);
    if (persist) indicatorManager?.setExtra('chartType', next);
    if (!priceChart) return;
    // Candlestick signals describe real candles, so they need a chart that draws them.
    if (historicalCandlestickOverlayEnabled && !StockWatchChartTypes.isOhlc(next)) {
      disableHistoricalCandlestickOverlay();
    }
    if (StockWatchChartTypes.isPriceOnly(next)) {
      showPriceOnlyView();
      return;
    }
    hidePriceOnlyView();
    if (next === seriesType) return;
    seriesType = next;
    const previous = priceSeries;
    priceSeries = createPriceSeries();
    setPriceSeriesData();
    drawingManager?.setSeries(priceSeries);
    if (previous) priceChart.removeSeries(previous);
    indicatorManager?.setPriceSeries(priceSeries);
    applySplitMarkers();
    applyPriceScale();
    scheduleAnchoredVolumeProfileRender();
    scheduleElliottHitTargetPositioning();
    scheduleHistoricalCandlestickHitTargetPositioning();
    scheduleHarmonicHitTargetPositioning();
  }

  // ---- price-only view: Renko, Line break, Kagi, Point & Figure

  function priceOnlyActive() {
    return Boolean(priceOnlyChart) && StockWatchChartTypes.isPriceOnly(chartType);
  }

  /** Price-only positions are numbered; their labels show the date of the candle that completed each one. */
  function priceOnlyDate(time) {
    const item = priceOnlyItems[StockWatchChartTypes.elementIndex(time)];
    if (!item) return '';
    // "25 Oct '22", like the drawing tools' date labels.
    const date = new Date(item.timestamp * 1000);
    return `${date.getUTCDate()} ${i18n.date(date, { month: 'short', timeZone: 'UTC' })} '${String(date.getUTCFullYear()).slice(-2)}`;
  }

  function ensurePriceOnlyChart() {
    if (priceOnlyChart) return;
    priceOnlyChart = StockWatchCharts.createChart(document.getElementById('priceOnlyChartContainer'), {
      ...chartOptions,
      timeScale: { ...chartOptions.timeScale, tickMarkFormatter: time => priceOnlyDate(time) },
      localization: { timeFormatter: time => priceOnlyDate(time) }
    });
  }

  function showPriceOnlyView() {
    ensurePriceOnlyChart();
    // Show the newest elements once data is there (it may still be loading).
    priceOnlyFitPending = true;
    document.getElementById('priceChartStage').classList.add('price-only-view');
    document.querySelector('.chart-workbench-body').classList.add('price-only-view');
    document.getElementById('priceOnlyChartContainer').hidden = false;
    document.getElementById('priceOnlyLegend').hidden = false;
    drawingManager?.setMode?.('cursor');
    priceOnlyKind = null;
    renderPriceOnlyChart();
    applyPriceScale();
    updateStageHeight();
  }

  function hidePriceOnlyView() {
    const stage = document.getElementById('priceChartStage');
    if (!stage.classList.contains('price-only-view')) return;
    stage.classList.remove('price-only-view');
    document.querySelector('.chart-workbench-body').classList.remove('price-only-view');
    document.getElementById('priceOnlyChartContainer').hidden = true;
    document.getElementById('priceOnlyLegend').hidden = true;
    updateStageHeight();
  }

  function renderPriceOnlyChart() {
    if (!priceOnlyActive() || !globalCandleData.length) return;
    const result = StockWatchChartTypes.priceOnly(chartType, globalCandleData, priceOnlySettings);
    priceOnlyItems = result.items;
    if (priceOnlyKind !== result.series) {
      if (priceOnlySeries) priceOnlyChart.removeSeries(priceOnlySeries);
      const colors = priceColors();
      priceOnlySeries = result.series === 'candles'
              ? priceOnlyChart.addSeries(LightweightCharts.CandlestickSeries,
                      { ...StockWatchChartTypes.options('candles', colors), priceFormat: priceSeriesFormat() })
              : priceOnlyChart.addCustomSeries(result.series === 'kagi'
                      ? new StockWatchChartTypes.KagiView() : new StockWatchChartTypes.PointFigureView(),
                      { upColor: colors.up, downColor: colors.down, priceFormat: priceSeriesFormat(), priceLineVisible: false });
      priceOnlyKind = result.series;
    }
    priceOnlySeries.setData(result.data);
    if (priceOnlyFitPending && priceOnlyItems.length) {
      priceOnlyFitPending = false;
      priceOnlyChart.timeScale().setVisibleLogicalRange({ from: Math.max(0, priceOnlyItems.length - 120), to: priceOnlyItems.length - 1 + 3 });
    }
    renderPriceOnlyLegend(result);
  }

  function renderPriceOnlyLegend(result) {
    const legend = document.getElementById('priceOnlyLegend');
    const title = document.createElement('strong');
    title.textContent = `${ticker} · ${i18n.t(`draw.interval.${currentInterval}`)} · ${i18n.t(`sw.chartType.${chartType}`)}`;
    const field = document.createElement('label');
    field.className = 'price-only-field';
    const caption = document.createElement('span');
    const input = document.createElement('input');
    input.type = 'number';
    input.min = chartType === 'lineBreak' ? '1' : '0';
    input.max = chartType === 'lineBreak' ? '10' : '';
    input.step = chartType === 'lineBreak' ? '1' : 'any';
    input.inputMode = 'decimal';
    if (chartType === 'lineBreak') {
      caption.textContent = i18n.t('sw.priceOnly.lines');
      input.value = String(priceOnlySettings.lines);
    } else {
      caption.textContent = i18n.t('sw.priceOnly.box');
      input.placeholder = i18n.t('sw.priceOnly.auto', formatPlainPrice(result.box));
      input.value = priceOnlySettings.box ? String(priceOnlySettings.box) : '';
      input.setAttribute('aria-describedby', 'priceOnlyNote');
    }
    input.addEventListener('change', () => {
      const value = Number(input.value);
      if (chartType === 'lineBreak') {
        priceOnlySettings.lines = Number.isInteger(value) && value >= 1 && value <= 10 ? value : 3;
      } else {
        priceOnlySettings.box = Number.isFinite(value) && value > 0 ? value : null;
      }
      indicatorManager?.setExtra('priceOnly', { ...priceOnlySettings });
      renderPriceOnlyChart();
    });
    field.append(caption, input);
    const note = document.createElement('span');
    note.id = 'priceOnlyNote';
    note.className = 'price-only-note';
    note.textContent = i18n.t(chartType === 'pointFigure' ? 'sw.priceOnly.notePointFigure' : 'sw.priceOnly.note');
    legend.replaceChildren(title, field, note);
  }

  /** A baseline chart splits above and below the close of the first visible candle. */
  function scheduleBaselineUpdate() {
    if (seriesType !== 'baseline' || baselineFrame !== null) return;
    baselineFrame = requestAnimationFrame(() => {
      baselineFrame = null;
      const range = priceChart?.timeScale().getVisibleLogicalRange();
      if (!range || !globalCandleData.length || seriesType !== 'baseline') return;
      const index = Math.max(0, Math.min(globalCandleData.length - 1, Math.ceil(range.from)));
      priceSeries.applyOptions({ baseValue: { type: 'price', price: globalCandleData[index].close } });
    });
  }

  // ---- price scale

  function applyPriceScale() {
    if (!priceChart) return;
    const mode = LightweightCharts.PriceScaleMode[PRICE_SCALE_MODES[priceScaleSettings.mode] || 'Normal'];
    const scale = { mode, invertScale: priceScaleSettings.invert, autoScale: priceScaleSettings.auto };
    priceChart.priceScale('right').applyOptions(scale);
    priceOnlyChart?.priceScale('right').applyOptions(scale);
    updatePriceScaleButtons();
  }

  function updatePriceScaleButtons() {
    if (priceChart) {
      // Dragging the price axis switches automatic scaling off inside the chart.
      priceScaleSettings.auto = priceChart.priceScale('right').options().autoScale !== false;
    }
    document.querySelectorAll('[data-chart-scale]').forEach(button => {
      const key = button.dataset.chartScale;
      const active = key === 'invert' ? priceScaleSettings.invert
              : (key === 'auto' ? priceScaleSettings.auto : priceScaleSettings.mode === key);
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
  }

  function togglePriceScale(key) {
    if (key === 'invert') priceScaleSettings.invert = !priceScaleSettings.invert;
    else if (key === 'auto') priceScaleSettings.auto = true;
    else priceScaleSettings.mode = priceScaleSettings.mode === key ? 'normal' : key;
    applyPriceScale();
    indicatorManager?.setExtra('scale', { mode: priceScaleSettings.mode, invert: priceScaleSettings.invert });
  }

  // ---- date ranges

  /** Loads older pages until {@code seconds} is on the chart or the history ends. */
  async function ensureHistoryFrom(seconds) {
    const interval = currentInterval;
    let attempts = 0;
    let announced = false;
    while (attempts++ < 400 && hasMoreHistory && currentInterval === interval
            && globalCandleData.length && globalCandleData[0].timestamp > seconds) {
      if (!announced) {
        showChartStatus(i18n.t('sw.loadingOlderHistory'));
        announced = true;
      }
      if (isLoadingOlder || isFetching) {
        await new Promise(resolve => setTimeout(resolve, 150));
        continue;
      }
      const before = globalCandleData.length;
      await loadOlderData({ refreshOverlays: false });
      if (globalCandleData.length === before && !hasMoreHistory) break;
    }
    if (announced) {
      showChartStatus('');
      await Promise.all([
        refreshHistoricalElliottOverlays(interval),
        refreshHistoricalCandlestickOverlay(interval),
        refreshHarmonicOverlays(interval)
      ]);
    }
  }

  async function showDateRange(preset) {
    if (!globalCandleData.length) return;
    const last = globalCandleData[globalCandleData.length - 1].timestamp;
    const start = StockWatchChartTypes.rangeStart(preset, last);
    await ensureHistoryFrom(start ?? Number.NEGATIVE_INFINITY);
    if (priceOnlyActive()) {
      const first = start === null ? 0 : priceOnlyItems.findIndex(item => item.timestamp >= start);
      const from = first < 0 ? Math.max(0, priceOnlyItems.length - 1) : first;
      priceOnlyChart.timeScale().setVisibleLogicalRange({ from, to: priceOnlyItems.length - 1 + 3 });
      return;
    }
    if (start === null) {
      priceChart.timeScale().setVisibleLogicalRange({ from: 0, to: globalCandleData.length - 1 + 3 });
      return;
    }
    const index = StockWatchChartTypes.nearestIndex(globalCandleData.map(candle => candle.timestamp), start);
    priceChart.timeScale().setVisibleLogicalRange({ from: index, to: globalCandleData.length - 1 + 3 });
  }

  /** Opened from a chart alert in Latest signals: show the candle that triggered it, then mark it read. */
  async function openChartAlertFromUrl() {
    const id = new URLSearchParams(window.location.search).get('chartAlert');
    if (!/^\d{1,18}$/.test(id || '')) return;
    try {
      const response = await fetch(`/api/chart-alerts/events/${id}`);
      if (response.ok) {
        const trigger = await response.json();
        const interval = { DAILY: '1d', WEEKLY: '1wk', MONTHLY: '1mo' }[trigger.interval] || '1d';
        if (interval !== currentInterval) await changeInterval(interval);
        await ensureHistoryFrom(trigger.candleTimestamp);
        const index = StockWatchChartTypes.nearestIndex(globalCandleData.map(candle => candle.timestamp), trigger.candleTimestamp);
        if (index >= 0) {
          const range = priceChart.timeScale().getVisibleLogicalRange();
          const span = range ? Math.max(20, range.to - range.from) : INITIAL_VISIBLE_CANDLES;
          priceChart.timeScale().setVisibleLogicalRange({ from: index - span * 0.7, to: index + span * 0.3 });
        }
        showChartStatus(i18n.t('alert.status.opened', trigger.label));
        await fetch(`/api/chart-alerts/events/${id}/read`, { method: 'POST', headers: secureJsonHeaders() });
      }
    } catch (error) {
      console.warn('Chart alert could not be opened', error);
    }
    // The address keeps no reference to the alert once it has been shown.
    const url = new URL(window.location.href);
    url.searchParams.delete('chartAlert');
    window.history.replaceState(null, '', url);
  }

  async function goToDate(event) {
    event.preventDefault();
    const value = document.getElementById('goToDateInput').value;
    const seconds = Date.parse(`${value}T00:00:00Z`) / 1000;
    if (!Number.isFinite(seconds) || !globalCandleData.length) return;
    await ensureHistoryFrom(seconds);
    if (seconds < globalCandleData[0].timestamp) showChartStatus(i18n.t('sw.dateBeforeHistory'));
    const chart = priceOnlyActive() ? priceOnlyChart : priceChart;
    const times = priceOnlyActive() ? priceOnlyItems.map(item => item.timestamp) : globalCandleData.map(candle => candle.timestamp);
    const index = StockWatchChartTypes.nearestIndex(times, seconds);
    const range = chart.timeScale().getVisibleLogicalRange();
    const span = range ? Math.max(20, range.to - range.from) : INITIAL_VISIBLE_CANDLES;
    chart.timeScale().setVisibleLogicalRange({ from: index - span / 2, to: index + span / 2 });
  }

  // ---- screenshot, full screen, data window

  function saveChartScreenshot() {
    if (!priceChart) return;
    const stage = document.getElementById('priceChartStage');
    const priceOnly = priceOnlyActive();
    const chartCanvas = (priceOnly ? priceOnlyChart : priceChart).takeScreenshot(true);
    const container = document.getElementById(priceOnly ? 'priceOnlyChartContainer' : 'priceChartContainer');
    const ratio = chartCanvas.width / Math.max(1, container.clientWidth);
    const header = 30;
    const canvas = document.createElement('canvas');
    canvas.width = chartCanvas.width;
    canvas.height = chartCanvas.height + Math.round(header * ratio);
    const context = canvas.getContext('2d');
    const styles = getComputedStyle(document.body);
    context.fillStyle = StockWatchCharts.isLight() ? '#ffffff' : '#0d1117';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(chartCanvas, 0, Math.round(header * ratio));
    context.scale(ratio, ratio);
    context.fillStyle = styles.color || (StockWatchCharts.isLight() ? '#182230' : '#e8edf3');
    context.font = '600 13px "Geist", sans-serif';
    const stamp = i18n.date(new Date(), { year: 'numeric', month: 'short', day: 'numeric' });
    const typeName = priceOnly ? ` · ${i18n.t(`sw.chartType.${chartType}`)}` : '';
    context.fillText(`${ticker} · ${i18n.t(`draw.interval.${currentInterval}`)}${typeName} · ${stamp} · StockWatch 24/7`, 10, 20);
    // Legends are page elements, so their text is written onto the image at the same place.
    context.font = '500 11px "Geist", sans-serif';
    if (!priceOnly) stage.querySelectorAll('.ind-legend:not([hidden])').forEach(legend => {
      const top = parseFloat(legend.style.top) || 0;
      const left = parseFloat(legend.style.left) || 0;
      [...legend.querySelectorAll('.ind-legend-row')].forEach((row, index) => {
        const name = row.querySelector('.ind-legend-name')?.textContent || '';
        const values = [...row.querySelectorAll('.ind-legend-value')].map(item => item.textContent).join('  ');
        context.fillText(`${name}  ${values}`.trim(), left + 2, header + top + 13 + index * 18);
      });
    });
    canvas.toBlob(blob => {
      if (!blob) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${ticker}-${currentInterval}-${new Date().toISOString().slice(0, 10)}.png`;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
    }, 'image/png');
  }

  function chartIsFullscreen() {
    return document.fullscreenElement === document.getElementById('priceChartPanel');
  }

  async function toggleChartFullscreen() {
    try {
      if (chartIsFullscreen()) await document.exitFullscreen();
      else await document.getElementById('priceChartPanel').requestFullscreen();
    } catch (error) {
      showChartStatus(i18n.t('sw.fullscreenUnavailable'));
    }
  }

  function onChartFullscreenChange() {
    const active = chartIsFullscreen();
    const button = document.getElementById('chartFullscreenButton');
    button.setAttribute('aria-pressed', String(active));
    button.title = i18n.t(active ? 'sw.exitFullscreen' : 'sw.fullscreen');
    button.setAttribute('aria-label', button.title);
    updateStageHeight();
  }

  function toggleDataWindow() {
    if (!indicatorManager) return;
    const open = indicatorManager.toggleDataWindow();
    document.getElementById('dataWindowToggle').setAttribute('aria-pressed', String(open));
    indicatorManager.setExtra('dataWindow', open);
  }

  // ---- stage height: one height for the whole chart, whatever panes it has

  /** Without a chosen height the chart fills the window below the panel heading, with room for its footer. */
  function defaultChartHeight() {
    fittedWindowHeight ??= window.innerHeight;
    const narrow = window.matchMedia?.('(max-width: 699px)').matches === true;
    return Math.round(Math.min(CHART_HEIGHT_MAX_DEFAULT, Math.max(narrow ? 360 : 420, fittedWindowHeight - 180)));
  }

  /** Phone address bars change the window height while scrolling; only a real resize refits the chart. */
  function refitChartToWindow() {
    if (chosenChartHeight !== null || fittedWindowHeight === null
        || Math.abs(window.innerHeight - fittedWindowHeight) < 150) return false;
    fittedWindowHeight = window.innerHeight;
    updateStageHeight();
    return true;
  }

  function currentChartHeight() {
    return chosenChartHeight ?? defaultChartHeight();
  }

  function updateStageHeight() {
    const stage = document.getElementById('priceChartStage');
    if (!stage) return;
    stage.style.height = chartIsFullscreen() ? '' : `${currentChartHeight()}px`;
    const handle = document.getElementById('priceChartPanel').querySelector('.chart-resize-handle');
    handle?.setAttribute('aria-valuenow', String(currentChartHeight()));
    scheduleGeneralWorkspaceChartResize();
  }

  // ---- split markers

  async function loadSplitMarkers(interval) {
    let splits = [];
    try {
      const response = await fetch(`/api/stocks/${encodedTicker}/splits?interval=${encodeURIComponent(interval)}`);
      if (response.ok) splits = await response.json();
    } catch (ignored) {
      // Markers are optional context; the chart works without them.
    }
    if (interval !== currentInterval) return;
    splitData = Array.isArray(splits) ? splits : [];
    applySplitMarkers();
  }

  function applySplitMarkers() {
    if (!priceSeries?.setMarkers) return;
    const markers = [];
    if (splitMarkersEnabled) {
      splitData.forEach(split => {
        const candle = globalCandleData.find(item => item.timestamp > Number(split.lastTimestamp));
        if (!candle) return;
        markers.push({
          time: candle.time,
          position: 'aboveBar',
          shape: 'square',
          size: 0.8,
          color: '#ffbd59',
          text: i18n.t('sw.splitMarker', split.numerator, split.denominator)
        });
      });
    }
    priceSeries.setMarkers(markers);
  }

  function updateSplitMarkersToggle() {
    const button = document.getElementById('splitMarkersToggle');
    button.classList.toggle('is-selected', splitMarkersEnabled);
    button.setAttribute('aria-pressed', String(splitMarkersEnabled));
  }

  function toggleSplitMarkers() {
    splitMarkersEnabled = !splitMarkersEnabled;
    updateSplitMarkersToggle();
    applySplitMarkers();
    indicatorManager?.setExtra('splits', splitMarkersEnabled);
  }

  // ---- volume pane

  function volumeHooks() {
    return {
      onCreate: series => { volumeSeries = series; },
      onRemove: () => { volumeSeries = null; }
    };
  }

  function updateVolumeChartToggle() {
    const button = document.getElementById('volumeChartToggle');
    button.classList.toggle('active', volumeChartEnabled);
    button.setAttribute('aria-pressed', String(volumeChartEnabled));
    button.textContent = i18n.t(volumeChartEnabled ? 'sw.volumeOn' : 'sw.volume');
  }

  function toggleVolumeChart() {
    volumeChartEnabled = !volumeChartEnabled;
    indicatorManager?.setVolume(volumeChartEnabled, volumeHooks());
    updateVolumeChartToggle();
    indicatorManager?.setExtra('volume', volumeChartEnabled);
  }

  // ---- saved chart preferences (kept with the indicator layout)

  function applyChartPreferences(extras = {}) {
    const wide = window.matchMedia?.('(min-width: 700px)').matches !== false;
    if (wide && Number.isFinite(extras.chartHeight)) {
      chosenChartHeight = Math.min(1200, Math.max(260, extras.chartHeight));
    } else if (wide && Number.isFinite(extras.priceHeight) && extras.priceHeight > defaultChartHeight()) {
      // Saved before panes shared the chart: it was the price pane's height, so only a taller one is kept.
      chosenChartHeight = Math.min(1200, extras.priceHeight);
    }
    if (typeof extras.volume === 'boolean' && extras.volume !== volumeChartEnabled) {
      volumeChartEnabled = extras.volume;
      indicatorManager.setVolume(volumeChartEnabled, volumeHooks());
      updateVolumeChartToggle();
    }
    if (extras.scale && typeof extras.scale === 'object') {
      priceScaleSettings.mode = Object.hasOwn(PRICE_SCALE_MODES, extras.scale.mode) ? extras.scale.mode : 'normal';
      priceScaleSettings.invert = extras.scale.invert === true;
      applyPriceScale();
    }
    if (typeof extras.splits === 'boolean') {
      splitMarkersEnabled = extras.splits;
      updateSplitMarkersToggle();
      applySplitMarkers();
    }
    if (extras.dataWindow === true) {
      indicatorManager.toggleDataWindow(true);
      document.getElementById('dataWindowToggle').setAttribute('aria-pressed', 'true');
    }
    if (extras.priceOnly && typeof extras.priceOnly === 'object') {
      const box = Number(extras.priceOnly.box);
      const lines = Number(extras.priceOnly.lines);
      priceOnlySettings.box = Number.isFinite(box) && box > 0 ? box : null;
      priceOnlySettings.lines = Number.isInteger(lines) && lines >= 1 && lines <= 10 ? lines : 3;
    }
    if (typeof extras.chartType === 'string') setChartType(extras.chartType, false);
    updateStageHeight();
  }

  // ---- compare

  function initializeCompareDialog() {
    const dialog = document.getElementById('compareSymbolDialog');
    const input = document.getElementById('compareSymbolInput');
    const results = document.getElementById('compareSymbolResults');
    const error = document.getElementById('compareSymbolError');
    let searchTimer = null;
    let searchSequence = 0;

    const add = value => {
      const symbol = String(value || '').trim().toUpperCase();
      if (!/^[A-Z0-9.^=-]{1,20}$/.test(symbol)) {
        error.textContent = i18n.t('sw.compare.invalid');
        input.focus();
        return;
      }
      if (symbol === String(ticker).toUpperCase()) {
        error.textContent = i18n.t('sw.compare.same');
        input.focus();
        return;
      }
      if (!indicatorManager?.add('COMPARE', { inputs: { symbol } })) {
        error.textContent = i18n.t('sw.compare.failed');
        return;
      }
      dialog.close();
      showChartStatus(i18n.t('sw.compare.added', symbol));
    };

    const search = async query => {
      const sequence = ++searchSequence;
      try {
        const response = await fetch(`/api/stocks/search/local?q=${encodeURIComponent(query)}`);
        const items = response.ok ? await response.json() : [];
        if (sequence !== searchSequence) return;
        results.replaceChildren(...items.slice(0, 8).map(item => {
          const symbol = String(item.symbol || '');
          const option = document.createElement('button');
          option.type = 'button';
          option.className = 'compare-symbol-option';
          option.setAttribute('role', 'option');
          const code = document.createElement('strong');
          code.textContent = symbol;
          const name = document.createElement('span');
          name.textContent = String(item.name || '');
          option.append(code, name);
          option.addEventListener('click', () => add(symbol));
          return option;
        }));
      } catch (ignored) {
        results.replaceChildren();
      }
    };

    document.getElementById('compareSymbolButton').addEventListener('click', () => {
      input.value = '';
      error.textContent = '';
      results.replaceChildren();
      dialog.showModal();
      input.focus();
    });
    input.addEventListener('input', () => {
      error.textContent = '';
      clearTimeout(searchTimer);
      const query = input.value.trim();
      if (query.length < 1) {
        results.replaceChildren();
        return;
      }
      searchTimer = setTimeout(() => search(query), 200);
    });
    document.getElementById('compareSymbolForm').addEventListener('submit', event => {
      event.preventDefault();
      add(input.value);
    });
    for (const id of ['closeCompareSymbolDialogBtn', 'cancelCompareSymbolBtn']) {
      document.getElementById(id).addEventListener('click', () => dialog.close());
    }
  }

  // ---- drawings and indicators

  function chartBars() {
    return globalCandleData.map((candle, index) => ({
      time: candle.timestamp,
      open: candle.open,
      high: candle.high,
      low: candle.low,
      close: candle.close,
      volume: globalVolumeData[index]?.value
    }));
  }

  function createDrawingManager(stage) {
    if (typeof StockWatchDrawing === 'undefined') return null;
    // Drawings used to be kept per interval in this browser; they move into the account once.
    const legacyKeys = ['1d', '1wk', '1mo'].map(interval => ({ key: `stock:${ticker}:${interval}`, interval }));
    const manager = new StockWatchDrawing.DrawingManager({
      chart: priceChart,
      series: priceSeries,
      container: stage,
      store: new StockWatchDrawing.ServerDrawingStore(ticker, legacyKeys),
      interval: currentInterval,
      bars: chartBars,
      priceFormatter: formatPlainPrice,
      features: { alerts: typeof StockWatchChartAlerts !== 'undefined', demoTrade: true },
      toolbarHost: document.getElementById('drawingToolbarHost')
    });
    manager.on('demo-trade', ({ side }) => openStockDemoTradeDialog(side, true));
    manager.on('alert-request', drawing => {
      if (typeof StockWatchChartAlerts !== 'undefined') StockWatchChartAlerts.openForDrawing(drawing);
    });
    return manager;
  }

  function createIndicatorManager(stage) {
    if (typeof StockWatchIndicatorManager === 'undefined') return null;
    const manager = new StockWatchIndicatorManager({
      chart: priceChart,
      priceSeries,
      stage,
      symbol: ticker,
      interval: currentInterval,
      candles: () => globalCandleData,
      anchors: priceAnchors,
      volume: () => themedVolumeData(),
      priceFormatter: formatPlainPrice,
      drawings: drawingManager,
      onPanesChanged: () => updateStageHeight()
    });
    manager.onLayoutLoaded = applyChartPreferences;
    manager.onStatus = showChartStatus;
    if (typeof StockWatchChartAlerts !== 'undefined') {
      manager.onAlertRequest = config => StockWatchChartAlerts.openForIndicator(config);
    }
    manager.setVolume(volumeChartEnabled, volumeHooks());
    manager.setPriceInfo(priceLegendInfo);
    return manager;
  }

  function candleIndexAt(time) {
    const key = chartTimeKey(time);
    let low = 0;
    let high = globalCandleData.length - 1;
    while (low <= high) {
      const middle = (low + high) >> 1;
      const value = globalCandleData[middle].time;
      if (value === key) return middle;
      if (value < key) low = middle + 1; else high = middle - 1;
    }
    return -1;
  }

  /** The price legend's first row: symbol, interval and the candle under the crosshair (else the latest). */
  function priceLegendInfo(param) {
    const title = `${ticker} · ${i18n.t(`draw.interval.${currentInterval}`)}`;
    if (!globalCandleData.length) return { title };
    let index = param?.time ? candleIndexAt(param.time) : -1;
    if (index < 0) index = globalCandleData.length - 1;
    const candle = globalCandleData[index];
    const previous = index > 0 ? globalCandleData[index - 1].close : candle.open;
    const change = previous ? (candle.close - previous) / previous * 100 : 0;
    const colors = priceColors();
    const tone = candle.close >= candle.open ? colors.up : colors.down;
    return {
      title,
      values: [
        { label: i18n.t('sw.legend.open'), value: formatPlainPrice(candle.open), color: tone },
        { label: i18n.t('sw.legend.high'), value: formatPlainPrice(candle.high), color: tone },
        { label: i18n.t('sw.legend.low'), value: formatPlainPrice(candle.low), color: tone },
        { label: i18n.t('sw.legend.close'), value: formatPlainPrice(candle.close), color: tone },
        { value: `${change > 0 ? '+' : ''}${i18n.number(change, 2)}%`, color: change >= 0 ? colors.up : colors.down }
      ]
    };
  }

  /** Overlays toggled from the toolbar appear in the price legend with a remove button. */
  function renderChartOverlayChips() {
    const items = [];
    if (historicalCandlestickOverlayEnabled) {
      items.push({
        label: i18n.t('sw.candlestickSignals'),
        removeLabel: i18n.t('sw.removeCandlestickSignals'),
        onRemove: () => {
          disableHistoricalCandlestickOverlay();
          document.getElementById('candlestickOverlayToggle').focus({ preventScroll: true });
        }
      });
    }
    indicatorManager?.setOverlays(items);
  }

  async function initCharts() {
    let completionMessage = i18n.t('sw.ready');
    try {
      updateStockLoadingStatus(i18n.t('sw.preparingCharts'));
      const stage = document.getElementById('priceChartStage');
      stage.style.height = `${currentChartHeight()}px`;
      // One chart holds the price, volume and indicator panes, so they share the time axis and crosshair.
      priceChart = StockWatchCharts.createChart(document.getElementById('priceChartContainer'), {
        ...chartOptions,
        layout: { ...chartOptions.layout, panes: { enableResize: true } }
      });
      const removeChartSeries = priceChart.removeSeries.bind(priceChart);
      priceChart.removeSeries = series => {
        anchoredPriceOverlays.delete(series);
        removeChartSeries(series);
      };

      priceChart.timeScale().subscribeVisibleLogicalRangeChange(range => {
        maybeLoadOlderForVisibleRange(range);
        schedulePatternRangeRefresh();
        scheduleBaselineUpdate();
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

      priceSeries = createPriceSeries();
      if (typeof StockWatchElliottHierarchyOverlay !== 'undefined') {
        elliottHierarchyOverlay = new StockWatchElliottHierarchyOverlay({
          chart: priceChart,
          container: stage,
          symbol: ticker,
          interval: currentInterval,
          color: ELLIOTT_SUBWAVE_COLOR,
          renderTime: timestampToChartDate,
          statusElement: document.getElementById('elliottOverlayStatus'),
          onWaveClick: showElliottHierarchyWaveCard,
          createLine: options => addPriceOverlayLine(options)
        });
      }
      // This series exists only to anchor confirmation markers; a transparent line never connects them.
      elliottConfirmationSeries = addPriceOverlayLine({
        color: 'rgba(0, 0, 0, 0)',
        lastValueVisible: false,
        priceLineVisible: false
      });
      drawingManager = createDrawingManager(stage);
      indicatorManager = createIndicatorManager(stage);
      if (typeof StockWatchChartAlerts !== 'undefined') {
        void StockWatchChartAlerts.attach({
          symbol: ticker,
          interval: () => currentInterval,
          drawings: drawingManager,
          indicators: indicatorManager,
          onStatus: showChartStatus
        });
      }
      stage.addEventListener('pointerup', () => setTimeout(updatePriceScaleButtons, 0));
      stage.addEventListener('wheel', () => setTimeout(updatePriceScaleButtons, 0), { passive: true });

      // Load currency metadata first
      updateStockLoadingStatus(i18n.t('sw.loadingMarket'));
      try {
        const micQuery = selectedMic ? `?micCode=${encodeURIComponent(selectedMic)}` : '';
        const metaRes = await fetch(`/api/stocks/${encodedTicker}/meta${micQuery}`);
        if (metaRes.ok) {
          const metaJson = await metaRes.json();
          const instrumentType = String(metaJson.instrumentType || 'EQUITY').toUpperCase();
          const instrumentTypeDisplay = document.getElementById('instrumentTypeDisplay');
          instrumentTypeDisplay.dataset.instrumentType = instrumentType;
          instrumentTypeDisplay.textContent = i18n.t(`sw.instrument.${instrumentType === 'INDEX' || instrumentType === 'ETF' ? instrumentType : 'EQUITY'}`);
          updateTickerAlertsEligibility(instrumentType);
          await Promise.all([
            configureCongressionalActivity(instrumentType),
            configureInsiderActivity(instrumentType)
          ]);
          if (metaJson.currency) {
            stockCurrency = metaJson.currency;
            priceSeries.applyOptions({ priceFormat: priceSeriesFormat() });
          }
        }
      } catch (e) { console.warn("Currency fallback to USD"); }

      // The saved indicator layout also holds the chart type, scale and pane heights.
      updateStockLoadingStatus(i18n.t('sw.preparingIndicators'));
      await indicatorManager?.ready;
      updateStageHeight();

      updateStockLoadingStatus(i18n.t('sw.loadingHistory'));
      await loadChartData('1d');
      void refreshLiveQuote();
      updateStockLoadingStatus(i18n.t('sw.loadingAlerts'));
      await Promise.all([loadAlertState(), loadOutlookFollowState()]);

      await reopenHistoricalCandlestickResultsFromUrl();
      await openChartAlertFromUrl();

    } catch (err) {
      console.error("Initialization error:", err);
      completionMessage = i18n.t('sw.someDataUnavailable');
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
    globalVolumeData = [];
    seenDates.clear();
    hasMoreHistory = true;
    oldestTimestamp = null;
    clearTimeout(historyRetryTimer);
    historyRetryTimer = null;
    historyRetryAttempts = 0;
    elliottRequestSequence++;
    patternRangeLoader?.reset();
    patternRangeSignature = '';
    clearElliottOverlays();
    updateElliottOverlayToggle();
    harmonicRequestSequence++;
    clearHarmonicOverlays();
    updateHarmonicOverlayToggle();
    historicalCandlestickOverlayRequestSequence++;
    clearHistoricalCandlestickOverlayVisuals();
    anchoredVolumeProfileRequestSequence++;
    anchoredVolumeProfile = null;
    anchoredVolumeProfileAnchorTimestamp = null;
    clearAnchoredVolumeProfileCanvas();
    updateAnchoredVolumeProfileToggle();

    try {
      document.querySelector('.ticker-name').nextElementSibling.innerText =
              i18n.t(`sw.loadingData.${apiIntervalEnum(interval)}`);

      const response = await fetch(`/api/stocks/${encodedTicker}/candles?interval=${encodeURIComponent(interval)}&limit=${INITIAL_CANDLE_LIMIT}`, {
        signal: chartLoadController.signal
      });
      if (!response.ok) throw new Error("HTTP error");
      const page = normaliseCandlePage(await response.json());
      if (requestId !== chartRequestSequence || currentInterval !== interval) return;
      const data = page.candles;

      if (!data || data.length === 0) {
        document.querySelector('.ticker-name').nextElementSibling.innerText = page.failureMessage || i18n.t('sw.noHistoricalData');
        hasMoreHistory = false;
        return;
      }

      oldestTimestamp = page.nextCursor ?? data[0].timestamp;
      hasMoreHistory = pageMayHaveMore(page, data, INITIAL_CANDLE_LIMIT);
      processAndSetData(data, false);
      showNewestCandles(INITIAL_VISIBLE_CANDLES);
      updatePriceHeaderFromLatestCandle();
      void loadSplitMarkers(interval);

      document.querySelector('.ticker-name').nextElementSibling.innerText = i18n.t(`sw.intervalLabel.${apiIntervalEnum(interval)}`);

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
      document.querySelector('.ticker-name').nextElementSibling.innerText = i18n.t('sw.chartLoadFailed');
    } finally {
      if (requestId === chartRequestSequence) {
        isFetching = false;
        chartLoadController = null;
      }
    }
  }

  async function loadOlderData({ refreshOverlays = true } = {}) {
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
        if (refreshOverlays) await Promise.all([
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
      newVols.push({ time: timeString, value: volume, isUp: close >= open });
    });

    if (isPrepend) {
      globalCandleData = [...newCandles, ...globalCandleData];
      globalVolumeData = [...newVols, ...globalVolumeData];
    } else {
      globalCandleData = newCandles;
      globalVolumeData = newVols;
    }

    globalCandleData.sort((a, b) => a.time.localeCompare(b.time));
    globalVolumeData.sort((a, b) => a.time.localeCompare(b.time));

    if (globalCandleData.length > 0) {
      latestCandle = { ...globalCandleData[globalCandleData.length - 1] };
    }
    updateAnchoredVolumeProfileToggle();

    try {
      // The chart type decides what the main series shows; the candles themselves stay unchanged.
      setPriceSeriesData();
      indicatorManager?.refreshVolume();
      indicatorManager?.dataChanged();
      applySplitMarkers();
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

  function maybeLoadOlderForVisibleRange(range) {
    if (!range || isFetching || isLoadingOlder || !hasMoreHistory || !priceSeries) return;

    // This is Lightweight Charts' supported infinite-history signal. Unlike
    // comparing raw logical indexes, barsBefore remains correct when another
    // series changes the chart's shared time scale.
    const barsInfo = priceSeries.barsInLogicalRange(range);
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
    const nativeEnabled = elliottOverlaysEnabled && ['1d', '1wk', '1mo'].includes(interval);
    const subwavesEnabled = elliottSubwavesEnabled && ['1d', '1wk'].includes(interval);
    if (!nativeEnabled && !subwavesEnabled) {
      patternRangeLoader?.cancel('waves');
      patternRangeLoader?.cancel('candidates');
      clearElliottOverlays();
      status.textContent = '';
      return;
    }
    if (!subwavesEnabled) clearElliottSubwaveOverlays();
    if (!nativeEnabled) {
      patternRangeLoader?.cancel('waves');
      patternRangeLoader?.cancel('candidates');
      clearIndependentElliottOverlays();
    }

    status.textContent = nativeEnabled && subwavesEnabled
            ? i18n.t('sw.elliott.analysingBoth')
            : i18n.t(nativeEnabled ? 'sw.elliott.analysingNative' : 'sw.elliott.buildingSubwaves');
    try {
      const cardInterval = interval === '1d' ? 'DAILY' : (interval === '1wk' ? 'WEEKLY' : 'MONTHLY');
      const hierarchyPromise = subwavesEnabled
              ? elliottHierarchyOverlay?.showInterval(interval) || Promise.resolve(0)
              : Promise.resolve(0);
      const [history, cardResponse, subwaveCount, candidateHistory] = await Promise.all([
        nativeEnabled
                ? loadPatternRange('waves', interval)
                : Promise.resolve({structures: []}),
        nativeEnabled
                ? fetch(`/api/alerts/${encodedTicker}/elliott-cards?interval=${encodeURIComponent(cardInterval)}`)
                        .catch(() => null)
                : Promise.resolve(null),
        hierarchyPromise,
        nativeEnabled
                ? loadPatternRange('candidates', interval)
                : Promise.resolve({candidates: []})
      ]);
      const cards = cardResponse?.ok ? await cardResponse.json() : [];
      const candidates = candidateHistory.candidates || [];
      if (requestId !== elliottRequestSequence || currentInterval !== interval) return;
      clearIndependentElliottOverlays();

      elliottSignalCards = new Map((Array.isArray(cards) ? cards : []).map(card => [
        elliottSignalCardKey(card.cycleKey, card.stage),
        card
      ]));

      const uniqueStructures = Array.from(new Map((history.structures || []).map(structure => [
        structure.structureId || elliottStructureFallbackId(structure),
        structure
      ])).values());
      uniqueStructures.forEach(structure => renderHistoricalElliottStructure(structure));
      const standalone = (Array.isArray(candidates) ? candidates : []).filter(candidate =>
              candidate.structureLabel !== 'Motive 1-2-3-4-5' && candidate.points?.length >= 4);
      const bestCandidate = bestOnScreenElliottCandidate(standalone);
      if (bestCandidate) renderStandaloneElliottCandidate(bestCandidate);
      const candidateNotice = bestCandidate
              ? ` ${i18n.t('sw.elliott.bestCandidate', structureName(bestCandidate.structureLabel), bestCandidate.qualityScore)}` : '';
      setElliottConfirmationMarkers(uniqueStructures);
      rebuildElliottHitTargets();

      if (uniqueStructures.length === 0) {
        if (bestCandidate) { status.textContent = candidateNotice.trim(); return; }
        if (Number(subwaveCount) > 0) {
          status.textContent = `${i18n.plural(`sw.elliott.subwavesShown.${interval === '1d' ? 'DAILY' : 'WEEKLY'}`, Number(subwaveCount), subwaveCount)}${nativeEnabled ? i18n.t('sw.elliott.noIndependentSuffix') : ''}.`;
          return;
        }
        if (!nativeEnabled) {
          status.textContent = i18n.t('sw.elliott.noSubwaves');
          return;
        }
        status.textContent = i18n.t('sw.elliott.noStructure');
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
                      ? ` ${i18n.t('sw.elliott.quality', qualityMin)}`
                      : ` ${i18n.t('sw.elliott.qualityRange', qualityMin, qualityMax)}`);
      const warningNotice = warnedStructureCount > 0
              ? ` ${i18n.plural('sw.elliott.warnings', warnedStructureCount, warnedStructureCount)}`
              : '';
      if (warnedStructureCount > 0) status.classList.add('warning');
      const subwaveNotice = subwavesEnabled && Number(subwaveCount) > 0
              ? ` ${i18n.plural(`sw.elliott.subwavesPlus.${interval === '1d' ? 'DAILY' : 'WEEKLY'}`, Number(subwaveCount), subwaveCount)}`
              : '';
      // Lead with the short summary; the status chip shows the rest when expanded.
      status.textContent = `${i18n.plural('sw.elliott.structuresOnChart', uniqueStructures.length, uniqueStructures.length, globalCandleData.length)}${subwaveNotice}${qualityNotice}${warningNotice}`;
      status.textContent += candidateNotice;
    } catch (error) {
      if (error.name === 'AbortError') return;
      if (requestId === elliottRequestSequence && currentInterval === interval) {
        status.classList.add('error');
        status.textContent = i18n.t('sw.elliott.unavailable');
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
            ? i18n.t(elliottOverlaysEnabled ? 'sw.toggle.elliottOn' : 'sw.toggle.elliottOff')
            : i18n.t('sw.toggle.elliott');
    updateElliottSubwaveToggle();
  }

  function updateElliottSubwaveToggle() {
    const button = document.getElementById('elliottSubwaveToggle');
    const supported = ['1d', '1wk'].includes(currentInterval);
    button.disabled = !supported;
    button.classList.toggle('active', supported && elliottSubwavesEnabled);
    button.setAttribute('aria-pressed', String(supported && elliottSubwavesEnabled));
    button.textContent = supported
            ? i18n.t(elliottSubwavesEnabled ? 'sw.toggle.subwavesOn' : 'sw.toggle.subwavesOff')
            : i18n.t('sw.toggle.subwaves');
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
            ? i18n.t('sw.toggle.harmonicOn')
            : i18n.t('sw.toggle.harmonic');
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
    if (!harmonicOverlaysEnabled || !['1d', '1wk', '1mo'].includes(interval)) {
      patternRangeLoader?.cancel('harmonics');
      clearHarmonicOverlays();
      status.textContent = '';
      return;
    }
    status.textContent = i18n.t('sw.harmonic.scanning');
    try {
      const history = await loadPatternRange('harmonics', interval);
      if (requestId !== harmonicRequestSequence || currentInterval !== interval || !harmonicOverlaysEnabled) return;
      clearHarmonicOverlays();
      const formations = Array.isArray(history.formations) ? history.formations : [];
      formations.forEach(renderHarmonicFormation);
      rebuildHarmonicHitTargets();
      if (formations.length === 0) {
        status.textContent = i18n.t('sw.harmonic.none');
        return;
      }
      const bullish = formations.filter(formation => formation.direction === 'BULLISH').length;
      const bearish = formations.length - bullish;
      const minimumQuality = Math.min(...formations.map(formation => Number(formation.qualityScore) || 0));
      const maximumQuality = Math.max(...formations.map(formation => Number(formation.qualityScore) || 0));
      status.textContent = i18n.plural('sw.harmonic.shown', formations.length, formations.length, bullish, bearish, minimumQuality, maximumQuality);
    } catch (error) {
      if (error.name === 'AbortError') return;
      if (requestId === harmonicRequestSequence && currentInterval === interval) {
        status.classList.add('error');
        status.textContent = i18n.t('sw.harmonic.unavailable');
      }
      if (error.name !== 'AbortError') console.warn('Harmonic formations overlay failed', error);
    }
  }

  function renderHarmonicFormation(formation) {
    const points = Array.isArray(formation.points) ? formation.points : [];
    if (points.length !== 4 && points.length !== 5) return;
    const colors = harmonicOverlayColors(formation.direction);
    const series = addPriceOverlayLine({
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
    const patternLabel = harmonicDisplayName(formation);
    series.setMarkers(points.map((point, index) => ({
      time: timestampToChartDate(Number(point.timestamp)),
      position: point.pivotType === 'HIGH' ? 'aboveBar' : 'belowBar',
      color: colors.marker,
      shape: index === points.length - 1 ? (formation.direction === 'BEARISH' ? 'arrowDown' : 'arrowUp') : 'circle',
      text: index === points.length - 1
              ? `${point.label} · ${patternLabel} · Q${formation.qualityScore}`
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
      button.setAttribute('aria-label', i18n.t('sw.harmonic.pointAria', endpoint.label, harmonicDisplayName(overlay.formation)));
      button.title = i18n.t('sw.harmonic.openSummary');
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
                && x >= 0 && x <= stage.clientWidth && y >= 0 && y <= pricePaneHeight();
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
    document.getElementById('harmonicFormationCardTitle').textContent = harmonicDisplayName(formation);
    document.getElementById('harmonicFormationCardStage').textContent =
            i18n.t('sw.harmonic.cardStage', tradeSignalDisplayLabel(formation.tradeSignal), formatHistoricalElliottPeriod(formation.confirmationTimestamp), formation.qualityScore);
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
              i18n.t(formation.tradeSignal === 'SELL' ? 'sw.lossAvoided' : 'sw.bestReturn');
      const best = Math.max(...returns);
      const value = document.getElementById('harmonicFormationCardOutcomeValue');
      value.textContent = formatSignedPercent(best);
      value.classList.toggle('positive', best >= 0);
      value.classList.toggle('negative', best < 0);
      document.getElementById('harmonicFormationCardWindowEnd').textContent =
              i18n.t('sw.atCandle10', formatSignedPercent(returns[9]));
    } else {
      outcome.hidden = true;
      message.hidden = false;
      const count = confirmationIndex < 0 ? 0 : globalCandleData.length - confirmationIndex - 1;
      message.textContent = confirmationIndex < 0
              ? i18n.t('sw.confirmationOutside')
              : i18n.plural('sw.waitingCandles', Math.max(0, 10 - count), Math.max(0, 10 - count));
    }
    document.getElementById('harmonicFormationCardNote').textContent =
            i18n.t('sw.harmonic.cardNote', endpoint.label, i18n.number(endpoint.price, 4));
    document.getElementById('harmonicFormationCardLink').href =
            `/stock/${encodedTicker}/harmonic-formations/${encodeURIComponent(currentInterval)}/${encodeURIComponent(formation.pattern)}/${encodeURIComponent(endpoint.timestamp)}`;
    const card = document.getElementById('harmonicFormationHoverCard');
    card.hidden = false;
    positionHarmonicFormationCard(overlay);
  }

  function harmonicPatternName(pattern) {
    const key = `harmonicPattern.${String(pattern || '').toUpperCase()}`;
    if (i18n.has(key)) return i18n.t(key);
    return String(pattern || '').toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, letter => letter.toUpperCase());
  }

  function harmonicDisplayName(formation) {
    return i18n.t(formation.direction === 'BEARISH' ? 'sw.harmonic.bearish' : 'sw.harmonic.bullish', harmonicPatternName(formation.pattern));
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

    if (!StockWatchChartTypes.isOhlc(chartType)) setChartType('candles');
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
            ? i18n.t('sw.toggle.profileOn')
            : i18n.t('sw.toggle.profile');
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
        throw new Error(payload.error || i18n.t('sw.profile.requestFailed'));
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
      status.textContent = error.message || i18n.t('sw.profile.unavailable');
      console.warn('Anchored volume profile failed', error);
    }
  }

  function showAnchoredVolumeProfileLoading(anchorTimestamp) {
    document.getElementById('anchoredVolumeProfileSummary')
            .classList.add('active');
    document.getElementById('anchoredVolumeProfileRange').textContent =
            i18n.t('sw.profile.loading', formatProfileDate(anchorTimestamp));
    document.getElementById('anchoredVolumeProfilePoc').textContent = '—';
    document.getElementById('anchoredVolumeProfileVah').textContent = '—';
    document.getElementById('anchoredVolumeProfileVal').textContent = '—';
    const status = document.getElementById('anchoredVolumeProfileStatus');
    status.classList.remove('error', 'locked');
    status.textContent = i18n.t('sw.profile.loadingData');
  }

  function updateAnchoredVolumeProfileSummary(profile) {
    const anchor = formatProfileDate(profile.anchorTimestamp);
    const end = formatProfileDate(profile.endTimestamp);
    const range = anchor === end ? i18n.t('sw.profile.anchored', anchor) : `${anchor} → ${end}`;
    const source = formatProfileCalculationInterval(profile.calculationInterval);
    const candleCount = i18n.plural('results.candles', profile.candlesIncluded, profile.candlesIncluded);
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
      status.textContent = profile.liveRefreshAvailableAt
              ? i18n.t('sw.profile.pausedUntil', formatProfileDateTime(profile.liveRefreshAvailableAt))
              : i18n.t('sw.profile.paused');
      return;
    }
    const updated = profile.liveDataRefreshedAt
            ? i18n.t('sw.profile.updated', formatProfileDateTime(profile.liveDataRefreshedAt))
            : i18n.t('sw.profile.cached');
    status.textContent = `${updated} · ${i18n.plural('sw.profile.refreshesRemaining', profile.liveRefreshesRemaining, profile.liveRefreshesRemaining)}`;
  }

  function formatProfileCalculationInterval(interval) {
    if (interval === '15min') return i18n.t('sw.profile.source.15min');
    if (interval === '1d+15min') return i18n.t('sw.profile.source.mixed');
    if (interval === '1wk') return i18n.t('sw.profile.source.weekly');
    if (interval === '1mo') return i18n.t('sw.profile.source.monthly');
    return i18n.t('sw.profile.source.daily');
  }

  function formatProfileDate(timestamp) {
    if (!timestamp) return i18n.t('sw.profile.latest');
    const date = new Date(Number(timestamp) * 1000);
    return new Intl.DateTimeFormat(i18n.tag, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      timeZone: 'UTC'
    }).format(date);
  }

  function formatProfileDateTime(timestamp) {
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat(i18n.tag, {
      dateStyle: 'medium',
      timeStyle: 'short'
    }).format(date);
  }

  function formatProfilePrice(price) {
    const numeric = Number(price);
    return Number.isFinite(numeric)
            ? `${i18n.number(numeric, 2)} ${stockCurrency}`
            : i18n.t('sw.na');
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
            || !priceSeries
            || !canvas
            || !container) {
      clearAnchoredVolumeProfileCanvas();
      return;
    }

    const width = container.clientWidth;
    const fullHeight = container.clientHeight;
    const height = Math.min(fullHeight, pricePaneHeight());
    if (width <= 0 || height <= 0) return;
    const pixelRatio = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(fullHeight * pixelRatio);

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
      // Lightweight Charts exposes this in normal operation; keep a safe fallback.
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
      const topCoordinate = priceSeries.priceToCoordinate(Number(bin.priceHigh));
      const bottomCoordinate = priceSeries.priceToCoordinate(Number(bin.priceLow));
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
    const coordinate = priceSeries.priceToCoordinate(Number(price));
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
    context.font = '600 12px "Geist", sans-serif';
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

  // Only the endpoint has to be on screen: it carries the signal and its click target.
  function bestOnScreenElliottCandidate(candidates) {
    const onScreen = visiblePatternRange(0);
    if (!onScreen) return null;
    return candidates
            .filter(candidate => {
              const endpoint = Number(candidate.points[candidate.points.length - 1]?.timestamp);
              return endpoint >= onScreen.from && endpoint <= onScreen.to;
            })
            .reduce((best, candidate) => !best
                    || Number(candidate.qualityScore) > Number(best.qualityScore)
                    || Number(candidate.qualityScore) === Number(best.qualityScore)
                    && Number(candidate.confirmationTimestamp) > Number(best.confirmationTimestamp)
                    ? candidate : best, null);
  }

  function renderStandaloneElliottCandidate(candidate) {
    const groupId = `candidate:${candidate.candidateKey || elliottStructureFallbackId(candidate)}`;
    const structure = { ...candidate, candidate: true };
    const points = candidate.points;
    const series = addPriceOverlayLine({
      color: ELLIOTT_CANDIDATE_COLOR, lineWidth: 1, lineStyle: 2,
      crosshairMarkerVisible: false, lastValueVisible: false, priceLineVisible: false
    });
    series.setData(points.map(point => ({ time: timestampToChartDate(point.timestamp), value: point.price })));
    series.setMarkers(points
            .filter(point => point.label)
            .map(point => ({
              time: timestampToChartDate(point.timestamp),
              position: point.pivotType === 'HIGH' ? 'aboveBar' : 'belowBar',
              color: ELLIOTT_CANDIDATE_COLOR,
              shape: 'circle',
              text: point.label
            })));
    historicalElliottSeries.push(series);
    historicalElliottGroups.set(groupId, { structure, segments: [{
      structure,
      groupId,
      stage: 'CANDIDATE',
      points,
      color: ELLIOTT_CANDIDATE_COLOR,
      series,
      lineWidth: 1,
      activeLineWidth: 3
    }] });
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
    const series = addPriceOverlayLine({
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
      series,
      lineWidth: 2,
      activeLineWidth: 4
    });
  }

  function isCorrectiveElliottPoint(point) {
    return ['A', 'B', 'C'].includes(String(point.label || '').toUpperCase());
  }

  function formatWaveTwoRetracement(retracement) {
    const percentage = Number(retracement) * 100;
    return Number.isFinite(percentage) ? `${i18n.number(percentage, 1)}%` : '';
  }

  function elliottQualityWarnings(structure) {
    if (Array.isArray(structure.qualityWarnings) && structure.qualityWarnings.length > 0) {
      return structure.qualityWarnings;
    }
    return structure.deepWaveTwo
            ? [i18n.t('sw.elliott.deepWaveTwo', formatWaveTwoRetracement(structure.waveTwoRetracement))]
            : [];
  }

  function elliottPointWarningLabel(structure, point) {
    const label = point.label.toUpperCase();
    if (label === 'II' && (structure.waveTwoRetracement < 0.236 || structure.waveTwoRetracement > 0.786)) {
      return i18n.t(structure.waveTwoRetracement > 0.786 ? 'signalDetail.wave.deep' : 'signalDetail.wave.shallow');
    }
    if (label === 'IV' && (structure.waveFourRetracement < 0.146 || structure.waveFourRetracement > 0.618)) {
      return i18n.t(structure.waveFourRetracement > 0.618 ? 'signalDetail.wave.deep' : 'signalDetail.wave.shallow');
    }
    if (label === 'V' && structure.impulseVariant === 'TRUNCATED_FIFTH') return i18n.t('signalDetail.wave.truncated');
    if (label === 'C' && structure.correctionVariant === 'EXPANDED_FLAT') return i18n.t('signalDetail.wave.expandedFlat');
    if (label === 'C' && structure.correctionVariant === 'RUNNING_FLAT') return i18n.t('signalDetail.wave.runningFlat');
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
      const candidate = Boolean(segment.structure.candidate);
      const card = candidate ? null : trackedElliottCard(segment);
      elliottInteractivePoints(segment).forEach(point => {
        const button = document.createElement('button');
        const pointLabel = String(point.label || i18n.t('sw.origin')).toUpperCase();
        button.type = 'button';
        button.className = candidate ? 'elliott-wave-hit-target candidate' : 'elliott-wave-hit-target';
        button.setAttribute('aria-label', candidate
                ? i18n.t('sw.elliott.candidatePointAria', pointLabel, structureName(segment.structure.structureLabel))
                : i18n.t('sw.elliott.pointAria', pointLabel, i18n.has(`sw.direction.${segment.structure.direction}`) ? i18n.t(`sw.direction.${segment.structure.direction}`) : (segment.structure.direction || ''),
                        elliottStageLabel(segment.stage), card?.status ? formatElliottStatus(card.status) : i18n.t('sw.elliott.historicalSignal')));
        button.title = candidate ? i18n.t('sw.elliott.openCandidateSummary') : i18n.t('sw.elliott.openStageDetails', elliottStageLabel(segment.stage));
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
              && x >= 0 && x <= stage.clientWidth && y >= 0 && y <= pricePaneHeight();
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

    const direction = i18n.t(Number(wave.endPrice) >= Number(wave.startPrice) ? 'sw.hierarchy.bullish' : 'sw.hierarchy.bearish');
    const childCount = Array.isArray(wave.subwaves) ? wave.subwaves.length : 0;
    document.getElementById('elliottWaveCardEyebrow').textContent =
            `${hierarchyWord('interval', wave.timeframe)} · ${hierarchyWord('sw.hierarchy.nature', wave.nature)}`;
    document.getElementById('elliottWaveCardTitle').textContent = i18n.t('sw.hierarchy.wave', wave.degreeLabel);
    document.getElementById('elliottWaveCardStage').textContent =
            `${direction} · ${formatHierarchyWavePeriod(wave.startTime)} → ${formatHierarchyWavePeriod(wave.endTime)}`;

    const status = document.getElementById('elliottWaveCardStatus');
    status.className = 'elliott-wave-card-status confirmed';
    status.textContent = i18n.t('sw.hierarchy.validated');
    document.getElementById('elliottWaveCardOutcome').hidden = true;
    const message = document.getElementById('elliottWaveCardMessage');
    message.hidden = false;
    message.textContent = `${i18n.number(wave.startPrice, 2)} → ${i18n.number(wave.endPrice, 2)}`
            + (childCount > 0 ? ` · ${i18n.plural('sw.hierarchy.lowerSubwaves', childCount, childCount)}` : '');
    document.getElementById('elliottWaveCardNote').textContent = i18n.t('sw.hierarchy.note');
    document.getElementById('elliottWaveCardLink').hidden = true;

    const card = document.getElementById('elliottWaveHoverCard');
    card.hidden = false;
    positionElliottWaveCardAt(point);
  }

  // Upper-case label for a hierarchy constant (MONTHLY, MOTIVE), translated where a message exists.
  function hierarchyWord(prefix, value) {
    const key = `${prefix}.${String(value || '').toUpperCase()}`;
    return (i18n.has(key) ? i18n.t(key) : String(value || '')).toUpperCase();
  }

  function formatHierarchyWavePeriod(timestamp) {
    if (!timestamp) return i18n.t('sw.unavailable');
    return new Date(Number(timestamp) * 1000).toLocaleDateString(i18n.tag, {
      year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'
    });
  }

  function renderElliottWaveCard(segment) {
    const structure = segment.structure;
    const candidate = Boolean(structure.candidate);
    const tracked = candidate ? null : trackedElliottCard(segment);
    const cardData = candidate ? candidateElliottCard(segment) : (tracked || historicalElliottCard(segment));
    document.getElementById('elliottWaveCardEyebrow').textContent =
            i18n.t(candidate ? 'sw.elliott.eyebrowCandidate' : 'sw.elliott.eyebrow');
    document.getElementById('elliottWaveCardTitle').textContent =
            candidate ? structureName(structure.structureLabel) : i18n.t(structure.direction === 'BEARISH' ? 'sw.elliott.structureBearish' : 'sw.elliott.structureBullish');
    document.getElementById('elliottWaveCardStage').textContent =
            i18n.t('sw.elliott.cardStage', cardData.stageLabel, tradeSignalDisplayLabel(cardData.tradeSignal), cardData.signalPeriodLabel);

    const status = document.getElementById('elliottWaveCardStatus');
    status.className = `elliott-wave-card-status ${cardData.statusClass}`;
    status.textContent = candidate
            ? i18n.t('sw.elliott.candidateScore', structure.qualityScore)
            : formatElliottStatus(cardData.status);

    const outcome = document.getElementById('elliottWaveCardOutcome');
    const message = document.getElementById('elliottWaveCardMessage');
    const note = document.getElementById('elliottWaveCardNote');
    const link = document.getElementById('elliottWaveCardLink');
    link.hidden = !cardData.detailUrl;
    if (cardData.detailUrl) {
      link.href = cardData.detailUrl;
      link.textContent = candidate
              ? i18n.t('sw.elliott.openCandidate')
              : i18n.t(tracked ? 'sw.elliott.openSignal' : 'sw.elliott.openHistorical');
    }
    note.textContent = `${cardData.outcomeNote
            || i18n.t('sw.elliott.historicalResults')} ${i18n.t('sw.elliott.measuredFrom', cardData.signalPeriodLabel)}`;
    if (cardData.outcomeAvailable) {
      outcome.hidden = false;
      message.hidden = true;
      document.getElementById('elliottWaveCardOutcomeLabel').textContent = cardData.outcomeLabel;
      const outcomeValue = document.getElementById('elliottWaveCardOutcomeValue');
      outcomeValue.textContent = formatSignedPercent(cardData.bestDirectionalReturnPercent);
      outcomeValue.classList.toggle('positive', Number(cardData.bestDirectionalReturnPercent) >= 0);
      outcomeValue.classList.toggle('negative', Number(cardData.bestDirectionalReturnPercent) < 0);
      document.getElementById('elliottWaveCardWindowEnd').textContent = i18n.t('sw.atCandle10', formatSignedPercent(cardData.windowEndDirectionalReturnPercent));
    } else {
      outcome.hidden = true;
      message.hidden = false;
      message.textContent = cardData.unavailableReason
              || i18n.t('sw.elliott.tenCandlesRequired');
    }
  }

  function historicalElliottCard(segment) {
    const structure = segment.structure;
    const tradeSignal = historicalElliottTradeSignal(structure.direction, segment.stage);
    const signalTimestamp = historicalElliottConfirmationTimestamp(segment);
    const endpoint = segment.points[segment.points.length - 1];
    const historicalDetailUrl = endpoint?.timestamp
            ? `/stock/${encodedTicker}/elliott-waves/${encodeURIComponent(currentInterval)}/${encodeURIComponent(segment.stage)}/${encodeURIComponent(endpoint.timestamp)}`
              + `?cycleKey=${encodeURIComponent(structure.cycleKey || '')}`
            : null;
    return {
      ...elliottForwardOutcome(signalTimestamp, tradeSignal,
              i18n.t('sw.elliott.noConfirmationStage')),
      stageLabel: elliottStageLabel(segment.stage),
      tradeSignal,
      status: signalTimestamp === null ? 'DETECTED' : 'CONFIRMED',
      statusClass: signalTimestamp === null ? 'detected' : 'confirmed',
      signalTimestamp,
      signalPeriodLabel: signalTimestamp === null
              ? formatHistoricalElliottPeriod(endpoint?.timestamp)
              : formatHistoricalElliottPeriod(signalTimestamp),
      outcomeNote: i18n.t('sw.elliott.firstTenResults'),
      detailUrl: historicalDetailUrl
    };
  }

  function candidateElliottCard(segment) {
    const candidate = segment.structure;
    const endpoint = segment.points[segment.points.length - 1];
    // The detector confirms a candidate by a reversal away from its final pivot.
    const tradeSignal = endpoint?.pivotType === 'LOW' ? 'BUY' : 'SELL';
    const signalTimestamp = Number(candidate.confirmationTimestamp) > 0
            ? Number(candidate.confirmationTimestamp) : null;
    const detailUrl = endpoint?.timestamp && candidate.candidateKey
            ? `/stock/${encodedTicker}/elliott-waves/${encodeURIComponent(currentInterval)}/candidate/${encodeURIComponent(endpoint.timestamp)}`
              + `?key=${encodeURIComponent(candidate.candidateKey)}`
            : null;
    return {
      ...elliottForwardOutcome(signalTimestamp, tradeSignal,
              i18n.t('sw.elliott.noConfirmationCandidate')),
      stageLabel: i18n.t(candidate.direction === 'RISING' ? 'sw.elliott.risingPattern' : 'sw.elliott.fallingPattern'),
      tradeSignal,
      status: 'CANDIDATE',
      statusClass: 'candidate',
      signalTimestamp,
      signalPeriodLabel: formatHistoricalElliottPeriod(signalTimestamp ?? endpoint?.timestamp),
      outcomeNote: i18n.t('sw.elliott.shapeOnlyNote'),
      detailUrl
    };
  }

  function elliottForwardOutcome(signalTimestamp, tradeSignal, missingConfirmationReason) {
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
    const unavailableReason = signalTimestamp === null
            ? missingConfirmationReason
            : (signalIndex < 0
                    ? i18n.t('sw.elliott.confirmationOutside')
                    : i18n.plural('sw.waitingCandles', 10 - availableForwardCandles, 10 - availableForwardCandles));
    return {
      outcomeAvailable,
      bestDirectionalReturnPercent,
      windowEndDirectionalReturnPercent,
      outcomeLabel: i18n.t(tradeSignal === 'SELL' ? 'sw.lossAvoided' : 'sw.bestReturn'),
      unavailableReason
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
    if (!timestamp) return i18n.t('sw.unavailable');
    const date = new Date(Number(timestamp) * 1000);
    if (currentInterval === '1mo') {
      return date.toLocaleDateString(i18n.tag, { month: 'long', year: 'numeric', timeZone: 'UTC' });
    }
    if (currentInterval === '1d') {
      return date.toLocaleDateString(i18n.tag, {
        year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'
      });
    }
    return i18n.t('sw.weekOf', date.toLocaleDateString(i18n.tag, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      timeZone: 'UTC'
    }));
  }

  function elliottStageLabel(stage) {
    return i18n.t(stage === 'CORRECTION_END' ? 'sw.stage.CORRECTION_END' : 'sw.stage.WAVE_V_END');
  }

  function formatElliottStatus(status) {
    const key = `sw.elliottStatus.${String(status || 'DETECTED').toUpperCase()}`;
    if (i18n.has(key)) return i18n.t(key);
    const value = String(status || 'DETECTED').toLowerCase();
    return value.charAt(0).toUpperCase() + value.slice(1);
  }

  function formatSignedPercent(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return '—';
    return `${numeric > 0 ? '+' : ''}${i18n.number(numeric, 2)}%`;
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
      item.series.applyOptions({
        lineWidth: item.groupId === segment?.groupId ? item.activeLineWidth : item.lineWidth
      });
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
      item.series.applyOptions({ lineWidth: item.lineWidth });
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

  // The header shows the provider's latest quote when there is one. Until then (or when no quote is
  // available) it shows the newest candle's close, labelled as such, with the change against the
  // previous candle of the selected interval.
  const LIVE_QUOTE_REFRESH_MS = 60_000;
  const LIVE_QUOTE_IDLE_MS = 15 * 60_000;
  let liveQuote = null;
  let liveQuoteTimer = null;
  let liveQuoteFetchedAt = 0;
  let liveQuoteStarted = false;

  function renderQuoteHeader(label, price, changePercent, meta) {
    document.getElementById('quoteLabel').textContent = label;
    document.getElementById('livePriceDisplay').textContent = `${i18n.number(price, 2)} ${stockCurrency}`;
    const changeEl = document.getElementById('liveChangeDisplay');
    changeEl.textContent = `${changePercent > 0 ? '+' : ''}${i18n.number(changePercent, 2)}%`;
    changeEl.className = 'price-change ' + (changePercent >= 0 ? 'positive' : 'negative');
    document.getElementById('quoteMeta').textContent = meta;
  }

  function updatePriceHeaderFromLatestCandle() {
    if (liveQuote) {
      renderLiveQuote();
      return;
    }
    if (!latestCandle) return;
    const previous = globalCandleData.length > 1 ? globalCandleData[globalCandleData.length - 2].close : latestCandle.open;
    const changePercent = previous > 0 ? ((latestCandle.close - previous) / previous) * 100 : 0;
    const candleDate = i18n.date(Date.parse(`${latestCandle.time}T00:00:00Z`),
        {year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'});
    renderQuoteHeader(i18n.t('sw.quote.lastClose'), latestCandle.close, changePercent,
        i18n.t(`sw.quote.candleMeta.${apiIntervalEnum(currentInterval)}`, candleDate));
  }

  function renderLiveQuote() {
    const quotedAt = Number(liveQuote.quotedAt ?? liveQuote.timestamp) * 1000;
    const sameDay = new Date(quotedAt).toDateString() === new Date().toDateString();
    const when = Number.isFinite(quotedAt)
        ? i18n.date(quotedAt, sameDay ? {timeStyle: 'short'} : {dateStyle: 'medium', timeStyle: 'short'})
        : '';
    const meta = liveQuote.marketOpen === false
        ? i18n.t('sw.quote.closedMeta', when)
        : i18n.t('sw.quote.liveMeta', when);
    renderQuoteHeader(i18n.t('sw.quote.live'), Number(liveQuote.price), Number(liveQuote.changePercent) || 0, meta);
  }

  function scheduleLiveQuote(delay) {
    window.clearTimeout(liveQuoteTimer);
    liveQuoteTimer = window.setTimeout(refreshLiveQuote, delay);
  }

  /** Polls once a minute while the tab is visible; a closed market or a missing quote is checked rarely. */
  async function refreshLiveQuote() {
    liveQuoteStarted = true;
    // A page opened in a background tab waits; the visibility handler starts it when the tab is shown.
    if (document.hidden) return;
    liveQuoteFetchedAt = Date.now();
    let next = LIVE_QUOTE_REFRESH_MS;
    try {
      const response = await fetch(`/api/stocks/${encodedTicker}/live`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const update = await response.json();
      // A stored candle is not a quote; the candle header already shows that close.
      if (update?.source === 'Twelve Data' && Number.isFinite(Number(update.price))) {
        liveQuote = update;
        renderLiveQuote();
        if (update.marketOpen === false) next = LIVE_QUOTE_IDLE_MS;
      } else {
        next = LIVE_QUOTE_IDLE_MS;
      }
    } catch (err) {
      next = LIVE_QUOTE_IDLE_MS;
      console.warn('Live quote unavailable', err);
    }
    scheduleLiveQuote(next);
  }

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && liveQuoteStarted && Date.now() - liveQuoteFetchedAt >= LIVE_QUOTE_REFRESH_MS) {
      window.clearTimeout(liveQuoteTimer);
      void refreshLiveQuote();
    }
  });

  async function changeInterval(newInterval) {
    if (currentInterval === newInterval) return;
    currentInterval = newInterval;
    drawingManager?.setInterval(newInterval);
    indicatorManager?.setInterval(newInterval);

    document.getElementById('int-1d').classList.remove('active');
    document.getElementById('int-1wk').classList.remove('active');
    document.getElementById('int-1mo').classList.remove('active');
    document.getElementById(`int-${newInterval}`).classList.add('active');

    await loadChartData(newInterval);
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
      status.innerText = i18n.t('sw.alertsUnavailable');
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
        input.addEventListener('change', updateAlertDraftUi);
        input.dataset.listenerBound = 'true';
      }
    });
    outlookFollowStateLoaded = true;
    updateAlertDraftUi();
  }

  async function loadOutlookFollowState() {
    const status = document.getElementById('outlookFollowStatus');
    try {
      const response = await fetch(`/api/stocks/${encodedTicker}/technical-outlook/subscriptions`);
      if (!response.ok) throw new Error('Outlook follow state request failed');
      const state = await response.json();
      hydrateOutlookFollowState(state);
      setOutlookFollowInputsDisabled(technicalFollowAllBusy);
      status.textContent = i18n.t('sw.outlookAlertsReady');
    } catch (error) {
      outlookFollowStateLoaded = false;
      status.textContent = i18n.t('sw.outlookAlertsUnavailable');
      outlookFollowInputs().forEach(input => input.disabled = true);
      updateTechnicalFollowAllButton();
      console.warn('Outlook follow state failed', error);
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
      if (!response.ok) throw new Error(state.error || i18n.t('sw.congressUnavailable'));
      applyCongressionalActivityState(state);
    } catch (error) {
      status.textContent = error.message || i18n.t('sw.congressUnavailable');
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
    followButton.textContent = i18n.t(congressionalFollowing ? 'sw.congressStopFollowing' : 'sw.congressFollow');
    const status = document.getElementById('congressionalActivityStatus');
    status.textContent = state.baselinePending
            ? i18n.t('sw.preparingActivityAlerts')
            : i18n.t('sw.congressFollowedCount', state.followedStocks, state.maximumFollowedStocks);
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
      if (!response.ok) throw new Error(state.error || i18n.t('sw.insiderUnavailable'));
      applyInsiderActivityState(state);
    } catch (error) {
      status.textContent = error.message || i18n.t('sw.insiderUnavailable');
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
    followButton.textContent = i18n.t(insiderFollowing ? 'sw.insiderStopFollowing' : 'sw.insiderFollow');
    document.getElementById('insiderActivityStatus').textContent = state.baselinePending
            ? i18n.t('sw.preparingActivityAlerts')
            : i18n.t('sw.insiderFollowedCount', state.followedStocks, state.maximumFollowedStocks);
  }

  function showHistoricalCandlestickViewPicker() {
    if (historicalCandlestickOverlayEnabled) {
      disableHistoricalCandlestickOverlay();
      return;
    }
    showHistoricalCandlestickLookbackPicker(currentInterval);
  }

  async function enableHistoricalCandlestickOverlay() {
    historicalCandlestickOverlayEnabled = true;
    if (!StockWatchChartTypes.isOhlc(chartType)) setChartType('candles');
    updateHistoricalCandlestickOverlayToggle();
    await refreshHistoricalCandlestickOverlay(currentInterval);
    if (historicalCandlestickOverlayEnabled) {
      document.getElementById('candlestickOverlayToggle').focus({ preventScroll: true });
    }
  }

  function disableHistoricalCandlestickOverlay() {
    historicalCandlestickOverlayEnabled = false;
    patternRangeLoader?.cancel('candles');
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
    button.textContent = i18n.t(historicalCandlestickOverlayEnabled ? 'sw.toggle.candlesOn' : 'sw.candlestickSignals');
    renderChartOverlayChips();
  }

  async function refreshHistoricalCandlestickOverlay(interval) {
    if (!historicalCandlestickOverlayEnabled) return;
    const requestId = ++historicalCandlestickOverlayRequestSequence;
    const status = document.getElementById('candlestickOverlayStatus');
    status.classList.remove('error', 'warning');
    status.textContent = i18n.t(`sw.candles.loading.${apiIntervalEnum(interval)}`);
    try {
      const history = await loadPatternRange('candles', interval);
      if (requestId !== historicalCandlestickOverlayRequestSequence
              || !historicalCandlestickOverlayEnabled
              || currentInterval !== interval) return;
      clearHistoricalCandlestickOverlayVisuals();
      historicalCandlestickOverlayHistory = history;
      historicalCandlestickOverlaySignals = Array.isArray(history.signals) ? history.signals : [];
      rebuildHistoricalCandlestickHitTargets();
      updateHistoricalCandlestickOverlayStatus();
    } catch (error) {
      if (error.name === 'AbortError') return;
      if (requestId !== historicalCandlestickOverlayRequestSequence) return;
      status.classList.add('error');
      status.textContent = error.message || i18n.t('sw.candles.overlayFailed');
      console.warn('Historical candlestick overlay failed', error);
    }
  }

  function updateHistoricalCandlestickOverlayStatus() {
    if (!historicalCandlestickOverlayEnabled || !historicalCandlestickOverlayHistory) return;
    const loadedTimestamps = new Set(globalCandleData.map(candle => Number(candle.timestamp)));
    const loadedSignalCount = historicalCandlestickOverlaySignals
            .filter(signal => loadedTimestamps.has(Number(signal.signalTimestamp))).length;
    const total = historicalCandlestickOverlaySignals.length;
    const olderNotice = loadedSignalCount < total
            ? i18n.plural('sw.candles.partlyLoaded', loadedSignalCount, loadedSignalCount)
            : i18n.t('sw.candles.allLoaded');
    document.getElementById('candlestickOverlayStatus').textContent = total === 0
            ? i18n.t(`sw.candles.none.${apiIntervalEnum(currentInterval)}`)
            : `${i18n.plural('sw.candles.shown', total, total)} ${olderNotice}`;
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
              button.textContent = i18n.t(direction === 'sell' ? 'sw.marker.sellShort' : 'sw.marker.buy');
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
    if (!priceChart || !priceSeries || !historicalCandlestickOverlayEnabled) return;
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
      const candleY = priceSeries.priceToCoordinate(candlePrice);
      const y = Number(candleY) + (buy ? 22 : -22);
      if (!Number.isFinite(x) || !Number.isFinite(y)
              || x < 0 || x > stage.clientWidth || y < 0 || y > pricePaneHeight()) return;
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
              : i18n.t(cluster.buy ? 'sw.marker.buy' : 'sw.marker.sell');
      representative.button.classList.toggle('cluster', signals.length > 1);
      representative.button.title = signals.length > 1
              ? i18n.plural(cluster.buy ? 'sw.cluster.BUY' : 'sw.cluster.SELL', signals.length, signals.length)
              : i18n.t('sw.openDetails', signals[0].patternLabel);
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
    status.textContent = signal.statusLabel || i18n.t('sw.awaitingOutcome');
    const outcome = historicalCandlestickCardOutcome(signal);
    const outcomeValue = document.getElementById('historicalCandlestickCardOutcomeValue');
    outcomeValue.textContent = outcome.value;
    outcomeValue.className = outcome.valueClass;
    document.getElementById('historicalCandlestickCardOutcomeLabel').textContent = outcome.label;
    document.getElementById('historicalCandlestickCardOutcomeWindow').textContent = outcome.detail;
    document.getElementById('historicalCandlestickCardTrend').textContent =
            historicalCandlestickTrendSentence(signal);
    document.getElementById('historicalCandlestickCardNote').textContent =
            signal.outcomeSummary || i18n.t('sw.candles.closingPrices');
    document.getElementById('historicalCandlestickCardLink').href =
            historicalCandlestickDetailUrl(signal, null, true);
    const navigation = document.getElementById('historicalCandlestickCardClusterNavigation');
    navigation.hidden = activeHistoricalCandlestickSignals.length <= 1;
    document.getElementById('historicalCandlestickCardClusterPosition').textContent =
            i18n.t('sw.cluster.position', activeHistoricalCandlestickSignalIndex + 1, activeHistoricalCandlestickSignals.length);
    highlightHistoricalCandlestickSignal(signal);
  }

  function historicalCandlestickTrendSentence(signal) {
    const trend = signal.trendLabel
            || i18n.t(String(signal.tradeSignal).toUpperCase() === 'BUY' ? 'sw.candles.requiredDowntrend' : 'sw.candles.requiredUptrend');
    const candles = Number(signal.formationCandles);
    return Number.isInteger(candles) && candles > 0
            ? i18n.t('sw.candles.trendBeforeFormation', trend, candles)
            : i18n.t('sw.candles.trendBeforePattern', trend);
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
    const entryLabel = entry == null ? i18n.t('sw.na') : formatProfilePrice(entry);
    const rewardRiskSuffix = rewardRiskRatio == null ? '' : ` - ${i18n.t('sw.rr', rewardRiskRatio)}`;

    if (lifecycleStatus === 'CONFIRMED') {
      return {
        label: i18n.t('sw.outcome.targetReturn'),
        value: targetReturn == null ? i18n.t('signalDetail.targetReached') : formatSignedPercent(targetReturn),
        valueClass: targetReturn == null ? 'positive' : candlestickReturnClass(targetReturn),
        detail: (target == null ? i18n.t('sw.outcome.soldAtTarget', entryLabel) : i18n.t('sw.outcome.soldAt', formatProfilePrice(target), entryLabel)) + rewardRiskSuffix
      };
    }
    if (lifecycleStatus === 'INVALIDATED') {
      const stopPriceLabel = stop == null ? i18n.t('sw.outcome.stopReached') : formatProfilePrice(stop);
      return {
        label: i18n.t('sw.outcome.stopLossReached'),
        value: stopReturn == null ? stopPriceLabel : `${stopPriceLabel} (${formatSignedPercent(stopReturn)})`,
        valueClass: 'negative',
        detail: i18n.t('sw.outcome.closedAtStop', entryLabel)
      };
    }
    if (lifecycleStatus === 'EXPIRED') {
      return {
        label: i18n.t('sw.outcome.timeStopReturn', timeStopCandles),
        value: recordedReturn == null ? i18n.t('sw.unavailableCap') : formatSignedPercent(recordedReturn),
        valueClass: recordedReturn == null ? '' : candlestickReturnClass(recordedReturn),
        detail: exit == null
                ? i18n.t(signal.evaluationPeriodLabel ? 'sw.outcome.closedAtTimeStopOn' : 'sw.outcome.closedAtTimeStop', signal.evaluationPeriodLabel)
                : i18n.t(signal.evaluationPeriodLabel ? 'sw.outcome.closedAtOn' : 'sw.outcome.closedAt', formatProfilePrice(exit), signal.evaluationPeriodLabel)
      };
    }
    if (lifecycleStatus === 'DETECTED') {
      return {
        label: i18n.t('sw.outcome.potentialTargetReturn'),
        value: targetReturn == null ? i18n.t('sw.outcome.targetPending') : formatSignedPercent(targetReturn),
        valueClass: targetReturn == null ? '' : candlestickReturnClass(targetReturn),
        detail: i18n.t('sw.outcome.plan', entryLabel, target == null ? i18n.t('sw.na') : formatProfilePrice(target), stop == null ? i18n.t('sw.na') : formatProfilePrice(stop))
                + `${rewardRiskSuffix} - ${i18n.t('sw.outcome.timeStopCandle', timeStopCandles)}`
      };
    }
    if (lifecycleStatus === 'REJECTED') {
      return {
        label: i18n.t('sw.outcome.rejected'),
        value: i18n.t('sw.outcome.noTrade'),
        valueClass: '',
        detail: i18n.t('sw.outcome.rejectedDetail')
      };
    }
    return {
      label: i18n.t('sw.outcome.potential'),
      value: i18n.t('sw.outcome.notOpened'),
      valueClass: '',
      detail: signal.setupScore == null ? i18n.t('sw.outcome.awaitingConfirmation')
              : i18n.t('sw.outcome.awaitingConfirmationScore', Number(signal.setupScore))
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
    if (!candle || !priceChart || !priceSeries) return null;
    const x = priceChart.timeScale().timeToCoordinate(candle.time);
    const buy = String(signal.tradeSignal).toUpperCase() === 'BUY';
    const y = priceSeries.priceToCoordinate(buy ? Number(candle.low) : Number(candle.high));
    return Number.isFinite(x) && Number.isFinite(y) ? {x, y} : null;
  }

  function ensureHistoricalCandlestickHighlightSeries() {
    if (!historicalCandlestickTrendSeries) {
      historicalCandlestickTrendSeries = addPriceOverlayLine({
        color: '#ffbd59',
        lineWidth: 3,
        crosshairMarkerVisible: false,
        lastValueVisible: false,
        priceLineVisible: false
      });
    }
    if (!historicalCandlestickFormationSeries) {
      historicalCandlestickFormationSeries = addPriceOverlayLine({
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
      text: signal.trendLabel || i18n.t('sw.candles.requiredTrend')
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
    showHistoricalCandlestickLookbackPicker(selectedHistoricalCandlestickInterval || currentInterval);
  }

  function showHistoricalCandlestickLookbackPicker(interval, initialLookback = null) {
    if (!Object.hasOwn(HISTORICAL_CANDLESTICK_DEFAULT_LOOKBACKS, interval)) return;
    selectedHistoricalCandlestickInterval = interval;

    const resultsDialog = document.getElementById('historicalCandlestickResultsDialog');
    if (resultsDialog.open) resultsDialog.close();
    document.getElementById('historicalScanInterval').value = interval;
    document.getElementById('historicalCandlestickLookbackInterval').textContent = i18n.t(`sw.intervalName.${apiIntervalEnum(interval)}`);
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
    const graphical = document.getElementById('historicalScanView').value === 'graphical';
    document.getElementById('historicalCandlestickLookbackInput').disabled = graphical;
    document.getElementById('historicalCandlestickLookbackInput').closest('label').hidden = graphical;
    document.getElementById('historicalScanPreview').hidden = graphical;
    document.getElementById('historicalScanChartNote').hidden = !graphical;
    const input = document.getElementById('historicalCandlestickLookbackInput');
    const error = document.getElementById('historicalCandlestickLookbackError');
    const preview = document.getElementById('historicalCandlestickLookbackDate');
    const lookback = selectedHistoricalCandlestickLookback();
    const valid = lookback !== null;
    input.setCustomValidity(valid ? '' : i18n.t('sw.lookbackInvalid'));
    error.textContent = input.value && !valid ? i18n.t('sw.lookbackInvalid') : '';
    preview.textContent = valid && selectedHistoricalCandlestickInterval
            ? formatHistoricalCandlestickLookbackDate(selectedHistoricalCandlestickInterval, lookback)
            : i18n.t('sw.lookbackPrompt');
  }

  function formatHistoricalCandlestickLookbackDate(interval, lookback) {
    const estimatedDate = new Date();
    estimatedDate.setHours(12, 0, 0, 0);
    if (interval === '1mo') {
      estimatedDate.setDate(1);
      estimatedDate.setMonth(estimatedDate.getMonth() - lookback);
      return i18n.t('sw.around', new Intl.DateTimeFormat(i18n.tag, {
        month: 'long',
        year: 'numeric'
      }).format(estimatedDate));
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
    return i18n.t('sw.around', new Intl.DateTimeFormat(i18n.tag, {
      day: 'numeric',
      month: 'short',
      year: 'numeric'
    }).format(estimatedDate));
  }

  async function submitHistoricalCandlestickLookback(event) {
    event.preventDefault();
    updateHistoricalCandlestickLookbackPreview();
    const input = document.getElementById('historicalCandlestickLookbackInput');
    const lookback = selectedHistoricalCandlestickLookback();
    if (!selectedHistoricalCandlestickInterval || (lookback === null && document.getElementById('historicalScanView').value !== 'graphical')) {
      input.reportValidity();
      return;
    }
    if (document.getElementById('historicalScanView').value === 'graphical') {
      document.getElementById('historicalCandlestickLookbackDialog').close();
      await changeInterval(selectedHistoricalCandlestickInterval);
      await enableHistoricalCandlestickOverlay();
      return;
    }
    disableHistoricalCandlestickOverlay();
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

    const lookbackDialog = document.getElementById('historicalCandlestickLookbackDialog');
    if (lookbackDialog.open) lookbackDialog.close();
    const resultsDialog = document.getElementById('historicalCandlestickResultsDialog');
    const status = document.getElementById('historicalCandlestickResultsStatus');
    const rows = document.getElementById('historicalCandlestickResultsRows');
    const empty = document.getElementById('historicalCandlestickResultsEmpty');
    rows.replaceChildren();
    empty.hidden = true;
    status.textContent = i18n.t('sw.results.calculating');
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
      if (!response.ok) throw new Error(history.error || i18n.t('sw.results.failed'));
      selectedHistoricalCandlestickInterval = interval;
      renderHistoricalCandlestickResults(history);
    } catch (error) {
      if (error.name === 'AbortError') return;
      status.textContent = error.message || i18n.t('sw.results.failed');
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
            history.interval ? i18n.t(`sw.results.title.${apiIntervalEnum(history.interval)}`) : i18n.t('sw.results.title');
    document.getElementById('historicalCandlestickResultsWindow').textContent =
            `${history.lookbackLabel || i18n.t('sw.results.recent')} · `
            + `${i18n.t('sw.results.timeStop')} · `
            + i18n.t('sw.rr', history.interval === '1wk' || history.interval === '1mo' ? 3 : 2);
    document.getElementById('historicalCandlestickResultsStatus').textContent =
            `${i18n.plural('sw.results.calculated', signals.length, signals.length)} · `
            + i18n.t('sw.results.noResults');
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
            historyCell(i18n.t('sw.col.signalDate'), signal.signalPeriodLabel),
            historicalCandlestickTypeCell(signal),
            historicalCandlestickStatusCell(signal),
            historyCell(i18n.t('sw.col.confidence'), `${signal.setupScore}/100`, signal.setupStrengthLabel),
            historyCell(i18n.t('sw.col.formation'), signal.patternLabel, signal.formationLabel),
            historicalCandlestickImpactCell(signal),
            historicalCandlestickDetailCell(detailUrl)
    );
    return row;
  }

  function historicalCandlestickTypeCell(signal) {
    const cell = document.createElement('td');
    cell.dataset.label = i18n.t('sw.col.type');
    const badge = document.createElement('span');
    badge.className = `historical-candlestick-type ${String(signal.tradeSignal || '').toLowerCase()}`;
    badge.textContent = signal.typeLabel || tradeSignalDisplayLabel(signal.tradeSignal);
    cell.append(badge);
    return cell;
  }

  function historicalCandlestickStatusCell(signal) {
    const cell = document.createElement('td');
    cell.dataset.label = i18n.t('sw.col.status');
    const badge = document.createElement('span');
    badge.className = `historical-candlestick-status ${signal.statusClass || 'pending'}`;
    badge.textContent = signal.statusLabel || i18n.t('sw.awaitingOutcome');
    cell.append(badge);
    return cell;
  }

  function historicalCandlestickImpactCell(signal) {
    const cell = document.createElement('td');
    cell.dataset.label = i18n.t('sw.col.tradeReturn');
    const strong = document.createElement('strong');
    const value = Number(signal.directionalReturnPercent);
    strong.textContent = Number.isFinite(value)
            ? `${value >= 0 ? '+' : ''}${i18n.number(value, 2)}%`
            : i18n.t('sw.pending');
    strong.className = Number.isFinite(value) ? (value >= 0 ? 'positive' : 'negative') : '';
    const detail = document.createElement('span');
    detail.textContent = signal.impactLabel || i18n.t('sw.tradeReturnPending');
    const basis = document.createElement('small');
    basis.textContent = signal.tradeEntryPrice == null
            ? i18n.t('sw.tradeNotOpened')
            : i18n.t('sw.fromEntry', i18n.number(signal.tradeEntryPrice, 4));
    cell.append(strong, detail, basis);
    return cell;
  }

  function historicalCandlestickDetailCell(detailUrl) {
    const cell = document.createElement('td');
    cell.dataset.label = i18n.t('sw.col.details');
    const link = document.createElement('a');
    link.href = detailUrl;
    link.className = 'historical-candlestick-detail-link';
    link.textContent = i18n.t('sw.viewSignal');
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
    status.textContent = i18n.t(desiredState ? 'sw.congressEnabling' : 'sw.congressStopping');
    try {
      const selection = desiredState ? await window.StockWatchLists.choose(decodeURIComponent(encodedTicker), { signalTypes: ['CONGRESS'] }) : {};
      if (selection === null) { status.textContent = i18n.t('sw.followCancelled'); return; }
      const response = await fetch(`/api/congressional-activity/${encodedTicker}/subscription`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({ active: desiredState, ...selection })
      });
      const state = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(state.error || i18n.t('sw.followChangeFailed'));
      applyCongressionalActivityState(state);
    } catch (error) {
      status.textContent = error.message || i18n.t('sw.followChangeFailed');
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
    status.textContent = i18n.t(desiredState ? 'sw.insiderEnabling' : 'sw.insiderStopping');
    try {
      const selection = desiredState ? await window.StockWatchLists.choose(decodeURIComponent(encodedTicker), { signalTypes: ['INSIDER'] }) : {};
      if (selection === null) { status.textContent = i18n.t('sw.followCancelled'); return; }
      const response = await fetch(`/api/insider-activity/${encodedTicker}/subscription`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({ active: desiredState, ...selection })
      });
      const state = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(state.error || i18n.t('sw.followChangeFailed'));
      applyInsiderActivityState(state);
    } catch (error) {
      status.textContent = error.message || i18n.t('sw.followChangeFailed');
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
    status.textContent = i18n.t('sw.insider.loading');
    if (!dialog.open) dialog.showModal();
    setInsiderControlsDisabled(true);
    let hasCachedTrades = false;
    try {
      const cacheResponse = await fetch(`/api/insider-activity/${encodedTicker}/history`);
      const cachedHistory = await cacheResponse.json().catch(() => ({}));
      if (!cacheResponse.ok) {
        throw new Error(cachedHistory.error || i18n.t('sw.insider.loadFailed'));
      }
      renderInsiderHistory(cachedHistory);
      applyInsiderActivityState(cachedHistory);
      hasCachedTrades = Array.isArray(cachedHistory.trades) && cachedHistory.trades.length > 0;
      status.textContent = hasCachedTrades
              ? i18n.t('sw.insider.checkingNew')
              : i18n.t('sw.insider.checkingLatest');

      const refreshResponse = await fetch(
              `/api/insider-activity/${encodedTicker}/history/refresh`,
              {
                method: 'POST',
                headers: secureJsonHeaders()
              });
      const refreshedHistory = await refreshResponse.json().catch(() => ({}));
      if (!refreshResponse.ok) {
        throw new Error(refreshedHistory.error || i18n.t('sw.insider.newRowsFailed'));
      }
      renderInsiderHistory(refreshedHistory);
      applyInsiderActivityState(refreshedHistory);
    } catch (error) {
      status.textContent = error.message || i18n.t('sw.insider.historyFailed');
      status.dataset.state = 'unavailable';
      if (!hasCachedTrades) {
        empty.hidden = false;
        empty.textContent = i18n.t('sw.insider.noHistory');
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
    empty.textContent = i18n.t('sw.insider.noneInWindow');
    document.getElementById('insiderHistoryRows')
            .replaceChildren(...trades.map(createInsiderHistoryRow));
    empty.hidden = trades.length > 0;
    const cacheLabel = ['CACHE', 'REFRESHED', 'STALE'].includes(history.refreshStatus)
            ? i18n.t(`sw.insider.cache.${history.refreshStatus}`)
            : i18n.t('sw.insider.loaded');
    const cachedDate = history.cachedAt ? ` · ${i18n.t('sw.profile.updated', formatDateTime(history.cachedAt))}` : '';
    document.getElementById('insiderHistoryStatus').textContent = cacheLabel + cachedDate;
    document.getElementById('insiderHistoryWindow').textContent = i18n.t('sw.insider.window',
            formatCongressionalDate(history.windowStart), formatCongressionalDate(history.windowEnd));
    document.getElementById('insiderHistoryDisclaimer').textContent =
            `${history.returnMethodology || ''} ${history.alertBaselineNotice || ''}`.trim();
  }

  function createInsiderHistoryRow(trade) {
    const row = document.createElement('tr');
    const detailUrl = activityHistoryDetailUrl(trade, 'insider');
    row.append(
            historyCell(i18n.t('sw.col.insider'), trade.insiderName, trade.ownerRole || i18n.t('sw.insider.roleMissing')),
            historyActivityCell(trade),
            historyCell(
                    i18n.t('sw.col.sharesPrice'),
                    formatInsiderShares(trade.shares),
                    trade.transactionPrice == null
                            ? i18n.t('sw.insider.priceMissing')
                            : i18n.t('sw.insider.atPrice', formatInsiderMoney(trade.transactionPrice))),
            historyCell(i18n.t('sw.col.tradeValue'), formatInsiderMoney(trade.transactionValue)),
            historyCell(
                    i18n.t('sw.col.effectiveDate'),
                    formatCongressionalDate(trade.transactionDate),
                    i18n.t('sw.insider.filingDateShown')),
            historyCell(i18n.t('sw.col.filed'), formatCongressionalDate(trade.filingDate)),
            insiderReturnCell(trade),
            historySourceCell(trade, detailUrl)
    );
    makeActivityHistoryRowInteractive(row, detailUrl, trade.insiderName);
    return row;
  }

  function insiderReturnCell(trade) {
    const cell = document.createElement('td');
    cell.dataset.label = i18n.t('sw.col.directionalReturn');
    if (trade.returnPercent == null) {
      const unavailable = document.createElement('span');
      unavailable.className = 'insider-return unavailable';
      unavailable.textContent = i18n.t('sw.insider.awaitingPrices');
      cell.append(unavailable);
      return cell;
    }
    const value = Number(trade.returnPercent);
    const label = document.createElement('strong');
    label.className = `insider-return ${value >= 0 ? 'positive' : 'negative'}`;
    label.textContent = `${value >= 0 ? '+' : ''}${i18n.number(value, 2)}%`;
    cell.append(label);
    if (trade.returnAsOf) {
      const asOf = document.createElement('span');
      asOf.textContent = i18n.t('sw.insider.returnWindow', formatCongressionalDate(trade.transactionDate), formatCongressionalDate(trade.returnAsOf));
      cell.append(asOf);
    }
    return cell;
  }

  function formatInsiderShares(value) {
    if (value == null) return i18n.t('sw.insider.sharesMissing');
    return i18n.plural('sw.insider.shares', Number(value), new Intl.NumberFormat(i18n.tag, {maximumFractionDigits: 2}).format(Number(value)));
  }

  function formatInsiderMoney(value) {
    if (value == null) return i18n.t('sw.unavailableCap');
    return new Intl.NumberFormat(i18n.tag, {
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
    status.textContent = i18n.t('sw.congress.loading');
    if (!dialog.open) dialog.showModal();
    setCongressionalControlsDisabled(true);
    try {
      const response = await fetch(`/api/congressional-activity/${encodedTicker}/history`);
      const history = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(history.error || i18n.t('sw.congress.historyFailed'));
      renderCongressionalHistory(history);
      applyCongressionalActivityState(history);
    } catch (error) {
      status.textContent = error.message || i18n.t('sw.congress.historyFailed');
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

    const cacheLabel = ['CACHE', 'REFRESHED', 'REFRESHING', 'COOLDOWN', 'STALE'].includes(history.cacheStatus)
            ? i18n.t(`sw.congress.cache.${history.cacheStatus}`)
            : i18n.t('sw.congress.loaded');
    const cachedDate = history.cachedAt ? ` · ${i18n.t('sw.profile.updated', formatDateTime(history.cachedAt))}` : '';
    document.getElementById('congressionalHistoryStatus').textContent = cacheLabel + cachedDate;
    document.getElementById('congressionalHistoryWindow').textContent =
            history.relevanceNotice || i18n.plural('sw.congress.window', history.historyDays || 365, history.historyDays || 365);
    if (history.attribution?.disclaimer) {
      document.getElementById('congressionalHistoryDisclaimer').textContent =
              `${history.attribution.disclaimer} ${history.alertBaselineNotice || ''}`.trim();
    }
  }

  function createCongressionalHistoryRow(trade) {
    const row = document.createElement('tr');
    const detailUrl = activityHistoryDetailUrl(trade, 'congressional');
    row.append(
            historyCell(i18n.t('sw.col.member'), trade.memberName, trade.chamber),
            historyActivityCell(trade),
            historyCell(i18n.t('sw.col.reportedValue'), trade.amountRange),
            historyCell(i18n.t('sw.col.transactionDate'), formatCongressionalDate(trade.transactionDate)),
            historyCell(i18n.t('sw.col.disclosureDate'), formatCongressionalDate(trade.disclosureDate)),
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
    row.setAttribute('aria-label', actorName ? i18n.t('sw.viewActivityFor', actorName) : i18n.t('sw.viewActivityThis'));
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
    strong.textContent = primary || i18n.t('sw.unavailableCap');
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
    cell.dataset.label = i18n.t('sw.col.activity');
    const badge = document.createElement('span');
    badge.className = `congressional-history-type ${trade.transactionType === 'PURCHASE' ? 'purchase' : 'sale'}`;
    badge.textContent = trade.transactionTypeLabel || trade.transactionType || i18n.t('sw.col.activity');
    cell.append(badge);
    return cell;
  }

  function historySourceCell(trade, detailUrl) {
    const cell = document.createElement('td');
    cell.dataset.label = i18n.t('sw.col.filing');
    if (detailUrl) {
      const signalLink = document.createElement('a');
      signalLink.href = detailUrl;
      signalLink.className = 'history-signal-detail-link';
      signalLink.textContent = i18n.t('sw.viewSignal');
      cell.append(signalLink);
    }
    const sourceUrl = trade?.sourceUrl;
    if (typeof sourceUrl === 'string' && /^https?:\/\//i.test(sourceUrl)) {
      const link = document.createElement('a');
      link.href = sourceUrl;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = i18n.t('sw.officialFiling');
      cell.append(link);
    } else if (!detailUrl) {
      cell.textContent = i18n.t('sw.unavailableCap');
    }
    return cell;
  }

  function formatCongressionalDate(value) {
    if (!value) return i18n.t('sw.unavailableCap');
    const date = new Date(`${value}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat(i18n.tag, {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC'
    }).format(date);
  }

  function formatDateTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat(i18n.tag, {
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
    trackedAlertSummary = i18n.t('sw.trackedStocks', state.trackedStocks, state.maxTrackedStocks);
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

  function changedOutlookInputs() {
    if (!outlookFollowStateLoaded) return [];
    return outlookFollowInputs().filter(input => (input.dataset.persisted === 'true') !== input.checked);
  }

  function hasUnappliedAlertChanges() {
    return changedAlertInputs().length + changedOutlookInputs().length > 0;
  }

  function updateAlertDraftUi() {
    const changedCount = changedAlertInputs().length + changedOutlookInputs().length;
    const applyButton = document.getElementById('applyAlertChangesBtn');
    const draftStatus = document.getElementById('alertDraftStatus');
    const alertPanel = document.querySelector('.alert-panel');
    if (outlookFollowStateLoaded) document.getElementById('outlookFollowStatus').textContent = changedOutlookInputs().length
            ? i18n.t('sw.outlookChanged') : i18n.t('sw.outlookAlertsReady');

    applyButton.disabled = !alertStateLoaded || alertSaveInProgress
            || technicalFollowAllBusy || changedCount === 0;
    alertPanel.classList.toggle('has-unapplied-changes', changedCount > 0);
    setAlertBeforeUnloadGuard(!pageExitAllowed && changedCount > 0);
    if (alertSaveInProgress) {
      applyButton.textContent = i18n.t('sw.applying');
      draftStatus.textContent = i18n.t('sw.savingChanges');
      updateTechnicalFollowAllButton();
      return;
    }
    applyButton.textContent = i18n.t('watchlistPicker.apply');
    draftStatus.textContent = changedCount === 0
            ? i18n.t('sw.noUnapplied')
            : i18n.plural('sw.unappliedChanges', changedCount, changedCount);
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
    const outlookInputs = changedOutlookInputs();
    if (changedInputs.length === 0 && outlookInputs.length === 0) return true;

    const status = document.getElementById('alertStatus');
    alertSaveInProgress = true;
    setAlertInputsDisabled(true);
    setOutlookFollowInputsDisabled(true);
    status.innerText = i18n.t('sw.applyingAlertChanges');
    updateAlertDraftUi();

    try {
      const selection = [...changedInputs, ...outlookInputs].some(input => input.checked)
              ? await window.StockWatchLists.choose(decodeURIComponent(encodedTicker), { signalTypes: [...new Set([
                  ...changedInputs.filter(input => input.checked).map(input => input.dataset?.alertFamily || 'CANDLESTICK'),
                  ...outlookInputs.filter(input => input.checked).map(() => 'OUTLOOK')])] }) : {};
      if (selection === null) { status.textContent = i18n.t('sw.changesNotApplied'); return false; }
      const response = await fetch(`/api/alerts/${encodedTicker}`, {
        method: 'PUT',
        headers: secureJsonHeaders(),
        body: JSON.stringify({ changes: changedInputs.map(alertChangePayload),
          outlookChanges: outlookInputs.map(input => ({ interval: input.dataset.outlookFollowInterval, active: input.checked })),
          ...selection })
      });

      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        status.innerText = payload.error || i18n.t('sw.applyFailed');
        return false;
      }

      hydrateAlertDraft(payload);
      if (payload.outlookState) hydrateOutlookFollowState(payload.outlookState);
      status.innerText = `${trackedAlertSummary} · ${i18n.t('sw.changesApplied')}`;
      return true;
    } catch (err) {
      status.innerText = i18n.t('sw.applyFailed');
      console.warn("Alert batch update failed", err);
      return false;
    } finally {
      alertSaveInProgress = false;
      setAlertInputsDisabled(technicalFollowAllBusy || !alertStateLoaded);
      setOutlookFollowInputsDisabled(technicalFollowAllBusy || !outlookFollowStateLoaded);
      updateAlertDraftUi();
    }
  }

  function hasAnyTechnicalFollow() {
    const patternFollowed = alertInputs().some(input => input.checked);
    const outlookFollowed = outlookFollowInputs().some(input => input.checked);
    return patternFollowed || outlookFollowed;
  }

  function updateTechnicalFollowAllButton() {
    const button = document.getElementById('toggleAllTechnicalMonitoringBtn');
    if (!button) return;
    const stateReady = alertStateLoaded && outlookFollowStateLoaded;
    const anyFollowed = stateReady && hasAnyTechnicalFollow();
    button.disabled = !stateReady || alertSaveInProgress
            || technicalFollowAllBusy;
    button.setAttribute('aria-pressed', String(anyFollowed));
    button.classList.toggle('following', anyFollowed);
    button.textContent = technicalFollowAllBusy
            ? i18n.t(anyFollowed ? 'sw.unfollowingAll' : 'sw.followingAll')
            : i18n.t(!stateReady ? 'sw.loadingFollowState' : (anyFollowed ? 'sw.unfollowAll' : 'sw.followAll'));
  }

  function toggleAllTechnicalMonitoring() {
    if (alertSaveInProgress || !alertStateLoaded || !outlookFollowStateLoaded) return;
    const followEverything = !hasAnyTechnicalFollow();
    [...alertInputs(), ...outlookFollowInputs()].forEach(input => { input.checked = followEverything; });
    updateAlertDraftUi();
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
    saveButton.textContent = i18n.t('sw.saving');
    dialogStatus.textContent = i18n.t('sw.applyingYourChanges');
    const saved = await persistAlertDraft();
    saveButton.disabled = false;
    saveButton.textContent = i18n.t('sw.saveAndLeave');
    if (saved) {
      continuePendingNavigation();
      return;
    }
    dialogStatus.textContent = i18n.t('sw.saveFailedDraft');
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

    // Let local form handlers (including the watchlist picker) prevent submission
    // before deciding whether this form will actually navigate away from the page.
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
    });

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
    if (priceContainer.clientWidth && priceContainer.clientHeight) {
      priceChart.resize(priceContainer.clientWidth, priceContainer.clientHeight);
    }
    const priceOnlyContainer = document.getElementById('priceOnlyChartContainer');
    if (priceOnlyActive() && priceOnlyContainer.clientWidth && priceOnlyContainer.clientHeight) {
      priceOnlyChart.resize(priceOnlyContainer.clientWidth, priceOnlyContainer.clientHeight);
    }
    indicatorManager?.applyPaneHeights();
    indicatorManager?.positionLegends();
    scheduleAnchoredVolumeProfileRender();
    scheduleElliottHitTargetPositioning();
    scheduleHistoricalCandlestickHitTargetPositioning();
    scheduleHarmonicHitTargetPositioning();
  }

  /** The price chart's handle sizes the whole chart; its panes share that height. */
  function resizableHeight(handle, target) {
    return handle.dataset.chartResizeTarget === 'priceChartStage'
            ? currentChartHeight()
            : target.getBoundingClientRect().height;
  }

  /** A height from the resize handle, or null to fit the window again. */
  function chooseChartHeight(height) {
    chosenChartHeight = height;
    indicatorManager?.setExtra('chartHeight', height);
    indicatorManager?.setExtra('priceHeight', null);
    updateStageHeight();
  }

  function setResizableChartHeight(handle, requestedHeight) {
    const target = document.getElementById(handle.dataset.chartResizeTarget);
    if (!target) return;
    const minimum = Number(handle.dataset.chartResizeMin);
    const maximum = Number(handle.dataset.chartResizeMax);
    const nextHeight = Math.round(Math.min(maximum, Math.max(minimum, requestedHeight)));
    if (handle.dataset.chartResizeTarget === 'priceChartStage') {
      chooseChartHeight(nextHeight);
      return;
    }
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
          handle?.setAttribute('aria-valuenow', String(Math.round(resizableHeight(handle, entry.target))));
        });
        scheduleGeneralWorkspaceChartResize();
      });
    }

    handles.forEach(handle => {
      const target = document.getElementById(handle.dataset.chartResizeTarget);
      if (!target) return;
      chartResizeObserver?.observe(target);
      const initialHeight = resizableHeight(handle, target)
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
        startHeight = resizableHeight(handle, target);
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
        const currentHeight = resizableHeight(handle, target);
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
        if (handle.dataset.chartResizeTarget === 'priceChartStage') chooseChartHeight(null);
        else setResizableChartHeight(handle, Number(handle.dataset.chartResizeDefault));
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

  /** {@code fromChart}: opened from a long or short position drawn on the chart. */
  function openStockDemoTradeDialog(side, fromChart = false) {
    const dialog = document.getElementById('stockDemoTradeDialog');
    const status = document.getElementById('stockDemoTradeStatus');
    const sizeInput = document.getElementById('stockDemoTradeSizeInput');
    const sizeField = document.getElementById('stockDemoTradeSizeField');
    const confirmButton = document.getElementById('confirmStockDemoTrade');
    pendingStockDemoTradeSide = side === 'SELL' ? 'SELL' : 'BUY';
    pendingStockDemoTradeInterval = currentInterval;
    pendingStockDemoTradeRequestId = crypto.randomUUID();
    stockDemoTradeSizeMode = 'none';
    document.querySelectorAll('[data-stock-demo-size-mode]').forEach(option => {
      option.classList.toggle('active', option.dataset.stockDemoSizeMode === 'none');
    });
    sizeField.hidden = true;
    sizeInput.value = '';
    document.getElementById('stockDemoTradeDialogTitle').textContent =
            i18n.t(pendingStockDemoTradeSide === 'BUY' ? 'outlook.demoBuyTitle' : 'outlook.demoSellTitle', ticker);
    document.getElementById('stockDemoTradeExplanation').textContent =
            i18n.t(pendingStockDemoTradeSide === 'BUY' ? 'outlook.demoBuyText' : 'outlook.demoSellText');
    status.textContent = i18n.t(`sw.demo.analysisSaved.${apiIntervalEnum(pendingStockDemoTradeInterval)}`);
    // The trade records the current quote; the drawn entry, target and stop stay on the chart.
    if (fromChart) status.textContent = `${status.textContent} ${i18n.t('sw.demo.fromPosition')}`;
    confirmButton.disabled = false;
    confirmButton.textContent = i18n.t('outlook.createDemoTrade');
    dialog.showModal();
  }

  function initializeStockDemoTrading() {
    const dialog = document.getElementById('stockDemoTradeDialog');
    const status = document.getElementById('stockDemoTradeStatus');
    const sizeInput = document.getElementById('stockDemoTradeSizeInput');
    const sizeField = document.getElementById('stockDemoTradeSizeField');
    const confirmButton = document.getElementById('confirmStockDemoTrade');

    document.querySelectorAll('[data-stock-demo-trade]').forEach(button => {
      button.addEventListener('click', () => openStockDemoTradeDialog(button.dataset.stockDemoTrade));
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
                i18n.t(stockDemoTradeSizeMode === 'quantity' ? 'outlook.numberOfShares' : 'outlook.virtualAmount');
        document.getElementById('stockDemoTradeSizeHelp').textContent =
                i18n.t(stockDemoTradeSizeMode === 'quantity' ? 'outlook.amountFromPrice' : 'outlook.quantityFromPrice');
      });
    });

    confirmButton.addEventListener('click', async () => {
      const size = sizeInput.value;
      if (stockDemoTradeSizeMode !== 'none' && (!size || Number(size) <= 0)) {
        status.textContent = i18n.t('outlook.positiveSize');
        return;
      }
      confirmButton.disabled = true;
      confirmButton.textContent = i18n.t('outlook.capturingQuote');
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
          throw new Error(error.message || error.detail || error.error || i18n.t('outlook.createFailed'));
        }
        const trade = await response.json();
        window.location.assign(trade.detailUrl);
      } catch (error) {
        status.textContent = error.message || i18n.t('outlook.createFailed');
        confirmButton.disabled = false;
        confirmButton.textContent = i18n.t('outlook.createDemoTrade');
      }
    });
  }

  window.addEventListener('resize', () => {
    if (!refitChartToWindow()) scheduleGeneralWorkspaceChartResize();
  });

  window.addEventListener('stockwatch:themechange', event => {
    if (!priceChart) return;
    const theme = event.detail?.theme;
    priceChart.applyOptions(chartThemeOptions(theme));
    priceSeries.applyOptions(StockWatchChartTypes.options(seriesType, priceColors(theme)));
    if (priceOnlyChart) {
      priceOnlyChart.applyOptions(chartThemeOptions(theme));
      priceOnlyKind = null;
    }
    setPriceSeriesData();
    indicatorManager?.refreshVolume();
    applySplitMarkers();
    scheduleAnchoredVolumeProfileRender();
  });

  document.querySelectorAll('[data-chart-type]').forEach(button => {
    button.addEventListener('click', () => setChartType(button.dataset.chartType));
  });
  document.querySelectorAll('[data-chart-range]').forEach(button => {
    button.addEventListener('click', () => showDateRange(button.dataset.chartRange));
  });
  document.querySelectorAll('[data-chart-scale]').forEach(button => {
    button.addEventListener('click', () => togglePriceScale(button.dataset.chartScale));
  });
  document.querySelectorAll('[data-chart-icon]').forEach(button => {
    const names = { data: 'dataWindow', camera: 'camera', fullscreen: 'fullscreen' };
    if (typeof StockWatchDrawingIcons !== 'undefined') button.append(StockWatchDrawingIcons.icon(names[button.dataset.chartIcon], 16));
  });
  document.getElementById('goToDateForm').addEventListener('submit', goToDate);
  document.getElementById('openIndicatorBrowser').addEventListener('click', () => indicatorManager?.openBrowser());
  document.getElementById('dataWindowToggle').addEventListener('click', toggleDataWindow);
  document.getElementById('chartScreenshotButton').addEventListener('click', saveChartScreenshot);
  document.getElementById('chartFullscreenButton').addEventListener('click', toggleChartFullscreen);
  document.getElementById('splitMarkersToggle').addEventListener('click', toggleSplitMarkers);
  document.getElementById('chartAlertsButton').addEventListener('click', () => {
    if (typeof StockWatchChartAlerts !== 'undefined') StockWatchChartAlerts.openManager();
  });
  document.addEventListener('fullscreenchange', onChartFullscreenChange);
  document.addEventListener('keydown', event => {
    // "/" opens the indicator list, as on TradingView.
    if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.target.closest?.('input, textarea, select, [contenteditable="true"], dialog')) return;
    if (document.getElementById('generalWorkspacePanel').hidden) return;
    event.preventDefault();
    indicatorManager?.openBrowser();
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
  document.getElementById('volumeChartToggle')
          .addEventListener('click', toggleVolumeChart);
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
  for (const id of ['closeHistoricalCandlestickLookbackBtn', 'cancelHistoricalScanBtn']) {
    document.getElementById(id).addEventListener('click', () => document.getElementById('historicalCandlestickLookbackDialog').close());
  }
  document.getElementById('historicalScanInterval').addEventListener('change', event => {
    showHistoricalCandlestickLookbackPicker(event.target.value);
  });
  document.getElementById('historicalScanView').addEventListener('change', updateHistoricalCandlestickLookbackPreview);
  document.getElementById('historicalCandlestickLookbackInput')
          .addEventListener('input', updateHistoricalCandlestickLookbackPreview);
  document.getElementById('historicalCandlestickLookbackForm')
          .addEventListener('submit', submitHistoricalCandlestickLookback);
  document.getElementById('closeHistoricalCandlestickResultsBtn')
          .addEventListener('click', () => document.getElementById('historicalCandlestickResultsDialog').close());
  document.getElementById('changeHistoricalCandlestickIntervalBtn')
          .addEventListener('click', showHistoricalCandlestickIntervalPicker);

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
    initializeCompareDialog();
  };
