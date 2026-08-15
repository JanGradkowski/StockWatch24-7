package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CdrSidewaysFilterDiagnosticTest {
    @Autowired CandleRepository candleRepository;
    @Autowired TechnicalIndicatorEnrichmentService enrichmentService;
    @Autowired CandlePatternDetectionService detectionService;

    @Test
    void februaryBullishEngulfingNoLongerHasADowntrend() {
        List<Candle> raw = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc("CDR", "1d");
        List<EnrichedCandle> enriched = enrichmentService.enrich(raw, raw.size(), TimeInterval.DAILY);
        long signalTimestamp = LocalDate.of(2026, 2, 25)
                .atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        int signalIndex = -1;
        for (int index = 0; index < enriched.size(); index++) {
            if (enriched.get(index).timestamp() == signalTimestamp) {
                signalIndex = index;
                break;
            }
        }

        assertThat(signalIndex).isPositive();
        CandlePatternDetectionService.PriorTrendAssessment assessment =
                detectionService.assessFactoryPriorTrendForLatestPattern(
                        enriched.subList(0, signalIndex + 1),
                        2,
                        TimeInterval.DAILY,
                        TradeSignal.BUY);

        assertThat(assessment.direction())
                .isEqualTo(CandlePatternDetectionService.TrendDirection.SIDEWAYS);
        assertThat(assessment.description()).contains("minimum swing displacement 0.25 ATR");
    }
}
