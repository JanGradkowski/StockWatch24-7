package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandlestickSignalLifecycleServiceTest {

    @Test
    void silentlyRevisesExtendedWaveVAndRestartsObservationAnchor() {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3, 10);
        AlertEvent event = new AlertEvent();
        event.setPattern(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
        event.setTradeSignal(TradeSignal.SELL);
        event.setSignalCandleTimestamp(600L);
        service.initializeElliottTracking(event, bullishWaveVStructure());
        ElliottWaveDetectionService.ElliottWaveStructure extended = structure(
                false,
                List.of(
                        point("0", 100L, 100.0, "LOW"),
                        point("I", 200L, 120.0, "HIGH"),
                        point("II", 300L, 108.0, "LOW"),
                        point("III", 400L, 135.0, "HIGH"),
                        point("IV", 500L, 122.0, "LOW"),
                        point("V", 700L, 145.0, "HIGH")
                ),
                800L);
        when(repository.findTrackedLifecycleEvents(
                "AAPL", TimeInterval.WEEKLY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(event));
        when(detector.findLatestStructureForCycle(
                List.of(enriched(800L, 142.0)),
                event.getElliottCycleKey(),
                event.getElliottSignalStage())).thenReturn(Optional.of(extended));

        CandlestickSignalLifecycleService.LifecycleEvaluationResult result = service.evaluatePending(
                "AAPL",
                TimeInterval.WEEKLY,
                List.of(candle(800L, 143.0, 144.0, 141.0, 142.0)),
                List.of(enriched(800L, 142.0)),
                detector);

        assertThat(result.resolved()).isZero();
        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.DETECTED);
        assertThat(event.getElliottEndpointTimestamp()).isEqualTo(700L);
        assertThat(event.getElliottEndpointPrice()).isEqualTo(145.0);
        assertThat(event.getLifecycleAnchorCandleTimestamp()).isEqualTo(800L);
        verify(repository).save(event);
        verify(notifications, never()).sendSignalLifecycleEmail(event);
    }

    @Test
    void silentlyRevisesExtendedWaveCWithinSeparateCorrectionStage() {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3, 10);
        AlertEvent event = new AlertEvent();
        event.setPattern(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(900L);
        service.initializeElliottTracking(event, bullishCorrectionStructure());
        ElliottWaveDetectionService.ElliottWaveStructure extended = structure(
                true,
                List.of(
                        point("0", 100L, 100.0, "LOW"),
                        point("I", 200L, 120.0, "HIGH"),
                        point("II", 300L, 110.0, "LOW"),
                        point("III", 400L, 135.0, "HIGH"),
                        point("IV", 500L, 123.0, "LOW"),
                        point("V", 600L, 145.0, "HIGH"),
                        point("A", 700L, 130.0, "LOW"),
                        point("B", 800L, 138.0, "HIGH"),
                        point("C", 1_000L, 115.0, "LOW")
                ),
                1_100L);
        List<EnrichedCandle> enriched = List.of(enriched(1_100L, 118.0));
        when(repository.findTrackedLifecycleEvents(
                "AAPL", TimeInterval.WEEKLY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(event));
        when(detector.findLatestStructureForCycle(
                enriched,
                event.getElliottCycleKey(),
                event.getElliottSignalStage())).thenReturn(Optional.of(extended));

        CandlestickSignalLifecycleService.LifecycleEvaluationResult result = service.evaluatePending(
                "AAPL",
                TimeInterval.WEEKLY,
                List.of(candle(1_100L, 116.0, 119.0, 114.0, 118.0)),
                enriched,
                detector);

        assertThat(result.resolved()).isZero();
        assertThat(event.getElliottEndpointTimestamp()).isEqualTo(1_000L);
        assertThat(event.getElliottEndpointPrice()).isEqualTo(115.0);
        assertThat(event.getLifecycleAnchorCandleTimestamp()).isEqualTo(1_100L);
        assertThat(event.getConfirmationTriggerPrice()).isEqualTo(138.0);
        assertThat(event.getInvalidationPrice()).isEqualTo(100.0);
        verify(repository).save(event);
        verify(notifications, never()).sendSignalLifecycleEmail(event);
    }

    @Test
    void invalidatesWaveVOnlyWhenExtensionBreaksHardImpulseRule() {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3, 10);
        AlertEvent event = new AlertEvent();
        event.setPattern(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
        event.setTradeSignal(TradeSignal.SELL);
        event.setSignalCandleTimestamp(700L);
        ElliottWaveDetectionService.ElliottWaveStructure structure = structure(
                false,
                List.of(
                        point("0", 100L, 100.0, "LOW"),
                        point("I", 200L, 140.0, "HIGH"),
                        point("II", 300L, 120.0, "LOW"),
                        point("III", 400L, 150.0, "HIGH"),
                        point("IV", 500L, 142.0, "LOW"),
                        point("V", 600L, 165.0, "HIGH")
                ),
                700L);
        service.initializeElliottTracking(event, structure);
        when(repository.findTrackedLifecycleEvents(
                "AAPL", TimeInterval.WEEKLY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(event));

        CandlestickSignalLifecycleService.LifecycleEvaluationResult result = service.evaluatePending(
                "AAPL",
                TimeInterval.WEEKLY,
                List.of(
                        candle(700L, 164.0, 166.0, 160.0, 163.0),
                        candle(800L, 170.0, 173.0, 169.0, 172.0)),
                List.of(enriched(800L, 172.0)),
                null);

        assertThat(result.invalidated()).isEqualTo(1);
        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.INVALIDATED);
        assertThat(event.getInvalidationPrice()).isEqualTo(172.0);
        assertThat(event.getLifecycleResolutionReason()).contains("Wave III");
        verify(notifications).sendSignalLifecycleEmail(event);
    }

    @Test
    void initializesElliottWaveVEndWithStableCycleAndRevisableEndpoint() {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3, 10);
        AlertEvent event = new AlertEvent();
        event.setPattern(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
        event.setTradeSignal(TradeSignal.SELL);
        event.setSignalCandleTimestamp(700L);

        boolean initialized = service.initializeElliottTracking(event, bullishWaveVStructure());

        assertThat(initialized).isTrue();
        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.DETECTED);
        assertThat(event.getPatternLow()).isEqualTo(100.0);
        assertThat(event.getPatternHigh()).isEqualTo(140.0);
        assertThat(event.getConfirmationTriggerPrice()).isEqualTo(122.0);
        assertThat(event.getInvalidationPrice()).isNull();
        assertThat(event.getElliottCycleKey()).isEqualTo("BULLISH:100:200:300:400:500");
        assertThat(event.getElliottSignalStage())
                .isEqualTo(org.example.stockwatch247.model.enums.ElliottSignalStage.WAVE_V_END);
        assertThat(event.getElliottEndpointTimestamp()).isEqualTo(600L);
        assertThat(event.getElliottEndpointPrice()).isEqualTo(140.0);
        assertThat(event.getConfirmationWindowCandles()).isEqualTo(10);
        assertThat(event.isLifecycleTracked()).isTrue();
    }

    @Test
    void initializesCompletedElliottCorrectionWithWaveBConfirmationAndOriginInvalidation() {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3, 10);
        AlertEvent event = new AlertEvent();
        event.setPattern(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(1_000L);

        boolean initialized = service.initializeElliottTracking(event, bullishCorrectionStructure());

        assertThat(initialized).isTrue();
        assertThat(event.getConfirmationTriggerPrice()).isEqualTo(138.0);
        assertThat(event.getInvalidationPrice()).isEqualTo(100.0);
        assertThat(event.getElliottSignalStage())
                .isEqualTo(org.example.stockwatch247.model.enums.ElliottSignalStage.CORRECTION_END);
        assertThat(event.getElliottEndpointPrice()).isEqualTo(120.0);
        assertThat(event.getConfirmationWindowCandles()).isEqualTo(10);
    }

    @Test
    void backfillsUntrackedElliottEventFromCachedStructure() {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        ElliottWaveDetectionService elliottWaveDetectionService = mock(ElliottWaveDetectionService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3, 10);
        AlertEvent event = new AlertEvent();
        event.setPattern(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
        event.setTradeSignal(TradeSignal.SELL);
        event.setSignalCandleTimestamp(700L);
        List<EnrichedCandle> cached = List.of(enriched(700L, 130.0));
        when(repository.findUntrackedLifecycleEvents(
                "MARA",
                TimeInterval.WEEKLY,
                AlertPatternFamily.ELLIOTT_WAVE,
                SignalLifecycleStatus.DETECTED)).thenReturn(List.of(event));
        when(elliottWaveDetectionService.findStructureForSignal(
                cached,
                event.getPattern(),
                event.getSignalCandleTimestamp())).thenReturn(Optional.of(bullishWaveVStructure()));

        int initialized = service.initializeUntrackedElliott(
                "MARA", TimeInterval.WEEKLY, cached, elliottWaveDetectionService);

        assertThat(initialized).isEqualTo(1);
        assertThat(event.isLifecycleTracked()).isTrue();
        verify(repository).save(event);
    }

    @Test
    void initializesTwoCandlePatternWithFrozenRangeAndDirectionalBoundaries() {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3);
        AlertEvent event = new AlertEvent();
        DetectedSignal signal = signal(CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY, 200L, 107.0);

        service.initializeTracking(event, signal, List.of(
                candle(100L, 105.0, 110.0, 90.0, 95.0),
                candle(200L, 94.0, 108.0, 88.0, 107.0)
        ));

        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.DETECTED);
        assertThat(event.getPatternHigh()).isEqualTo(110.0);
        assertThat(event.getPatternLow()).isEqualTo(88.0);
        assertThat(event.getConfirmationTriggerPrice()).isEqualTo(110.0);
        assertThat(event.getInvalidationPrice()).isEqualTo(88.0);
        assertThat(event.getConfirmationWindowCandles()).isEqualTo(3);
        assertThat(event.isLifecycleTracked()).isTrue();
    }

    @Test
    void confirmsBuyOnFirstSubsequentCloseAbovePatternHighAndSendsOneFollowUp() {
        Fixture fixture = fixture(TradeSignal.BUY, 105.0, 95.0);
        when(fixture.repository().findTrackedLifecycleEvents(
                "AAPL", TimeInterval.DAILY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(fixture.event()));

        CandlestickSignalLifecycleService.LifecycleEvaluationResult result =
                fixture.service().evaluatePending("AAPL", TimeInterval.DAILY, List.of(
                        candle(100L, 99.0, 105.0, 95.0, 100.0),
                        candle(200L, 101.0, 108.0, 100.0, 106.0)
                ));

        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(result.resolved()).isEqualTo(1);
        assertThat(fixture.event().getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.CONFIRMED);
        assertThat(fixture.event().getResolutionCandleTimestamp()).isEqualTo(200L);
        assertThat(fixture.event().getResolutionCandleOffset()).isEqualTo(1);
        assertThat(fixture.event().getResolutionClosePrice()).isEqualTo(106.0);
        verify(fixture.notifications()).sendSignalLifecycleEmail(fixture.event());
        verify(fixture.repository()).save(fixture.event());
    }

    @Test
    void invalidatesSellWhenOppositeBoundaryClosesFirst() {
        Fixture fixture = fixture(TradeSignal.SELL, 105.0, 95.0);
        when(fixture.repository().findTrackedLifecycleEvents(
                "AAPL", TimeInterval.DAILY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(fixture.event()));

        fixture.service().evaluatePending("AAPL", TimeInterval.DAILY, List.of(
                candle(100L, 99.0, 105.0, 95.0, 100.0),
                candle(200L, 103.0, 108.0, 101.0, 106.0)
        ));

        assertThat(fixture.event().getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.INVALIDATED);
        assertThat(fixture.event().getResolutionCandleOffset()).isEqualTo(1);
        verify(fixture.notifications()).sendSignalLifecycleEmail(fixture.event());
    }

    @Test
    void expiresAfterConfiguredNumberOfClosesRemainInsideRange() {
        Fixture fixture = fixture(TradeSignal.BUY, 105.0, 95.0);
        when(fixture.repository().findTrackedLifecycleEvents(
                "AAPL", TimeInterval.DAILY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(fixture.event()));

        CandlestickSignalLifecycleService.LifecycleEvaluationResult result =
                fixture.service().evaluatePending("AAPL", TimeInterval.DAILY, List.of(
                        candle(100L, 99.0, 105.0, 95.0, 100.0),
                        candle(200L, 100.0, 104.0, 97.0, 101.0),
                        candle(300L, 101.0, 103.0, 96.0, 99.0),
                        candle(400L, 99.0, 104.0, 98.0, 102.0)
                ));

        assertThat(result.expired()).isEqualTo(1);
        assertThat(fixture.event().getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.EXPIRED);
        assertThat(fixture.event().getResolutionCandleTimestamp()).isEqualTo(400L);
        assertThat(fixture.event().getResolutionCandleOffset()).isEqualTo(3);
        verify(fixture.notifications()).sendSignalLifecycleEmail(fixture.event());
    }

    @Test
    void remainsDetectedUntilEnoughCandlesOrABoundaryCloseArrives() {
        Fixture fixture = fixture(TradeSignal.BUY, 105.0, 95.0);
        when(fixture.repository().findTrackedLifecycleEvents(
                "AAPL", TimeInterval.DAILY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(fixture.event()));

        CandlestickSignalLifecycleService.LifecycleEvaluationResult result =
                fixture.service().evaluatePending("AAPL", TimeInterval.DAILY, List.of(
                        candle(100L, 99.0, 105.0, 95.0, 100.0),
                        candle(200L, 100.0, 104.0, 97.0, 101.0),
                        candle(300L, 101.0, 103.0, 96.0, 99.0)
                ));

        assertThat(result.resolved()).isZero();
        assertThat(fixture.event().getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.DETECTED);
        verify(fixture.notifications(), never()).sendSignalLifecycleEmail(fixture.event());
        verify(fixture.repository(), never()).save(fixture.event());
    }

    @Test
    void exactBoundaryClosesDoNotConfirmOrInvalidateAndEventuallyExpire() {
        Fixture fixture = fixture(TradeSignal.BUY, 105.0, 95.0);
        when(fixture.repository().findTrackedLifecycleEvents(
                "AAPL", TimeInterval.DAILY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(fixture.event()));

        fixture.service().evaluatePending("AAPL", TimeInterval.DAILY, List.of(
                candle(100L, 99.0, 105.0, 95.0, 100.0),
                candle(200L, 100.0, 106.0, 97.0, 105.0),
                candle(300L, 101.0, 103.0, 94.0, 95.0),
                candle(400L, 99.0, 104.0, 98.0, 102.0)
        ));

        assertThat(fixture.event().getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.EXPIRED);
        assertThat(fixture.event().getResolutionCandleOffset()).isEqualTo(3);
        assertThat(fixture.event().getResolutionCandleTimestamp()).isEqualTo(400L);
    }

    private Fixture fixture(TradeSignal direction, double patternHigh, double patternLow) {
        AlertEventRepository repository = mock(AlertEventRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        CandlestickSignalLifecycleService service =
                new CandlestickSignalLifecycleService(repository, notifications, 3);
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule(direction));
        event.setPattern(direction == TradeSignal.BUY
                ? CandlePattern.HAMMER
                : CandlePattern.SHOOTING_STAR);
        event.setTradeSignal(direction);
        event.setSignalCandleTimestamp(100L);
        event.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
        event.setPatternHigh(patternHigh);
        event.setPatternLow(patternLow);
        event.setConfirmationTriggerPrice(direction == TradeSignal.BUY ? patternHigh : patternLow);
        event.setInvalidationPrice(direction == TradeSignal.BUY ? patternLow : patternHigh);
        event.setConfirmationWindowCandles(3);
        return new Fixture(repository, notifications, service, event);
    }

    private AlertRule rule(TradeSignal direction) {
        User user = new User();
        user.setEmail("alerts@example.com");
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(AlertPatternFamily.CANDLESTICK);
        rule.setTradeSignal(direction);
        return rule;
    }

    private DetectedSignal signal(CandlePattern pattern,
                                  TradeSignal direction,
                                  long timestamp,
                                  double close) {
        return new DetectedSignal(
                pattern,
                direction,
                SignalStength.MEDIUM_CONFIDENCE,
                80,
                List.of("test"),
                timestamp,
                close
        );
    }

    private Candle candle(long timestamp,
                          double open,
                          double high,
                          double low,
                          double close) {
        return new Candle("AAPL", "1d", timestamp, open, high, low, close, 1_000L);
    }

    private EnrichedCandle enriched(long timestamp, double close) {
        return new EnrichedCandle(timestamp, close, close + 1.0, close - 1.0, close,
                1_000.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN);
    }

    private ElliottWaveDetectionService.ElliottWaveStructure bullishWaveVStructure() {
        return structure(false, List.of(
                point("0", 100L, 100.0, "LOW"),
                point("I", 200L, 120.0, "HIGH"),
                point("II", 300L, 108.0, "LOW"),
                point("III", 400L, 135.0, "HIGH"),
                point("IV", 500L, 122.0, "LOW"),
                point("V", 600L, 140.0, "HIGH")
        ));
    }

    private ElliottWaveDetectionService.ElliottWaveStructure bullishCorrectionStructure() {
        return structure(true, List.of(
                point("0", 100L, 100.0, "LOW"),
                point("I", 200L, 120.0, "HIGH"),
                point("II", 300L, 110.0, "LOW"),
                point("III", 400L, 135.0, "HIGH"),
                point("IV", 500L, 123.0, "LOW"),
                point("V", 600L, 145.0, "HIGH"),
                point("A", 700L, 130.0, "LOW"),
                point("B", 800L, 138.0, "HIGH"),
                point("C", 900L, 120.0, "LOW")
        ));
    }

    private ElliottWaveDetectionService.ElliottWaveStructure structure(
            boolean correctionComplete,
            List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        return structure(correctionComplete, points, points.getLast().timestamp());
    }

    private ElliottWaveDetectionService.ElliottWaveStructure structure(
            boolean correctionComplete,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            long confirmationTimestamp) {
        return new ElliottWaveDetectionService.ElliottWaveStructure(
                "BULLISH",
                correctionComplete,
                points,
                confirmationTimestamp,
                85,
                0.5,
                false,
                1.5,
                0.35,
                ElliottWaveDetectionService.ImpulseVariant.STANDARD,
                correctionComplete
                        ? ElliottWaveDetectionService.CorrectionVariant.STANDARD
                        : ElliottWaveDetectionService.CorrectionVariant.NONE,
                correctionComplete ? 0.5 : Double.NaN,
                correctionComplete ? 1.0 : Double.NaN,
                List.of()
        );
    }

    private ElliottWaveDetectionService.ElliottWavePoint point(
            String label, long timestamp, double price, String pivotType) {
        return new ElliottWaveDetectionService.ElliottWavePoint(label, timestamp, price, pivotType);
    }

    private record Fixture(
            AlertEventRepository repository,
            AlertNotificationService notifications,
            CandlestickSignalLifecycleService service,
            AlertEvent event
    ) {
    }
}
