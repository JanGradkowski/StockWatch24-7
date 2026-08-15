package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.service.CandlePatternDetectionService.PriorTrendAssessment;
import org.example.stockwatch247.service.CandlePatternDetectionService.TrendDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Causal price-trend research classifier. Swing pivots are used only after all
 * right-side confirmation candles have completed, and regression consumes only
 * candles completed before the pattern begins.
 */
final class CandlestickAdaptiveTrendService {

    TrendAssessment assess(List<EnrichedCandle> candles,
                           int patternStartIndex,
                           TrendModelParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("Adaptive trend parameters are required.");
        }
        parameters.validate();
        StructureEvidence structure = parameters.structure() == null
                ? StructureEvidence.unavailable("market structure is not used by this model")
                : assessStructure(candles, patternStartIndex, parameters.structure());
        RegressionEvidence regression = parameters.regression() == null
                ? RegressionEvidence.unavailable("regression is not used by this model")
                : assessRegression(candles, patternStartIndex, parameters.regression());
        TrendAssessment combined = combine(parameters.policy(), structure, regression);
        if (parameters.participation() == null || !isDirectional(combined.direction())) {
            return combined;
        }
        DirectionalParticipationEvidence participation = assessDirectionalParticipation(
                candles,
                structure.trendStartIndex(),
                structure.trendEndIndex(),
                combined.direction(),
                parameters.participation());
        return new TrendAssessment(
                participation.accepted() ? combined.direction() : TrendDirection.SIDEWAYS,
                combined.policy(),
                combined.structure(),
                combined.regression(),
                combined.description() + "; " + participation.description());
    }

    private DirectionalParticipationEvidence assessDirectionalParticipation(
            List<EnrichedCandle> candles,
            int trendStartIndex,
            int trendEndIndex,
            TrendDirection direction,
            DirectionalParticipationParameters parameters) {
        parameters.validate();
        if (!isDirectional(direction) || trendStartIndex < 0 || trendEndIndex < trendStartIndex) {
            return new DirectionalParticipationEvidence(false,
                    "directional-participation check failed because no exact directional trend leg was available");
        }
        int end = Math.min(trendEndIndex, candles.size() - 1);
        int start = Math.max(trendStartIndex, end - parameters.windowCandles() + 1);
        int availableCandles = end - start + 1;
        if (availableCandles < parameters.windowCandles()) {
            return new DirectionalParticipationEvidence(false, String.format(Locale.ROOT,
                    "directional-participation check failed because the exact trend leg supplied only %d of %d required candles",
                    availableCandles, parameters.windowCandles()));
        }
        int directionalCloses = 0;
        int directionalStructures = 0;
        for (int index = start + 1; index <= end; index++) {
            EnrichedCandle previous = candles.get(index - 1);
            EnrichedCandle current = candles.get(index);
            if (!hasPrices(previous) || !hasPrices(current)) continue;
            if (direction == TrendDirection.UP) {
                if (current.close() > previous.close()) directionalCloses++;
                if (current.high() > previous.high() && current.low() > previous.low()) {
                    directionalStructures++;
                }
            } else {
                if (current.close() < previous.close()) directionalCloses++;
                if (current.high() < previous.high() && current.low() < previous.low()) {
                    directionalStructures++;
                }
            }
        }
        double firstClose = candles.get(start).close();
        double terminalClose = candles.get(end).close();
        double directionalMovePercent = firstClose > 0.0
                ? (direction == TrendDirection.UP
                        ? terminalClose - firstClose : firstClose - terminalClose)
                        / firstClose * 100.0
                : Double.NaN;
        boolean countAccepted = Math.max(directionalCloses, directionalStructures)
                >= parameters.minimumDirectionalTransitions();
        boolean moveAccepted = Double.isFinite(directionalMovePercent)
                && directionalMovePercent >= parameters.minimumDirectionalMovePercent();
        boolean accepted = countAccepted && moveAccepted;
        return new DirectionalParticipationEvidence(accepted, String.format(Locale.ROOT,
                "directional-participation check %s across the latest %d candles of the exact trend leg: %d directional closes, %d directional high/low transitions, requires %d; directional close move %.2f%%, requires %.2f%%",
                accepted ? "passed" : "failed",
                parameters.windowCandles(), directionalCloses, directionalStructures,
                parameters.minimumDirectionalTransitions(), directionalMovePercent,
                parameters.minimumDirectionalMovePercent()));
    }

    StructureEvidence assessStructure(List<EnrichedCandle> candles,
                                      int patternStartIndex,
                                      SwingParameters parameters) {
        parameters.validate();
        int endIndex = patternStartIndex - 1;
        if (candles == null || endIndex < 0 || endIndex >= candles.size()) {
            return StructureEvidence.unavailable("no completed pre-pattern candles were available");
        }
        int latestPivotCenter = endIndex - parameters.rightConfirmationBars();
        if (latestPivotCenter < parameters.leftBars()) {
            return StructureEvidence.unavailable("insufficient candles for confirmed swing pivots");
        }
        int earliestPivotCenter = Math.max(
                parameters.leftBars(),
                endIndex - parameters.maximumLookbackBars() + 1);
        List<Pivot> highs = new ArrayList<>();
        List<Pivot> lows = new ArrayList<>();
        for (int center = earliestPivotCenter; center <= latestPivotCenter; center++) {
            EnrichedCandle candle = candles.get(center);
            if (!hasPrices(candle)) continue;
            if (isPivotHigh(candles, center, parameters)
                    && prominenceAtr(candles, center, true, parameters) >= parameters.minimumProminenceAtr()) {
                highs.add(new Pivot(center, candle.timestamp(), candle.high()));
            }
            if (isPivotLow(candles, center, parameters)
                    && prominenceAtr(candles, center, false, parameters) >= parameters.minimumProminenceAtr()) {
                lows.add(new Pivot(center, candle.timestamp(), candle.low()));
            }
        }
        if (highs.size() < 2 || lows.size() < 2) {
            return new StructureEvidence(
                    TrendDirection.SIDEWAYS,
                    highs.size(),
                    lows.size(),
                    latestTimestamp(highs, lows),
                    "fewer than two confirmed pivot highs or pivot lows were available");
        }
        Pivot previousHigh = highs.get(highs.size() - 2);
        Pivot latestHigh = highs.getLast();
        Pivot previousLow = lows.get(lows.size() - 2);
        Pivot latestLow = lows.getLast();
        double highDisplacementAtr = displacementAtr(
                candles, previousHigh, latestHigh);
        double lowDisplacementAtr = displacementAtr(
                candles, previousLow, latestLow);
        boolean meaningfulHighMove = highDisplacementAtr >= parameters.minimumSwingDisplacementAtr();
        boolean meaningfulLowMove = lowDisplacementAtr >= parameters.minimumSwingDisplacementAtr();
        boolean higherHigh = latestHigh.price() > previousHigh.price() && meaningfulHighMove;
        boolean higherLow = latestLow.price() > previousLow.price() && meaningfulLowMove;
        boolean lowerHigh = latestHigh.price() < previousHigh.price() && meaningfulHighMove;
        boolean lowerLow = latestLow.price() < previousLow.price() && meaningfulLowMove;
        TrendDirection structuralDirection = higherHigh && higherLow
                ? TrendDirection.UP
                : lowerHigh && lowerLow ? TrendDirection.DOWN : TrendDirection.SIDEWAYS;
        StructureContinuity continuity = assessStructureContinuity(
                candles, endIndex, latestHigh, latestLow, structuralDirection);
        int trendStartIndex = Math.min(previousHigh.index(), previousLow.index());
        TerminalPosition terminalPosition = assessTerminalPosition(
                candles, trendStartIndex, endIndex, structuralDirection,
                parameters.minimumTerminalMedianDistanceAtr());
        TrendDirection direction = continuity.intact() && terminalPosition.accepted()
                ? structuralDirection : TrendDirection.SIDEWAYS;
        String description = String.format(Locale.ROOT,
                "%s from confirmed swings: highs %.4f -> %.4f (%.2f ATR) and lows %.4f -> %.4f (%.2f ATR); minimum swing displacement %.2f ATR; %s; %s; newest pivot required %d completed right-side confirmation candle%s",
                directionLabel(direction), previousHigh.price(), latestHigh.price(),
                highDisplacementAtr, previousLow.price(), latestLow.price(), lowDisplacementAtr,
                parameters.minimumSwingDisplacementAtr(), continuity.description(), terminalPosition.description(),
                parameters.rightConfirmationBars(),
                parameters.rightConfirmationBars() == 1 ? "" : "s");
        return new StructureEvidence(
                direction, highs.size(), lows.size(),
                Math.max(latestHigh.timestamp(), latestLow.timestamp()),
                trendStartIndex, endIndex, description);
    }

    private TerminalPosition assessTerminalPosition(List<EnrichedCandle> candles,
                                                     int trendStartIndex,
                                                     int trendEndIndex,
                                                     TrendDirection direction,
                                                     double minimumDistanceAtr) {
        if (!isDirectional(direction)) {
            return new TerminalPosition(true,
                    "no directional structure required a terminal-position check");
        }
        if (minimumDistanceAtr < 0.0) {
            return new TerminalPosition(true, "terminal-position check was disabled");
        }
        int start = Math.max(0, trendStartIndex);
        int end = Math.min(candles.size() - 1, trendEndIndex);
        if (start > end || !hasPrices(candles.get(end))) {
            return new TerminalPosition(false,
                    "terminal-position check failed because the identified trend leg was unavailable");
        }
        double[] closes = new double[end - start + 1];
        double[] atrValues = new double[end - start + 1];
        int closeCount = 0;
        int atrCount = 0;
        for (int index = start; index <= end; index++) {
            EnrichedCandle candle = candles.get(index);
            if (!hasPrices(candle)) continue;
            closes[closeCount++] = candle.close();
            double candleAtr = atr(candles, index);
            if (Double.isFinite(candleAtr) && candleAtr > 0.0) {
                atrValues[atrCount++] = candleAtr;
            }
        }
        if (closeCount == 0 || atrCount == 0) {
            return new TerminalPosition(false,
                    "terminal-position check failed because close or ATR data was unavailable");
        }
        double medianClose = median(java.util.Arrays.copyOf(closes, closeCount));
        double medianAtr = median(java.util.Arrays.copyOf(atrValues, atrCount));
        double terminalClose = candles.get(end).close();
        double margin = minimumDistanceAtr * medianAtr;
        boolean accepted = direction == TrendDirection.UP
                ? terminalClose >= medianClose + margin
                : terminalClose <= medianClose - margin;
        String comparison = direction == TrendDirection.UP ? "above" : "below";
        String status = accepted ? "passed" : "failed";
        return new TerminalPosition(accepted, String.format(Locale.ROOT,
                "terminal-position check %s: final completed pre-pattern close %.4f must be at least %.2f ATR %s the identified trend-leg median close %.4f (median ATR %.4f)",
                status, terminalClose, minimumDistanceAtr, comparison, medianClose, medianAtr));
    }

    /**
     * A pair of historical lower highs/lows (or higher highs/lows) is not
     * sufficient when price has already reversed through the latest structural
     * boundary before the pattern begins. Only completed pre-pattern closes are
     * inspected, which keeps this gate causal and prevents stale V-shaped trend
     * legs from qualifying reversal patterns.
     */
    private StructureContinuity assessStructureContinuity(List<EnrichedCandle> candles,
                                                          int endIndex,
                                                          Pivot latestHigh,
                                                          Pivot latestLow,
                                                          TrendDirection direction) {
        if (!isDirectional(direction)) {
            return new StructureContinuity(true, "no directional structure required a continuity check");
        }
        int startIndex = Math.max(latestHigh.index(), latestLow.index()) + 1;
        if (startIndex > endIndex) {
            return new StructureContinuity(true,
                    "the latest confirmed structure remained active through the pattern setup");
        }
        for (int index = startIndex; index <= endIndex; index++) {
            EnrichedCandle candle = candles.get(index);
            if (!hasPrices(candle)) continue;
            if (direction == TrendDirection.DOWN && candle.close() > latestHigh.price()) {
                return new StructureContinuity(false, String.format(Locale.ROOT,
                        "downtrend continuity failed because the completed pre-pattern close %.4f broke above the latest lower high %.4f",
                        candle.close(), latestHigh.price()));
            }
            if (direction == TrendDirection.UP && candle.close() < latestLow.price()) {
                return new StructureContinuity(false, String.format(Locale.ROOT,
                        "uptrend continuity failed because the completed pre-pattern close %.4f broke below the latest higher low %.4f",
                        candle.close(), latestLow.price()));
            }
        }
        return new StructureContinuity(true,
                direction == TrendDirection.DOWN
                        ? "downtrend continuity remained intact below the latest lower high"
                        : "uptrend continuity remained intact above the latest higher low");
    }

    RegressionEvidence assessRegression(List<EnrichedCandle> candles,
                                        int patternStartIndex,
                                        RegressionParameters parameters) {
        parameters.validate();
        return classifyRegression(measureRegression(candles, patternStartIndex, parameters.windowBars()), parameters);
    }

    RegressionEvidence measureRegression(List<EnrichedCandle> candles,
                                         int patternStartIndex,
                                         int windowBars) {
        if (windowBars < 3 || windowBars > 200) {
            throw new IllegalArgumentException("Regression window must be between 3 and 200 candles.");
        }
        int endIndex = patternStartIndex - 1;
        int startIndex = endIndex - windowBars + 1;
        if (candles == null || startIndex < 0 || endIndex >= candles.size()) {
            return RegressionEvidence.unavailable("insufficient completed candles for robust regression");
        }
        double[] logCloses = new double[windowBars];
        double[] normalizedAtr = new double[windowBars];
        for (int offset = 0; offset < windowBars; offset++) {
            EnrichedCandle candle = candles.get(startIndex + offset);
            if (!hasPrices(candle) || candle.close() <= 0.0) {
                return RegressionEvidence.unavailable("regression requires finite positive closes");
            }
            logCloses[offset] = Math.log(candle.close());
            normalizedAtr[offset] = normalizedAtr(candles, startIndex + offset);
        }
        double volatility = median(normalizedAtr);
        if (!Double.isFinite(volatility) || volatility <= 0.0) {
            return RegressionEvidence.unavailable("positive pre-pattern volatility was unavailable");
        }
        double slope = theilSenSlope(logCloses);
        double intercept = medianIntercept(logCloses, slope);
        double rSquared = rSquared(logCloses, slope, intercept);
        double normalizedSlope = slope / volatility;
        return new RegressionEvidence(
                TrendDirection.SIDEWAYS, normalizedSlope, rSquared, startIndex, endIndex,
                String.format(Locale.ROOT,
                        "unclassified Theil-Sen log-price regression across %d completed candles (slope %.4f ATR/candle, R-squared %.3f)",
                        windowBars, normalizedSlope, rSquared));
    }

    RegressionEvidence classifyRegression(RegressionEvidence measurement,
                                          RegressionParameters parameters) {
        parameters.validate();
        if (measurement == null || measurement.startIndex() < 0
                || !Double.isFinite(measurement.normalizedSlope())
                || !Double.isFinite(measurement.rSquared())) {
            return measurement == null
                    ? RegressionEvidence.unavailable("regression measurement was unavailable")
                    : measurement;
        }
        TrendDirection direction = TrendDirection.SIDEWAYS;
        double slopeThreshold = Math.max(parameters.minimumAbsoluteNormalizedSlope(), 0.000_000_001);
        if (measurement.rSquared() >= parameters.minimumRSquared()) {
            if (measurement.normalizedSlope() >= slopeThreshold) {
                direction = TrendDirection.UP;
            } else if (measurement.normalizedSlope() <= -slopeThreshold) {
                direction = TrendDirection.DOWN;
            }
        }
        String description = String.format(Locale.ROOT,
                "%s from Theil-Sen log-price regression across %d completed candles (slope %.4f ATR/candle, R-squared %.3f)",
                directionLabel(direction), parameters.windowBars(), measurement.normalizedSlope(), measurement.rSquared());
        return new RegressionEvidence(
                direction, measurement.normalizedSlope(), measurement.rSquared(),
                measurement.startIndex(), measurement.endIndex(), description);
    }

    TrendAssessment combine(TrendPolicy policy,
                            StructureEvidence structure,
                            RegressionEvidence regression) {
        if (policy == null || structure == null || regression == null) {
            throw new IllegalArgumentException("A trend policy and both evidence records are required.");
        }
        TrendDirection direction = switch (policy) {
            case STRUCTURE_ONLY -> structure.direction();
            case REGRESSION_ONLY -> regression.direction();
            case STRICT_AGREEMENT -> isDirectional(structure.direction())
                    && structure.direction() == regression.direction()
                    ? structure.direction() : TrendDirection.SIDEWAYS;
            case STRUCTURE_WITH_REGRESSION_VETO -> isDirectional(structure.direction())
                    && regression.direction() != opposite(structure.direction())
                    ? structure.direction() : TrendDirection.SIDEWAYS;
        };
        String description = switch (policy) {
            case STRUCTURE_ONLY -> structure.description();
            case REGRESSION_ONLY -> regression.description();
            case STRICT_AGREEMENT -> "strict structure/regression agreement: "
                    + structure.description() + "; " + regression.description();
            case STRUCTURE_WITH_REGRESSION_VETO -> "structure with strong regression veto: "
                    + structure.description() + "; " + regression.description();
        };
        return new TrendAssessment(direction, policy, structure, regression, description);
    }

    private boolean isPivotHigh(List<EnrichedCandle> candles,
                                int center,
                                SwingParameters parameters) {
        double value = candles.get(center).high();
        for (int index = center - parameters.leftBars();
             index <= center + parameters.rightConfirmationBars(); index++) {
            if (index == center || index < 0 || index >= candles.size()
                    || !hasPrices(candles.get(index)) || value <= candles.get(index).high()) {
                if (index != center) return false;
            }
        }
        return true;
    }

    private boolean isPivotLow(List<EnrichedCandle> candles,
                               int center,
                               SwingParameters parameters) {
        double value = candles.get(center).low();
        for (int index = center - parameters.leftBars();
             index <= center + parameters.rightConfirmationBars(); index++) {
            if (index == center || index < 0 || index >= candles.size()
                    || !hasPrices(candles.get(index)) || value >= candles.get(index).low()) {
                if (index != center) return false;
            }
        }
        return true;
    }

    private double prominenceAtr(List<EnrichedCandle> candles,
                                 int center,
                                 boolean high,
                                 SwingParameters parameters) {
        double neighboringExtreme = high ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int index = center - parameters.leftBars();
             index <= center + parameters.rightConfirmationBars(); index++) {
            if (index == center) continue;
            neighboringExtreme = high
                    ? Math.max(neighboringExtreme, candles.get(index).high())
                    : Math.min(neighboringExtreme, candles.get(index).low());
        }
        double distance = high
                ? candles.get(center).high() - neighboringExtreme
                : neighboringExtreme - candles.get(center).low();
        double atr = atr(candles, center);
        return atr > 0.0 ? distance / atr : 0.0;
    }

    private double normalizedAtr(List<EnrichedCandle> candles, int index) {
        EnrichedCandle candle = candles.get(index);
        double atr = atr(candles, index);
        return candle.close() > 0.0 ? atr / candle.close() : Double.NaN;
    }

    private double displacementAtr(List<EnrichedCandle> candles, Pivot first, Pivot second) {
        int start = Math.max(0, Math.min(first.index(), second.index()));
        int end = Math.min(candles.size() - 1, Math.max(first.index(), second.index()));
        double[] atrValues = new double[end - start + 1];
        int count = 0;
        for (int index = start; index <= end; index++) {
            double value = atr(candles, index);
            if (Double.isFinite(value) && value > 0.0) {
                atrValues[count++] = value;
            }
        }
        if (count == 0) return 0.0;
        double scale = median(java.util.Arrays.copyOf(atrValues, count));
        return scale > 0.0 ? Math.abs(second.price() - first.price()) / scale : 0.0;
    }

    private double atr(List<EnrichedCandle> candles, int index) {
        EnrichedCandle candle = candles.get(index);
        if (Double.isFinite(candle.atr()) && candle.atr() > 0.0) return candle.atr();
        double previousClose = index > 0 && hasPrices(candles.get(index - 1))
                ? candles.get(index - 1).close() : candle.close();
        return Math.max(candle.high() - candle.low(),
                Math.max(Math.abs(candle.high() - previousClose),
                        Math.abs(candle.low() - previousClose)));
    }

    private double theilSenSlope(double[] values) {
        double[] slopes = new double[(values.length * (values.length - 1)) / 2];
        int slopeIndex = 0;
        for (int first = 0; first < values.length - 1; first++) {
            for (int second = first + 1; second < values.length; second++) {
                slopes[slopeIndex++] = (values[second] - values[first]) / (second - first);
            }
        }
        java.util.Arrays.sort(slopes);
        int middle = slopes.length / 2;
        return slopes.length % 2 == 0
                ? (slopes[middle - 1] + slopes[middle]) / 2.0
                : slopes[middle];
    }

    private double medianIntercept(double[] values, double slope) {
        double[] intercepts = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            intercepts[index] = values[index] - slope * index;
        }
        return median(intercepts);
    }

    private double rSquared(double[] values, double slope, double intercept) {
        double mean = 0.0;
        for (double value : values) mean += value;
        mean /= values.length;
        double residual = 0.0;
        double total = 0.0;
        for (int index = 0; index < values.length; index++) {
            double error = values[index] - (intercept + slope * index);
            residual += error * error;
            double centered = values[index] - mean;
            total += centered * centered;
        }
        if (total <= 0.0) return 0.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - residual / total));
    }

    private double median(double[] values) {
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2.0 : copy[middle];
    }

    private long latestTimestamp(List<Pivot> highs, List<Pivot> lows) {
        long high = highs.isEmpty() || highs.getLast().timestamp() == null ? 0L : highs.getLast().timestamp();
        long low = lows.isEmpty() || lows.getLast().timestamp() == null ? 0L : lows.getLast().timestamp();
        return Math.max(high, low);
    }

    private boolean hasPrices(EnrichedCandle candle) {
        return candle != null
                && Double.isFinite(candle.high())
                && Double.isFinite(candle.low())
                && Double.isFinite(candle.close());
    }

    private boolean isDirectional(TrendDirection direction) {
        return direction == TrendDirection.UP || direction == TrendDirection.DOWN;
    }

    private TrendDirection opposite(TrendDirection direction) {
        return direction == TrendDirection.UP ? TrendDirection.DOWN
                : direction == TrendDirection.DOWN ? TrendDirection.UP : TrendDirection.SIDEWAYS;
    }

    private String directionLabel(TrendDirection direction) {
        return switch (direction) {
            case UP -> "uptrend";
            case DOWN -> "downtrend";
            case BASE -> "base";
            case SIDEWAYS -> "no established trend";
        };
    }

    enum TrendPolicy {
        STRUCTURE_ONLY,
        REGRESSION_ONLY,
        STRICT_AGREEMENT,
        STRUCTURE_WITH_REGRESSION_VETO
    }

    record SwingParameters(int leftBars,
                           int rightConfirmationBars,
                           int maximumLookbackBars,
                           double minimumProminenceAtr,
                           double minimumSwingDisplacementAtr,
                           double minimumTerminalMedianDistanceAtr) {
        SwingParameters(int leftBars,
                        int rightConfirmationBars,
                        int maximumLookbackBars,
                        double minimumProminenceAtr,
                        double minimumSwingDisplacementAtr) {
            this(leftBars, rightConfirmationBars, maximumLookbackBars,
                    minimumProminenceAtr, minimumSwingDisplacementAtr, -1.0);
        }

        SwingParameters(int leftBars,
                        int rightConfirmationBars,
                        int maximumLookbackBars,
                        double minimumProminenceAtr) {
            this(leftBars, rightConfirmationBars, maximumLookbackBars,
                    minimumProminenceAtr, 0.0, -1.0);
        }

        void validate() {
            if (leftBars < 1 || leftBars > 20 || rightConfirmationBars < 1 || rightConfirmationBars > 20) {
                throw new IllegalArgumentException("Pivot left/right confirmation bars must be between 1 and 20.");
            }
            if (maximumLookbackBars < leftBars + rightConfirmationBars + 4 || maximumLookbackBars > 500) {
                throw new IllegalArgumentException("Pivot lookback cannot establish two confirmed highs and lows.");
            }
            if (!Double.isFinite(minimumProminenceAtr) || minimumProminenceAtr < 0.0
                    || minimumProminenceAtr > 10.0) {
                throw new IllegalArgumentException("Pivot prominence must be between 0 and 10 ATR.");
            }
            if (!Double.isFinite(minimumSwingDisplacementAtr)
                    || minimumSwingDisplacementAtr < 0.0
                    || minimumSwingDisplacementAtr > 20.0) {
                throw new IllegalArgumentException(
                        "Minimum swing displacement must be between 0 and 20 ATR.");
            }
            if (!Double.isFinite(minimumTerminalMedianDistanceAtr)
                    || minimumTerminalMedianDistanceAtr < -1.0
                    || minimumTerminalMedianDistanceAtr > 10.0) {
                throw new IllegalArgumentException(
                        "Terminal median distance must be disabled (-1) or between 0 and 10 ATR.");
            }
        }
    }

    record RegressionParameters(int windowBars,
                                double minimumAbsoluteNormalizedSlope,
                                double minimumRSquared) {
        void validate() {
            if (windowBars < 3 || windowBars > 200) {
                throw new IllegalArgumentException("Regression window must be between 3 and 200 candles.");
            }
            if (!Double.isFinite(minimumAbsoluteNormalizedSlope)
                    || minimumAbsoluteNormalizedSlope < 0.0
                    || minimumAbsoluteNormalizedSlope > 10.0) {
                throw new IllegalArgumentException("Normalized slope threshold must be between 0 and 10.");
            }
            if (!Double.isFinite(minimumRSquared) || minimumRSquared < 0.0 || minimumRSquared > 1.0) {
                throw new IllegalArgumentException("Minimum regression R-squared must be between 0 and 1.");
            }
        }
    }

    record TrendModelParameters(TrendPolicy policy,
                                SwingParameters structure,
                                RegressionParameters regression,
                                DirectionalParticipationParameters participation) {
        TrendModelParameters(TrendPolicy policy,
                             SwingParameters structure,
                             RegressionParameters regression) {
            this(policy, structure, regression, null);
        }

        void validate() {
            if (policy == null) throw new IllegalArgumentException("A trend policy is required.");
            if (policy != TrendPolicy.REGRESSION_ONLY && structure == null) {
                throw new IllegalArgumentException("This trend policy requires swing parameters.");
            }
            if (policy != TrendPolicy.STRUCTURE_ONLY && regression == null) {
                throw new IllegalArgumentException("This trend policy requires regression parameters.");
            }
            if (structure != null) structure.validate();
            if (regression != null) regression.validate();
            if (participation != null) {
                if (structure == null) {
                    throw new IllegalArgumentException(
                            "Directional participation requires an exact structural trend leg.");
                }
                participation.validate();
            }
        }
    }

    record DirectionalParticipationParameters(int minimumDirectionalTransitions,
                                              int windowCandles,
                                              double minimumDirectionalMovePercent) {
        void validate() {
            if (windowCandles < 2 || windowCandles > 100) {
                throw new IllegalArgumentException(
                        "Directional participation window must be between 2 and 100 candles.");
            }
            if (minimumDirectionalTransitions < 1
                    || minimumDirectionalTransitions >= windowCandles) {
                throw new IllegalArgumentException(
                        "Directional transitions must be between 1 and window candles minus one.");
            }
            if (!Double.isFinite(minimumDirectionalMovePercent)
                    || minimumDirectionalMovePercent < 0.0
                    || minimumDirectionalMovePercent > 50.0) {
                throw new IllegalArgumentException(
                        "Directional move must be between 0 and 50 percent.");
            }
        }
    }

    record StructureEvidence(TrendDirection direction,
                             int confirmedHighs,
                             int confirmedLows,
                             long latestConfirmedPivotTimestamp,
                             int trendStartIndex,
                             int trendEndIndex,
                             String description) {
        StructureEvidence(TrendDirection direction,
                          int confirmedHighs,
                          int confirmedLows,
                          long latestConfirmedPivotTimestamp,
                          String description) {
            this(direction, confirmedHighs, confirmedLows, latestConfirmedPivotTimestamp,
                    -1, -1, description);
        }

        static StructureEvidence unavailable(String reason) {
            return new StructureEvidence(TrendDirection.SIDEWAYS, 0, 0, 0L,
                    -1, -1, reason);
        }
    }

    record RegressionEvidence(TrendDirection direction,
                              double normalizedSlope,
                              double rSquared,
                              int startIndex,
                              int endIndex,
                              String description) {
        static RegressionEvidence unavailable(String reason) {
            return new RegressionEvidence(
                    TrendDirection.SIDEWAYS, Double.NaN, Double.NaN, -1, -1, reason);
        }
    }

    record TrendAssessment(TrendDirection direction,
                           TrendPolicy policy,
                           StructureEvidence structure,
                           RegressionEvidence regression,
                           String description) {
        PriorTrendAssessment asPriorTrendAssessment() {
            return new PriorTrendAssessment(
                    direction,
                    direction == TrendDirection.UP || direction == TrendDirection.DOWN ? 20 : 0,
                    description,
                    structure.trendStartIndex(),
                    structure.trendEndIndex());
        }
    }

    private record Pivot(int index, Long timestamp, double price) { }

    private record StructureContinuity(boolean intact, String description) { }

    private record TerminalPosition(boolean accepted, String description) { }

    private record DirectionalParticipationEvidence(boolean accepted, String description) { }
}
