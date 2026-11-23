package org.example.stockwatch247.service.channel;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushCryptoTest {
    // RFC 8291, Appendix A.
    static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void encryptionMatchesTheRfc8291TestVector() {
        KeyPair server = new KeyPair(WebPushCrypto.publicKey(WebPushCrypto.base64Url(AS_PUBLIC)),
                WebPushCrypto.privateKey(WebPushCrypto.base64Url(AS_PRIVATE)));
        byte[] body = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), WebPushCrypto.base64Url(UA_PUBLIC),
                WebPushCrypto.base64Url(AUTH), server, WebPushCrypto.base64Url(SALT));
        assertThat(WebPushCrypto.base64Url(body)).isEqualTo(BODY);
    }

    @Test
    void aFreshMessageDecryptsWithTheSubscriptionsKeys() throws Exception {
        KeyPair browser = WebPushCrypto.newKeyPair();
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        byte[] uaPublic = WebPushCrypto.uncompressed((ECPublicKey) browser.getPublic());
        byte[] body = WebPushCrypto.encrypt("{\"title\":\"Hammer\"}".getBytes(StandardCharsets.UTF_8), uaPublic, auth);
        assertThat(new String(decrypt(body, (ECPrivateKey) browser.getPrivate(), uaPublic, auth), StandardCharsets.UTF_8))
                .isEqualTo("{\"title\":\"Hammer\"}");
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT + 1], uaPublic, auth))
                .hasMessageContaining("too long");
    }

    @Test
    void pointsOffTheCurveAreRefused() {
        byte[] point = WebPushCrypto.base64Url(UA_PUBLIC);
        point[40] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.publicKey(point)).hasMessageContaining("not on P-256");
        assertThatThrownBy(() -> WebPushCrypto.publicKey(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVapidTokenVerifiesWithItsPublicKeyAndPairsAreChecked() throws Exception {
        KeyPair pair = WebPushCrypto.newKeyPair();
        byte[] publicKey = WebPushCrypto.uncompressed((ECPublicKey) pair.getPublic());
        byte[] privateKey = WebPushCrypto.scalar((ECPrivateKey) pair.getPrivate());
        long exp = Instant.now().plusSeconds(12 * 3600).getEpochSecond();
        String jwt = WebPushCrypto.vapidJwt("https://fcm.googleapis.com", "mailto:alerts@example.com", exp, privateKey);
        String[] parts = jwt.split("\\.");
        assertThat(new String(WebPushCrypto.base64Url(parts[1]), StandardCharsets.UTF_8))
                .isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":" + exp + ",\"sub\":\"mailto:alerts@example.com\"}");
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(WebPushCrypto.base64Url(parts[2]))).isTrue();
        assertThat(WebPushCrypto.matches(publicKey, privateKey)).isTrue();
        assertThat(WebPushCrypto.matches(WebPushCrypto.base64Url(UA_PUBLIC), privateKey)).isFalse();
    }

    /** The browser's side of RFC 8291, so tests can read what a push service would carry. */
    static byte[] decrypt(byte[] body, ECPrivateKey uaPrivate, byte[] uaPublic, byte[] auth) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt();
        byte[] asPublic = new byte[in.get()];
        in.get(asPublic);
        byte[] record = new byte[in.remaining()];
        in.get(record);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(uaPrivate);
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] prkKey = WebPushCrypto.hmac(auth, agreement.generateSecret());
        byte[] ikm = WebPushCrypto.hmac(prkKey, WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0}, uaPublic, asPublic, new byte[]{1}));
        byte[] prk = WebPushCrypto.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(record);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) end--;
        return Arrays.copyOf(padded, end);
    }
}
