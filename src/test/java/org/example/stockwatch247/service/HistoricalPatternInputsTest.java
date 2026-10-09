package org.example.stockwatch247.service;

import java.util.List;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HistoricalPatternInputsTest {
    @Test void rangeBatchesShareContextButCorrectionsAndNewlyCompletedCandlesInvalidateIt() {
        var repository = mock(CandleRepository.class);
        var revisions = mock(CandleRevisionService.class);
        var completion = mock(CandleCompletionService.class);
        var enrichment = mock(TechnicalIndicatorEnrichmentService.class);
        when(revisions.generation("AAPL", "1d")).thenReturn(1L);
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY)).thenReturn(100L);
        var raw = List.of(new Candle("AAPL", "1d", 20L, 10, 12, 9, 11.0, 100L));
        when(repository.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc("AAPL", "1d", 99L)).thenReturn(raw);
        var inputs = new HistoricalPatternInputs(repository, revisions, completion, enrichment);
        var first = inputs.load("AAPL", "1d", TimeInterval.DAILY);
        assertThat(inputs.load("AAPL", "1d", TimeInterval.DAILY)).isSameAs(first);
        first.waves();
        first.waves();
        verify(enrichment).enrichForElliott(raw, 1, TimeInterval.DAILY);
        verify(repository).findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc("AAPL", "1d", 99L);
        when(revisions.generation("AAPL", "1d")).thenReturn(2L);
        assertThat(inputs.load("AAPL", "1d", TimeInterval.DAILY)).isNotSameAs(first);
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY)).thenReturn(101L);
        when(repository.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc("AAPL", "1d", 100L)).thenReturn(raw);
        inputs.load("AAPL", "1d", TimeInterval.DAILY);
        verify(repository).findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc("AAPL", "1d", 100L);
    }

    @Test void concurrentRevisionChangeDiscardsTheInconsistentSnapshot() {
        var repository = mock(CandleRepository.class);
        var revisions = mock(CandleRevisionService.class);
        var completion = mock(CandleCompletionService.class);
        when(revisions.generation("AAPL", "1d")).thenReturn(1L, 2L, 2L, 2L);
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY)).thenReturn(100L);
        var first = new Candle("AAPL", "1d", 20L, 10, 12, 9, 11.0, 100L);
        var corrected = new Candle("AAPL", "1d", 20L, 10, 13, 9, 12.0, 100L);
        when(repository.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc("AAPL", "1d", 99L))
                .thenReturn(List.of(first), List.of(corrected));
        var inputs = new HistoricalPatternInputs(repository, revisions, completion, new TechnicalIndicatorEnrichmentService());
        assertThat(inputs.load("AAPL", "1d", TimeInterval.DAILY).raw()).containsExactly(corrected);
    }
}
