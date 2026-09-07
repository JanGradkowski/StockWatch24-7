package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.VirtualTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VirtualTradeRepository extends JpaRepository<VirtualTrade, Long> {
    @Query("select t from VirtualTrade t join fetch t.stockAsset where t.id in :ids and t.user = :user and t.deletedAt is null")
    List<VirtualTrade> findOwnedIds(@Param("ids") List<Long> ids, @Param("user") User user);

    @Query("""
            select trade from VirtualTrade trade
            join fetch trade.stockAsset
            where trade.user = :user and trade.deletedAt is null
            order by trade.entryAt desc, trade.id desc
            """)
    List<VirtualTrade> findAllForUser(@Param("user") User user);

    @Query("""
            select trade from VirtualTrade trade
            join fetch trade.stockAsset
            where trade.user = :user and trade.stockAsset = :stockAsset and trade.deletedAt is null
            order by trade.entryAt desc, trade.id desc
            """)
    List<VirtualTrade> findAllForUserAndStockAsset(@Param("user") User user,
                                                   @Param("stockAsset") StockAsset stockAsset);

    @Query("""
            select trade from VirtualTrade trade
            join fetch trade.stockAsset
            where trade.id = :id and trade.user = :user and trade.deletedAt is null
            """)
    Optional<VirtualTrade> findOwnedById(@Param("id") Long id, @Param("user") User user);

    Optional<VirtualTrade> findByUserAndClientRequestId(User user, String clientRequestId);
}
