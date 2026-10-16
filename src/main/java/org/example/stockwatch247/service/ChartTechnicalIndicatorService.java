package org.example.stockwatch247.service;

import org.example.stockwatch247.i18n.ServerMessages;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorEngine;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorEngine.Computed;
import org.example.stockwatch247.service.chartindicator.IndicatorContext;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputKind;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputValues;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputStyle;
import org.example.stockwatch247.service.chartindicator.MarketCalendar;
import org.example.stockwatch247.service.chartindicator.PriceSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes the configurable indicators a chart asks for. Definitions come from the indicator
 * catalog; this service loads the bars, orders indicators that read each other, maps
 * higher-timeframe results onto chart bars and turns the results into chart series, markers and
 * shapes. Only finite values are returned, so a warm-up NaN can never break the JSON.
 */
@Service
public class ChartTechnicalIndicatorService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChartTechnicalIndicatorService.class);
    private static final int MAX_CONFIGURATIONS = 32;
    private static final int MAX_PARAMETERS = 16;
    private static final int MAX_TEXT = 240;
    private static final int MAX_FUTURE_BARS = 600;

    private final CandleRepository candleRepository;
    private final ChartIndicatorEngine engine = new ChartIndicatorEngine();
    private final BoundedTtlCache<List<Object>, IndicatorBatchView> resultCache = new BoundedTtlCache<>(64, 120);
    private final BoundedTtlCache<List<Object>, List<Candle>> seriesCache = new BoundedTtlCache<>(8, 300);
    private ChartIndicatorCatalog catalog = new ChartIndicatorCatalog();
    private CandleRevisionService revisions;
    private MarketDataService marketData;

    public ChartTechnicalIndicatorService(CandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    @Autowired
    void setRevisions(CandleRevisionService revisions) { this.revisions = revisions; }

    @Autowired(required = false)
    void setCatalog(ChartIndicatorCatalog catalog) { this.catalog = catalog; }

    @Autowired(required = false)
    void setMarketData(MarketDataService marketData) { this.marketData = marketData; }

    public ChartIndicatorCatalog catalog() { return catalog; }

    public IndicatorBatchView calculate(String symbol, String interval, IndicatorBatchRequest request) {
        if (request == null || request.indicators() == null || request.indicators().isEmpty()) {
            return new IndicatorBatchView(interval, List.of());
        }
        if (request.indicators().size() > MAX_CONFIGURATIONS) {
            throw new IllegalArgumentException("At most 32 chart indicators can be active at once.");
        }
        symbol = symbol.toUpperCase(Locale.ROOT);
        Set<String> ids = new HashSet<>();
        for (IndicatorConfiguration config : request.indicators()) {
            if (config == null || config.id() == null || config.id().isBlank() || config.id().length() > 100 || !ids.add(config.id())
                    || config.parameters() != null && (config.parameters().size() > MAX_PARAMETERS
                    || config.parameters().values().stream().anyMatch(value -> !allowedValue(value)))) {
                throw new IllegalArgumentException("Chart indicator configuration is invalid.");
            }
        }
        // Copy nested request collections before using them as a cache key.
        request = new IndicatorBatchRequest(request.from(), request.to(), request.visibleFrom(), request.visibleTo(),
                request.indicators().stream().map(config -> new IndicatorConfiguration(config.id(),
                        config.type() == null ? "" : config.type().trim().toUpperCase(Locale.ROOT),
                        config.parameters() == null ? Map.of() : Map.copyOf(config.parameters()))).toList());

        List<Resolved> resolved = new ArrayList<>();
        // A setting the user can correct (a formula typo, fast >= slow) marks only that indicator.
        Map<String, String> rejected = new HashMap<>();
        for (IndicatorConfiguration config : request.indicators()) {
            IndicatorDefinition definition = catalog.find(config.type())
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported chart indicator: " + config.type()));
            try {
                InputValues values = catalog.resolve(definition, config.parameters(), ids, config.id(), interval);
                resolved.add(new Resolved(config, definition, values));
            } catch (UserInputException invalid) {
                rejected.put(config.id(), ServerMessages.translate(invalid.getMessage()));
            }
        }
        List<Resolved> ordered = dependencyOrder(resolved);
        boolean visibleRange = resolved.stream().anyMatch(item -> item.definition().visibleRangeDependent());

        List<Object> key = cacheKey(symbol, interval, request, visibleRange, resolved);
        long now = Instant.now().getEpochSecond();
        IndicatorBatchView cached = resultCache.get(key, now);
        if (cached != null) return cached;

        List<Candle> candles = completed(load(symbol, interval, request.to()));
        if (candles.isEmpty()) return new IndicatorBatchView(interval, List.of());
        List<Long> future = MarketCalendar.futureTimestamps(candles.getLast().getTimestamp(), interval, MAX_FUTURE_BARS);

        Map<String, Computed> computed = new HashMap<>();
        Map<String, String> errors = new HashMap<>();
        for (Resolved item : ordered) {
            try {
                computed.put(item.config().id(), computeOne(item, symbol, interval, candles, computed, request));
            } catch (IndicatorUnavailableException unavailable) {
                errors.put(item.config().id(), ServerMessages.translate(unavailable.getMessage()));
            } catch (UserInputException invalid) {
                errors.put(item.config().id(), ServerMessages.translate(invalid.getMessage()));
            } catch (IllegalArgumentException invalid) {
                throw invalid;
            } catch (RuntimeException failure) {
                log.warn("Chart indicator {} failed for {} {}", item.definition().type(), symbol, interval, failure);
                errors.put(item.config().id(), ServerMessages.translate("This indicator could not be calculated for the loaded bars."));
            }
        }
        List<IndicatorConfigurationView> views = new ArrayList<>();
        Map<String, Resolved> byId = new HashMap<>();
        resolved.forEach(item -> byId.put(item.config().id(), item));
        for (IndicatorConfiguration config : request.indicators()) {
            String id = config.id();
            Resolved item = byId.get(id);
            if (item == null) {
                views.add(new IndicatorConfigurationView(id, config.type(), config.type(), null, config.parameters(),
                        List.of(), List.of(), List.of(), List.of(), rejected.get(id)));
            } else {
                views.add(errors.containsKey(id)
                        ? errorView(item, errors.get(id))
                        : view(item, computed.get(id), candles, future, request));
            }
        }
        IndicatorBatchView result = new IndicatorBatchView(interval, List.copyOf(views));
        long pointCount = result.indicators().stream().flatMap(view -> view.series().stream())
                .mapToLong(series -> series.points().size()).sum();
        if (pointCount <= 20_000) resultCache.put(key, result, now);
        return result;
    }

    private Computed computeOne(Resolved item, String symbol, String interval, List<Candle> candles,
                                Map<String, Computed> computed, IndicatorBatchRequest request) {
        IndicatorDefinition definition = item.definition();
        InputValues values = item.values();
        Map<String, double[]> external = new HashMap<>();
        for (InputSpec spec : definition.inputs()) {
            if (spec.kind() == InputKind.SOURCE && PriceSource.isDependency(values.text(spec.key()))) {
                String source = values.text(spec.key());
                Computed dependency = computed.get(PriceSource.dependencyId(source));
                double[] output = dependency == null ? null : dependency.outputs().get(PriceSource.dependencyOutput(source));
                if (output == null) throw new IndicatorUnavailableException("The source indicator is unavailable.");
                external.put(source, output);
            } else if (spec.kind() == InputKind.SYMBOL) {
                String other = values.text(spec.key());
                external.put("symbol:" + other, alignedClose(other, interval, candles, request.to()));
            }
        }
        String timeframe = definition.inputs().stream().anyMatch(spec -> spec.kind() == InputKind.TIMEFRAME)
                ? values.text("timeframe") : "chart";
        if ("chart".equals(timeframe)) {
            return engine.run(definition, values, interval, candles, external, request.from(), request.visibleFrom(), request.visibleTo());
        }
        if (!ChartIndicatorCatalog.dependencies(definition, values).isEmpty()) {
            throw new UserInputException("A higher timeframe cannot use another indicator as its source.");
        }
        List<Candle> higher = completed(load(symbol, timeframe, request.to()));
        if (higher.size() < 2) throw new IndicatorUnavailableException("No " + timeframe + " candles are stored for " + symbol + ".");
        Computed raw = engine.run(definition, values, timeframe, higher, external, null, null, null);
        Map<String, double[]> outputs = new LinkedHashMap<>();
        raw.outputs().forEach((output, array) -> outputs.put(output, ChartIndicatorEngine.mapHigherTimeframe(candles, higher, array)));
        Map<String, int[]> tones = new HashMap<>();
        raw.tones().forEach((output, array) -> tones.put(output, ChartIndicatorEngine.mapHigherTimeframe(candles, higher, array)));
        return new Computed(raw.label(), outputs, raw.shifts(), tones, Map.of(), List.of(), raw.references());
    }

    /** Closing prices of another symbol on the chart's bars, filling its history first when necessary. */
    private double[] alignedClose(String other, String interval, List<Candle> candles, Long to) {
        if (marketData != null) {
            try {
                marketData.loadCandlePage(other, interval, null, 1000);
            } catch (RuntimeException unavailable) {
                // Stored candles, if any, are still used below.
            }
        }
        Map<Long, Double> closes = new HashMap<>();
        for (Candle candle : completed(load(other, interval, to))) closes.put(candle.getTimestamp(), candle.getClosePrice());
        double[] result = new double[candles.size()];
        int matched = 0;
        for (int index = 0; index < candles.size(); index++) {
            Double close = closes.get(candles.get(index).getTimestamp());
            result[index] = close == null ? Double.NaN : close;
            if (close != null) matched++;
        }
        if (matched == 0) throw new IndicatorUnavailableException("No candles are stored for " + other + ".");
        return result;
    }

    private IndicatorConfigurationView view(Resolved item, Computed computed, List<Candle> candles, List<Long> future,
                                            IndicatorBatchRequest request) {
        IndicatorDefinition definition = item.definition();
        Long from = request.from();
        Long to = request.to();
        int size = candles.size();
        List<SeriesView> series = new ArrayList<>();
        List<MarkerView> markers = new ArrayList<>();
        for (OutputSpec spec : definition.outputs()) {
            if (spec.style() == OutputStyle.MARKERS) {
                for (IndicatorContext.Marker marker : computed.markers().getOrDefault(spec.key(), List.of())) {
                    if (marker.index() < 0 || marker.index() >= size || !Double.isFinite(marker.price())) continue;
                    long timestamp = candles.get(marker.index()).getTimestamp();
                    if (from != null && timestamp < from || to != null && timestamp > to) continue;
                    markers.add(new MarkerView(spec.key(), timestamp, marker.price(), marker.text()));
                }
                continue;
            }
            double[] values = computed.outputs().get(spec.key());
            if (values == null) continue;
            int shift = computed.shifts().getOrDefault(spec.key(), 0);
            int[] tones = computed.tones().get(spec.key());
            List<PointView> points = new ArrayList<>();
            for (int index = 0; index < values.length; index++) {
                if (!Double.isFinite(values[index])) continue;
                int target = index + shift;
                if (target < 0) continue;
                long timestamp;
                if (target < size) {
                    timestamp = candles.get(target).getTimestamp();
                    if (from != null && timestamp < from || to != null && timestamp > to) continue;
                } else {
                    if (target - size >= future.size()) continue;
                    timestamp = future.get(target - size);
                }
                points.add(new PointView(timestamp, values[index], tones == null ? null : tones[index]));
            }
            series.add(new SeriesView(spec.key(), englishLabel(spec.key()), spec.style().name(), List.copyOf(points)));
        }
        List<ShapeView> shapes = new ArrayList<>();
        for (IndicatorContext.Shape shape : computed.shapes()) {
            List<ShapePointView> points = new ArrayList<>();
            boolean valid = true;
            for (IndicatorContext.ShapePoint point : shape.points()) {
                long timestamp;
                if (point.index() < 0) { valid = false; break; }
                if (point.index() < size) timestamp = candles.get(point.index()).getTimestamp();
                else if (point.index() - size < future.size()) timestamp = future.get(point.index() - size);
                else timestamp = future.getLast();
                points.add(new ShapePointView(timestamp, point.price()));
            }
            if (valid) shapes.add(new ShapeView(shape.kind(), List.copyOf(points), shape.text(), shape.role(), shape.extend(), shape.data()));
        }
        List<Double> references = new ArrayList<>(definition.references());
        references.addAll(computed.references());
        return new IndicatorConfigurationView(item.config().id(), definition.type(), computed.label(), definition.placement().name(),
                item.values().asMap(), List.copyOf(series), List.copyOf(references), List.copyOf(markers), List.copyOf(shapes), null);
    }

    private IndicatorConfigurationView errorView(Resolved item, String message) {
        IndicatorDefinition definition = item.definition();
        return new IndicatorConfigurationView(item.config().id(), definition.type(), definition.label().apply(item.values()),
                definition.placement().name(), item.values().asMap(), List.of(), List.copyOf(definition.references()),
                List.of(), List.of(), message);
    }

    /** Indicators that read another indicator are computed after it; a cycle is rejected. */
    private List<Resolved> dependencyOrder(List<Resolved> resolved) {
        Map<String, Resolved> byId = new LinkedHashMap<>();
        resolved.forEach(item -> byId.put(item.config().id(), item));
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Resolved item : resolved) {
            // A rejected source is not in the batch; its dependents report it as unavailable instead.
            List<String> dependencies = ChartIndicatorCatalog.dependencies(item.definition(), item.values()).stream()
                    .filter(byId::containsKey).toList();
            incoming.put(item.config().id(), dependencies.size());
            for (String dependency : dependencies) dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(item.config().id());
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        byId.keySet().forEach(id -> { if (incoming.get(id) == 0) ready.add(id); });
        List<Resolved> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            ordered.add(byId.get(id));
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (incoming.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
            }
        }
        if (ordered.size() != resolved.size()) throw new UserInputException("Indicator sources form a cycle.");
        return ordered;
    }

    private List<Object> cacheKey(String symbol, String interval, IndicatorBatchRequest request, boolean visibleRange, List<Resolved> resolved) {
        List<Object> key = new ArrayList<>();
        key.add(symbol);
        key.add(interval);
        // Error messages in the result are in the reader's language.
        key.add(org.example.stockwatch247.i18n.Messages.current().getLanguage());
        key.add(visibleRange ? request : new IndicatorBatchRequest(request.from(), request.to(), null, null, request.indicators()));
        key.add(generation(symbol, interval));
        for (Resolved item : resolved) {
            for (InputSpec spec : item.definition().inputs()) {
                if (spec.kind() == InputKind.SYMBOL) key.add(generation(item.values().text(spec.key()), interval));
                if (spec.kind() == InputKind.TIMEFRAME && !"chart".equals(item.values().text(spec.key()))) {
                    key.add(generation(symbol, item.values().text(spec.key())));
                }
            }
        }
        return List.copyOf(key);
    }

    private long generation(String symbol, String interval) {
        return revisions == null ? 0 : revisions.generation(symbol, interval);
    }

    /**
     * The stored series up to {@code to}. Indicators run over the whole history so values do not depend
     * on how far the chart has scrolled; the series is kept per candle revision, so changing an
     * indicator or panning re-reads nothing until new candles are stored.
     */
    private List<Candle> load(String symbol, String interval, Long to) {
        if (revisions == null) {
            return to == null ? candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval)
                    : candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanEqualOrderByTimestampAsc(symbol, interval, to);
        }
        List<Candle> series = seriesCache.getOrCompute(List.of(symbol, interval, generation(symbol, interval)),
                () -> List.copyOf(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval)));
        if (to == null) return series;
        int low = 0, high = series.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            Long timestamp = series.get(middle).getTimestamp();
            if (timestamp != null && timestamp <= to) low = middle + 1; else high = middle;
        }
        return series.subList(0, low);
    }

    private List<Candle> completed(List<Candle> candles) {
        return candles.stream().filter(this::complete).sorted(Comparator.comparingLong(Candle::getTimestamp)).toList();
    }

    private boolean complete(Candle candle) {
        return candle != null && candle.getTimestamp() != null && candle.getOpenPrice() != null
                && candle.getHighPrice() != null && candle.getLowPrice() != null && candle.getClosePrice() != null;
    }

    private static boolean allowedValue(Object value) {
        if (value instanceof Number number) return Double.isFinite(number.doubleValue());
        if (value instanceof String text) return text.length() <= MAX_TEXT;
        return value instanceof Boolean;
    }

    private static String englishLabel(String key) {
        return switch (key) {
            case "value" -> "Value";
            case "macd" -> "MACD";
            case "k" -> "%K";
            case "d" -> "%D";
            case "adx" -> "ADX";
            case "plusDi" -> "+DI";
            case "minusDi" -> "-DI";
            case "poc" -> "Point of control";
            case "val" -> "Value area low";
            case "vah" -> "Value area high";
            default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }

    private record Resolved(IndicatorConfiguration config, IndicatorDefinition definition, InputValues values) { }

    /** A reason one indicator could not be drawn (for example a comparison symbol without data). */
    static final class IndicatorUnavailableException extends RuntimeException {
        IndicatorUnavailableException(String message) { super(message); }
    }

    public record IndicatorBatchRequest(Long from, Long to, Long visibleFrom, Long visibleTo, List<IndicatorConfiguration> indicators) {
        public IndicatorBatchRequest(Long from, Long to, List<IndicatorConfiguration> indicators) {
            this(from, to, null, null, indicators);
        }
    }

    public record IndicatorConfiguration(String id, String type, Map<String, Object> parameters) { }

    public record IndicatorBatchView(String interval, List<IndicatorConfigurationView> indicators) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndicatorConfigurationView(String id, String type, String label, String placement,
                                             Map<String, Object> parameters, List<SeriesView> series,
                                             List<Double> references, List<MarkerView> markers, List<ShapeView> shapes,
                                             String error) { }

    public record SeriesView(String key, String label, String style, List<PointView> points) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PointView(long timestamp, double value, Integer tone) {
        public PointView(long timestamp, double value) { this(timestamp, value, null); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarkerView(String key, long timestamp, double price, String text) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShapeView(String kind, List<ShapePointView> points, String text, String role, String extend, Map<String, Object> data) { }

    public record ShapePointView(long timestamp, double price) { }
}
