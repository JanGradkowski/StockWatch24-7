package org.example.stockwatch247.i18n;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

/**
 * Serves the browser-side translations ("js." keys) as a script, loaded before every page script so
 * they can call {@code StockWatchI18n.t(key, ...args)}. The response depends only on the URL (language
 * and bundle version), so it is cached long-term and still changes when a translation does.
 */
@RestController
public class I18nScriptController {
    private static final String PREFIX = "js.";
    private static final MediaType JAVASCRIPT = new MediaType("text", "javascript", StandardCharsets.UTF_8);

    /**
     * Changes whenever a bundle changes; part of the script URL so browsers fetch the new text. Static so
     * templates can read it without the application context.
     */
    public static final String VERSION = bundleVersion();

    private final Map<AppLanguage, String> scripts = new EnumMap<>(AppLanguage.class);

    public I18nScriptController(ObjectMapper json) {
        for (AppLanguage language : AppLanguage.values()) scripts.put(language, script(language, json));
    }

    @GetMapping("/i18n/messages.js")
    public ResponseEntity<String> messages(@RequestParam(name = "lang", required = false) String lang) {
        AppLanguage language = AppLanguage.fromCode(lang);
        return ResponseEntity.ok()
                .contentType(JAVASCRIPT)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(scripts.get(language == null ? AppLanguage.DEFAULT : language));
    }

    private static String script(AppLanguage language, ObjectMapper json) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", language.locale(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        Map<String, String> messages = new TreeMap<>();
        for (String key : bundle.keySet()) {
            if (key.startsWith(PREFIX)) messages.put(key.substring(PREFIX.length()), bundle.getString(key));
        }
        String tag = language.locale().toLanguageTag();
        return """
                (function () {
                  'use strict';
                  var messages = %s;
                  var tag = %s;
                  function format(message, args) {
                    if (!args.length) return message;
                    return message.replace(/''/g, "\\u0000").replace(/\\{(\\d+)\\}/g, function (match, index) {
                      var value = args[Number(index)];
                      return value === undefined || value === null ? match : String(value);
                    }).replace(/\\u0000/g, "'");
                  }
                  window.StockWatchI18n = Object.freeze({
                    locale: %s,
                    tag: tag,
                    has: function (key) {
                      return Object.prototype.hasOwnProperty.call(messages, key);
                    },
                    t: function (key) {
                      var message = Object.prototype.hasOwnProperty.call(messages, key) ? messages[key] : key;
                      return format(message, Array.prototype.slice.call(arguments, 1));
                    },
                    plural: function (key, count) {
                      var full = key + '.' + new Intl.PluralRules(tag).select(Number(count));
                      if (!Object.prototype.hasOwnProperty.call(messages, full)) full = key + '.other';
                      var message = Object.prototype.hasOwnProperty.call(messages, full) ? messages[full] : key;
                      return format(message, Array.prototype.slice.call(arguments, 2));
                    },
                    number: function (value, digits) {
                      var number = Number(value);
                      if (!Number.isFinite(number)) return '';
                      if (tag === 'en') return number.toFixed(digits);
                      return new Intl.NumberFormat(tag, { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(number);
                    },
                    date: function (value, options) {
                      return new Intl.DateTimeFormat(tag, options).format(value instanceof Date ? value : new Date(value));
                    }
                  });
                })();
                """.formatted(json.writeValueAsString(messages), json.writeValueAsString(tag),
                json.writeValueAsString(language.code()));
    }

    private static String bundleVersion() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AppLanguage language : AppLanguage.values()) {
                String name = language == AppLanguage.DEFAULT ? "messages.properties" : "messages_" + language.code() + ".properties";
                digest.update(new ClassPathResource(name).getContentAsByteArray());
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (IOException exception) {
            throw new UncheckedIOException("Translation bundles are missing.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
