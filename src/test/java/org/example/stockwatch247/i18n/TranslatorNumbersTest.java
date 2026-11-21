package org.example.stockwatch247.i18n;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Amounts and changes as the demo trading page shows them, in each language. */
class TranslatorNumbersTest {
    private static final TranslationDialect.Translator ENGLISH = new TranslationDialect.Translator(Locale.ENGLISH);
    private static final TranslationDialect.Translator POLISH = new TranslationDialect.Translator(AppLanguage.PL.locale());

    @Test
    void groupsThousandsAndKeepsTheCurrencyOnTheSameLine() {
        assertThat(ENGLISH.money(new BigDecimal("6540.634"), 2, "USD")).isEqualTo("6,540.63 USD");
        assertThat(POLISH.money(new BigDecimal("6540.635"), 2, "USD")).isEqualTo("6 540,64 USD");
        assertThat(ENGLISH.number(78.16, 2)).isEqualTo("78.16");
    }

    @Test
    void zeroIsNeitherAGainNorALoss() {
        assertThat(ENGLISH.signedPercent(new BigDecimal("0.004"))).isEqualTo("0.00%");
        assertThat(ENGLISH.tone(new BigDecimal("-0.004"))).isEqualTo("neutral");
        assertThat(ENGLISH.signedPercent(new BigDecimal("23.98"))).isEqualTo("+23.98%");
        assertThat(ENGLISH.tone(new BigDecimal("23.98"))).isEqualTo("positive");
        assertThat(POLISH.signedMoney(new BigDecimal("-1188.72"), "USD")).isEqualTo("-1 188,72 USD");
        assertThat(POLISH.tone(new BigDecimal("-1188.72"))).isEqualTo("negative");
    }
}
