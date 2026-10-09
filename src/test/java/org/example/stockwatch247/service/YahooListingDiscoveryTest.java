package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooListingDiscoveryTest {

    @Test
    void unconfirmedTickerWithStoredHistoryIsNotRemappedToAnotherExchangeListing() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        StockAssetRepository assets = mock(StockAssetRepository.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("XYZ");
        asset.setCompanyName("XYZ");
        asset.setExchange("UNKNOWN");
        asset.setCurrency("USD");
        when(assets.findByTickerSymbolIgnoreCase("XYZ")).thenReturn(Optional.of(asset));
        CandleRepository candles = mock(CandleRepository.class);
        when(candles.countBySymbolAndTimeInterval("XYZ", "1d")).thenReturn(250L);
        // Only the original listing is tried: no search and no .WA/.DE/... suffix guesses follow.
        server.expect(requestTo(containsString("/v8/finance/chart/XYZ?")))
                .andRespond(withSuccess("{\"chart\":{\"result\":null,\"error\":{\"description\":\"No data found\"}}}",
                        MediaType.APPLICATION_JSON));
        YahooFinanceService service = new YahooFinanceService(rest, new ObjectMapper(), assets,
                "https://query1.finance.yahoo.com", true);
        service.configureCandleRepository(candles);

        assertThatThrownBy(() -> service.getTimeSeries("XYZ", "1d", 10))
                .hasMessageContaining("could not retrieve candle data for XYZ");
        server.verify();
        verify(assets, never()).save(any());
    }
}
