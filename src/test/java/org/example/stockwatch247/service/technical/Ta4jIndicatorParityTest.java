package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class Ta4jIndicatorParityTest {
    private static final double TOLERANCE = 0.000_01;
    private final Ta4jBarSeriesFactory seriesFactory = new Ta4jBarSeriesFactory();
    private final Ta4jIndicatorRegistry registry = new Ta4jIndicatorRegistry();

    @Test
    void ta4jCoreIndicatorsMatchIndependentReferenceFormulasOnOscillatingFixture() {
        List<Candle> candles = oscillatingCandles(360);
        TechnicalIndicatorParameters parameters = parameters();
        BarSeries series = seriesFactory.create(candles);
        Ta4jIndicatorRegistry.CoreIndicators ta4j = registry.core(series, parameters);
        IndependentReference reference = IndependentReference.calculate(candles, parameters);
        int index = candles.size() - 1;

        assertClose(ta4j.averageVolume().getValue(index).doubleValue(), reference.averageVolume[index]);
        assertClose(ta4j.rsi().getValue(index).doubleValue(), reference.rsi[index]);
        assertClose(ta4j.fastEma().getValue(index).doubleValue(), reference.fastEma[index]);
        assertClose(ta4j.slowEma().getValue(index).doubleValue(), reference.slowEma[index]);
        assertClose(ta4j.longSma().getValue(index).doubleValue(), reference.longSma[index]);
        assertClose(ta4j.macd().getValue(index).doubleValue(), reference.macd[index]);
        assertClose(ta4j.macdSignal().getValue(index).doubleValue(), reference.macdSignal[index]);
        assertClose(ta4j.macdHistogram().getValue(index).doubleValue(), reference.macdHistogram[index]);
        assertClose(ta4j.cci().getValue(index).doubleValue(), reference.cci[index]);
        assertClose(ta4j.bollingerMiddle().getValue(index).doubleValue(), reference.bollingerMiddle[index]);
        assertClose(ta4j.bollingerLower().getValue(index).doubleValue(), reference.bollingerLower[index]);
        assertClose(ta4j.bollingerUpper().getValue(index).doubleValue(), reference.bollingerUpper[index]);
        assertClose(ta4j.atr().getValue(index).doubleValue(), reference.atr[index]);
        assertClose(ta4j.rollingVwap().getValue(index).doubleValue(), reference.vwap[index]);
    }

    @Test
    void goldenLinearFixtureLocksFormulaSemantics() {
        List<Candle> candles = linearCandles(30);
        TechnicalIndicatorParameters parameters = new TechnicalIndicatorParameters(
                5, 5, 3, 5, 5, 3, 5, 3, 5, 5, 2.0, 5, 5, 10);
        Ta4jIndicatorRegistry.CoreIndicators indicators = registry.core(
                seriesFactory.create(candles), parameters);
        int index = candles.size() - 1;

        assertThat(indicators.averageVolume().getValue(index).doubleValue()).isEqualTo(1_000.0);
        assertThat(indicators.rsi().getValue(index).doubleValue()).isEqualTo(100.0);
        assertThat(indicators.longSma().getValue(index).doubleValue()).isEqualTo(28.0);
        assertThat(indicators.bollingerMiddle().getValue(index).doubleValue()).isEqualTo(28.0);
        assertThat(indicators.bollingerLower().getValue(index).doubleValue())
                .isCloseTo(28.0 - 2.0 * Math.sqrt(2.0), within(TOLERANCE));
        assertThat(indicators.bollingerUpper().getValue(index).doubleValue())
                .isCloseTo(28.0 + 2.0 * Math.sqrt(2.0), within(TOLERANCE));
        assertThat(indicators.atr().getValue(index).doubleValue()).isCloseTo(2.0, within(TOLERANCE));
        assertThat(indicators.rollingVwap().getValue(index).doubleValue()).isCloseTo(28.0, within(TOLERANCE));
        assertThat(indicators.cci().getValue(index).doubleValue())
                .isCloseTo(111.111_111_111, within(TOLERANCE));
    }

    private void assertClose(double actual, double expected) {
        assertThat(actual).isCloseTo(expected, within(TOLERANCE));
    }

    private TechnicalIndicatorParameters parameters() {
        return new TechnicalIndicatorParameters(
                14, 14, 20, 50, 200, 12, 26, 9, 20, 20,
                2.0, 20, 20, 60);
    }

    private List<Candle> oscillatingCandles(int count) {
        Instant first = Instant.parse("2024-01-02T21:00:00Z");
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double close = 100.0 + index * 0.08 + Math.sin(index * 0.21) * 2.4
                    + Math.cos(index * 0.07) * 0.8;
            double open = close + Math.sin(index * 0.31) * 0.45;
            double high = Math.max(open, close) + 0.8 + (index % 5) * 0.06;
            double low = Math.min(open, close) - 0.7 - (index % 7) * 0.04;
            candles.add(new Candle("FIX", "1d", first.plus(index, ChronoUnit.DAYS).getEpochSecond(),
                    open, high, low, close, 800_000L + (index % 19) * 17_000L));
        }
        return candles;
    }

    private List<Candle> linearCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            candles.add(new Candle("LINEAR", "1d", index * 86_400L,
                    index - 0.25, index + 1.0, index - 1.0, (double) index, 1_000L));
        }
        return candles;
    }

    private record IndependentReference(
            double[] averageVolume,
            double[] rsi,
            double[] fastEma,
            double[] slowEma,
            double[] longSma,
            double[] macd,
            double[] macdSignal,
            double[] macdHistogram,
            double[] cci,
            double[] bollingerMiddle,
            double[] bollingerLower,
            double[] bollingerUpper,
            double[] atr,
            double[] vwap) {

        private static IndependentReference calculate(List<Candle> candles,
                                                      TechnicalIndicatorParameters parameters) {
            double[] close = candles.stream().mapToDouble(Candle::getClosePrice).toArray();
            double[] high = candles.stream().mapToDouble(Candle::getHighPrice).toArray();
            double[] low = candles.stream().mapToDouble(Candle::getLowPrice).toArray();
            double[] volume = candles.stream().mapToDouble(candle -> candle.getVolume()).toArray();
            double[] typical = new double[close.length];
            double[] trueRange = new double[close.length];
            for (int index = 0; index < close.length; index++) {
                typical[index] = (high[index] + low[index] + close[index]) / 3.0;
                trueRange[index] = index == 0 ? high[index] - low[index]
                        : Math.max(high[index] - low[index], Math.max(
                                Math.abs(high[index] - close[index - 1]),
                                Math.abs(low[index] - close[index - 1])));
            }
            double[] fast = ema(close, parameters.fastEmaPeriod());
            double[] slow = ema(close, parameters.slowEmaPeriod());
            double[] macd = subtract(
                    ema(close, parameters.macdFastPeriod()),
                    ema(close, parameters.macdSlowPeriod()));
            double[] signal = ema(macd, parameters.macdSignalPeriod());
            double[] middle = sma(close, parameters.bollingerPeriod());
            double[] deviation = populationDeviation(close, parameters.bollingerPeriod());
            return new IndependentReference(
                    sma(volume, parameters.volumePeriod()),
                    rsi(close, parameters.rsiPeriod()),
                    fast,
                    slow,
                    sma(close, parameters.longSmaPeriod()),
                    macd,
                    signal,
                    subtract(macd, signal),
                    cci(typical, parameters.cciPeriod()),
                    middle,
                    combine(middle, deviation, -parameters.bollingerDeviation()),
                    combine(middle, deviation, parameters.bollingerDeviation()),
                    modifiedMovingAverage(trueRange, parameters.atrPeriod()),
                    vwap(typical, volume, parameters.vwapPeriod()));
        }

        private static double[] sma(double[] values, int period) {
            double[] result = new double[values.length];
            double sum = 0.0;
            for (int index = 0; index < values.length; index++) {
                sum += values[index];
                if (index >= period) sum -= values[index - period];
                result[index] = sum / Math.min(index + 1, period);
            }
            return result;
        }

        private static double[] ema(double[] values, int period) {
            double[] result = new double[values.length];
            double alpha = 2.0 / (period + 1.0);
            result[0] = values[0];
            for (int index = 1; index < values.length; index++) {
                result[index] = alpha * values[index] + (1.0 - alpha) * result[index - 1];
            }
            return result;
        }

        private static double[] modifiedMovingAverage(double[] values, int period) {
            double[] result = new double[values.length];
            double alpha = 1.0 / period;
            result[0] = values[0];
            for (int index = 1; index < values.length; index++) {
                result[index] = alpha * values[index] + (1.0 - alpha) * result[index - 1];
            }
            return result;
        }

        private static double[] rsi(double[] close, int period) {
            double[] gains = new double[close.length];
            double[] losses = new double[close.length];
            for (int index = 1; index < close.length; index++) {
                double change = close[index] - close[index - 1];
                gains[index] = Math.max(0.0, change);
                losses[index] = Math.max(0.0, -change);
            }
            double[] averageGain = modifiedMovingAverage(gains, period);
            double[] averageLoss = modifiedMovingAverage(losses, period);
            double[] result = new double[close.length];
            for (int index = 0; index < close.length; index++) {
                result[index] = averageLoss[index] == 0.0 ? 100.0
                        : 100.0 - 100.0 / (1.0 + averageGain[index] / averageLoss[index]);
            }
            return result;
        }

        private static double[] cci(double[] typical, int period) {
            double[] mean = sma(typical, period);
            double[] result = new double[typical.length];
            for (int index = 0; index < typical.length; index++) {
                int start = Math.max(0, index - period + 1);
                double deviation = 0.0;
                for (int cursor = start; cursor <= index; cursor++) {
                    deviation += Math.abs(typical[cursor] - mean[index]);
                }
                deviation /= index - start + 1;
                result[index] = deviation == 0.0 ? 0.0
                        : (typical[index] - mean[index]) / (0.015 * deviation);
            }
            return result;
        }

        private static double[] populationDeviation(double[] values, int period) {
            double[] mean = sma(values, period);
            double[] result = new double[values.length];
            for (int index = 0; index < values.length; index++) {
                int start = Math.max(0, index - period + 1);
                double variance = 0.0;
                for (int cursor = start; cursor <= index; cursor++) {
                    double difference = values[cursor] - mean[index];
                    variance += difference * difference;
                }
                result[index] = Math.sqrt(variance / (index - start + 1));
            }
            return result;
        }

        private static double[] vwap(double[] typical, double[] volume, int period) {
            double[] result = new double[typical.length];
            for (int index = 0; index < typical.length; index++) {
                int start = Math.max(0, index - period + 1);
                double weighted = 0.0;
                double totalVolume = 0.0;
                for (int cursor = start; cursor <= index; cursor++) {
                    weighted += typical[cursor] * volume[cursor];
                    totalVolume += volume[cursor];
                }
                result[index] = totalVolume == 0.0 ? 0.0 : weighted / totalVolume;
            }
            return result;
        }

        private static double[] subtract(double[] left, double[] right) {
            double[] result = new double[left.length];
            for (int index = 0; index < left.length; index++) result[index] = left[index] - right[index];
            return result;
        }

        private static double[] combine(double[] mean, double[] deviation, double multiplier) {
            double[] result = new double[mean.length];
            for (int index = 0; index < mean.length; index++) {
                result[index] = mean[index] + deviation[index] * multiplier;
            }
            return result;
        }
    }
}
