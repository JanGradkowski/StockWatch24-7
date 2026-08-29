package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic structural-recall benchmark for the developing Elliott-cycle detector.
 *
 * <p>This deliberately remains opt-in: it generates and detects 1,000 complete,
 * nested impulses and is intended for research runs rather than every Maven test run.</p>
 */
@EnabledIfSystemProperty(named = "elliott.synthetic.benchmark", matches = "true")
class DevelopingElliottSyntheticRecallBenchmarkTest {
    private static final long DEFAULT_SEED = 0xE11077L;
    private static final int DEFAULT_CASES = 1_000;
    private static final List<String> PARENT_LABELS = List.of("0", "I", "II", "III", "IV", "V");

    private final ElliottWaveDetectionService detectionService = new ElliottWaveDetectionService();

    @Test
    void recoversOneThousandCompleteNestedImpulses() {
        int caseCount = Integer.getInteger("elliott.synthetic.count", DEFAULT_CASES);
        long seed = Long.getLong("elliott.synthetic.seed", DEFAULT_SEED);
        Random random = new Random(seed);
        Map<String, Tally> breakdown = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int recovered = 0;
        int hypothesisRecovered = 0;
        int cycleRecovered = 0;
        int nestedLegSetsRecovered = 0;

        for (int caseNumber = 1; caseNumber <= caseCount; caseNumber++) {
            boolean bullish = caseNumber % 2 == 1;
            CorrectionShape waveTwoShape = CorrectionShape.values()[(caseNumber - 1) % 3];
            CorrectionShape waveFourShape = CorrectionShape.values()[(caseNumber - 1) % 4];
            SyntheticImpulse generated = generate(caseNumber, bullish, waveTwoShape, waveFourShape, random);
            LegRecognition legRecognition = recognizeIntendedLegs(generated);
            List<ElliottWaveDetectionService.DevelopingImpulse> candidates =
                    detectionService.findDevelopingImpulses(generated.candles());
            List<ElliottWaveDetectionService.DevelopingImpulse> hypotheses =
                    detectionService.findDevelopingImpulseHypotheses(generated.candles());
            ElliottWaveDetectionService.DevelopingImpulse exact = candidates.stream()
                    .filter(candidate -> isExactRecovery(candidate, generated))
                    .findFirst()
                    .orElse(null);
            boolean exactHypothesis = hypotheses.stream()
                    .anyMatch(candidate -> isExactRecovery(candidate, generated));

            String bucket = generated.direction() + " / II=" + waveTwoShape.label
                    + " / IV=" + waveFourShape.label;
            Tally tally = breakdown.computeIfAbsent(bucket, ignored -> new Tally());
            tally.total++;
            if (legRecognition.allRecognized()) {
                nestedLegSetsRecovered++;
                tally.nestedLegSetsRecovered++;
            }
            if (candidates.stream().anyMatch(candidate -> isCycleRecovery(candidate, generated))) {
                cycleRecovered++;
                tally.cycleRecovered++;
            }
            if (exact != null) {
                recovered++;
                tally.recovered++;
            } else if (failures.size() < 10) {
                failures.add(describeFailure(generated, candidates, legRecognition.labels()));
            }
            if (exactHypothesis) {
                hypothesisRecovered++;
                tally.hypothesisRecovered++;
            } else if (failures.size() < 10) {
                failures.add(describeFailure(generated, hypotheses, legRecognition.labels()));
            }
        }

        String report = renderReport(caseCount, seed, recovered, hypothesisRecovered, cycleRecovered,
                nestedLegSetsRecovered, breakdown, failures);
        System.out.println(report);
        assertThat(hypothesisRecovered)
                .withFailMessage("%s", report)
                .isEqualTo(caseCount);
    }

    private boolean isCycleRecovery(ElliottWaveDetectionService.DevelopingImpulse candidate,
                                    SyntheticImpulse generated) {
        if (candidate.stage() != ElliottSignalStage.WAVE_V_END
                || !candidate.direction().equals(generated.direction())
                || candidate.points().size() != PARENT_LABELS.size()
                || candidate.completedStructure() == null) {
            return false;
        }
        return Objects.equals(candidate.points().get(0).timestamp(), generated.parentTimestamps().get(0))
                && Objects.equals(candidate.points().get(1).timestamp(), generated.parentTimestamps().get(1))
                && Objects.equals(candidate.points().get(5).timestamp(), generated.parentTimestamps().get(5))
                && PARENT_LABELS.subList(1, PARENT_LABELS.size()).stream()
                .allMatch(label -> candidate.evidence().stream()
                        .anyMatch(entry -> entry.startsWith("Wave " + label + ":")));
    }

