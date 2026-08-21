package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "backtest.elliott.rule-comparison.enabled", matches = "true")
class ElliottWaveOneShortestRuleComparisonTest {
    private static final Path CURRENT = Path.of(
            "target/expanded-backtest-data/expanded-elliott-signals-elliott-v1.csv");
    private static final Path WAVE_ONE_SHORTEST = Path.of(
            "target/expanded-backtest-data/expanded-elliott-signals-elliott-v1-wave-one-shortest.csv");
    private static final Path REPORT = Path.of("docs/elliott-wave-one-shortest-rule-validation.md");
    private static final List<ComparisonRow> ROWS = List.of(
            new ComparisonRow("1wk", "Weekly", 4, "4 candles / 4%"),
            new ComparisonRow("1wk", "Weekly", 8, "8 candles / 8%"),
            new ComparisonRow("1wk", "Weekly", 12, "12 candles / 12%"),
            new ComparisonRow("1mo", "Monthly", 3, "3 candles / 6%"),
            new ComparisonRow("1mo", "Monthly", 6, "6 candles / 12%"),
            new ComparisonRow("1mo", "Monthly", 9, "9 candles / 18%"));
    private static final List<Stage> STAGES = List.of(
            new Stage("All", ignored -> true),
            new Stage("Wave V end", signal -> signal.key().pattern().endsWith("WAVE_V_END")),
            new Stage("ABC end", signal -> signal.key().pattern().endsWith("CORRECTION")));

