package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeRiskPolicySidesTest {

    @Test
    void aBuyNeedsTheStopBelowAndTheTargetAboveTheEntry() {
        assertThat(TradeRiskPolicy.validSides(TradeSignal.BUY, 100, 95, 110)).isTrue();
        // Entry re-stamped at a later close that has already passed the target.
        assertThat(TradeRiskPolicy.validSides(TradeSignal.BUY, 112, 95, 110)).isFalse();
        // Entry re-stamped below the structural stop.
        assertThat(TradeRiskPolicy.validSides(TradeSignal.BUY, 94, 95, 110)).isFalse();
    }

    @Test
    void aSellNeedsTheStopAboveAndTheTargetBelowTheEntry() {
        assertThat(TradeRiskPolicy.validSides(TradeSignal.SELL, 100, 105, 90)).isTrue();
        assertThat(TradeRiskPolicy.validSides(TradeSignal.SELL, 88, 105, 90)).isFalse();
        assertThat(TradeRiskPolicy.validSides(TradeSignal.SELL, 100, 95, 90)).isFalse();
        assertThat(TradeRiskPolicy.validSides(TradeSignal.HOLD, 100, 95, 110)).isFalse();
        assertThat(TradeRiskPolicy.validSides(TradeSignal.BUY, 100, Double.NaN, 110)).isFalse();
    }
}
