package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> stateConflict(IllegalStateException exception) {
        String requestId = UUID.randomUUID().toString();
        log.warn("Request {} could not be completed: {}", requestId, exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "The request could not be completed.",
                "requestId", requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception exception, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.error("Unexpected request failure {} on {} {}", requestId, request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "An unexpected error occurred.",
                "requestId", requestId));
    }
}
