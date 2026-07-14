package org.example.stockwatch247.service;

/**
 * Provider-neutral OHLCV data used at the market-data ingestion boundary.
 */
public record MarketDataBar(
        String providerSymbol,
        long timestamp,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
}
