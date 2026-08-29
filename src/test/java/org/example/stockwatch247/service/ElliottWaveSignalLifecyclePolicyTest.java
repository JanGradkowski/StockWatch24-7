package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElliottWaveSignalLifecyclePolicyTest {

    @Test
    void usesTheTerminalComplexCorrectionLegsAsTriggerAndEndpoint() {
        List<String> labels = List.of(
                "", "I", "II", "III", "IV", "V",
                "W.A", "W.B", "W.C", "X", "Y.A", "Y.B", "Y.C");
        List<Double> prices = List.of(
                100.0, 140.0, 115.0, 200.0, 170.0, 230.0,
                180.0, 195.0, 165.0, 190.0, 155.0, 175.0, 140.0);
        List<ElliottWaveDetectionService.ElliottWavePoint> points = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            points.add(new ElliottWaveDetectionService.ElliottWavePoint(
                    labels.get(index), (long) index + 1, prices.get(index),
                    index % 2 == 0 ? "LOW" : "HIGH"));
        }
        ElliottWaveDetectionService.ElliottWaveStructure structure =
                new ElliottWaveDetectionService.ElliottWaveStructure(
                        "BULLISH", true, points, 14L, 88,
                        .625, false, 2.125, .35,
                        ElliottWaveDetectionService.ImpulseVariant.STANDARD,
                        ElliottWaveDetectionService.CorrectionVariant.STANDARD,
                        .69, 0.0, List.of("Validated correction: Double zigzag W-X-Y."));

        assertThat(ElliottWaveSignalLifecyclePolicy.boundaries(
                CandlePattern.ELLIOTT_BULLISH_CORRECTION, TradeSignal.BUY, structure))
                .hasValueSatisfying(boundaries -> {
                    assertThat(boundaries.confirmationTrigger()).isEqualTo(175.0);
                    assertThat(boundaries.endpointTimestamp()).isEqualTo(13L);
                    assertThat(boundaries.endpointPrice()).isEqualTo(140.0);
                    assertThat(boundaries.terminalAnchorTimestamp()).isEqualTo(12L);
                });
    }
}
