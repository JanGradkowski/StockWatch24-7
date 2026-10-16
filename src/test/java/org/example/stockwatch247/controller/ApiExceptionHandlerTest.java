package org.example.stockwatch247.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.stockwatch247.service.ConflictException;
import org.example.stockwatch247.service.MarketDataUnavailableException;
import org.example.stockwatch247.service.TechnicalOutlookCapacityException;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.congress.CongressionalRefreshLimitException;
import org.example.stockwatch247.service.insider.InsiderDataUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void missingStaticResourceReturnsNotFound() {
        MockHttpServletRequest request = request("GET", "/favicon.ico");

        ResponseEntity<Void> response = handler.missingResource(
                mock(NoResourceFoundException.class), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void canceledClientConnectionIsHandledWithoutWritingAnotherResponse() {
        MockHttpServletRequest request = request("GET", "/api/stocks/AAPL/candles");

        assertThatCode(() -> handler.clientDisconnected(
                new AsyncRequestNotUsableException("response already closed"), request))
                .doesNotThrowAnyException();
    }

    @Test
    void wrappedClientAbortWhileWritingJsonIsAlsoHandledQuietly() {
        HttpMessageNotWritableException wrapped = new HttpMessageNotWritableException(
                "Could not write JSON",
                new IOException("An established connection was aborted by the software in your host machine"));

        assertThatCode(() -> handler.responseWriteFailed(
                wrapped, request("GET", "/api/stocks/NFLX/technical-outlook")))
                .doesNotThrowAnyException();
    }

    @Test
    void unexpectedFailureLogsItsStackTraceAndReturnsTraceableRequestId() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean originalAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        RuntimeException failure = new RuntimeException("diagnostic failure");

        try {
            ResponseEntity<Map<String, String>> response = json(handler.unexpected(
                    failure, request("GET", "/api/stocks/AAPL/candles")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).containsEntry("error", "An unexpected error occurred.");
            assertThat(response.getBody().get("requestId")).isNotBlank();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains(response.getBody().get("requestId"))
                        .contains("GET /api/stocks/AAPL/candles");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName())
                        .isEqualTo(RuntimeException.class.getName());
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("diagnostic failure");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }
    }

    @Test
    void congressionalRefreshLimitReturns429AndRetryAfter() {
        ResponseEntity<Map<String, String>> response = handler.congressionalRefreshLimited(
                new CongressionalRefreshLimitException(
                        "Only two uncached refreshes are allowed per minute.",
                        60L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(response.getBody())
                .containsEntry("error", "Only two uncached refreshes are allowed per minute.");
    }

    @Test
    void technicalOutlookCapacityReturnsItsSafeActionableMessage() {
        ResponseEntity<Map<String, String>> response = json(handler.technicalOutlookCapacity(
                new TechnicalOutlookCapacityException("You can track at most 300 companies."),
                request("POST", "/api/stocks/AAPL/technical-outlook/subscription")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("error", "You can track at most 300 companies.")
                .containsEntry("code", "TECHNICAL_OUTLOOK_CAPACITY");
    }

    @Test
    void insiderProviderFailureReturnsItsSafeMessage() {
        ResponseEntity<Map<String, String>> response = json(handler.insiderDataUnavailable(
                new InsiderDataUnavailableException(
                        "The API Ninjas insider-data quota has been reached."),
                request("GET", "/api/insider-activity/AAPL/history")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("code", "INSIDER_DATA_UNAVAILABLE")
                .containsEntry(
                        "error",
                        "The API Ninjas insider-data quota has been reached.");
    }

    @Test
    void candleProviderFailureReturnsSpecificServiceUnavailableResponse() {
        ResponseEntity<Map<String, String>> response = json(handler.marketDataUnavailable(
                new MarketDataUnavailableException(
                        "CDR",
                        "1mo",
                        "Twelve Data plan restriction; Yahoo Finance unavailable"),
                request("GET", "/api/stocks/CDR/candlestick-patterns/history")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("code", "CANDLE_DATA_UNAVAILABLE")
                .containsEntry("error", "Monthly candle data is temporarily unavailable for CDR.")
                .doesNotContainValue("Twelve Data plan restriction; Yahoo Finance unavailable");
    }

    @Test
    void anUnclassifiedStateFailureIsAServerFaultNotAConflict() {
        ResponseEntity<Map<String, String>> response = json(handler.serverFault(
                new IllegalStateException("SHA-256 is unavailable."), request("POST", "/api/alerts/AAPL")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "The request could not be completed.")
                .doesNotContainValue("SHA-256 is unavailable.");
        assertThat(response.getBody().get("requestId")).isNotBlank();
    }

    @Test
    void writtenForUserMessagesAreShownAndOthersStayGeneric() {
        assertThat(json(handler.conflict(new ConflictException("Global market data capacity has been reached."),
                request("POST", "/api/alerts/AAPL"))).getBody())
                .containsEntry("error", "Global market data capacity has been reached.");
        assertThat(json(handler.userInput(new UserInputException("Choose a watchlist before enabling monitoring."),
                request("PUT", "/api/alerts/AAPL"))).getBody())
                .containsEntry("error", "Choose a watchlist before enabling monitoring.");
        assertThat(json(handler.invalidRequest(new IllegalArgumentException("No enum constant TimeInterval.X"),
                request("PUT", "/api/alerts/AAPL"))).getBody())
                .containsEntry("error", "Invalid request.");
    }

    @Test
    void browserNavigationGetsTheErrorPageInsteadOfJson() throws Exception {
        MockHttpServletRequest page = request("GET", "/technical-outlook/changes/9");
        page.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(new PageController(), PageController.class.getMethod("page")));

        Object result = handler.invalidRequest(new IllegalArgumentException("not found"), page);

        assertThat(result).isInstanceOfSatisfying(ModelAndView.class, view -> {
            assertThat(view.getViewName()).isEqualTo("error");
            assertThat(view.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(view.getModel()).containsEntry("status", 400).doesNotContainKey("userMessage");
        });
    }

    @Test
    void responseBodyHandlersOnPageControllersStillAnswerWithJson() throws Exception {
        MockHttpServletRequest api = request("GET", "/api/watchlists");
        api.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(new PageController(), PageController.class.getMethod("data")));

        assertThat(handler.invalidRequest(new IllegalArgumentException("bad"), api)).isInstanceOf(ResponseEntity.class);
    }

    static class PageController {
        public String page() { return "technical-outlook-change"; }
        @org.springframework.web.bind.annotation.ResponseBody
        public Map<String, String> data() { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<Map<String, String>> json(Object response) {
        assertThat(response).isInstanceOf(ResponseEntity.class);
        return (ResponseEntity<Map<String, String>>) response;
    }

    @Test
    @SuppressWarnings("unchecked")
    void callerMistakesAnswerWithTheirClientStatusInsteadOfAServerFault() {
        var missing = new org.springframework.web.bind.MissingServletRequestParameterException("token", "String");
        var method = new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE");
        var mismatch = new org.springframework.beans.TypeMismatchException("abc", Long.class);

        assertThat(ApiExceptionHandler.clientErrorStatus(missing)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ApiExceptionHandler.clientErrorStatus(method)).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(ApiExceptionHandler.clientErrorStatus(mismatch)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ApiExceptionHandler.clientErrorStatus(new RuntimeException("boom"))).isNull();

        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                handler.unexpected(missing, request("GET", "/api/stocks/AAPL/candles"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContainKey("requestId");
    }

    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
