package org.example.stockwatch247.service;

import org.example.stockwatch247.service.HomeStocksService.StockCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HomeStocksServiceTest {

    @Test
    void sparklineRunsLeftToRightWithTheHighestCloseAtTheTop() {
        assertThat(HomeStocksService.sparkline(List.of(10.0, 20.0, 15.0)))
                .isEqualTo("M0.0 30.0 L50.0 2.0 L100.0 16.0");
    }

    @Test
    void aFlatSparklineSitsInTheMiddle() {
        assertThat(HomeStocksService.sparkline(List.of(5.0, 5.0))).isEqualTo("M0.0 16.0 L100.0 16.0");
    }

    @Test
    void oneCloseIsNotALine() {
        assertThat(HomeStocksService.sparkline(List.of(5.0))).isNull();
    }

    @Test
    void cardsColourTheirChangeAndOutlook() {
        StockCard falling = new StockCard("MSFT", "Microsoft Corp.", "USD", 524.51, -0.39, 1_792_713_600L, "M0 0 L100 0",
                "Slight sell outlook");
        StockCard rising = new StockCard("AAPL", "Apple Inc.", "USD", 232.10, 0.48, 1_792_713_600L, "M0 0 L100 0", null);

        assertThat(falling.rising()).isFalse();
        assertThat(falling.changeMagnitude()).isEqualTo(0.39);
        assertThat(falling.outlookTone()).isEqualTo("sell");
        assertThat(rising.rising()).isTrue();
        assertThat(rising.outlookTone()).isEqualTo("neutral");
        assertThat(new StockCard("NVDA", "NVIDIA", "USD", 1, 0, 0, null, "Moderate buy outlook").outlookTone()).isEqualTo("buy");
    }
}
