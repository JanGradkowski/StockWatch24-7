package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Produces conditional textbook scenarios from a detector-owned Elliott count.
 * The paths are schematic; the price zones and candle windows are the testable parts.
 */
final class ElliottProjectionPolicy {
    static final String VERSION = "ELLIOTT_SCENARIOS_V1";
    private static final double TARGET_ZONE_PERCENT = 1.5;

    private ElliottProjectionPolicy() {
    }

    static List<Scenario> generate(
            ElliottSignalStage stage,
            String cycleDirection,
            TradeSignal expectedMove,
            long sourceTimestamp,
            double sourcePrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            List<Candle> candles,
            TimeInterval interval) {
        if (stage == null || expectedMove == null || sourceTimestamp <= 0
                || !Double.isFinite(sourcePrice) || sourcePrice <= 0.0
                || points == null || points.isEmpty() || interval == null) {
            return List.of();
        }
        boolean bullish = "BULLISH".equalsIgnoreCase(cycleDirection);
        boolean bearish = "BEARISH".equalsIgnoreCase(cycleDirection);
        if (!bullish && !bearish) return List.of();
        double cycleSign = bullish ? 1.0 : -1.0;
        long spacing = candleSpacing(candles, sourceTimestamp, interval);

        Point wave0 = point(points, "0");
        Point wave1 = point(points, "I");
        Point wave2 = point(points, "II");
        if (wave0 == null || wave1 == null || wave2 == null) return List.of();

        List<ScenarioSeed> seeds = switch (stage) {
            case WAVE_II_END -> waveThreeSeeds(sourcePrice, cycleSign, wave0, wave1, wave2, spacing);
            case WAVE_III_END -> waveFourSeeds(sourcePrice, cycleSign, wave0, wave1, wave2,
                    point(points, "III"), spacing);
            case WAVE_IV_END -> waveFiveSeeds(sourcePrice, cycleSign, wave0, wave1, wave2,
                    point(points, "III"), point(points, "IV"), spacing);
            case WAVE_V_END -> correctionSeeds(sourcePrice, cycleSign, wave0, wave1,
                    point(points, "IV"), point(points, "V"), spacing);
            case CORRECTION_END -> resumptionSeeds(sourcePrice, cycleSign, wave0,
                    point(points, "V"), point(points, "C"), spacing);
        };
        if (seeds.isEmpty()) return List.of();

        List<Scenario> scenarios = new ArrayList<>(Math.min(3, seeds.size()));
        for (int index = 0; index < seeds.size() && index < 3; index++) {
            ScenarioSeed seed = seeds.get(index);
            if (!Double.isFinite(seed.target()) || seed.target() <= 0.0) continue;
            double zoneLow = seed.target() * (1.0 - TARGET_ZONE_PERCENT / 100.0);
            double zoneHigh = seed.target() * (1.0 + TARGET_ZONE_PERCENT / 100.0);
            if (zoneLow <= 0.0) continue;
            List<ProjectedPoint> path = projectedPath(
                    sourceTimestamp, sourcePrice, seed.target(), spacing,
                    seed.maximumCandles(), seed.pathShape(), interval);
            scenarios.add(new Scenario(
                    seed.key(), seed.label(), seed.description(), scenarios.size() + 1,
                    expectedMove, seed.confidence(), path,
                    zoneLow, zoneHigh, seed.target(), seed.targetBasis(),
                    seed.minimumCandles(), seed.maximumCandles(),
                    seed.hardInvalidationPrice(), seed.hardInvalidationSide(),
                    seed.scenarioInvalidationPrice(), seed.scenarioInvalidationSide(),
                    seed.evidence()));
        }
        return List.copyOf(scenarios);
    }

