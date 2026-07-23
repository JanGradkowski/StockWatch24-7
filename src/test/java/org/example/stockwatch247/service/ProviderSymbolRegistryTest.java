package org.example.stockwatch247.service;

import org.example.stockwatch247.model.ProviderSymbolAlias;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.MarketDataProvider;
import org.example.stockwatch247.repository.ProviderSymbolAliasRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSymbolRegistryTest {

    @Test
    void storesAndResolvesAProviderSpecificSymbolForAnInternalAsset() {
        ProviderSymbolAliasRepository repository = mock(ProviderSymbolAliasRepository.class);
        ProviderSymbolAlias alias = mock(ProviderSymbolAlias.class);
        StockAsset asset = new StockAsset();
        asset.setId(42L);
        when(alias.getProviderSymbol()).thenReturn("DNP.WA");
        when(alias.getMicCode()).thenReturn("XWAR");
        when(repository.findByStockAsset_IdAndProvider(42L, MarketDataProvider.YAHOO_FINANCE))
                .thenReturn(Optional.of(alias));
        ProviderSymbolRegistry registry = new ProviderSymbolRegistry(repository);

        registry.remember(asset, MarketDataProvider.YAHOO_FINANCE,
                " dnp.wa ", "xwar", "verified_search");
        Optional<ProviderSymbolRegistry.ProviderSymbolReference> resolved = registry.find(
                asset, MarketDataProvider.YAHOO_FINANCE);

        verify(repository).upsert(
                42L,
                "YAHOO_FINANCE",
                "DNP.WA",
                "XWAR",
                "VERIFIED_SEARCH"
        );
        assertThat(resolved).contains(new ProviderSymbolRegistry.ProviderSymbolReference("DNP.WA", "XWAR"));
    }
}
