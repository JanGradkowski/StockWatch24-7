package org.example.stockwatch247.controller;

import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalCandlestickControllerTest {

    @Test
    void historicalSignalResponseIsExplicitlyNotCacheable() {
        HistoricalCandlestickService service = mock(HistoricalCandlestickService.class);
        HistoricalCandlestickService.HistoricalScan scan =
                new HistoricalCandlestickService.HistoricalScan(
                        "AAPL",
                        "Apple Inc.",
                        "1d",
                        "Daily",
                        144,
                        "last 144 completed daily candles",
                        10,
                        "10-session horizon",
                        3.0,
                        259,
                        List.of()
                );
        when(service.scan("AAPL", "1d", 144)).thenReturn(scan);
        HistoricalCandlestickController controller = new HistoricalCandlestickController(service);

        ResponseEntity<HistoricalCandlestickService.HistoricalScan> response =
                controller.historicalCandlestickPatterns("aapl", "1d", 144);

        assertThat(response.getBody()).isSameAs(scan);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(service).scan("AAPL", "1d", 144);
    }
}
