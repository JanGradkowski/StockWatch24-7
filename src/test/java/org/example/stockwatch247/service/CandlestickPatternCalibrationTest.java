package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.CandlePattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandlestickPatternCalibrationTest {

    @Test
    void weeklyCalibrationRewardsSupportedBullishPatternsAndPenalizesWeakBearishPatterns() {
        CandlestickPatternCalibration.Assessment hammer = CandlestickPatternCalibration.assess(
                CandlePattern.HAMMER,
                CandlestickPatternCalibration.Timeframe.WEEKLY
        );
        CandlestickPatternCalibration.Assessment shootingStar = CandlestickPatternCalibration.assess(
                CandlePattern.SHOOTING_STAR,
                CandlestickPatternCalibration.Timeframe.WEEKLY
        );

        assertThat(hammer.points()).isEqualTo(10);
        assertThat(shootingStar.points()).isZero();
        assertThat(hammer.detail()).contains("pre-2020", "not a probability");
    }

    @Test
    void monthlyCalibrationUsesItsOwnFrozenProfile() {
        CandlestickPatternCalibration.Assessment monthly = CandlestickPatternCalibration.assess(
                CandlePattern.INVERTED_HAMMER,
                CandlestickPatternCalibration.Timeframe.MONTHLY
        );
        CandlestickPatternCalibration.Assessment weekly = CandlestickPatternCalibration.assess(
                CandlePattern.INVERTED_HAMMER,
                CandlestickPatternCalibration.Timeframe.WEEKLY
        );

        assertThat(monthly.points()).isEqualTo(9);
        assertThat(weekly.points()).isEqualTo(8);
    }

    @Test
    void tinySamplePatternStaysNeutral() {
        CandlestickPatternCalibration.Assessment assessment = CandlestickPatternCalibration.assess(
                CandlePattern.THREE_WHITE_SOLDIERS,
                CandlestickPatternCalibration.Timeframe.WEEKLY
        );

        assertThat(assessment.points()).isEqualTo(CandlestickPatternCalibration.NEUTRAL_POINTS);
        assertThat(assessment.detail()).contains("too few");
    }
}
