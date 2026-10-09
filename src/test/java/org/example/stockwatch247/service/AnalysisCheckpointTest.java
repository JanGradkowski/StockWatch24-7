package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.example.stockwatch247.model.EnrichedCandle;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class AnalysisCheckpointTest {
    @Test void continuousWorkCheckpointsWithoutExpiringTheCalculation() {
        var store = new MemoryStore();
        try (var work = AnalysisWork.continuous(store, Duration.ofNanos(1))) {
            assertThat(work.checkpointDue()).isTrue();
            assertThat(work.exhausted()).isFalse();
            assertThat(work.continueAfterCheckpoint()).isTrue();
        }
    }

    @Test void adjacentParentAndChildWindowsReuseOnlyUnchangedLegs() {
        var input = new ElliottWaveDetectionServiceTest().completeNestedImpulseWithNestedZigzagCorrection();
        var detector = new ElliottWaveDetectionService();
        var context = new ElliottWaveDetectionService.DevelopingScanContext();
        for (int size : new int[]{input.size()-2,input.size(),input.size()-1,input.size()}) {
            var prefix = input.subList(0,size);
            assertThat(detector.findDevelopingImpulses(prefix,prefix,context))
                    .isEqualTo(new ElliottWaveDetectionService().findDevelopingImpulses(prefix,prefix));
        }
    }

    @Test void compactGrammarOptionsKeepTheSameBestGeometryAndEvidenceForEveryLabel() throws Exception {
        var detector=new ElliottWaveDetectionService();
        var compact=ElliottWaveDetectionService.class.getDeclaredMethod("findStrictSubdivisions",List.class,String.class,double.class,double.class,boolean.class);
        compact.setAccessible(true);
        var best=ElliottWaveDetectionService.class.getDeclaredMethod("findStrictSubdivisions",List.class,String.class,double.class,double.class,boolean.class,String.class);
        best.setAccessible(true);
        var fixtures=new ElliottWaveDetectionServiceTest();
        int nonempty=0;
        for(var candles:List.of(fixtures.completeNestedImpulseWithNestedZigzagCorrection(),fixtures.completeNestedImpulseWithNestedFlatCorrection(),fixtures.completeNestedImpulseWithTriangularWaveB())) {
            for(var parent:detector.findDevelopingImpulses(candles).stream().limit(5).toList()) {
                for(int index=1;index<parent.points().size();index++) {
                    var from=parent.points().get(index-1);var to=parent.points().get(index);
                    var window=candles.stream().filter(c->c.timestamp()>=from.timestamp()&&c.timestamp()<=to.timestamp()).toList();
                    var full=detector.findStrictSubdivisions(window,to.label(),from.price(),to.price());
                    var expected=new LinkedHashMap<String,ElliottWaveDetectionService.ElliottSubdivision>();
                    full.forEach(option->expected.putIfAbsent(option.structureLabel(),option));
                    assertThat(compact.invoke(detector,window,to.label(),from.price(),to.price(),true)).isEqualTo(List.copyOf(expected.values()));
                    var categories=new LinkedHashMap<Boolean,ElliottWaveDetectionService.ElliottSubdivision>();
                    full.forEach(option->categories.putIfAbsent(option.structureLabel().startsWith("Motive"),option));
                    assertThat(best.invoke(detector,window,to.label(),from.price(),to.price(),true,"CATEGORIES"))
                            .isEqualTo(List.copyOf(categories.values()));
                    for(String mode:List.of("ANY","MOTIVE","CORRECTIVE")) {
                        var winner=full.stream().filter(option -> mode.equals("ANY")
                                || mode.equals("MOTIVE")==option.structureLabel().startsWith("Motive"))
                                .max(Comparator.comparingInt(ElliottWaveDetectionService.ElliottSubdivision::confidence)).stream().toList();
                        assertThat(best.invoke(detector,window,to.label(),from.price(),to.price(),true,mode)).isEqualTo(winner);
                    }
                    if(!full.isEmpty()) nonempty++;
                }
            }
        }
        assertThat(nonempty).isGreaterThan(0);
    }
    @Test void serializedContinuationPreservesAllPrimaryResultsAndAlternativeCounts() {
        var fixtures = new ElliottWaveDetectionServiceTest();
        for (var candles : List.of(fixtures.completeNestedImpulseWithNestedZigzagCorrection(),
                fixtures.completeNestedImpulseWithNestedFlatCorrection(), fixtures.completeNestedImpulseWithTriangularWaveB())) {
            var expected = new ElliottWaveDetectionService().findDevelopingImpulses(candles);
            var store = new MemoryStore();
            var resumed = resume(store, () -> new ElliottWaveDetectionService().findDevelopingImpulses(candles));
            assertThat(resumed).isEqualTo(expected);
            assertThat(store.yields).isGreaterThan(0);
            // A second run reuses completed checkpoints, without changing the result.
            try (var work = AnalysisWork.open(store, Duration.ofNanos(1), () -> 0)) {
                assertThat(new ElliottWaveDetectionService().findDevelopingImpulses(candles)).isEqualTo(expected);
            }
        }
    }

    @Test void compactConfluenceCanResumeWithoutLosingAnyPatternDirection() {
        var candles = new ElliottWaveDetectionServiceTest().completeNestedImpulseWithNestedZigzagCorrection();
        var previous = candles.subList(0, candles.size() - 1);
        var expected = new ElliottWaveDetectionService().newConfluenceObservations(candles, previous,
                new ElliottWaveDetectionService.DevelopingScanContext());
        var actual = resume(new MemoryStore(), () -> new ElliottWaveDetectionService().newConfluenceObservations(
                candles, previous, new ElliottWaveDetectionService.DevelopingScanContext()));
        assertThat(actual).isEqualTo(expected);
    }

    @Test void correctedInputCannotReuseAnOldCheckpoint() {
        var fixture = new ElliottWaveDetectionServiceTest();
        var first = fixture.completeNestedImpulseWithNestedZigzagCorrection();
        var corrected = fixture.completeNestedImpulseWithNestedFlatCorrection();
        var store = new MemoryStore();
        resume(store, () -> new ElliottWaveDetectionService().findDevelopingImpulses(first));
        var result = resume(store, () -> new ElliottWaveDetectionService().findDevelopingImpulses(corrected));
        assertThat(result).isEqualTo(new ElliottWaveDetectionService().findDevelopingImpulses(corrected));
    }

    private <T> T resume(MemoryStore store, java.util.function.Supplier<T> operation) {
        for (int attempt = 0; attempt < 2000; attempt++) {
            var clock = new AtomicLong();
            try (var work = AnalysisWork.open(store, Duration.ofNanos(100), clock::getAndIncrement)) {
                return operation.get();
            } catch (AnalysisDeferredException deferred) { store.yields++; }
        }
        throw new AssertionError("Continuation never completed");
    }

    static class MemoryStore extends AnalysisCheckpointStore {
        final Map<String, String> payloads = new HashMap<>();
        final ObjectMapper json = new ObjectMapper();
        int yields;
        MemoryStore() { super(null, null); }
        @Override <T> T read(String key, Class<T> type) {
            return payloads.containsKey(key) ? json.readValue(payloads.get(key), type) : null;
        }
        @Override public void write(String key, Object value, long visited) { payloads.put(key, json.writeValueAsString(value)); }
    }
}
