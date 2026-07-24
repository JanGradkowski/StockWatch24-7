package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.util.List;

/**
 * Pure, close-based rules for resolving a tracked candlestick signal.
 *
 * <p>The live lifecycle service and the historical lifecycle backtest both use
 * this policy so that confirmation research cannot drift away from production
 * semantics.</p>
 */
final class CandlestickSignalLifecyclePolicy {

    private CandlestickSignalLifecyclePolicy() {
    }

    static LifecycleResolution resolve(TradeSignal tradeSignal,
                                       double confirmationTriggerPrice,
                                       double invalidationPrice,
                                       List<Candle> subsequentCandles,
                                       int confirmationWindowCandles) {
        if ((tradeSignal != TradeSignal.BUY && tradeSignal != TradeSignal.SELL)
                || confirmationWindowCandles < 1) {
            return null;
        }

        List<Candle> candles = subsequentCandles == null ? List.of() : subsequentCandles;
        int observedCandles = Math.min(candles.size(), confirmationWindowCandles);
        for (int index = 0; index < observedCandles; index++) {
            Candle candle = candles.get(index);
            SignalLifecycleStatus outcome = closeBasedOutcome(
                    tradeSignal,
                    confirmationTriggerPrice,
                    invalidationPrice,
                    candle.getClosePrice()
            );
            if (outcome != null) {
                return new LifecycleResolution(outcome, candle, index + 1);
            }
        }

        if (observedCandles >= confirmationWindowCandles) {
            return new LifecycleResolution(
                    SignalLifecycleStatus.EXPIRED,
                    candles.get(confirmationWindowCandles - 1),
                    confirmationWindowCandles
            );
        }
        return null;
    }

    static int patternCandleCount(CandlePattern pattern) {
        if (pattern == null) {
            return 0;
        }
        return switch (pattern) {
            case HAMMER, HANGING_MAN, INVERTED_HAMMER, SHOOTING_STAR -> 1;
            case BULLISH_ENGULFING, BEARISH_ENGULFING,
                    PIERCING_LINE, DARK_CLOUD_COVER,
                    BULLISH_HARAMI, BEARISH_HARAMI -> 2;
            case MORNING_STAR, EVENING_STAR,
                    THREE_WHITE_SOLDIERS, THREE_BLACK_CROWS -> 3;
            default -> 0;
        };
    }

    private static SignalLifecycleStatus closeBasedOutcome(TradeSignal tradeSignal,
                                                            double confirmationTriggerPrice,
                                                            double invalidationPrice,
                                                            double closePrice) {
        if (tradeSignal == TradeSignal.BUY) {
            if (closePrice > confirmationTriggerPrice) {
                return SignalLifecycleStatus.CONFIRMED;
            }
            if (closePrice < invalidationPrice) {
                return SignalLifecycleStatus.INVALIDATED;
            }
            return null;
        }
        if (tradeSignal == TradeSignal.SELL) {
            if (closePrice < confirmationTriggerPrice) {
                return SignalLifecycleStatus.CONFIRMED;
            }
            if (closePrice > invalidationPrice) {
                return SignalLifecycleStatus.INVALIDATED;
            }
        }
        return null;
    }

    record LifecycleResolution(
            SignalLifecycleStatus status,
            Candle resolutionCandle,
            int candleOffset
    ) {
    }
}
