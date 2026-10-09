package org.example.stockwatch247.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Translated text for Java code. Inside a web request the request's language applies; anywhere else
 * (schedulers, email workers) pass the recipient's locale explicitly, because the JVM default locale
 * says nothing about the user.
 */
@Component
public class Messages {
    private final MessageSource source;

    public Messages(MessageSource source) {
        this.source = source;
    }

    private static volatile Messages standalone;

    /** The same bundles without a Spring context, for code built directly (tests, tools). */
    public static Messages standalone() {
        Messages messages = standalone;
        if (messages == null) {
            org.springframework.context.support.ResourceBundleMessageSource bundles =
                    new org.springframework.context.support.ResourceBundleMessageSource();
            bundles.setBasename("messages");
            bundles.setDefaultEncoding("UTF-8");
            bundles.setFallbackToSystemLocale(false);
            messages = new Messages(bundles);
            standalone = messages;
        }
        return messages;
    }

    public String get(String key, Object... args) {
        return get(current(), key, args);
    }

    public String get(Locale locale, String key, Object... args) {
        return source.getMessage(key, args, locale == null ? AppLanguage.DEFAULT.locale() : locale);
    }

    /** A pluralised message ("key.one", "key.few", "key.many", "key.other") chosen for {@code count}. */
    public String plural(Locale locale, String baseKey, Number count, Object... args) {
        Locale target = locale == null ? AppLanguage.DEFAULT.locale() : locale;
        String message = source.getMessage(Plurals.key(target, baseKey, count), args, null, target);
        return message != null ? message : source.getMessage(baseKey + ".other", args, target);
    }

    public String plural(String baseKey, Number count, Object... args) {
        return plural(current(), baseKey, count, args);
    }

    /** The request's language, or English outside a request. */
    public static Locale current() {
        return LocaleContextHolder.getLocaleContext() == null
                ? AppLanguage.DEFAULT.locale()
                : AppLanguage.of(LocaleContextHolder.getLocale()).locale();
    }
}
