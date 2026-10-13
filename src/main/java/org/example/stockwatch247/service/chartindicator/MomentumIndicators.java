package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.ColorMode;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.ConnorsRSIIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;

import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Oscillators measuring the speed and exhaustion of price moves. */
final class MomentumIndicators {
    private static final String[] SMOOTHING = {"NONE", "SMA", "EMA", "SMMA", "WMA", "VWMA"};

    private MomentumIndicators() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("RSI", "RSI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.period(14), InputSpec.source("close"), InputSpec.decimal("upper", 70, 0, 100, 1),
                        InputSpec.decimal("lower", 30, 0, 100, 1), InputSpec.choice("maType", "smoothing", "NONE", SMOOTHING),
                        InputSpec.length("maLength", 14), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.VIOLET), OutputSpec.line("ma", Palette.YELLOW).width(1))
                .zones("upper", "lower")
                .validate(values -> {
                    if (values.decimal("lower") >= values.decimal("upper")) {
                        throw new UserInputException("RSI lower boundary must be below its upper boundary.");
                    }
                })
                .compute(context -> {
                    int period = context.integer("period");
                    double[] rsi = context.priceSource("source")
                            ? context.values(new RSIIndicator(context.ta4jPrice(context.text("source")), period))
                            : TaMath.rsi(context.source(), period);
                    context.output("value", rsi);
                    smoothing(context, rsi);
                    context.reference(context.decimal("lower"));
                    context.reference(50);
                    context.reference(context.decimal("upper"));
                }));

        catalog.accept(define("MACD", "MACD", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.period(12).withKey("fast"), InputSpec.period(26).withKey("slow"), InputSpec.period(9).withKey("signal"),
                        InputSpec.source("close"), InputSpec.choice("oscillatorMa", "emaSma", "EMA", "EMA", "SMA"),
                        InputSpec.choice("signalMa", "emaSma", "EMA", "EMA", "SMA"), InputSpec.timeframe())
                .outputs(OutputSpec.line("macd", Palette.BLUE), OutputSpec.line("signal", Palette.ORANGE),
                        OutputSpec.histogram("histogram", Palette.MINT, ColorMode.SIGN_MOMENTUM))
                .references(0)
                .label(values -> "MACD " + values.integer("fast") + "/" + values.integer("slow") + "/" + values.integer("signal"))
                .validate(values -> {
                    if (values.integer("fast") >= values.integer("slow")) throw new UserInputException("MACD fast period must be below its slow period.");
                })
                .compute(context -> {
                    int fast = context.integer("fast");
                    int slow = context.integer("slow");
                    int signalPeriod = context.integer("signal");
                    boolean exponential = "EMA".equals(context.text("oscillatorMa")) && "EMA".equals(context.text("signalMa"));
                    if (exponential && context.priceSource("source")) {
                        MACDIndicator macd = new MACDIndicator(context.ta4jPrice(context.text("source")), fast, slow);
                        context.output("macd", macd);
                        context.output("signal", macd.getSignalLine(signalPeriod));
                        context.output("histogram", macd.getHistogram(signalPeriod));
                        return;
                    }
                    double[] source = context.source();
                    double[] macd = TaMath.subtract(context.ma(context.text("oscillatorMa"), source, fast), context.ma(context.text("oscillatorMa"), source, slow));
                    double[] signal = context.ma(context.text("signalMa"), macd, signalPeriod);
                    context.output("macd", macd);
                    context.output("signal", signal);
                    context.output("histogram", TaMath.subtract(macd, signal));
                }));

        catalog.accept(define("STOCHASTIC", "Stoch", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.period(14), InputSpec.length("smooth", 1), InputSpec.length("dPeriod", 3), InputSpec.timeframe())
                .outputs(OutputSpec.line("k", Palette.BLUE), OutputSpec.line("d", Palette.ORANGE))
                .references(20, 50, 80)
                .compute(context -> {
                    double[] raw = TaMath.stoch(context.close(), context.high(), context.low(), context.integer("period"));
                    double[] k = context.integer("smooth") == 1 ? raw : TaMath.sma(raw, context.integer("smooth"));
                    context.output("k", k);
                    context.output("d", TaMath.sma(k, context.integer("dPeriod")));
                }));

        catalog.accept(define("STOCHASTIC_RSI", "Stoch RSI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.period(14), InputSpec.length("stochLength", 14), InputSpec.length("smoothK", 3),
                        InputSpec.length("smoothD", 3), InputSpec.source("close"), InputSpec.timeframe())
                .outputs(OutputSpec.line("k", Palette.BLUE), OutputSpec.line("d", Palette.ORANGE))
                .references(20, 50, 80)
                .compute(context -> {
                    double[] rsi = TaMath.rsi(context.source(), context.integer("period"));
                    double[] k = TaMath.sma(TaMath.stoch(rsi, rsi, rsi, context.integer("stochLength")), context.integer("smoothK"));
                    context.output("k", k);
                    context.output("d", TaMath.sma(k, context.integer("smoothD")));
                }));

        catalog.accept(define("CCI", "CCI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.period(20), InputSpec.source("hlc3"), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.CYAN))
                .references(-100, 0, 100)
                .compute(context -> {
                    int period = context.integer("period");
                    context.output("value", "hlc3".equals(context.text("source"))
                            ? context.values(new CCIIndicator(context.bars(), period))
                            : TaMath.cci(context.source(), period));
                }));

        catalog.accept(define("WILLIAMS_R", "%R", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 14), InputSpec.source("close"), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.VIOLET))
                .references(-80, -50, -20)
                .compute(context -> context.output("value",
                        TaMath.offset(TaMath.stoch(context.source(), context.high(), context.low(), context.integer("period")), -100))));

        catalog.accept(define("MOMENTUM", "Mom", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 10), InputSpec.source("close"), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(0)
                .compute(context -> context.output("value", TaMath.change(context.source(), context.integer("period")))));

        catalog.accept(define("ROC", "ROC", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 9), InputSpec.source("close"), InputSpec.timeframe())
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(0)
                .compute(context -> context.output("value", TaMath.roc(context.source(), context.integer("period")))));

        catalog.accept(define("AWESOME_OSCILLATOR", "AO", Category.MOMENTUM, Placement.PANEL)
                .outputs(OutputSpec.histogram("value", Palette.MINT, ColorMode.MOMENTUM))
                .compute(context -> context.output("value", awesome(context))));

        catalog.accept(define("ACCELERATOR_OSCILLATOR", "AC", Category.MOMENTUM, Placement.PANEL)
                .outputs(OutputSpec.histogram("value", Palette.MINT, ColorMode.MOMENTUM))
                .compute(context -> {
                    double[] ao = awesome(context);
                    context.output("value", TaMath.subtract(ao, TaMath.sma(ao, 5)));
                }));

        catalog.accept(define("ULTIMATE_OSCILLATOR", "UO", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("fast", 7), InputSpec.length("middle", 14), InputSpec.length("slow", 28))
                .outputs(OutputSpec.line("value", Palette.RED))
                .references(30, 50, 70)
                .compute(context -> {
                    double[] high = context.high();
                    double[] low = context.low();
                    double[] close = context.close();
                    double[] buying = TaMath.nan(close.length);
                    double[] range = TaMath.nan(close.length);
                    for (int index = 1; index < close.length; index++) {
                        double floor = Math.min(low[index], close[index - 1]);
                        buying[index] = close[index] - floor;
                        range[index] = Math.max(high[index], close[index - 1]) - floor;
                    }
                    double[] fast = TaMath.divide(TaMath.sum(buying, context.integer("fast")), TaMath.sum(range, context.integer("fast")));
                    double[] middle = TaMath.divide(TaMath.sum(buying, context.integer("middle")), TaMath.sum(range, context.integer("middle")));
                    double[] slow = TaMath.divide(TaMath.sum(buying, context.integer("slow")), TaMath.sum(range, context.integer("slow")));
                    double[] result = new double[close.length];
                    for (int index = 0; index < close.length; index++) result[index] = 100 * (4 * fast[index] + 2 * middle[index] + slow[index]) / 7;
                    context.output("value", result);
                }));

        catalog.accept(define("TSI", "TSI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("longLength", 25), InputSpec.length("shortLength", 13), InputSpec.length("signal", 13), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.BLUE), OutputSpec.line("signal", Palette.RED))
                .references(0)
                .compute(context -> {
                    double[] tsi = TaMath.tsi(context.source(), context.integer("shortLength"), context.integer("longLength"));
                    context.output("value", tsi);
                    context.output("signal", TaMath.ema(tsi, context.integer("signal")));
                }));

        catalog.accept(define("CMO", "CMO", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 9), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(-50, 0, 50)
                .compute(context -> context.output("value", TaMath.cmo(context.source(), context.integer("period")))));

        catalog.accept(define("FISHER_TRANSFORM", "Fisher", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 9))
                .outputs(OutputSpec.line("fisher", Palette.BLUE), OutputSpec.line("trigger", Palette.ORANGE))
                .references(-1.5, -0.75, 0, 0.75, 1.5)
                .compute(MomentumIndicators::fisher));

        catalog.accept(define("CONNORS_RSI", "CRSI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.integer("rsiLength", 3, 2, 500), InputSpec.integer("streakLength", 2, 2, 500),
                        InputSpec.integer("rankLength", 100, 2, 500), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(30, 70)
                .compute(context -> context.output("value", new ConnorsRSIIndicator(context.ta4j(context.source()),
                        context.integer("rsiLength"), context.integer("streakLength"), context.integer("rankLength")))));

        catalog.accept(define("KST", "KST", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("roc1", 10), InputSpec.length("roc2", 15), InputSpec.length("roc3", 20), InputSpec.length("roc4", 30),
                        InputSpec.length("sma1", 10), InputSpec.length("sma2", 10), InputSpec.length("sma3", 10), InputSpec.length("sma4", 15),
                        InputSpec.length("signal", 9), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.MINT), OutputSpec.line("signal", Palette.RED))
                .references(0)
                .compute(context -> {
                    double[] source = context.source();
                    double[] kst = new double[source.length];
                    for (int part = 1; part <= 4; part++) {
                        double[] smoothed = TaMath.sma(TaMath.roc(source, context.integer("roc" + part)), context.integer("sma" + part));
                        for (int index = 0; index < kst.length; index++) kst[index] += part * smoothed[index];
                    }
                    context.output("value", kst);
                    context.output("signal", TaMath.sma(kst, context.integer("signal")));
                }));

        catalog.accept(define("COPPOCK", "Coppock", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("wmaLength", 10), InputSpec.length("longRoc", 14), InputSpec.length("shortRoc", 11), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(0)
                .compute(context -> {
                    double[] source = context.source();
                    context.output("value", TaMath.wma(TaMath.add(TaMath.roc(source, context.integer("longRoc")),
                            TaMath.roc(source, context.integer("shortRoc"))), context.integer("wmaLength")));
                }));

        catalog.accept(define("DPO", "DPO", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 21), InputSpec.bool("centered", false))
                .outputs(OutputSpec.line("value", Palette.MINT))
                .references(0)
                .compute(context -> {
                    int period = context.integer("period");
                    int back = period / 2 + 1;
                    double[] close = context.close();
                    double[] average = TaMath.sma(close, period);
                    if (context.bool("centered")) {
                        context.output("value", TaMath.subtract(TaMath.previous(close, back), average));
                        context.shift("value", -back);
                    } else {
                        context.output("value", TaMath.subtract(close, TaMath.previous(average, back)));
                    }
                }));

        catalog.accept(define("PRICE_OSCILLATOR", "PPO", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("fast", 10), InputSpec.length("slow", 21), InputSpec.choice("maType", "emaSma", "SMA", "EMA", "SMA"),
                        InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.MINT))
                .references(0)
                .validate(values -> {
                    if (values.integer("fast") >= values.integer("slow")) throw new UserInputException("The fast length must be below the slow length.");
                })
                .compute(context -> {
                    double[] source = context.source();
                    double[] fast = context.ma(context.text("maType"), source, context.integer("fast"));
                    double[] slow = context.ma(context.text("maType"), source, context.integer("slow"));
                    context.output("value", TaMath.scale(TaMath.divide(TaMath.subtract(fast, slow), slow), 100));
                }));

        catalog.accept(define("TRIX", "TRIX", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 18))
                .outputs(OutputSpec.line("value", Palette.RED))
                .references(0)
                .compute(context -> {
                    int period = context.integer("period");
                    double[] logClose = new double[context.size()];
                    double[] close = context.close();
                    for (int index = 0; index < close.length; index++) logClose[index] = close[index] > 0 ? Math.log(close[index]) : Double.NaN;
                    double[] triple = TaMath.ema(TaMath.ema(TaMath.ema(logClose, period), period), period);
                    context.output("value", TaMath.scale(TaMath.change(triple, 1), 10000));
                }));

        catalog.accept(define("RVI", "RVGI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 10))
                .outputs(OutputSpec.line("value", Palette.MINT), OutputSpec.line("signal", Palette.RED))
                .references(0)
                .compute(context -> {
                    int period = context.integer("period");
                    double[] numerator = TaMath.sum(TaMath.swma(TaMath.subtract(context.close(), context.open())), period);
                    double[] denominator = TaMath.sum(TaMath.swma(TaMath.subtract(context.high(), context.low())), period);
                    double[] rvi = TaMath.divide(numerator, denominator);
                    context.output("value", rvi);
                    context.output("signal", TaMath.swma(rvi));
                }));

        catalog.accept(define("SMI_ERGODIC", "SMIO", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("longLength", 20), InputSpec.length("shortLength", 5), InputSpec.length("signal", 5))
                .outputs(OutputSpec.line("value", Palette.BLUE), OutputSpec.line("signal", Palette.ORANGE),
                        OutputSpec.histogram("oscillator", Palette.RED, ColorMode.SIGN))
                .references(0)
                .compute(context -> {
                    double[] ergodic = TaMath.scale(TaMath.tsi(context.close(), context.integer("shortLength"), context.integer("longLength")), 0.01);
                    double[] signal = TaMath.ema(ergodic, context.integer("signal"));
                    context.output("value", ergodic);
                    context.output("signal", signal);
                    context.output("oscillator", TaMath.subtract(ergodic, signal));
                }));

        catalog.accept(define("SMI", "SMI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("kLength", 10), InputSpec.length("dLength", 3), InputSpec.length("emaLength", 3))
                .outputs(OutputSpec.line("value", Palette.BLUE), OutputSpec.line("signal", Palette.ORANGE))
                .references(-40, 0, 40)
                .compute(context -> {
                    int kLength = context.integer("kLength");
                    int dLength = context.integer("dLength");
                    double[] highest = TaMath.highest(context.high(), kLength);
                    double[] lowest = TaMath.lowest(context.low(), kLength);
                    double[] range = TaMath.subtract(highest, lowest);
                    double[] relative = new double[range.length];
                    double[] close = context.close();
                    for (int index = 0; index < relative.length; index++) relative[index] = close[index] - (highest[index] + lowest[index]) / 2;
                    double[] smi = TaMath.scale(TaMath.divide(TaMath.ema(TaMath.ema(relative, dLength), dLength),
                            TaMath.ema(TaMath.ema(range, dLength), dLength)), 200);
                    context.output("value", smi);
                    context.output("signal", TaMath.ema(smi, context.integer("emaLength")));
                }));

        catalog.accept(define("BALANCE_OF_POWER", "BOP", Category.MOMENTUM, Placement.PANEL)
                .outputs(OutputSpec.line("value", Palette.RED))
                .references(0)
                .compute(context -> context.output("value",
                        TaMath.divide(TaMath.subtract(context.close(), context.open()), TaMath.subtract(context.high(), context.low())))));

        catalog.accept(define("BULL_BEAR_POWER", "BBP", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 13))
                .outputs(OutputSpec.histogram("value", Palette.MINT, ColorMode.SIGN))
                .references(0)
                .compute(context -> {
                    double[] ema = TaMath.ema(context.close(), context.integer("period"));
                    context.output("value", TaMath.add(TaMath.subtract(context.high(), ema), TaMath.subtract(context.low(), ema)));
                }));

        catalog.accept(define("WOODIES_CCI", "Woodies CCI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("period", 14), InputSpec.length("turbo", 6))
                .outputs(OutputSpec.histogram("histogram", Palette.MINT, ColorMode.SIGN), OutputSpec.line("cci", Palette.ORANGE),
                        OutputSpec.line("turbo", Palette.BLUE).width(1))
                .references(-100, 0, 100)
                .compute(context -> {
                    double[] typical = context.price("hlc3");
                    double[] cci = TaMath.cci(typical, context.integer("period"));
                    context.output("histogram", cci.clone());
                    context.output("cci", cci);
                    context.output("turbo", TaMath.cci(typical, context.integer("turbo")));
                }));

        catalog.accept(define("RCI", "RCI", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.integer("period", 10, 2, 500), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.BLUE))
                .references(-80, 0, 80)
                .compute(context -> context.output("value", rankCorrelation(context.source(), context.integer("period")))));

        catalog.accept(define("SQUEEZE_MOMENTUM", "SQZMOM", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("bbLength", 20), InputSpec.decimal("bbMultiplier", 2, 0.1, 10, 0.1),
                        InputSpec.length("kcLength", 20), InputSpec.decimal("kcMultiplier", 1.5, 0.1, 10, 0.1))
                .outputs(OutputSpec.histogram("value", Palette.MINT, ColorMode.SIGN_MOMENTUM),
                        OutputSpec.dots("squeeze", Palette.TAN).tones(Palette.RED, Palette.VIOLET, Palette.BLUE))
                .compute(MomentumIndicators::squeeze));

        catalog.accept(define("SCHAFF_TREND_CYCLE", "STC", Category.MOMENTUM, Placement.PANEL)
                .inputs(InputSpec.length("cycle", 10), InputSpec.length("fast", 23), InputSpec.length("slow", 50),
                        InputSpec.decimal("factor", 0.5, 0.01, 1, 0.01), InputSpec.source("close"))
                .outputs(OutputSpec.line("value", Palette.CYAN))
                .references(25, 75)
                .compute(context -> {
                    double[] source = context.source();
                    double[] macd = TaMath.subtract(TaMath.ema(source, context.integer("fast")), TaMath.ema(source, context.integer("slow")));
                    int cycle = context.integer("cycle");
                    double factor = context.decimal("factor");
                    double[] first = smoothStochastic(macd, cycle, factor);
                    context.output("value", smoothStochastic(first, cycle, factor));
                }));
    }

    /** Optional moving average of an oscillator, chosen with the maType and maLength inputs. */
    static void smoothing(IndicatorContext context, double[] values) {
        String type = context.text("maType");
        if (!"NONE".equals(type)) context.output("ma", context.ma(type, values, context.integer("maLength")));
    }

    static double[] awesome(IndicatorContext context) {
        double[] median = context.price("hl2");
        return TaMath.subtract(TaMath.sma(median, 5), TaMath.sma(median, 34));
    }

    static void fisher(IndicatorContext context) {
        int period = context.integer("period");
        double[] median = context.price("hl2");
        double[] highest = TaMath.highest(median, period);
        double[] lowest = TaMath.lowest(median, period);
        double[] fisher = TaMath.nan(median.length);
        double[] trigger = TaMath.nan(median.length);
        double value = 0;
        double previousFisher = 0;
        boolean started = false;
        for (int index = 0; index < median.length; index++) {
            if (!Double.isFinite(highest[index])) continue;
            double range = highest[index] - lowest[index];
            double raw = range == 0 ? 0 : 0.66 * ((median[index] - lowest[index]) / range - 0.5) + 0.67 * value;
            value = Math.max(-0.999, Math.min(0.999, raw));
            double current = 0.5 * Math.log((1 + value) / (1 - value)) + 0.5 * previousFisher;
            fisher[index] = current;
            if (started) trigger[index] = previousFisher;
            previousFisher = current;
            started = true;
        }
        context.output("fisher", fisher);
        context.output("trigger", trigger);
    }

    /** Spearman correlation between bar order and price over the window, scaled to ±100. */
    static double[] rankCorrelation(double[] source, int length) {
        double[] result = TaMath.nan(source.length);
        for (int index = length - 1; index < source.length; index++) {
            boolean complete = true;
            for (int offset = index - length + 1; offset <= index; offset++) complete &= Double.isFinite(source[offset]);
            if (!complete) continue;
            double[] ranks = ranks(source, index - length + 1, length);
            double sum = 0;
            for (int position = 0; position < length; position++) {
                double timeRank = position + 1;
                double difference = timeRank - ranks[position];
                sum += difference * difference;
            }
            result[index] = (1 - 6 * sum / (length * ((double) length * length - 1))) * 100;
        }
        return result;
    }

    private static double[] ranks(double[] source, int start, int length) {
        double[] ranks = new double[length];
        for (int position = 0; position < length; position++) {
            double value = source[start + position];
            int below = 0;
            int equal = 0;
            for (int other = 0; other < length; other++) {
                double candidate = source[start + other];
                if (candidate < value) below++;
                else if (candidate == value) equal++;
            }
            ranks[position] = below + (equal + 1) / 2.0;
        }
        return ranks;
    }

    /** LazyBear's squeeze momentum: momentum histogram plus a squeeze state dot on the zero line. */
    static void squeeze(IndicatorContext context) {
        double[] close = context.close();
        int bbLength = context.integer("bbLength");
        int kcLength = context.integer("kcLength");
        double[] basis = TaMath.sma(close, bbLength);
        double[] deviation = TaMath.scale(TaMath.stdev(close, bbLength), context.decimal("bbMultiplier"));
        double[] keltnerMid = TaMath.sma(close, kcLength);
        double[] range = TaMath.scale(TaMath.sma(TaMath.trueRange(context.high(), context.low(), close), kcLength), context.decimal("kcMultiplier"));
        double[] highest = TaMath.highest(context.high(), kcLength);
        double[] lowest = TaMath.lowest(context.low(), kcLength);
        double[] centered = new double[close.length];
        for (int index = 0; index < close.length; index++) {
            double mid = ((highest[index] + lowest[index]) / 2 + keltnerMid[index]) / 2;
            centered[index] = close[index] - mid;
        }
        context.output("value", TaMath.linreg(centered, kcLength, 0));
        double[] zero = TaMath.nan(close.length);
        int[] tones = new int[close.length];
        for (int index = 0; index < close.length; index++) {
            if (!Double.isFinite(basis[index]) || !Double.isFinite(keltnerMid[index]) || !Double.isFinite(range[index])) continue;
            double lowerBand = basis[index] - deviation[index];
            double upperBand = basis[index] + deviation[index];
            double lowerKeltner = keltnerMid[index] - range[index];
            double upperKeltner = keltnerMid[index] + range[index];
            boolean on = lowerBand > lowerKeltner && upperBand < upperKeltner;
            boolean off = lowerBand < lowerKeltner && upperBand > upperKeltner;
            zero[index] = 0;
            tones[index] = on ? 0 : (off ? 1 : 2);
        }
        context.output("squeeze", zero);
        context.tones("squeeze", tones);
    }

    /** The doubly smoothed stochastic used by the Schaff trend cycle. */
    static double[] smoothStochastic(double[] source, int cycle, double factor) {
        double[] lowest = TaMath.lowest(source, cycle);
        double[] highest = TaMath.highest(source, cycle);
        double[] result = TaMath.nan(source.length);
        double previousRaw = 0;
        double previous = Double.NaN;
        for (int index = 0; index < source.length; index++) {
            if (!Double.isFinite(lowest[index])) continue;
            double range = highest[index] - lowest[index];
            double raw = range > 0 ? (source[index] - lowest[index]) / range * 100 : previousRaw;
            previousRaw = raw;
            previous = Double.isFinite(previous) ? previous + factor * (raw - previous) : raw;
            result[index] = previous;
        }
        return result;
    }
}
