package org.example.stockwatch247.service;

import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TwelveDataBudgetBoundaryTest {
    @Test void everyQuoteAndCandlePageReservesExactlyOneCreditImmediatelyBeforeHttp() {
        var http = mock(RestTemplate.class);
        var budget = mock(MarketDataProviderRequestBudget.class);
        var service = new TwelveDataService(http, JsonMapper.builder().build(), mock(StockAssetRepository.class), "test-key", "https://provider.example");
        service.setRequestBudget(budget);
        when(http.getForObject(any(URI.class), eq(String.class))).thenReturn("{\"values\":[],\"close\":\"100\",\"symbol\":\"AAPL\"}");
        service.getQuote("AAPL");
        service.getTimeSeries("AAPL", "1day", 10);
        service.getTimeSeriesBefore("AAPL", "1day", 10, 1_700_000_000L);
        var order = inOrder(budget, http);
        for (int i = 0; i < 3; i++) {
            order.verify(budget).reserveTwelveDataRequest();
            order.verify(http).getForObject(any(URI.class), eq(String.class));
        }
        verifyNoMoreInteractions(budget, http);
    }
    @Test void missingConfigurationDoesNotSpendCreditAndExhaustionNeverSendsHttp() {
        var http = mock(RestTemplate.class); var budget = mock(MarketDataProviderRequestBudget.class);
        var assets = mock(StockAssetRepository.class);
        var missing = new TwelveDataService(http, JsonMapper.builder().build(), assets, "", "https://provider.example");
        missing.setRequestBudget(budget);
        assertThatThrownBy(() -> missing.getQuote("AAPL")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(budget, http);
        var configured = new TwelveDataService(http, JsonMapper.builder().build(), assets, "test-key", "https://provider.example");
        configured.setRequestBudget(budget);
        doThrow(new MarketDataProviderRequestBudget.BudgetUnavailableException("exhausted")).when(budget).reserveTwelveDataRequest();
        assertThatThrownBy(() -> configured.getQuote("AAPL")).isInstanceOf(MarketDataProviderRequestBudget.BudgetUnavailableException.class);
        verifyNoInteractions(http);
    }
}
