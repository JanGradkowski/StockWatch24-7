package org.example.stockwatch247.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecurityCryptoService {
    private static final int GCM_TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecurityCryptoService(@Value("${security.mfa.encryption-key:local-development-only-change-me}") String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("MFA encryption key is required.");
        try {
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize MFA encryption.", e);
        }
    }

    public EncryptedValue encrypt(String plaintext, String context) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new IllegalStateException("Could not protect the authenticator secret.", e);
        }
    }

    public String decrypt(String ciphertext, String encodedIv, String context) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(encodedIv)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the authenticator secret.", e);
        }
    }

    public record EncryptedValue(String ciphertext, String iv) {}
}
