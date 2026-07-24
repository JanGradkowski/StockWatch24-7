package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.CongressionalTrade;
import org.example.stockwatch247.model.StockAsset;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CongressionalTradeRepository extends JpaRepository<CongressionalTrade, Long> {

    @EntityGraph(attributePaths = "stockAsset")
    List<CongressionalTrade> findByStockAssetAndTransactionDateGreaterThanEqualOrderByDisclosureDateDescTransactionDateDescIdDesc(
            StockAsset stockAsset,
            LocalDate transactionDate);
}
