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
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Service
public class TotpService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        return encodeBase32(secret);
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

    public Long matchingStep(String secret, String rawCode, long nowEpochSeconds) {
        String code = normalizeCode(rawCode);
        if (code == null) return null;
        long current = nowEpochSeconds / 30L;
        for (long step = current - 1; step <= current + 1; step++) {
            if (MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),
                    generate(secret, step).getBytes(StandardCharsets.US_ASCII))) return step;
        }
        return null;
    }

    public boolean verify(String secret, String code) {
        return matchingStep(secret, code, Instant.now().getEpochSecond()) != null;
    }

    private String generate(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("Could not verify authenticator code.", e);
        }
    }

    private String normalizeCode(String raw) {
        if (raw == null) return null;
        String code = raw.replace(" ", "").replace("-", "");
        return code.matches("\\d{6}") ? code : null;
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
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : encoded.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (value < 0) continue;
            buffer = (buffer << 5) | value; bits += 5;
            if (bits >= 8) { output.write((buffer >> (bits - 8)) & 255); bits -= 8; }
        }
        return output.toByteArray();
    }

    private String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
