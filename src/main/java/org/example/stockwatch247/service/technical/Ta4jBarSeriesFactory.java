package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Converts the application's completed OHLCV candles into one canonical TA4J series. */
public final class Ta4jBarSeriesFactory {

    public BarSeries create(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("At least one completed candle is required.");
        }
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(candles.getFirst().getSymbol() == null
                        ? "technical-series" : candles.getFirst().getSymbol())
                .build();
        Duration timePeriod = inferTimePeriod(candles);
        candles.forEach(candle -> series.addBar(series.barBuilder()
                .timePeriod(timePeriod)
                .endTime(Instant.ofEpochSecond(candle.getTimestamp()))
                .openPrice(candle.getOpenPrice())
                .highPrice(candle.getHighPrice())
                .lowPrice(candle.getLowPrice())
                .closePrice(candle.getClosePrice())
                .volume(candle.getVolume() == null ? 0.0 : candle.getVolume())
                .build()));
        return series;
    }

    private Duration inferTimePeriod(List<Candle> candles) {
        if (candles.size() < 2) return Duration.ofDays(1);
        long seconds = Math.max(1L,
                candles.get(1).getTimestamp() - candles.getFirst().getTimestamp());
        return Duration.ofSeconds(seconds);
    }
}
