package org.example.stockwatch247.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Service
public class SecurityCryptoService {
    static final String DEVELOPMENT_KEY = "local-development-only-change-me";
    private static final Set<String> PLACEHOLDER_KEYS = Set.of(DEVELOPMENT_KEY, "replace-with-a-long-random-secret");
    private static final int MINIMUM_KEY_LENGTH = 32;
    private static final int GCM_TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecretKeySpec signingKey;
    private final SecureRandom random = new SecureRandom();

    public SecurityCryptoService(String secret) {
        this(secret, false);
    }

    /**
     * Only the dev and test profiles may run with the built-in or a short key; anything else
     * must supply a unique secret, because a guessable key exposes every stored authenticator seed.
     */
    @Autowired
    public SecurityCryptoService(@Value("${security.mfa.encryption-key:}") String secret,
                                 @Value("${security.allow-insecure-defaults:false}") boolean allowInsecureDefaults) {
        String effective = secret == null || secret.isBlank() ? (allowInsecureDefaults ? DEVELOPMENT_KEY : "") : secret;
        if (!allowInsecureDefaults && (effective.isBlank() || PLACEHOLDER_KEYS.contains(effective)
                || effective.length() < MINIMUM_KEY_LENGTH)) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY must be a unique secret of at least "
                    + MINIMUM_KEY_LENGTH + " characters. Use the dev profile for local development.");
        }
        try {
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(effective.getBytes(StandardCharsets.UTF_8)), "AES");
            this.signingKey = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(("signing:" + effective).getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
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

    /** URL-safe HMAC-SHA256 of {@code value}; the context keeps signatures for different purposes apart. */
    public String sign(String value, String context) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            mac.update(context.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the value.", e);
        }
    }

    public boolean verify(String value, String context, String signature) {
        if (value == null || signature == null) return false;
        return MessageDigest.isEqual(sign(value, context).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    public record EncryptedValue(String ciphertext, String iv) {}
}
