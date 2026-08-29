package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stockwatch247.service.MarketDataUnavailableException;
import org.example.stockwatch247.service.TechnicalOutlookCapacityException;
import org.example.stockwatch247.service.congress.CongressionalRefreshLimitException;
import org.example.stockwatch247.service.insider.InsiderDataUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request."));
    }

    @ExceptionHandler(CongressionalRefreshLimitException.class)
    public ResponseEntity<Map<String, String>> congressionalRefreshLimited(
            CongressionalRefreshLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(InsiderDataUnavailableException.class)
    public ResponseEntity<Map<String, String>> insiderDataUnavailable(
            InsiderDataUnavailableException exception,
            HttpServletRequest request) {
        log.warn("Insider data unavailable on {} {}: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "code", "INSIDER_DATA_UNAVAILABLE"));
    }

    @ExceptionHandler(MarketDataUnavailableException.class)
    public ResponseEntity<Map<String, String>> marketDataUnavailable(
            MarketDataUnavailableException exception,
            HttpServletRequest request) {
        log.warn("Market data unavailable on {} {} for {} {}: {}",
                request.getMethod(), request.getRequestURI(), exception.symbol(), exception.interval(),
                exception.diagnosticMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "code", "CANDLE_DATA_UNAVAILABLE"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> stateConflict(IllegalStateException exception) {
        String requestId = UUID.randomUUID().toString();
        log.warn("Request {} could not be completed: {}", requestId, exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "The request could not be completed.",
                "requestId", requestId));
    }

    @ExceptionHandler(TechnicalOutlookCapacityException.class)
    public ResponseEntity<Map<String, String>> technicalOutlookCapacity(
            TechnicalOutlookCapacityException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", exception.getMessage(),
                "code", "TECHNICAL_OUTLOOK_CAPACITY"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> missingResource(NoResourceFoundException exception,
                                                HttpServletRequest request) {
        log.debug("Resource not found on {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientDisconnected(AsyncRequestNotUsableException exception,
                                   HttpServletRequest request) {
        // The response stream is already unusable, so do not attempt to write
        // another response or report an expected browser cancellation as a 500.
        log.debug("Client disconnected while processing {} {}", request.getMethod(), request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public void responseWriteFailed(HttpMessageNotWritableException exception,
                                    HttpServletRequest request) {
        if (isClientDisconnect(exception)) {
            log.debug("Client disconnected while writing {} {}", request.getMethod(), request.getRequestURI());
            return;
        }
        // A response may already be committed, so attempting to serialize a
        // second JSON error can create another write failure. Preserve the
        // diagnostic without writing to the unusable response.
        log.error("Response serialization failed on {} {}",
                request.getMethod(), request.getRequestURI(), exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception exception, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.error("Unexpected request failure {} on {} {}",
                requestId, request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "An unexpected error occurred.",
                "requestId", requestId));
    }

    private boolean isClientDisconnect(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String type = current.getClass().getSimpleName();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (current instanceof AsyncRequestNotUsableException
                    || type.equals("ClientAbortException")
                    || message.contains("connection was aborted")
                    || message.contains("broken pipe")
                    || message.contains("connection reset")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
