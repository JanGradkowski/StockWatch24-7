package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
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
    static final String RISK_REWARD_VERSION = "CANDLE_RR_V2";
    static final int TIME_STOP_CANDLES = 8;

    private CandlestickSignalLifecyclePolicy() {
    }

    static LifecycleResolution resolve(TradeSignal tradeSignal,
                                       double confirmationTriggerPrice,
                                       double invalidationPrice,
                                       List<Candle> subsequentCandles,
                                       int confirmationWindowCandles) {
        return resolve(null, tradeSignal, confirmationTriggerPrice, invalidationPrice,
                subsequentCandles, confirmationWindowCandles);
    }

    static LifecycleResolution resolve(CandlePattern pattern,
                                       TradeSignal tradeSignal,
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

    static boolean requiresNextCandleConfirmation(CandlePattern pattern) {
        return pattern == CandlePattern.HAMMER
                || pattern == CandlePattern.HANGING_MAN
                || pattern == CandlePattern.INVERTED_HAMMER
                || pattern == CandlePattern.SHOOTING_STAR;
    }

    static TradePlan tradePlan(CandlePattern pattern,
                               TradeSignal tradeSignal,
                               double entryPrice,
                               List<Candle> formationCandles,
                               TimeInterval interval) {
        return tradePlan(pattern, tradeSignal, entryPrice, formationCandles, interval,
                CandlestickPatternPreferencesService.StopLossMode.STRUCTURAL_BUFFER,
                0.0, rewardRiskRatio(interval));
    }

    static TradePlan tradePlan(CandlePattern pattern,
                               TradeSignal tradeSignal,
                               double entryPrice,
                               List<Candle> formationCandles,
                               TimeInterval interval,
                               CandlestickPatternPreferencesService.StopLossMode stopLossMode,
                               double stopLossValuePercent,
                               double rewardRiskRatio) {
        if ((tradeSignal != TradeSignal.BUY && tradeSignal != TradeSignal.SELL)
                || !Double.isFinite(entryPrice) || entryPrice <= 0
                || formationCandles == null || formationCandles.isEmpty()) {
            throw new IllegalArgumentException("A directional signal, positive entry, and formation are required.");
        }
        List<Candle> formation = formationCandles.stream()
                .filter(candle -> candle != null
                        && candle.getHighPrice() != null && Double.isFinite(candle.getHighPrice())
                        && candle.getLowPrice() != null && Double.isFinite(candle.getLowPrice()))
                .toList();
        if (formation.isEmpty()) {
            throw new IllegalArgumentException("The formation has no complete price range.");
        }
        double structuralStop = structuralStopPrice(pattern, formation);
        double stopLoss = configuredStopPrice(tradeSignal, entryPrice, structuralStop,
                stopLossMode, stopLossValuePercent);
        return tradePlan(tradeSignal, entryPrice, stopLoss, interval, rewardRiskRatio);
    }

    static double configuredStopPrice(
            TradeSignal tradeSignal,
            double entryPrice,
            double structuralStopPrice,
            CandlestickPatternPreferencesService.StopLossMode mode,
            double valuePercent) {
        if ((tradeSignal != TradeSignal.BUY && tradeSignal != TradeSignal.SELL)
                || !Double.isFinite(entryPrice) || entryPrice <= 0
                || !Double.isFinite(structuralStopPrice)
                || mode == null || !Double.isFinite(valuePercent)) {
            throw new IllegalArgumentException("A valid directional stop-loss rule is required.");
        }
        double distance = entryPrice * valuePercent / 100.0;
        return switch (mode) {
            case STRUCTURAL_BUFFER -> tradeSignal == TradeSignal.BUY
                    ? structuralStopPrice - distance
                    : structuralStopPrice + distance;
            case FIXED_ENTRY_PERCENT -> tradeSignal == TradeSignal.BUY
                    ? entryPrice - distance
                    : entryPrice + distance;
        };
    }

    static double structuralStopPrice(CandlePattern pattern, List<Candle> formation) {
        if (formation == null || formation.isEmpty()) {
            throw new IllegalArgumentException("A candlestick formation is required.");
        }
        Candle signalCandle = formation.getLast();
        double formationLow = formation.stream().mapToDouble(Candle::getLowPrice).min().orElseThrow();
        double formationHigh = formation.stream().mapToDouble(Candle::getHighPrice).max().orElseThrow();
        return switch (pattern) {
            case HAMMER, INVERTED_HAMMER -> signalCandle.getLowPrice();
            case SHOOTING_STAR, HANGING_MAN -> signalCandle.getHighPrice();
            case PIERCING_LINE -> signalCandle.getLowPrice();
            case DARK_CLOUD_COVER -> signalCandle.getHighPrice();
            case BULLISH_ENGULFING, BULLISH_HARAMI, MORNING_STAR, THREE_WHITE_SOLDIERS -> formationLow;
            case BEARISH_ENGULFING, BEARISH_HARAMI, EVENING_STAR, THREE_BLACK_CROWS -> formationHigh;
            default -> throw new IllegalArgumentException("No candlestick trade plan exists for " + pattern + ".");
        };
    }

    static TradePlan tradePlan(TradeSignal tradeSignal,
                               double entryPrice,
                               double stopLoss,
                               TimeInterval interval) {
        return tradePlan(tradeSignal, entryPrice, stopLoss, interval, rewardRiskRatio(interval));
    }

    static TradePlan tradePlan(TradeSignal tradeSignal,
                               double entryPrice,
                               double stopLoss,
                               TimeInterval interval,
                               double rewardRiskRatio) {
        double risk = tradeSignal == TradeSignal.BUY
                ? entryPrice - stopLoss
                : stopLoss - entryPrice;
        if (!Double.isFinite(risk) || risk <= 0) {
            throw new IllegalStateException("The structural stop must be beyond the entry in the adverse direction.");
        }
        if (!Double.isFinite(rewardRiskRatio) || rewardRiskRatio <= 0) {
            throw new IllegalArgumentException("A positive risk-to-reward ratio is required.");
        }
        double profitTarget = tradeSignal == TradeSignal.BUY
                ? entryPrice + risk * rewardRiskRatio
                : entryPrice - risk * rewardRiskRatio;
        if (!Double.isFinite(profitTarget)) {
            throw new IllegalStateException("The profit target could not be calculated.");
        }
        return new TradePlan(entryPrice, stopLoss, profitTarget, rewardRiskRatio, TIME_STOP_CANDLES);
    }

    static double rewardRiskRatio(TimeInterval interval) {
        return switch (interval) {
            case WEEKLY -> 3.0;
            case MONTHLY -> 4.0;
            default -> 2.0;
        };
    }

    static LifecycleResolution resolveCandidateGate(CandlePattern pattern,
                                                    TradeSignal tradeSignal,
                                                    double candidateClose,
                                                    List<Candle> subsequentCandles) {
        if (!requiresNextCandleConfirmation(pattern)
                || (tradeSignal != TradeSignal.BUY && tradeSignal != TradeSignal.SELL)
                || subsequentCandles == null || subsequentCandles.isEmpty()) {
            return null;
        }
        Candle next = subsequentCandles.getFirst();
        boolean favorableClose = tradeSignal == TradeSignal.BUY
                ? next.getClosePrice() > candidateClose
                : next.getClosePrice() < candidateClose;
        boolean confirmingBody = tradeSignal == TradeSignal.BUY
                ? next.getClosePrice() > next.getOpenPrice()
                : next.getClosePrice() < next.getOpenPrice();
        return new LifecycleResolution(
                favorableClose && confirmingBody
                        ? SignalLifecycleStatus.DETECTED
                        : SignalLifecycleStatus.REJECTED,
                next,
                1);
    }

    private static SignalLifecycleStatus closeBasedOutcome(TradeSignal tradeSignal,
                                                            double confirmationTriggerPrice,
                                                            double invalidationPrice,
                                                            double closePrice) {
        if (tradeSignal == TradeSignal.BUY) {
            if (closePrice >= confirmationTriggerPrice) {
                return SignalLifecycleStatus.CONFIRMED;
            }
            if (closePrice <= invalidationPrice) {
                return SignalLifecycleStatus.INVALIDATED;
            }
            return null;
        }
        if (tradeSignal == TradeSignal.SELL) {
            if (closePrice <= confirmationTriggerPrice) {
                return SignalLifecycleStatus.CONFIRMED;
            }
            if (closePrice >= invalidationPrice) {
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

    record TradePlan(double entryPrice,
                     double stopLossPrice,
                     double profitTargetPrice,
                     double rewardRiskRatio,
                     int timeStopCandles) {
    }
}
