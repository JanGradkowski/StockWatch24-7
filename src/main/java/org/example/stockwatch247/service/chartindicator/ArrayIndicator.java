package org.example.stockwatch247.service.chartindicator;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.NaN;
import org.ta4j.core.num.Num;

/** Exposes a precomputed array as a TA4J indicator, so TA4J classes can read any chart source. */
final class ArrayIndicator extends CachedIndicator<Num> {
    private final double[] values;

    ArrayIndicator(BarSeries series, double[] values) {
        super(series);
        if (values.length != series.getBarCount()) {
            throw new IllegalStateException("Source length does not match the bar series.");
        }
        this.values = values;
    }

    @Override
    protected Num calculate(int index) {
        double value = values[index];
        return Double.isFinite(value) ? getBarSeries().numFactory().numOf(value) : NaN.NaN;
    }

    @Override
    public int getCountOfUnstableBars() {
        return 0;
    }
}
