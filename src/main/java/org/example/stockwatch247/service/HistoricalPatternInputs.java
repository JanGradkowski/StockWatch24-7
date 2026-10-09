package org.example.stockwatch247.service;

import java.util.List;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.springframework.stereotype.Service;

@Service
public class HistoricalPatternInputs {
    private final CandleRepository candles;
    private final CandleRevisionService revisions;
    private final CandleCompletionService completion;
    private final TechnicalIndicatorEnrichmentService enrichment;
    private final BoundedTtlCache<InputKey, Snapshot> snapshots = new BoundedTtlCache<>(8, 600);
    private final Object[] locks = java.util.stream.IntStream.range(0, 32).mapToObj(index -> new Object()).toArray();

    public HistoricalPatternInputs(CandleRepository candles, CandleRevisionService revisions,
                                   CandleCompletionService completion, TechnicalIndicatorEnrichmentService enrichment) {
        this.candles = candles;
        this.revisions = revisions;
        this.completion = completion;
        this.enrichment = enrichment;
    }

    public long cutoff(TimeInterval interval) {
        return completion.firstIncompleteCandleTimestamp(interval);
    }

    public Snapshot load(String symbol, String interval, TimeInterval timeframe) {
        synchronized (locks[Math.floorMod((symbol + interval).hashCode(), locks.length)]) {
            for (int attempt = 0; attempt < 3; attempt++) {
                InputKey key = new InputKey(symbol, interval, revisions.generation(symbol, interval), cutoff(timeframe));
                long now = java.time.Instant.now().getEpochSecond();
                Snapshot saved = snapshots.get(key, now);
                if (saved != null) return saved;
                List<Candle> raw = candles.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc(
                        symbol, interval, key.cutoff() - 1);
                if (key.generation() != revisions.generation(symbol, interval)) continue;
                Snapshot snapshot = new Snapshot(List.copyOf(raw), timeframe);
                snapshots.put(key, snapshot, now);
                return snapshot;
            }
            throw new IllegalStateException("Candle history changed during analysis; retry the range request.");
        }
    }

    public static void validateRange(long from, long to) {
        if (from <= 0 || to < from) throw new IllegalArgumentException("A positive, ordered candle range is required.");
    }

    private record InputKey(String symbol, String interval, long generation, long cutoff) { }

    public final class Snapshot {
        private final List<Candle> raw;
        private final TimeInterval interval;
        private List<EnrichedCandle> waves;
        private final BoundedTtlCache<ElliottWaveDetectionService.DetectionRules,
                List<ElliottWaveDetectionService.ElliottWaveStructure>> structures = new BoundedTtlCache<>(8, 600);

        private Snapshot(List<Candle> raw, TimeInterval interval) {
            this.raw = raw;
            this.interval = interval;
        }

        public List<Candle> raw() { return raw; }

        public synchronized List<EnrichedCandle> waves() {
            if (waves == null) waves = enrichment.enrichForElliott(raw, raw.size(), interval);
            return waves;
        }

        public synchronized List<ElliottWaveDetectionService.ElliottWaveStructure> structures(
                ElliottWaveDetectionService detector, ElliottWaveDetectionService.DetectionRules rules) {
            long now = java.time.Instant.now().getEpochSecond();
            var saved = structures.get(rules, now);
            if (saved != null) return saved;
            var result = detector.findHistoricalWaveStructures(waves());
            structures.put(rules, result, now);
            return result;
        }
    }
}
