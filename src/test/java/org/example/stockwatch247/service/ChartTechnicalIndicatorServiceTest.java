package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartTechnicalIndicatorServiceTest {

    @Test
    void calculatesEveryAutomatedOutlookIndicatorAsFiniteChartSeries() {
        CandleRepository repository = mock(CandleRepository.class);
        when(repository.findBySymbolAndTimeIntervalOrderByTimestampAsc("AAPL", "1d"))
                .thenReturn(candles(260));
        ChartTechnicalIndicatorService service = new ChartTechnicalIndicatorService(repository);

        List<ChartTechnicalIndicatorService.IndicatorConfiguration> configurations = List.of(
                config("ema", "EMA", Map.of("period", 20.0)),
                config("sma", "SMA", Map.of("period", 50.0)),
                config("bollinger", "BOLLINGER", Map.of("period", 20.0, "deviation", 2.0)),
                config("vwap", "VWAP", Map.of("period", 20.0)),
                config("levels", "SUPPORT_RESISTANCE", Map.of("period", 20.0)),
                config("rsi", "RSI", Map.of("period", 14.0, "lower", 30.0, "upper", 70.0)),
                config("macd", "MACD", Map.of("fast", 12.0, "slow", 26.0, "signal", 9.0)),
                config("cci", "CCI", Map.of("period", 20.0)),
                config("atr", "ATR", Map.of("period", 14.0)),
                config("relativeVolume", "RELATIVE_VOLUME", Map.of("period", 20.0)),
                config("adx", "ADX_DMI", Map.of("period", 14.0)),
                config("stochastic", "STOCHASTIC", Map.of("period", 14.0)),
                config("stochasticRsi", "STOCHASTIC_RSI", Map.of("period", 14.0)),
                config("obv", "OBV", Map.of()),
                config("mfi", "MFI", Map.of("period", 14.0)),
                config("donchian", "DONCHIAN", Map.of("period", 20.0)),
                config("keltner", "KELTNER", Map.of("period", 20.0, "atrPeriod", 14.0, "multiplier", 2.0)),
                config("trend", "TREND", Map.of("period", 20.0)),
                config("profile", "VOLUME_PROFILE", Map.of("period", 60.0, "valueArea", 0.70)),
                config("kde", "KDE_VOLUME_PROFILE", Map.of("period", 60.0)));

        var result = service.calculate("aapl", "1d",
                new ChartTechnicalIndicatorService.IndicatorBatchRequest(
                        100L * 86_400L, 260L * 86_400L, configurations));

        assertThat(result.indicators()).hasSize(configurations.size());
        assertThat(result.indicators()).allSatisfy(indicator -> {
            assertThat(indicator.placement()).isIn("PRICE", "PANEL");
            assertThat(indicator.series()).isNotEmpty();
            assertThat(indicator.series()).allSatisfy(series -> {
                assertThat(series.points()).isNotEmpty();
                assertThat(series.points()).allSatisfy(point -> assertThat(point.value()).isFinite());
            });
        });
        assertThat(result.indicators()).filteredOn(indicator -> indicator.id().equals("macd"))
                .singleElement().satisfies(indicator -> assertThat(indicator.series()).hasSize(3));
        assertThat(result.indicators()).filteredOn(indicator -> indicator.id().equals("profile"))
                .singleElement().satisfies(indicator -> assertThat(indicator.series()).hasSize(3));
    }

    @Test
    void rejectsInvalidPeriodsAndMacdOrdering() {
        CandleRepository repository = mock(CandleRepository.class);
        when(repository.findBySymbolAndTimeIntervalOrderByTimestampAsc("AAPL", "1d"))
                .thenReturn(candles(40));
        ChartTechnicalIndicatorService service = new ChartTechnicalIndicatorService(repository);

        assertThatThrownBy(() -> service.calculate("AAPL", "1d", request(
                config("ema", "EMA", Map.of("period", 501.0)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 to 500");
        assertThatThrownBy(() -> service.calculate("AAPL", "1d", request(
                config("macd", "MACD", Map.of("fast", 30.0, "slow", 20.0, "signal", 9.0)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fast period");
    }

    private ChartTechnicalIndicatorService.IndicatorBatchRequest request(
            ChartTechnicalIndicatorService.IndicatorConfiguration configuration) {
        return new ChartTechnicalIndicatorService.IndicatorBatchRequest(null, null, List.of(configuration));
    }

    private ChartTechnicalIndicatorService.IndicatorConfiguration config(
            String id, String type, Map<String, Double> parameters) {
        return new ChartTechnicalIndicatorService.IndicatorConfiguration(id, type, parameters);
    }

    private List<Candle> candles(int count) {
        List<Candle> candles = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            double trend = 80 + index * 0.35;
            double wave = Math.sin(index / 5.0) * 4;
            double close = trend + wave;
            double open = close - Math.cos(index / 4.0);
            candles.add(new Candle("AAPL", "1d", index * 86_400L,
                    open, Math.max(open, close) + 2, Math.min(open, close) - 2,
                    close, 1_000L + index * 13L));
        }
        return candles;
    }
}
