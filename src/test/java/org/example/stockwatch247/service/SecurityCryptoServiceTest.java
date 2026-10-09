package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SecurityCryptoServiceTest {
    @Test
    void encryptsWithRandomIvAndBindsCiphertextToTheUserContext() {
        SecurityCryptoService crypto = new SecurityCryptoService("a-test-key-that-is-long-and-unique-for-tests");
        var first = crypto.encrypt("TOPSECRET", "user:1");
        var second = crypto.encrypt("TOPSECRET", "user:1");

        assertThat(first.ciphertext()).doesNotContain("TOPSECRET").isNotEqualTo(second.ciphertext());
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(crypto.decrypt(first.ciphertext(), first.iv(), "user:1")).isEqualTo("TOPSECRET");
        assertThatThrownBy(() -> crypto.decrypt(first.ciphertext(), first.iv(), "user:2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outsideDevAndTestAMissingPlaceholderOrShortKeyStopsStartup() {
        assertThatThrownBy(() -> new SecurityCryptoService("", false)).hasMessageContaining("MFA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new SecurityCryptoService("local-development-only-change-me", false))
                .hasMessageContaining("MFA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new SecurityCryptoService("short-secret", false)).hasMessageContaining("32");
        assertThatCode(() -> new SecurityCryptoService("", true)).doesNotThrowAnyException();
    }

    @Test
    void signaturesAreBoundToTheirPurposeAndKey() {
        SecurityCryptoService crypto = new SecurityCryptoService("a-test-key-that-is-long-and-unique-for-tests");
        String signature = crypto.sign("device:42", "known-device");

        assertThat(crypto.verify("device:42", "known-device", signature)).isTrue();
        assertThat(crypto.verify("device:43", "known-device", signature)).isFalse();
        assertThat(crypto.verify("device:42", "other-purpose", signature)).isFalse();
        assertThat(new SecurityCryptoService("another-test-key-that-is-long-and-unique")
                .verify("device:42", "known-device", signature)).isFalse();
    }
}
