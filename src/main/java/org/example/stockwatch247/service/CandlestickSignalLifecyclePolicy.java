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
    /**
     * V5 enters at the next candle's open and applies the configured stop exactly. V4 entered at the
     * signal close and added a volatility buffer beyond the configured stop; stored V4 plans keep that.
     */
    static final String RISK_REWARD_VERSION = "CANDLE_RR_V5";
    static final String PREVIOUS_RISK_REWARD_VERSION = "CANDLE_RR_V4";

    static boolean protectivePlan(String version) {
        return RISK_REWARD_VERSION.equals(version) || PREVIOUS_RISK_REWARD_VERSION.equals(version);
    }
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
                    BULLISH_HARAMI, BEARISH_HARAMI, BULLISH_HARAMI_CROSS, BEARISH_HARAMI_CROSS -> 2;
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
        if (formation.size() != formationCandles.size() || formation.stream().anyMatch(c -> !TradeRiskPolicy.valid(c))) {
            throw new IllegalArgumentException("The formation has no complete price range.");
        }
        double structuralStop = structuralStopPrice(pattern, formation);
        double stopLoss = finalStopPrice(tradeSignal, entryPrice, structuralStop,
                stopLossMode, stopLossValuePercent);
        return planAtStop(tradeSignal, entryPrice, stopLoss, interval, rewardRiskRatio, Double.NaN,
                new CandlestickPatternPreferencesService.CircuitBreakerSettings(
                        false, 14, 1.5, interval == TimeInterval.DAILY ? 25.0 : 50.0), List.of(), -1);
    }

    /**
     * The stop the user configured, exactly: the pattern's structural price moved out by the chosen
     * buffer percent (the factory 0% means the textbook price itself), or a fixed percent from the
     * entry. Nothing else is added and a fixed stop is never swapped for the structural one.
     */
    static double finalStopPrice(TradeSignal tradeSignal, double entryPrice, double structuralStopPrice,
                                 CandlestickPatternPreferencesService.StopLossMode mode, double valuePercent) {
        return TradeRiskPolicy.roundStop(
                configuredStopPrice(tradeSignal, entryPrice, structuralStopPrice, mode, valuePercent),
                tradeSignal, entryPrice);
    }

    /** The reward/risk multiple a stored plan was built for, recovered from its unscaled R:R target. */
    static Double requiredRewardRisk(TradeSignal side, Double entry, Double stop, Double target, Double rrTarget) {
        Double objective = rrTarget != null ? rrTarget : target;
        if (entry == null || stop == null || objective == null) return null;
        double risk = TradeRiskPolicy.risk(side, entry, stop);
        double reward = TradeRiskPolicy.reward(side, entry, objective);
        return risk > 0 && reward > 0 ? reward / risk : null;
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
            case BULLISH_ENGULFING, BULLISH_HARAMI, BULLISH_HARAMI_CROSS, MORNING_STAR, THREE_WHITE_SOLDIERS -> formationLow;
            case BEARISH_ENGULFING, BEARISH_HARAMI, BEARISH_HARAMI_CROSS, EVENING_STAR, THREE_BLACK_CROWS -> formationHigh;
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
        return tradePlan(tradeSignal, entryPrice, stopLoss, interval, rewardRiskRatio,
                Double.NaN, new CandlestickPatternPreferencesService.CircuitBreakerSettings(
                        false, 14, 1.5, interval == TimeInterval.DAILY ? 25.0 : 50.0));
    }

    static TradePlan tradePlan(TradeSignal tradeSignal,
                               double entryPrice,
                               double stopLoss,
                               TimeInterval interval,
                               double rewardRiskRatio,
                               double atr,
                               CandlestickPatternPreferencesService.CircuitBreakerSettings circuitBreaker) {
        return tradePlan(tradeSignal, entryPrice, stopLoss, interval, rewardRiskRatio, atr, circuitBreaker, List.of(), -1);
    }

    static TradePlan tradePlan(TradeSignal side, double entry, double configuredStop, TimeInterval interval,
                               double requiredRr, double atr,
                               CandlestickPatternPreferencesService.CircuitBreakerSettings settings,
                               List<Candle> candles, int through) {
        if ((side != TradeSignal.BUY && side != TradeSignal.SELL) || !TradeRiskPolicy.positive(entry)
                || !TradeRiskPolicy.positive(configuredStop) || !TradeRiskPolicy.positive(requiredRr))
            throw new IllegalArgumentException("Entry, stop and reward/risk must be finite and positive.");
        double stop = TradeRiskPolicy.roundStop(side == TradeSignal.BUY
                ? configuredStop - TradeRiskPolicy.buffer(entry, atr, interval)
                : configuredStop + TradeRiskPolicy.buffer(entry, atr, interval), side, entry);
        return build(side, entry, stop, configuredStop, interval, requiredRr, atr, settings, candles, through);
    }

    /** Builds the plan around a final stop; the target is set by the required reward/risk. */
    static TradePlan planAtStop(TradeSignal side, double entry, double stop, TimeInterval interval,
                                double requiredRr, double atr,
                                CandlestickPatternPreferencesService.CircuitBreakerSettings settings,
                                List<Candle> candles, int through) {
        if ((side != TradeSignal.BUY && side != TradeSignal.SELL) || !TradeRiskPolicy.positive(entry)
                || !TradeRiskPolicy.positive(stop) || !TradeRiskPolicy.positive(requiredRr))
            throw new IllegalArgumentException("Entry, stop and reward/risk must be finite and positive.");
        return build(side, entry, stop, stop, interval, requiredRr, atr, settings, candles, through);
    }

    private static TradePlan build(TradeSignal side, double entry, double stop, double configuredStop,
                                   TimeInterval interval, double requiredRr, double atr,
                                   CandlestickPatternPreferencesService.CircuitBreakerSettings settings,
                                   List<Candle> candles, int through) {
        double risk = TradeRiskPolicy.risk(side, entry, stop);
        if (!TradeRiskPolicy.positive(stop) || risk <= 0) throw new IllegalStateException("No valid adverse structural stop.");
        double rrTarget = side == TradeSignal.BUY ? entry + risk * requiredRr : entry - risk * requiredRr;
        double target = TradeRiskPolicy.nearestObjective(candles, through, side, entry, rrTarget);
        // An impossible short target must not produce a negative price in a persisted plan.
        if (!TradeRiskPolicy.positive(target)) throw new IllegalStateException("No positive primary target.");
        var q = TradeRiskPolicy.qualify(side, entry, stop, target, atr, interval, requiredRr);
        return new TradePlan(entry, stop, target, target == rrTarget ? requiredRr : TradeRiskPolicy.reward(side, entry, target) / risk,
                TradeRiskPolicy.profile(interval).candleHorizon(), configuredStop, false,
                TradeRiskPolicy.positive(atr) ? atr : null, settings == null ? 14 : settings.atrPeriod(),
                null, null, q.actionable(), q.reason(), q.riskAtr(), q.riskPercent(),
                target == rrTarget ? null : rrTarget);
    }

    static LifecycleResolution resolveProtective(TradeSignal side, double target, double stop,
                                                 List<Candle> candles, int horizon) {
        if (candles == null || horizon <= 0) return null;
        for (int i = 0; i < Math.min(candles.size(), horizon); i++) {
            if (!TradeRiskPolicy.valid(candles.get(i))) return null;
            var outcome = TradeOutcomePolicy.evaluate(side, stop, target, candles.get(i));
            if (outcome != null) return new LifecycleResolution(outcome.kind() == TradeOutcomePolicy.Kind.STOPPED
                    ? SignalLifecycleStatus.INVALIDATED : SignalLifecycleStatus.CONFIRMED,
                    candles.get(i), i + 1, outcome.price(), outcome.reason());
        }
        if (candles.size() >= horizon) return new LifecycleResolution(SignalLifecycleStatus.EXPIRED,
                candles.get(horizon - 1), horizon, candles.get(horizon - 1).getClosePrice(), "Trade horizon ended at the completed close.");
        return null;
    }

    static double averageTrueRange(List<Candle> candles, int throughIndex, int period) {
        return TradeRiskPolicy.atr(candles, throughIndex, period);
    }

    static double rewardRiskRatio(TimeInterval interval) {
        return switch (interval) {
            case WEEKLY -> 3.0;
            case MONTHLY -> 3.0;
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
            int candleOffset, Double fillPrice, String reason
    ) {
        LifecycleResolution(SignalLifecycleStatus status, Candle candle, int offset) {
            this(status, candle, offset, candle.getClosePrice(), null);
        }
    }

    record TradePlan(double entryPrice,
                     double stopLossPrice,
                     double profitTargetPrice,
                     double rewardRiskRatio,
                     int timeStopCandles,
                     double configuredStopLossPrice,
                     boolean atrCircuitBreakerApplied,
                     Double atrValue,
                     Integer atrPeriod,
                     Double atrMultiplier,
                     Double activationThresholdPercent, boolean actionable, String qualification,
                     Double riskAtr, double riskPercent, Double secondaryTarget) {
    }
}
