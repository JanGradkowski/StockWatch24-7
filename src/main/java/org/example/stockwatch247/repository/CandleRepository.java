package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.Candle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandleRepository extends JpaRepository<Candle, Long> {

    // 1. Initial Load: Get the latest 100 candles
    List<Candle> findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(String symbol, String timeInterval);

    // 2. Pagination: Get the 100 candles that occurred strictly BEFORE a specific timestamp
    List<Candle> findTop100BySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(String symbol, String timeInterval, Long timestamp);

    // 3. Sync Check: Find the absolute newest candle we have in the database
    List<Candle> findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(String symbol, String timeInterval);
    List<Candle> findTop2BySymbolAndTimeIntervalOrderByTimestampDesc(String symbol, String timeInterval);
    List<Candle> findTop10BySymbolAndTimeIntervalOrderByTimestampDesc(String symbol, String timeInterval);
    List<Candle> findBySymbolAndTimeIntervalOrderByTimestampAsc(String symbol, String timeInterval);

    // 4. Cache Density Check: See how many candles we have in a specific historical window
    long countBySymbolAndTimeIntervalAndTimestampBetween(String symbol, String timeInterval, Long startTime, Long endTime);

    // 5. Duplication Check
    boolean existsBySymbolAndTimestampAndTimeInterval(String symbol, long timestamp, String timeInterval);
    Optional<Candle> findBySymbolAndTimestampAndTimeInterval(String symbol, long timestamp, String timeInterval);
    List<Candle> findBySymbolAndTimeIntervalAndTimestampIn(String symbol,
                                                           String timeInterval,
                                                           Collection<Long> timestamps);
}
