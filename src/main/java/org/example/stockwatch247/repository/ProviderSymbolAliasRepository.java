package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.ProviderSymbolAlias;
import org.example.stockwatch247.model.enums.MarketDataProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProviderSymbolAliasRepository extends JpaRepository<ProviderSymbolAlias, Long> {

    Optional<ProviderSymbolAlias> findByStockAsset_IdAndProvider(Long stockAssetId,
                                                                 MarketDataProvider provider);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into asset_provider_symbols
                (stock_asset_id, provider, provider_symbol, mic_code, resolution_source, verified_at)
            values
                (:assetId, :provider, :providerSymbol, :micCode, :resolutionSource, current_timestamp)
            on conflict (stock_asset_id, provider) do update set
                provider_symbol = excluded.provider_symbol,
                mic_code = excluded.mic_code,
                resolution_source = excluded.resolution_source,
                verified_at = current_timestamp
            """, nativeQuery = true)
    void upsert(@Param("assetId") Long assetId,
                @Param("provider") String provider,
                @Param("providerSymbol") String providerSymbol,
                @Param("micCode") String micCode,
                @Param("resolutionSource") String resolutionSource);
}
