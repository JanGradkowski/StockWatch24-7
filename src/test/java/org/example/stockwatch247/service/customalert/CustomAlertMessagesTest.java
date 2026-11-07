package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.i18n.ServerMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Messages a custom alert can show are really translated, including those matched by general patterns. */
class CustomAlertMessagesTest {
    @Test
    void validationMessagesReadInPolish() {
        for (String message : List.of(
                "Choose 1 to 20 candles.", "Choose 1 to 60 candles.", "Choose 2 to 250 candles.", "Choose 1 to 730 days.",
                "Choose 1 to 20 people.", "Enter both levels of the range.", "Enter the value to compare with.",
                "Enter the percentage change.", "Enter a minimum score from 0 to 100.", "The alert definition is incomplete.",
                "Choose the indicator line the alert watches.", "Name the alert in 1 to 80 characters.",
                "Add at least one condition.", "AAPL is not in the watchlist Tech.",
                "This alert would track 12 more instruments, but your account has room for 3. Apply it to fewer stocks or stop following others.")) {
            String polish = ServerMessages.translate(message, AppLanguage.PL.locale());
            assertThat(polish).as(message).isNotEqualTo(message);
        }
        assertThat(ServerMessages.translate("AAPL is not in the watchlist Tech.", AppLanguage.PL.locale()))
                .isEqualTo("AAPL nie należy do listy obserwowanych Tech.");
    }

    @Test
    void emailLinesReadInPolish() {
        for (String line : List.of("Custom alert: Oversold bounce", "Interval: Daily", "Rule:", "All of these:",
                "Close price is above 105", "RSI (14) crosses below 30 at least once in the last 3 candles",
                "Bullish Elliott Wave signal detected in the last 5 candles. Patterns: Wave II end. Score at least 60.",
                "Candlestick pattern detected in the last 3 candles. Patterns: Bullish Engulfing, Hammer. Confirmed patterns only.",
                "Bullish candlestick pattern confirmed in the last 5 candles. Score at least 60.",
                "Insiders bought in the last 30 days. At least 2 people. At least $250,000 in total. Roles: officers, directors.",
                "Members of Congress sold in the last 45 days. Senate only.", "Automated outlook at least moderate buy",
                "Not: Volume makes a new 20-candle high", "MACD histogram (12, 26, 9) rises for 2 candles in a row",
                "Completed candle: 14 Oct 2026", "Close: 182.4", "Conditions met: 3 of 3", "And 14 more",
                "Earlier triggers (2): 13 Oct 2026, 12 Oct 2026",
                "Informational only, not financial advice.",
                "Custom alert: Oversold bounce for AAPL", "Custom alert: Oversold bounce for 6 stocks",
                "In this order, all within 20 candles:", "In this order, all within 25 candles, completed in the last 3 candles:",
                "- Step 1: RSI (14) is below 30 for at least 5 candles in a row", "- Step 2, within 3 candles: RSI (14) is above 30",
                "- Step 3, on the next candle: Bearish harmonic pattern detected. Score at least 60.",
                "The whole sequence needs at least 6 candles.")) {
            String polish = ServerMessages.translateLines(line, AppLanguage.PL.locale());
            assertThat(polish).as(line).isNotEqualTo(line);
        }
        assertThat(ServerMessages.translateLines("- Step 1: RSI (14) is below 30 for at least 5 candles in a row", AppLanguage.PL.locale()))
                .isEqualTo("- Krok 1: RSI (14) poniżej 30 na każdej z co najmniej 5 kolejnych świec");
        assertThat(ServerMessages.translateLines("- Step 2, within 3 candles: Bullish candlestick pattern confirmed.", AppLanguage.PL.locale()))
                .isEqualTo("- Krok 2, w ciągu 3 świec: Wzrostowa formacja świecowa potwierdzona.");
    }
}
