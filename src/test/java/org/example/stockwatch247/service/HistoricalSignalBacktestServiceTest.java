package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestOutcome;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestReport;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalSignalBacktestServiceTest {
    private final HistoricalSignalBacktestService backtestService = new HistoricalSignalBacktestService(
            new TechnicalIndicatorEnrichmentService(),
            new SignalOnTimestampDetector(28L)
    );

    @Test
    void reportsSuccessfulSignalsWhenForwardPriceConfirmsTheDetectedDirection() {
        List<Candle> candles = bullishEngulfingSetup();
        candles.add(candle(29L, 93.0, 95.5, 92.5, 95.0, 1_400L));
        candles.add(candle(30L, 95.0, 96.5, 94.5, 96.0, 1_400L));
        candles.add(candle(31L, 96.0, 98.5, 95.5, 97.5, 1_400L));

        BacktestReport report = backtestService.backtest(candles, new BacktestSettings(20, 5, 3, 2.0));

        assertThat(report.totalSignals()).isGreaterThanOrEqualTo(1);
        assertThat(report.successfulSignals()).isGreaterThanOrEqualTo(1);
        assertThat(report.precisionPercent()).isGreaterThan(0.0);
        assertThat(report.trades()).anySatisfy(trade -> {
            assertThat(trade.pattern()).isEqualTo(CandlePattern.BULLISH_ENGULFING);
            assertThat(trade.outcome()).isEqualTo(BacktestOutcome.SUCCESS);
            assertThat(trade.directionalReturnPercent()).isGreaterThanOrEqualTo(2.0);
        });
    }

    @Test
    void reportsFailedSignalsWhenForwardPriceRejectsTheDetectedDirection() {
        List<Candle> candles = bullishEngulfingSetup();
        candles.add(candle(29L, 93.0, 94.0, 91.0, 91.5, 1_400L));
        candles.add(candle(30L, 91.5, 92.0, 89.0, 90.0, 1_400L));
        candles.add(candle(31L, 90.0, 90.5, 87.0, 88.0, 1_400L));

        BacktestReport report = backtestService.backtest(candles, new BacktestSettings(20, 5, 3, 2.0));

        assertThat(report.totalSignals()).isGreaterThanOrEqualTo(1);
        assertThat(report.failedSignals()).isGreaterThanOrEqualTo(1);
        assertThat(report.trades()).anySatisfy(trade -> {
            assertThat(trade.pattern()).isEqualTo(CandlePattern.BULLISH_ENGULFING);
            assertThat(trade.outcome()).isEqualTo(BacktestOutcome.FAILURE);
            assertThat(trade.directionalReturnPercent()).isLessThanOrEqualTo(-2.0);
        });
    }

    private List<Candle> bullishEngulfingSetup() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 1; i <= 26; i++) {
            double open = 120.0 - i;
            double close = open - 1.0;
            candles.add(candle((long) i, open, open + 1.0, close - 1.0, close, 1_000L));
        }
        candles.add(candle(27L, 91.0, 92.0, 85.0, 86.0, 1_000L));
        candles.add(candle(28L, 85.0, 94.0, 84.0, 93.0, 2_200L));
        return candles;
    }

    private Candle candle(long timestamp,
                          double open,
                          double high,
                          double low,
                          double close,
                          long volume) {
        return new Candle("AAPL", "1d", timestamp, open, high, low, close, volume);
    }

    private static class SignalOnTimestampDetector extends CandlePatternDetectionService {
        private final Long signalTimestamp;

        private SignalOnTimestampDetector(Long signalTimestamp) {
            this.signalTimestamp = signalTimestamp;
        }

        @Override
        public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
            if (recentCandles == null || recentCandles.isEmpty()) {
                return List.of();
            }

            EnrichedCandle latest = recentCandles.get(recentCandles.size() - 1);
            if (!signalTimestamp.equals(latest.timestamp())) {
                return List.of();
            }

            return List.of(new DetectedSignal(
                    CandlePattern.BULLISH_ENGULFING,
                    TradeSignal.BUY,
                    latest.timestamp(),
                    latest.close()
            ));
        }
    }
}
