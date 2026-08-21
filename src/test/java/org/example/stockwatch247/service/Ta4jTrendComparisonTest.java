package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.CandlePatternDetectionService.TrendDirection;
import org.example.stockwatch247.service.technical.TechnicalResearchSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Ta4jTrendComparisonTest {

    @Test
    void comparesTa4jTrendLabelsWithEstablishedCausalRegressionWithoutReplacingIt() {
        List<Candle> raw = labeledRegimes();
        TechnicalIndicatorEnrichmentService enrichment = new TechnicalIndicatorEnrichmentService();
        List<EnrichedCandle> establishedInputs = enrichment.enrich(raw, raw.size(), TimeInterval.DAILY);
        List<TechnicalResearchSnapshot> ta4j = enrichment.research(
                raw, raw.size(), TechnicalIndicatorProfile.forInterval(TimeInterval.DAILY));
        CandlestickAdaptiveTrendService established = new CandlestickAdaptiveTrendService();
        CandlestickAdaptiveTrendService.RegressionParameters parameters =
                new CandlestickAdaptiveTrendService.RegressionParameters(25, 0.15, 0.20);

        int establishedCorrect = 0;
        int ta4jCorrect = 0;
        int samples = 0;
        for (int index = 40; index < raw.size(); index++) {
            int offset = index % 90;
            if (offset < 40) continue;
            TrendDirection expected = expectedDirection(index / 90);
            TrendDirection establishedDirection = established
                    .assessRegression(establishedInputs, index + 1, parameters).direction();
            TechnicalResearchSnapshot snapshot = ta4j.get(index);
            TrendDirection ta4jDirection = snapshot.upTrend() == snapshot.downTrend()
                    ? TrendDirection.SIDEWAYS
                    : snapshot.upTrend() ? TrendDirection.UP : TrendDirection.DOWN;
            if (establishedDirection == expected) establishedCorrect++;
            if (ta4jDirection == expected) ta4jCorrect++;
            samples++;
        }

        TrendComparison result = new TrendComparison(samples, establishedCorrect, ta4jCorrect);
        assertThat(result.samples()).isGreaterThan(100);
        assertThat(result.establishedAccuracy()).isBetween(0.0, 1.0);
        assertThat(result.ta4jAccuracy()).isBetween(0.0, 1.0);
        assertThat(result.establishedAccuracy()).isGreaterThanOrEqualTo(result.ta4jAccuracy());
        assertThat(establishedInputs).hasSameSizeAs(ta4j);
    }

    private TrendDirection expectedDirection(int regime) {
        return switch (regime) {
            case 0, 3 -> TrendDirection.UP;
            case 1 -> TrendDirection.DOWN;
            default -> TrendDirection.SIDEWAYS;
        };
    }

    private List<Candle> labeledRegimes() {
        Instant first = Instant.parse("2024-01-02T21:00:00Z");
        List<Candle> candles = new ArrayList<>();
        double close = 100.0;
        double sidewaysAnchor = close;
        for (int index = 0; index < 360; index++) {
            int regime = index / 90;
            if (regime == 0 || regime == 3) {
                close += 0.32 + Math.sin(index * 0.27) * 0.025;
            } else if (regime == 1) {
                close -= 0.29 + Math.sin(index * 0.23) * 0.025;
            } else {
                if (index == 180) sidewaysAnchor = close;
                close = sidewaysAnchor + Math.sin((index - 180) * 0.35) * 0.45;
            }
            double open = close - Math.sin(index * 0.17) * 0.18;
            candles.add(new Candle("TREND", "1d", first.plus(index, ChronoUnit.DAYS).getEpochSecond(),
                    open, Math.max(open, close) + 0.8, Math.min(open, close) - 0.8,
                    close, 1_000_000L + (index % 13) * 12_000L));
        }
        return candles;
    }

    private record TrendComparison(int samples, int establishedCorrect, int ta4jCorrect) {
        private double establishedAccuracy() {
            return samples == 0 ? Double.NaN : (double) establishedCorrect / samples;
        }

        private double ta4jAccuracy() {
            return samples == 0 ? Double.NaN : (double) ta4jCorrect / samples;
        }

        @Override
        public String toString() {
            return "samples=%d, establishedAccuracy=%.4f, ta4jAccuracy=%.4f"
                    .formatted(samples, establishedAccuracy(), ta4jAccuracy());
        }
    }
}
