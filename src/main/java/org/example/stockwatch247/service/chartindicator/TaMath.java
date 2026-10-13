package org.example.stockwatch247.service.chartindicator;

import java.util.Arrays;

/**
 * Series functions over plain arrays with TradingView's {@code ta.*} semantics. A value is NaN until
 * its window holds enough finite inputs, so a series that itself starts with a warm-up (for example
 * an RSI used as the source of a moving average) is handled like any price series.
 */
public final class TaMath {
    private TaMath() { }

    public static double[] nan(int size) {
        double[] values = new double[size];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private static boolean finite(double value) { return Double.isFinite(value); }

    /** True when the window ending at index holds only finite values. */
    private static boolean window(double[] values, int index, int length) {
        if (index - length + 1 < 0) return false;
        for (int offset = index - length + 1; offset <= index; offset++) if (!finite(values[offset])) return false;
        return true;
    }

    public static double[] sma(double[] source, int length) {
        double[] result = nan(source.length);
        double sum = 0;
        int count = 0;
        for (int index = 0; index < source.length; index++) {
            double value = source[index];
            if (!finite(value)) { sum = 0; count = 0; continue; }
            sum += value;
            count++;
            if (count > length) { sum -= source[index - length]; count = length; }
            if (count == length) result[index] = sum / length;
        }
        return result;
    }

    /** Exponential average seeded with the simple average of its first full window. */
    public static double[] ema(double[] source, int length) { return exponential(source, length, 2.0 / (length + 1)); }

    /** Wilder's running average (TradingView's ta.rma). */
    public static double[] rma(double[] source, int length) { return exponential(source, length, 1.0 / length); }

    private static double[] exponential(double[] source, int length, double alpha) {
        double[] result = nan(source.length);
        double previous = Double.NaN;
        double sum = 0;
        int count = 0;
        for (int index = 0; index < source.length; index++) {
            double value = source[index];
            if (!finite(value)) {
                if (!finite(previous)) { sum = 0; count = 0; }
                continue;
            }
            if (!finite(previous)) {
                sum += value;
                count++;
                if (count == length) {
                    previous = sum / length;
                    result[index] = previous;
                }
                continue;
            }
            previous = alpha * value + (1 - alpha) * previous;
            result[index] = previous;
        }
        return result;
    }

    public static double[] wma(double[] source, int length) {
        double[] result = nan(source.length);
        double denominator = length * (length + 1) / 2.0;
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
            double sum = 0;
            for (int weight = 1; weight <= length; weight++) sum += source[index - length + weight] * weight;
            result[index] = sum / denominator;
        }
        return result;
    }

    public static double[] vwma(double[] source, double[] volume, int length) {
        double[] weighted = new double[source.length];
        for (int index = 0; index < source.length; index++) weighted[index] = source[index] * volume[index];
        return divide(sma(weighted, length), sma(volume, length));
    }

    public static double[] hma(double[] source, int length) {
        int half = Math.max(1, length / 2);
        int root = Math.max(1, (int) Math.floor(Math.sqrt(length)));
        double[] fast = wma(source, half);
        double[] slow = wma(source, length);
        double[] raw = new double[source.length];
        for (int index = 0; index < source.length; index++) raw[index] = 2 * fast[index] - slow[index];
        return wma(raw, root);
    }

    public static double[] dema(double[] source, int length) {
        double[] first = ema(source, length);
        double[] second = ema(first, length);
        double[] result = new double[source.length];
        for (int index = 0; index < source.length; index++) result[index] = 2 * first[index] - second[index];
        return result;
    }

    public static double[] tema(double[] source, int length) {
        double[] first = ema(source, length);
        double[] second = ema(first, length);
        double[] third = ema(second, length);
        double[] result = new double[source.length];
        for (int index = 0; index < source.length; index++) result[index] = 3 * (first[index] - second[index]) + third[index];
        return result;
    }

