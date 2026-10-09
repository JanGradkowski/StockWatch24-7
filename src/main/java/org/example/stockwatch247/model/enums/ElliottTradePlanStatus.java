package org.example.stockwatch247.model.enums;

public enum ElliottTradePlanStatus {
    ACTIVE,
    PROJECTION_ONLY,
    TARGET_REACHED,
    STOPPED,
    TIME_STOPPED,
    STRUCTURE_INVALIDATED,
    STAGE_COMPLETED,
    REVISED,
    /** The next candle opened beyond the stop or target, so the entry was never filled. */
    NOT_FILLED
}
