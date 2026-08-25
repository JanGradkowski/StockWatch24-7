package org.example.stockwatch247.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * On-demand historical candlestick analysis for the stock workspace.
 *
 * <p>Results are intentionally neither persisted nor cached. Every request
 * refreshes the candle source, scans the configured recent window, and derives
 * outcomes from completed candles.</p>
 */
@Service
public class HistoricalCandlestickService {
    private static final int CANDLESTICK_TIME_STOP_CANDLES = 8;
    public static final String SCORE_VERSION = CandlePatternDetectionService.SETUP_SCORE_VERSION;
    public static final int MIN_LOOKBACK_CANDLES = 1;
    public static final int MAX_LOOKBACK_CANDLES = 750;
    private static final int PRE_WINDOW_CONTEXT_CANDLES = 35;
    private static final int MINIMUM_RESULT_CANDLES = 10;

    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService detectionService;
    private final CandleCompletionService completionService;
    private final ZoneId signalTimeZone;

    @Autowired
    public HistoricalCandlestickService(
            CandleRepository candleRepository,
            StockAssetRepository stockAssetRepository,
            MarketDataService marketDataService,
            TechnicalIndicatorEnrichmentService enrichmentService,
            CandlePatternDetectionService detectionService,
            CandleCompletionService completionService,
            @Value("${alerts.email.time-zone:${alerts.schedule.zone:Europe/Brussels}}") String signalTimeZone,
            @Value("${alerts.candlestick.lifecycle-window-candles:8}") int ignoredConfirmationWindowCandles) {
        this.candleRepository = candleRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.marketDataService = marketDataService;
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
        this.completionService = completionService;
        this.signalTimeZone = ZoneId.of(signalTimeZone);
    }

