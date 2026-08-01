package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.StockAsset;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InsiderTradeRepository extends JpaRepository<InsiderTrade, Long> {
    Optional<InsiderTrade> findByProviderAndProviderFingerprint(String provider, String providerFingerprint);

    @EntityGraph(attributePaths = "stockAsset")
    List<InsiderTrade> findByStockAssetAndTransactionDateGreaterThanEqualOrderByFilingDateDescTransactionDateDescIdDesc(
            StockAsset stockAsset,
            LocalDate transactionDate);
}
