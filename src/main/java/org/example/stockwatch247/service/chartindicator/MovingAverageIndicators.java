package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.FillSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;

import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Moving averages and the indicators built from several of them. */
final class MovingAverageIndicators {
    static final String[] TYPES = {"SMA", "EMA", "SMMA", "WMA", "VWMA", "HMA", "DEMA", "TEMA", "LSMA", "ZLEMA", "TMA", "ALMA", "KAMA", "MCGINLEY", "VIDYA"};
    private static final String[] GUPPY_SHORT = {"3", "5", "8", "10", "12", "15"};
    private static final String[] GUPPY_LONG = {"30", "35", "40", "45", "50", "60"};

    private MovingAverageIndicators() { }

    static InputSpec maType(String key, String defaultValue) {
        return InputSpec.choice(key, "maType", defaultValue, TYPES);
    }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(single("SMA", "SMA", "SMA", 200, Palette.ORANGE));
        catalog.accept(single("EMA", "EMA", "EMA", 20, Palette.BLUE));
        catalog.accept(single("WMA", "WMA", "WMA", 9, Palette.CYAN));
        catalog.accept(single("VWMA", "VWMA", "VWMA", 20, Palette.VIOLET));
        catalog.accept(single("HMA", "HMA", "HMA", 9, Palette.PINK));
        catalog.accept(single("DEMA", "DEMA", "DEMA", 9, Palette.PERIWINKLE));
        catalog.accept(single("TEMA", "TEMA", "TEMA", 9, Palette.CORAL));
        catalog.accept(single("SMMA", "SMMA", "SMMA", 7, Palette.TAN));
        catalog.accept(single("LSMA", "LSMA", "LSMA", 25, Palette.YELLOW));
        catalog.accept(single("ZLEMA", "ZLEMA", "ZLEMA", 20, Palette.MINT));
        catalog.accept(single("TMA", "TMA", "TMA", 20, Palette.LIME));
        catalog.accept(single("MCGINLEY", "McGinley", "MCGINLEY", 14, Palette.ORANGE));
        catalog.accept(single("VIDYA", "VIDYA", "VIDYA", 9, Palette.CYAN));

        catalog.accept(define("ALMA", "ALMA", Category.MOVING_AVERAGES, Placement.PRICE)
                .inputs(InputSpec.length("period", 9), InputSpec.decimal("almaOffset", 0.85, 0, 1, 0.01),
                        InputSpec.decimal("sigma", 6, 0.5, 20, 0.1), InputSpec.source("close"), InputSpec.offset(), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .compute(context -> {
                    context.output("value", TaMath.alma(context.source(), context.integer("period"),
                            context.decimal("almaOffset"), context.decimal("sigma")));
                    context.shift("value", context.integer("offset"));
                }));

        catalog.accept(define("KAMA", "KAMA", Category.MOVING_AVERAGES, Placement.PRICE)
                .inputs(InputSpec.length("period", 10), InputSpec.length("fast", 2), InputSpec.length("slow", 30),
                        InputSpec.source("close"), InputSpec.offset(), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.MINT))
                .validate(values -> {
                    if (values.integer("fast") >= values.integer("slow")) throw new UserInputException("KAMA fast period must be below its slow period.");
                })
                .compute(context -> {
                    context.output("value", TaMath.kama(context.source(), context.integer("period"), context.integer("fast"), context.integer("slow")));
                    context.shift("value", context.integer("offset"));
                }));

        catalog.accept(define("MA_RIBBON", "MA Ribbon", Category.MOVING_AVERAGES, Placement.PRICE)
                .inputs(maType("maType", "SMA"), InputSpec.length("period1", 20), InputSpec.length("period2", 50),
                        InputSpec.length("period3", 100), InputSpec.length("period4", 200), InputSpec.source("close"))
                .outputs(OutputSpec.line("ma1", Palette.YELLOW).width(1), OutputSpec.line("ma2", Palette.ORANGE).width(1),
                        OutputSpec.line("ma3", Palette.CORAL).width(1), OutputSpec.line("ma4", Palette.RED).width(1))
                .compute(context -> {
                    double[] source = context.source();
                    for (int index = 1; index <= 4; index++) {
                        context.output("ma" + index, context.ma(context.text("maType"), source, context.integer("period" + index)));
                    }
                }));

