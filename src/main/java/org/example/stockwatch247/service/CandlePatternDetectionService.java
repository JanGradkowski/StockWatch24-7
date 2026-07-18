package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CandlePatternDetectionService {
    private static final int MIN_CALIBRATED_CONFIDENCE = 75;
    private static final int HIGH_CONFIDENCE = 85;

    public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
        if (recentCandles == null || recentCandles.size() < 2) {
            return List.of();
        }

        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < 2) {
            return List.of();
        }

        List<DetectedSignal> signals = new ArrayList<>();
        int last = candles.size() - 1;
        EnrichedCandle current = candles.get(last);
        EnrichedCandle previous = candles.get(last - 1);

        if (isGeometricDoji(current)) {
            signals.add(neutralSignal(CandlePattern.DOJI, current, List.of(
                    "real body is less than 10% of the candle range",
                    "candle range is meaningful versus ATR"
            )));
        }

        if (isGeometricHammerShape(current)) {
            if (isDowntrendBefore(candles, last)) {
                addSignal(signals, CandlePattern.HAMMER, TradeSignal.BUY,
                        evaluateBullishReversal(candles, last, last - 1));
            } else if (isUptrendBefore(candles, last)) {
                addSignal(signals, CandlePattern.HANGING_MAN, TradeSignal.SELL,
                        evaluateBearishReversal(candles, last, last - 1));
            }
        }

        if (isGeometricShootingStarShape(current)) {
            if (isEstablishedUptrendBefore(candles, last)) {
                addSignal(signals, CandlePattern.SHOOTING_STAR, TradeSignal.SELL,
                        evaluateBearishReversal(candles, last, last));
            } else if (isDowntrendBefore(candles, last)) {
                addSignal(signals, CandlePattern.INVERTED_HAMMER, TradeSignal.BUY,
                        evaluateBullishReversal(candles, last, last - 1));
            }
        }

        if (isGeometricBullishEngulfing(previous, current)) {
            addSignal(signals, CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY,
                    evaluateBullishReversal(candles, last, last - 1));
        }
        if (isGeometricBearishEngulfing(previous, current)) {
            addSignal(signals, CandlePattern.BEARISH_ENGULFING, TradeSignal.SELL,
                    evaluateBearishReversal(candles, last, last - 1));
        }
        if (isGeometricPiercingLine(previous, current)) {
            addSignal(signals, CandlePattern.PIERCING_LINE, TradeSignal.BUY,
                    evaluateBullishReversal(candles, last, last - 1));
        }
        if (isGeometricDarkCloudCover(previous, current)) {
            addSignal(signals, CandlePattern.DARK_CLOUD_COVER, TradeSignal.SELL,
                    evaluateBearishReversal(candles, last, last - 1));
        }
        if (isGeometricBullishHarami(previous, current)) {
            addSignal(signals, CandlePattern.BULLISH_HARAMI, TradeSignal.BUY,
                    evaluateBullishReversal(candles, last, last - 1));
        }
        if (isGeometricBearishHarami(previous, current)) {
            addSignal(signals, CandlePattern.BEARISH_HARAMI, TradeSignal.SELL,
                    evaluateBearishReversal(candles, last, last - 1));
        }

        if (candles.size() >= 3) {
            int firstIndex = last - 2;
            EnrichedCandle first = candles.get(firstIndex);
            EnrichedCandle middle = candles.get(last - 1);

            if (isGeometricMorningStar(first, middle, current)) {
                addSignal(signals, CandlePattern.MORNING_STAR, TradeSignal.BUY,
                        evaluateBullishReversal(candles, last, firstIndex));
            }
            if (isGeometricEveningStar(first, middle, current)) {
                addSignal(signals, CandlePattern.EVENING_STAR, TradeSignal.SELL,
                        evaluateBearishReversal(candles, last, firstIndex));
            }
            if (isGeometricThreeWhiteSoldiers(first, middle, current)) {
                addSignal(signals, CandlePattern.THREE_WHITE_SOLDIERS, TradeSignal.BUY,
                        evaluateBullishContinuation(candles, last, firstIndex));
            }
            if (isGeometricThreeBlackCrows(first, middle, current)) {
                addSignal(signals, CandlePattern.THREE_BLACK_CROWS, TradeSignal.SELL,
                        evaluateBearishContinuation(candles, last, firstIndex));
            }
        }

        return signals;
    }

    /**
     * Returns structurally valid BUY/SELL patterns for alert matching. Confidence
     * describes supporting evidence only and never determines pattern validity.
     */
    public List<DetectedSignal> detectAlertSignals(List<EnrichedCandle> recentCandles) {
        return detect(recentCandles).stream()
                .filter(signal -> signal.tradeSignal() != TradeSignal.HOLD)
                .toList();
    }

    private void addSignal(List<DetectedSignal> signals,
                           CandlePattern pattern,
                           TradeSignal tradeSignal,
                           SignalEvidence evidence) {
        SignalEvidence calibratedEvidence = applyPatternCalibration(pattern, evidence);
        EnrichedCandle candle = calibratedEvidence.candle();
        List<String> reasons = classifyReasons(calibratedEvidence);
        signals.add(new DetectedSignal(
                pattern,
                tradeSignal,
                classifyStrength(calibratedEvidence.confidenceScore()),
                calibratedEvidence.confidenceScore(),
                reasons,
                candle.timestamp(),
                candle.close()
        ));
    }

    private SignalEvidence applyPatternCalibration(CandlePattern pattern, SignalEvidence evidence) {
        int adjustment = patternCalibrationAdjustment(pattern);
        if (adjustment == 0) {
            return evidence;
        }

        int adjustedScore = clampScore(evidence.confidenceScore() + adjustment);
        List<String> reasons = new ArrayList<>(evidence.reasons());
        if (adjustment > 0) {
            reasons.add("pattern calibration raised confidence by " + adjustment + " points based on historical precision");
        } else {
            reasons.add("pattern calibration lowered confidence by " + Math.abs(adjustment) + " points based on historical precision");
        }
        return new SignalEvidence(evidence.candle(), adjustedScore, reasons);
    }

    private int patternCalibrationAdjustment(CandlePattern pattern) {
        return switch (pattern) {
            case INVERTED_HAMMER, MORNING_STAR -> 10;
            case HAMMER, BULLISH_HARAMI, BULLISH_ENGULFING -> 5;
            case HANGING_MAN, BEARISH_HARAMI -> -5;
            case SHOOTING_STAR, BEARISH_ENGULFING -> -10;
            case DARK_CLOUD_COVER -> -15;
            default -> 0;
        };
    }

    private SignalStength classifyStrength(int confidenceScore) {
        if (confidenceScore < MIN_CALIBRATED_CONFIDENCE) {
            return SignalStength.LOW_CONFIDENCE;
        }
        return confidenceScore >= HIGH_CONFIDENCE
                ? SignalStength.HIGH_CONFIDENCE
                : SignalStength.MEDIUM_CONFIDENCE;
    }

    private List<String> classifyReasons(SignalEvidence evidence) {
        List<String> reasons = new ArrayList<>(evidence.reasons());
        if (evidence.confidenceScore() < MIN_CALIBRATED_CONFIDENCE) {
            reasons.add("supporting evidence is below the calibrated confidence range; pattern geometry remains valid");
        }
        return List.copyOf(reasons);
    }

    private DetectedSignal neutralSignal(CandlePattern pattern, EnrichedCandle candle, List<String> reasons) {
        return new DetectedSignal(
                pattern,
                TradeSignal.HOLD,
                SignalStength.MEDIUM_CONFIDENCE,
                50,
                reasons,
                candle.timestamp(),
                candle.close()
        );
    }

    private SignalEvidence evaluateBullishReversal(List<EnrichedCandle> candles, int signalIndex, int setupIndex) {
        EnrichedCandle current = candles.get(signalIndex);
        EnrichedCandle previous = candles.get(Math.max(0, signalIndex - 1));
        int score = 30;
        List<String> reasons = new ArrayList<>();
        reasons.add("strict bullish candle-pattern geometry");

        if (isDowntrendBefore(candles, setupIndex)) {
            score += 20;
            reasons.add("pattern appears after a confirmed downtrend");
        }
        if (isAvailable(current.rsi14()) && current.rsi14() <= 35) {
            score += 15;
            reasons.add("RSI is oversold");
        } else if (isAvailable(current.rsi14()) && current.rsi14() < 45) {
            score += 8;
            reasons.add("RSI is below neutral");
        }
        if (isAvailable(current.rsi14())
                && isAvailable(previous.rsi14())
                && current.rsi14() > previous.rsi14()) {
            score += 10;
            reasons.add("RSI is rising versus the previous candle");
        }
        if (isAvailable(current.lowerBollinger()) && current.low() <= current.lowerBollinger() * 1.005) {
            score += 15;
            reasons.add("price tested the lower Bollinger Band");
        }
        if (isVolumeSurge(current, 1.5)) {
            score += 15;
            reasons.add("volume is at least 50% above its 20-period average");
        } else if (isVolumeSurge(current, 1.2)) {
            score += 10;
            reasons.add("volume is at least 20% above its 20-period average");
        }
        if (current.close() > previous.high()) {
            score += 10;
            reasons.add("close broke above the previous candle high");
        }

        return new SignalEvidence(current, clampScore(score), reasons);
    }

    private SignalEvidence evaluateBearishReversal(List<EnrichedCandle> candles, int signalIndex, int setupIndex) {
        EnrichedCandle current = candles.get(signalIndex);
        EnrichedCandle previous = candles.get(Math.max(0, signalIndex - 1));
        int score = 30;
        List<String> reasons = new ArrayList<>();
        reasons.add("strict bearish candle-pattern geometry");

        if (isUptrendBefore(candles, setupIndex)) {
            score += 20;
            reasons.add("pattern appears after a confirmed uptrend");
        }
        if (isAvailable(current.rsi14()) && current.rsi14() >= 65) {
            score += 15;
            reasons.add("RSI is overbought");
        } else if (isAvailable(current.rsi14()) && current.rsi14() > 55) {
            score += 8;
            reasons.add("RSI is above neutral");
        }
        if (isAvailable(current.rsi14())
                && isAvailable(previous.rsi14())
                && current.rsi14() < previous.rsi14()) {
            score += 10;
            reasons.add("RSI is falling versus the previous candle");
        }
        if (isAvailable(current.upperBollinger()) && current.high() >= current.upperBollinger() * 0.995) {
            score += 15;
            reasons.add("price tested the upper Bollinger Band");
        }
        if (isVolumeSurge(current, 1.5)) {
            score += 15;
            reasons.add("volume is at least 50% above its 20-period average");
        } else if (isVolumeSurge(current, 1.2)) {
            score += 10;
            reasons.add("volume is at least 20% above its 20-period average");
        }
        if (current.close() < previous.low()) {
            score += 10;
            reasons.add("close broke below the previous candle low");
        }

        return new SignalEvidence(current, clampScore(score), reasons);
    }

    private SignalEvidence evaluateBullishContinuation(List<EnrichedCandle> candles, int signalIndex, int setupIndex) {
        EnrichedCandle current = candles.get(signalIndex);
        int score = 35;
        List<String> reasons = new ArrayList<>();
        reasons.add("strict bullish continuation geometry");

        if (isDowntrendBefore(candles, setupIndex)) {
            score += 15;
            reasons.add("sequence begins after prior downside pressure");
        }
        if (isAvailable(current.ema20()) && current.close() > current.ema20()) {
            score += 15;
            reasons.add("close is above the 20-period EMA");
        }
        if (isAvailable(current.rsi14()) && current.rsi14() > 50 && current.rsi14() < 70) {
            score += 15;
            reasons.add("RSI confirms bullish momentum without extreme overbought pressure");
        }
        if (isVolumeSurge(current, 1.2)) {
            score += 15;
            reasons.add("volume confirms the continuation move");
        }

        return new SignalEvidence(current, clampScore(score), reasons);
    }

    private SignalEvidence evaluateBearishContinuation(List<EnrichedCandle> candles, int signalIndex, int setupIndex) {
        EnrichedCandle current = candles.get(signalIndex);
        int score = 35;
        List<String> reasons = new ArrayList<>();
        reasons.add("strict bearish continuation geometry");

        if (isUptrendBefore(candles, setupIndex)) {
            score += 15;
            reasons.add("sequence begins after prior upside pressure");
        }
        if (isAvailable(current.ema20()) && current.close() < current.ema20()) {
            score += 15;
            reasons.add("close is below the 20-period EMA");
        }
        if (isAvailable(current.rsi14()) && current.rsi14() < 50 && current.rsi14() > 30) {
            score += 15;
            reasons.add("RSI confirms bearish momentum without extreme oversold pressure");
        }
        if (isVolumeSurge(current, 1.2)) {
            score += 15;
            reasons.add("volume confirms the continuation move");
        }

        return new SignalEvidence(current, clampScore(score), reasons);
    }

    private boolean isGeometricDoji(EnrichedCandle candle) {
        return body(candle) <= range(candle) * 0.1 && isMeaningfulVolatility(candle);
    }

    private boolean isGeometricHammerShape(EnrichedCandle candle) {
        return isMeaningfulVolatility(candle)
                && lowerShadow(candle) >= body(candle) * 2.0
                && upperShadow(candle) <= body(candle) * 0.5
                && body(candle) <= range(candle) * 0.3;
    }

    private boolean isGeometricShootingStarShape(EnrichedCandle candle) {
        return isMeaningfulVolatility(candle)
                && upperShadow(candle) >= body(candle) * 2.0
                && lowerShadow(candle) <= body(candle) * 0.5
                && body(candle) <= range(candle) * 0.3;
    }

    private boolean isGeometricBullishEngulfing(EnrichedCandle previous, EnrichedCandle current) {
        return isMeaningfulVolatility(current)
                && isBearish(previous)
                && isBullish(current)
                && body(current) >= body(previous) * 1.05
                && body(current) >= range(current) * 0.45
                && current.open() <= previous.close()
                && current.close() > previous.open();
    }

    private boolean isGeometricBearishEngulfing(EnrichedCandle previous, EnrichedCandle current) {
        return isMeaningfulVolatility(current)
                && isBullish(previous)
                && isBearish(current)
                && body(current) >= body(previous) * 1.05
                && body(current) >= range(current) * 0.45
                && current.open() >= previous.close()
                && current.close() < previous.open();
    }

    private boolean isGeometricPiercingLine(EnrichedCandle previous, EnrichedCandle current) {
        double previousMidpoint = previous.close() + body(previous) / 2.0;
        return isMeaningfulVolatility(current)
                && isBearish(previous)
                && isBullish(current)
                && current.open() < previous.low()
                && current.close() > previousMidpoint
                && current.close() < previous.open();
    }

    private boolean isGeometricDarkCloudCover(EnrichedCandle previous, EnrichedCandle current) {
        double previousMidpoint = previous.open() + body(previous) / 2.0;
        return isMeaningfulVolatility(current)
                && isBullish(previous)
                && isBearish(current)
                && current.open() > previous.high()
                && current.close() < previousMidpoint
                && current.close() > previous.open();
    }

    private boolean isGeometricBullishHarami(EnrichedCandle previous, EnrichedCandle current) {
        return isMeaningfulVolatility(current)
                && isBearish(previous)
                && isBullish(current)
                && body(previous) >= range(previous) * 0.5
                && current.open() > previous.close()
                && current.close() < previous.open()
                && body(current) <= body(previous) * 0.45;
    }

    private boolean isGeometricBearishHarami(EnrichedCandle previous, EnrichedCandle current) {
        return isMeaningfulVolatility(current)
                && isBullish(previous)
                && isBearish(current)
                && body(previous) >= range(previous) * 0.5
                && current.open() < previous.close()
                && current.close() > previous.open()
                && body(current) <= body(previous) * 0.45;
    }

    private boolean isGeometricMorningStar(EnrichedCandle first, EnrichedCandle middle, EnrichedCandle current) {
        boolean middleSeparated = Math.max(middle.open(), middle.close()) < first.close();
        return isMeaningfulVolatility(first)
                && isMeaningfulVolatility(current)
                && isBearish(first)
                && isBullish(current)
                && middleSeparated
                && body(middle) <= range(middle) * 0.3
                && current.close() > (first.open() + first.close()) / 2.0;
    }

    private boolean isGeometricEveningStar(EnrichedCandle first, EnrichedCandle middle, EnrichedCandle current) {
        boolean middleSeparated = Math.min(middle.open(), middle.close()) > first.close();
        return isMeaningfulVolatility(first)
                && isMeaningfulVolatility(current)
                && isBullish(first)
                && isBearish(current)
                && middleSeparated
                && body(middle) <= range(middle) * 0.3
                && current.close() < (first.open() + first.close()) / 2.0;
    }

    private boolean isGeometricThreeWhiteSoldiers(EnrichedCandle first,
                                                  EnrichedCandle second,
                                                  EnrichedCandle third) {
        return isMeaningfulVolatility(third)
                && isBullish(first)
                && isBullish(second)
                && isBullish(third)
                && body(first) >= range(first) * 0.55
                && body(second) >= range(second) * 0.55
                && body(third) >= range(third) * 0.55
                && second.close() > first.close()
                && third.close() > second.close()
                && opensWithin(first, second)
                && opensWithin(second, third)
                && upperShadow(first) < body(first) * 0.25
                && upperShadow(second) < body(second) * 0.25
                && upperShadow(third) < body(third) * 0.25;
    }

    private boolean isGeometricThreeBlackCrows(EnrichedCandle first,
                                               EnrichedCandle second,
                                               EnrichedCandle third) {
        return isMeaningfulVolatility(third)
                && isBearish(first)
                && isBearish(second)
                && isBearish(third)
                && body(first) >= range(first) * 0.55
                && body(second) >= range(second) * 0.55
                && body(third) >= range(third) * 0.55
                && second.close() < first.close()
                && third.close() < second.close()
                && opensWithin(first, second)
                && opensWithin(second, third)
                && lowerShadow(first) < body(first) * 0.25
                && lowerShadow(second) < body(second) * 0.25
                && lowerShadow(third) < body(third) * 0.25;
    }

    private boolean isDowntrendBefore(List<EnrichedCandle> candles, int signalOrSetupIndex) {
        int contextIndex = Math.max(0, signalOrSetupIndex - 1);
        EnrichedCandle context = candles.get(contextIndex);
        int score = 0;

        if (isAvailable(context.ema20()) && context.close() < context.ema20()) {
            score++;
        }
        if (contextIndex >= 1 && isAvailable(context.ema20()) && isAvailable(candles.get(contextIndex - 1).ema20())
                && context.ema20() < candles.get(contextIndex - 1).ema20()) {
            score++;
        }
        if (contextIndex >= 3 && context.close() < candles.get(contextIndex - 3).close()) {
            score++;
        }

        return score >= 2;
    }

    private boolean isUptrendBefore(List<EnrichedCandle> candles, int signalOrSetupIndex) {
        int contextIndex = Math.max(0, signalOrSetupIndex - 1);
        EnrichedCandle context = candles.get(contextIndex);
        int score = 0;

        if (isAvailable(context.ema20()) && context.close() > context.ema20()) {
            score++;
        }
        if (contextIndex >= 1 && isAvailable(context.ema20()) && isAvailable(candles.get(contextIndex - 1).ema20())
                && context.ema20() > candles.get(contextIndex - 1).ema20()) {
            score++;
        }
        if (contextIndex >= 3 && context.close() > candles.get(contextIndex - 3).close()) {
            score++;
        }

        return score >= 2;
    }

    /**
     * A shooting-star shape is only a bearish reversal when it forms after a real
     * advance. Four context candles allow one pullback, while still requiring a
     * sequence dominated by higher highs/higher lows and a meaningful price move.
     */
    private boolean isEstablishedUptrendBefore(List<EnrichedCandle> candles, int signalIndex) {
        int contextIndex = signalIndex - 1;
        int firstContextIndex = contextIndex - 3;
        if (firstContextIndex < 0) {
            return false;
        }

        EnrichedCandle first = candles.get(firstContextIndex);
        EnrichedCandle context = candles.get(contextIndex);
        int higherHighAndLowTransitions = 0;
        for (int index = firstContextIndex + 1; index <= contextIndex; index++) {
            EnrichedCandle previous = candles.get(index - 1);
            EnrichedCandle current = candles.get(index);
            if (current.high() > previous.high() && current.low() > previous.low()) {
                higherHighAndLowTransitions++;
            }
        }

        double minimumAdvance = first.close() * 0.02;
        if (isAvailable(context.atr14())) {
            minimumAdvance = Math.max(minimumAdvance, context.atr14() * 0.5);
        }

        boolean meaningfulAdvance = context.close() - first.close() >= minimumAdvance;
        boolean higherRange = context.high() > first.high() && context.low() > first.low();
        boolean emaPositionConfirmed = !isAvailable(context.ema20()) || context.close() > context.ema20();
        boolean emaSlopeConfirmed = contextIndex == 0
                || !isAvailable(context.ema20())
                || !isAvailable(candles.get(contextIndex - 1).ema20())
                || context.ema20() > candles.get(contextIndex - 1).ema20();

        return higherHighAndLowTransitions >= 2
                && higherRange
                && meaningfulAdvance
                && emaPositionConfirmed
                && emaSlopeConfirmed;
    }

    private boolean isMeaningfulVolatility(EnrichedCandle candle) {
        return !isAvailable(candle.atr14()) || range(candle) > candle.atr14() * 0.4;
    }

    private boolean isVolumeSurge(EnrichedCandle candle, double multiplier) {
        return isAvailable(candle.averageVolume20()) && candle.volume() > candle.averageVolume20() * multiplier;
    }

    private boolean hasCompleteData(EnrichedCandle candle) {
        return candle != null
                && candle.timestamp() != null
                && isAvailable(candle.open())
                && isAvailable(candle.high())
                && isAvailable(candle.low())
                && isAvailable(candle.close());
    }

    private boolean opensWithin(EnrichedCandle previous, EnrichedCandle current) {
        return current.open() >= Math.min(previous.open(), previous.close())
                && current.open() <= Math.max(previous.open(), previous.close());
    }

    private boolean isBullish(EnrichedCandle candle) {
        return candle.close() > candle.open();
    }

    private boolean isBearish(EnrichedCandle candle) {
        return candle.close() < candle.open();
    }

    private double body(EnrichedCandle candle) {
        return Math.abs(candle.close() - candle.open());
    }

    private double range(EnrichedCandle candle) {
        return Math.max(candle.high() - candle.low(), 0.000001);
    }

    private double upperShadow(EnrichedCandle candle) {
        return candle.high() - Math.max(candle.open(), candle.close());
    }

    private double lowerShadow(EnrichedCandle candle) {
        return Math.min(candle.open(), candle.close()) - candle.low();
    }

    private boolean isAvailable(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(score, 100));
    }

    public record DetectedSignal(
            CandlePattern pattern,
            TradeSignal tradeSignal,
            SignalStength strength,
            int confidenceScore,
            List<String> reasons,
            Long candleTimestamp,
            Double closePrice
    ) {
        public DetectedSignal {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public DetectedSignal(CandlePattern pattern, TradeSignal tradeSignal, Long candleTimestamp, Double closePrice) {
            this(pattern, tradeSignal, SignalStength.LOW_CONFIDENCE, 0, List.of(), candleTimestamp, closePrice);
        }
    }

    private record SignalEvidence(EnrichedCandle candle, int confidenceScore, List<String> reasons) {
        private SignalEvidence {
            reasons = List.copyOf(reasons);
        }
    }
}
