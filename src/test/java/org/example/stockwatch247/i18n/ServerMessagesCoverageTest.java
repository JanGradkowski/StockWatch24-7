package org.example.stockwatch247.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message the Java code can show a user must be classified in i18n/server-messages.txt: either
 * translated or marked internal. Messages assembled from pieces are checked with each computed value
 * replaced by a sample, so the catalog's "{0}" patterns have to cover them too.
 */
class ServerMessagesCoverageTest {
    private static final Pattern MESSAGE_SITE = Pattern.compile(
            "new (?:UserInputException|IllegalArgumentException|IllegalStateException|ConflictException"
                    + "|TechnicalOutlookCapacityException|InsiderDataUnavailableException|CongressionalRefreshLimitException)\\(\\s*"
                    + "|add(?:Flash)?Attribute\\(\"(?:success|error|message|warning|notice|[a-zA-Z]+(?:Error|Message))\",\\s*"
                    + "|(?:ApiErrorResponses\\.of|failure)\\([a-zA-Z]+,\\s*"
                    + "|Map\\.of\\(\"(?:error|status)\",\\s*(?:org\\.example\\.stockwatch247\\.i18n\\.ServerMessages\\.translate\\()?");
    /** Assembled with branches a single sample cannot follow; their catalog entries were checked by hand. */
    private static final Set<String> CHECKED_BY_HAND = Set.of(
            "Use XX characters without control characters.",
            // Scheduler stage failure "<stage>: <cause>"; internal, and a "{0}: {1}" catalog entry would
            // swallow every labelled settings message above it.
            "X: X");

    @Test
    void everyUserFacingMessageInTheCodeIsTranslatedOrMarkedInternal() throws IOException {
        ServerMessages.Catalog catalog = ServerMessages.catalog();
        Set<String> missing = new TreeSet<>();
        for (String message : messagesInCode()) {
            if (!catalog.covers(message) && !CHECKED_BY_HAND.contains(message)) missing.add(message);
        }
        assertThat(missing).as("add these to src/main/resources/i18n/server-messages.txt").isEmpty();
    }

    @Test
    void polishTranslationsKeepTheirValuesAndExactEntriesWin() {
        Locale polish = AppLanguage.PL.locale();
        assertThat(ServerMessages.translate("Current password is incorrect.", polish))
                .isEqualTo("Obecne hasło jest nieprawidłowe.");
        assertThat(ServerMessages.translate("No market data was found for XYZ.WA. Check the ticker and exchange, then try again.", polish))
                .isEqualTo("Nie znaleziono danych rynkowych dla XYZ.WA. Sprawdź ticker i giełdę, a następnie spróbuj ponownie.");
        assertThat(ServerMessages.translate(
                "This watchlist does not allow candlestick signals. Choose another list or edit its allowed signal types.", polish))
                .as("an exact entry wins over the general pattern").contains("sygnałów formacji świecowych");
        assertThat(ServerMessages.translate("Bullish Engulfing — Minimum first-candle body must be a number.", polish))
                .as("names inside a message come from the interface translations")
                .isEqualTo("„Objęcie hossy”: wartość „Minimalny korpus pierwszej świecy” musi być liczbą.");
        assertThat(ServerMessages.translate("Enter a whole number for RSI buy boundary.", polish))
                .isEqualTo("Wpisz liczbę całkowitą w polu „Próg kupna RSI”.");
        assertThat(ServerMessages.translate("Shared risk-to-reward ratio must be between 0.1 and 20.", polish))
                .as("a specific entry wins over the generic one listed later")
                .isEqualTo("Stosunek zysku do ryzyka musi wynosić od 0,1 do 20.");
        assertThat(ServerMessages.translate("Twelve Data returned no candles.", polish))
                .as("internal messages stay in English").isEqualTo("Twelve Data returned no candles.");
        assertThat(ServerMessages.translate("Something nobody catalogued.", polish)).isEqualTo("Something nobody catalogued.");
        assertThat(ServerMessages.translate("Current password is incorrect.", Locale.ENGLISH))
                .isEqualTo("Current password is incorrect.");
        assertThat(ServerMessages.translate(null, polish)).isNull();
    }

    private static List<String> messagesInCode() throws IOException {
        List<String> messages = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher site = MESSAGE_SITE.matcher(source);
                while (site.find()) {
                    String argument = argument(source, site.end());
                    if (argument.indexOf('"') < 0) continue;
                    String sample = sample(argument);
                    if (!sample.isBlank() && !sample.equals("X")) messages.add(sample);
                }
            }
        }
        return messages;
    }

    /** The argument starting at {@code start}, up to the comma or parenthesis that ends it. */
    private static String argument(String source, int start) {
        int depth = 0;
        for (int index = start; index < source.length(); index++) {
            char c = source.charAt(index);
            if (c == '"' || c == '\'') {
                index = endOfLiteral(source, index, c);
            } else if (c == '(') {
                depth++;
            } else if (c == ')' || c == ',' || c == ';') {
                if (depth == 0) return source.substring(start, index);
                if (c == ')') depth--;
            }
        }
        return source.substring(start);
    }

    private static int endOfLiteral(String source, int open, char quote) {
        for (int index = open + 1; index < source.length(); index++) {
            if (source.charAt(index) == '\\') index++;
            else if (source.charAt(index) == quote) return index;
        }
        return source.length();
    }

    /** Joins the string literals of a top-level concatenation and stands an "X" in for each computed value. */
    private static String sample(String expression) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int partStart = 0;
        for (int index = 0; index < expression.length(); index++) {
            char c = expression.charAt(index);
            if (c == '"' || c == '\'') index = endOfLiteral(expression, index, c);
            else if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '+' && depth == 0) {
                parts.add(expression.substring(partStart, index).strip());
                partStart = index + 1;
            }
        }
        parts.add(expression.substring(partStart).strip());
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            boolean literal = part.length() >= 2 && part.charAt(0) == '"' && endOfLiteral(part, 0, '"') == part.length() - 1;
            text.append(literal ? unescape(part.substring(1, part.length() - 1)) : "X");
        }
        return text.toString();
    }

    private static String unescape(String literal) {
        return literal.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
