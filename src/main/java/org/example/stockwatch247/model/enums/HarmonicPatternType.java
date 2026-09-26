package org.example.stockwatch247.model.enums;

public enum HarmonicPatternType {
    GARTLEY("Gartley"),
    BAT("Bat"),
    BUTTERFLY("Butterfly"),
    CRAB("Crab"),
    SHARK("Shark"),
    CYPHER("Cypher"),
    ALTERNATE_BAT("Alternate Bat"),
    DEEP_CRAB("Deep Crab"),
    FIVE_ZERO("5-0"),
    AB_CD("AB=CD"),
    ALTERNATE_AB_CD("Alternate AB=CD");

    private final String displayName;

    HarmonicPatternType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
