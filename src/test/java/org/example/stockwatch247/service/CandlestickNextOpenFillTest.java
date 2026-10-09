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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Candlestick trades enter at the open after the signal, never at the signal close. */
class CandlestickNextOpenFillTest {
    private final AlertEventRepository repository = mock(AlertEventRepository.class);
    private final AlertNotificationService notifications = mock(AlertNotificationService.class);
    private final CandlestickSignalLifecycleService service =
            new CandlestickSignalLifecycleService(repository, notifications);

    @Test
    void theNextOpenFillsTheTradeAndThatSameCandleCanStopIt() {
        AlertEvent event = detectedBullishEngulfing();
        assertThat(event.getTradeFillStatus()).isEqualTo("AWAITING_FILL");
        assertThat(event.getTradeEntryPrice()).isEqualTo(102.5);

        evaluate(event, candle(1_700L, 101.0, 102.0, 98.5, 99.5));

        assertThat(event.getTradeFillStatus()).isEqualTo("FILLED");
        assertThat(event.getTradeEntryTimestamp()).isEqualTo(1_700L);
        assertThat(event.getTradeEntryPrice()).isEqualTo(101.0);
        // The target is rebuilt from the real entry: 101 + 2 x (101 - 98.8).
        assertThat(event.getProfitTargetPrice()).isCloseTo(105.4, within(1e-8));
        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.INVALIDATED);
        assertThat(event.getResolutionCandleOffset()).isEqualTo(1);
        assertThat(event.getTradeResolutionPrice()).isEqualTo(98.8);
    }

    @Test
    void anOpenBeyondTheStopMeansNoTradeWasEverOpened() {
        AlertEvent event = detectedBullishEngulfing();

        evaluate(event, candle(1_700L, 98.0, 99.0, 97.0, 98.5));

        assertThat(event.getTradeFillStatus()).isEqualTo("NOT_FILLED");
        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.REJECTED);
        assertThat(event.getLifecycleResolutionReason()).contains("beyond the stop");
        assertThat(event.getTradeResolutionPrice()).isNull();
        verify(notifications).sendSignalLifecycleEmail(event);
    }

    @Test
    void anOpenBeyondTheTargetMeansTheMoveHappenedWithoutAnEntry() {
        AlertEvent event = detectedBullishEngulfing();

        evaluate(event, candle(1_700L, 110.5, 112.0, 110.0, 111.0));

        assertThat(event.getTradeFillStatus()).isEqualTo("NOT_FILLED");
        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.REJECTED);
        assertThat(event.getLifecycleResolutionReason()).contains("beyond the target");
    }

    @Test
    void storedV4PlansKeepEnteringAtTheSignalClose() {
        AlertEvent event = detectedBullishEngulfing();
        event.setTradePlanVersion("CANDLE_RR_V4");
        event.setTradeFillStatus(null);
        event.setStopLossPrice(98.69);

        evaluate(event, candle(1_700L, 101.0, 102.0, 98.5, 99.5));

        assertThat(event.getTradeEntryPrice()).isEqualTo(102.5);
        assertThat(event.getTradeFillStatus()).isNull();
        assertThat(event.getLifecycleStatus()).isEqualTo(SignalLifecycleStatus.INVALIDATED);
        assertThat(event.getTradeResolutionPrice()).isEqualTo(98.69);
    }

    private AlertEvent detectedBullishEngulfing() {
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule());
        event.setPattern(CandlePattern.BULLISH_ENGULFING);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(1_600L);
        service.initializeTracking(event, new DetectedSignal(CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY,
                SignalStength.MEDIUM_CONFIDENCE, 80, List.of("test"), 1_600L, 102.5), history());
        assertThat(event.getTradeActionable()).isTrue();
        return event;
    }

    private void evaluate(AlertEvent event, Candle next) {
        when(repository.findTrackedLifecycleEvents("AAPL", TimeInterval.DAILY, SignalLifecycleStatus.DETECTED))
                .thenReturn(List.of(event));
        List<Candle> candles = new ArrayList<>(history());
        candles.add(next);
        service.evaluatePending("AAPL", TimeInterval.DAILY, candles);
    }

    /** Fourteen quiet bars give a two-point ATR, then a bearish bar engulfed by a bullish one. */
    private List<Candle> history() {
        List<Candle> candles = new ArrayList<>();
        for (int index = 1; index <= 14; index++) candles.add(candle(index * 100L, 100, 101, 99, 100));
        candles.add(candle(1_500L, 101, 101.5, 99, 99.2));
        candles.add(candle(1_600L, 99, 103, 98.8, 102.5));
        return candles;
    }

    private AlertRule rule() {
        User user = new User();
        user.setEmail("fills@example.com");
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(AlertPatternFamily.CANDLESTICK);
        rule.setTradeSignal(TradeSignal.BUY);
        return rule;
    }

    private static Candle candle(long timestamp, double open, double high, double low, double close) {
        return new Candle("AAPL", "1d", timestamp, open, high, low, close, 1_000L);
    }
}
