package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ScheduledAlertServiceTest {

    @Test
    void dailyElliottLoadsPersistedHourlyChildrenThroughTheAnalysisCache() {
        String symbol = "AAPL";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(
                symbol, TimeInterval.DAILY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        List<Candle> parent = syntheticElliottCandles(symbol, "1d");
        List<Candle> hourly = hourlyCandles(symbol, parent.getFirst().getTimestamp(), 600);

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(marketDataService.syncCandlesForAnalysis(symbol, "60min", 1_000))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 299))).thenReturn(parent.reversed());
        when(candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol, "60min", parent.getFirst().getTimestamp()))
                .thenReturn(hourly);
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));
        when(detector.findDevelopingImpulses(any(), any())).thenReturn(List.of());
        when(detector.detectAlertSignals(any())).thenReturn(List.of());
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.DAILY))
                .thenReturn(List.of());

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);

        service.processSymbolInterval(symbol, TimeInterval.DAILY);

        verify(marketDataService).syncCandlesForAnalysis(symbol, "60min", 1_000);
        verify(marketDataService, never()).syncCandles(symbol, "60min", null, true);
        verify(detector).findDevelopingImpulses(any(), any());
    }

    @Test
    void developingElliottUsesSuppliedLowerDegreeCandles() {
        String symbol = "SAP.DE";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.WEEKLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        List<EnrichedCandle> parent = List.of(enriched(1, 100.0));
        List<EnrichedCandle> child = List.of(enriched(1, 99.0), enriched(2, 101.0));
        Candle latest = candle(symbol, "1wk", 3, 105, 107, 103, 106);
        ElliottWaveDetectionService.DevelopingImpulse waveTwo = developingCandidate(
                ElliottSignalStage.WAVE_II_END,
                CandlePattern.ELLIOTT_BULLISH_WAVE_II_END,
                TradeSignal.BUY, latest.getTimestamp(), 106.0,
                List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                        point("II", 3, 106, "LOW")));
        when(detector.findDevelopingImpulses(parent, child)).thenReturn(List.of(waveTwo));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                rule, waveTwo.developmentKey())).thenReturn(Optional.empty());
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.WEEKLY))
                .thenReturn(List.of());
        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(latest), parent, child, latest.getTimestamp());

        verify(detector).findDevelopingImpulses(parent, child);
        verify(alertEventRepository).save(any(AlertEvent.class));
    }

    @Test
    void developingElliottBelowTheConfiguredConfidenceDoesNotCreateOrEmailAnAlert() {
        String symbol = "SAP.DE";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        Candle latest = candle(symbol, "1d", 4, 108, 110, 106, 109);
        ElliottWaveDetectionService.DevelopingImpulse candidate =
                new ElliottWaveDetectionService.DevelopingImpulse(
                        "BULLISH:86400:172800", "BULLISH", ElliottSignalStage.WAVE_II_END,
                        CandlePattern.ELLIOTT_BULLISH_WAVE_II_END, TradeSignal.BUY,
                        latest.getTimestamp(), latest.getClosePrice(), 107.0, 98.0, 138.0,
                        "Projected Wave III", "Corrective A-B-C", 82,
                        List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                                point("II", 3, 107, "LOW")),
                        List.of("Validated nested structure"), null);

        ElliottWavePreferencesService preferences = mock(ElliottWavePreferencesService.class);
        ElliottWavePreferencesService.PreferencesView preferencesView =
                mock(ElliottWavePreferencesService.PreferencesView.class);
        ElliottWavePreferencesService.IntervalProfile intervalProfile =
                mock(ElliottWavePreferencesService.IntervalProfile.class);
        ElliottWaveDetectionService.DetectionRules rules =
                mock(ElliottWaveDetectionService.DetectionRules.class);
        when(preferences.get(rule.getUser())).thenReturn(preferencesView);
        when(preferencesView.profile(TimeInterval.DAILY)).thenReturn(intervalProfile);
        when(intervalProfile.rules()).thenReturn(rules);
        when(rules.minimumSignalConfidence()).thenReturn(85);
        when(detector.configured(rules)).thenReturn(detector);
        when(detector.minimumSignalConfidence()).thenReturn(85);
        when(detector.findDevelopingImpulses(any())).thenReturn(List.of(candidate));
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.DAILY))
                .thenReturn(List.of());

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);
        service.configureElliottWavePreferences(preferences);

        service.processDevelopingElliott(symbol, TimeInterval.DAILY, List.of(rule),
                List.of(latest), List.of(enriched(1, 100.0)), latest.getTimestamp());

        verify(alertEventRepository, never()).save(any(AlertEvent.class));
        verify(alertEventRepository, never()).saveAndFlush(any(AlertEvent.class));
        verify(notificationService, never()).sendDevelopingElliottEmail(
                any(), any(), any(), eq(false), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void developingElliottNeverPersistsANonpositiveRawTargetWhenNoQualifiedPlanExists() {
        String symbol = "MARA";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL);
        Candle latest = candle(symbol, "1d", 4, 10, 11, 9, 10.6635);
        ElliottWaveDetectionService.DevelopingImpulse candidate =
                new ElliottWaveDetectionService.DevelopingImpulse(
                        "BEARISH:86400:172800", "BEARISH", ElliottSignalStage.WAVE_II_END,
                        CandlePattern.ELLIOTT_BEARISH_WAVE_II_END, TradeSignal.SELL,
                        latest.getTimestamp(), latest.getClosePrice(), 12.32, 16.54, -0.22,
                        "Projected Wave III", "Corrective A-B-C", 82,
                        List.of(point("0", 1, 16.43, "HIGH"), point("I", 2, 8.68, "LOW"),
                                point("II", 3, 12.32, "HIGH")),
                        List.of("Validated nested structure"), null);
        when(detector.minimumSignalConfidence()).thenReturn(75);
        when(detector.findDevelopingImpulses(any())).thenReturn(List.of(candidate));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                rule, candidate.developmentKey())).thenReturn(Optional.empty());
        when(alertEventRepository
                .findFirstByAlertRuleAndPatternAndSignalCandleTimestampOrderByIdAsc(
                        rule, candidate.pattern(), candidate.confirmationTimestamp()))
                .thenReturn(Optional.empty());
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.DAILY))
                .thenReturn(List.of());
        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);

        service.processDevelopingElliott(symbol, TimeInterval.DAILY, List.of(rule),
                List.of(latest), List.of(enriched(1, 16.43)), latest.getTimestamp());

        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(saved.capture());
        assertThat(saved.getValue().getProfitTargetPrice()).isNull();
        assertThat(saved.getValue().getConfirmationTriggerPrice()).isNull();
        assertThat(saved.getValue().getRewardRiskRatio()).isNull();
        assertThat(saved.getValue().getTradePlanVersion()).isNull();
    }

    @Test
    void competingWaveTwoHypothesesReuseTheExactSignalEvent() {
        String symbol = "SAP.DE";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.WEEKLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        Candle latest = candle(symbol, "1wk", 3, 105, 107, 103, 106);
        ElliottWaveDetectionService.DevelopingImpulse primary = developingCandidate(
                ElliottSignalStage.WAVE_II_END,
                CandlePattern.ELLIOTT_BULLISH_WAVE_II_END,
                TradeSignal.BUY, latest.getTimestamp(), 106.0,
                List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                        point("II", 3, 106, "LOW")));
        ElliottWaveDetectionService.DevelopingImpulse alternate =
                new ElliottWaveDetectionService.DevelopingImpulse(
                        "BULLISH:ALTERNATE", primary.direction(), primary.stage(), primary.pattern(),
                        primary.expectedMove(), primary.confirmationTimestamp(), primary.confirmationClose(),
                        108.0, primary.stopLossPrice(), primary.targetPrice(), primary.forecastLabel(),
                        primary.correctionType(), primary.confidenceScore(),
                        List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                                point("II", 3, 108, "LOW")),
                        primary.evidence(), primary.completedStructure());
        when(detector.findDevelopingImpulses(any())).thenReturn(List.of(primary, alternate));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                eq(rule), anyString())).thenReturn(Optional.empty());
        AtomicReference<AlertEvent> persisted = new AtomicReference<>();
        when(alertEventRepository
                .findFirstByAlertRuleAndPatternAndSignalCandleTimestampOrderByIdAsc(
                        rule, primary.pattern(), primary.confirmationTimestamp()))
                .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
        when(alertEventRepository.save(any(AlertEvent.class))).thenAnswer(invocation -> {
            AlertEvent event = invocation.getArgument(0);
            persisted.set(event);
            return event;
        });
        when(alertEventRepository.saveAndFlush(any(AlertEvent.class))).thenAnswer(invocation -> {
            AlertEvent event = invocation.getArgument(0);
            persisted.set(event);
            return event;
        });
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.WEEKLY))
                .thenReturn(List.of());
        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(latest), List.of(enriched(1, 100.0)), latest.getTimestamp());

        verify(alertEventRepository, org.mockito.Mockito.times(2)).save(persisted.get());
        verify(notificationService).sendDevelopingElliottEmail(
                eq(rule), any(DetectedSignal.class), eq(persisted.get()), eq(false), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(persisted.get().getElliottStructureSnapshot()).contains("II|259200|108.0000000000|LOW");
    }

    @Test
    void developingElliottCycleCreatesOneWaveTwoSignalThenUpdatesItAtWaveThree() {
        String symbol = "SAP.DE";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.WEEKLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        List<EnrichedCandle> enriched = List.of(enriched(1, 100.0));
        Candle latestWaveTwo = candle(symbol, "1wk", 3, 105, 107, 103, 106);
        ElliottWaveDetectionService.DevelopingImpulse waveTwo = developingCandidate(
                ElliottSignalStage.WAVE_II_END,
                CandlePattern.ELLIOTT_BULLISH_WAVE_II_END,
                TradeSignal.BUY, 3 * 86_400L, 106.0,
                List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"), point("II", 3, 106, "LOW")));
        when(detector.findDevelopingImpulses(enriched)).thenReturn(List.of(waveTwo));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                rule, "BULLISH:86400:172800")).thenReturn(Optional.empty());
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.WEEKLY))
                .thenReturn(List.of());
        when(notificationService.sendDevelopingElliottEmail(
                eq(rule), any(DetectedSignal.class), any(AlertEvent.class), eq(false), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(latestWaveTwo), enriched, latestWaveTwo.getTimestamp());

        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(saved.capture());
        AlertEvent cycle = saved.getValue();
        assertThat(cycle.getElliottSignalStage()).isEqualTo(ElliottSignalStage.WAVE_II_END);
        assertThat(cycle.getPattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_WAVE_II_END);
        assertThat(cycle.getStopLossPrice()).isEqualTo(98.0);
        assertThat(cycle.getProfitTargetPrice()).isEqualTo(138.0);
        assertThat(cycle.getElliottTransitionHistory()).contains("WAVE_II_END");

        ElliottWaveDetectionService.DevelopingImpulse waveThree = developingCandidate(
                ElliottSignalStage.WAVE_III_END,
                CandlePattern.ELLIOTT_BULLISH_WAVE_III_END,
                TradeSignal.SELL, 4 * 86_400L, 139.0,
                List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                        point("II", 3, 106, "LOW"), point("III", 4, 140, "HIGH")));
        Candle latestWaveThree = candle(symbol, "1wk", 4, 141, 142, 138, 139);
        when(detector.findDevelopingImpulses(enriched)).thenReturn(List.of(waveThree));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                rule, "BULLISH:86400:172800")).thenReturn(Optional.of(cycle));

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(latestWaveThree), enriched, latestWaveThree.getTimestamp());

        assertThat(cycle.getElliottSignalStage()).isEqualTo(ElliottSignalStage.WAVE_III_END);
        assertThat(cycle.getPattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_WAVE_III_END);
        assertThat(cycle.getTradeSignal()).isEqualTo(TradeSignal.SELL);
        assertThat(cycle.getElliottTransitionHistory().lines()).hasSize(2);
        verify(notificationService, org.mockito.Mockito.times(2)).sendDevelopingElliottEmail(
                eq(rule), any(DetectedSignal.class), eq(cycle), eq(false), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void waveVRemainsOnTheSameDevelopingCardUntilValidatedAbcCompletes() {
        String symbol = "SAP.DE";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.WEEKLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        List<EnrichedCandle> enriched = List.of(enriched(1, 100.0));
        List<ElliottWaveDetectionService.ElliottWavePoint> impulsePoints = List.of(
                point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                point("II", 3, 110, "LOW"), point("III", 4, 145, "HIGH"),
                point("IV", 5, 130, "LOW"), point("V", 6, 155, "HIGH"));
        ElliottWaveDetectionService.ElliottWaveStructure waveVStructure =
                elliottStructure(false, impulsePoints, 7 * 86_400L);
        ElliottWaveDetectionService.DevelopingImpulse waveV = developingCandidate(
                ElliottSignalStage.WAVE_V_END,
                CandlePattern.ELLIOTT_BULLISH_WAVE_V_END,
                TradeSignal.SELL, 7 * 86_400L, 152.0, impulsePoints, waveVStructure,
                "Projected ABC correction", "Wave II and IV corrective structures");
        AlertEvent cycle = new AlertEvent();
        cycle.setAlertRule(rule);
        cycle.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
        cycle.setElliottSignalStage(ElliottSignalStage.WAVE_IV_END);
        cycle.setElliottDeveloping(true);
        cycle.setElliottDevelopmentKey(waveV.developmentKey());
        when(detector.findDevelopingImpulses(enriched)).thenReturn(List.of(waveV));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                rule, waveV.developmentKey())).thenReturn(Optional.of(cycle));
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.WEEKLY))
                .thenReturn(List.of());
        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(candle(symbol, "1wk", 7, 153, 154, 151, 152)),
                enriched, 7 * 86_400L);

        String cycleKey = ElliottWaveSignalLifecyclePolicy.cycleKey(waveVStructure).orElseThrow();
        assertThat(cycle.getElliottSignalStage()).isEqualTo(ElliottSignalStage.WAVE_V_END);
        assertThat(cycle.getElliottCycleKey()).isEqualTo(cycleKey);
        assertThat(cycle.isElliottDeveloping()).isTrue();
        assertThat(cycle.getConfirmationWindowCandles()).isNull();

        List<ElliottWaveDetectionService.ElliottWavePoint> correctionPoints = new ArrayList<>(impulsePoints);
        correctionPoints.add(point("A", 8, 137, "LOW"));
        correctionPoints.add(point("B", 9, 149, "HIGH"));
        correctionPoints.add(point("C", 10, 128, "LOW"));
        ElliottWaveDetectionService.ElliottWaveStructure correctionStructure =
                elliottStructure(true, correctionPoints, 11 * 86_400L);
        ElliottWaveDetectionService.DevelopingImpulse correction = developingCandidate(
                ElliottSignalStage.CORRECTION_END,
                CandlePattern.ELLIOTT_BULLISH_CORRECTION,
                TradeSignal.BUY, 11 * 86_400L, 132.0, correctionPoints,
                correctionStructure, "Projected primary-trend resumption toward Wave V",
                "Zigzag 5-3-5 with triangular Wave B (3-3-3-3-3)");
        when(detector.findDevelopingImpulses(enriched)).thenReturn(List.of(correction));

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(candle(symbol, "1wk", 11, 130, 133, 129, 132)),
                enriched, 11 * 86_400L);

        assertThat(cycle.getElliottSignalStage()).isEqualTo(ElliottSignalStage.CORRECTION_END);
        assertThat(cycle.getPattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
        assertThat(cycle.getTradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(cycle.isElliottDeveloping()).isFalse();
        assertThat(cycle.getElliottCycleKey()).isEqualTo(cycleKey);
        assertThat(cycle.getConfirmationWindowCandles()).isPositive();
        assertThat(cycle.getElliottCorrectionType()).contains("Zigzag 5-3-5", "triangular Wave B");
        verify(alertEventRepository, org.mockito.Mockito.times(2)).save(cycle);
        verify(notificationService, org.mockito.Mockito.times(2)).sendDevelopingElliottEmail(
                eq(rule), any(DetectedSignal.class), eq(cycle), eq(false), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void revisedPrimaryCountUpdatesTheSameSignalWithoutAnotherEmail() {
        String symbol = "SAP.DE";
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.WEEKLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);
        List<EnrichedCandle> enriched = List.of(enriched(1, 100.0));
        Candle latest = candle(symbol, "1wk", 5, 113, 115, 112, 114);
        ElliottWaveDetectionService.DevelopingImpulse firstCount = developingCandidate(
                ElliottSignalStage.WAVE_II_END,
                CandlePattern.ELLIOTT_BULLISH_WAVE_II_END,
                TradeSignal.BUY, latest.getTimestamp(), 114.0,
                List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                        point("II", 3, 106, "LOW")));
        when(detector.findDevelopingImpulses(enriched)).thenReturn(List.of(firstCount));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                rule, firstCount.developmentKey())).thenReturn(Optional.empty());
        when(alertEventRepository.findDevelopingElliottEvents(symbol, TimeInterval.WEEKLY))
                .thenReturn(List.of());
        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detector);

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(latest), enriched, latest.getTimestamp());

        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(saved.capture());
        AlertEvent event = saved.getValue();
        String originalSnapshot = event.getElliottStructureSnapshot();

        ElliottWaveDetectionService.DevelopingImpulse revisedCount = developingCandidate(
                ElliottSignalStage.WAVE_II_END,
                CandlePattern.ELLIOTT_BULLISH_WAVE_II_END,
                TradeSignal.BUY, latest.getTimestamp(), 114.0,
                List.of(point("0", 1, 100, "LOW"), point("I", 2, 120, "HIGH"),
                        point("II", 4, 109, "LOW")));
        when(detector.findDevelopingImpulses(enriched)).thenReturn(List.of(revisedCount));
        when(alertEventRepository.findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                rule, revisedCount.developmentKey())).thenReturn(Optional.of(event));

        service.processDevelopingElliott(symbol, TimeInterval.WEEKLY, List.of(rule),
                List.of(latest), enriched, latest.getTimestamp());

        assertThat(event.getElliottStructureSnapshot()).isNotEqualTo(originalSnapshot);
        assertThat(event.getElliottStructureSnapshot()).contains("II|345600|109.0000000000|LOW");
        verify(alertEventRepository, org.mockito.Mockito.times(2)).save(event);
        verify(notificationService, org.mockito.Mockito.times(1)).sendDevelopingElliottEmail(
                eq(rule), any(DetectedSignal.class), eq(event), eq(false), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void confirmedHarmonicCreatesImmutableSignalSnapshotAndSendsEmail() {
        String symbol = "MSFT";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        CandlePatternDetectionService detectionService = mock(CandlePatternDetectionService.class);
        AlertRule rule = rule(
                symbol, TimeInterval.DAILY, AlertPatternFamily.HARMONIC_FORMATION, TradeSignal.BUY);
        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 299)))
                .thenReturn(List.of(
                        candle(symbol, "1d", 7, 130, 131, 129, 130),
                        candle(symbol, "1d", 6, 122, 123, 121.4, 122),
                        candle(symbol, "1d", 5, 182.7, 183.2, 182, 182.7),
                        candle(symbol, "1d", 4, 138.7, 139, 138.2, 138.7),
                        candle(symbol, "1d", 3, 199.5, 200, 199, 199.5),
                        candle(symbol, "1d", 2, 100.5, 101, 100, 100.5),
                        candle(symbol, "1d", 1, 110, 111, 109, 110)));
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(
                rule, CandlePattern.HARMONIC_GARTLEY, 7 * 86_400L)).thenReturn(false);
        when(notificationService.sendSignalEmail(eq(rule), any(DetectedSignal.class), any(AlertEvent.class)))
                .thenReturn(true);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detectionService, new ElliottWaveDetectionService());
        service.configureHarmonicPatterns(new HarmonicPatternDetectionService(
                new HarmonicPatternDetectionService.Rules(.04, .08, .10, 0.0, 1, 40)));
        service.configureHarmonicStopPlans(
                new HarmonicStopPlanService(alertEventRepository, notificationService));

        service.processSymbolInterval(symbol, TimeInterval.DAILY);

        verify(marketDataService).syncCandlesForAnalysis(eq(symbol), eq("1d"), anyInt());
        verify(detectionService, never()).detectAlertSignalsFactory(any(), any());
        verify(notificationService).sendSignalEmail(eq(rule), any(DetectedSignal.class), any(AlertEvent.class));
        ArgumentCaptor<AlertEvent> event = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(event.capture());
        assertThat(event.getValue().getPattern()).isEqualTo(CandlePattern.HARMONIC_GARTLEY);
        assertThat(event.getValue().getSignalCandleTimestamp()).isEqualTo(7 * 86_400L);
        assertThat(event.getValue().getClosePrice()).isEqualTo(130.0);
        assertThat(event.getValue().getHarmonicEndpointTimestamp()).isEqualTo(6 * 86_400L);
        assertThat(event.getValue().getHarmonicEndpointPrice()).isEqualTo(121.4);
        assertThat(event.getValue().getHarmonicPointsSnapshot()).contains("X|", "D|");
        assertThat(event.getValue().getHarmonicMeasurementsSnapshot()).contains("B_XA|");
        assertThat(event.getValue().getTradePlanVersion()).isEqualTo(HarmonicStopPlanPolicy.VERSION);
        assertThat(event.getValue().getTradeEntryPrice()).isEqualTo(130.0);
        assertThat(event.getValue().getStructuralStopPrice()).isEqualTo(100.0);
        assertThat(event.getValue().getStopLossPrice()).isEqualTo(99.5);
        assertThat(event.getValue().getHarmonicStopBasis()).isEqualTo("Point X / 1.0 XA");
        assertThat(event.getValue().getHarmonicStopBufferPercent()).isEqualTo(.5);
        assertThat(event.getValue().getProfitTargetPrice()).isNull();
        assertThat(event.getValue().getRewardRiskRatio()).isNull();
        assertThat(event.getValue().getScoreVersion())
                .isEqualTo(HarmonicPatternDetectionService.RULE_VERSION);
        assertThat(event.getValue().getInitialEmailSentAt()).isNotNull();
    }

    @Test
    void dailyScheduleRunsTuesdayThroughSaturdayForMondayThroughFridayCandles() throws Exception {
        Scheduled scheduled = ScheduledAlertService.class.getDeclaredMethod("enqueueDailyChecks")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${alerts.schedule.daily-cron:0 0 0 * * TUE-SAT}");
        assertThat(scheduled.zone()).isEqualTo("${alerts.schedule.zone:Europe/Brussels}");
    }

    @Test
    void queuedAlertDispatcherUsesTheConfiguredPollingDelay() throws Exception {
        Scheduled scheduled = ScheduledAlertService.class.getDeclaredMethod("dispatchPending")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${alerts.schedule.worker-delay-ms:15000}");
    }

    @Test
    void setupScoreDoesNotSuppressValidatedYahooCandlestick() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        CandlePatternDetectionService detectionService = mock(CandlePatternDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY);
        DetectedSignal lowScoreSignal = new DetectedSignal(
                CandlePattern.BULLISH_ENGULFING,
                TradeSignal.BUY,
                SignalStength.LOW_CONFIDENCE,
                70,
                List.of("Pattern quality +20: validated low-score fixture"),
                5 * 86_400L,
                104.0);

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
        when(detectionService.detectAlertSignalsFactory(any(), eq(TimeInterval.DAILY)))
                .thenReturn(List.of(lowScoreSignal));

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detectionService, new ElliottWaveDetectionService());

        service.processSymbolInterval(symbol, TimeInterval.DAILY);

        verify(notificationService).sendSignalEmail(any(), any(), any());
        ArgumentCaptor<AlertEvent> savedEvent = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getConfidenceScore()).isLessThan(75);
        assertThat(savedEvent.getValue().getScoreVersion())
                .isEqualTo(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        assertThat(savedEvent.getValue().isLifecycleTracked()).isTrue();
        assertThat(savedEvent.getValue().getConfirmationWindowCandles()).isEqualTo(8);
        assertThat(savedEvent.getValue().hasCandlestickRiskRewardPlan()).isTrue();
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
        when(detectionService.detectAlertSignalsFactory(any(), eq(TimeInterval.DAILY)))
                .thenReturn(List.of(qualifiedSignal));

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, detectionService, new ElliottWaveDetectionService());

        service.processSymbolInterval(symbol, TimeInterval.DAILY, scheduledFor);

        verify(candleRepository).findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, "1d", scheduledFor.getEpochSecond(), PageRequest.of(0, 299));
        verify(candleRepository, never()).findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 299));
        verify(detectionService).detectAlertSignalsFactory(any(), eq(TimeInterval.DAILY));
        verify(notificationService).sendSignalEmail(eq(rule), eq(qualifiedSignal), any(AlertEvent.class));
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

        when(marketDataService.syncCandlesForAnalysis(eq(symbol), eq("1d"), anyInt()))
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
        when(detectionService.detectAlertSignalsFactory(any(), eq(TimeInterval.DAILY)))
                .thenReturn(List.of());

        ScheduledAlertService service = new ScheduledAlertService(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                detectionService,
                new ElliottWaveDetectionService(),
                notificationService,
                new CandlestickSignalLifecycleService(alertEventRepository, notificationService, 3),
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
        verify(detectionService).detectAlertSignalsFactory(
                assetCandles.capture(), eq(TimeInterval.DAILY));
        assertThat(assetCandles.getValue())
                .extracting(EnrichedCandle::close)
                .containsExactly(100.0, 102.0);
        verify(marketDataService).syncCandlesForAnalysis(eq(symbol), eq("1d"), anyInt());
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

        verify(notificationService).sendSignalEmail(any(), any(), any());
        ArgumentCaptor<AlertEvent> eventCaptor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().isLifecycleTracked()).isTrue();
        assertThat(eventCaptor.getValue().getConfirmationWindowCandles()).isEqualTo(10);
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
                .thenReturn(syntheticElliottCandles(symbol, "1wk").reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.WEEKLY)).thenReturn(List.of(rule));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(any(), any(), any()))
                .thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);
        service.processSymbolInterval(symbol, TimeInterval.WEEKLY);

        verify(notificationService).sendSignalEmail(any(), any(), any());
        ArgumentCaptor<AlertEvent> eventCaptor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().isLifecycleTracked()).isTrue();
        assertThat(eventCaptor.getValue().getConfirmationTriggerPrice())
                .isGreaterThan(eventCaptor.getValue().getInvalidationPrice());
        assertThat(eventCaptor.getValue().getElliottSignalStage())
                .isEqualTo(ElliottSignalStage.CORRECTION_END);
    }

    @Test
    void dailyEndOfWaveCTriggersAutomaticElliottEmailAndEventRecording() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        AlertRule rule = rule(symbol, TimeInterval.DAILY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY);

        when(marketDataService.syncCandles(symbol, "1d", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 299)))
                .thenReturn(syntheticElliottCandles(symbol, "1d").reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.DAILY)).thenReturn(List.of(rule));

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService, notificationService);
        service.processSymbolInterval(symbol, TimeInterval.DAILY);

        verify(notificationService).sendSignalEmail(any(), any(), any());
        ArgumentCaptor<AlertEvent> eventCaptor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getAlertRule().getInterval()).isEqualTo(TimeInterval.DAILY);
        assertThat(eventCaptor.getValue().getElliottSignalStage())
                .isEqualTo(ElliottSignalStage.CORRECTION_END);
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

        verify(notificationService).sendSignalEmail(any(), any(), any());
        ArgumentCaptor<AlertEvent> eventCaptor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().isLifecycleTracked()).isTrue();
        assertThat(eventCaptor.getValue().getConfirmationTriggerPrice())
                .isLessThan(eventCaptor.getValue().getElliottEndpointPrice());
        assertThat(eventCaptor.getValue().getInvalidationPrice()).isNull();
        assertThat(eventCaptor.getValue().getElliottSignalStage())
                .isEqualTo(ElliottSignalStage.WAVE_V_END);
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
                .thenReturn(syntheticElliottCandles(symbol, "1wk").reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.WEEKLY)).thenReturn(List.of(rule));
        when(elliottWaveDetectionService.detectAlertSignals(any())).thenReturn(List.of());

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, elliottWaveDetectionService);

        service.processSymbolInterval(symbol, TimeInterval.WEEKLY);

        verify(elliottWaveDetectionService).detectAlertSignals(any());
        verify(elliottWaveDetectionService, never()).detect(any());
        verify(notificationService, never()).sendSignalEmail(any(), any(), any());
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
                149.0,
                93);
        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1mo", PageRequest.of(0, 299)))
                .thenReturn(syntheticWaveVEndCandles(symbol).reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.MONTHLY)).thenReturn(List.of(rule));
        when(elliottWaveDetectionService.detectAlertSignals(any())).thenReturn(List.of(truncatedFifth));
        when(elliottWaveDetectionService.findStructureForSignal(
                any(), eq(truncatedFifth.pattern()), eq(signalTimestamp)))
                .thenReturn(Optional.of(truncatedWaveVStructure(signalTimestamp)));
        when(alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(
                rule, truncatedFifth.pattern(), signalTimestamp)).thenReturn(false);

        ScheduledAlertService service = service(
                alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                notificationService, elliottWaveDetectionService);

        service.processSymbolInterval(symbol, TimeInterval.MONTHLY);

        verify(notificationService).sendSignalEmail(eq(rule), eq(truncatedFifth), any(AlertEvent.class));
        ArgumentCaptor<AlertEvent> savedEvent = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getPattern())
                .isEqualTo(CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END);
        assertThat(savedEvent.getValue().getConfidenceScore()).isEqualTo(81);
        assertThat(savedEvent.getValue().getElliottV1EligibilityScore()).isEqualTo(93);
        assertThat(savedEvent.getValue().getScoreVersion())
                .isEqualTo(ElliottWaveDetectionService.SETUP_SCORE_VERSION);
    }

    @Test
    void extendedWaveVInSameCycleDoesNotSendAnotherDetectionEmail() {
        String symbol = "MSFT";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleRepository alertRuleRepository = mock(AlertRuleRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AlertNotificationService notificationService = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        AlertRule rule = rule(symbol, TimeInterval.MONTHLY, AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL);
        long signalTimestamp = 68L * 86_400L;
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END,
                TradeSignal.SELL,
                SignalStength.HIGH_CONFIDENCE,
                81,
                List.of("revised endpoint"),
                signalTimestamp,
                149.0);
        ElliottWaveDetectionService.ElliottWaveStructure structure =
                truncatedWaveVStructure(signalTimestamp);
        String cycleKey = ElliottWaveSignalLifecyclePolicy.cycleKey(structure).orElseThrow();
        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1mo", PageRequest.of(0, 299)))
                .thenReturn(syntheticWaveVEndCandles(symbol).reversed());
        when(alertRuleRepository.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(
                symbol, TimeInterval.MONTHLY)).thenReturn(List.of(rule));
        when(detector.detectAlertSignals(any())).thenReturn(List.of(signal));
        when(detector.findStructureForSignal(any(), eq(signal.pattern()), eq(signalTimestamp)))
                .thenReturn(Optional.of(structure));
        when(alertEventRepository.findElliottCycleStageForUser(
                rule.getUser(), symbol, TimeInterval.MONTHLY, cycleKey,
                ElliottSignalStage.WAVE_V_END, PageRequest.of(0, 1)))
                .thenReturn(List.of(new AlertEvent()));

        ScheduledAlertService service = service(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                notificationService,
                detector);

        service.processSymbolInterval(symbol, TimeInterval.MONTHLY);

        verify(notificationService, never()).sendSignalEmail(any(), any(), any());
        verify(alertEventRepository, never()).save(any());
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
        when(marketDataService.syncCandlesForAnalysis(anyString(), anyString(), anyInt()))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        return new ScheduledAlertService(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                elliottWaveDetectionService,
                notificationService,
                new CandlestickSignalLifecycleService(alertEventRepository, notificationService, 3),
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
        when(marketDataService.syncCandlesForAnalysis(anyString(), anyString(), anyInt()))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        return new ScheduledAlertService(
                alertRuleRepository,
                alertEventRepository,
                candleRepository,
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                detectionService,
                elliottWaveDetectionService,
                notificationService,
                new CandlestickSignalLifecycleService(alertEventRepository, notificationService, 3),
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

    private EnrichedCandle enriched(long timestamp, double close) {
        return new EnrichedCandle(timestamp, close - .4, close + .6, close - .6, close,
                1_500, 1_000, 55, close - 2, Double.NaN, Double.NaN, Double.NaN, 4);
    }

    private ElliottWaveDetectionService.ElliottWavePoint point(
            String label, long day, double price, String type) {
        return new ElliottWaveDetectionService.ElliottWavePoint(label, day * 86_400L, price, type);
    }

    private ElliottWaveDetectionService.DevelopingImpulse developingCandidate(
            ElliottSignalStage stage,
            CandlePattern pattern,
            TradeSignal expectedMove,
            long confirmationTimestamp,
            double close,
            List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        return new ElliottWaveDetectionService.DevelopingImpulse(
                "BULLISH:86400:172800", "BULLISH", stage, pattern, expectedMove,
                confirmationTimestamp, close, points.getLast().price(), 98.0, 138.0,
                "Projected next wave", "Corrective A-B-C", 82, points,
                List.of("Validated nested structure"), null);
    }

    private ElliottWaveDetectionService.DevelopingImpulse developingCandidate(
            ElliottSignalStage stage,
            CandlePattern pattern,
            TradeSignal expectedMove,
            long confirmationTimestamp,
            double close,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            ElliottWaveDetectionService.ElliottWaveStructure completedStructure,
            String forecast,
            String correctionType) {
        return new ElliottWaveDetectionService.DevelopingImpulse(
                "BULLISH:86400:172800", "BULLISH", stage, pattern, expectedMove,
                confirmationTimestamp, close, points.getLast().price(), 98.0, 155.0,
                forecast, correctionType, 86, points,
                List.of("Validated nested structure"), completedStructure);
    }

    private ElliottWaveDetectionService.ElliottWaveStructure elliottStructure(
            boolean correctionComplete,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            long confirmationTimestamp) {
        return new ElliottWaveDetectionService.ElliottWaveStructure(
                "BULLISH", correctionComplete, List.copyOf(points), confirmationTimestamp, 86,
                .5, false, 1.75, .4,
                ElliottWaveDetectionService.ImpulseVariant.STANDARD,
                correctionComplete ? ElliottWaveDetectionService.CorrectionVariant.STANDARD
                        : ElliottWaveDetectionService.CorrectionVariant.NONE,
                correctionComplete ? .48 : Double.NaN,
                correctionComplete ? 1.1 : Double.NaN,
                List.of());
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
                        symbol, interval, index * 86_400L,
                        close - 0.4, close + 0.6, close - 0.6, close, 1_500L));
            }
        }
        return candles;
    }

    private List<Candle> hourlyCandles(String symbol, long firstTimestamp, int count) {
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double close = 100.0 + Math.sin(index / 8.0) * 4.0;
            candles.add(new Candle(symbol, "60min", firstTimestamp + index * 3_600L,
                    close - .2, close + .4, close - .4, close, 1_000L));
        }
        return List.copyOf(candles);
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

    private ElliottWaveDetectionService.ElliottWaveStructure truncatedWaveVStructure(
            long confirmationTimestamp) {
        List<ElliottWaveDetectionService.ElliottWavePoint> points = List.of(
                new ElliottWaveDetectionService.ElliottWavePoint("", 6L * 86_400L, 100.0, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("I", 14L * 86_400L, 121.0, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("II", 24L * 86_400L, 110.0, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("III", 38L * 86_400L, 143.0, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("IV", 54L * 86_400L, 126.0, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("V", 68L * 86_400L, 140.0, "HIGH")
        );
        return new ElliottWaveDetectionService.ElliottWaveStructure(
                "BULLISH", false, points, confirmationTimestamp, 81,
                0.5, false, 1.5, 0.35,
                ElliottWaveDetectionService.ImpulseVariant.TRUNCATED_FIFTH,
                ElliottWaveDetectionService.CorrectionVariant.NONE,
                Double.NaN, Double.NaN, List.of());
    }

    private record Anchor(int index, double price) {
    }
}
