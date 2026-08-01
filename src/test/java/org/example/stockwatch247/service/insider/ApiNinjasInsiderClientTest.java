package org.example.stockwatch247.service.insider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.enums.InsiderTradeType;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiNinjasInsiderClientTest {

    @Test
    void parsesPurchasesAndSalesAndUsesFilingDateAsTheDocumentedFallback() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RequestRateLimiter rateLimiter = allowingLimiter();
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class))).thenReturn(ResponseEntity.ok("""
                [
                  {
                    "accession_number": "0001",
                    "filing_date": "2026-07-20",
                    "sec_filing_url": "https://www.sec.gov/Archives/example-purchase",
                    "ticker": "AAPL",
                    "insider_name": "Example Buyer",
                    "insider_position": "Chief Example Officer",
                    "transaction_code": "P",
                    "transaction_name": "Open Market Purchase",
                    "transaction_type": "purchase",
                    "transaction_price": 200.50,
                    "shares": 1250,
                    "remaining_shares": 5000
                  },
                  {
                    "filing_date": "2026-07-21",
                    "sec_filing_url": "https://attacker.example/unsafe",
                    "ticker": "AAPL",
                    "insider_name": "Example Seller",
                    "insider_position": "Director",
                    "transaction_code": "S",
                    "transaction_name": "Open Market Sale",
                    "transaction_type": "sale",
                    "transaction_price": 210,
                    "shares": 500,
                    "remaining_shares": 4500
                  },
                  {
                    "filing_date": "2026-07-22",
                    "ticker": "AAPL",
                    "insider_name": "Award Recipient",
                    "transaction_code": "A",
                    "transaction_type": "award",
                    "transaction_price": 0,
                    "shares": 1000
                  }
                ]
                """));

        ApiNinjasInsiderClient client = client(restTemplate, rateLimiter, "test-key");

        var trades = client.fetchTickerTrades("aapl");

        assertThat(trades).hasSize(2);
        assertThat(trades.getFirst().transactionType()).isEqualTo(InsiderTradeType.PURCHASE);
        assertThat(trades.getFirst().transactionDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(trades.getFirst().filingDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(trades.getFirst().sourceUrl()).startsWith("https://www.sec.gov/");
        assertThat(trades.getLast().transactionType()).isEqualTo(InsiderTradeType.SALE);
        assertThat(trades.getLast().sourceUrl()).isNull();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(
                uri.capture(), eq(HttpMethod.GET), entity.capture(), eq(String.class));
        assertThat(uri.getValue().getPath()).isEqualTo("/v1/insidertransactions");
        assertThat(uri.getValue().getQuery()).isEqualTo("ticker=AAPL");
        assertThat(entity.getValue().getHeaders().getFirst("X-Api-Key")).isEqualTo("test-key");
        verify(rateLimiter).tryAcquire(
                "api-ninjas-insider-shared-hour", 100, Duration.ofHours(1));
    }

    @Test
    void refusesCallsWhenTheSharedKeyHourlyBudgetIsExhausted() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RequestRateLimiter rateLimiter = mock(RequestRateLimiter.class);
        when(rateLimiter.tryAcquire(any(), eq(100), eq(Duration.ofHours(1))))
                .thenReturn(false);

        ApiNinjasInsiderClient client = client(restTemplate, rateLimiter, "test-key");

        assertThatThrownBy(() -> client.fetchTickerTrades("AAPL"))
                .isInstanceOf(InsiderDataUnavailableException.class)
                .hasMessageContaining("100 requests per hour");
        verify(restTemplate, never()).exchange(
                any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    void refusesProviderCallsWithoutAConfiguredKey() {
        ApiNinjasInsiderClient client =
                client(mock(RestTemplate.class), allowingLimiter(), "");

        assertThatThrownBy(() -> client.fetchTickerTrades("AAPL"))
                .isInstanceOf(InsiderDataUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    private ApiNinjasInsiderClient client(
            RestTemplate restTemplate,
            RequestRateLimiter rateLimiter,
            String apiKey) {
        return new ApiNinjasInsiderClient(
                restTemplate,
                new ObjectMapper(),
                rateLimiter,
                true,
                "https://api.api-ninjas.com/v1/",
                apiKey,
                100);
    }

    private RequestRateLimiter allowingLimiter() {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(any(), any(Integer.class), any(Duration.class)))
                .thenReturn(true);
        return limiter;
    }
}
