package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stockwatch247.service.ConflictException;
import org.example.stockwatch247.service.MarketDataUnavailableException;
import org.example.stockwatch247.service.TechnicalOutlookCapacityException;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.congress.CongressionalRefreshLimitException;
import org.example.stockwatch247.service.insider.InsiderDataUnavailableException;
import org.example.stockwatch247.i18n.ServerMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns failures into JSON for API calls and into the error page for browser navigation.
 * Only {@link UserInputException} and {@link ConflictException} messages are shown to users;
 * any other {@link IllegalStateException} is a server fault and answers 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public Object requestStatus(ResponseStatusException exception, HttpServletRequest request) {
        String reason = exception.getReason() == null ? "Request could not be completed." : exception.getReason();
        return respond(request, exception.getStatusCode(), Map.of("error", reason), reason, null);
    }

    @ExceptionHandler(UserInputException.class)
    public Object userInput(UserInputException exception, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST,
                Map.of("error", exception.getMessage()), exception.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object invalidRequest(IllegalArgumentException exception, HttpServletRequest request) {
        log.debug("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), exception.toString());
        return respond(request, HttpStatus.BAD_REQUEST, Map.of("error", "Invalid request."), null, null);
    }

    @ExceptionHandler(CongressionalRefreshLimitException.class)
    public ResponseEntity<Map<String, String>> congressionalRefreshLimited(
            CongressionalRefreshLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(Map.of("error", ServerMessages.translate(exception.getMessage())));
    }

    @ExceptionHandler(InsiderDataUnavailableException.class)
    public Object insiderDataUnavailable(InsiderDataUnavailableException exception,
                                         HttpServletRequest request) {
        log.warn("Insider data unavailable on {} {}: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());
        return respond(request, HttpStatus.SERVICE_UNAVAILABLE,
                Map.of("error", exception.getMessage(), "code", "INSIDER_DATA_UNAVAILABLE"),
                exception.getMessage(), null);
    }

    @ExceptionHandler(MarketDataUnavailableException.class)
    public Object marketDataUnavailable(MarketDataUnavailableException exception,
                                        HttpServletRequest request) {
        log.warn("Market data unavailable on {} {} for {} {}: {}",
                request.getMethod(), request.getRequestURI(), exception.symbol(), exception.interval(),
                exception.diagnosticMessage());
        return respond(request, HttpStatus.SERVICE_UNAVAILABLE,
                Map.of("error", exception.getMessage(), "code", "CANDLE_DATA_UNAVAILABLE"),
                exception.getMessage(), null);
    }

    @ExceptionHandler(TechnicalOutlookCapacityException.class)
    public Object technicalOutlookCapacity(TechnicalOutlookCapacityException exception,
                                           HttpServletRequest request) {
        return respond(request, HttpStatus.CONFLICT,
                Map.of("error", exception.getMessage(), "code", "TECHNICAL_OUTLOOK_CAPACITY"),
                exception.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    public Object conflict(ConflictException exception, HttpServletRequest request) {
        return respond(request, HttpStatus.CONFLICT,
                Map.of("error", exception.getMessage()), exception.getMessage(), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public Object serverFault(IllegalStateException exception, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.error("Request {} failed on {} {}", requestId, request.getMethod(), request.getRequestURI(), exception);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR,
                Map.of("error", "The request could not be completed.", "requestId", requestId), null, requestId);
    }

    /** A mistyped address in the browser gets the error page; a missing asset or API path stays an empty 404. */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object missingResource(NoResourceFoundException exception,
                                  HttpServletRequest request) {
        log.debug("Resource not found on {} {}", request.getMethod(), request.getRequestURI());
        if (!rendersPage(request, null)) {
            return ResponseEntity.notFound().build();
        }
        return new ModelAndView("error", Map.of("status", HttpStatus.NOT_FOUND.value()), HttpStatus.NOT_FOUND);
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
    public Object unexpected(Exception exception, HttpServletRequest request) {
        HttpStatusCode clientError = clientErrorStatus(exception);
        if (clientError != null) {
            // A missing or malformed parameter, an unreadable body or a wrong method is the caller's
            // mistake: answer with its 4xx status instead of reporting a server failure.
            log.debug("Rejected {} {} with {}: {}", request.getMethod(), request.getRequestURI(),
                    clientError.value(), exception.toString());
            return respond(request, clientError, Map.of("error", "Invalid request."), null, null);
        }
        String requestId = UUID.randomUUID().toString();
        log.error("Unexpected request failure {} on {} {}",
                requestId, request.getMethod(), request.getRequestURI(), exception);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR,
                Map.of("error", "An unexpected error occurred.", "requestId", requestId), null, requestId);
    }

    private Object respond(HttpServletRequest request, HttpStatusCode status,
                           Map<String, String> body, String userMessage, String requestId) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        // Messages written for users are shown in their language; see ServerMessages.
        Map<String, String> translated = new LinkedHashMap<>(body);
        translated.computeIfPresent("error", (key, message) -> ServerMessages.translate(message));
        if (!rendersPage(request, handler instanceof HandlerMethod method ? method : null)) {
            return ResponseEntity.status(status).body(translated);
        }
        Map<String, Object> model = new HashMap<>();
        model.put("status", status.value());
        if (userMessage != null) model.put("userMessage", ServerMessages.translate(userMessage));
        if (requestId != null) model.put("requestId", requestId);
        return new ModelAndView("error", model, status);
    }

    /** A handler that returns a view name answers browser navigation; everything else is an API call. */
    static boolean rendersPage(HttpServletRequest request, HandlerMethod handler) {
        if (handler == null) {
            String accept = request.getHeader(HttpHeaders.ACCEPT);
            return !request.getRequestURI().startsWith(request.getContextPath() + "/api/")
                    && accept != null && accept.contains("text/html");
        }
        if (handler.hasMethodAnnotation(ResponseBody.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), ResponseBody.class)) {
            return false;
        }
        Class<?> returnType = handler.getReturnType().getParameterType();
        return !HttpEntity.class.isAssignableFrom(returnType);
    }

    static HttpStatusCode clientErrorStatus(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse response
                && response.getStatusCode().is4xxClientError()) {
            return response.getStatusCode();
        }
        if (exception instanceof org.springframework.beans.TypeMismatchException
                || exception instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return HttpStatus.BAD_REQUEST;
        }
        return null;
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
