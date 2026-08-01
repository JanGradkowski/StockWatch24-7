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
}
