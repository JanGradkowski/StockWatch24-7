package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.technical.Ta4jBarSeriesFactory;
import org.example.stockwatch247.service.technical.Ta4jIndicatorRegistry;
import org.example.stockwatch247.service.technical.TechnicalIndicatorParameters;
import org.example.stockwatch247.service.technical.TechnicalResearchSnapshot;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class TechnicalIndicatorEnrichmentService {
    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int VOLUME_PROFILE_BIN_COUNT = 24;
    private final Ta4jBarSeriesFactory seriesFactory;
    private final Ta4jIndicatorRegistry indicatorRegistry;

    public TechnicalIndicatorEnrichmentService() {
        this(new Ta4jBarSeriesFactory(), new Ta4jIndicatorRegistry());
    }

    TechnicalIndicatorEnrichmentService(Ta4jBarSeriesFactory seriesFactory,
                                        Ta4jIndicatorRegistry indicatorRegistry) {
        this.seriesFactory = Objects.requireNonNull(seriesFactory);
        this.indicatorRegistry = Objects.requireNonNull(indicatorRegistry);
    }

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

    public int requiredInputCandles(int latestCount, TechnicalIndicatorProfile profile) {
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

    public List<EnrichedCandle> enrich(List<Candle> rawCandles,
                                       int latestCount,
                                       TechnicalIndicatorProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("A technical indicator profile is required.");
        }
        return enrich(rawCandles, latestCount, profile.interval(), profile);
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

        BarSeries series = seriesFactory.create(candles);
        TechnicalIndicatorParameters parameters = parameters(profile);
        Ta4jIndicatorRegistry.CoreIndicators indicators = indicatorRegistry.core(series, parameters);

        int firstIndex = Math.max(0, candles.size() - latestCount);
        List<EnrichedCandle> enrichedCandles = new ArrayList<>();
        for (int index = firstIndex; index < candles.size(); index++) {
            Candle candle = candles.get(index);
            VolumeProfileSnapshot volumeProfile = volumeProfile(
                    candles,
                    index,
                    profile.volumeProfilePeriod(),
                    profile.volumeProfileValueAreaFraction()
            );
            enrichedCandles.add(new EnrichedCandle(
                    candle.getTimestamp(),
                    candle.getOpenPrice(),
                    candle.getHighPrice(),
                    candle.getLowPrice(),
                    candle.getClosePrice(),
                    candle.getVolume() == null ? 0.0 : candle.getVolume(),
                    indicatorValue(indicators.averageVolume(), index,
                            indicators.stableBars(indicators.averageVolume(), profile.volumePeriod())),
                    indicatorValue(indicators.rsi(), index,
                            indicators.stableBars(indicators.rsi(), profile.rsiPeriod() + 1)),
                    indicatorValue(indicators.fastEma(), index,
                            indicators.stableBars(indicators.fastEma(), profile.fastEmaPeriod())),
                    indicatorValue(indicators.slowEma(), index,
                            indicators.stableBars(indicators.slowEma(), profile.slowEmaPeriod())),
                    indicatorValue(indicators.longSma(), index,
                            indicators.stableBars(indicators.longSma(), profile.longSmaPeriod())),
                    indicatorValue(indicators.macd(), index,
                            indicators.stableBars(indicators.macd(), profile.macdSlowPeriod())),
                    indicatorValue(
                            indicators.macdSignal(),
                            index,
                            indicators.stableBars(indicators.macdSignal(),
                                    profile.macdSlowPeriod() + profile.macdSignalPeriod() - 1)
                    ),
                    indicatorValue(
                            indicators.macdHistogram(),
                            index,
                            indicators.stableBars(indicators.macdHistogram(),
                                    profile.macdSlowPeriod() + profile.macdSignalPeriod() - 1)
                    ),
                    indicatorValue(indicators.cci(), index,
                            indicators.stableBars(indicators.cci(), profile.cciPeriod())),
                    indicatorValue(indicators.bollingerMiddle(), index,
                            indicators.stableBars(indicators.bollingerMiddle(), profile.bollingerPeriod())),
                    indicatorValue(indicators.bollingerLower(), index,
                            indicators.stableBars(indicators.bollingerLower(), profile.bollingerPeriod())),
                    indicatorValue(indicators.bollingerUpper(), index,
                            indicators.stableBars(indicators.bollingerUpper(), profile.bollingerPeriod())),
                    indicatorValue(indicators.atr(), index,
                            indicators.stableBars(indicators.atr(), profile.atrPeriod() + 1)),
                    indicatorValue(indicators.rollingVwap(), index,
                            indicators.stableBars(indicators.rollingVwap(), profile.vwapPeriod())),
                    volumeProfile.pointOfControl(),
                    volumeProfile.valueAreaLow(),
                    volumeProfile.valueAreaHigh()
            ));
        }
        return List.copyOf(enrichedCandles);
    }

    /**
     * Calculates additional TA4J indicators for technical-analysis display and
     * research. These snapshots are intentionally separate from EnrichedCandle
     * so Elliott, candlestick, scoring, and alert code cannot consume them by
     * accident.
     */
    public List<TechnicalResearchSnapshot> research(List<Candle> rawCandles,
                                                    int latestCount,
                                                    TechnicalIndicatorProfile profile) {
        return research(rawCandles, latestCount, profile, false);
    }

    List<TechnicalResearchSnapshot> research(List<Candle> rawCandles,
                                             int latestCount,
                                             TechnicalIndicatorProfile profile,
                                             boolean includeHistoricalKde) {
        if (rawCandles == null || rawCandles.isEmpty() || latestCount <= 0) return List.of();
        if (profile == null) throw new IllegalArgumentException("A technical indicator profile is required.");
        List<Candle> candles = rawCandles.stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        if (candles.isEmpty()) return List.of();
        validateInterval(candles, profile.interval());
        BarSeries series = seriesFactory.create(candles);
        Ta4jIndicatorRegistry.ResearchIndicators indicators = indicatorRegistry.research(
                series, parameters(profile));
        int firstIndex = Math.max(0, candles.size() - latestCount);
        List<TechnicalResearchSnapshot> snapshots = new ArrayList<>();
        for (int index = firstIndex; index < candles.size(); index++) {
            snapshots.add(new TechnicalResearchSnapshot(
                    candles.get(index).getTimestamp(),
                    researchValue(indicators.adx(), index),
                    researchValue(indicators.plusDi(), index),
                    researchValue(indicators.minusDi(), index),
                    researchValue(indicators.stochasticK(), index),
                    researchValue(indicators.stochasticD(), index),
                    researchValue(indicators.stochasticRsi(), index),
                    researchValue(indicators.obv(), index),
                    researchValue(indicators.moneyFlow(), index),
                    researchValue(indicators.donchianLower(), index),
                    researchValue(indicators.donchianMiddle(), index),
                    researchValue(indicators.donchianUpper(), index),
                    researchValue(indicators.keltnerLower(), index),
                    researchValue(indicators.keltnerMiddle(), index),
                    researchValue(indicators.keltnerUpper(), index),
                    researchBoolean(indicators.upTrend(), index),
                    researchBoolean(indicators.downTrend(), index),
                    includeHistoricalKde || index == candles.size() - 1
                            ? researchKdeMode(indicators.volumeProfileKde(), index)
                            : Double.NaN
            ));
        }
        return List.copyOf(snapshots);
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
                                                int period,
                                                double valueAreaFraction) {
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
        double targetVolume = totalVolume * valueAreaFraction;
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

    private double indicatorValue(Indicator<Num> indicator, int index, int minimumBars) {
        if (index + 1 < minimumBars) {
            return Double.NaN;
        }

        Num value = indicator.getValue(index);
        return value == null || value.isNaN() ? Double.NaN : value.doubleValue();
    }

    private double researchValue(Indicator<Num> indicator, int index) {
        if (index < indicator.getCountOfUnstableBars()) return Double.NaN;
        Num value = indicator.getValue(index);
        return value == null || value.isNaN() ? Double.NaN : value.doubleValue();
    }

    private boolean researchBoolean(Indicator<Boolean> indicator, int index) {
        return index >= indicator.getCountOfUnstableBars() && Boolean.TRUE.equals(indicator.getValue(index));
    }

    private double researchKdeMode(org.ta4j.core.indicators.supportresistance.VolumeProfileKDEIndicator indicator,
                                   int index) {
        if (index < indicator.getCountOfUnstableBars()) return Double.NaN;
        Num value = indicator.getModePrice(index);
        return value == null || value.isNaN() ? Double.NaN : value.doubleValue();
    }

    private TechnicalIndicatorParameters parameters(TechnicalIndicatorProfile profile) {
        return new TechnicalIndicatorParameters(
                profile.rsiPeriod(), profile.atrPeriod(), profile.fastEmaPeriod(),
                profile.slowEmaPeriod(), profile.longSmaPeriod(), profile.macdFastPeriod(),
                profile.macdSlowPeriod(), profile.macdSignalPeriod(), profile.cciPeriod(),
                profile.bollingerPeriod(), profile.bollingerDeviation(), profile.volumePeriod(),
                profile.vwapPeriod(), profile.volumeProfilePeriod());
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
