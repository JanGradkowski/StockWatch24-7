package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandlestickSignalLifecycleServiceTest {

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

    private record Fixture(
            AlertEventRepository repository,
            AlertNotificationService notifications,
            CandlestickSignalLifecycleService service,
            AlertEvent event
    ) {
    }
}
