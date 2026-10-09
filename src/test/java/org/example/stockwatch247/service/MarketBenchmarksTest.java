package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketBenchmarksTest {

    @Test
    void theExchangeDecidesTheBenchmarkBeforeTheCurrency() {
        assertThat(benchmark("XWAR", "WSE", "Poland", "PLN").symbol()).isEqualTo("ETFBW20TR.WA");
        assertThat(benchmark("XTSE", "TSX", "Canada", "CAD").symbol()).isEqualTo("^GSPTSE");
        assertThat(benchmark("XASX", "ASX", "Australia", "AUD").symbol()).isEqualTo("^AXJO");
        assertThat(benchmark("XSWX", "SIX", "Switzerland", "CHF").symbol()).isEqualTo("^SSMI");
        assertThat(benchmark("XAMS", "Euronext Amsterdam", "Netherlands", "EUR").symbol()).isEqualTo("^AEX");
        assertThat(benchmark("XMIL", "Borsa Italiana", "Italy", "EUR").symbol()).isEqualTo("FTSEMIB.MI");
        assertThat(benchmark("XNAS", "NASDAQ", "United States", "USD").symbol()).isEqualTo("^GSPC");
    }

    @Test
    void missingIdentityFallsBackThroughExchangeNameCountryAndCurrency() {
        assertThat(benchmark(null, "Stockholm", null, null).symbol()).isEqualTo("^OMX");
        assertThat(benchmark(null, null, "Spain", null).symbol()).isEqualTo("^IBEX");
        assertThat(benchmark(null, null, null, "DKK").symbol()).isEqualTo("^OMXC25");
        assertThat(benchmark(null, null, null, "EUR").symbol()).isEqualTo("^STOXX50E");
    }

    @Test
    void aMarketWithoutALocalBenchmarkSaysSoRatherThanPretendingTheSandP500Fits() {
        var norway = benchmark("XOSL", "OSL", "Norway", "NOK");

        assertThat(norway.symbol()).isEqualTo("^GSPC");
        assertThat(norway.local()).isFalse();
        assertThat(norway.name()).contains("no local benchmark");
        assertThat(MarketBenchmarks.isBenchmark("ETFBW20TR.WA")).isTrue();
        assertThat(MarketBenchmarks.isBenchmark("AAPL")).isFalse();
    }

    private static MarketBenchmarks.MarketBenchmark benchmark(String mic, String exchange, String country, String currency) {
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("TEST");
        asset.setMicCode(mic);
        asset.setExchange(exchange);
        asset.setCountry(country);
        asset.setCurrency(currency);
        return MarketBenchmarks.forAsset(asset);
    }
}
