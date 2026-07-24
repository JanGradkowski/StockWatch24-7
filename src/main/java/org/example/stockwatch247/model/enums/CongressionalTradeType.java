package org.example.stockwatch247.model.enums;

import java.util.Locale;
import java.util.Optional;

public enum CongressionalTradeType {
    PURCHASE("Purchase"),
    SALE("Sale");

    private final String label;

    CongressionalTradeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static Optional<CongressionalTradeType> fromProviderValue(String rawValue) {
        String normalized = rawValue == null
                ? ""
                : rawValue.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("BUY") || normalized.contains("PURCHASE")) {
            return Optional.of(PURCHASE);
        }
        if (normalized.contains("SELL") || normalized.contains("SALE")) {
            return Optional.of(SALE);
        }
        return Optional.empty();
    }
}
