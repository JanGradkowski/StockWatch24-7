package org.example.stockwatch247.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.stockwatch247.service.congress.CongressionalRefreshLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

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
    void unexpectedFailureLogsItsStackTraceAndReturnsTraceableRequestId() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean originalAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        RuntimeException failure = new RuntimeException("diagnostic failure");

        try {
            ResponseEntity<Map<String, String>> response = handler.unexpected(
                    failure, request("GET", "/api/stocks/AAPL/candles"));

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

    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
