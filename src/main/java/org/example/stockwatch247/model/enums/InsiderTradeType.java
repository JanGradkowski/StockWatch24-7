package org.example.stockwatch247.model.enums;

import java.util.Locale;
import java.util.Optional;

public enum InsiderTradeType {
    PURCHASE("Purchase"),
    SALE("Sale");

    private final String label;

    InsiderTradeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static Optional<InsiderTradeType> fromProviderValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("P") || normalized.startsWith("P-")
                || normalized.equals("PURCHASE")) {
            return Optional.of(PURCHASE);
        }
        if (normalized.equals("S") || normalized.startsWith("S-")
                || normalized.equals("SALE")) {
            return Optional.of(SALE);
        }
        return Optional.empty();
    }
}
