package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The status dot a signal shows on the homepage and in the signal lists: waiting, open (still measured) or done. */
class SignalToneTest {

    @Test
    void aPatternWaitingForConfirmationIsWaitingAndAFinishedOneIsDone() {
        assertThat(AlertRuleService.signalTone(AlertPatternFamily.CANDLESTICK, lifecycle(SignalLifecycleStatus.DETECTED, null, null)))
                .isEqualTo("waiting");
        assertThat(AlertRuleService.signalTone(AlertPatternFamily.CANDLESTICK, lifecycle(SignalLifecycleStatus.POTENTIAL, null, null)))
                .isEqualTo("waiting");
        assertThat(AlertRuleService.signalTone(AlertPatternFamily.CANDLESTICK, lifecycle(SignalLifecycleStatus.CONFIRMED, null, null)))
                .isEqualTo("done");
    }

    @Test
    void anOpenTradePlanOrProjectionIsStillMeasured() {
        assertThat(AlertRuleService.signalTone(AlertPatternFamily.ELLIOTT_WAVE, lifecycle(SignalLifecycleStatus.INVALIDATED, "PROJECTION_ONLY", null)))
                .as("a cancelled Elliott pattern whose projection is still followed").isEqualTo("open");
        assertThat(AlertRuleService.signalTone(AlertPatternFamily.ELLIOTT_WAVE, lifecycle(SignalLifecycleStatus.DETECTED, "ACTIVE", null)))
                .as("a detected pattern still waits for confirmation").isEqualTo("waiting");
        assertThat(AlertRuleService.signalTone(AlertPatternFamily.HARMONIC_FORMATION, lifecycle(SignalLifecycleStatus.DETECTED, null, "ACTIVE")))
                .as("a harmonic trade already open with its stop is running, not waiting").isEqualTo("open");
        assertThat(AlertRuleService.signalTone(AlertPatternFamily.HARMONIC_FORMATION, lifecycle(SignalLifecycleStatus.DETECTED, null, "STOPPED")))
                .isEqualTo("done");
    }

    private static AlertRuleService.SignalLifecycleView lifecycle(SignalLifecycleStatus status, String elliottPlan, String harmonicStop) {
        AlertRuleService.SignalLifecycleView view = mock(AlertRuleService.SignalLifecycleView.class);
        when(view.status()).thenReturn(status);
        when(view.elliottTradePlanStatus()).thenReturn(elliottPlan);
        when(view.harmonicStopStatus()).thenReturn(harmonicStop);
        return view;
    }
}
