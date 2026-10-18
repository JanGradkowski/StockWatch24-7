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
                "Insiders bought in the last 30 days. At least 2 people. At least $250,000 in total. Roles: officers, directors.",
                "Members of Congress sold in the last 45 days. Senate only.", "Automated outlook at least moderate buy",
                "Not: Volume makes a new 20-candle high", "MACD histogram (12, 26, 9) rises for 2 candles in a row",
                "Completed candle: 14 Oct 2026", "Close: 182.4", "Conditions met: 3 of 3", "And 14 more",
                "Informational only, not financial advice.",
                "Custom alert: Oversold bounce for AAPL", "Custom alert: Oversold bounce for 6 stocks")) {
            String polish = ServerMessages.translateLines(line, AppLanguage.PL.locale());
            assertThat(polish).as(line).isNotEqualTo(line);
        }
    }
}
