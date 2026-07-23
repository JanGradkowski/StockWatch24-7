package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwelveDataIndexSearchTest {

    @Test
    void returnsBuiltInIndexSuggestionWhenProviderSearchIsUnavailable() {
        StockAssetRepository repository = mock(StockAssetRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        TwelveDataService service = new TwelveDataService(
                new RestTemplate(), new ObjectMapper(), repository, "", "https://api.twelvedata.com");

        List<Map<String, Object>> suggestions = service.searchSymbols("SPX");

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.getFirst())
                .containsEntry("symbol", "^GSPC")
                .containsEntry("name", "S&P 500")
                .containsEntry("instrumentType", "INDEX");
        verify(repository, never()).save(any());
    }

    @Test
    void providerSuggestionsRemainReadOnlyUntilAListingIsOpened() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository repository = mock(StockAssetRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("/symbol_search"))
                .andRespond(withSuccess("""
                        {
                          "data": [{
                            "symbol": "DNPW",
                            "instrument_name": "Dino Polska S.A.",
                            "exchange": "Warsaw Stock Exchange",
                            "mic_code": "XWAR",
                            "country": "Poland",
                            "currency": "PLN",
                            "instrument_type": "Common Stock"
                          }],
                          "status": "ok"
                        }
                        """, MediaType.APPLICATION_JSON));
        TwelveDataService service = new TwelveDataService(
                restTemplate, new ObjectMapper(), repository, "test-key", "https://api.twelvedata.com");

        List<Map<String, Object>> suggestions = service.searchSymbols("Dino");

        assertThat(suggestions).anySatisfy(suggestion -> assertThat(suggestion)
                .containsEntry("symbol", "DNPW")
                .containsEntry("micCode", "XWAR")
                .containsEntry("currency", "PLN"));
        verify(repository, never()).save(any());
        server.verify();
    }

    @Test
    void providerMetadataDoesNotMoveAnExactCatalogAliasBehindPrefixMatches() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository repository = mock(StockAssetRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("/symbol_search"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {
                              "symbol": "SPX3",
                              "instrument_name": "3x Long Space Index ETP",
                              "exchange": "MTA",
                              "mic_code": "XMIL",
                              "currency": "EUR",
                              "instrument_type": "ETF"
                            },
                            {
                              "symbol": "^GSPC",
                              "instrument_name": "S&P 500",
                              "exchange": "S&P Index",
                              "mic_code": "SNPX",
                              "country": "United States",
                              "currency": "USD",
                              "instrument_type": "Index"
                            }
                          ],
                          "status": "ok"
                        }
                        """, MediaType.APPLICATION_JSON));
        TwelveDataService service = new TwelveDataService(
                restTemplate, new ObjectMapper(), repository, "test-key", "https://api.twelvedata.com");

        List<Map<String, Object>> suggestions = service.searchSymbols("SPX");

        assertThat(suggestions.getFirst())
                .containsEntry("symbol", "^GSPC")
                .containsEntry("micCode", "SNPX")
                .containsEntry("instrumentType", "INDEX");
        verify(repository, never()).save(any());
        server.verify();
    }
}
