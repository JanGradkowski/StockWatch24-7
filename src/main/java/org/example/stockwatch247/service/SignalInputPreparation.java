package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;

import java.util.Comparator;
import java.util.List;

/**
 * How much history each pattern family reads and how its candles are prepared. The scheduled alert
 * check and the manual "check signal" request both use these rules, so they cannot disagree.
 */
final class SignalInputPreparation {
    /** Elliott counts are found on up to this many completed parent candles. */
    static final int ELLIOTT_PARENT_CANDLES = 1000;

    private SignalInputPreparation() {
    }

    static int elliottHistory(TechnicalIndicatorEnrichmentService enrichment, TimeInterval interval) {
        return enrichment.requiredElliottInputCandles(ELLIOTT_PARENT_CANDLES, interval);
    }

    /** Elliott input: Elliott-specific enrichment, cut back to the latest unbroken run of valid candles. */
    static List<EnrichedCandle> elliottInput(TechnicalIndicatorEnrichmentService enrichment,
                                             List<Candle> candles, TimeInterval interval) {
        List<Candle> chronological = chronological(candles);
        List<EnrichedCandle> enriched = enrichment.enrichForElliott(chronological, ELLIOTT_PARENT_CANDLES, interval);
        return new CandlestickFormationIntegrity(chronological).latestContext(enriched);
    }

    static List<Candle> chronological(List<Candle> candles) {
        return candles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
    }
}
