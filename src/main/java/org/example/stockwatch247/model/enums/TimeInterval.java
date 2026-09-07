package org.example.stockwatch247.model.enums;

public enum TimeInterval {
    FIFTEEN_MINUTE,
    ONE_HOUR,
    FOUR_HOUR,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    ALL_TIME;

    /** Legacy analysis defaults to daily outside the supported daily/weekly/monthly set. */
    public String analysisApiValue() {
        return switch (this) { case WEEKLY -> "1wk"; case MONTHLY -> "1mo"; default -> "1d"; };
    }

    public String analysisLabel() {
        return switch (this) { case WEEKLY -> "Weekly"; case MONTHLY -> "Monthly"; default -> "Daily"; };
    }
}
