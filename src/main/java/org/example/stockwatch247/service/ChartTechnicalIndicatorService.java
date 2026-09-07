package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.technical.Ta4jBarSeriesFactory;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.StochasticRSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.donchian.DonchianChannelFacade;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.keltner.KeltnerChannelFacade;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.supportresistance.VolumeProfileKDEIndicator;
import org.ta4j.core.indicators.trend.DownTrendIndicator;
import org.ta4j.core.indicators.trend.UpTrendIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Produces configurable chart series using the same TA4J indicator classes as
 * the automated technical outlook. The service deliberately returns only
 * finite points so JSON serialization cannot be broken by a warm-up NaN.
 */
@Service
public class ChartTechnicalIndicatorService {
    private CandleRevisionService revisions;
    private final BoundedTtlCache<java.util.List<Object>, IndicatorBatchView> resultCache = new BoundedTtlCache<>(64, 120);
    @org.springframework.beans.factory.annotation.Autowired
    void setRevisions(CandleRevisionService revisions) { this.revisions = revisions; }

    private static final int MAX_CONFIGURATIONS = 32;
    private static final int MAX_PERIOD = 500;
    private static final int PROFILE_BINS = 24;

    private final CandleRepository candleRepository;
    private final Ta4jBarSeriesFactory seriesFactory = new Ta4jBarSeriesFactory();

    public ChartTechnicalIndicatorService(CandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public IndicatorBatchView calculate(String symbol, String interval, IndicatorBatchRequest request) {
        if (request == null || request.indicators() == null || request.indicators().isEmpty()) {
            return new IndicatorBatchView(interval, List.of());
        }
        if (request.indicators().size() > MAX_CONFIGURATIONS) {
            throw new IllegalArgumentException("At most 32 chart indicators can be active at once.");
        }
        symbol = symbol.toUpperCase(Locale.ROOT);
        if (request.indicators().stream().anyMatch(config -> config == null || config.id() == null || config.id().isBlank()
                || config.id().length() > 100 || config.parameters() != null && (config.parameters().size() > 8
                || config.parameters().values().stream().anyMatch(value -> value == null || !Double.isFinite(value)))))
            throw new IllegalArgumentException("Chart indicator configuration is invalid.");
        // Copy nested request collections before using them as a cache key.
        request = new IndicatorBatchRequest(request.from(), request.to(), request.indicators().stream()
                .map(config -> new IndicatorConfiguration(config.id(), config.type(), config.parameters() == null ? Map.of() : Map.copyOf(config.parameters()))).toList());
        var key = java.util.List.<Object>of(symbol, interval, request, revisions == null ? 0 : revisions.generation(symbol, interval));
        var cached = resultCache.get(key, java.time.Instant.now().getEpochSecond());
        if (cached != null) return cached;
        List<Candle> candles = request.to() == null ? candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval)
                : candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc(symbol, interval, request.to());
        candles = candles.stream().filter(this::complete).sorted(Comparator.comparingLong(Candle::getTimestamp)).toList();
        if (candles.isEmpty()) return new IndicatorBatchView(interval, List.of());
        BarSeries series = seriesFactory.create(candles);
        List<IndicatorConfigurationView> results = new ArrayList<>();
        Map<IndicatorConfiguration, IndicatorConfigurationView> calculated = new LinkedHashMap<>();
        for (IndicatorConfiguration config : request.indicators()) {
            var computation = new IndicatorConfiguration("", config.type() == null ? "" : config.type().trim().toUpperCase(Locale.ROOT), config.parameters());
            var values = calculated.get(computation);
            if (values == null) {
                values = calculateOne(candles, series, config, request.from(), request.to());
                calculated.put(computation, values);
            }
            results.add(new IndicatorConfigurationView(config.id(), values.type(), values.label(), values.placement(),
                    values.parameters(), values.series(), values.references()));
        }
        var result = new IndicatorBatchView(interval, List.copyOf(results));
        long pointCount = result.indicators().stream().flatMap(config -> config.series().stream()).mapToLong(outputSeries -> outputSeries.points().size()).sum();
        if (pointCount <= 10_000) resultCache.put(key, result, java.time.Instant.now().getEpochSecond());
        return result;
    }

