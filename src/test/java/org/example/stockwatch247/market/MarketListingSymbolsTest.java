package org.example.stockwatch247.market;

import org.example.stockwatch247.security.SecurityInputValidator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MarketListingSymbolsTest {
    @Test void sameTickerOnDifferentExchangesStaysDistinct() {
        assertThat(MarketListingSymbols.canonical("RY","XTSE")).isEqualTo("RY.TO");
        assertThat(MarketListingSymbols.canonical("RY","XNYS")).isEqualTo("RY");
        assertThat(MarketListingSymbols.canonical("ASML","XAMS")).isEqualTo("ASML.AS");
        assertThat(MarketListingSymbols.canonical("ASML","XNAS")).isEqualTo("ASML");
    }
    @Test void indexAliasesAndAlreadyQualifiedListingsRemainStable() {
        assertThat(MarketListingSymbols.canonical("SPX",null)).isEqualTo("^GSPC");
        assertThat(MarketListingSymbols.canonical("7203.T","XTKS")).isEqualTo("7203.T");
        assertThat(MarketListingSymbols.canonical("700","XHKG")).isEqualTo("0700.HK");
        assertThat(MarketListingSymbols.localSymbol("BIP-UN.TO")).isEqualTo("BIP.UN");
        assertThat(MarketListingSymbols.mic("7203.T")).isEqualTo("XTKS");
        assertThat(SecurityInputValidator.requireMarketSymbol("M&M.NS")).isEqualTo("M&M.NS");
    }
}
