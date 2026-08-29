package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicFormation;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicPivot;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.PivotType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in research audit comparing production classification with independently
 * transcribed published harmonic-ratio definitions on real OHLC data.
 *
 * <p>This is a recall/disagreement audit, not a claim of predictive validity or
 * manually adjudicated ground truth. Both classifiers receive the exact same
 * production-confirmed pivots, which isolates ratio/classification differences
 * from pivot-selection differences.</p>
 */
@EnabledIfSystemProperty(named = "backtest.harmonic.reference-recall.enabled", matches = "true")
class HarmonicPatternReferenceRecallResearchTest {
    private static final Path DEFAULT_DATA = Path.of(
            "target/expanded-backtest-data/post-2018-candles.csv.gz");
    private static final Path DEFAULT_REPORT = Path.of(
            "target/expanded-backtest-data/harmonic-reference-recall-report.md");
    private static final Path DEFAULT_CSV = Path.of(
            "target/expanded-backtest-data/harmonic-reference-recall-candidates.csv");
    private static final double REFERENCE_TOLERANCE = .03;
    private static final int MINIMUM_REFERENCE_FORMATIONS = 1_000;

    private final HarmonicPatternDetectionService production =
            new HarmonicPatternDetectionService(new HarmonicPatternDetectionService.Rules(
                    .03, .03, .10, .005, 2, 250));

    @Test
    void auditsAtLeastOneThousandReferenceLabelledRealMarketFormations() throws Exception {
        Path data = Path.of(System.getProperty(
                "backtest.harmonic.reference-recall.data-file", DEFAULT_DATA.toString()));
        Path report = Path.of(System.getProperty(
                "backtest.harmonic.reference-recall.report-file", DEFAULT_REPORT.toString()));
        Path csv = Path.of(System.getProperty(
                "backtest.harmonic.reference-recall.csv-file", DEFAULT_CSV.toString()));

        Audit audit = new Audit();
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.createDirectories(csv.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("symbol,interval,reference_pattern,direction,x_date,a_date,b_date,c_date,d_date,"
                    + "b_xa,c_ab,cd_bc,cd_ab,ad_xa,production_result,outcome\n");
            streamSymbols(data, candles -> evaluate(candles, audit, writer));
        }

        assertTrue(audit.totalReference >= MINIMUM_REFERENCE_FORMATIONS,
                "The audit must evaluate at least 1,000 independently reference-labelled formations.");
        Files.writeString(report, report(audit, data, csv), StandardCharsets.UTF_8);
        System.out.println(Files.readString(report));
        System.out.println("Harmonic recall report: " + report.toAbsolutePath());
        System.out.println("Harmonic candidate CSV: " + csv.toAbsolutePath());
    }

    private void evaluate(List<Candle> candles, Audit audit, BufferedWriter writer) {
        if (candles.size() < 10) return;
        audit.symbols++;
        audit.candles += candles.size();
        List<HarmonicPivot> pivots = production.confirmedPivots(candles);
        audit.pivots += pivots.size();
        List<HarmonicFormation> productionFormations = production.detectHistorical(candles);
        audit.addProductionFormations(productionFormations, candles);
        Map<String, HarmonicFormation> actualByCompletion = productionFormations.stream()
                .collect(Collectors.toMap(this::completionKey, Function.identity(),
                        (first, second) -> first.classificationError() <= second.classificationError()
                                ? first : second));
        for (int index = 4; index < pivots.size(); index++) {
            List<HarmonicPivot> geometry = pivots.subList(index - 4, index + 1);
            Ratios ratios = Ratios.from(geometry);
            List<HarmonicPatternType> labels = referenceLabels(geometry, ratios);
            if (labels.isEmpty()) continue;
            for (HarmonicPatternType expected : labels) {
                Optional<HarmonicFormation> actual = Optional.ofNullable(actualByCompletion.get(
                        completionKey(expected,
                                geometry.getFirst().type() == PivotType.LOW ? "BULLISH" : "BEARISH",
                                geometry.getLast().timestamp())));
                Outcome outcome = actual.isEmpty()
                        ? Outcome.MISSED
                        : actual.get().pattern() == expected ? Outcome.MATCHED : Outcome.MISCLASSIFIED;
                audit.add(expected, outcome, actual.map(HarmonicFormation::pattern).orElse(null));
                writeCandidate(writer, candles.getFirst().getSymbol(), expected, geometry, ratios, actual, outcome);
            }
        }
        if (audit.symbols % 100 == 0) {
            System.out.printf(Locale.ROOT,
                    "Harmonic reference audit processed %,d symbols and %,d labelled formations.%n",
                    audit.symbols, audit.totalReference);
        }
    }

