package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.ColorMode;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.FillSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;

import java.time.LocalDate;
import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Indicators that weigh price moves by traded volume. */
final class VolumeIndicators {
    private static final String[] SMOOTHING = {"NONE", "SMA", "EMA", "SMMA", "WMA"};
    static final String[] ANCHOR_PERIODS = {"WEEK", "MONTH", "QUARTER", "YEAR"};

    private VolumeIndicators() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("OBV", "OBV", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.choice("maType", "smoothing", "NONE", SMOOTHING), InputSpec.length("maLength", 9))
                .outputs(OutputSpec.line("value", Palette.BLUE), OutputSpec.line("ma", Palette.YELLOW).width(1))
                .label(values -> "On-balance volume")
                .compute(context -> {
                    double[] obv = context.values(new OnBalanceVolumeIndicator(context.bars()));
                    context.output("value", obv);
                    MomentumIndicators.smoothing(context, obv);
                }));

        catalog.accept(define("MFI", "MFI", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.period(14), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.VIOLET))
                .references(20, 50, 80)
                .label(values -> "Money Flow Index " + values.integer("period"))
                .compute(context -> context.output("value", new MoneyFlowIndexIndicator(context.bars(), context.integer("period")))));

        catalog.accept(define("RELATIVE_VOLUME", "RVOL", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.period(20))
                .outputs(OutputSpec.histogram("value", Palette.MINT, ColorMode.SIGN))
                .references(-1, 0, 1)
                .label(values -> "Relative volume " + values.integer("period"))
                .compute(context -> {
                    double[] volume = context.volume();
                    double[] average = context.values(new SMAIndicator(new VolumeIndicator(context.bars()), context.integer("period")));
                    double[] open = context.open();
                    double[] close = context.close();
                    double[] result = TaMath.nan(volume.length);
                    for (int index = 0; index < volume.length; index++) {
                        if (!Double.isFinite(average[index]) || average[index] == 0) continue;
                        result[index] = volume[index] / average[index] * (close[index] >= open[index] ? 1 : -1);
                    }
                    context.output("value", result);
                }));

        catalog.accept(define("VWAP", "VWAP", Category.VOLUME, Placement.PRICE)
                .inputs(InputSpec.period(20), InputSpec.source("hlc3"))
                .outputs(OutputSpec.line("value", Palette.PINK))
                .label(values -> "Rolling VWAP " + values.integer("period"))
                .compute(context -> {
                    int period = context.integer("period");
                    if ("hlc3".equals(context.text("source"))) {
                        context.output("value", new VWAPIndicator(new TypicalPriceIndicator(context.bars()), new VolumeIndicator(context.bars()), period));
                    } else {
                        context.output("value", TaMath.vwma(context.source(), context.volume(), period));
                    }
                }));

        catalog.accept(define("VWAP_PERIODIC", "VWAP", Category.VOLUME, Placement.PRICE)
                .inputs(InputSpec.choice("anchor", "anchorPeriod", "MONTH", ANCHOR_PERIODS), InputSpec.source("hlc3"),
                        InputSpec.bool("band1", true), InputSpec.decimal("multiplier1", 1, 0.1, 10, 0.1),
                        InputSpec.bool("band2", false), InputSpec.decimal("multiplier2", 2, 0.1, 10, 0.1),
                        InputSpec.bool("band3", false), InputSpec.decimal("multiplier3", 3, 0.1, 10, 0.1))
                .outputs(vwapOutputs())
                .fills(FillSpec.band("upper1", "lower1", Palette.MINT_FILL), FillSpec.band("upper2", "lower2", Palette.ORANGE_FILL),
                        FillSpec.band("upper3", "lower3", Palette.BLUE_FILL))
                .label(values -> "VWAP " + values.text("anchor").charAt(0) + values.text("anchor").substring(1).toLowerCase(java.util.Locale.ROOT))
                .compute(context -> {
                    int size = context.size();
                    boolean[] resets = new boolean[size];
                    LocalDate current = null;
                    for (int index = 0; index < size; index++) {
                        LocalDate period = MarketCalendar.periodStart(context.time(index), context.text("anchor"));
                        resets[index] = !period.equals(current);
                        current = period;
                    }
                    anchoredVwap(context, resets, 0);
                }));

        catalog.accept(define("ANCHORED_VWAP", "AVWAP", Category.VOLUME, Placement.PRICE)
                .inputs(InputSpec.timestamp("anchor"), InputSpec.source("hlc3"),
                        InputSpec.bool("band1", true), InputSpec.decimal("multiplier1", 1, 0.1, 10, 0.1),
                        InputSpec.bool("band2", false), InputSpec.decimal("multiplier2", 2, 0.1, 10, 0.1),
                        InputSpec.bool("band3", false), InputSpec.decimal("multiplier3", 3, 0.1, 10, 0.1))
                .outputs(vwapOutputs())
                .fills(FillSpec.band("upper1", "lower1", Palette.MINT_FILL), FillSpec.band("upper2", "lower2", Palette.ORANGE_FILL),
                        FillSpec.band("upper3", "lower3", Palette.BLUE_FILL))
                .label(values -> "Anchored VWAP")
                .compute(context -> {
                    long anchor = context.inputs().timestamp("anchor");
                    int start = 0;
                    while (start < context.size() && context.time(start) < anchor) start++;
                    boolean[] resets = new boolean[context.size()];
                    if (start < context.size()) resets[start] = true;
                    anchoredVwap(context, resets, start);
                }));

        catalog.accept(define("ACCUMULATION_DISTRIBUTION", "Accum/Dist", Category.VOLUME, Placement.PANEL)
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .compute(context -> context.output("value", TaMath.cum(moneyFlowVolume(context)))));

        catalog.accept(define("CHAIKIN_MONEY_FLOW", "CMF", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.integer("period", 20, 2, 500))
                .outputs(OutputSpec.histogram("value", Palette.MINT, ColorMode.SIGN))
                .references(0)
                .compute(context -> {
                    int period = context.integer("period");
                    context.output("value", TaMath.divide(TaMath.sum(moneyFlowVolume(context), period), TaMath.sum(context.volume(), period)));
                }));

        catalog.accept(define("CHAIKIN_OSCILLATOR", "Chaikin Osc", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.length("fast", 3), InputSpec.length("slow", 10))
                .outputs(OutputSpec.line("value", Palette.RED))
                .references(0)
                .compute(context -> {
                    double[] ad = TaMath.cum(moneyFlowVolume(context));
                    context.output("value", TaMath.subtract(TaMath.ema(ad, context.integer("fast")), TaMath.ema(ad, context.integer("slow"))));
                }));

        catalog.accept(define("ELDER_FORCE_INDEX", "EFI", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.length("period", 13))
                .outputs(OutputSpec.line("value", Palette.RED))
                .references(0)
                .compute(context -> context.output("value",
                        TaMath.ema(TaMath.multiply(TaMath.change(context.close(), 1), context.volume()), context.integer("period")))));

        catalog.accept(define("EASE_OF_MOVEMENT", "EOM", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.length("period", 14), InputSpec.integer("divisor", 10000, 1, 1_000_000_000))
                .outputs(OutputSpec.line("value", Palette.MINT))
                .references(0)
                .compute(context -> {
                    double[] median = context.price("hl2");
                    double[] change = TaMath.change(median, 1);
                    double[] high = context.high();
                    double[] low = context.low();
                    double[] volume = context.volume();
                    double[] raw = TaMath.nan(median.length);
                    for (int index = 0; index < raw.length; index++) {
                        if (volume[index] > 0 && Double.isFinite(change[index])) {
                            raw[index] = context.integer("divisor") * change[index] * (high[index] - low[index]) / volume[index];
                        }
                    }
                    context.output("value", TaMath.sma(raw, context.integer("period")));
                }));

        catalog.accept(define("KLINGER_OSCILLATOR", "KVO", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.length("fast", 34), InputSpec.length("slow", 55), InputSpec.length("signal", 13))
                .outputs(OutputSpec.line("value", Palette.BLUE), OutputSpec.line("signal", Palette.MINT))
                .references(0)
                .compute(context -> {
                    double[] change = TaMath.change(context.price("hlc3"), 1);
                    double[] volume = context.volume();
                    double[] signed = TaMath.nan(volume.length);
                    for (int index = 0; index < volume.length; index++) {
                        if (Double.isFinite(change[index])) signed[index] = change[index] >= 0 ? volume[index] : -volume[index];
                    }
                    double[] kvo = TaMath.subtract(TaMath.ema(signed, context.integer("fast")), TaMath.ema(signed, context.integer("slow")));
                    context.output("value", kvo);
                    context.output("signal", TaMath.ema(kvo, context.integer("signal")));
                }));

        catalog.accept(volumeIndex("NEGATIVE_VOLUME_INDEX", "NVI", true));
        catalog.accept(volumeIndex("POSITIVE_VOLUME_INDEX", "PVI", false));

        catalog.accept(define("PRICE_VOLUME_TREND", "PVT", Category.VOLUME, Placement.PANEL)
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .compute(context -> {
                    double[] close = context.close();
                    double[] flow = TaMath.nan(close.length);
                    for (int index = 1; index < close.length; index++) {
                        if (close[index - 1] != 0) flow[index] = (close[index] - close[index - 1]) / close[index - 1] * context.volume()[index];
                    }
                    context.output("value", TaMath.cum(flow));
                }));

        catalog.accept(define("VOLUME_OSCILLATOR", "Vol Osc", Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.length("fast", 5), InputSpec.length("slow", 10))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(0)
                .compute(context -> {
                    double[] fast = TaMath.ema(context.volume(), context.integer("fast"));
                    double[] slow = TaMath.ema(context.volume(), context.integer("slow"));
                    context.output("value", TaMath.scale(TaMath.divide(TaMath.subtract(fast, slow), slow), 100));
                }));

        catalog.accept(define("VOLUME_MA", "Volume MA", Category.VOLUME, Placement.VOLUME)
                .inputs(InputSpec.length("period", 20), InputSpec.choice("maType", "maType", "SMA", "SMA", "EMA", "WMA"))
                .outputs(OutputSpec.line("value", Palette.ORANGE).width(1))
                .compute(context -> context.output("value", context.ma(context.text("maType"), context.volume(), context.integer("period")))));
    }

    private static OutputSpec[] vwapOutputs() {
        return new OutputSpec[]{
                OutputSpec.line("vwap", Palette.BLUE),
                OutputSpec.line("upper1", Palette.MINT).width(1), OutputSpec.line("lower1", Palette.MINT).width(1),
                OutputSpec.line("upper2", Palette.ORANGE).width(1), OutputSpec.line("lower2", Palette.ORANGE).width(1),
                OutputSpec.line("upper3", Palette.BLUE).width(1), OutputSpec.line("lower3", Palette.BLUE).width(1)};
    }

    /** Volume-weighted average and standard-deviation bands that restart wherever {@code resets} is set. */
    static void anchoredVwap(IndicatorContext context, boolean[] resets, int firstIndex) {
        double[] source = context.source();
        double[] volume = context.volume();
        int size = source.length;
        double[] vwap = TaMath.nan(size);
        double[] deviation = TaMath.nan(size);
        double sumVolume = 0;
        double sumPrice = 0;
        double sumSquares = 0;
        boolean active = false;
        for (int index = firstIndex; index < size; index++) {
            if (resets[index]) { sumVolume = 0; sumPrice = 0; sumSquares = 0; active = true; }
            if (!active) continue;
            sumVolume += volume[index];
            sumPrice += source[index] * volume[index];
            sumSquares += source[index] * source[index] * volume[index];
            if (sumVolume <= 0) { vwap[index] = source[index]; deviation[index] = 0; continue; }
            double mean = sumPrice / sumVolume;
            vwap[index] = mean;
            deviation[index] = Math.sqrt(Math.max(0, sumSquares / sumVolume - mean * mean));
        }
        context.output("vwap", vwap);
        for (int band = 1; band <= 3; band++) {
            if (!context.bool("band" + band)) continue;
            double[] width = TaMath.scale(deviation, context.decimal("multiplier" + band));
            context.output("upper" + band, TaMath.add(vwap, width));
            context.output("lower" + band, TaMath.subtract(vwap, width));
        }
    }

    /** Money-flow volume per bar: volume scaled by where the close sits in the bar's range. */
    static double[] moneyFlowVolume(IndicatorContext context) {
        double[] high = context.high();
        double[] low = context.low();
        double[] close = context.close();
        double[] volume = context.volume();
        double[] result = new double[close.length];
        for (int index = 0; index < close.length; index++) {
            double range = high[index] - low[index];
            result[index] = range == 0 ? 0 : ((close[index] - low[index]) - (high[index] - close[index])) / range * volume[index];
        }
        return result;
    }

    private static IndicatorDefinition volumeIndex(String type, String shortName, boolean negative) {
        return define(type, shortName, Category.VOLUME, Placement.PANEL)
                .inputs(InputSpec.integer("emaLength", 255, 2, 1000))
                .outputs(OutputSpec.line("value", Palette.BLUE), OutputSpec.line("ema", Palette.ORANGE).width(1))
                .compute(context -> {
                    double[] close = context.close();
                    double[] volume = context.volume();
                    double[] index = new double[close.length];
                    double value = 1000;
                    for (int bar = 0; bar < close.length; bar++) {
                        if (bar > 0 && close[bar - 1] != 0) {
                            boolean move = negative ? volume[bar] < volume[bar - 1] : volume[bar] > volume[bar - 1];
                            if (move) value += value * (close[bar] - close[bar - 1]) / close[bar - 1];
                        }
                        index[bar] = value;
                    }
                    context.output("value", index);
                    context.output("ema", TaMath.ema(index, context.integer("emaLength")));
                });
    }
}
