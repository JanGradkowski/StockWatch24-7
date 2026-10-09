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
    /** Positive, finite prices whose high/low actually bound the open and close. */
    public boolean hasValidPrices() {
        return validPrices(open, high, low, close);
    }

    public static boolean validPrices(double open, double high, double low, double close) {
        return Double.isFinite(open) && Double.isFinite(high) && Double.isFinite(low) && Double.isFinite(close)
                && open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0
                && high >= Math.max(open, close)
                && low <= Math.min(open, close);
    }
}
