package org.example.stockwatch247.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationValidatorTest {

    @Test
    void acceptsACompleteFailSafeProductionConfiguration() {
        ProductionConfigurationValidator validator = validator(
                "https://stockwatch.example", "127\\.0\\.0\\.1|10\\.0\\.0\\.5", true, "secret");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonHttpsPublicUrlAndCatchAllProxyTrust() {
        assertThatThrownBy(() -> validator(
                "http://stockwatch.example", "127\\.0\\.0\\.1", true, "secret").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> validator(
                "https://stockwatch.example", ".*", true, "secret").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not trust every address");
    }

    @Test
    void rejectsMissingAuthenticatedSmtpCredentials() {
        assertThatThrownBy(() -> validator(
                "https://stockwatch.example", "127\\.0\\.0\\.1", true, "").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_PASSWORD");
    }

    @Test
    void rejectsMigrationCredentialsInTheWebProcess() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                true,
                "https://stockwatch.example",
                "native",
                "127\\.0\\.0\\.1",
                true,
                true,
                "smtp.example",
                "mailer",
                "secret",
                "no-reply@stockwatch.example",
                true,
                true,
                true,
                "schema-owner",
                "migration-secret");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration credentials");
    }

    private ProductionConfigurationValidator validator(String publicUrl,
                                                        String trustedProxies,
                                                        boolean emailEnabled,
                                                        String password) {
        return new ProductionConfigurationValidator(
                true,
                publicUrl,
                "native",
                trustedProxies,
                true,
                emailEnabled,
                "smtp.example",
                "mailer",
                password,
                "no-reply@stockwatch.example",
                true,
                true,
                true,
                "",
                "");
    }
}
