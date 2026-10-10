package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Ta4jBarSeriesFactoryTest {
    @Test
    void treatsMissingAndNegativeVolumeAsZeroInsteadOfRejectingTheSeries() {
        var series = new Ta4jBarSeriesFactory().create(List.of(
                candle(0, 250L), candle(1, null), candle(2, -100L)));

        assertThat(series.getBar(0).getVolume().doubleValue()).isEqualTo(250);
        assertThat(series.getBar(1).getVolume().doubleValue()).isZero();
        assertThat(series.getBar(2).getVolume().doubleValue()).isZero();
    }

    private Candle candle(int index, Long volume) {
        return new Candle("TEST", "1d", 86400L * (index + 1), 10.0, 10.0, 10.0, 10.0, volume);
    }
}
