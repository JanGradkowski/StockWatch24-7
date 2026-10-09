package org.example.stockwatch247.service;

import org.example.stockwatch247.model.ProviderSymbolAlias;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.MarketDataProvider;
import org.example.stockwatch247.repository.ProviderSymbolAliasRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class ProviderSymbolRegistry {
    private final ProviderSymbolAliasRepository aliasRepository;

    public ProviderSymbolRegistry(ProviderSymbolAliasRepository aliasRepository) {
        this.aliasRepository = aliasRepository;
    }

    @Transactional(readOnly = true)
    public Optional<ProviderSymbolReference> find(StockAsset asset, MarketDataProvider provider) {
        if (asset == null || asset.getId() == null || provider == null) {
            return Optional.empty();
        }
        return aliasRepository.findByStockAsset_IdAndProvider(asset.getId(), provider)
                .filter(alias -> !conflictsWithListing(asset, provider, alias.getMicCode()))
                .map(this::toReference);
    }

    @Transactional
    public void remember(StockAsset asset,
                         MarketDataProvider provider,
                         String providerSymbol,
                         String micCode,
                         String resolutionSource) {
        if (asset == null || asset.getId() == null || provider == null) {
            return;
        }
        String symbol = normalizeSymbol(providerSymbol);
        if (symbol.isBlank() || conflictsWithListing(asset, provider, micCode)) {
            return;
        }
        aliasRepository.upsert(
                asset.getId(),
                provider.name(),
                symbol,
                normalizeMic(micCode),
                safeSource(resolutionSource)
        );
    }

    private ProviderSymbolReference toReference(ProviderSymbolAlias alias) {
        return new ProviderSymbolReference(alias.getProviderSymbol(), alias.getMicCode());
    }

    private boolean conflictsWithListing(StockAsset asset, MarketDataProvider provider, String micCode) {
        String expected = normalizeMic(asset.getMicCode()), actual = normalizeMic(micCode);
        return provider == MarketDataProvider.TWELVE_DATA && expected != null && actual != null
                && !expected.equals(actual);
    }

    private String normalizeSymbol(String rawSymbol) {
        if (rawSymbol == null) {
            return "";
        }
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        return symbol.length() <= 64 && symbol.chars().noneMatch(Character::isISOControl) ? symbol : "";
    }

    private String normalizeMic(String rawMic) {
        String mic = rawMic == null ? "" : rawMic.trim().toUpperCase(Locale.ROOT);
        return mic.matches("[A-Z0-9]{4,12}") ? mic : null;
    }

    private String safeSource(String rawSource) {
        String source = rawSource == null ? "PROVIDER_METADATA" : rawSource.trim().toUpperCase(Locale.ROOT);
        return source.matches("[A-Z0-9_]{1,32}") ? source : "PROVIDER_METADATA";
    }

    public record ProviderSymbolReference(String symbol, String micCode) {
    }
}