    private IndicatorConfigurationView calculateOne(List<Candle> candles,
                                                     BarSeries bars,
                                                     IndicatorConfiguration config,
                                                     Long from,
                                                     Long to) {
        if (config == null || config.id() == null || config.id().isBlank()) {
            throw new IllegalArgumentException("Every chart indicator needs an id.");
        }
        if (config.id().length() > 100) throw new IllegalArgumentException("Chart indicator id is too long.");
        String type = config.type() == null ? "" : config.type().trim().toUpperCase(Locale.ROOT);
        Map<String, Double> parameters = config.parameters() == null ? Map.of() : config.parameters();
        if (parameters.size() > 8 || parameters.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Chart indicator parameters are invalid.");
        }
        ClosePriceIndicator close = new ClosePriceIndicator(bars);
        TypicalPriceIndicator typical = new TypicalPriceIndicator(bars);
        VolumeIndicator volume = new VolumeIndicator(bars);
        List<SeriesView> output = new ArrayList<>();
        List<Double> references = new ArrayList<>();
        String placement;
        String label;

        switch (type) {
            case "EMA" -> {
                int period = period(parameters, "period", 20);
                label = "EMA " + period;
                placement = "PRICE";
                add(output, "value", label, "LINE", new EMAIndicator(close, period), candles, from, to);
            }
            case "SMA" -> {
                int period = period(parameters, "period", 200);
                label = "SMA " + period;
                placement = "PRICE";
                add(output, "value", label, "LINE", new SMAIndicator(close, period), candles, from, to);
            }
            case "BOLLINGER" -> {
                int period = period(parameters, "period", 20);
                double deviation = positive(parameters, "deviation", 2.0, 0.1, 10.0);
                label = "Bollinger " + period + " / " + compact(deviation) + "σ";
                placement = "PRICE";
                BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(new SMAIndicator(close, period));
                StandardDeviationIndicator std = StandardDeviationIndicator.ofPopulation(close, period);
                Num multiplier = bars.numFactory().numOf(deviation);
                add(output, "lower", "Lower", "LINE", new BollingerBandsLowerIndicator(middle, std, multiplier), candles, from, to);
                add(output, "middle", "Middle", "LINE", middle, candles, from, to);
                add(output, "upper", "Upper", "LINE", new BollingerBandsUpperIndicator(middle, std, multiplier), candles, from, to);
            }
            case "VWAP" -> {
                int period = period(parameters, "period", 20);
                label = "Rolling VWAP " + period;
                placement = "PRICE";
                add(output, "value", label, "LINE", new VWAPIndicator(typical, volume, period), candles, from, to);
            }
            case "SUPPORT_RESISTANCE" -> {
                int period = period(parameters, "period", 20);
                label = "Support / resistance " + period;
                placement = "PRICE";
                output.addAll(supportResistance(candles, period, from, to));
            }
            case "RSI" -> {
                int period = period(parameters, "period", 14);
                double lower = bounded(parameters, "lower", 30, 0, 100);
                double upper = bounded(parameters, "upper", 70, 0, 100);
                if (lower >= upper) throw new IllegalArgumentException("RSI lower boundary must be below its upper boundary.");
                label = "RSI " + period;
                placement = "PANEL";
                add(output, "value", label, "LINE", new RSIIndicator(close, period), candles, from, to);
                references.addAll(List.of(lower, 50.0, upper));
            }
            case "MACD" -> {
                int fast = period(parameters, "fast", 12);
                int slow = period(parameters, "slow", 26);
                int signalPeriod = period(parameters, "signal", 9);
                if (fast >= slow) throw new IllegalArgumentException("MACD fast period must be below its slow period.");
                label = "MACD " + fast + "/" + slow + "/" + signalPeriod;
                placement = "PANEL";
                MACDIndicator macd = new MACDIndicator(close, fast, slow);
                add(output, "macd", "MACD", "LINE", macd, candles, from, to);
                add(output, "signal", "Signal", "LINE", macd.getSignalLine(signalPeriod), candles, from, to);
                add(output, "histogram", "Histogram", "HISTOGRAM", macd.getHistogram(signalPeriod), candles, from, to);
                references.add(0.0);
            }
            case "CCI" -> {
                int period = period(parameters, "period", 20);
                label = "CCI " + period;
                placement = "PANEL";
                add(output, "value", label, "LINE", new CCIIndicator(bars, period), candles, from, to);
                references.addAll(List.of(-100.0, 0.0, 100.0));
            }
            case "ATR" -> {
                int period = period(parameters, "period", 14);
                label = "ATR " + period;
                placement = "PANEL";
                add(output, "value", label, "LINE", new ATRIndicator(bars, period), candles, from, to);
            }
            case "RELATIVE_VOLUME" -> {
                int period = period(parameters, "period", 20);
                label = "Relative volume " + period;
                placement = "PANEL";
                SMAIndicator average = new SMAIndicator(volume, period);
                output.add(series("value", label, "HISTOGRAM", candles, from, to, index -> {
                    double denominator = value(average, index);
                    if (!Double.isFinite(denominator) || denominator == 0) return Double.NaN;
                    double direction = candles.get(index).getClosePrice() >= candles.get(index).getOpenPrice() ? 1 : -1;
                    return value(volume, index) / denominator * direction;
                }));
                references.addAll(List.of(-1.0, 0.0, 1.0));
            }
            case "ADX_DMI" -> {
                int period = period(parameters, "period", 14);
                label = "ADX / DMI " + period;
                placement = "PANEL";
                add(output, "adx", "ADX", "LINE", new ADXIndicator(bars, period), candles, from, to);
                add(output, "plusDi", "+DI", "LINE", new PlusDIIndicator(bars, period), candles, from, to);
                add(output, "minusDi", "-DI", "LINE", new MinusDIIndicator(bars, period), candles, from, to);
                references.addAll(List.of(20.0, 25.0));
            }
            case "STOCHASTIC" -> {
                int period = period(parameters, "period", 14);
                label = "Stochastic " + period;
                placement = "PANEL";
                StochasticOscillatorKIndicator k = new StochasticOscillatorKIndicator(bars, period);
                add(output, "k", "%K", "LINE", k, candles, from, to);
                add(output, "d", "%D", "LINE", new StochasticOscillatorDIndicator(k), candles, from, to);
                references.addAll(List.of(20.0, 50.0, 80.0));
            }
            case "STOCHASTIC_RSI" -> {
                int period = period(parameters, "period", 14);
                label = "Stochastic RSI " + period;
                placement = "PANEL";
                add(output, "value", label, "LINE", new StochasticRSIIndicator(bars, period), candles, from, to);
                references.addAll(List.of(20.0, 50.0, 80.0));
            }
            case "OBV" -> {
                label = "On-balance volume";
                placement = "PANEL";
                add(output, "value", label, "LINE", new OnBalanceVolumeIndicator(bars), candles, from, to);
            }
            case "MFI" -> {
                int period = period(parameters, "period", 14);
                label = "Money Flow Index " + period;
                placement = "PANEL";
                add(output, "value", label, "LINE", new MoneyFlowIndexIndicator(bars, period), candles, from, to);
                references.addAll(List.of(20.0, 50.0, 80.0));
            }
            case "DONCHIAN" -> {
                int period = period(parameters, "period", 20);
                label = "Donchian Channel " + period;
                placement = "PRICE";
                DonchianChannelFacade channel = new DonchianChannelFacade(bars, period);
                add(output, "lower", "Lower", "LINE", channel.lower(), candles, from, to);
                add(output, "middle", "Middle", "LINE", channel.middle(), candles, from, to);
                add(output, "upper", "Upper", "LINE", channel.upper(), candles, from, to);
            }
            case "KELTNER" -> {
                int period = period(parameters, "period", 20);
                int atrPeriod = period(parameters, "atrPeriod", 14);
                double multiplier = positive(parameters, "multiplier", 2.0, 0.1, 10.0);
                label = "Keltner Channel " + period + " / ATR " + atrPeriod;
                placement = "PRICE";
                KeltnerChannelFacade channel = new KeltnerChannelFacade(bars, period, atrPeriod, multiplier);
                add(output, "lower", "Lower", "LINE", channel.lower(), candles, from, to);
                add(output, "middle", "Middle", "LINE", channel.middle(), candles, from, to);
                add(output, "upper", "Upper", "LINE", channel.upper(), candles, from, to);
            }
            case "TREND" -> {
                int period = period(parameters, "period", 20);
                label = "TA4J trend state " + period;
                placement = "PANEL";
                UpTrendIndicator up = new UpTrendIndicator(bars, period);
                DownTrendIndicator down = new DownTrendIndicator(bars, period);
                output.add(series("value", label, "LINE", candles, from, to, index -> {
                    boolean rising = Boolean.TRUE.equals(up.getValue(index));
                    boolean falling = Boolean.TRUE.equals(down.getValue(index));
                    return rising == falling ? 0.0 : rising ? 1.0 : -1.0;
                }));
                references.addAll(List.of(-1.0, 0.0, 1.0));
            }
            case "VOLUME_PROFILE" -> {
                int period = period(parameters, "period", 60);
                double valueArea = positive(parameters, "valueArea", 0.70, 0.50, 0.95);
                label = "Rolling volume profile " + period;
                placement = "PRICE";
                output.addAll(volumeProfile(candles, period, valueArea, from, to));
            }
            case "KDE_VOLUME_PROFILE" -> {
                int period = period(parameters, "period", 60);
                label = "KDE volume-profile mode " + period;
                placement = "PRICE";
                double firstClose = Math.abs(candles.getFirst().getClosePrice());
                double firstRange = Math.abs(candles.getFirst().getHighPrice() - candles.getFirst().getLowPrice());
                double bandwidth = Math.max(0.000_001, Math.max(firstClose * 0.0025, firstRange * 0.5));
                VolumeProfileKDEIndicator kde = new VolumeProfileKDEIndicator(
                        typical, volume, period, bars.numFactory().numOf(bandwidth));
                output.add(series("mode", label, "LINE", candles, from, to, index -> {
                    if (index < kde.getCountOfUnstableBars()) return Double.NaN;
                    Num mode = kde.getModePrice(index);
                    return mode == null || mode.isNaN() ? Double.NaN : mode.doubleValue();
                }));
            }
            default -> throw new IllegalArgumentException("Unsupported chart indicator: " + type);
        }
        return new IndicatorConfigurationView(config.id(), type, label, placement,
                Map.copyOf(parameters), List.copyOf(output), List.copyOf(references));
    }

