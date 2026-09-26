package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.MarketDataProvider;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooFinanceServiceTest {

    @Test
    void berkshireShareClassesUseYahooDashWithoutChangingCanonicalTicker() {
        for (String symbol : List.of("BRK.A", "BRK.B")) {
            var rest = new RestTemplate();
            var server = MockRestServiceServer.bindTo(rest).build();
            var repository = mock(StockAssetRepository.class);
            var asset = new StockAsset(); asset.setTickerSymbol(symbol); asset.setCompanyName("Berkshire Hathaway Inc.");
            asset.setExchange("NYSE"); asset.setCountry("United States"); asset.setCurrency("USD");
            when(repository.findByTickerSymbolIgnoreCase(symbol)).thenReturn(Optional.of(asset));
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            server.expect(requestTo(containsString("/v8/finance/chart/"+symbol.replace('.','-')+"?")))
                    .andRespond(withSuccess("""
                    {"chart":{"result":[{"meta":{"symbol":"%s","currency":"USD",
                      "longName":"Berkshire Hathaway Inc.","exchangeName":"NYSE","instrumentType":"EQUITY",
                      "exchangeTimezoneName":"America/New_York","dataGranularity":"1d"},
                      "timestamp":[1790002800],"indicators":{"quote":[{"open":[100],"high":[105],"low":[99],"close":[104],"volume":[1000]}]}}],"error":null}}
                    """.formatted(symbol.replace('.','-')),MediaType.APPLICATION_JSON));
            var service = new YahooFinanceService(rest,new ObjectMapper(),repository,"https://query1.finance.yahoo.com",true);
            assertThat(service.getTimeSeries(symbol,"1d",10)).hasSize(1);
            assertThat(asset.getTickerSymbol()).isEqualTo(symbol);
            server.verify();
        }
    }

    @Test
    void acceptsWarsawCompanyWhenProviderLongNameChangesButCompleteShortNameStillMatches() {
        var fixture = warsawFixture("CDPROJEKT", "PLN");
        var bars = fixture.service().getTimeSeries("CDR", "1d", 10);
        assertThat(bars).singleElement().satisfies(bar -> assertThat(bar.providerSymbol()).isEqualTo("CDR.WA"));
        verify(fixture.repository()).save(argThat(asset -> asset.getCompanyName().equals("CD Projekt Red S.A.")));
        fixture.server().verify();
    }

    @Test
    void alternateProviderNameCannotBypassCompanyOrCurrencyChecks() {
        var unrelated = warsawFixture("CD PROJEKT OTHER", "PLN");
        assertThatThrownBy(() -> unrelated.service().getTimeSeries("CDR", "1d", 10))
                .hasMessageContaining("different company");
        verify(unrelated.repository(), never()).save(any());
        unrelated.server().verify();

        var wrongCurrency = warsawFixture("CDPROJEKT", "USD");
        assertThatThrownBy(() -> wrongCurrency.service().getTimeSeries("CDR", "1d", 10))
                .hasMessageContaining("USD instead of PLN");
        verify(wrongCurrency.repository(), never()).save(any());
        wrongCurrency.server().verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"America/New_York", "Europe/Warsaw"})
    void weeklyResponseRejectsLiveQuotesEvenWhenTheyFallOnMonday(String zoneName) {
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var repository = mock(StockAssetRepository.class);
        ZoneId zone = ZoneId.of(zoneName);
        LocalDate monday = LocalDate.of(2026, 9, 14);
        long weekly = monday.atStartOfDay(zone).toEpochSecond();
        long mondayLive = monday.atTime(16, 0).atZone(zone).toEpochSecond();
        long fridayLive = monday.plusDays(4).atTime(16, 0).atZone(zone).toEpochSecond();
        server.expect(requestTo(containsString("/v8/finance/chart/TEST?"))).andRespond(withSuccess("""
                {"chart":{"result":[{"meta":{"symbol":"TEST","currency":"USD",
                 "exchangeTimezoneName":"%s","dataGranularity":"1wk"},
                 "timestamp":[%d,%d,%d],"indicators":{"quote":[{
                   "open":[100,110,120],"high":[130,111,121],"low":[90,109,119],
                   "close":[125,110,120],"volume":[1000,10,20]}]}}],"error":null}}
                """.formatted(zoneName, weekly, mondayLive, fridayLive), MediaType.APPLICATION_JSON));
        var service = new YahooFinanceService(rest, new ObjectMapper(), repository, "https://query1.finance.yahoo.com", true);
        assertThat(service.getTimeSeries("TEST", "1wk", 10)).singleElement().satisfies(bar -> {
            assertThat(bar.timestamp()).isEqualTo(monday.atStartOfDay(ZoneOffset.UTC).toEpochSecond());
            assertThat(bar.open()).isEqualTo(100);
            assertThat(bar.high()).isEqualTo(130);
            assertThat(bar.close()).isEqualTo(125);
            assertThat(bar.volume()).isEqualTo(1000);
        });
        server.verify();
    }

    private WarsawFixture warsawFixture(String shortName, String currency) {
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        var repository = mock(StockAssetRepository.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("CDR"); asset.setCompanyName("CD Projekt S.A.");
        asset.setExchange("GPW"); asset.setMicCode("XWAR"); asset.setCountry("Poland");
        asset.setCurrency("PLN"); asset.setInstrumentType(InstrumentType.EQUITY);
        when(repository.findByTickerSymbolIgnoreCase("CDR")).thenReturn(Optional.of(asset));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        server.expect(org.springframework.test.web.client.ExpectedCount.between(1, 5),
                requestTo(containsString("/v8/finance/chart/"))).andRespond(withSuccess("""
                {"chart":{"result":[{"meta":{"symbol":"CDR.WA","currency":"%s",
                 "longName":"CD Projekt Red S.A.","shortName":"%s","instrumentType":"EQUITY",
                 "exchangeName":"WSE","fullExchangeName":"Warsaw","exchangeTimezoneName":"Europe/Warsaw",
                 "dataGranularity":"1d"},"timestamp":[1790002800],"indicators":{"quote":[{
                 "open":[240],"high":[250],"low":[239],"close":[248],"volume":[1000]}]}}],"error":null}}
                """.formatted(currency, shortName), MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.ExpectedCount.between(0, 5),
                requestTo(containsString("/v1/finance/search")))
                .andRespond(withSuccess("{\"quotes\":[]}", MediaType.APPLICATION_JSON));
        return new WarsawFixture(new YahooFinanceService(rest, new ObjectMapper(), repository,
                "https://query1.finance.yahoo.com", true), server, repository);
    }

    private record WarsawFixture(YahooFinanceService service, MockRestServiceServer server, StockAssetRepository repository) { }

    @Test
    void exactUsTickerRepairsAConflictingEuropeanAliasAndCurrency() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        ProviderSymbolRegistry providerSymbolRegistry = mock(ProviderSymbolRegistry.class);
        StockAsset asset = new StockAsset();
        asset.setId(51L);
        asset.setTickerSymbol("MARA");
        asset.setCompanyName("Marubeni Corporation");
        asset.setExchange("Frankfurt");
        asset.setMicCode("XNCM");
        asset.setCountry("United States");
        asset.setCurrency("EUR");
        asset.setInstrumentType(InstrumentType.EQUITY);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("MARA"))
                .thenReturn(Optional.of(asset));
        when(stockAssetRepository.save(asset)).thenReturn(asset);
        when(providerSymbolRegistry.find(asset, MarketDataProvider.YAHOO_FINANCE))
                .thenReturn(Optional.of(
                        new ProviderSymbolRegistry.ProviderSymbolReference("MARA.F", "XFRA")));

        long timestamp = LocalDate.of(2026, 7, 27)
                .atTime(16, 0)
                .atZone(ZoneId.of("America/New_York"))
                .toEpochSecond();
        server.expect(requestTo(containsString("/v8/finance/chart/MARA?")))
                .andRespond(withSuccess("""
                        {
                          "chart": {
                            "result": [{
                              "meta": {
                                "symbol": "MARA",
                                "currency": "USD",
                                "exchangeName": "NCM",
                                "fullExchangeName": "NasdaqCM",
                                "longName": "MARA Holdings, Inc.",
                                "instrumentType": "EQUITY",
                                "dataGranularity": "1d",
                                "exchangeTimezoneName": "America/New_York"
                              },
                              "timestamp": [%d],
                              "indicators": {"quote": [{
                                "open": [15.0],
                                "high": [15.8],
                                "low": [14.7],
                                "close": [15.4],
                                "volume": [25000000]
                              }]}
                            }],
                            "error": null
                          }
                        }
                        """.formatted(timestamp), MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate,
                tools.jackson.databind.json.JsonMapper.builder().build(),
                stockAssetRepository,
                providerSymbolRegistry,
                "https://query1.finance.yahoo.com",
                true);

        StockAsset repaired = service.refreshStockAssetMetadata("MARA");

        assertThat(repaired.getCompanyName()).isEqualTo("MARA Holdings, Inc.");
        assertThat(repaired.getExchange()).isEqualTo("NasdaqCM");
        assertThat(repaired.getCurrency()).isEqualTo("USD");
        assertThat(repaired.getMicCode()).isEqualTo("XNCM");
        verify(providerSymbolRegistry).remember(
                asset,
                MarketDataProvider.YAHOO_FINANCE,
                "MARA",
                "XNAS",
                "DIRECT_OR_ALIAS");
        server.verify();
    }

    @Test
    void resolvesCboeDinoSymbolToVerifiedYahooWarsawPrimaryListing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        ProviderSymbolRegistry providerSymbolRegistry = mock(ProviderSymbolRegistry.class);
        StockAsset asset = new StockAsset();
        asset.setId(42L);
        asset.setTickerSymbol("DNPW");
        asset.setCompanyName("Dino Polska S.A.");
        asset.setExchange("CBOE");
        asset.setMicCode("BCXE");
        asset.setCountry("United Kingdom");
        asset.setCurrency("PLN");
        asset.setInstrumentType(InstrumentType.EQUITY);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("DNPW")).thenReturn(Optional.of(asset));
        when(stockAssetRepository.save(asset)).thenReturn(asset);
        when(providerSymbolRegistry.find(asset, MarketDataProvider.YAHOO_FINANCE)).thenReturn(Optional.empty());

        String unavailableResponse = """
                {"chart":{"result":null,"error":{"description":"No data found"}}}
                """;
        server.expect(requestTo(containsString("/v8/finance/chart/DNPW?")))
                .andRespond(withSuccess(unavailableResponse, MediaType.APPLICATION_JSON));
        server.expect(request -> {
                    String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query).contains("q=Dino Polska S.A.");
                })
                .andRespond(withSuccess("""
                        {
                          "quotes": [
                            {"symbol":"DNOPF","quoteType":"EQUITY","longname":"Dino Polska S.A.","exchange":"PNK"},
                            {"symbol":"5Y2.F","quoteType":"EQUITY","longname":"Dino Polska S.A.","exchange":"FRA"},
                            {"symbol":"5Y2.DU","quoteType":"EQUITY","longname":"Dino Polska S.A.","exchange":"DUS"},
                            {"symbol":"0TCP.IL","quoteType":"EQUITY","longname":"Dino Polska S.A.","exchange":"IOB"},
                            {"symbol":"DNOPY","quoteType":"EQUITY","longname":"Dino Polska S.A.","exchange":"PNK"},
                            {"symbol":"DNP.WA","quoteType":"EQUITY","longname":"Dino Polska S.A.","exchange":"WSE"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        long timestamp = LocalDate.of(2026, 7, 21)
                .atTime(17, 0)
                .atZone(ZoneId.of("Europe/Warsaw"))
                .toEpochSecond();
        server.expect(requestTo(containsString("/v8/finance/chart/DNP.WA?")))
                .andRespond(withSuccess("""
                        {
                          "chart": {
                            "result": [{
                              "meta": {
                                "symbol": "DNP.WA",
                                "currency": "PLN",
                                "exchangeName": "WSE",
                                "fullExchangeName": "Warsaw",
                                "longName": "Dino Polska S.A.",
                                "instrumentType": "EQUITY",
                                "dataGranularity": "1d",
                                "exchangeTimezoneName": "Europe/Warsaw"
                              },
                              "timestamp": [%d],
                              "indicators": {"quote": [{
                                "open": [41.2], "high": [42.1], "low": [40.9],
                                "close": [41.8], "volume": [1250000]
                              }]}
                            }],
                            "error": null
                          }
                        }
                        """.formatted(timestamp), MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate,
                tools.jackson.databind.json.JsonMapper.builder().build(),
                stockAssetRepository,
                providerSymbolRegistry,
                "https://query1.finance.yahoo.com",
                true
        );

        List<MarketDataBar> bars = service.getTimeSeries("DNPW", "1d", 1000);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.providerSymbol()).isEqualTo("DNP.WA");
            assertThat(bar.close()).isEqualTo(41.8);
        });
        assertThat(asset.getExchange()).isEqualTo("CBOE");
        assertThat(asset.getMicCode()).isEqualTo("BCXE");
        verify(providerSymbolRegistry).remember(
                asset,
                MarketDataProvider.YAHOO_FINANCE,
                "DNP.WA",
                "XWAR",
                "VERIFIED_SEARCH"
        );
        server.verify();
    }

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
                        "dataGranularity": "1d",
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
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository, "https://query1.finance.yahoo.com", true);

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
    void acceptsEquivalentFrenchLegalFormWhenResolvingParisTicker() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        ProviderSymbolRegistry providerSymbolRegistry = mock(ProviderSymbolRegistry.class);
        StockAsset asset = new StockAsset();
        asset.setId(1535L);
        asset.setTickerSymbol("ALHGR");
        asset.setCompanyName("Hoffmann Green Cement Technologies S.A.");
        asset.setExchange("Euronext");
        asset.setMicCode("XPAR");
        asset.setCountry("France");
        asset.setCurrency("EUR");
        asset.setInstrumentType(InstrumentType.EQUITY);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("ALHGR")).thenReturn(Optional.of(asset));
        when(stockAssetRepository.save(asset)).thenReturn(asset);
        when(providerSymbolRegistry.find(asset, MarketDataProvider.YAHOO_FINANCE)).thenReturn(Optional.empty());

        long timestamp = LocalDate.of(2026, 7, 23)
                .atTime(17, 30)
                .atZone(ZoneId.of("Europe/Paris"))
                .toEpochSecond();
        server.expect(requestTo(containsString("/v8/finance/chart/ALHGR.PA?")))
                .andRespond(withSuccess("""
                        {
                          "chart": {
                            "result": [{
                              "meta": {
                                "symbol": "ALHGR.PA",
                                "currency": "EUR",
                                "exchangeName": "PAR",
                                "fullExchangeName": "Paris",
                                "longName": "Hoffmann Green Cement Technologies Societe anonyme",
                                "shortName": "HOFFMANN",
                                "instrumentType": "EQUITY",
                                "dataGranularity": "1d",
                                "exchangeTimezoneName": "Europe/Paris"
                              },
                              "timestamp": [%d],
                              "indicators": {"quote": [{
                                "open": [3.80],
                                "high": [3.95],
                                "low": [3.75],
                                "close": [3.90],
                                "volume": [42893]
                              }]}
                            }],
                            "error": null
                          }
                        }
                        """.formatted(timestamp), MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate,
                tools.jackson.databind.json.JsonMapper.builder().build(),
                stockAssetRepository,
                providerSymbolRegistry,
                "https://query1.finance.yahoo.com",
                true
        );

        List<MarketDataBar> bars = service.getTimeSeries("ALHGR", "1d", 1000);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.providerSymbol()).isEqualTo("ALHGR.PA");
            assertThat(bar.close()).isEqualTo(3.90);
        });
        verify(providerSymbolRegistry).remember(
                asset,
                MarketDataProvider.YAHOO_FINANCE,
                "ALHGR.PA",
                "XPAR",
                "DIRECT_OR_ALIAS"
        );
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
                        "dataGranularity": "1d",
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
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository, "https://query1.finance.yahoo.com", true);

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

        long timestamp = LocalDate.of(2026, 7, 6).atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond();
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
                        "dataGranularity": "1wk",
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

        server.expect(requestTo(allOf(
                        containsString("/v8/finance/chart/%5EGSPC?"),
                        containsString("interval=1wk"),
                        containsString("period1="),
                        containsString("period2="),
                        not(containsString("range=")))))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        YahooFinanceService service = new YahooFinanceService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository, "https://query1.finance.yahoo.com", true);

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

    @Test
    void monthlyRequestUsesPeriodBoundsSoYahooPreservesMonthlyGranularity() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("ZAB");
        asset.setCompanyName("Zabka Group S.A.");
        asset.setExchange("Warsaw");
        asset.setCurrency("PLN");
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("ZAB")).thenReturn(Optional.of(asset));
        when(stockAssetRepository.save(any(StockAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        long octoberAtExchangeMidnight = LocalDate.of(2025, 10, 1).atStartOfDay(warsaw).toEpochSecond();
        long decemberAtExchangeMidnight = LocalDate.of(2025, 12, 1).atStartOfDay(warsaw).toEpochSecond();
        long canonicalOctober = LocalDate.of(2025, 10, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long canonicalDecember = LocalDate.of(2025, 12, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String response = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "ZAB.WA",
                        "currency": "PLN",
                        "exchangeName": "WSE",
                        "fullExchangeName": "Warsaw",
                        "shortName": "Zabka Group S.A.",
                        "dataGranularity": "1mo",
                        "exchangeTimezoneName": "Europe/Warsaw"
                      },
                      "timestamp": [%d, %d],
                      "indicators": {"quote": [{
                        "open": [20.0, 21.0],
                        "high": [22.0, 23.0],
                        "low": [19.0, 20.0],
                        "close": [21.0, 22.0],
                        "volume": [1000000, 1200000]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(octoberAtExchangeMidnight, decemberAtExchangeMidnight);

        server.expect(requestTo(allOf(
                        containsString("/v8/finance/chart/ZAB.WA?"),
                        containsString("interval=1mo"),
                        containsString("period1="),
                        containsString("period2="),
                        not(containsString("range=")))))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository,
                "https://query1.finance.yahoo.com", true);

        List<MarketDataBar> bars = service.getTimeSeries("ZAB", "1mo", 1000);

        assertThat(bars).hasSize(2);
        assertThat(bars).extracting(MarketDataBar::providerSymbol).containsOnly("ZAB.WA");
        assertThat(bars).extracting(MarketDataBar::timestamp)
                .containsExactly(canonicalOctober, canonicalDecember);
        assertThat(bars).extracting(MarketDataBar::close).containsExactly(21.0, 22.0);
        server.verify();
    }

    @Test
    void monthlyRequestDropsYahooLiveSnapshotThatUsesADailyTimestamp() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("^GSPC");
        asset.setCompanyName("S&P 500");
        asset.setExchange("SNP");
        asset.setCurrency("USD");
        asset.setInstrumentType(InstrumentType.INDEX);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("^GSPC")).thenReturn(Optional.of(asset));
        when(stockAssetRepository.save(any(StockAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ZoneId newYork = ZoneId.of("America/New_York");
        long julyMonthlyAtExchangeMidnight = LocalDate.of(2026, 7, 1).atStartOfDay(newYork).toEpochSecond();
        long canonicalJuly = LocalDate.of(2026, 7, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long julyDailySnapshot = LocalDate.of(2026, 7, 29).atStartOfDay(newYork).plusHours(12).toEpochSecond();
        String response = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "^GSPC",
                        "currency": "USD",
                        "exchangeName": "SNP",
                        "fullExchangeName": "S&P 500",
                        "shortName": "S&P 500",
                        "quoteType": "INDEX",
                        "dataGranularity": "1mo",
                        "exchangeTimezoneName": "America/New_York"
                      },
                      "timestamp": [%d, %d],
                      "indicators": {"quote": [{
                        "open": [7478.84, 7418.16],
                        "high": [7581.50, 7450.84],
                        "low": [7313.92, 7313.92],
                        "close": [7489.72, 7316.15],
                        "volume": [0, 0]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(julyMonthlyAtExchangeMidnight, julyDailySnapshot);

        server.expect(requestTo(containsString("interval=1mo")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository,
                "https://query1.finance.yahoo.com", true);

        List<MarketDataBar> bars = service.getTimeSeries("^GSPC", "1mo", 1000);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.timestamp()).isEqualTo(canonicalJuly);
            assertThat(bar.close()).isEqualTo(7489.72);
        });
        server.verify();
    }

    @Test
    void weeklyRequestDropsNullAndEpochTimestampSnapshots() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("ZTS");
        asset.setCompanyName("Zoetis Inc.");
        asset.setExchange("NYSE");
        asset.setCurrency("USD");
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("ZTS")).thenReturn(Optional.of(asset));

        long providerTimestamp = LocalDate.of(2026, 8, 3)
                .atStartOfDay()
                .atZone(ZoneId.of("America/New_York"))
                .toEpochSecond();
        long expectedTimestamp = LocalDate.of(2026, 8, 3)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond();
        String response = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "ZTS",
                        "currency": "USD",
                        "exchangeName": "NYQ",
                        "fullExchangeName": "NYSE",
                        "shortName": "Zoetis Inc.",
                        "dataGranularity": "1wk",
                        "exchangeTimezoneName": "America/New_York"
                      },
                      "timestamp": [%d, null, 0],
                      "indicators": {"quote": [{
                        "open": [72.0, 122.85, 122.85],
                        "high": [74.0, 122.93, 122.93],
                        "low": [71.0, 122.84, 122.84],
                        "close": [72.66, 122.87, 122.87],
                        "volume": [1000000, 597475, 597475]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(providerTimestamp);

        server.expect(requestTo(containsString("/v8/finance/chart/ZTS?")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        YahooFinanceService service = new YahooFinanceService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository,
                "https://query1.finance.yahoo.com", true);

        List<MarketDataBar> bars = service.getTimeSeries("ZTS", "1wk", 1000);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.timestamp()).isEqualTo(expectedTimestamp);
            assertThat(bar.close()).isEqualTo(72.66);
        });
        server.verify();
    }

    @Test
    void historicalPageUsesPeriodBoundsInsteadOfARange() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("MSFT");
        asset.setCompanyName("Microsoft Corporation");
        asset.setExchange("NMS");
        asset.setCurrency("USD");
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("MSFT")).thenReturn(Optional.of(asset));

        long before = LocalDate.of(2026, 7, 11).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long timestamp = LocalDate.of(2026, 7, 10).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String response = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "MSFT",
                        "currency": "USD",
                        "exchangeName": "NMS",
                        "shortName": "Microsoft Corporation",
                        "dataGranularity": "1d",
                        "exchangeTimezoneName": "America/New_York"
                      },
                      "timestamp": [%d],
                      "indicators": {"quote": [{
                        "open": [500.0],
                        "high": [505.0],
                        "low": [498.0],
                        "close": [503.0],
                        "volume": [18000000]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(timestamp);

        server.expect(request -> {
                    String query = URLDecoder.decode(
                            request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("interval=1d")
                            .contains("period1=")
                            .contains("period2=" + before)
                            .doesNotContain("range=");
                })
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        YahooFinanceService service = new YahooFinanceService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository,
                "https://query1.finance.yahoo.com", true);

        List<MarketDataBar> bars = service.getTimeSeriesBefore("MSFT", "1d", 2, before);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.timestamp()).isLessThan(before);
            assertThat(bar.close()).isEqualTo(503.0);
        });
        server.verify();
    }

    @Test
    void rejectsMonthlyCandlesReturnedForAWeeklyRequest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("^GSPC")).thenReturn(Optional.empty());

        long timestamp = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String response = """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "^GSPC",
                        "dataGranularity": "1mo",
                        "exchangeTimezoneName": "America/New_York"
                      },
                      "timestamp": [%d],
                      "indicators": {"quote": [{
                        "open": [5900.0],
                        "high": [6100.0],
                        "low": [5800.0],
                        "close": [6050.0],
                        "volume": [3000000000]
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(timestamp);

        server.expect(requestTo(allOf(
                        containsString("/v8/finance/chart/%5EGSPC?"),
                        containsString("interval=1wk"),
                        containsString("period1="),
                        containsString("period2="),
                        not(containsString("range=")))))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        YahooFinanceService service = new YahooFinanceService(
                restTemplate, tools.jackson.databind.json.JsonMapper.builder().build(), stockAssetRepository, "https://query1.finance.yahoo.com", true);

        assertThatThrownBy(() -> service.getTimeSeries("SPX", "1wk", 1000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned 1mo candles")
                .hasMessageContaining("when 1wk was requested");
        verify(stockAssetRepository, never()).save(any(StockAsset.class));
        server.verify();
    }
}
