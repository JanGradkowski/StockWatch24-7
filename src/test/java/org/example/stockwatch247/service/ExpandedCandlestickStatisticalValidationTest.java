package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestOutcome;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestReport;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestSettings;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "backtest.expanded.enabled", matches = "true")
class ExpandedCandlestickStatisticalValidationTest {
    private static final String MANIFEST_RESOURCE =
            "/backtest/expanded-candlestick-universe.tsv";
    private static final String POWER_MANIFEST_SHA256 =
            "8364BE9B75B3D91A3377FB128195849CAD080189FE928243AC92F6CE573F160F";
    private static final Path DEFAULT_DATA_FILE =
            Path.of("target/expanded-backtest-data/expanded-candles.csv.gz");
    private static final Path OUTPUT_DIRECTORY =
            Path.of("target/expanded-backtest-data");
    private static final int HIGH_CONFIDENCE_SCORE = 85;
    private static final int ACTIONABLE_SAMPLE_TARGET = 400;
    private static final int FIRST_STUDY_YEAR = 2003;
    private static final int LAST_STUDY_YEAR = 2018;
    private static final double WILSON_Z_95 = 1.959963984540054;

    private static final Map<String, String> FROZEN_SOURCE_HASHES = Map.of(
            "src/main/java/org/example/stockwatch247/service/CandlePatternDetectionService.java",
            "E1F2EF86A9CE23C464F9601AF2EE976A9D569CECFA1E4DBE4AB2CC0FEA25D49B",
            "src/main/java/org/example/stockwatch247/service/TechnicalIndicatorEnrichmentService.java",
            "3087613C08DD25D14B62AE27C9FAC76CFC3604BF4AA18C743693736C1CBF4005",
            "src/main/java/org/example/stockwatch247/service/HistoricalSignalBacktestService.java",
            "0122A12997695648EE3BE44CC90F407E2212D87833777E3E1E2F56E29E6CA13F",
            "src/main/java/org/example/stockwatch247/service/CandlestickPatternCalibration.java",
            "738D04485D8E80936884F8F49604664A9CC6260701EB3FABE88CD01171141C68"
    );
    private static final String SCORE_MODEL_FILE_LABEL = "v4-rollback";

    private static final List<IntervalRun> RUNS = List.of(
            new IntervalRun(
                    "1d",
                    "Daily",
                    Aggregation.DAILY,
                    new BacktestSettings(250, 100, 10, 3.0)
            ),
            new IntervalRun(
                    "1wk",
                    "Weekly",
                    Aggregation.WEEKLY,
                    new BacktestSettings(80, 80, 8, 8.0)
            ),
            new IntervalRun(
                    "1mo",
                    "Monthly",
                    Aggregation.MONTHLY,
                    new BacktestSettings(36, 80, 6, 12.0)
            )
    );

    private final HistoricalSignalBacktestService backtestService =
            new HistoricalSignalBacktestService(
                    new TechnicalIndicatorEnrichmentService(),
                    new CandlePatternDetectionService()
            );

