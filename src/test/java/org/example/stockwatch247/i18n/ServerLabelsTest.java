package org.example.stockwatch247.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Labels and summaries built by services, as they are shown to Polish users (i18n/server-labels.txt). */
class ServerLabelsTest {
    private static String polish(String english) {
        return ServerMessages.translate(english, AppLanguage.PL.locale());
    }

    @Test
    void signalLifecycleSummariesTranslateWithPolishDatesAndNumbers() {
        assertThat(polish("Trade closed successfully on 3–7 Mar 2025 when candle 4 closed at 123.4500 and reached the 125.0000 profit target."))
                .isEqualTo("Transakcja zamknęła się z zyskiem (3–7 mar 2025), gdy świeca 4 zamknęła się na poziomie 123,4500 i osiągnęła cel zysku 125,0000.");
        assertThat(polish("Invalidated on March 2025 because the stored wave structure stopped satisfying its hard rules."))
                .isEqualTo("Zanegowano (marzec 2025), ponieważ zapisana struktura fal przestała spełniać swoje twarde reguły.");
        assertThat(polish("Waiting for a completed candle to close below 98.1200. The setup expires after 8 candles unless it confirms or invalidates first."))
                .isEqualTo("Oczekiwanie na zamknięcie świecy poniżej 98,1200. Układ wygaśnie po 8 świecach, jeśli wcześniej nie zostanie potwierdzony ani zanegowany.");
        assertThat(polish("Potential sell/short")).isEqualTo("Potencjalny sygnał sprzedaży / krótkiej sprzedaży");
        assertThat(polish("Wave IV target reached")).isEqualTo("Fala IV: osiągnięto cel");
        assertThat(polish("09 October 2026")).isEqualTo("09 października 2026");
    }

    @Test
    void scoreEvidenceTranslatesSubjectsFragmentsAndSpecificPatternsFirst() {
        assertThat(polish("EMA(20)/EMA(50) order supported the bullish direction."))
                .isEqualTo("Kolejność EMA(20)/EMA(50): wspiera kierunek wzrostowy.");
        assertThat(polish("Rolling VWAP(20) slope did not support the bearish direction."))
                .isEqualTo("Nachylenie kroczącego VWAP(20): nie wspiera kierunku spadkowego.");
        assertThat(polish("Completed weekly close/5-period mean supported the bullish direction and 5-period mean slope did not support the bullish direction."))
                .isEqualTo("Zamknięcie tygodniowe względem średniej z 5 okresów wspiera kierunek wzrostowy; "
                        + "nachylenie średniej z 5 okresów nie wspiera kierunku wzrostowego.");
        assertThat(polish("Independent original move/count model: established downtrend across 5 completed pre-pattern candles "
                + "(2.10% net move, 4/5 directional closes, 3/4 lower-high/lower-low transitions)."))
                .isEqualTo("Niezależny pierwotny model ruchu i liczby świec: ustalony trend spadkowy w 5 zamkniętych świecach "
                        + "przed formacją (ruch netto 2,10%, kierunkowe zamknięcia 4/5, przejścia niższych szczytów i dołków 3/4).");
        assertThat(polish("Wave III is at least as long as wave I (+5).")).isEqualTo("Fala III jest co najmniej tak długa jak fala I (+5).");
        assertThat(polish("Wave II is in its common zone (+5).")).isEqualTo("Fala II: w typowej strefie (+5).");
    }

    @Test
    void signalDetailScoreCardUsesTheSameWordingAsItsLegend() {
        assertThat(polish("Moderate support")).isEqualTo("Umiarkowane wsparcie");
        assertThat(polish("Several selected indicators support this signal; others are mixed."))
                .isEqualTo("Kilka wybranych wskaźników wspiera ten sygnał; pozostałe są niejednoznaczne.");
        assertThat(polish("Wave v end was bullish and occurred 3 candles earlier. Current scoring settings apply +5 points."))
                .isEqualTo("Koniec fali V: sygnał wzrostowy, liczba świec wstecz: 3. Bieżące ustawienia oceny przyznają +5 pkt.");
        assertThat(polish("Structural / pivot quality")).as("same wording as the scoring settings page")
                .isEqualTo("Jakość struktury i punktów zwrotnych");
    }

    @Test
    void joinedSentencesTranslateOnlyWhenEveryPartIsKnown() {
        assertThat(polish("The entry candle is unavailable, so results cannot be measured safely. Trade open"))
                .isEqualTo("Świeca wejścia jest niedostępna, więc nie da się wiarygodnie zmierzyć wyników. Transakcja otwarta");
        assertThat(polish("The entry candle is unavailable, so results cannot be measured safely. Something unknown."))
                .isEqualTo("The entry candle is unavailable, so results cannot be measured safely. Something unknown.");
    }

    @Test
    void alertEmailsTranslateLineByLineKeepingBulletsAndIndentation() {
        String body = """
                A technical pattern was detected for AAPL.

                Pattern family: CANDLESTICK
                Pattern: BULLISH_ENGULFING
                Direction classification: BUY
                Setup strength: High confluence
                Heuristic setup score: 88/100
                Score note: technical confluence, not a probability of profit.

                Score breakdown
                - Trend indicators: 12/20 (partial support)
                  - EMA(20)/EMA(50) order [5 pts]: EMA(20)/EMA(50) order supported the bullish direction.

                Interval: DAILY
                Signal candle period: 3–7 Mar 2025
                Close price: 123.45
                """;
        assertThat(ServerMessages.translateLines(body, AppLanguage.PL.locale())).isEqualTo("""
                Wykryto formację techniczną dla AAPL.

                Rodzina formacji: Formacje świecowe
                Formacja: Objęcie hossy
                Klasyfikacja kierunku: KUPNO
                Siła układu: Wysoka zbieżność
                Heurystyczna ocena sygnału: 88/100
                Uwaga do oceny: zbieżność techniczna, a nie prawdopodobieństwo zysku.

                Składniki oceny
                - Wskaźniki trendu: 12/20 (częściowe wsparcie)
                  - Kolejność EMA(20)/EMA(50) [5 pkt]: Kolejność EMA(20)/EMA(50): wspiera kierunek wzrostowy.

                Interwał: dzienny
                Okres świecy sygnału: 3–7 mar 2025
                Cena zamknięcia: 123,45
                """);
        assertThat(ServerMessages.translateLines(body, Locale.ENGLISH)).isEqualTo(body);
    }

    @Test
    void englishStaysEnglish() {
        String summary = "Trade closed successfully on 3–7 Mar 2025 when candle 4 closed at 123.4500 and reached the 125.0000 profit target.";
        assertThat(ServerMessages.translate(summary, Locale.ENGLISH)).isEqualTo(summary);
    }
}
