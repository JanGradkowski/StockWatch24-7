package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "diagnostic.local-data.enabled", matches = "true")
class GspcActiveTrendDiagnosticTest {
    @Autowired CandleRepository candleRepository;
    @Autowired TechnicalIndicatorEnrichmentService enrichmentService;
    @Autowired CandlePatternDetectionService detectionService;

    @Test
    void recoveredAprilBullishEngulfingNoLongerInheritsTheOldDowntrend() {
        List<Candle> raw = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc("^GSPC", "1d");
        List<EnrichedCandle> enriched = enrichmentService.enrich(raw, raw.size(), TimeInterval.DAILY);
        int matchingIndex = -1;
        for (int index = 1; index < enriched.size(); index++) {
            EnrichedCandle candle = enriched.get(index);
            LocalDate date = Instant.ofEpochSecond(candle.timestamp()).atZone(ZoneOffset.UTC).toLocalDate();
            if (date.getMonthValue() != 4 || candle.close() < 6_700 || candle.close() > 7_000) continue;
            boolean bullishEngulfing = detectionService.geometricCandidatesAt(enriched, index).stream()
                    .anyMatch(candidate -> candidate.pattern() == CandlePattern.BULLISH_ENGULFING);
            if (bullishEngulfing) matchingIndex = index;
        }

        assertThat(matchingIndex).as("cached April ^GSPC bullish engulfing near 6,800").isPositive();
        CandlePatternDetectionService.PriorTrendAssessment assessment =
                detectionService.assessFactoryPriorTrendForLatestPattern(
                        enriched.subList(0, matchingIndex + 1),
                        2,
                        TimeInterval.DAILY,
                        TradeSignal.BUY);

        assertThat(assessment.direction())
                .as(assessment.description())
                .isNotEqualTo(CandlePatternDetectionService.TrendDirection.DOWN);
        assertThat(assessment.description())
                .contains("downtrend continuity failed")
                .contains("conflict-aware OR result");
    }

    @Test
    void recoveredAprilHammerFailsTheTerminalMedianPositionGate() {
        List<Candle> raw = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc("^GSPC", "1d");
        List<EnrichedCandle> enriched = enrichmentService.enrich(raw, raw.size(), TimeInterval.DAILY);
        CandlePatternDetectionService.PriorTrendAssessment matchingAssessment = null;
        for (int index = 1; index < enriched.size(); index++) {
            EnrichedCandle candle = enriched.get(index);
            LocalDate date = Instant.ofEpochSecond(candle.timestamp()).atZone(ZoneOffset.UTC).toLocalDate();
            if (date.getYear() != 2026 || date.getMonthValue() != 4
                    || candle.close() < 6_500 || candle.close() > 6_700) continue;
            boolean hammer = detectionService.geometricCandidatesAt(enriched, index).stream()
                    .anyMatch(candidate -> candidate.pattern() == CandlePattern.HAMMER);
            if (!hammer) continue;
            CandlePatternDetectionService.PriorTrendAssessment assessment =
                    detectionService.assessPriorTrendForLatestPattern(
                            enriched.subList(0, index + 1),
                            1,
                            CandlePatternDetectionService.TrendDetectionRules
                                    .adaptiveFactory(TimeInterval.DAILY, 0.25),
                            TradeSignal.BUY);
            if (assessment.description().contains("terminal-position check failed")) {
                matchingAssessment = assessment;
                break;
            }
        }

        assertThat(matchingAssessment)
                .as("cached April 2026 ^GSPC hammer near 6,600")
                .isNotNull();
        assertThat(matchingAssessment.direction())
                .isNotEqualTo(CandlePatternDetectionService.TrendDirection.DOWN);
        assertThat(matchingAssessment.description())
                .contains("terminal-position check failed")
                .contains("trend-leg median close");
    }
}