    private String completionKey(HarmonicFormation formation) {
        return completionKey(formation.pattern(), formation.direction().name(),
                formation.points().getLast().timestamp());
    }

    private String completionKey(HarmonicPatternType pattern, String direction, long timestamp) {
        return pattern + ":" + direction + ":" + timestamp;
    }

    private List<HarmonicPatternType> referenceLabels(List<HarmonicPivot> p, Ratios r) {
        List<HarmonicPatternType> labels = new ArrayList<>();
        boolean bullish = p.getFirst().type() == PivotType.LOW;
        double x = p.get(0).price();
        double a = p.get(1).price();
        double b = p.get(2).price();
        double c = p.get(3).price();
        double d = p.get(4).price();
        boolean validDirection = bullish
                ? a > x && b < a && c > b && d < c
                : a < x && b > a && c < b && d > c;
        if (!validDirection) return List.of();
        boolean insideCompletion = bullish
                ? c <= a && d > x && d < a
                : c >= a && d < x && d > a;
        boolean outsideCompletion = bullish
                ? c <= a && d < x
                : c >= a && d > x;

        // Carney: precise .618 B (+/-3%), 1.13-1.618 BC, .786 XA,
        // and separate AB=CD / 1.27 alternate AB=CD targets.
        if (insideCompletion
                && near(r.bXa, .618)
                && within(r.cAb, .382, .886)
                && within(r.cdBc, 1.13, 1.618)
                && (near(r.cdAb, 1.0) || near(r.cdAb, 1.27))
                && near(r.adXa, .786)) {
            labels.add(HarmonicPatternType.GARTLEY);
        }
        // Published Bat identity: .382-.50 B and .886 XA completion.
        if (insideCompletion
                && within(r.bXa, .382, .50)
                && within(r.cAb, .382, .886)
                && within(r.cdBc, 1.618, 2.618)
                && (near(r.cdAb, 1.0) || near(r.cdAb, 1.27))
                && near(r.adXa, .886)) {
            labels.add(HarmonicPatternType.BAT);
        }
        // Carney Volume 3: .786 B, 1.618-2.24 BC and 1.27-1.41 XA.
        if (outsideCompletion
                && near(r.bXa, .786)
                && within(r.cAb, .382, .886)
                && within(r.cdBc, 1.618, 2.24)
                && (near(r.cdAb, 1.0) || near(r.cdAb, 1.27))
                && near(r.adXa, 1.27)) {
            labels.add(HarmonicPatternType.BUTTERFLY);
        }
        if (outsideCompletion
                && within(r.bXa, .382, .618)
                && within(r.cAb, .382, .886)
                && within(r.cdBc, 2.618, 3.618)
                && near(r.adXa, 1.618)) {
            labels.add(HarmonicPatternType.CRAB);
        }

        boolean cypherOrder = bullish ? c > a && d < b : c < a && d > b;
        if (cypherOrder
                && within(r.bXa, .382, .618)
                && r.xcXa >= 1.272 * (1.0 - REFERENCE_TOLERANCE)
                && r.xcXa <= 1.414
                && near(r.cdXc, .786)) {
            labels.add(HarmonicPatternType.CYPHER);
        }

        // Shark is labelled 0-X-A-B-C; these are the corresponding ratios.
        boolean sharkOrder = bullish
                ? c > a && d < b && d <= a
                : c < a && d > b && d >= a;
        if (sharkOrder
                && within(r.sharkA0x, .32, .618)
                && within(r.sharkAbXa, 1.13, 1.618)
                && within(r.sharkBcAb, 1.618, 2.24)
                && (near(r.sharkCompletion, .886) || near(r.sharkCompletion, 1.13))) {
            labels.add(HarmonicPatternType.SHARK);
        }
        return List.copyOf(labels);
    }

    private boolean near(double value, double target) {
        return value >= target * (1.0 - REFERENCE_TOLERANCE)
                && value <= target * (1.0 + REFERENCE_TOLERANCE);
    }

    private boolean within(double value, double minimum, double maximum) {
        return value >= minimum * (1.0 - REFERENCE_TOLERANCE)
                && value <= maximum * (1.0 + REFERENCE_TOLERANCE);
    }