    private static List<ScenarioSeed> waveThreeSeeds(
            double source, double sign, Point wave0, Point wave1, Point wave2, long spacing) {
        double length = Math.abs(wave1.price() - wave0.price());
        int waveOneCandles = legCandles(wave0, wave1, spacing);
        BoundarySide hardSide = sign > 0 ? BoundarySide.BELOW : BoundarySide.ABOVE;
        return List.of(
                motive("STANDARD_WAVE_III", "Preferred · standard Wave III",
                        "A five-wave advance toward the common 161.8% Wave I extension.", 62,
                        wave2.price() + sign * length * 1.618,
                        "Wave III at 161.8% of Wave I from Wave II", waveOneCandles, .8, 1.8,
                        wave0.price(), hardSide, source,
                        "Wave III is commonly the strongest motive wave.",
                        "Wave II must not cross the Wave I origin."),
                motive("EXTENDED_WAVE_III", "Alternate A · extended Wave III",
                        "A stronger third-wave extension toward 261.8% of Wave I.", 52,
                        wave2.price() + sign * length * 2.618,
                        "Extended Wave III at 261.8% of Wave I from Wave II", waveOneCandles, 1.2, 2.8,
                        wave0.price(), hardSide, source,
                        "Third waves are the motive wave most often extended.",
                        "Momentum should expand as the scenario develops."),
                motive("CONSERVATIVE_WAVE_III", "Alternate B · conservative Wave III",
                        "A shorter motive path toward equality with Wave I.", 42,
                        wave2.price() + sign * length,
                        "Conservative Wave III at equality with Wave I", waveOneCandles, .6, 1.4,
                        wave0.price(), hardSide, source,
                        "Equality is retained as a conservative alternate.",
                        "It loses credibility quickly if price extends through its target zone."));
    }

    private static List<ScenarioSeed> waveFourSeeds(
            double source, double sign, Point wave0, Point wave1, Point wave2, Point wave3, long spacing) {
        if (wave3 == null) return List.of();
        double length = Math.abs(wave3.price() - wave2.price());
        int reference = legCandles(wave1, wave2, spacing);
        BoundarySide hardSide = sign > 0 ? BoundarySide.BELOW : BoundarySide.ABOVE;
        BoundarySide correctionSide = sign > 0 ? BoundarySide.BELOW : BoundarySide.ABOVE;
        return List.of(
                correction("SHALLOW_FLAT_WAVE_IV", "Preferred · flat / combination",
                        "A shallow sideways Wave IV centered near a 38.2% retracement.", 60,
                        wave3.price() - sign * length * .382,
                        "Wave IV at a 38.2% retracement of Wave III", reference, .8, 2.0,
                        wave1.price(), hardSide,
                        wave3.price() - sign * length * .50, correctionSide, source,
                        PathShape.FLAT,
                        "Alternation favors a sideways correction when Wave II was sharp.",
                        "A move through the 50% retracement discredits this shallow form."),
                correction("ZIGZAG_WAVE_IV", "Alternate A · zigzag",
                        "A sharper A-B-C correction toward a 50% Wave III retracement.", 51,
                        wave3.price() - sign * length * .50,
                        "Wave IV at a 50.0% retracement of Wave III", reference, .6, 1.5,
                        wave1.price(), hardSide,
                        wave3.price() - sign * length * .618, correctionSide, source,
                        PathShape.ZIGZAG,
                        "Zigzags provide the direct corrective alternate.",
                        "Wave IV must remain outside Wave I territory in a standard impulse."),
                correction("TRIANGLE_WAVE_IV", "Alternate B · triangle",
                        "A contracting five-swing sideways Wave IV near a 23.6% retracement.", 44,
                        wave3.price() - sign * length * .236,
                        "Triangle Wave IV near a 23.6% retracement of Wave III", reference, 1.2, 2.8,
                        wave1.price(), hardSide,
                        wave3.price() - sign * length * .382, correctionSide, source,
                        PathShape.TRIANGLE,
                        "Triangles are valid in Wave IV and imply prolonged sideways action.",
                        "A decisive 38.2% retracement discredits the shallow triangle path."));
    }

