package org.example.stockwatch247.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySizeLimitFilterTest {

    @Test
    void rejectsOversizedChunkedJsonBeforeControllerProcessing() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(1_024);
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/alerts/AAPL");
        request.setContentType("application/json");
        request.setContent(("{\"value\":\"" + "x".repeat(2_000) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("too large");
        assertThat(invoked).isFalse();
    }

    @Test
    void passesAReplayableBodyWhenJsonIsWithinTheLimit() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(1_024);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/alerts/AAPL");
        request.setContentType("application/json; charset=UTF-8");
        byte[] expected = "{\"active\":true}".getBytes(StandardCharsets.UTF_8);
        request.setContent(expected);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(req.getInputStream().readAllBytes());

        filter.doFilter(request, response, chain);

        assertThat(observed.get()).isEqualTo(expected);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
