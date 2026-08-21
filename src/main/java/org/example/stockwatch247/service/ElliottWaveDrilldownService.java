package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class ElliottWaveDrilldownService {
    private static final int MAX_LOWER_INTERVAL_CANDLES = 1_000;
    private static final long MAX_PARENT_SPAN_SECONDS = 60L * 366L * 24L * 60L * 60L;
    private static final Set<String> WAVE_LABELS = Set.of(
            "I", "II", "III", "IV", "V", "A", "B", "C", "1", "2", "3", "4", "5",
            "D", "E", "i", "ii", "iii", "iv", "v", "a", "b", "c", "d", "e");

    private final MarketDataService marketDataService;
    private final CandleCompletionService candleCompletionService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final ElliottWaveDetectionService detectionService;

    public ElliottWaveDrilldownService(MarketDataService marketDataService,
                                       CandleCompletionService candleCompletionService,
                                       TechnicalIndicatorEnrichmentService enrichmentService,
                                       ElliottWaveDetectionService detectionService) {
        this.marketDataService = marketDataService;
        this.candleCompletionService = candleCompletionService;
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
    }

    public DrilldownView drillDown(String rawSymbol,
                                   String rawParentInterval,
                                   String rawParentLabel,
                                   long parentStart,
                                   long parentEnd,
                                   double parentStartPrice,
                                   double parentEndPrice,
                                   Long requestedAsOfExclusive) {
        return drillDown(rawSymbol, rawParentInterval, rawParentLabel, parentStart, parentEnd,
                parentStartPrice, parentEndPrice, requestedAsOfExclusive, null);
    }

    public DrilldownView drillDown(String rawSymbol,
                                   String rawParentInterval,
                                   String rawParentLabel,
                                   long parentStart,
                                   long parentEnd,
                                   double parentStartPrice,
                                   double parentEndPrice,
                                   Long requestedAsOfExclusive,
                                   ElliottWaveDetectionService.DetectionRules rules) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        String parentInterval = SecurityInputValidator.requireInterval(rawParentInterval);
        String lowerInterval = lowerInterval(parentInterval);
        String parentLabel = requireWaveLabel(rawParentLabel);
        validateBoundary(parentStart, parentEnd, parentStartPrice, parentEndPrice);

        TimeInterval lowerTimeInterval = "1wk".equals(lowerInterval)
                ? TimeInterval.WEEKLY
                : TimeInterval.DAILY;
        long parentEndExclusive = periodEndExclusive(parentEnd, parentInterval);
        long completedBoundary = candleCompletionService.firstIncompleteCandleTimestamp(lowerTimeInterval);
        long asOfExclusive = requestedAsOfExclusive == null
                ? parentEndExclusive
                : Math.min(requestedAsOfExclusive, completedBoundary);
        if (asOfExclusive <= parentEnd || asOfExclusive < parentEndExclusive) {
            return unavailable(symbol, parentInterval, lowerInterval, parentLabel, parentStart,
                    parentEnd, asOfExclusive,
                    "The selected parent wave extends beyond the historical as-of boundary.");
        }
        long rangeEndExclusive = Math.min(parentEndExclusive, asOfExclusive);

        MarketDataService.CandlePage page = marketDataService.loadCandlePage(
                symbol, lowerInterval, rangeEndExclusive, MAX_LOWER_INTERVAL_CANDLES);
        List<Candle> candles = page.candles().stream()
                .filter(this::validCandle)
                .filter(candle -> candle.getTimestamp() >= parentStart
                        && lowerCandleEndExclusive(candle.getTimestamp(), lowerInterval)
                        <= rangeEndExclusive)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        if (candles.size() < 6) {
            return unavailableWithCandles(symbol, parentInterval, lowerInterval, parentLabel,
                    parentStart, parentEnd, parentStartPrice, parentEndPrice, asOfExclusive, candles,
                    "Not enough completed " + intervalLabel(lowerInterval)
                            + " candles are available inside this parent wave.");
        }
        List<EnrichedCandle> enriched = enrichmentService.enrich(
                candles, candles.size(), lowerTimeInterval);
        ElliottWaveDetectionService detector = rules == null
                ? detectionService
                : detectionService.configured(rules);
        return detector.findSubdivision(
                        enriched, parentLabel, parentStartPrice, parentEndPrice)
                .map(subdivision -> new DrilldownView(
                        true,
                        symbol,
                        parentInterval,
                        lowerInterval,
                        parentLabel,
                        parentStart,
                        parentEnd,
                        parentStartPrice,
                        parentEndPrice,
                        asOfExclusive,
                        subdivision.structureLabel(),
                        subdivision.confidence(),
                        subdivision.validated(),
                        subdivision.validated()
                                ? "One validated lower degree"
                                : "One provisional lower degree",
                        subdivision.evidence(),
                        candles.stream().map(this::toCandleView).toList(),
                        subdivision.points().stream().map(this::toPointView).toList(),
                        subdivision.alternatives().stream()
                                .map(alternative -> new AlternativeView(
                                        alternative.structureLabel(),
                                        alternative.confidence(),
                                        alternative.points().stream().map(this::toPointView).toList()))
                                .toList(),
                        null))
                .orElseGet(() -> unavailableWithCandles(symbol, parentInterval, lowerInterval,
                        parentLabel, parentStart, parentEnd, parentStartPrice, parentEndPrice,
                        asOfExclusive, candles,
                        "No lower-degree count passes the structural confidence threshold for this wave."));
    }

    private String lowerInterval(String parentInterval) {
        return switch (parentInterval) {
            case "1mo" -> "1wk";
            case "1wk" -> "1d";
            default -> throw new IllegalArgumentException(
                    "Wave drill-down currently moves from monthly to weekly or weekly to daily candles.");
        };
    }

    private String requireWaveLabel(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.trim();
        if (!WAVE_LABELS.contains(label)) {
            throw new IllegalArgumentException("Unsupported Elliott parent label.");
        }
        return label;
    }

    private void validateBoundary(long start, long end, double startPrice, double endPrice) {
        if (start <= 0L || end <= start || end - start > MAX_PARENT_SPAN_SECONDS
                || !Double.isFinite(startPrice) || !Double.isFinite(endPrice)
                || startPrice <= 0.0 || endPrice <= 0.0) {
            throw new IllegalArgumentException("Invalid parent-wave boundary.");
        }
    }

    private long periodEndExclusive(long periodStart, String interval) {
        LocalDate date = Instant.ofEpochSecond(periodStart).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate exclusive = switch (interval) {
            case "1mo" -> date.plusMonths(1).withDayOfMonth(1);
            case "1wk" -> date.plusWeeks(1);
            default -> throw new IllegalArgumentException("Unsupported parent interval.");
        };
        return exclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private long lowerCandleEndExclusive(long periodStart, String interval) {
        LocalDate date = Instant.ofEpochSecond(periodStart).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate exclusive = "1wk".equals(interval) ? date.plusWeeks(1) : date.plusDays(1);
        return exclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private boolean validCandle(Candle candle) {
        return candle != null && candle.getTimestamp() != null
                && candle.getOpenPrice() != null && Double.isFinite(candle.getOpenPrice())
                && candle.getHighPrice() != null && Double.isFinite(candle.getHighPrice())
                && candle.getLowPrice() != null && Double.isFinite(candle.getLowPrice())
                && candle.getClosePrice() != null && Double.isFinite(candle.getClosePrice())
                && candle.getHighPrice() >= candle.getLowPrice();
    }

    private String intervalLabel(String interval) {
        return "1wk".equals(interval) ? "weekly" : "daily";
    }

    private CandleView toCandleView(Candle candle) {
        return new CandleView(candle.getTimestamp(), candle.getOpenPrice(), candle.getHighPrice(),
                candle.getLowPrice(), candle.getClosePrice());
    }

    private PointView toPointView(ElliottWaveDetectionService.ElliottWavePoint point) {
        return new PointView(point.label(), point.timestamp(), point.price(), point.pivotType(), true);
    }

    private DrilldownView unavailable(String symbol,
                                      String parentInterval,
                                      String lowerInterval,
                                      String parentLabel,
                                      long parentStart,
                                      long parentEnd,
                                      long asOfExclusive,
                                      String reason) {
        return new DrilldownView(false, symbol, parentInterval, lowerInterval, parentLabel,
                parentStart, parentEnd, null, null, asOfExclusive, null, 0,
                false, null, List.of(), List.of(), List.of(), List.of(), reason);
    }

    private DrilldownView unavailableWithCandles(String symbol,
                                                 String parentInterval,
                                                 String lowerInterval,
                                                 String parentLabel,
                                                 long parentStart,
                                                 long parentEnd,
                                                 double parentStartPrice,
                                                 double parentEndPrice,
                                                 long asOfExclusive,
                                                 List<Candle> candles,
                                                 String reason) {
        return new DrilldownView(false, symbol, parentInterval, lowerInterval, parentLabel,
                parentStart, parentEnd, parentStartPrice, parentEndPrice, asOfExclusive,
                null, 0, false, "Lower-interval context", List.of(),
                candles.stream().map(this::toCandleView).toList(),
                List.of(), List.of(), reason);
    }

    public record DrilldownView(boolean available,
                                String symbol,
                                String parentInterval,
                                String interval,
                                String parentLabel,
                                long parentStart,
                                long parentEnd,
                                Double parentStartPrice,
                                Double parentEndPrice,
                                long asOfExclusive,
                                String structureLabel,
                                int confidence,
                                boolean validated,
                                String degreeLabel,
                                List<String> evidence,
                                List<CandleView> candles,
                                List<PointView> points,
                                List<AlternativeView> alternatives,
                                String unavailableReason) {
    }

    public record CandleView(long timestamp, double open, double high, double low, double close) {
    }

    public record PointView(String label, long timestamp, double price, String pivotType,
                            boolean completed) {
    }

    public record AlternativeView(String structureLabel, int confidence, List<PointView> points) {
    }
}
