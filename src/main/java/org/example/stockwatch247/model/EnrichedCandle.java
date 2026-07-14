package org.example.stockwatch247.model;

public record EnrichedCandle(
        Long timestamp,
        double open,
        double high,
        double low,
        double close,
        double volume,
        double averageVolume20, // 20-period simple moving average of volume
        double rsi14, // 14-period relative strength index
        double ema20, // 20-period exponential moving average
        double sma200,           // 200-period Simple Moving Average
        double lowerBollinger,   // Lower Bollinger Band (20, 2)
        double upperBollinger,   // Upper Bollinger Band (20, 2)
        double atr14             // 14-period Average True Range
) {
}
