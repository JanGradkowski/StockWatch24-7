package org.example.stockwatch247.controller;

import org.example.stockwatch247.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The installable web app: its manifest, its service worker and the page shown when the network is gone. All three
 * are public (a browser fetches the manifest without cookies). The worker lives at the site root so its scope is the
 * whole site, and it is never cached by the browser's HTTP cache, so a fix reaches every installed copy on its next
 * visit. With {@code pwa.enabled=false} the same address serves a worker that removes itself and its caches.
 */
@Controller
public class PwaController {
    static final MediaType MANIFEST = MediaType.parseMediaType("application/manifest+json");
    static final MediaType JAVASCRIPT = MediaType.parseMediaType("text/javascript;charset=UTF-8");

    private final Messages messages;
    private final boolean enabled;

    public PwaController(Messages messages, @Value("${pwa.enabled:true}") boolean enabled) {
        this.messages = messages;
        this.enabled = enabled;
    }

    @GetMapping("/manifest.webmanifest")
    public ResponseEntity<Map<String, Object>> manifest() {
        Locale locale = Messages.current();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", "/home");
        manifest.put("name", "StockWatch 24/7");
        manifest.put("short_name", "StockWatch");
        manifest.put("description", messages.get(locale, "pwa.description"));
        manifest.put("lang", locale.getLanguage());
        manifest.put("start_url", "/home");
        manifest.put("scope", "/");
        manifest.put("display", "standalone");
        manifest.put("background_color", "#0a0d12");
        manifest.put("theme_color", "#0a0d12");
        manifest.put("icons", List.of(
                icon("/icons/icon-192.png", "192x192", "any"),
                icon("/icons/icon-512.png", "512x512", "any"),
                icon("/icons/maskable-512.png", "512x512", "maskable")));
        return ResponseEntity.ok().contentType(MANIFEST).cacheControl(CacheControl.noCache()).body(manifest);
    }

    private static Map<String, String> icon(String src, String sizes, String purpose) {
        return Map.of("src", src, "sizes", sizes, "type", "image/png", "purpose", purpose);
    }

    @GetMapping("/sw.js")
    public ResponseEntity<String> serviceWorker() throws IOException {
        String file = enabled ? "pwa/sw.js" : "pwa/sw-off.js";
        String source = new ClassPathResource(file).getContentAsString(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(JAVASCRIPT).cacheControl(CacheControl.noCache()).body(source);
    }

    /** What an installed copy shows without a network: nothing about the account, so it can be cached. */
    @GetMapping("/offline")
    public String offline() {
        return "offline";
    }
}
