package org.example.stockwatch247.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates messages produced by services (validation errors, confirmations) at the point they are
 * shown. Like gettext, the catalog is keyed by the English text, so throw sites stay unchanged; a
 * "{0}" in an entry matches any value and carries it into the translation (translated too when the value
 * is itself a catalogued term, such as a settings field label). Entries marked internal
 * describe failures users never see (they get a generic error page) and stay in English.
 */
public final class ServerMessages {
    static final String CATALOG = "i18n/server-messages.txt";
    /** Labels, summaries and notes that services build for pages, JSON responses and emails. */
    static final String LABELS = "i18n/server-labels.txt";
    private static final Catalog POLISH = Catalog.load(CATALOG, LABELS).withTerms(interfaceTerms());

    private ServerMessages() {
    }

    /** The message in the current request's language (English outside a request). */
    public static String translate(String message) {
        return translate(message, Messages.current());
    }

    public static String translate(String message, Locale locale) {
        if (message == null || AppLanguage.of(locale) != AppLanguage.PL) return message;
        return POLISH.translate(message);
    }

    /**
     * A multi-line text (an alert email composed in English) translated line by line; indentation and
     * "- " bullets stay as they are.
     */
    public static String translateLines(String text, Locale locale) {
        if (text == null || AppLanguage.of(locale) != AppLanguage.PL) return text;
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) result.append('\n');
            String line = lines[index];
            Matcher prefix = LINE_PREFIX.matcher(line);
            int start = prefix.lookingAt() ? prefix.end() : 0;
            String content = line.substring(start).stripTrailing();
            result.append(line, 0, start)
                    .append(content.isEmpty() ? "" : POLISH.translate(content))
                    .append(line.substring(start + content.length()));
        }
        return result.toString();
    }

    private static final Pattern LINE_PREFIX = Pattern.compile("\\s*(?:[-*•]\\s+)?");

    static Catalog catalog() {
        return POLISH;
    }

    /**
     * English → Polish for every interface text that has exactly one Polish translation, so a value
     * inserted into a message (a pattern name, a settings label) is translated from the same bundle the
     * page uses.
     */
    private static Map<String, String> interfaceTerms() {
        java.util.ResourceBundle.Control control = java.util.ResourceBundle.Control.getNoFallbackControl(
                java.util.ResourceBundle.Control.FORMAT_PROPERTIES);
        java.util.ResourceBundle english = java.util.ResourceBundle.getBundle("messages", AppLanguage.EN.locale(), control);
        java.util.ResourceBundle polish = java.util.ResourceBundle.getBundle("messages", AppLanguage.PL.locale(), control);
        Map<String, String> terms = new java.util.HashMap<>();
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (String key : english.keySet()) {
            if (!polish.containsKey(key)) continue;
            String en = english.getString(key), pl = polish.getString(key);
            String previous = terms.putIfAbsent(en, pl);
            if (previous != null && !previous.equals(pl)) ambiguous.add(en);
        }
        ambiguous.forEach(terms::remove);
        return terms;
    }

    /** A catalog entry; {@code anchor} is the longest fixed piece of a pattern, which a match must contain. */
    record Entry(String english, String polish, Pattern pattern, String anchor) {
        boolean internal() {
            return polish == null;
        }
    }

    static final class Catalog {
        private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");
        private static final Pattern LIST_BREAK = Pattern.compile(", ");
        private final Map<String, Entry> exact = new LinkedHashMap<>();
        private final List<Entry> patterns = new ArrayList<>();
        private Map<String, String> terms = Map.of();

        Catalog withTerms(Map<String, String> interfaceTerms) {
            terms = Map.copyOf(interfaceTerms);
            return this;
        }

        static Catalog load(String... resources) {
            Catalog catalog = new Catalog();
            for (String resource : resources) catalog.read(resource);
            // The pattern with more fixed text is the more specific one and is tried first, so a
            // general entry ("{0} slope") never hides a specific one ("Rolling {0} slope").
            catalog.patterns.sort(java.util.Comparator.comparingInt(Catalog::fixedText).reversed());
            return catalog;
        }

        private static int fixedText(Entry entry) {
            return entry.english().replaceAll("\\{\\d}", "").length();
        }

        private void read(String resource) {
            try (InputStream input = ServerMessages.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing " + resource);
                String english = null;
                for (String raw : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("en: ")) {
                        if (english != null) throw new IllegalStateException("No translation for: " + english);
                        english = line.substring(4);
                    } else if (line.startsWith("pl: ") || line.equals("internal")) {
                        if (english == null) throw new IllegalStateException("Translation without English: " + line);
                        add(english, line.equals("internal") ? null : line.substring(4));
                        english = null;
                    } else {
                        throw new IllegalStateException("Unreadable catalog line: " + line);
                    }
                }
                if (english != null) throw new IllegalStateException("No translation for: " + english);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private void add(String english, String polish) {
            if (exact.containsKey(english)) throw new IllegalStateException("Duplicate catalog entry: " + english);
            Pattern pattern = english.contains("{0}") ? compile(english) : null;
            String anchor = "";
            for (String piece : english.split("\\{\\d}")) {
                if (piece.length() > anchor.length()) anchor = piece;
            }
            Entry entry = new Entry(english, polish, pattern, anchor);
            exact.put(english, entry);
            if (pattern != null) patterns.add(entry);
        }

        private static Pattern compile(String english) {
            StringBuilder regex = new StringBuilder("^");
            Matcher placeholder = Pattern.compile("\\{(\\d)}").matcher(english);
            int last = 0;
            while (placeholder.find()) {
                regex.append(Pattern.quote(english.substring(last, placeholder.start())))
                        .append("(?<p").append(placeholder.group(1)).append(">.+?)");
                last = placeholder.end();
            }
            return Pattern.compile(regex.append(Pattern.quote(english.substring(last))).append('$').toString(), Pattern.DOTALL);
        }

        /** Labels repeat on every page, so translations are kept (bounded, least recently used first out). */
        private final Map<String, String> translated = Collections.synchronizedMap(
                new LinkedHashMap<>(1024, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > 20_000;
                    }
                });

        String translate(String message) {
            String known = translated.get(message);
            if (known != null) return known;
            String result = lookup(message);
            translated.put(message, result);
            return result;
        }

        private String lookup(String message) {
            String found = catalogued(message);
            if (found != null) return found;
            // Services join sentences ("<reason> <summary>") and list items ("Trend (Buy → Neutral), …")
            // that are catalogued one by one; such a text is translated when every part is known,
            // otherwise only the dates inside it change.
            String joined = joinedParts(message, SENTENCE_BREAK, " ");
            if (joined == null) joined = joinedParts(message, LIST_BREAK, ", ");
            if (joined != null) return joined;
            String number = PolishFormats.number(message);
            return number != null ? number : PolishFormats.dates(message);
        }

        private String joinedParts(String message, Pattern separator, String glue) {
            String[] parts = separator.split(message);
            if (parts.length < 2) return null;
            StringBuilder joined = new StringBuilder();
            for (String part : parts) {
                String translated = catalogued(part);
                if (translated == null) return null;
                if (!joined.isEmpty()) joined.append(glue);
                joined.append(translated);
            }
            return joined.toString();
        }

        /**
         * The translation from a catalog entry or an interface term, or null when neither knows the text.
         * A fragment of a longer text ("established uptrend across …") matches the entry for the same
         * sentence written on its own, with a capital letter and a full stop.
         */
        private String catalogued(String message) {
            String found = direct(message);
            if (found != null || message.isEmpty()) return found;
            boolean lowerCase = Character.isLowerCase(message.charAt(0));
            boolean unfinished = !message.endsWith(".");
            String capitalized = Character.toUpperCase(message.charAt(0)) + message.substring(1);
            if (lowerCase && (found = direct(capitalized)) != null) return lowered(found);
            if (unfinished && (found = direct(message + ".")) != null) return withoutStop(found);
            if (lowerCase && unfinished && (found = direct(capitalized + ".")) != null) return lowered(withoutStop(found));
            return null;
        }

        private static String withoutStop(String text) {
            return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        }

        /** Lower case like the English fragment, unless the translation starts with an abbreviation. */
        private static String lowered(String text) {
            return text.length() > 1 && Character.isLowerCase(text.charAt(1))
                    ? Character.toLowerCase(text.charAt(0)) + text.substring(1) : text;
        }

        private String direct(String message) {
            Entry entry = exact.get(message);
            if (entry != null) return entry.internal() ? message : entry.polish();
            for (Entry candidate : patterns) {
                if (!message.contains(candidate.anchor())) continue;
                Matcher matcher = candidate.pattern().matcher(message);
                if (!matcher.matches()) continue;
                if (candidate.internal()) return message;
                String result = candidate.polish();
                for (int index = 0; index < 10; index++) {
                    if (!result.contains("{" + index + "}")) continue;
                    result = result.replace("{" + index + "}", value(matcher.group("p" + index)));
                }
                return result;
            }
            // A label that is also interface text (a pattern or interval name) uses the bundle's translation.
            return terms.get(message);
        }

        /**
         * A value inserted into a message: a number gets Polish separators; anything else is translated
         * like a message of its own (a field label, a nested sentence, a date). Values are shorter than
         * the message they come from, so this ends.
         */
        private String value(String captured) {
            String number = PolishFormats.number(captured);
            return number != null ? number : translate(captured);
        }

        /** Whether the catalog classifies this exact message or pattern (translated or internal). */
        boolean covers(String message) {
            if (exact.containsKey(message)) return true;
            return patterns.stream().anyMatch(entry -> entry.pattern().matcher(message).matches());
        }

        Map<String, Entry> entries() {
            return Collections.unmodifiableMap(exact);
        }
    }
}
