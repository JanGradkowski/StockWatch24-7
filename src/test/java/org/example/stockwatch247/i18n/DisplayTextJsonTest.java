package org.example.stockwatch247.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayTextJsonTest {
    record Row(String patternLabel, String structureLabel, String pattern, List<String> warnings, String companyName) {
    }

    private final JsonMapper mapper = JsonMapper.builder().addModule(DisplayTextJsonConfiguration.module()).build();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void polishResponsesTranslateDisplayFieldsOnly() {
        LocaleContextHolder.setLocale(AppLanguage.PL.locale());
        String json = mapper.writeValueAsString(new Row("Bullish Engulfing", "Bullish Engulfing", "Bullish Engulfing",
                List.of("Bullish Engulfing"), "Bullish Engulfing"));

        assertThat(json).isEqualTo("{\"patternLabel\":\"Objęcie hossy\",\"structureLabel\":\"Bullish Engulfing\","
                + "\"pattern\":\"Bullish Engulfing\",\"warnings\":[\"Objęcie hossy\"],\"companyName\":\"Bullish Engulfing\"}");
    }

    @Test
    void englishResponsesAndNullsAreUnchanged() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(mapper.writeValueAsString(new Row("Bullish Engulfing", null, null, null, null)))
                .contains("\"patternLabel\":\"Bullish Engulfing\"").contains("\"warnings\":null");
    }

    @Test
    void englishDatesAndNumbersBecomePolish() {
        assertThat(PolishFormats.dates("3–7 Mar 2025")).isEqualTo("3–7 mar 2025");
        assertThat(PolishFormats.dates("28 Feb–4 Mar 2025")).isEqualTo("28 lut–4 mar 2025");
        assertThat(PolishFormats.dates("March 2025")).isEqualTo("marzec 2025");
        assertThat(PolishFormats.dates("05 March 2025")).isEqualTo("05 marca 2025");
        assertThat(PolishFormats.dates("9 Oct 2026, 14:30 UTC")).isEqualTo("9 paź 2026, 14:30 UTC");
        assertThat(PolishFormats.dates("Mar 5, 2025")).isEqualTo("5 mar 2025");
        assertThat(PolishFormats.dates("May the trend continue")).isEqualTo("May the trend continue");

        assertThat(PolishFormats.number("0.618")).isEqualTo("0,618");
        assertThat(PolishFormats.number("-1.25%")).isEqualTo("-1,25%");
        assertThat(PolishFormats.number("1234.5")).isEqualTo("1234,5");
        assertThat(PolishFormats.number("12,345.67 USD")).isEqualTo("12 345,67 USD");
        assertThat(PolishFormats.number("2.0σ")).isEqualTo("2,0σ");
        assertThat(PolishFormats.number("BRK.B")).isNull();
        assertThat(PolishFormats.number("5 candles")).isNull();
    }
}
