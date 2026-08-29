package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.Direction;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicFormation;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in duration/market-noise audit. Unlike the reference-recall audit, this
 * starts with independently valid XABCD ratios and synthesizes complete OHLC
 * histories in which each structural leg lasts many candles and contains
 * smaller counter-swings. Production must rediscover the intended pivots.
 */
@EnabledIfSystemProperty(named = "backtest.harmonic.long-duration.enabled", matches = "true")
class HarmonicPatternLongDurationResearchTest {
    private static final int COHORT_SIZE = 1_000;
    private static final double REFERENCE_TOLERANCE = .03;
    private static final long RANDOM_SEED = 0x10A6D247L;
    private static final Path REPORT = Path.of(
            "target/expanded-backtest-data/harmonic-long-duration-recall-report.md");
    private static final Path CSV = Path.of(
            "target/expanded-backtest-data/harmonic-long-duration-cases.csv");
    private static final Map<HarmonicPatternType, double[]> SEEDS = seedGeometries();

    private final HarmonicPatternDetectionService detector = new HarmonicPatternDetectionService(
            new Rules(.03, .03, .10, .005, 2, 250, 55, .34, 0));

    @Test
    void auditsOneThousandLongDurationNoisyFormationsThroughTheFullPipeline() throws Exception {
        Files.createDirectories(REPORT.toAbsolutePath().getParent());
        Audit audit = new Audit();
        Random random = new Random(RANDOM_SEED);
        try (BufferedWriter writer = Files.newBufferedWriter(CSV, StandardCharsets.UTF_8)) {
            writer.write("case_id,pattern,direction,span_candles,duration_bucket,noise_percent,noise_bucket,"
                    + "base_pivots,detected,detected_patterns\n");
            for (int caseId = 0; caseId < COHORT_SIZE; caseId++) {
                HarmonicPatternType expected = HarmonicPatternType.values()[caseId % HarmonicPatternType.values().length];
                boolean bearish = (caseId / HarmonicPatternType.values().length) % 2 == 1;
                DurationBucket durationBucket = DurationBucket.forCase(caseId);
                double[] bullishGeometry = generateValidGeometry(expected, random);
                double priceScale = Math.exp(-2.5 + random.nextDouble() * 6.0);
                double[] prices = transform(bullishGeometry, bearish, priceScale);
                int[] legDurations = durationBucket.legDurations(random);
                double noiseFraction = .004 + random.nextDouble() * .116;
                GeneratedSeries series = generateCandles(prices, bearish, legDurations,
                        noiseFraction, random, caseId);

                List<HarmonicFormation> formations = detector.detectHistorical(series.candles());
                boolean detected = formations.stream().anyMatch(formation ->
                        formation.pattern() == expected
                                && formation.direction() == (bearish ? Direction.BEARISH : Direction.BULLISH)
                                && formation.points().getLast().timestamp() == series.terminalTimestamp());
                int basePivots = detector.confirmedPivots(series.candles()).size();
                NoiseBucket noiseBucket = NoiseBucket.of(noiseFraction);
                audit.add(expected, durationBucket, noiseBucket, detected, basePivots);
                writer.write(String.format(Locale.ROOT, "%d,%s,%s,%d,%s,%.4f,%s,%d,%s,%s%n",
                        caseId, expected, bearish ? "BEARISH" : "BULLISH", series.spanCandles(),
                        durationBucket, noiseFraction * 100.0, noiseBucket, basePivots, detected,
                        formations.stream().map(item -> item.pattern().name()).distinct()
                                .sorted().reduce((left, right) -> left + ";" + right).orElse("")));
            }
        }

        assertEquals(COHORT_SIZE, audit.total, "The duration audit must contain exactly 1,000 formations.");
        assertTrue(audit.matched >= 950,
                "Long-duration recall must remain at or above 95% without relaxing textbook ratios.");
        Files.writeString(REPORT, report(audit), StandardCharsets.UTF_8);
        System.out.println(Files.readString(REPORT));
        System.out.println("Long-duration harmonic report: " + REPORT.toAbsolutePath());
        System.out.println("Long-duration case CSV: " + CSV.toAbsolutePath());
    }

    private double[] generateValidGeometry(HarmonicPatternType expected, Random random) {
        double[] seed = SEEDS.get(expected);
        for (int attempt = 0; attempt < 100_000; attempt++) {
            double[] candidate = seed.clone();
            candidate[2] += random.nextGaussian() * .22;
            candidate[3] += random.nextGaussian() * .22;
            candidate[4] += random.nextGaussian() * .22;
            if (referenceLabels(candidate).equals(Set.of(expected))) return candidate;
        }
        throw new IllegalStateException("Unable to generate an independently valid " + expected + " geometry.");
    }

