package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandlestickHorizonGuidanceTest {

    @Test
    void mapsTheThreeBacktestedCandlestickIntervals() {
        assertThat(CandlestickHorizonGuidance.forSignal(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY))
                .get()
                .extracting(CandlestickHorizonGuidance.Guidance::label)
                .isEqualTo("10 trading sessions");
        assertThat(CandlestickHorizonGuidance.forSignal(AlertPatternFamily.CANDLESTICK, TimeInterval.WEEKLY))
                .get()
                .extracting(CandlestickHorizonGuidance.Guidance::label)
                .isEqualTo("4, 8, and 12 weeks");
        assertThat(CandlestickHorizonGuidance.forSignal(AlertPatternFamily.CANDLESTICK, TimeInterval.MONTHLY))
                .get()
                .satisfies(guidance -> {
                    assertThat(guidance.label()).isEqualTo("3, 6, and 9 months");
                    assertThat(guidance.summary()).contains("exploratory", "aggregate results were weak");
                    assertThat(guidance.disclaimer()).contains("not a recommended holding period");
                });
    }

    @Test
    void omitsCandlestickResearchGuidanceFromElliottAndUnsupportedIntervals() {
        assertThat(CandlestickHorizonGuidance.forSignal(
                AlertPatternFamily.ELLIOTT_WAVE, TimeInterval.WEEKLY)).isEmpty();
        assertThat(CandlestickHorizonGuidance.forSignal(
                AlertPatternFamily.CANDLESTICK, TimeInterval.ONE_HOUR)).isEmpty();
    }
}
