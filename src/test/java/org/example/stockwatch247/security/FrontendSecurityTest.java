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
        assertTrue(stock.contains("refreshHistoricalElliottOverlays"));
        assertTrue(stock.contains("series.setMarkers(structure.points"));
        assertTrue(stock.contains("await showCheckedElliottWave(payload.interval)"));
        assertTrue(stock.contains("alertInterval === 'WEEKLY' ? '1wk' : '1mo'"));
        assertTrue(stock.contains("id=\"elliottOverlayToggle\""));
        assertFalse(stock.contains("id=\"historicElliottBtn\""));
        assertTrue(stock.contains("/elliott-waves/history?interval="));
        assertTrue(stock.contains("&from=${encodeURIComponent(oldestTimestamp)}"));
        assertTrue(stock.contains("structure.structureId || elliottStructureFallbackId(structure)"));
        assertTrue(stock.contains("status.classList.add('error')"));
        assertTrue(stock.contains("setElliottConfirmationMarkers"));
        assertTrue(stock.contains("structure.confirmationTimestamp"));
        assertTrue(stock.contains("color: 'rgba(0, 0, 0, 0)'"));
        assertFalse(stock.contains("lineVisible: false"));
        assertTrue(stock.contains("elliottQualityWarnings(structure)"));
        assertTrue(stock.contains("Quality range ${qualityMin}–${qualityMax}/100"));
        assertTrue(stock.contains("structure.deepWaveTwo"));
        assertTrue(stock.contains("structure.qualityWarnings"));
        assertTrue(stock.contains("structure.impulseVariant === 'TRUNCATED_FIFTH'"));
        assertTrue(stock.contains("structure.correctionVariant === 'EXPANDED_FLAT'"));
        assertTrue(stock.contains("structure.correctionVariant === 'RUNNING_FLAT'"));
        assertTrue(stock.contains("elliottPointMarkerText"));
        assertTrue(stock.contains("Deep Wave II"));
        assertTrue(stock.contains("reduced confidence"));
        assertTrue(stock.contains("id=\"instrumentTypeDisplay\""));
        assertTrue(stock.contains("instrumentType === 'INDEX'"));

        String navbar = Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html"));
        assertTrue(navbar.contains("Search stocks or indexes"));
        assertTrue(navbar.contains("instrumentType === 'INDEX'"));
        assertTrue(navbar.contains("typeLabel.textContent"));
        assertTrue(navbar.contains("/api/stocks/search/local?q="));
        assertTrue(navbar.contains("new AbortController()"));
        assertTrue(navbar.contains("setTimeout(() => runTickerSearch(query), 225)"));

        assertTrue(stock.contains("const INITIAL_CANDLE_LIMIT = 1000"));
        assertTrue(stock.contains("const INITIAL_VISIBLE_CANDLES = 150"));
        assertTrue(stock.contains("const HISTORY_PAGE_LIMIT = 500"));
        assertTrue(stock.contains("getVisibleLogicalRange()"));
        assertTrue(stock.contains("candleSeries.barsInLogicalRange(range)"));
        assertTrue(stock.contains("barsBefore < HISTORY_PREFETCH_THRESHOLD"));
        assertTrue(stock.contains("pageMayHaveMore(page, data, INITIAL_CANDLE_LIMIT)"));
        assertTrue(stock.contains("visibleLogicalRange.from + newCandles.length"));
        assertTrue(stock.contains("scheduleViewportHistoryFill()"));
        assertTrue(stock.contains("candleSeries.setData(globalCandleData.map(item => ({ ...item })))"));
        assertFalse(stock.contains("setVisibleRange(visibleTimeRange)"));
        assertFalse(stock.contains("timeScale().fitContent()"));
        int loadChartData = stock.indexOf("async function loadChartData(interval)");
        int paginationReady = stock.indexOf("isFetching = false;", loadChartData);
        int initialOverlayRefresh = stock.indexOf(
                "await refreshHistoricalElliottOverlays(interval);", loadChartData);
        assertTrue(paginationReady > loadChartData);
        assertTrue(paginationReady < initialOverlayRefresh);

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
    void stockAlertChangesRemainDraftsUntilExplicitlyApplied() throws IOException {
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));
        String navbar = Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html"));

        assertTrue(stock.contains("id=\"applyAlertChangesBtn\""));
        assertTrue(stock.contains("id=\"unsavedAlertDialog\""));
        assertTrue(stock.contains("id=\"saveAlertChangesBeforeLeaveBtn\""));
        assertTrue(stock.contains("id=\"discardAlertChangesBtn\""));
        assertTrue(stock.contains("id=\"keepEditingAlertsBtn\""));
        assertTrue(stock.contains("const persistedAlertState = new Map()"));
        assertTrue(stock.contains("body: JSON.stringify({ changes: changedInputs.map(alertChangePayload) })"));
        assertTrue(stock.contains("method: 'PUT'"));
        assertTrue(stock.contains("if (alertSavePromise) return alertSavePromise"));
        assertTrue(stock.contains("window.addEventListener('beforeunload'"));
        assertTrue(stock.contains("window.requestStockWatchNavigation"));
        assertTrue(stock.contains("if (pageExitAllowed || !hasUnappliedAlertChanges()"));
        assertFalse(stock.contains("input.addEventListener('change', () => updateAlert(input))"));
        assertFalse(stock.contains("async function updateAlert(input)"));
        assertTrue(navbar.contains("window.requestStockWatchNavigation(destination)"));
    }

    @Test
    void dashboardAndHistoryUseCompanyLevelDynamicRuleColumns() throws IOException {
        String dashboard = Files.readString(Path.of("src/main/resources/templates/home.html"));
        String dashboardScript = Files.readString(Path.of("src/main/resources/static/js/dashboard.js"));
        String history = Files.readString(Path.of("src/main/resources/templates/alert-history.html"));
        String signalDetail = Files.readString(Path.of("src/main/resources/templates/signal-detail.html"));

        assertTrue(dashboard.contains("th:each=\"company : ${trackedCompanies}\""));
        assertTrue(dashboard.contains("company.representativeAlertId()"));
        assertTrue(dashboard.contains("company.ruleCount()"));
        assertFalse(dashboard.contains("th:each=\"alert : ${activeAlerts}\""));
        assertTrue(dashboard.contains("th:each=\"signal : ${latestSignals}\""));
        assertTrue(dashboard.contains("@{/alerts/signals/{id}(id=${signal.id()})}"));
        assertTrue(dashboard.contains("signal.setupScore()"));
        assertTrue(dashboard.contains("signal.signalPeriodLabel()"));
        assertTrue(dashboard.contains("signal.researchHorizonLabel()"));
        assertTrue(dashboard.contains("data-watch-filter=\"stocks\""));
        assertTrue(dashboard.contains("data-watch-filter=\"funds\""));
        assertTrue(dashboard.contains("data-instrument-group=${company.instrumentGroup()}"));
        assertTrue(dashboard.contains("th:hidden=\"${company.instrumentGroup() != 'stocks'}\""));
        assertTrue(dashboard.contains("@{/js/dashboard.js}"));
        assertTrue(dashboardScript.contains("activateFilter(\"stocks\")"));
        assertTrue(dashboardScript.contains("row.hidden = !visible"));
        assertTrue(dashboardScript.contains("button.setAttribute(\"aria-pressed\", String(selected))"));
        assertFalse(dashboardScript.contains("innerHTML"));

        assertTrue(history.contains("th:each=\"column, columnStatus : ${history.columns()}\""));
        assertTrue(history.contains("column.alert().familyLabel()"));
        assertTrue(history.contains("column.alert().tradeSignal()"));
        assertTrue(history.contains("column.alert().intervalLabel()"));
        assertTrue(history.contains("column.alert().researchHorizonLabel()"));
        assertTrue(history.contains("/alerts/signals/{id}"));
        assertTrue(history.contains("event.id()"));
        assertTrue(history.contains("event.signalPeriodLabel()"));
        assertFalse(history.contains("class=\"signal-table\""));

        assertTrue(signalDetail.contains("signal.setupScore()"));
        assertTrue(signalDetail.contains("signal.signalPeriodLabel()"));
        assertTrue(signalDetail.contains("signal.researchHorizonLabel()"));
        assertTrue(signalDetail.contains("#numbers.sequence(1, 20)"));
        assertTrue(signalDetail.contains("signal.reasons()"));
        assertTrue(signalDetail.contains("reason.text()"));
        assertTrue(signalDetail.contains("Detailed evidence was not stored for this signal"));
        assertFalse(signalDetail.contains("th:utext"));
        assertFalse(signalDetail.contains("style="));
        assertFalse(signalDetail.contains("<style>"));
        assertFalse(signalDetail.matches("(?s).*\\son(?:click|load|error)=.*"));
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