    /** Triangular average: a simple average of a simple average. */
    public static double[] tma(double[] source, int length) {
        int first = (int) Math.ceil(length / 2.0);
        int second = length / 2 + 1;
        return sma(sma(source, first), second);
    }

    /** Least-squares line value at the latest bar of each window, shifted by offset bars (ta.linreg). */
    public static double[] linreg(double[] source, int length, int offset) {
        double[] result = nan(source.length);
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
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
            result[index] = intercept + slope * (length - 1 - offset);
        }
        return result;
    }

    /** Slope per bar of the least-squares line of each window. */
    public static double[] linregSlope(double[] source, int length) {
        double[] result = nan(source.length);
        double[] now = linreg(source, length, 0);
        double[] before = linreg(source, length, 1);
        for (int index = 0; index < source.length; index++) result[index] = now[index] - before[index];
        return result;
    }

    public static double[] alma(double[] source, int length, double offset, double sigma) {
        double[] result = nan(source.length);
        double m = offset * (length - 1);
        double s = length / sigma;
        double[] weights = new double[length];
        double norm = 0;
        for (int position = 0; position < length; position++) {
            weights[position] = Math.exp(-((position - m) * (position - m)) / (2 * s * s));
            norm += weights[position];
        }
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
            double sum = 0;
            for (int position = 0; position < length; position++) sum += source[index - length + 1 + position] * weights[position];
            result[index] = sum / norm;
        }
        return result;
    }

    /** Kaufman's adaptive average. */
    public static double[] kama(double[] source, int length, int fast, int slow) {
        double[] result = nan(source.length);
        double fastest = 2.0 / (fast + 1);
        double slowest = 2.0 / (slow + 1);
        double previous = Double.NaN;
        for (int index = 0; index < source.length; index++) {
            if (!finite(source[index])) continue;
            if (index < length || !window(source, index, length + 1)) {
                continue;
            }
            if (!finite(previous)) previous = source[index - 1];
            double direction = Math.abs(source[index] - source[index - length]);
            double volatility = 0;
            for (int offset = index - length + 1; offset <= index; offset++) volatility += Math.abs(source[offset] - source[offset - 1]);
            double efficiency = volatility == 0 ? 0 : direction / volatility;
            double smoothing = Math.pow(efficiency * (fastest - slowest) + slowest, 2);
            previous = previous + smoothing * (source[index] - previous);
            result[index] = previous;
        }
        return result;
    }

    public static double[] zlema(double[] source, int length) {
        int lag = (length - 1) / 2;
        double[] adjusted = nan(source.length);
        for (int index = lag; index < source.length; index++) adjusted[index] = source[index] + (source[index] - source[index - lag]);
        return ema(adjusted, length);
    }

    public static double[] mcginley(double[] source, int length) {
        double[] result = nan(source.length);
        double[] seed = ema(source, length);
        double previous = Double.NaN;
        for (int index = 0; index < source.length; index++) {
            if (!finite(source[index])) continue;
            if (!finite(previous)) {
                if (finite(seed[index])) { previous = seed[index]; result[index] = previous; }
                continue;
            }
            double ratio = previous == 0 ? 1 : source[index] / previous;
            previous = previous + (source[index] - previous) / (length * Math.pow(ratio, 4));
            result[index] = previous;
        }
        return result;
    }

    /** Variable index dynamic average: an EMA scaled by the absolute Chande momentum. */
    public static double[] vidya(double[] source, int length) {
        double[] result = nan(source.length);
        double alpha = 2.0 / (length + 1);
        double[] cmo = cmo(source, length);
        double previous = Double.NaN;
        for (int index = 0; index < source.length; index++) {
            if (!finite(cmo[index]) || !finite(source[index])) continue;
            double factor = alpha * Math.abs(cmo[index]) / 100;
            previous = finite(previous) ? factor * source[index] + (1 - factor) * previous : source[index];
            result[index] = previous;
        }
        return result;
    }

    /** Symmetrically weighted average of the last four values (ta.swma). */
    public static double[] swma(double[] source) {
        double[] result = nan(source.length);
        for (int index = 3; index < source.length; index++) {
            if (!window(source, index, 4)) continue;
            result[index] = source[index - 3] / 6 + source[index - 2] * 2 / 6 + source[index - 1] * 2 / 6 + source[index] / 6;
        }
        return result;
    }

    public static double[] movingAverage(String type, double[] source, double[] volume, int length) {
        return switch (type) {
            case "SMA" -> sma(source, length);
            case "EMA" -> ema(source, length);
            case "SMMA", "RMA" -> rma(source, length);
            case "WMA" -> wma(source, length);
            case "VWMA" -> vwma(source, volume, length);
            case "HMA" -> hma(source, length);
            case "DEMA" -> dema(source, length);
            case "TEMA" -> tema(source, length);
            case "LSMA" -> linreg(source, length, 0);
            case "ZLEMA" -> zlema(source, length);
            case "TMA" -> tma(source, length);
            case "ALMA" -> alma(source, length, 0.85, 6);
            case "KAMA" -> kama(source, length, 2, 30);
            case "MCGINLEY" -> mcginley(source, length);
            case "VIDYA" -> vidya(source, length);
            default -> throw new IllegalArgumentException("Unsupported moving average type: " + type);
        };
    }

    /** Population standard deviation, as TradingView's ta.stdev. */
    public static double[] stdev(double[] source, int length) {
        double[] result = nan(source.length);
        double[] mean = sma(source, length);
        for (int index = length - 1; index < source.length; index++) {
            if (!finite(mean[index])) continue;
            double sum = 0;
            for (int offset = index - length + 1; offset <= index; offset++) {
                double difference = source[offset] - mean[index];
                sum += difference * difference;
            }
            result[index] = Math.sqrt(sum / length);
        }
        return result;
    }

    /** Mean absolute deviation from the simple average (ta.dev). */
    public static double[] deviation(double[] source, int length) {
        double[] result = nan(source.length);
        double[] mean = sma(source, length);
        for (int index = length - 1; index < source.length; index++) {
            if (!finite(mean[index])) continue;
            double sum = 0;
            for (int offset = index - length + 1; offset <= index; offset++) sum += Math.abs(source[offset] - mean[index]);
            result[index] = sum / length;
        }
        return result;
    }

    public static double[] highest(double[] source, int length) {
        double[] result = nan(source.length);
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
            double value = Double.NEGATIVE_INFINITY;
            for (int offset = index - length + 1; offset <= index; offset++) value = Math.max(value, source[offset]);
            result[index] = value;
        }
        return result;
    }

    public static double[] lowest(double[] source, int length) {
        double[] result = nan(source.length);
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
            double value = Double.POSITIVE_INFINITY;
            for (int offset = index - length + 1; offset <= index; offset++) value = Math.min(value, source[offset]);
            result[index] = value;
        }
        return result;
    }

    /** Bars since the highest value of the window, as a non-positive offset (ta.highestbars). */
    public static double[] highestBars(double[] source, int length) {
        double[] result = nan(source.length);
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
            int best = index;
            for (int offset = index; offset >= index - length + 1; offset--) if (source[offset] > source[best]) best = offset;
            result[index] = best - index;
        }
        return result;
    }

    public static double[] lowestBars(double[] source, int length) {
        double[] result = nan(source.length);
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
            int best = index;
            for (int offset = index; offset >= index - length + 1; offset--) if (source[offset] < source[best]) best = offset;
            result[index] = best - index;
        }
        return result;
    }

    public static double[] sum(double[] source, int length) {
        double[] result = nan(source.length);
        double total = 0;
        int count = 0;
        for (int index = 0; index < source.length; index++) {
            double value = source[index];
            if (!finite(value)) { total = 0; count = 0; continue; }
            total += value;
            count++;
            if (count > length) { total -= source[index - length]; count = length; }
            if (count == length) result[index] = total;
        }
        return result;
    }

    public static double[] cum(double[] source) {
        double[] result = nan(source.length);
        double total = 0;
        boolean started = false;
        for (int index = 0; index < source.length; index++) {
            if (finite(source[index])) { total += source[index]; started = true; }
            if (started) result[index] = total;
        }
        return result;
    }

    /** The value {@code bars} bars ago. */
    public static double[] previous(double[] source, int bars) {
        double[] result = nan(source.length);
        for (int index = Math.max(0, bars); index < source.length; index++) result[index] = source[index - bars];
        return result;
    }

    public static double[] change(double[] source, int bars) {
        double[] result = nan(source.length);
        for (int index = bars; index < source.length; index++) result[index] = source[index] - source[index - bars];
        return result;
    }

    public static double[] roc(double[] source, int length) {
        double[] result = nan(source.length);
        for (int index = length; index < source.length; index++) {
            double base = source[index - length];
            if (finite(base) && base != 0) result[index] = 100 * (source[index] - base) / base;
        }
        return result;
    }

    public static double[] trueRange(double[] high, double[] low, double[] close) {
        double[] result = new double[close.length];
        for (int index = 0; index < close.length; index++) {
            double range = high[index] - low[index];
            if (index == 0) { result[index] = range; continue; }
            result[index] = Math.max(range, Math.max(Math.abs(high[index] - close[index - 1]), Math.abs(low[index] - close[index - 1])));
        }
        return result;
    }

    public static double[] atr(double[] high, double[] low, double[] close, int length) {
        return rma(trueRange(high, low, close), length);
    }

    /** Wilder's RSI on any series (ta.rsi). */
    public static double[] rsi(double[] source, int length) {
        double[] gains = nan(source.length);
        double[] losses = nan(source.length);
        for (int index = 1; index < source.length; index++) {
            double difference = source[index] - source[index - 1];
            if (!finite(difference)) continue;
            gains[index] = Math.max(difference, 0);
            losses[index] = Math.max(-difference, 0);
        }
        double[] up = rma(gains, length);
        double[] down = rma(losses, length);
        double[] result = nan(source.length);
        for (int index = 0; index < source.length; index++) {
            if (!finite(up[index]) || !finite(down[index])) continue;
            result[index] = down[index] == 0 ? 100 : (up[index] == 0 ? 0 : 100 - 100 / (1 + up[index] / down[index]));
        }
        return result;
    }

    /** Stochastic %K of a source against its own or other high/low series (ta.stoch). */
    public static double[] stoch(double[] source, double[] high, double[] low, int length) {
        double[] highest = highest(high, length);
        double[] lowest = lowest(low, length);
        double[] result = nan(source.length);
        for (int index = 0; index < source.length; index++) {
            double range = highest[index] - lowest[index];
            if (!finite(range) || !finite(source[index])) continue;
            result[index] = range == 0 ? 50 : 100 * (source[index] - lowest[index]) / range;
        }
        return result;
    }

    public static double[] cci(double[] source, int length) {
        double[] mean = sma(source, length);
        double[] deviation = deviation(source, length);
        double[] result = nan(source.length);
        for (int index = 0; index < source.length; index++) {
            if (!finite(deviation[index]) || deviation[index] == 0) continue;
            result[index] = (source[index] - mean[index]) / (0.015 * deviation[index]);
        }
        return result;
    }

    /** Chande momentum oscillator. */
    public static double[] cmo(double[] source, int length) {
        double[] up = nan(source.length);
        double[] down = nan(source.length);
        for (int index = 1; index < source.length; index++) {
            double difference = source[index] - source[index - 1];
            if (!finite(difference)) continue;
            up[index] = Math.max(difference, 0);
            down[index] = Math.max(-difference, 0);
        }
        double[] upSum = sum(up, length);
        double[] downSum = sum(down, length);
        double[] result = nan(source.length);
        for (int index = 0; index < source.length; index++) {
            double total = upSum[index] + downSum[index];
            if (finite(total)) result[index] = total == 0 ? 0 : 100 * (upSum[index] - downSum[index]) / total;
        }
        return result;
    }

    /** Double-smoothed momentum ratio used by the true strength index (ta.tsi, as a percentage). */
    public static double[] tsi(double[] source, int shortLength, int longLength) {
        double[] momentum = change(source, 1);
        double[] absolute = abs(momentum);
        double[] numerator = ema(ema(momentum, longLength), shortLength);
        double[] denominator = ema(ema(absolute, longLength), shortLength);
        double[] result = nan(source.length);
        for (int index = 0; index < source.length; index++) {
            if (finite(denominator[index]) && denominator[index] != 0) result[index] = 100 * numerator[index] / denominator[index];
        }
        return result;
    }

    public static double[] median(double[] source, int length) {
        double[] result = nan(source.length);
        double[] window = new double[length];
        for (int index = length - 1; index < source.length; index++) {
            if (!window(source, index, length)) continue;
            System.arraycopy(source, index - length + 1, window, 0, length);
            Arrays.sort(window);
            result[index] = length % 2 == 1 ? window[length / 2] : (window[length / 2 - 1] + window[length / 2]) / 2;
        }
        return result;
    }

    public static double[] correlation(double[] first, double[] second, int length) {
        double[] result = nan(first.length);
        for (int index = length - 1; index < first.length; index++) {
            if (!window(first, index, length) || !window(second, index, length)) continue;
            double meanA = 0;
            double meanB = 0;
            for (int offset = index - length + 1; offset <= index; offset++) { meanA += first[offset]; meanB += second[offset]; }
            meanA /= length;
            meanB /= length;
            double covariance = 0;
            double varianceA = 0;
            double varianceB = 0;
            for (int offset = index - length + 1; offset <= index; offset++) {
                double a = first[offset] - meanA;
                double b = second[offset] - meanB;
                covariance += a * b;
                varianceA += a * a;
                varianceB += b * b;
            }
            double denominator = Math.sqrt(varianceA * varianceB);
            if (denominator > 0) result[index] = covariance / denominator;
        }
        return result;
    }

    public static double[] abs(double[] source) {
        double[] result = new double[source.length];
        for (int index = 0; index < source.length; index++) result[index] = Math.abs(source[index]);
        return result;
    }

    public static double[] add(double[] first, double[] second) {
        double[] result = new double[first.length];
        for (int index = 0; index < first.length; index++) result[index] = first[index] + second[index];
        return result;
    }

    public static double[] subtract(double[] first, double[] second) {
        double[] result = new double[first.length];
        for (int index = 0; index < first.length; index++) result[index] = first[index] - second[index];
        return result;
    }

    public static double[] multiply(double[] first, double[] second) {
        double[] result = new double[first.length];
        for (int index = 0; index < first.length; index++) result[index] = first[index] * second[index];
        return result;
    }

    public static double[] scale(double[] source, double factor) {
        double[] result = new double[source.length];
        for (int index = 0; index < source.length; index++) result[index] = source[index] * factor;
        return result;
    }

    public static double[] offset(double[] source, double amount) {
        double[] result = new double[source.length];
        for (int index = 0; index < source.length; index++) result[index] = source[index] + amount;
        return result;
    }

    /** Element-wise division; a zero denominator gives NaN rather than an infinity. */
    public static double[] divide(double[] numerator, double[] denominator) {
        double[] result = nan(numerator.length);
        for (int index = 0; index < numerator.length; index++) {
            if (finite(denominator[index]) && denominator[index] != 0) result[index] = numerator[index] / denominator[index];
        }
        return result;
    }
}
