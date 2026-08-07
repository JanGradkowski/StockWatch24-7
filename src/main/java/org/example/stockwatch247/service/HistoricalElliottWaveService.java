package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Service
public class HistoricalElliottWaveService {
    private static final int RESULT_CANDLES = 10;
    private static final int CONFIRMATION_LAG_CANDLES = 3;

    private final CandleRepository candleRepository;
    private final CandleCompletionService candleCompletionService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final ElliottWaveDetectionService detectionService;

    public HistoricalElliottWaveService(
            CandleRepository candleRepository,
            CandleCompletionService candleCompletionService,
            TechnicalIndicatorEnrichmentService enrichmentService,
            ElliottWaveDetectionService detectionService) {
        this.candleRepository = candleRepository;
        this.candleCompletionService = candleCompletionService;
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
    }

    public HistoricalElliottWaveDetail findDetail(
            String rawSymbol,
            String rawInterval,
            ElliottSignalStage stage,
            long endpointTimestamp,
            String requestedCycleKey) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        String interval = SecurityInputValidator.requireInterval(rawInterval);
        TimeInterval timeInterval = switch (interval) {
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> throw new IllegalArgumentException("Historical Elliott details require a weekly or monthly interval.");
        };
        if (stage == null || endpointTimestamp <= 0L) {
            throw new IllegalArgumentException("A valid Elliott stage and endpoint are required.");
        }

