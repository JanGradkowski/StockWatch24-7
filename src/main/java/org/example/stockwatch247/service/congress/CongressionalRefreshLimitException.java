package org.example.stockwatch247.service.congress;

public final class CongressionalRefreshLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public CongressionalRefreshLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
