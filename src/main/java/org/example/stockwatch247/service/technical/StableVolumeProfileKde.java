package org.example.stockwatch247.service.technical;

import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.util.Objects;

public final class StableVolumeProfileKde {
    private final Indicator<Num> prices;
    private final Indicator<Num> volumes;
    private final int lookback;
    private final double bandwidth;

    public StableVolumeProfileKde(Indicator<Num> prices, Indicator<Num> volumes,
                                  int lookback, double bandwidth) {
        this.prices = Objects.requireNonNull(prices);
        this.volumes = Objects.requireNonNull(volumes);
        if (prices.getBarSeries() == null || !Objects.equals(prices.getBarSeries(), volumes.getBarSeries())
                || !Double.isFinite(bandwidth) || bandwidth < 0) {
            throw new IllegalArgumentException("KDE requires a shared series and finite non-negative bandwidth.");
        }
        this.lookback = lookback;
        this.bandwidth = bandwidth;
    }

    public int getCountOfUnstableBars() {
        return Math.max(prices.getCountOfUnstableBars(), volumes.getCountOfUnstableBars())
                + Math.max(0, lookback - 1);
    }

    public double getModePrice(int index) {
        int begin = prices.getBarSeries().getBeginIndex();
        if (index < begin + getCountOfUnstableBars()) return Double.NaN;
        int start = lookback <= 0 ? begin : Math.max(begin, index - lookback + 1);
        double[] samples = new double[index - start + 1];
        double[] weights = new double[samples.length];
        double maximumWeight = 0;
        int count = 0;
        for (int position = start; position <= index; position++) {
            double price = prices.getValue(position).doubleValue();
            double weight = Math.abs(volumes.getValue(position).doubleValue());
            if (!Double.isFinite(price) || !Double.isFinite(weight) || weight == 0) continue;
            samples[count] = price;
            weights[count++] = weight;
            maximumWeight = Math.max(maximumWeight, weight);
        }
        if (count == 0) return Double.NaN;
        for (int sample = 0; sample < count; sample++) weights[sample] /= maximumWeight;
        double bestPrice = Double.NaN;
        double bestDensity = -1;
        for (int candidate = 0; candidate < count; candidate++) {
            double density = 0;
            double compensation = 0;
            for (int sample = 0; sample < count; sample++) {
                double distance = bandwidth == 0 ? 0 : (samples[candidate] - samples[sample]) / bandwidth;
                double kernel = bandwidth == 0 ? (samples[candidate] == samples[sample] ? 1 : 0)
                        : Math.exp(-0.5 * distance * distance);
                double increment = weights[sample] * kernel - compensation;
                double next = density + increment;
                compensation = (next - density) - increment;
                density = next;
            }
            if (density > bestDensity || density == bestDensity && samples[candidate] < bestPrice) {
                bestDensity = density;
                bestPrice = samples[candidate];
            }
        }
        return bestPrice;
    }
}
