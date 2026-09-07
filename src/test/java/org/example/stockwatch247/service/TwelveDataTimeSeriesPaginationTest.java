package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwelveDataTimeSeriesPaginationTest {

    @Test
    void reportsTheTransportRootCauseWithoutExposingTheApiKey() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/time_series"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });
        TwelveDataService service = new TwelveDataService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), mock(StockAssetRepository.class),
                "highly-secret-test-key", "https://api.twelvedata.com");

        assertThatThrownBy(() -> service.getTimeSeries("AAPL", "1week", 1000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Twelve Data response could not be read: SocketTimeoutException: Read timed out")
                .hasMessageNotContaining("highly-secret-test-key")
                .hasMessageNotContaining("apikey=");
        server.verify();
    }

    @Test
    void sendsAnExclusiveHistoricalCursorAndFiltersAnyBoundaryCandle() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        long before = LocalDate.of(2026, 7, 11).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        server.expect(request -> {
                    String query = URLDecoder.decode(
                            request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("interval=1day")
                            .contains("outputsize=2")
                            .contains("end_date=2026-07-10 23:59:59")
                            .contains("adjust=splits");
                })
                .andRespond(withSuccess("""
                        {
                          "meta": {},
                          "values": [
                            {"datetime":"2026-07-11","open":"102","high":"104","low":"101","close":"103","volume":"1100"},
                            {"datetime":"2026-07-10 07:00:00","open":"100","high":"103","low":"99","close":"102","volume":"1000"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        TwelveDataService service = new TwelveDataService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), mock(StockAssetRepository.class),
                "test-key", "https://api.twelvedata.com");

        List<MarketDataBar> bars = service.getTimeSeriesBefore("MSFT", "1day", 2, before);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.timestamp()).isLessThan(before);
            assertThat(bar.timestamp()).isEqualTo(
                    LocalDate.of(2026, 7, 10).atStartOfDay(ZoneOffset.UTC).toEpochSecond());
            assertThat(bar.close()).isEqualTo(102.0);
        });
        server.verify();
    }
}
