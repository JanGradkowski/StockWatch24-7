package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.IndicatorContext.Shape;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;
import org.example.stockwatch247.service.technical.StableVolumeProfileKde;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/** Where volume traded by price: rolling levels and horizontal histograms. */
final class ProfileIndicators {
    private static final int ROLLING_BINS = 24;

    private ProfileIndicators() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("VOLUME_PROFILE", "VP", Category.PROFILES, Placement.PRICE)
                .inputs(InputSpec.period(60), InputSpec.decimal("valueArea", 0.7, 0.5, 0.95, 0.01))
                .outputs(OutputSpec.line("val", Palette.BLUE).width(1), OutputSpec.line("poc", Palette.ORANGE).dashed(),
                        OutputSpec.line("vah", Palette.BLUE).width(1))
                .label(values -> "Rolling volume profile " + values.integer("period"))
                .compute(ProfileIndicators::rolling));

        catalog.accept(define("KDE_VOLUME_PROFILE", "KDE mode", Category.PROFILES, Placement.PRICE)
                .inputs(InputSpec.period(60))
                .outputs(OutputSpec.line("mode", Palette.PINK))
                .label(values -> "KDE volume-profile mode " + values.integer("period"))
                .compute(context -> {
                    if (context.size() == 0) return;
                    int period = context.integer("period");
                    double firstClose = Math.abs(context.close()[0]);
                    double firstRange = Math.abs(context.high()[0] - context.low()[0]);
                    double bandwidth = Math.max(0.000_001, Math.max(firstClose * 0.0025, firstRange * 0.5));
                    StableVolumeProfileKde kde = new StableVolumeProfileKde(new TypicalPriceIndicator(context.bars()),
                            new VolumeIndicator(context.bars()), period, bandwidth);
                    double[] mode = TaMath.nan(context.size());
                    for (int index = kde.getCountOfUnstableBars(); index < mode.length; index++) mode[index] = kde.getModePrice(index);
                    context.output("mode", mode);
                }));

        catalog.accept(define("VISIBLE_RANGE_VOLUME_PROFILE", "VRVP", Category.PROFILES, Placement.PRICE)
                .inputs(InputSpec.integer("rows", 24, 6, 200), InputSpec.decimal("valueArea", 0.7, 0.5, 0.95, 0.01),
                        InputSpec.integer("width", 30, 5, 80))
                .shapes()
                .visibleRange()
                .compute(context -> {
                    int from = 0;
                    int to = context.size() - 1;
                    if (context.visibleFrom() != null) while (from < context.size() && context.time(from) < context.visibleFrom()) from++;
                    if (context.visibleTo() != null) while (to >= 0 && context.time(to) > context.visibleTo()) to--;
                    if (from > to) return;
                    Map<String, Object> data = profile(context, from, to, context.integer("rows"), context.decimal("valueArea"));
                    if (data == null) return;
                    data.put("placement", "right");
                    data.put("width", context.integer("width"));
                    context.shape(Shape.profile(from, to, data));
                }));

        catalog.accept(define("PERIODIC_VOLUME_PROFILE", "PVP", Category.PROFILES, Placement.PRICE)
                .inputs(InputSpec.choice("period", "anchorPeriod", "MONTH", VolumeIndicators.ANCHOR_PERIODS),
                        InputSpec.integer("rows", 24, 6, 200), InputSpec.decimal("valueArea", 0.7, 0.5, 0.95, 0.01),
                        InputSpec.integer("periodsBack", 12, 1, 120))
                .shapes()
                .compute(context -> {
                    List<LevelIndicators.Period> periods = LevelIndicators.periods(context, context.text("period"));
                    int first = Math.max(0, periods.size() - context.integer("periodsBack"));
                    for (int position = first; position < periods.size(); position++) {
                        LevelIndicators.Period period = periods.get(position);
                        Map<String, Object> data = profile(context, period.first(), period.last(), context.integer("rows"), context.decimal("valueArea"));
                        if (data == null) continue;
                        data.put("placement", "span");
                        context.shape(Shape.profile(period.first(), period.last(), data));
                    }
                }));

        catalog.accept(define("FIXED_RANGE_VOLUME_PROFILE", "FRVP", Category.PROFILES, Placement.PRICE)
                .inputs(InputSpec.timestamp("from"), InputSpec.timestamp("to"), InputSpec.integer("rows", 24, 6, 200),
                        InputSpec.decimal("valueArea", 0.7, 0.5, 0.95, 0.01))
                .shapes()
                .validate(values -> {
                    if (values.timestamp("to") != 0 && values.timestamp("to") < values.timestamp("from")) {
                        throw new UserInputException("The profile must end after it starts.");
                    }
                })
                .compute(context -> {
                    long fromTime = context.inputs().timestamp("from");
                    long toTime = context.inputs().timestamp("to");
                    int from = 0;
                    while (from < context.size() && context.time(from) < fromTime) from++;
                    int to = context.size() - 1;
                    if (toTime > 0) while (to >= 0 && context.time(to) > toTime) to--;
                    if (from > to) return;
                    Map<String, Object> data = profile(context, from, to, context.integer("rows"), context.decimal("valueArea"));
                    if (data == null) return;
                    data.put("placement", "span");
                    context.shape(Shape.profile(from, to, data));
                }));
    }

    /**
     * Volume by price between two bars. Each bar's volume is spread over its high-low range in
     * proportion to how much of the range falls in each row, and split into up and down volume by
     * whether the bar closed above its open.
     */
    static Map<String, Object> profile(IndicatorContext context, int from, int to, int rows, double valueArea) {
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int index = from; index <= to; index++) {
            minimum = Math.min(minimum, context.low()[index]);
            maximum = Math.max(maximum, context.high()[index]);
        }
        if (!(maximum > minimum)) return null;
        double height = (maximum - minimum) / rows;
        double[] up = new double[rows];
        double[] down = new double[rows];
        double total = 0;
        for (int index = from; index <= to; index++) {
            double volume = context.volume()[index];
            if (volume <= 0) continue;
            boolean rising = context.close()[index] >= context.open()[index];
            double low = context.low()[index];
            double high = context.high()[index];
            total += volume;
            if (high <= low) {
                int row = Math.min(rows - 1, Math.max(0, (int) ((low - minimum) / height)));
                if (rising) up[row] += volume; else down[row] += volume;
                continue;
            }
            int lowRow = Math.min(rows - 1, Math.max(0, (int) ((low - minimum) / height)));
            int highRow = Math.min(rows - 1, Math.max(0, (int) ((high - minimum) / height)));
            for (int row = lowRow; row <= highRow; row++) {
                double rowLow = minimum + row * height;
                double overlap = Math.min(high, rowLow + height) - Math.max(low, rowLow);
                if (overlap <= 0) continue;
                double share = volume * overlap / (high - low);
                if (rising) up[row] += share; else down[row] += share;
            }
        }
        if (total <= 0) return null;
        int mode = 0;
        for (int row = 1; row < rows; row++) if (up[row] + down[row] > up[mode] + down[mode]) mode = row;
        int lower = mode;
        int upper = mode;
        double included = up[mode] + down[mode];
        while (included < total * valueArea && (lower > 0 || upper < rows - 1)) {
            double below = lower > 0 ? up[lower - 1] + down[lower - 1] : -1;
            double above = upper < rows - 1 ? up[upper + 1] + down[upper + 1] : -1;
            if (above > below) { upper++; included += up[upper] + down[upper]; }
            else { lower--; included += up[lower] + down[lower]; }
        }
        List<Map<String, Object>> histogram = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            histogram.add(Map.of("low", minimum + row * height, "high", minimum + (row + 1) * height,
                    "up", up[row], "down", down[row], "valueArea", row >= lower && row <= upper));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", histogram);
        data.put("poc", minimum + (mode + 0.5) * height);
        data.put("val", minimum + lower * height);
        data.put("vah", minimum + (upper + 1) * height);
        data.put("total", total);
        return data;
    }

    /** Point of control and value area of the trailing window at every bar. */
    static void rolling(IndicatorContext context) {
        int period = context.integer("period");
        double valueArea = context.decimal("valueArea");
        int size = context.size();
        double[] poc = TaMath.nan(size);
        double[] val = TaMath.nan(size);
        double[] vah = TaMath.nan(size);
        double[] high = context.high();
        double[] low = context.low();
        double[] volume = context.volume();
        for (int end = period - 1; end < size; end++) {
            int start = end - period + 1;
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            double total = 0;
            for (int index = start; index <= end; index++) {
                minimum = Math.min(minimum, low[index]);
                maximum = Math.max(maximum, high[index]);
                total += volume[index];
            }
            if (!(maximum > minimum) || total <= 0) continue;
            double width = (maximum - minimum) / ROLLING_BINS;
            double[] bins = new double[ROLLING_BINS];
            for (int index = start; index <= end; index++) {
                int lowBin = Math.min(ROLLING_BINS - 1, Math.max(0, (int) ((low[index] - minimum) / width)));
                int highBin = Math.min(ROLLING_BINS - 1, Math.max(0, (int) ((high[index] - minimum) / width)));
                double amount = volume[index] / Math.max(1, highBin - lowBin + 1);
                for (int bin = lowBin; bin <= highBin; bin++) bins[bin] += amount;
            }
            int mode = 0;
            for (int bin = 1; bin < bins.length; bin++) if (bins[bin] > bins[mode]) mode = bin;
            int lower = mode;
            int upper = mode;
            double included = bins[mode];
            while (included < total * valueArea && (lower > 0 || upper < ROLLING_BINS - 1)) {
                double below = lower > 0 ? bins[lower - 1] : -1;
                double above = upper < ROLLING_BINS - 1 ? bins[upper + 1] : -1;
                if (above > below) included += bins[++upper]; else included += bins[--lower];
            }
            poc[end] = minimum + (mode + 0.5) * width;
            val[end] = minimum + lower * width;
            vah[end] = minimum + (upper + 1.0) * width;
        }
        context.output("val", val);
        context.output("poc", poc);
        context.output("vah", vah);
    }
}
