package org.example.stockwatch247.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Only content-addressed assets get a long browser lifetime; HTML and unversioned URLs retain their policies. */
@Component
public class FingerprintedAssetCacheFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.matches("/(css|js)/[A-Za-z0-9/_-]+-[a-f0-9]{32}\\.(css|js)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override public void setHeader(String name, String value) {
                super.setHeader(name, name.equalsIgnoreCase("Cache-Control") && getStatus() < 400
                        ? "public, max-age=31536000, immutable" : value);
            }
            @Override public void sendError(int code) throws IOException {
                super.setHeader("Cache-Control", "no-cache"); super.sendError(code);
            }
            @Override public void sendError(int code, String message) throws IOException {
                super.setHeader("Cache-Control", "no-cache"); super.sendError(code, message);
            }
        });
    }
}
