package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
