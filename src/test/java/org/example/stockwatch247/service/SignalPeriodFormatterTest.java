package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class SignalPeriodFormatterTest {
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    @Test
    void formatsDailyWeeklyAndMonthlyCandlePeriods() {
        assertThat(format("2026-07-13T00:00:00Z", TimeInterval.DAILY))
                .isEqualTo("13 Jul 2026");
        assertThat(format("2026-07-13T00:00:00Z", TimeInterval.WEEKLY))
                .isEqualTo("13\u201317 Jul 2026");
        assertThat(format("2026-07-01T00:00:00Z", TimeInterval.MONTHLY))
                .isEqualTo("July 2026");
    }

    @Test
    void preservesBothMonthNamesWhenAWeekCrossesAMonthBoundary() {
        assertThat(format("2026-03-30T00:00:00Z", TimeInterval.WEEKLY))
                .isEqualTo("30 Mar\u20133 Apr 2026");
    }

    private String format(String instant, TimeInterval interval) {
        return SignalPeriodFormatter.format(Instant.parse(instant).getEpochSecond(), interval, BRUSSELS);
    }
}