    @Test
    void runsFrozenExpandedValidation() throws Exception {
        verifyFrozenProductionSources();
        List<UniverseEntry> fullManifest = loadManifest();
        validateManifest(fullManifest);

        StudyStage stage = StudyStage.fromProperty(
                System.getProperty("backtest.expanded.stage", "development")
        );
        List<UniverseEntry> studyUniverse = fullManifest.stream()
                .filter(stage::includes)
                .toList();
        Path dataFile = Path.of(System.getProperty(
                "backtest.expanded.data-file",
                DEFAULT_DATA_FILE.toString()
        ));
        Map<String, List<Candle>> dailyBySymbol =
                loadAdjustedDailyCandles(dataFile, studyUniverse);

        System.out.printf(
                Locale.ROOT,
                "Expanded validation stage=%s symbols=%d dailyCandles=%,d bootstrapReplicates=%,d%n",
                stage.fileLabel(),
                studyUniverse.size(),
                dailyBySymbol.values().stream().mapToInt(List::size).sum(),
                bootstrapReplicates()
        );

        List<LabeledTrade> allTrades = new ArrayList<>();
        List<IntervalCoverage> coverage = new ArrayList<>();
        for (IntervalRun run : RUNS) {
            int intervalCandles = 0;
            int analyzedCandles = 0;
            int processedSymbols = 0;
            for (UniverseEntry entry : studyUniverse) {
                List<Candle> candles = aggregate(
                        dailyBySymbol.get(entry.symbol()),
                        run.aggregation()
                );
                intervalCandles += candles.size();
                BacktestReport report = backtestService.backtest(
                        candles,
                        run.settings(),
                        false
                );
                analyzedCandles += report.analyzedCandles();
                report.trades().forEach(trade ->
                        allTrades.add(LabeledTrade.from(run.interval(), entry, trade)));
                processedSymbols++;
                if (processedSymbols % 10 == 0 || processedSymbols == studyUniverse.size()) {
                    System.out.printf(
                            Locale.ROOT,
                            "  %s: processed %d/%d symbols, candles=%,d%n",
                            run.label(),
                            processedSymbols,
                            studyUniverse.size(),
                            intervalCandles
                    );
                }
            }
            int rawSignals = (int) allTrades.stream()
                    .filter(trade -> trade.interval().equals(run.interval()))
                    .count();
            coverage.add(new IntervalCoverage(
                    run,
                    studyUniverse.size(),
                    intervalCandles,
                    analyzedCandles,
                    rawSignals
            ));
        }

        Files.createDirectories(OUTPUT_DIRECTORY);
        Path tradesFile = OUTPUT_DIRECTORY.resolve(
                "expanded-candlestick-trades-" + stage.fileLabel()
                        + "-" + SCORE_MODEL_FILE_LABEL + ".csv"
        );
        writeTrades(tradesFile, allTrades);

        String report = buildReport(stage, studyUniverse, coverage, allTrades);
        Path reportFile = OUTPUT_DIRECTORY.resolve(
                "expanded-candlestick-summary-" + stage.fileLabel()
                        + "-" + SCORE_MODEL_FILE_LABEL + ".md"
        );
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Trade output: " + tradesFile);
        System.out.println("Summary output: " + reportFile);
    }

