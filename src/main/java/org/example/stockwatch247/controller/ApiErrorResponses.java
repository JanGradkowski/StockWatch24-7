package org.example.stockwatch247.controller;

import org.example.stockwatch247.i18n.ServerMessages;
import org.example.stockwatch247.service.ConflictException;
import org.example.stockwatch247.service.MarketDataUnavailableException;
import org.example.stockwatch247.service.UserInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Maps service failures to JSON error bodies. Only messages written for users are echoed;
 * library, provider and database text is logged and replaced with a generic sentence.
 */
final class ApiErrorResponses {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorResponses.class);

    private ApiErrorResponses() {
    }

    static ResponseEntity<Map<String, String>> of(RuntimeException error, String invalidMessage, String failureMessage) {
        if (error instanceof UserInputException) {
            return body(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        if (error instanceof ConflictException) {
            return body(HttpStatus.CONFLICT, error.getMessage());
        }
        if (error instanceof MarketDataUnavailableException) {
            return body(HttpStatus.SERVICE_UNAVAILABLE,
                    "Market data is temporarily unavailable. Try again in a few minutes.");
        }
        if (error instanceof ResponseStatusException status) {
            return body(HttpStatus.valueOf(status.getStatusCode().value()),
                    status.getReason() == null ? failureMessage : status.getReason());
        }
        if (error instanceof IllegalArgumentException) {
            log.debug("Rejected request: {}", error.toString());
            return body(HttpStatus.BAD_REQUEST, invalidMessage);
        }
        if (error instanceof IllegalStateException) {
            String requestId = UUID.randomUUID().toString();
            log.error("Request {} failed", requestId, error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ServerMessages.translate(failureMessage), "requestId", requestId));
        }
        throw error;
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", ServerMessages.translate(message)));
    }
}
