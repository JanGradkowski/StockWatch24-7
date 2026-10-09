package org.example.stockwatch247.service;

/**
 * A validation failure whose message was written for the person using the app.
 * Other exceptions may carry library or provider text and must never be shown verbatim.
 */
public class UserInputException extends IllegalArgumentException {
    public UserInputException(String message) {
        super(message);
    }
}
