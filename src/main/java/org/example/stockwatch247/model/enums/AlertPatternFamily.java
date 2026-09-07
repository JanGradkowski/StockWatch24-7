package org.example.stockwatch247.model.enums;

public enum AlertPatternFamily {
    CANDLESTICK,
    ELLIOTT_WAVE,
    HARMONIC_FORMATION;

    public static AlertPatternFamily forPattern(CandlePattern pattern) {
        if (pattern != null && pattern.name().startsWith("ELLIOTT_")) return ELLIOTT_WAVE;
        if (pattern != null && pattern.name().startsWith("HARMONIC_")) return HARMONIC_FORMATION;
        return CANDLESTICK;
    }
}
