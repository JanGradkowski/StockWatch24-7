package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputValues;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Every configurable chart indicator, and validation of the inputs a chart sends for one. */
@Component
public class ChartIndicatorCatalog {
    private static final List<String> INTERVAL_ORDER = List.of("1d", "1wk", "1mo");

    private final Map<String, IndicatorDefinition> definitions;

    public ChartIndicatorCatalog() {
        Map<String, IndicatorDefinition> registered = new LinkedHashMap<>();
        java.util.function.Consumer<IndicatorDefinition> register = definition -> {
            if (registered.putIfAbsent(definition.type(), definition) != null) {
                throw new IllegalStateException("Duplicate chart indicator " + definition.type());
            }
        };
        MovingAverageIndicators.register(register);
        TrendIndicators.register(register);
        MomentumIndicators.register(register);
        VolatilityIndicators.register(register);
        VolumeIndicators.register(register);
        LevelIndicators.register(register);
        StatisticsIndicators.register(register);
        ProfileIndicators.register(register);
        FormulaIndicator.register(register);
        definitions = Collections.unmodifiableMap(registered);
    }

    public Optional<IndicatorDefinition> find(String type) {
        return Optional.ofNullable(type == null ? null : definitions.get(type.trim().toUpperCase(Locale.ROOT)));
    }

    public List<IndicatorDefinition> all() {
        return List.copyOf(definitions.values());
    }

    /**
     * Applies defaults and checks every input against its definition. {@code batchIds} are the other
     * indicators of the same request, which a source input may reference.
     */
    public InputValues resolve(IndicatorDefinition definition, Map<String, Object> raw, Set<String> batchIds,
                               String ownId, String chartInterval) {
        Map<String, Object> provided = raw == null ? Map.of() : raw;
        Map<String, InputSpec> specs = new HashMap<>();
        definition.inputs().forEach(spec -> specs.put(spec.key(), spec));
        for (String key : provided.keySet()) {
            if (!specs.containsKey(key)) throw new IllegalArgumentException("Unknown " + definition.type() + " input: " + key + ".");
        }
        Map<String, Object> values = new HashMap<>();
        for (InputSpec spec : definition.inputs()) {
            Object value = provided.containsKey(spec.key()) ? provided.get(spec.key()) : spec.defaultValue();
            values.put(spec.key(), validate(spec, value, batchIds, ownId, chartInterval));
        }
        InputValues result = new InputValues(values);
        definition.validator().validate(result);
        return result;
    }

    private Object validate(InputSpec spec, Object value, Set<String> batchIds, String ownId, String chartInterval) {
        String key = spec.key();
        return switch (spec.kind()) {
            case INTEGER -> {
                String message = key + " must be a whole number from " + whole(spec.min()) + " to " + whole(spec.max()) + ".";
                if (!(value instanceof Number number)) throw new UserInputException(message);
                double raw = number.doubleValue();
                if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < spec.min() || raw > spec.max()) {
                    throw new UserInputException(message);
                }
                yield (int) raw;
            }
            case DECIMAL -> {
                String message = key + " must be between " + IndicatorDefinition.compact(spec.min()) + " and "
                        + IndicatorDefinition.compact(spec.max()) + ".";
                if (!(value instanceof Number number)) throw new UserInputException(message);
                double raw = number.doubleValue();
                if (!Double.isFinite(raw) || raw < spec.min() || raw > spec.max()) throw new UserInputException(message);
                yield raw;
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean flag)) throw new UserInputException(key + " must be true or false.");
                yield flag;
            }
            case CHOICE -> {
                if (!(value instanceof String text) || !spec.options().contains(text)) {
                    throw new UserInputException(key + " must be one of " + String.join(", ", spec.options()) + ".");
                }
                yield text;
            }
            case SOURCE -> {
                if (!(value instanceof String text)) throw new UserInputException(key + " must name a price or an indicator.");
                if (PriceSource.isPrice(text)) yield text;
                if (PriceSource.isDependency(text) && text.length() <= 120) {
                    String dependency = PriceSource.dependencyId(text);
                    if (!dependency.equals(ownId) && batchIds.contains(dependency)) yield text;
                }
                throw new UserInputException(key + " refers to an unknown price or indicator.");
            }
            case TIMEFRAME -> {
                if (!(value instanceof String text) || !spec.options().contains(text)) {
                    throw new UserInputException(key + " must be chart, 1wk or 1mo.");
                }
                if ("chart".equals(text) || text.equals(chartInterval)) yield "chart";
                if (INTERVAL_ORDER.indexOf(text) < INTERVAL_ORDER.indexOf(chartInterval)) {
                    throw new UserInputException("The timeframe must not be shorter than the chart interval.");
                }
                yield text;
            }
            case SYMBOL -> {
                if (!(value instanceof String text)) throw new UserInputException(key + " must be a market symbol.");
                yield SecurityInputValidator.requireMarketSymbol(text);
            }
            case TIMESTAMP -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0
                        || number.doubleValue() != Math.rint(number.doubleValue())) {
                    throw new UserInputException(key + " must be a date.");
                }
                yield number.longValue();
            }
            case TEXT -> {
                if (!(value instanceof String text) || text.length() > spec.max()) {
                    throw new UserInputException(key + " must be text of at most " + whole(spec.max()) + " characters.");
                }
                yield text;
            }
        };
    }

    private static String whole(Double value) {
        return Long.toString(Math.round(value));
    }

    /** Ids of the other indicators a configuration reads through its source inputs. */
    public static List<String> dependencies(IndicatorDefinition definition, InputValues values) {
        List<String> result = new ArrayList<>();
        for (InputSpec spec : definition.inputs()) {
            if (spec.kind() != IndicatorDefinition.InputKind.SOURCE) continue;
            String value = values.text(spec.key());
            if (PriceSource.isDependency(value)) result.add(PriceSource.dependencyId(value));
        }
        return result;
    }
}
