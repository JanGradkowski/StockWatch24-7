package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class TechnicalIndicatorEnrichmentService {
    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int VOLUME_PROFILE_BIN_COUNT = 24;
    private static final double VOLUME_PROFILE_VALUE_AREA_FRACTION = 0.70;

    public List<EnrichedCandle> enrichForSignalDetection(List<Candle> rawCandles) {
        return enrich(rawCandles, DEFAULT_SIGNAL_CANDLES);
    }

    /**
     * Returns the input history needed for every emitted candle to have a fully
     * warmed daily-profile value. Prefer the interval-explicit overload in
     * production code.
     */
    public int requiredInputCandles(int latestCount) {
        return requiredInputCandles(latestCount, TimeInterval.DAILY);
    }

    /**
     * The current candle is part of each indicator period, so one fewer
     * additional warm-up candle than the longest profile period is required.
     */
    public int requiredInputCandles(int latestCount, TimeInterval interval) {
        return requiredInputCandles(latestCount, TechnicalIndicatorProfile.forInterval(interval));
    }

    public int requiredElliottInputCandles(int latestCount, TimeInterval interval) {
        return requiredInputCandles(latestCount, TechnicalIndicatorProfile.forElliott(interval));
    }

    private int requiredInputCandles(int latestCount, TechnicalIndicatorProfile profile) {
        if (latestCount <= 0) {
            return 0;
        }
        long required = (long) latestCount + profile.warmupBars() - 1L;
        return required > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) required;
    }

    public List<EnrichedCandle> enrich(List<Candle> rawCandles, int latestCount) {
        if (rawCandles == null || rawCandles.isEmpty() || latestCount <= 0) {
            return List.of();
        }
        return enrich(rawCandles, latestCount, inferInterval(rawCandles));
    }

    public List<EnrichedCandle> enrich(List<Candle> rawCandles,
                                       int latestCount,
                                       TimeInterval interval) {
        return enrich(
                rawCandles,
                latestCount,
                interval,
                TechnicalIndicatorProfile.forInterval(interval)
        );
    }

    public List<EnrichedCandle> enrichForElliott(List<Candle> rawCandles, int latestCount) {
        if (rawCandles == null || rawCandles.isEmpty() || latestCount <= 0) {
            return List.of();
        }
        TimeInterval interval = inferInterval(rawCandles);
        return enrichForElliott(rawCandles, latestCount, interval);
    }

    public List<EnrichedCandle> enrichForElliott(List<Candle> rawCandles,
                                                 int latestCount,
                                                 TimeInterval interval) {
        return enrich(
                rawCandles,
                latestCount,
                interval,
                TechnicalIndicatorProfile.forElliott(interval)
        );
    }

    private List<EnrichedCandle> enrich(List<Candle> rawCandles,
                                        int latestCount,
                                        TimeInterval interval,
                                        TechnicalIndicatorProfile profile) {
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
        validateInterval(candles, interval);

        BarSeries series = toSeries(candles);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator averageVolume = new SMAIndicator(volume, profile.volumePeriod());
        RSIIndicator rsi = new RSIIndicator(close, profile.rsiPeriod());
        EMAIndicator fastEma = new EMAIndicator(close, profile.fastEmaPeriod());
        EMAIndicator slowEma = new EMAIndicator(close, profile.slowEmaPeriod());
        SMAIndicator longSma = new SMAIndicator(close, profile.longSmaPeriod());
        MACDIndicator macd = new MACDIndicator(
                close,
                profile.macdFastPeriod(),
                profile.macdSlowPeriod()
        );
        Indicator<Num> macdSignal = macd.getSignalLine(profile.macdSignalPeriod());
        Indicator<Num> macdHistogram = macd.getHistogram(profile.macdSignalPeriod());
        CCIIndicator cci = new CCIIndicator(series, profile.cciPeriod());
        ATRIndicator atr = new ATRIndicator(series, profile.atrPeriod());
        BollingerBandsMiddleIndicator bollingerMiddle = new BollingerBandsMiddleIndicator(
                new SMAIndicator(close, profile.bollingerPeriod())
        );
        StandardDeviationIndicator standardDeviation = StandardDeviationIndicator.ofPopulation(
                close,
                profile.bollingerPeriod()
        );
        BollingerBandsLowerIndicator lowerBollinger = new BollingerBandsLowerIndicator(
                bollingerMiddle,
                standardDeviation,
                series.numFactory().numOf(profile.bollingerDeviation())
        );
        BollingerBandsUpperIndicator upperBollinger = new BollingerBandsUpperIndicator(
                bollingerMiddle,
                standardDeviation,
                series.numFactory().numOf(profile.bollingerDeviation())
        );
        VWAPIndicator rollingVwap = new VWAPIndicator(
                new TypicalPriceIndicator(series),
                volume,
                profile.vwapPeriod()
        );

        int firstIndex = Math.max(0, candles.size() - latestCount);
        List<EnrichedCandle> enrichedCandles = new ArrayList<>();
        for (int index = firstIndex; index < candles.size(); index++) {
            Candle candle = candles.get(index);
            VolumeProfileSnapshot volumeProfile = volumeProfile(
                    candles,
                    index,
                    profile.volumeProfilePeriod()
            );
            enrichedCandles.add(new EnrichedCandle(
                    candle.getTimestamp(),
                    candle.getOpenPrice(),
                    candle.getHighPrice(),
                    candle.getLowPrice(),
                    candle.getClosePrice(),
                    candle.getVolume() == null ? 0.0 : candle.getVolume(),
                    indicatorValue(averageVolume, index, profile.volumePeriod()),
                    indicatorValue(rsi, index, profile.rsiPeriod() + 1),
                    indicatorValue(fastEma, index, profile.fastEmaPeriod()),
                    indicatorValue(slowEma, index, profile.slowEmaPeriod()),
                    indicatorValue(longSma, index, profile.longSmaPeriod()),
                    indicatorValue(macd, index, profile.macdSlowPeriod()),
                    indicatorValue(
                            macdSignal,
                            index,
                            profile.macdSlowPeriod() + profile.macdSignalPeriod() - 1
                    ),
                    indicatorValue(
                            macdHistogram,
                            index,
                            profile.macdSlowPeriod() + profile.macdSignalPeriod() - 1
                    ),
                    indicatorValue(cci, index, profile.cciPeriod()),
                    indicatorValue(bollingerMiddle, index, profile.bollingerPeriod()),
                    indicatorValue(lowerBollinger, index, profile.bollingerPeriod()),
                    indicatorValue(upperBollinger, index, profile.bollingerPeriod()),
                    indicatorValue(atr, index, profile.atrPeriod() + 1),
                    indicatorValue(rollingVwap, index, profile.vwapPeriod()),
                    volumeProfile.pointOfControl(),
                    volumeProfile.valueAreaLow(),
                    volumeProfile.valueAreaHigh()
            ));
        }
        return List.copyOf(enrichedCandles);
    }

    /**
     * Builds a deterministic volume-at-price approximation from the data that
     * this service actually owns. Each candle's reported volume is spread
     * uniformly across the price bins intersected by its high-low range. This
     * must not be described as an exchange-grade volume profile because OHLCV
     * candles do not disclose where inside the range the trades occurred.
     */
    private VolumeProfileSnapshot volumeProfile(List<Candle> candles,
                                                int endIndex,
                                                int period) {
        if (period <= 0 || endIndex + 1 < period) {
            return VolumeProfileSnapshot.unavailable();
        }

        int startIndex = endIndex - period + 1;
        double minimumPrice = Double.POSITIVE_INFINITY;
        double maximumPrice = Double.NEGATIVE_INFINITY;
        double totalVolume = 0.0;
        for (int index = startIndex; index <= endIndex; index++) {
            Candle candle = candles.get(index);
            minimumPrice = Math.min(minimumPrice, candle.getLowPrice());
            maximumPrice = Math.max(maximumPrice, candle.getHighPrice());
            totalVolume += Math.max(0.0, candle.getVolume() == null ? 0.0 : candle.getVolume());
        }
        if (!Double.isFinite(minimumPrice)
                || !Double.isFinite(maximumPrice)
                || maximumPrice < minimumPrice
                || totalVolume <= 0.0) {
            return VolumeProfileSnapshot.unavailable();
        }
        if (maximumPrice == minimumPrice) {
            return new VolumeProfileSnapshot(minimumPrice, minimumPrice, maximumPrice);
        }

        double binWidth = (maximumPrice - minimumPrice) / VOLUME_PROFILE_BIN_COUNT;
        double[] volumeByPrice = new double[VOLUME_PROFILE_BIN_COUNT];
        for (int index = startIndex; index <= endIndex; index++) {
            Candle candle = candles.get(index);
            double candleVolume = Math.max(
                    0.0,
                    candle.getVolume() == null ? 0.0 : candle.getVolume()
            );
            if (candleVolume == 0.0) {
                continue;
            }
            int lowBin = volumeProfileBin(candle.getLowPrice(), minimumPrice, binWidth);
            int highBin = volumeProfileBin(candle.getHighPrice(), minimumPrice, binWidth);
            int coveredBins = Math.max(1, highBin - lowBin + 1);
            double allocatedVolume = candleVolume / coveredBins;
            for (int bin = lowBin; bin <= highBin; bin++) {
                volumeByPrice[bin] += allocatedVolume;
            }
        }

        int pointOfControlBin = 0;
        for (int bin = 1; bin < volumeByPrice.length; bin++) {
            if (volumeByPrice[bin] > volumeByPrice[pointOfControlBin]) {
                pointOfControlBin = bin;
            }
        }

        int valueAreaLowBin = pointOfControlBin;
        int valueAreaHighBin = pointOfControlBin;
        double includedVolume = volumeByPrice[pointOfControlBin];
        double targetVolume = totalVolume * VOLUME_PROFILE_VALUE_AREA_FRACTION;
        while (includedVolume < targetVolume
                && (valueAreaLowBin > 0 || valueAreaHighBin < volumeByPrice.length - 1)) {
            double nextLowerVolume = valueAreaLowBin > 0
                    ? volumeByPrice[valueAreaLowBin - 1]
                    : Double.NEGATIVE_INFINITY;
            double nextUpperVolume = valueAreaHighBin < volumeByPrice.length - 1
                    ? volumeByPrice[valueAreaHighBin + 1]
                    : Double.NEGATIVE_INFINITY;
            if (nextUpperVolume > nextLowerVolume) {
                valueAreaHighBin++;
                includedVolume += volumeByPrice[valueAreaHighBin];
            } else {
                valueAreaLowBin--;
                includedVolume += volumeByPrice[valueAreaLowBin];
            }
        }

        return new VolumeProfileSnapshot(
                minimumPrice + (pointOfControlBin + 0.5) * binWidth,
                minimumPrice + valueAreaLowBin * binWidth,
                minimumPrice + (valueAreaHighBin + 1.0) * binWidth
        );
    }

    private int volumeProfileBin(double price, double minimumPrice, double binWidth) {
        int bin = (int) Math.floor((price - minimumPrice) / binWidth);
        return Math.max(0, Math.min(VOLUME_PROFILE_BIN_COUNT - 1, bin));
    }

    private TimeInterval inferInterval(List<Candle> candles) {
        List<TimeInterval> declared = candles.stream()
                .map(Candle::getTimeInterval)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::parseInterval)
                .distinct()
                .toList();
        if (declared.size() > 1) {
            throw new IllegalArgumentException("Cannot enrich candles from mixed intervals.");
        }
        if (declared.size() == 1) {
            return declared.getFirst();
        }
        return inferIntervalFromTimestamps(candles);
    }

    private TimeInterval parseInterval(String rawInterval) {
        String normalized = rawInterval.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1d", "daily" -> TimeInterval.DAILY;
            case "1wk", "1w", "weekly" -> TimeInterval.WEEKLY;
            case "1mo", "monthly" -> TimeInterval.MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Unsupported candlestick scoring interval: " + rawInterval);
        };
    }

    private TimeInterval inferIntervalFromTimestamps(List<Candle> candles) {
        List<Candle> ordered = candles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        if (ordered.size() < 2) {
            return TimeInterval.DAILY;
        }
        List<Long> gaps = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            long gap = ordered.get(index).getTimestamp() - ordered.get(index - 1).getTimestamp();
            if (gap > 0) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return TimeInterval.DAILY;
        }
        List<Long> sortedGaps = gaps.stream().sorted().toList();
        long medianGap = sortedGaps.get(sortedGaps.size() / 2);
        if (medianGap <= Duration.ofDays(4).toSeconds()) {
            return TimeInterval.DAILY;
        }
        if (medianGap <= Duration.ofDays(14).toSeconds()) {
            return TimeInterval.WEEKLY;
        }
        return TimeInterval.MONTHLY;
    }

    private void validateInterval(List<Candle> candles, TimeInterval requestedInterval) {
        for (Candle candle : candles) {
            if (candle.getTimeInterval() == null || candle.getTimeInterval().isBlank()) {
                continue;
            }
            TimeInterval candleInterval = parseInterval(candle.getTimeInterval());
            if (candleInterval != requestedInterval) {
                throw new IllegalArgumentException(
                        "Candle interval " + candle.getTimeInterval()
                                + " does not match requested " + requestedInterval + " profile.");
            }
        }
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

    private record VolumeProfileSnapshot(double pointOfControl,
                                         double valueAreaLow,
                                         double valueAreaHigh) {
        private static VolumeProfileSnapshot unavailable() {
            return new VolumeProfileSnapshot(Double.NaN, Double.NaN, Double.NaN);
        }
    }
}