        catalog.accept(define("MA_CROSS", "MA Cross", Category.MOVING_AVERAGES, Placement.PRICE)
                .inputs(maType("maType", "SMA"), InputSpec.length("fast", 9), InputSpec.length("slow", 21), InputSpec.source("close"))
                .outputs(OutputSpec.line("fast", Palette.MINT), OutputSpec.line("slow", Palette.RED),
                        OutputSpec.markers("crosses", Palette.YELLOW, "circle", "atPrice"))
                .validate(values -> {
                    if (values.integer("fast") >= values.integer("slow")) throw new UserInputException("The fast average must be shorter than the slow average.");
                })
                .compute(context -> {
                    double[] source = context.source();
                    double[] fast = context.ma(context.text("maType"), source, context.integer("fast"));
                    double[] slow = context.ma(context.text("maType"), source, context.integer("slow"));
                    context.output("fast", fast);
                    context.output("slow", slow);
                    for (int index = 1; index < fast.length; index++) {
                        double before = fast[index - 1] - slow[index - 1];
                        double now = fast[index] - slow[index];
                        if (Double.isFinite(before) && Double.isFinite(now) && Math.signum(before) != Math.signum(now) && now != 0) {
                            context.marker("crosses", index, fast[index], now > 0 ? "up" : "down");
                        }
                    }
                }));

        var guppy = define("GUPPY", "GMMA", Category.MOVING_AVERAGES, Placement.PRICE).inputs(InputSpec.source("close"));
        for (int index = 0; index < GUPPY_SHORT.length; index++) guppy.outputs(OutputSpec.line("short" + (index + 1), Palette.CYAN).width(1));
        for (int index = 0; index < GUPPY_LONG.length; index++) guppy.outputs(OutputSpec.line("long" + (index + 1), Palette.RED).width(1));
        catalog.accept(guppy.compute(context -> {
            double[] source = context.source();
            for (int index = 0; index < GUPPY_SHORT.length; index++) {
                context.output("short" + (index + 1), TaMath.ema(source, Integer.parseInt(GUPPY_SHORT[index])));
            }
            for (int index = 0; index < GUPPY_LONG.length; index++) {
                context.output("long" + (index + 1), TaMath.ema(source, Integer.parseInt(GUPPY_LONG[index])));
            }
        }));

        catalog.accept(define("MA_CHANNEL", "MA Channel", Category.MOVING_AVERAGES, Placement.PRICE)
                .inputs(maType("maType", "SMA"), InputSpec.length("period", 20), InputSpec.integer("upperOffset", 0, -500, 500),
                        InputSpec.integer("lowerOffset", 0, -500, 500))
                .outputs(OutputSpec.line("upper", Palette.BLUE).width(1), OutputSpec.line("lower", Palette.BLUE).width(1))
                .fills(FillSpec.band("upper", "lower", Palette.BLUE_FILL))
                .compute(context -> {
                    int period = context.integer("period");
                    context.output("upper", context.ma(context.text("maType"), context.high(), period));
                    context.output("lower", context.ma(context.text("maType"), context.low(), period));
                    context.shift("upper", context.integer("upperOffset"));
                    context.shift("lower", context.integer("lowerOffset"));
                }));
    }

    /** One average with length, source, offset and an optional higher timeframe — TradingView's common inputs. */
    private static IndicatorDefinition single(String type, String shortName, String maType, int period, String color) {
        InputSpec length = "SMA".equals(type) || "EMA".equals(type) ? InputSpec.period(period) : InputSpec.length("period", period);
        return define(type, shortName, Category.MOVING_AVERAGES, Placement.PRICE)
                .inputs(length, InputSpec.source("close"), InputSpec.offset(), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", color))
                .compute(context -> {
                    context.output("value", context.ma(maType, context.source(), context.integer("period")));
                    context.shift("value", context.integer("offset"));
                });
    }
}