    private static List<ScenarioSeed> waveFiveSeeds(
            double source, double sign, Point wave0, Point wave1, Point wave2,
            Point wave3, Point wave4, long spacing) {
        if (wave3 == null || wave4 == null) return List.of();
        double waveOneLength = Math.abs(wave1.price() - wave0.price());
        double zeroToThree = Math.abs(wave3.price() - wave0.price());
        int reference = legCandles(wave0, wave1, spacing);
        BoundarySide hardSide = sign > 0 ? BoundarySide.BELOW : BoundarySide.ABOVE;
        return List.of(
                motive("EQUAL_WAVE_V", "Preferred · Wave V equals Wave I",
                        "A five-wave final advance matching the length of Wave I.", 60,
                        wave4.price() + sign * waveOneLength,
                        "Wave V at equality with Wave I from Wave IV", reference, .7, 1.6,
                        wave4.price(), hardSide, source,
                        "Wave V commonly relates to Wave I by equality.",
                        "Crossing the Wave IV endpoint invalidates this motive path."),
                motive("EXTENDED_WAVE_V", "Alternate A · extended Wave V",
                        "A stronger fifth wave toward 61.8% of the 0-to-III move.", 49,
                        wave4.price() + sign * zeroToThree * .618,
                        "Extended Wave V at 61.8% of the Wave 0-to-III move", reference, 1.0, 2.2,
                        wave4.price(), hardSide, source,
                        "A fifth-wave extension remains possible when Wave III was not extended.",
                        "The target is an objective zone, not an exact reversal price."),
                motive("TRUNCATED_WAVE_V", "Alternate B · truncated / short Wave V",
                        "A weak final push using 61.8% of Wave I and allowing truncation.", 41,
                        Math.max(.000001, wave4.price() + sign * waveOneLength * .618),
                        "Short Wave V at 61.8% of Wave I from Wave IV", reference, .5, 1.2,
                        wave4.price(), hardSide, source,
                        "Weak momentum can produce a short or truncated fifth.",
                        "Strong continuation through this zone discredits the short-fifth alternate."));
    }

    private static List<ScenarioSeed> correctionSeeds(
            double source, double sign, Point wave0, Point wave1, Point wave4, Point wave5, long spacing) {
        if (wave4 == null || wave5 == null) return List.of();
        double impulse = Math.abs(wave5.price() - wave0.price());
        int reference = Math.max(3, legCandles(wave0, wave5, spacing));
        BoundarySide correctionSide = sign > 0 ? BoundarySide.BELOW : BoundarySide.ABOVE;
        return List.of(
                correction("ZIGZAG_CORRECTION", "Preferred · zigzag correction",
                        "A direct A-B-C retracement toward 61.8% of the complete impulse.", 59,
                        wave5.price() - sign * impulse * .618,
                        "Correction at a 61.8% retracement of the complete impulse", reference, .18, .55,
                        null, null,
                        wave5.price() - sign * impulse * .786, correctionSide, source,
                        PathShape.ZIGZAG,
                        "A zigzag is the clearest sharp corrective path after five waves.",
                        "A 78.6% retracement discredits this measured endpoint."),
                correction("EXPANDED_FLAT_CORRECTION", "Alternate A · expanded flat",
                        "A sideways A-B-C path with B briefly exceeding Wave V before C declines.", 51,
                        wave5.price() - sign * impulse * .382,
                        "Expanded-flat C near a 38.2% impulse retracement", reference, .25, .75,
                        null, null,
                        wave5.price() - sign * impulse * .786, correctionSide, source,
                        PathShape.EXPANDED_FLAT,
                        "Expanded flats can briefly resume the old trend before Wave C reverses.",
                        "Its dashed path is schematic; the target zone is the evaluated forecast."),
                correction("COMBINATION_CORRECTION", "Alternate B · combination",
                        "A prolonged sideways combination returning toward prior Wave IV territory.", 43,
                        wave4.price(),
                        "Combination correction toward prior Wave IV territory", reference, .35, 1.0,
                        null, null,
                        wave5.price() - sign * impulse * .786, correctionSide, source,
                        PathShape.COMBINATION,
                        "Combinations explain corrections that consume time more than price.",
                        "This alternate is favored only while price remains broadly sideways."));
    }

    private static List<ScenarioSeed> resumptionSeeds(
            double source, double sign, Point wave0, Point wave5, Point waveC, long spacing) {
        if (wave5 == null || waveC == null) return List.of();
        double priorRange = Math.abs(wave5.price() - wave0.price());
        int reference = Math.max(3, legCandles(wave5, waveC, spacing));
        BoundarySide hardSide = sign > 0 ? BoundarySide.BELOW : BoundarySide.ABOVE;
        return List.of(
                motive("TREND_RESUMPTION", "Preferred · trend resumption",
                        "A new five-wave motive sequence retesting the prior Wave V extreme.", 58,
                        wave5.price(), "New motive wave toward the prior Wave V extreme",
                        reference, .7, 1.8, waveC.price(), hardSide, source,
                        "A completed A-B-C correction permits the primary trend to resume.",
                        "The correction endpoint is the initial structural boundary."),
                motive("BREAKOUT_RESUMPTION", "Alternate A · breakout extension",
                        "A stronger new motive sequence extending beyond the former Wave V.", 49,
                        wave5.price() + sign * priorRange * .272,
                        "Trend resumption to a 127.2% extension of the prior impulse range",
                        reference, 1.0, 2.4, waveC.price(), hardSide, source,
                        "A decisive break of Wave V can open an extension target.",
                        "Confidence should rise only after price clears the prior extreme."),
                motive("BASE_BUILDING_RESUMPTION", "Alternate B · base-building motive",
                        "A slower overlapping start before the new motive leg reaches 61.8% of the prior range.", 41,
                        waveC.price() + sign * priorRange * .618,
                        "Conservative resumption at 61.8% of the prior impulse range",
                        reference, 1.2, 3.0, waveC.price(), hardSide, source,
                        "Early overlap can indicate a slower base before trend resumption.",
                        "This is a lower-confidence path until a clean motive subdivision appears."));
    }

