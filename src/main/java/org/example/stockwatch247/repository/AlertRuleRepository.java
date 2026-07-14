package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
    @EntityGraph(attributePaths = {"user", "stockAsset"})
    @Query("""
            select ar from AlertRule ar
            where ar.user = :user
              and ar.stockAsset = :stockAsset
              and ar.interval = :interval
              and ar.tradeSignal = :tradeSignal
              and (
                    ar.patternFamily = :patternFamily
                    or (:patternFamily = org.example.stockwatch247.model.enums.AlertPatternFamily.CANDLESTICK
                        and ar.patternFamily is null)
                  )
            """)
    Optional<AlertRule> findByUserAndStockAssetAndIntervalAndTradeSignalAndPatternFamily(User user,
                                                                                         StockAsset stockAsset,
                                                                                         TimeInterval interval,
                                                                                         TradeSignal tradeSignal,
                                                                                         AlertPatternFamily patternFamily);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    Optional<AlertRule> findByIdAndUserAndIsActiveTrue(Long id, User user);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    List<AlertRule> findByUserAndIsActiveTrueOrderByStockAsset_TickerSymbolAscIntervalAscPatternFamilyAscTradeSignalAsc(User user);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    List<AlertRule> findByUserAndStockAssetAndIsActiveTrue(User user, StockAsset stockAsset);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    List<AlertRule> findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(String tickerSymbol,
                                                                                       TimeInterval interval);

    @Query("select distinct ar.stockAsset.tickerSymbol from AlertRule ar where ar.interval = :interval and ar.isActive = true")
    List<String> findDistinctActiveSymbolsByInterval(TimeInterval interval);

    @Query("select count(distinct ar.stockAsset.id) from AlertRule ar where ar.isActive = true")
    long countDistinctActiveStocks();

    @Query("select count(distinct ar.stockAsset.id) from AlertRule ar where ar.user = :user and ar.isActive = true")
    long countDistinctActiveStocksByUser(User user);

    boolean existsByStockAssetAndIsActiveTrue(StockAsset stockAsset);

    boolean existsByUserAndStockAssetAndIsActiveTrue(User user, StockAsset stockAsset);
}
