package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CandlePatternDetectionService {
    private static final int MIN_SETUP_SCORE = 75;
    private static final int STRONG_SETUP_SCORE = 85;
    private static final int BODY_COMPARISON_LOOKBACK = 20;
    private static final int TREND_LOOKBACK = 5;
    private static final int MIN_TREND_CANDLES = 3;
    /*
     * The former stock-only components totaled 70 points:
     * pattern quality 25, higher timeframe 15, price location 10,
     * volatility/momentum 15, and volume 5. These allocations preserve those
     * proportions to one decimal place while making the stock-only total 100.
     */
    private static final int DAILY_PATTERN_QUALITY_MAX_TENTHS = 357;
    private static final int HIGHER_INTERVAL_STRUCTURE_MAX_TENTHS = 214;
    private static final int HISTORICAL_CALIBRATION_MAX_TENTHS = 143;
    private static final int HIGHER_TIMEFRAME_MAX_TENTHS = 214;
    private static final int PRICE_LOCATION_MAX_TENTHS = 143;
    private static final int VOLATILITY_MOMENTUM_MAX_TENTHS = 214;
    private static final int VOLUME_MAX_TENTHS = 72;

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
                    "Pattern geometry: the real body is no more than 10% of the candle range",
                    isAvailable(current.atr14())
                            ? "Volatility context: ATR was available for interpretation"
                            : "Volatility context: ATR was unavailable"
            )));
        }

        TrendContext singleCandleTrend = trendBefore(candles, last);
        CandleStatistics singleCandleStatistics = statisticsBefore(candles, last);
        if (isGeometricHammerShape(current)) {
            if (singleCandleTrend.direction() == TrendDirection.DOWN) {
                addSignal(signals, CandlePattern.HAMMER, TradeSignal.BUY,
                        evaluateSetup(CandlePattern.HAMMER, candles, last, last,
                                TradeSignal.BUY, singleCandleTrend, singleCandleStatistics));
            } else if (singleCandleTrend.direction() == TrendDirection.UP) {
                addSignal(signals, CandlePattern.HANGING_MAN, TradeSignal.SELL,
                        evaluateSetup(CandlePattern.HANGING_MAN, candles, last, last,
                                TradeSignal.SELL, singleCandleTrend, singleCandleStatistics));
            }
        }

        if (isGeometricShootingStarShape(current)) {
            if (singleCandleTrend.direction() == TrendDirection.UP) {
                addSignal(signals, CandlePattern.SHOOTING_STAR, TradeSignal.SELL,
                        evaluateSetup(CandlePattern.SHOOTING_STAR, candles, last, last,
                                TradeSignal.SELL, singleCandleTrend, singleCandleStatistics));
            } else if (singleCandleTrend.direction() == TrendDirection.DOWN) {
                addSignal(signals, CandlePattern.INVERTED_HAMMER, TradeSignal.BUY,
                        evaluateSetup(CandlePattern.INVERTED_HAMMER, candles, last, last,
                                TradeSignal.BUY, singleCandleTrend, singleCandleStatistics));
            }
        }

        int twoCandleStart = last - 1;
        TrendContext twoCandleTrend = trendBefore(candles, twoCandleStart);
        CandleStatistics twoCandleStatistics = statisticsBefore(candles, twoCandleStart);

        if (twoCandleTrend.direction() == TrendDirection.DOWN
                && isGeometricBullishEngulfing(previous, current)) {
            addSignal(signals, CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.BULLISH_ENGULFING, candles, last, twoCandleStart,
                            TradeSignal.BUY, twoCandleTrend, twoCandleStatistics));
        }
        if (twoCandleTrend.direction() == TrendDirection.UP
                && isGeometricBearishEngulfing(previous, current)) {
            addSignal(signals, CandlePattern.BEARISH_ENGULFING, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.BEARISH_ENGULFING, candles, last, twoCandleStart,
                            TradeSignal.SELL, twoCandleTrend, twoCandleStatistics));
        }
        if (twoCandleTrend.direction() == TrendDirection.DOWN
                && isGeometricPiercingLine(previous, current, twoCandleStatistics)) {
            addSignal(signals, CandlePattern.PIERCING_LINE, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.PIERCING_LINE, candles, last, twoCandleStart,
                            TradeSignal.BUY, twoCandleTrend, twoCandleStatistics));
        }
        if (twoCandleTrend.direction() == TrendDirection.UP
                && isGeometricDarkCloudCover(previous, current, twoCandleStatistics)) {
            addSignal(signals, CandlePattern.DARK_CLOUD_COVER, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.DARK_CLOUD_COVER, candles, last, twoCandleStart,
                            TradeSignal.SELL, twoCandleTrend, twoCandleStatistics));
        }
        if (twoCandleTrend.direction() == TrendDirection.DOWN
                && isGeometricBullishHarami(previous, current, twoCandleStatistics)) {
            addSignal(signals, CandlePattern.BULLISH_HARAMI, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.BULLISH_HARAMI, candles, last, twoCandleStart,
                            TradeSignal.BUY, twoCandleTrend, twoCandleStatistics));
        }
        if (twoCandleTrend.direction() == TrendDirection.UP
                && isGeometricBearishHarami(previous, current, twoCandleStatistics)) {
            addSignal(signals, CandlePattern.BEARISH_HARAMI, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.BEARISH_HARAMI, candles, last, twoCandleStart,
                            TradeSignal.SELL, twoCandleTrend, twoCandleStatistics));
        }

        if (candles.size() >= 3) {
            int threeCandleStart = last - 2;
            EnrichedCandle first = candles.get(threeCandleStart);
            EnrichedCandle middle = candles.get(last - 1);
            TrendContext threeCandleTrend = trendBefore(candles, threeCandleStart);
            CandleStatistics threeCandleStatistics = statisticsBefore(candles, threeCandleStart);

            if (threeCandleTrend.direction() == TrendDirection.DOWN
                    && isGeometricMorningStar(first, middle, current, threeCandleStatistics)) {
                addSignal(signals, CandlePattern.MORNING_STAR, TradeSignal.BUY,
                        evaluateSetup(CandlePattern.MORNING_STAR, candles, last, threeCandleStart,
                                TradeSignal.BUY, threeCandleTrend, threeCandleStatistics));
            }
            if (threeCandleTrend.direction() == TrendDirection.UP
                    && isGeometricEveningStar(first, middle, current, threeCandleStatistics)) {
                addSignal(signals, CandlePattern.EVENING_STAR, TradeSignal.SELL,
                        evaluateSetup(CandlePattern.EVENING_STAR, candles, last, threeCandleStart,
                                TradeSignal.SELL, threeCandleTrend, threeCandleStatistics));
            }
            if ((threeCandleTrend.direction() == TrendDirection.DOWN
                    || threeCandleTrend.direction() == TrendDirection.BASE)
                    && isGeometricThreeWhiteSoldiers(first, middle, current, threeCandleStatistics)) {
                addSignal(signals, CandlePattern.THREE_WHITE_SOLDIERS, TradeSignal.BUY,
                        evaluateSetup(CandlePattern.THREE_WHITE_SOLDIERS, candles, last, threeCandleStart,
                                TradeSignal.BUY, threeCandleTrend, threeCandleStatistics));
            }
            if (threeCandleTrend.direction() == TrendDirection.UP
                    && isGeometricThreeBlackCrows(first, middle, current, threeCandleStatistics)) {
                addSignal(signals, CandlePattern.THREE_BLACK_CROWS, TradeSignal.SELL,
                        evaluateSetup(CandlePattern.THREE_BLACK_CROWS, candles, last, threeCandleStart,
                                TradeSignal.SELL, threeCandleTrend, threeCandleStatistics));
            }
        }

        return List.copyOf(signals);
    }

    /**
     * Returns only patterns that pass both their mandatory candle geometry and
     * their required stock-specific prior-trend context. The setup score ranks confluence
     * after validity has been established; it never turns an invalid shape into a
     * named pattern.
     */
    public List<DetectedSignal> detectAlertSignals(List<EnrichedCandle> recentCandles) {
        return detect(recentCandles).stream()
                .filter(signal -> signal.tradeSignal() != TradeSignal.HOLD)
                .toList();
    }

    /**
     * Exposes the detector's exact pre-pattern trend classification to
     * package-level research harnesses so matched controls cannot drift from
     * production pattern semantics.
     */
    PriorTrendAssessment assessPriorTrendForLatestPattern(List<EnrichedCandle> recentCandles,
                                                           int patternCandleCount) {
        if (patternCandleCount < 1) {
            throw new IllegalArgumentException("patternCandleCount must be positive.");
        }
        if (recentCandles == null || recentCandles.isEmpty()) {
            return PriorTrendAssessment.none();
        }

        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        int patternStartIndex = candles.size() - patternCandleCount;
        if (patternStartIndex < 0) {
            return PriorTrendAssessment.none();
        }

        TrendContext context = trendBefore(candles, patternStartIndex);
        return new PriorTrendAssessment(context.direction(), context.scorePoints(), context.description());
    }

    private void addSignal(List<DetectedSignal> signals,
                           CandlePattern pattern,
                           TradeSignal tradeSignal,
                           SignalEvidence evidence) {
        int setupScore = evidence.setupScore();
        EnrichedCandle candle = evidence.candle();
        signals.add(new DetectedSignal(
                pattern,
                tradeSignal,
                classifyStrength(setupScore),
                setupScore,
                evidence.renderedComponents(),
                candle.timestamp(),
                candle.close()
        ));
    }

    private SignalStength classifyStrength(int setupScore) {
        if (setupScore < MIN_SETUP_SCORE) {
            return SignalStength.LOW_CONFIDENCE;
        }
        return setupScore >= STRONG_SETUP_SCORE
                ? SignalStength.HIGH_CONFIDENCE
                : SignalStength.MEDIUM_CONFIDENCE;
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

    private SignalEvidence evaluateSetup(CandlePattern pattern,
                                         List<EnrichedCandle> candles,
                                         int signalIndex,
                                         int setupIndex,
                                         TradeSignal direction,
                                         TrendContext trend,
                                         CandleStatistics statistics) {
        EnrichedCandle current = candles.get(signalIndex);
        List<ScoreComponent> components = new ArrayList<>();

        int rawGeometryPoints = geometryScore(pattern, candles, setupIndex, signalIndex, statistics);
        BaseInterval baseInterval = inferBaseInterval(candles);
        boolean calibratedHigherInterval = baseInterval != BaseInterval.DAILY;
        int patternQualityMaximum = calibratedHigherInterval ? 15 : 25;
        int geometryMaximum = calibratedHigherInterval ? 6 : 10;
        int trendMaximum = calibratedHigherInterval ? 9 : 15;
        int geometryPoints = (int) Math.round(rawGeometryPoints * geometryMaximum / 25.0);
        int trendPoints = (int) Math.round(trend.scorePoints() * trendMaximum / 25.0);
        int patternQualityMaximumTenths = calibratedHigherInterval
                ? HIGHER_INTERVAL_STRUCTURE_MAX_TENTHS
                : DAILY_PATTERN_QUALITY_MAX_TENTHS;
        components.add(weightedComponent(
                "Pattern quality",
                geometryPoints + trendPoints,
                patternQualityMaximum,
                patternQualityMaximumTenths,
                "all mandatory " + patternLabel(pattern)
                        + " geometry and prior-trend rules passed (geometry "
                        + rawGeometryPoints + "/25, trend " + trend.scorePoints()
                        + "/25); " + trend.description()
        ));
        if (calibratedHigherInterval) {
            CandlestickPatternCalibration.Timeframe calibrationTimeframe =
                    baseInterval == BaseInterval.WEEKLY
                            ? CandlestickPatternCalibration.Timeframe.WEEKLY
                            : CandlestickPatternCalibration.Timeframe.MONTHLY;
            CandlestickPatternCalibration.Assessment calibration =
                    CandlestickPatternCalibration.assess(pattern, calibrationTimeframe);
            components.add(weightedComponent(
                    "Historical pattern calibration",
                    calibration.points(),
                    CandlestickPatternCalibration.MAX_POINTS,
                    HISTORICAL_CALIBRATION_MAX_TENTHS,
                    calibration.detail()
            ));
        }
        components.add(higherTimeframeComponent(candles, current.timestamp(), direction));
        components.add(locationComponent(candles, setupIndex, signalIndex, direction));
        components.add(volatilityMomentumComponent(candles, signalIndex, direction));
        components.add(volumeComponent(current));

        return new SignalEvidence(current, components);
    }

    private ScoreComponent higherTimeframeComponent(List<EnrichedCandle> candles,
                                                     Long signalTimestamp,
                                                     TradeSignal direction) {
        BaseInterval baseInterval = inferBaseInterval(candles);
        List<String> details = new ArrayList<>();
        int points;

        if (baseInterval == BaseInterval.DAILY) {
            AlignmentAssessment weekly = higherPeriodAlignment(
                    completedPeriodCloses(candles, signalTimestamp, HigherPeriod.WEEKLY),
                    direction,
                    8,
                    "completed weekly"
            );
            AlignmentAssessment monthly = higherPeriodAlignment(
                    completedPeriodCloses(candles, signalTimestamp, HigherPeriod.MONTHLY),
                    direction,
                    7,
                    "completed monthly"
            );
            points = weekly.points() + monthly.points();
            details.add(weekly.detail());
            details.add(monthly.detail());
        } else if (baseInterval == BaseInterval.WEEKLY) {
            AlignmentAssessment monthly = higherPeriodAlignment(
                    completedPeriodCloses(candles, signalTimestamp, HigherPeriod.MONTHLY),
                    direction,
                    15,
                    "completed monthly"
            );
            points = monthly.points();
            details.add(monthly.detail());
        } else {
            AlignmentAssessment quarterly = higherPeriodAlignment(
                    completedPeriodCloses(candles, signalTimestamp, HigherPeriod.QUARTERLY),
                    direction,
                    15,
                    "completed quarterly"
            );
            points = quarterly.points();
            details.add(quarterly.detail());
        }
        return weightedComponent(
                "Higher-timeframe alignment",
                points,
                15,
                HIGHER_TIMEFRAME_MAX_TENTHS,
                String.join("; ", details)
        );
    }

    private ScoreComponent locationComponent(List<EnrichedCandle> candles,
                                             int setupIndex,
                                             int signalIndex,
                                             TradeSignal direction) {
        int points = 0;
        List<String> details = new ArrayList<>();
        int referenceStart = Math.max(0, setupIndex - BODY_COMPARISON_LOOKBACK);

        if (setupIndex > referenceStart) {
            double patternLow = Double.POSITIVE_INFINITY;
            double patternHigh = Double.NEGATIVE_INFINITY;
            for (int index = setupIndex; index <= signalIndex; index++) {
                patternLow = Math.min(patternLow, candles.get(index).low());
                patternHigh = Math.max(patternHigh, candles.get(index).high());
            }

            double referenceLow = Double.POSITIVE_INFINITY;
            double referenceHigh = Double.NEGATIVE_INFINITY;
            for (int index = referenceStart; index < setupIndex; index++) {
                referenceLow = Math.min(referenceLow, candles.get(index).low());
                referenceHigh = Math.max(referenceHigh, candles.get(index).high());
            }

            EnrichedCandle current = candles.get(signalIndex);
            double tolerance = Math.max(Math.abs(current.close()) * 0.005,
                    isAvailable(current.atr14()) ? current.atr14() * 0.25 : 0.0);
            if (direction == TradeSignal.BUY && patternLow <= referenceLow + tolerance) {
                points += 6;
                details.add("the pattern tested the recent support area within an ATR-aware tolerance");
            } else if (direction == TradeSignal.SELL && patternHigh >= referenceHigh - tolerance) {
                points += 6;
                details.add("the pattern tested the recent resistance area within an ATR-aware tolerance");
            }
        }

        boolean testedBollingerBand = false;
        for (int index = setupIndex; index <= signalIndex; index++) {
            EnrichedCandle candle = candles.get(index);
            if (direction == TradeSignal.BUY
                    && isAvailable(candle.lowerBollinger())
                    && candle.low() <= candle.lowerBollinger() * 1.005) {
                testedBollingerBand = true;
            } else if (direction == TradeSignal.SELL
                    && isAvailable(candle.upperBollinger())
                    && candle.high() >= candle.upperBollinger() * 0.995) {
                testedBollingerBand = true;
            }
        }
        if (testedBollingerBand) {
            points += 4;
            details.add(direction == TradeSignal.BUY
                    ? "price tested the lower Bollinger Band"
                    : "price tested the upper Bollinger Band");
        }

        if (details.isEmpty()) {
            details.add("no recent swing-level or Bollinger Band confluence was detected");
        }
        return weightedComponent(
                "Price location",
                points,
                10,
                PRICE_LOCATION_MAX_TENTHS,
                String.join("; ", details)
        );
    }

    private ScoreComponent volatilityMomentumComponent(List<EnrichedCandle> candles,
                                                       int signalIndex,
                                                       TradeSignal direction) {
        EnrichedCandle current = candles.get(signalIndex);
        EnrichedCandle previous = candles.get(Math.max(0, signalIndex - 1));
        int points = 0;
        List<String> details = new ArrayList<>();

        if (isAvailable(current.atr14()) && current.atr14() > 0.0) {
            double rangeToAtr = range(current) / current.atr14();
            int rangePoints = rangeToAtr >= 0.6 && rangeToAtr <= 2.0
                    ? 5
                    : rangeToAtr >= 0.4 && rangeToAtr <= 2.5 ? 3 : 1;
            points += rangePoints;
            details.add(String.format(Locale.ROOT,
                    "pattern candle range was %.2f ATR (+%d/5)",
                    rangeToAtr,
                    rangePoints));

            List<Double> priorAtr = new ArrayList<>();
            int atrStart = Math.max(0, signalIndex - BODY_COMPARISON_LOOKBACK);
            for (int index = atrStart; index <= signalIndex; index++) {
                if (isAvailable(candles.get(index).atr14()) && candles.get(index).atr14() > 0.0) {
                    priorAtr.add(candles.get(index).atr14());
                }
            }
            if (priorAtr.size() >= 5) {
                double percentile = percentileRank(priorAtr, current.atr14());
                int regimePoints = percentile >= 20.0 && percentile <= 80.0 ? 3 : 1;
                points += regimePoints;
                details.add(String.format(Locale.ROOT,
                        "ATR was at the %.0fth recent percentile (+%d/3)",
                        percentile,
                        regimePoints));
            }
        } else {
            details.add("ATR data was unavailable");
        }

        if (isAvailable(current.rsi14())) {
            if (direction == TradeSignal.BUY) {
                if (current.rsi14() <= 35) {
                    points += 4;
                    details.add("RSI is oversold");
                } else if (current.rsi14() < 45) {
                    points += 2;
                    details.add("RSI is below neutral");
                }
                if (isAvailable(previous.rsi14()) && current.rsi14() > previous.rsi14()) {
                    points += 3;
                    details.add("RSI is turning upward");
                }
            } else {
                if (current.rsi14() >= 65) {
                    points += 4;
                    details.add("RSI is overbought");
                } else if (current.rsi14() > 55) {
                    points += 2;
                    details.add("RSI is above neutral");
                }
                if (isAvailable(previous.rsi14()) && current.rsi14() < previous.rsi14()) {
                    points += 3;
                    details.add("RSI is turning downward");
                }
            }
        }

        if (details.isEmpty()) {
            details.add(isAvailable(current.rsi14())
                    ? "RSI did not add directional confluence"
                    : "RSI data was unavailable");
        }
        return weightedComponent(
                "Volatility and momentum",
                Math.min(points, 15),
                15,
                VOLATILITY_MOMENTUM_MAX_TENTHS,
                String.join("; ", details)
        );
    }

    private ScoreComponent volumeComponent(EnrichedCandle candle) {
        int points = 0;
        String detail;
        if (!isAvailable(candle.averageVolume20()) || candle.averageVolume20() <= 0) {
            detail = "20-period average volume was unavailable";
        } else {
            double ratio = candle.volume() / candle.averageVolume20();
            if (ratio >= 1.5) {
                points = 5;
                detail = "volume was at least 50% above its 20-period average";
            } else if (ratio >= 1.2) {
                points = 4;
                detail = "volume was at least 20% above its 20-period average";
            } else if (ratio >= 1.0) {
                points = 2;
                detail = "volume met its 20-period average";
            } else {
                detail = "volume was below its 20-period average";
            }
        }
        return weightedComponent("Volume", points, 5, VOLUME_MAX_TENTHS, detail);
    }

    private ScoreComponent weightedComponent(String category,
                                             int rawPoints,
                                             int rawMaximum,
                                             int allocatedMaximumTenths,
                                             String detail) {
        int clampedRawPoints = Math.max(0, Math.min(rawPoints, rawMaximum));
        int allocatedPointsTenths = rawMaximum <= 0
                ? 0
                : (int) Math.round(clampedRawPoints * allocatedMaximumTenths / (double) rawMaximum);
        return new ScoreComponent(category, allocatedPointsTenths, allocatedMaximumTenths, detail);
    }

    private double simpleMovingAverage(List<Double> values, int endInclusive, int period) {
        int start = endInclusive - period + 1;
        if (period <= 0 || start < 0 || endInclusive >= values.size()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int index = start; index <= endInclusive; index++) {
            sum += values.get(index);
        }
        return sum / period;
    }

    private boolean directionalDelta(double delta, TradeSignal direction) {
        return direction == TradeSignal.BUY ? delta > 0.0 : delta < 0.0;
    }

    private String alignmentLabel(boolean aligned) {
        return aligned ? "aligned with the signal" : "not aligned with the signal";
    }

    private BaseInterval inferBaseInterval(List<EnrichedCandle> candles) {
        if (candles.size() < 2) {
            return BaseInterval.DAILY;
        }
        List<Double> gaps = new ArrayList<>();
        int first = Math.max(1, candles.size() - BODY_COMPARISON_LOOKBACK);
        for (int index = first; index < candles.size(); index++) {
            long gap = candles.get(index).timestamp() - candles.get(index - 1).timestamp();
            if (gap > 0) {
                gaps.add((double) gap);
            }
        }
        double medianGapSeconds = median(gaps);
        if (medianGapSeconds <= 4.0 * 86_400.0) {
            return BaseInterval.DAILY;
        }
        if (medianGapSeconds <= 14.0 * 86_400.0) {
            return BaseInterval.WEEKLY;
        }
        return BaseInterval.MONTHLY;
    }

    private List<Double> completedPeriodCloses(List<EnrichedCandle> candles,
                                               Long signalTimestamp,
                                               HigherPeriod period) {
        if (candles.isEmpty() || signalTimestamp == null) {
            return List.of();
        }
        String currentPeriod = periodKey(toUtcDate(signalTimestamp), period);
        Map<String, Double> closeByPeriod = new LinkedHashMap<>();
        for (EnrichedCandle candle : candles) {
            if (candle.timestamp() > signalTimestamp) {
                continue;
            }
            String key = periodKey(toUtcDate(candle.timestamp()), period);
            if (!key.equals(currentPeriod)) {
                closeByPeriod.put(key, candle.close());
            }
        }
        return List.copyOf(closeByPeriod.values());
    }

    private LocalDate toUtcDate(Long timestamp) {
        Instant instant = timestamp > 100_000_000_000L
                ? Instant.ofEpochMilli(timestamp)
                : Instant.ofEpochSecond(timestamp);
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private String periodKey(LocalDate date, HigherPeriod period) {
        return switch (period) {
            case WEEKLY -> date.get(WeekFields.ISO.weekBasedYear()) + "-W"
                    + date.get(WeekFields.ISO.weekOfWeekBasedYear());
            case MONTHLY -> YearMonth.from(date).toString();
            case QUARTERLY -> date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
        };
    }

    private AlignmentAssessment higherPeriodAlignment(List<Double> closes,
                                                       TradeSignal direction,
                                                       int maximumPoints,
                                                       String label) {
        if (closes.size() < 4) {
            return new AlignmentAssessment(
                    0,
                    label + " alignment was unavailable (fewer than four closed periods)"
            );
        }

        int last = closes.size() - 1;
        int averagePeriod = Math.min(5, closes.size() - 1);
        double currentAverage = simpleMovingAverage(closes, last, averagePeriod);
        double priorAverage = simpleMovingAverage(closes, last - 1, averagePeriod);
        boolean priceAligned = directionalDelta(closes.get(last) - currentAverage, direction);
        boolean slopeAligned = directionalDelta(currentAverage - priorAverage, direction);
        int pricePoints = (maximumPoints + 1) / 2;
        int slopePoints = maximumPoints - pricePoints;
        int points = (priceAligned ? pricePoints : 0) + (slopeAligned ? slopePoints : 0);
        return new AlignmentAssessment(
                points,
                label + " close/mean was " + alignmentLabel(priceAligned)
                        + " and mean slope was " + alignmentLabel(slopeAligned)
                        + " (+" + points + "/" + maximumPoints + ")"
        );
    }

    private double percentileRank(List<Double> values, double target) {
        if (values.isEmpty()) {
            return 0.0;
        }
        long atOrBelow = values.stream().filter(value -> value <= target).count();
        return atOrBelow * 100.0 / values.size();
    }

    private int geometryScore(CandlePattern pattern,
                              List<EnrichedCandle> candles,
                              int setupIndex,
                              int signalIndex,
                              CandleStatistics statistics) {
        EnrichedCandle first = candles.get(setupIndex);
        EnrichedCandle current = candles.get(signalIndex);
        int score = 20;

        switch (pattern) {
            case HAMMER, HANGING_MAN -> {
                if (lowerShadow(current) >= body(current) * 3.0) {
                    score += 3;
                }
                if (upperShadow(current) <= range(current) * 0.1) {
                    score += 2;
                }
            }
            case INVERTED_HAMMER, SHOOTING_STAR -> {
                if (upperShadow(current) >= body(current) * 3.0) {
                    score += 3;
                }
                if (lowerShadow(current) <= range(current) * 0.1) {
                    score += 2;
                }
            }
            case BULLISH_ENGULFING, BEARISH_ENGULFING -> {
                if (body(current) >= body(first) * 1.25) {
                    score += 3;
                }
                if (body(current) >= range(current) * 0.6) {
                    score += 2;
                }
            }
            case BULLISH_HARAMI, BEARISH_HARAMI -> {
                if (statistics.hasEnoughData()
                        && body(first) >= statistics.medianBody() * 1.4) {
                    score += 3;
                }
                if (body(current) <= body(first) * 0.25) {
                    score += 2;
                }
            }
            case PIERCING_LINE, DARK_CLOUD_COVER -> {
                double penetration = body(first) == 0.0
                        ? 0.0
                        : Math.abs(current.close() - first.close()) / body(first);
                if (penetration >= 0.65) {
                    score += 3;
                }
                if (body(current) >= range(current) * 0.6) {
                    score += 2;
                }
            }
            case MORNING_STAR, EVENING_STAR -> {
                EnrichedCandle middle = candles.get(setupIndex + 1);
                boolean bodyGap = pattern == CandlePattern.MORNING_STAR
                        ? Math.max(middle.open(), middle.close()) < Math.min(first.open(), first.close())
                        : Math.min(middle.open(), middle.close()) > Math.max(first.open(), first.close());
                if (bodyGap) {
                    score += 2;
                }
                if (body(current) >= body(first) * 0.8) {
                    score += 3;
                }
            }
            case THREE_WHITE_SOLDIERS, THREE_BLACK_CROWS -> {
                boolean veryLongBodies = true;
                boolean verySmallDirectionalWicks = true;
                for (int index = setupIndex; index <= signalIndex; index++) {
                    EnrichedCandle candle = candles.get(index);
                    veryLongBodies &= body(candle) >= range(candle) * 0.65;
                    verySmallDirectionalWicks &= pattern == CandlePattern.THREE_WHITE_SOLDIERS
                            ? upperShadow(candle) <= body(candle) * 0.15
                            : lowerShadow(candle) <= body(candle) * 0.15;
                }
                if (veryLongBodies) {
                    score += 3;
                }
                if (verySmallDirectionalWicks) {
                    score += 2;
                }
            }
            default -> score = 25;
        }
        return Math.min(score, 25);
    }

    private boolean isGeometricDoji(EnrichedCandle candle) {
        return body(candle) <= range(candle) * 0.1;
    }

    private boolean isGeometricHammerShape(EnrichedCandle candle) {
        return body(candle) >= range(candle) * 0.02
                && lowerShadow(candle) >= body(candle) * 2.0
                && upperShadow(candle) <= body(candle) * 0.5
                && body(candle) <= range(candle) * 0.3;
    }

    private boolean isGeometricShootingStarShape(EnrichedCandle candle) {
        return body(candle) >= range(candle) * 0.02
                && upperShadow(candle) >= body(candle) * 2.0
                && lowerShadow(candle) <= body(candle) * 0.5
                && body(candle) <= range(candle) * 0.3;
    }

    private boolean isGeometricBullishEngulfing(EnrichedCandle previous, EnrichedCandle current) {
        return isBearish(previous)
                && isBullish(current)
                && body(previous) >= range(previous) * 0.2
                && body(current) >= body(previous)
                && body(current) >= range(current) * 0.45
                && current.open() <= previous.close()
                && current.close() >= previous.open();
    }

    private boolean isGeometricBearishEngulfing(EnrichedCandle previous, EnrichedCandle current) {
        return isBullish(previous)
                && isBearish(current)
                && body(previous) >= range(previous) * 0.2
                && body(current) >= body(previous)
                && body(current) >= range(current) * 0.45
                && current.open() >= previous.close()
                && current.close() <= previous.open();
    }

    private boolean isGeometricPiercingLine(EnrichedCandle previous,
                                            EnrichedCandle current,
                                            CandleStatistics statistics) {
        double previousMidpoint = (previous.open() + previous.close()) / 2.0;
        return isBearish(previous)
                && isBullish(current)
                && isLongBody(previous, statistics)
                && isStrongBody(current, statistics)
                && current.open() < previous.close()
                && current.close() > previousMidpoint
                && current.close() < previous.open();
    }

    private boolean isGeometricDarkCloudCover(EnrichedCandle previous,
                                               EnrichedCandle current,
                                               CandleStatistics statistics) {
        double previousMidpoint = (previous.open() + previous.close()) / 2.0;
        return isBullish(previous)
                && isBearish(current)
                && isLongBody(previous, statistics)
                && isStrongBody(current, statistics)
                && current.open() > previous.close()
                && current.close() < previousMidpoint
                && current.close() > previous.open();
    }

    private boolean isGeometricBullishHarami(EnrichedCandle previous,
                                             EnrichedCandle current,
                                             CandleStatistics statistics) {
        return isBearish(previous)
                && isBullish(current)
                && isLongBody(previous, statistics)
                && current.open() >= previous.close()
                && current.close() <= previous.open()
                && isHaramiSmallBody(previous, current, statistics);
    }

    private boolean isGeometricBearishHarami(EnrichedCandle previous,
                                             EnrichedCandle current,
                                             CandleStatistics statistics) {
        return isBullish(previous)
                && isBearish(current)
                && isLongBody(previous, statistics)
                && current.open() <= previous.close()
                && current.close() >= previous.open()
                && isHaramiSmallBody(previous, current, statistics);
    }

    private boolean isGeometricMorningStar(EnrichedCandle first,
                                           EnrichedCandle middle,
                                           EnrichedCandle current,
                                           CandleStatistics statistics) {
        return isBearish(first)
                && isBullish(current)
                && isLongBody(first, statistics)
                && isSmallBody(middle, statistics)
                && isStrongBody(current, statistics)
                && current.close() > (first.open() + first.close()) / 2.0;
    }

    private boolean isGeometricEveningStar(EnrichedCandle first,
                                           EnrichedCandle middle,
                                           EnrichedCandle current,
                                           CandleStatistics statistics) {
        return isBullish(first)
                && isBearish(current)
                && isLongBody(first, statistics)
                && isSmallBody(middle, statistics)
                && isStrongBody(current, statistics)
                && current.close() < (first.open() + first.close()) / 2.0;
    }

    private boolean isGeometricThreeWhiteSoldiers(EnrichedCandle first,
                                                  EnrichedCandle second,
                                                  EnrichedCandle third,
                                                  CandleStatistics statistics) {
        return isBullish(first)
                && isBullish(second)
                && isBullish(third)
                && isRelativelyLongDirectionalBody(first, statistics)
                && isRelativelyLongDirectionalBody(second, statistics)
                && isRelativelyLongDirectionalBody(third, statistics)
                && second.close() > first.close()
                && third.close() > second.close()
                && opensWithin(first, second)
                && opensWithin(second, third)
                && upperShadow(first) <= body(first) * 0.3
                && upperShadow(second) <= body(second) * 0.3
                && upperShadow(third) <= body(third) * 0.3;
    }

    private boolean isGeometricThreeBlackCrows(EnrichedCandle first,
                                               EnrichedCandle second,
                                               EnrichedCandle third,
                                               CandleStatistics statistics) {
        return isBearish(first)
                && isBearish(second)
                && isBearish(third)
                && isRelativelyLongDirectionalBody(first, statistics)
                && isRelativelyLongDirectionalBody(second, statistics)
                && isRelativelyLongDirectionalBody(third, statistics)
                && second.close() < first.close()
                && third.close() < second.close()
                && opensWithin(first, second)
                && opensWithin(second, third)
                && lowerShadow(first) <= body(first) * 0.3
                && lowerShadow(second) <= body(second) * 0.3
                && lowerShadow(third) <= body(third) * 0.3;
    }

    private boolean isLongBody(EnrichedCandle candle, CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * 0.55
                && body(candle) >= statistics.medianBody() * 1.1;
    }

    private boolean isStrongBody(EnrichedCandle candle, CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * 0.5
                && body(candle) >= statistics.medianBody() * 0.9;
    }

    private boolean isSmallBody(EnrichedCandle candle, CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) <= range(candle) * 0.3
                && body(candle) <= statistics.medianBody() * 0.75;
    }

    private boolean isHaramiSmallBody(EnrichedCandle first,
                                      EnrichedCandle second,
                                      CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(second) <= body(first) * 0.45
                && body(second) <= statistics.medianBody() * 0.75;
    }

    private boolean isRelativelyLongDirectionalBody(EnrichedCandle candle,
                                                    CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * 0.5
                && body(candle) >= statistics.medianBody() * 0.8;
    }

    private TrendContext trendBefore(List<EnrichedCandle> candles, int patternStartIndex) {
        int endIndex = patternStartIndex - 1;
        if (endIndex < 0) {
            return TrendContext.none(0);
        }
        int startIndex = Math.max(0, endIndex - TREND_LOOKBACK + 1);
        int candleCount = endIndex - startIndex + 1;
        if (candleCount < MIN_TREND_CANDLES) {
            return TrendContext.none(candleCount);
        }

        int higherCloses = 0;
        int lowerCloses = 0;
        int higherHighAndLow = 0;
        int lowerHighAndLow = 0;
        double grossCloseMove = 0.0;
        for (int index = startIndex + 1; index <= endIndex; index++) {
            EnrichedCandle previous = candles.get(index - 1);
            EnrichedCandle current = candles.get(index);
            grossCloseMove += Math.abs(current.close() - previous.close());
            if (current.close() > previous.close()) {
                higherCloses++;
            } else if (current.close() < previous.close()) {
                lowerCloses++;
            }
            if (current.high() > previous.high() && current.low() > previous.low()) {
                higherHighAndLow++;
            } else if (current.high() < previous.high() && current.low() < previous.low()) {
                lowerHighAndLow++;
            }
        }

        EnrichedCandle first = candles.get(startIndex);
        EnrichedCandle last = candles.get(endIndex);
        int transitions = candleCount - 1;
        int requiredTransitions = (int) Math.ceil(transitions * 0.6);
        double netMove = last.close() - first.close();
        double minimumMove = Math.max(Math.abs(first.close()) * 0.015, 0.000001);
        double directionalEfficiency = grossCloseMove > 0.0
                ? Math.abs(netMove) / grossCloseMove
                : 0.0;

        // Trend is part of the classical pattern definition, so establish it from
        // raw price structure only. Indicator values (including ATR and EMA) must
        // never decide whether the pattern exists. Close-only sequences must also
        // make coherent net progress so choppy alternation is not called a trend.
        boolean upwardSequence = higherHighAndLow >= requiredTransitions
                || (higherCloses >= requiredTransitions && directionalEfficiency >= 0.35);
        boolean downwardSequence = lowerHighAndLow >= requiredTransitions
                || (lowerCloses >= requiredTransitions && directionalEfficiency >= 0.35);
        boolean emaUp = emaAligned(candles, endIndex, TrendDirection.UP);
        boolean emaDown = emaAligned(candles, endIndex, TrendDirection.DOWN);

        if (netMove >= minimumMove && upwardSequence) {
            return trendContext(TrendDirection.UP, candleCount, transitions,
                    higherCloses, higherHighAndLow, netMove, minimumMove, emaUp, first.close());
        }
        if (-netMove >= minimumMove && downwardSequence) {
            return trendContext(TrendDirection.DOWN, candleCount, transitions,
                    lowerCloses, lowerHighAndLow, -netMove, minimumMove, emaDown, first.close());
        }

        double contextHigh = Double.NEGATIVE_INFINITY;
        double contextLow = Double.POSITIVE_INFINITY;
        for (int index = startIndex; index <= endIndex; index++) {
            contextHigh = Math.max(contextHigh, candles.get(index).high());
            contextLow = Math.min(contextLow, candles.get(index).low());
        }
        double maximumBaseRange = Math.abs(first.close()) * 0.04;
        boolean mixedCloses = higherCloses > 0 && lowerCloses > 0;
        if (Math.abs(netMove) < minimumMove
                && mixedCloses
                && contextHigh - contextLow <= Math.max(maximumBaseRange, 0.000001)) {
            return new TrendContext(
                    TrendDirection.BASE,
                    15,
                    String.format(Locale.ROOT,
                            "compact basing range across %d completed pre-pattern candles (%.2f%% total span)",
                            candleCount,
                            first.close() == 0.0
                                    ? 0.0
                                    : (contextHigh - contextLow) / Math.abs(first.close()) * 100.0)
            );
        }
        return TrendContext.none(candleCount);
    }

    private TrendContext trendContext(TrendDirection direction,
                                      int candleCount,
                                      int transitions,
                                      int directionalCloses,
                                      int directionalStructure,
                                      double absoluteMove,
                                      double minimumMove,
                                      boolean emaAligned,
                                      double startingClose) {
        double structureRatio = transitions == 0 ? 0.0 : (double) directionalStructure / transitions;
        double closeRatio = transitions == 0 ? 0.0 : (double) directionalCloses / transitions;
        double moveMultiple = absoluteMove / minimumMove;
        int points = 14;
        points += structureRatio >= 0.75 ? 4 : structureRatio >= 0.5 ? 2 : 0;
        points += closeRatio >= 0.75 ? 3 : closeRatio >= 0.5 ? 1 : 0;
        points += moveMultiple >= 2.0 ? 3 : moveMultiple >= 1.5 ? 2 : 1;
        points += emaAligned ? 1 : 0;
        points = Math.min(points, 25);

        double percentageMove = startingClose == 0.0 ? 0.0 : absoluteMove / Math.abs(startingClose) * 100.0;
        String directionLabel = direction == TrendDirection.UP ? "uptrend" : "downtrend";
        String transitionLabel = direction == TrendDirection.UP
                ? "higher-high/higher-low"
                : "lower-high/lower-low";
        String description = String.format(Locale.ROOT,
                "established %s across %d completed pre-pattern candles (%.2f%% net move, %d/%d directional closes, %d/%d %s transitions%s)",
                directionLabel,
                candleCount,
                percentageMove,
                directionalCloses,
                transitions,
                directionalStructure,
                transitions,
                transitionLabel,
                emaAligned ? ", EMA aligned" : "");
        return new TrendContext(direction, points, description);
    }

    private boolean emaAligned(List<EnrichedCandle> candles,
                               int contextIndex,
                               TrendDirection direction) {
        EnrichedCandle context = candles.get(contextIndex);
        if (!isAvailable(context.ema20())) {
            return false;
        }
        boolean positionAligned = direction == TrendDirection.UP
                ? context.close() > context.ema20()
                : context.close() < context.ema20();
        if (!positionAligned || contextIndex == 0
                || !isAvailable(candles.get(contextIndex - 1).ema20())) {
            return positionAligned;
        }
        return direction == TrendDirection.UP
                ? context.ema20() > candles.get(contextIndex - 1).ema20()
                : context.ema20() < candles.get(contextIndex - 1).ema20();
    }

    private CandleStatistics statisticsBefore(List<EnrichedCandle> candles, int patternStartIndex) {
        int startIndex = Math.max(0, patternStartIndex - BODY_COMPARISON_LOOKBACK);
        List<Double> bodies = new ArrayList<>();
        List<Double> ranges = new ArrayList<>();
        for (int index = startIndex; index < patternStartIndex; index++) {
            bodies.add(body(candles.get(index)));
            ranges.add(range(candles.get(index)));
        }
        return new CandleStatistics(median(bodies), median(ranges), bodies.size());
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
        }
        return sorted.get(middle);
    }

    private boolean hasCompleteData(EnrichedCandle candle) {
        return candle != null
                && candle.timestamp() != null
                && isAvailable(candle.open())
                && isAvailable(candle.high())
                && isAvailable(candle.low())
                && isAvailable(candle.close())
                && candle.high() >= Math.max(candle.open(), candle.close())
                && candle.low() <= Math.min(candle.open(), candle.close())
                && candle.high() >= candle.low();
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
        return Math.max(0.0, candle.high() - Math.max(candle.open(), candle.close()));
    }

    private double lowerShadow(EnrichedCandle candle) {
        return Math.max(0.0, Math.min(candle.open(), candle.close()) - candle.low());
    }

    private boolean isAvailable(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private String patternLabel(CandlePattern pattern) {
        String value = pattern.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
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

        /**
         * Preferred semantic name. confidenceScore remains the persisted/API
         * compatibility accessor used by existing Elliott-wave code.
         */
        public int setupScore() {
            return confidenceScore;
        }
    }

    private record SignalEvidence(EnrichedCandle candle, List<ScoreComponent> components) {
        private SignalEvidence {
            components = List.copyOf(components);
        }

        private int setupScore() {
            int totalTenths = components.stream().mapToInt(ScoreComponent::pointsTenths).sum();
            return Math.min(100, (int) Math.round(totalTenths / 10.0));
        }

        private List<String> renderedComponents() {
            return components.stream().map(ScoreComponent::render).toList();
        }
    }

    private record ScoreComponent(String category,
                                  int pointsTenths,
                                  int maximumTenths,
                                  String detail) {
        private ScoreComponent {
            pointsTenths = Math.max(0, Math.min(pointsTenths, maximumTenths));
        }

        private String render() {
            return category + " +" + formatTenths(pointsTenths)
                    + "/" + formatTenths(maximumTenths) + ": " + detail;
        }

        private String formatTenths(int value) {
            if (value % 10 == 0) {
                return Integer.toString(value / 10);
            }
            return String.format(Locale.ROOT, "%.1f", value / 10.0);
        }
    }

    private record AlignmentAssessment(int points, String detail) {
    }

    private record CandleStatistics(double medianBody, double medianRange, int sampleSize) {
        private boolean hasEnoughData() {
            return sampleSize >= MIN_TREND_CANDLES && medianBody > 0.0 && medianRange > 0.0;
        }
    }

    private record TrendContext(TrendDirection direction, int scorePoints, String description) {
        private static TrendContext none(int candleCount) {
            return new TrendContext(
                    TrendDirection.SIDEWAYS,
                    0,
                    candleCount < MIN_TREND_CANDLES
                            ? "fewer than three completed pre-pattern candles were available"
                            : "no established directional trend was present before the pattern"
            );
        }
    }

    record PriorTrendAssessment(TrendDirection direction, int scorePoints, String description) {
        private static PriorTrendAssessment none() {
            return new PriorTrendAssessment(TrendDirection.SIDEWAYS, 0, "no prior trend was available");
        }
    }

    enum TrendDirection {
        UP,
        DOWN,
        BASE,
        SIDEWAYS
    }

    private enum BaseInterval {
        DAILY,
        WEEKLY,
        MONTHLY
    }

    private enum HigherPeriod {
        WEEKLY,
        MONTHLY,
        QUARTERLY
    }
}
