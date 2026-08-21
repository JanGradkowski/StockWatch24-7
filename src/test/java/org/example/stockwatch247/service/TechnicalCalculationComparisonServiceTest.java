package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalCalculationComparisonServiceTest {

    @Test
    void comparesEstablishedBinnedAndTa4jKdeVolumeProfilesWithoutPromotingEither() {
        TechnicalCalculationComparisonService.ComparisonReport report =
                new TechnicalCalculationComparisonService().compare(marketFixture(520), TimeInterval.DAILY);

        assertThat(report.coreIndicators().status()).isEqualTo("TA4J_ALREADY_IN_PRODUCTION");
        assertThat(report.volumeProfile().available()).isTrue();
        assertThat(report.volumeProfile().samples()).isGreaterThanOrEqualTo(100);
        assertThat(report.volumeProfile().establishedMeanAtrError()).isFinite().isPositive();
        assertThat(report.volumeProfile().ta4jMeanAtrError()).isFinite().isPositive();
        assertThat(report.volumeProfile().lowerErrorMethod()).isEqualTo("ESTABLISHED_BINNED_PROFILE");
        assertThat(report.volumeProfile().ta4jRelativeImprovement()).isLessThan(0.0);
        assertThat(report.volumeProfile().recommendation()).isEqualTo("KEEP_ESTABLISHED_PRODUCTION_INPUT");
        assertThat(report.safetyBoundary()).contains("No result automatically changes detection");
    }

    private List<Candle> marketFixture(int count) {
        Instant first = Instant.parse("2023-01-03T21:00:00Z");
        List<Candle> candles = new ArrayList<>();
        double close = 100.0;
        for (int index = 0; index < count; index++) {
            double cycle = Math.sin(index * 0.12) * 0.9 + Math.cos(index * 0.031) * 0.45;
            close = Math.max(10.0, close + 0.07 + cycle * 0.18);
            double open = close - Math.sin(index * 0.19) * 0.5;
            double high = Math.max(open, close) + 0.75 + (index % 9) * 0.035;
            double low = Math.min(open, close) - 0.70 - (index % 6) * 0.04;
            long volume = 900_000L + (index % 23) * 31_000L
                    + Math.round(Math.abs(cycle) * 160_000L);
            candles.add(new Candle("CMP", "1d", first.plus(index, ChronoUnit.DAYS).getEpochSecond(),
                    open, high, low, close, volume));
        }
        return candles;
    }
}
