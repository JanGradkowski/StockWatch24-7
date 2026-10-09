package org.example.stockwatch247.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Both languages must say the same things: same keys, no gaps, same placeholders. */
class TranslationBundleTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*}");

    private static final Pattern PLURAL = Pattern.compile("^(.+)\\.(zero|one|two|few|many|other)$");

    @Test
    void everyKeyExistsInBothLanguagesWithText() throws IOException {
        Properties english = load("messages.properties");
        Properties polish = load("messages_pl.properties");

        // Plural forms differ by language (English one/other, Polish one/few/many/other); compare their base keys.
        assertThat(comparable(polish.stringPropertyNames()))
                .as("keys only in English (missing Polish)")
                .containsAll(comparable(english.stringPropertyNames()));
        assertThat(comparable(english.stringPropertyNames()))
                .as("keys only in Polish (missing English)")
                .containsAll(comparable(polish.stringPropertyNames()));
        for (Properties bundle : List.of(english, polish)) {
            for (String key : bundle.stringPropertyNames()) {
                Matcher plural = PLURAL.matcher(key);
                if (plural.matches()) {
                    assertThat(bundle.getProperty(plural.group(1) + ".other")).as(key + " needs an .other form").isNotNull();
                }
            }
        }
        for (String key : english.stringPropertyNames()) {
            assertThat(english.getProperty(key)).as("English " + key).isNotBlank();
        }
        for (String key : polish.stringPropertyNames()) {
            assertThat(polish.getProperty(key)).as("Polish " + key).isNotBlank();
        }
    }

    private static Set<String> comparable(Set<String> keys) {
        Set<String> result = new TreeSet<>();
        for (String key : keys) {
            Matcher plural = PLURAL.matcher(key);
            result.add(plural.matches() ? plural.group(1) + ".<plural>" : key);
        }
        return result;
    }

    @Test
    void translationsKeepTheSamePlaceholdersAndEscapeApostrophesWhereFormatted() throws IOException {
        Properties english = load("messages.properties");
        Properties polish = load("messages_pl.properties");
        for (String key : english.stringPropertyNames()) {
            Matcher plural = PLURAL.matcher(key);
            String en = english.getProperty(key);
            String pl = polish.getProperty(key, plural.matches() ? polish.getProperty(plural.group(1) + ".other", "") : "");
            assertThat(placeholders(pl)).as("placeholders of " + key).isEqualTo(placeholders(en));
            for (String message : new String[]{en, pl}) {
                if (!placeholders(message).isEmpty()) {
                    // MessageFormat drops a lone apostrophe and everything quoted after it.
                    assertThat(message.replace("''", "")).as("lone apostrophe in " + key).doesNotContain("'");
                }
            }
        }
    }

    private static Set<String> placeholders(String message) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(message);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                TranslationBundleTest.class.getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