    private boolean isExactRecovery(ElliottWaveDetectionService.DevelopingImpulse candidate,
                                    SyntheticImpulse generated) {
        if (candidate.stage() != ElliottSignalStage.WAVE_V_END
                || !candidate.direction().equals(generated.direction())
                || candidate.completedStructure() == null
                || candidate.points().size() != PARENT_LABELS.size()) {
            return false;
        }
        for (int index = 0; index < PARENT_LABELS.size(); index++) {
            ElliottWaveDetectionService.ElliottWavePoint point = candidate.points().get(index);
            if (!point.label().equals(PARENT_LABELS.get(index))
                    || !Objects.equals(point.timestamp(), generated.parentTimestamps().get(index))) {
                return false;
            }
        }
        return PARENT_LABELS.subList(1, PARENT_LABELS.size()).stream()
                .allMatch(label -> candidate.evidence().stream()
                        .anyMatch(entry -> entry.startsWith("Wave " + label + ":")));
    }

    private SyntheticImpulse generate(int caseNumber,
                                      boolean bullish,
                                      CorrectionShape waveTwoShape,
                                      CorrectionShape waveFourShape,
                                      Random random) {
        double direction = bullish ? 1.0 : -1.0;
        double origin = between(random, 80.0, 260.0);
        double waveOneLength = origin * between(random, .32, .40);
        double waveTwoDepth = waveOneLength * between(random, .40, .62);
        double waveThreeLength = waveOneLength * between(random, 1.55, 2.10);
        double waveFourDepth = waveThreeLength * between(random, .24, .34);
        double waveFiveLength = waveOneLength * between(random, .72, 1.24);

        double waveOne = origin + direction * waveOneLength;
        double waveTwo = waveOne - direction * waveTwoDepth;
        double waveThree = waveTwo + direction * waveThreeLength;
        double waveFour = waveThree - direction * waveFourDepth;
        double waveFive = waveFour + direction * waveFiveLength;

        // A triangle's A extreme is 1.6x its net parent correction. Keep it outside Wave I territory.
        if (waveFourShape == CorrectionShape.TRIANGLE) {
            double marginFromWaveOne = Math.abs(waveThree - waveOne);
            waveFourDepth = Math.min(waveFourDepth, marginFromWaveOne * .54);
            waveFour = waveThree - direction * waveFourDepth;
            waveFive = waveFour + direction * waveFiveLength;
        }

        List<Anchor> anchors = new ArrayList<>();
        int index = 1;
        anchors.add(new Anchor(index, origin + direction * waveOneLength * .35));
        index += duration(random);
        anchors.add(new Anchor(index, origin));

        List<Long> parentTimestamps = new ArrayList<>();
        parentTimestamps.add((long) index);
        index = appendShape(anchors, index, motive(origin, waveOne), random);
        parentTimestamps.add((long) index);
        index = appendShape(anchors, index, correction(waveOne, waveTwo, waveTwoShape), random);
        parentTimestamps.add((long) index);
        index = appendShape(anchors, index, motive(waveTwo, waveThree), random);
        parentTimestamps.add((long) index);
        index = appendShape(anchors, index, correction(waveThree, waveFour, waveFourShape), random);
        parentTimestamps.add((long) index);
        index = appendShape(anchors, index, motive(waveFour, waveFive), random);
        parentTimestamps.add((long) index);

        // Confirm the final pivot on the immediately following candle.
        anchors.add(new Anchor(index + 1, waveFive - direction * waveOneLength * .10));
        List<EnrichedCandle> candles = interpolate(anchors, origin);
        List<Double> parentPrices = new ArrayList<>();
        for (int point = 0; point < parentTimestamps.size(); point++) {
            EnrichedCandle candle = candleAt(candles, parentTimestamps.get(point));
            boolean high = bullish ? point % 2 == 1 : point % 2 == 0;
            parentPrices.add(high ? candle.high() : candle.low());
        }
        return new SyntheticImpulse(
                caseNumber,
                bullish ? "BULLISH" : "BEARISH",
                waveTwoShape,
                waveFourShape,
                candles,
                List.copyOf(parentTimestamps),
                List.copyOf(parentPrices));
    }

    private int appendShape(List<Anchor> anchors,
                            int currentIndex,
                            List<Double> shape,
                            Random random) {
        int index = currentIndex;
        for (int point = 1; point < shape.size(); point++) {
            index += duration(random);
            anchors.add(new Anchor(index, shape.get(point)));
        }
        return index;
    }

