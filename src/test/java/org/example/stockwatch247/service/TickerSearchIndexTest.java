package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TickerSearchIndexTest {

    @Test
    void ranksExactTickerBeforeTickerAndCompanyWordPrefixes() {
        TickerSearchIndex index = new TickerSearchIndex();
        index.rebuild(List.of(
                asset("APP", "AppLovin Corporation"),
                asset("AAPL", "Apple Inc."),
                asset("MICRO", "MicroStrategy Holdings"),
                asset("MSFT", "Microsoft Corporation"),
                asset("SOFT", "Mega Soft Holdings")));

        assertThat(symbols(index.search("APP", 8)))
                .startsWith("APP", "AAPL");
        assertThat(symbols(index.search("MIC", 8)))
                .startsWith("MICRO", "MSFT");
        assertThat(symbols(index.search("SOF", 8)))
                .contains("SOFT");
    }

    @Test
    void includesIndexAliasesAndPreservesTickerPunctuation() {
        TickerSearchIndex index = new TickerSearchIndex();
        index.rebuild(List.of(
                asset("BRK.B", "Berkshire Hathaway Class B"),
                asset("OR.PA", "L'Oréal S.A.")));

        assertThat(index.search("SPX", 8).getFirst())
                .containsEntry("symbol", "^GSPC")
                .containsEntry("instrumentType", "INDEX");
        assertThat(index.search("BRK", 8).getFirst())
                .containsEntry("symbol", "BRK.B");
        assertThat(index.search("LOREAL", 8).getFirst())
                .containsEntry("symbol", "OR.PA");
    }

    private List<String> symbols(List<Map<String, Object>> suggestions) {
        return suggestions.stream().map(result -> String.valueOf(result.get("symbol"))).toList();
    }

    private StockAsset asset(String symbol, String name) {
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol(symbol);
        asset.setCompanyName(name);
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");
        asset.setInstrumentType(InstrumentType.EQUITY);
        return asset;
    }
}
