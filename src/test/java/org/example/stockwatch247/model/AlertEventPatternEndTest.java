package org.example.stockwatch247.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Where each kind of signal's pattern ends, which decides the signals that share a point. */
class AlertEventPatternEndTest {

    @Test
    void aHarmonicFormationEndsAtItsDPoint() {
        AlertEvent event = signal(1_794_960_000L);
        event.setHarmonicEndpointTimestamp(1_794_787_200L);
        assertThat(event.patternEnd()).isEqualTo(1_794_787_200L);
    }

    @Test
    void aDevelopingElliottCountEndsAtItsLastWavePointNotItsConfirmationCandle() {
        AlertEvent event = signal(1_794_873_600L);
        event.setElliottEndpointTimestamp(1_794_873_600L);
        event.setElliottStructureSnapshot("""
                0|1789516800|1.0349999666|HIGH
                I|1793750400|0.5799999833|LOW
                II|1794787200|0.7360000014|HIGH
                """);
        assertThat(event.patternEnd()).isEqualTo(1_794_787_200L);
    }

    @Test
    void aCompletedElliottSignalEndsAtItsEndpointAndACandlestickAtItsCandle() {
        AlertEvent elliott = signal(1_794_873_600L);
        elliott.setElliottEndpointTimestamp(1_794_787_200L);
        assertThat(elliott.patternEnd()).isEqualTo(1_794_787_200L);
        assertThat(signal(1_794_873_600L).patternEnd()).isEqualTo(1_794_873_600L);
    }

    @Test
    void anUnreadableStructureFallsBackToTheEndpoint() {
        AlertEvent event = signal(1_794_873_600L);
        event.setElliottEndpointTimestamp(1_794_787_200L);
        event.setElliottStructureSnapshot("not a structure");
        assertThat(event.patternEnd()).isEqualTo(1_794_787_200L);
    }

    @Test
    void savingStoresTheEnd() {
        AlertEvent event = signal(1_794_873_600L);
        event.setHarmonicEndpointTimestamp(1_794_787_200L);
        event.derivePatternEnd();
        assertThat(event.getPatternEndTimestamp()).isEqualTo(1_794_787_200L);
    }

    private static AlertEvent signal(long candle) {
        AlertEvent event = new AlertEvent();
        event.setSignalCandleTimestamp(candle);
        return event;
    }
}
