package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TechnicalOutlookServiceTest {
    private CandleRepository candleRepository;
    private StockAssetRepository stockAssetRepository;
    private AlertEventRepository alertEventRepository;
    private MarketDataService marketDataService;
    private TechnicalOutlookService service;
    private User user;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        candleRepository = mock(CandleRepository.class);
        stockAssetRepository = mock(StockAssetRepository.class);
        alertEventRepository = mock(AlertEventRepository.class);
        CongressionalTradeDeliveryRepository congressionalRepository =
                mock(CongressionalTradeDeliveryRepository.class);
        InsiderTradeDeliveryRepository insiderRepository = mock(InsiderTradeDeliveryRepository.class);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(alertEventRepository.findRecentForTechnicalOutlook(
                any(), anyString(), any(), any(Long.class), any(Pageable.class))).thenReturn(List.of());
        when(congressionalRepository.findForTechnicalOutlook(
                any(), anyString(), any(), any(Pageable.class))).thenReturn(List.of());
        when(insiderRepository.findForTechnicalOutlook(
                any(), anyString(), any(), any(Pageable.class))).thenReturn(List.of());
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        service = new TechnicalOutlookService(
                marketDataService,
                candleRepository,
                stockAssetRepository,
                new TechnicalIndicatorEnrichmentService(),
                alertEventRepository,
                congressionalRepository,
                insiderRepository);
        user = new User();
    }

    @Test
    void buildsAuditableRawAndCategoryBalancedScoresFromCompletedCandles() {
        List<Candle> candles = risingDailyCandles(260);
        stubLatest("AAPL", "1d", candles);

        TechnicalOutlookService.OutlookView outlook = service.getOutlook(user, "aapl", "1d");

        assertThat(outlook.available()).isTrue();
        assertThat(outlook.intervalLabel()).isEqualTo("Daily");
        assertThat(outlook.candles()).hasSize(260);
        assertThat(outlook.indicators()).extracting(TechnicalOutlookService.IndicatorView::key)
                .contains("rsi", "ema", "sma", "macd", "cci", "bollinger", "atr", "vwap",
                        "relativeVolume", "volumeProfile", "supportResistance", "adx", "dmi",
                        "stochastic", "stochasticRsi", "obv", "mfi", "donchian", "keltner",
                        "ta4jTrend", "volumeProfileKde");
        assertThat(outlook.indicators()).extracting(TechnicalOutlookService.IndicatorView::label)
                .contains("RSI 14", "EMA 20 / EMA 50", "Price vs SMA 200",
                        "MACD 12/26/9 histogram", "CCI 20", "ATR 14", "Rolling VWAP 20",
                        "Support / resistance 20");
        assertThat(outlook.indicatorSettings().rsiPeriod()).isEqualTo(14);
        assertThat(outlook.indicatorSettings().fastEmaPeriod()).isEqualTo(20);
        assertThat(outlook.indicatorSettings().slowEmaPeriod()).isEqualTo(50);
        assertThat(outlook.indicatorSettings().longSmaPeriod()).isEqualTo(200);
        assertThat(outlook.rawScore().denominator()).isEqualTo(11);
        assertThat(outlook.indicators())
                .filteredOn(indicator -> List.of("adx", "dmi", "stochastic", "stochasticRsi",
                        "obv", "mfi", "donchian", "keltner", "ta4jTrend", "volumeProfileKde")
                        .contains(indicator.key()))
                .allSatisfy(indicator -> assertThat(indicator.scored()).isFalse());
        assertThat(outlook.categories()).extracting(TechnicalOutlookService.CategoryView::key)
                .containsExactly("TREND", "MOMENTUM", "VOLATILITY", "VOLUME", "PRICE_LOCATION");
        assertThat(outlook.headlineScore().denominator()).isEqualTo(5);
        assertThat(outlook.indicators().stream().filter(indicator -> indicator.key().equals("atr")).findFirst())
                .hasValueSatisfying(atr -> {
                    assertThat(atr.vote()).isZero();
                    assertThat(atr.rule()).contains("always neutral");
                });
        assertThat(outlook.methodology().thresholds()).contains("10%", "30%", "60%");
        assertThat(outlook.methodology().disclaimer()).contains("not probabilities");
        assertThat(outlook.indicators()).allSatisfy(indicator ->
                assertThat(indicator.series()).hasSizeLessThanOrEqualTo(160));
        verify(marketDataService, never()).syncCandles(anyString(), anyString(), any());
    }

    @Test
    void reusesBriefComputedOutlookCacheForTheSameUserProfileAndInterval() {
        List<Candle> candles = risingDailyCandles(260);
        stubLatest("AAPL", "1d", candles);

        TechnicalOutlookService.OutlookView first = service.getOutlook(user, "AAPL", "1d");
        clearInvocations(candleRepository);
        TechnicalOutlookService.OutlookView second = service.getOutlook(user, "AAPL", "1d");

        assertThat(second).isSameAs(first);
        verifyNoInteractions(candleRepository);
    }

    @Test
    void compactSummaryDefersResearchAndHistoricalIndicatorSeries() {
        List<Candle> candles = risingDailyCandles(260);
        stubLatest("AAPL", "1d", candles);

        TechnicalOutlookService.OutlookView summary = service.getSummaryOutlook(user, "AAPL", "1d");

        assertThat(summary.available()).isTrue();
        assertThat(summary.indicators()).extracting(TechnicalOutlookService.IndicatorView::key)
                .contains("rsi", "ema", "volumeProfile")
                .doesNotContain("adx", "stochastic", "volumeProfileKde");
        assertThat(summary.indicators()).allSatisfy(indicator -> assertThat(indicator.series()).isEmpty());
        assertThat(summary.candles()).allSatisfy(candle -> {
            assertThat(candle.valueAreaLow()).isNull();
            assertThat(candle.pointOfControl()).isNull();
            assertThat(candle.valueAreaHigh()).isNull();
        });
        assertThat(summary.marketComparison().ratioSeries()).isEmpty();
    }

    @Test
    void historicalChartPageUsesOlderWarmupCandlesAndReturnsOnlyTheRequestedPage() {
        List<Candle> allCandles = risingDailyCandles(699);
        List<Candle> requestedPage = List.copyOf(allCandles.subList(199, 699));
        long before = allCandles.getLast().getTimestamp() + 86_400L;
        when(marketDataService.loadCandlePage("AAPL", "1d", before, 500))
                .thenReturn(new MarketDataService.CandlePage(
                        requestedPage,
                        requestedPage.getFirst().getTimestamp(),
                        true,
                        MarketDataService.CandleSource.CACHE,
                        null));
        List<Candle> descending = new ArrayList<>(allCandles);
        Collections.reverse(descending);
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                org.mockito.ArgumentMatchers.eq("AAPL"),
                org.mockito.ArgumentMatchers.eq("1d"),
                org.mockito.ArgumentMatchers.eq(before),
                any(Pageable.class))).thenReturn(descending);

        TechnicalOutlookService.HistoricalChartPageView page =
                service.getHistoricalChartPage(user, "aapl", "1d", before, 500);

        assertThat(page.candles()).hasSize(500);
        assertThat(page.candles().getFirst().timestamp())
                .isEqualTo(requestedPage.getFirst().getTimestamp());
        assertThat(page.candles().getFirst().longSma()).isNotNull();
        assertThat(page.nextCursor()).isEqualTo(requestedPage.getFirst().getTimestamp());
        assertThat(page.hasMore()).isTrue();
        verify(marketDataService).loadCandlePage("AAPL", "1d", before, 500);
        verify(candleRepository).findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                org.mockito.ArgumentMatchers.eq("AAPL"),
                org.mockito.ArgumentMatchers.eq("1d"),
                org.mockito.ArgumentMatchers.eq(before),
                org.mockito.ArgumentMatchers.argThat(pageable -> pageable.getPageSize() == 699));
    }

    @Test
    void candleChangeEventInvalidatesTheComputedOutlook() {
        List<Candle> candles = risingDailyCandles(260);
        stubLatest("AAPL", "1d", candles);
        service.getSummaryOutlook(user, "AAPL", "1d");
        clearInvocations(candleRepository);

        service.candleDataChanged(new CandleDataChangedEvent("AAPL", "1d", 1));
        service.getSummaryOutlook(user, "AAPL", "1d");

        verify(candleRepository, org.mockito.Mockito.atLeastOnce())
                .findBySymbolAndTimeIntervalOrderByTimestampDesc(
                        org.mockito.ArgumentMatchers.eq("AAPL"),
                        org.mockito.ArgumentMatchers.eq("1d"), any(Pageable.class));
    }

    @Test
    void providerRefreshRunsOnlyThroughTheBackgroundExecutor() {
        when(marketDataService.syncCandlesForAnalysis(anyString(), anyString(), any(Integer.class)))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        service.configureTechnicalOutlookExecutor(Runnable::run);

        service.requestBackgroundRefresh("aapl", "1d");

        verify(marketDataService).syncCandlesForAnalysis("AAPL", "1d", 519);
        verify(marketDataService).syncCandlesForAnalysis("^GSPC", "1d", 420);
    }

    @Test
    void returnsAnExplicitUnavailableViewInsteadOfCountingMissingInputsAsNeutral() {
        TechnicalOutlookService.OutlookView outlook = service.getOutlook(user, "EMPTY", "weekly");

        assertThat(outlook.available()).isFalse();
        assertThat(outlook.rawScore().denominator()).isZero();
        assertThat(outlook.headlineScore().denominator()).isZero();
        assertThat(outlook.indicators()).isEmpty();
    }

    @Test
    void dailyOutlookUsesOnlyDailyNativeElliottEvents() {
        List<Candle> candles = risingDailyCandles(260);
        stubLatest("AAPL", "1d", candles);
        StockAsset asset = mock(StockAsset.class);
        when(asset.getTickerSymbol()).thenReturn("AAPL");

        AlertRule dailyRule = mock(AlertRule.class);
        when(dailyRule.getStockAsset()).thenReturn(asset);
        when(dailyRule.getInterval()).thenReturn(TimeInterval.DAILY);
        when(dailyRule.getPatternFamily()).thenReturn(AlertPatternFamily.ELLIOTT_WAVE);
        AlertEvent dailyEvent = mock(AlertEvent.class);
        when(dailyEvent.getId()).thenReturn(71L);
        when(dailyEvent.getAlertRule()).thenReturn(dailyRule);
        when(dailyEvent.getPattern()).thenReturn(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
        when(dailyEvent.getTradeSignal()).thenReturn(TradeSignal.BUY);
        when(dailyEvent.getSignalCandleTimestamp()).thenReturn(candles.getLast().getTimestamp());
        when(dailyEvent.getLifecycleStatus()).thenReturn(SignalLifecycleStatus.CONFIRMED);

        AlertRule monthlyRule = mock(AlertRule.class);
        when(monthlyRule.getStockAsset()).thenReturn(asset);
        when(monthlyRule.getInterval()).thenReturn(TimeInterval.MONTHLY);
        AlertEvent newerMonthlyEvent = mock(AlertEvent.class);
        when(newerMonthlyEvent.getAlertRule()).thenReturn(monthlyRule);
        when(newerMonthlyEvent.getSignalCandleTimestamp())
                .thenReturn(candles.getLast().getTimestamp() + 86_400L);
        when(alertEventRepository.findRecentForTechnicalOutlook(
                any(), anyString(), any(), any(Long.class), any(Pageable.class)))
                .thenReturn(List.of(dailyEvent));

        TechnicalOutlookService.OutlookView outlook = service.getOutlook(user, "AAPL", "1d");

        assertThat(outlook.recentSignals()).hasSize(1);
        assertThat(outlook.recentSignals().getFirst().id()).isEqualTo(71L);
        assertThat(outlook.recentSignals().getFirst().interval()).isEqualTo("DAILY");
        assertThat(outlook.categories())
                .filteredOn(category -> category.key().equals("ELLIOTT"))
                .singleElement()
                .satisfies(category -> assertThat(category.vote()).isEqualTo(1));
    }

    private List<Candle> risingDailyCandles(int count) {
        Instant first = Instant.parse("2025-01-02T21:00:00Z");
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double close = 100 + index * 0.35 + Math.sin(index / 6.0);
            double open = close - 0.25;
            candles.add(new Candle(
                    "AAPL",
                    "1d",
                    first.plus(index, ChronoUnit.DAYS).getEpochSecond(),
                    open,
                    close + 1.1,
                    open - 1.0,
                    close,
                    1_000_000L + index * 1_000L));
        }
        return candles;
    }

    private void stubLatest(String symbol, String interval, List<Candle> ascending) {
        List<Candle> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                org.mockito.ArgumentMatchers.eq(symbol),
                org.mockito.ArgumentMatchers.eq(interval),
                any(Pageable.class))).thenReturn(descending);
    }
}
