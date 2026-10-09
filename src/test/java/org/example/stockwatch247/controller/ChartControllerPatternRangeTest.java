package org.example.stockwatch247.controller;

import java.util.List;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.HistoricalPatternInputs;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.HarmonicPatternDetectionService;
import org.example.stockwatch247.service.MarketDataService;
import org.example.stockwatch247.service.LivePricingService;
import org.example.stockwatch247.service.TwelveDataService;
import org.example.stockwatch247.service.YahooFinanceService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class ChartControllerPatternRangeTest {
    @Test void rangeEndpointsPassBothBoundsToDetectorsAndUseSharedCompletedContext() {
        var inputs = mock(HistoricalPatternInputs.class);
        var snapshot = mock(HistoricalPatternInputs.Snapshot.class);
        var waves = mock(ElliottWaveDetectionService.class);
        var harmonics = mock(HarmonicPatternDetectionService.class);
        var repository = mock(CandleRepository.class);
        var market = mock(MarketDataService.class);
        var controller = new ChartController(repository, mock(LivePricingService.class), market,
                mock(StockAssetRepository.class), mock(TwelveDataService.class), mock(YahooFinanceService.class),
                mock(TechnicalIndicatorEnrichmentService.class), waves);
        ReflectionTestUtils.setField(controller, "patternInputs", inputs);
        controller.configureHarmonicPatterns(harmonics);
        when(inputs.load("AAPL", "1d", TimeInterval.DAILY)).thenReturn(snapshot);
        when(waves.configured(any())).thenReturn(waves);
        when(harmonics.configured(any(), any())).thenReturn(harmonics);
        var raw = List.of(new Candle("AAPL", "1d", 10L, 10, 12, 9, 11.0, 100L));
        when(snapshot.raw()).thenReturn(raw);
        controller.getElliottRange("aapl", "1d", 100, 200, null);
        controller.getElliottCandidateRange("aapl", "1d", 100, 200, null);
        controller.getHarmonicRange("aapl", "1d", 100, 200, null);
        verify(waves).findPatternCandidates(List.of(), 100, 200);
        verify(harmonics).detectRange(raw, 100, 200);
        verify(snapshot).structures(eq(waves), any());
        verifyNoInteractions(repository, market);
        assertThatThrownBy(() -> controller.getHarmonicRange("AAPL", "1d", 200, 100, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.getElliottCandidateRange("AAPL", "1h", 100, 200, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
