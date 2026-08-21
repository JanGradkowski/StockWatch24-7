package org.example.stockwatch247.model.enums;

public enum HarmonicPatternType {
    GARTLEY("Gartley"),
    BAT("Bat"),
    BUTTERFLY("Butterfly"),
    CRAB("Crab"),
    SHARK("Shark"),
    CYPHER("Cypher");

    private final String displayName;

    HarmonicPatternType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