    private void add(List<SeriesView> output, String key, String label, String style,
                     Indicator<Num> indicator, List<Candle> candles, Long from, Long to) {
        output.add(series(key, label, style, candles, from, to, index -> value(indicator, index)));
    }

    private SeriesView series(String key, String label, String style, List<Candle> candles,
                              Long from, Long to, IndexedValue values) {
        List<PointView> points = new ArrayList<>();
        for (int index = 0; index < candles.size(); index++) {
            long timestamp = candles.get(index).getTimestamp();
            if (from != null && timestamp < from) continue;
            if (to != null && timestamp > to) continue;
            double value = values.at(index);
            if (Double.isFinite(value)) points.add(new PointView(timestamp, value));
        }
        return new SeriesView(key, label, style, List.copyOf(points));
    }

    private List<SeriesView> supportResistance(List<Candle> candles, int period, Long from, Long to) {
        ArrayDeque<Integer> lows = new ArrayDeque<>();
        ArrayDeque<Integer> highs = new ArrayDeque<>();
        double[] support = new double[candles.size()];
        double[] resistance = new double[candles.size()];
        java.util.Arrays.fill(support, Double.NaN);
        java.util.Arrays.fill(resistance, Double.NaN);
        for (int index = 0; index < candles.size(); index++) {
            while (!lows.isEmpty() && candles.get(lows.getLast()).getLowPrice() >= candles.get(index).getLowPrice()) lows.removeLast();
            while (!highs.isEmpty() && candles.get(highs.getLast()).getHighPrice() <= candles.get(index).getHighPrice()) highs.removeLast();
            lows.addLast(index);
            highs.addLast(index);
            int expired = index - period;
            while (!lows.isEmpty() && lows.getFirst() <= expired) lows.removeFirst();
            while (!highs.isEmpty() && highs.getFirst() <= expired) highs.removeFirst();
            if (index + 1 >= period) {
                support[index] = candles.get(lows.getFirst()).getLowPrice();
                resistance[index] = candles.get(highs.getFirst()).getHighPrice();
            }
        }
        return List.of(
                series("support", "Support", "LINE", candles, from, to, index -> support[index]),
                series("resistance", "Resistance", "LINE", candles, from, to, index -> resistance[index]));
    }

