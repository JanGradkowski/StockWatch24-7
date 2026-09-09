package org.example.stockwatch247.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in HTML fixtures for local browser review, using only isolated test accounts. */
@TestConfiguration(proxyBeanMethods = false)
public class DesignPreviewCapture {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Bean
    MockMvcBuilderCustomizer designPreviewCapture() {
        return builder -> {
            if (!Boolean.getBoolean("design.preview.capture")) return;
            builder.alwaysDo(result -> {
                var response = result.getResponse();
                if (response.getStatus() != 200 || response.getContentType() == null
                        || !response.getContentType().contains("text/html")) return;
                Path directory = Path.of(System.getProperty("design.preview.directory", "target/design-preview"));
                Files.createDirectories(directory);
                String name = result.getRequest().getRequestURI().replaceAll("[^a-zA-Z0-9-]", "_");
                Files.writeString(directory.resolve(name + "-" + SEQUENCE.incrementAndGet() + ".html"),
                        response.getContentAsString(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            });
        };
    }
}
