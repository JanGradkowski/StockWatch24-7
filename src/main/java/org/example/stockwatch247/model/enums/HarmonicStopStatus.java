package org.example.stockwatch247.model.enums;

public enum HarmonicStopStatus {
    ACTIVE,
    PROJECTION_ONLY,
    TARGET_REACHED,
    STOPPED,
    TIME_STOPPED,
    /** The next candle opened beyond the stop or target, so the entry was never filled. */
    NOT_FILLED
}
