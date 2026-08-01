package org.example.stockwatch247.service.insider;

/**
 * A user-safe failure raised when the configured insider-data provider cannot
 * serve a request. The message is intentionally suitable for an API response.
 */
public class InsiderDataUnavailableException extends IllegalStateException {

    public InsiderDataUnavailableException(String message) {
        super(message);
    }

    public InsiderDataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