    private List<Double> motive(double start, double end) {
        return projected(start, end, 0.0, .30, .18, .75, .58, 1.0);
    }

    private List<Double> correction(double start, double end, CorrectionShape shape) {
        return switch (shape) {
            case ABC -> projected(start, end, 0.0, .65, .30, 1.0);
            case WXY -> projected(start, end, 0.0, .35, .15, .50, .25, .65, .45, 1.0);
            case WXYXZ -> projected(start, end,
                    0.0, .25, .10, .40, .20, .50, .30, .70, .45, .75, .58, 1.0);
            case TRIANGLE -> projected(start, end, 0.0, 1.60, .45, 1.30, .65, 1.0);
        };
    }

    private List<Double> projected(double start, double end, double... fractions) {
        List<Double> points = new ArrayList<>(fractions.length);
        for (double fraction : fractions) {
            points.add(start + (end - start) * fraction);
        }
        return List.copyOf(points);
    }

    private List<EnrichedCandle> interpolate(List<Anchor> anchors, double origin) {
        List<EnrichedCandle> candles = new ArrayList<>();
        double wick = Math.max(.05, origin * .0006);
        for (int segment = 0; segment < anchors.size() - 1; segment++) {
            Anchor start = anchors.get(segment);
            Anchor end = anchors.get(segment + 1);
            int from = segment == 0 ? start.index() : start.index() + 1;
            for (int index = from; index <= end.index(); index++) {
                double progress = (index - start.index()) / (double) (end.index() - start.index());
                double close = start.price() + (end.price() - start.price()) * progress;
                candles.add(candle(index, close, wick));
            }
        }
        return List.copyOf(candles);
    }

    private EnrichedCandle candle(long timestamp, double close, double wick) {
        return new EnrichedCandle(
                timestamp,
                close,
                close + wick,
                close - wick,
                close,
                1_500.0,
                1_000.0,
                50.0,
                close,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Math.max(.05, wick * 2.0));
    }

    private LegRecognition recognizeIntendedLegs(SyntheticImpulse generated) {
        List<String> labels = new ArrayList<>();
        boolean allRecognized = true;
        for (int leg = 0; leg < 5; leg++) {
            long start = generated.parentTimestamps().get(leg);
            long end = generated.parentTimestamps().get(leg + 1);
            int from = candleIndexAt(generated.candles(), start);
            int to = candleIndexAt(generated.candles(), end);
            List<String> structures = detectionService.findStrictSubdivisions(
                            generated.candles().subList(from, to + 1),
                            PARENT_LABELS.get(leg + 1),
                            generated.parentPrices().get(leg),
                            generated.parentPrices().get(leg + 1)).stream()
                    .map(ElliottWaveDetectionService.ElliottSubdivision::structureLabel)
                    .distinct().toList();
            CorrectionShape correctionShape = leg == 1 ? generated.waveTwoShape()
                    : leg == 3 ? generated.waveFourShape() : null;
            boolean recognized = correctionShape == null
                    ? structures.stream().anyMatch(label -> label.contains("Motive"))
                    : structures.stream().anyMatch(label -> matchesShape(label, correctionShape));
            allRecognized &= recognized;
            labels.add(PARENT_LABELS.get(leg + 1) + '=' + structures);
        }
        return new LegRecognition(allRecognized, List.copyOf(labels));
    }

    private boolean matchesShape(String label, CorrectionShape shape) {
        return switch (shape) {
            case ABC -> label.equals("Corrective zigzag A-B-C");
            case WXY -> label.equals("Double zigzag W-X-Y");
            case WXYXZ -> label.equals("Triple zigzag W-X-Y-X-Z");
            case TRIANGLE -> label.toLowerCase(Locale.ROOT).contains("triangle");
        };
    }

    private String describeFailure(SyntheticImpulse generated,
                                   List<ElliottWaveDetectionService.DevelopingImpulse> candidates,
                                   List<String> intendedLegs) {
        Map<ElliottSignalStage, Integer> stages = new EnumMap<>(ElliottSignalStage.class);
        for (ElliottWaveDetectionService.DevelopingImpulse candidate : candidates) {
            stages.merge(candidate.stage(), 1, Integer::sum);
        }
        List<String> waveFiveEndpoints = candidates.stream()
                .filter(candidate -> candidate.stage() == ElliottSignalStage.WAVE_V_END)
                .limit(4)
                .map(candidate -> candidate.direction() + candidate.points().stream()
                        .map(point -> Long.toString(point.timestamp()))
                        .toList())
                .toList();
        return "case=" + generated.caseNumber()
                + ", direction=" + generated.direction()
                + ", II=" + generated.waveTwoShape().label
                + ", IV=" + generated.waveFourShape().label
                + ", expected=" + generated.parentTimestamps()
                + ", intended legs=" + intendedLegs
                + ", stages=" + stages
                + ", WaveV endpoints=" + waveFiveEndpoints;
    }

