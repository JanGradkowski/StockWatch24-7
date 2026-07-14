package org.example.stockwatch247.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

public class CspNonceFilter extends OncePerRequestFilter {
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] nonceBytes = new byte[18];
        secureRandom.nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        request.setAttribute("cspNonce", nonce);
        // Lightweight Charts positions its canvases with generated style attributes. Keep that
        // narrowly-scoped compatibility exception on the authenticated chart page only.
        String styleAttributes = request.getRequestURI().startsWith("/stock/")
                ? "style-src-attr 'unsafe-inline'; "
                : "style-src-attr 'none'; ";
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'nonce-" + nonce + "'; "
                        + "style-src 'self' 'nonce-" + nonce + "'; " + styleAttributes
                        + "img-src 'self' data:; connect-src 'self'; "
                        + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        filterChain.doFilter(request, response);
    }
}