    private void writeCandidate(BufferedWriter writer,
                                String symbol,
                                HarmonicPatternType expected,
                                List<HarmonicPivot> p,
                                Ratios r,
                                Optional<HarmonicFormation> actual,
                                Outcome outcome) {
        try {
            writer.write(String.format(Locale.ROOT,
                    "%s,1d,%s,%s,%s,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%s,%s%n",
                    csv(symbol), expected, p.getFirst().type() == PivotType.LOW ? "BULLISH" : "BEARISH",
                    date(p.get(0)), date(p.get(1)), date(p.get(2)), date(p.get(3)), date(p.get(4)),
                    r.bXa, r.cAb, r.cdBc, r.cdAb, r.adXa,
                    actual.map(value -> value.pattern().name()).orElse("NONE"), outcome));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write harmonic reference candidate.", exception);
        }
    }

    private String report(Audit audit, Path data, Path csv) {
        StringBuilder out = new StringBuilder();
        out.append("# Harmonic reference-recall audit\n\n")
                .append("## Scope and interpretation\n\n")
                .append("- Dataset: `").append(data).append("`\n")
                .append("- Symbols: ").append(String.format(Locale.ROOT, "%,d", audit.symbols)).append("\n")
                .append("- Daily candles: ").append(String.format(Locale.ROOT, "%,d", audit.candles)).append("\n")
                .append("- Production-confirmed pivots: ").append(String.format(Locale.ROOT, "%,d", audit.pivots)).append("\n")
                .append("- Independently reference-labelled formations: ")
                .append(String.format(Locale.ROOT, "%,d", audit.totalReference)).append("\n")
                .append("- All production formations discovered: ")
                .append(String.format(Locale.ROOT, "%,d", audit.productionFormations)).append("\n")
                .append("- Production formations spanning at least 80 candles: ")
                .append(String.format(Locale.ROOT, "%,d", audit.productionAtLeast80)).append("\n")
                .append("- Production formations spanning at least 160 candles: ")
                .append(String.format(Locale.ROOT, "%,d", audit.productionAtLeast160)).append("\n")
                .append("- Longest production formation: ")
                .append(String.format(Locale.ROOT, "%,d candles", audit.longestProductionSpan)).append("\n")
                .append("- Candidate-level CSV: `").append(csv).append("`\n\n")
                .append("The labels use independently transcribed published Fibonacci definitions with a 3% boundary tolerance. ")
                .append("They are systematic reference labels on real OHLC data, not 1,000 manually adjudicated chart screenshots. ")
                .append("Reference labels are created from independently transcribed rules over production-confirmed pivots, then matched against ")
                .append("the full production historical detector output. This covers classification, multi-scale scanning and completion deduplication, ")
                .append("but it does not measure formations that an entirely different pivot algorithm would select.\n\n")
                .append("## Results\n\n")
                .append("| Reference pattern | Reference labels | Exact matches | Wrong production type | No production match | Exact recall | Any-pattern recognition |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (HarmonicPatternType pattern : HarmonicPatternType.values()) {
            PatternStats stats = audit.byPattern.get(pattern);
            out.append(String.format(Locale.ROOT,
                    "| %s | %,d | %,d | %,d | %,d | %.2f%% | %.2f%% |%n",
                    pattern, stats.total, stats.matched, stats.misclassified, stats.missed,
                    percent(stats.matched, stats.total),
                    percent(stats.matched + stats.misclassified, stats.total)));
        }
        out.append(String.format(Locale.ROOT,
                "| **TOTAL** | **%,d** | **%,d** | **%,d** | **%,d** | **%.2f%%** | **%.2f%%** |%n%n",
                audit.totalReference, audit.matched, audit.misclassified, audit.missed,
                percent(audit.matched, audit.totalReference),
                percent(audit.matched + audit.misclassified, audit.totalReference)));
        out.append("## Production substitutions\n\n")
                .append("These counts show which production type won when the expected reference type did not.\n\n")
                .append("| Reference -> production | Count |\n|---|---:|\n");
        audit.substitutions.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .forEach(entry -> out.append(String.format(Locale.ROOT,
                        "| %s | %,d |%n", entry.getKey(), entry.getValue())));
        out.append("\n## Reproduction\n\n```powershell\n")
                .append(".\\mvnw.cmd '-Dtest=HarmonicPatternReferenceRecallResearchTest' ")
                .append("'-Dbacktest.harmonic.reference-recall.enabled=true' test\n```\n");
        return out.toString();
    }

