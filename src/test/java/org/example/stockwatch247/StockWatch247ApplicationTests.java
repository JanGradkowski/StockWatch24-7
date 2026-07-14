package org.example.stockwatch247;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@AutoConfigureMockMvc
class StockWatch247ApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void publicResponsesContainSecurityHeaders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src-attr 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void protectedApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/stocks/search").param("query", "AAPL"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void authenticatedStateChangeWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/alerts/AAPL")
                        .with(user("security-test@example.com"))
                        .contentType("application/json")
                        .content("{\"interval\":\"DAILY\",\"signal\":\"BUY\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void indexAliasIsSearchableAndNavigatesWithTheCanonicalSymbol() throws Exception {
        mockMvc.perform(get("/api/stocks/search")
                        .param("q", "SPX")
                        .with(user("index-test@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("^GSPC"))
                .andExpect(jsonPath("$[0].instrumentType").value("INDEX"));

        mockMvc.perform(get("/stock/SPX").with(user("index-test@example.com")))
                .andExpect(status().isOk())
                .andExpect(view().name("stock"))
                .andExpect(model().attribute("symbol", "^GSPC"));
    }

}
