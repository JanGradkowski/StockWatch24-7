package org.example.stockwatch247.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.service.SecurityCryptoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * A signed cookie marking a browser that has completed sign-in for an account. It lets the owner
 * keep signing in while someone else floods that account with failed attempts from other browsers.
 * The cookie names no account: it only proves that this browser signed in as the attempted email.
 */
@Component
public class KnownDeviceCookies {
    static final String NAME = "sw_known_device";
    private static final String PURPOSE = "known-device";
    private static final Duration LIFETIME = Duration.ofDays(90);
    private final SecurityCryptoService crypto;
    private Clock clock = Clock.systemUTC();

    public KnownDeviceCookies(SecurityCryptoService crypto) {
        this.crypto = crypto;
    }

    @Autowired(required = false)
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public void remember(HttpServletRequest request, HttpServletResponse response, String email) {
        long issued = clock.instant().getEpochSecond();
        String value = issued + "." + crypto.sign(normalize(email) + "|" + issued, PURPOSE);
        ResponseCookie cookie = ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/login")
                .maxAge(LIFETIME)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public boolean recognizes(HttpServletRequest request, String email) {
        if (email == null || request.getCookies() == null) return false;
        for (Cookie cookie : request.getCookies()) {
            if (NAME.equals(cookie.getName()) && valid(cookie.getValue(), normalize(email))) return true;
        }
        return false;
    }

    private boolean valid(String value, String email) {
        if (value == null) return false;
        int separator = value.indexOf('.');
        if (separator <= 0 || separator > 19) return false;
        long issued;
        try {
            issued = Long.parseLong(value.substring(0, separator));
        } catch (NumberFormatException invalid) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (issued > now + 60 || now - issued > LIFETIME.toSeconds()) return false;
        return crypto.verify(email + "|" + issued, PURPOSE, value.substring(separator + 1));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