    private List<SeriesView> volumeProfile(List<Candle> candles, int period, double valueArea, Long from, Long to) {
        double[] poc = new double[candles.size()];
        double[] val = new double[candles.size()];
        double[] vah = new double[candles.size()];
        java.util.Arrays.fill(poc, Double.NaN);
        java.util.Arrays.fill(val, Double.NaN);
        java.util.Arrays.fill(vah, Double.NaN);
        for (int end = period - 1; end < candles.size(); end++) {
            int start = end - period + 1;
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            double total = 0;
            for (int index = start; index <= end; index++) {
                minimum = Math.min(minimum, candles.get(index).getLowPrice());
                maximum = Math.max(maximum, candles.get(index).getHighPrice());
                total += Math.max(0, candles.get(index).getVolume() == null ? 0 : candles.get(index).getVolume());
            }
            if (!(maximum > minimum) || total <= 0) continue;
            double width = (maximum - minimum) / PROFILE_BINS;
            double[] bins = new double[PROFILE_BINS];
            for (int index = start; index <= end; index++) {
                Candle candle = candles.get(index);
                int lowBin = Math.min(PROFILE_BINS - 1, Math.max(0, (int) ((candle.getLowPrice() - minimum) / width)));
                int highBin = Math.min(PROFILE_BINS - 1, Math.max(0, (int) ((candle.getHighPrice() - minimum) / width)));
                double amount = Math.max(0, candle.getVolume() == null ? 0 : candle.getVolume()) / Math.max(1, highBin - lowBin + 1);
                for (int bin = lowBin; bin <= highBin; bin++) bins[bin] += amount;
            }
            int mode = 0;
            for (int bin = 1; bin < bins.length; bin++) if (bins[bin] > bins[mode]) mode = bin;
            int lower = mode;
            int upper = mode;
            double included = bins[mode];
            while (included < total * valueArea && (lower > 0 || upper < PROFILE_BINS - 1)) {
                double below = lower > 0 ? bins[lower - 1] : -1;
                double above = upper < PROFILE_BINS - 1 ? bins[upper + 1] : -1;
                if (above > below) included += bins[++upper]; else included += bins[--lower];
            }
            poc[end] = minimum + (mode + 0.5) * width;
            val[end] = minimum + lower * width;
            vah[end] = minimum + (upper + 1.0) * width;
        }
        return List.of(
                series("val", "Value area low", "LINE", candles, from, to, index -> val[index]),
                series("poc", "Point of control", "LINE", candles, from, to, index -> poc[index]),
                series("vah", "Value area high", "LINE", candles, from, to, index -> vah[index]));
    }

