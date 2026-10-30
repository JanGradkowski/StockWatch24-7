package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Twelve Data's time-series metadata names no company; a candle sync must not replace a stored name with the ticker. */
class TwelveDataAssetNameTest {
    private static final String SERIES = """
            {
              "meta": {"symbol":"AAPL","interval":"1day","currency":"USD","exchange_timezone":"America/New_York",
                       "exchange":"NASDAQ","mic_code":"XNGS","type":"Common Stock"},
              "values": [
                {"datetime":"2026-10-26","open":"340","high":"345","low":"339","close":"343.91","volume":"1000"}
              ]
            }
            """;

    @Test
    void candleSyncKeepsTheStoredCompanyName() {
        StockAsset asset = asset("Apple Inc.", "NasdaqGS");
        TwelveDataService service = service(asset);

        service.getTimeSeries("AAPL", "1day", 1);

        assertThat(asset.getCompanyName()).isEqualTo("Apple Inc.");
        assertThat(asset.getExchange()).isEqualTo("NASDAQ");
        assertThat(asset.hasConfirmedListing()).isTrue();
    }

    @Test
    void candleSyncStillFillsAPlaceholderListing() {
        StockAsset asset = asset("AAPL", "UNKNOWN");
        TwelveDataService service = service(asset);

        service.getTimeSeries("AAPL", "1day", 1);

        assertThat(asset.getCompanyName()).isEqualTo("AAPL");
        assertThat(asset.getExchange()).isEqualTo("NASDAQ");
        assertThat(asset.getMicCode()).isEqualTo("XNGS");
    }

    @Test
    void aMissingExchangeKeepsTheStoredOne() {
        StockAsset asset = asset("Apple Inc.", "NasdaqGS");
        StockAssetRepository repository = repository(asset);
        TwelveDataService service = new TwelveDataService(new RestTemplate(),
                tools.jackson.databind.json.JsonMapper.builder().build(), repository, "test-key", "https://api.twelvedata.com");

        service.upsertStockAsset("AAPL", "", "", "USD");

        assertThat(asset.getCompanyName()).isEqualTo("Apple Inc.");
        assertThat(asset.getExchange()).isEqualTo("NasdaqGS");
    }

    @Test
    void aRealNameFromTheProviderReplacesThePlaceholder() {
        StockAsset asset = asset("AAPL", "NASDAQ");
        StockAssetRepository repository = repository(asset);
        TwelveDataService service = new TwelveDataService(new RestTemplate(),
                tools.jackson.databind.json.JsonMapper.builder().build(), repository, "test-key", "https://api.twelvedata.com");

        service.upsertStockAsset("AAPL", "Apple Inc.", "NASDAQ", "USD");

        assertThat(asset.getCompanyName()).isEqualTo("Apple Inc.");
    }

    private static TwelveDataService service(StockAsset asset) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/time_series"))
                .andRespond(withSuccess(SERIES, MediaType.APPLICATION_JSON));
        return new TwelveDataService(restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(),
                repository(asset), "test-key", "https://api.twelvedata.com");
    }

    private static StockAssetRepository repository(StockAsset asset) {
        StockAssetRepository repository = mock(StockAssetRepository.class);
        when(repository.findByTickerSymbolIgnoreCase(anyString())).thenReturn(Optional.of(asset));
        when(repository.save(any(StockAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    private static StockAsset asset(String name, String exchange) {
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        asset.setCompanyName(name);
        asset.setExchange(exchange);
        asset.setCurrency("USD");
        return asset;
    }
}
