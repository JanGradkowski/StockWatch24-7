package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooFinanceServiceTest {

    @Test
    void resolvesXetraSuffixParsesValidBarsAndCanonicalizesTheTradingDate() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("SAP");
        asset.setCompanyName("SAP");
        asset.setExchange("XETRA");
        asset.setCurrency("EUR");
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("SAP")).thenReturn(Optional.of(asset));

        long firstTimestamp = LocalDate.of(2026, 7, 8)
                .atTime(17, 30)
                .atZone(ZoneId.of("Europe/Berlin"))
                .toEpochSecond();
        long secondTimestamp = LocalDate.of(2026, 7, 9)
                .atTime(17, 30)
                .atZone(ZoneId.of("Europe/Berlin"))
                .toEpochSecond();

        String response = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "SAP.DE",
                        "currency": "EUR",
                        "exchangeName": "GER",
                        "fullExchangeName": "XETRA",
                        "shortName": "SAP SE",
                        "exchangeTimezoneName": "Europe/Berlin"
                      },
                      "timestamp": [%d, %d, %d],
                      "indicators": {"quote": [{
                        "open": [190.0, null, 192.0],
                        "high": [194.0, null, 196.0],
                        "low": [189.0, null, 191.0],
                        "close": [193.0, null, 195.0],
                        "volume": [1200000, null, 1300000]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(firstTimestamp, firstTimestamp + 3_600, secondTimestamp);

        server.expect(requestTo(containsString("/v8/finance/chart/SAP.DE")))
                .andExpect(header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate, new ObjectMapper(), stockAssetRepository, "https://query1.finance.yahoo.com", true);

        List<MarketDataBar> bars = service.getTimeSeries("SAP", "1d", 1000);

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).providerSymbol()).isEqualTo("SAP.DE");
        assertThat(bars.get(0).timestamp()).isEqualTo(
                LocalDate.of(2026, 7, 8).atStartOfDay(ZoneOffset.UTC).toEpochSecond());
        assertThat(bars.get(1).timestamp()).isEqualTo(
                LocalDate.of(2026, 7, 9).atStartOfDay(ZoneOffset.UTC).toEpochSecond());
        assertThat(bars.get(1).close()).isEqualTo(195.0);
        assertThat(bars.get(1).volume()).isEqualTo(1_300_000L);
        assertThat(asset.getCompanyName()).isEqualTo("SAP SE");
        assertThat(asset.getCurrency()).isEqualTo("EUR");
        verify(stockAssetRepository).save(asset);
        server.verify();
    }

    @Test
    void resolvesWarsawTickerWhenYahooSearchDoesNotReturnThePlainSymbol() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("CDR")).thenReturn(Optional.empty());
        when(stockAssetRepository.save(any(StockAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        long timestamp = LocalDate.of(2026, 7, 9)
                .atTime(17, 0)
                .atZone(ZoneId.of("Europe/Warsaw"))
                .toEpochSecond();

        String unavailableResponse = """
                {
                  "chart": {
                    "result": null,
                    "error": {"description": "No data found, symbol may be delisted"}
                  }
                }
                """;
        String searchResponse = """
                {
                  "quotes": [
                    {"symbol": "AAPL.TO", "quoteType": "EQUITY", "shortname": "APPLE CDR (CAD HEDGED)"}
                  ]
                }
                """;
        String warsawResponse = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "CDR.WA",
                        "currency": "PLN",
                        "exchangeName": "WSE",
                        "fullExchangeName": "Warsaw",
                        "longName": "CD Projekt S.A.",
                        "shortName": "CDPROJEKT",
                        "exchangeTimezoneName": "Europe/Warsaw"
                      },
                      "timestamp": [%d],
                      "indicators": {"quote": [{
                        "open": [230.0],
                        "high": [235.0],
                        "low": [229.0],
                        "close": [233.0],
                        "volume": [195827]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(timestamp);

        server.expect(requestTo(containsString("/v8/finance/chart/CDR?")))
                .andRespond(withSuccess(unavailableResponse, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v1/finance/search?q=CDR")))
                .andRespond(withSuccess(searchResponse, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/CDR.WA?")))
                .andRespond(withSuccess(warsawResponse, MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate, new ObjectMapper(), stockAssetRepository, "https://query1.finance.yahoo.com", true);

        List<MarketDataBar> bars = service.getTimeSeries("CDR", "1d", 1000);

        assertThat(bars).hasSize(1);
        assertThat(bars.getFirst().providerSymbol()).isEqualTo("CDR.WA");
        assertThat(bars.getFirst().close()).isEqualTo(233.0);
        verify(stockAssetRepository).save(argThat(asset ->
                "CDR".equals(asset.getTickerSymbol())
                        && "CD Projekt S.A.".equals(asset.getCompanyName())
                        && "Warsaw".equals(asset.getExchange())
                        && "PLN".equals(asset.getCurrency())));
        server.verify();
    }

    @Test
    void resolvesSp500AliasAndMarksTheAssetAsAnIndex() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("^GSPC")).thenReturn(Optional.empty());
        when(stockAssetRepository.save(any(StockAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        long timestamp = LocalDate.of(2026, 7, 10).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String response = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "^GSPC",
                        "currency": "USD",
                        "exchangeName": "SNP",
                        "fullExchangeName": "S&P Index",
                        "shortName": "S&P 500",
                        "instrumentType": "INDEX",
                        "exchangeTimezoneName": "America/New_York"
                      },
                      "timestamp": [%d],
                      "indicators": {"quote": [{
                        "open": [6200.0],
                        "high": [6250.0],
                        "low": [6180.0],
                        "close": [6240.0],
                        "volume": [3000000000]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(timestamp);

        server.expect(requestTo(containsString("/v8/finance/chart/%5EGSPC?")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        YahooFinanceService service = new YahooFinanceService(
                restTemplate, new ObjectMapper(), stockAssetRepository, "https://query1.finance.yahoo.com", true);

        List<MarketDataBar> bars = service.getTimeSeries("SPX", "1wk", 1000);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.providerSymbol()).isEqualTo("^GSPC");
            assertThat(bar.close()).isEqualTo(6240.0);
        });
        verify(stockAssetRepository).save(argThat(asset ->
                "^GSPC".equals(asset.getTickerSymbol())
                        && "S&P 500".equals(asset.getCompanyName())
                        && asset.getInstrumentType() == InstrumentType.INDEX));
        server.verify();
    }
}
