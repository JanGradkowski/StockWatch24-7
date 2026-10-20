package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputKind;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputStyle;

import java.util.List;
import java.util.Set;

/**
 * Which chart indicators a custom alert may watch.
 *
 * <p>An alert decides on each completed candle with the data known at its close. Indicators whose past
 * values change when later candles arrive (they look for pivots that need future bars, fit a line through
 * the whole visible history, or anchor to a range the chart shows) would make the preview and history look
 * better than the live alert, so they are not offered. {@code CustomAlertIndicatorsTest} recomputes every
 * catalog indicator on a shorter history and checks that these lists are exactly what moves.</p>
 */
public final class CustomAlertIndicators {
    /** Indicators whose earlier values are revised by later candles. */
    static final Set<String> NON_CAUSAL = Set.of();

    /**
     * Single outputs drawn on an earlier candle than the one they were computed on, so they show a later
     * close on that candle: Ichimoku's lagging span. The indicator's other lines are fine.
     */
    static final Set<String> NON_CAUSAL_OUTPUTS = Set.of("ICHIMOKU:lagging");

    private CustomAlertIndicators() { }

    public static boolean alertable(IndicatorDefinition definition) {
        if (definition == null || definition.visibleRangeDependent() || NON_CAUSAL.contains(definition.type())) return false;
        // Anchored to a date the user picks on a chart, or reading free-text formulas: not reusable across stocks.
        if (definition.inputs().stream().anyMatch(input -> input.kind() == InputKind.TIMESTAMP || input.kind() == InputKind.TEXT)) {
            return false;
        }
        return !outputs(definition).isEmpty();
    }

    /** The numeric outputs (lines, histograms) a condition can compare; markers and shapes are not values. */
    public static List<String> outputs(IndicatorDefinition definition) {
        return definition.outputs().stream().filter(output -> output.style() != OutputStyle.MARKERS)
                .map(IndicatorDefinition.OutputSpec::key)
                .filter(key -> !NON_CAUSAL_OUTPUTS.contains(definition.type() + ":" + key)).toList();
    }
}
