package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.chartindicator.IndicatorContext.Shape;
import org.example.stockwatch247.service.chartindicator.IndicatorContext.ShapePoint;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.FillSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Regression, correlation, comparisons with other symbols and summary tables. */
final class StatisticsIndicators {
    static final String DEFAULT_COMPARISON = "SPY";

    private StatisticsIndicators() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("LINEAR_REGRESSION", "LinReg", Category.STATISTICS, Placement.PRICE)
                .inputs(InputSpec.integer("period", 9, 2, 500), InputSpec.integer("offset", 0, 0, 500), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.CYAN))
                .compute(context -> context.output("value", TaMath.linreg(context.source(), context.integer("period"), context.integer("offset")))));

        catalog.accept(define("LINEAR_REGRESSION_SLOPE", "LRS", Category.STATISTICS, Placement.PANEL)
                .inputs(InputSpec.integer("period", 14, 2, 500), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(0)
                .compute(context -> context.output("value", TaMath.linregSlope(context.source(), context.integer("period")))));

        catalog.accept(define("LINEAR_REGRESSION_CHANNEL", "LR Channel", Category.STATISTICS, Placement.PRICE)
                .inputs(InputSpec.integer("period", 100, 3, 2000), InputSpec.decimal("upperDeviation", 2, 0, 10, 0.1),
                        InputSpec.decimal("lowerDeviation", 2, 0, 10, 0.1), InputSpec.bool("extendLeft", false),
                        InputSpec.bool("extendRight", false), InputSpec.source("close"))
                .shapes()
                .compute(StatisticsIndicators::regressionChannel));

        catalog.accept(define("CORRELATION_COEFFICIENT", "CC", Category.STATISTICS, Placement.PANEL)
                .inputs(InputSpec.symbol("symbol", DEFAULT_COMPARISON), InputSpec.integer("period", 20, 2, 500), InputSpec.source("close"))
                .outputs(OutputSpec.area("value", Palette.BLUE))
                .references(-1, 0, 1)
                .compute(context -> context.output("value",
                        TaMath.correlation(context.source(), context.symbolClose("symbol"), context.integer("period")))));

        catalog.accept(define("COMPARE", "Compare", Category.STATISTICS, Placement.PRICE)
                .inputs(InputSpec.symbol("symbol", DEFAULT_COMPARISON))
                .outputs(OutputSpec.line("value", Palette.ORANGE))
                .label(values -> values.text("symbol"))
                .compute(context -> {
                    double[] close = context.close();
                    double[] other = context.symbolClose("symbol");
                    int base = context.rangeStartIndex();
                    while (base < close.length && !(Double.isFinite(other[base]) && other[base] != 0)) base++;
                    if (base >= close.length) return;
                    context.output("value", TaMath.scale(other, close[base] / other[base]));
                }));

        catalog.accept(define("RATIO", "Ratio", Category.STATISTICS, Placement.PANEL)
                .inputs(InputSpec.symbol("symbol", DEFAULT_COMPARISON), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.ORANGE))
                .compute(context -> context.output("value", TaMath.divide(context.source(), context.symbolClose("symbol")))));

        catalog.accept(define("SPREAD", "Spread", Category.STATISTICS, Placement.PANEL)
                .inputs(InputSpec.symbol("symbol", DEFAULT_COMPARISON), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.ORANGE))
                .references(0)
                .compute(context -> context.output("value", TaMath.subtract(context.source(), context.symbolClose("symbol")))));

        catalog.accept(define("MEDIAN", "Median", Category.STATISTICS, Placement.PRICE)
                .inputs(InputSpec.integer("period", 3, 1, 500), InputSpec.length("atrPeriod", 14), InputSpec.decimal("atrMultiplier", 2, 0.1, 10, 0.1),
                        InputSpec.source("hl2"))
                .outputs(OutputSpec.line("upper", Palette.MINT).width(1), OutputSpec.line("median", Palette.RED), OutputSpec.line("lower", Palette.MINT).width(1))
                .fills(FillSpec.band("upper", "lower", Palette.MINT_FILL))
                .compute(context -> {
                    double[] median = TaMath.median(context.source(), context.integer("period"));
                    double[] width = TaMath.scale(TaMath.atr(context.high(), context.low(), context.close(), context.integer("atrPeriod")),
                            context.decimal("atrMultiplier"));
                    context.output("upper", TaMath.add(median, width));
                    context.output("median", median);
                    context.output("lower", TaMath.subtract(median, width));
                }));

        catalog.accept(define("Z_SCORE", "Z", Category.STATISTICS, Placement.PANEL)
                .inputs(InputSpec.integer("period", 20, 2, 500), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.VIOLET))
                .references(-2, 0, 2)
                .compute(context -> {
                    double[] source = context.source();
                    int period = context.integer("period");
                    context.output("value", TaMath.divide(TaMath.subtract(source, TaMath.sma(source, period)), TaMath.stdev(source, period)));
                }));

        catalog.accept(define("SEASONALITY", "Seasonality", Category.STATISTICS, Placement.PRICE)
                .shapes()
                .compute(StatisticsIndicators::seasonality));

        catalog.accept(define("PERFORMANCE", "Performance", Category.STATISTICS, Placement.PRICE)
                .shapes()
                .compute(StatisticsIndicators::performance));
    }

    static void regressionChannel(IndicatorContext context) {
        double[] source = context.source();
        int length = Math.min(context.integer("period"), source.length);
        if (length < 3) return;
        int start = source.length - length;
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumXX = 0;
        for (int position = 0; position < length; position++) {
            double y = source[start + position];
            sumX += position;
            sumY += y;
            sumXY += position * y;
            sumXX += position * (double) position;
        }
        double denominator = length * sumXX - sumX * sumX;
        double slope = denominator == 0 ? 0 : (length * sumXY - sumX * sumY) / denominator;
        double intercept = (sumY - slope * sumX) / length;
        double residuals = 0;
        for (int position = 0; position < length; position++) {
            double residual = source[start + position] - (intercept + slope * position);
            residuals += residual * residual;
        }
        double deviation = Math.sqrt(residuals / length);
        int end = source.length - 1;
        double first = intercept;
        double last = intercept + slope * (length - 1);
        double up = context.decimal("upperDeviation") * deviation;
        double down = context.decimal("lowerDeviation") * deviation;
        String extend = LevelIndicators.extend(context);
        context.shape(new Shape("fill", List.of(new ShapePoint(start, first + up), new ShapePoint(end, last + up),
                new ShapePoint(end, last - down), new ShapePoint(start, first - down)), null, "channel", extend, null));
        context.shape(Shape.segment(start, first + up, end, last + up, "upper", extend));
        context.shape(Shape.segment(start, first, end, last, "base", extend));
        context.shape(Shape.segment(start, first - down, end, last - down, "lower", extend));
    }

    /** Average return, share of rising years and sample size for each calendar month. */
    static void seasonality(IndicatorContext context) {
        Map<LocalDate, Double> closes = new LinkedHashMap<>();
        for (int index = 0; index < context.size(); index++) {
            closes.put(MarketCalendar.date(context.time(index)).withDayOfMonth(1), context.close()[index]);
        }
        List<LocalDate> months = new ArrayList<>(closes.keySet());
        if (!months.isEmpty()) months.removeLast();
        double[] sums = new double[12];
        int[] counts = new int[12];
        int[] rising = new int[12];
        for (int position = 1; position < months.size(); position++) {
            LocalDate month = months.get(position);
            LocalDate previous = months.get(position - 1);
            if (!previous.plusMonths(1).equals(month)) continue;
            double before = closes.get(previous);
            if (before == 0) continue;
            double change = (closes.get(month) / before - 1) * 100;
            int slot = month.getMonthValue() - 1;
            sums[slot] += change;
            counts[slot]++;
            if (change > 0) rising[slot]++;
        }
        List<List<Object>> rows = new ArrayList<>();
        for (int slot = 0; slot < 12; slot++) {
            rows.add(Arrays.asList(slot + 1, counts[slot] == 0 ? null : sums[slot] / counts[slot],
                    counts[slot] == 0 ? null : rising[slot] * 100.0 / counts[slot], counts[slot]));
        }
        context.shape(Shape.table(Map.of("table", "seasonality", "columns", List.of("month", "average", "positive", "count"), "rows", rows)));
    }

    /** Price change over the usual look-back periods, measured from the last bar. */
    static void performance(IndicatorContext context) {
        if (context.size() < 2) return;
        int last = context.size() - 1;
        LocalDate lastDate = MarketCalendar.date(context.time(last));
        double lastClose = context.close()[last];
        List<List<Object>> rows = new ArrayList<>();
        Map<String, LocalDate> periods = new LinkedHashMap<>();
        periods.put("1W", lastDate.minusWeeks(1));
        periods.put("1M", lastDate.minusMonths(1));
        periods.put("3M", lastDate.minusMonths(3));
        periods.put("6M", lastDate.minusMonths(6));
        periods.put("YTD", LocalDate.of(lastDate.getYear(), 1, 1).minusDays(1));
        periods.put("1Y", lastDate.minusYears(1));
        periods.put("3Y", lastDate.minusYears(3));
        periods.put("5Y", lastDate.minusYears(5));
        for (Map.Entry<String, LocalDate> period : periods.entrySet()) {
            int base = -1;
            for (int index = last; index >= 0; index--) {
                if (!MarketCalendar.date(context.time(index)).isAfter(period.getValue())) { base = index; break; }
            }
            Double change = base < 0 || base == last || context.close()[base] == 0 ? null : (lastClose / context.close()[base] - 1) * 100;
            rows.add(Arrays.asList(period.getKey(), change));
        }
        Double all = context.close()[0] == 0 ? null : (lastClose / context.close()[0] - 1) * 100;
        rows.add(Arrays.asList("ALL", all));
        context.shape(Shape.table(Map.of("table", "performance", "columns", List.of("period", "change"), "rows", rows)));
    }
}
