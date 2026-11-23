package org.example.stockwatch247.service.channel;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web Push on the JDK alone: payload encryption with aes128gcm (RFC 8291 over RFC 8188) and VAPID signatures (RFC 8292),
 * both on P-256. The push service sees only the encrypted record; the browser decrypts it with the subscription's keys.
 */
public final class WebPushCrypto {
    /** The plaintext of one 4,096-byte record: 4,096 minus the 16-byte tag, the 1-byte delimiter and the 86-byte header. */
    public static final int MAX_PLAINTEXT = 3993;
    private static final int RECORD_SIZE = 4096;
    private static final ECParameterSpec P256 = curve();
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushCrypto() { }

    private static ECParameterSpec curve() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("P-256 is unavailable.", unavailable);
        }
    }

    public static byte[] base64Url(String value) {
        return Base64.getUrlDecoder().decode(value.strip().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** A P-256 public key from its uncompressed form (0x04, X, Y), refused unless the point is on the curve. */
    public static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed.length != 65 || uncompressed[0] != 4) throw new IllegalArgumentException("Not an uncompressed P-256 point.");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        if (!onCurve(x, y)) throw new IllegalArgumentException("The point is not on P-256.");
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("Not a P-256 public key.", invalid);
        }
    }

    public static ECPrivateKey privateKey(byte[] scalar) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("Not a P-256 private key.", invalid);
        }
    }

    static boolean onCurve(BigInteger x, BigInteger y) {
        EllipticCurve curve = P256.getCurve();
        BigInteger p = ((java.security.spec.ECFieldFp) curve.getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) return false;
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return left.equals(right);
    }

    public static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(key.getW().getAffineX(), out, 1);
        copy32(key.getW().getAffineY(), out, 33);
        return out;
    }

    public static byte[] scalar(ECPrivateKey key) {
        byte[] out = new byte[32];
        copy32(key.getS(), out, 0);
        return out;
    }

    private static void copy32(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = Math.min(32, bytes.length);
        System.arraycopy(bytes, start, out, offset + 32 - length, length);
    }

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("P-256 is unavailable.", unavailable);
        }
    }

    /** True when the public key belongs to the private one (the VAPID pair from the secret manager must match). */
    public static boolean matches(byte[] publicKey, byte[] privateScalar) {
        try {
            byte[] challenge = "stockwatch-vapid-check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey(privateScalar));
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey(publicKey));
            verifier.update(challenge);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Encrypts with a fresh server key pair and salt. */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, newKeyPair(), salt);
    }

    /** RFC 8291 section 3.4 with a given server key pair and salt (the RFC's test vector fixes both). */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair server, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) throw new IllegalArgumentException("The push message is too long.");
        try {
            byte[] asPublic = uncompressed((ECPublicKey) server.getPublic());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(server.getPrivate());
            agreement.doPhase(publicKey(uaPublic), true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[]{1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                    new byte[]{0, 1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                    new byte[]{0, 1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] record = cipher.doFinal(concat(plaintext, new byte[]{2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), record);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not encrypt the push message.", failure);
        }
    }

    /** A VAPID JWT (ES256) for one push service origin, valid for {@code seconds}. */
    public static String vapidJwt(String audience, String subject, long expiresAt, byte[] privateScalar) {
        try {
            String header = base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
            String claims = base64Url(("{\"aud\":\"" + audience + "\",\"exp\":" + expiresAt + ",\"sub\":\"" + subject + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(privateKey(privateScalar));
            signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            return header + "." + claims + "." + base64Url(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not sign the VAPID token.", failure);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