        long firstIncompleteTimestamp = candleCompletionService.firstIncompleteCandleTimestamp(timeInterval);
        List<Candle> candles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval)
                .stream()
                .filter(this::validCandle)
                .filter(candle -> candle.getTimestamp() < firstIncompleteTimestamp)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        List<EnrichedCandle> enriched = enrichmentService.enrichForElliott(
                candles,
                candles.size(),
                timeInterval
        );
        ElliottWaveDetectionService.ElliottWaveStructure structure = detectionService
                .findHistoricalWaveStructures(enriched)
                .stream()
                .filter(candidate -> requestedCycleKey == null || requestedCycleKey.isBlank()
                        || detectionService.lifecycleCycleKey(candidate)
                        .filter(requestedCycleKey::equals)
                        .isPresent())
                .filter(candidate -> endpoint(candidate, stage) != null
                        && endpoint(candidate, stage).timestamp() == endpointTimestamp)
                .max(Comparator.comparingInt(ElliottWaveDetectionService.ElliottWaveStructure::qualityScore))
                .orElseThrow(() -> new IllegalArgumentException("The historical Elliott wave is no longer available in the completed candle cache."));

        ElliottWaveDetectionService.ElliottWavePoint endpoint = endpoint(structure, stage);
        TradeSignal tradeSignal = tradeSignal(structure.direction(), stage);
        Long confirmationTimestamp = confirmationTimestamp(structure, stage, endpoint, tradeSignal, candles);
        ElliottWaveDetectionService.ElliottScoreAssessment score = java.util.Optional.ofNullable(
                        detectionService.scoreHistoricalStructure(enriched, structure, stage, confirmationTimestamp))
                .orElseGet(() -> new ElliottWaveDetectionService.ElliottScoreAssessment(
                        structure.qualityScore(), List.of()));
        List<ScoreSectionView> scoreSections = score.reasons().stream()
                .map(reason -> SignalScoreBreakdown.parse(reason, "Elliott evidence", tradeSignal))
                .map(section -> new ScoreSectionView(
                        section.category(),
                        section.scoreLabel(),
                        section.status(),
                        section.details().stream()
                                .map(detail -> new ScoreDetailView(detail.label(), detail.text(), detail.score()))
                                .toList()))
                .toList();
        int signalIndex = candleIndex(candles, confirmationTimestamp);
        ResultView result = result(candles, signalIndex, tradeSignal);
        int firstPointIndex = candleIndex(candles, structure.points().getFirst().timestamp());
        int lastPointIndex = candleIndex(candles, structure.points().getLast().timestamp());
        int chartStart = Math.max(0, firstPointIndex - 5);
        int chartEnd = Math.min(
                candles.size(),
                Math.max(lastPointIndex + 1, signalIndex < 0 ? 0 : signalIndex + RESULT_CANDLES + 1)
        );
        List<CandleView> chartCandles = chartStart < chartEnd
                ? candles.subList(chartStart, chartEnd).stream().map(this::toCandleView).toList()
                : List.of();

        String cycleKey = detectionService.lifecycleCycleKey(structure).orElse(null);
        return new HistoricalElliottWaveDetail(
                symbol,
                interval,
                timeInterval,
                cycleKey,
                stage,
                stage == ElliottSignalStage.CORRECTION_END ? "ABC correction ending" : "Wave V ending",
                structure.direction(),
                tradeSignal,
                confirmationTimestamp == null ? "DETECTED" : "CONFIRMED",
                confirmationTimestamp == null ? "Detected" : "Confirmed",
                endpointTimestamp,
                SignalPeriodFormatter.format(endpointTimestamp, timeInterval, ZoneId.systemDefault()),
                confirmationTimestamp,
                SignalPeriodFormatter.format(
                        confirmationTimestamp == null ? endpointTimestamp : confirmationTimestamp,
                        timeInterval,
                        ZoneId.systemDefault()),
                score.score(),
                ElliottWaveDetectionService.SETUP_SCORE_VERSION,
                scoreSections,
                structure.waveTwoRetracement(),
                structure.waveThreeToOneRatio(),
                structure.waveFourRetracement(),
                structure.impulseVariant().name(),
                structure.correctionVariant().name(),
                structure.qualityWarnings(),
                structure.points().stream()
                        .map(point -> new PointView(point.label(), point.timestamp(), point.price(), point.pivotType()))
                        .toList(),
                chartCandles,
                result
        );
    }

    private ElliottWaveDetectionService.ElliottWavePoint endpoint(
            ElliottWaveDetectionService.ElliottWaveStructure structure,
            ElliottSignalStage stage) {
        String label = stage == ElliottSignalStage.CORRECTION_END ? "C" : "V";
        return structure.points().stream()
                .filter(point -> label.equalsIgnoreCase(point.label()))
                .findFirst()
                .orElse(null);
    }

    private TradeSignal tradeSignal(String direction, ElliottSignalStage stage) {
        boolean bullish = "BULLISH".equals(direction);
        if (stage == ElliottSignalStage.CORRECTION_END) {
            return bullish ? TradeSignal.BUY : TradeSignal.SELL;
        }
        return bullish ? TradeSignal.SELL : TradeSignal.BUY;
    }

    private Long confirmationTimestamp(
            ElliottWaveDetectionService.ElliottWaveStructure structure,
            ElliottSignalStage stage,
            ElliottWaveDetectionService.ElliottWavePoint endpoint,
            TradeSignal tradeSignal,
            List<Candle> candles) {
        boolean structureEndsAtRequestedStage = structure.correctionComplete()
                == (stage == ElliottSignalStage.CORRECTION_END);
        if (structureEndsAtRequestedStage && structure.confirmationTimestamp() != null) {
            return structure.confirmationTimestamp();
        }
        int endpointIndex = candleIndex(candles, endpoint.timestamp());
        if (endpointIndex < 0) {
            return null;
        }
        int lastCandidate = Math.min(candles.size() - 1, endpointIndex + CONFIRMATION_LAG_CANDLES);
        for (int index = endpointIndex + 1; index <= lastCandidate; index++) {
            Candle current = candles.get(index);
            Candle previous = candles.get(index - 1);
            boolean confirmed = tradeSignal == TradeSignal.BUY
                    ? current.getClosePrice() > previous.getHighPrice()
                    : current.getClosePrice() < previous.getLowPrice();
            if (confirmed) {
                return current.getTimestamp();
            }
        }
        return null;
    }

    private ResultView result(List<Candle> candles, int signalIndex, TradeSignal tradeSignal) {
        int available = signalIndex < 0 ? 0 : candles.size() - signalIndex - 1;
        if (signalIndex < 0 || available < RESULT_CANDLES) {
            return new ResultView(
                    false,
                    RESULT_CANDLES,
                    Math.max(0, available),
                    null,
                    null,
                    null,
                    null,
                    tradeSignal,
                    tradeSignal == TradeSignal.SELL
                            ? "Largest close-based loss avoided"
                            : "Best close-based return",
                    tradeSignal == TradeSignal.SELL ? "Best re-entry close" : "Best sell close",
                    List.of(),
                    signalIndex < 0
                            ? "The historical confirmation candle is unavailable."
                            : "At least 10 completed candles after confirmation are required."
            );
        }
        double signalClose = candles.get(signalIndex).getClosePrice();
        List<ResultPointView> points = new java.util.ArrayList<>(available + 1);
        Candle signalCandle = candles.get(signalIndex);
        points.add(new ResultPointView(
                0,
                signalCandle.getTimestamp(),
                SignalPeriodFormatter.format(signalCandle.getTimestamp(), intervalFor(signalCandle), ZoneId.systemDefault()),
                signalClose,
                0.0,
                0.0
        ));
        for (int offset = 1; offset <= available; offset++) {
            Candle candle = candles.get(signalIndex + offset);
            double rawDifference = candle.getClosePrice() - signalClose;
            double directionalDifference = tradeSignal == TradeSignal.SELL ? -rawDifference : rawDifference;
            points.add(new ResultPointView(
                    offset,
                    candle.getTimestamp(),
                    SignalPeriodFormatter.format(candle.getTimestamp(), intervalFor(candle), ZoneId.systemDefault()),
                    candle.getClosePrice(),
                    directionalDifference / signalClose * 100.0,
                    directionalDifference
            ));
        }
        List<Double> returns = points.subList(1, RESULT_CANDLES + 1).stream()
                .map(ResultPointView::directionalReturnPercent)
                .toList();
        return new ResultView(
                true,
                RESULT_CANDLES,
                available,
                returns.stream().mapToDouble(Double::doubleValue).max().orElse(0.0),
                returns.getLast(),
                signalClose,
                signalCandle.getTimestamp(),
                tradeSignal,
                tradeSignal == TradeSignal.SELL
                        ? "Largest close-based loss avoided"
                        : "Best close-based return",
                tradeSignal == TradeSignal.SELL ? "Best re-entry close" : "Best sell close",
                List.copyOf(points),
                null
        );
    }

    private TimeInterval intervalFor(Candle candle) {
        return "1mo".equals(candle.getTimeInterval()) ? TimeInterval.MONTHLY : TimeInterval.WEEKLY;
    }

    private int candleIndex(List<Candle> candles, Long timestamp) {
        if (timestamp == null) {
            return -1;
        }
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp().equals(timestamp)) {
                return index;
            }
        }
        return -1;
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
        return new CandleView(
                candle.getTimestamp(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice()
        );
    }

    public record HistoricalElliottWaveDetail(
            String symbol,
            String interval,
            TimeInterval timeInterval,
            String cycleKey,
            ElliottSignalStage stage,
            String stageLabel,
            String direction,
            TradeSignal tradeSignal,
            String status,
            String statusLabel,
            long endpointTimestamp,
            String endpointPeriodLabel,
            Long confirmationTimestamp,
            String signalPeriodLabel,
            int qualityScore,
            String scoreVersion,
            List<ScoreSectionView> scoreSections,
            double waveTwoRetracement,
            double waveThreeToOneRatio,
            double waveFourRetracement,
            String impulseVariant,
            String correctionVariant,
            List<String> qualityWarnings,
            List<PointView> points,
            List<CandleView> candles,
            ResultView result) {
    }

    public record ScoreSectionView(String category, String scoreLabel, String status,
                                   List<ScoreDetailView> details) {
    }

    public record ScoreDetailView(String label, String text, String score) {
    }

    public record PointView(String label, Long timestamp, double price, String pivotType) {
    }

    public record CandleView(long timestamp, double open, double high, double low, double close) {
    }

    public record ResultView(
            boolean available,
            int requiredForwardCandles,
            int availableForwardCandles,
            Double bestDirectionalReturnPercent,
            Double windowEndDirectionalReturnPercent,
            Double signalClose,
            Long signalTimestamp,
            TradeSignal tradeSignal,
            String outcomeLabel,
            String bestActionLabel,
            List<ResultPointView> points,
            String unavailableReason) {
    }

    public record ResultPointView(
            int candleNumber,
            long timestamp,
            String periodLabel,
            double close,
            double directionalReturnPercent,
            double directionalPriceDifference) {
    }
}
