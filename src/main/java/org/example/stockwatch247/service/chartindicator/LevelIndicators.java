package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.chartindicator.IndicatorContext.Shape;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;
import org.example.stockwatch247.service.chartindicator.TrendIndicators.Pivot;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Price levels: pivots, support and resistance, and automatic Fibonacci and pitchfork drawings. */
final class LevelIndicators {
    static final double[] RETRACEMENT_LEVELS = {0, 0.236, 0.382, 0.5, 0.618, 0.786, 1, 1.618, 2.618};
    static final double[] EXTENSION_LEVELS = {0, 0.236, 0.382, 0.5, 0.618, 0.786, 1, 1.272, 1.414, 1.618, 2, 2.618};

    private LevelIndicators() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("SUPPORT_RESISTANCE", "S/R", Category.LEVELS, Placement.PRICE)
                .inputs(InputSpec.period(20))
                .outputs(OutputSpec.line("support", Palette.MINT).width(1).step(), OutputSpec.line("resistance", Palette.RED).width(1).step())
                .label(values -> "Support / resistance " + values.integer("period"))
                .compute(LevelIndicators::supportResistance));

        catalog.accept(define("PIVOT_POINTS_STANDARD", "Pivots", Category.LEVELS, Placement.PRICE)
                .inputs(InputSpec.choice("pivotType", "pivotType", "TRADITIONAL", "TRADITIONAL", "FIBONACCI", "WOODIE", "CLASSIC", "DM", "CAMARILLA"),
                        InputSpec.choice("pivotsTimeframe", "pivotsTimeframe", "AUTO", "AUTO", "WEEK", "MONTH", "QUARTER", "YEAR"),
                        InputSpec.integer("pivotsBack", 15, 1, 200), InputSpec.bool("showLabels", true))
                .shapes()
                .label(values -> "Pivots " + values.text("pivotType").charAt(0) + values.text("pivotType").substring(1).toLowerCase(Locale.ROOT))
                .compute(LevelIndicators::pivotPoints));

        catalog.accept(define("PIVOT_POINTS_HL", "Pivots HL", Category.LEVELS, Placement.PRICE)
                .inputs(InputSpec.integer("leftHigh", 10, 1, 100), InputSpec.integer("rightHigh", 10, 1, 100),
                        InputSpec.integer("leftLow", 10, 1, 100), InputSpec.integer("rightLow", 10, 1, 100))
                .outputs(OutputSpec.markers("pivotHigh", Palette.RED, "arrowDown", "aboveBar"),
                        OutputSpec.markers("pivotLow", Palette.MINT, "arrowUp", "belowBar"))
                .compute(context -> {
                    double[] high = context.high();
                    double[] low = context.low();
                    for (int index : pivotIndexes(high, context.integer("leftHigh"), context.integer("rightHigh"), true)) {
                        context.marker("pivotHigh", index, high[index], "{price}");
                    }
                    for (int index : pivotIndexes(low, context.integer("leftLow"), context.integer("rightLow"), false)) {
                        context.marker("pivotLow", index, low[index], "{price}");
                    }
                }));

        catalog.accept(define("AUTO_FIB_RETRACEMENT", "Auto Fib", Category.LEVELS, Placement.PRICE)
                .inputs(InputSpec.decimal("deviation", 3, 0.1, 20, 0.1), InputSpec.integer("depth", 10, 1, 100),
                        InputSpec.bool("reverse", false), InputSpec.bool("extendLeft", false), InputSpec.bool("extendRight", true))
                .shapes()
                .compute(context -> {
                    List<Pivot> pivots = swings(context);
                    if (pivots.size() < 2) return;
                    Pivot start = pivots.get(pivots.size() - 2);
                    Pivot end = pivots.getLast();
                    double from = context.bool("reverse") ? start.price() : end.price();
                    double to = context.bool("reverse") ? end.price() : start.price();
                    String extend = extend(context);
                    for (double level : RETRACEMENT_LEVELS) {
                        context.shape(Shape.level(start.index(), context.size() - 1, from + (to - from) * level, levelText(level), "fib", extend));
                    }
                    context.shape(Shape.segment(start.index(), start.price(), end.index(), end.price(), "fibTrend", "none"));
                }));

        catalog.accept(define("AUTO_FIB_EXTENSION", "Auto Fib Ext", Category.LEVELS, Placement.PRICE)
                .inputs(InputSpec.decimal("deviation", 3, 0.1, 20, 0.1), InputSpec.integer("depth", 10, 1, 100),
                        InputSpec.bool("extendLeft", false), InputSpec.bool("extendRight", true))
                .shapes()
                .compute(context -> {
                    List<Pivot> pivots = swings(context);
                    if (pivots.size() < 3) return;
                    Pivot a = pivots.get(pivots.size() - 3);
                    Pivot b = pivots.get(pivots.size() - 2);
                    Pivot c = pivots.getLast();
                    String extend = extend(context);
                    for (double level : EXTENSION_LEVELS) {
                        context.shape(Shape.level(c.index(), context.size() - 1, c.price() + (b.price() - a.price()) * level, levelText(level), "fib", extend));
                    }
                    context.shape(Shape.polyline(List.of(new IndicatorContext.ShapePoint(a.index(), a.price()),
                            new IndicatorContext.ShapePoint(b.index(), b.price()), new IndicatorContext.ShapePoint(c.index(), c.price())), "fibTrend"));
                }));

        catalog.accept(define("AUTO_PITCHFORK", "Auto Pitchfork", Category.LEVELS, Placement.PRICE)
                .inputs(InputSpec.decimal("deviation", 3, 0.1, 20, 0.1), InputSpec.integer("depth", 10, 1, 100),
                        InputSpec.choice("pitchforkType", "pitchforkType", "ORIGINAL", "ORIGINAL", "SCHIFF", "MODIFIED_SCHIFF"))
                .shapes()
                .compute(context -> {
                    List<Pivot> pivots = swings(context);
                    if (pivots.size() < 3) return;
                    Pivot p1 = pivots.get(pivots.size() - 3);
                    Pivot p2 = pivots.get(pivots.size() - 2);
                    Pivot p3 = pivots.getLast();
                    double startIndex = p1.index();
                    double startPrice = p1.price();
                    switch (context.text("pitchforkType")) {
                        case "SCHIFF" -> startPrice = (p1.price() + p2.price()) / 2;
                        case "MODIFIED_SCHIFF" -> {
                            startIndex = (p1.index() + p2.index()) / 2.0;
                            startPrice = (p1.price() + p2.price()) / 2;
                        }
                        default -> { }
                    }
                    double midIndex = (p2.index() + p3.index()) / 2.0;
                    double midPrice = (p2.price() + p3.price()) / 2;
                    double run = midIndex - startIndex;
                    if (run <= 0) return;
                    double slope = (midPrice - startPrice) / run;
                    int end = context.size() - 1;
                    context.shape(Shape.segment((int) Math.round(startIndex), startPrice, end, startPrice + slope * (end - startIndex), "median", "right"));
                    context.shape(Shape.segment(p2.index(), p2.price(), end, p2.price() + slope * (end - p2.index()), "upper", "right"));
                    context.shape(Shape.segment(p3.index(), p3.price(), end, p3.price() + slope * (end - p3.index()), "lower", "right"));
                    context.shape(Shape.segment(p2.index(), p2.price(), p3.index(), p3.price(), "handle", "none"));
                }));

        catalog.accept(define("AUTO_TRENDLINES", "Trendlines", Category.LEVELS, Placement.PRICE)
                .inputs(InputSpec.integer("length", 10, 2, 100))
                .shapes()
                .compute(context -> {
                    int length = context.integer("length");
                    double[] high = context.high();
                    double[] low = context.low();
                    List<Integer> highs = pivotIndexes(high, length, length, true);
                    List<Integer> lows = pivotIndexes(low, length, length, false);
                    int end = context.size() - 1;
                    if (highs.size() >= 2) {
                        int first = highs.get(highs.size() - 2);
                        int second = highs.getLast();
                        double slope = (high[second] - high[first]) / (second - first);
                        context.shape(Shape.segment(first, high[first], end, high[first] + slope * (end - first), "resistance", "right"));
                    }
                    if (lows.size() >= 2) {
                        int first = lows.get(lows.size() - 2);
                        int second = lows.getLast();
                        double slope = (low[second] - low[first]) / (second - first);
                        context.shape(Shape.segment(first, low[first], end, low[first] + slope * (end - first), "support", "right"));
                    }
                }));

        catalog.accept(define("FIFTY_TWO_WEEK_HIGH_LOW", "52W H/L", Category.LEVELS, Placement.PRICE)
                .outputs(OutputSpec.line("high", Palette.MINT).width(1).step(), OutputSpec.line("low", Palette.RED).width(1).step())
                .compute(context -> {
                    double[] high = context.high();
                    double[] low = context.low();
                    double[] highest = TaMath.nan(high.length);
                    double[] lowest = TaMath.nan(high.length);
                    int start = 0;
                    for (int index = 0; index < high.length; index++) {
                        long cutoff = context.time(index) - 364L * 86_400L;
                        while (context.time(start) <= cutoff) start++;
                        if (context.time(index) - context.time(0) < 360L * 86_400L) continue;
                        double max = Double.NEGATIVE_INFINITY;
                        double min = Double.POSITIVE_INFINITY;
                        for (int offset = start; offset <= index; offset++) { max = Math.max(max, high[offset]); min = Math.min(min, low[offset]); }
                        highest[index] = max;
                        lowest[index] = min;
                    }
                    context.output("high", highest);
                    context.output("low", lowest);
                }));
    }

    static String extend(IndicatorContext context) {
        boolean left = context.bool("extendLeft");
        boolean right = context.bool("extendRight");
        return left && right ? "both" : (left ? "left" : (right ? "right" : "none"));
    }

    static String levelText(double level) {
        String text = String.format(Locale.ROOT, "%.3f", level);
        while (text.endsWith("0") && text.contains(".")) text = text.substring(0, text.length() - 1);
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Swings from a zig zag whose reversal threshold scales with volatility (ATR 10 relative to price). */
    static List<Pivot> swings(IndicatorContext context) {
        double[] close = context.close();
        double[] atr = TaMath.atr(context.high(), context.low(), close, 10);
        double[] thresholds = new double[close.length];
        double multiplier = context.decimal("deviation");
        for (int index = 0; index < close.length; index++) {
            thresholds[index] = Double.isFinite(atr[index]) && close[index] > 0 ? multiplier * atr[index] / close[index] : Double.NaN;
        }
        return TrendIndicators.ZigZag.withThresholds(context.high(), context.low(), thresholds, context.integer("depth"));
    }

    /** Bars that are the strict extreme of {@code left} bars before and {@code right} bars after (ta.pivothigh/low). */
    static List<Integer> pivotIndexes(double[] values, int left, int right, boolean high) {
        List<Integer> result = new ArrayList<>();
        for (int index = left; index + right < values.length; index++) {
            boolean pivot = true;
            for (int offset = index - left; offset <= index + right && pivot; offset++) {
                if (offset == index) continue;
                pivot = high ? values[offset] < values[index] : values[offset] > values[index];
            }
            if (pivot) result.add(index);
        }
        return result;
    }

    static void supportResistance(IndicatorContext context) {
        int period = context.integer("period");
        double[] high = context.high();
        double[] low = context.low();
        ArrayDeque<Integer> lows = new ArrayDeque<>();
        ArrayDeque<Integer> highs = new ArrayDeque<>();
        double[] support = TaMath.nan(high.length);
        double[] resistance = TaMath.nan(high.length);
        for (int index = 0; index < high.length; index++) {
            while (!lows.isEmpty() && low[lows.getLast()] >= low[index]) lows.removeLast();
            while (!highs.isEmpty() && high[highs.getLast()] <= high[index]) highs.removeLast();
            lows.addLast(index);
            highs.addLast(index);
            int expired = index - period;
            while (!lows.isEmpty() && lows.getFirst() <= expired) lows.removeFirst();
            while (!highs.isEmpty() && highs.getFirst() <= expired) highs.removeFirst();
            if (index + 1 >= period) {
                support[index] = low[lows.getFirst()];
                resistance[index] = high[highs.getFirst()];
            }
        }
        context.output("support", support);
        context.output("resistance", resistance);
    }

    /** One calendar period of bars: its index range and OHLC. */
    record Period(LocalDate start, int first, int last, double open, double high, double low, double close) { }

    static List<Period> periods(IndicatorContext context, String period) {
        List<Period> result = new ArrayList<>();
        LocalDate current = null;
        int first = 0;
        double high = 0;
        double low = 0;
        for (int index = 0; index <= context.size(); index++) {
            LocalDate start = index < context.size() ? MarketCalendar.periodStart(context.time(index), period) : null;
            if (current != null && !current.equals(start)) {
                result.add(new Period(current, first, index - 1, context.open()[first], high, low, context.close()[index - 1]));
            }
            if (index == context.size()) break;
            if (!start.equals(current)) {
                current = start;
                first = index;
                high = context.high()[index];
                low = context.low()[index];
            } else {
                high = Math.max(high, context.high()[index]);
                low = Math.min(low, context.low()[index]);
            }
        }
        return result;
    }

    static String pivotPeriod(String requested, String interval) {
        if (!"AUTO".equals(requested)) return requested;
        return "1d".equals(interval) ? "MONTH" : "YEAR";
    }

    static void pivotPoints(IndicatorContext context) {
        if (context.size() == 0) return;
        String period = pivotPeriod(context.text("pivotsTimeframe"), context.interval());
        List<Period> periods = periods(context, period);
        int first = Math.max(1, periods.size() - context.integer("pivotsBack"));
        boolean labels = context.bool("showLabels");
        for (int position = first; position < periods.size(); position++) {
            Period previous = periods.get(position - 1);
            Period current = periods.get(position);
            int to;
            if (position + 1 < periods.size()) {
                to = periods.get(position + 1).first();
            } else {
                long end = MarketCalendar.timestamp(MarketCalendar.nextPeriodStart(current.start(), period));
                to = context.size() + MarketCalendar.barsUntil(context.time(context.size() - 1), context.interval(), end);
            }
            for (Map.Entry<String, Double> level : pivotLevels(context.text("pivotType"), previous, current.open()).entrySet()) {
                if (!Double.isFinite(level.getValue())) continue;
                String name = level.getKey();
                String role = name.startsWith("R") ? "resistance" : (name.startsWith("S") ? "support" : "pivot");
                context.shape(Shape.level(current.first(), to, level.getValue(), labels ? name : null, role, "none"));
            }
        }
    }

    static Map<String, Double> pivotLevels(String type, Period previous, double currentOpen) {
        double h = previous.high();
        double l = previous.low();
        double c = previous.close();
        double range = h - l;
        Map<String, Double> levels = new LinkedHashMap<>();
        switch (type) {
            case "FIBONACCI" -> {
                double p = (h + l + c) / 3;
                levels.put("P", p);
                levels.put("R1", p + 0.382 * range);
                levels.put("S1", p - 0.382 * range);
                levels.put("R2", p + 0.618 * range);
                levels.put("S2", p - 0.618 * range);
                levels.put("R3", p + range);
                levels.put("S3", p - range);
            }
            case "WOODIE" -> {
                double p = (h + l + 2 * currentOpen) / 4;
                levels.put("P", p);
                levels.put("R1", 2 * p - l);
                levels.put("S1", 2 * p - h);
                levels.put("R2", p + range);
                levels.put("S2", p - range);
                levels.put("R3", h + 2 * (p - l));
                levels.put("S3", l - 2 * (h - p));
                levels.put("R4", h + 2 * (p - l) + range);
                levels.put("S4", l - 2 * (h - p) - range);
            }
            case "CLASSIC" -> {
                double p = (h + l + c) / 3;
                levels.put("P", p);
                levels.put("R1", 2 * p - l);
                levels.put("S1", 2 * p - h);
                levels.put("R2", p + range);
                levels.put("S2", p - range);
                levels.put("R3", p + 2 * range);
                levels.put("S3", p - 2 * range);
                levels.put("R4", p + 3 * range);
                levels.put("S4", p - 3 * range);
            }
            case "DM" -> {
                double open = previous.open();
                double x = c < open ? h + 2 * l + c : (c > open ? 2 * h + l + c : h + l + 2 * c);
                levels.put("P", x / 4);
                levels.put("R1", x / 2 - l);
                levels.put("S1", x / 2 - h);
            }
            case "CAMARILLA" -> {
                levels.put("P", (h + l + c) / 3);
                levels.put("R1", c + 1.1 * range / 12);
                levels.put("S1", c - 1.1 * range / 12);
                levels.put("R2", c + 1.1 * range / 6);
                levels.put("S2", c - 1.1 * range / 6);
                levels.put("R3", c + 1.1 * range / 4);
                levels.put("S3", c - 1.1 * range / 4);
                levels.put("R4", c + 1.1 * range / 2);
                levels.put("S4", c - 1.1 * range / 2);
                if (l != 0) {
                    double r5 = h / l * c;
                    levels.put("R5", r5);
                    levels.put("S5", c - (r5 - c));
                }
            }
            default -> {
                double p = (h + l + c) / 3;
                levels.put("P", p);
                levels.put("R1", 2 * p - l);
                levels.put("S1", 2 * p - h);
                levels.put("R2", p + range);
                levels.put("S2", p - range);
                levels.put("R3", 2 * p + (h - 2 * l));
                levels.put("S3", 2 * p - (2 * h - l));
                levels.put("R4", 3 * p + (h - 3 * l));
                levels.put("S4", 3 * p - (3 * h - l));
                levels.put("R5", 4 * p + (h - 4 * l));
                levels.put("S5", 4 * p - (4 * h - l));
            }
        }
        return levels;
    }
}
