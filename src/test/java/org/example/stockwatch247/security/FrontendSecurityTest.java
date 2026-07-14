package org.example.stockwatch247.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSecurityTest {

    @Test
    void templatesAvoidKnownDomXssAndThirdPartyLeakageSinks() throws IOException {
        String navbar = Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html"));
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));

        assertFalse(navbar.contains("innerHTML"));
        assertFalse(navbar.matches("(?s).*\\son(?:click|load|error)=.*"));
        assertFalse(navbar.contains("style="));
        assertFalse(navbar.contains(".style."));
        assertTrue(navbar.contains("textContent"));
        assertTrue(navbar.contains("encodeURIComponent"));
        assertFalse(stock.contains("unpkg.com"));
        assertFalse(stock.contains("financialmodelingprep.com"));
        assertTrue(stock.contains("/webjars/lightweight-charts/"));
        assertFalse(stock.contains("<style>"));
        assertTrue(stock.contains("<style th:attr=\"nonce=${cspNonce}\">"));
        assertFalse(stock.contains("style="));
    }

    @Test
    void stockTemplateSupportsWeeklyAndMonthlyElliottControlsAndOverlay() throws IOException {
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));

        assertTrue(stock.contains("data-check-family=\"ELLIOTT_WAVE\" data-check-interval=\"MONTHLY\" data-check-signal=\"BUY\""));
        assertTrue(stock.contains("data-check-family=\"ELLIOTT_WAVE\" data-check-interval=\"MONTHLY\" data-check-signal=\"SELL\""));
        assertTrue(stock.contains("data-check-family=\"ELLIOTT_WAVE\" data-check-interval=\"WEEKLY\" data-check-signal=\"BUY\""));
        assertTrue(stock.contains("data-check-family=\"ELLIOTT_WAVE\" data-check-interval=\"WEEKLY\" data-check-signal=\"SELL\""));
        assertTrue(stock.contains("patternFamily: button.dataset.checkFamily || 'CANDLESTICK'"));
        assertTrue(stock.contains("/elliott-waves?interval="));
        assertTrue(stock.contains("elliottSeries.setMarkers(markers)"));
        assertTrue(stock.contains("await showCheckedElliottWave(payload.interval)"));
        assertTrue(stock.contains("alertInterval === 'WEEKLY' ? '1wk' : '1mo'"));
        assertTrue(stock.contains("id=\"historicElliottBtn\""));
        assertTrue(stock.contains("/elliott-waves/history?interval="));
        assertTrue(stock.contains("Daily Elliott waves are not supported."));
        assertTrue(stock.contains("status.classList.add('error')"));
        assertTrue(stock.contains("setElliottConfirmationMarkers"));
        assertTrue(stock.contains("structure.confirmationTimestamp"));
        assertTrue(stock.contains("quality ${overlay.qualityScore}/100"));
        assertTrue(stock.contains("id=\"instrumentTypeDisplay\""));
        assertTrue(stock.contains("instrumentType === 'INDEX'"));

        String navbar = Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html"));
        assertTrue(navbar.contains("Search stocks or indexes"));
        assertTrue(navbar.contains("instrumentType === 'INDEX'"));
        assertTrue(navbar.contains("typeLabel.textContent"));

        int candlestickStart = stock.indexOf("<div class=\"alert-section-title\">Candlestick Patterns</div>");
        int elliottStart = stock.indexOf("<div class=\"alert-section-title\">Elliott Wave Patterns</div>");
        int elliottEnd = stock.indexOf("<!-- Price Chart Canvas -->");
        String candlestickSection = stock.substring(candlestickStart, elliottStart);
        String elliottSection = stock.substring(elliottStart, elliottEnd);
        assertFalse(candlestickSection.contains("data-alert-family=\"ELLIOTT_WAVE\""));
        assertTrue(elliottSection.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"WEEKLY\""));
        assertTrue(elliottSection.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"MONTHLY\""));
    }

    @Test
    void fullCandleHistoryReplacementRebuildsDateDeduplicationState() throws IOException {
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));

        int processorStart = stock.indexOf("function processAndSetData(rawData, isPrepend)");
        int candleLoopStart = stock.indexOf("rawData.forEach", processorStart);
        assertTrue(processorStart >= 0);
        assertTrue(candleLoopStart > processorStart);

        String replacementSetup = stock.substring(processorStart, candleLoopStart);
        assertTrue(replacementSetup.matches(
                "(?s).*if\\s*\\(!isPrepend\\)\\s*\\{\\s*seenDates\\.clear\\(\\);\\s*}.*"));
    }
}
