package org.example.stockwatch247.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;

@Service
public class TotpService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        try { return encodeBase32(secret); } finally { java.util.Arrays.fill(secret, (byte) 0); }
    }

    public String provisioningUri(String email, String secret) {
        String issuer = "StockWatch 24/7";
        String label = issuer + ":" + email;
        return "otpauth://totp/" + url(label) + "?secret=" + secret + "&issuer=" + url(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    public String qrDataUri(String provisioningUri) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(provisioningUri, BarcodeFormat.QR_CODE, 240, 240);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not create authenticator QR code.", e);
        }
    }

    /** Returns a candidate step only; callers must atomically enforce replay protection. */
    public Long matchingStep(String secret, String rawCode, long nowEpochSeconds) {
        String code = normalizeFactor(rawCode);
        if (code == null || !code.matches("[0-9]{6}") || nowEpochSeconds < 0) return null;
        final byte[] decoded;
        try { decoded = decodeBase32(secret); }
        catch (IllegalArgumentException exception) { return null; }
        byte[] supplied = code.getBytes(StandardCharsets.US_ASCII);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decoded, "HmacSHA1"));
            long current = nowEpochSeconds / 30L;
            Long matched = null;
            for (long step = Math.max(0, current - 1); step <= current + 1; step++) {
                if (MessageDigest.isEqual(supplied, generate(mac, step)) && matched == null) matched = step;
            }
            return matched;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not verify authenticator code.", exception);
        } finally { java.util.Arrays.fill(decoded, (byte) 0); }
    }

    private byte[] generate(Mac mac, long step) {
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        int value = binary % 1_000_000;
        byte[] digits = new byte[6];
        for (int index = 5; index >= 0; index--) { digits[index] = (byte) ('0' + value % 10); value /= 10; }
        return digits;
    }

    public static String normalizeFactor(String raw) {
        if (raw == null || raw.length() > 64) return null;
        return raw.trim().replace(" ", "").replace("-", "");
    }

    private String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff); bits += 8;
            while (bits >= 5) { result.append(BASE32[(buffer >> (bits - 5)) & 31]); bits -= 5; }
        }
        if (bits > 0) result.append(BASE32[(buffer << (5 - bits)) & 31]);
        return result.toString();
    }

    private byte[] decodeBase32(String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z2-7]{32}")) throw new IllegalArgumentException("Invalid authenticator secret.");
        ByteArrayOutputStream output = new ByteArrayOutputStream(20);
        int buffer = 0, bits = 0;
        for (char c : encoded.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (value < 0) throw new IllegalArgumentException("Invalid authenticator secret.");
            buffer = (buffer << 5) | value; bits += 5;
            if (bits >= 8) { output.write((buffer >> (bits - 8)) & 255); bits -= 8; }
        }
        return output.toByteArray();
    }

    private String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
