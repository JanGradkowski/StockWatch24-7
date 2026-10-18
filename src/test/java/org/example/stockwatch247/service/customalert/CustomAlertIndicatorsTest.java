package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorEngine;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputKind;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An alert sees each candle once, at its close. Every indicator offered to custom alerts must therefore
 * give the same past values whether or not later candles exist; this recomputes each one on a shorter
 * history and compares.
 */
class CustomAlertIndicatorsTest {
    private static final int FULL = 360;
    private static final int PREFIX = 290;

    @Test
    void exactlyTheIndicatorsWhosePastValuesMoveAreExcluded() {
        ChartIndicatorCatalog catalog = new ChartIndicatorCatalog();
        List<Candle> candles = randomWalk(FULL);
        Set<String> moving = new TreeSet<>();
        for (IndicatorDefinition definition : catalog.all()) {
            if (definition.visibleRangeDependent() || CustomAlertIndicators.outputs(definition).isEmpty()) continue;
            if (definition.inputs().stream().anyMatch(input -> input.kind() == InputKind.TIMESTAMP || input.kind() == InputKind.TEXT)) continue;
            if (pastValuesMove(catalog, definition, candles)) moving.add(definition.type());
        }
        assertThat(moving).as("indicators whose earlier values change with later candles")
                .containsExactlyInAnyOrderElementsOf(CustomAlertIndicators.NON_CAUSAL);
    }

    @Test
    void popularIndicatorsStayAvailable() {
        ChartIndicatorCatalog catalog = new ChartIndicatorCatalog();
        for (String type : List.of("RSI", "MACD", "SMA", "EMA", "BOLLINGER", "STOCHASTIC", "RELATIVE_VOLUME", "ATR", "ADX_DMI", "OBV")) {
            catalog.find(type).ifPresent(definition -> assertThat(CustomAlertIndicators.alertable(definition)).as(type).isTrue());
        }
    }

    private static boolean pastValuesMove(ChartIndicatorCatalog catalog, IndicatorDefinition definition, List<Candle> candles) {
        var values = catalog.resolve(definition, Map.of(), Set.of(), "audit", "1d");
        var engine = new ChartIndicatorEngine();
        var full = engine.run(definition, values, "1d", candles, external(definition, values, FULL), null, null, null);
        var prefix = engine.run(definition, values, "1d", candles.subList(0, PREFIX), external(definition, values, PREFIX), null, null, null);
        for (String output : CustomAlertIndicators.outputs(definition)) {
            // A plot drawn earlier than the bar it was computed on shows a later close on an earlier candle.
            if (full.shifts().getOrDefault(output, 0) < 0) return true;
            double[] a = full.outputs().get(output);
            double[] b = prefix.outputs().get(output);
            if (a == null || b == null) continue;
            for (int index = 0; index < PREFIX; index++) {
                boolean finiteA = Double.isFinite(a[index]);
                boolean finiteB = Double.isFinite(b[index]);
                if (finiteA != finiteB) return true;
                if (finiteA && Math.abs(a[index] - b[index]) > 1e-6 * Math.max(1, Math.abs(a[index]))) return true;
            }
        }
        return false;
    }

    private static Map<String, double[]> external(IndicatorDefinition definition, IndicatorDefinition.InputValues values, int size) {
        Map<String, double[]> external = new HashMap<>();
        Random random = new Random(7);
        for (var input : definition.inputs()) {
            if (input.kind() != InputKind.SYMBOL) continue;
            double[] other = new double[size];
            double price = 50;
            for (int index = 0; index < size; index++) {
                price *= 1 + (random.nextDouble() - 0.5) * 0.04;
                other[index] = price;
            }
            external.put("symbol:" + values.text(input.key()), other);
        }
        return external;
    }

    static List<Candle> randomWalk(int count) {
        Random random = new Random(42);
        List<Candle> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        double close = 100;
        while (candles.size() < count) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                double open = close;
                close = Math.max(5, open * (1 + (random.nextDouble() - 0.48) * 0.05));
                double high = Math.max(open, close) * (1 + random.nextDouble() * 0.02);
                double low = Math.min(open, close) * (1 - random.nextDouble() * 0.02);
                candles.add(new Candle("AUDIT", "1d", date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), open, high, low, close,
                        (long) (1_000_000 + random.nextInt(900_000))));
            }
            date = date.plusDays(1);
        }
        return candles;
    }
}
