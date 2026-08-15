package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlestickPatternPreferencesService.PatternProfile;
import org.example.stockwatch247.service.CandlestickPatternPreferencesService.PreferencesView;
import org.example.stockwatch247.service.CandlestickPatternPreferencesService.TrendRequirement;
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
    public static final String SETUP_SCORE_VERSION = "CANDLE_V4_EXPERIMENTAL";
    private static final int MIN_SETUP_SCORE = 75;
    private static final int STRONG_SETUP_SCORE = 85;
    private static final int BODY_COMPARISON_LOOKBACK = 20;
    private static final int TREND_LOOKBACK = 5;
    private static final int MIN_TREND_CANDLES = 3;
    private static final double MIN_TREND_MOVE_PERCENT = 1.5;
    /*
     * V4 is an explainable setup rank, not a probability forecast. Correlated
     * indicators share family caps so an individual price move cannot collect
     * full independent credit from every mathematical transformation of it.
     */
    private static final int PATTERN_QUALITY_MAX_TENTHS = 250;
    private static final int TREND_MAX_TENTHS = 200;
    private static final int HIGHER_TIMEFRAME_MAX_TENTHS = 50;
    private static final int MOMENTUM_MAX_TENTHS = 150;
    private static final int BOLLINGER_MAX_TENTHS = 100;
    private static final int SUPPORT_RESISTANCE_MAX_TENTHS = 150;
    private static final int VOLUME_MAX_TENTHS = 100;
    private final CandlestickAdaptiveTrendService adaptiveTrendService =
            new CandlestickAdaptiveTrendService();

    public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
        return detect(recentCandles, TrendDetectionRules.factory(),
                CandlestickPatternPreferencesService.factoryPreferences());
    }

    /**
     * Uses the validated interval- and direction-specific factory trend model.
     * Explicit user trend settings continue to use {@link #detect(List, TrendDetectionRules)}.
     */
    public List<DetectedSignal> detectFactory(List<EnrichedCandle> recentCandles,
                                               TimeInterval interval) {
        return detect(recentCandles, TrendDetectionRules.adaptiveFactory(interval),
                CandlestickPatternPreferencesService.factoryPreferences());
    }

    public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles,
                                       TrendDetectionRules trendRules) {
        return detect(recentCandles, trendRules, CandlestickPatternPreferencesService.factoryPreferences());
    }

    public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles,
                                       TrendDetectionRules trendRules,
                                       PreferencesView definitions) {
        if (trendRules == null) {
            throw new IllegalArgumentException("Candlestick trend-detection rules are required.");
        }
        if (definitions == null) {
            throw new IllegalArgumentException("Candlestick pattern definitions are required.");
        }
        trendRules.validate();
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

        TrendContext singleCandleBuyTrend = trendBefore(candles, last, trendRules, TradeSignal.BUY);
        TrendContext singleCandleSellTrend = trendBefore(candles, last, trendRules, TradeSignal.SELL);
        PatternProfile doji = definitions.profile(CandlePattern.DOJI);
        if (matchesTrend(doji, singleCandleBuyTrend, singleCandleSellTrend)
                && isGeometricDoji(current, doji)) {
            signals.add(neutralSignal(CandlePattern.DOJI, current, List.of(
                    "Pattern geometry: the real body is no more than " + formatThreshold(doji.value("maxBodyPercent"))
                            + "% of the candle range",
                    isAvailable(current.atr())
                            ? "Volatility context: ATR was available for interpretation"
                            : "Volatility context: ATR was unavailable"
            )));
        }

        CandleStatistics singleCandleStatistics = statisticsBefore(candles, last);
        PatternProfile hammer = definitions.profile(CandlePattern.HAMMER);
        if (matchesTrend(hammer, singleCandleBuyTrend, singleCandleSellTrend) && isGeometricHammerShape(current, hammer)) {
            addSignal(signals, CandlePattern.HAMMER, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.HAMMER, candles, last, last,
                            TradeSignal.BUY, singleCandleBuyTrend, singleCandleStatistics));
        }
        PatternProfile hangingMan = definitions.profile(CandlePattern.HANGING_MAN);
        if (matchesTrend(hangingMan, singleCandleBuyTrend, singleCandleSellTrend) && isGeometricHammerShape(current, hangingMan)) {
            addSignal(signals, CandlePattern.HANGING_MAN, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.HANGING_MAN, candles, last, last,
                            TradeSignal.SELL, singleCandleSellTrend, singleCandleStatistics));
        }
        PatternProfile shootingStar = definitions.profile(CandlePattern.SHOOTING_STAR);
        if (matchesTrend(shootingStar, singleCandleBuyTrend, singleCandleSellTrend) && isGeometricShootingStarShape(current, shootingStar)) {
            addSignal(signals, CandlePattern.SHOOTING_STAR, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.SHOOTING_STAR, candles, last, last,
                            TradeSignal.SELL, singleCandleSellTrend, singleCandleStatistics));
        }
        PatternProfile invertedHammer = definitions.profile(CandlePattern.INVERTED_HAMMER);
        if (matchesTrend(invertedHammer, singleCandleBuyTrend, singleCandleSellTrend) && isGeometricShootingStarShape(current, invertedHammer)) {
            addSignal(signals, CandlePattern.INVERTED_HAMMER, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.INVERTED_HAMMER, candles, last, last,
                            TradeSignal.BUY, singleCandleBuyTrend, singleCandleStatistics));
        }

        int twoCandleStart = last - 1;
        TrendContext twoCandleBuyTrend = trendBefore(
                candles, twoCandleStart, trendRules, TradeSignal.BUY);
        TrendContext twoCandleSellTrend = trendBefore(
                candles, twoCandleStart, trendRules, TradeSignal.SELL);
        CandleStatistics twoCandleStatistics = statisticsBefore(candles, twoCandleStart);

        PatternProfile bullishEngulfing = definitions.profile(CandlePattern.BULLISH_ENGULFING);
        if (matchesTrend(bullishEngulfing, twoCandleBuyTrend, twoCandleSellTrend)
                && isGeometricBullishEngulfing(previous, current, bullishEngulfing)) {
            addSignal(signals, CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.BULLISH_ENGULFING, candles, last, twoCandleStart,
                            TradeSignal.BUY, twoCandleBuyTrend, twoCandleStatistics));
        }
        PatternProfile bearishEngulfing = definitions.profile(CandlePattern.BEARISH_ENGULFING);
        if (matchesTrend(bearishEngulfing, twoCandleBuyTrend, twoCandleSellTrend)
                && isGeometricBearishEngulfing(previous, current, bearishEngulfing)) {
            addSignal(signals, CandlePattern.BEARISH_ENGULFING, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.BEARISH_ENGULFING, candles, last, twoCandleStart,
                            TradeSignal.SELL, twoCandleSellTrend, twoCandleStatistics));
        }
        PatternProfile piercing = definitions.profile(CandlePattern.PIERCING_LINE);
        if (matchesTrend(piercing, twoCandleBuyTrend, twoCandleSellTrend)
                && isGeometricPiercingLine(previous, current, twoCandleStatistics, piercing)) {
            addSignal(signals, CandlePattern.PIERCING_LINE, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.PIERCING_LINE, candles, last, twoCandleStart,
                            TradeSignal.BUY, twoCandleBuyTrend, twoCandleStatistics));
        }
        PatternProfile darkCloud = definitions.profile(CandlePattern.DARK_CLOUD_COVER);
        if (matchesTrend(darkCloud, twoCandleBuyTrend, twoCandleSellTrend)
                && isGeometricDarkCloudCover(previous, current, twoCandleStatistics, darkCloud)) {
            addSignal(signals, CandlePattern.DARK_CLOUD_COVER, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.DARK_CLOUD_COVER, candles, last, twoCandleStart,
                            TradeSignal.SELL, twoCandleSellTrend, twoCandleStatistics));
        }
        PatternProfile bullishHarami = definitions.profile(CandlePattern.BULLISH_HARAMI);
        if (matchesTrend(bullishHarami, twoCandleBuyTrend, twoCandleSellTrend)
                && isGeometricBullishHarami(previous, current, twoCandleStatistics, bullishHarami)) {
            addSignal(signals, CandlePattern.BULLISH_HARAMI, TradeSignal.BUY,
                    evaluateSetup(CandlePattern.BULLISH_HARAMI, candles, last, twoCandleStart,
                            TradeSignal.BUY, twoCandleBuyTrend, twoCandleStatistics));
        }
        PatternProfile bearishHarami = definitions.profile(CandlePattern.BEARISH_HARAMI);
        if (matchesTrend(bearishHarami, twoCandleBuyTrend, twoCandleSellTrend)
                && isGeometricBearishHarami(previous, current, twoCandleStatistics, bearishHarami)) {
            addSignal(signals, CandlePattern.BEARISH_HARAMI, TradeSignal.SELL,
                    evaluateSetup(CandlePattern.BEARISH_HARAMI, candles, last, twoCandleStart,
                            TradeSignal.SELL, twoCandleSellTrend, twoCandleStatistics));
        }

        if (candles.size() >= 3) {
            int threeCandleStart = last - 2;
            EnrichedCandle first = candles.get(threeCandleStart);
            EnrichedCandle middle = candles.get(last - 1);
            TrendContext threeCandleBuyTrend = trendBefore(
                    candles, threeCandleStart, trendRules, TradeSignal.BUY);
            TrendContext threeCandleSellTrend = trendBefore(
                    candles, threeCandleStart, trendRules, TradeSignal.SELL);
            CandleStatistics threeCandleStatistics = statisticsBefore(candles, threeCandleStart);

            PatternProfile morningStar = definitions.profile(CandlePattern.MORNING_STAR);
            if (matchesTrend(morningStar, threeCandleBuyTrend, threeCandleSellTrend)
                    && isGeometricMorningStar(first, middle, current, threeCandleStatistics, morningStar)) {
                addSignal(signals, CandlePattern.MORNING_STAR, TradeSignal.BUY,
                        evaluateSetup(CandlePattern.MORNING_STAR, candles, last, threeCandleStart,
                                TradeSignal.BUY, threeCandleBuyTrend, threeCandleStatistics));
            }
            PatternProfile eveningStar = definitions.profile(CandlePattern.EVENING_STAR);
            if (matchesTrend(eveningStar, threeCandleBuyTrend, threeCandleSellTrend)
                    && isGeometricEveningStar(first, middle, current, threeCandleStatistics, eveningStar)) {
                addSignal(signals, CandlePattern.EVENING_STAR, TradeSignal.SELL,
                        evaluateSetup(CandlePattern.EVENING_STAR, candles, last, threeCandleStart,
                                TradeSignal.SELL, threeCandleSellTrend, threeCandleStatistics));
            }
            PatternProfile soldiers = definitions.profile(CandlePattern.THREE_WHITE_SOLDIERS);
            if (matchesTrend(soldiers, threeCandleBuyTrend, threeCandleSellTrend)
                    && isGeometricThreeWhiteSoldiers(first, middle, current, threeCandleStatistics, soldiers)) {
                addSignal(signals, CandlePattern.THREE_WHITE_SOLDIERS, TradeSignal.BUY,
                        evaluateSetup(CandlePattern.THREE_WHITE_SOLDIERS, candles, last, threeCandleStart,
                                TradeSignal.BUY, threeCandleBuyTrend, threeCandleStatistics));
            }
            PatternProfile crows = definitions.profile(CandlePattern.THREE_BLACK_CROWS);
            if (matchesTrend(crows, threeCandleBuyTrend, threeCandleSellTrend)
                    && isGeometricThreeBlackCrows(first, middle, current, threeCandleStatistics, crows)) {
                addSignal(signals, CandlePattern.THREE_BLACK_CROWS, TradeSignal.SELL,
                        evaluateSetup(CandlePattern.THREE_BLACK_CROWS, candles, last, threeCandleStart,
                                TradeSignal.SELL, threeCandleSellTrend, threeCandleStatistics));
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

    public List<DetectedSignal> detectAlertSignalsFactory(List<EnrichedCandle> recentCandles,
                                                           TimeInterval interval) {
        return detectFactory(recentCandles, interval).stream()
                .filter(signal -> signal.tradeSignal() != TradeSignal.HOLD)
                .toList();
    }

    public List<DetectedSignal> detectAlertSignals(List<EnrichedCandle> recentCandles,
                                                    TrendDetectionRules trendRules) {
        return detect(recentCandles, trendRules).stream()
                .filter(signal -> signal.tradeSignal() != TradeSignal.HOLD)
                .toList();
    }

    public List<DetectedSignal> detectAlertSignals(List<EnrichedCandle> recentCandles,
                                                    TrendDetectionRules trendRules,
                                                    PreferencesView definitions) {
        return detect(recentCandles, trendRules, definitions).stream()
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
        return assessPriorTrendForLatestPattern(
                recentCandles, patternCandleCount, TrendDetectionRules.factory());
    }

    PriorTrendAssessment assessPriorTrendForLatestPattern(List<EnrichedCandle> recentCandles,
                                                           int patternCandleCount,
                                                           TrendDetectionRules trendRules) {
        if (patternCandleCount < 1) {
            throw new IllegalArgumentException("patternCandleCount must be positive.");
        }
        if (trendRules == null) {
            throw new IllegalArgumentException("Candlestick trend-detection rules are required.");
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

        TrendContext context = trendBefore(candles, patternStartIndex, trendRules);
        return context.asAssessment();
    }

    PriorTrendAssessment assessFactoryPriorTrendForLatestPattern(
            List<EnrichedCandle> recentCandles,
            int patternCandleCount,
            TimeInterval interval,
            TradeSignal direction) {
        if (patternCandleCount < 1 || recentCandles == null || recentCandles.isEmpty()) {
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
        TrendContext context = trendBefore(candles, patternStartIndex,
                TrendDetectionRules.adaptiveFactory(interval), direction);
        return context.asAssessment();
    }

    PriorTrendAssessment assessPriorTrendForLatestPattern(
            List<EnrichedCandle> recentCandles,
            int patternCandleCount,
            TrendDetectionRules trendRules,
            TradeSignal direction) {
        if (patternCandleCount < 1 || recentCandles == null || recentCandles.isEmpty()
                || trendRules == null || direction == null) {
            return PriorTrendAssessment.none();
        }
        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        int patternStartIndex = candles.size() - patternCandleCount;
        if (patternStartIndex < 0) return PriorTrendAssessment.none();
        return trendBefore(candles, patternStartIndex, trendRules, direction).asAssessment();
    }

    /**
     * Returns geometry-only directional candidates for a prepared chronological
     * candle series. Research sweeps can calculate geometry once and then apply
     * many prior-trend configurations without re-running the expensive indicator
     * and candle-shape work. Production detection still uses {@link #detect}.
     */
    List<GeometricPatternCandidate> geometricCandidatesAt(List<EnrichedCandle> candles,
                                                           int last) {
        if (candles == null || last < 1 || last >= candles.size()) {
            return List.of();
        }
        EnrichedCandle current = candles.get(last);
        EnrichedCandle previous = candles.get(last - 1);
        if (!hasCompleteData(current) || !hasCompleteData(previous)) {
            return List.of();
        }

        List<GeometricPatternCandidate> candidates = new ArrayList<>();
        if (isGeometricHammerShape(current)) {
            candidates.add(new GeometricPatternCandidate(
                    CandlePattern.HAMMER, TradeSignal.BUY, TrendDirection.DOWN, false, 1));
            candidates.add(new GeometricPatternCandidate(
                    CandlePattern.HANGING_MAN, TradeSignal.SELL, TrendDirection.UP, false, 1));
        }
        if (isGeometricShootingStarShape(current)) {
            candidates.add(new GeometricPatternCandidate(
                    CandlePattern.SHOOTING_STAR, TradeSignal.SELL, TrendDirection.UP, false, 1));
            candidates.add(new GeometricPatternCandidate(
                    CandlePattern.INVERTED_HAMMER, TradeSignal.BUY, TrendDirection.DOWN, false, 1));
        }

        int twoCandleStart = last - 1;
        CandleStatistics twoStatistics = statisticsBefore(candles, twoCandleStart);
        if (isGeometricBullishEngulfing(previous, current)) {
            candidates.add(candidate(CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY, TrendDirection.DOWN, 2));
        }
        if (isGeometricBearishEngulfing(previous, current)) {
            candidates.add(candidate(CandlePattern.BEARISH_ENGULFING, TradeSignal.SELL, TrendDirection.UP, 2));
        }
        if (isGeometricPiercingLine(previous, current, twoStatistics)) {
            candidates.add(candidate(CandlePattern.PIERCING_LINE, TradeSignal.BUY, TrendDirection.DOWN, 2));
        }
        if (isGeometricDarkCloudCover(previous, current, twoStatistics)) {
            candidates.add(candidate(CandlePattern.DARK_CLOUD_COVER, TradeSignal.SELL, TrendDirection.UP, 2));
        }
        if (isGeometricBullishHarami(previous, current, twoStatistics)) {
            candidates.add(candidate(CandlePattern.BULLISH_HARAMI, TradeSignal.BUY, TrendDirection.DOWN, 2));
        }
        if (isGeometricBearishHarami(previous, current, twoStatistics)) {
            candidates.add(candidate(CandlePattern.BEARISH_HARAMI, TradeSignal.SELL, TrendDirection.UP, 2));
        }

        if (last >= 2) {
            int threeCandleStart = last - 2;
            EnrichedCandle first = candles.get(threeCandleStart);
            EnrichedCandle middle = candles.get(last - 1);
            CandleStatistics threeStatistics = statisticsBefore(candles, threeCandleStart);
            if (isGeometricMorningStar(first, middle, current, threeStatistics)) {
                candidates.add(candidate(CandlePattern.MORNING_STAR, TradeSignal.BUY, TrendDirection.DOWN, 3));
            }
            if (isGeometricEveningStar(first, middle, current, threeStatistics)) {
                candidates.add(candidate(CandlePattern.EVENING_STAR, TradeSignal.SELL, TrendDirection.UP, 3));
            }
            if (isGeometricThreeWhiteSoldiers(first, middle, current, threeStatistics)) {
                candidates.add(new GeometricPatternCandidate(
                        CandlePattern.THREE_WHITE_SOLDIERS,
                        TradeSignal.BUY,
                        TrendDirection.DOWN,
                        true,
                        3));
            }
            if (isGeometricThreeBlackCrows(first, middle, current, threeStatistics)) {
                candidates.add(candidate(CandlePattern.THREE_BLACK_CROWS, TradeSignal.SELL, TrendDirection.UP, 3));
            }
        }
        return List.copyOf(candidates);
    }

    PriorTrendAssessment assessPreparedPriorTrend(List<EnrichedCandle> chronologicalCandles,
                                                   int patternStartIndex,
                                                   TrendDetectionRules trendRules) {
        if (chronologicalCandles == null || chronologicalCandles.isEmpty()
                || patternStartIndex < 0 || patternStartIndex >= chronologicalCandles.size()) {
            return PriorTrendAssessment.none();
        }
        TrendContext context = trendBefore(chronologicalCandles, patternStartIndex, trendRules);
        return context.asAssessment();
    }

    private GeometricPatternCandidate candidate(CandlePattern pattern,
                                                TradeSignal tradeSignal,
                                                TrendDirection trendDirection,
                                                int patternCandleCount) {
        return new GeometricPatternCandidate(
                pattern, tradeSignal, trendDirection, false, patternCandleCount);
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
                candle.close(),
                evidence.trendStartTimestamp()
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
        int geometryPoints = (int) Math.round(rawGeometryPoints * 10.0 / 25.0);
        int trendPoints = (int) Math.round(trend.scorePoints() * 15.0 / 25.0);
        components.add(weightedComponent(
                "Pattern quality",
                geometryPoints + trendPoints,
                25,
                PATTERN_QUALITY_MAX_TENTHS,
                "all mandatory " + patternLabel(pattern)
                        + " geometry and prior-trend rules passed (geometry "
                        + rawGeometryPoints + "/25, trend " + trend.scorePoints()
                        + "/25); " + trend.description()
        ));
        components.add(trendIndicatorComponent(candles, signalIndex, direction));
        components.add(higherTimeframeComponent(candles, current.timestamp(), direction));
        components.add(momentumComponent(candles, signalIndex, direction));
        components.add(bollingerComponent(candles, setupIndex, signalIndex, direction));
        components.add(supportResistanceComponent(candles, setupIndex, signalIndex, direction));
        components.add(volumeComponent(candles, signalIndex, direction));

        Long trendStartTimestamp = trend.trendStartIndex() >= 0
                && trend.trendStartIndex() < candles.size()
                ? candles.get(trend.trendStartIndex()).timestamp()
                : null;
        return new SignalEvidence(current, components, trendStartTimestamp);
    }

    private ScoreComponent trendIndicatorComponent(List<EnrichedCandle> candles,
                                                    int signalIndex,
                                                    TradeSignal direction) {
        EnrichedCandle current = candles.get(signalIndex);
        EnrichedCandle previous = candles.get(Math.max(0, signalIndex - 1));
        TechnicalIndicatorProfile profile = indicatorProfile(candles);
        int points = 0;
        List<String> details = new ArrayList<>();

        if (isAvailable(current.fastEma()) && isAvailable(current.slowEma())) {
            boolean emaOrderAligned = directionalDelta(current.fastEma() - current.slowEma(), direction);
            if (emaOrderAligned) {
                points += 5;
            }
            details.add("EMA(" + profile.fastEmaPeriod() + ")/EMA(" + profile.slowEmaPeriod()
                    + ") order was " + alignmentLabel(emaOrderAligned));

            if (isAvailable(previous.fastEma())) {
                boolean fastSlopeAligned = directionalDelta(current.fastEma() - previous.fastEma(), direction);
                if (fastSlopeAligned) {
                    points += 3;
                }
                details.add("EMA(" + profile.fastEmaPeriod() + ") slope was "
                        + alignmentLabel(fastSlopeAligned));
            }
            if (isAvailable(previous.slowEma())) {
                boolean slowSlopeAligned = directionalDelta(current.slowEma() - previous.slowEma(), direction);
                if (slowSlopeAligned) {
                    points += 2;
                }
                details.add("EMA(" + profile.slowEmaPeriod() + ") slope was "
                        + alignmentLabel(slowSlopeAligned));
            }
        } else {
            details.add("EMA(" + profile.fastEmaPeriod() + ")/EMA(" + profile.slowEmaPeriod()
                    + ") context was unavailable");
        }

        if (isAvailable(current.longSma())) {
            boolean longTrendAligned = directionalDelta(current.close() - current.longSma(), direction);
            if (longTrendAligned) {
                points += 4;
            }
            details.add("close versus SMA(" + profile.longSmaPeriod() + ") was "
                    + alignmentLabel(longTrendAligned));
        } else {
            details.add("SMA(" + profile.longSmaPeriod() + ") context was unavailable");
        }

        if (isAvailable(current.macdLine()) && isAvailable(current.macdSignal())) {
            boolean macdAligned = directionalDelta(current.macdLine() - current.macdSignal(), direction);
            if (macdAligned) {
                points += 3;
            }
            details.add("MACD(" + profile.macdFastPeriod() + "," + profile.macdSlowPeriod()
                    + "," + profile.macdSignalPeriod() + ") line/signal was "
                    + alignmentLabel(macdAligned));
        } else {
            details.add("MACD(" + profile.macdFastPeriod() + "," + profile.macdSlowPeriod()
                    + "," + profile.macdSignalPeriod() + ") line/signal context was unavailable");
        }
        if (isAvailable(current.macdHistogram()) && isAvailable(previous.macdHistogram())) {
            boolean histogramAligned = directionalDelta(
                    current.macdHistogram() - previous.macdHistogram(),
                    direction
            );
            if (histogramAligned) {
                points += 3;
            }
            details.add("MACD(" + profile.macdFastPeriod() + "," + profile.macdSlowPeriod()
                    + "," + profile.macdSignalPeriod() + ") histogram change was "
                    + alignmentLabel(histogramAligned));
        }

        return weightedComponent(
                "Trend indicators",
                points,
                20,
                TREND_MAX_TENTHS,
                profile.shortLabel() + " profile: " + String.join("; ", details)
        );
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
                "Higher-timeframe trend",
                points,
                15,
                HIGHER_TIMEFRAME_MAX_TENTHS,
                String.join("; ", details)
        );
    }

    private ScoreComponent momentumComponent(List<EnrichedCandle> candles,
                                             int signalIndex,
                                             TradeSignal direction) {
        EnrichedCandle current = candles.get(signalIndex);
        EnrichedCandle previous = candles.get(Math.max(0, signalIndex - 1));
        TechnicalIndicatorProfile profile = indicatorProfile(candles);
        int points = 0;
        List<String> details = new ArrayList<>();

        if (isAvailable(current.rsi())) {
            int rsiLevelPoints;
            if (direction == TradeSignal.BUY) {
                rsiLevelPoints = current.rsi() <= 35.0 ? 5
                        : current.rsi() < 45.0 ? 3
                        : current.rsi() < 50.0 ? 1 : 0;
            } else {
                rsiLevelPoints = current.rsi() >= 65.0 ? 5
                        : current.rsi() > 55.0 ? 3
                        : current.rsi() > 50.0 ? 1 : 0;
            }
            points += rsiLevelPoints;
            details.add(String.format(
                    Locale.ROOT,
                    "RSI(%d) was %.1f (+%d/5 for directional reversal location)",
                    profile.rsiPeriod(),
                    current.rsi(),
                    rsiLevelPoints
            ));
            if (isAvailable(previous.rsi())) {
                boolean rsiTurnAligned = directionalDelta(current.rsi() - previous.rsi(), direction);
                if (rsiTurnAligned) {
                    points += 3;
                }
                details.add("RSI(" + profile.rsiPeriod() + ") change was "
                        + alignmentLabel(rsiTurnAligned));
            }
        } else {
            details.add("RSI(" + profile.rsiPeriod() + ") was unavailable");
        }

        if (isAvailable(current.cci())) {
            int cciLevelPoints;
            if (direction == TradeSignal.BUY) {
                cciLevelPoints = current.cci() <= -100.0 ? 4 : current.cci() <= -50.0 ? 2 : 0;
            } else {
                cciLevelPoints = current.cci() >= 100.0 ? 4 : current.cci() >= 50.0 ? 2 : 0;
            }
            points += cciLevelPoints;
            details.add(String.format(
                    Locale.ROOT,
                    "CCI(%d) was %.1f (+%d/4 for directional reversal location)",
                    profile.cciPeriod(),
                    current.cci(),
                    cciLevelPoints
            ));
            if (isAvailable(previous.cci())) {
                boolean cciTurnAligned = directionalDelta(current.cci() - previous.cci(), direction);
                if (cciTurnAligned) {
                    points += 3;
                }
                details.add("CCI(" + profile.cciPeriod() + ") change was "
                        + alignmentLabel(cciTurnAligned));
            }
        } else {
            details.add("CCI(" + profile.cciPeriod() + ") was unavailable");
        }

        return weightedComponent(
                "Momentum",
                points,
                15,
                MOMENTUM_MAX_TENTHS,
                profile.shortLabel() + " profile: " + String.join("; ", details)
        );
    }

    private ScoreComponent bollingerComponent(List<EnrichedCandle> candles,
                                              int setupIndex,
                                              int signalIndex,
                                              TradeSignal direction) {
        EnrichedCandle current = candles.get(signalIndex);
        TechnicalIndicatorProfile profile = indicatorProfile(candles);
        int points = 0;
        List<String> details = new ArrayList<>();
        boolean testedBand = false;
        for (int index = setupIndex; index <= signalIndex; index++) {
            EnrichedCandle candle = candles.get(index);
            if (direction == TradeSignal.BUY
                    && isAvailable(candle.lowerBollinger())
                    && candle.low() <= candle.lowerBollinger() * 1.005) {
                testedBand = true;
            } else if (direction == TradeSignal.SELL
                    && isAvailable(candle.upperBollinger())
                    && candle.high() >= candle.upperBollinger() * 0.995) {
                testedBand = true;
            }
        }
        if (testedBand) {
            points += 4;
            details.add(direction == TradeSignal.BUY
                    ? "the pattern tested the lower band"
                    : "the pattern tested the upper band");
        }

        if (isAvailable(current.lowerBollinger())
                && isAvailable(current.upperBollinger())
                && current.upperBollinger() > current.lowerBollinger()) {
            double percentB = (current.close() - current.lowerBollinger())
                    / (current.upperBollinger() - current.lowerBollinger());
            int locationPoints = direction == TradeSignal.BUY
                    ? percentB <= 0.20 ? 4 : percentB <= 0.35 ? 2 : 0
                    : percentB >= 0.80 ? 4 : percentB >= 0.65 ? 2 : 0;
            points += locationPoints;
            details.add(String.format(Locale.ROOT, "Bollinger %%B was %.2f (+%d/4)", percentB, locationPoints));

            boolean reentered = testedBand && (direction == TradeSignal.BUY
                    ? current.close() > current.lowerBollinger()
                    : current.close() < current.upperBollinger());
            if (reentered) {
                points += 2;
                details.add("the close moved back inside the tested band");
            }

            if (isAvailable(current.bollingerMiddle()) && current.bollingerMiddle() != 0.0) {
                double bandwidth = (current.upperBollinger() - current.lowerBollinger())
                        / Math.abs(current.bollingerMiddle()) * 100.0;
                details.add(String.format(Locale.ROOT, "bandwidth was %.2f%%", bandwidth));
            }
        } else {
            details.add("Bollinger values were unavailable");
        }

        return weightedComponent(
                "Bollinger volatility/location",
                points,
                10,
                BOLLINGER_MAX_TENTHS,
                "Bollinger(" + profile.bollingerPeriod() + ","
                        + String.format(Locale.ROOT, "%.1f", profile.bollingerDeviation())
                        + "): " + String.join("; ", details)
        );
    }

    private ScoreComponent supportResistanceComponent(List<EnrichedCandle> candles,
                                                      int setupIndex,
                                                      int signalIndex,
                                                      TradeSignal direction) {
        EnrichedCandle current = candles.get(signalIndex);
        int points = 0;
        List<String> details = new ArrayList<>();
        int referenceStart = Math.max(0, setupIndex - BODY_COMPARISON_LOOKBACK);

        if (setupIndex <= referenceStart) {
            details.add("no completed pre-pattern bars were available for a level");
        } else {
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

            double scale = isAvailable(current.atr()) && current.atr() > 0.0
                    ? current.atr()
                    : Math.max(Math.abs(current.close()) * 0.01, 0.000001);
            double level = direction == TradeSignal.BUY ? referenceLow : referenceHigh;
            double patternExtreme = direction == TradeSignal.BUY ? patternLow : patternHigh;
            double distanceInAtr = Math.abs(patternExtreme - level) / scale;
            int proximityPoints = distanceInAtr <= 0.25 ? 7
                    : distanceInAtr <= 0.50 ? 5
                    : distanceInAtr <= 1.0 ? 3 : 0;
            points += proximityPoints;
            details.add(String.format(
                    Locale.ROOT,
                    "pattern extreme was %.2f ATR-equivalents from recent %s (+%d/7)",
                    distanceInAtr,
                    direction == TradeSignal.BUY ? "support" : "resistance",
                    proximityPoints
            ));

            double touchTolerance = scale * 0.5;
            int touches = 0;
            for (int index = referenceStart; index < setupIndex; index++) {
                double candidate = direction == TradeSignal.BUY
                        ? candles.get(index).low()
                        : candles.get(index).high();
                if (Math.abs(candidate - level) <= touchTolerance) {
                    touches++;
                }
            }
            int touchPoints = touches >= 3 ? 4 : touches == 2 ? 2 : touches == 1 ? 1 : 0;
            points += touchPoints;
            details.add(touches + " prior level touch(es) (+" + touchPoints + "/4)");

            double patternRange = Math.max(patternHigh - patternLow, 0.000001);
            double rejectionFraction = direction == TradeSignal.BUY
                    ? (current.close() - patternLow) / patternRange
                    : (patternHigh - current.close()) / patternRange;
            int rejectionPoints = rejectionFraction >= 0.65 ? 4 : rejectionFraction >= 0.50 ? 2 : 0;
            points += rejectionPoints;
            details.add(String.format(
                    Locale.ROOT,
                    "close rejected %.0f%% of the pattern range from the level (+%d/4)",
                    rejectionFraction * 100.0,
                    rejectionPoints
            ));
        }

        return weightedComponent(
                "Support/resistance",
                points,
                15,
                SUPPORT_RESISTANCE_MAX_TENTHS,
                String.join("; ", details)
        );
    }

    private ScoreComponent volumeComponent(List<EnrichedCandle> candles,
                                           int signalIndex,
                                           TradeSignal direction) {
        EnrichedCandle candle = candles.get(signalIndex);
        EnrichedCandle previous = candles.get(Math.max(0, signalIndex - 1));
        TechnicalIndicatorProfile profile = indicatorProfile(candles);
        int points = 0;
        List<String> details = new ArrayList<>();
        if (!isAvailable(candle.averageVolume()) || candle.averageVolume() <= 0) {
            details.add("relative volume versus the " + profile.volumePeriod()
                    + "-bar average was unavailable");
        } else {
            double ratio = candle.volume() / candle.averageVolume();
            int relativeVolumePoints;
            if (ratio >= 1.5) {
                relativeVolumePoints = 4;
            } else if (ratio >= 1.2) {
                relativeVolumePoints = 3;
            } else if (ratio >= 1.0) {
                relativeVolumePoints = 2;
            } else {
                relativeVolumePoints = 0;
            }
            points += relativeVolumePoints;
            details.add(String.format(
                    Locale.ROOT,
                    "volume was %.2fx its %d-bar average (+%d/4)",
                    ratio,
                    profile.volumePeriod(),
                    relativeVolumePoints
            ));
        }

        if (isAvailable(candle.rollingVwap())) {
            boolean priceAligned = directionalDelta(candle.close() - candle.rollingVwap(), direction);
            if (priceAligned) {
                points += 2;
            }
            details.add("close versus rolling VWAP(" + profile.vwapPeriod() + ") was "
                    + alignmentLabel(priceAligned));

            if (isAvailable(previous.rollingVwap())) {
                boolean vwapSlopeAligned = directionalDelta(
                        candle.rollingVwap() - previous.rollingVwap(),
                        direction
                );
                if (vwapSlopeAligned) {
                    points += 1;
                }
                details.add("rolling VWAP(" + profile.vwapPeriod() + ") slope was "
                        + alignmentLabel(vwapSlopeAligned));
            }
        } else {
            details.add("rolling VWAP(" + profile.vwapPeriod() + ") was unavailable");
        }

        if (isAvailable(candle.volumeProfilePointOfControl())
                && isAvailable(candle.volumeProfileValueAreaLow())
                && isAvailable(candle.volumeProfileValueAreaHigh())) {
            boolean pointOfControlSideAligned = direction == TradeSignal.BUY
                    ? candle.close() <= candle.volumeProfilePointOfControl()
                    : candle.close() >= candle.volumeProfilePointOfControl();
            if (pointOfControlSideAligned) {
                points += 1;
            }

            double relevantBoundary = direction == TradeSignal.BUY
                    ? candle.volumeProfileValueAreaLow()
                    : candle.volumeProfileValueAreaHigh();
            double scale = isAvailable(candle.atr()) && candle.atr() > 0.0
                    ? candle.atr()
                    : Math.max(Math.abs(candle.close()) * 0.01, 0.000001);
            double boundaryDistance = Math.abs(candle.close() - relevantBoundary) / scale;
            int boundaryPoints = boundaryDistance <= 0.5 ? 2 : boundaryDistance <= 1.0 ? 1 : 0;
            points += boundaryPoints;
            details.add(String.format(
                    Locale.ROOT,
                    "%d-bar OHLCV volume-profile approximation: point-of-control side was %s; "
                            + "close was %.2f ATR-equivalents from the relevant 70%% value-area boundary (+%d/2)",
                    profile.volumeProfilePeriod(),
                    alignmentLabel(pointOfControlSideAligned),
                    boundaryDistance,
                    boundaryPoints
            ));
        } else {
            details.add(profile.volumeProfilePeriod()
                    + "-bar OHLCV volume-profile approximation was unavailable");
        }

        return weightedComponent(
                "Volume participation",
                points,
                10,
                VOLUME_MAX_TENTHS,
                profile.shortLabel() + " profile: " + String.join("; ", details)
        );
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

    private TechnicalIndicatorProfile indicatorProfile(List<EnrichedCandle> candles) {
        return TechnicalIndicatorProfile.forInterval(switch (inferBaseInterval(candles)) {
            case DAILY -> org.example.stockwatch247.model.enums.TimeInterval.DAILY;
            case WEEKLY -> org.example.stockwatch247.model.enums.TimeInterval.WEEKLY;
            case MONTHLY -> org.example.stockwatch247.model.enums.TimeInterval.MONTHLY;
        });
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
                label + " close/" + averagePeriod + "-period mean was " + alignmentLabel(priceAligned)
                        + " and " + averagePeriod + "-period mean slope was " + alignmentLabel(slopeAligned)
                        + " (+" + points + "/" + maximumPoints + ")"
        );
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
        return isGeometricDoji(candle, factoryProfile(CandlePattern.DOJI));
    }
    private boolean isGeometricDoji(EnrichedCandle candle, PatternProfile profile) {
        return body(candle) <= range(candle) * profile.fraction("maxBodyPercent");
    }
    private boolean isGeometricHammerShape(EnrichedCandle candle) {
        return isGeometricHammerShape(candle, factoryProfile(CandlePattern.HAMMER));
    }
    private boolean isGeometricHammerShape(EnrichedCandle candle, PatternProfile profile) {
        return body(candle) >= range(candle) * profile.fraction("minBodyPercent")
                && lowerShadow(candle) >= body(candle) * profile.value("minLongShadowBodyMultiple")
                && upperShadow(candle) <= body(candle) * profile.value("maxShortShadowBodyMultiple")
                && body(candle) <= range(candle) * profile.fraction("maxBodyPercent");
    }
    private boolean isGeometricShootingStarShape(EnrichedCandle candle) {
        return isGeometricShootingStarShape(candle, factoryProfile(CandlePattern.SHOOTING_STAR));
    }
    private boolean isGeometricShootingStarShape(EnrichedCandle candle, PatternProfile profile) {
        return body(candle) >= range(candle) * profile.fraction("minBodyPercent")
                && upperShadow(candle) >= body(candle) * profile.value("minLongShadowBodyMultiple")
                && lowerShadow(candle) <= body(candle) * profile.value("maxShortShadowBodyMultiple")
                && body(candle) <= range(candle) * profile.fraction("maxBodyPercent");
    }
    private boolean isGeometricBullishEngulfing(EnrichedCandle previous, EnrichedCandle current) {
        return isGeometricBullishEngulfing(previous, current, factoryProfile(CandlePattern.BULLISH_ENGULFING));
    }
    private boolean isGeometricBullishEngulfing(EnrichedCandle previous, EnrichedCandle current, PatternProfile profile) {
        return isBearish(previous)
                && isBullish(current)
                && body(previous) >= range(previous) * profile.fraction("previousMinBodyPercent")
                && body(current) >= body(previous) * profile.value("currentMinPreviousBodyMultiple")
                && body(current) >= range(current) * profile.fraction("currentMinBodyPercent")
                && current.open() <= previous.close()
                && current.close() >= previous.open();
    }

    private boolean isGeometricBearishEngulfing(EnrichedCandle previous, EnrichedCandle current) {
        return isGeometricBearishEngulfing(previous, current, factoryProfile(CandlePattern.BEARISH_ENGULFING));
    }
    private boolean isGeometricBearishEngulfing(EnrichedCandle previous, EnrichedCandle current, PatternProfile profile) {
        return isBullish(previous)
                && isBearish(current)
                && body(previous) >= range(previous) * profile.fraction("previousMinBodyPercent")
                && body(current) >= body(previous) * profile.value("currentMinPreviousBodyMultiple")
                && body(current) >= range(current) * profile.fraction("currentMinBodyPercent")
                && current.open() >= previous.close()
                && current.close() <= previous.open();
    }

    private boolean isGeometricPiercingLine(EnrichedCandle previous,
                                            EnrichedCandle current,
                                            CandleStatistics statistics) {
        return isGeometricPiercingLine(previous, current, statistics, factoryProfile(CandlePattern.PIERCING_LINE));
    }
    private boolean isGeometricPiercingLine(EnrichedCandle previous, EnrichedCandle current,
                                            CandleStatistics statistics, PatternProfile profile) {
        double requiredClose = previous.close() + body(previous) * profile.fraction("penetrationPercent");
        return isBearish(previous)
                && isBullish(current)
                && isLongBody(previous, statistics, profile, "previousMinBodyPercent", "previousMinMedianMultiple")
                && isStrongBody(current, statistics, profile)
                && current.open() < previous.close()
                && current.close() > requiredClose
                && current.close() < previous.open();
    }

    private boolean isGeometricDarkCloudCover(EnrichedCandle previous,
                                               EnrichedCandle current,
                                               CandleStatistics statistics) {
        return isGeometricDarkCloudCover(previous, current, statistics, factoryProfile(CandlePattern.DARK_CLOUD_COVER));
    }
    private boolean isGeometricDarkCloudCover(EnrichedCandle previous, EnrichedCandle current,
                                               CandleStatistics statistics, PatternProfile profile) {
        double requiredClose = previous.close() - body(previous) * profile.fraction("penetrationPercent");
        return isBullish(previous)
                && isBearish(current)
                && isLongBody(previous, statistics, profile, "previousMinBodyPercent", "previousMinMedianMultiple")
                && isStrongBody(current, statistics, profile)
                && current.open() > previous.close()
                && current.close() < requiredClose
                && current.close() > previous.open();
    }

    private boolean isGeometricBullishHarami(EnrichedCandle previous,
                                             EnrichedCandle current,
                                             CandleStatistics statistics) {
        return isGeometricBullishHarami(previous, current, statistics, factoryProfile(CandlePattern.BULLISH_HARAMI));
    }
    private boolean isGeometricBullishHarami(EnrichedCandle previous, EnrichedCandle current,
                                             CandleStatistics statistics, PatternProfile profile) {
        return isBearish(previous)
                && isBullish(current)
                && isLongBody(previous, statistics, profile, "firstMinBodyPercent", "firstMinMedianMultiple")
                && current.open() >= previous.close()
                && current.close() <= previous.open()
                && isHaramiSmallBody(previous, current, statistics, profile);
    }

    private boolean isGeometricBearishHarami(EnrichedCandle previous,
                                             EnrichedCandle current,
                                             CandleStatistics statistics) {
        return isGeometricBearishHarami(previous, current, statistics, factoryProfile(CandlePattern.BEARISH_HARAMI));
    }
    private boolean isGeometricBearishHarami(EnrichedCandle previous, EnrichedCandle current,
                                             CandleStatistics statistics, PatternProfile profile) {
        return isBullish(previous)
                && isBearish(current)
                && isLongBody(previous, statistics, profile, "firstMinBodyPercent", "firstMinMedianMultiple")
                && current.open() <= previous.close()
                && current.close() >= previous.open()
                && isHaramiSmallBody(previous, current, statistics, profile);
    }

    private boolean isGeometricMorningStar(EnrichedCandle first,
                                           EnrichedCandle middle,
                                           EnrichedCandle current,
                                           CandleStatistics statistics) {
        return isGeometricMorningStar(first, middle, current, statistics, factoryProfile(CandlePattern.MORNING_STAR));
    }
    private boolean isGeometricMorningStar(EnrichedCandle first, EnrichedCandle middle,
                                           EnrichedCandle current, CandleStatistics statistics, PatternProfile profile) {
        return isBearish(first)
                && isBullish(current)
                && isLongBody(first, statistics, profile, "firstMinBodyPercent", "firstMinMedianMultiple")
                && isSmallBody(middle, statistics, profile)
                && isStrongBody(current, statistics, profile)
                && current.close() > first.close() + body(first) * profile.fraction("penetrationPercent");
    }

    private boolean isGeometricEveningStar(EnrichedCandle first,
                                           EnrichedCandle middle,
                                           EnrichedCandle current,
                                           CandleStatistics statistics) {
        return isGeometricEveningStar(first, middle, current, statistics, factoryProfile(CandlePattern.EVENING_STAR));
    }
    private boolean isGeometricEveningStar(EnrichedCandle first, EnrichedCandle middle,
                                           EnrichedCandle current, CandleStatistics statistics, PatternProfile profile) {
        return isBullish(first)
                && isBearish(current)
                && isLongBody(first, statistics, profile, "firstMinBodyPercent", "firstMinMedianMultiple")
                && isSmallBody(middle, statistics, profile)
                && isStrongBody(current, statistics, profile)
                && current.close() < first.close() - body(first) * profile.fraction("penetrationPercent");
    }

    private boolean isGeometricThreeWhiteSoldiers(EnrichedCandle first,
                                                  EnrichedCandle second,
                                                  EnrichedCandle third,
                                                  CandleStatistics statistics) {
        return isGeometricThreeWhiteSoldiers(first, second, third, statistics,
                factoryProfile(CandlePattern.THREE_WHITE_SOLDIERS));
    }
    private boolean isGeometricThreeWhiteSoldiers(EnrichedCandle first, EnrichedCandle second,
                                                  EnrichedCandle third, CandleStatistics statistics,
                                                  PatternProfile profile) {
        return isBullish(first)
                && isBullish(second)
                && isBullish(third)
                && isRelativelyLongDirectionalBody(first, statistics, profile)
                && isRelativelyLongDirectionalBody(second, statistics, profile)
                && isRelativelyLongDirectionalBody(third, statistics, profile)
                && second.close() > first.close()
                && third.close() > second.close()
                && opensWithin(first, second)
                && opensWithin(second, third)
                && upperShadow(first) <= body(first) * profile.value("maxDirectionalShadowBodyMultiple")
                && upperShadow(second) <= body(second) * profile.value("maxDirectionalShadowBodyMultiple")
                && upperShadow(third) <= body(third) * profile.value("maxDirectionalShadowBodyMultiple");
    }

    private boolean isGeometricThreeBlackCrows(EnrichedCandle first,
                                               EnrichedCandle second,
                                               EnrichedCandle third,
                                               CandleStatistics statistics) {
        return isGeometricThreeBlackCrows(first, second, third, statistics,
                factoryProfile(CandlePattern.THREE_BLACK_CROWS));
    }
    private boolean isGeometricThreeBlackCrows(EnrichedCandle first, EnrichedCandle second,
                                               EnrichedCandle third, CandleStatistics statistics,
                                               PatternProfile profile) {
        return isBearish(first)
                && isBearish(second)
                && isBearish(third)
                && isRelativelyLongDirectionalBody(first, statistics, profile)
                && isRelativelyLongDirectionalBody(second, statistics, profile)
                && isRelativelyLongDirectionalBody(third, statistics, profile)
                && second.close() < first.close()
                && third.close() < second.close()
                && opensWithin(first, second)
                && opensWithin(second, third)
                && lowerShadow(first) <= body(first) * profile.value("maxDirectionalShadowBodyMultiple")
                && lowerShadow(second) <= body(second) * profile.value("maxDirectionalShadowBodyMultiple")
                && lowerShadow(third) <= body(third) * profile.value("maxDirectionalShadowBodyMultiple");
    }

    private boolean isLongBody(EnrichedCandle candle, CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * 0.55
                && body(candle) >= statistics.medianBody() * 1.1;
    }

    private boolean isLongBody(EnrichedCandle candle, CandleStatistics statistics, PatternProfile profile,
                               String percentKey, String medianKey) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * profile.fraction(percentKey)
                && body(candle) >= statistics.medianBody() * profile.value(medianKey);
    }

    private boolean isStrongBody(EnrichedCandle candle, CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * 0.5
                && body(candle) >= statistics.medianBody() * 0.9;
    }
    private boolean isStrongBody(EnrichedCandle candle, CandleStatistics statistics, PatternProfile profile) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * profile.fraction("currentMinBodyPercent")
                && body(candle) >= statistics.medianBody() * profile.value("currentMinMedianMultiple");
    }

    private boolean isSmallBody(EnrichedCandle candle, CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) <= range(candle) * 0.3
                && body(candle) <= statistics.medianBody() * 0.75;
    }
    private boolean isSmallBody(EnrichedCandle candle, CandleStatistics statistics, PatternProfile profile) {
        return statistics.hasEnoughData()
                && body(candle) <= range(candle) * profile.fraction("middleMaxBodyPercent")
                && body(candle) <= statistics.medianBody() * profile.value("middleMaxMedianMultiple");
    }

    private boolean isHaramiSmallBody(EnrichedCandle first,
                                      EnrichedCandle second,
                                      CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(second) <= body(first) * 0.45
                && body(second) <= statistics.medianBody() * 0.75;
    }
    private boolean isHaramiSmallBody(EnrichedCandle first, EnrichedCandle second,
                                      CandleStatistics statistics, PatternProfile profile) {
        return statistics.hasEnoughData()
                && body(second) <= body(first) * profile.fraction("secondMaxFirstBodyPercent")
                && body(second) <= statistics.medianBody() * profile.value("secondMaxMedianMultiple");
    }

    private boolean isRelativelyLongDirectionalBody(EnrichedCandle candle,
                                                    CandleStatistics statistics) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * 0.5
                && body(candle) >= statistics.medianBody() * 0.8;
    }
    private boolean isRelativelyLongDirectionalBody(EnrichedCandle candle, CandleStatistics statistics,
                                                    PatternProfile profile) {
        return statistics.hasEnoughData()
                && body(candle) >= range(candle) * profile.fraction("candleMinBodyPercent")
                && body(candle) >= statistics.medianBody() * profile.value("candleMinMedianMultiple");
    }

    private static PatternProfile factoryProfile(CandlePattern pattern) {
        return CandlestickPatternPreferencesService.factoryPreferences().profile(pattern);
    }

    private static boolean matchesTrend(PatternProfile profile, TrendContext buyTrend, TrendContext sellTrend) {
        return switch (profile.trendRequirement()) {
            case NONE -> true;
            case DOWN -> buyTrend.direction() == TrendDirection.DOWN;
            case UP -> sellTrend.direction() == TrendDirection.UP;
            case DOWN_OR_BASE -> buyTrend.direction() == TrendDirection.DOWN || buyTrend.direction() == TrendDirection.BASE;
        };
    }

    private static String formatThreshold(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private TrendContext trendBefore(List<EnrichedCandle> candles,
                                     int patternStartIndex,
                                     TrendDetectionRules trendRules) {
        int endIndex = patternStartIndex - 1;
        if (endIndex < 0) {
            return TrendContext.none(0, trendRules.minimumCandles());
        }
        int availableCandles = endIndex + 1;
        int maximumWindow = Math.min(availableCandles, trendRules.lookbackCandles());
        if (maximumWindow < trendRules.minimumCandles()) {
            return TrendContext.none(maximumWindow, trendRules.minimumCandles());
        }

        return trendBeforeExactWindow(
                candles,
                endIndex,
                maximumWindow,
                trendRules.minimumMovePercent(),
                Math.min(trendRules.minimumCandles(), maximumWindow - 1),
                trendRules.terminalMedianDistanceAtr());
    }

    private TrendContext trendBefore(List<EnrichedCandle> candles,
                                     int patternStartIndex,
                                     TrendDetectionRules trendRules,
                                     TradeSignal direction) {
        if (!trendRules.adaptiveFactory()) {
            return trendBefore(candles, patternStartIndex, trendRules);
        }
        CandlestickAdaptiveTrendService.TrendModelParameters parameters =
                factoryAdaptiveParameters(trendRules, direction);
        if (parameters == null) {
            return trendBefore(candles, patternStartIndex, trendRules);
        }
        CandlestickAdaptiveTrendService.TrendAssessment assessment =
                adaptiveTrendService.assess(candles, patternStartIndex, parameters);
        boolean adaptiveDirectional = isDirectionalTrend(assessment.direction());
        if (trendRules.directionalParticipationEnabled()) {
            TrendContext originalRule = trendBefore(
                    candles,
                    patternStartIndex,
                    new TrendDetectionRules(
                            trendRules.minimumCandles(),
                            trendRules.lookbackCandles(),
                            trendRules.minimumMovePercent()));
            TrendDirection combinedDirection = combineIndependentTrendDirections(
                    assessment.direction(), originalRule.direction());
            boolean conflict = adaptiveDirectional
                    && isDirectionalTrend(originalRule.direction())
                    && assessment.direction() != originalRule.direction();
            boolean useAdaptiveLeg = combinedDirection == assessment.direction()
                    && adaptiveDirectional;
            return new TrendContext(
                    combinedDirection,
                    isDirectionalTrend(combinedDirection) ? 20 : 0,
                    assessment.description() + "; independent original move/count model: "
                            + originalRule.description() + "; conflict-aware OR result: "
                            + (conflict ? "rejected because the models identified opposite directions"
                            : isDirectionalTrend(combinedDirection)
                                    ? "accepted " + combinedDirection.name().toLowerCase(Locale.ROOT)
                                            + " because at least one model identified it"
                                    : "neither model identified a directional trend"),
                    useAdaptiveLeg
                            ? assessment.structure().trendStartIndex()
                            : originalRule.trendStartIndex(),
                    useAdaptiveLeg
                            ? assessment.structure().trendEndIndex()
                            : originalRule.trendEndIndex());
        }
        return new TrendContext(
                assessment.direction(),
                adaptiveDirectional ? 20 : 0,
                assessment.description(),
                assessment.structure().trendStartIndex(),
                assessment.structure().trendEndIndex());
    }

    TrendDirection combineIndependentTrendDirections(TrendDirection adaptive,
                                                      TrendDirection original) {
        boolean adaptiveDirectional = isDirectionalTrend(adaptive);
        boolean originalDirectional = isDirectionalTrend(original);
        if (adaptiveDirectional && originalDirectional) {
            return adaptive == original ? adaptive : TrendDirection.SIDEWAYS;
        }
        if (adaptiveDirectional) return adaptive;
        if (originalDirectional) return original;
        if (adaptive == TrendDirection.BASE || original == TrendDirection.BASE) {
            return TrendDirection.BASE;
        }
        return TrendDirection.SIDEWAYS;
    }

    private boolean isDirectionalTrend(TrendDirection direction) {
        return direction == TrendDirection.UP || direction == TrendDirection.DOWN;
    }

    private CandlestickAdaptiveTrendService.TrendModelParameters factoryAdaptiveParameters(
            TrendDetectionRules trendRules,
            TradeSignal direction) {
        TimeInterval interval = trendRules.interval();
        if (interval == null || direction == null) {
            return null;
        }
        if (interval == TimeInterval.DAILY
                || interval == TimeInterval.WEEKLY
                || interval == TimeInterval.MONTHLY) {
            return new CandlestickAdaptiveTrendService.TrendModelParameters(
                    CandlestickAdaptiveTrendService.TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                    new CandlestickAdaptiveTrendService.SwingParameters(
                            2, 2, 30, 0.0, 0.25, trendRules.terminalMedianDistanceAtr()),
                    new CandlestickAdaptiveTrendService.RegressionParameters(25, 0.15, 0.20));
        }
        return null;
    }

    private TrendContext trendBeforeExactWindow(List<EnrichedCandle> candles,
                                                int endIndex,
                                                int candleCount,
                                                double minimumMovePercent,
                                                int requiredDirectionalTransitions,
                                                double terminalMedianDistanceAtr) {
        int startIndex = endIndex - candleCount + 1;

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
        int requiredTransitions = Math.max(1, requiredDirectionalTransitions);
        double netMove = last.close() - first.close();
        double minimumMove = Math.max(
                Math.abs(first.close()) * minimumMovePercent / 100.0,
                0.000001);
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
            TrendContext context = trendContext(TrendDirection.UP, candleCount, transitions,
                    higherCloses, higherHighAndLow, netMove, minimumMove, emaUp, first.close());
            return applyFixedTerminalMedianGate(
                    context, candles, startIndex, endIndex, terminalMedianDistanceAtr);
        }
        if (-netMove >= minimumMove && downwardSequence) {
            TrendContext context = trendContext(TrendDirection.DOWN, candleCount, transitions,
                    lowerCloses, lowerHighAndLow, -netMove, minimumMove, emaDown, first.close());
            return applyFixedTerminalMedianGate(
                    context, candles, startIndex, endIndex, terminalMedianDistanceAtr);
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
                                    : (contextHigh - contextLow) / Math.abs(first.close()) * 100.0),
                    startIndex,
                    endIndex
            );
        }
        return TrendContext.none(candleCount, candleCount);
    }

    private TrendContext applyFixedTerminalMedianGate(TrendContext context,
                                                      List<EnrichedCandle> candles,
                                                      int startIndex,
                                                      int endIndex,
                                                      double minimumDistanceAtr) {
        if (minimumDistanceAtr < 0.0) {
            return new TrendContext(context.direction(), context.scorePoints(), context.description(),
                    startIndex, endIndex);
        }
        List<Double> closes = new ArrayList<>();
        List<Double> atrValues = new ArrayList<>();
        for (int index = startIndex; index <= endIndex; index++) {
            EnrichedCandle candle = candles.get(index);
            closes.add(candle.close());
            double atr = candle.atr();
            if (Double.isFinite(atr) && atr > 0.0) atrValues.add(atr);
        }
        if (closes.isEmpty() || atrValues.isEmpty()) {
            return new TrendContext(TrendDirection.SIDEWAYS, 0,
                    context.description() + "; terminal-position check failed because ATR was unavailable",
                    startIndex, endIndex);
        }
        double medianClose = median(closes);
        double medianAtr = median(atrValues);
        double terminalClose = candles.get(endIndex).close();
        double margin = minimumDistanceAtr * medianAtr;
        boolean accepted = context.direction() == TrendDirection.UP
                ? terminalClose >= medianClose + margin
                : terminalClose <= medianClose - margin;
        String comparison = context.direction() == TrendDirection.UP ? "above" : "below";
        String detail = String.format(Locale.ROOT,
                "terminal-position check %s: final completed pre-pattern close %.4f must be at least %.2f ATR %s the trend-leg median close %.4f",
                accepted ? "passed" : "failed", terminalClose, minimumDistanceAtr,
                comparison, medianClose);
        return new TrendContext(
                accepted ? context.direction() : TrendDirection.SIDEWAYS,
                accepted ? context.scorePoints() : 0,
                context.description() + "; " + detail,
                startIndex,
                endIndex);
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
        return new TrendContext(direction, points, description, -1, -1);
    }

    private boolean emaAligned(List<EnrichedCandle> candles,
                               int contextIndex,
                               TrendDirection direction) {
        EnrichedCandle context = candles.get(contextIndex);
        if (!isAvailable(context.fastEma())) {
            return false;
        }
        boolean positionAligned = direction == TrendDirection.UP
                ? context.close() > context.fastEma()
                : context.close() < context.fastEma();
        if (!positionAligned || contextIndex == 0
                || !isAvailable(candles.get(contextIndex - 1).fastEma())) {
            return positionAligned;
        }
        return direction == TrendDirection.UP
                ? context.fastEma() > candles.get(contextIndex - 1).fastEma()
                : context.fastEma() < candles.get(contextIndex - 1).fastEma();
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
            Double closePrice,
            int eligibilityScore,
            Long trendStartTimestamp
    ) {
        public DetectedSignal {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public DetectedSignal(CandlePattern pattern,
                              TradeSignal tradeSignal,
                              SignalStength strength,
                              int confidenceScore,
                              List<String> reasons,
                              Long candleTimestamp,
                              Double closePrice) {
            this(pattern, tradeSignal, strength, confidenceScore, reasons, candleTimestamp, closePrice,
                    confidenceScore, null);
        }

        public DetectedSignal(CandlePattern pattern,
                              TradeSignal tradeSignal,
                              SignalStength strength,
                              int confidenceScore,
                              List<String> reasons,
                              Long candleTimestamp,
                              Double closePrice,
                              Long trendStartTimestamp) {
            this(pattern, tradeSignal, strength, confidenceScore, reasons, candleTimestamp, closePrice,
                    confidenceScore, trendStartTimestamp);
        }

        public DetectedSignal(CandlePattern pattern,
                              TradeSignal tradeSignal,
                              SignalStength strength,
                              int confidenceScore,
                              List<String> reasons,
                              Long candleTimestamp,
                              Double closePrice,
                              int eligibilityScore) {
            this(pattern, tradeSignal, strength, confidenceScore, reasons, candleTimestamp, closePrice,
                    eligibilityScore, null);
        }

        public DetectedSignal(CandlePattern pattern, TradeSignal tradeSignal, Long candleTimestamp, Double closePrice) {
            this(pattern, tradeSignal, SignalStength.LOW_CONFIDENCE, 0, List.of(), candleTimestamp,
                    closePrice, 0, null);
        }

        /**
         * Preferred semantic name. confidenceScore remains the persisted/API
         * compatibility accessor used by existing Elliott-wave code.
         */
        public int setupScore() {
            return confidenceScore;
        }
    }

    public record TrendDetectionRules(int minimumCandles,
                                      int lookbackCandles,
                                      double minimumMovePercent,
                                      TimeInterval interval,
                                      boolean adaptiveFactory,
                                      double terminalMedianDistanceAtr,
                                      boolean directionalParticipationEnabled) {
        public TrendDetectionRules(int minimumCandles,
                                   int lookbackCandles,
                                   double minimumMovePercent) {
            this(minimumCandles, lookbackCandles, minimumMovePercent,
                    null, false, -1.0, false);
        }

        public TrendDetectionRules(int minimumCandles,
                                   int lookbackCandles,
                                   double minimumMovePercent,
                                   double terminalMedianDistanceAtr) {
            this(minimumCandles, lookbackCandles, minimumMovePercent,
                    null, false, terminalMedianDistanceAtr, false);
        }

        public TrendDetectionRules {
            validate(minimumCandles, lookbackCandles, minimumMovePercent);
            if (adaptiveFactory && interval != TimeInterval.DAILY
                    && interval != TimeInterval.WEEKLY
                    && interval != TimeInterval.MONTHLY) {
                throw new IllegalArgumentException(
                        "Adaptive factory trend detection requires a daily, weekly, or monthly interval.");
            }
            if (adaptiveFactory && (!Double.isFinite(terminalMedianDistanceAtr)
                    || terminalMedianDistanceAtr < 0.0 || terminalMedianDistanceAtr > 10.0)) {
                throw new IllegalArgumentException(
                        "Adaptive terminal median distance must be between 0 and 10 ATR.");
            }
            if (!adaptiveFactory && (!Double.isFinite(terminalMedianDistanceAtr)
                    || terminalMedianDistanceAtr < -1.0 || terminalMedianDistanceAtr > 10.0)) {
                throw new IllegalArgumentException(
                        "Terminal median distance must be disabled (-1) or between 0 and 10 ATR.");
            }
            if (adaptiveFactory && directionalParticipationEnabled) {
                new CandlestickAdaptiveTrendService.DirectionalParticipationParameters(
                        minimumCandles, lookbackCandles, minimumMovePercent).validate();
            }
        }

        public static TrendDetectionRules factory() {
            return new TrendDetectionRules(
                    MIN_TREND_CANDLES,
                    TREND_LOOKBACK,
                    MIN_TREND_MOVE_PERCENT);
        }

        public static TrendDetectionRules adaptiveFactory(TimeInterval interval) {
            double terminalMedianDistanceAtr = interval == TimeInterval.DAILY ? 0.25 : 0.0;
            return adaptiveFactory(interval, terminalMedianDistanceAtr);
        }

        public static TrendDetectionRules adaptiveFactory(TimeInterval interval,
                                                          double terminalMedianDistanceAtr) {
            return switch (interval) {
                case DAILY -> new TrendDetectionRules(
                        4, 6, 3.0, interval, true, terminalMedianDistanceAtr, true);
                case WEEKLY -> new TrendDetectionRules(
                        4, 6, 3.0, interval, true, terminalMedianDistanceAtr, true);
                case MONTHLY -> new TrendDetectionRules(
                        4, 6, 3.0, interval, true, terminalMedianDistanceAtr, true);
                default -> throw new IllegalArgumentException(
                        "Only daily, weekly, and monthly factory trend models are supported.");
            };
        }

        public static TrendDetectionRules adaptiveFactory(
                TimeInterval interval,
                double terminalMedianDistanceAtr,
                boolean directionalParticipationEnabled,
                int minimumDirectionalTransitions,
                int participationWindowCandles,
                double minimumDirectionalMovePercent) {
            return new TrendDetectionRules(
                    minimumDirectionalTransitions, participationWindowCandles,
                    minimumDirectionalMovePercent, interval, true,
                    terminalMedianDistanceAtr, directionalParticipationEnabled);
        }

        private void validate() {
            validate(minimumCandles, lookbackCandles, minimumMovePercent);
        }

        private static void validate(int minimumCandles,
                                     int lookbackCandles,
                                     double minimumMovePercent) {
            if (minimumCandles < 2 || minimumCandles > 100) {
                throw new IllegalArgumentException("Minimum trend candles must be between 2 and 100.");
            }
            if (lookbackCandles < 2 || lookbackCandles > 100) {
                throw new IllegalArgumentException("Trend lookback candles must be between 2 and 100.");
            }
            if (minimumCandles > lookbackCandles) {
                throw new IllegalArgumentException("Minimum trend candles cannot exceed the trend lookback.");
            }
            if (!Double.isFinite(minimumMovePercent)
                    || minimumMovePercent < 0.0 || minimumMovePercent > 50.0) {
                throw new IllegalArgumentException("Minimum trend move must be between 0% and 50%.");
            }
        }
    }

    private record SignalEvidence(EnrichedCandle candle,
                                  List<ScoreComponent> components,
                                  Long trendStartTimestamp) {
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

    private record TrendContext(TrendDirection direction,
                                int scorePoints,
                                String description,
                                int trendStartIndex,
                                int trendEndIndex) {
        private static TrendContext none(int candleCount, int minimumCandles) {
            return new TrendContext(
                    TrendDirection.SIDEWAYS,
                    0,
                    candleCount < minimumCandles
                            ? String.format(Locale.ROOT,
                            "fewer than %d completed pre-pattern candles were available",
                            minimumCandles)
                            : "no established directional trend was present before the pattern",
                    -1,
                    -1
            );
        }

        private PriorTrendAssessment asAssessment() {
            return new PriorTrendAssessment(
                    direction, scorePoints, description, trendStartIndex, trendEndIndex);
        }
    }

    record PriorTrendAssessment(TrendDirection direction,
                                int scorePoints,
                                String description,
                                int trendStartIndex,
                                int trendEndIndex) {
        PriorTrendAssessment(TrendDirection direction, int scorePoints, String description) {
            this(direction, scorePoints, description, -1, -1);
        }

        private static PriorTrendAssessment none() {
            return new PriorTrendAssessment(
                    TrendDirection.SIDEWAYS, 0, "no prior trend was available", -1, -1);
        }
    }

    record GeometricPatternCandidate(CandlePattern pattern,
                                     TradeSignal tradeSignal,
                                     TrendDirection requiredTrend,
                                     boolean acceptsBase,
                                     int patternCandleCount) {
        boolean accepts(PriorTrendAssessment assessment) {
            return assessment != null
                    && (assessment.direction() == requiredTrend
                    || acceptsBase && assessment.direction() == TrendDirection.BASE);
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
