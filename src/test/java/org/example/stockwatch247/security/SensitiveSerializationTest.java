package org.example.stockwatch247.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.controller.CandleResponse;
import org.example.stockwatch247.model.Candle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveSerializationTest {

    @Test
    void userJsonDoesNotExposeAuthenticationSecrets() throws Exception {
        User user = new User();
        user.setEmail("user@example.com");
        user.setPasswordHash("bcrypt-hash");
        user.setVerificationTokenHash("verification-hash");
        user.setVerificationLastSentAt(java.time.LocalDateTime.now());
        // This focused unit test uses a bare mapper; Java-time serialization is
        // configured by Spring in the running application.
        user.setCreatedAt(null);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(user);

        assertTrue(json.contains("user@example.com"));
        assertFalse(json.contains("bcrypt-hash"));
        assertFalse(json.contains("verification-hash"));
        assertFalse(json.contains("passwordHash"));
        assertFalse(json.contains("twoFactorSecret"));
        assertFalse(json.contains("verificationLastSentAt"));
    }

    @Test
    void publicCandleDtoDoesNotExposePersistenceId() throws Exception {
        Candle candle = new Candle("AAPL", "1d", 1_700_000_000L,
                100.0, 102.0, 99.0, 101.0, 1_000L);
        candle.setId(987L);

        String json = new ObjectMapper().writeValueAsString(CandleResponse.from(candle));

        assertTrue(json.contains("AAPL"));
        assertFalse(json.contains("987"));
        assertFalse(json.contains("\"id\""));
    }
}
