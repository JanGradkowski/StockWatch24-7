package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.Candle;

/** Public candle representation; deliberately excludes persistence identifiers. */
public record CandleResponse(
        String symbol,
        String timeInterval,
        Long timestamp,
        Double openPrice,
        Double highPrice,
        Double lowPrice,
        Double closePrice,
        Long volume) {

    public static CandleResponse from(Candle candle) {
        return new CandleResponse(
                candle.getSymbol(),
                candle.getTimeInterval(),
                candle.getTimestamp(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                candle.getVolume());
    }
}
