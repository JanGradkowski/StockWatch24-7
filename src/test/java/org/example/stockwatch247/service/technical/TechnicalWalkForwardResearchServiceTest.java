package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.junit.jupiter.api.Test;
import org.ta4j.core.walkforward.WalkForwardConfig;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalWalkForwardResearchServiceTest {

    @Test
    void evaluatesCandidateOutOfSampleWithoutConnectingItToProductionSignals() {
        List<Candle> candles = regimeFixture(720);
        TechnicalIndicatorParameters parameters = new TechnicalIndicatorParameters(
                14, 14, 20, 50, 200, 12, 26, 9, 20, 20,
                2.0, 20, 20, 60);
        WalkForwardConfig config = new WalkForwardConfig(
                240, 80, 80, 5, 5, 80, 10,
                List.of(5, 10, 20), 5, List.of(1, 3, 5), 24_701L);

        TechnicalWalkForwardResearchService.WalkForwardReport report =
                new TechnicalWalkForwardResearchService().evaluate(
                        candles, parameters,
                        TechnicalWalkForwardResearchService.Candidate.TA4J_TREND,
                        config);

        assertThat(report.outOfSampleFolds()).isGreaterThan(0);
        assertThat(report.averageNetReturn()).isFinite();
        assertThat(report.averageMaximumDrawdown()).isFinite();
        assertThat(report.configurationHash()).isNotBlank();
        assertThat(report.boundaryNotice()).contains("no production detector");
    }

    private List<Candle> regimeFixture(int count) {
        Instant first = Instant.parse("2022-01-03T21:00:00Z");
        List<Candle> candles = new ArrayList<>();
        double close = 100.0;
        for (int index = 0; index < count; index++) {
            int regime = (index / 90) % 3;
            double drift = regime == 0 ? 0.35 : regime == 1 ? -0.28 : 0.02;
            close = Math.max(5.0, close + drift + Math.sin(index * 0.23) * 0.16);
            double open = close - drift * 0.4;
            candles.add(new Candle("WALK", "1d", first.plus(index, ChronoUnit.DAYS).getEpochSecond(),
                    open, Math.max(open, close) + 0.9, Math.min(open, close) - 0.9,
                    close, 1_000_000L + (index % 17) * 20_000L));
        }
        return candles;
    }
}
