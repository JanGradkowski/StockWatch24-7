package org.example.stockwatch247.service;

/**
 * A safe, user-facing capacity rejection for Automated Technical Outlook follows.
 */
public class TechnicalOutlookCapacityException extends ConflictException {
    public TechnicalOutlookCapacityException(String message) {
        super(message);
    }
}
