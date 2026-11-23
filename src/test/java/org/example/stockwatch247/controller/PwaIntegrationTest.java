package org.example.stockwatch247.controller;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The installable web app's public pieces, signed out, and the kill switch. */
@SpringBootTest(properties = "alerts.schedule.enabled=false")
@AutoConfigureMockMvc
class PwaIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void theManifestIsPublicAndDescribesAnInstallableApp() throws Exception {
        mvc.perform(get("/manifest.webmanifest").header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/manifest+json"))
                .andExpect(jsonPath("$.name").value("StockWatch 24/7"))
                .andExpect(jsonPath("$.start_url").value("/home"))
                .andExpect(jsonPath("$.scope").value("/"))
                .andExpect(jsonPath("$.display").value("standalone"))
                .andExpect(jsonPath("$.lang").value("pl"))
                .andExpect(jsonPath("$.icons[?(@.purpose == 'maskable')].sizes").value("512x512"));
        mvc.perform(get("/icons/icon-512.png")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/png"));
        mvc.perform(get("/icons/apple-touch-icon.png")).andExpect(status().isOk());
    }

    @Test
    void theWorkerIsPublicNeverCachedAndOnlyHandlesNavigations() throws Exception {
        String worker = mvc.perform(get("/sw.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andReturn().getResponse().getContentAsString();
        assertThat(worker).contains("request.mode !== 'navigate'", "caches.match('/offline')")
                .doesNotContain("cache.put").doesNotContain("unregister");
    }

    @Test
    void theOfflinePageStylesItselfWithThisResponsesNonce() throws Exception {
        var response = mvc.perform(get("/offline")).andExpect(status().isOk())
                .andExpect(content().string(containsString("You are offline"))).andReturn().getResponse();
        var nonce = Pattern.compile("'nonce-([^']+)'").matcher(response.getHeader("Content-Security-Policy"));
        assertThat(nonce.find()).isTrue();
        assertThat(response.getContentAsString()).contains("<style nonce=\"" + nonce.group(1) + "\">").doesNotContain(" style=\"");
    }

    @Test
    void everyPageLinksTheManifestAndRegistersTheWorker() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(content().string(containsString("rel=\"manifest\" href=\"/manifest.webmanifest\"")))
                .andExpect(content().string(containsString("/js/pwa")))
                .andExpect(content().string(containsString("rel=\"apple-touch-icon\"")));
        try (Stream<Path> templates = Files.list(Path.of("src/main/resources/templates"))) {
            var missing = templates.filter(path -> path.toString().endsWith(".html"))
                    .filter(path -> !path.getFileName().toString().equals("offline.html"))
                    .filter(path -> !read(path).contains("fragments/pwa :: head")).toList();
            assertThat(missing).isEmpty();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Nested
    @SpringBootTest(properties = {"alerts.schedule.enabled=false", "pwa.enabled=false"})
    @AutoConfigureMockMvc
    class KillSwitch {
        @Autowired MockMvc mvc;

        @Test
        void switchedOffTheWorkerRemovesItselfAndPagesStopRegisteringIt() throws Exception {
            mvc.perform(get("/sw.js")).andExpect(status().isOk())
                    .andExpect(content().string(containsString("self.registration.unregister()")))
                    .andExpect(content().string(containsString("caches.delete(key)")));
            mvc.perform(get("/login")).andExpect(content().string(not(containsString("/js/pwa"))));
        }
    }
}