    private void verifyFrozenProductionSources() throws IOException {
        for (Map.Entry<String, String> entry : FROZEN_SOURCE_HASHES.entrySet()) {
            Path path = Path.of(entry.getKey());
            assertTrue(Files.isRegularFile(path), "Frozen source is missing: " + path);
            String normalizedSource = Files.readString(path, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            assertEquals(
                    entry.getValue(),
                    sha256(normalizedSource.getBytes(StandardCharsets.UTF_8)),
                    "Production research dependency changed; freeze a new version and rerun the study: " + path
            );
        }
    }

    private List<UniverseEntry> loadManifest() throws IOException {
        String externalPath = System.getProperty("backtest.expanded.manifest-file", "").trim();
        if (!externalPath.isEmpty()) {
            Path path = Path.of(externalPath);
            assertTrue(Files.isRegularFile(path), "External manifest is missing: " + path);
            assertEquals(
                    POWER_MANIFEST_SHA256,
                    sha256(Files.readAllBytes(path)),
                    "The power-validation manifest is not the frozen pre-outcome universe."
            );
            try (InputStream stream = Files.newInputStream(path)) {
                return parseManifest(stream);
            }
        }
        InputStream stream = getClass().getResourceAsStream(MANIFEST_RESOURCE);
        if (stream == null) {
            throw new IOException("Missing frozen universe resource " + MANIFEST_RESOURCE);
        }
        try (stream) {
            return parseManifest(stream);
        }
    }

    private List<UniverseEntry> parseManifest(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String header = reader.readLine();
            assertEquals(
                    "symbol\tname\tsector\tcap_tier\tcohort\trole\tfirst_date\tlast_date\tdaily_candles\tmarket_cap_usd",
                    header
            );
            List<UniverseEntry> entries = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split("\t", -1);
                assertEquals(10, values.length, "Malformed manifest row: " + line);
                entries.add(new UniverseEntry(
                        values[0],
                        values[1],
                        values[2],
                        values[3],
                        values[4],
                        StudyRole.valueOf(values[5]),
                        LocalDate.parse(values[6]),
                        LocalDate.parse(values[7]),
                        Integer.parseInt(values[8]),
                        values[9].isBlank() ? null : Long.parseLong(values[9])
                ));
            }
            return List.copyOf(entries);
        }
    }

    private void validateManifest(List<UniverseEntry> manifest) throws IOException {
        if (manifest.size() == 2_193) {
            validatePowerManifest(manifest);
            return;
        }
        validateCoreManifest(manifest);
    }

    private void validateCoreManifest(List<UniverseEntry> manifest) {
        assertEquals(150, manifest.size());
        assertEquals(150, manifest.stream().map(UniverseEntry::symbol).distinct().count());
        assertEquals(120, count(manifest, entry -> entry.role() == StudyRole.DEVELOPMENT));
        assertEquals(30, count(manifest, entry -> entry.role() == StudyRole.HOLDOUT));
        assertEquals(40, count(manifest, entry -> entry.capTier().equals("LARGE")));
        assertEquals(40, count(manifest, entry -> entry.capTier().equals("MID")));
        assertEquals(40, count(manifest, entry -> entry.capTier().equals("SMALL")));
        assertEquals(30, count(manifest, entry -> entry.capTier().equals("FORMER_SP500")));
        assertEquals(549_920, manifest.stream().mapToInt(UniverseEntry::dailyCandles).sum());

        Set<String> activeSectors = manifest.stream()
                .filter(entry -> !entry.capTier().equals("FORMER_SP500"))
                .map(UniverseEntry::sector)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "Basic Materials",
                "Communication Services",
                "Consumer Cyclical",
                "Consumer Defensive",
                "Energy",
                "Financial Services",
                "Healthcare",
                "Industrials",
                "Real Estate",
                "Technology",
                "Utilities"
        ), activeSectors);
    }

    private void validatePowerManifest(List<UniverseEntry> manifest) throws IOException {
        assertEquals(2_193, manifest.size());
        assertEquals(2_193, manifest.stream().map(UniverseEntry::symbol).distinct().count());
        assertEquals(7_802_979, manifest.stream().mapToInt(UniverseEntry::dailyCandles).sum());
        assertEquals(400, count(manifest, entry ->
                entry.cohort().equals("ENDED_BEFORE_2018")));
        assertTrue(manifest.stream().allMatch(entry ->
                entry.role() == StudyRole.VALIDATION));
        assertTrue(manifest.stream().allMatch(entry ->
                entry.capTier().equals("POWER")));

        InputStream coreStream = getClass().getResourceAsStream(MANIFEST_RESOURCE);
        if (coreStream == null) {
            throw new IOException("Missing frozen core universe " + MANIFEST_RESOURCE);
        }
        Set<String> coreSymbols;
        try (coreStream) {
            coreSymbols = parseManifest(coreStream).stream()
                    .map(UniverseEntry::symbol)
                    .collect(java.util.stream.Collectors.toSet());
        }
        assertTrue(manifest.stream().noneMatch(entry ->
                coreSymbols.contains(entry.symbol())));
    }

    private Map<String, List<Candle>> loadAdjustedDailyCandles(
            Path dataFile,
            List<UniverseEntry> universe
    ) throws IOException {
        assertTrue(Files.isRegularFile(dataFile), () ->
                "Prepared data is missing: " + dataFile
                        + ". Run scripts/prepare_expanded_candlestick_backtest.py first.");
        Map<String, UniverseEntry> bySymbol = universe.stream()
                .collect(java.util.stream.Collectors.toMap(
                        UniverseEntry::symbol,
                        entry -> entry
                ));
        Map<String, List<Candle>> candles = new LinkedHashMap<>();
        universe.forEach(entry -> candles.put(entry.symbol(), new ArrayList<>()));

        try (InputStream fileInput = Files.newInputStream(dataFile);
             InputStream dataInput = dataFile.toString().endsWith(".gz")
                     ? new GZIPInputStream(fileInput)
                     : fileInput;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(dataInput, StandardCharsets.UTF_8)
             )) {
            assertEquals("ticker,date,open,high,low,close,volume", reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);
                assertEquals(7, values.length, "Malformed adjusted candle row.");
                UniverseEntry entry = bySymbol.get(values[0]);
                if (entry == null) {
                    continue;
                }
                LocalDate date = LocalDate.parse(values[1]);
                double open = Double.parseDouble(values[2]);
                double high = Double.parseDouble(values[3]);
                double low = Double.parseDouble(values[4]);
                double close = Double.parseDouble(values[5]);
                long volume = Long.parseLong(values[6]);
                assertTrue(open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0);
                assertTrue(high + 1e-9 >= Math.max(open, close));
                assertTrue(low - 1e-9 <= Math.min(open, close));
                candles.get(entry.symbol()).add(new Candle(
                        entry.symbol(),
                        "1d",
                        date.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                        open,
                        high,
                        low,
                        close,
                        volume
                ));
            }
        }

        for (UniverseEntry entry : universe) {
            List<Candle> symbolCandles = candles.get(entry.symbol());
            symbolCandles.sort(Comparator.comparing(Candle::getTimestamp));
            assertEquals(
                    entry.dailyCandles(),
                    symbolCandles.size(),
                    "Prepared data drift for " + entry.symbol()
            );
            assertEquals(entry.firstDate(), utcDate(symbolCandles.getFirst().getTimestamp()));
            assertEquals(entry.lastDate(), utcDate(symbolCandles.getLast().getTimestamp()));
            assertEquals(
                    symbolCandles.size(),
                    symbolCandles.stream().map(Candle::getTimestamp).distinct().count(),
                    "Duplicate dates for " + entry.symbol()
            );
        }
        return Map.copyOf(candles);
    }

    private List<Candle> aggregate(List<Candle> daily, Aggregation aggregation) {
        if (aggregation == Aggregation.DAILY) {
            return daily;
        }
        Map<Object, MutableBar> bars = new LinkedHashMap<>();
        for (Candle candle : daily) {
            LocalDate date = utcDate(candle.getTimestamp());
            Object key = aggregation == Aggregation.WEEKLY
                    ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : YearMonth.from(date);
            bars.computeIfAbsent(key, ignored -> new MutableBar(
                    candle.getSymbol(),
                    aggregation.interval(),
                    candle
            )).add(candle);
        }
        return bars.values().stream().map(MutableBar::toCandle).toList();
    }

    private String buildReport(
            StudyStage stage,
            List<UniverseEntry> universe,
            List<IntervalCoverage> coverage,
            List<LabeledTrade> allTrades
    ) {
        StringBuilder output = new StringBuilder();
        output.append("# Expanded candlestick statistical validation\n\n");
        output.append(String.format(
                Locale.ROOT,
                "Stage: `%s`; symbols: %,d; adjusted daily source candles: %,d; "
                        + "score threshold frozen at `%d`; bootstrap replicates: %,d.%n%n",
                stage.fileLabel(),
                universe.size(),
                universe.stream().mapToInt(UniverseEntry::dailyCandles).sum(),
                HIGH_CONFIDENCE_SCORE,
                bootstrapReplicates()
        ));
        output.append("Precision is success / (success + failure); inconclusive outcomes are excluded. ");
        output.append("The cluster interval is a deterministic two-way symbol/year bootstrap. ");
        output.append("Daily, weekly, and monthly rows are separate analyses and must not be added together.\n\n");
        output.append("| Interval | Horizon / move | Symbols | Candles | Analyzed | Raw signals |\n");
        output.append("|---|---:|---:|---:|---:|---:|\n");
        for (IntervalCoverage item : coverage) {
            output.append(String.format(
                    Locale.ROOT,
                    "| %s | %d candles / %.1f%% | %,d | %,d | %,d | %,d |%n",
                    item.run().label(),
                    item.run().settings().forwardCandles(),
                    item.run().settings().minimumMovePercent(),
                    item.symbols(),
                    item.candles(),
                    item.analyzedCandles(),
                    item.rawSignals()
            ));
        }

        output.append("\n## Primary 85+ results\n\n");
        output.append("| Interval | Direction | Signals | Success | Failure | Inconclusive | "
                + "Actionable | Precision | Wilson 95% CI | Cluster 95% CI | "
                + "Cluster MOE | Avg return | 400 target |\n");
        output.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (IntervalRun run : RUNS) {
            List<LabeledTrade> intervalTrades = allTrades.stream()
                    .filter(trade -> trade.interval().equals(run.interval()))
                    .toList();
            appendStatsRow(output, run, "ALL", intervalTrades, universe, trade ->
                    trade.confidenceScore() >= HIGH_CONFIDENCE_SCORE);
            appendStatsRow(output, run, "BUY", intervalTrades, universe, trade ->
                    trade.confidenceScore() >= HIGH_CONFIDENCE_SCORE
                            && trade.tradeSignal() == TradeSignal.BUY);
            appendStatsRow(output, run, "SELL", intervalTrades, universe, trade ->
                    trade.confidenceScore() >= HIGH_CONFIDENCE_SCORE
                            && trade.tradeSignal() == TradeSignal.SELL);
        }

        output.append("\n## Threshold context\n\n");
        output.append("| Interval | Score group | Signals | Actionable | Precision | "
                + "Wilson 95% CI | Avg return |\n");
        output.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (IntervalRun run : RUNS) {
            List<LabeledTrade> intervalTrades = allTrades.stream()
                    .filter(trade -> trade.interval().equals(run.interval()))
                    .toList();
            appendContextRow(output, run, "All detected", intervalTrades, ignored -> true);
            appendContextRow(output, run, "Below 75", intervalTrades,
                    trade -> trade.confidenceScore() < 75);
            appendContextRow(output, run, "75-84", intervalTrades,
                    trade -> trade.confidenceScore() >= 75
                            && trade.confidenceScore() < 85);
            appendContextRow(output, run, "85+", intervalTrades,
                    trade -> trade.confidenceScore() >= 85);
        }
        return output.toString();
    }

    private void appendStatsRow(
            StringBuilder output,
            IntervalRun run,
            String direction,
            List<LabeledTrade> intervalTrades,
            List<UniverseEntry> universe,
            Predicate<LabeledTrade> filter
    ) {
        List<LabeledTrade> selected = intervalTrades.stream().filter(filter).toList();
        PrecisionStats stats = PrecisionStats.from(
                selected,
                universe,
                bootstrapReplicates(),
                stableSeed(run.interval() + "|" + direction + "|85+")
        );
        output.append(String.format(
                Locale.ROOT,
                "| %s | %s | %,d | %,d | %,d | %,d | %,d | %s | %s | %s | %s | %s | %s |%n",
                run.label(),
                direction,
                stats.signals(),
                stats.successes(),
                stats.failures(),
                stats.inconclusive(),
                stats.actionable(),
                formatPercent(stats.precision()),
                formatInterval(stats.wilsonLow(), stats.wilsonHigh()),
                formatInterval(stats.clusterLow(), stats.clusterHigh()),
                formatPercent(stats.clusterMarginOfError()),
                formatSignedPercent(stats.averageReturn()),
                stats.actionable() >= ACTIONABLE_SAMPLE_TARGET ? "MET" : "NOT MET"
        ));
    }

    private void appendContextRow(
            StringBuilder output,
            IntervalRun run,
            String scoreGroup,
            List<LabeledTrade> intervalTrades,
            Predicate<LabeledTrade> filter
    ) {
        List<LabeledTrade> selected = intervalTrades.stream().filter(filter).toList();
        DescriptiveStats stats = DescriptiveStats.from(selected);
        output.append(String.format(
                Locale.ROOT,
                "| %s | %s | %,d | %,d | %s | %s | %s |%n",
                run.label(),
                scoreGroup,
                stats.signals(),
                stats.actionable(),
                formatPercent(stats.precision()),
                formatInterval(stats.wilsonLow(), stats.wilsonHigh()),
                formatSignedPercent(stats.averageReturn())
        ));
    }

    private void writeTrades(Path output, List<LabeledTrade> trades) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("interval,symbol,role,sector,cap_tier,cohort,signal_date,exit_date,"
                    + "pattern,direction,score,outcome,directional_return,best_move,worst_move\n");
            for (LabeledTrade labeled : trades) {
                writer.write(String.join(
                        ",",
                        labeled.interval(),
                        labeled.entry().symbol(),
                        labeled.entry().role().name(),
                        csvValue(labeled.entry().sector()),
                        labeled.entry().capTier(),
                        labeled.entry().cohort(),
                        utcDate(labeled.signalTimestamp()).toString(),
                        utcDate(labeled.exitTimestamp()).toString(),
                        labeled.pattern().name(),
                        labeled.tradeSignal().name(),
                        Integer.toString(labeled.confidenceScore()),
                        labeled.outcome().name(),
                        decimal(labeled.directionalReturnPercent()),
                        decimal(labeled.bestDirectionalMovePercent()),
                        decimal(labeled.worstDirectionalMovePercent())
                ));
                writer.newLine();
            }
        }
    }

    private static int bootstrapReplicates() {
        return Integer.getInteger("backtest.expanded.bootstrap-replicates", 10_000);
    }

    private static long stableSeed(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        long seed = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            seed = (seed << 8) | (digest[index] & 0xffL);
        }
        return seed;
    }

    private static String sha256(byte[] input) {
        try {
            return java.util.HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String csvValue(String value) {
        if (!value.contains(",") && !value.contains("\"")) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.8f", value);
    }

    private static String formatPercent(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.2f%%", value);
    }

    private static String formatSignedPercent(double value) {
        return Double.isNaN(value)
                ? "n/a"
                : String.format(Locale.ROOT, "%+.2f%%", value);
    }

    private static String formatInterval(double low, double high) {
        return Double.isNaN(low) || Double.isNaN(high)
                ? "n/a"
                : String.format(Locale.ROOT, "%.2f%%–%.2f%%", low, high);
    }

    private static LocalDate utcDate(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static int count(List<UniverseEntry> entries, Predicate<UniverseEntry> predicate) {
        return (int) entries.stream().filter(predicate).count();
    }

    private enum Aggregation {
        DAILY("1d"),
        WEEKLY("1wk"),
        MONTHLY("1mo");

        private final String interval;

        Aggregation(String interval) {
            this.interval = interval;
        }

        private String interval() {
            return interval;
        }
    }

    private enum StudyRole {
        DEVELOPMENT,
        HOLDOUT,
        VALIDATION
    }

    private enum StudyStage {
        DEVELOPMENT,
        HOLDOUT,
        VALIDATION,
        ALL;

        private static StudyStage fromProperty(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "development" -> DEVELOPMENT;
                case "holdout" -> HOLDOUT;
                case "validation", "power" -> VALIDATION;
                case "all", "combined" -> ALL;
                default -> throw new IllegalArgumentException(
                        "backtest.expanded.stage must be development, holdout, validation, or all."
                );
            };
        }

        private boolean includes(UniverseEntry entry) {
            return this == ALL || entry.role().name().equals(name());
        }

        private String fileLabel() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record IntervalRun(
            String interval,
            String label,
            Aggregation aggregation,
            BacktestSettings settings
    ) {
    }

    private record IntervalCoverage(
            IntervalRun run,
            int symbols,
            int candles,
            int analyzedCandles,
            int rawSignals
    ) {
    }

    private record UniverseEntry(
            String symbol,
            String name,
            String sector,
            String capTier,
            String cohort,
            StudyRole role,
            LocalDate firstDate,
            LocalDate lastDate,
            int dailyCandles,
            Long marketCapUsd
    ) {
    }

    private record LabeledTrade(
            String interval,
            UniverseEntry entry,
            CandlePattern pattern,
            TradeSignal tradeSignal,
            int confidenceScore,
            BacktestOutcome outcome,
            long signalTimestamp,
            long exitTimestamp,
            double directionalReturnPercent,
            double bestDirectionalMovePercent,
            double worstDirectionalMovePercent
    ) {
        private static LabeledTrade from(
                String interval,
                UniverseEntry entry,
                BacktestTrade trade
        ) {
            return new LabeledTrade(
                    interval,
                    entry,
                    trade.pattern(),
                    trade.tradeSignal(),
                    trade.confidenceScore(),
                    trade.outcome(),
                    trade.signalTimestamp(),
                    trade.exitTimestamp(),
                    trade.directionalReturnPercent(),
                    trade.bestDirectionalMovePercent(),
                    trade.worstDirectionalMovePercent()
            );
        }
    }

    private static final class MutableBar {
        private final String symbol;
        private final String interval;
        private long timestamp;
        private final double open;
        private double high;
        private double low;
        private double close;
        private long volume;

        private MutableBar(String symbol, String interval, Candle first) {
            this.symbol = symbol;
            this.interval = interval;
            this.timestamp = first.getTimestamp();
            this.open = first.getOpenPrice();
            this.high = first.getHighPrice();
            this.low = first.getLowPrice();
            this.close = first.getClosePrice();
            this.volume = first.getVolume() == null ? 0L : first.getVolume();
        }

        private void add(Candle candle) {
            if (candle.getTimestamp() == timestamp) {
                return;
            }
            timestamp = candle.getTimestamp();
            high = Math.max(high, candle.getHighPrice());
            low = Math.min(low, candle.getLowPrice());
            close = candle.getClosePrice();
            long nextVolume = candle.getVolume() == null ? 0L : candle.getVolume();
            volume = Long.MAX_VALUE - volume < nextVolume ? Long.MAX_VALUE : volume + nextVolume;
        }

        private Candle toCandle() {
            return new Candle(
                    symbol,
                    interval,
                    timestamp,
                    open,
                    high,
                    low,
                    close,
                    volume
            );
        }
    }

    private record DescriptiveStats(
            int signals,
            int successes,
            int failures,
            int inconclusive,
            int actionable,
            double precision,
            double wilsonLow,
            double wilsonHigh,
            double averageReturn
    ) {
        private static DescriptiveStats from(List<LabeledTrade> trades) {
            int successes = countOutcome(trades, BacktestOutcome.SUCCESS);
            int failures = countOutcome(trades, BacktestOutcome.FAILURE);
            int inconclusive = countOutcome(trades, BacktestOutcome.INCONCLUSIVE);
            int actionable = successes + failures;
            double precision = percentage(successes, actionable);
            double[] wilson = wilson(successes, actionable);
            double averageReturn = trades.stream()
                    .mapToDouble(LabeledTrade::directionalReturnPercent)
                    .average()
                    .orElse(Double.NaN);
            return new DescriptiveStats(
                    trades.size(),
                    successes,
                    failures,
                    inconclusive,
                    actionable,
                    precision,
                    wilson[0],
                    wilson[1],
                    averageReturn
            );
        }
    }

    private record PrecisionStats(
            int signals,
            int successes,
            int failures,
            int inconclusive,
            int actionable,
            double precision,
            double wilsonLow,
            double wilsonHigh,
            double clusterLow,
            double clusterHigh,
            double clusterMarginOfError,
            double averageReturn
    ) {
        private static PrecisionStats from(
                List<LabeledTrade> trades,
                List<UniverseEntry> universe,
                int replicates,
                long seed
        ) {
            DescriptiveStats descriptive = DescriptiveStats.from(trades);
            // A two-way symbol/year bootstrap is not interpretable when only a
            // handful of actionable outcomes occupy one or two cluster cells.
            // Preserve the raw and Wilson statistics, but report the clustered
            // interval as unavailable instead of failing the complete study.
            double[] cluster = descriptive.actionable() < 30
                    ? new double[]{Double.NaN, Double.NaN}
                    : clusterBootstrap(trades, universe, replicates, seed);
            double margin = Double.isNaN(cluster[0])
                    ? Double.NaN
                    : Math.max(
                            descriptive.precision() - cluster[0],
                            cluster[1] - descriptive.precision()
                    );
            return new PrecisionStats(
                    descriptive.signals(),
                    descriptive.successes(),
                    descriptive.failures(),
                    descriptive.inconclusive(),
                    descriptive.actionable(),
                    descriptive.precision(),
                    descriptive.wilsonLow(),
                    descriptive.wilsonHigh(),
                    cluster[0],
                    cluster[1],
                    margin,
                    descriptive.averageReturn()
            );
        }
    }

    private static int countOutcome(
            List<LabeledTrade> trades,
            BacktestOutcome outcome
    ) {
        return (int) trades.stream()
                .filter(trade -> trade.outcome() == outcome)
                .count();
    }

    private static double percentage(int numerator, int denominator) {
        return denominator == 0 ? Double.NaN : numerator * 100.0 / denominator;
    }

    private static double[] wilson(int successes, int observations) {
        if (observations == 0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double p = (double) successes / observations;
        double zSquared = WILSON_Z_95 * WILSON_Z_95;
        double denominator = 1.0 + zSquared / observations;
        double center = (p + zSquared / (2.0 * observations)) / denominator;
        double halfWidth = WILSON_Z_95
                * Math.sqrt(
                p * (1.0 - p) / observations
                        + zSquared / (4.0 * observations * observations)
        ) / denominator;
        return new double[]{
                Math.max(0.0, center - halfWidth) * 100.0,
                Math.min(1.0, center + halfWidth) * 100.0
        };
    }

    private static double[] clusterBootstrap(
            List<LabeledTrade> trades,
            List<UniverseEntry> universe,
            int replicates,
            long seed
    ) {
        if (trades.stream().noneMatch(trade ->
                trade.outcome() != BacktestOutcome.INCONCLUSIVE)) {
            return new double[]{Double.NaN, Double.NaN};
        }
        if (replicates < 1_000) {
            throw new IllegalArgumentException(
                    "At least 1,000 bootstrap replicates are required."
            );
        }

        Map<String, Integer> symbolIndex = new HashMap<>();
        for (int index = 0; index < universe.size(); index++) {
            symbolIndex.put(universe.get(index).symbol(), index);
        }
        int yearCount = LAST_STUDY_YEAR - FIRST_STUDY_YEAR + 1;
        int[][] successes = new int[universe.size()][yearCount];
        int[][] failures = new int[universe.size()][yearCount];
        for (LabeledTrade labeled : trades) {
            BacktestOutcome outcome = labeled.outcome();
            if (outcome == BacktestOutcome.INCONCLUSIVE) {
                continue;
            }
            int symbol = symbolIndex.get(labeled.entry().symbol());
            int year = utcDate(labeled.signalTimestamp()).getYear() - FIRST_STUDY_YEAR;
            if (outcome == BacktestOutcome.SUCCESS) {
                successes[symbol][year]++;
            } else {
                failures[symbol][year]++;
            }
        }
        List<OutcomeCell> cells = new ArrayList<>();
        for (int symbol = 0; symbol < universe.size(); symbol++) {
            for (int year = 0; year < yearCount; year++) {
                if (successes[symbol][year] + failures[symbol][year] > 0) {
                    cells.add(new OutcomeCell(
                            symbol,
                            year,
                            successes[symbol][year],
                            failures[symbol][year]
                    ));
                }
            }
        }

        Random random = new Random(seed);
        int[] symbolWeights = new int[universe.size()];
        int[] yearWeights = new int[yearCount];
        double[] estimates = new double[replicates];
        int valid = 0;
        for (int replicate = 0; replicate < replicates; replicate++) {
            Arrays.fill(symbolWeights, 0);
            Arrays.fill(yearWeights, 0);
            for (int draw = 0; draw < universe.size(); draw++) {
                symbolWeights[random.nextInt(universe.size())]++;
            }
            for (int draw = 0; draw < yearCount; draw++) {
                yearWeights[random.nextInt(yearCount)]++;
            }

            long success = 0L;
            long failure = 0L;
            for (OutcomeCell cell : cells) {
                if (symbolWeights[cell.symbol()] == 0
                        || yearWeights[cell.year()] == 0) {
                    continue;
                }
                long weight = (long) symbolWeights[cell.symbol()]
                        * yearWeights[cell.year()];
                success += cell.successes() * weight;
                failure += cell.failures() * weight;
            }
            if (success + failure > 0L) {
                estimates[valid++] = success * 100.0 / (success + failure);
            }
        }
        assertTrue(valid >= replicates * 0.95, "Too many empty cluster replicates.");
        Arrays.sort(estimates, 0, valid);
        return new double[]{
                percentile(estimates, valid, 0.025),
                percentile(estimates, valid, 0.975)
        };
    }

    private record OutcomeCell(
            int symbol,
            int year,
            int successes,
            int failures
    ) {
    }

    private static double percentile(
            double[] sorted,
            int length,
            double probability
    ) {
        assertFalse(length == 0);
        double position = probability * (length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }
}
