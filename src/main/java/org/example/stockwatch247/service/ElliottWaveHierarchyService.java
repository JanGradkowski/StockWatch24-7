package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ElliottWaveHierarchyService {
    private static final int PAGE_SIZE = 1_000;
    private static final int MAX_PAGES_PER_INTERVAL = 12;
    private static final int MAX_MONTHLY_BASELINES = 12;

    private final MarketDataService marketDataService;
    private final CandleCompletionService candleCompletionService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final ElliottWaveDetectionService detectionService;

    public ElliottWaveHierarchyService(MarketDataService marketDataService,
                                       CandleCompletionService candleCompletionService,
                                       TechnicalIndicatorEnrichmentService enrichmentService,
                                       ElliottWaveDetectionService detectionService) {
        this.marketDataService = marketDataService;
        this.candleCompletionService = candleCompletionService;
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
    }

    public HierarchyView build(String rawSymbol,
                               Long requestedAsOfExclusive,
                               ElliottWaveDetectionService.DetectionRules monthlyRules,
                               ElliottWaveDetectionService.DetectionRules weeklyRules) {
        return build(rawSymbol, requestedAsOfExclusive, monthlyRules, weeklyRules, weeklyRules);
    }

    public HierarchyView build(String rawSymbol,
                               Long requestedAsOfExclusive,
                               ElliottWaveDetectionService.DetectionRules monthlyRules,
                               ElliottWaveDetectionService.DetectionRules weeklyRules,
                               ElliottWaveDetectionService.DetectionRules dailyRules) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        long completedDailyBoundary = candleCompletionService
                .firstIncompleteCandleTimestamp(TimeInterval.DAILY);
        long asOfExclusive = requestedAsOfExclusive == null
                ? completedDailyBoundary
                : Math.min(requestedAsOfExclusive, completedDailyBoundary);
        if (asOfExclusive <= 0L) throw new IllegalArgumentException("Invalid Elliott hierarchy boundary.");

        List<Candle> monthlyCandles = loadRange(symbol, "1mo", null, asOfExclusive);
        if (monthlyCandles.size() < 34) {
            return unavailable(symbol, asOfExclusive,
                    "Not enough completed monthly candles are available to establish the macro baseline.");
        }
        ElliottWaveDetectionService monthlyDetector = detector(monthlyRules);
        List<ElliottWaveDetectionService.ElliottWaveStructure> detectedBaselines = monthlyDetector
                .findHistoricalWaveStructures(enrich(monthlyCandles, TimeInterval.MONTHLY))
                .stream()
                .filter(this::passesMonthlyHardRules)
                .sorted(Comparator.comparingLong(this::structureEndTime).reversed()
                        .thenComparing(Comparator.comparingInt(
                                ElliottWaveDetectionService.ElliottWaveStructure::qualityScore).reversed()))
                .toList();
        Map<String, ElliottWaveDetectionService.ElliottWaveStructure> distinctBaselines = new LinkedHashMap<>();
        detectedBaselines.forEach(baseline -> distinctBaselines.putIfAbsent(
                cycleKey(monthlyDetector, baseline), baseline));
        List<ElliottWaveDetectionService.ElliottWaveStructure> baselines = distinctBaselines.values()
                .stream().limit(MAX_MONTHLY_BASELINES).toList();
        if (baselines.isEmpty()) {
            return unavailable(symbol, asOfExclusive,
                    "No completed monthly count passes the Elliott hard constraints.");
        }

        long earliestBaseline = baselines.stream().mapToLong(this::structureStartTime).min().orElseThrow();
        List<Candle> weeklyCandles = loadRange(symbol, "1wk", earliestBaseline, asOfExclusive);
        List<Candle> dailyCandles = loadRange(symbol, "1d", earliestBaseline, asOfExclusive);
        ElliottWaveDetectionService weeklyDetector = detector(weeklyRules);
        ElliottWaveDetectionService dailyDetector = detector(dailyRules);

        List<Wave> populatedHierarchies = new ArrayList<>();
        int bestQuality = 0;
        String latestDirection = baselines.getFirst().direction();
        for (ElliottWaveDetectionService.ElliottWaveStructure baseline : baselines) {
            List<Wave> monthlyWaves = rootWaves(baseline);
            List<Wave> populated = new ArrayList<>();
            for (Wave monthlyWave : monthlyWaves) {
                populated.add(populateMonthlyWave(
                        monthlyWave, weeklyCandles, dailyCandles, weeklyDetector, dailyDetector));
            }
            populatedHierarchies.addAll(populated);
            bestQuality = Math.max(bestQuality, baseline.qualityScore());
        }
        return new HierarchyView(true, symbol, asOfExclusive, latestDirection,
                bestQuality, List.copyOf(populatedHierarchies), null);
    }

    private Wave populateMonthlyWave(Wave parent,
                                     List<Candle> weeklyCandles,
                                     List<Candle> dailyCandles,
                                     ElliottWaveDetectionService weeklyDetector,
                                     ElliottWaveDetectionService dailyDetector) {
        List<Candle> weeklySlice = slice(weeklyCandles, parent);
        List<EnrichedCandle> enrichedWeekly = enrich(weeklySlice, TimeInterval.WEEKLY);
        Wave bestValidatedWeeklyCount = parent;
        int bestDailyBranches = -1;
        for (ElliottWaveDetectionService.ElliottSubdivision candidate : weeklyDetector.findStrictSubdivisions(
                enrichedWeekly, parent.degreeLabel(), parent.startPrice(), parent.endPrice())) {
            List<Wave> weeklyWaves = childWaves(candidate, Timeframe.WEEKLY, parent);
            if (weeklyWaves.isEmpty()) continue;
            List<Wave> populatedWeekly = new ArrayList<>();
            int dailyBranches = 0;
            for (Wave weeklyWave : weeklyWaves) {
                Optional<Wave> expanded = populateWeeklyWave(weeklyWave, dailyCandles, dailyDetector);
                populatedWeekly.add(expanded.orElse(weeklyWave));
                if (expanded.isPresent()) dailyBranches++;
            }
            Wave populatedCandidate = parent.withSubwaves(populatedWeekly);
            if (dailyBranches == weeklyWaves.size()) return populatedCandidate;
            if (dailyBranches > bestDailyBranches) {
                bestDailyBranches = dailyBranches;
                bestValidatedWeeklyCount = populatedCandidate;
            }
        }
        return bestValidatedWeeklyCount;
    }

    private Optional<Wave> populateWeeklyWave(Wave parent,
                                               List<Candle> dailyCandles,
                                               ElliottWaveDetectionService detector) {
        List<Candle> dailySlice = slice(dailyCandles, parent);
        List<EnrichedCandle> enrichedDaily = enrich(dailySlice, TimeInterval.DAILY);
        return detector.findStrictSubdivisions(
                        enrichedDaily, parent.degreeLabel(), parent.startPrice(), parent.endPrice())
                .stream()
                .findFirst()
                .map(candidate -> childWaves(candidate, Timeframe.DAILY, parent))
                .filter(children -> !children.isEmpty())
                .map(parent::withSubwaves);
    }

    private List<Wave> rootWaves(ElliottWaveDetectionService.ElliottWaveStructure structure) {
        List<ElliottWaveDetectionService.ElliottWavePoint> points = structure.points();
        List<Wave> waves = new ArrayList<>();
        String cycleKey = structure.direction() + ":" + structureStartTime(structure)
                + ":" + structureEndTime(structure);
        for (int index = 1; index < points.size(); index++) {
            ElliottWaveDetectionService.ElliottWavePoint start = points.get(index - 1);
            ElliottWaveDetectionService.ElliottWavePoint end = points.get(index);
            String label = normalizeDegreeLabel(end.label());
            waves.add(new Wave(cycleKey, Timeframe.MONTHLY, label,
                    rootChildNature(structure, label),
                    start.price(), end.price(), start.timestamp(), end.timestamp(), List.of()));
        }
        return List.copyOf(waves);
    }

    private WaveNature rootChildNature(
            ElliottWaveDetectionService.ElliottWaveStructure structure,
            String label) {
        String normalized = normalizeDegreeLabel(label);
        if (!structure.correctionComplete() || java.util.Set.of("1", "2", "3", "4", "5")
                .contains(normalized)) return nature(normalized);
        String prefix = normalized.contains(".")
                ? normalized.substring(0, normalized.lastIndexOf('.') + 1) : "";
        boolean triangleComponent = structure.points().stream()
                .map(ElliottWaveDetectionService.ElliottWavePoint::label)
                .filter(java.util.Objects::nonNull)
                .map(this::normalizeDegreeLabel)
                .anyMatch(candidate -> candidate.equals(prefix + "D") || candidate.equals(prefix + "E"));
        if (triangleComponent) return WaveNature.CORRECTIVE;
        String component = normalized.contains(".")
                ? normalized.substring(normalized.lastIndexOf('.') + 1) : normalized;
        if ((structure.correctionVariant() == ElliottWaveDetectionService.CorrectionVariant.EXPANDED_FLAT
                || structure.correctionVariant() == ElliottWaveDetectionService.CorrectionVariant.RUNNING_FLAT)
                && component.equals("A")) return WaveNature.CORRECTIVE;
        return nature(normalized);
    }

    private List<Wave> childWaves(ElliottWaveDetectionService.ElliottSubdivision subdivision,
                                  Timeframe timeframe,
                                  Wave parent) {
        List<ElliottWaveDetectionService.ElliottWavePoint> points = subdivision.points();
        if (points.size() < 2) return List.of();
        if (parent.nature() == WaveNature.MOTIVE && points.size() != 6) return List.of();
        List<Wave> waves = new ArrayList<>();
        for (int index = 1; index < points.size(); index++) {
            ElliottWaveDetectionService.ElliottWavePoint start = points.get(index - 1);
            ElliottWaveDetectionService.ElliottWavePoint end = points.get(index);
            String label = parent.nature() == WaveNature.MOTIVE
                    ? Integer.toString(index)
                    : normalizeDegreeLabel(end.label());
            long startTime = start.timestamp();
            long endTime = end.timestamp();
            long parentEndExclusive = periodEndExclusive(parent.endTime(), parent.timeframe());
            if (startTime >= endTime || startTime < parent.startTime() || endTime >= parentEndExclusive) {
                return List.of();
            }
            waves.add(new Wave(parent.cycleKey(), timeframe, label,
                    childNature(subdivision.structureLabel(), label), start.price(), end.price(),
                    startTime, endTime, List.of()));
        }
        if (parent.nature() == WaveNature.MOTIVE && !waveThreeIsNotShortest(waves)) {
            return List.of();
        }
        return List.copyOf(waves);
    }

    private WaveNature childNature(String structureLabel, String label) {
        String structure = structureLabel == null
                ? "" : structureLabel.toLowerCase(java.util.Locale.ROOT);
        String normalized = normalizeDegreeLabel(label);
        String component = normalized.contains(".")
                ? normalized.substring(normalized.lastIndexOf('.') + 1) : normalized;
        if (structure.contains("triangle")) return WaveNature.CORRECTIVE;
        if (component.equals("X") || component.matches("X[0-9]+")
                || component.equals("B") || component.equals("D") || component.equals("E")) {
            return WaveNature.CORRECTIVE;
        }
        if (structure.contains("flat") && component.equals("A")) return WaveNature.CORRECTIVE;
        return nature(component);
    }

    static boolean waveThreeIsNotShortest(List<Wave> waves) {
        if (waves == null || waves.size() != 5) return false;
        double waveOne = waveLength(waves.get(0));
        double waveThree = waveLength(waves.get(2));
        double waveFive = waveLength(waves.get(4));
        double comparisonTolerance = Math.max(Math.max(waveOne, waveThree), waveFive) * 1.0e-9;
        return Double.isFinite(waveOne) && Double.isFinite(waveThree) && Double.isFinite(waveFive)
                && waveOne > 0.0 && waveThree > 0.0 && waveFive > 0.0
                && !(waveThree + comparisonTolerance < waveOne
                && waveThree + comparisonTolerance < waveFive);
    }

    private static double waveLength(Wave wave) {
        return Math.abs(wave.endPrice() - wave.startPrice());
    }

    private boolean passesMonthlyHardRules(ElliottWaveDetectionService.ElliottWaveStructure structure) {
        List<ElliottWaveDetectionService.ElliottWavePoint> points = structure.points();
        if (points.size() < 6) return false;
        ElliottWaveDetectionService.ElliottWavePoint w0 = points.get(0);
        ElliottWaveDetectionService.ElliottWavePoint w1 = points.get(1);
        ElliottWaveDetectionService.ElliottWavePoint w2 = points.get(2);
        ElliottWaveDetectionService.ElliottWavePoint w3 = points.get(3);
        ElliottWaveDetectionService.ElliottWavePoint w4 = points.get(4);
        ElliottWaveDetectionService.ElliottWavePoint w5 = points.get(5);
        boolean bullish = "BULLISH".equalsIgnoreCase(structure.direction());
        boolean retracementAndOverlap = bullish
                ? w2.price() > w0.price() && w4.price() > w1.price()
                : w2.price() < w0.price() && w4.price() < w1.price();
        double one = Math.abs(w1.price() - w0.price());
        double three = Math.abs(w3.price() - w2.price());
        double five = Math.abs(w5.price() - w4.price());
        return retracementAndOverlap && three >= Math.min(one, five);
    }

    private List<Candle> loadRange(String symbol, String interval, Long fromInclusive, long toExclusive) {
        Map<Long, Candle> candles = new LinkedHashMap<>();
        Long cursor = toExclusive;
        for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_INTERVAL; pageNumber++) {
            MarketDataService.CandlePage page = marketDataService.loadCandlePage(
                    symbol, interval, cursor, PAGE_SIZE);
            page.candles().stream().filter(this::validCandle)
                    .filter(candle -> candle.getTimestamp() < toExclusive)
                    .filter(candle -> periodEndExclusive(
                            candle.getTimestamp(), timeframe(interval)) <= toExclusive)
                    .filter(candle -> fromInclusive == null || candle.getTimestamp() >= fromInclusive)
                    .forEach(candle -> candles.put(candle.getTimestamp(), candle));
            if (fromInclusive == null) break;
            Long next = page.nextCursor();
            if (!page.hasMore() || next == null || next >= cursor
                    || fromInclusive != null && next <= fromInclusive) break;
            cursor = next;
        }
        return candles.values().stream().sorted(Comparator.comparing(Candle::getTimestamp)).toList();
    }

    private List<Candle> slice(List<Candle> candles, Wave parent) {
        long endExclusive = periodEndExclusive(parent.endTime(), parent.timeframe());
        return candles.stream()
                .filter(candle -> candle.getTimestamp() >= parent.startTime())
                .filter(candle -> periodEndExclusive(candle.getTimestamp(), childTimeframe(parent.timeframe()))
                        <= endExclusive)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
    }

    private Timeframe childTimeframe(Timeframe parentTimeframe) {
        return switch (parentTimeframe) {
            case MONTHLY -> Timeframe.WEEKLY;
            case WEEKLY -> Timeframe.DAILY;
            case DAILY -> throw new IllegalArgumentException("Daily Elliott waves do not have a configured child timeframe.");
        };
    }

    private List<EnrichedCandle> enrich(List<Candle> candles, TimeInterval interval) {
        return enrichmentService.enrichForElliott(candles, candles.size(), interval);
    }

    private ElliottWaveDetectionService detector(ElliottWaveDetectionService.DetectionRules rules) {
        return rules == null ? detectionService : detectionService.configured(rules);
    }

    private long periodEndExclusive(long timestamp, Timeframe timeframe) {
        LocalDate date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate exclusive = switch (timeframe) {
            case MONTHLY -> date.plusMonths(1).withDayOfMonth(1);
            case WEEKLY -> date.plusWeeks(1);
            case DAILY -> date.plusDays(1);
        };
        return exclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private boolean validCandle(Candle candle) {
        return candle != null && candle.getTimestamp() != null
                && candle.getOpenPrice() != null && Double.isFinite(candle.getOpenPrice())
                && candle.getHighPrice() != null && Double.isFinite(candle.getHighPrice())
                && candle.getLowPrice() != null && Double.isFinite(candle.getLowPrice())
                && candle.getClosePrice() != null && Double.isFinite(candle.getClosePrice())
                && candle.getHighPrice() >= candle.getLowPrice();
    }

    private Timeframe timeframe(String interval) {
        return switch (interval) {
            case "1mo" -> Timeframe.MONTHLY;
            case "1wk" -> Timeframe.WEEKLY;
            case "1d" -> Timeframe.DAILY;
            default -> throw new IllegalArgumentException("Unsupported Elliott hierarchy interval.");
        };
    }

    private long structureStartTime(ElliottWaveDetectionService.ElliottWaveStructure structure) {
        return structure.points().getFirst().timestamp();
    }

    private long structureEndTime(ElliottWaveDetectionService.ElliottWaveStructure structure) {
        return structure.points().getLast().timestamp();
    }

    private String cycleKey(ElliottWaveDetectionService detector,
                            ElliottWaveDetectionService.ElliottWaveStructure structure) {
        return detector.lifecycleCycleKey(structure).orElseGet(() ->
                structure.direction() + ":" + structureStartTime(structure)
                        + ":" + structureEndTime(structure));
    }

    private String normalizeDegreeLabel(String label) {
        return switch (label == null ? "" : label.trim().toUpperCase()) {
            case "I" -> "1";
            case "II" -> "2";
            case "III" -> "3";
            case "IV" -> "4";
            case "V" -> "5";
            default -> label == null ? "" : label.trim().toUpperCase();
        };
    }

    private WaveNature nature(String label) {
        String normalized = normalizeDegreeLabel(label);
        if (normalized.contains(".")) {
            normalized = normalized.substring(normalized.lastIndexOf('.') + 1);
        }
        if (normalized.equals("X") || normalized.matches("X[0-9]+")) {
            return WaveNature.CORRECTIVE;
        }
        return switch (normalized) {
            case "1", "3", "5", "A", "C" -> WaveNature.MOTIVE;
            case "2", "4", "B", "D", "E" -> WaveNature.CORRECTIVE;
            default -> throw new IllegalArgumentException("Unsupported Elliott degree label: " + label);
        };
    }

    private HierarchyView unavailable(String symbol, long asOfExclusive, String reason) {
        return new HierarchyView(false, symbol, asOfExclusive, null, 0, List.of(), reason);
    }

    public enum Timeframe { MONTHLY, WEEKLY, DAILY }
    public enum WaveNature { MOTIVE, CORRECTIVE }

    public record Wave(String cycleKey,
                       Timeframe timeframe,
                       String degreeLabel,
                       WaveNature nature,
                       double startPrice,
                       double endPrice,
                       long startTime,
                       long endTime,
                       List<Wave> subwaves) {
        public Wave {
            subwaves = subwaves == null ? List.of() : List.copyOf(subwaves);
        }

        public Wave withSubwaves(List<Wave> children) {
            return new Wave(cycleKey, timeframe, degreeLabel, nature, startPrice, endPrice,
                    startTime, endTime, children);
        }
    }

    public record HierarchyView(boolean available,
                                String symbol,
                                long asOfExclusive,
                                String macroDirection,
                                int macroQuality,
                                List<Wave> waves,
                                String unavailableReason) {
        public HierarchyView {
            waves = waves == null ? List.of() : List.copyOf(waves);
        }
    }
}
