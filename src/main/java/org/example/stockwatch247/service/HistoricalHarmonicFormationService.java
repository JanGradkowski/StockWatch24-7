package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class HistoricalHarmonicFormationService {
    private static final int RESULT_CANDLES = 10;

    private final CandleRepository candleRepository;
    private final CandleCompletionService candleCompletionService;
    private final HarmonicPatternDetectionService detectionService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;
    private final CrossPatternConfluenceService crossPatternConfluenceService;

    @Autowired
    public HistoricalHarmonicFormationService(
            CandleRepository candleRepository,
            CandleCompletionService candleCompletionService,
            HarmonicPatternDetectionService detectionService,
            TechnicalIndicatorEnrichmentService enrichmentService,
            ElliottWaveDetectionService elliottWaveDetectionService,
            CrossPatternConfluenceService crossPatternConfluenceService) {
        this.candleRepository = candleRepository;
        this.candleCompletionService = candleCompletionService;
        this.detectionService = detectionService;
        this.enrichmentService = enrichmentService;
        this.elliottWaveDetectionService = elliottWaveDetectionService;
        this.crossPatternConfluenceService = crossPatternConfluenceService;
    }

    HistoricalHarmonicFormationService(
            CandleRepository candleRepository,
            CandleCompletionService candleCompletionService,
            HarmonicPatternDetectionService detectionService) {
        this(candleRepository, candleCompletionService, detectionService,
                null, new ElliottWaveDetectionService(),
                new CrossPatternConfluenceService(new CandlePatternDetectionService()));
    }

    public HistoricalHarmonicDetail findDetail(
            String rawSymbol,
            String rawInterval,
            HarmonicPatternType pattern,
            long endpointTimestamp) {
        return findDetail(rawSymbol, rawInterval, pattern, endpointTimestamp, detectionService);
    }

    public HistoricalHarmonicDetail findDetail(
            String rawSymbol,
            String rawInterval,
            HarmonicPatternType pattern,
            long endpointTimestamp,
            HarmonicPatternDetectionService configuredDetector) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        String interval = SecurityInputValidator.requireInterval(rawInterval);
        TimeInterval timeInterval = timeInterval(interval);
        if (pattern == null || endpointTimestamp <= 0L) {
            throw new IllegalArgumentException("A valid harmonic pattern and endpoint are required.");
        }
        long firstIncomplete = candleCompletionService.firstIncompleteCandleTimestamp(timeInterval);
        List<Candle> candles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval)
                .stream()
                .filter(this::validCandle)
                .filter(candle -> candle.getTimestamp() < firstIncomplete)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        HarmonicPatternDetectionService.HarmonicFormation formation = (configuredDetector == null
                ? detectionService : configuredDetector)
                .detectHistorical(candles)
                .stream()
                .filter(candidate -> candidate.pattern() == pattern)
                .filter(candidate -> candidate.points().getLast().timestamp() == endpointTimestamp)
                .min(Comparator.comparingDouble(
                        HarmonicPatternDetectionService.HarmonicFormation::classificationError))
                .orElseThrow(() -> new IllegalArgumentException(
                        "The historical harmonic formation is no longer available in the completed candle cache."));

        int confirmationIndex = candleIndex(candles, formation.confirmationTimestamp());
        if (confirmationIndex < 0) {
            throw new IllegalArgumentException("The harmonic confirmation candle is unavailable.");
        }
        Candle confirmationCandle = candles.get(confirmationIndex);
        int firstPointIndex = candleIndex(candles, formation.points().getFirst().timestamp());
        int chartStart = Math.max(0, firstPointIndex - 5);
        int chartEnd = Math.min(candles.size(), confirmationIndex + RESULT_CANDLES + 1);
        List<CandleView> chartCandles = candles.subList(chartStart, chartEnd).stream()
                .map(this::toCandleView)
                .toList();
        CrossPatternConfluenceService.Assessment confluence = historicalConfluence(
                candles, timeInterval, formation, configuredDetector);
        List<String> scoringReasons = new java.util.ArrayList<>(formation.reasons());
        scoringReasons.add(confluence.reason());
        List<ScoreSectionView> scoreSections = scoringReasons.stream()
                .map(reason -> SignalScoreBreakdown.parse(
                        reason, "Harmonic geometry", formation.tradeSignal()))
                .map(section -> new ScoreSectionView(
                        section.category(), section.scoreLabel(), section.status(),
                        section.details().stream()
                                .map(detail -> new ScoreDetailView(
                                        detail.label(), detail.text(), detail.score()))
                                .toList()))
                .toList();
        HarmonicPatternDetectionService.HarmonicPoint endpoint = formation.points().getLast();
        return new HistoricalHarmonicDetail(
                symbol,
                interval,
                timeInterval,
                pattern,
                pattern.displayName(),
                formation.direction().name(),
                formation.tradeSignal(),
                endpoint.label(),
                endpoint.timestamp(),
                endpoint.price(),
                SignalPeriodFormatter.format(endpoint.timestamp(), timeInterval, ZoneId.systemDefault()),
                formation.confirmationTimestamp(),
                confirmationCandle.getClosePrice(),
                SignalPeriodFormatter.format(
                        formation.confirmationTimestamp(), timeInterval, ZoneId.systemDefault()),
                confluence.adjustedScore(),
                formation.classificationError(),
                formation.ruleVersion(),
                formation.points().stream()
                        .map(point -> new PointView(
                                point.label(), point.timestamp(), point.price(), point.pivotType().name()))
                        .toList(),
                formation.measurements(),
                List.copyOf(scoringReasons),
                scoreSections,
                chartCandles,
                result(candles, confirmationIndex, confirmationCandle, formation.tradeSignal())
        );
    }

    private CrossPatternConfluenceService.Assessment historicalConfluence(
            List<Candle> candles,
            TimeInterval interval,
            HarmonicPatternDetectionService.HarmonicFormation formation,
            HarmonicPatternDetectionService configuredDetector) {
        if (enrichmentService == null) {
            return crossPatternConfluenceService.assess(
                    formation.qualityScore(),
                    org.example.stockwatch247.model.enums.AlertPatternFamily.HARMONIC_FORMATION,
                    formation.tradeSignal(), formation.confirmationTimestamp(),
                    new CrossPatternConfluenceService.Timeline(
                            candles.stream().map(Candle::getTimestamp).toList(), List.of()));
        }
        List<org.example.stockwatch247.model.EnrichedCandle> candlestick = enrichmentService.enrich(
                candles, candles.size(), interval);
        List<org.example.stockwatch247.model.EnrichedCandle> elliott = enrichmentService.enrichForElliott(
                candles, candles.size(), interval);
        CrossPatternConfluenceService.Timeline timeline = crossPatternConfluenceService.buildTimeline(
                candles, candlestick, elliott, interval,
                CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval),
                CandlestickPatternPreferencesService.factoryPreferences(),
                elliottWaveDetectionService,
                configuredDetector == null ? detectionService : configuredDetector);
        return crossPatternConfluenceService.assess(
                formation.qualityScore(),
                org.example.stockwatch247.model.enums.AlertPatternFamily.HARMONIC_FORMATION,
                formation.tradeSignal(), formation.confirmationTimestamp(), timeline);
    }

    private ResultView result(
            List<Candle> candles,
            int signalIndex,
            Candle signalCandle,
            TradeSignal tradeSignal) {
        int available = candles.size() - signalIndex - 1;
        List<ResultPointView> points = new java.util.ArrayList<>();
        points.add(resultPoint(0, signalCandle, signalCandle.getClosePrice(), tradeSignal));
        for (int offset = 1; offset <= available; offset++) {
            points.add(resultPoint(
                    offset, candles.get(signalIndex + offset), signalCandle.getClosePrice(), tradeSignal));
        }
        if (available < RESULT_CANDLES) {
            return new ResultView(false, RESULT_CANDLES, available, null, null,
                    signalCandle.getClosePrice(), signalCandle.getTimestamp(), tradeSignal,
                    outcomeLabel(tradeSignal), bestActionLabel(tradeSignal), List.copyOf(points),
                    "At least 10 completed candles after confirmation are required.");
        }
        List<ResultPointView> window = points.subList(1, RESULT_CANDLES + 1);
        return new ResultView(
                true,
                RESULT_CANDLES,
                available,
                window.stream().mapToDouble(ResultPointView::directionalReturnPercent).max().orElse(0.0),
                window.getLast().directionalReturnPercent(),
                signalCandle.getClosePrice(),
                signalCandle.getTimestamp(),
                tradeSignal,
                outcomeLabel(tradeSignal),
                bestActionLabel(tradeSignal),
                List.copyOf(points),
                null);
    }

    private ResultPointView resultPoint(
            int offset,
            Candle candle,
            double signalClose,
            TradeSignal tradeSignal) {
        double raw = (candle.getClosePrice() - signalClose) / signalClose * 100.0;
        double rawDifference = candle.getClosePrice() - signalClose;
        double directional = tradeSignal == TradeSignal.SELL ? -raw : raw;
        double directionalDifference = tradeSignal == TradeSignal.SELL ? -rawDifference : rawDifference;
        return new ResultPointView(
                offset,
                candle.getTimestamp(),
                SignalPeriodFormatter.format(
                        candle.getTimestamp(), timeInterval(candle.getTimeInterval()), ZoneId.systemDefault()),
                candle.getClosePrice(),
                directional,
                directionalDifference);
    }

    private String outcomeLabel(TradeSignal tradeSignal) {
        return tradeSignal == TradeSignal.SELL
                ? "Largest close-based loss avoided"
                : "Best close-based return";
    }

    private String bestActionLabel(TradeSignal tradeSignal) {
        return tradeSignal == TradeSignal.SELL ? "Best re-entry close" : "Best sell close";
    }

    private int candleIndex(List<Candle> candles, long timestamp) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp() == timestamp) return index;
        }
        return -1;
    }

    private TimeInterval timeInterval(String interval) {
        return switch (interval) {
            case "1d" -> TimeInterval.DAILY;
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Historical harmonic details require a daily, weekly, or monthly interval.");
        };
    }

    private boolean validCandle(Candle candle) {
        return candle != null && candle.getTimestamp() != null
                && candle.getOpenPrice() != null && Double.isFinite(candle.getOpenPrice())
                && candle.getHighPrice() != null && Double.isFinite(candle.getHighPrice())
                && candle.getLowPrice() != null && Double.isFinite(candle.getLowPrice())
                && candle.getClosePrice() != null && Double.isFinite(candle.getClosePrice())
                && candle.getHighPrice() >= candle.getLowPrice();
    }

    private CandleView toCandleView(Candle candle) {
        return new CandleView(candle.getTimestamp(), candle.getOpenPrice(), candle.getHighPrice(),
                candle.getLowPrice(), candle.getClosePrice());
    }

    public record HistoricalHarmonicDetail(
            String symbol,
            String interval,
            TimeInterval timeInterval,
            HarmonicPatternType pattern,
            String patternLabel,
            String direction,
            TradeSignal tradeSignal,
            String endpointLabel,
            long endpointTimestamp,
            double endpointPrice,
            String endpointPeriodLabel,
            long confirmationTimestamp,
            double confirmationClose,
            String confirmationPeriodLabel,
            int qualityScore,
            double classificationError,
            String ruleVersion,
            List<PointView> points,
            Map<String, Double> measurements,
            List<String> reasons,
            List<ScoreSectionView> scoreSections,
            List<CandleView> candles,
            ResultView result) {
        public String intervalLabel() {
            return switch (timeInterval) {
                case DAILY -> "Daily";
                case WEEKLY -> "Weekly";
                case MONTHLY -> "Monthly";
                default -> timeInterval.name();
            };
        }
    }

    public record PointView(String label, long timestamp, double price, String pivotType) { }
    public record CandleView(long timestamp, double open, double high, double low, double close) { }
    public record ScoreSectionView(String category, String scoreLabel, String status,
                                   List<ScoreDetailView> details) { }
    public record ScoreDetailView(String label, String text, String score) { }
    public record ResultView(boolean available, int requiredForwardCandles, int availableForwardCandles,
                             Double bestDirectionalReturnPercent, Double windowEndDirectionalReturnPercent,
                             Double signalClose, Long signalTimestamp, TradeSignal tradeSignal,
                             String outcomeLabel, String bestActionLabel,
                             List<ResultPointView> points, String unavailableReason) { }
    public record ResultPointView(int candleNumber, long timestamp, String periodLabel,
                                  double close, double directionalReturnPercent,
                                  double directionalPriceDifference) { }
}
