package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.junit.jupiter.api.Test;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.supportresistance.VolumeProfileKDEIndicator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class StableVolumeProfileKdeTest {
    @Test
    void matchesDecimalReferenceForOrdinaryInputsAndEveryCausalPrefix() {
        Random random = new Random(491);
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < 45; index++) {
            candles.add(candle(index, 100 + random.nextDouble(), 1 + random.nextInt(1000)));
        }
        var series = new Ta4jBarSeriesFactory().create(candles);
        var price = new TypicalPriceIndicator(series);
        var volume = new VolumeIndicator(series);
        var reference = new VolumeProfileKDEIndicator(price, volume, 12, series.numFactory().numOf(1));
        var stable = new StableVolumeProfileKde(price, volume, 12, 1);
        assertThat(stable.getCountOfUnstableBars()).isEqualTo(reference.getCountOfUnstableBars());
        assertThat(stable.getModePrice(10)).isNaN();
        for (int index = 11; index < candles.size(); index++) {
            assertThat(stable.getModePrice(index)).isCloseTo(reference.getModePrice(index).doubleValue(), within(1e-12));
            var prefix = new Ta4jBarSeriesFactory().create(candles.subList(0, index + 1));
            assertThat(new StableVolumeProfileKde(new TypicalPriceIndicator(prefix),
                    new VolumeIndicator(prefix), 12, 1).getModePrice(index)).isEqualTo(stable.getModePrice(index));
        }
    }

    @Test
    void handlesExtremePriceToBandwidthRatiosWithoutDecimalTaylorExpansion() {
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < 60; index++) candles.add(candle(index, 160 + index * 3, index == 42 ? 10000 : 100));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThat(mode(candles, 60, 0.025)).isEqualTo(286));
    }

    @Test
    void preservesDuplicateWeightsZeroBandwidthLowerPriceTiesAndNearTies() {
        assertThat(mode(List.of(candle(0, 11, 100), candle(1, 10, 100)), 2, 0.001)).isEqualTo(10);
        assertThat(mode(List.of(candle(0, 11, 101), candle(1, 10, 100)), 2, 1)).isEqualTo(11);
        assertThat(mode(List.of(candle(0, 11, 1000000001), candle(1, 10, 1000000000)), 2, 1)).isEqualTo(11);
        assertThat(mode(List.of(candle(0, 11, 120), candle(1, 10, 70), candle(2, 10, 70)), 3, 0)).isEqualTo(10);
        assertThat(mode(List.of(candle(0, 10, 0), candle(1, 11, 0)), 2, 1)).isNaN();
        assertThat(mode(List.of(candle(0, 10, 0), candle(1, 11, -100)), 2, 1)).isNaN();
    }

    @Test
    void matchesIndependentHighPrecisionGaussianForSeparatedSamples() {
        var precision = new java.math.MathContext(70);
        Random random = new Random(981);
        for (int scenario = 0; scenario < 12; scenario++) {
            List<Candle> candles = new ArrayList<>();
            for (int index = 0; index < 10; index++) candles.add(candle(index, 10 + random.nextInt(20), 1 + random.nextInt(1000)));
            java.math.BigDecimal bestDensity = java.math.BigDecimal.valueOf(-1);
            double bestPrice = Double.NaN;
            for (Candle candidate : candles) {
                java.math.BigDecimal density = java.math.BigDecimal.ZERO;
                for (Candle sample : candles) {
                    var distance = java.math.BigDecimal.valueOf(candidate.getClosePrice() - sample.getClosePrice());
                    var magnitude = distance.multiply(distance).divide(java.math.BigDecimal.TWO);
                    density = density.add(negativeExponential(magnitude, precision)
                            .multiply(java.math.BigDecimal.valueOf(sample.getVolume()), precision), precision);
                }
                if (density.compareTo(bestDensity) > 0 || density.compareTo(bestDensity) == 0 && candidate.getClosePrice() < bestPrice) {
                    bestDensity = density;
                    bestPrice = candidate.getClosePrice();
                }
            }
            assertThat(mode(candles, candles.size(), 1)).isEqualTo(bestPrice);
        }
    }

    private java.math.BigDecimal negativeExponential(java.math.BigDecimal magnitude, java.math.MathContext precision) {
        int squarings = 0;
        while (magnitude.compareTo(java.math.BigDecimal.valueOf(0.5)) > 0) {
            magnitude = magnitude.divide(java.math.BigDecimal.TWO);
            squarings++;
        }
        var sum = java.math.BigDecimal.ONE;
        var term = java.math.BigDecimal.ONE;
        for (int order = 1; order < 200; order++) {
            term = term.multiply(magnitude, precision).divide(java.math.BigDecimal.valueOf(order), precision);
            sum = sum.add(term, precision);
            if (term.abs().compareTo(java.math.BigDecimal.ONE.scaleByPowerOfTen(-65)) < 0) break;
        }
        for (int square = 0; square < squarings; square++) sum = sum.multiply(sum, precision);
        return java.math.BigDecimal.ONE.divide(sum, precision);
    }

    private double mode(List<Candle> candles, int window, double bandwidth) {
        var series = new Ta4jBarSeriesFactory().create(candles);
        return new StableVolumeProfileKde(new TypicalPriceIndicator(series), new VolumeIndicator(series),
                window, bandwidth).getModePrice(candles.size() - 1);
    }

    private Candle candle(int index, double price, long volume) {
        return new Candle("TEST", "1d", 86400L * (index + 1), price, price, price, price, volume);
    }
}
