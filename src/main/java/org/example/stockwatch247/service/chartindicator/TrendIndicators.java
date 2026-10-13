package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.IndicatorContext.Shape;
import org.example.stockwatch247.service.chartindicator.IndicatorContext.ShapePoint;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.ColorMode;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.FillSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.trend.DownTrendIndicator;
import org.ta4j.core.indicators.trend.UpTrendIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Trend-following overlays and trend-strength panels. */
final class TrendIndicators {
    private TrendIndicators() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("ICHIMOKU", "Ichimoku", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.length("conversion", 9), InputSpec.length("base", 26), InputSpec.length("spanB", 52),
                        InputSpec.length("displacement", 26))
                .outputs(OutputSpec.line("conversion", Palette.BLUE).width(1), OutputSpec.line("base", Palette.RED).width(1),
                        OutputSpec.line("lagging", Palette.MINT).width(1), OutputSpec.line("spanA", Palette.MINT).width(1),
                        OutputSpec.line("spanB", Palette.RED).width(1))
                .fills(FillSpec.cloud("spanA", "spanB", Palette.MINT_FILL, Palette.RED_FILL))
                .compute(context -> {
                    double[] conversion = donchianMid(context, context.integer("conversion"));
                    double[] base = donchianMid(context, context.integer("base"));
                    double[] spanA = new double[conversion.length];
                    for (int index = 0; index < spanA.length; index++) spanA[index] = (conversion[index] + base[index]) / 2;
                    int displacement = context.integer("displacement");
                    context.output("conversion", conversion);
                    context.output("base", base);
                    context.output("lagging", context.close().clone());
                    context.output("spanA", spanA);
                    context.output("spanB", donchianMid(context, context.integer("spanB")));
                    context.shift("lagging", -displacement + 1);
                    context.shift("spanA", displacement - 1);
                    context.shift("spanB", displacement - 1);
                }));

        catalog.accept(define("PARABOLIC_SAR", "SAR", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.decimal("start", 0.02, 0.001, 1, 0.001), InputSpec.decimal("increment", 0.02, 0.001, 1, 0.001),
                        InputSpec.decimal("maximum", 0.2, 0.01, 1, 0.01))
                .outputs(OutputSpec.dots("value", Palette.BLUE).tones(Palette.MINT, Palette.RED))
                .validate(values -> {
                    if (values.decimal("start") > values.decimal("maximum")) throw new UserInputException("The SAR start must not exceed its maximum.");
                })
                .compute(context -> parabolicSar(context, context.decimal("start"), context.decimal("increment"), context.decimal("maximum"))));

        catalog.accept(define("SUPERTREND", "Supertrend", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.length("atrPeriod", 10), InputSpec.decimal("factor", 3, 0.1, 20, 0.1))
                .outputs(OutputSpec.line("up", Palette.MINT).withBreaks(), OutputSpec.line("down", Palette.RED).withBreaks(),
                        OutputSpec.line("middle", Palette.TAN).hidden())
                .fills(FillSpec.band("up", "middle", Palette.MINT_FILL), FillSpec.band("down", "middle", Palette.RED_FILL))
                .compute(context -> superTrend(context, context.integer("atrPeriod"), context.decimal("factor"))));

        catalog.accept(define("ADX_DMI", "ADX / DMI", Category.TREND, Placement.PANEL)
                .inputs(InputSpec.period(14), InputSpec.length("smoothing", 14), InputSpec.timeframe())
                .outputs(OutputSpec.line("adx", Palette.RED), OutputSpec.line("plusDi", Palette.BLUE), OutputSpec.line("minusDi", Palette.ORANGE))
                .references(20, 25)
                .compute(context -> {
                    int period = context.integer("period");
                    context.output("adx", new ADXIndicator(context.bars(), period, context.integer("smoothing")));
                    context.output("plusDi", new PlusDIIndicator(context.bars(), period));
                    context.output("minusDi", new MinusDIIndicator(context.bars(), period));
                }));

        catalog.accept(define("AROON", "Aroon", Category.TREND, Placement.PANEL)
                .inputs(InputSpec.length("period", 14))
                .outputs(OutputSpec.line("up", Palette.ORANGE), OutputSpec.line("down", Palette.BLUE))
                .references(30, 70)
                .compute(context -> {
                    int period = context.integer("period");
                    double[] highBars = TaMath.highestBars(context.high(), period + 1);
                    double[] lowBars = TaMath.lowestBars(context.low(), period + 1);
                    context.output("up", TaMath.scale(TaMath.offset(highBars, period), 100.0 / period));
                    context.output("down", TaMath.scale(TaMath.offset(lowBars, period), 100.0 / period));
                }));

        catalog.accept(define("AROON_OSCILLATOR", "Aroon Osc", Category.TREND, Placement.PANEL)
                .inputs(InputSpec.length("period", 14))
                .outputs(OutputSpec.histogram("value", Palette.VIOLET, ColorMode.SIGN))
                .references(-50, 0, 50)
                .compute(context -> {
                    int period = context.integer("period");
                    double[] up = TaMath.highestBars(context.high(), period + 1);
                    double[] down = TaMath.lowestBars(context.low(), period + 1);
                    context.output("value", TaMath.scale(TaMath.subtract(up, down), 100.0 / period));
                }));

        catalog.accept(define("VORTEX", "VI", Category.TREND, Placement.PANEL)
                .inputs(InputSpec.integer("period", 14, 2, 500))
                .outputs(OutputSpec.line("plus", Palette.BLUE), OutputSpec.line("minus", Palette.RED))
                .references(1)
                .compute(context -> {
                    int period = context.integer("period");
                    double[] high = context.high();
                    double[] low = context.low();
                    double[] plus = TaMath.nan(high.length);
                    double[] minus = TaMath.nan(high.length);
                    for (int index = 1; index < high.length; index++) {
                        plus[index] = Math.abs(high[index] - low[index - 1]);
                        minus[index] = Math.abs(low[index] - high[index - 1]);
                    }
                    double[] range = TaMath.sum(TaMath.trueRange(high, low, context.close()), period);
                    context.output("plus", TaMath.divide(TaMath.sum(plus, period), range));
                    context.output("minus", TaMath.divide(TaMath.sum(minus, period), range));
                }));

        catalog.accept(define("ALLIGATOR", "Alligator", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.length("jawLength", 13), InputSpec.length("teethLength", 8), InputSpec.length("lipsLength", 5),
                        InputSpec.integer("jawOffset", 8, -100, 100), InputSpec.integer("teethOffset", 5, -100, 100),
                        InputSpec.integer("lipsOffset", 3, -100, 100))
                .outputs(OutputSpec.line("jaw", Palette.BLUE), OutputSpec.line("teeth", Palette.RED), OutputSpec.line("lips", Palette.MINT))
                .compute(context -> {
                    double[] median = context.price("hl2");
                    for (String part : List.of("jaw", "teeth", "lips")) {
                        context.output(part, TaMath.rma(median, context.integer(part + "Length")));
                        context.shift(part, context.integer(part + "Offset"));
                    }
                }));

        catalog.accept(define("ZIG_ZAG", "Zig Zag", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.decimal("deviation", 5, 0.1, 100, 0.1), InputSpec.integer("depth", 10, 1, 100),
                        InputSpec.bool("showLabels", true))
                .shapes()
                .compute(context -> {
                    List<Pivot> pivots = ZigZag.percent(context.high(), context.low(), context.decimal("deviation") / 100, context.integer("depth"));
                    List<ShapePoint> line = new ArrayList<>();
                    pivots.forEach(pivot -> line.add(new ShapePoint(pivot.index(), pivot.price())));
                    if (line.size() >= 2) context.shape(Shape.polyline(line, "zigzag"));
                    if (context.bool("showLabels")) {
                        for (int index = 1; index < pivots.size(); index++) {
                            Pivot previous = pivots.get(index - 1);
                            Pivot pivot = pivots.get(index);
                            double change = previous.price() == 0 ? 0 : (pivot.price() - previous.price()) / previous.price() * 100;
                            context.shape(new Shape("label", List.of(new ShapePoint(pivot.index(), pivot.price())), null,
                                    pivot.high() ? "pivotHigh" : "pivotLow", null, Map.of("price", pivot.price(), "percent", change)));
                        }
                    }
                }));

        catalog.accept(define("WILLIAMS_FRACTALS", "Fractals", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.integer("periods", 2, 1, 20))
                .outputs(OutputSpec.markers("upFractal", Palette.MINT, "arrowDown", "aboveBar"),
                        OutputSpec.markers("downFractal", Palette.RED, "arrowUp", "belowBar"))
                .compute(context -> {
                    int periods = context.integer("periods");
                    double[] high = context.high();
                    double[] low = context.low();
                    for (int index = periods; index + periods < high.length; index++) {
                        boolean up = true;
                        boolean down = true;
                        for (int offset = 1; offset <= periods; offset++) {
                            up &= high[index] > high[index - offset] && high[index] > high[index + offset];
                            down &= low[index] < low[index - offset] && low[index] < low[index + offset];
                        }
                        if (up) context.marker("upFractal", index, high[index], null);
                        if (down) context.marker("downFractal", index, low[index], null);
                    }
                }));

        catalog.accept(define("CHANDELIER_EXIT", "Chandelier", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.length("period", 22), InputSpec.decimal("multiplier", 3, 0.1, 20, 0.1), InputSpec.bool("useClose", true))
                .outputs(OutputSpec.line("longStop", Palette.MINT).withBreaks(), OutputSpec.line("shortStop", Palette.RED).withBreaks())
                .compute(TrendIndicators::chandelierExit));

        catalog.accept(define("VOLATILITY_STOP", "VStop", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.length("period", 20), InputSpec.decimal("multiplier", 2, 0.1, 20, 0.1), InputSpec.source("close"))
                .outputs(OutputSpec.dots("value", Palette.MINT).tones(Palette.MINT, Palette.RED))
                .compute(TrendIndicators::volatilityStop));

        catalog.accept(define("CHANDE_KROLL_STOP", "CK Stop", Category.TREND, Placement.PRICE)
                .inputs(InputSpec.length("p", 10), InputSpec.decimal("x", 1, 0.1, 20, 0.1), InputSpec.length("q", 9))
                .outputs(OutputSpec.line("stopShort", Palette.RED).width(1), OutputSpec.line("stopLong", Palette.MINT).width(1))
                .compute(context -> {
                    int p = context.integer("p");
                    double[] atr = TaMath.atr(context.high(), context.low(), context.close(), p);
                    double[] highStop = TaMath.subtract(TaMath.highest(context.high(), p), TaMath.scale(atr, context.decimal("x")));
                    double[] lowStop = TaMath.add(TaMath.lowest(context.low(), p), TaMath.scale(atr, context.decimal("x")));
                    context.output("stopShort", TaMath.highest(highStop, context.integer("q")));
                    context.output("stopLong", TaMath.lowest(lowStop, context.integer("q")));
                }));

        catalog.accept(define("CHOPPINESS", "CHOP", Category.TREND, Placement.PANEL)
                .inputs(InputSpec.integer("period", 14, 2, 500))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(38.2, 61.8)
                .compute(context -> {
                    int period = context.integer("period");
                    double[] range = TaMath.subtract(TaMath.highest(context.high(), period), TaMath.lowest(context.low(), period));
                    double[] ratio = TaMath.divide(TaMath.sum(TaMath.trueRange(context.high(), context.low(), context.close()), period), range);
                    double[] result = TaMath.nan(ratio.length);
                    for (int index = 0; index < ratio.length; index++) {
                        if (Double.isFinite(ratio[index]) && ratio[index] > 0) result[index] = 100 * Math.log10(ratio[index]) / Math.log10(period);
                    }
                    context.output("value", result);
                }));

        catalog.accept(define("CHOP_ZONE", "Chop Zone", Category.TREND, Placement.PANEL)
                .outputs(OutputSpec.columns("value", Palette.YELLOW, ColorMode.TONE)
                        .tones("#26c6da", "#43a047", "#a5d6a7", "#009688", "#d50000", "#e91e63", "#ff6d00", "#ffb74d", "#fdd835"))
                .compute(TrendIndicators::chopZone));

        catalog.accept(define("TREND", "Trend state", Category.TREND, Placement.PANEL)
                .inputs(InputSpec.period(20))
                .outputs(OutputSpec.line("value", Palette.VIOLET).step())
                .references(-1, 0, 1)
                .label(values -> "TA4J trend state " + values.integer("period"))
                .compute(context -> {
                    int period = context.integer("period");
                    UpTrendIndicator up = new UpTrendIndicator(context.bars(), period);
                    DownTrendIndicator down = new DownTrendIndicator(context.bars(), period);
                    double[] result = new double[context.size()];
                    for (int index = 0; index < result.length; index++) {
                        boolean rising = Boolean.TRUE.equals(up.getValue(index));
                        boolean falling = Boolean.TRUE.equals(down.getValue(index));
                        result[index] = rising == falling ? 0 : (rising ? 1 : -1);
                    }
                    context.output("value", result);
                }));
    }

    static double[] donchianMid(IndicatorContext context, int length) {
        double[] highest = TaMath.highest(context.high(), length);
        double[] lowest = TaMath.lowest(context.low(), length);
        double[] result = new double[highest.length];
        for (int index = 0; index < result.length; index++) result[index] = (highest[index] + lowest[index]) / 2;
        return result;
    }

    /** TradingView's ta.sar, translated line by line. */
    static void parabolicSar(IndicatorContext context, double start, double increment, double maximum) {
        double[] high = context.high();
        double[] low = context.low();
        double[] close = context.close();
        double[] result = TaMath.nan(close.length);
        int[] tones = new int[close.length];
        double sar = Double.NaN;
        double extreme = Double.NaN;
        double acceleration = Double.NaN;
        boolean below = false;
        for (int index = 1; index < close.length; index++) {
            boolean firstTrendBar = false;
            if (index == 1) {
                if (close[1] > close[0]) { below = true; extreme = high[1]; sar = low[0]; }
                else { below = false; extreme = low[1]; sar = high[0]; }
                firstTrendBar = true;
                acceleration = start;
            }
            sar = sar + acceleration * (extreme - sar);
            if (below) {
                if (sar > low[index]) {
                    firstTrendBar = true;
                    below = false;
                    sar = Math.max(high[index], extreme);
                    extreme = low[index];
                    acceleration = start;
                }
            } else if (sar < high[index]) {
                firstTrendBar = true;
                below = true;
                sar = Math.min(low[index], extreme);
                extreme = high[index];
                acceleration = start;
            }
            if (!firstTrendBar) {
                if (below) {
                    if (high[index] > extreme) { extreme = high[index]; acceleration = Math.min(acceleration + increment, maximum); }
                } else if (low[index] < extreme) {
                    extreme = low[index];
                    acceleration = Math.min(acceleration + increment, maximum);
                }
            }
            if (below) {
                sar = Math.min(sar, low[index - 1]);
                if (index > 1) sar = Math.min(sar, low[index - 2]);
            } else {
                sar = Math.max(sar, high[index - 1]);
                if (index > 1) sar = Math.max(sar, high[index - 2]);
            }
            result[index] = sar;
            tones[index] = below ? 0 : 1;
        }
        context.output("value", result);
        context.tones("value", tones);
    }

    /** TradingView's ta.supertrend: direction -1 is an uptrend drawn on the lower band. */
    static void superTrend(IndicatorContext context, int atrPeriod, double factor) {
        double[] high = context.high();
        double[] low = context.low();
        double[] close = context.close();
        double[] open = context.open();
        double[] atr = TaMath.atr(high, low, close, atrPeriod);
        int size = close.length;
        double[] up = TaMath.nan(size);
        double[] down = TaMath.nan(size);
        double[] middle = new double[size];
        double previousLower = 0;
        double previousUpper = 0;
        double previousTrend = Double.NaN;
        for (int index = 0; index < size; index++) {
            middle[index] = (open[index] + close[index]) / 2;
            if (!Double.isFinite(atr[index])) continue;
            double source = (high[index] + low[index]) / 2;
            double upper = source + factor * atr[index];
            double lower = source - factor * atr[index];
            double previousClose = index > 0 ? close[index - 1] : close[index];
            lower = lower > previousLower || previousClose < previousLower ? lower : previousLower;
            upper = upper < previousUpper || previousClose > previousUpper ? upper : previousUpper;
            int direction;
            if (index == 0 || !Double.isFinite(atr[index - 1])) direction = 1;
            else if (previousTrend == previousUpper) direction = close[index] > upper ? -1 : 1;
            else direction = close[index] < lower ? 1 : -1;
            double trend = direction == -1 ? lower : upper;
            if (direction == -1) up[index] = trend; else down[index] = trend;
            previousTrend = trend;
            previousLower = lower;
            previousUpper = upper;
        }
        context.output("up", up);
        context.output("down", down);
        context.output("middle", middle);
    }

    static void chandelierExit(IndicatorContext context) {
        int period = context.integer("period");
        double multiplier = context.decimal("multiplier");
        boolean useClose = context.bool("useClose");
        double[] close = context.close();
        double[] atr = TaMath.scale(TaMath.atr(context.high(), context.low(), close, period), multiplier);
        double[] highest = TaMath.highest(useClose ? close : context.high(), period);
        double[] lowest = TaMath.lowest(useClose ? close : context.low(), period);
        int size = close.length;
        double[] longOut = TaMath.nan(size);
        double[] shortOut = TaMath.nan(size);
        double longStop = Double.NaN;
        double shortStop = Double.NaN;
        int direction = 1;
        for (int index = 0; index < size; index++) {
            if (!Double.isFinite(atr[index]) || !Double.isFinite(highest[index])) continue;
            double longCandidate = highest[index] - atr[index];
            double shortCandidate = lowest[index] + atr[index];
            double longPrevious = Double.isFinite(longStop) ? longStop : longCandidate;
            double shortPrevious = Double.isFinite(shortStop) ? shortStop : shortCandidate;
            double previousClose = index > 0 ? close[index - 1] : close[index];
            longStop = previousClose > longPrevious ? Math.max(longCandidate, longPrevious) : longCandidate;
            shortStop = previousClose < shortPrevious ? Math.min(shortCandidate, shortPrevious) : shortCandidate;
            if (close[index] > shortPrevious) direction = 1;
            else if (close[index] < longPrevious) direction = -1;
            if (direction == 1) longOut[index] = longStop; else shortOut[index] = shortStop;
        }
        context.output("longStop", longOut);
        context.output("shortStop", shortOut);
    }

    /** TradingView's built-in Volatility Stop. */
    static void volatilityStop(IndicatorContext context) {
        double[] source = context.source();
        double[] range = TaMath.trueRange(context.high(), context.low(), context.close());
        double[] atr = TaMath.atr(context.high(), context.low(), context.close(), context.integer("period"));
        double multiplier = context.decimal("multiplier");
        int size = source.length;
        double[] result = TaMath.nan(size);
        int[] tones = new int[size];
        double max = Double.NaN;
        double min = Double.NaN;
        double stop = Double.NaN;
        boolean uptrend = true;
        boolean previousUptrend = true;
        for (int index = 0; index < size; index++) {
            double value = source[index];
            if (!Double.isFinite(value)) continue;
            if (!Double.isFinite(max)) { max = value; min = value; }
            double atrM = Double.isFinite(atr[index]) ? atr[index] * multiplier : range[index];
            max = Math.max(max, value);
            min = Math.min(min, value);
            double candidate = uptrend ? (Double.isFinite(stop) ? Math.max(stop, max - atrM) : max - atrM)
                    : (Double.isFinite(stop) ? Math.min(stop, min + atrM) : min + atrM);
            stop = Double.isFinite(candidate) ? candidate : value;
            uptrend = value - stop >= 0;
            if (uptrend != previousUptrend) {
                max = value;
                min = value;
                stop = uptrend ? max - atrM : min + atrM;
            }
            previousUptrend = uptrend;
            result[index] = stop;
            tones[index] = uptrend ? 0 : 1;
        }
        context.output("value", result);
        context.tones("value", tones);
    }

    /** TradingView's Chop Zone: a constant column coloured by the angle of a 34-period EMA. */
    static void chopZone(IndicatorContext context) {
        double[] close = context.close();
        double[] typical = context.price("hlc3");
        double[] highest = TaMath.highest(context.high(), 30);
        double[] lowest = TaMath.lowest(context.low(), 30);
        double[] ema = TaMath.ema(close, 34);
        double[] values = TaMath.nan(close.length);
        int[] tones = new int[close.length];
        for (int index = 1; index < close.length; index++) {
            if (!Double.isFinite(ema[index]) || !Double.isFinite(ema[index - 1]) || !Double.isFinite(highest[index])) continue;
            double range = highest[index] - lowest[index];
            if (range == 0 || typical[index] == 0) continue;
            double span = 25 / range * lowest[index];
            double y = (ema[index - 1] - ema[index]) / typical[index] * span;
            double hypotenuse = Math.sqrt(1 + y * y);
            double angle = Math.round(180 * Math.acos(1 / hypotenuse) / Math.PI);
            if (y > 0) angle = -angle;
            int tone;
            if (angle >= 5) tone = 0;
            else if (angle >= 3.57) tone = 1;
            else if (angle >= 2.14) tone = 2;
            else if (angle >= 0.71) tone = 3;
            else if (angle <= -5) tone = 4;
            else if (angle <= -3.57) tone = 5;
            else if (angle <= -2.14) tone = 6;
            else if (angle <= -0.71) tone = 7;
            else tone = 8;
            values[index] = 1;
            tones[index] = tone;
        }
        context.output("value", values);
        context.tones("value", tones);
    }

    record Pivot(int index, double price, boolean high) { }

    /** Swing detection shared by the zig zag and the automatic Fibonacci, pitchfork and trend-line tools. */
    static final class ZigZag {
        private ZigZag() { }

        /**
         * Alternating swing highs and lows: a swing is confirmed once price reverses by the threshold
         * (a fraction of the swing price) and at least {@code depth} bars separate it from the previous
         * swing. The final, still-developing extreme is included so the line reaches the latest bars.
         */
        static List<Pivot> percent(double[] high, double[] low, double threshold, int depth) {
            double[] thresholds = new double[high.length];
            java.util.Arrays.fill(thresholds, threshold);
            return withThresholds(high, low, thresholds, depth);
        }

        static List<Pivot> withThresholds(double[] high, double[] low, double[] thresholds, int depth) {
            List<Pivot> pivots = new ArrayList<>();
            if (high.length == 0) return pivots;
            int trend = 0;
            int extremeIndex = 0;
            double extreme = high[0];
            int lowIndex = 0;
            int highIndex = 0;
            for (int index = 1; index < high.length; index++) {
                double threshold = Double.isFinite(thresholds[index]) ? thresholds[index] : thresholds[thresholds.length - 1];
                if (!Double.isFinite(threshold)) continue;
                if (trend == 0) {
                    if (high[index] > high[highIndex]) highIndex = index;
                    if (low[index] < low[lowIndex]) lowIndex = index;
                    if (low[lowIndex] > 0 && high[index] / low[lowIndex] - 1 >= threshold && index - lowIndex >= 1) {
                        pivots.add(new Pivot(lowIndex, low[lowIndex], false));
                        trend = 1;
                        extremeIndex = index;
                        extreme = high[index];
                    } else if (high[highIndex] > 0 && 1 - low[index] / high[highIndex] >= threshold && index - highIndex >= 1) {
                        pivots.add(new Pivot(highIndex, high[highIndex], true));
                        trend = -1;
                        extremeIndex = index;
                        extreme = low[index];
                    }
                    continue;
                }
                if (trend == 1) {
                    if (high[index] >= extreme) { extreme = high[index]; extremeIndex = index; }
                    else if (extreme > 0 && 1 - low[index] / extreme >= threshold && extremeIndex - pivots.getLast().index() >= depth) {
                        pivots.add(new Pivot(extremeIndex, extreme, true));
                        trend = -1;
                        extreme = low[index];
                        extremeIndex = index;
                    }
                } else {
                    if (low[index] <= extreme) { extreme = low[index]; extremeIndex = index; }
                    else if (extreme > 0 && high[index] / extreme - 1 >= threshold && extremeIndex - pivots.getLast().index() >= depth) {
                        pivots.add(new Pivot(extremeIndex, extreme, false));
                        trend = 1;
                        extreme = high[index];
                        extremeIndex = index;
                    }
                }
            }
            if (trend != 0 && extremeIndex > pivots.getLast().index()) pivots.add(new Pivot(extremeIndex, extreme, trend == 1));
            return pivots;
        }
    }
}