    private EnrichedCandle candleAt(List<EnrichedCandle> candles, long timestamp) {
        return candles.get(candleIndexAt(candles, timestamp));
    }

    private int candleIndexAt(List<EnrichedCandle> candles, long timestamp) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == timestamp) return index;
        }
        throw new IllegalArgumentException("Missing synthetic candle " + timestamp);
    }

    private String renderReport(int caseCount,
                                long seed,
                                int recovered,
                                int hypothesisRecovered,
                                int cycleRecovered,
                                int nestedLegSetsRecovered,
                                Map<String, Tally> breakdown,
                                List<String> failures) {
        StringBuilder report = new StringBuilder();
        report.append(System.lineSeparator())
                .append("Synthetic developing-Elliott recall benchmark").append(System.lineSeparator())
                .append("seed: ").append(seed).append(System.lineSeparator())
                .append("cases: ").append(caseCount).append(System.lineSeparator())
                .append("all five intended nested leg shapes recognized: ")
                .append(nestedLegSetsRecovered).append('/').append(caseCount)
                .append(" (").append(String.format(Locale.ROOT, "%.2f",
                        nestedLegSetsRecovered * 100.0 / caseCount)).append("%)")
                .append(System.lineSeparator())
                .append("parent cycle endpoints recovered (0/I/V): ")
                .append(cycleRecovered).append('/').append(caseCount)
                .append(" (").append(String.format(Locale.ROOT, "%.2f",
                        cycleRecovered * 100.0 / caseCount)).append("%)")
                .append(System.lineSeparator())
                .append("exact intended count retained among hypotheses: ")
                .append(hypothesisRecovered).append('/').append(caseCount)
                .append(" (").append(String.format(Locale.ROOT, "%.2f",
                        hypothesisRecovered * 100.0 / caseCount)).append("%)")
                .append(System.lineSeparator())
                .append("exact recoveries: ").append(recovered).append('/').append(caseCount)
                .append(" (").append(String.format(Locale.ROOT, "%.2f", recovered * 100.0 / caseCount))
                .append("%)").append(System.lineSeparator())
                .append("breakdown:").append(System.lineSeparator());
        breakdown.forEach((bucket, tally) -> report.append("  ")
                .append(bucket).append(": exact ").append(tally.recovered).append('/')
                .append(tally.total).append(", hypothesis ").append(tally.hypothesisRecovered).append('/')
                .append(tally.total).append(", cycle ").append(tally.cycleRecovered).append('/')
                .append(tally.total).append(", nested ").append(tally.nestedLegSetsRecovered).append('/')
                .append(tally.total).append(System.lineSeparator()));
        if (!failures.isEmpty()) {
            report.append("first failures:").append(System.lineSeparator());
            failures.forEach(failure -> report.append("  ").append(failure).append(System.lineSeparator()));
        }
        return report.toString();
    }

    private int duration(Random random) {
        return 4 + random.nextInt(4);
    }

    private double between(Random random, double minimum, double maximum) {
        return minimum + random.nextDouble() * (maximum - minimum);
    }

    private enum CorrectionShape {
        ABC("ABC"),
        WXY("W-X-Y"),
        WXYXZ("W-X-Y-X-Z"),
        TRIANGLE("triangle");

        private final String label;

        CorrectionShape(String label) {
            this.label = label;
        }
    }

    private record Anchor(int index, double price) {
    }

    private record SyntheticImpulse(int caseNumber,
                                    String direction,
                                    CorrectionShape waveTwoShape,
                                    CorrectionShape waveFourShape,
                                    List<EnrichedCandle> candles,
                                    List<Long> parentTimestamps,
                                    List<Double> parentPrices) {
    }

    private record LegRecognition(boolean allRecognized, List<String> labels) {
    }

    private static final class Tally {
        private int total;
        private int recovered;
        private int hypothesisRecovered;
        private int cycleRecovered;
        private int nestedLegSetsRecovered;
    }
}
