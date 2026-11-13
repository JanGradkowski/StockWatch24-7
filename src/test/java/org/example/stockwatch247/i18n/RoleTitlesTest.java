package org.example.stockwatch247.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Officer titles from SEC filings, as Polish pages show them. */
class RoleTitlesTest {
    private static final TranslationDialect.Translator POLISH = new TranslationDialect.Translator(AppLanguage.PL.locale());

    @Test
    void knownTitlesAndCombinationsOfKnownRolesAreTranslated() {
        assertThat(RoleTitles.polish("Chief Financial Officer")).isEqualTo("Dyrektor finansowy (CFO)");
        assertThat(RoleTitles.polish("CHIEF TECHNOLOGY OFFICER")).isEqualTo("Dyrektor ds. technologii (CTO)");
        assertThat(RoleTitles.polish("Chairman and CEO")).isEqualTo("Przewodniczący rady dyrektorów i prezes zarządu (CEO)");
        assertThat(RoleTitles.polish("Chair, President & CEO")).isEqualTo("Przewodniczący rady dyrektorów i prezes zarządu (CEO)");
        assertThat(RoleTitles.polish("EVP, CFO and Treasurer")).isEqualTo("Wiceprezes wykonawczy, dyrektor finansowy (CFO) i skarbnik");
        assertThat(RoleTitles.polish("Chief Technology Officer & EVP")).isEqualTo("Dyrektor ds. technologii (CTO) i wiceprezes wykonawczy");
        assertThat(RoleTitles.polish("Pres. & Chief Customer Officer")).isEqualTo("Prezes i dyrektor ds. klientów");
    }

    @Test
    void aTitleWithAnyUnknownPartIsShownAsFiled() {
        assertThat(RoleTitles.polish("SVP - Enterprise Finance & CAO")).isNull();
        assertThat(RoleTitles.polish("EVP & GM DSG")).isNull();
        assertThat(POLISH.role("SVP, GC and Government Affairs")).isEqualTo("SVP, GC and Government Affairs");
    }

    @Test
    void chambersStillUseTheCatalogueAndEnglishStaysAsFiled() {
        assertThat(POLISH.role("Director")).isEqualTo("Członek rady dyrektorów");
        assertThat(new TranslationDialect.Translator(Locale.ENGLISH).role("EVP, CFO and Treasurer")).isEqualTo("EVP, CFO and Treasurer");
        assertThat(POLISH.role(null)).isNull();
    }
}