    public HistoricalScan scan(String symbol, String apiInterval) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        return scan(symbol, apiInterval, profile.defaultLookbackCandles());
    }

    public HistoricalScan scan(String symbol, String apiInterval, int lookbackCandles) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        return scan(symbol, apiInterval, lookbackCandles,
                CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(profile.interval()));
    }

    public HistoricalScan scan(String symbol, String apiInterval, int lookbackCandles,
                               CandlePatternDetectionService.TrendDetectionRules trendRules) {
        return scan(symbol, apiInterval, lookbackCandles, trendRules,
                CandlestickPatternPreferencesService.factoryPreferences());
    }

    public HistoricalScan scan(String symbol, String apiInterval, int lookbackCandles,
                               CandlePatternDetectionService.TrendDetectionRules trendRules,
                               CandlestickPatternPreferencesService.PreferencesView definitions) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        int validatedLookbackCandles = requireLookbackCandles(lookbackCandles);
        synchronizeCandles(symbol, apiInterval);

        int analysisCandles = validatedLookbackCandles + PRE_WINDOW_CONTEXT_CANDLES;
        int requiredCandles = enrichmentService.requiredInputCandles(
                analysisCandles,
                profile.interval()
        );
        List<Candle> storedCandles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampDesc(
                        symbol,
                        apiInterval,
                        PageRequest.of(0, requiredCandles + 1)
                );
        List<Candle> completedCandles = storedCandles.stream()
                .filter(this::hasCompletePriceData)
                .filter(candle -> completionService.isComplete(candle.getTimestamp(), profile.interval()))
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();

        String companyName = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .map(StockAsset::getCompanyName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(symbol);
        List<HistoricalSignal> signals = analyze(
                symbol,
                companyName,
                completedCandles,
                profile,
                validatedLookbackCandles,
                trendRules,
                definitions
        );
        return new HistoricalScan(
                symbol,
                companyName,
                apiInterval,
                profile.intervalLabel(),
                validatedLookbackCandles,
                lookbackLabel(profile.interval(), validatedLookbackCandles),
                CANDLESTICK_TIME_STOP_CANDLES,
                "Candle 8 time stop",
                definitions.rewardRiskRatio(profile.interval()),
                completedCandles.size(),
                signals
        );
    }

    public HistoricalScan scanAll(String symbol, String apiInterval) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        return scanAll(symbol, apiInterval,
                CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(profile.interval()));
    }

    public HistoricalScan scanAll(String symbol, String apiInterval,
                                  CandlePatternDetectionService.TrendDetectionRules trendRules) {
        return scanAll(symbol, apiInterval, trendRules, CandlestickPatternPreferencesService.factoryPreferences());
    }

    public HistoricalScan scanAll(String symbol, String apiInterval,
                                  CandlePatternDetectionService.TrendDetectionRules trendRules,
                                  CandlestickPatternPreferencesService.PreferencesView definitions) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        synchronizeCandles(symbol, apiInterval);
        List<Candle> completedCandles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, apiInterval)
                .stream()
                .filter(this::hasCompletePriceData)
                .filter(candle -> completionService.isComplete(candle.getTimestamp(), profile.interval()))
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        String companyName = companyName(symbol);
        List<HistoricalSignal> signals = analyze(
                symbol,
                companyName,
                completedCandles,
                profile,
                completedCandles.size(),
                trendRules,
                definitions
        );
        return new HistoricalScan(
                symbol,
                companyName,
                apiInterval,
                profile.intervalLabel(),
                completedCandles.size(),
                "All " + completedCandles.size() + " completed "
                        + profile.intervalLabel().toLowerCase(Locale.ROOT) + " candles in the local archive",
                CANDLESTICK_TIME_STOP_CANDLES,
                "Candle 8 time stop",
                definitions.rewardRiskRatio(profile.interval()),
                completedCandles.size(),
                signals
        );
    }

    public HistoricalSignal findSignalInFullHistory(
            String symbol,
            String apiInterval,
            long signalTimestamp,
            CandlePattern pattern) {
        if (pattern == null || pattern == CandlePattern.ANY || pattern.name().startsWith("ELLIOTT_")) {
            throw new IllegalArgumentException("A historical candlestick pattern is required.");
        }
        return scanAll(symbol, apiInterval).signals().stream()
                .filter(signal -> signal.signalTimestamp() == signalTimestamp)
                .filter(signal -> signal.pattern() == pattern)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Historical candlestick signal is outside the completed candle archive."));
    }

    public HistoricalSignal findSignalInFullHistory(
            String symbol,
            String apiInterval,
            long signalTimestamp,
            CandlePattern pattern,
            CandlePatternDetectionService.TrendDetectionRules trendRules) {
        return findSignalInFullHistory(symbol, apiInterval, signalTimestamp, pattern, trendRules,
                CandlestickPatternPreferencesService.factoryPreferences());
    }

    public HistoricalSignal findSignalInFullHistory(String symbol, String apiInterval, long signalTimestamp,
                                                    CandlePattern pattern,
                                                    CandlePatternDetectionService.TrendDetectionRules trendRules,
                                                    CandlestickPatternPreferencesService.PreferencesView definitions) {
        if (pattern == null || pattern == CandlePattern.ANY || pattern.name().startsWith("ELLIOTT_")) {
            throw new IllegalArgumentException("A historical candlestick pattern is required.");
        }
        return scanAll(symbol, apiInterval, trendRules, definitions).signals().stream()
                .filter(signal -> signal.signalTimestamp() == signalTimestamp)
                .filter(signal -> signal.pattern() == pattern)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Historical candlestick signal is outside the completed candle archive."));
    }

    private void synchronizeCandles(String symbol, String apiInterval) {
        MarketDataService.CandleSyncResult syncResult =
                marketDataService.syncCandles(symbol, apiInterval, null);
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed.");
        }
    }

    private String companyName(String symbol) {
        return stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .map(StockAsset::getCompanyName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(symbol);
    }

    public HistoricalSignal findSignal(String symbol,
                                       String apiInterval,
                                       long signalTimestamp,
                                       CandlePattern pattern) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        return findSignal(
                symbol,
                apiInterval,
                signalTimestamp,
                pattern,
                profile.defaultLookbackCandles()
        );
    }

    public HistoricalSignal findSignal(String symbol,
                                       String apiInterval,
                                       long signalTimestamp,
                                       CandlePattern pattern,
                                       int lookbackCandles,
                                       CandlePatternDetectionService.TrendDetectionRules trendRules) {
        return findSignal(symbol, apiInterval, signalTimestamp, pattern, lookbackCandles, trendRules,
                CandlestickPatternPreferencesService.factoryPreferences());
    }

    public HistoricalSignal findSignal(String symbol, String apiInterval, long signalTimestamp,
                                       CandlePattern pattern, int lookbackCandles,
                                       CandlePatternDetectionService.TrendDetectionRules trendRules,
                                       CandlestickPatternPreferencesService.PreferencesView definitions) {
        if (pattern == null || pattern == CandlePattern.ANY || pattern.name().startsWith("ELLIOTT_")) {
            throw new IllegalArgumentException("A historical candlestick pattern is required.");
        }
        return scan(symbol, apiInterval, lookbackCandles, trendRules, definitions).signals().stream()
                .filter(signal -> signal.signalTimestamp() == signalTimestamp)
                .filter(signal -> signal.pattern() == pattern)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Historical candlestick signal is outside the current analysis window."));
    }

    public HistoricalSignal findSignal(String symbol,
                                       String apiInterval,
                                       long signalTimestamp,
                                       CandlePattern pattern,
                                       int lookbackCandles) {
        if (pattern == null || pattern == CandlePattern.ANY || pattern.name().startsWith("ELLIOTT_")) {
            throw new IllegalArgumentException("A historical candlestick pattern is required.");
        }
        return scan(symbol, apiInterval, lookbackCandles).signals().stream()
                .filter(signal -> signal.signalTimestamp() == signalTimestamp)
                .filter(signal -> signal.pattern() == pattern)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Historical candlestick signal is outside the current analysis window."));
    }

    public HistoricalSignalChart chartForSignal(HistoricalSignal signal) {
        if (signal == null) {
            return HistoricalSignalChart.unavailable("Historical signal details were not available.");
        }
        ScanProfile profile = ScanProfile.forApiInterval(signal.interval());
        List<Candle> candles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(signal.symbol(), signal.interval())
                .stream()
                .filter(this::hasCompletePriceData)
                .filter(candle -> completionService.isComplete(candle.getTimestamp(), profile.interval()))
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        int signalIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp() == signal.signalTimestamp()) {
                signalIndex = index;
                break;
            }
        }
        if (signalIndex < 0) {
            return HistoricalSignalChart.unavailable(
                    "The original OHLC candle is not present in the local cache, so the chart cannot be reconstructed safely."
            );
        }

        int patternStartIndex = Math.max(0, signalIndex - signal.formationCandles() + 1);
        Long trendStartTimestamp = patternStartIndex > 0
                ? signal.trendStartTimestamp()
                : null;
        List<HistoricalChartCandle> chartCandles = candles.stream()
                .map(candle -> new HistoricalChartCandle(
                        candle.getTimestamp(),
                        candle.getOpenPrice(),
                        candle.getHighPrice(),
                        candle.getLowPrice(),
                        candle.getClosePrice()
                ))
                .toList();
        return new HistoricalSignalChart(
                true,
                null,
                chartCandles,
                trendStartTimestamp,
                candles.get(patternStartIndex).getTimestamp(),
                signal.signalTimestamp(),
                Math.min(signal.formationCandles(), signalIndex + 1),
                signal.tradeSignal() == TradeSignal.SELL ? "Required uptrend" : "Required downtrend",
                "The highlighted region and amber guide trace the identified trend leg inside the detector's broader completed context; labeled arrows identify every candle that formed the pattern."
        );
    }

    public HistoricalSignalResults resultsForSignal(HistoricalSignal signal,
                                                     HistoricalSignalChart chart) {
        if (signal == null || chart == null || !chart.available()
                || !Double.isFinite(signal.entryClose()) || signal.entryClose() <= 0.0) {
            return HistoricalSignalResults.unavailable(
                    "Results cannot be calculated because the completed signal candle is not available in the local cache.",
                    0
            );
        }

        List<HistoricalChartCandle> candles = chart.candles();
        boolean confirmationRequired =
                CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern());
        if (confirmationRequired && signal.lifecycle().status() == SignalLifecycleStatus.POTENTIAL) {
            return HistoricalSignalResults.unavailable(
                    "Results are waiting for the mandatory next-candle detection gate.", 0);
        }
        if (confirmationRequired && signal.lifecycle().status() == SignalLifecycleStatus.REJECTED) {
            return HistoricalSignalResults.unavailable(
                    "No results are measured because this candidate was rejected and never became a signal.", 0);
        }
        long measurementTimestamp = confirmationRequired
                ? signal.lifecycle().detectionCandleTimestamp()
                : signal.signalTimestamp();
        double measurementClose = confirmationRequired
                ? signal.lifecycle().detectionClosePrice()
                : signal.entryClose();
        String measurementPeriod = confirmationRequired
                ? signal.lifecycle().detectionPeriodLabel()
                : signal.signalPeriodLabel();
        String measurementStartLabel = (confirmationRequired
                ? "From detection candle close · "
                : "From signal candle close · ") + measurementPeriod;
        int measurementIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == measurementTimestamp) {
                measurementIndex = index;
                break;
            }
        }
        if (measurementIndex < 0) {
            return HistoricalSignalResults.unavailable(
                    "Results cannot be calculated because the measurement-start candle is missing from the local cache.",
                    0
            );
        }

        int availableForwardCandles = candles.size() - measurementIndex - 1;
        if (availableForwardCandles < MINIMUM_RESULT_CANDLES) {
            String candleWord = availableForwardCandles == 1 ? "candle is" : "candles are";
            return HistoricalSignalResults.unavailable(
                    "Results are not available yet. At least " + MINIMUM_RESULT_CANDLES
                            + " completed candles after the measurement start are required to provide a meaningful outcome window. "
                            + availableForwardCandles + " completed cached " + candleWord + " currently available.",
                    availableForwardCandles
            );
        }

        ScanProfile profile = ScanProfile.forApiInterval(signal.interval());
        List<HistoricalResultPoint> points = new ArrayList<>(availableForwardCandles + 1);
        points.add(new HistoricalResultPoint(
                0,
                measurementTimestamp,
                measurementPeriod,
                measurementClose,
                0.0,
                0.0
        ));
        for (int offset = 1; offset <= availableForwardCandles; offset++) {
            HistoricalChartCandle candle = candles.get(measurementIndex + offset);
            double rawDifference = candle.close() - measurementClose;
            double directionalDifference = signal.tradeSignal() == TradeSignal.SELL
                    ? -rawDifference
                    : rawDifference;
            points.add(new HistoricalResultPoint(
                    offset,
                    candle.timestamp(),
                    SignalPeriodFormatter.format(candle.timestamp(), profile.interval(), signalTimeZone),
                    candle.close(),
                    directionalDifference / measurementClose * 100.0,
                    directionalDifference
            ));
        }

        boolean sellSignal = signal.tradeSignal() == TradeSignal.SELL;
        return new HistoricalSignalResults(
                true,
                null,
                MINIMUM_RESULT_CANDLES,
                availableForwardCandles,
                measurementClose,
                measurementTimestamp,
                signal.tradeSignal(),
                sellSignal ? "Decline avoided / rise missed" : "Gain / loss",
                sellSignal ? "Best re-entry close" : "Best sell close",
                List.copyOf(points),
                measurementStartLabel
        );
    }

    private List<HistoricalSignal> analyze(String symbol,
                                           String companyName,
                                           List<Candle> candles,
                                           ScanProfile profile,
                                           int lookbackCandles,
                                           CandlePatternDetectionService.TrendDetectionRules trendRules,
                                           CandlestickPatternPreferencesService.PreferencesView definitions) {
        if (candles.size() < 2) {
            return List.of();
        }
        List<EnrichedCandle> enriched = enrichmentService.enrich(
                candles,
                candles.size(),
                profile.interval()
        );
        if (enriched.size() < 2) {
            return List.of();
        }

        int firstVisibleIndex = Math.max(0, enriched.size() - lookbackCandles);
        List<HistoricalSignal> signals = new ArrayList<>();
        for (int signalIndex = firstVisibleIndex; signalIndex < enriched.size(); signalIndex++) {
            int firstContextIndex = Math.max(0, signalIndex - 99);
            List<EnrichedCandle> context = enriched.subList(firstContextIndex, signalIndex + 1);
            long signalTimestamp = enriched.get(signalIndex).timestamp();
            List<DetectedSignal> detected = (definitions.custom()
                    ? detectionService.detectAlertSignals(context, trendRules, definitions)
                    : detectionService.detectAlertSignals(context, trendRules)).stream()
                    .filter(signal -> signal.candleTimestamp() == signalTimestamp)
                    .toList();
            for (DetectedSignal signal : detected) {
                HistoricalSignal historicalSignal = toHistoricalSignal(
                        symbol,
                        companyName,
                        signal,
                        candles,
                        signalIndex,
                        profile,
                        trendRules,
                        definitions
                );
                if (historicalSignal.lifecycle().status() != SignalLifecycleStatus.REJECTED) {
                    signals.add(historicalSignal);
                }
            }
        }
        return signals.stream()
                .sorted(Comparator.comparingLong(HistoricalSignal::signalTimestamp)
                        .reversed()
                        .thenComparing(signal -> signal.pattern().name()))
                .toList();
    }

    private HistoricalSignal toHistoricalSignal(String symbol,
                                                String companyName,
                                                DetectedSignal signal,
                                                List<Candle> candles,
                                                int signalIndex,
                                                ScanProfile profile,
                                                CandlePatternDetectionService.TrendDetectionRules trendRules,
                                                CandlestickPatternPreferencesService.PreferencesView definitions) {
        int formationCandles = CandlestickSignalLifecyclePolicy.patternCandleCount(signal.pattern());
        int formationStart = Math.max(0, signalIndex - formationCandles + 1);
        int trendContextStart = Math.max(0, formationStart
                - trendContextCandles(profile.interval(), signal.tradeSignal(), trendRules));
        int trendStart = indexOfTimestamp(candles, signal.trendStartTimestamp());
        if (trendStart < trendContextStart || trendStart >= formationStart) {
            trendStart = CandlestickTrendLegLocator.locateStartIndex(
                    candles,
                    trendContextStart,
                    formationStart,
                    signal.tradeSignal()
            );
        }
        List<Candle> formation = candles.subList(formationStart, signalIndex + 1);
        double patternHigh = formation.stream().mapToDouble(Candle::getHighPrice).max().orElse(signal.closePrice());
        double patternLow = formation.stream().mapToDouble(Candle::getLowPrice).min().orElse(signal.closePrice());
        HistoricalLifecycleView lifecycle = evaluateLifecycle(
                signal,
                candles,
                signalIndex,
                formation,
                patternHigh,
                patternLow,
                profile,
                definitions
        );
        OutcomeEvaluation evaluation = evaluateOutcome(
                signal, candles, signalIndex, profile, lifecycle);
        List<EvidenceSection> evidence = signal.reasons().stream()
                .map(reason -> toEvidenceSection(
                        SignalScoreBreakdown.parse(reason, "Evidence", signal.tradeSignal())
                ))
                .toList();

        return new HistoricalSignal(
                symbol,
                companyName,
                profile.apiInterval(),
                profile.intervalLabel(),
                signal.pattern(),
                patternLabel(signal.pattern()),
                signal.tradeSignal(),
                signalTypeLabel(signal, lifecycle),
                signal.strength(),
                setupStrengthLabel(signal.strength(), signal.setupScore()),
                setupBand(signal.setupScore()),
                signal.setupScore(),
                SCORE_VERSION,
                setupExplanation(signal.setupScore()),
                signal.candleTimestamp(),
                SignalPeriodFormatter.format(
                        signal.candleTimestamp(),
                        profile.interval(),
                        signalTimeZone
                ),
                signal.closePrice(),
                formationCandles,
                formationCandles + "-candle formation",
                patternHigh,
                patternLow,
                lifecycle.entryPrice(),
                lifecycle.stopLossPrice(),
                lifecycle.profitTargetPrice(),
                lifecycle.rewardRiskRatio(),
                lifecycle.confirmationWindowCandles(),
                lifecycle.resolutionClosePrice(),
                lifecycle.resolutionCandleTimestamp(),
                lifecycle.entryPrice() == null || lifecycle.resolutionClosePrice() == null
                        ? null
                        : directionalReturnPercent(signal.tradeSignal(),
                                lifecycle.entryPrice(), lifecycle.resolutionClosePrice()),
                lifecycle.status(),
                lifecycle.label(),
                lifecycle.cssClass(),
                evaluation.summary(),
                evaluation.evaluationTimestamp(),
                evaluation.evaluationPeriodLabel(),
                evaluation.evaluationClose(),
                evaluation.directionalReturnPercent(),
                evaluation.bestDirectionalMovePercent(),
                evaluation.worstDirectionalMovePercent(),
                impactLabel(signal.tradeSignal(), evaluation.directionalReturnPercent()),
                candles.get(trendStart).getTimestamp(),
                candles.get(formationStart).getTimestamp(),
                definitions.profile(signal.pattern()).trendRequirement().label(),
                lifecycle,
                evidence
        );
    }

    private int indexOfTimestamp(List<Candle> candles, Long timestamp) {
        if (timestamp == null) return -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp().equals(timestamp)) return index;
        }
        return -1;
    }

    private int trendContextCandles(TimeInterval interval,
                                    TradeSignal direction,
                                    CandlePatternDetectionService.TrendDetectionRules trendRules) {
        if (!trendRules.adaptiveFactory()) {
            return trendRules.lookbackCandles();
        }
        return 30;
    }

    private HistoricalLifecycleView evaluateLifecycle(DetectedSignal signal,
                                                       List<Candle> candles,
                                                       int signalIndex,
                                                       List<Candle> formationCandles,
                                                       double patternHigh,
                                                       double patternLow,
                                                       ScanProfile profile,
                                                       CandlestickPatternPreferencesService.PreferencesView definitions) {
        boolean candidateGateRequired =
                CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern());
        double structuralStop = CandlestickSignalLifecyclePolicy.structuralStopPrice(
                signal.pattern(), formationCandles);
        CandlestickPatternPreferencesService.PatternProfile patternProfile =
                definitions.profile(signal.pattern());
        List<Candle> subsequentCandles = signalIndex + 1 >= candles.size()
                ? List.of()
                : candles.subList(signalIndex + 1, candles.size());
        CandlestickSignalLifecyclePolicy.LifecycleResolution gate = candidateGateRequired
                ? CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                        signal.pattern(), signal.tradeSignal(), signal.closePrice(), subsequentCandles)
                : null;
        Long detectionTimestamp = candidateGateRequired && gate != null
                && gate.status() == SignalLifecycleStatus.DETECTED
                ? gate.resolutionCandle().getTimestamp() : null;
        Double detectionClose = candidateGateRequired && gate != null
                && gate.status() == SignalLifecycleStatus.DETECTED
                ? gate.resolutionCandle().getClosePrice() : null;
        int outcomeWindow = CANDLESTICK_TIME_STOP_CANDLES;
        Double entryPrice = candidateGateRequired
                ? detectionClose
                : candles.get(signalIndex).getClosePrice();
        double stopLoss = CandlestickSignalLifecyclePolicy.configuredStopPrice(
                signal.tradeSignal(), entryPrice == null ? signal.closePrice() : entryPrice,
                structuralStop, patternProfile.stopLossMode(),
                patternProfile.stopLossValuePercent());
        double rewardRiskRatio = definitions.rewardRiskRatio(profile.interval());
        CandlestickSignalLifecyclePolicy.TradePlan tradePlan = entryPrice == null
                ? null
                : CandlestickSignalLifecyclePolicy.tradePlan(
                        signal.tradeSignal(), entryPrice, stopLoss, profile.interval(), rewardRiskRatio);
        Double profitTarget = tradePlan == null ? null : tradePlan.profitTargetPrice();
        CandlestickSignalLifecyclePolicy.LifecycleResolution resolution;
        SignalLifecycleStatus status;
        if (candidateGateRequired && gate == null) {
            status = SignalLifecycleStatus.POTENTIAL;
            resolution = null;
        } else if (candidateGateRequired && gate.status() == SignalLifecycleStatus.REJECTED) {
            status = SignalLifecycleStatus.REJECTED;
            resolution = gate;
        } else {
            int outcomeStartIndex = candidateGateRequired ? 1 : 0;
            List<Candle> outcomeCandles = subsequentCandles.size() <= outcomeStartIndex
                    ? List.of()
                    : subsequentCandles.subList(outcomeStartIndex, subsequentCandles.size());
            resolution = CandlestickSignalLifecyclePolicy.resolve(
                    signal.tradeSignal(), profitTarget, stopLoss,
                    outcomeCandles, outcomeWindow);
            status = resolution == null ? SignalLifecycleStatus.DETECTED : resolution.status();
        }
        String resolutionPeriod = resolution == null
                ? null
                : SignalPeriodFormatter.format(
                        resolution.resolutionCandle().getTimestamp(),
                        profile.interval(),
                        signalTimeZone
                );
        String boundaryDirection = signal.tradeSignal() == TradeSignal.BUY ? "above" : "below";
        String invalidationDirection = signal.tradeSignal() == TradeSignal.BUY ? "below" : "above";
        String summary = switch (status) {
            case POTENTIAL -> String.format(
                    Locale.ROOT,
                    "Potential %s candidate only. The next candle must have a %s body and close %s the %.4f candidate close before this becomes a signal.",
                    signal.tradeSignal() == TradeSignal.SELL ? "sell/short" : "buy",
                    signal.tradeSignal() == TradeSignal.BUY ? "green" : "red",
                    boundaryDirection,
                    signal.closePrice());
            case REJECTED -> String.format(
                    Locale.ROOT,
                    "Rejected on %s because the next candle did not provide the required %s-body close %s the %.4f candidate close. It never became a signal.",
                    resolutionPeriod,
                    signal.tradeSignal() == TradeSignal.BUY ? "green" : "red",
                    boundaryDirection,
                    signal.closePrice());
            case DETECTED -> candidateGateRequired
                    ? String.format(
                            Locale.ROOT,
                            "Trade opened on %s at the %.4f detection close after the mandatory gate passed. Target %.4f, stop %.4f, and candle %d is the time stop.",
                            SignalPeriodFormatter.format(detectionTimestamp, profile.interval(), signalTimeZone),
                            detectionClose,
                            profitTarget,
                            stopLoss,
                            outcomeWindow)
                    : String.format(
                            Locale.ROOT,
                            "Trade opened at %.4f. A completed close at %.4f reaches the target; a close at %.4f hits the stop; otherwise candle %d closes the trade.",
                            entryPrice,
                            profitTarget,
                            stopLoss,
                            outcomeWindow
                    );
            case CONFIRMED -> String.format(
                    Locale.ROOT,
                    "Trade closed successfully on %s when candle %d closed at %.4f, reaching the %.4f profit target.",
                    resolutionPeriod,
                    resolution.candleOffset(),
                    resolution.resolutionCandle().getClosePrice(),
                    profitTarget
            );
            case INVALIDATED -> String.format(
                    Locale.ROOT,
                    "Stop loss hit on %s when candle %d closed at %.4f, beyond the configured %.4f stop.",
                    resolutionPeriod,
                    resolution.candleOffset(),
                    resolution.resolutionCandle().getClosePrice(),
                    stopLoss
            );
            case EXPIRED -> String.format(
                    Locale.ROOT,
                    "Time stop reached after %d completed candles; the trade closed at candle 8's %.4f close.",
                    outcomeWindow,
                    resolution.resolutionCandle().getClosePrice()
            );
        };
        return new HistoricalLifecycleView(
                status,
                lifecycleLabel(status),
                status.name().toLowerCase(Locale.ROOT),
                status != SignalLifecycleStatus.POTENTIAL
                        && status != SignalLifecycleStatus.DETECTED,
                candidateGateRequired,
                summary,
                patternHigh,
                patternLow,
                profitTarget == null ? signal.closePrice() : profitTarget,
                stopLoss,
                outcomeWindow,
                entryPrice,
                stopLoss,
                profitTarget,
                rewardRiskRatio,
                detectionTimestamp,
                detectionTimestamp == null ? null : SignalPeriodFormatter.format(
                        detectionTimestamp, profile.interval(), signalTimeZone),
                detectionClose,
                resolution == null ? null : resolution.resolutionCandle().getTimestamp(),
                resolution == null ? null : resolution.candleOffset(),
                resolutionPeriod,
                resolution == null ? null : resolution.resolutionCandle().getClosePrice()
        );
    }

    private String lifecycleLabel(SignalLifecycleStatus status) {
        return switch (status) {
            case POTENTIAL -> "Potential";
            case DETECTED -> "Detected";
            case REJECTED -> "Rejected";
            case CONFIRMED -> "Confirmed";
            case INVALIDATED -> "Invalidated";
            case EXPIRED -> "Expired";
        };
    }

    private OutcomeEvaluation evaluateOutcome(DetectedSignal signal,
                                              List<Candle> candles,
                                              int signalIndex,
                                              ScanProfile profile,
                                              HistoricalLifecycleView lifecycle) {
        boolean confirmationRequired =
                CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern());
        if (confirmationRequired && lifecycle.status() == SignalLifecycleStatus.POTENTIAL) {
            return pendingOutcome("This is only a potential candidate until the immediately following candle passes the mandatory detection gate.");
        }
        if (confirmationRequired && lifecycle.status() == SignalLifecycleStatus.REJECTED) {
            return terminalWithoutOutcome(
                    lifecycle,
                    "No outcome is measured because this candidate never became a signal.");
        }
        int measurementIndex = signalIndex;
        double measurementClose = signal.closePrice();
        if (confirmationRequired) {
            measurementIndex = -1;
            for (int index = signalIndex + 1; index < candles.size(); index++) {
                if (candles.get(index).getTimestamp().equals(lifecycle.detectionCandleTimestamp())) {
                    measurementIndex = index;
                    break;
                }
            }
            if (measurementIndex < 0 || lifecycle.detectionClosePrice() == null) {
                return pendingOutcome("The detection candle is unavailable, so results cannot be measured safely.");
            }
            measurementClose = lifecycle.detectionClosePrice();
        }
        if (lifecycle.entryPrice() == null) {
            return pendingOutcome("The trade entry close is unavailable.");
        }
        if (lifecycle.terminal() && (lifecycle.resolutionCandleTimestamp() == null
                || lifecycle.resolutionClosePrice() == null)) {
            return pendingOutcome("The terminal trade record is missing its exit close.");
        }
        int maximumEvaluationIndex = Math.min(
                candles.size() - 1, measurementIndex + CANDLESTICK_TIME_STOP_CANDLES);
        int resolutionIndex = lifecycle.terminal()
                ? indexOfTimestamp(candles, lifecycle.resolutionCandleTimestamp())
                : -1;
        if (lifecycle.terminal() && resolutionIndex <= measurementIndex) {
            return pendingOutcome("The stored exit candle is unavailable, so the trade path cannot be measured safely.");
        }
        int evaluationIndex = lifecycle.terminal()
                ? Math.min(maximumEvaluationIndex, resolutionIndex)
                : maximumEvaluationIndex;
        if (evaluationIndex <= measurementIndex) {
            return pendingOutcome("The trade is open and no completed outcome candle is available yet.");
        }
        Candle evaluationCandle = candles.get(evaluationIndex);
        List<Candle> futureCandles = candles.subList(measurementIndex + 1, evaluationIndex + 1);
        double directionalReturn = directionalReturnPercent(
                signal.tradeSignal(),
                measurementClose,
                evaluationCandle.getClosePrice()
        );
        double highestClose = futureCandles.stream().mapToDouble(Candle::getClosePrice).max()
                .orElse(evaluationCandle.getClosePrice());
        double lowestClose = futureCandles.stream().mapToDouble(Candle::getClosePrice).min()
                .orElse(evaluationCandle.getClosePrice());
        double observedBestMove = signal.tradeSignal() == TradeSignal.BUY
                ? percentMove(measurementClose, highestClose)
                : -percentMove(measurementClose, lowestClose);
        double targetMove = lifecycle.profitTargetPrice() == null
                ? observedBestMove
                : directionalReturnPercent(
                        signal.tradeSignal(), measurementClose, lifecycle.profitTargetPrice());
        double bestMove = lifecycle.status() == SignalLifecycleStatus.CONFIRMED
                ? targetMove
                : Math.max(0.0, observedBestMove);
        double worstMove = signal.tradeSignal() == TradeSignal.BUY
                ? percentMove(measurementClose, lowestClose)
                : -percentMove(measurementClose, highestClose);

        String evaluationPeriod = SignalPeriodFormatter.format(
                evaluationCandle.getTimestamp(), profile.interval(), signalTimeZone);
        if (!lifecycle.terminal()) {
            int observed = evaluationIndex - measurementIndex;
            int remaining = Math.max(0, CANDLESTICK_TIME_STOP_CANDLES - observed);
            return new OutcomeEvaluation(
                    "Trade open",
                    "detected",
                    String.format(
                            Locale.ROOT,
                            "The trade is open after %d completed trade candle%s. Best favorable completed-close move: %+.2f%%. %d candle%s remain before the candle 8 time stop.",
                            observed, observed == 1 ? "" : "s", bestMove,
                            remaining, remaining == 1 ? "" : "s"),
                    evaluationCandle.getTimestamp(),
                    evaluationPeriod,
                    evaluationCandle.getClosePrice(),
                    directionalReturn,
                    bestMove,
                    worstMove);
        }

        return new OutcomeEvaluation(
                lifecycle.label(),
                lifecycle.cssClass(),
                lifecycle.summary() + " Directional entry-to-exit return: "
                        + String.format(Locale.ROOT, "%+.2f%%.", directionalReturn),
                evaluationCandle.getTimestamp(),
                evaluationPeriod,
                evaluationCandle.getClosePrice(),
                directionalReturn,
                bestMove,
                worstMove
        );
    }

    private OutcomeEvaluation pendingOutcome(String reason) {
        return new OutcomeEvaluation(
                "Detected",
                "detected",
                reason,
                null, null, null, null, null, null);
    }

    private OutcomeEvaluation terminalWithoutOutcome(HistoricalLifecycleView lifecycle, String reason) {
        return new OutcomeEvaluation(
                lifecycle.label(),
                lifecycle.cssClass(),
                reason + " " + lifecycle.summary(),
                null, null, null, null, null, null);
    }

    private String signalTypeLabel(DetectedSignal signal, HistoricalLifecycleView lifecycle) {
        String direction = signal.tradeSignal() == TradeSignal.BUY ? "buy" : "sell/short";
        if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern())) {
            return switch (lifecycle.status()) {
                case POTENTIAL -> "Potential " + direction + " candidate";
                case REJECTED -> "Rejected " + direction + " candidate";
                case DETECTED -> "Detected " + direction + " signal";
                case CONFIRMED -> "Confirmed " + direction + " signal";
                case INVALIDATED -> "Invalidated " + direction + " signal";
                case EXPIRED -> "Expired " + direction + " signal";
            };
        }
        return Character.toUpperCase(direction.charAt(0)) + direction.substring(1) + " signal";
    }

    private String impactLabel(TradeSignal direction, Double directionalReturn) {
        if (directionalReturn == null) {
            return "Trade return pending";
        }
        if (direction == TradeSignal.BUY) {
            return "Entry-to-current/exit return";
        }
        return "Direction-adjusted entry-to-current/exit return";
    }

    private double directionalReturnPercent(TradeSignal direction, double entry, double exit) {
        double marketMove = percentMove(entry, exit);
        return direction == TradeSignal.BUY ? marketMove : -marketMove;
    }

    private double percentMove(double entry, double exit) {
        return entry == 0.0 ? 0.0 : ((exit - entry) / entry) * 100.0;
    }

    private EvidenceSection toEvidenceSection(SignalScoreBreakdown.Section section) {
        return new EvidenceSection(
                section.category(),
                section.scoreLabel(),
                section.status(),
                section.scored(),
                section.details().stream()
                        .map(detail -> new EvidenceDetail(
                                detail.label(),
                                detail.text(),
                                detail.score()
                        ))
                        .toList()
        );
    }

    private String patternLabel(CandlePattern pattern) {
        String value = pattern.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String setupBand(int score) {
        return score >= 85 ? "high" : score >= 75 ? "medium" : "low";
    }

    private String setupStrengthLabel(SignalStength strength, int score) {
        return switch (setupBand(score)) {
            case "high" -> "High confluence";
            case "medium" -> "Moderate confluence";
            default -> "Low confluence";
        };
    }

    private String setupExplanation(int score) {
        if (score >= 85) {
            return "High heuristic confluence: broad alignment across the recorded technical evidence.";
        }
        if (score >= 75) {
            return "Moderate heuristic confluence: several factors align, with mixed or unavailable evidence.";
        }
        return "Low heuristic confluence: the pattern is valid, but supporting technical evidence is limited.";
    }

    private String nativeCandleLabel(TimeInterval interval, int count) {
        String unit = switch (interval) {
            case DAILY -> "daily candle";
            case WEEKLY -> "weekly candle";
            case MONTHLY -> "monthly candle";
            default -> "candle";
        };
        return count == 1 ? unit : unit + "s";
    }

    private boolean hasCompletePriceData(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null
                && candle.getHighPrice() != null
                && candle.getLowPrice() != null
                && candle.getClosePrice() != null;
    }

    public int defaultLookbackCandles(String apiInterval) {
        return ScanProfile.forApiInterval(apiInterval).defaultLookbackCandles();
    }

    private int requireLookbackCandles(int lookbackCandles) {
        if (lookbackCandles < MIN_LOOKBACK_CANDLES || lookbackCandles > MAX_LOOKBACK_CANDLES) {
            throw new IllegalArgumentException(
                    "Candle lookback must be between "
                            + MIN_LOOKBACK_CANDLES
                            + " and "
                            + MAX_LOOKBACK_CANDLES
                            + ".");
        }
        return lookbackCandles;
    }

    private String lookbackLabel(TimeInterval interval, int lookbackCandles) {
        return "last " + lookbackCandles + " completed "
                + nativeCandleLabel(interval, lookbackCandles);
    }

    private enum ScanProfile {
        DAILY("1d", TimeInterval.DAILY, "Daily", 60),
        WEEKLY("1wk", TimeInterval.WEEKLY, "Weekly", 104),
        MONTHLY("1mo", TimeInterval.MONTHLY, "Monthly", 120);

        private final String apiInterval;
        private final TimeInterval interval;
        private final String intervalLabel;
        private final int defaultLookbackCandles;

        ScanProfile(String apiInterval,
                    TimeInterval interval,
                    String intervalLabel,
                    int defaultLookbackCandles) {
            this.apiInterval = apiInterval;
            this.interval = interval;
            this.intervalLabel = intervalLabel;
            this.defaultLookbackCandles = defaultLookbackCandles;
        }

        private static ScanProfile forApiInterval(String apiInterval) {
            for (ScanProfile profile : values()) {
                if (profile.apiInterval.equals(apiInterval)) {
                    return profile;
                }
            }
            throw new IllegalArgumentException(
                    "Historical candlestick analysis supports daily, weekly, and monthly intervals.");
        }

        private String apiInterval() {
            return apiInterval;
        }

        private TimeInterval interval() {
            return interval;
        }

        private String intervalLabel() {
            return intervalLabel;
        }

        private int defaultLookbackCandles() {
            return defaultLookbackCandles;
        }

    }

    public record HistoricalScan(
            String symbol,
            String companyName,
            String interval,
            String intervalLabel,
            int lookbackCandles,
            String lookbackLabel,
            int timeStopCandles,
            String timeStopLabel,
            double rewardRiskRatio,
            int completedCandlesLoaded,
            List<HistoricalSignal> signals
    ) {
        public HistoricalScan {
            signals = List.copyOf(signals);
        }
    }

    public record HistoricalSignal(
            String symbol,
            String companyName,
            String interval,
            String intervalLabel,
            CandlePattern pattern,
            String patternLabel,
            TradeSignal tradeSignal,
            String typeLabel,
            SignalStength strength,
            String setupStrengthLabel,
            String setupBand,
            int setupScore,
            String scoreVersion,
            String setupExplanation,
            long signalTimestamp,
            String signalPeriodLabel,
            double entryClose,
            int formationCandles,
            String formationLabel,
            double patternHigh,
            double patternLow,
            Double tradeEntryPrice,
            Double stopLossPrice,
            Double profitTargetPrice,
            double rewardRiskRatio,
            int timeStopCandles,
            Double tradeExitPrice,
            Long tradeExitTimestamp,
            Double tradeReturnPercent,
            SignalLifecycleStatus status,
            String statusLabel,
            String statusClass,
            String outcomeSummary,
            Long evaluationTimestamp,
            String evaluationPeriodLabel,
            Double evaluationClose,
            Double directionalReturnPercent,
            Double bestDirectionalMovePercent,
            Double worstDirectionalMovePercent,
            String impactLabel,
            long trendStartTimestamp,
            long patternStartTimestamp,
            String trendLabel,
            @JsonIgnore HistoricalLifecycleView lifecycle,
            @JsonIgnore List<EvidenceSection> evidence
    ) {
        public HistoricalSignal {
            evidence = List.copyOf(evidence);
        }

        public boolean outcomeAvailable() {
            return evaluationTimestamp != null;
        }

        public String measurementStartLabel() {
            if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(pattern)
                    && lifecycle.status() != SignalLifecycleStatus.POTENTIAL
                    && lifecycle.status() != SignalLifecycleStatus.REJECTED) {
                return "From detection candle close \u00b7 " + lifecycle.detectionPeriodLabel();
            }
            return "From signal candle close \u00b7 " + signalPeriodLabel;
        }
    }

    public record HistoricalLifecycleView(
            SignalLifecycleStatus status,
            String label,
            String cssClass,
            boolean terminal,
            boolean immediateConfirmationRequired,
            String summary,
            double patternHigh,
            double patternLow,
            double confirmationTriggerPrice,
            double invalidationPrice,
            int confirmationWindowCandles,
            Double entryPrice,
            Double stopLossPrice,
            Double profitTargetPrice,
            double rewardRiskRatio,
            Long detectionCandleTimestamp,
            String detectionPeriodLabel,
            Double detectionClosePrice,
            Long resolutionCandleTimestamp,
            Integer resolutionCandleOffset,
            String resolutionPeriodLabel,
            Double resolutionClosePrice
    ) {
    }

    public record HistoricalSignalChart(
            boolean available,
            String unavailableReason,
            List<HistoricalChartCandle> candles,
            Long trendStartTimestamp,
            Long patternStartTimestamp,
            Long signalTimestamp,
            int patternCandleCount,
            String trendLabel,
            String summary
    ) {
        private static HistoricalSignalChart unavailable(String reason) {
            return new HistoricalSignalChart(false, reason, List.of(), null, null, null, 0, null, null);
        }

        public HistoricalSignalChart {
            candles = List.copyOf(candles);
        }
    }

    public record HistoricalChartCandle(
            long timestamp,
            double open,
            double high,
            double low,
            double close
    ) {
    }

    public record HistoricalSignalResults(
            boolean available,
            String unavailableReason,
            int minimumForwardCandles,
            int availableForwardCandles,
            Double signalClose,
            Long signalTimestamp,
            TradeSignal tradeSignal,
            String outcomeLabel,
            String bestActionLabel,
            List<HistoricalResultPoint> points,
            String measurementStartLabel
    ) {
        private static HistoricalSignalResults unavailable(String reason, int availableForwardCandles) {
            return new HistoricalSignalResults(
                    false,
                    reason,
                    MINIMUM_RESULT_CANDLES,
                    availableForwardCandles,
                    null,
                    null,
                    null,
                    "Outcome unavailable",
                    "Best reversal unavailable",
                    List.of(),
                    null
            );
        }

        public HistoricalSignalResults {
            points = List.copyOf(points);
        }
    }

    public record HistoricalResultPoint(
            int candleNumber,
            long timestamp,
            String periodLabel,
            double close,
            double directionalReturnPercent,
            double directionalPriceDifference
    ) {
    }

    public record EvidenceSection(
            String category,
            String scoreLabel,
            String statusLabel,
            boolean scored,
            List<EvidenceDetail> details
    ) {
        public EvidenceSection {
            details = List.copyOf(details);
        }
    }

    public record EvidenceDetail(String label, String text, String scoreLabel) {
    }

    private record OutcomeEvaluation(
            String statusLabel,
            String statusClass,
            String summary,
            Long evaluationTimestamp,
            String evaluationPeriodLabel,
            Double evaluationClose,
            Double directionalReturnPercent,
            Double bestDirectionalMovePercent,
            Double worstDirectionalMovePercent
    ) {
    }
}
