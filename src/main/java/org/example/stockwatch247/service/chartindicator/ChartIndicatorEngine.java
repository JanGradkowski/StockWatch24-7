package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.service.chartindicator.IndicatorContext.Marker;
import org.example.stockwatch247.service.chartindicator.IndicatorContext.Shape;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputValues;
import org.example.stockwatch247.service.technical.Ta4jBarSeriesFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs one indicator definition over a list of bars. */
public final class ChartIndicatorEngine {
    private final Ta4jBarSeriesFactory seriesFactory = new Ta4jBarSeriesFactory();

    /** Everything one indicator produced, aligned with the bars it was computed on. */
    public record Computed(String label, Map<String, double[]> outputs, Map<String, Integer> shifts, Map<String, int[]> tones,
                           Map<String, List<Marker>> markers, List<Shape> shapes, List<Double> references) { }

    public Computed run(IndicatorDefinition definition, InputValues values, String interval, List<Candle> candles,
                        Map<String, double[]> external, Long rangeFrom, Long visibleFrom, Long visibleTo) {
        IndicatorContext context = new IndicatorContext(interval, candles, seriesFactory.create(candles), values, external,
                rangeFrom, visibleFrom, visibleTo);
        definition.computation().compute(context);
        return new Computed(definition.label().apply(values), new LinkedHashMap<>(context.outputs()), Map.copyOf(context.shifts()),
                Map.copyOf(context.tones()), new LinkedHashMap<>(context.markers()), List.copyOf(context.shapes()),
                List.copyOf(context.references()));
    }

    /**
     * Higher-timeframe values on chart bars without look-ahead: a weekly or monthly bar's value is
     * shown from the first chart bar of the following period, once that bar is complete.
     */
    public static double[] mapHigherTimeframe(List<Candle> chart, List<Candle> higher, double[] values) {
        double[] result = new double[chart.size()];
        Arrays.fill(result, Double.NaN);
        int source = -1;
        for (int index = 0; index < chart.size(); index++) {
            long time = chart.get(index).getTimestamp();
            while (source + 2 < higher.size() && higher.get(source + 2).getTimestamp() <= time) source++;
            if (source >= 0 && source + 1 < higher.size() && higher.get(source + 1).getTimestamp() <= time) result[index] = values[source];
        }
        return result;
    }

    public static int[] mapHigherTimeframe(List<Candle> chart, List<Candle> higher, int[] values) {
        double[] asDoubles = new double[values.length];
        for (int index = 0; index < values.length; index++) asDoubles[index] = values[index];
        double[] mapped = mapHigherTimeframe(chart, higher, asDoubles);
        int[] result = new int[mapped.length];
        for (int index = 0; index < mapped.length; index++) result[index] = Double.isFinite(mapped[index]) ? (int) mapped[index] : 0;
        return result;
    }
}
