package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** A count whose starting points are spread over several threads finds exactly what one thread finds. */
class ElliottParallelSeedsTest {
    private final java.util.concurrent.Executor previous = ElliottWaveDetectionService.seedsRunOn();
    private ExecutorService pool;

    @AfterEach
    void restore() {
        ElliottWaveDetectionService.runSeedsOn(previous);
        if (pool != null) pool.shutdownNow();
    }

    @Test
    void spreadingTheStartingPointsOverThreadsChangesNoCount() {
        int compared = 0;
        int counts = 0;
        var helpersStarted = new java.util.concurrent.atomic.AtomicInteger();
        for (int series = 0; series < 12; series++) {
            List<EnrichedCandle> parents = walk(new Random(31L * series + 5), 220 + series * 40, 86_400);
            List<EnrichedCandle> children = series % 3 == 0 ? null : walk(new Random(97L * series + 3), parents.size() * 5, 86_400 / 5);
            ElliottWaveDetectionService.runSeedsOn(null);
            var alone = new ElliottWaveDetectionService().primaryScan(parents, children,
                    new ElliottWaveDetectionService.DevelopingScanContext());
            if (pool == null) pool = Executors.newFixedThreadPool(6);
            ExecutorService threads = pool;
            ElliottWaveDetectionService.runSeedsOn(work -> {
                helpersStarted.incrementAndGet();
                threads.execute(work);
            });
            var spread = new ElliottWaveDetectionService().primaryScan(parents, children,
                    new ElliottWaveDetectionService.DevelopingScanContext());
            assertThat(spread).as("series %d", series).isEqualTo(alone);
            compared++;
            counts += alone.size();
        }
        assertThat(compared).isEqualTo(12);
        assertThat(counts).as("counts found, so the comparison is not of empty results").isGreaterThan(0);
        assertThat(helpersStarted).as("the counts were spread over threads").hasPositiveValue();
    }

    @Test
    void countingSomeDevelopmentsFindsWhatTheFullCountShowsForThem() {
        ElliottWaveDetectionService.runSeedsOn(null);
        int compared = 0;
        for (int series = 0; series < 12; series++) {
            List<EnrichedCandle> parents = walk(new Random(31L * series + 5), 220 + series * 40, 86_400);
            List<EnrichedCandle> children = series % 3 == 0 ? null : walk(new Random(97L * series + 3), parents.size() * 5, 86_400 / 5);
            var full = new ElliottWaveDetectionService().primaryScan(parents, children,
                    new ElliottWaveDetectionService.DevelopingScanContext());
            var fullByKey = new java.util.LinkedHashMap<String, ElliottWaveDetectionService.DevelopingImpulse>();
            full.forEach(development -> fullByKey.putIfAbsent(development.developmentKey(), development));
            String absent = "BULLISH:" + parents.getFirst().timestamp() + ':' + parents.getLast().timestamp();
            var every = new java.util.LinkedHashSet<>(fullByKey.keySet());
            every.add(absent);
            assertThat(new ElliottWaveDetectionService().findDevelopments(parents, children, every))
                    .as("series %d, every development", series).isEqualTo(fullByKey);
            // Every other development, and each one alone.
            var some = new java.util.LinkedHashSet<String>();
            int index = 0;
            for (String key : fullByKey.keySet()) if (index++ % 2 == 0) some.add(key);
            var expected = new java.util.LinkedHashMap<>(fullByKey);
            expected.keySet().retainAll(some);
            assertThat(new ElliottWaveDetectionService().findDevelopments(parents, children, some))
                    .as("series %d, every other development", series).isEqualTo(expected);
            for (var development : fullByKey.values()) {
                assertThat(new ElliottWaveDetectionService().findDevelopments(parents, children, java.util.Set.of(development.developmentKey())))
                        .as("series %d %s", series, development.developmentKey())
                        .isEqualTo(java.util.Map.of(development.developmentKey(), development));
                compared++;
            }
            assertThat(new ElliottWaveDetectionService().findDevelopments(parents, children, java.util.Set.of(absent))).isEmpty();
            // The same, with the chosen developments spread over threads.
            if (pool == null) pool = Executors.newFixedThreadPool(6);
            ExecutorService threads = pool;
            ElliottWaveDetectionService.runSeedsOn(threads::execute);
            BoundedTtlCache.expireAll(); // so the counts are worked out again, not read from the shared cache
            assertThat(new ElliottWaveDetectionService().findDevelopments(parents, children, every))
                    .as("series %d, every development on threads", series).isEqualTo(fullByKey);
            assertThat(new ElliottWaveDetectionService().findDevelopments(parents, children, some))
                    .as("series %d, every other development on threads", series).isEqualTo(expected);
            ElliottWaveDetectionService.runSeedsOn(null);
        }
        assertThat(compared).as("developments compared, so the comparison is not of empty results").isGreaterThan(5);
    }

    @Test
    void aPoolThatRunsNothingLeavesEverySeedToTheCallingThread() {
        List<EnrichedCandle> parents = walk(new Random(11), 400, 86_400);
        ElliottWaveDetectionService.runSeedsOn(null);
        var alone = new ElliottWaveDetectionService().primaryScan(parents, null,
                new ElliottWaveDetectionService.DevelopingScanContext());
        ElliottWaveDetectionService.runSeedsOn(work -> { });
        var spread = new ElliottWaveDetectionService().primaryScan(parents, null,
                new ElliottWaveDetectionService.DevelopingScanContext());
        assertThat(spread).isEqualTo(alone);
    }

    /** Swinging random walk: trends of random length and strength, so counts form. */
    private static List<EnrichedCandle> walk(Random random, int size, long step) {
        List<EnrichedCandle> candles = new ArrayList<>(size);
        double close = 100;
        double atr = Double.NaN;
        int trendLeft = 0;
        double drift = 0;
        for (int index = 0; index < size; index++) {
            if (trendLeft-- <= 0) {
                trendLeft = 5 + random.nextInt(40);
                drift = (random.nextBoolean() ? 1 : -1) * (0.002 + random.nextDouble() * 0.012);
            }
            double open = close;
            close = Math.max(1, open * (1 + drift + 0.01 * random.nextGaussian()));
            double high = Math.max(open, close) * (1 + 0.006 * random.nextDouble());
            double low = Math.min(open, close) * (1 - 0.006 * random.nextDouble());
            atr = Double.isNaN(atr) ? high - low : (atr * 13 + high - low) / 14;
            candles.add(new EnrichedCandle(1_600_000_000L + index * step, open, high, low, close, 1_000_000, 1_000_000,
                    50, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, atr, Double.NaN, Double.NaN, Double.NaN, Double.NaN));
        }
        return List.copyOf(candles);
    }
}
