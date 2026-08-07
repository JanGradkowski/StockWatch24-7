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
    public static final String SCORE_VERSION = CandlePatternDetectionService.SETUP_SCORE_VERSION;
    public static final int MIN_LOOKBACK_CANDLES = 1;
    public static final int MAX_LOOKBACK_CANDLES = 750;
    private static final int PRE_WINDOW_CONTEXT_CANDLES = 8;
    private static final int SIGNAL_CHART_TREND_CANDLES = 5;
    private static final int MINIMUM_RESULT_CANDLES = 10;

    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService detectionService;
    private final CandleCompletionService completionService;
    private final ZoneId signalTimeZone;
    private final int confirmationWindowCandles;

    @Autowired
    public HistoricalCandlestickService(
            CandleRepository candleRepository,
            StockAssetRepository stockAssetRepository,
            MarketDataService marketDataService,
            TechnicalIndicatorEnrichmentService enrichmentService,
            CandlePatternDetectionService detectionService,
            CandleCompletionService completionService,
            @Value("${alerts.email.time-zone:${alerts.schedule.zone:Europe/Brussels}}") String signalTimeZone,
            @Value("${alerts.candlestick.lifecycle-window-candles:3}") int confirmationWindowCandles) {
        this.candleRepository = candleRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.marketDataService = marketDataService;
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
        this.completionService = completionService;
        this.signalTimeZone = ZoneId.of(signalTimeZone);
        this.confirmationWindowCandles = Math.clamp(confirmationWindowCandles, 1, 20);
    }

    public HistoricalScan scan(String symbol, String apiInterval) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        return scan(symbol, apiInterval, profile.defaultLookbackCandles());
    }

    public HistoricalScan scan(String symbol, String apiInterval, int lookbackCandles) {
        ScanProfile profile = ScanProfile.forApiInterval(apiInterval);
        int validatedLookbackCandles = requireLookbackCandles(lookbackCandles);
        MarketDataService.CandleSyncResult syncResult =
                marketDataService.syncCandles(symbol, apiInterval, null);
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed.");
        }

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
                validatedLookbackCandles
        );
        return new HistoricalScan(
                symbol,
                companyName,
                apiInterval,
                profile.intervalLabel(),
                validatedLookbackCandles,
                lookbackLabel(profile.interval(), validatedLookbackCandles),
                profile.forwardCandles(),
                profile.horizonLabel(),
                profile.minimumMovePercent(),
                completedCandles.size(),
                signals
        );
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
        int trendStartIndex = Math.max(0, patternStartIndex - SIGNAL_CHART_TREND_CANDLES);
        Long trendStartTimestamp = patternStartIndex > 0
                ? candles.get(trendStartIndex).getTimestamp()
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
                "The highlighted region and amber guide mark the five-candle prior-trend window used by the detector; labeled arrows identify every candle that formed the pattern."
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
        int signalIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == signal.signalTimestamp()) {
                signalIndex = index;
                break;
            }
        }
        if (signalIndex < 0) {
            return HistoricalSignalResults.unavailable(
                    "Results cannot be calculated because the signal candle is missing from the local cache.",
                    0
            );
        }

        int availableForwardCandles = candles.size() - signalIndex - 1;
        if (availableForwardCandles < MINIMUM_RESULT_CANDLES) {
            String candleWord = availableForwardCandles == 1 ? "candle is" : "candles are";
            return HistoricalSignalResults.unavailable(
                    "Results are not available yet. At least " + MINIMUM_RESULT_CANDLES
                            + " completed candles after the signal are required to provide a meaningful outcome window. "
                            + availableForwardCandles + " completed cached " + candleWord + " currently available.",
                    availableForwardCandles
            );
        }

        ScanProfile profile = ScanProfile.forApiInterval(signal.interval());
        List<HistoricalResultPoint> points = new ArrayList<>(availableForwardCandles + 1);
        points.add(new HistoricalResultPoint(
                0,
                signal.signalTimestamp(),
                signal.signalPeriodLabel(),
                signal.entryClose(),
                0.0,
                0.0
        ));
        for (int offset = 1; offset <= availableForwardCandles; offset++) {
            HistoricalChartCandle candle = candles.get(signalIndex + offset);
            double rawDifference = candle.close() - signal.entryClose();
            double directionalDifference = signal.tradeSignal() == TradeSignal.SELL
                    ? -rawDifference
                    : rawDifference;
            points.add(new HistoricalResultPoint(
                    offset,
                    candle.timestamp(),
                    SignalPeriodFormatter.format(candle.timestamp(), profile.interval(), signalTimeZone),
                    candle.close(),
                    directionalDifference / signal.entryClose() * 100.0,
                    directionalDifference
            ));
        }

        boolean sellSignal = signal.tradeSignal() == TradeSignal.SELL;
        return new HistoricalSignalResults(
                true,
                null,
                MINIMUM_RESULT_CANDLES,
                availableForwardCandles,
                signal.entryClose(),
                signal.signalTimestamp(),
                signal.tradeSignal(),
                sellSignal ? "Decline avoided / rise missed" : "Gain / loss",
                sellSignal ? "Best re-entry close" : "Best sell close",
                List.copyOf(points)
        );
    }

    private List<HistoricalSignal> analyze(String symbol,
                                           String companyName,
                                           List<Candle> candles,
                                           ScanProfile profile,
                                           int lookbackCandles) {
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
            List<DetectedSignal> detected = detectionService.detectAlertSignals(context).stream()
                    .filter(signal -> signal.candleTimestamp() == signalTimestamp)
                    .toList();
            for (DetectedSignal signal : detected) {
                signals.add(toHistoricalSignal(
                        symbol,
                        companyName,
                        signal,
                        candles,
                        signalIndex,
                        profile
                ));
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
                                                ScanProfile profile) {
        OutcomeEvaluation evaluation = evaluateOutcome(signal, candles, signalIndex, profile);
        int formationCandles = CandlestickSignalLifecyclePolicy.patternCandleCount(signal.pattern());
        int formationStart = Math.max(0, signalIndex - formationCandles + 1);
        List<Candle> formation = candles.subList(formationStart, signalIndex + 1);
        double patternHigh = formation.stream().mapToDouble(Candle::getHighPrice).max().orElse(signal.closePrice());
        double patternLow = formation.stream().mapToDouble(Candle::getLowPrice).min().orElse(signal.closePrice());
        HistoricalLifecycleView lifecycle = evaluateLifecycle(
                signal,
                candles,
                signalIndex,
                patternHigh,
                patternLow,
                profile
        );
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
                signal.tradeSignal() == TradeSignal.BUY ? "Buy signal" : "Sell signal",
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
                evaluation.status(),
                evaluation.statusLabel(),
                evaluation.statusClass(),
                evaluation.summary(),
                profile.forwardCandles(),
                profile.horizonLabel(),
                profile.minimumMovePercent(),
                evaluation.evaluationTimestamp(),
                evaluation.evaluationPeriodLabel(),
                evaluation.evaluationClose(),
                evaluation.directionalReturnPercent(),
                evaluation.bestDirectionalMovePercent(),
                evaluation.worstDirectionalMovePercent(),
                impactLabel(signal.tradeSignal(), evaluation.directionalReturnPercent()),
                lifecycle,
                evidence
        );
    }

    private HistoricalLifecycleView evaluateLifecycle(DetectedSignal signal,
                                                       List<Candle> candles,
                                                       int signalIndex,
                                                       double patternHigh,
                                                       double patternLow,
                                                       ScanProfile profile) {
        double confirmationTrigger = signal.tradeSignal() == TradeSignal.BUY ? patternHigh : patternLow;
        double invalidationBoundary = signal.tradeSignal() == TradeSignal.BUY ? patternLow : patternHigh;
        List<Candle> subsequentCandles = signalIndex + 1 >= candles.size()
                ? List.of()
                : candles.subList(signalIndex + 1, candles.size());
        CandlestickSignalLifecyclePolicy.LifecycleResolution resolution =
                CandlestickSignalLifecyclePolicy.resolve(
                        signal.tradeSignal(),
                        confirmationTrigger,
                        invalidationBoundary,
                        subsequentCandles,
                        confirmationWindowCandles
                );
        SignalLifecycleStatus status = resolution == null
                ? SignalLifecycleStatus.DETECTED
                : resolution.status();
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
            case DETECTED -> String.format(
                    Locale.ROOT,
                    "Waiting for a completed candle to close %s %.4f. The setup expires after %d candles unless it confirms or invalidates first.",
                    boundaryDirection,
                    confirmationTrigger,
                    confirmationWindowCandles
            );
            case CONFIRMED -> String.format(
                    Locale.ROOT,
                    "Confirmed on %s when candle %d closed at %.4f, %s the %.4f trigger.",
                    resolutionPeriod,
                    resolution.candleOffset(),
                    resolution.resolutionCandle().getClosePrice(),
                    boundaryDirection,
                    confirmationTrigger
            );
            case INVALIDATED -> String.format(
                    Locale.ROOT,
                    "Invalidated on %s when candle %d closed at %.4f, %s the %.4f boundary.",
                    resolutionPeriod,
                    resolution.candleOffset(),
                    resolution.resolutionCandle().getClosePrice(),
                    invalidationDirection,
                    invalidationBoundary
            );
            case EXPIRED -> String.format(
                    Locale.ROOT,
                    "Expired after %d completed candles without a close beyond either lifecycle boundary.",
                    confirmationWindowCandles
            );
        };
        return new HistoricalLifecycleView(
                status,
                lifecycleLabel(status),
                status.name().toLowerCase(Locale.ROOT),
                status != SignalLifecycleStatus.DETECTED,
                summary,
                patternHigh,
                patternLow,
                confirmationTrigger,
                invalidationBoundary,
                confirmationWindowCandles,
                resolution == null ? null : resolution.candleOffset(),
                resolutionPeriod,
                resolution == null ? null : resolution.resolutionCandle().getClosePrice()
        );
    }

    private String lifecycleLabel(SignalLifecycleStatus status) {
        return switch (status) {
            case DETECTED -> "Detected";
            case CONFIRMED -> "Confirmed";
            case INVALIDATED -> "Invalidated";
            case EXPIRED -> "Expired";
        };
    }

    private OutcomeEvaluation evaluateOutcome(DetectedSignal signal,
                                              List<Candle> candles,
                                              int signalIndex,
                                              ScanProfile profile) {
        int availableForwardCandles = candles.size() - signalIndex - 1;
        if (availableForwardCandles < profile.forwardCandles()) {
            int remaining = profile.forwardCandles() - availableForwardCandles;
            return new OutcomeEvaluation(
                    HistoricalOutcome.PENDING,
                    "Awaiting outcome",
                    "pending",
                    "Needs " + remaining + " more completed "
                            + nativeCandleLabel(profile.interval(), remaining)
                            + " before the " + profile.horizonLabel() + " result is known.",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        int evaluationIndex = signalIndex + profile.forwardCandles();
        Candle evaluationCandle = candles.get(evaluationIndex);
        List<Candle> futureCandles = candles.subList(signalIndex + 1, evaluationIndex + 1);
        double directionalReturn = directionalReturnPercent(
                signal.tradeSignal(),
                signal.closePrice(),
                evaluationCandle.getClosePrice()
        );
        double highestHigh = futureCandles.stream().mapToDouble(Candle::getHighPrice).max()
                .orElse(evaluationCandle.getClosePrice());
        double lowestLow = futureCandles.stream().mapToDouble(Candle::getLowPrice).min()
                .orElse(evaluationCandle.getClosePrice());
        double bestMove = signal.tradeSignal() == TradeSignal.BUY
                ? percentMove(signal.closePrice(), highestHigh)
                : -percentMove(signal.closePrice(), lowestLow);
        double worstMove = signal.tradeSignal() == TradeSignal.BUY
                ? percentMove(signal.closePrice(), lowestLow)
                : -percentMove(signal.closePrice(), highestHigh);

        HistoricalOutcome outcome;
        if (directionalReturn >= profile.minimumMovePercent()) {
            outcome = HistoricalOutcome.SUCCESS;
        } else if (directionalReturn <= -profile.minimumMovePercent()) {
            outcome = HistoricalOutcome.FAILURE;
        } else {
            outcome = HistoricalOutcome.INCONCLUSIVE;
        }
        String evaluationPeriod = SignalPeriodFormatter.format(
                evaluationCandle.getTimestamp(),
                profile.interval(),
                signalTimeZone
        );
        return new OutcomeEvaluation(
                outcome,
                outcome == HistoricalOutcome.SUCCESS
                        ? "Successful"
                        : outcome == HistoricalOutcome.FAILURE
                        ? "Unsuccessful"
                        : "Inconclusive",
                outcome.name().toLowerCase(Locale.ROOT),
                outcomeSummary(signal.tradeSignal(), directionalReturn, profile),
                evaluationCandle.getTimestamp(),
                evaluationPeriod,
                evaluationCandle.getClosePrice(),
                directionalReturn,
                bestMove,
                worstMove
        );
    }

    private String outcomeSummary(TradeSignal direction,
                                  double directionalReturn,
                                  ScanProfile profile) {
        String result = String.format(Locale.ROOT, "%+.2f%%", directionalReturn);
        String interpretation = direction == TradeSignal.BUY
                ? "directional return"
                : "decline avoided";
        return result + " " + interpretation + " at the " + profile.horizonLabel()
                + " close. Success requires at least "
                + String.format(Locale.ROOT, "%.1f%%", profile.minimumMovePercent())
                + " in the expected direction.";
    }

    private String impactLabel(TradeSignal direction, Double directionalReturn) {
        if (directionalReturn == null) {
            return "Outcome pending";
        }
        if (direction == TradeSignal.BUY) {
            return directionalReturn >= 0.0 ? "Potential gain" : "Potential loss";
        }
        return directionalReturn >= 0.0 ? "Potential loss avoided" : "Price rose instead";
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
        DAILY("1d", TimeInterval.DAILY, "Daily", 60,
                10, "10-session horizon", 3.0),
        WEEKLY("1wk", TimeInterval.WEEKLY, "Weekly", 104,
                4, "4-week horizon", 4.0),
        MONTHLY("1mo", TimeInterval.MONTHLY, "Monthly", 120,
                3, "3-month horizon", 6.0);

        private final String apiInterval;
        private final TimeInterval interval;
        private final String intervalLabel;
        private final int defaultLookbackCandles;
        private final int forwardCandles;
        private final String horizonLabel;
        private final double minimumMovePercent;

        ScanProfile(String apiInterval,
                    TimeInterval interval,
                    String intervalLabel,
                    int defaultLookbackCandles,
                    int forwardCandles,
                    String horizonLabel,
                    double minimumMovePercent) {
            this.apiInterval = apiInterval;
            this.interval = interval;
            this.intervalLabel = intervalLabel;
            this.defaultLookbackCandles = defaultLookbackCandles;
            this.forwardCandles = forwardCandles;
            this.horizonLabel = horizonLabel;
            this.minimumMovePercent = minimumMovePercent;
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

        private int forwardCandles() {
            return forwardCandles;
        }

        private String horizonLabel() {
            return horizonLabel;
        }

        private double minimumMovePercent() {
            return minimumMovePercent;
        }
    }

    public record HistoricalScan(
            String symbol,
            String companyName,
            String interval,
            String intervalLabel,
            int lookbackCandles,
            String lookbackLabel,
            int evaluationHorizonCandles,
            String evaluationHorizonLabel,
            double successThresholdPercent,
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
            HistoricalOutcome status,
            String statusLabel,
            String statusClass,
            String outcomeSummary,
            int evaluationHorizonCandles,
            String evaluationHorizonLabel,
            double successThresholdPercent,
            Long evaluationTimestamp,
            String evaluationPeriodLabel,
            Double evaluationClose,
            Double directionalReturnPercent,
            Double bestDirectionalMovePercent,
            Double worstDirectionalMovePercent,
            String impactLabel,
            @JsonIgnore HistoricalLifecycleView lifecycle,
            @JsonIgnore List<EvidenceSection> evidence
    ) {
        public HistoricalSignal {
            evidence = List.copyOf(evidence);
        }

        public boolean outcomeAvailable() {
            return status != HistoricalOutcome.PENDING;
        }
    }

    public record HistoricalLifecycleView(
            SignalLifecycleStatus status,
            String label,
            String cssClass,
            boolean terminal,
            String summary,
            double patternHigh,
            double patternLow,
            double confirmationTriggerPrice,
            double invalidationPrice,
            int confirmationWindowCandles,
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
            List<HistoricalResultPoint> points
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
                    List.of()
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

    public enum HistoricalOutcome {
        SUCCESS,
        FAILURE,
        INCONCLUSIVE,
        PENDING
    }

    private record OutcomeEvaluation(
            HistoricalOutcome status,
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