    private double value(Indicator<Num> indicator, int index) {
        if (index < indicator.getCountOfUnstableBars()) return Double.NaN;
        Num value = indicator.getValue(index);
        return value == null || value.isNaN() ? Double.NaN : value.doubleValue();
    }

    private int period(Map<String, Double> parameters, String key, int fallback) {
        double raw = parameters.getOrDefault(key, (double) fallback);
        if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < 2 || raw > MAX_PERIOD) {
            throw new IllegalArgumentException(key + " must be a whole number from 2 to 500.");
        }
        return (int) raw;
    }

    private double positive(Map<String, Double> parameters, String key, double fallback, double minimum, double maximum) {
        double raw = parameters.getOrDefault(key, fallback);
        if (!Double.isFinite(raw) || raw < minimum || raw > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum + ".");
        }
        return raw;
    }

    private double bounded(Map<String, Double> parameters, String key, double fallback, double minimum, double maximum) {
        return positive(parameters, key, fallback, minimum, maximum);
    }

    private boolean complete(Candle candle) {
        return candle != null && candle.getTimestamp() != null && candle.getOpenPrice() != null
                && candle.getHighPrice() != null && candle.getLowPrice() != null && candle.getClosePrice() != null;
    }

    private String compact(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
    }

    @FunctionalInterface
    private interface IndexedValue { double at(int index); }

    public record IndicatorBatchRequest(Long from, Long to, List<IndicatorConfiguration> indicators) { }
    public record IndicatorConfiguration(String id, String type, Map<String, Double> parameters) { }
    public record IndicatorBatchView(String interval, List<IndicatorConfigurationView> indicators) { }
    public record IndicatorConfigurationView(String id, String type, String label, String placement,
                                             Map<String, Double> parameters, List<SeriesView> series,
                                             List<Double> references) { }
    public record SeriesView(String key, String label, String style, List<PointView> points) { }
    public record PointView(long timestamp, double value) { }
}
