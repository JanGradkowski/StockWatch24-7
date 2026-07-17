package org.example.stockwatch247.service;

public interface MarketDataHistoryStateStore {
    boolean isEndReached(String symbol, String interval);

    void recordProgress(String symbol, String interval, long oldestTimestamp, boolean endReached);
}
