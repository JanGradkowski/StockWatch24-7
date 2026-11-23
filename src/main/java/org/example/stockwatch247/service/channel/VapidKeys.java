package org.example.stockwatch247.service.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's VAPID key pair, which identifies StockWatch to the browsers' push services. It comes from the secret
 * manager (VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, VAPID_SUBJECT). Every browser subscription is bound to the public key,
 * so the pair is generated once ({@code VapidKeyGenerator}) and never rotated casually. Without a valid, matching pair
 * push is not offered. Development may generate a pair once into target/dev-vapid.properties.
 */
@Component
public class VapidKeys {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VapidKeys.class);
    static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    static final Duration RESIGN_AFTER = Duration.ofMinutes(30);

    private record Token(String value, Instant signedAt) { }

    private final byte[] publicKey;
    private final byte[] privateKey;
    private final String subject;
    private final boolean ready;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    public VapidKeys(@Value("${alert-channels.push.vapid-public-key:}") String publicKey,
                     @Value("${alert-channels.push.vapid-private-key:}") String privateKey,
                     @Value("${alert-channels.push.vapid-subject:}") String subject,
                     @Value("${alert-channels.push.enabled:true}") boolean enabled,
                     @Value("${alert-channels.push.dev-generate:false}") boolean devGenerate) {
        String pub = publicKey == null ? "" : publicKey.strip();
        String priv = privateKey == null ? "" : privateKey.strip();
        String sub = subject == null ? "" : subject.strip();
        if (enabled && devGenerate && pub.isEmpty() && priv.isEmpty()) {
            String[] generated = developmentPair();
            pub = generated[0];
            priv = generated[1];
            if (sub.isEmpty()) sub = "mailto:alerts@stockwatch.local";
        }
        byte[] parsedPublic = null;
        byte[] parsedPrivate = null;
        try {
            if (!pub.isEmpty() && !priv.isEmpty()) {
                parsedPublic = WebPushCrypto.base64Url(pub);
                parsedPrivate = WebPushCrypto.base64Url(priv);
            }
        } catch (IllegalArgumentException invalid) {
            log.error("The VAPID keys are not base64url; Web Push is off.");
        }
        this.publicKey = parsedPublic;
        this.privateKey = parsedPrivate;
        this.subject = sub;
        this.ready = enabled && parsedPublic != null && valid(pub, priv, sub);
    }

    /** Both keys decode, belong together, and the subject is a mailto: or https: contact. */
    public static boolean valid(String publicKey, String privateKey, String subject) {
        try {
            return (subject.startsWith("mailto:") && subject.length() > 7 || subject.startsWith("https://"))
                    && WebPushCrypto.matches(WebPushCrypto.base64Url(publicKey), WebPushCrypto.base64Url(privateKey));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public boolean ready() { return ready; }

    /** The applicationServerKey browsers subscribe with. */
    public String publicKey() { return ready ? WebPushCrypto.base64Url(publicKey) : null; }

    /** The Authorization header for one push service origin; each token is re-signed after 30 minutes. */
    public String authorization(String audience) {
        Instant now = Instant.now();
        Token token = tokens.compute(audience, (key, current) -> current != null && current.signedAt().plus(RESIGN_AFTER).isAfter(now)
                ? current : new Token(WebPushCrypto.vapidJwt(audience, subject, now.plus(TOKEN_LIFETIME).getEpochSecond(), privateKey), now));
        return "vapid t=" + token.value() + ", k=" + WebPushCrypto.base64Url(publicKey);
    }

    private static String[] developmentPair() {
        Path file = Path.of("target", "dev-vapid.properties");
        Properties properties = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            } else {
                KeyPair pair = WebPushCrypto.newKeyPair();
                properties.setProperty("public", WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) pair.getPublic())));
                properties.setProperty("private", WebPushCrypto.base64Url(WebPushCrypto.scalar((ECPrivateKey) pair.getPrivate())));
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) {
                    properties.store(out, "Development-only VAPID pair. Never use it in production.");
                }
            }
        } catch (IOException failure) {
            log.warn("Development VAPID pair unavailable: {}", failure.getClass().getSimpleName());
            return new String[]{"", ""};
        }
        return new String[]{properties.getProperty("public", ""), properties.getProperty("private", "")};
    }
}
