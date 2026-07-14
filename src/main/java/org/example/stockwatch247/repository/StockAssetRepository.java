package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.StockAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockAssetRepository extends JpaRepository<StockAsset, Long> {
    List<StockAsset> findByTickerSymbolStartsWithIgnoreCase(String tickerSymbol);
    List<StockAsset> findByCompanyNameContainingIgnoreCase(String companyName);

    // NEW: Find exact match for the metadata lookup
    Optional<StockAsset> findByTickerSymbolIgnoreCase(String tickerSymbol);
}
