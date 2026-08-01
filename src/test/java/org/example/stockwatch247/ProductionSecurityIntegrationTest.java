package org.example.stockwatch247;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.public-base-url=https://stockwatch.example",
        "alerts.email.enabled=true",
        "alerts.email.from=no-reply@stockwatch.example",
        "spring.mail.host=smtp.example",
        "spring.mail.username=mailer",
        "spring.mail.password=test-only-password",
        "security.mfa.encryption-key=test-only-mfa-encryption-key-that-is-at-least-32-characters",
        "spring.flyway.enabled=true",
        "alerts.schedule.enabled=false",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1"
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProductionSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void productionContextStartsAndRedirectsPlainHttpToHttps() throws Exception {
        mockMvc.perform(get("/login").secure(false))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://")))
                .andExpect(header().string("Location", not(containsString(";jsessionid="))));
    }

    @Test
    void secureProductionResponseContainsHstsAndStrictDefaultStylePolicy() throws Exception {
        mockMvc.perform(get("/login").with(request -> {
                    request.setScheme("https");
                    request.setServerPort(443);
                    request.setSecure(true);
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src-attr 'none'")));
    }
}
