package org.example.stockwatch247.model.enums;

import java.util.Locale;

public enum InstrumentType {
    EQUITY,
    ETF,
    INDEX,
    OTHER;

    public static InstrumentType fromProviderValue(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim().toUpperCase(Locale.ROOT);
        if (value.contains("INDEX")) {
            return INDEX;
        }
        if (value.contains("ETF") || value.contains("EXCHANGE TRADED FUND")) {
            return ETF;
        }
        if (value.contains("EQUITY") || value.contains("STOCK")) {
            return EQUITY;
        }
        return OTHER;
    }
}
