package org.example.stockwatch247.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CspNonceFilterTest {

    @Test
    void technicalOutlookChangeAllowsChartGeneratedStyleAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/technical-outlook/changes/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CspNonceFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("style-src-attr 'unsafe-inline'");
        assertThat(request.getAttribute("cspNonce")).isNotNull();
    }

    @Test
    void unrelatedRoutesKeepStrictStyleAttributePolicy() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CspNonceFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("style-src-attr 'none'");
    }
}