    private static ScenarioSeed motive(
            String key, String label, String description, int confidence, double target,
            String basis, int referenceCandles, double minFactor, double maxFactor,
            Double hardPrice, BoundarySide hardSide, double source, String... evidence) {
        int minimum = duration(referenceCandles, minFactor);
        int maximum = Math.max(minimum + 1, duration(referenceCandles, maxFactor));
        BoundarySide overshootSide = target >= source ? BoundarySide.ABOVE : BoundarySide.BELOW;
        double overshoot = target * (overshootSide == BoundarySide.ABOVE ? 1.03 : .97);
        return new ScenarioSeed(key, label, description, confidence, target, basis,
                minimum, maximum, hardPrice, hardSide, overshoot, overshootSide,
                PathShape.MOTIVE, List.of(evidence));
    }

    private static ScenarioSeed correction(
            String key, String label, String description, int confidence, double target,
            String basis, int referenceCandles, double minFactor, double maxFactor,
            Double hardPrice, BoundarySide hardSide, Double scenarioPrice,
            BoundarySide scenarioSide, double source, PathShape shape, String... evidence) {
        int minimum = duration(referenceCandles, minFactor);
        int maximum = Math.max(minimum + 1, duration(referenceCandles, maxFactor));
        return new ScenarioSeed(key, label, description, confidence, target, basis,
                minimum, maximum, hardPrice, hardSide, scenarioPrice, scenarioSide,
                shape, List.of(evidence));
    }

    private static List<ProjectedPoint> projectedPath(
            long sourceTimestamp, double sourcePrice, double target, long spacing,
            int maximumCandles, PathShape shape, TimeInterval interval) {
        double distance = target - sourcePrice;
        double[] progresses = switch (shape) {
            case MOTIVE -> new double[]{0, .24, .15, .64, .50, 1};
            case ZIGZAG -> new double[]{0, .64, .35, 1};
            case FLAT -> new double[]{0, .58, -.08, 1};
            case TRIANGLE -> new double[]{0, .55, .18, .43, .29, 1};
            case EXPANDED_FLAT -> new double[]{0, .42, -.18, 1};
            case COMBINATION -> new double[]{0, .42, .12, .62, .30, 1};
        };
        String[] labels = switch (shape) {
            case MOTIVE -> new String[]{"", "1", "2", "3", "4", "5"};
            case ZIGZAG, EXPANDED_FLAT -> new String[]{"", "A", "B", "C"};
            case FLAT -> new String[]{"", "A", "B", "C"};
            case TRIANGLE, COMBINATION -> new String[]{"", "A", "B", "C", "D", "E"};
        };
        int[] nodeOffsets = new int[progresses.length];
        for (int index = 0; index < progresses.length; index++) {
            nodeOffsets[index] = index == 0 ? 0
                    : Math.max(1, (int) Math.round(
                    maximumCandles * index / (double) (progresses.length - 1)));
        }
        List<ProjectedPoint> path = new ArrayList<>(maximumCandles + 1);
        int segment = 0;
        for (int candleOffset = 0; candleOffset <= maximumCandles; candleOffset++) {
            while (segment + 1 < nodeOffsets.length - 1
                    && candleOffset > nodeOffsets[segment + 1]) segment++;
            int fromOffset = nodeOffsets[segment];
            int toOffset = nodeOffsets[Math.min(segment + 1, nodeOffsets.length - 1)];
            double interpolation = toOffset == fromOffset ? 1.0
                    : (candleOffset - fromOffset) / (double) (toOffset - fromOffset);
            double progress = progresses[segment]
                    + (progresses[Math.min(segment + 1, progresses.length - 1)]
                    - progresses[segment]) * interpolation;
            String label = "";
            for (int node = 0; node < nodeOffsets.length; node++) {
                if (nodeOffsets[node] == candleOffset) {
                    label = labels[node];
                    break;
                }
            }
            path.add(new ProjectedPoint(candleOffset,
                    futureTimestamp(sourceTimestamp, candleOffset, spacing, interval),
                    Math.max(.000001, sourcePrice + distance * progress), label));
        }
        return List.copyOf(path);
    }

