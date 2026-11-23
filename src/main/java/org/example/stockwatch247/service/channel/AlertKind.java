package org.example.stockwatch247.service.channel;

import java.util.EnumSet;
import java.util.Set;

/** What an alert is about, for choosing which channels receive it. Mirrors the email switches. */
public enum AlertKind {
    SIGNAL_CANDLESTICK, SIGNAL_ELLIOTT, SIGNAL_HARMONIC,
    OUTCOME_CONFIRMED, OUTCOME_INVALIDATED, OUTCOME_EXPIRED,
    OUTLOOK, CHART, CUSTOM, INSIDER, CONGRESS;

    /** The kinds the email summary collects too: new signals, their follow-ups and outlook changes. */
    public static final Set<AlertKind> SUMMARIZABLE = EnumSet.of(SIGNAL_CANDLESTICK, SIGNAL_ELLIOTT, SIGNAL_HARMONIC,
            OUTCOME_CONFIRMED, OUTCOME_INVALIDATED, OUTCOME_EXPIRED, OUTLOOK);

    public boolean signal() {
        return this == SIGNAL_CANDLESTICK || this == SIGNAL_ELLIOTT || this == SIGNAL_HARMONIC;
    }

    public static AlertKind parse(String value) {
        try {
            return value == null ? null : valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
