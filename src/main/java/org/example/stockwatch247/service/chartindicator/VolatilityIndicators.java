package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.ColorMode;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.FillSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.donchian.DonchianChannelFacade;
import org.ta4j.core.indicators.keltner.KeltnerChannelFacade;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Bands, channels and measures of price dispersion. */
final class VolatilityIndicators {
    private static final String[] BAND_AVERAGES = {"SMA", "EMA", "SMMA", "WMA", "VWMA"};

    private VolatilityIndicators() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("BOLLINGER", "BB", Category.VOLATILITY, Placement.PRICE)
                .inputs(InputSpec.period(20), InputSpec.decimal("deviation", 2, 0.1, 10, 0.1),
                        InputSpec.choice("maType", "maType", "SMA", BAND_AVERAGES), InputSpec.source("close"), InputSpec.offset(),
                        InputSpec.timeframe())
                .outputs(OutputSpec.line("lower", Palette.BLUE).width(1), OutputSpec.line("middle", Palette.ORANGE).width(1),
                        OutputSpec.line("upper", Palette.BLUE).width(1))
                .fills(FillSpec.band("upper", "lower", Palette.BLUE_FILL))
                .label(values -> "Bollinger " + values.integer("period") + " / " + IndicatorDefinition.compact(values.decimal("deviation")) + "σ")
                .compute(context -> {
                    int period = context.integer("period");
                    double deviation = context.decimal("deviation");
                    if ("SMA".equals(context.text("maType")) && context.priceSource("source")) {
                        var close = context.ta4jPrice(context.text("source"));
                        var middle = new BollingerBandsMiddleIndicator(new SMAIndicator(close, period));
                        var std = StandardDeviationIndicator.ofPopulation(close, period);
                        Num multiplier = context.bars().numFactory().numOf(deviation);
                        context.output("lower", new BollingerBandsLowerIndicator(middle, std, multiplier));
                        context.output("middle", middle);
                        context.output("upper", new BollingerBandsUpperIndicator(middle, std, multiplier));
                    } else {
                        double[] source = context.source();
                        double[] basis = context.ma(context.text("maType"), source, period);
                        double[] width = TaMath.scale(TaMath.stdev(source, period), deviation);
                        context.output("lower", TaMath.subtract(basis, width));
                        context.output("middle", basis);
                        context.output("upper", TaMath.add(basis, width));
                    }
                    for (String key : new String[]{"lower", "middle", "upper"}) context.shift(key, context.integer("offset"));
                }));

        catalog.accept(define("BOLLINGER_PERCENT_B", "%B", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.period(20), InputSpec.decimal("deviation", 2, 0.1, 10, 0.1), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.CYAN))
                .references(0, 0.5, 1)
                .compute(context -> {
                    double[] source = context.source();
                    int period = context.integer("period");
                    double[] basis = TaMath.sma(source, period);
                    double[] width = TaMath.scale(TaMath.stdev(source, period), context.decimal("deviation"));
                    double[] lower = TaMath.subtract(basis, width);
                    context.output("value", TaMath.divide(TaMath.subtract(source, lower), TaMath.scale(width, 2)));
                }));

        catalog.accept(define("BOLLINGER_BANDWIDTH", "BBW", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.period(20), InputSpec.decimal("deviation", 2, 0.1, 10, 0.1), InputSpec.source("close"),
                        InputSpec.integer("expansionLength", 125, 2, 1000), InputSpec.integer("contractionLength", 125, 2, 1000))
                .outputs(OutputSpec.line("value", Palette.BLUE), OutputSpec.line("expansion", Palette.RED).width(1),
                        OutputSpec.line("contraction", Palette.MINT).width(1))
                .compute(context -> {
                    double[] source = context.source();
                    int period = context.integer("period");
                    double[] basis = TaMath.sma(source, period);
                    double[] width = TaMath.scale(TaMath.stdev(source, period), 2 * context.decimal("deviation"));
                    double[] bandwidth = TaMath.scale(TaMath.divide(width, basis), 100);
                    context.output("value", bandwidth);
                    context.output("expansion", TaMath.highest(bandwidth, context.integer("expansionLength")));
                    context.output("contraction", TaMath.lowest(bandwidth, context.integer("contractionLength")));
                }));

        catalog.accept(define("BBTREND", "BBTrend", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.length("shortLength", 20), InputSpec.length("longLength", 50), InputSpec.decimal("deviation", 2, 0.1, 10, 0.1))
                .outputs(OutputSpec.histogram("value", Palette.MINT, ColorMode.SIGN_MOMENTUM))
                .references(0)
                .compute(context -> {
                    double[] close = context.close();
                    double deviation = context.decimal("deviation");
                    int shortLength = context.integer("shortLength");
                    int longLength = context.integer("longLength");
                    double[] shortMiddle = TaMath.sma(close, shortLength);
                    double[] shortWidth = TaMath.scale(TaMath.stdev(close, shortLength), deviation);
                    double[] longMiddle = TaMath.sma(close, longLength);
                    double[] longWidth = TaMath.scale(TaMath.stdev(close, longLength), deviation);
                    double[] result = TaMath.nan(close.length);
                    for (int index = 0; index < close.length; index++) {
                        double lowerGap = Math.abs((shortMiddle[index] - shortWidth[index]) - (longMiddle[index] - longWidth[index]));
                        double upperGap = Math.abs((shortMiddle[index] + shortWidth[index]) - (longMiddle[index] + longWidth[index]));
                        if (Double.isFinite(lowerGap) && Double.isFinite(upperGap) && shortMiddle[index] != 0) {
                            result[index] = (lowerGap - upperGap) / shortMiddle[index] * 100;
                        }
                    }
                    context.output("value", result);
                }));

        catalog.accept(define("KELTNER", "KC", Category.VOLATILITY, Placement.PRICE)
                .inputs(InputSpec.period(20), InputSpec.decimal("multiplier", 2, 0.1, 10, 0.1), InputSpec.period(14).withKey("atrPeriod"),
                        InputSpec.source("close"), InputSpec.bool("exponential", true),
                        InputSpec.choice("bandsStyle", "bandsStyle", "ATR", "ATR", "TRUE_RANGE", "RANGE"), InputSpec.timeframe())
                .outputs(OutputSpec.line("lower", Palette.BLUE).width(1), OutputSpec.line("middle", Palette.BLUE).width(1).dashed(),
                        OutputSpec.line("upper", Palette.BLUE).width(1))
                .fills(FillSpec.band("upper", "lower", Palette.BLUE_FILL))
                .label(values -> "Keltner Channel " + values.integer("period") + " / ATR " + values.integer("atrPeriod"))
                .compute(context -> {
                    int period = context.integer("period");
                    int atrPeriod = context.integer("atrPeriod");
                    double multiplier = context.decimal("multiplier");
                    boolean factory = "close".equals(context.text("source")) && context.bool("exponential") && "ATR".equals(context.text("bandsStyle"));
                    if (factory) {
                        KeltnerChannelFacade channel = new KeltnerChannelFacade(context.bars(), period, atrPeriod, multiplier);
                        context.output("lower", channel.lower());
                        context.output("middle", channel.middle());
                        context.output("upper", channel.upper());
                        return;
                    }
                    double[] source = context.source();
                    double[] middle = context.bool("exponential") ? TaMath.ema(source, period) : TaMath.sma(source, period);
                    double[] range = switch (context.text("bandsStyle")) {
                        case "TRUE_RANGE" -> TaMath.trueRange(context.high(), context.low(), context.close());
                        case "RANGE" -> TaMath.rma(TaMath.subtract(context.high(), context.low()), atrPeriod);
                        default -> TaMath.atr(context.high(), context.low(), context.close(), atrPeriod);
                    };
                    double[] width = TaMath.scale(range, multiplier);
                    context.output("lower", TaMath.subtract(middle, width));
                    context.output("middle", middle);
                    context.output("upper", TaMath.add(middle, width));
                }));

        catalog.accept(define("DONCHIAN", "DC", Category.VOLATILITY, Placement.PRICE)
                .inputs(InputSpec.period(20), InputSpec.offset(), InputSpec.timeframe())
                .outputs(OutputSpec.line("lower", Palette.BLUE).width(1), OutputSpec.line("middle", Palette.ORANGE).width(1),
                        OutputSpec.line("upper", Palette.BLUE).width(1))
                .fills(FillSpec.band("upper", "lower", Palette.BLUE_FILL))
                .label(values -> "Donchian Channel " + values.integer("period"))
                .compute(context -> {
                    DonchianChannelFacade channel = new DonchianChannelFacade(context.bars(), context.integer("period"));
                    context.output("lower", channel.lower());
                    context.output("middle", channel.middle());
                    context.output("upper", channel.upper());
                    for (String key : new String[]{"lower", "middle", "upper"}) context.shift(key, context.integer("offset"));
                }));

        catalog.accept(define("ENVELOPES", "Env", Category.VOLATILITY, Placement.PRICE)
                .inputs(InputSpec.length("period", 20), InputSpec.decimal("percent", 10, 0.01, 100, 0.1), InputSpec.bool("exponential", false),
                        InputSpec.source("close"))
                .outputs(OutputSpec.line("upper", Palette.BLUE).width(1), OutputSpec.line("basis", Palette.ORANGE).width(1),
                        OutputSpec.line("lower", Palette.BLUE).width(1))
                .fills(FillSpec.band("upper", "lower", Palette.BLUE_FILL))
                .compute(context -> {
                    double[] source = context.source();
                    int period = context.integer("period");
                    double[] basis = context.bool("exponential") ? TaMath.ema(source, period) : TaMath.sma(source, period);
                    double percent = context.decimal("percent") / 100;
                    context.output("upper", TaMath.scale(basis, 1 + percent));
                    context.output("basis", basis);
                    context.output("lower", TaMath.scale(basis, 1 - percent));
                }));

        catalog.accept(define("ATR", "ATR", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.period(14), InputSpec.choice("smoothing", "atrSmoothing", "RMA", "RMA", "SMA", "EMA", "WMA"), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.RED))
                .compute(context -> {
                    int period = context.integer("period");
                    if ("RMA".equals(context.text("smoothing"))) {
                        context.output("value", new ATRIndicator(context.bars(), period));
                    } else {
                        context.output("value", context.ma(context.text("smoothing"),
                                TaMath.trueRange(context.high(), context.low(), context.close()), period));
                    }
                }));

        catalog.accept(define("HISTORICAL_VOLATILITY", "HV", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.integer("period", 10, 2, 500))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .compute(context -> {
                    double[] close = context.close();
                    double[] logReturns = TaMath.nan(close.length);
                    for (int index = 1; index < close.length; index++) {
                        if (close[index] > 0 && close[index - 1] > 0) logReturns[index] = Math.log(close[index] / close[index - 1]);
                    }
                    double annualise = 100 * Math.sqrt(MarketCalendar.periodsPerYear(context.interval()));
                    context.output("value", TaMath.scale(TaMath.stdev(logReturns, context.integer("period")), annualise));
                }));

        catalog.accept(define("STANDARD_DEVIATION", "StdDev", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.integer("period", 20, 2, 500), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .compute(context -> context.output("value", TaMath.stdev(context.source(), context.integer("period")))));

        catalog.accept(define("STANDARD_ERROR_BANDS", "SEB", Category.VOLATILITY, Placement.PRICE)
                .inputs(InputSpec.integer("period", 21, 3, 500), InputSpec.decimal("multiplier", 2, 0.1, 10, 0.1), InputSpec.length("smoothing", 3),
                        InputSpec.source("close"))
                .outputs(OutputSpec.line("upper", Palette.BLUE).width(1), OutputSpec.line("middle", Palette.RED).width(1),
                        OutputSpec.line("lower", Palette.BLUE).width(1))
                .fills(FillSpec.band("upper", "lower", Palette.BLUE_FILL))
                .compute(context -> {
                    double[] source = context.source();
                    int period = context.integer("period");
                    int smoothing = context.integer("smoothing");
                    double[] regression = TaMath.linreg(source, period, 0);
                    double[] error = standardError(source, regression, period);
                    double[] middle = TaMath.sma(regression, smoothing);
                    double[] width = TaMath.scale(TaMath.sma(error, smoothing), context.decimal("multiplier"));
                    context.output("upper", TaMath.add(middle, width));
                    context.output("middle", middle);
                    context.output("lower", TaMath.subtract(middle, width));
                }));

        catalog.accept(define("RELATIVE_VOLATILITY_INDEX", "RVI", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.integer("period", 10, 2, 500), InputSpec.length("smoothing", 14), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.VIOLET))
                .references(20, 50, 80)
                .compute(context -> {
                    double[] source = context.source();
                    double[] deviation = TaMath.stdev(source, context.integer("period"));
                    double[] change = TaMath.change(source, 1);
                    double[] upward = TaMath.nan(source.length);
                    double[] downward = TaMath.nan(source.length);
                    for (int index = 0; index < source.length; index++) {
                        if (!Double.isFinite(change[index]) || !Double.isFinite(deviation[index])) continue;
                        upward[index] = change[index] <= 0 ? 0 : deviation[index];
                        downward[index] = change[index] > 0 ? 0 : deviation[index];
                    }
                    int smoothing = context.integer("smoothing");
                    double[] upper = TaMath.ema(upward, smoothing);
                    double[] lower = TaMath.ema(downward, smoothing);
                    context.output("value", TaMath.scale(TaMath.divide(upper, TaMath.add(upper, lower)), 100));
                }));

        catalog.accept(define("MASS_INDEX", "MI", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.length("period", 10))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(26.5, 27)
                .compute(context -> {
                    double[] span = TaMath.subtract(context.high(), context.low());
                    double[] single = TaMath.ema(span, 9);
                    context.output("value", TaMath.sum(TaMath.divide(single, TaMath.ema(single, 9)), context.integer("period")));
                }));

        catalog.accept(define("ULCER_INDEX", "UI", Category.VOLATILITY, Placement.PANEL)
                .inputs(InputSpec.integer("period", 14, 2, 500), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.RED))
                .compute(context -> {
                    double[] source = context.source();
                    int period = context.integer("period");
                    double[] highest = TaMath.highest(source, period);
                    double[] squared = TaMath.nan(source.length);
                    for (int index = 0; index < source.length; index++) {
                        if (Double.isFinite(highest[index]) && highest[index] != 0) {
                            double drawdown = 100 * (source[index] - highest[index]) / highest[index];
                            squared[index] = drawdown * drawdown;
                        }
                    }
                    double[] mean = TaMath.sma(squared, period);
                    double[] result = TaMath.nan(source.length);
                    for (int index = 0; index < source.length; index++) if (Double.isFinite(mean[index])) result[index] = Math.sqrt(mean[index]);
                    context.output("value", result);
                }));
    }

    /** Standard error of the least-squares fit of each window. */
    static double[] standardError(double[] source, double[] regression, int length) {
        double[] result = TaMath.nan(source.length);
        for (int index = length - 1; index < source.length; index++) {
            if (!Double.isFinite(regression[index])) continue;
            double sumX = 0;
            double sumY = 0;
            double sumXY = 0;
            double sumXX = 0;
            for (int position = 0; position < length; position++) {
                double y = source[index - length + 1 + position];
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
                double residual = source[index - length + 1 + position] - (intercept + slope * position);
                residuals += residual * residual;
            }
            result[index] = Math.sqrt(residuals / (length - 2));
        }
        return result;
    }
}
