package org.example.stockwatch247.support;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every Spring integration test uses a disposable schema, including runs from IntelliJ. */
public class IsolatedDatabaseContextCustomizerFactory implements ContextCustomizerFactory {
    private static final String SCHEMA = "stockwatch_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicBoolean CLEANUP_REGISTERED = new AtomicBoolean();
    private static final ContextCustomizer ISOLATION = (context, configuration) -> {
        var environment = context.getEnvironment();
        String originalUrl = environment.getRequiredProperty("spring.datasource.url");
        if (!originalUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("Integration tests require an isolated PostgreSQL database schema.");
        }
        String url = originalUrl.replaceAll("([?&])currentSchema=[^&]*&?", "$1")
                .replaceAll("[?&]$", "");
        url += (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        TestPropertyValues.of("spring.datasource.url=" + url, "spring.flyway.url=" + url,
                "spring.flyway.schemas=" + SCHEMA, "spring.flyway.default-schema=" + SCHEMA,
                "spring.jpa.properties.hibernate.default_schema=" + SCHEMA,
                // Fixture tickers are synthetic; provider verification has dedicated unit tests.
                "market-data.require-verification=false",
                // Every cached test context keeps its own pool; idle ones must hand their connections
                // back, or a full run exceeds PostgreSQL's connection limit (see spring.properties).
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.idle-timeout=10000").applyTo(environment);
        // Production-profile tests keep the real startup checks; every other test runs as "test".
        if (!java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            environment.addActiveProfile("test");
            TestPropertyValues.of("security.allow-insecure-defaults=true").applyTo(environment);
        }
        // Tests invoke workers explicitly when needed; no timer may run real provider/SMTP work.
        context.addBeanFactoryPostProcessor(factory -> {
            if (factory instanceof BeanDefinitionRegistry registry && registry.containsBeanDefinition(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor")) {
                registry.removeBeanDefinition("org.springframework.context.annotation.internalScheduledAnnotationProcessor");
            }
        });
        if (CLEANUP_REGISTERED.compareAndSet(false, true)) {
            String cleanupUrl = url;
            String username = environment.getRequiredProperty("spring.flyway.user");
            String password = environment.getRequiredProperty("spring.flyway.password");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!SCHEMA.matches("stockwatch_test_[a-f0-9]{32}")) return;
                try (var connection = DriverManager.getConnection(cleanupUrl, username, password);
                     var statement = connection.createStatement()) {
                    statement.execute("drop schema if exists " + SCHEMA + " cascade");
                } catch (Exception failure) {
                    System.err.println("Could not remove isolated test schema " + SCHEMA + ": "
                            + failure.getClass().getSimpleName());
                }
            }, "isolated-test-schema-cleanup"));
        }
    };

    @Override public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> attributes) {
        return AnnotatedElementUtils.hasAnnotation(testClass, SpringBootTest.class) ? ISOLATION : null;
    }
}
