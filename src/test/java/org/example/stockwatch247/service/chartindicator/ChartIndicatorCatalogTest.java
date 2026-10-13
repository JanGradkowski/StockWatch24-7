package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorBatchRequest;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorConfiguration;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorConfigurationView;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputKind;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartIndicatorCatalogTest {
    private final ChartIndicatorCatalog catalog = new ChartIndicatorCatalog();

    @Test
    void catalogCoversTheTradingViewParitySetWithConsistentDefinitions() {
        List<IndicatorDefinition> all = catalog.all();
        assertThat(all).hasSizeGreaterThanOrEqualTo(100);
        Set<String> types = new HashSet<>();
        for (IndicatorDefinition definition : all) {
            assertThat(types.add(definition.type())).as(definition.type()).isTrue();
            assertThat(definition.outputs().isEmpty() && !definition.shapes()).as(definition.type() + " draws something").isFalse();
            // Defaults must always pass validation.
            catalog.resolve(definition, Map.of(), Set.of(), "self", "1d");
            if (definition.zones() != null) {
                assertThat(definition.inputs()).extracting(IndicatorDefinition.InputSpec::key)
                        .contains(definition.zones().upperInput(), definition.zones().lowerInput());
            }
            for (OutputSpec output : definition.outputs()) {
                assertThat(output.color()).as(definition.type() + "." + output.key()).isNotBlank();
                if (output.colorMode() == IndicatorDefinition.ColorMode.TONE) assertThat(output.palette()).isNotEmpty();
            }
        }
        assertThat(types).contains("ICHIMOKU", "PARABOLIC_SAR", "SUPERTREND", "WILLIAMS_R", "AROON", "PIVOT_POINTS_STANDARD",
                "ANCHORED_VWAP", "ZIG_ZAG", "HMA", "ALMA", "VWAP_PERIODIC", "AUTO_FIB_RETRACEMENT", "LINEAR_REGRESSION_CHANNEL",
                "VISIBLE_RANGE_VOLUME_PROFILE", "SEASONALITY", "PERFORMANCE", "COMPARE", "FORMULA");
    }

    @Test
    void everyIndicatorComputesWithItsDefaultsOnARealisticSeries() {
        CandleRepository repository = repository(candles("AAPL", "1d", 700, 0), candles("SPY", "1d", 700, 2));
        ChartTechnicalIndicatorService service = new ChartTechnicalIndicatorService(repository);
        long first = day(0);
        long last = day(699);
        for (IndicatorDefinition definition : catalog.all()) {
            var result = service.calculate("AAPL", "1d", new IndicatorBatchRequest(first, last, day(500), last,
                    List.of(new IndicatorConfiguration("a", definition.type(), Map.of()))));
            IndicatorConfigurationView view = result.indicators().getFirst();
            assertThat(view.error()).as(definition.type()).isNull();
            boolean drawsSomething = view.series().stream().anyMatch(series -> !series.points().isEmpty())
                    || !view.markers().isEmpty() || !view.shapes().isEmpty();
            assertThat(drawsSomething).as(definition.type() + " draws something").isTrue();
            view.series().forEach(series -> series.points().forEach(point -> assertThat(point.value()).isFinite()));
        }
    }

    @Test
    void movingAveragesAndRsiMatchHandComputedValues() {
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] sma = TaMath.sma(values, 3);
        assertThat(sma[1]).isNaN();
        assertThat(sma[2]).isEqualTo(2);
        assertThat(sma[9]).isEqualTo(9);
        double[] ema = TaMath.ema(values, 3);
        assertThat(ema[2]).isEqualTo(2);
        assertThat(ema[3]).isCloseTo(0.5 * 4 + 0.5 * 2, within(1e-12));
        double[] rising = TaMath.rsi(values, 3);
        assertThat(rising[3]).isEqualTo(100);
        double[] warmup = {Double.NaN, Double.NaN, 4, 5, 6};
        assertThat(TaMath.sma(warmup, 2)[3]).isEqualTo(4.5);
        assertThat(TaMath.wma(values, 3)[2]).isCloseTo((1 + 2 * 2 + 3 * 3) / 6.0, within(1e-12));
        assertThat(TaMath.linreg(values, 4, 0)[9]).isCloseTo(10, within(1e-9));
        assertThat(TaMath.stdev(new double[]{2, 4, 4, 4, 5, 5, 7, 9}, 8)[7]).isCloseTo(2, within(1e-12));
    }

    @Test
    void traditionalPivotsUseThePreviousPeriodsHighLowAndClose() {
        LevelIndicators.Period previous = new LevelIndicators.Period(LocalDate.of(2024, 1, 1), 0, 20, 100, 120, 90, 110);
        Map<String, Double> levels = LevelIndicators.pivotLevels("TRADITIONAL", previous, 111);
        double pivot = (120 + 90 + 110) / 3.0;
        assertThat(levels.get("P")).isCloseTo(pivot, within(1e-9));
        assertThat(levels.get("R1")).isCloseTo(2 * pivot - 90, within(1e-9));
        assertThat(levels.get("S2")).isCloseTo(pivot - 30, within(1e-9));
        assertThat(LevelIndicators.pivotLevels("CAMARILLA", previous, 111).get("R4")).isCloseTo(110 + 1.1 * 30 / 2, within(1e-9));
        assertThat(LevelIndicators.pivotLevels("DM", previous, 111).get("P")).isCloseTo((2 * 120 + 90 + 110) / 4.0, within(1e-9));
    }

    @Test
    void ichimokuLeadingSpansExtendIntoFutureWeekdays() {
        CandleRepository repository = repository(candles("AAPL", "1d", 200, 0));
        var view = new ChartTechnicalIndicatorService(repository).calculate("AAPL", "1d",
                new IndicatorBatchRequest(null, null, List.of(new IndicatorConfiguration("ichi", "ICHIMOKU", Map.of())))).indicators().getFirst();
        var spanA = view.series().stream().filter(series -> series.key().equals("spanA")).findFirst().orElseThrow();
        long last = candles("AAPL", "1d", 200, 0).getLast().getTimestamp();
        List<Long> future = spanA.points().stream().map(ChartTechnicalIndicatorService.PointView::timestamp).filter(time -> time > last).toList();
        assertThat(future).hasSize(25);
        future.forEach(time -> assertThat(MarketCalendar.date(time).getDayOfWeek().getValue()).isLessThan(6));
    }

    @Test
    void higherTimeframeValuesAppearOnlyAfterTheirPeriodCloses() {
        List<Candle> daily = new ArrayList<>();
        List<Candle> weekly = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            weekly.add(new Candle("X", "1wk", day(week * 7), 1, 1, 1, 1.0, 1L));
            for (int dayOfWeek = 0; dayOfWeek < 5; dayOfWeek++) daily.add(new Candle("X", "1d", day(week * 7 + dayOfWeek), 1, 1, 1, 1.0, 1L));
        }
        double[] mapped = ChartIndicatorEngine.mapHigherTimeframe(daily, weekly, new double[]{10, 20, 30});
        for (int index = 0; index < 5; index++) assertThat(mapped[index]).isNaN();
        for (int index = 5; index < 10; index++) assertThat(mapped[index]).isEqualTo(10);
        for (int index = 10; index < 15; index++) assertThat(mapped[index]).isEqualTo(20);
    }

    @Test
    void indicatorsCanReadAnotherIndicatorAndCyclesAreRejected() {
        CandleRepository repository = repository(candles("AAPL", "1d", 300, 0));
        ChartTechnicalIndicatorService service = new ChartTechnicalIndicatorService(repository);
        var result = service.calculate("AAPL", "1d", new IndicatorBatchRequest(null, null, List.of(
                new IndicatorConfiguration("smoothed", "SMA", Map.of("period", 5, "source", "ind:rsi:value")),
                new IndicatorConfiguration("rsi", "RSI", Map.of()))));
        var smoothed = result.indicators().getFirst().series().getFirst().points();
        var rsi = result.indicators().get(1).series().getFirst().points();
        assertThat(smoothed).isNotEmpty();
        assertThat(smoothed.getLast().value()).isCloseTo(rsi.subList(rsi.size() - 5, rsi.size()).stream()
                .mapToDouble(ChartTechnicalIndicatorService.PointView::value).average().orElseThrow(), within(1e-9));
        assertThatThrownBy(() -> service.calculate("AAPL", "1d", new IndicatorBatchRequest(null, null, List.of(
                new IndicatorConfiguration("a", "SMA", Map.of("source", "ind:b:value")),
                new IndicatorConfiguration("b", "EMA", Map.of("source", "ind:a:value"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    @Test
    void inputsAreValidatedAgainstTheirDefinitions() {
        IndicatorDefinition sma = catalog.find("SMA").orElseThrow();
        assertThatThrownBy(() -> catalog.resolve(sma, Map.of("period", 2.5), Set.of(), "a", "1d"))
                .hasMessageContaining("whole number");
        assertThatThrownBy(() -> catalog.resolve(sma, Map.of("unknown", 1), Set.of(), "a", "1d"))
                .hasMessageContaining("Unknown");
        assertThatThrownBy(() -> catalog.resolve(sma, Map.of("source", "ind:missing:value"), Set.of(), "a", "1d"))
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> catalog.resolve(sma, Map.of("timeframe", "1wk"), Set.of(), "a", "1mo"))
                .hasMessageContaining("shorter");
        assertThat(catalog.resolve(sma, Map.of("timeframe", "1wk"), Set.of(), "a", "1wk").text("timeframe")).isEqualTo("chart");
        IndicatorDefinition compare = catalog.find("COMPARE").orElseThrow();
        assertThatThrownBy(() -> catalog.resolve(compare, Map.of("symbol", "<script>"), Set.of(), "a", "1d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(compare.inputs()).anyMatch(input -> input.kind() == InputKind.SYMBOL);
    }

    @Test
    void formulasParseSafelyAndEvaluateLikeTheirBuiltInEquivalents() {
        assertThatThrownBy(() -> FormulaIndicator.parse("close + ")).hasMessageContaining("position");
        assertThatThrownBy(() -> FormulaIndicator.parse("system(1)")).hasMessageContaining("Unknown function");
        assertThatThrownBy(() -> FormulaIndicator.parse("sma(close, x)")).hasMessageContaining("whole number");
        assertThatThrownBy(() -> FormulaIndicator.parse("java.lang.Runtime")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaIndicator.parse("close".repeat(60))).hasMessageContaining("at most");
        CandleRepository repository = repository(candles("AAPL", "1d", 120, 0));
        var view = new ChartTechnicalIndicatorService(repository).calculate("AAPL", "1d", new IndicatorBatchRequest(null, null, List.of(
                new IndicatorConfiguration("f", "FORMULA", Map.of("expression", "sma(close, 10) - sma(close, 20) + close[1] * 0")),
                new IndicatorConfiguration("fast", "SMA", Map.of("period", 10)),
                new IndicatorConfiguration("slow", "SMA", Map.of("period", 20))))).indicators();
        var formula = view.getFirst().series().getFirst().points();
        double fast = view.get(1).series().getFirst().points().getLast().value();
        double slow = view.get(2).series().getFirst().points().getLast().value();
        assertThat(formula.getLast().value()).isCloseTo(fast - slow, within(1e-9));
        assertThat(FormulaIndicator.parse("close > open ? high : low[2]")).isNotNull();
    }

    @Test
    void comparisonIndicatorsReportAMissingSymbolWithoutFailingTheBatch() {
        CandleRepository repository = repository(candles("AAPL", "1d", 120, 0));
        var views = new ChartTechnicalIndicatorService(repository).calculate("AAPL", "1d", new IndicatorBatchRequest(null, null, List.of(
                new IndicatorConfiguration("cmp", "COMPARE", Map.of("symbol", "MSFT")),
                new IndicatorConfiguration("ema", "EMA", Map.of())))).indicators();
        assertThat(views.getFirst().error()).contains("MSFT");
        assertThat(views.get(1).series().getFirst().points()).isNotEmpty();
    }

    private static long day(int index) {
        return LocalDate.of(2020, 1, 6).plusDays(index).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
    }

    /**
     * With -Ddesign.preview.capture=true, writes the catalog as the endpoint serves it, for the stock chart's
     * browser check (scripts/check_stock_chart_ui.cjs).
     */
    @Test
    void capturesTheCatalogForTheBrowserCheckWhenAsked() throws java.io.IOException {
        if (!Boolean.getBoolean("design.preview.capture")) return;
        java.nio.file.Path directory = java.nio.file.Path.of(System.getProperty("design.preview.directory", "target/design-preview"));
        java.nio.file.Files.createDirectories(directory);
        var view = new org.example.stockwatch247.controller.ChartIndicatorCatalogController.CatalogView(
                catalog.all(), FormulaIndicator.functions(), FormulaIndicator.series());
        java.nio.file.Files.writeString(directory.resolve("chart-indicator-catalog.json"),
                new tools.jackson.databind.ObjectMapper().writeValueAsString(view));
    }

    /** The indicator list, settings dialog and legend label everything from the bundles, in both languages. */
    @Test
    void everyIndicatorInputOutputAndChoiceHasEnglishAndPolishText() throws java.io.IOException {
        for (String bundle : List.of("messages.properties", "messages_pl.properties")) {
            java.util.Properties messages = new java.util.Properties();
            try (var reader = new java.io.InputStreamReader(getClass().getClassLoader().getResourceAsStream(bundle),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
            Set<String> missing = new java.util.TreeSet<>();
            for (IndicatorDefinition definition : catalog.all()) {
                String type = definition.type();
                for (String key : List.of("name", "description")) {
                    if (!messages.containsKey("js.ind." + type + "." + key)) missing.add("js.ind." + type + "." + key);
                }
                if (!messages.containsKey("js.ind.category." + definition.category())) missing.add("js.ind.category." + definition.category());
                for (IndicatorDefinition.InputSpec input : definition.inputs()) {
                    if (!messages.containsKey("js.ind." + type + ".input." + input.key()) && !messages.containsKey("js.ind.input." + input.key())) {
                        missing.add("js.ind.input." + input.key());
                    }
                    if (input.options() == null) continue;
                    String set = input.choiceSet() == null ? input.key() : input.choiceSet();
                    for (String option : input.options()) {
                        if (!messages.containsKey("js.ind.choice." + set + "." + option)) missing.add("js.ind.choice." + set + "." + option);
                    }
                }
                for (OutputSpec output : definition.outputs()) {
                    if (!messages.containsKey("js.ind." + type + ".output." + output.key()) && !messages.containsKey("js.ind.output." + output.key())) {
                        missing.add("js.ind.output." + output.key());
                    }
                }
            }
            assertThat(missing).as("indicator text missing from " + bundle).isEmpty();
        }
    }

    /** Weekday bars with a trend, a cycle and varying volume. */
    private static List<Candle> candles(String symbol, String interval, int count, int phase) {
        List<Candle> result = new ArrayList<>();
        int bar = 0;
        for (int index = 0; result.size() < count; index++) {
            LocalDate date = LocalDate.of(2020, 1, 6).plusDays(index);
            if (date.getDayOfWeek().getValue() > 5) continue;
            double trend = 80 + bar * 0.21;
            double wave = Math.sin((bar + phase) / 6.0) * 5 + Math.sin(bar / 23.0) * 9;
            double close = trend + wave;
            double open = close - Math.cos((bar + phase) / 3.0) * 1.5;
            result.add(new Candle(symbol, interval, date.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond(),
                    open, Math.max(open, close) + 1.2 + (bar % 5) * 0.3, Math.min(open, close) - 1.1 - (bar % 7) * 0.2,
                    close, 1_000_000L + (bar % 11) * 70_000L));
            bar++;
        }
        return result;
    }

    @SafeVarargs
    private static CandleRepository repository(List<Candle>... sets) {
        CandleRepository repository = mock(CandleRepository.class);
        when(repository.findBySymbolAndTimeIntervalOrderByTimestampAsc(anyString(), anyString())).thenReturn(List.of());
        when(repository.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc(anyString(), anyString(), anyLong()))
                .thenReturn(List.of());
        for (List<Candle> set : sets) {
            String symbol = set.getFirst().getSymbol();
            String interval = set.getFirst().getTimeInterval();
            when(repository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval)).thenReturn(set);
            when(repository.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc(eq(symbol), eq(interval), anyLong()))
                    .thenAnswer(invocation -> {
                        long to = invocation.getArgument(2);
                        return set.stream().filter(candle -> candle.getTimestamp() <= to).toList();
                    });
        }
        return repository;
    }
}
