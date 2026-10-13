package org.example.stockwatch247.service.chartindicator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Describes one configurable chart indicator: its inputs, the series it draws and how it is computed.
 * The same description is served to the browser as the indicator catalog, so the menu, settings
 * dialog and legend are generated from it instead of being duplicated in the page.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record IndicatorDefinition(
        String type,
        String shortName,
        Category category,
        Placement placement,
        List<InputSpec> inputs,
        List<OutputSpec> outputs,
        List<FillSpec> fills,
        List<Double> references,
        Zones zones,
        boolean visibleRangeDependent,
        boolean shapes,
        @JsonIgnore Validator validator,
        @JsonIgnore Computation computation,
        @JsonIgnore Function<InputValues, String> label) {

    public enum Category { MOVING_AVERAGES, TREND, MOMENTUM, VOLATILITY, VOLUME, LEVELS, STATISTICS, PROFILES }

    /** PRICE draws over the candles, PANEL in its own pane, VOLUME over the volume pane. */
    public enum Placement { PRICE, PANEL, VOLUME }

    public enum InputKind { INTEGER, DECIMAL, BOOLEAN, CHOICE, SOURCE, TIMEFRAME, SYMBOL, TIMESTAMP, TEXT }

    /**
     * LINE and STEP are lines, HISTOGRAM is a zero-based histogram, COLUMNS a histogram without a zero
     * baseline, DOTS a line drawn only as points, AREA a filled line and MARKERS shapes at single bars.
     */
    public enum OutputStyle { LINE, STEP, HISTOGRAM, COLUMNS, DOTS, AREA, MARKERS }

    /**
     * How a histogram or dotted output is coloured: NONE uses the output colour, SIGN colours by the
     * sign, SIGN_MOMENTUM by sign and whether it grew, MOMENTUM by whether it grew, and TONE uses the
     * palette index carried by each point.
     */
    public enum ColorMode { NONE, SIGN, SIGN_MOMENTUM, MOMENTUM, TONE }

    @FunctionalInterface
    public interface Computation { void compute(IndicatorContext context); }

    @FunctionalInterface
    public interface Validator { void validate(InputValues values); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InputSpec(String key, InputKind kind, Object defaultValue, Double min, Double max, Double step,
                            String choiceSet, List<String> options) {
        public static InputSpec period(int defaultValue) { return integer("period", defaultValue, 2, 500); }

        public static InputSpec length(String key, int defaultValue) { return integer(key, defaultValue, 1, 500); }

        public static InputSpec integer(String key, int defaultValue, int min, int max) {
            return new InputSpec(key, InputKind.INTEGER, defaultValue, (double) min, (double) max, 1.0, null, null);
        }

        public static InputSpec decimal(String key, double defaultValue, double min, double max, double step) {
            return new InputSpec(key, InputKind.DECIMAL, defaultValue, min, max, step, null, null);
        }

        public static InputSpec bool(String key, boolean defaultValue) {
            return new InputSpec(key, InputKind.BOOLEAN, defaultValue, null, null, null, null, null);
        }

        public static InputSpec choice(String key, String choiceSet, String defaultValue, String... options) {
            if (!Arrays.asList(options).contains(defaultValue)) {
                throw new IllegalArgumentException("Default " + defaultValue + " is not an option of " + key);
            }
            return new InputSpec(key, InputKind.CHOICE, defaultValue, null, null, null, choiceSet, List.of(options));
        }

        public static InputSpec source(String defaultValue) { return source("source", defaultValue); }

        public static InputSpec source(String key, String defaultValue) {
            return new InputSpec(key, InputKind.SOURCE, defaultValue, null, null, null, "source", PriceSource.KEYS);
        }

        public static InputSpec offset() { return integer("offset", 0, -500, 500); }

        public static InputSpec timeframe() {
            return new InputSpec("timeframe", InputKind.TIMEFRAME, "chart", null, null, null, "timeframe",
                    List.of("chart", "1wk", "1mo"));
        }

        public static InputSpec symbol(String key, String defaultValue) {
            return new InputSpec(key, InputKind.SYMBOL, defaultValue, null, null, null, null, null);
        }

        public static InputSpec timestamp(String key) {
            return new InputSpec(key, InputKind.TIMESTAMP, 0L, null, null, null, null, null);
        }

        public static InputSpec text(String key, String defaultValue, int maxLength) {
            return new InputSpec(key, InputKind.TEXT, defaultValue, 0.0, (double) maxLength, null, null, null);
        }

        public InputSpec withKey(String newKey) {
            return new InputSpec(newKey, kind, defaultValue, min, max, step, choiceSet, options);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputSpec(String key, OutputStyle style, String color, int width, int lineStyle, boolean breaks,
                             ColorMode colorMode, boolean visible, String shape, String position, List<String> palette) {
        public static OutputSpec line(String key, String color) {
            return new OutputSpec(key, OutputStyle.LINE, color, 2, 0, false, ColorMode.NONE, true, null, null, null);
        }

        public static OutputSpec histogram(String key, String color, ColorMode mode) {
            return new OutputSpec(key, OutputStyle.HISTOGRAM, color, 1, 0, false, mode, true, null, null, null);
        }

        public static OutputSpec columns(String key, String color, ColorMode mode) {
            return new OutputSpec(key, OutputStyle.COLUMNS, color, 1, 0, false, mode, true, null, null, null);
        }

        public static OutputSpec dots(String key, String color) {
            return new OutputSpec(key, OutputStyle.DOTS, color, 2, 0, true, ColorMode.NONE, true, null, null, null);
        }

        public static OutputSpec area(String key, String color) {
            return new OutputSpec(key, OutputStyle.AREA, color, 2, 0, false, ColorMode.NONE, true, null, null, null);
        }

        /** Markers: shape is circle, square, arrowUp or arrowDown; position aboveBar, belowBar or atPrice. */
        public static OutputSpec markers(String key, String color, String shape, String position) {
            return new OutputSpec(key, OutputStyle.MARKERS, color, 1, 0, true, ColorMode.NONE, true, shape, position, null);
        }

        public OutputSpec width(int value) {
            return new OutputSpec(key, style, color, value, lineStyle, breaks, colorMode, visible, shape, position, palette);
        }

        public OutputSpec dashed() {
            return new OutputSpec(key, style, color, width, 2, breaks, colorMode, visible, shape, position, palette);
        }

        public OutputSpec dotted() {
            return new OutputSpec(key, style, color, width, 1, breaks, colorMode, visible, shape, position, palette);
        }

        public OutputSpec step() {
            return new OutputSpec(key, OutputStyle.STEP, color, width, lineStyle, breaks, colorMode, visible, shape, position, palette);
        }

        public OutputSpec withBreaks() {
            return new OutputSpec(key, style, color, width, lineStyle, true, colorMode, visible, shape, position, palette);
        }

        public OutputSpec hidden() {
            return new OutputSpec(key, style, color, width, lineStyle, breaks, colorMode, false, shape, position, palette);
        }

        public OutputSpec tones(String... colors) {
            return new OutputSpec(key, style, color, width, lineStyle, breaks, ColorMode.TONE, visible, shape, position, List.of(colors));
        }
    }

    /** Fills the area between two outputs. A CLOUD uses the second colour where the lower output is on top. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FillSpec(String upper, String lower, String color, String alternateColor, boolean cloud) {
        public static FillSpec band(String upper, String lower, String color) {
            return new FillSpec(upper, lower, color, null, false);
        }

        public static FillSpec cloud(String first, String second, String color, String alternateColor) {
            return new FillSpec(first, second, color, alternateColor, true);
        }
    }

    /** Input keys holding the overbought and oversold levels a line is coloured beyond. */
    public record Zones(String upperInput, String lowerInput) { }

    /** Validated inputs with defaults applied. */
    public static final class InputValues {
        private final java.util.Map<String, Object> values;

        public InputValues(java.util.Map<String, Object> values) { this.values = java.util.Map.copyOf(values); }

        public int integer(String key) { return ((Number) values.get(key)).intValue(); }

        public double decimal(String key) { return ((Number) values.get(key)).doubleValue(); }

        public boolean bool(String key) { return Boolean.TRUE.equals(values.get(key)); }

        public String text(String key) { return String.valueOf(values.get(key)); }

        public long timestamp(String key) { return ((Number) values.get(key)).longValue(); }

        public boolean has(String key) { return values.containsKey(key); }

        public java.util.Map<String, Object> asMap() { return values; }
    }

    public static Builder define(String type, String shortName, Category category, Placement placement) {
        return new Builder(type, shortName, category, placement);
    }

    public static final class Builder {
        private final String type;
        private final String shortName;
        private final Category category;
        private final Placement placement;
        private final List<InputSpec> inputs = new ArrayList<>();
        private final List<OutputSpec> outputs = new ArrayList<>();
        private final List<FillSpec> fills = new ArrayList<>();
        private final List<Double> references = new ArrayList<>();
        private Zones zones;
        private boolean visibleRangeDependent;
        private boolean shapes;
        private Validator validator = values -> { };
        private Computation computation;
        private Function<InputValues, String> label;

        private Builder(String type, String shortName, Category category, Placement placement) {
            this.type = Objects.requireNonNull(type);
            this.shortName = Objects.requireNonNull(shortName);
            this.category = Objects.requireNonNull(category);
            this.placement = Objects.requireNonNull(placement);
        }

        public Builder inputs(InputSpec... specs) { inputs.addAll(List.of(specs)); return this; }

        public Builder outputs(OutputSpec... specs) { outputs.addAll(List.of(specs)); return this; }

        public Builder fills(FillSpec... specs) { fills.addAll(List.of(specs)); return this; }

        public Builder references(double... values) {
            for (double value : values) references.add(value);
            return this;
        }

        public Builder zones(String upperInput, String lowerInput) { zones = new Zones(upperInput, lowerInput); return this; }

        public Builder visibleRange() { visibleRangeDependent = true; return this; }

        /** The indicator draws shapes (levels, segments, profiles, tables) instead of, or as well as, series. */
        public Builder shapes() { shapes = true; return this; }

        public Builder validate(Validator value) { validator = value; return this; }

        public Builder label(Function<InputValues, String> value) { label = value; return this; }

        public IndicatorDefinition compute(Computation value) {
            computation = value;
            return build();
        }

        private IndicatorDefinition build() {
            Objects.requireNonNull(computation, "computation");
            List<String> keys = inputs.stream().map(InputSpec::key).toList();
            if (keys.size() != keys.stream().distinct().count()) throw new IllegalStateException(type + " repeats an input");
            List<String> outputKeys = outputs.stream().map(OutputSpec::key).toList();
            if (outputKeys.size() != outputKeys.stream().distinct().count()) throw new IllegalStateException(type + " repeats an output");
            for (FillSpec fill : fills) {
                if (!outputKeys.contains(fill.upper()) || !outputKeys.contains(fill.lower())) {
                    throw new IllegalStateException(type + " fills an unknown output");
                }
            }
            Function<InputValues, String> labeler = label != null ? label : values -> defaultLabel(shortName, inputs, values);
            return new IndicatorDefinition(type, shortName, category, placement, List.copyOf(inputs), List.copyOf(outputs),
                    List.copyOf(fills), List.copyOf(references), zones, visibleRangeDependent, shapes, validator, computation, labeler);
        }
    }

    /** "EMA 20", "Bollinger 20 2" — the numbers and choices a user would recognise, sources and offsets left out. */
    static String defaultLabel(String shortName, List<InputSpec> inputs, InputValues values) {
        StringBuilder label = new StringBuilder(shortName);
        for (InputSpec input : inputs) {
            if (!values.has(input.key())) continue;
            switch (input.kind()) {
                case INTEGER -> {
                    if (!input.key().equals("offset") || values.integer("offset") != 0) label.append(' ').append(values.integer(input.key()));
                }
                case DECIMAL -> label.append(' ').append(compact(values.decimal(input.key())));
                case SYMBOL -> label.append(' ').append(values.text(input.key()));
                case TIMEFRAME -> {
                    if (!"chart".equals(values.text(input.key()))) label.append(' ').append(values.text(input.key()));
                }
                default -> { }
            }
        }
        return label.toString();
    }

    static String compact(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString(Math.round(value)) : Double.toString(value);
    }
}