    private double percent(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private String date(HarmonicPivot pivot) {
        return LocalDate.ofEpochDay(pivot.timestamp() / 86_400L).toString();
    }

    private String csv(String value) {
        return value.indexOf(',') < 0 && value.indexOf('"') < 0
                ? value : '"' + value.replace("\"", "\"\"") + '"';
    }

    private void streamSymbols(Path data, SymbolConsumer consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(data)), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!"ticker,date,open,high,low,close,volume".equals(header)) {
                throw new IllegalStateException("Unexpected harmonic audit CSV header: " + header);
            }
            String symbol = null;
            List<Candle> candles = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);
                if (values.length != 7) throw new IllegalStateException("Malformed candle row: " + line);
                if (symbol != null && !symbol.equals(values[0])) {
                    consumer.accept(List.copyOf(candles));
                    candles.clear();
                }
                symbol = values[0];
                candles.add(new Candle(symbol, "1d",
                        LocalDate.parse(values[1]).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                        Double.parseDouble(values[2]), Double.parseDouble(values[3]),
                        Double.parseDouble(values[4]), Double.parseDouble(values[5]),
                        Long.parseLong(values[6])));
            }
            if (!candles.isEmpty()) consumer.accept(List.copyOf(candles));
        }
    }

    private enum Outcome { MATCHED, MISCLASSIFIED, MISSED }

    private static final class Audit {
        private final Map<HarmonicPatternType, PatternStats> byPattern =
                new EnumMap<>(HarmonicPatternType.class);
        private final Map<String, Long> substitutions = new java.util.TreeMap<>();
        private long symbols;
        private long candles;
        private long pivots;
        private long totalReference;
        private long matched;
        private long misclassified;
        private long missed;
        private long productionFormations;
        private long productionAtLeast80;
        private long productionAtLeast160;
        private int longestProductionSpan;

        private Audit() {
            for (HarmonicPatternType pattern : HarmonicPatternType.values()) {
                byPattern.put(pattern, new PatternStats());
            }
        }

        private void add(HarmonicPatternType expected, Outcome outcome, HarmonicPatternType actual) {
            totalReference++;
            PatternStats stats = byPattern.get(expected);
            stats.total++;
            switch (outcome) {
                case MATCHED -> { matched++; stats.matched++; }
                case MISCLASSIFIED -> {
                    misclassified++;
                    stats.misclassified++;
                    substitutions.merge(expected + " -> " + actual, 1L, Long::sum);
                }
                case MISSED -> { missed++; stats.missed++; }
            }
        }

        private void addProductionFormations(List<HarmonicFormation> formations, List<Candle> candles) {
            Map<Long, Integer> indexes = new java.util.HashMap<>();
            for (int index = 0; index < candles.size(); index++) {
                indexes.put(candles.get(index).getTimestamp(), index);
            }
            for (HarmonicFormation formation : formations) {
                Integer first = indexes.get(formation.points().getFirst().timestamp());
                Integer last = indexes.get(formation.points().getLast().timestamp());
                if (first == null || last == null || last < first) continue;
                int span = last - first + 1;
                productionFormations++;
                if (span >= 80) productionAtLeast80++;
                if (span >= 160) productionAtLeast160++;
                longestProductionSpan = Math.max(longestProductionSpan, span);
            }
        }
    }

    private static final class PatternStats {
        private long total;
        private long matched;
        private long misclassified;
        private long missed;
    }

    private record Ratios(double bXa,
                          double cAb,
                          double cdBc,
                          double cdAb,
                          double adXa,
                          double xcXa,
                          double cdXc,
                          double sharkA0x,
                          double sharkAbXa,
                          double sharkBcAb,
                          double sharkCompletion) {
        private static Ratios from(List<HarmonicPivot> p) {
            double l01 = distance(p, 0, 1);
            double l12 = distance(p, 1, 2);
            double l23 = distance(p, 2, 3);
            double l34 = distance(p, 3, 4);
            double xc = distance(p, 0, 3);
            return new Ratios(
                    ratio(l12, l01), ratio(l23, l12), ratio(l34, l23), ratio(l34, l12),
                    ratio(distance(p, 1, 4), l01), ratio(xc, l01), ratio(l34, xc),
                    ratio(l12, l01), ratio(l23, l12), ratio(l34, l23),
                    ratio(distance(p, 1, 4), l01));
        }

        private static double distance(List<HarmonicPivot> p, int first, int second) {
            return Math.abs(p.get(second).price() - p.get(first).price());
        }

        private static double ratio(double numerator, double denominator) {
            return denominator <= 0.0 ? Double.NaN : numerator / denominator;
        }
    }

    @FunctionalInterface
    private interface SymbolConsumer {
        void accept(List<Candle> candles);
    }
}
