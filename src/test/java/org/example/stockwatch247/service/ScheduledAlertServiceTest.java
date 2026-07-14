package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;
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
    void yahooBackedDailyCandlesStillTriggerAutomaticPatternEmailAndEventRecording() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE, 2, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1d"))
                .thenReturn(List.of(
                        candle(symbol, "1d", 2, 89, 103, 87, 101),
                        candle(symbol, "1d", 1, 100, 105, 88, 90)
                ));
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);

        service.processSymbolInterval(symbol, TimeInterval.DAILY);

        verify(notificationService).sendSignalEmail(any(), any());
        verify(alertEventRepository).save(any());
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
        AlertRule rule = rule(symbol, TimeInterval.DAILY, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE, 3, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1d", scheduledFor.getEpochSecond()))
                .thenReturn(List.of(
                        candle(symbol, "1d", 2, 89, 103, 87, 101),
                        candle(symbol, "1d", 1, 100, 105, 88, 90)
                ));
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);

        service.processSymbolInterval(symbol, TimeInterval.DAILY, scheduledFor);

        verify(candleRepository).findTop100BySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1d", scheduledFor.getEpochSecond());
        verify(candleRepository, never()).findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1d");
        verify(notificationService).sendSignalEmail(any(), any());
        verify(alertEventRepository).save(any());
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
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1mo"))
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
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1wk"))
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
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1mo"))
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

    private ScheduledAlertService service(AlertRuleRepository alertRuleRepository,
                                          AlertEventRepository alertEventRepository,
                                          CandleRepository candleRepository,
                                          MarketDataService marketDataService,
                                          AlertNotificationService notificationService) {
        return new ScheduledAlertService(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
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
