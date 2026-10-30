package org.example.stockwatch247.model.enums;

public enum CandlePattern {
    ANY,
    BULLISH_ENGULFING,
    BEARISH_ENGULFING,
    DOJI,
    HAMMER,
    INVERTED_HAMMER,
    HANGING_MAN,
    SHOOTING_STAR,
    MORNING_STAR,
    EVENING_STAR,
    PIERCING_LINE,
    DARK_CLOUD_COVER,
    BULLISH_HARAMI,
    BEARISH_HARAMI,
    BULLISH_HARAMI_CROSS,
    BEARISH_HARAMI_CROSS,
    THREE_WHITE_SOLDIERS,
    THREE_BLACK_CROWS,
    ELLIOTT_BULLISH_IMPULSE,
    ELLIOTT_BEARISH_IMPULSE,
    ELLIOTT_BULLISH_WAVE_II_END,
    ELLIOTT_BEARISH_WAVE_II_END,
    ELLIOTT_BULLISH_WAVE_III_END,
    ELLIOTT_BEARISH_WAVE_III_END,
    ELLIOTT_BULLISH_WAVE_IV_END,
    ELLIOTT_BEARISH_WAVE_IV_END,
    ELLIOTT_BULLISH_WAVE_V_END,
    ELLIOTT_BEARISH_WAVE_V_END,
    ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END,
    ELLIOTT_BEARISH_TRUNCATED_WAVE_V_END,
    ELLIOTT_BULLISH_CORRECTION,
    ELLIOTT_BEARISH_CORRECTION,
    ELLIOTT_BULLISH_EXPANDED_FLAT_CORRECTION,
    ELLIOTT_BEARISH_EXPANDED_FLAT_CORRECTION,
    ELLIOTT_BULLISH_RUNNING_FLAT_CORRECTION,
    ELLIOTT_BEARISH_RUNNING_FLAT_CORRECTION,
    HARMONIC_GARTLEY,
    HARMONIC_BAT,
    HARMONIC_BUTTERFLY,
    HARMONIC_CRAB,
    HARMONIC_SHARK,
    HARMONIC_CYPHER,
    HARMONIC_ALTERNATE_BAT,
    HARMONIC_DEEP_CRAB,
    HARMONIC_FIVE_ZERO,
    HARMONIC_AB_CD,
    HARMONIC_ALTERNATE_AB_CD;

    /**
     * The English name pages and emails show ("Elliott Bullish Wave IV End", "Gartley"); the translation
     * tables key Polish names by it.
     */
    public String label() {
        StringBuilder label = new StringBuilder();
        String rawName = name().startsWith("HARMONIC_") ? name().substring("HARMONIC_".length()) : name();
        for (String word : rawName.toLowerCase(java.util.Locale.ROOT).split("_")) {
            if (!label.isEmpty()) label.append(' ');
            // Wave numbers (II, IV, V) and harmonic legs (AB, CD) stay upper case.
            if (word.matches("i{2,3}|iv|v|ab|cd")) label.append(word.toUpperCase(java.util.Locale.ROOT));
            else label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    /**
     * Learning-mode glossary id (js.glossary.&lt;id&gt;) that explains this pattern. Bullish and bearish
     * forms of an Elliott stage share one explanation; ANY has none.
     */
    public String glossaryTerm() {
        if (this == ANY) return null;
        String name = name();
        return name.startsWith("ELLIOTT_")
                ? "elliott." + name.replaceFirst("^ELLIOTT_(BULLISH|BEARISH)_", "")
                : "pattern." + name;
    }
}