    @Test
    void comparesFactoryWithRequiringWaveOneAsTheShortestActionaryWave() throws Exception {
        Map<SignalKey, Signal> current = read(CURRENT);
        Map<SignalKey, Signal> candidate = read(WAVE_ONE_SHORTEST);
        assertFalse(current.isEmpty(), "Current-model Elliott output is empty.");
        assertFalse(candidate.isEmpty(), "Wave-I-shortest Elliott output is empty.");

        String report = report(current, candidate);
        Files.writeString(REPORT, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Comparison report: " + REPORT.toAbsolutePath());
    }

    private Map<SignalKey, Signal> read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "Missing expanded Elliott result: " + path);
        Map<SignalKey, SignalBuilder> builders = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            assertTrue(header != null && header.startsWith("interval,symbol,cohort,pattern,direction,score"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] value = line.split(",", -1);
                SignalKey key = new SignalKey(value[0], value[1], value[3], value[4], Long.parseLong(value[6]));
                SignalBuilder builder = builders.computeIfAbsent(key,
                        ignored -> new SignalBuilder(key, Integer.parseInt(value[5]), new LinkedHashMap<>()));
                builder.outcomes().put(Integer.parseInt(value[8]), new Outcome(
                        Double.parseDouble(value[11]), value[14]));
            }
        }
        Map<SignalKey, Signal> result = new LinkedHashMap<>();
        builders.forEach((key, value) -> result.put(key,
                new Signal(value.key(), value.score(), Map.copyOf(value.outcomes()))));
        return result;
    }

    private String report(Map<SignalKey, Signal> current, Map<SignalKey, Signal> candidate) {
        Set<SignalKey> retained = new LinkedHashSet<>(current.keySet());
        retained.retainAll(candidate.keySet());
        Set<SignalKey> removed = new LinkedHashSet<>(current.keySet());
        removed.removeAll(candidate.keySet());
        Set<SignalKey> added = new LinkedHashSet<>(candidate.keySet());
        added.removeAll(current.keySet());

        StringBuilder output = new StringBuilder();
        output.append("# Elliott Wave I shortest-rule validation\n\n");
        output.append("Run date: ").append(LocalDate.now()).append(".\n\n");
        output.append("## Study design\n\n");
        output.append("- Frozen pre-outcome universe: **2,193 equities** and **7,802,979 adjusted daily candles**.\n");
        output.append("- Both variants use V1 production scoring, completed weekly/monthly candles, rolling 100-candle windows, identical pivot rules, and the production 75-point alert gate.\n");
        output.append("- Current model: Wave I may be the longest actionary wave, while Wave III still cannot be the shortest.\n");
        output.append("- Candidate: Wave I must be no longer than both Waves III and V. This is the only changed rule.\n");
        output.append("- Precision is success / (success + failure); inconclusive outcomes are excluded. Average return includes every retained signal and is direction-adjusted, so positive is favorable for BUY and SELL.\n\n");

        output.append("## Signal identity impact\n\n");
        output.append("| Model / transition | Unique signals | Tickers |\n");
        output.append("|---|---:|---:|\n");
        appendIdentity(output, "Current model", current.values());
        appendIdentity(output, "Wave I must be shortest", candidate.values());
        appendIdentity(output, "Retained in both", retained.stream().map(current::get).toList());
        appendIdentity(output, "Removed by candidate", removed.stream().map(current::get).toList());
        appendIdentity(output, "Candidate-only after count reselection", added.stream().map(candidate::get).toList());

        output.append("\n## Precision and return comparison\n\n");
        output.append("| Interval | Horizon / move | Stage | Current N | Candidate N | N change | Current precision | Candidate precision | Precision change | Current avg return | Candidate avg return | Return change |\n");
        output.append("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ComparisonRow row : ROWS) {
            for (Stage stage : STAGES) {
                Metrics baseline = metrics(current.values(), row, stage.filter());
                Metrics alternate = metrics(candidate.values(), row, stage.filter());
                output.append(String.format(Locale.ROOT,
                        "| %s | %s | %s | %,d | %,d | %+,d (%+.2f%%) | %.2f%% | %.2f%% | %+.2f pp | %+.2f%% | %+.2f%% | %+.2f pp |%n",
                        row.label(), row.horizonLabel(), stage.label(), baseline.signals(), alternate.signals(),
                        alternate.signals() - baseline.signals(), percentChange(baseline.signals(), alternate.signals()),
                        baseline.precision(), alternate.precision(), alternate.precision() - baseline.precision(),
                        baseline.averageReturn(), alternate.averageReturn(),
                        alternate.averageReturn() - baseline.averageReturn()));
            }
        }

        output.append("\n## Primary-horizon direction split\n\n");
        output.append("| Interval | Direction | Current N | Candidate N | N change | Current precision | Candidate precision | Precision change | Current avg return | Candidate avg return | Return change |\n");
        output.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ComparisonRow row : List.of(ROWS.get(0), ROWS.get(3))) {
            for (String direction : List.of("BUY", "SELL")) {
                Predicate<Signal> filter = signal -> direction.equals(signal.key().direction());
                Metrics baseline = metrics(current.values(), row, filter);
                Metrics alternate = metrics(candidate.values(), row, filter);
                output.append(String.format(Locale.ROOT,
                        "| %s | %s | %,d | %,d | %+,d | %.2f%% | %.2f%% | %+.2f pp | %+.2f%% | %+.2f%% | %+.2f pp |%n",
                        row.label(), direction, baseline.signals(), alternate.signals(),
                        alternate.signals() - baseline.signals(), baseline.precision(), alternate.precision(),
                        alternate.precision() - baseline.precision(), baseline.averageReturn(),
                        alternate.averageReturn(), alternate.averageReturn() - baseline.averageReturn()));
            }
        }

        output.append("\n## Interpretation\n\n");
        output.append(interpret(current.values(), candidate.values()));
        return output.toString();
    }

    private void appendIdentity(StringBuilder output, String label, java.util.Collection<Signal> signals) {
        long tickers = signals.stream().map(signal -> signal.key().symbol()).distinct().count();
        output.append(String.format(Locale.ROOT, "| %s | %,d | %,d |%n", label, signals.size(), tickers));
    }

    private Metrics metrics(java.util.Collection<Signal> source,
                            ComparisonRow row,
                            Predicate<Signal> filter) {
        List<Outcome> outcomes = source.stream()
                .filter(signal -> row.interval().equals(signal.key().interval()))
                .filter(filter)
                .map(signal -> signal.outcomes().get(row.horizon()))
                .filter(java.util.Objects::nonNull)
                .toList();
        long success = outcomes.stream().filter(outcome -> "SUCCESS".equals(outcome.outcome())).count();
        long failure = outcomes.stream().filter(outcome -> "FAILURE".equals(outcome.outcome())).count();
        double precision = success + failure == 0 ? 0.0 : success * 100.0 / (success + failure);
        double average = outcomes.stream().mapToDouble(Outcome::directionalReturn).average().orElse(0.0);
        return new Metrics(outcomes.size(), precision, average);
    }

    private double percentChange(long baseline, long candidate) {
        return baseline == 0 ? 0.0 : (candidate - baseline) * 100.0 / baseline;
    }

    private String interpret(java.util.Collection<Signal> current,
                             java.util.Collection<Signal> candidate) {
        Metrics currentWeekly = metrics(current, ROWS.get(0), ignored -> true);
        Metrics candidateWeekly = metrics(candidate, ROWS.get(0), ignored -> true);
        Metrics currentMonthly = metrics(current, ROWS.get(3), ignored -> true);
        Metrics candidateMonthly = metrics(candidate, ROWS.get(3), ignored -> true);
        boolean candidateLosesReturnAtBothPrimaryHorizons =
                candidateWeekly.averageReturn() < currentWeekly.averageReturn()
                        && candidateMonthly.averageReturn() < currentMonthly.averageReturn();
        String recommendation = candidateLosesReturnAtBothPrimaryHorizons
                ? " Recommendation: retain the current factory rule; the small precision changes do not compensate for lower returns and fewer signals."
                : " The candidate should only replace the factory rule if its precision/return change is large enough to justify its signal-volume cost and is stable across interval, stage, and direction.";
        return String.format(Locale.ROOT,
                "At the primary production horizons, requiring Wave I as the shortest changed weekly signal volume by %+.2f%%, precision by %+.2f percentage points, and average return by %+.2f percentage points. Monthly signal volume changed by %+.2f%%, precision by %+.2f percentage points, and average return by %+.2f percentage points. These are descriptive full-universe results on the frozen dataset.%s\n",
                percentChange(currentWeekly.signals(), candidateWeekly.signals()),
                candidateWeekly.precision() - currentWeekly.precision(),
                candidateWeekly.averageReturn() - currentWeekly.averageReturn(),
                percentChange(currentMonthly.signals(), candidateMonthly.signals()),
                candidateMonthly.precision() - currentMonthly.precision(),
                candidateMonthly.averageReturn() - currentMonthly.averageReturn(),
                recommendation);
    }

    private record ComparisonRow(String interval, String label, int horizon, String horizonLabel) { }
    private record Stage(String label, Predicate<Signal> filter) { }
    private record SignalKey(String interval, String symbol, String pattern, String direction, long timestamp) { }
    private record Outcome(double directionalReturn, String outcome) { }
    private record Signal(SignalKey key, int score, Map<Integer, Outcome> outcomes) { }
    private record SignalBuilder(SignalKey key, int score, Map<Integer, Outcome> outcomes) { }
    private record Metrics(long signals, double precision, double averageReturn) { }
}
