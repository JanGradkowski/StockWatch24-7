package org.example.stockwatch247.service.chartindicator;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputValues;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.MedianPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one indicator computation can read (bars, validated inputs, sources from other indicators or
 * symbols) and where it writes its series, markers and shapes. Every output array is aligned with
 * the bars: index i belongs to bar i.
 */
public final class IndicatorContext {
    private final String interval;
    private final List<Candle> candles;
    private final BarSeries bars;
    private final InputValues inputs;
    private final Map<String, double[]> external;
    private final Long rangeFrom;
    private final Long visibleFrom;
    private final Long visibleTo;
    private final double[] open;
    private final double[] high;
    private final double[] low;
    private final double[] close;
    private final double[] volume;
    private final Map<String, double[]> priceSources = new HashMap<>();

    private final Map<String, double[]> outputs = new LinkedHashMap<>();
    private final Map<String, Integer> shifts = new HashMap<>();
    private final Map<String, int[]> tones = new HashMap<>();
    private final Map<String, List<Marker>> markers = new LinkedHashMap<>();
    private final List<Shape> shapes = new ArrayList<>();
    private final List<Double> references = new ArrayList<>();

    IndicatorContext(String interval, List<Candle> candles, BarSeries bars, InputValues inputs,
                     Map<String, double[]> external, Long rangeFrom, Long visibleFrom, Long visibleTo) {
        this.interval = interval;
        this.candles = candles;
        this.bars = bars;
        this.inputs = inputs;
        this.external = external == null ? Map.of() : external;
        this.rangeFrom = rangeFrom;
        this.visibleFrom = visibleFrom;
        this.visibleTo = visibleTo;
        int size = candles.size();
        open = new double[size];
        high = new double[size];
        low = new double[size];
        close = new double[size];
        volume = new double[size];
        for (int index = 0; index < size; index++) {
            Candle candle = candles.get(index);
            open[index] = candle.getOpenPrice();
            high[index] = candle.getHighPrice();
            low[index] = candle.getLowPrice();
            close[index] = candle.getClosePrice();
            volume[index] = candle.getVolume() == null ? 0 : Math.max(0, candle.getVolume());
        }
    }

    public int size() { return candles.size(); }

    public String interval() { return interval; }

    public long time(int index) { return candles.get(index).getTimestamp(); }

    public BarSeries bars() { return bars; }

    public InputValues inputs() { return inputs; }

    public int integer(String key) { return inputs.integer(key); }

    public double decimal(String key) { return inputs.decimal(key); }

    public boolean bool(String key) { return inputs.bool(key); }

    public String text(String key) { return inputs.text(key); }

    public double[] open() { return open; }

    public double[] high() { return high; }

    public double[] low() { return low; }

    public double[] close() { return close; }

    public double[] volume() { return volume; }

    public double[] price(String key) {
        return priceSources.computeIfAbsent(key, name -> PriceSource.values(name, open, high, low, close));
    }

    /** The series selected by the "source" input. */
    public double[] source() { return source("source"); }

    public double[] source(String inputKey) {
        String value = inputs.text(inputKey);
        if (PriceSource.isPrice(value)) return price(value);
        double[] dependency = external.get(value);
        if (dependency == null) throw new IllegalStateException("The source " + value + " is not available.");
        return dependency;
    }

    /** True when the input reads a bar price, so its values have no warm-up gap. */
    public boolean priceSource(String inputKey) { return PriceSource.isPrice(inputs.text(inputKey)); }

    /** A TA4J view of a price source, using TA4J's own price helpers for the common ones. */
    public Indicator<Num> ta4jPrice(String key) {
        return switch (key) {
            case "close" -> new ClosePriceIndicator(bars);
            case "open" -> new OpenPriceIndicator(bars);
            case "high" -> new HighPriceIndicator(bars);
            case "low" -> new LowPriceIndicator(bars);
            case "hl2" -> new MedianPriceIndicator(bars);
            case "hlc3" -> new TypicalPriceIndicator(bars);
            default -> new ArrayIndicator(bars, price(key));
        };
    }

    public Indicator<Num> ta4j(double[] values) { return new ArrayIndicator(bars, values); }

