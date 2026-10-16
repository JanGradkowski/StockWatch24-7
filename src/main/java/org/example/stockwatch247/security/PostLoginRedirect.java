package org.example.stockwatch247.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Where to go after signing in: the page that sent the visitor to the login form (a bookmarked chart,
 * a page whose session had expired), or the dashboard. Only a path on this site is ever used, never a
 * host, so the target cannot leave StockWatch; data and asset URLs fall back to the dashboard.
 */
public final class PostLoginRedirect {
    public static final String DEFAULT_TARGET = "/home";
    private static final RequestCache REQUESTS = new HttpSessionRequestCache();
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/api/", "/i18n/", "/css/", "/js/", "/images/", "/webjars/", "/login", "/logout", "/error",
            "/signup", "/verify-email", "/resend-verification", "/forgot-password", "/reset-password",
            "/cancel-account-deletion");

    private PostLoginRedirect() {
    }

    /** The saved target (consumed, so it is used once), as a path relative to the context. */
    public static String consume(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = REQUESTS.getRequest(request, response);
        if (saved == null) return DEFAULT_TARGET;
        REQUESTS.removeRequest(request, response);
        return safeTarget(saved.getRedirectUrl(), request.getContextPath());
    }

    public static String safeTarget(String url, String contextPath) {
        if (url == null || url.isBlank()) return DEFAULT_TARGET;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException invalid) {
            return DEFAULT_TARGET;
        }
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")) return DEFAULT_TARGET;
        String context = contextPath == null ? "" : contextPath;
        if (!context.isEmpty()) {
            if (!path.startsWith(context + "/")) return DEFAULT_TARGET;
            path = path.substring(context.length());
        }
        if (path.equals("/") || EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith)) return DEFAULT_TARGET;
        String query = withoutContinueMarker(uri.getRawQuery());
        String target = query.isEmpty() ? path : path + "?" + query;
        return target.length() > 2_048 ? DEFAULT_TARGET : target;
    }

    /**
     * The target without its leading slash, for redirects written as {@code "/" + pathAfterSlash(target)}:
     * the constant slash keeps the URL relative to this host. Anything that could start a second slash
     * (a protocol-relative {@code //host}) falls back to the dashboard.
     */
    public static String pathAfterSlash(String target) {
        String safe = safeTarget(target, "");
        String rest = safe.substring(1);
        return rest.isEmpty() || rest.startsWith("/") || rest.startsWith("\\") ? DEFAULT_TARGET.substring(1) : rest;
    }

    /** Spring Security marks a replayed request with a bare {@code continue} parameter; it is not part of the page. */
    private static String withoutContinueMarker(String query) {
        if (query == null || query.isEmpty()) return "";
        return String.join("&", java.util.Arrays.stream(query.split("&"))
                .filter(parameter -> !parameter.isEmpty() && !parameter.equals("continue") && !parameter.startsWith("continue="))
                .toList());
    }
}
