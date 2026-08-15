package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.service.CandlePatternDetectionService.TrendDirection;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.RegressionEvidence;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.RegressionParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.DirectionalParticipationParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.StructureEvidence;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.SwingParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendAssessment;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendModelParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandlestickAdaptiveTrendServiceTest {
    private final CandlestickAdaptiveTrendService service = new CandlestickAdaptiveTrendService();

    @Test
    void recognizesLowerConfirmedSwingHighsAndLowsWithoutReadingThePatternCandle() {
        List<EnrichedCandle> candles = swingDowntrend();
        SwingParameters parameters = new SwingParameters(1, 1, 10, 0.0);

        StructureEvidence original = service.assessStructure(candles, 10, parameters);
        List<EnrichedCandle> alteredFuture = new ArrayList<>(candles);
        alteredFuture.set(10, candle(10, 500, 1, 450, 10));
        alteredFuture.set(11, candle(11, 900, 1, 850, 10));
        StructureEvidence afterFutureMutation = service.assessStructure(alteredFuture, 10, parameters);

        assertThat(original.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(original.confirmedHighs()).isGreaterThanOrEqualTo(2);
        assertThat(original.confirmedLows()).isGreaterThanOrEqualTo(2);
        assertThat(afterFutureMutation).isEqualTo(original);
    }

    @Test
    void doesNotUseAPivotUntilEveryRightSideConfirmationCandleExists() {
        List<EnrichedCandle> candles = swingDowntrend();
        SwingParameters parameters = new SwingParameters(1, 2, 10, 0.0);

        StructureEvidence evidence = service.assessStructure(candles, 11, parameters);

        assertThat(evidence.latestConfirmedPivotTimestamp()).isLessThanOrEqualTo(8L);
    }

    @Test
    void rejectsTinyLowerHighsAndLowsAsSideways() {
        StructureEvidence evidence = service.assessStructure(
                tinyDownwardDrift(),
                8,
                new SwingParameters(1, 1, 8, 0.0, 0.25));

        assertThat(evidence.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(evidence.description()).contains("minimum swing displacement 0.25 ATR");
    }

    @Test
    void rejectsTinyHigherHighsAndLowsAsSideways() {
        StructureEvidence evidence = service.assessStructure(
                tinyUpwardDrift(),
                8,
                new SwingParameters(1, 1, 8, 0.0, 0.25));

        assertThat(evidence.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(evidence.description()).contains("minimum swing displacement 0.25 ATR");
    }

    @Test
    void rejectsAStaleDowntrendAfterPriceClosesAboveTheLatestLowerHigh() {
        StructureEvidence evidence = service.assessStructure(
                downtrendWithTerminalBullishReversal(),
                10,
                new SwingParameters(1, 1, 10, 0.0, 0.25));

        assertThat(evidence.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(evidence.description())
                .contains("downtrend continuity failed")
                .contains("broke above the latest lower high");
    }

    @Test
    void rejectsAStaleUptrendAfterPriceClosesBelowTheLatestHigherLow() {
        StructureEvidence evidence = service.assessStructure(
                uptrendWithTerminalBearishReversal(),
                10,
                new SwingParameters(1, 1, 10, 0.0, 0.25));

        assertThat(evidence.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(evidence.description())
                .contains("uptrend continuity failed")
                .contains("broke below the latest higher low");
    }

    @Test
    void retainsDirectionalStructureWhenTheLatestBoundaryIsNotBroken() {
        StructureEvidence down = service.assessStructure(
                activeDowntrend(), 10,
                new SwingParameters(1, 1, 10, 0.0, 0.25));
        StructureEvidence up = service.assessStructure(
                activeUptrend(), 10,
                new SwingParameters(1, 1, 10, 0.0, 0.25));

        assertThat(down.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(down.description()).contains("downtrend continuity remained intact");
        assertThat(up.direction()).isEqualTo(TrendDirection.UP);
        assertThat(up.description()).contains("uptrend continuity remained intact");
    }

    @Test
    void rejectsRecoveredDowntrendThatRemainsBelowItsLowerHighButNotBelowItsMedian() {
        StructureEvidence evidence = service.assessStructure(
                recoveredButUnbrokenDowntrend(), 10,
                new SwingParameters(1, 1, 10, 0.0, 0.25, 0.25));

        assertThat(evidence.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(evidence.description())
                .contains("downtrend continuity remained intact")
                .contains("terminal-position check failed")
                .contains("trend-leg median close");
    }

    @Test
    void appliesTerminalMedianPositionSymmetricallyAndExposesTheExactLeg() {
        StructureEvidence down = service.assessStructure(
                activeDowntrend(), 10,
                new SwingParameters(1, 1, 10, 0.0, 0.25, 0.25));
        StructureEvidence up = service.assessStructure(
                activeUptrend(), 10,
                new SwingParameters(1, 1, 10, 0.0, 0.25, 0.25));

        assertThat(down.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(up.direction()).isEqualTo(TrendDirection.UP);
        assertThat(down.description()).contains("terminal-position check passed");
        assertThat(up.description()).contains("terminal-position check passed");
        assertThat(down.trendStartIndex()).isEqualTo(up.trendStartIndex()).isPositive();
        assertThat(down.trendEndIndex()).isEqualTo(9);
        assertThat(up.trendEndIndex()).isEqualTo(9);
    }

    @Test
    void directionalParticipationCanRejectStructureThatOtherwisePasses() {
        TrendModelParameters permissive = new TrendModelParameters(
                TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                new SwingParameters(1, 1, 10, 0.0, 0.25, 0.0),
                new RegressionParameters(5, 0.0, 0.0),
                new DirectionalParticipationParameters(1, 3, 0.0));
        TrendModelParameters strict = new TrendModelParameters(
                TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                new SwingParameters(1, 1, 10, 0.0, 0.25, 0.0),
                new RegressionParameters(5, 0.0, 0.0),
                new DirectionalParticipationParameters(2, 3, 25.0));

        TrendAssessment accepted = service.assess(activeDowntrend(), 10, permissive);
        TrendAssessment rejected = service.assess(activeDowntrend(), 10, strict);

        assertThat(accepted.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(accepted.description()).contains("directional-participation check passed");
        assertThat(rejected.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(rejected.description()).contains("directional-participation check failed");
    }

    @Test
    void normalizedRobustRegressionIsInvariantToPriceScale() {
        List<EnrichedCandle> original = regressionSeries(1.0);
        List<EnrichedCandle> scaled = regressionSeries(100.0);
        RegressionParameters parameters = new RegressionParameters(8, 0.1, 0.2);

        RegressionEvidence first = service.assessRegression(original, original.size(), parameters);
        RegressionEvidence second = service.assessRegression(scaled, scaled.size(), parameters);

        assertThat(first.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(second.direction()).isEqualTo(first.direction());
        assertThat(second.normalizedSlope()).isCloseTo(first.normalizedSlope(),
                org.assertj.core.data.Offset.offset(0.000_000_1));
        assertThat(second.rSquared()).isCloseTo(first.rSquared(),
                org.assertj.core.data.Offset.offset(0.000_000_1));
    }

    @Test
    void strictAgreementAndRegressionVetoHaveDifferentNeutralBehavior() {
        StructureEvidence downStructure = new StructureEvidence(
                TrendDirection.DOWN, 2, 2, 10L, "lower highs and lows");
        RegressionEvidence neutralRegression = new RegressionEvidence(
                TrendDirection.SIDEWAYS, -0.05, 0.1, 1, 8, "weak slope");
        RegressionEvidence upRegression = new RegressionEvidence(
                TrendDirection.UP, 0.5, 0.8, 1, 8, "strong positive slope");

        assertThat(service.combine(TrendPolicy.STRICT_AGREEMENT,
                downStructure, neutralRegression).direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(service.combine(TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                downStructure, neutralRegression).direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(service.combine(TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                downStructure, upRegression).direction()).isEqualTo(TrendDirection.SIDEWAYS);
    }

    @Test
    void rejectsInvalidAdaptiveParameters() {
        assertThatThrownBy(() -> service.assessStructure(
                swingDowntrend(), 10, new SwingParameters(0, 2, 10, 0.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.assessRegression(
                regressionSeries(1.0), 8, new RegressionParameters(2, 0.1, 0.2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<EnrichedCandle> swingDowntrend() {
        return List.of(
                candle(0, 106, 104, 105, 3),
                candle(1, 108, 103, 106, 3),
                candle(2, 110, 102, 107, 3),
                candle(3, 107, 99, 102, 3),
                candle(4, 106, 95, 98, 3),
                candle(5, 104, 97, 101, 3),
                candle(6, 105, 96, 100, 3),
                candle(7, 103, 93, 95, 3),
                candle(8, 102, 90, 93, 3),
                candle(9, 101, 92, 96, 3),
                candle(10, 100, 91, 94, 3),
                candle(11, 99, 89, 92, 3)
        );
    }

    private List<EnrichedCandle> regressionSeries(double scale) {
        double[] closes = {110, 108, 106, 130, 102, 100, 98, 96};
        List<EnrichedCandle> candles = new ArrayList<>();
        for (int index = 0; index < closes.length; index++) {
            double close = closes[index] * scale;
            candles.add(candle(index, close + 2 * scale, close - 2 * scale,
                    close, 4 * scale));
        }
        return candles;
    }

    private List<EnrichedCandle> tinyDownwardDrift() {
        return List.of(
                candle(0, 99, 96, 98, 10),
                candle(1, 101, 98, 100, 10),
                candle(2, 99, 97, 98, 10),
                candle(3, 98, 95, 96, 10),
                candle(4, 100, 97, 99, 10),
                candle(5, 98, 96, 97, 10),
                candle(6, 97, 94, 95, 10),
                candle(7, 98, 95, 97, 10));
    }

    private List<EnrichedCandle> tinyUpwardDrift() {
        return List.of(
                candle(0, 99, 96, 98, 10),
                candle(1, 98, 94, 95, 10),
                candle(2, 99, 95, 98, 10),
                candle(3, 101, 97, 100, 10),
                candle(4, 100, 96, 98, 10),
                candle(5, 99, 95, 96, 10),
                candle(6, 102, 97, 101, 10),
                candle(7, 101, 96, 99, 10));
    }

    private List<EnrichedCandle> downtrendWithTerminalBullishReversal() {
        return List.of(
                candle(0, 108, 104, 106, 4),
                candle(1, 112, 106, 110, 4),
                candle(2, 109, 103, 105, 4),
                candle(3, 107, 99, 101, 4),
                candle(4, 110, 102, 108, 4),
                candle(5, 106, 98, 100, 4),
                candle(6, 104, 95, 97, 4),
                candle(7, 108, 97, 106, 4),
                candle(8, 112, 103, 111, 4),
                candle(9, 114, 108, 113, 4),
                candle(10, 115, 109, 114, 4));
    }

    private List<EnrichedCandle> uptrendWithTerminalBearishReversal() {
        return mirror(downtrendWithTerminalBullishReversal(), 220.0);
    }

    private List<EnrichedCandle> activeDowntrend() {
        List<EnrichedCandle> candles = new ArrayList<>(downtrendWithTerminalBullishReversal());
        candles.set(7, candle(7, 103, 97, 99, 4));
        candles.set(8, candle(8, 101, 96, 98, 4));
        candles.set(9, candle(9, 100, 95, 96, 4));
        return candles;
    }

    private List<EnrichedCandle> recoveredButUnbrokenDowntrend() {
        List<EnrichedCandle> candles = new ArrayList<>(downtrendWithTerminalBullishReversal());
        candles.set(8, candle(8, 109, 101, 107, 4));
        candles.set(9, candle(9, 109, 100, 108, 4));
        return candles;
    }

    private List<EnrichedCandle> activeUptrend() {
        return mirror(activeDowntrend(), 220.0);
    }

    private List<EnrichedCandle> mirror(List<EnrichedCandle> source, double axis) {
        List<EnrichedCandle> mirrored = new ArrayList<>();
        for (EnrichedCandle value : source) {
            mirrored.add(candle(value.timestamp(), axis - value.low(), axis - value.high(),
                    axis - value.close(), value.atr()));
        }
        return mirrored;
    }

    private EnrichedCandle candle(long timestamp,
                                  double high,
                                  double low,
                                  double close,
                                  double atr) {
        return new EnrichedCandle(
                timestamp, close, high, low, close,
                1_000, 1_000, 50, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0,
                atr, 0, Double.NaN, Double.NaN, Double.NaN);
    }
}