    /** Values of a TA4J indicator, NaN during its unstable warm-up bars. */
    public double[] values(Indicator<Num> indicator) {
        double[] result = new double[size()];
        int unstable = indicator.getCountOfUnstableBars();
        for (int index = 0; index < result.length; index++) {
            if (index < unstable) { result[index] = Double.NaN; continue; }
            Num value = indicator.getValue(index);
            result[index] = value == null || value.isNaN() ? Double.NaN : value.doubleValue();
        }
        return result;
    }

    public double[] ma(String type, double[] source, int length) {
        return TaMath.movingAverage(type, source, volume, length);
    }

    /** Close prices of another symbol aligned with these bars (NaN where it has no bar). */
    public double[] symbolClose(String inputKey) {
        double[] values = external.get("symbol:" + inputs.text(inputKey));
        if (values == null) throw new IllegalStateException("No candles are available for " + inputs.text(inputKey) + ".");
        return values;
    }

    /** First bar timestamp the chart asked for; earlier bars only warm the indicator up. */
    public Long rangeFrom() { return rangeFrom; }

    /** First index at or after the requested range start. */
    public int rangeStartIndex() {
        if (rangeFrom == null) return 0;
        int index = 0;
        while (index < size() && time(index) < rangeFrom) index++;
        return index;
    }

    public Long visibleFrom() { return visibleFrom; }

    public Long visibleTo() { return visibleTo; }

    public void output(String key, double[] values) {
        if (values.length != size()) throw new IllegalStateException("Output " + key + " is not aligned with the bars.");
        outputs.put(key, values);
    }

    public void output(String key, Indicator<Num> indicator) { output(key, values(indicator)); }

    /** Draws the output shifted by the given number of bars; positive values extend into the future. */
    public void shift(String key, int barsForward) { shifts.put(key, barsForward); }

    /** Palette index per bar for an output whose colour mode is TONE. */
    public void tones(String key, int[] values) { tones.put(key, values); }

    public void marker(String key, int index, double price, String text) {
        markers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Marker(index, price, text));
    }

    public void shape(Shape shape) { shapes.add(shape); }

    public void reference(double value) { references.add(value); }

    Map<String, double[]> outputs() { return outputs; }

    Map<String, Integer> shifts() { return shifts; }

    Map<String, int[]> tones() { return tones; }

    Map<String, List<Marker>> markers() { return markers; }

    List<Shape> shapes() { return shapes; }

    List<Double> references() { return references; }

    long timeOf(int index) { return candles.get(index).getTimestamp(); }

    public record Marker(int index, double price, String text) { }

    /**
     * A drawing produced by an indicator. Points refer to bars by index (an index past the last bar
     * is a future bar). Kinds: segment, polyline, level, label, box, profile, table.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shape(String kind, List<ShapePoint> points, String text, String role, String extend,
                        Map<String, Object> data) {
        public static Shape segment(int fromIndex, double fromPrice, int toIndex, double toPrice, String role, String extend) {
            return new Shape("segment", List.of(new ShapePoint(fromIndex, fromPrice), new ShapePoint(toIndex, toPrice)), null, role, extend, null);
        }

        public static Shape level(int fromIndex, int toIndex, double price, String text, String role, String extend) {
            return new Shape("level", List.of(new ShapePoint(fromIndex, price), new ShapePoint(toIndex, price)), text, role, extend, null);
        }

        public static Shape polyline(List<ShapePoint> points, String role) {
            return new Shape("polyline", List.copyOf(points), null, role, null, null);
        }

        public static Shape label(int index, double price, String text, String role) {
            return new Shape("label", List.of(new ShapePoint(index, price)), text, role, null, null);
        }

        public static Shape box(int fromIndex, double top, int toIndex, double bottom, String role) {
            return new Shape("box", List.of(new ShapePoint(fromIndex, top), new ShapePoint(toIndex, bottom)), null, role, null, null);
        }

        public static Shape profile(int fromIndex, int toIndex, Map<String, Object> data) {
            return new Shape("profile", List.of(new ShapePoint(fromIndex, 0), new ShapePoint(toIndex, 0)), null, "profile", null, data);
        }

        public static Shape table(Map<String, Object> data) {
            return new Shape("table", List.of(), null, "table", null, data);
        }
    }

    public record ShapePoint(int index, double price) { }
}
