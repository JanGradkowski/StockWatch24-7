package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ElliottCompressedWaveFourTest {
    private final ElliottWaveDetectionService detector = new ElliottWaveDetectionService();

    @Test
    void waveFourRoomIsTheShareOfWaveThreeLeftBeforeWaveOneTerritory() {
        // HD daily, Oct 2026 (bearish): I 289.10, II 358.85, III 277.15.
        assertThat(ElliottTargetRules.waveFourRoom(289.10, 358.85, 277.15)).isCloseTo(.1463, within(.0001));
        assertThat(ElliottTargetRules.waveFourRoom(110, 105, 125)).isCloseTo(.75, within(1e-9));
        // Wave III never passed Wave I: there is no legal Wave IV room to measure.
        assertThat(ElliottTargetRules.waveFourRoom(110, 105, 108)).isNaN();
        assertThat(ElliottTargetRules.waveFourRoom(110, 125, 125)).isNaN();
    }

    @Test
    void compressedRoomLowersConfidenceWithoutInvalidatingTheCount() {
        assertThat(detector.compressedWaveFourPenalty(.1463)).isEqualTo(8);
        assertThat(detector.compressedWaveFourPenalty(.01)).isEqualTo(10);
        assertThat(detector.compressedWaveFourPenalty(.235)).isEqualTo(6);
        assertThat(detector.compressedWaveFourPenalty(.236)).isZero();
        assertThat(detector.compressedWaveFourPenalty(.50)).isZero();
        assertThat(detector.compressedWaveFourPenalty(Double.NaN)).isZero();
    }
}
