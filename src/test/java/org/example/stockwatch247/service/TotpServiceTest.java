package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {
    private final TotpService service = new TotpService();

    @Test
    void rejectsMalformedSecretsAndNonAsciiOrOversizedFactors() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(service.matchingStep(secret.toLowerCase(java.util.Locale.ROOT), "287-082", 59)).isEqualTo(1L);
        for (String malformed : new String[]{"", secret + "!", secret.substring(1), "!" + secret.substring(1), "ß" + secret.substring(1)})
            assertThat(service.matchingStep(malformed, "287082", 59)).isNull();
        for (String code : new String[]{"２８７０８２", "287082x", " ".repeat(100) + "287082"})
            assertThat(service.matchingStep(secret, code, 59)).isNull();
        assertThat(service.matchingStep(secret, "287082", -1)).isNull();
        assertThat(service.matchingStep(secret, "287082", 120)).isNull();
    }

    @Test
    void validatesTheRfc6238Sha1VectorWithSixDigitsAndClockSkew() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(service.matchingStep(secret, "287082", 59)).isEqualTo(1L);
        assertThat(service.matchingStep(secret, "287082", 89)).isEqualTo(1L);
        assertThat(service.matchingStep(secret, "000000", 59)).isNull();
    }

    @Test
    void producesAuthenticatorCompatibleSecretsAndUris() {
        String secret = service.newSecret();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(service.provisioningUri("owner@example.com", secret))
                .startsWith("otpauth://totp/")
                .contains("secret=" + secret, "issuer=StockWatch%2024%2F7", "digits=6", "period=30");
    }
}
