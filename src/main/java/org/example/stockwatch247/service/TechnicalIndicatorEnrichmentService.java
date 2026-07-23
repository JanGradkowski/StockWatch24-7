package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class TechnicalIndicatorEnrichmentService {
    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int FAST_PERIOD = 20;
    private static final int SLOW_PERIOD = 200;
    private static final double BOLLINGER_DEVIATION = 2.0;

    public List<EnrichedCandle> enrichForSignalDetection(List<Candle> rawCandles) {
        return enrich(rawCandles, DEFAULT_SIGNAL_CANDLES);
    }

    /**
     * Returns the input history needed for every emitted candle to have a fully
     * warmed value for the longest technical indicator. The current candle is
     * part of the indicator period, so only {@code SLOW_PERIOD - 1} additional
     * warm-up candles are required.
     */
    public int requiredInputCandles(int latestCount) {
        if (latestCount <= 0) {
            return 0;
        }
        long required = (long) latestCount + SLOW_PERIOD - 1L;
        return required > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) required;
    }

    public List<EnrichedCandle> enrich(List<Candle> rawCandles, int latestCount) {
        if (rawCandles == null || rawCandles.isEmpty() || latestCount <= 0) {
            return List.of();
        }

        List<Candle> candles = rawCandles.stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        if (candles.isEmpty()) {
            return List.of();
        }

        BarSeries series = toSeries(candles);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator averageVolume20 = new SMAIndicator(volume, FAST_PERIOD);
        RSIIndicator rsi14 = new RSIIndicator(close, RSI_PERIOD);
        EMAIndicator ema20 = new EMAIndicator(close, FAST_PERIOD);
        SMAIndicator sma200 = new SMAIndicator(close, SLOW_PERIOD);
        ATRIndicator atr14 = new ATRIndicator(series, ATR_PERIOD);
        BollingerBandsMiddleIndicator bollingerMiddle = new BollingerBandsMiddleIndicator(
                new SMAIndicator(close, FAST_PERIOD)
        );
        StandardDeviationIndicator standardDeviation = StandardDeviationIndicator.ofPopulation(close, FAST_PERIOD);
        BollingerBandsLowerIndicator lowerBollinger = new BollingerBandsLowerIndicator(
                bollingerMiddle,
                standardDeviation,
                series.numFactory().numOf(BOLLINGER_DEVIATION)
        );
        BollingerBandsUpperIndicator upperBollinger = new BollingerBandsUpperIndicator(
                bollingerMiddle,
                standardDeviation,
                series.numFactory().numOf(BOLLINGER_DEVIATION)
        );

        int firstIndex = Math.max(0, candles.size() - latestCount);
        List<EnrichedCandle> enrichedCandles = new java.util.ArrayList<>();
        for (int index = firstIndex; index < candles.size(); index++) {
            Candle candle = candles.get(index);
            enrichedCandles.add(new EnrichedCandle(
                    candle.getTimestamp(),
                    candle.getOpenPrice(),
                    candle.getHighPrice(),
                    candle.getLowPrice(),
                    candle.getClosePrice(),
                    candle.getVolume() == null ? 0.0 : candle.getVolume(),
                    indicatorValue(averageVolume20, index, FAST_PERIOD),
                    indicatorValue(rsi14, index, RSI_PERIOD + 1),
                    indicatorValue(ema20, index, FAST_PERIOD),
                    indicatorValue(sma200, index, SLOW_PERIOD),
                    indicatorValue(lowerBollinger, index, FAST_PERIOD),
                    indicatorValue(upperBollinger, index, FAST_PERIOD),
                    indicatorValue(atr14, index, ATR_PERIOD + 1)
            ));
        }
        return List.copyOf(enrichedCandles);
    }

    private BarSeries toSeries(List<Candle> candles) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(candles.get(0).getSymbol() == null ? "signal-series" : candles.get(0).getSymbol())
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
        if (candles.size() < 2) {
            return Duration.ofDays(1);
        }

        long seconds = Math.max(1L, candles.get(1).getTimestamp() - candles.get(0).getTimestamp());
        return Duration.ofSeconds(seconds);
    }

    private double indicatorValue(Indicator<Num> indicator, int index, int minimumBars) {
        if (index + 1 < minimumBars) {
            return Double.NaN;
        }

        Num value = indicator.getValue(index);
        return value == null || value.isNaN() ? Double.NaN : value.doubleValue();
    }

    private boolean hasCompletePriceData(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null
                && candle.getHighPrice() != null
                && candle.getLowPrice() != null
                && candle.getClosePrice() != null;
    }
}
