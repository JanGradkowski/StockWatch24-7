package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicStopStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Harmonic V3 plans take half at the first target, move the stop to entry, and exit the rest later. */
class HarmonicScaleOutTest {
    private static final double ENTRY = 100;
    private static final double STOP = 95;
    private static final double FIRST = 106;
    private static final double SECOND = 112;

    @Test
    void bothTargetsGiveTheAverageOfTheTwoExits() {
        var exit = replay(bar(1, 101, 106.5, 100.5, 105), bar(2, 105, 113, 104, 112.5));

        assertThat(exit.status()).isEqualTo(HarmonicStopStatus.TARGET_REACHED);
        assertThat(exit.averageExit()).isEqualTo((FIRST + SECOND) / 2);
        assertThat(exit.candle().getTimestamp()).isEqualTo(2L);
    }

    @Test
    void afterTheFirstTargetTheStopIsBreakevenNotTheOriginalStop() {
        var exit = replay(bar(1, 101, 106.5, 100.5, 105), bar(2, 104, 104.5, 99.0, 99.5));

        assertThat(exit.status()).isEqualTo(HarmonicStopStatus.TARGET_REACHED);
        assertThat(exit.averageExit()).isEqualTo((FIRST + ENTRY) / 2);
        assertThat(exit.reason()).contains("breakeven");
    }

    @Test
    void aStopBeforeTheFirstTargetLosesTheWholePosition() {
        var exit = replay(bar(1, 99, 101, 94, 96));

        assertThat(exit.status()).isEqualTo(HarmonicStopStatus.STOPPED);
        assertThat(exit.averageExit()).isEqualTo(STOP);
    }

    @Test
    void theHorizonClosesTheRemainingHalfAtTheCompletedClose() {
        var exit = HarmonicStopPlanService.replayScaleOut(TradeSignal.BUY, ENTRY, STOP, FIRST, SECOND,
                List.of(bar(1, 101, 106.5, 100.5, 105), bar(2, 105, 108, 103, 107)), 2);

        assertThat(exit.status()).isEqualTo(HarmonicStopStatus.TIME_STOPPED);
        assertThat(exit.averageExit()).isEqualTo((FIRST + 107) / 2);
    }

    @Test
    void anOpenPlanReturnsNothingYet() {
        assertThat(replay(bar(1, 101, 104, 99, 103))).isNull();
    }

    @Test
    void qualificationUsesTheBlendedTargetAndTheScaleOutMinimum() {
        assertThat(HarmonicStopPlanPolicy.blendedTarget(FIRST, SECOND)).isEqualTo(109.0);
        assertThat(HarmonicStopPlanPolicy.blendedTarget(FIRST, null)).isEqualTo(FIRST);
        assertThat(HarmonicStopPlanPolicy.scaleOutRequiredRewardRisk(TimeInterval.DAILY)).isEqualTo(1.5);
        assertThat(HarmonicStopPlanPolicy.scaleOutRequiredRewardRisk(TimeInterval.WEEKLY)).isEqualTo(2.0);
    }

    private static HarmonicStopPlanService.ScaleOutExit replay(Candle... bars) {
        return HarmonicStopPlanService.replayScaleOut(TradeSignal.BUY, ENTRY, STOP, FIRST, SECOND, List.of(bars), 8);
    }

    private static Candle bar(long timestamp, double open, double high, double low, double close) {
        return new Candle("TEST", "1d", timestamp, open, high, low, close, 1_000L);
    }
}
