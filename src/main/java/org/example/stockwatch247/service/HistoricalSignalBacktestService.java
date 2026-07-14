package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class HistoricalSignalBacktestService {
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService detectionService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;

    @Autowired
    public HistoricalSignalBacktestService(TechnicalIndicatorEnrichmentService enrichmentService,
                                           CandlePatternDetectionService detectionService,
                                           ElliottWaveDetectionService elliottWaveDetectionService) {
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
        this.elliottWaveDetectionService = elliottWaveDetectionService;
    }

    public HistoricalSignalBacktestService(TechnicalIndicatorEnrichmentService enrichmentService,
                                           CandlePatternDetectionService detectionService) {
        this(enrichmentService, detectionService, new ElliottWaveDetectionService());
    }

    public BacktestReport backtest(List<Candle> historicalCandles, BacktestSettings settings) {
        return backtest(historicalCandles, settings, false);
    }

    public BacktestReport backtest(List<Candle> historicalCandles,
                                   BacktestSettings settings,
                                   boolean includeElliottWaves) {
        BacktestSettings validatedSettings = settings == null ? BacktestSettings.defaults() : settings.validated();
        List<Candle> candles = historicalCandles == null
                ? List.of()
                : historicalCandles.stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();

        if (candles.size() < validatedSettings.minimumHistoricalCandles() + validatedSettings.forwardCandles()) {
            return BacktestReport.empty(candles.size(), validatedSettings);
        }

        List<EnrichedCandle> enrichedHistory = enrichmentService.enrich(candles, candles.size());
        List<BacktestTrade> trades = new ArrayList<>();
        int lastSignalIndex = candles.size() - validatedSettings.forwardCandles() - 1;
        for (int signalIndex = validatedSettings.minimumHistoricalCandles() - 1;
             signalIndex <= lastSignalIndex;
             signalIndex++) {
            int firstEnrichedIndex = Math.max(0, signalIndex - validatedSettings.signalCandles() + 1);
            List<EnrichedCandle> enrichedCandles = enrichedHistory.subList(firstEnrichedIndex, signalIndex + 1);
            Long signalTimestamp = candles.get(signalIndex).getTimestamp();

            List<DetectedSignal> signals = detectSignals(enrichedCandles, includeElliottWaves).stream()
                    .filter(signal -> signal.tradeSignal() == TradeSignal.BUY || signal.tradeSignal() == TradeSignal.SELL)
                    .filter(signal -> signal.candleTimestamp().equals(signalTimestamp))
                    .toList();

            for (DetectedSignal signal : signals) {
                trades.add(evaluateSignal(signal, candles, signalIndex, validatedSettings));
            }
        }

        return BacktestReport.from(candles.size(), lastSignalIndex + 1, validatedSettings, trades);
    }

    private List<DetectedSignal> detectSignals(List<EnrichedCandle> enrichedCandles, boolean includeElliottWaves) {
        List<DetectedSignal> signals = new ArrayList<>(detectionService.detect(enrichedCandles));
        if (includeElliottWaves) {
            signals.addAll(elliottWaveDetectionService.detect(enrichedCandles));
        }
        return List.copyOf(signals);
    }

    private BacktestTrade evaluateSignal(DetectedSignal signal,
                                         List<Candle> candles,
                                         int signalIndex,
                                         BacktestSettings settings) {
        int exitIndex = signalIndex + settings.forwardCandles();
        List<Candle> futureCandles = candles.subList(signalIndex + 1, exitIndex + 1);
        Candle exitCandle = candles.get(exitIndex);
        double entryClose = signal.closePrice();
        double exitClose = exitCandle.getClosePrice();
        double highestHigh = futureCandles.stream()
                .mapToDouble(Candle::getHighPrice)
                .max()
                .orElse(exitClose);
        double lowestLow = futureCandles.stream()
                .mapToDouble(Candle::getLowPrice)
                .min()
                .orElse(exitClose);

        double directionalReturn = directionalReturnPercent(signal.tradeSignal(), entryClose, exitClose);
        double bestDirectionalMove = signal.tradeSignal() == TradeSignal.BUY
                ? percentMoveFromEntry(entryClose, highestHigh)
                : percentMoveFromEntry(entryClose, lowestLow) * -1.0;
        double worstDirectionalMove = signal.tradeSignal() == TradeSignal.BUY
                ? percentMoveFromEntry(entryClose, lowestLow)
                : percentMoveFromEntry(entryClose, highestHigh) * -1.0;

        BacktestOutcome outcome;
        if (directionalReturn >= settings.minimumMovePercent()) {
            outcome = BacktestOutcome.SUCCESS;
        } else if (directionalReturn <= -settings.minimumMovePercent()) {
            outcome = BacktestOutcome.FAILURE;
        } else {
            outcome = BacktestOutcome.INCONCLUSIVE;
        }

        return new BacktestTrade(
                signal.pattern(),
                signal.tradeSignal(),
                signal.strength(),
                signal.confidenceScore(),
                signal.reasons(),
                signal.candleTimestamp(),
                entryClose,
                exitCandle.getTimestamp(),
                exitClose,
                directionalReturn,
                bestDirectionalMove,
                worstDirectionalMove,
                outcome
        );
    }

    private double directionalReturnPercent(TradeSignal signal, double entryClose, double exitClose) {
        return signal == TradeSignal.BUY
                ? percentMoveFromEntry(entryClose, exitClose)
                : percentMoveFromEntry(entryClose, exitClose) * -1.0;
    }

    private double percentMoveFromEntry(double entryClose, double targetPrice) {
        if (entryClose == 0.0) {
            return 0.0;
        }
        return ((targetPrice - entryClose) / entryClose) * 100.0;
    }

    private boolean hasCompletePriceData(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null
                && candle.getHighPrice() != null
                && candle.getLowPrice() != null
                && candle.getClosePrice() != null;
    }

    public record BacktestSettings(
            int minimumHistoricalCandles,
            int signalCandles,
            int forwardCandles,
            double minimumMovePercent
    ) {
        public static BacktestSettings defaults() {
            return new BacktestSettings(30, 5, 5, 2.0);
        }

        private BacktestSettings validated() {
            if (minimumHistoricalCandles < 2) {
                throw new IllegalArgumentException("minimumHistoricalCandles must be at least 2.");
            }
            if (signalCandles < 2) {
                throw new IllegalArgumentException("signalCandles must be at least 2.");
            }
            if (forwardCandles < 1) {
                throw new IllegalArgumentException("forwardCandles must be at least 1.");
            }
            if (minimumMovePercent <= 0.0) {
                throw new IllegalArgumentException("minimumMovePercent must be positive.");
            }
            return this;
        }
    }

    public record BacktestReport(
            BacktestSettings settings,
            int totalCandles,
            int analyzedCandles,
            int totalSignals,
            int buySignals,
            int sellSignals,
            int successfulSignals,
            int failedSignals,
            int inconclusiveSignals,
            double successRatePercent,
            double precisionPercent,
            double averageDirectionalReturnPercent,
            List<BacktestTrade> trades
    ) {
        private static BacktestReport empty(int totalCandles, BacktestSettings settings) {
            return new BacktestReport(
                    settings,
                    totalCandles,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    List.of()
            );
        }

        private static BacktestReport from(int totalCandles,
                                           int analyzedCandles,
                                           BacktestSettings settings,
                                           List<BacktestTrade> trades) {
            int buySignals = (int) trades.stream().filter(trade -> trade.tradeSignal() == TradeSignal.BUY).count();
            int sellSignals = (int) trades.stream().filter(trade -> trade.tradeSignal() == TradeSignal.SELL).count();
            int successfulSignals = (int) trades.stream()
                    .filter(trade -> trade.outcome() == BacktestOutcome.SUCCESS)
                    .count();
            int failedSignals = (int) trades.stream()
                    .filter(trade -> trade.outcome() == BacktestOutcome.FAILURE)
                    .count();
            int inconclusiveSignals = (int) trades.stream()
                    .filter(trade -> trade.outcome() == BacktestOutcome.INCONCLUSIVE)
                    .count();
            int actionableSignals = successfulSignals + failedSignals;
            double averageReturn = trades.stream()
                    .mapToDouble(BacktestTrade::directionalReturnPercent)
                    .average()
                    .orElse(0.0);

            return new BacktestReport(
                    settings,
                    totalCandles,
                    analyzedCandles,
                    trades.size(),
                    buySignals,
                    sellSignals,
                    successfulSignals,
                    failedSignals,
                    inconclusiveSignals,
                    percentage(successfulSignals, trades.size()),
                    percentage(successfulSignals, actionableSignals),
                    averageReturn,
                    List.copyOf(trades)
            );
        }

        private static double percentage(int numerator, int denominator) {
            if (denominator == 0) {
                return 0.0;
            }
            return (numerator * 100.0) / denominator;
        }
    }

    public record BacktestTrade(
            CandlePattern pattern,
            TradeSignal tradeSignal,
            SignalStength strength,
            int confidenceScore,
            List<String> reasons,
            Long signalTimestamp,
            double entryClose,
            Long exitTimestamp,
            double exitClose,
            double directionalReturnPercent,
            double bestDirectionalMovePercent,
            double worstDirectionalMovePercent,
            BacktestOutcome outcome
    ) {
        public BacktestTrade {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public enum BacktestOutcome {
        SUCCESS,
        FAILURE,
        INCONCLUSIVE
    }
}
