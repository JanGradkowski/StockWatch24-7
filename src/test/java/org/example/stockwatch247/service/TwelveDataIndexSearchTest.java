package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TwelveDataIndexSearchTest {

    @Test
    void returnsBuiltInIndexSuggestionWhenProviderSearchIsUnavailable() {
        StockAssetRepository repository = mock(StockAssetRepository.class);
        when(repository.findByTickerSymbolIgnoreCase("^GSPC")).thenReturn(Optional.empty());
        when(repository.findByTickerSymbolStartsWithIgnoreCase("SPX")).thenReturn(List.of());
        when(repository.findByCompanyNameContainingIgnoreCase("SPX")).thenReturn(List.of());
        when(repository.save(any(StockAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TwelveDataService service = new TwelveDataService(
                new RestTemplate(), new ObjectMapper(), repository, "", "https://api.twelvedata.com");

        List<Map<String, Object>> suggestions = service.searchSymbols("SPX");

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.getFirst())
                .containsEntry("symbol", "^GSPC")
                .containsEntry("name", "S&P 500")
                .containsEntry("instrumentType", "INDEX");
        verify(repository, atLeastOnce()).save(argThat(asset ->
                "^GSPC".equals(asset.getTickerSymbol())
                        && asset.getInstrumentType() == InstrumentType.INDEX));
    }
}
