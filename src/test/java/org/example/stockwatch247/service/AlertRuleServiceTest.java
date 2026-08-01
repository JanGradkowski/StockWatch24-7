package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AlertRuleServiceTest {
    private static final Instant MANUAL_CHECK_NOW =
            Instant.parse("2026-07-24T16:44:00Z");
    private static final long DAILY_COMPLETION_CUTOFF =
            Instant.parse("2026-07-24T00:00:00Z").getEpochSecond();
    private static final long WEEKLY_COMPLETION_CUTOFF =
            Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
    private static final long MONTHLY_COMPLETION_CUTOFF =
            Instant.parse("2026-07-01T00:00:00Z").getEpochSecond();
    private static final long WEEK_OF_13_JULY_2026 =
            Instant.parse("2026-07-13T00:00:00Z").getEpochSecond();

    @Test
    void checkLatestSignalDetectsCurrentMonthlyElliottBuySignal() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleService service = service(candleRepository, marketDataService);
        User user = new User();
        user.setEmail("alerts@example.com");

        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE, 76, null));
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1mo", MONTHLY_COMPLETION_CUTOFF, PageRequest.of(0, 299)))
                .thenReturn(syntheticElliottCandles(symbol).reversed());

        Map<String, Object> response = service.checkLatestSignal(
                user,
                symbol,
                TimeInterval.MONTHLY,
                TradeSignal.BUY,
                AlertPatternFamily.ELLIOTT_WAVE
        );

        assertThat(response)
                .containsEntry("symbol", symbol)
                .containsEntry("interval", "MONTHLY")
                .containsEntry("signal", "BUY")
                .containsEntry("patternFamily", "ELLIOTT_WAVE")
                .containsEntry("matched", true);
        assertThat(response).doesNotContainKeys(
                "researchHorizonLabel", "researchHorizonSummary", "researchHorizonDisclaimer");
        assertThat(response.get("matchingPatterns"))
                .asList()
                .contains("ELLIOTT_BULLISH_CORRECTION");
        assertThat(response.get("detectedSignals"))
                .asList()
                .anySatisfy(signal -> {
                    Map<?, ?> signalMap = (Map<?, ?>) signal;
                    assertThat(signalMap.get("patternFamily")).isEqualTo("ELLIOTT_WAVE");
                    assertThat(signalMap.get("signal")).isEqualTo("BUY");
                    assertThat(signalMap.get("pattern")).isEqualTo("ELLIOTT_BULLISH_CORRECTION");
                    assertThat(signalMap.get("scoreVersion")).isEqualTo("ELLIOTT_V1");
                });
    }

    @Test
    void manualCandlestickCheckRefreshesOnlyTheRequestedStock() {
        String symbol = "VST";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleService service = service(candleRepository, marketDataService);

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.TWELVE_DATA, 2, null));
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1d", DAILY_COMPLETION_CUTOFF, PageRequest.of(0, 299)))
                .thenReturn(List.of(
                        new Candle(symbol, "1d", 2 * 86_400L, 100.0, 103.0, 99.0, 102.0, 1_000L),
                        new Candle(symbol, "1d", 86_400L, 98.0, 101.0, 97.0, 100.0, 1_000L)
                ));

        Map<String, Object> response = service.checkLatestSignal(
                new User(), symbol, TimeInterval.DAILY, TradeSignal.BUY, AlertPatternFamily.CANDLESTICK);

        verify(marketDataService).syncCandles(symbol, "1d", null, true);
        verifyNoMoreInteractions(marketDataService);
        assertThat(response)
                .containsEntry("firstIncompleteTimestamp", DAILY_COMPLETION_CUTOFF)
                .containsEntry("latestCompletedTimestamp", 2 * 86_400L);
    }

    @Test
    void checkLatestSignalSupportsCurrentWeeklyEndOfWaveC() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleService service = service(candleRepository, marketDataService);

        when(marketDataService.syncCandles(symbol, "1wk", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1wk", WEEKLY_COMPLETION_CUTOFF, PageRequest.of(0, 299)))
                .thenReturn(syntheticElliottCandles(symbol, "1wk").reversed());

        Map<String, Object> response = service.checkLatestSignal(
                new User(), symbol, TimeInterval.WEEKLY, TradeSignal.BUY, AlertPatternFamily.ELLIOTT_WAVE);

        assertThat(response).containsEntry("matched", true).containsEntry("interval", "WEEKLY");
        assertThat(response.get("matchingPatterns")).asList().contains("ELLIOTT_BULLISH_CORRECTION");
    }

    @Test
    void checkLatestSignalDetectsCurrentBullishWaveVEndAsSell() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleService service = service(candleRepository, marketDataService);
        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1mo", MONTHLY_COMPLETION_CUTOFF, PageRequest.of(0, 299)))
                .thenReturn(syntheticWaveVEndCandles(symbol, "1mo").reversed());

        Map<String, Object> response = service.checkLatestSignal(
                new User(), symbol, TimeInterval.MONTHLY, TradeSignal.SELL, AlertPatternFamily.ELLIOTT_WAVE);

        assertThat(response).containsEntry("matched", true);
        assertThat(response.get("matchingPatterns")).asList().contains("ELLIOTT_BULLISH_WAVE_V_END");
    }

    @Test
    void activeCompanyViewsCollapseRulesForTheSameInstrument() {
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertRuleService service = service(alertRuleRepository, alertEventRepository);
        User user = new User();
        user.setEmail("grouped-alerts@example.com");

        StockAsset mara = stock(1L, "MARA", "MARA Holdings, Inc.");
        StockAsset apple = stock(2L, "AAPL", "Apple Inc.");
        AlertRule maraDailyBuy = rule(11L, user, mara, TimeInterval.DAILY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);
        AlertRule maraWeeklySell = rule(12L, user, mara, TimeInterval.WEEKLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL);
        AlertRule appleMonthlyBuy = rule(13L, user, apple, TimeInterval.MONTHLY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);

        when(alertRuleRepository
                .findByUserAndIsActiveTrueOrderByStockAsset_TickerSymbolAscIntervalAscPatternFamilyAscTradeSignalAsc(user))
                .thenReturn(List.of(appleMonthlyBuy, maraDailyBuy, maraWeeklySell));
        when(alertEventRepository.countByAlertRule(appleMonthlyBuy)).thenReturn(1L);
        when(alertEventRepository.countByAlertRule(maraDailyBuy)).thenReturn(2L);
        when(alertEventRepository.countByAlertRule(maraWeeklySell)).thenReturn(3L);

        List<AlertRuleService.TrackedCompanyView> companies = service.getActiveCompanyViews(user);

        assertThat(companies).extracting(AlertRuleService.TrackedCompanyView::symbol)
                .containsExactly("AAPL", "MARA");
        AlertRuleService.TrackedCompanyView maraView = companies.get(1);
        assertThat(maraView.representativeAlertId()).isEqualTo(11L);
        assertThat(maraView.ruleCount()).isEqualTo(2);
        assertThat(maraView.eventCount()).isEqualTo(5L);
        assertThat(maraView.intervalLabels()).containsExactly("1d", "1wk");
        assertThat(maraView.familyLabels()).containsExactly("Candlestick", "Elliott Wave");
        assertThat(maraView.tradeSignals()).containsExactly(TradeSignal.BUY, TradeSignal.SELL);
        assertThat(maraView.instrumentType()).isEqualTo(InstrumentType.EQUITY);
        assertThat(maraView.instrumentTypeLabel()).isEqualTo("Stock");
        assertThat(maraView.instrumentGroup()).isEqualTo("stocks");
    }

    @Test
    void activeCompanyViewsGroupIndexesAndEtfsSeparatelyFromStocks() {
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertRuleService service = service(alertRuleRepository, alertEventRepository);
        User user = new User();
        user.setEmail("instrument-groups@example.com");

        StockAsset index = stock(3L, "^GSPC", "S&P 500 Index");
        index.setInstrumentType(InstrumentType.INDEX);
        StockAsset etf = stock(4L, "SPY", "SPDR S&P 500 ETF Trust");
        etf.setInstrumentType(InstrumentType.ETF);
        AlertRule indexRule = rule(31L, user, index, TimeInterval.WEEKLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        AlertRule etfRule = rule(32L, user, etf, TimeInterval.DAILY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.SELL);

        when(alertRuleRepository
                .findByUserAndIsActiveTrueOrderByStockAsset_TickerSymbolAscIntervalAscPatternFamilyAscTradeSignalAsc(user))
                .thenReturn(List.of(indexRule, etfRule));

        List<AlertRuleService.TrackedCompanyView> companies = service.getActiveCompanyViews(user);

        assertThat(companies).extracting(AlertRuleService.TrackedCompanyView::instrumentType)
                .containsExactly(InstrumentType.INDEX, InstrumentType.ETF);
        assertThat(companies).extracting(AlertRuleService.TrackedCompanyView::instrumentTypeLabel)
                .containsExactly("Index", "ETF");
        assertThat(companies).extracting(AlertRuleService.TrackedCompanyView::instrumentGroup)
                .containsOnly("funds");
    }

    @Test
    void latestSignalViewsReturnTheNewestActiveSignalsWithDashboardMetadata() {
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertRuleService service = service(alertRuleRepository, alertEventRepository);
        User user = new User();
        user.setEmail("latest-signals@example.com");
        StockAsset mara = stock(1L, "MARA", "MARA Holdings, Inc.");
        AlertRule weeklyBuy = rule(21L, user, mara, TimeInterval.WEEKLY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);

        AlertEvent latest = new AlertEvent();
        latest.setId(302L);
        latest.setAlertRule(weeklyBuy);
        latest.setPattern(CandlePattern.BULLISH_ENGULFING);
        latest.setTradeSignal(TradeSignal.BUY);
        latest.setSignalCandleTimestamp(WEEK_OF_13_JULY_2026);
        latest.setConfidenceScore(88);
        latest.setScoreVersion(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        latest.setSentAt(LocalDateTime.of(2025, 7, 8, 8, 15));

        when(alertEventRepository
                .findByAlertRule_UserAndAlertRule_IsActiveTrueOrderBySentAtDescIdDesc(
                        user,
                        PageRequest.of(0, 8)
                ))
                .thenReturn(List.of(latest));

        List<AlertRuleService.LatestSignalView> signals = service.getLatestSignalViews(user);

        assertThat(signals).hasSize(1);
        AlertRuleService.LatestSignalView signal = signals.getFirst();
        assertThat(signal.id()).isEqualTo(302L);
        assertThat(signal.symbol()).isEqualTo("MARA");
        assertThat(signal.companyName()).isEqualTo("MARA Holdings, Inc.");
        assertThat(signal.patternLabel()).isEqualTo("Bullish Engulfing");
        assertThat(signal.familyLabel()).isEqualTo("Candlestick");
        assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(signal.intervalLabel()).isEqualTo("1wk");
        assertThat(signal.researchHorizonLabel()).isEqualTo("4, 8, and 12 weeks");
        assertThat(signal.researchHorizonSummary()).contains("not stable");
        assertThat(signal.researchHorizonDisclaimer()).contains("not a recommended holding period");
        assertThat(signal.setupScore()).isEqualTo(88);
        assertThat(signal.setupBand()).isEqualTo("high");
        assertThat(signal.scoreVersion()).isEqualTo(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        assertThat(signal.signalDate()).isNotNull();
        assertThat(signal.signalPeriodLabel()).isEqualTo("13\u201317 Jul 2026");
        assertThat(signal.sentAt()).isEqualTo(LocalDateTime.of(2025, 7, 8, 8, 15));
        verify(alertEventRepository)
                .findByAlertRule_UserAndAlertRule_IsActiveTrueOrderBySentAtDescIdDesc(
                        user,
                        PageRequest.of(0, 8)
                );
    }

    @Test
    void companySignalHistoryBuildsOneColumnPerActiveRule() {
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertRuleService service = service(alertRuleRepository, alertEventRepository);
        User user = new User();
        user.setEmail("history-board@example.com");
        StockAsset mara = stock(1L, "MARA", "MARA Holdings, Inc.");
        AlertRule weeklyCandleBuy = rule(21L, user, mara, TimeInterval.WEEKLY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);
        AlertRule monthlyElliottSell = rule(22L, user, mara, TimeInterval.MONTHLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL);
        AlertEvent event = spy(new AlertEvent());
        event.setId(301L);
        event.setAlertRule(weeklyCandleBuy);
        event.setPattern(CandlePattern.BULLISH_ENGULFING);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(WEEK_OF_13_JULY_2026);
        event.setSignalStrength(SignalStength.HIGH_CONFIDENCE);
        event.setConfidenceScore(88);
        event.setScoreVersion(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        event.setConfidenceReasons(List.of("strict bullish candle-pattern geometry"));
        event.setClosePrice(19.42);
        event.setSentAt(LocalDateTime.of(2025, 7, 8, 8, 15));
        event.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
        event.setPatternHigh(20.0);
        event.setPatternLow(18.0);
        event.setConfirmationTriggerPrice(20.0);
        event.setInvalidationPrice(18.0);
        event.setConfirmationWindowCandles(3);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-20T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(1);
        event.setResolutionClosePrice(20.75);
        event.setLifecycleUpdatedAt(LocalDateTime.of(2026, 7, 24, 22, 15));

        when(alertRuleRepository.findByIdAndUserAndIsActiveTrue(weeklyCandleBuy.getId(), user))
                .thenReturn(Optional.of(weeklyCandleBuy));
        when(alertRuleRepository.findByUserAndStockAssetAndIsActiveTrue(user, mara))
                .thenReturn(List.of(monthlyElliottSell, weeklyCandleBuy));
        when(alertEventRepository.findByAlertRuleOrderBySignalCandleTimestampDesc(weeklyCandleBuy))
                .thenReturn(List.of(event));
        when(alertEventRepository.findByAlertRuleOrderBySignalCandleTimestampDesc(monthlyElliottSell))
                .thenReturn(List.of());

        AlertRuleService.CompanySignalHistory history = service.getCompanySignalHistory(
                user, weeklyCandleBuy.getId());

        assertThat(history.symbol()).isEqualTo("MARA");
        assertThat(history.companyName()).isEqualTo("MARA Holdings, Inc.");
        assertThat(history.ruleCount()).isEqualTo(2);
        assertThat(history.eventCount()).isEqualTo(1L);
        assertThat(history.columns()).extracting(column -> column.alert().interval())
                .containsExactly(TimeInterval.WEEKLY, TimeInterval.MONTHLY);
        assertThat(history.columns().getFirst().alert().familyLabel()).isEqualTo("Candlestick");
        assertThat(history.columns().getFirst().alert().researchHorizonLabel()).isEqualTo("4, 8, and 12 weeks");
        assertThat(history.columns().getFirst().alert().tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(history.columns().getFirst().events()).hasSize(1);
        assertThat(history.columns().getFirst().events().getFirst().id()).isEqualTo(301L);
        assertThat(history.columns().getFirst().events().getFirst().patternLabel()).isEqualTo("Bullish Engulfing");
        assertThat(history.columns().getFirst().events().getFirst().scoreVersion())
                .isEqualTo(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        assertThat(history.columns().getFirst().events().getFirst().signalPeriodLabel())
                .isEqualTo("13\u201317 Jul 2026");
        assertThat(history.columns().getFirst().events().getFirst().lifecycle().label())
                .isEqualTo("Confirmed");
        assertThat(history.columns().getFirst().events().getFirst().lifecycle().resolutionPeriodLabel())
                .isEqualTo("20\u201324 Jul 2026");
        assertThat(history.columns().get(1).alert().familyLabel()).isEqualTo("Elliott Wave");
        assertThat(history.columns().get(1).alert().researchHorizonLabel()).isNull();
        assertThat(history.columns().get(1).alert().tradeSignal()).isEqualTo(TradeSignal.SELL);
        verify(event, never()).getAlertRule();
    }

    @Test
    void companySignalHistoryRejectsAnAlertOutsideTheUsersActiveRules() {
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertRuleService service = service(alertRuleRepository, mock(AlertEventRepository.class));
        User user = new User();
        user.setEmail("owner@example.com");
        when(alertRuleRepository.findByIdAndUserAndIsActiveTrue(999L, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCompanySignalHistory(user, 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Active alert rule not found.");
        verify(alertRuleRepository, never()).findByUserAndStockAssetAndIsActiveTrue(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void signalDetailExplainsThePersistedSetupEvidence() {
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertRuleService service = service(mock(AlertRuleRepository.class), alertEventRepository);
        User user = new User();
        user.setEmail("signal-owner@example.com");
        StockAsset mara = stock(1L, "MARA", "MARA Holdings, Inc.");
        AlertRule rule = rule(21L, user, mara, TimeInterval.WEEKLY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);
        AlertEvent event = new AlertEvent();
        event.setId(301L);
        event.setAlertRule(rule);
        event.setPattern(CandlePattern.BULLISH_ENGULFING);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(WEEK_OF_13_JULY_2026);
        event.setSignalStrength(SignalStength.HIGH_CONFIDENCE);
        event.setConfidenceScore(88);
        event.setScoreVersion(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        event.setConfidenceReasons(List.of(
                "strict bullish candle-pattern geometry",
                "RSI is rising versus the previous candle",
                "pattern calibration lowered confidence by 2 points based on historical precision"
        ));
        event.setClosePrice(19.42);
        event.setSentAt(LocalDateTime.of(2025, 7, 8, 8, 15));
        event.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
        event.setPatternHigh(20.0);
        event.setPatternLow(18.0);
        event.setConfirmationTriggerPrice(20.0);
        event.setInvalidationPrice(18.0);
        event.setConfirmationWindowCandles(3);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-20T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(1);
        event.setResolutionClosePrice(20.75);
        event.setLifecycleUpdatedAt(LocalDateTime.of(2026, 7, 24, 22, 15));
        when(alertEventRepository.findOwnedByIdAndUser(301L, user)).thenReturn(Optional.of(event));

        AlertRuleService.SignalDetailView detail = service.getSignalDetail(user, 301L);

        assertThat(detail.id()).isEqualTo(301L);
        assertThat(detail.alertRuleId()).isEqualTo(21L);
        assertThat(detail.symbol()).isEqualTo("MARA");
        assertThat(detail.patternLabel()).isEqualTo("Bullish Engulfing");
        assertThat(detail.setupBand()).isEqualTo("high");
        assertThat(detail.researchHorizonLabel()).isEqualTo("4, 8, and 12 weeks");
        assertThat(detail.researchHorizonSummary()).contains("not stable");
        assertThat(detail.researchHorizonDisclaimer()).contains("not a recommended holding period");
        assertThat(detail.signalPeriodLabel()).isEqualTo("13\u201317 Jul 2026");
        assertThat(detail.lifecycle().label()).isEqualTo("Confirmed");
        assertThat(detail.lifecycle().resolutionPeriodLabel()).isEqualTo("20\u201324 Jul 2026");
        assertThat(detail.lifecycle().updatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 24, 22, 15));
        assertThat(detail.setupStrengthLabel()).isEqualTo("High confluence");
        assertThat(detail.scoreVersion()).isEqualTo(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        assertThat(detail.setupExplanation()).contains("experimental", "not demonstrated stable");
        assertThat(detail.reasonsAvailable()).isTrue();
        assertThat(detail.reasons()).extracting(AlertRuleService.SignalReasonView::category)
                .containsExactly("Pattern geometry", "Momentum", "Calibration");
        assertThat(detail.reasons().getLast().caution()).isTrue();
        assertThat(detail.reasons()).allSatisfy(reason -> {
            assertThat(reason.scored()).isFalse();
            assertThat(reason.details()).hasSize(1);
        });
    }

    @Test
    void signalDetailSplitsScoredReasonIntoLabeledEvidenceRows() {
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertRuleService service = service(mock(AlertRuleRepository.class), alertEventRepository);
        User user = new User();
        user.setEmail("signal-owner@example.com");
        AlertRule rule = rule(
                22L,
                user,
                stock(12L, "MARA", "MARA Holdings, Inc."),
                TimeInterval.WEEKLY,
                AlertPatternFamily.CANDLESTICK,
                TradeSignal.SELL
        );
        AlertEvent event = new AlertEvent();
        event.setId(302L);
        event.setAlertRule(rule);
        event.setPattern(CandlePattern.BEARISH_HARAMI);
        event.setTradeSignal(TradeSignal.SELL);
        event.setSignalCandleTimestamp(WEEK_OF_13_JULY_2026);
        event.setSignalStrength(SignalStength.MEDIUM_CONFIDENCE);
        event.setConfidenceScore(54);
        event.setScoreVersion(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        event.setConfidenceReasons(List.of(
                "Trend indicators +0/20: weekly profile: EMA(8)/EMA(21) order was not aligned "
                        + "with the signal; fast EMA slope was not aligned with the signal; "
                        + "slow EMA slope was not aligned with the signal; "
                        + "MACD(8,21,5) histogram change was not aligned with the signal",
                "Momentum +15/15: weekly profile: RSI(10) was 71.3 "
                        + "(+5/5 for directional reversal location); RSI change was aligned with the signal"
        ));
        event.setClosePrice(19.42);
        when(alertEventRepository.findOwnedByIdAndUser(302L, user)).thenReturn(Optional.of(event));

        AlertRuleService.SignalDetailView detail = service.getSignalDetail(user, 302L);

        assertThat(detail.reasons()).extracting(AlertRuleService.SignalReasonView::category)
                .containsExactly("Trend indicators", "Momentum");
        assertThat(detail.reasons()).extracting(AlertRuleService.SignalReasonView::scoreLabel)
                .containsExactly("0/20", "15/15");
        assertThat(detail.reasons()).extracting(AlertRuleService.SignalReasonView::statusLabel)
                .containsExactly("No supporting points", "Full score");
        assertThat(detail.reasons().getFirst().details())
                .extracting(AlertRuleService.SignalReasonDetailView::label)
                .containsExactly(
                        "Indicator profile",
                        "EMA(8) vs EMA(21)",
                        "EMA(8) slope",
                        "EMA(21) slope",
                        "MACD(8, 21, 5) histogram"
                );
        assertThat(detail.reasons().getFirst().details().get(1).text())
                .isEqualTo("EMA(8)/EMA(21) order did not support the bearish direction.");
        assertThat(detail.reasons().get(1).details().get(1).scoreLabel()).isEqualTo("5/5");
    }

    @Test
    void signalDetailRejectsAnEventThatIsNotOwnedByTheUser() {
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertRuleService service = service(mock(AlertRuleRepository.class), alertEventRepository);
        User user = new User();
        user.setEmail("signal-owner@example.com");
        when(alertEventRepository.findOwnedByIdAndUser(999L, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSignalDetail(user, 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Signal event not found.");
    }

    private AlertRuleService service(CandleRepository candleRepository,
                                     MarketDataService marketDataService) {
        return new AlertRuleService(
                mock(AlertRuleRepository.class),
                mock(AlertEventRepository.class),
                mock(StockAssetRepository.class),
                candleRepository,
                mock(TwelveDataService.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                marketDataService,
                completionService(),
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                new ElliottWaveDetectionService(),
                true,
                true,
                50,
                500
        );
    }

    private AlertRuleService service(AlertRuleRepository alertRuleRepository,
                                     AlertEventRepository alertEventRepository) {
        return new AlertRuleService(
                alertRuleRepository,
                alertEventRepository,
                mock(StockAssetRepository.class),
                mock(CandleRepository.class),
                mock(TwelveDataService.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(MarketDataService.class),
                completionService(),
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                new ElliottWaveDetectionService(),
                true,
                true,
                50,
                500
        );
    }

    private CandleCompletionService completionService() {
        return new CandleCompletionService(
                "Europe/Brussels",
                Clock.fixed(MANUAL_CHECK_NOW, ZoneOffset.UTC)
        );
    }

    private StockAsset stock(Long id, String symbol, String companyName) {
        StockAsset stockAsset = new StockAsset();
        stockAsset.setId(id);
        stockAsset.setTickerSymbol(symbol);
        stockAsset.setCompanyName(companyName);
        stockAsset.setExchange("NASDAQ");
        return stockAsset;
    }

    private AlertRule rule(Long id,
                           User user,
                           StockAsset stockAsset,
                           TimeInterval interval,
                           AlertPatternFamily family,
                           TradeSignal signal) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setUser(user);
        rule.setStockAsset(stockAsset);
        rule.setInterval(interval);
        rule.setPatternFamily(family);
        rule.setTradeSignal(signal);
        rule.setActive(true);
        return rule;
    }

    private List<Candle> syntheticElliottCandles(String symbol) {
        return syntheticElliottCandles(symbol, "1mo");
    }

    private List<Candle> syntheticElliottCandles(String symbol, String interval) {
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
                        symbol, interval, index * 86_400L, close - 0.4, close + 0.6, close - 0.6, close, 1_500L));
            }
        }
        return candles;
    }

    private List<Candle> syntheticWaveVEndCandles(String symbol, String interval) {
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
                candles.add(new Candle(symbol, interval, index * 86_400L,
                        close - 0.4, close + 0.6, close - 0.6, close, 1_500L));
            }
        }
        return candles;
    }

    private record Anchor(int index, double price) {
    }
}
