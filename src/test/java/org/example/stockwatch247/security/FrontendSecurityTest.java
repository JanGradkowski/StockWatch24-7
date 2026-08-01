package org.example.stockwatch247.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSecurityTest {

    @Test
    void everyPageLoadsThePersistentCspSafeThemeToggle() throws IOException {
        List<String> templates = List.of(
                "index.html",
                "about.html",
                "login.html",
                "signup.html",
                "resend-verification.html",
                "home.html",
                "stock.html",
                "alert-history.html",
                "signal-detail.html",
                "historical-candlestick-detail.html");
        templates = new java.util.ArrayList<>(templates);
        templates.addAll(List.of("settings.html", "login-2fa.html", "forgot-password.html", "reset-password.html",
                "cancel-account-deletion.html"));
        for (String template : templates) {
            String source = Files.readString(
                    Path.of("src/main/resources/templates", template));
            assertTrue(source.contains("th:src=\"@{/js/theme.js}\""), template);
            assertTrue(source.indexOf("/js/theme.js") < source.indexOf("/css/style.css"), template);
        }

        String theme = Files.readString(
                Path.of("src/main/resources/static/js/theme.js"));
        String stylesheet = Files.readString(
                Path.of("src/main/resources/static/css/style.css"));
        assertTrue(theme.contains("stockwatch-theme"));
        assertTrue(theme.contains("return 'dark'"));
        assertTrue(theme.contains("createSvgIcon('sun')"));
        assertTrue(theme.contains("createSvgIcon('moon')"));
        assertTrue(theme.contains("Switch to light mode"));
        assertTrue(theme.contains("Switch to dark mode"));
        assertTrue(theme.contains("stockwatch:themechange"));
        assertTrue(theme.contains("toggleAttribute('hidden'"));
        assertTrue(theme.contains("document.querySelector('.navbar')"));
        assertTrue(theme.contains("theme-toggle-in-navbar"));
        assertFalse(theme.contains("innerHTML"));
        assertFalse(theme.contains(".style."));
        assertTrue(stylesheet.contains(":root[data-theme=\"light\"]"));
        assertTrue(stylesheet.contains(".theme-toggle"));
        assertTrue(stylesheet.contains(".theme-toggle.theme-toggle-in-navbar"));
        assertTrue(stylesheet.contains("Light theme refinement"));
        assertTrue(theme.contains("persistAccountTheme"));
        assertFalse(theme.contains("innerHTML"));
    }

    @Test
    void templatesAvoidKnownDomXssAndThirdPartyLeakageSinks() throws IOException {
        String navbar = Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html"));
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));
        String historicalCandlestickDetail = Files.readString(
                Path.of("src/main/resources/templates/historical-candlestick-detail.html"));
        String about = Files.readString(Path.of("src/main/resources/templates/about.html"));

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
        assertFalse(historicalCandlestickDetail.contains("th:utext"));
        assertFalse(historicalCandlestickDetail.contains("style="));
        assertFalse(historicalCandlestickDetail.contains("<style>"));
        assertFalse(historicalCandlestickDetail.matches("(?s).*\\son(?:click|load|error)=.*"));
        assertFalse(about.contains("th:utext"));
        assertFalse(about.contains("style="));
        assertFalse(about.contains("<style>"));
        assertFalse(about.matches("(?s).*\\son(?:click|load|error)=.*"));
    }

    @Test
    void aboutPageDocumentsTheImplementedDetectionAndResearchRules() throws IOException {
        String index = Files.readString(Path.of("src/main/resources/templates/index.html"));
        String about = Files.readString(Path.of("src/main/resources/templates/about.html"));

        assertTrue(index.contains("th:href=\"@{/about}\""));
        assertTrue(about.contains("id=\"candlesticks\""));
        assertTrue(about.contains("id=\"scoring\""));
        assertTrue(about.contains("id=\"elliott\""));
        assertTrue(about.contains("id=\"history\""));
        assertTrue(about.contains("CANDLE_V4_EXPERIMENTAL"));
        assertTrue(about.contains("ELLIOTT_V1"));
        assertTrue(about.contains("Pattern quality"));
        assertTrue(about.contains("Support or resistance"));
        assertTrue(about.contains("At least 34 completed candles"));
        assertTrue(about.contains("1–750 candles"));
        assertTrue(about.contains("If the ticker has less history"));
        assertTrue(about.contains("@{/images/about/analysis-hero.png}"));
        assertTrue(about.contains("@{/images/about/market-workspace.png}"));
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
        assertTrue(stock.contains("id=\"showHistoricalCandlestickPatternsBtn\""));
        assertTrue(stock.indexOf("Automated email signals")
                < stock.indexOf("id=\"showHistoricalCandlestickPatternsBtn\""));
        assertTrue(stock.indexOf("id=\"showHistoricalCandlestickPatternsBtn\"")
                < stock.indexOf("Candlestick Patterns"));
        assertTrue(stock.contains("id=\"historicalCandlestickIntervalDialog\""));
        assertTrue(stock.contains("id=\"historicalCandlestickLookbackDialog\""));
        assertTrue(stock.contains("id=\"historicalCandlestickLookbackInput\""));
        assertTrue(stock.contains("id=\"historicalCandlestickLookbackDate\""));
        assertTrue(stock.contains("id=\"historicalCandlestickResultsDialog\""));
        assertTrue(stock.contains("data-historical-candlestick-interval=\"1d\""));
        assertTrue(stock.contains("data-historical-candlestick-interval=\"1wk\""));
        assertTrue(stock.contains("data-historical-candlestick-interval=\"1mo\""));
        assertTrue(stock.contains("/candlestick-patterns/history?interval="));
        assertTrue(stock.contains("&lookbackCandles=${encodeURIComponent(lookbackCandles)}"));
        assertTrue(stock.contains("cache: 'no-store'"));
        assertTrue(stock.contains("createHistoricalCandlestickRow"));
        assertTrue(stock.contains("reopenHistoricalCandlestickResultsFromUrl"));

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
        assertTrue(stock.contains("candleSeries.setData(globalCandleData.map(item => ({"));
        assertTrue(stock.contains("id=\"anchoredVolumeProfileToggle\""));
        assertTrue(stock.contains("handleAnchoredVolumeProfileChartClick"));
        assertTrue(stock.contains("/anchored-volume-profile"));
        assertTrue(stock.contains("id=\"anchoredVolumeProfileSummary\""));
        assertTrue(stock.contains("applyAnchoredVolumeProfileChartSpacing"));
        assertTrue(stock.contains("ANCHORED_PROFILE_RIGHT_OFFSET_BARS"));
        assertTrue(stock.contains("formatProfileCalculationInterval"));
        assertFalse(stock.contains("anchoredVolumeProfileRail"));
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
        int elliottEnd = stock.indexOf("<section id=\"congressionalActivityPanel\"", elliottStart);
        String candlestickSection = stock.substring(candlestickStart, elliottStart);
        String elliottSection = stock.substring(elliottStart, elliottEnd);
        assertFalse(candlestickSection.contains("data-alert-family=\"ELLIOTT_WAVE\""));
        assertTrue(elliottSection.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"WEEKLY\""));
        assertTrue(elliottSection.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"MONTHLY\""));
    }

    @Test
    void stockWorkspacePutsMarketAnalysisBeforeAlertControlsAndCongressionalActivity() throws IOException {
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));

        int priceChart = stock.indexOf("id=\"priceChartContainer\"");
        int volumeChart = stock.indexOf("id=\"volumeChartContainer\"");
        int automatedSignals = stock.indexOf("<section class=\"alert-panel\">");
        int congressionalActivity = stock.indexOf("id=\"congressionalActivityPanel\"");

        assertTrue(priceChart >= 0);
        assertTrue(volumeChart > priceChart);
        assertTrue(automatedSignals > volumeChart);
        assertTrue(congressionalActivity > automatedSignals);
    }

    @Test
    void stockWorkspaceUsesAnAccessibleLoaderUntilAsyncInitializationFinishes() throws IOException {
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));
        String stylesheet = Files.readString(Path.of("src/main/resources/static/css/style.css"));

        assertTrue(stock.contains("<body class=\"stock-is-loading\">"));
        assertTrue(stock.contains("id=\"stockPageLoader\""));
        assertTrue(stock.contains("role=\"status\""));
        assertTrue(stock.contains("aria-live=\"polite\""));
        assertTrue(stock.contains("<main class=\"stock-page\" aria-busy=\"true\">"));
        assertTrue(stock.contains("const stockLoaderSafetyTimer = window.setTimeout"));
        assertTrue(stock.contains("finishStockPageLoading(completionMessage);"));
        assertTrue(stock.contains("stockWorkspace.setAttribute('aria-busy', 'false')"));
        assertTrue(stock.contains("document.body.classList.remove('stock-is-loading')"));
        assertTrue(stock.contains("stockPageLoader.classList.add('is-complete')"));
        assertTrue(stock.contains("window.onload = initCharts"));
        assertTrue(stock.indexOf("id=\"stockPageLoader\"")
                < stock.indexOf("<div th:replace=\"~{fragments/navbar :: navbar}\"></div>"));

        assertTrue(stylesheet.contains(".stock-page-loader"));
        assertTrue(stylesheet.contains("@keyframes stock-loader-path"));
        assertTrue(stylesheet.contains("@keyframes stock-loader-progress"));
        assertTrue(stylesheet.contains(".stock-page-loader.is-complete"));
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
    void congressionalActivityIsStockOnlyAndExplainsItsCacheAndHistoryWindow() throws IOException {
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));
        String dashboard = Files.readString(Path.of("src/main/resources/templates/home.html"));

        assertTrue(stock.contains("id=\"congressionalActivityPanel\""));
        assertTrue(stock.contains("panel.hidden = !congressionalActivityEligible"));
        assertTrue(stock.contains("instrumentType === 'EQUITY'"));
        assertTrue(stock.contains("/api/congressional-activity/${encodedTicker}/state"));
        assertTrue(stock.contains("/api/congressional-activity/${encodedTicker}/history"));
        assertTrue(stock.contains("/api/congressional-activity/${encodedTicker}/subscription"));
        assertTrue(stock.contains("Checking the database cache"));
        assertTrue(stock.contains("last 365 days"));
        assertTrue(stock.contains("never generates old email alerts"));
        assertTrue(stock.contains("rows.replaceChildren"));
        assertFalse(stock.contains("innerHTML"));

        assertTrue(dashboard.contains("th:each=\"activity : ${congressionalActivities}\""));
        assertTrue(dashboard.contains("th:each=\"stock : ${congressionalFollowedStocks}\""));
        assertTrue(dashboard.contains("CongressInvests"));
        assertTrue(dashboard.contains("up to 45 days"));
    }

    @Test
    void insiderActivityUsesDailyFiledTradeMonitoringAndCompletedCloseReturns() throws IOException {
        String stock = Files.readString(Path.of("src/main/resources/templates/stock.html"));
        String dashboard = Files.readString(Path.of("src/main/resources/templates/home.html"));

        assertTrue(stock.contains("id=\"insiderActivityPanel\""));
        assertTrue(stock.contains("id=\"insiderHistoryDialog\""));
        assertTrue(stock.contains("/api/insider-activity/${encodedTicker}/state"));
        assertTrue(stock.contains("/api/insider-activity/${encodedTicker}/history"));
        assertTrue(stock.contains("/api/insider-activity/${encodedTicker}/history/refresh"));
        assertTrue(stock.contains("/api/insider-activity/${encodedTicker}/subscription"));
        assertTrue(stock.indexOf(
                "fetch(`/api/insider-activity/${encodedTicker}/history`)")
                < stock.indexOf("/api/insider-activity/${encodedTicker}/history/refresh"));
        assertTrue(stock.contains("formatInsiderMoney"));
        assertTrue(stock.contains("trade.returnPercent"));
        assertTrue(stock.contains("Awards, gifts, option exercises, and zero-price grants are excluded"));
        assertFalse(stock.contains("innerHTML"));

        assertTrue(dashboard.contains("id=\"latestTickerNotifications\""));
        assertTrue(dashboard.contains("id=\"insiderActivityDashboard\""));
        assertTrue(dashboard.contains("th:each=\"notification : ${latestTickerNotifications}\""));
        assertTrue(dashboard.contains("th:each=\"activity : ${insiderActivities}\""));
        assertTrue(dashboard.contains("th:each=\"stock : ${insiderFollowedStocks}\""));
        assertTrue(dashboard.contains("API Ninjas"));
        assertTrue(stock.contains("Growing SEC insider archive"));
        assertTrue(stock.contains("Loading the stored insider archive"));
        assertTrue(stock.contains("API Ninjas free tier: SEC filing date fallback"));
        assertTrue(dashboard.contains("Directional return"));
    }

    @Test
    void dashboardSeparatesTechnicalAnalysisFromTickerAlerts() throws IOException {
        String dashboard = Files.readString(Path.of("src/main/resources/templates/home.html"));
        String dashboardScript = Files.readString(Path.of("src/main/resources/static/js/dashboard.js"));

        assertTrue(dashboard.contains("id=\"technicalAnalysisViewButton\""));
        assertTrue(dashboard.contains("id=\"tickerAlertsViewButton\""));
        assertTrue(dashboard.contains("data-dashboard-view-button=\"technical\""));
        assertTrue(dashboard.contains("data-dashboard-view-button=\"alerts\""));
        assertTrue(dashboard.contains("id=\"technicalDashboardMetrics\""));
        assertTrue(dashboard.contains("id=\"tickerAlertsIntroduction\""));
        assertTrue(dashboard.contains("id=\"latestTickerNotifications\""));
        assertTrue(dashboard.contains("id=\"congressionalActivityDashboard\""));
        assertTrue(dashboard.contains("id=\"insiderActivityDashboard\""));
        assertTrue(dashboard.contains("data-dashboard-view=\"technical\""));
        assertTrue(dashboard.contains("data-dashboard-view=\"alerts\""));
        assertTrue(dashboard.contains("Congressional disclosures and corporate insider filings"));

        int watchDesk = dashboard.indexOf("id=\"technicalWatchDesk\"");
        int latestSignals = dashboard.indexOf("id=\"latestTechnicalSignals\"");
        int tickerIntroduction = dashboard.indexOf("id=\"tickerAlertsIntroduction\"");
        int latestNotifications = dashboard.indexOf("id=\"latestTickerNotifications\"");
        int congressionalActivity = dashboard.indexOf("id=\"congressionalActivityDashboard\"");
        int insiderActivity = dashboard.indexOf("id=\"insiderActivityDashboard\"");
        assertTrue(watchDesk >= 0);
        assertTrue(latestSignals > watchDesk);
        assertTrue(tickerIntroduction > latestSignals);
        assertTrue(latestNotifications > tickerIntroduction);
        assertTrue(congressionalActivity > latestNotifications);
        assertTrue(insiderActivity > congressionalActivity);

        assertTrue(dashboardScript.contains("function initializeDashboardViews()"));
        assertTrue(dashboardScript.contains("activateView(\"technical\")"));
        assertTrue(dashboardScript.contains("section.hidden = section.dataset.dashboardView !== view"));
        assertTrue(dashboardScript.contains("view === \"alerts\""));
        assertTrue(dashboardScript.contains("button.setAttribute(\"aria-pressed\", String(selected))"));
        assertFalse(dashboardScript.contains("innerHTML"));
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
        assertTrue(dashboard.contains("<span>Status</span>"));
        assertTrue(dashboard.contains("class=\"latest-lifecycle\""));
        assertTrue(dashboard.contains("signal.lifecycle().label()"));
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
        assertTrue(history.contains("signal-event-lifecycle-label"));
        assertTrue(history.contains("event.lifecycle().label()"));
        assertTrue(history.contains("event.lifecycle().resolutionPeriodLabel()"));
        assertFalse(history.contains("class=\"signal-table\""));

        assertTrue(signalDetail.contains("signal.setupScore()"));
        assertTrue(signalDetail.contains("signal.signalPeriodLabel()"));
        assertTrue(signalDetail.contains("signal.researchHorizonLabel()"));
        assertTrue(signalDetail.contains("class=\"signal-lifecycle-timeline\""));
        assertTrue(signalDetail.contains("signal.lifecycle().resolutionPeriodLabel()"));
        assertTrue(signalDetail.contains("signal.lifecycle().updatedAt()"));
        assertTrue(signalDetail.contains("Alert recorded"));
        assertTrue(signalDetail.contains("Lifecycle processed"));
        assertTrue(signalDetail.contains("#numbers.sequence(1, 20)"));
        assertTrue(signalDetail.contains("signal.reasons()"));
        assertTrue(signalDetail.contains("reason.scoreLabel()"));
        assertTrue(signalDetail.contains("reason.details()"));
        assertTrue(signalDetail.contains("detail.text()"));
        assertTrue(signalDetail.contains("class=\"evidence-detail-list\""));
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
