package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElliottWaveHierarchyServiceTest {

    @Test
    void buildsMonthlyParentsWithWeeklyChildrenAndDailyGrandchildren() {
        ElliottWaveHierarchyService.HierarchyView hierarchy = buildHierarchy(false);

        assertThat(hierarchy.available()).isTrue();
        assertThat(hierarchy.waves()).extracting(ElliottWaveHierarchyService.Wave::degreeLabel)
                .containsExactly("1", "2", "3", "4", "5", "A", "B", "C");
        assertThat(hierarchy.waves()).allSatisfy(monthlyWave -> {
            int expectedWeekly = monthlyWave.nature() == ElliottWaveHierarchyService.WaveNature.MOTIVE ? 5 : 3;
            assertThat(monthlyWave.timeframe()).isEqualTo(ElliottWaveHierarchyService.Timeframe.MONTHLY);
            assertThat(monthlyWave.subwaves()).hasSize(expectedWeekly).allSatisfy(weeklyWave -> {
                int expectedDaily = weeklyWave.nature() == ElliottWaveHierarchyService.WaveNature.MOTIVE ? 5 : 3;
                assertThat(weeklyWave.timeframe()).isEqualTo(ElliottWaveHierarchyService.Timeframe.WEEKLY);
                assertThat(weeklyWave.startTime()).isGreaterThanOrEqualTo(monthlyWave.startTime());
                assertThat(weeklyWave.endTime()).isLessThan(parentEndExclusive(monthlyWave));
                assertThat(weeklyWave.subwaves()).hasSize(expectedDaily)
                        .allSatisfy(dailyWave -> {
                            assertThat(dailyWave.timeframe()).isEqualTo(ElliottWaveHierarchyService.Timeframe.DAILY);
                            assertThat(dailyWave.startTime()).isGreaterThanOrEqualTo(weeklyWave.startTime());
                            assertThat(dailyWave.endTime()).isLessThan(parentEndExclusive(weeklyWave));
                            assertThat(dailyWave.subwaves()).isEmpty();
                        });
            });
        });
    }

    @Test
    void keepsValidatedWeeklyWavesWhenOneDailyBranchHasNoValidCount() {
        ElliottWaveHierarchyService.HierarchyView hierarchy = buildHierarchy(true);

        assertThat(hierarchy.available()).isTrue();
        assertThat(hierarchy.waves()).allSatisfy(monthlyWave ->
                assertThat(monthlyWave.subwaves()).isNotEmpty());
        assertThat(hierarchy.waves().stream()
                .flatMap(monthly -> monthly.subwaves().stream())
                .filter(weekly -> "2".equals(weekly.degreeLabel()))
                .toList())
                .isNotEmpty()
                .allSatisfy(weekly -> assertThat(weekly.subwaves()).isEmpty());
        assertThat(hierarchy.waves().stream()
                .flatMap(monthly -> monthly.subwaves().stream())
                .filter(weekly -> !"2".equals(weekly.degreeLabel()))
                .toList())
                .anySatisfy(weekly -> assertThat(weekly.subwaves()).isNotEmpty());
    }

    @Test
    void retainsEveryDistinctMonthlyCycleForLowerIntervalRendering() {
        ElliottWaveHierarchyService.HierarchyView hierarchy = buildHierarchy(false, true);

        assertThat(hierarchy.waves()).hasSize(16);
        assertThat(hierarchy.waves()).extracting(ElliottWaveHierarchyService.Wave::cycleKey)
                .doesNotContainNull();
        assertThat(hierarchy.waves().stream()
                .map(ElliottWaveHierarchyService.Wave::cycleKey)
                .distinct().count()).isEqualTo(2);
        String firstCycle = hierarchy.waves().getFirst().cycleKey();
        assertThat(hierarchy.waves().stream()
                .filter(wave -> firstCycle.equals(wave.cycleKey())).count()).isEqualTo(8);
    }

    @Test
    void hierarchyBoundaryRejectsAMotiveCountWhoseWaveThreeIsShortest() {
        List<ElliottWaveHierarchyService.Wave> invalid = List.of(
                motiveWave("1", 100.0, 120.0, 1),
                motiveWave("2", 120.0, 110.0, 2),
                motiveWave("3", 110.0, 125.0, 3),
                motiveWave("4", 125.0, 115.0, 4),
                motiveWave("5", 115.0, 150.0, 5));

        assertThat(ElliottWaveHierarchyService.waveThreeIsNotShortest(invalid)).isFalse();
    }

    private ElliottWaveHierarchyService.Wave motiveWave(String label,
                                                         double startPrice,
                                                         double endPrice,
                                                         long index) {
        return new ElliottWaveHierarchyService.Wave(
                "test-cycle", ElliottWaveHierarchyService.Timeframe.WEEKLY,
                label, ElliottWaveHierarchyService.WaveNature.MOTIVE,
                startPrice, endPrice, index, index + 1, List.of());
    }

    private ElliottWaveHierarchyService.HierarchyView buildHierarchy(boolean rejectDailyWaveTwo) {
        return buildHierarchy(rejectDailyWaveTwo, false);
    }

    private ElliottWaveHierarchyService.HierarchyView buildHierarchy(boolean rejectDailyWaveTwo,
                                                                       boolean includeSecondCycle) {
        MarketDataService marketData = mock(MarketDataService.class);
        CandleCompletionService completion = mock(CandleCompletionService.class);
        TechnicalIndicatorEnrichmentService enrichment = mock(TechnicalIndicatorEnrichmentService.class);
        ElliottWaveDetectionService detector = mock(ElliottWaveDetectionService.class);
        ElliottWaveHierarchyService service = new ElliottWaveHierarchyService(
                marketData, completion, enrichment, detector);
        long asOf = timestamp(2030, 1, 1);
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY)).thenReturn(asOf);

        List<Candle> monthly = candles("1mo", LocalDate.of(2015, 1, 1), LocalDate.of(2026, 1, 1), 28);
        List<Candle> weekly = candles("1wk", LocalDate.of(2019, 1, 1), LocalDate.of(2025, 1, 1), 7);
        List<Candle> daily = candles("1d", LocalDate.of(2019, 1, 1), LocalDate.of(2025, 1, 1), 1);
        when(marketData.loadCandlePage(eq("TEST"), anyString(), anyLong(), eq(1_000)))
                .thenAnswer(invocation -> {
                    String interval = invocation.getArgument(1);
                    List<Candle> values = switch (interval) {
                        case "1mo" -> monthly;
                        case "1wk" -> weekly;
                        default -> daily;
                    };
                    return new MarketDataService.CandlePage(
                            values, null, false, MarketDataService.CandleSource.CACHE, null);
                });
        when(enrichment.enrichForElliott(anyList(), anyInt(), eq(TimeInterval.MONTHLY)))
                .thenAnswer(invocation -> enriched(invocation.getArgument(0)));
        when(enrichment.enrichForElliott(anyList(), anyInt(), eq(TimeInterval.WEEKLY)))
                .thenAnswer(invocation -> enriched(invocation.getArgument(0)));
        when(enrichment.enrichForElliott(anyList(), anyInt(), eq(TimeInterval.DAILY)))
                .thenAnswer(invocation -> enriched(invocation.getArgument(0)));

        List<ElliottWaveDetectionService.ElliottWaveStructure> baselines = includeSecondCycle
                ? List.of(baseline(LocalDate.of(2021, 1, 1)), baseline())
                : List.of(baseline());
        when(detector.findHistoricalWaveStructures(anyList())).thenReturn(baselines);
        when(detector.findStrictSubdivisions(anyList(), anyString(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenAnswer(invocation -> {
                    List<EnrichedCandle> values = invocation.getArgument(0);
                    String label = invocation.getArgument(1);
                    boolean dailyInput = values.size() > 1
                            && values.get(1).timestamp() - values.get(0).timestamp() <= 86_400L;
                    if (rejectDailyWaveTwo && dailyInput && "2".equals(label)) return List.of();
                    return subdivisions(values, label,
                            invocation.getArgument(2), invocation.getArgument(3));
                });

        return service.build("test", asOf, null, null);
    }

    private ElliottWaveDetectionService.ElliottWaveStructure baseline() {
        return baseline(LocalDate.of(2020, 1, 1));
    }

    private ElliottWaveDetectionService.ElliottWaveStructure baseline(LocalDate start) {
        List<String> labels = List.of("", "I", "II", "III", "IV", "V", "A", "B", "C");
        List<Double> prices = List.of(100.0, 120.0, 110.0, 145.0, 125.0, 155.0, 140.0, 150.0, 130.0);
        List<ElliottWaveDetectionService.ElliottWavePoint> points = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            points.add(new ElliottWaveDetectionService.ElliottWavePoint(
                    labels.get(index), epoch(start.plusMonths(index * 4L)), prices.get(index),
                    index % 2 == 0 ? "LOW" : "HIGH"));
        }
        return new ElliottWaveDetectionService.ElliottWaveStructure(
                "BULLISH", true, points, epoch(start.plusMonths(33)), 90,
                .5, false, 1.5, .5,
                ElliottWaveDetectionService.ImpulseVariant.STANDARD,
                ElliottWaveDetectionService.CorrectionVariant.STANDARD,
                .5, 1.0, List.of());
    }

    private List<ElliottWaveDetectionService.ElliottSubdivision> subdivisions(
            List<EnrichedCandle> candles, String parentLabel, double startPrice, double endPrice) {
        String normalized = parentLabel.toUpperCase();
        boolean motive = List.of("I", "III", "V", "1", "3", "5", "A", "C").contains(normalized);
        int waves = motive ? 5 : 3;
        if (candles.size() < waves + 1) return List.of();
        List<ElliottWaveDetectionService.ElliottWavePoint> points = new ArrayList<>();
        for (int index = 0; index <= waves; index++) {
            int candleIndex = Math.min(candles.size() - 1,
                    (int) Math.round(index * (candles.size() - 1.0) / waves));
            double progress = index / (double) waves;
            double price = startPrice + (endPrice - startPrice) * progress;
            points.add(new ElliottWaveDetectionService.ElliottWavePoint(
                    index == 0 ? "" : Integer.toString(index),
                    candles.get(candleIndex).timestamp(), price, index % 2 == 0 ? "LOW" : "HIGH"));
        }
        return List.of(new ElliottWaveDetectionService.ElliottSubdivision(
                motive ? "Motive 1-2-3-4-5" : "Corrective A-B-C",
                80, true, points, List.of("Validated"), List.of()));
    }

    private List<Candle> candles(String interval, LocalDate start, LocalDate end, int stepDays) {
        List<Candle> values = new ArrayList<>();
        LocalDate date = start;
        int index = 0;
        while (date.isBefore(end)) {
            double close = 100.0 + index * .05;
            values.add(new Candle("TEST", interval, epoch(date),
                    close - .5, close + 1.0, close - 1.0, close, 1_000L));
            date = date.plusDays(stepDays);
            index++;
        }
        return values;
    }

    private List<EnrichedCandle> enriched(List<Candle> candles) {
        return candles.stream().map(candle -> new EnrichedCandle(
                candle.getTimestamp(), candle.getOpenPrice(), candle.getHighPrice(), candle.getLowPrice(),
                candle.getClosePrice(), 1_000.0, 900.0, 55.0, candle.getClosePrice(),
                Double.NaN, Double.NaN, Double.NaN, 2.0)).toList();
    }

    private long timestamp(int year, int month, int day) {
        return epoch(LocalDate.of(year, month, day));
    }

    private long parentEndExclusive(ElliottWaveHierarchyService.Wave parent) {
        LocalDate end = Instant.ofEpochSecond(parent.endTime()).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate exclusive = switch (parent.timeframe()) {
            case MONTHLY -> end.plusMonths(1).withDayOfMonth(1);
            case WEEKLY -> end.plusWeeks(1);
            case DAILY -> end.plusDays(1);
        };
        return epoch(exclusive);
    }

    private long epoch(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }
}