    private static long futureTimestamp(
            long sourceTimestamp, int candleOffset, long spacing, TimeInterval interval) {
        if (candleOffset == 0) return sourceTimestamp;
        java.time.ZonedDateTime time = java.time.Instant.ofEpochSecond(sourceTimestamp)
                .atZone(java.time.ZoneOffset.UTC);
        return switch (interval) {
            case DAILY -> {
                int remaining = candleOffset;
                while (remaining > 0) {
                    time = time.plusDays(1);
                    java.time.DayOfWeek day = time.getDayOfWeek();
                    if (day != java.time.DayOfWeek.SATURDAY
                            && day != java.time.DayOfWeek.SUNDAY) remaining--;
                }
                yield time.toEpochSecond();
            }
            case WEEKLY -> time.plusWeeks(candleOffset).toEpochSecond();
            case MONTHLY -> time.plusMonths(candleOffset).toEpochSecond();
            default -> sourceTimestamp + spacing * candleOffset;
        };
    }

    private static int duration(int reference, double factor) {
        return Math.max(2, Math.min(240, (int) Math.round(Math.max(2, reference) * factor)));
    }

    private static int legCandles(Point from, Point to, long spacing) {
        if (from == null || to == null || spacing <= 0) return 8;
        return Math.max(2, (int) Math.round(Math.abs(to.timestamp() - from.timestamp()) / (double) spacing));
    }

    private static long candleSpacing(List<Candle> candles, long throughTimestamp, TimeInterval interval) {
        if (candles != null) {
            List<Long> timestamps = candles.stream()
                    .filter(candle -> candle != null && candle.getTimestamp() != null
                            && candle.getTimestamp() <= throughTimestamp)
                    .map(Candle::getTimestamp).sorted().toList();
            List<Long> differences = new ArrayList<>();
            for (int index = Math.max(1, timestamps.size() - 20); index < timestamps.size(); index++) {
                long difference = timestamps.get(index) - timestamps.get(index - 1);
                if (difference > 0) differences.add(difference);
            }
            if (!differences.isEmpty()) {
                differences.sort(Comparator.naturalOrder());
                return differences.get(differences.size() / 2);
            }
        }
        return switch (interval) {
            case DAILY -> 86_400L;
            case WEEKLY -> 604_800L;
            case MONTHLY -> 2_592_000L;
            default -> 3_600L;
        };
    }

    private static Point point(List<ElliottWaveDetectionService.ElliottWavePoint> points, String label) {
        return points.stream()
                .filter(value -> value != null
                        && (label.equalsIgnoreCase(value.label())
                        || ("0".equals(label) && (value.label() == null || value.label().isBlank())))
                        && Double.isFinite(value.price()) && value.price() > 0.0)
                .map(value -> new Point(value.timestamp(), value.price()))
                .findFirst().orElse(null);
    }

    enum BoundarySide { BELOW, ABOVE }
    private enum PathShape { MOTIVE, ZIGZAG, FLAT, TRIANGLE, EXPANDED_FLAT, COMBINATION }
    private record Point(long timestamp, double price) { }

    record ProjectedPoint(int candleOffset, long timestamp, double price, String label) { }

    record Scenario(
            String key, String label, String description, int displayRank,
            TradeSignal expectedMove, int confidence, List<ProjectedPoint> path,
            double targetZoneLow, double targetZoneHigh, double targetMidpoint,
            String targetBasis, int minimumCandles, int maximumCandles,
            Double hardInvalidationPrice, BoundarySide hardInvalidationSide,
            Double scenarioInvalidationPrice, BoundarySide scenarioInvalidationSide,
            List<String> evidence) { }

    private record ScenarioSeed(
            String key, String label, String description, int confidence, double target,
            String targetBasis, int minimumCandles, int maximumCandles,
            Double hardInvalidationPrice, BoundarySide hardInvalidationSide,
            Double scenarioInvalidationPrice, BoundarySide scenarioInvalidationSide,
            PathShape pathShape, List<String> evidence) { }
}
