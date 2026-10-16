package org.example.stockwatch247.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSecurityTest {
    /** Message keys referenced by templates (#{key}, #messages.msg('key'), pluralKey) and scripts (i18n.t('key')). */
    private static final Pattern MESSAGE_KEY = Pattern.compile(
            "#\\{([\\w.]+)|msg(?:OrNull)?\\('([\\w.]+)'|pluralKey\\('([\\w.]+)'|(?:i18n|StockWatchI18n)\\.(?:t|plural)\\([`'\"]([\\w.]+)"
                    // Any other quoted dotted name, such as either branch of i18n.t(cond ? 'a.b' : 'a.c').
                    + "|[`'\"]([A-Za-z]\\w*(?:\\.\\w+)+)[`'\"]");
    private static Properties englishMessages;

    /**
     * The source followed by the English text of every message key it uses, so checks for interface
     * wording keep working now that the wording lives in messages.properties.
     */
    private static String english(String source) throws IOException {
        if (englishMessages == null) {
            Properties loaded = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/messages.properties"), StandardCharsets.UTF_8)) {
                loaded.load(reader);
            }
            englishMessages = loaded;
        }
        StringBuilder text = new StringBuilder(source);
        Matcher matcher = MESSAGE_KEY.matcher(source);
        while (matcher.find()) {
            String key = null;
            for (int group = 1; group <= matcher.groupCount() && key == null; group++) key = matcher.group(group);
            for (String prefix : List.of("", "js.")) {
                String full = prefix + key;
                for (String name : englishMessages.stringPropertyNames()) {
                    // A key built at runtime ('family.' + value) covers every key with that prefix.
                    boolean matches = full.endsWith(".") ? name.startsWith(full)
                            : name.equals(full) || name.equals(full + ".one") || name.equals(full + ".other");
                    if (matches) text.append('\n').append(englishMessages.getProperty(name));
                }
            }
        }
        return text.toString();
    }

    private String stylesheets() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/css/style.css"));
    }


    @Test
    void stockPageExposesDeliveredHarmonicRulesHistoricalHoverCardAndConfirmedOverlay() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String historical = english(Files.readString(
                Path.of("src/main/resources/templates/historical-harmonic-detail.html")));

        assertTrue(stock.contains("id=\"harmonicOverlayToggle\""));
        assertTrue(stock.contains("Harmonic formations overlay"));
        assertTrue(stock.contains("data-alert-family=\"HARMONIC_FORMATION\""));
        assertTrue(stock.contains("function refreshHarmonicOverlays"));
        assertTrue(stock.contains("function renderHarmonicFormation"));
        assertTrue(stock.contains("loadPatternRange('harmonics', interval)"));
        assertTrue(stock.contains("addEventListener('click', toggleHarmonicOverlays)"));
        assertTrue(stock.contains("id=\"harmonicFormationHoverCard\""));
        assertTrue(stock.contains("id=\"harmonicFormationHitTargets\""));
        assertTrue(stock.contains("function showHarmonicFormationCard"));
        assertTrue(stock.contains("/harmonic-formations/${encodeURIComponent(currentInterval)}"));
        assertTrue(stock.contains("Confirmation requires candles after the final turning point"));
        assertTrue(historical.contains("Historical harmonic signal"));
        assertTrue(historical.contains("How the pattern is scored"));
        assertTrue(historical.contains("id=\"graphicalOutlookTab\""));
        assertTrue(historical.contains("id=\"scoreReportTab\""));
        assertTrue(historical.contains("id=\"resultsTab\""));
        assertTrue(historical.contains("class=\"signal-view-tabs signal-three-view-tabs\""));
        assertTrue(historical.contains("id=\"signalResultsWorkspace\""));
        assertTrue(historical.contains("~{signal-detail :: signalDetailBehavior}"));
        assertTrue(historical.contains("window.StockWatchChartTools?.attach("));
    }

    @Test
    void stockPageCreatesDemoTradesAndWatchlistRemovalExplainsMonitoringScope() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String dashboard = english(Files.readString(Path.of("src/main/resources/templates/home.html")));
        String dashboardScript = english(Files.readString(Path.of("src/main/resources/static/js/dashboard.js")));
        String stylesheet = stylesheets();

        assertTrue(stock.contains("data-stock-demo-trade=\"BUY\""));
        assertTrue(stock.contains("class=\"price-header-demo-trading\""));
        assertTrue(stock.indexOf("class=\"market-identity\"")
                < stock.indexOf("class=\"price-header-demo-trading\""));
        assertTrue(stock.indexOf("class=\"price-header-demo-trading\"")
                < stock.indexOf("class=\"quote-block\""));
        assertFalse(stock.contains("stock-workspace-action-card virtual-trade-launch-card"));
        assertTrue(stock.contains("id=\"stockDemoTradeDialog\""));
        assertTrue(stock.contains("`/api/virtual-trades/${encodedTicker}`"));
        assertTrue(stock.contains("method: 'POST'"));

        String watchlists = english(Files.readString(Path.of("src/main/resources/static/js/watchlists.js")));
        String picker = english(Files.readString(Path.of("src/main/resources/static/js/watchlist-picker.js")));
        assertTrue(dashboard.contains("data-named-watchlists"));
        String watchlistText = Files.readString(Path.of("src/main/resources/messages.properties"));
        assertTrue(watchlists.contains("watchlists.removeFromList") && watchlistText.contains("=Remove from this list"));
        assertTrue(watchlists.contains("watchlists.removeSymbolText") && watchlistText.contains("If this is its last list, its alerts stop"));
        assertTrue(watchlists.contains("watchlists.deleteText") && watchlistText.contains("Notification history is preserved"));
        assertTrue(watchlists.contains("method: 'DELETE'"));
        assertTrue(picker.contains("X-CSRF-TOKEN"));
        assertFalse(watchlists.contains("innerHTML"));
        assertTrue(stylesheet.contains("scrollbar-gutter: stable"));
        assertTrue(stylesheet.contains("html[data-theme='light'] .alert-list::-webkit-scrollbar-thumb"));
    }

    @Test
    void priceChartsShareThePersistentDrawingToolset() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String fragment = Files.readString(Path.of("src/main/resources/templates/fragments/chart-workbench.html"));
        String technicalOutlook = english(Files.readString(Path.of("src/main/resources/templates/technical-outlook.html")));
        String virtualTrade = english(Files.readString(Path.of("src/main/resources/templates/virtual-trade.html")));
        String intervals = english(Files.readString(Path.of("src/main/resources/static/js/detail-chart-intervals.js")));
        String core = Files.readString(Path.of("src/main/resources/static/js/chart-drawing-core.js"));
        String fibonacci = Files.readString(Path.of("src/main/resources/static/js/chart-drawing-tools-fib.js"));
        String measure = Files.readString(Path.of("src/main/resources/static/js/chart-drawing-tools-measure.js"));

        String stockTemplate = Files.readString(Path.of("src/main/resources/templates/stock.html"));
        assertTrue(stockTemplate.contains("~{fragments/chart-workbench :: drawingAssets}"));
        // Every other candlestick chart gets the stock page's toolset: the rail in its own column beside the
        // chart, drawings stored per symbol, alerts on drawings and demo trades from positions.
        for (String page : List.of("signal-detail", "historical-candlestick-detail", "historical-elliott-detail",
                "historical-harmonic-detail", "activity-signal-detail", "technical-outlook", "technical-outlook-change", "virtual-trade")) {
            String template = Files.readString(Path.of("src/main/resources/templates/" + page + ".html"));
            assertTrue(template.contains("~{fragments/chart-workbench :: pageAssets}"), page);
            assertTrue(template.contains("<div class=\"chart-tools-rail\" data-drawing-rail-host></div>"), page);
            // The candlestick history page draws its chart with signal-detail's script.
            assertTrue(template.contains("window.StockWatchChartTools?.attach(")
                    || template.contains("~{signal-detail :: signalDetailBehavior}"), page);
            assertFalse(template.contains("StockWatchFibonacciDrawingTool"), page);
            assertFalse(template.contains("fibonacci-drawing-tool.js"), page);
            if (!page.equals("technical-outlook")) {
                assertTrue(template.contains("~{fragments/chart-workbench :: demoTradeDialog}"), page);
            }
        }
        assertFalse(Files.exists(Path.of("src/main/resources/static/js/fibonacci-drawing-tool.js")));
        assertTrue(fragment.contains("@{/js/chart-drawing-core.js}"));
        assertTrue(fragment.contains("@{/js/chart-drawing-ui.js}"));
        assertTrue(fragment.contains("@{/js/chart-alerts.js}"));
        assertTrue(fragment.contains("@{/js/chart-page-tools.js}"));
        assertTrue(fragment.contains("id=\"chartDemoTradeDialog\""));

        // The stock page keeps drawings in the account (older per-interval browser drawings move over once).
        assertTrue(stock.contains("new StockWatchDrawing.DrawingManager"));
        assertTrue(stock.contains("new StockWatchDrawing.ServerDrawingStore(ticker, legacyKeys)"));
        assertTrue(stock.contains("key: `stock:${ticker}:${interval}`"));
        assertTrue(stock.contains("drawingManager?.setInterval(newInterval)"));
        assertTrue(stock.contains("toolbarHost: document.getElementById('drawingToolbarHost')"));
        assertTrue(stock.contains("manager.on('demo-trade'"));
        // Charts rebuilt for another interval take their toolset down with them.
        assertTrue(technicalOutlook.contains("priceDrawingTools?.destroy()"));
        assertTrue(technicalOutlook.contains("onDemoTrade: side =>"));
        assertTrue(technicalOutlook.contains("'ta4jTrend', 'volumeProfileKde'"));
        assertTrue(technicalOutlook.contains("researchOnly ? 'RESEARCH'"));
        assertTrue(virtualTrade.contains("priceDrawingTools?.destroy()"));
        assertTrue(intervals.contains("alternateDrawingTools?.destroy()"));
        assertTrue(intervals.contains("window.StockWatchChartTools?.attach("));

        for (String level : List.of("0.236", "0.382", "0.618", "0.786", "-0.618", "1.272", "1.618", "2.618")) {
            assertTrue(fibonacci.contains(level), level);
        }
        assertTrue(measure.contains("positionTool('longPosition', true)"));
        assertTrue(measure.contains("positionTool('shortPosition', false)"));
        assertTrue(core.contains("setPointerCapture"));
        assertTrue(core.contains("coordinateToLogical"));
        assertTrue(core.contains("coordinateToPrice"));
        for (String file : List.of("core", "kit", "ui", "icons", "tools-lines", "tools-fib", "tools-patterns", "tools-measure", "tools-shapes")) {
            assertFalse(Files.readString(Path.of("src/main/resources/static/js/chart-drawing-" + file + ".js")).contains("innerHTML"), file);
        }
        assertFalse(Files.readString(Path.of("src/main/resources/static/js/chart-indicators.js")).contains("innerHTML"));
        assertFalse(Files.readString(Path.of("src/main/resources/static/js/chart-page-tools.js")).contains("innerHTML"));
    }

    @Test
    void harmonicSignalDetailKeepsTheStructuralStopAndShowsATargetOnlyWhenOneWasSaved() throws IOException {
        String signal = english(Files.readString(Path.of("src/main/resources/templates/signal-detail.html")));

        assertTrue(signal.contains("Entry, targets, stop, and trade horizon"));
        assertTrue(signal.contains("Exact structural invalidation"));
        assertTrue(signal.contains("Structural formula"));
        assertTrue(signal.contains("Saved price buffer"));
        assertTrue(signal.contains("Protective stop trigger"));
        assertTrue(signal.contains("data-trade-structural-stop"));
        assertTrue(signal.contains("<article th:if=\"${signal.lifecycle().profitTargetPrice() != null}\" class=\"reward\">"));
        assertTrue(signal.contains("Legacy harmonic plans retain their eight-bar stop-only rules."));
        assertFalse(signal.contains("harmonicStopBasis()} ?"));
    }

    @Test
    void virtualTradeSurfacesExposeConfirmedCspSafeDeletion() throws IOException {
        String archive = english(Files.readString(Path.of("src/main/resources/templates/virtual-trades.html")));
        String outlook = english(Files.readString(Path.of("src/main/resources/templates/technical-outlook.html")));
        String deletionScript = english(Files.readString(
                Path.of("src/main/resources/static/js/virtual-trade-deletion.js")));

        assertTrue(archive.contains("/virtual-trades/{id}/delete"));
        assertTrue(archive.contains("data-virtual-trade-delete-dialog"));
        assertTrue(archive.contains("@{/js/virtual-trade-deletion.js}"));
        assertTrue(outlook.contains("/api/virtual-trades/${encodeURIComponent(tradeId)}/delete"));
        assertTrue(outlook.contains("id=\"deleteVirtualTradeDialog\""));
        assertTrue(outlook.contains("company-virtual-trade-delete"));
        assertFalse(outlook.contains("innerHTML"));
        assertFalse(deletionScript.contains("innerHTML"));
    }

    @Test
    void settingsExposeStrictPerFamilyAndIntervalScoringProfiles() throws IOException {
        String settings = english(Files.readString(Path.of("src/main/resources/templates/settings.html")));
        String scoring = english(Files.readString(
                Path.of("src/main/resources/templates/fragments/scoring-settings.html")));
        String script = english(Files.readString(
                Path.of("src/main/resources/static/js/scoring-settings.js")));

        assertTrue(settings.contains("@{/settings/scoring}"));
        assertTrue(settings.contains("settingsTab == 'scoring'"));
        assertTrue(scoring.contains("Included point total"));
        assertTrue(scoring.contains("Harmonic Formation"));
        assertTrue(scoring.contains("Each scoring profile must total 100 points"));
        assertTrue(scoring.contains("Support from other pattern families"));
        assertTrue(scoring.contains(".supportingPoints"));
        assertTrue(scoring.contains(".opposingPoints"));
        assertTrue(scoring.contains("scoring-confluence-include"));
        assertTrue(scoring.contains("@{/settings/scoring/reset}"));
        assertTrue(script.contains("total === 100"));
        assertTrue(script.contains("apply.disabled = !valid"));
        assertFalse(scoring.contains("innerHTML"));
        assertFalse(script.contains("innerHTML"));
    }

    @Test
    void settingsExposeHarmonicAppearanceEmailScoringAndRuleDefinitions() throws IOException {
        String settings = english(Files.readString(Path.of("src/main/resources/templates/settings.html")));
        String analysis = english(Files.readString(Path.of("src/main/resources/templates/fragments/analysis-settings.html")));
        String harmonic = english(Files.readString(Path.of("src/main/resources/templates/fragments/harmonic-pattern-settings.html")));
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));

        assertTrue(settings.contains("name=\"harmonicFormationColor\""));
        assertTrue(settings.contains("@{/settings/harmonic-formations}"));
        assertTrue(settings.contains("settingsTab == 'harmonic-formations'"));
        assertTrue(analysis.contains("name=\"email.newHarmonic\""));
        assertTrue(harmonic.contains("Extra soft-rule allowance"));
        assertTrue(harmonic.contains("Set turning-point and Fibonacci rules"));
        assertTrue(harmonic.contains("@{/settings/harmonic-formations/reset}"));
        assertTrue(stock.contains("HARMONIC_FORMATION_COLOR"));
        assertFalse(harmonic.contains("innerHTML"));
    }

    @Test
    void settingsExposeIntervalSpecificCandlestickDetectionRulesAndScopedReset() throws IOException {
        String settings = english(Files.readString(Path.of("src/main/resources/templates/settings.html")));
        String detection = english(Files.readString(
                Path.of("src/main/resources/templates/fragments/detection-settings.html")));

        assertTrue(settings.contains("@{/settings/detection}"));
        assertTrue(settings.contains("settingsTab == 'detection'"));
        assertTrue(settings.contains("detection-reset-trigger"));
        assertTrue(detection.contains("trendMinimumMovePercent"));
        assertTrue(detection.contains("trendMinimumCandles"));
        assertTrue(detection.contains("trendLookbackCandles"));
        assertTrue(detection.contains("@{/settings/detection/reset}"));
        assertFalse(detection.contains("innerHTML"));
    }

    @Test
    void settingsExplainEveryCustomCandlestickDefinitionAndAvoidUnsafeRendering() throws IOException {
        String settings = english(Files.readString(Path.of("src/main/resources/templates/settings.html")));
        String definitions = english(Files.readString(
                Path.of("src/main/resources/templates/fragments/candlestick-pattern-settings.html")));
        String script = english(Files.readString(
                Path.of("src/main/resources/static/js/candlestick-pattern-settings.js")));

        assertTrue(settings.contains("@{/settings/candlestick-patterns}"));
        assertTrue(settings.contains("settingsTab == 'candlestick-patterns'"));
        assertTrue(definitions.contains("Rules that stay fixed"));
        assertTrue(definitions.contains("Default:"));
        assertTrue(definitions.contains("setting.description"));
        assertTrue(definitions.contains("setting.effect"));
        assertTrue(definitions.contains("Set one reward multiple for all intervals."));
        assertTrue(definitions.contains("rewardRisk."));
        assertTrue(definitions.contains("Stop-loss rule"));
        assertTrue(definitions.contains(".stopLossMode"));
        assertTrue(definitions.contains(".stopLossValuePercent"));
        assertTrue(definitions.contains("Structural risk checks"));
        assertTrue(definitions.contains("circuitBreaker."));
        assertTrue(definitions.contains(".atrPeriod"));
        assertTrue(definitions.contains(".atrMultiplier"));
        assertTrue(definitions.contains(".activationThresholdPercent"));
        assertTrue(script.contains("FIXED_ENTRY_PERCENT"));
        assertTrue(definitions.contains("@{/settings/candlestick-patterns/reset}"));
        assertFalse(script.contains("innerHTML"));
    }

    @Test
    void technicalOutlookShowsAndEditsTheSavedIndicatorPeriods() throws IOException {
        String outlook = english(Files.readString(
                Path.of("src/main/resources/templates/technical-outlook.html")));
        String controller = Files.readString(
                Path.of("src/main/java/org/example/stockwatch247/controller/TechnicalOutlookController.java"));
        String service = Files.readString(
                Path.of("src/main/java/org/example/stockwatch247/service/TechnicalOutlookService.java"));

        assertTrue(outlook.contains("id=\"editIndicatorPeriods\""));
        assertTrue(outlook.contains("id=\"indicatorPeriodDialog\""));
        assertTrue(outlook.contains("data-period-field=\"rsiPeriod\""));
        assertTrue(outlook.contains("data-period-field=\"fastEmaPeriod\""));
        assertTrue(outlook.contains("data-period-field=\"supportResistancePeriod\""));
        assertTrue(outlook.contains("renderIndicatorPeriodLabels(outlook)"));
        assertTrue(outlook.contains("rollingLevels(candles, mode, period)"));
        assertTrue(outlook.contains("settings.supportResistancePeriod"));
        assertTrue(outlook.contains("technical-outlook/indicator-periods"));
        assertTrue(outlook.contains("id=\"technicalOutlookPageLoader\""));
        assertTrue(outlook.contains("class=\"stock-page-loader\""));
        assertTrue(outlook.contains("requestController = new AbortController()"));
        assertTrue(outlook.contains("}, 50000)"));
        assertTrue(outlook.contains("technical-outlook/score-report"));
        assertTrue(outlook.contains("technical-outlook/market-comparison"));
        assertTrue(outlook.contains("technical-outlook/refresh-status"));
        assertTrue(outlook.contains("technical-outlook/history?interval="));
        assertTrue(outlook.contains("priceCandleSeries.barsInLogicalRange(range)"));
        assertTrue(outlook.contains("barsBefore < HISTORY_PREFETCH_THRESHOLD"));
        assertTrue(outlook.contains("visibleRange.from + added"));
        assertTrue(outlook.contains("priceHistoryByInterval"));
        assertTrue(outlook.contains("id=\"technicalOutlookHistoryStatus\""));
        assertTrue(outlook.contains("ensureTabRendered(activeTab)"));
        assertTrue(outlook.contains("loadCompanyTrades(generation, controller.signal)"));
        assertTrue(outlook.contains("function recommendationDisplayLabel(value)"));
        assertTrue(outlook.contains("Sell/Short"));
        assertTrue(outlook.contains("recommendationDisplayLabel(signal.direction)"));
        assertFalse(outlook.contains("innerHTML"));
        assertTrue(controller.contains("updateIndicatorPeriods"));
        assertTrue(controller.contains("requestBackgroundRefresh"));
        assertTrue(controller.contains("historicalChartPage"));
        assertTrue(service.contains("String rsiLabel = \"RSI \" + rules.rsiPeriod()"));
        assertTrue(service.contains("getHistoricalChartPage"));
        assertTrue(service.contains("String emaPairLabel = fastEmaLabel + \" / \" + slowEmaLabel"));
        assertTrue(service.contains("IndicatorSettingsView.from(rules)"));
        assertFalse(service.contains("refresh(normalizedSymbol, interval.apiValue())"));
    }

    @Test
    void authenticatedPageBackNavigationUsesOneReusableHistoryControl() throws IOException {
        String fragment = english(Files.readString(
                Path.of("src/main/resources/templates/fragments/history-back.html")));
        String behavior = english(Files.readString(
                Path.of("src/main/resources/static/js/history-back.js")));
        String stylesheet = stylesheets();
        String navbar = english(Files.readString(
                Path.of("src/main/resources/templates/fragments/navbar.html")));
        String dashboard = english(Files.readString(
                Path.of("src/main/resources/templates/home.html")));

        assertTrue(fragment.contains("th:fragment=\"historyBack(fallbackUrl)\""));
        assertTrue(fragment.contains("data-history-back"));
        assertTrue(fragment.contains("@{/js/history-back.js}"));
        assertFalse(fragment.matches("(?s).*\\son(?:click|load|error)=.*"));
        assertTrue(behavior.contains("window.history.back()"));
        assertTrue(behavior.contains("window.location.assign(fallbackUrl)"));
        assertTrue(behavior.contains("window.requestStockWatchHistoryBack = navigateBack"));
        assertTrue(behavior.contains("stockwatch:page-state:"));
        assertTrue(behavior.contains("window.addEventListener('pagehide', capturePageState)"));
        assertTrue(behavior.contains("window.addEventListener('pageshow', restorePageState)"));
        assertTrue(behavior.contains("event.persisted"));
        assertFalse(behavior.contains("innerHTML"));
        assertTrue(stylesheet.contains(".history-back-button"));
        assertTrue(stylesheet.contains(".history-back-disabled .history-back-button"));
        assertTrue(navbar.contains("firstName != null"));
        assertTrue(navbar.contains("fragments/history-back :: historyBack"));
        assertTrue(dashboard.contains("<body class=\"history-back-disabled\">"));

        for (String template : List.of(
                "all-signals.html", "all-activity-signals.html", "alert-history.html",
                "virtual-trades.html", "signal-detail.html", "activity-signal-detail.html",
                "technical-outlook.html", "historical-candlestick-detail.html",
                "historical-elliott-detail.html", "settings.html", "stock.html",
                "virtual-trade.html")) {
            String source = Files.readString(Path.of("src/main/resources/templates", template));
            assertTrue(source.contains("fragments/navbar :: navbar"), template);
        }

        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        assertTrue(stock.contains("setAlertBeforeUnloadGuard(!pageExitAllowed && changedCount > 0)"));
        assertTrue(stock.contains("window.removeEventListener('beforeunload', handleUnsavedAlertBeforeUnload)"));
        assertFalse(stock.contains("window.addEventListener('beforeunload', event =>"));
    }

    @Test
    void templatesDoNotExposeApiIntervalCodesAsVisibleText() throws IOException {
        Path templateRoot = Path.of("src/main/resources/templates");
        java.util.regex.Pattern visibleIntervalCode = java.util.regex.Pattern.compile(
                "(?is)>[^<]*(?<![a-z0-9])(1d|1wk|1mo)(?![a-z0-9])[^<]*<");

        try (java.util.stream.Stream<Path> templates = Files.walk(templateRoot)) {
            for (Path template : templates.filter(path -> path.toString().endsWith(".html")).toList()) {
                String source = Files.readString(template)
                        .replaceAll("(?is)<!--.*?-->", "")
                        .replaceAll("(?is)<script\\b.*?</script>", "")
                        .replaceAll("(?is)<style\\b.*?</style>", "");
                assertFalse(visibleIntervalCode.matcher(source).find(),
                        () -> template + " contains a visible API interval code");
            }
        }

        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String detailIntervals = english(Files.readString(
                Path.of("src/main/resources/static/js/detail-chart-intervals.js")));
        assertTrue(stock.contains("i18n.t(`sw.candles.loading.${apiIntervalEnum(interval)}`)"));
        assertTrue(detailIntervals.contains("intervalLabel.textContent = intervalName(interval).toLowerCase()"));
        assertFalse(detailIntervals.contains("(native ? nativeLabel : interval)"));
    }

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
                "all-signals.html",
                "all-activity-signals.html",
                "activity-signal-detail.html",
                "technical-outlook.html",
                "virtual-trades.html",
                "virtual-trade.html",
                "signal-detail.html",
                "historical-candlestick-detail.html",
                "historical-elliott-detail.html");
        templates = new java.util.ArrayList<>(templates);
        templates.addAll(List.of("settings.html", "login-2fa.html", "forgot-password.html", "reset-password.html",
                "cancel-account-deletion.html"));
        for (String template : templates) {
            String source = Files.readString(
                    Path.of("src/main/resources/templates", template));
            assertTrue(source.contains("th:src=\"@{/js/theme.js}\""), template);
            assertTrue(source.indexOf("/js/theme.js") < source.indexOf("/css/style.css"), template);
        }

        String theme = english(Files.readString(
                Path.of("src/main/resources/static/js/theme.js")));
        String stylesheet = stylesheets();
        assertTrue(theme.contains("stockwatch-theme"));
        assertTrue(theme.contains("return 'dark'"));
        assertTrue(theme.contains("createSvgIcon('sun')"));
        assertTrue(theme.contains("createSvgIcon('moon')"));
        assertTrue(theme.contains("theme.toLight"));
        assertTrue(theme.contains("theme.toDark"));
        String englishMessages = Files.readString(Path.of("src/main/resources/messages.properties"));
        assertTrue(englishMessages.contains("js.theme.toLight=Switch to light mode"));
        assertTrue(englishMessages.contains("js.theme.toDark=Switch to dark mode"));
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
        // The animated backdrop was always hidden by design.css; its markup and styles are gone.
        assertFalse(stylesheet.contains("bg-animation"));
        assertTrue(stylesheet.contains("@media (prefers-reduced-motion: reduce)"));
        assertTrue(theme.contains("persistAccountTheme"));
        assertFalse(theme.contains("innerHTML"));
    }

    @Test
    void templatesAvoidKnownDomXssAndThirdPartyLeakageSinks() throws IOException {
        String navbar = english(Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html")));
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String historicalCandlestickDetail = english(Files.readString(
                Path.of("src/main/resources/templates/historical-candlestick-detail.html")));
        String historicalElliottDetail = english(Files.readString(
                Path.of("src/main/resources/templates/historical-elliott-detail.html")));
        String about = english(Files.readString(Path.of("src/main/resources/templates/about.html")));

        assertFalse(navbar.contains("innerHTML"));
        assertFalse(navbar.matches("(?s).*\\son(?:click|load|error)=.*"));
        assertFalse(navbar.contains("style="));
        assertFalse(navbar.contains(".style."));
        assertTrue(navbar.contains("textContent"));
        assertTrue(navbar.contains("encodeURIComponent"));
        assertFalse(stock.contains("unpkg.com"));
        assertFalse(stock.contains("financialmodelingprep.com"));
        assertTrue(stock.contains("@{/js/vendor/lightweight-charts/5.2.1/lightweight-charts.standalone.production.js}"));
        assertFalse(stock.contains("/webjars/lightweight-charts/"));
        assertFalse(stock.contains("<style>"));
        assertTrue(stock.contains("<style th:attr=\"nonce=${cspNonce}\">"));
        assertFalse(stock.contains("style="));
        assertFalse(historicalCandlestickDetail.contains("th:utext"));
        assertFalse(historicalCandlestickDetail.contains("style="));
        assertFalse(historicalCandlestickDetail.contains("<style>"));
        assertFalse(historicalCandlestickDetail.matches("(?s).*\\son(?:click|load|error)=.*"));
        assertFalse(historicalElliottDetail.contains("th:utext"));
        assertFalse(historicalElliottDetail.contains("style="));
        assertFalse(historicalElliottDetail.matches("(?s).*\\son(?:click|load|error)=.*"));
        assertTrue(historicalElliottDetail.contains("id=\"historicalElliottDetailChart\""));
        assertTrue(historicalElliottDetail.contains("id=\"graphicalOutlookTab\""));
        assertTrue(historicalElliottDetail.contains("id=\"scoreReportTab\""));
        assertTrue(historicalElliottDetail.contains("id=\"resultsTab\""));
        assertTrue(historicalElliottDetail.contains("id=\"graphicalOutlookPanel\""));
        assertTrue(historicalElliottDetail.contains("id=\"scoreReportPanel\""));
        assertTrue(historicalElliottDetail.contains("id=\"resultsPanel\""));
        assertTrue(historicalElliottDetail.contains("id=\"signalResultsChart\""));
        assertTrue(historicalElliottDetail.contains("id=\"resultWindowSlider\""));
        assertTrue(historicalElliottDetail.contains("signal-detail :: signalDetailBehavior"));
        assertTrue(historicalElliottDetail.contains("Historical reconstruction")
                || historicalElliottDetail.contains("Historical Elliott Wave signal"));
        assertTrue(historicalCandlestickDetail.contains("id=\"graphicalOutlookTab\""));
        assertTrue(historicalCandlestickDetail.contains("id=\"scoreReportTab\""));
        assertTrue(historicalCandlestickDetail.contains("id=\"resultsTab\""));
        assertTrue(historicalCandlestickDetail.contains("id=\"resultsPanel\""));
        assertTrue(historicalCandlestickDetail.contains("id=\"signalResultsChart\""));
        assertTrue(historicalCandlestickDetail.contains("id=\"signalResultsData\""));
        assertTrue(historicalCandlestickDetail.contains("id=\"resultWindowSlider\""));
        assertTrue(historicalCandlestickDetail.contains("id=\"signalResultsUnavailableDialog\""));
        assertTrue(historicalCandlestickDetail.contains("results.minimumForwardCandles()"));
        assertTrue(historicalCandlestickDetail.contains("Results chart color legend"));
        assertTrue(historicalCandlestickDetail.contains("id=\"signalChart\""));
        assertTrue(historicalCandlestickDetail.contains("chart.candles()"));
        assertTrue(historicalCandlestickDetail.contains("signalDetailBehavior"));
        assertTrue(historicalCandlestickDetail.contains("Detection and trade execution"));
        assertTrue(historicalCandlestickDetail.contains("signal.lifecycle().profitTargetPrice()"));
        assertTrue(historicalCandlestickDetail.contains("Most favorable completed-close move"));
        assertTrue(historicalCandlestickDetail.contains("data-trade-time-stop-candles"));
        assertFalse(about.contains("th:utext"));
        assertFalse(about.contains("style="));
        assertFalse(about.contains("<style>"));
        assertFalse(about.matches("(?s).*\\son(?:click|load|error)=.*"));
    }

    @Test
    void signupAndAppearanceSettingsExposeSeparateElliottColorChoices() throws IOException {
        String signup = english(Files.readString(Path.of("src/main/resources/templates/signup.html")));
        String settings = english(Files.readString(Path.of("src/main/resources/templates/settings.html")));

        assertTrue(signup.contains("name=\"elliottMotiveColor\""));
        assertTrue(signup.contains("name=\"elliottCorrectiveColor\""));
        assertTrue(signup.contains("value=\"#3B82F6\""));
        assertTrue(signup.contains("value=\"#A855F7\""));
        assertTrue(settings.contains("@{/settings/appearance}"));
        assertTrue(settings.contains("settingsTab == 'appearance'"));
        assertTrue(settings.contains("name=\"elliottSubwaveColor\""));
        assertTrue(settings.contains("Hovered subwaves"));
        assertTrue(settings.contains("Motive I–V"));
        assertTrue(settings.contains("Corrective A–B–C"));
        assertTrue(settings.contains("Apply changes"));
    }

    @Test
    void aboutPageDocumentsTheImplementedDetectionAndResearchRules() throws IOException {
        String index = english(Files.readString(Path.of("src/main/resources/templates/index.html")));
        String about = english(Files.readString(Path.of("src/main/resources/templates/about.html")));

        assertTrue(index.contains("th:href=\"@{/about}\""));
        assertTrue(about.contains("id=\"candlesticks\""));
        assertTrue(about.contains("id=\"scoring\""));
        assertTrue(about.contains("id=\"elliott\""));
        assertTrue(about.contains("id=\"history\""));
        assertTrue(about.contains("A pattern must meet its shape and prior-trend rules"));
        assertTrue(about.contains("ELLIOTT_V1"));
        assertTrue(about.contains("Pattern quality"));
        assertTrue(about.contains("Support or resistance"));
        assertTrue(about.contains("At least 34 completed candles"));
        assertTrue(about.contains("1–750 candles"));
        assertTrue(about.contains("If the ticker has less history"));
        assertTrue(about.contains("@{/images/about/analysis-hero.webp}"));
        assertTrue(about.contains("@{/images/about/market-workspace.webp}"));
    }

    @Test
    void stockTemplateSeparatesNativeElliottStructuresFromSubwavesAndSupportsDailyAlerts() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String outlook = english(Files.readString(Path.of("src/main/resources/templates/technical-outlook.html")));
        String styles = stylesheets();

        assertTrue(stock.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"DAILY\" data-alert-signal=\"BUY\""));
        assertTrue(stock.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"DAILY\" data-alert-signal=\"SELL\""));
        assertTrue(stock.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"MONTHLY\" data-alert-signal=\"BUY\""));
        assertTrue(stock.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"MONTHLY\" data-alert-signal=\"SELL\""));
        assertTrue(stock.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"WEEKLY\" data-alert-signal=\"BUY\""));
        assertTrue(stock.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"WEEKLY\" data-alert-signal=\"SELL\""));
        assertTrue(stock.contains("function tradeSignalDisplayLabel(value, lowercase = false)"));
        assertTrue(stock.contains("Sell/Short</span></div>"));
        assertTrue(stock.contains("copy.textContent = i18n.t('sw.sellShortWord')"));
        assertTrue(stock.contains("class=\"alert-eye-input\" type=\"checkbox\""));
        assertTrue(stock.contains("class=\"alert-panel alert-star-panel\""));
        assertTrue(stock.contains("Switch on the signals you want to follow, then click Apply changes."));
        assertTrue(styles.contains(".alert-star-panel .alert-eye-graphic::before"));
        assertTrue(styles.contains(".alert-star-panel .alert-eye-input:checked + .alert-eye-graphic::before"));
        assertTrue(styles.contains("transform: translateX(16px)"));
        assertTrue(styles.contains(".alert-star-panel .alert-eye-input:checked + .alert-eye-graphic .alert-eye-filled"));
        assertFalse(stock.contains("check-alert-btn"));
        assertFalse(stock.contains("data-check-family"));
        assertFalse(stock.contains("checkLatestSignal("));
        assertTrue(stock.contains("refreshHistoricalElliottOverlays"));
        assertTrue(stock.contains("series.setMarkers(points"));
        assertTrue(stock.contains("ELLIOTT_MOTIVE_COLOR"));
        assertTrue(stock.contains("ELLIOTT_CORRECTIVE_COLOR"));
        assertTrue(stock.contains("ELLIOTT_SUBWAVE_COLOR"));
        assertTrue(stock.contains("StockWatchElliottHierarchyOverlay"));
        assertTrue(stock.contains("@{/js/elliott-wave-hierarchy.js}"));
        assertTrue(stock.contains("structure.points.slice(0, 6)"));
        assertTrue(stock.contains("structure.points.slice(5)"));
        assertTrue(stock.contains("isCorrectiveElliottPoint"));
        assertTrue(stock.contains("id=\"elliottOverlayToggle\""));
        // Indicators come from the server catalog through the indicator manager, one pane per panel indicator.
        assertTrue(stock.contains("id=\"openIndicatorBrowser\""));
        assertTrue(stock.contains("@{/js/chart-indicators.js}"));
        assertTrue(stock.contains("new StockWatchIndicatorManager({"));
        assertFalse(stock.contains("data-add-chart-indicator="));
        assertFalse(stock.contains("id=\"dynamicIndicatorPanels\""));
        assertTrue(stock.contains("id=\"elliottSubwaveToggle\""));
        assertTrue(stock.contains("Independent Elliott structures"));
        assertTrue(stock.contains("Fractal subwaves"));
        assertTrue(stock.contains("let elliottSubwavesEnabled = false"));
        assertTrue(stock.contains("const historicalDetailUrl = endpoint?.timestamp"));
        assertFalse(stock.contains("currentInterval !== '1d' && endpoint?.timestamp"));
        assertTrue(stock.contains("tracked ? 'sw.elliott.openSignal' : 'sw.elliott.openHistorical'"));
        assertFalse(stock.contains("id=\"historicElliottBtn\""));
        assertTrue(stock.contains("loadPatternRange('waves', interval)"));
        assertTrue(stock.contains("loadPatternRange('candidates', interval)"));
        assertTrue(stock.contains("function visiblePatternRange(paddingCandles = 20)"));
        assertTrue(stock.contains("structure.structureId || elliottStructureFallbackId(structure)"));
        assertTrue(stock.contains("id=\"elliottWaveHoverCard\""));
        assertTrue(stock.contains("/elliott-cards?interval="));
        assertTrue(stock.contains("function nearestElliottSegment(point)"));
        assertTrue(stock.contains("function rebuildElliottHitTargets()"));
        assertTrue(stock.contains("function elliottInteractivePoints(segment)"));
        assertTrue(stock.contains("function historicalElliottCard(segment)"));
        assertTrue(stock.contains("Historical results from the first 10 completed candles."));
        assertTrue(stock.contains("return segment.points;"));
        assertTrue(stock.contains("lineWidth: item.groupId === segment?.groupId ? item.activeLineWidth : item.lineWidth"));
        assertTrue(stock.contains("Open signal details"));
        assertTrue(stock.contains("status.classList.add('error')"));
        assertTrue(stock.contains("setElliottConfirmationMarkers"));
        assertTrue(stock.contains("structure.confirmationTimestamp"));
        assertTrue(stock.contains("color: 'rgba(0, 0, 0, 0)'"));
        assertFalse(stock.contains("lineVisible: false"));
        assertTrue(stock.contains("elliottQualityWarnings(structure)"));
        assertTrue(stock.contains("i18n.t('sw.elliott.qualityRange', qualityMin, qualityMax)"));
        assertTrue(stock.contains("Quality range {0}–{1}/100"));
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
        assertFalse(stock.contains("id=\"showHistoricalCandlestickPatternsBtn\""));
        assertFalse(stock.contains("Pattern research"));
        assertTrue(stock.contains("id=\"candlestickOverlayToggle\""));
        assertTrue(stock.contains("aria-controls=\"historicalCandlestickLookbackDialog\""));
        assertTrue(stock.indexOf("id=\"candlestickOverlayToggle\"")
                < stock.indexOf("id=\"priceChartContainer\""));
        assertTrue(stock.indexOf("id=\"generalWorkspacePanel\"")
                < stock.indexOf("id=\"candlestickOverlayToggle\""));
        assertTrue(stock.indexOf("id=\"candlestickOverlayToggle\"")
                < stock.indexOf("id=\"technicalAnalysisWorkspacePanel\""));
        assertTrue(stock.contains(".addEventListener('click', showHistoricalCandlestickViewPicker)"));
        assertTrue(stock.contains("id=\"historicalCandlestickLookbackDialog\""));
        assertTrue(stock.contains("value=\"graphical\""));
        assertTrue(stock.contains("value=\"list\""));
        assertTrue(stock.contains("id=\"historicalScanInterval\""));
        assertTrue(stock.contains("id=\"historicalCandlestickLookbackDialog\""));
        assertTrue(stock.contains("id=\"historicalCandlestickLookbackInput\""));
        assertTrue(stock.contains("id=\"historicalCandlestickLookbackDate\""));
        assertTrue(stock.contains("id=\"historicalCandlestickResultsDialog\""));
        assertTrue(stock.contains("value=\"1d\""));
        assertTrue(stock.contains("value=\"1wk\""));
        assertTrue(stock.contains("value=\"1mo\""));
        assertTrue(stock.contains("/candlestick-patterns/history?interval="));
        assertTrue(stock.contains("loadPatternRange('candles', interval)"));
        assertTrue(stock.contains("${baseUrl}?fullHistory=true"));
        assertTrue(stock.contains("&lookbackCandles=${encodeURIComponent(lookbackCandles)}"));
        assertTrue(stock.contains("cache: 'no-store'"));
        assertTrue(stock.contains("createHistoricalCandlestickRow"));
        assertTrue(stock.contains("id=\"historicalCandlestickHitTargets\""));
        assertTrue(stock.contains("id=\"historicalCandlestickHoverCard\""));
        assertTrue(stock.contains("function rebuildHistoricalCandlestickHitTargets()"));
        assertTrue(stock.contains("function positionHistoricalCandlestickHitTargets()"));
        assertTrue(stock.contains("function highlightHistoricalCandlestickSignal(signal)"));
        assertTrue(stock.contains("signal.trendStartTimestamp"));
        assertTrue(stock.contains("historicalCandlestickDetailUrl(signal, null, true)"));
        assertTrue(stock.contains("activeHistoricalCandlestickSignals.length"));
        assertTrue(stock.contains("function historicalCandlestickCardOutcome(signal)"));
        assertTrue(stock.contains("Return at sell target"));
        assertTrue(stock.contains("Stop loss reached"));
        assertTrue(stock.contains("i18n.t('sw.outcome.timeStopReturn', timeStopCandles)"));
        assertTrue(stock.contains("Return at candle {0} time stop"));
        assertTrue(stock.contains("Potential return at sell target"));
        assertTrue(stock.contains("function historicalCandlestickDirectionalReturn(signal, exitPrice)"));
        assertTrue(stock.contains("reopenHistoricalCandlestickResultsFromUrl"));

        String navbar = english(Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html")));
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
        assertTrue(stock.contains("priceSeries.barsInLogicalRange(range)"));
        assertTrue(stock.contains("barsBefore < HISTORY_PREFETCH_THRESHOLD"));
        assertTrue(stock.contains("pageMayHaveMore(page, data, INITIAL_CANDLE_LIMIT)"));
        assertTrue(stock.contains("visibleLogicalRange.from + newCandles.length"));
        assertTrue(stock.contains("scheduleViewportHistoryFill()"));
        assertTrue(stock.contains("priceSeries.setData(StockWatchChartTypes.data(seriesType, globalCandleData, priceColors()))"));
        assertTrue(stock.contains("id=\"anchoredVolumeProfileToggle\""));
        assertTrue(stock.contains("handleAnchoredVolumeProfileChartClick"));
        assertTrue(stock.contains("/anchored-volume-profile"));
        assertTrue(stock.contains("id=\"anchoredVolumeProfileSummary\""));
        assertTrue(stock.contains("applyAnchoredVolumeProfileChartSpacing"));
        assertTrue(stock.contains("ANCHORED_PROFILE_RIGHT_OFFSET_BARS"));
        assertTrue(stock.contains("formatProfileCalculationInterval"));
        // RSI is one of the catalog indicators now; the separate RSI chart and dialog are gone.
        assertFalse(stock.contains("rsiOverlayToggle"));
        assertFalse(stock.contains("rsiPeriodDialog"));
        assertFalse(stock.contains("calculateWilderRsi"));
        String catalogRsi = Files.readString(Path.of("src/main/java/org/example/stockwatch247/service/chartindicator/MomentumIndicators.java"));
        assertTrue(catalogRsi.contains("define(\"RSI\""));
        assertFalse(stock.contains("anchoredVolumeProfileRail"));
        assertFalse(stock.contains("setVisibleRange(visibleTimeRange)"));
        assertFalse(stock.contains("timeScale().fitContent()"));
        int loadChartData = stock.indexOf("async function loadChartData(interval)");
        int paginationReady = stock.indexOf("isFetching = false;", loadChartData);
        int initialOverlayRefresh = stock.indexOf(
                "refreshHistoricalElliottOverlays(interval)", loadChartData);
        assertTrue(paginationReady > loadChartData);
        assertTrue(paginationReady < initialOverlayRefresh);

        int candlestickStart = stock.indexOf("#{stock.candleHeading}");
        int elliottStart = stock.indexOf("#{stock.elliottHeading}");
        int elliottEnd = stock.indexOf("<section id=\"congressionalActivityPanel\"", elliottStart);
        String candlestickSection = stock.substring(candlestickStart, elliottStart);
        String elliottSection = stock.substring(elliottStart, elliottEnd);
        assertFalse(candlestickSection.contains("data-alert-family=\"ELLIOTT_WAVE\""));
        assertTrue(elliottSection.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"DAILY\""));
        assertTrue(elliottSection.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"WEEKLY\""));
        assertTrue(elliottSection.contains("data-alert-family=\"ELLIOTT_WAVE\" data-alert-interval=\"MONTHLY\""));
        assertTrue(outlook.contains("Independent Elliott structures"));
        assertTrue(outlook.contains("elliott-waves/history?interval="));
        assertFalse(outlook.contains("currentInterval === '1d' || !outlook.candles.length"));
        assertFalse(outlook.contains("elliott-waves/hierarchy"));
    }

    @Test
    void elliottHierarchyRendersByIntervalWithoutHoverDrivenRequests() throws IOException {
        String overlay = Files.readString(Path.of(
                "src/main/resources/static/js/elliott-wave-hierarchy.js"));

        assertTrue(overlay.contains("/elliott-waves/hierarchy"));
        assertTrue(overlay.contains("groupsAtTimeframe"));
        assertTrue(overlay.contains("addLineSeries"));
        assertTrue(overlay.contains("removeSeries"));
        assertTrue(overlay.contains("setData"));
        assertFalse(overlay.contains("subscribeCrosshairMove"));
        assertFalse(overlay.contains("mouseenter"));
        assertFalse(overlay.contains("setVisibleLogicalRange"));
        assertFalse(overlay.contains("fitContent"));
    }

    @Test
    void stockWorkspacePutsMarketAnalysisBeforeAlertControlsAndCongressionalActivity() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String stylesheet = stylesheets();

        int priceChart = stock.indexOf("id=\"priceChartContainer\"");
        int automatedSignals = stock.indexOf("<section class=\"alert-panel alert-star-panel\">");
        int congressionalActivity = stock.indexOf("id=\"congressionalActivityPanel\"");

        assertTrue(priceChart >= 0);
        assertTrue(automatedSignals > priceChart);
        assertTrue(congressionalActivity > automatedSignals);

        // Volume and indicator panels are panes of the one price chart, sharing its time axis.
        assertFalse(stock.contains("id=\"volumeChartContainer\""));
        assertFalse(stock.contains("id=\"rsiChartContainer\""));
        assertTrue(stock.contains("id=\"volumeChartToggle\""));
        assertTrue(stock.contains("aria-controls=\"priceChartStage\""));
        assertTrue(stock.contains("aria-pressed=\"true\""));
        assertTrue(stock.contains("let volumeChartEnabled = true"));
        assertTrue(stock.contains("indicatorManager?.setVolume(volumeChartEnabled, volumeHooks())"));
        assertTrue(stock.contains(".addEventListener('click', toggleVolumeChart)"));
        assertTrue(stock.contains("data-chart-resize-target=\"priceChartStage\""));
        assertEquals(1, stock.split("data-chart-resize-target=", -1).length - 1);
        assertTrue(stock.contains("role=\"separator\""));
        assertTrue(stock.contains("new ResizeObserver(entries =>"));
        assertTrue(stock.contains("handle.addEventListener('pointerdown'"));
        assertTrue(stock.contains("handle.addEventListener('keydown'"));
        assertTrue(stock.contains("initializeResizableCharts();"));
        assertTrue(stylesheet.contains(".chart-resize-handle"));
        assertTrue(stylesheet.contains("cursor: ns-resize"));
        assertTrue(stylesheet.contains("touch-action: none"));
    }

    @Test
    void stockWorkspaceUsesAccessibleStatePreservingSubtabs() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String stylesheet = stylesheets();

        assertTrue(stock.contains("class=\"stock-workspace-tabs\" role=\"tablist\""));
        assertTrue(stock.contains("id=\"generalWorkspaceTab\""));
        assertTrue(stock.contains("id=\"technicalAnalysisWorkspaceTab\""));
        assertTrue(stock.contains("id=\"tickerAlertsWorkspaceTab\""));
        assertTrue(stock.contains("id=\"generalWorkspacePanel\""));
        assertTrue(stock.contains("id=\"technicalAnalysisWorkspacePanel\""));
        assertTrue(stock.contains("id=\"tickerAlertsWorkspacePanel\""));
        assertTrue(stock.contains("data-stock-workspace-tab=\"general\""));
        assertTrue(stock.contains("data-stock-workspace-tab=\"technical-analysis\""));
        assertTrue(stock.contains("data-stock-workspace-tab=\"ticker-alerts\""));
        assertTrue(stock.contains("const STOCK_WORKSPACE_SECTIONS = ['general', 'technical-analysis', 'ticker-alerts']"));
        assertTrue(stock.contains("window.history.replaceState"));
        assertTrue(stock.contains("window.addEventListener('hashchange'"));
        assertTrue(stock.contains("event.key === 'ArrowRight'"));
        assertTrue(stock.contains("event.key === 'ArrowLeft'"));
        assertTrue(stock.contains("resizeGeneralWorkspaceCharts"));
        assertTrue(stock.contains("id=\"tickerAlertsEligibilityNotice\""));
        assertTrue(stock.contains("not for indexes or ETFs"));
        int workspaceNavigationStyle = stylesheet.indexOf(".stock-workspace-navigation {");
        int workspaceNavigationStyleEnd = stylesheet.indexOf('}', workspaceNavigationStyle);
        String workspaceNavigationRules = stylesheet.substring(workspaceNavigationStyle, workspaceNavigationStyleEnd);
        assertTrue(workspaceNavigationRules.contains("position: relative"));
        assertFalse(workspaceNavigationRules.contains("position: sticky"));
        assertTrue(stylesheet.contains("[data-stock-workspace-tab=\"technical-analysis\"]"));
        assertTrue(stylesheet.contains("--workspace-tab-accent: #9a8cff"));
        assertTrue(stylesheet.contains("--workspace-tab-accent: #36cbb1"));
        assertTrue(stylesheet.contains(".stock-workspace-panel[hidden]"));
    }

    @Test
    void stockWorkspaceUsesAnAccessibleLoaderUntilAsyncInitializationFinishes() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String stylesheet = stylesheets();

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
        assertTrue(stock.contains("window.onload = async () =>"));
        assertTrue(stock.contains("await initCharts();"));
        assertTrue(stock.contains("initializeStockWorkspaceTabs();"));
        assertTrue(stock.indexOf("id=\"stockPageLoader\"")
                < stock.indexOf("<div th:replace=\"~{fragments/navbar :: navbar}\"></div>"));

        assertTrue(stylesheet.contains(".stock-page-loader"));
        assertTrue(stylesheet.contains("@keyframes stock-loader-path"));
        assertTrue(stylesheet.contains("@keyframes stock-loader-progress"));
        assertTrue(stylesheet.contains(".stock-page-loader.is-complete"));
    }

    @Test
    void stockAlertChangesRemainDraftsUntilExplicitlyApplied() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String navbar = english(Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html")));

        assertTrue(stock.contains("id=\"applyAlertChangesBtn\""));
        assertTrue(stock.contains("id=\"toggleAllTechnicalMonitoringBtn\""));
        assertTrue(stock.contains("function toggleAllTechnicalMonitoring()"));
        assertTrue(stock.contains("alertInputs().forEach(input =>"));
        assertTrue(stock.contains("[...alertInputs(), ...outlookFollowInputs()].forEach"));
        assertTrue(stock.contains("anyFollowed ? 'sw.unfollowAll' : 'sw.followAll'"));
        assertFalse(stock.contains("async function updateOutlookFollow("));
        assertTrue(stock.contains("id=\"unsavedAlertDialog\""));
        assertTrue(stock.contains("id=\"saveAlertChangesBeforeLeaveBtn\""));
        assertTrue(stock.contains("id=\"discardAlertChangesBtn\""));
        assertTrue(stock.contains("id=\"keepEditingAlertsBtn\""));
        assertTrue(stock.contains("const persistedAlertState = new Map()"));
        assertTrue(stock.contains("outlookChanges: outlookInputs.map(input =>"));
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
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String dashboard = english(Files.readString(Path.of("src/main/resources/templates/home.html")));

        assertTrue(stock.contains("id=\"congressionalActivityPanel\""));
        assertTrue(stock.contains("panel.hidden = !congressionalActivityEligible"));
        assertTrue(stock.contains("instrumentType === 'EQUITY'"));
        assertTrue(stock.contains("/api/congressional-activity/${encodedTicker}/state"));
        assertTrue(stock.contains("/api/congressional-activity/${encodedTicker}/history"));
        assertTrue(stock.contains("/api/congressional-activity/${encodedTicker}/subscription"));
        assertTrue(stock.contains("Loading filings"));
        assertTrue(stock.contains("last 365 days"));
        assertTrue(stock.contains("never generates old email alerts"));
        assertTrue(stock.contains("rows.replaceChildren"));
        assertTrue(stock.contains("function activityHistoryDetailUrl(trade, source)"));
        assertTrue(stock.contains("/activity-signals/${source}/trades/${encodeURIComponent(trade.id)}"));
        assertTrue(stock.contains("makeActivityHistoryRowInteractive(row, detailUrl, trade.memberName)"));
        assertTrue(stock.contains("makeActivityHistoryRowInteractive(row, detailUrl, trade.insiderName)"));
        assertTrue(stock.contains("if (event.target.closest('a, button')) return"));
        assertFalse(stock.contains("innerHTML"));

        assertTrue(dashboard.contains("th:each=\"activity : ${congressionalActivities}\""));
        assertTrue(dashboard.contains("th:each=\"stock : ${congressionalFollowedStocks}\""));
        assertTrue(dashboard.contains("CongressInvests"));
        assertTrue(dashboard.contains("up to 45 days"));
    }

    @Test
    void insiderActivityUsesDailyFiledTradeMonitoringAndCompletedCloseReturns() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String dashboard = english(Files.readString(Path.of("src/main/resources/templates/home.html")));

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
        assertTrue(stock.contains("Company insider filings"));
        assertTrue(stock.contains("Loading insider filings"));
        assertTrue(stock.contains("Filing date shown; trade date unavailable"));
        assertTrue(dashboard.contains("Directional return"));
    }

    @Test
    void dashboardSeparatesTechnicalAnalysisFromTickerAlerts() throws IOException {
        String dashboard = english(Files.readString(Path.of("src/main/resources/templates/home.html")));
        String dashboardScript = english(Files.readString(Path.of("src/main/resources/static/js/dashboard.js")));
        String styles = stylesheets();

        assertFalse(dashboard.contains("dashboard-view-navigation"));
        assertFalse(dashboard.contains("data-dashboard-view-button"));
        assertTrue(dashboard.contains("th:href=\"@{/signals}\""));
        assertTrue(dashboard.contains("th:href=\"@{/signals(state='unread')}\""));
        assertTrue(dashboard.contains("th:href=\"@{/activity-signals}\""));
        assertTrue(dashboard.contains("id=\"technicalDashboardMetrics\""));
        assertTrue(dashboard.contains("id=\"tickerAlertsIntroduction\""));
        assertTrue(dashboard.contains("id=\"latestTickerNotifications\""));
        assertTrue(dashboard.contains("id=\"congressionalActivityDashboard\""));
        assertTrue(dashboard.contains("id=\"insiderActivityDashboard\""));
        assertTrue(dashboard.contains("data-dashboard-view=\"technical\""));
        assertTrue(dashboard.contains("data-dashboard-view=\"alerts\""));
        assertTrue(dashboard.contains("Purchases and sales reported by company insiders"));

        int watchDesk = dashboard.indexOf("id=\"technicalWatchDesk\"");
        int latestSignals = dashboard.indexOf("id=\"latestTechnicalSignals\"");
        int technicalSidebar = dashboard.indexOf("class=\"dashboard-technical-sidebar\"");
        int technicalMetrics = dashboard.indexOf("id=\"technicalDashboardMetrics\"");
        int systemPulse = dashboard.indexOf("id=\"systemPulsePanel\"");
        int tickerIntroduction = dashboard.indexOf("id=\"tickerAlertsIntroduction\"");
        int latestNotifications = dashboard.indexOf("id=\"latestTickerNotifications\"");
        int congressionalActivity = dashboard.indexOf("id=\"congressionalActivityDashboard\"");
        int insiderActivity = dashboard.indexOf("id=\"insiderActivityDashboard\"");
        assertTrue(watchDesk >= 0);
        assertTrue(latestSignals >= 0);
        assertTrue(latestSignals < watchDesk);
        assertTrue(tickerIntroduction > watchDesk);
        assertTrue(latestNotifications > tickerIntroduction);
        assertTrue(congressionalActivity > latestNotifications);
        assertTrue(insiderActivity > congressionalActivity);
        assertTrue(technicalSidebar > insiderActivity);
        assertTrue(technicalMetrics > technicalSidebar);
        assertTrue(systemPulse > technicalMetrics);
        assertTrue(styles.contains(".dashboard-technical-sidebar"));
        assertTrue(styles.contains(".dashboard-sidebar-metrics"));
        assertTrue(styles.contains("grid-template-columns: repeat(2, minmax(0, 1fr));"));

        assertTrue(dashboardScript.contains("function initializeDashboardViews()"));
        assertTrue(dashboardScript.contains("window.location.replace(\"/ticker-alerts\")"));
        assertTrue(dashboardScript.contains("data-notification-read-all"));
        assertTrue(dashboardScript.contains("reloadTickerAlerts"));
        assertTrue(dashboardScript.contains("section.hidden = section.dataset.dashboardView !== view"));
        assertTrue(dashboardScript.contains("view === \"alerts\""));
        assertTrue(dashboardScript.contains("button.setAttribute(\"aria-pressed\", String(selected))"));
        assertFalse(dashboardScript.contains("innerHTML"));
    }

    @Test
    void dashboardAndCompanyHistoryReuseTheSharedSignalArchive() throws IOException {
        String dashboard = english(Files.readString(Path.of("src/main/resources/templates/home.html")));
        String dashboardScript = english(Files.readString(Path.of("src/main/resources/static/js/dashboard.js")));
        String styles = stylesheets();
        String archive = english(Files.readString(Path.of("src/main/resources/templates/all-signals.html")));
        String activityArchive = english(Files.readString(
                Path.of("src/main/resources/templates/all-activity-signals.html")));
        String archiveSelectionScript = english(Files.readString(
                Path.of("src/main/resources/static/js/signal-archive-selection.js")));
        String activityDetail = english(Files.readString(
                Path.of("src/main/resources/templates/activity-signal-detail.html")));
        String signalDetail = english(Files.readString(Path.of("src/main/resources/templates/signal-detail.html")));
        String detailChartIntervals = english(Files.readString(
                Path.of("src/main/resources/static/js/detail-chart-intervals.js")));

        String watchlists = english(Files.readString(Path.of("src/main/resources/static/js/watchlists.js")));
        assertTrue(dashboard.contains("data-named-watchlists"));
        assertTrue(watchlists.contains("/signals?${new URLSearchParams({ watchlistId: list.id, ticker: item.symbol })}"));
        String watchlistText = Files.readString(Path.of("src/main/resources/messages.properties"));
        assertTrue(watchlists.contains("watchlists.viewAllSignals") && watchlistText.contains("js.watchlists.viewAllSignals=View all signals"));
        assertTrue(watchlists.contains("item.signals"));
        assertTrue(watchlistText.contains("js.watchlists.column.unread=Unread signals"));
        assertFalse(dashboard.contains("th:each=\"alert : ${activeAlerts}\""));
        assertTrue(dashboard.contains("th:each=\"item : ${latestSignalItems}\""));
        assertTrue(dashboard.contains("item.technicalSignal()"));
        assertTrue(dashboard.contains("item.outlookChange()"));
        assertTrue(dashboard.contains("change.detailUrl()"));
        assertTrue(dashboard.contains("@{/signals(state='unread')}"));
        assertFalse(archive.contains("technicalOutlook"));
        assertFalse(archive.contains("outlookChange"));
        assertTrue(dashboard.contains("@{/alerts/signals/{id}(id=${signal.id()})}"));
        assertTrue(dashboard.contains("signal.setupScore()"));
        assertTrue(dashboard.contains("signal.signalPeriodLabel()"));
        assertTrue(dashboard.contains("signal.researchHorizonLabel()"));
        assertTrue(dashboard.contains(">Status</span>"));
        assertTrue(dashboard.contains("class=\"latest-lifecycle\""));
        assertTrue(dashboard.contains("signal.lifecycle().label()"));
        assertTrue(watchlists.contains("['all', 'stocks', 'funds']"));
        assertTrue(watchlistText.contains("js.watchlists.group.stocks=Stocks"));
        assertTrue(watchlistText.contains("js.watchlists.group.funds=Indexes / ETFs"));
        assertTrue(watchlists.contains("group: group.input.value"));
        assertTrue(dashboard.contains("@{/js/dashboard.js}"));
        assertTrue(dashboardScript.contains("activateFilter(\"stocks\")"));
        assertTrue(dashboardScript.contains("row.hidden = !visible"));
        assertTrue(dashboardScript.contains("button.setAttribute(\"aria-pressed\", String(selected))"));
        assertFalse(dashboardScript.contains("innerHTML"));

        assertTrue(activityDetail.contains("class=\"signal-report-header\""));
        assertTrue(activityDetail.contains("class=\"signal-view-tabs signal-three-view-tabs\""));
        assertTrue(activityDetail.contains("class=\"signal-view-tab active\""));
        assertTrue(activityDetail.contains("class=\"signal-chart-card\""));
        assertTrue(activityDetail.contains("class=\"signal-evidence-section\""));
        assertTrue(activityDetail.contains("class=\"signal-results-section\""));
        assertTrue(activityDetail.contains("data-chart-kind=\"activity\""));
        assertTrue(activityDetail.contains("data-detail-interval=\"1d\""));
        assertTrue(activityDetail.contains("data-detail-interval=\"1wk\""));
        assertTrue(activityDetail.contains("data-detail-interval=\"1mo\""));
        assertTrue(activityDetail.contains("data-alternate-detail-chart"));
        assertTrue(activityDetail.contains("window.StockWatchSignalTabs"));
        assertFalse(activityDetail.contains("signal-detail-tab"));
        assertFalse(activityDetail.contains("activity-detail-hero"));

        assertTrue(archive.contains("archive.signals()"));
        assertTrue(archive.contains("companyArchive != null"));
        assertTrue(archive.contains("'signals.companyHeading', companyArchive.symbol()"));
        assertTrue(archive.contains("@{/alerts/{id}/signals/delete"));
        assertTrue(archive.contains("name=\"sort\""));
        assertTrue(archive.contains("name=\"direction\""));
        assertTrue(archive.contains("value=\"confidence\""));
        assertTrue(archive.contains("value=\"interval\""));
        assertTrue(archive.contains("value=\"status\""));
        assertTrue(archive.contains("value=\"trade-return\""));
        assertTrue(archive.contains("archive.groupKey"));
        assertTrue(archive.contains("signal.hasBeenRead()"));
        assertTrue(archive.contains("Signal status"));
        assertTrue(archive.contains("Setup score"));
        assertTrue(archive.contains("signal.lifecycle().label()"));
        assertTrue(archive.contains("entry.outcome().valueLabel()"));
        assertTrue(archive.contains("entry.outcome().priceDetail()"));
        assertTrue(archive.contains("using each pattern family's stop, target and time-limit rules"));
        assertTrue(archive.contains("stop, target and time-limit rules"));
        assertTrue(archive.contains("entry.stageOutcomes()"));
        assertFalse(archive.contains("entry.bestDirectionalMovePercent()"));
        assertFalse(archive.contains("entry.worstDirectionalMovePercent()"));
        assertTrue(archive.contains("@{/alerts/signals/{id}(id=${signal.id()},returnTo=${archiveReturnUrl})}"));
        assertTrue(archive.contains("@{/signals/delete}"));
        assertTrue(archive.contains("name=\"signalIds\""));
        assertTrue(archive.contains("name=\"singleSignalId\""));
        assertTrue(archive.contains("data-select-all"));
        assertTrue(archive.contains("data-delete-dialog"));
        assertTrue(dashboard.contains("data-notification-read"));
        assertTrue(dashboard.contains("@{/activity-signals}"));
        assertTrue(dashboard.contains("ticker-notification-row latest-signal-row is-unread"));
        assertTrue(dashboard.contains("congressional-dashboard-row latest-signal-row is-unread"));
        assertTrue(dashboard.contains("insider-dashboard-row latest-signal-row is-unread"));
        assertTrue(dashboard.contains("activity.returnPercent()"));
        assertTrue(dashboard.contains("from the first close after disclosure"));
        assertTrue(styles.contains(".activity-archive-links"));
        assertTrue(styles.contains(".ticker-notification-row .congressional-row-actions"));
        assertTrue(dashboardScript.contains("initializeNotificationReadButtons"));
        assertTrue(dashboardScript.contains("X-CSRF-TOKEN"));
        assertFalse(dashboardScript.contains("innerHTML"));
        assertTrue(activityArchive.contains("archive.signals()"));
        assertTrue(activityArchive.contains("value=\"company\""));
        assertTrue(activityArchive.contains("value=\"transaction\""));
        assertTrue(activityArchive.contains("value=\"type\""));
        assertTrue(activityArchive.contains("value=\"actor\""));
        assertTrue(activityArchive.contains("signal.hasBeenRead()"));
        assertTrue(activityArchive.contains("Directional return"));
        assertTrue(activityArchive.contains("activity-signal-archive-row latest-signal-row"));
        assertTrue(activityArchive.contains("activity-archive-return"));
        assertTrue(activityArchive.contains("signal.returnPercent()"));
        assertTrue(activityArchive.contains("latest-direction-tag"));
        assertTrue(activityArchive.contains("@{/activity-signals/delete}"));
        assertTrue(activityArchive.contains("name=\"signalKeys\""));
        assertTrue(activityArchive.contains("name=\"singleSignalKey\""));
        assertTrue(activityArchive.contains("data-select-all"));
        assertTrue(activityArchive.contains("data-delete-dialog"));
        assertTrue(archiveSelectionScript.contains("form.requestSubmit"));
        assertTrue(archiveSelectionScript.contains("selectAll.indeterminate"));
        assertFalse(archiveSelectionScript.contains("innerHTML"));

        assertTrue(signalDetail.contains("signal.setupScore()"));
        assertTrue(signalDetail.contains("signal.signalPeriodLabel()"));
        assertTrue(signalDetail.contains("signal.researchHorizonLabel()"));
        assertTrue(signalDetail.contains("class=\"signal-lifecycle-timeline\""));
        assertTrue(signalDetail.contains("signal.lifecycle().resolutionPeriodLabel()"));
        assertTrue(signalDetail.contains("signal.lifecycle().updatedAt()"));
        assertTrue(signalDetail.contains("Wave structure range"));
        assertTrue(signalDetail.contains("Alert recorded"));
        assertTrue(signalDetail.contains("Outcome checked"));
        assertTrue(signalDetail.contains("#numbers.sequence(1, 20)"));
        assertTrue(signalDetail.contains("id=\"graphicalOutlookTab\""));
        assertTrue(signalDetail.contains("id=\"scoreReportTab\""));
        assertTrue(signalDetail.contains("id=\"resultsTab\""));
        assertTrue(signalDetail.contains("id=\"graphicalOutlookPanel\""));
        assertTrue(signalDetail.contains("id=\"scoreReportPanel\""));
        assertTrue(signalDetail.contains("id=\"resultsPanel\""));
        assertTrue(signalDetail.contains("signal.results().available()"));
        assertTrue(signalDetail.contains("id=\"signalResultsChart\""));
        assertTrue(signalDetail.contains("id=\"signalResultsData\""));
        assertTrue(signalDetail.contains("id=\"resultWindowSlider\""));
        assertTrue(signalDetail.contains("signal.results().minimumForwardCandles()"));
        assertTrue(signalDetail.contains("signal.results().availableForwardCandles()"));
        assertTrue(signalDetail.contains("id=\"signalResultsUnavailableDialog\""));
        assertTrue(signalDetail.contains("function renderSelectedResults()"));
        assertTrue(signalDetail.contains("const segmentEnd = selectedPoints[Math.min(index + 1"));
        assertTrue(signalDetail.contains("color: segmentEnd.directionalReturn > 0"));
        assertTrue(signalDetail.contains("tradeSignal === 'SELL'"));
        assertTrue(signalDetail.contains("point.close < best.close"));
        assertTrue(signalDetail.contains("point.close > best.close"));
        assertTrue(signalDetail.contains("Completed closing prices"));
        assertTrue(signalDetail.contains("The highlighted best price is known only in hindsight."));
        assertTrue(signalDetail.contains("Results chart color legend"));
        assertTrue(signalDetail.contains("Favorable move in the signal direction"));
        assertTrue(signalDetail.contains("Adverse move against the signal"));
        assertTrue(signalDetail.contains("Starting price"));
        assertTrue(signalDetail.contains("signalDetail.measurementStart.SELL") && signalDetail.contains("SELL/SHORT measurement start"));
        assertTrue(signalDetail.contains("color: colors.signalStart"));
        assertTrue(signalDetail.contains("Best price in this period"));
        assertTrue(signalDetail.contains("id=\"signalChart\""));
        assertTrue(signalDetail.contains("data-chart-kind=\"technical\""));
        assertTrue(signalDetail.contains("data-signal-family=${signal.patternFamily().name()}"));
        assertTrue(signalDetail.contains("data-native-interval-notice"));
        assertTrue(signalDetail.contains("data-return-native-interval"));
        assertTrue(signalDetail.contains("data-alternate-detail-chart"));
        assertTrue(detailChartIntervals.contains("interval === nativeInterval"));
        assertTrue(detailChartIntervals.contains("chartKind === 'activity'"));
        assertTrue(detailChartIntervals.contains("nativeLegend.hidden = chartKind === 'technical'"));
        assertTrue(detailChartIntervals.contains("Top-down Elliott degree"));
        assertTrue(detailChartIntervals.contains("/candles?interval="));
        assertFalse(detailChartIntervals.contains("innerHTML"));
        assertTrue(signalDetail.contains("signal.chart().candles()"));
        assertTrue(signalDetail.contains("signal.chart().elliottWave()"));
        assertTrue(signalDetail.contains("id=\"signalElliottWaveData\""));
        assertTrue(signalDetail.contains("const recordedEndpoint = [...elliottWave.points]"));
        assertTrue(signalDetail.contains("const buyConfirmation = container.dataset.direction === 'BUY'"));
        assertTrue(signalDetail.contains("function renderElliottSegment"));
        assertTrue(signalDetail.contains("Motive I&ndash;V"));
        assertTrue(signalDetail.contains("Available price history"));
        assertTrue(signalDetail.contains("signal-chart-trend-band"));
        assertTrue(signalDetail.contains("signal-pattern-callout"));
        assertTrue(signalDetail.contains("timeToCoordinate"));
        assertTrue(signalDetail.contains("const visibleLeft = Math.max(0, rawLeft)"));
        assertTrue(signalDetail.contains("const visibleRight = Math.min(chartWidth, rawRight)"));
        assertTrue(signalDetail.contains("trendBand.hidden = visibleRight <= visibleLeft"));
        assertTrue(signalDetail.contains("rectanglesOverlap"));
        assertTrue(signalDetail.contains("classList.add('elbow-right')"));
        assertTrue(signalDetail.contains("function focusSignal()"));
        assertTrue(signalDetail.contains("setVisibleLogicalRange"));
        assertTrue(signalDetail.contains("stockwatch:themechange"));
        assertTrue(signalDetail.contains("displayScore.sections()"));
        assertTrue(signalDetail.contains("reason.scoreLabel()"));
        assertTrue(signalDetail.contains("reason.details()"));
        assertTrue(signalDetail.contains("detail.text()"));
        assertTrue(signalDetail.contains("class=\"evidence-detail-list\""));
        assertTrue(signalDetail.contains("Detailed evidence was not stored for this signal"));
        assertTrue(signalDetail.contains("Modeled trade outcome"));
        assertTrue(signalDetail.contains("signal.observedOutcome().directionalReturnPercent()"));
        assertTrue(signalDetail.contains("Most favorable completed-close move"));
        assertTrue(signalDetail.contains("data-trade-target"));
        assertFalse(signalDetail.contains("result-measurement-basis"));
        assertFalse(signalDetail.contains("th:utext"));
        assertFalse(signalDetail.contains("style="));
        assertFalse(signalDetail.contains("<style>"));
        assertFalse(signalDetail.matches("(?s).*\\son(?:click|load|error)=.*"));
    }

    @Test
    void fullCandleHistoryReplacementRebuildsDateDeduplicationState() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));

        int processorStart = stock.indexOf("function processAndSetData(rawData, isPrepend)");
        int candleLoopStart = stock.indexOf("rawData.forEach", processorStart);
        assertTrue(processorStart >= 0);
        assertTrue(candleLoopStart > processorStart);

        String replacementSetup = stock.substring(processorStart, candleLoopStart);
        assertTrue(replacementSetup.matches(
                "(?s).*if\\s*\\(!isPrepend\\)\\s*\\{\\s*seenDates\\.clear\\(\\);\\s*}.*"));
    }

    @Test
    void automatedOutlookFollowingUsesIntervalSubscriptionsAndDedicatedChangeReport() throws IOException {
        String stock = (english(Files.readString(Path.of("src/main/resources/templates/stock.html"))) + english(Files.readString(Path.of("src/main/resources/static/js/stock-workspace.js"))));
        String dashboard = english(Files.readString(Path.of("src/main/resources/templates/home.html")));
        String detail = english(Files.readString(
                Path.of("src/main/resources/templates/technical-outlook-change.html")));
        String stylesheet = stylesheets();

        assertTrue(stock.contains(">Automated Technical Outlook</span></div>"));
        assertTrue(stock.contains("data-outlook-follow-interval=\"DAILY\""));
        assertTrue(stock.contains("data-outlook-follow-interval=\"WEEKLY\""));
        assertTrue(stock.contains("data-outlook-follow-interval=\"MONTHLY\""));
        assertTrue(stock.contains("/technical-outlook/subscriptions"));
        assertTrue(stock.contains("class=\"alert-section outlook-alert-section\""));
        assertTrue(stock.contains("class=\"alert-grid outlook-alert-grid\""));
        assertTrue(stock.contains("class=\"alert-eye-input outlook-follow-input\""));
        assertTrue(stock.contains("Follow outlook"));
        assertFalse(stock.contains("class=\"outlook-follow-panel\""));
        assertFalse(stock.contains("data-outlook-follow-signal"));
        assertTrue(dashboard.contains("item.outlookChange()"));
        assertTrue(detail.contains("data-change-tab=\"chart\""));
        assertTrue(detail.contains("data-change-tab=\"report\""));
        assertTrue(detail.contains("outlookChangeIndicatorCharts"));
        assertTrue(detail.contains("outlookIndicatorDeltas"));
        assertTrue(detail.contains("outlookSignalEvidence"));
        assertTrue(detail.contains("<script th:inline=\"javascript\" th:attr=\"nonce=${cspNonce}\">"));
        assertTrue(detail.contains("JSON.parse(/*[[${currentSnapshotJson}]]*/ 'null')"));
        assertTrue(detail.contains("JSON.parse(/*[[${changeReportJson}]]*/ 'null')"));
        assertFalse(detail.contains("/*[[${change}]]*/"));
        assertTrue(detail.contains("id=\"toggleUnchangedIndicators\""));
        assertTrue(detail.contains("outlook-driver-card"));
        assertTrue(detail.contains("technical-recent-signal outlook-evidence-card"));
        assertTrue(detail.contains("outlook-alignment-badge"));
        assertFalse(detail.contains("technical-recent-signal-card"));
        assertTrue(stylesheet.contains(".outlook-evidence-card"));
        assertTrue(stylesheet.contains(".outlook-alignment-badge.contradicts"));
        assertTrue(stylesheet.contains(".outlook-indicator-delta.is-collapsed"));
        assertTrue(stylesheet.contains("var(--accent-color) 28%"));
        assertFalse(stylesheet.contains("var(--panel-bg)"));
        assertFalse(detail.contains("th:utext"));
        assertFalse(detail.contains("innerHTML"));
    }
}
