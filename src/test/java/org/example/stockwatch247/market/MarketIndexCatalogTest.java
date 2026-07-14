package org.example.stockwatch247.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketIndexCatalogTest {

    @Test
    void resolvesTickerAliasesToCanonicalYahooIndexSymbols() {
        assertThat(MarketIndexCatalog.canonicalTickerSymbol("SPX")).isEqualTo("^GSPC");
        assertThat(MarketIndexCatalog.canonicalTickerSymbol("gspc")).isEqualTo("^GSPC");
        assertThat(MarketIndexCatalog.canonicalTickerSymbol("DJIA")).isEqualTo("^DJI");
        assertThat(MarketIndexCatalog.canonicalTickerSymbol("AAPL")).isEqualTo("AAPL");
    }

    @Test
    void findsIndexesByHumanNameAndTickerAlias() {
        assertThat(MarketIndexCatalog.search("S&P 500"))
                .extracting(MarketIndexCatalog.IndexDefinition::symbol)
                .contains("^GSPC");
        assertThat(MarketIndexCatalog.search("SPX").getFirst().symbol()).isEqualTo("^GSPC");
        assertThat(MarketIndexCatalog.search("NASDAQ Composite"))
                .extracting(MarketIndexCatalog.IndexDefinition::symbol)
                .contains("^IXIC");
    }
}