    private GeneratedSeries generateCandles(double[] prices,
                                            boolean bearish,
                                            int[] legDurations,
                                            double noiseFraction,
                                            Random random,
                                            int caseId) {
        int[] anchors = new int[prices.length];
        anchors[0] = 3;
        for (int index = 1; index < anchors.length; index++) {
            anchors[index] = anchors[index - 1] + legDurations[index - 1];
        }
        int candleCount = anchors[4] + 3;
        double[] centers = new double[candleCount];
        double firstMove = distance(prices[0], prices[1]);
        double lastMove = distance(prices[3], prices[4]);
        double before = prices[0] + (bearish ? -1.0 : 1.0) * firstMove * .10;
        double after = prices[4] + (bearish ? -1.0 : 1.0) * lastMove * .10;
        linear(centers, 0, before, anchors[0], prices[0]);

        for (int leg = 0; leg < legDurations.length; leg++) {
            int from = anchors[leg];
            int to = anchors[leg + 1];
            double fromPrice = prices[leg];
            double toPrice = prices[leg + 1];
            double legSize = distance(fromPrice, toPrice);
            double amplitude = Math.min((fromPrice + toPrice) * .5 * noiseFraction, legSize * .18);
            int cycles = Math.max(1, Math.min(7, legDurations[leg] / 10));
            double phaseSign = random.nextBoolean() ? 1.0 : -1.0;
            double guard = legSize * .002;
            for (int index = from; index <= to; index++) {
                double progress = (double) (index - from) / (to - from);
                double baseline = fromPrice + (toPrice - fromPrice) * progress;
                double envelope = Math.sin(Math.PI * progress);
                double wave = phaseSign * amplitude * envelope
                        * Math.sin(Math.PI * 2.0 * cycles * progress);
                double lower = Math.min(fromPrice, toPrice) + guard;
                double upper = Math.max(fromPrice, toPrice) - guard;
                centers[index] = index == from ? fromPrice
                        : index == to ? toPrice
                        : Math.clamp(baseline + wave, lower, upper);
            }
        }
        linear(centers, anchors[4], prices[4], candleCount - 1, after);

        double minimumLeg = Double.POSITIVE_INFINITY;
        for (int index = 1; index < prices.length; index++) {
            minimumLeg = Math.min(minimumLeg, distance(prices[index - 1], prices[index]));
        }
        double spread = Math.max(minimumLeg * .0002, 1e-8);
        long start = 1_500_000_000L + caseId * 40_000_000L;
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < candleCount; index++) {
            int anchor = anchorPosition(anchors, index);
            double high = centers[index] + spread;
            double low = centers[index] - spread;
            double close = centers[index];
            if (anchor >= 0) {
                boolean bullishLow = anchor % 2 == 0;
                boolean lowPivot = bearish ? !bullishLow : bullishLow;
                if (lowPivot) {
                    low = prices[anchor];
                    high = prices[anchor] + spread * 2.0;
                    close = prices[anchor] + spread;
                } else {
                    high = prices[anchor];
                    low = prices[anchor] - spread * 2.0;
                    close = prices[anchor] - spread;
                }
            }
            candles.add(new Candle("LONG" + caseId, "1d", start + index * 86_400L,
                    close, high, low, close, 1_000L));
        }
        return new GeneratedSeries(List.copyOf(candles),
                candles.get(anchors[4]).getTimestamp(), anchors[4] - anchors[0] + 1);
    }

    private Set<HarmonicPatternType> referenceLabels(double[] p) {
        Set<HarmonicPatternType> labels = new LinkedHashSet<>();
        double xa = distance(p[0], p[1]);
        double ab = distance(p[1], p[2]);
        double bc = distance(p[2], p[3]);
        double cd = distance(p[3], p[4]);
        double xc = distance(p[0], p[3]);
        double bXa = ratio(ab, xa);
        double cAb = ratio(bc, ab);
        double cdBc = ratio(cd, bc);
        double cdAb = ratio(cd, ab);
        double adXa = ratio(distance(p[1], p[4]), xa);
        if (!(p[1] > p[0] && p[2] < p[1] && p[3] > p[2] && p[4] < p[3])) return Set.of();
        boolean inside = p[3] <= p[1] && p[4] > p[0] && p[4] < p[1];
        boolean outside = p[3] <= p[1] && p[4] < p[0];
        if (inside && near(bXa, .618) && within(cAb, .382, .886)
                && within(cdBc, 1.13, 1.618) && alternative(cdAb, 1.0, 1.27)
                && near(adXa, .786)) labels.add(HarmonicPatternType.GARTLEY);
        if (inside && within(bXa, .382, .50) && within(cAb, .382, .886)
                && within(cdBc, 1.618, 2.618) && alternative(cdAb, 1.0, 1.27)
                && near(adXa, .886)) labels.add(HarmonicPatternType.BAT);
        if (outside && near(bXa, .786) && within(cAb, .382, .886)
                && within(cdBc, 1.618, 2.24) && alternative(cdAb, 1.0, 1.27)
                && near(adXa, 1.27)) labels.add(HarmonicPatternType.BUTTERFLY);
        if (outside && within(bXa, .382, .618) && within(cAb, .382, .886)
                && within(cdBc, 2.618, 3.618) && near(adXa, 1.618)
                && Math.abs(cdAb - 1.0) >= .10) labels.add(HarmonicPatternType.CRAB);
        boolean sharkOrder = p[3] > p[1] && p[4] < p[2] && p[4] <= p[1];
        if (sharkOrder && within(bXa, .32, .618) && within(cAb, 1.13, 1.618)
                && within(cdBc, 1.618, 2.24) && alternative(adXa, .886, 1.13)) {
            labels.add(HarmonicPatternType.SHARK);
        }
        double xcXa = ratio(xc, xa);
        double cdXc = ratio(cd, xc);
        boolean cypherOrder = p[3] > p[1] && p[4] < p[2];
        if (cypherOrder && within(bXa, .382, .618)
                && xcXa >= 1.272 * (1.0 - REFERENCE_TOLERANCE) && xcXa <= 1.414
                && near(cdXc, .786)) labels.add(HarmonicPatternType.CYPHER);
        return Set.copyOf(labels);
    }

    private String report(Audit audit) {
        StringBuilder text = new StringBuilder("# Long-duration harmonic detection audit\n\n")
                .append("## Scope\n\n")
                .append("- Independently valid generated formations: 1,000\n")
                .append("- Full path: generated OHLC -> production pivots -> multi-scale detector\n")
                .append("- Formation spans: approximately 40-320 candles\n")
                .append("- Intra-leg oscillation request: 0.4%-12% of price, capped at 18% of each leg\n")
                .append("- Deterministic seed: ").append(RANDOM_SEED).append("\n\n")
                .append("## Overall\n\n")
                .append(String.format(Locale.ROOT, "- Exact intended completion matches: %,d / %,d (%.2f%%)\n",
                        audit.matched, audit.total, percent(audit.matched, audit.total)))
                .append(String.format(Locale.ROOT, "- Misses: %,d\n", audit.total - audit.matched))
                .append(String.format(Locale.ROOT, "- Average base-scale pivots per case: %.2f\n\n",
                        (double) audit.basePivots / audit.total))
                .append("## By pattern\n\n")
                .append("| Pattern | Cases | Matched | Missed | Recall |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (HarmonicPatternType pattern : HarmonicPatternType.values()) {
            Count count = audit.patterns.get(pattern);
            text.append(String.format(Locale.ROOT, "| %s | %,d | %,d | %,d | %.2f%% |%n",
                    pattern, count.total, count.matched, count.total - count.matched,
                    percent(count.matched, count.total)));
        }
        appendBreakdown(text, "By duration", audit.durations);
        appendBreakdown(text, "By intra-leg noise", audit.noise);
        text.append("\n## Interpretation\n\n")
                .append("This audit isolates whether duration plus smaller intra-leg swings prevent the production ")
                .append("pivot selector from recovering otherwise valid harmonic geometry. It is a detector-recall ")
                .append("test, not evidence of profitability or proof that generated paths reproduce every market regime.\n")
                .append("\n## Reproduction\n\n```powershell\n")
                .append(".\\mvnw.cmd '-Dtest=HarmonicPatternLongDurationResearchTest' ")
                .append("'-Dbacktest.harmonic.long-duration.enabled=true' test\n```\n");
        return text.toString();
    }

    private <K extends Enum<K>> void appendBreakdown(StringBuilder text, String title, Map<K, Count> counts) {
        text.append("\n## ").append(title).append("\n\n")
                .append("| Group | Cases | Matched | Missed | Recall |\n")
                .append("|---|---:|---:|---:|---:|\n");
        counts.forEach((key, count) -> text.append(String.format(Locale.ROOT,
                "| %s | %,d | %,d | %,d | %.2f%% |%n", key, count.total, count.matched,
                count.total - count.matched, percent(count.matched, count.total))));
    }

    private double percent(long matched, long total) {
        return total == 0 ? 0.0 : matched * 100.0 / total;
    }

    private double[] transform(double[] source, boolean bearish, double scale) {
        double[] result = new double[source.length];
        for (int index = 0; index < source.length; index++) {
            result[index] = scale * (bearish ? 300.0 - source[index] : source[index]);
        }
        return result;
    }

    private void linear(double[] values, int from, double fromValue, int to, double toValue) {
        for (int index = from; index <= to; index++) {
            double progress = (double) (index - from) / (to - from);
            values[index] = fromValue + (toValue - fromValue) * progress;
        }
    }

    private int anchorPosition(int[] anchors, int candleIndex) {
        for (int index = 0; index < anchors.length; index++) {
            if (anchors[index] == candleIndex) return index;
        }
        return -1;
    }

    private boolean near(double value, double target) {
        return value >= target * (1.0 - REFERENCE_TOLERANCE)
                && value <= target * (1.0 + REFERENCE_TOLERANCE);
    }

    private boolean within(double value, double minimum, double maximum) {
        return value >= minimum * (1.0 - REFERENCE_TOLERANCE)
                && value <= maximum * (1.0 + REFERENCE_TOLERANCE);
    }

    private boolean alternative(double value, double first, double second) {
        return near(value, first) || near(value, second);
    }

    private double distance(double first, double second) {
        return Math.abs(second - first);
    }

    private double ratio(double numerator, double denominator) {
        return denominator <= 0.0 ? Double.NaN : numerator / denominator;
    }

    private static Map<HarmonicPatternType, double[]> seedGeometries() {
        Map<HarmonicPatternType, double[]> result = new EnumMap<>(HarmonicPatternType.class);
        result.put(HarmonicPatternType.GARTLEY, new double[]{100, 200, 138.2, 183.2, 121.4});
        result.put(HarmonicPatternType.BAT, new double[]{100, 200, 150, 174.9, 111.4});
        result.put(HarmonicPatternType.BUTTERFLY, new double[]{100, 200, 121.4, 172.622, 73});
        result.put(HarmonicPatternType.CRAB, new double[]{100, 200, 150, 194, 38.2});
        result.put(HarmonicPatternType.SHARK, new double[]{100, 200, 150, 210, 111.4});
        result.put(HarmonicPatternType.CYPHER, new double[]{100, 200, 150, 227.2, 127.2});
        return Map.copyOf(result);
    }

    private enum DurationBucket {
        CANDLES_40_TO_79(10, 19),
        CANDLES_80_TO_159(20, 39),
        CANDLES_160_TO_320(40, 79);

        private final int minimumLeg;
        private final int maximumLeg;

        DurationBucket(int minimumLeg, int maximumLeg) {
            this.minimumLeg = minimumLeg;
            this.maximumLeg = maximumLeg;
        }

        static DurationBucket forCase(int caseId) {
            if (caseId < 334) return CANDLES_40_TO_79;
            if (caseId < 667) return CANDLES_80_TO_159;
            return CANDLES_160_TO_320;
        }

        int[] legDurations(Random random) {
            int[] result = new int[4];
            for (int index = 0; index < result.length; index++) {
                result[index] = minimumLeg + random.nextInt(maximumLeg - minimumLeg + 1);
            }
            return result;
        }
    }

    private enum NoiseBucket {
        LOW, MEDIUM, HIGH;

        static NoiseBucket of(double noiseFraction) {
            return noiseFraction < .03 ? LOW : noiseFraction < .07 ? MEDIUM : HIGH;
        }
    }

    private static final class Audit {
        private int total;
        private int matched;
        private long basePivots;
        private final Map<HarmonicPatternType, Count> patterns = initialized(HarmonicPatternType.class);
        private final Map<DurationBucket, Count> durations = initialized(DurationBucket.class);
        private final Map<NoiseBucket, Count> noise = initialized(NoiseBucket.class);

        private void add(HarmonicPatternType pattern,
                         DurationBucket duration,
                         NoiseBucket noiseBucket,
                         boolean detected,
                         int pivots) {
            total++;
            if (detected) matched++;
            basePivots += pivots;
            patterns.get(pattern).add(detected);
            durations.get(duration).add(detected);
            noise.get(noiseBucket).add(detected);
        }

        private static <K extends Enum<K>> Map<K, Count> initialized(Class<K> type) {
            Map<K, Count> result = new LinkedHashMap<>();
            for (K value : type.getEnumConstants()) result.put(value, new Count());
            return result;
        }
    }

    private static final class Count {
        private int total;
        private int matched;

        private void add(boolean detected) {
            total++;
            if (detected) matched++;
        }
    }

    private record GeneratedSeries(List<Candle> candles, long terminalTimestamp, int spanCandles) { }
}
