package org.example.stockwatch247.service;

/**
 * An expected refusal caused by the current state, such as a reached limit, with a message
 * written for the user. Any other {@link IllegalStateException} is treated as a server fault.
 */
public class ConflictException extends IllegalStateException {
    public ConflictException(String message) {
        super(message);
    }
}
