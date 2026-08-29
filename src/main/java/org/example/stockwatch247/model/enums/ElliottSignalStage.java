package org.example.stockwatch247.model.enums;

/**
 * The independently actionable turning points within one Elliott cycle.
 */
public enum ElliottSignalStage {
    WAVE_II_END,
    WAVE_III_END,
    WAVE_IV_END,
    WAVE_V_END,
    CORRECTION_END;

    public boolean isDevelopingImpulseStage() {
        return this == WAVE_II_END || this == WAVE_III_END || this == WAVE_IV_END;
    }

    public int progressionOrder() {
        return switch (this) {
            case WAVE_II_END -> 2;
            case WAVE_III_END -> 3;
            case WAVE_IV_END -> 4;
            case WAVE_V_END -> 5;
            case CORRECTION_END -> 6;
        };
    }
}
