package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ScheduledAlertServiceTest {

    @Test
    void dailyScheduleRunsTuesdayThroughSaturdayForMondayThroughFridayCandles() throws Exception {
        Scheduled scheduled = ScheduledAlertService.class.getDeclaredMethod("enqueueDailyChecks")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${alerts.schedule.daily-cron:0 0 0 * * TUE-SAT}");
        assertThat(scheduled.zone()).isEqualTo("${alerts.schedule.zone:Europe/Brussels}");
    }

    @Test
    void setupScoreDoesNotSuppressValidatedYahooCandlestick() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE, 5, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 299)))
                .thenReturn(List.of(
                        candle(symbol, "1d", 5, 98, 105, 97, 104),
                        candle(symbol, "1d", 4, 103, 104, 98, 99),
                        candle(symbol, "1d", 3, 105, 106, 101, 102),
                        candle(symbol, "1d", 2, 108, 109, 104, 105),
                        candle(symbol, "1d", 1, 110, 111, 107, 108)
                ));
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);

        service.processSymbolInterval(symbol, TimeInterval.DAILY);

        verify(notificationService).sendSignalEmail(any(), any());
        ArgumentCaptor<AlertEvent> savedEvent = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getConfidenceScore()).isLessThan(75);
        assertThat(savedEvent.getValue().getConfidenceReasons())
                .anyMatch(reason -> reason.startsWith("Pattern quality +"));
    }

    @Test
    void recoveredCheckEvaluatesCandlesBeforeItsOriginalScheduledTime() {
        String symbol = "SAP.DE";
        Instant scheduledFor = Instant.ofEpochSecond(3 * 86_400L);
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        CandlePatternDetectionService detectionService = mock(CandlePatternDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);
        DetectedSignal qualifiedSignal = new DetectedSignal(
                CandlePattern.BULLISH_ENGULFING,
                TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE,
                88,
                List.of("qualified regression fixture"),
                2 * 86_400L,
                101.0
        );

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE, 3, null));
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1d", scheduledFor.getEpochSecond(), PageRequest.of(0, 299)))
                .thenReturn(List.of(
                        candle(symbol, "1d", 2, 89, 103, 87, 101),
                        candle(symbol, "1d", 1, 100, 105, 88, 90)
                ));
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);
        when(detectionService.detectAlertSignals(any())).thenReturn(List.of(qualifiedSignal));

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detectionService, new ElliottWaveDetectionService());

        service.processSymbolInterval(symbol, TimeInterval.DAILY, scheduledFor);

        verify(candleRepository).findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1d", scheduledFor.getEpochSecond(), PageRequest.of(0, 299));
        verify(candleRepository, never()).findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 299));
        verify(detectionService).detectAlertSignals(any());
        verify(notificationService).sendSignalEmail(rule, qualifiedSignal);
        verify(alertEventRepository).save(any());
    }

    @Test
    void candlestickCheckUsesOnlyTheRequestedStocksCandles() {
        String symbol = "VST";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        CandlePatternDetectionService detectionService = mock(CandlePatternDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.TWELVE_DATA, 2, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 299)))
                .thenReturn(List.of(
                        candle(symbol, "1d", 2, 100, 103, 99, 102),
                        candle(symbol, "1d", 1, 98, 101, 97, 100)
                ));
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));
        when(detectionService.detectAlertSignals(any())).thenReturn(List.of());

        ScheduledAlertService service = new ScheduledAlertService(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                detectionService,
                new ElliottWaveDetectionService(),
                notificationService,
                mock(AlertCheckJobStore.class),
                mock(AlertScheduleRecoveryService.class),
                true,
                true,
                true,
                300,
                60,
                3
        );

        service.processSymbolInterval(symbol, TimeInterval.DAILY);

        ArgumentCaptor<List<EnrichedCandle>> assetCandles = ArgumentCaptor.captor();
        verify(detectionService).detectAlertSignals(assetCandles.capture());
        assertThat(assetCandles.getValue())
                .extracting(EnrichedCandle::close)
                .containsExactly(100.0, 102.0);
        verify(marketDataService).syncCandles(symbol, "1d", null, true);
        verifyNoMoreInteractions(marketDataService);
    }

    @Test
    void yahooBackedMonthlyCandlesStillTriggerAutomaticElliottEmailAndEventRecording() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        AlertRule rule = rule(symbol, TimeInterval.MONTHLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);

        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE, 76, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1mo", PageRequest.of(0, 299)))
                .thenReturn(syntheticElliottCandles(symbol).reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.MONTHLY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);

        service.processSymbolInterval(symbol, TimeInterval.MONTHLY);

        verify(notificationService).sendSignalEmail(any(), any());
        verify(alertEventRepository).save(any());
    }

    @Test
    void weeklyEndOfWaveCTriggersAutomaticElliottEmailAndEventRecording() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        AlertRule rule = rule(symbol, TimeInterval.WEEKLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);

        when(marketDataService.syncCandles(symbol, "1wk", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1wk", PageRequest.of(0, 299)))
                .thenReturn(syntheticElliottCandles(symbol).reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.WEEKLY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);
        service.processSymbolInterval(symbol, TimeInterval.WEEKLY);

        verify(notificationService).sendSignalEmail(any(), any());
        verify(alertEventRepository).save(any());
    }

    @Test
    void bullishWaveVEndTriggersAutomaticSellEmailAndEventRecording() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        AlertRule rule = rule(symbol, TimeInterval.MONTHLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL);
        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1mo", PageRequest.of(0, 299)))
                .thenReturn(syntheticWaveVEndCandles(symbol).reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.MONTHLY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);
        service.processSymbolInterval(symbol, TimeInterval.MONTHLY);

        verify(notificationService).sendSignalEmail(any(), any());
        verify(alertEventRepository).save(any());
    }

    @Test
    void scheduledElliottChecksUseOnlyConfidenceFilteredAlertSignals() {
        String symbol = "MSFT";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService elliottWaveDetectionService = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.WEEKLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        when(marketDataService.syncCandles(symbol, "1wk", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1wk", PageRequest.of(0, 299)))
                .thenReturn(syntheticElliottCandles(symbol).reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.WEEKLY)).thenReturn(List.of(rule));
        when(elliottWaveDetectionService.detectAlertSignals(any())).thenReturn(List.of());

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, elliottWaveDetectionService);

        service.processSymbolInterval(symbol, TimeInterval.WEEKLY);

        verify(elliottWaveDetectionService).detectAlertSignals(any());
        verify(elliottWaveDetectionService, never()).detect(any());
        verify(notificationService, never()).sendSignalEmail(any(), any());
        verify(alertEventRepository, never()).save(any());
    }

    @Test
    void scheduledElliottChecksDeliverNewTurningPointVariants() {
        String symbol = "MSFT";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService elliottWaveDetectionService = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.MONTHLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL);
        long signalTimestamp = 68L * 86_400L;
        DetectedSignal truncatedFifth = new DetectedSignal(
                CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END,
                TradeSignal.SELL,
                SignalStength.HIGH_CONFIDENCE,
                81,
                List.of("Truncated Wave V — reduced confidence"),
                signalTimestamp,
                149.0);
        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1mo", PageRequest.of(0, 299)))
                .thenReturn(syntheticWaveVEndCandles(symbol).reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.MONTHLY)).thenReturn(List.of(rule));
        when(elliottWaveDetectionService.detectAlertSignals(any())).thenReturn(List.of(truncatedFifth));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(
                rule, truncatedFifth.pattern(), signalTimestamp)).thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, elliottWaveDetectionService);

        service.processSymbolInterval(symbol, TimeInterval.MONTHLY);

        verify(notificationService).sendSignalEmail(rule, truncatedFifth);
        ArgumentCaptor<AlertEvent> savedEvent = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getPattern())
                .isEqualTo(CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END);
        assertThat(savedEvent.getValue().getConfidenceScore()).isEqualTo(81);
    }

    private ScheduledAlertService service(AlertRuleRepository alertRuleRepository,
                                          AlertEventRepository alertEventRepository,
                                          CandleRepository candleRepository,
                                          MarketDataService marketDataService,
                                          AlertNotificationService notificationService) {
        return service(alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, new CandlePatternDetectionService(), new ElliottWaveDetectionService());
    }

    private ScheduledAlertService service(AlertRuleRepository alertRuleRepository,
                                          AlertEventRepository alertEventRepository,
                                          CandleRepository candleRepository,
                                          MarketDataService marketDataService,
                                          AlertNotificationService notificationService,
                                          ElliottWaveDetectionService elliottWaveDetectionService) {
        return new ScheduledAlertService(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                elliottWaveDetectionService,
                notificationService,
                mock(AlertCheckJobStore.class),
                mock(AlertScheduleRecoveryService.class),
                true,
                true,
                true,
                300,
                60,
                3
        );
    }

    private ScheduledAlertService service(AlertRuleRepository alertRuleRepository,
                                          AlertEventRepository alertEventRepository,
                                          CandleRepository candleRepository,
                                          MarketDataService marketDataService,
                                          AlertNotificationService notificationService,
                                          CandlePatternDetectionService detectionService,
                                          ElliottWaveDetectionService elliottWaveDetectionService) {
        return new ScheduledAlertService(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                detectionService,
                elliottWaveDetectionService,
                notificationService,
                mock(AlertCheckJobStore.class),
                mock(AlertScheduleRecoveryService.class),
                true,
                true,
                true,
                300,
                60,
                3
        );
    }

    private AlertRule rule(String symbol,
                           TimeInterval interval,
                           AlertPatternFamily family,
                           TradeSignal tradeSignal) {
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol(symbol);
        asset.setCompanyName("SAP SE");
        asset.setExchange("XETRA");
        asset.setCurrency("EUR");
        User user = new User();
        user.setEmail("alerts@example.com");
        AlertRule rule = new AlertRule();
        rule.setStockAsset(asset);
        rule.setUser(user);
        rule.setInterval(interval);
        rule.setPatternFamily(family);
        rule.setTradeSignal(tradeSignal);
        return rule;
    }

    private Candle candle(String symbol,
                          String interval,
                          int day,
                          double open,
                          double high,
                          double low,
                          double close) {
        return new Candle(symbol, interval, day * 86_400L, open, high, low, close, 1_000L);
    }

    private List<Candle> syntheticElliottCandles(String symbol) {
        List<Anchor> anchors = List.of(
                new Anchor(1, 112.0),
                new Anchor(6, 100.0),
                new Anchor(14, 121.0),
                new Anchor(24, 110.0),
                new Anchor(38, 143.0),
                new Anchor(54, 126.0),
                new Anchor(68, 150.0),
                new Anchor(74, 134.0),
                new Anchor(80, 144.0),
                new Anchor(86, 130.0),
                new Anchor(87, 133.0)
        ).stream().sorted(Comparator.comparingInt(Anchor::index)).toList();
        List<Candle> candles = new ArrayList<>();
        for (int anchorIndex = 0; anchorIndex < anchors.size() - 1; anchorIndex++) {
            Anchor start = anchors.get(anchorIndex);
            Anchor end = anchors.get(anchorIndex + 1);
            int from = anchorIndex == 0 ? start.index() : start.index() + 1;
            for (int index = from; index <= end.index(); index++) {
                double progress = (index - start.index()) / (double) (end.index() - start.index());
                double close = start.price() + (end.price() - start.price()) * progress;
                candles.add(new Candle(
                        symbol, "1mo", index * 86_400L, close - 0.4, close + 0.6, close - 0.6, close, 1_500L));
            }
        }
        return candles;
    }

    private List<Candle> syntheticWaveVEndCandles(String symbol) {
        List<Anchor> anchors = List.of(
                new Anchor(1, 112.0), new Anchor(6, 100.0), new Anchor(14, 121.0),
                new Anchor(24, 110.0), new Anchor(38, 143.0), new Anchor(54, 126.0),
                new Anchor(68, 150.0), new Anchor(69, 147.0)
        );
        List<Candle> candles = new ArrayList<>();
        for (int anchorIndex = 0; anchorIndex < anchors.size() - 1; anchorIndex++) {
            Anchor start = anchors.get(anchorIndex);
            Anchor end = anchors.get(anchorIndex + 1);
            int from = anchorIndex == 0 ? start.index() : start.index() + 1;
            for (int index = from; index <= end.index(); index++) {
                double progress = (index - start.index()) / (double) (end.index() - start.index());
                double close = start.price() + (end.price() - start.price()) * progress;
                candles.add(new Candle(symbol, "1mo", index * 86_400L,
                        close - 0.4, close + 0.6, close - 0.6, close, 1_500L));
            }
        }
        return candles;
    }

    private record Anchor(int index, double price) {
    }
}
