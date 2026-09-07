package org.example.stockwatch247.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.VirtualTrade;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.VirtualTradeSide;
import org.example.stockwatch247.model.enums.VirtualTradeStatus;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.VirtualTradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class VirtualTradeService {
    private VirtualTradeArchiveQuery archiveQuery;
    @org.springframework.beans.factory.annotation.Autowired
    void setArchiveQuery(VirtualTradeArchiveQuery archiveQuery) { this.archiveQuery = archiveQuery; }

    private static final MathContext MONEY_CONTEXT = new MathContext(18, RoundingMode.HALF_UP);
    private static final BigDecimal MAX_SIZE = new BigDecimal("1000000000000");
    private static final String SNAPSHOT_VERSION = "TECHNICAL_OUTLOOK_V1";

    private final VirtualTradeRepository tradeRepository;
    private final StockAssetRepository stockAssetRepository;
    private final CandleRepository candleRepository;
    private final LivePricingService livePricingService;
    private final TechnicalOutlookService outlookService;
    private final ObjectMapper objectMapper;

    public VirtualTradeService(VirtualTradeRepository tradeRepository,
                               StockAssetRepository stockAssetRepository,
                               CandleRepository candleRepository,
                               LivePricingService livePricingService,
                               TechnicalOutlookService outlookService,
                               ObjectMapper objectMapper) {
        this.tradeRepository = tradeRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.candleRepository = candleRepository;
        this.livePricingService = livePricingService;
        this.outlookService = outlookService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TradeView create(User user, String symbol, CreateCommand command) {
        String requestId = requireRequestId(command.clientRequestId());
        var duplicate = tradeRepository.findByUserAndClientRequestId(user, requestId);
        if (duplicate.isPresent()) {
            return toTradeView(duplicate.get(), cachedQuote(duplicate.get().getStockAsset().getTickerSymbol()));
        }
        VirtualTradeSide side = requireSide(command.side());
        Interval interval = Interval.parse(command.interval());
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Open the stock workspace once so its market metadata can be resolved before creating a demo trade."));
        Quote quote = liveQuote(asset.getTickerSymbol());
        TechnicalOutlookService.OutlookView outlook = outlookService.getOutlook(
                user, asset.getTickerSymbol(), interval.apiValue());
        if (!outlook.available() || outlook.candleTimestamp() == null) {
            throw new IllegalStateException("Completed technical data is not available for this demo trade.");
        }
        BigDecimal quantity = optionalPositive(command.quantity(), "Quantity");
        BigDecimal notional = optionalPositive(command.notionalValue(), "Virtual investment");
        if (quantity != null && notional != null) {
            throw new IllegalArgumentException("Choose either a quantity or a virtual investment amount, not both.");
        }
        if (quantity == null && notional != null) {
            quantity = notional.divide(quote.price(), 8, RoundingMode.HALF_UP);
        } else if (quantity != null) {
            notional = quantity.multiply(quote.price(), MONEY_CONTEXT).setScale(8, RoundingMode.HALF_UP);
        }

        Instant now = Instant.now();
        VirtualTrade trade = new VirtualTrade();
        trade.setUser(user);
        trade.setStockAsset(asset);
        trade.setSide(side);
        trade.setStatus(VirtualTradeStatus.TRACKING);
        trade.setAnalysisInterval(interval.timeInterval());
        trade.setEntryPrice(quote.price());
        trade.setEntryAt(now);
        trade.setEntryQuoteTimestamp(quote.timestamp());
        trade.setEntryCandleTimestamp(outlook.candleTimestamp());
        trade.setEntryQuoteSource(quote.source());
        trade.setCurrency(asset.getCurrency() == null ? "USD" : asset.getCurrency());
        trade.setQuantity(quantity);
        trade.setNotionalValue(notional);
        trade.setEntrySnapshot(writeSnapshot(snapshot(outlook, now)));
        trade.setClientRequestId(requestId);
        trade.setCreatedAt(now);
        trade.setUpdatedAt(now);
        return toTradeView(tradeRepository.saveAndFlush(trade), quote);
    }

    @Transactional
    public TradeView close(User user, Long tradeId) {
        VirtualTrade trade = owned(user, tradeId);
        if (trade.getStatus() == VirtualTradeStatus.CLOSED) {
            return toTradeView(trade, exitQuote(trade));
        }
        Quote quote = liveQuote(trade.getStockAsset().getTickerSymbol());
        TechnicalOutlookService.OutlookView outlook = outlookService.getOutlook(
                user, trade.getStockAsset().getTickerSymbol(), interval(trade.getAnalysisInterval()));
        Instant now = Instant.now();
        trade.setStatus(VirtualTradeStatus.CLOSED);
        trade.setExitPrice(quote.price());
        trade.setExitAt(now);
        trade.setExitQuoteTimestamp(quote.timestamp());
        trade.setExitQuoteSource(quote.source());
        trade.setExitSnapshot(writeSnapshot(snapshot(outlook, now)));
        trade.setUpdatedAt(now);
        return toTradeView(tradeRepository.saveAndFlush(trade), quote);
    }

    @Transactional
    public void delete(User user, Long tradeId) {
        VirtualTrade trade = owned(user, tradeId);
        Instant deletedAt = Instant.now();
        trade.markDeleted(deletedAt);
        trade.setUpdatedAt(deletedAt);
        tradeRepository.saveAndFlush(trade);
    }

    @Transactional(readOnly = true)
    public List<TradeView> companyTrades(User user, String symbol) {
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        if (asset == null) return List.of();
        Quote quote = cachedQuote(asset.getTickerSymbol());
        return tradeRepository.findAllForUserAndStockAsset(user, asset).stream()
                .map(trade -> toTradeView(trade, trade.getStatus() == VirtualTradeStatus.CLOSED
                        ? exitQuote(trade) : quote))
                .toList();
    }

    @Transactional(readOnly = true)
    public ArchiveView archive(User user, String rawSort, String rawDirection) { return archive(user, rawSort, rawDirection, 0); }

    @Transactional(readOnly = true)
    public ArchiveView archive(User user, String rawSort, String rawDirection, int requestedPage) {
        String sort = normalizeSort(rawSort);
        String direction = "asc".equalsIgnoreCase(rawDirection) ? "asc" : "desc";
        if (archiveQuery != null) {
            var result = archiveQuery.page(user.getId(), sort, direction, requestedPage);
            var byId = result.ids().isEmpty() ? Map.<Long, VirtualTrade>of() : tradeRepository.findOwnedIds(result.ids(), user).stream()
                    .collect(java.util.stream.Collectors.toMap(VirtualTrade::getId, trade -> trade));
            Map<String, Quote> prices = new LinkedHashMap<>();
            var trades = result.ids().stream().map(byId::get).filter(java.util.Objects::nonNull)
                    .map(trade -> toTradeView(trade, trade.getStatus() == VirtualTradeStatus.CLOSED ? exitQuote(trade)
                            : prices.computeIfAbsent(trade.getStockAsset().getTickerSymbol(), this::cachedQuote))).toList();
            return new ArchiveView(trades, sort, direction, (int) result.count(), result.page(), result.pages());
        }
        Map<String, Quote> quotes = new LinkedHashMap<>();
        List<TradeView> trades = tradeRepository.findAllForUser(user).stream()
                .map(trade -> toTradeView(trade, trade.getStatus() == VirtualTradeStatus.CLOSED
                        ? exitQuote(trade)
                        : quotes.computeIfAbsent(trade.getStockAsset().getTickerSymbol(), this::cachedQuote)))
                .sorted(tradeComparator(sort, direction))
                .toList();
        return new ArchiveView(trades, sort, direction, trades.size());
    }

    public DetailView detail(User user, Long tradeId) {
        VirtualTrade trade = owned(user, tradeId);
        Quote currentQuote = trade.getStatus() == VirtualTradeStatus.CLOSED
                ? exitQuote(trade)
                : liveQuote(trade.getStockAsset().getTickerSymbol());
        TechnicalSnapshot entry = readSnapshot(trade.getEntrySnapshot());
        TechnicalSnapshot comparison;
        if (trade.getStatus() == VirtualTradeStatus.CLOSED && trade.getExitSnapshot() != null) {
            comparison = readSnapshot(trade.getExitSnapshot());
        } else {
            TechnicalOutlookService.OutlookView outlook = outlookService.getOutlook(
                    user, trade.getStockAsset().getTickerSymbol(), interval(trade.getAnalysisInterval()));
            comparison = snapshot(outlook, Instant.now());
        }
        TradeView summary = toTradeView(trade, currentQuote);
        return new DetailView(summary, entry, comparison, indicatorComparisons(entry, comparison),
                results(trade, currentQuote));
    }

    private List<IndicatorComparisonView> indicatorComparisons(TechnicalSnapshot entry,
                                                               TechnicalSnapshot comparison) {
        Map<String, SnapshotIndicator> currentByKey = new LinkedHashMap<>();
        comparison.indicators().forEach(indicator -> currentByKey.put(indicator.key(), indicator));
        return entry.indicators().stream().map(start -> {
            SnapshotIndicator current = currentByKey.get(start.key());
            return new IndicatorComparisonView(start.key(), start.label(), start.category(), start.unit(),
                    start.value(), start.classification(), current == null ? null : current.value(),
                    current == null ? "UNAVAILABLE" : current.classification(), start.rule(),
                    current == null ? "Current value unavailable" : transition(start.classification(), current.classification()));
        }).toList();
    }

    private ResultsView results(VirtualTrade trade, Quote currentQuote) {
        String apiInterval = interval(trade.getAnalysisInterval());
        List<Candle> laterCandles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(trade.getStockAsset().getTickerSymbol(), apiInterval)
                .stream()
                .filter(candle -> candle.getTimestamp() > trade.getEntryCandleTimestamp())
                .filter(candle -> trade.getExitAt() == null || candle.getTimestamp() <= trade.getExitAt().getEpochSecond())
                .toList();
        List<ResultPointView> points = new ArrayList<>();
        points.add(resultPoint(trade, 0, trade.getEntryQuoteTimestamp(), trade.getEntryPrice()));
        int candleNumber = 1;
        for (Candle candle : laterCandles) {
            points.add(resultPoint(trade, candleNumber++, candle.getTimestamp(),
                    BigDecimal.valueOf(candle.getClosePrice())));
        }
        long lastTimestamp = points.getLast().timestamp();
        if (currentQuote.timestamp() > lastTimestamp || points.size() == 1) {
            points.add(resultPoint(trade, candleNumber, Math.max(currentQuote.timestamp(), lastTimestamp + 1),
                    currentQuote.price()));
        }
        ResultPointView best = points.stream().max(Comparator.comparingDouble(ResultPointView::directionalReturnPercent))
                .orElse(points.getFirst());
        ResultPointView worst = points.stream().min(Comparator.comparingDouble(ResultPointView::directionalReturnPercent))
                .orElse(points.getFirst());
        return new ResultsView(points.size() > 1, points.size() - 1, List.copyOf(points), best, worst,
                trade.getSide() == VirtualTradeSide.BUY ? "Best virtual exit" : "Best virtual re-entry",
                trade.getSide() == VirtualTradeSide.BUY ? "Gain / loss" : "Avoided loss / missed upside");
    }

    private ResultPointView resultPoint(VirtualTrade trade, int candleNumber, long timestamp, BigDecimal price) {
        double entry = trade.getEntryPrice().doubleValue();
        double difference = price.doubleValue() - entry;
        if (trade.getSide() == VirtualTradeSide.SELL) difference = -difference;
        double percent = entry == 0 ? 0 : difference / entry * 100.0;
        return new ResultPointView(candleNumber, timestamp, periodLabel(timestamp), price.doubleValue(), percent, difference);
    }

    private TechnicalSnapshot snapshot(TechnicalOutlookService.OutlookView outlook, Instant capturedAt) {
        List<SnapshotIndicator> indicators = outlook.indicators().stream()
                .map(indicator -> new SnapshotIndicator(indicator.key(), indicator.label(), indicator.category(),
                        indicator.unit(), indicator.currentValue(), indicator.vote(), indicator.classification(),
                        indicator.explanation(), indicator.rule()))
                .toList();
        List<SnapshotCategory> categories = outlook.categories().stream()
                .map(category -> new SnapshotCategory(category.key(), category.label(), category.vote(),
                        category.classification(), category.averageInputVote(), category.inputs()))
                .toList();
        List<SnapshotSignal> signals = outlook.recentSignals().stream()
                .map(signal -> new SnapshotSignal(signal.id(), signal.family(), signal.label(), signal.direction(),
                        signal.intervalLabel(), signal.timestamp(), signal.score(), signal.lifecycle(), signal.detailUrl()))
                .toList();
        return new TechnicalSnapshot(SNAPSHOT_VERSION, capturedAt.getEpochSecond(), outlook.interval(), outlook.intervalLabel(),
                outlook.candleTimestamp(), outlook.freshness(), outlook.headlineScore(), outlook.rawScore(),
                indicators, categories, signals);
    }

    private TradeView toTradeView(VirtualTrade trade, Quote quote) {
        BigDecimal evaluationPrice = trade.getStatus() == VirtualTradeStatus.CLOSED
                ? trade.getExitPrice()
                : quote.price().signum() > 0 ? quote.price() : trade.getEntryPrice();
        double entry = trade.getEntryPrice().doubleValue();
        double rawDifference = evaluationPrice.doubleValue() - entry;
        double directionalDifference = trade.getSide() == VirtualTradeSide.SELL ? -rawDifference : rawDifference;
        double percent = entry == 0 ? 0 : directionalDifference / entry * 100.0;
        BigDecimal monetary = trade.getNotionalValue() == null ? null
                : trade.getNotionalValue().multiply(BigDecimal.valueOf(percent / 100.0), MONEY_CONTEXT);
        BigDecimal evaluatedPositionValue = trade.getQuantity() == null ? null
                : trade.getQuantity().multiply(evaluationPrice, MONEY_CONTEXT)
                        .setScale(8, RoundingMode.HALF_UP);
        String outcome = outcomeLabel(trade.getSide(), percent);
        return new TradeView(trade.getId(), trade.getStockAsset().getTickerSymbol(),
                trade.getStockAsset().getCompanyName(), trade.getSide().name(), sideLabel(trade.getSide()),
                trade.getStatus().name(), statusLabel(trade.getStatus()), trade.getAnalysisInterval().name(),
                intervalLabel(trade.getAnalysisInterval()), trade.getEntryPrice(), trade.getEntryAt(),
                trade.getEntryQuoteTimestamp(), trade.getEntryCandleTimestamp(), trade.getEntryQuoteSource(),
                trade.getCurrency(), trade.getQuantity(), trade.getNotionalValue(), evaluatedPositionValue,
                evaluationPrice,
                trade.getStatus() == VirtualTradeStatus.CLOSED ? trade.getExitAt() : Instant.now(),
                trade.getStatus() == VirtualTradeStatus.CLOSED ? trade.getExitQuoteSource() : quote.source(),
                percent, directionalDifference, monetary, outcome,
                Duration.between(trade.getEntryAt(), trade.getStatus() == VirtualTradeStatus.CLOSED
                        ? trade.getExitAt() : Instant.now()).toDays(),
                "/virtual-trades/" + trade.getId());
    }

    private VirtualTrade owned(User user, Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("A valid demo trade is required.");
        return tradeRepository.findOwnedById(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Demo trade was not found."));
    }

    private Quote liveQuote(String symbol) {
        Map<String, Object> values = livePricingService.getLatestPrice(symbol);
        Object rawPrice = values.get("price");
        if (!(rawPrice instanceof Number price) || !Double.isFinite(price.doubleValue()) || price.doubleValue() <= 0) {
            throw new IllegalStateException("A reliable current price is not available for this demo trade.");
        }
        long timestamp = values.get("timestamp") instanceof Number value
                ? value.longValue() : Instant.now().getEpochSecond();
        String source = String.valueOf(values.getOrDefault("source", "Current market quote"));
        return new Quote(BigDecimal.valueOf(price.doubleValue()).setScale(8, RoundingMode.HALF_UP), timestamp, source);
    }

    private Quote cachedQuote(String symbol) {
        List<Candle> candles = candleRepository.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1d");
        if (candles.isEmpty()) return new Quote(BigDecimal.ZERO, Instant.now().getEpochSecond(), "Price unavailable");
        Candle candle = candles.getFirst();
        return new Quote(BigDecimal.valueOf(candle.getClosePrice()).setScale(8, RoundingMode.HALF_UP),
                candle.getTimestamp(), "Latest completed daily candle");
    }

    private Quote exitQuote(VirtualTrade trade) {
        return new Quote(trade.getExitPrice(), trade.getExitQuoteTimestamp(), trade.getExitQuoteSource());
    }

    private String writeSnapshot(TechnicalSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException("The technical entry snapshot could not be stored.", exception);
        }
    }

    private TechnicalSnapshot readSnapshot(String payload) {
        try {
            return objectMapper.readValue(payload, TechnicalSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("The stored technical snapshot could not be read.", exception);
        }
    }

    private Comparator<TradeView> tradeComparator(String sort, String direction) {
        Comparator<TradeView> comparator = switch (sort) {
            case "ticker" -> Comparator.comparing(TradeView::symbol, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TradeView::entryAt);
            case "side" -> Comparator.comparing(TradeView::side).thenComparing(TradeView::entryAt);
            case "status" -> Comparator.comparing(TradeView::status).thenComparing(TradeView::entryAt);
            case "return" -> Comparator.comparingDouble(TradeView::resultPercent).thenComparing(TradeView::entryAt);
            default -> Comparator.comparing(TradeView::entryAt);
        };
        return "asc".equals(direction) ? comparator : comparator.reversed();
    }

    private String normalizeSort(String raw) {
        String value = raw == null ? "date" : raw.toLowerCase(Locale.ROOT);
        return List.of("date", "ticker", "side", "status", "return").contains(value) ? value : "date";
    }

    private String requireRequestId(String raw) {
        try {
            return UUID.fromString(raw).toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("A valid virtual-trade request identifier is required.");
        }
    }

    private VirtualTradeSide requireSide(String raw) {
        try {
            return VirtualTradeSide.valueOf(raw == null ? "" : raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Choose Virtual Buy or Virtual Sell.");
        }
    }

    private BigDecimal optionalPositive(BigDecimal value, String label) {
        if (value == null) return null;
        if (value.signum() <= 0 || value.compareTo(MAX_SIZE) > 0) {
            throw new IllegalArgumentException(label + " must be greater than zero and no more than " + MAX_SIZE.toPlainString() + ".");
        }
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    private static String transition(String entry, String current) {
        return entry.equals(current) ? "Still " + current.toLowerCase(Locale.ROOT)
                : entry + " → " + current;
    }

    private static String sideLabel(VirtualTradeSide side) {
        return side == VirtualTradeSide.BUY ? "Virtual Buy" : "Virtual Sell";
    }

    private static String statusLabel(VirtualTradeStatus status) {
        return status == VirtualTradeStatus.TRACKING ? "Tracking" : "Closed";
    }

    private static String outcomeLabel(VirtualTradeSide side, double percent) {
        if (Math.abs(percent) < 0.000001) return "No change";
        if (side == VirtualTradeSide.BUY) return percent > 0 ? "Gain" : "Loss";
        return percent > 0 ? "Avoided loss" : "Missed upside";
    }

    private static String interval(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY ? "1wk" : interval == TimeInterval.MONTHLY ? "1mo" : "1d";
    }

    private static String intervalLabel(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY ? "Weekly" : interval == TimeInterval.MONTHLY ? "Monthly" : "Daily";
    }

    private static String periodLabel(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'UTC'"));
    }

    private record Quote(BigDecimal price, long timestamp, String source) { }
    private record Interval(String apiValue, TimeInterval timeInterval) {
        private static Interval parse(String raw) {
            return switch (raw == null ? "1d" : raw.toLowerCase(Locale.ROOT)) {
                case "1d", "daily" -> new Interval("1d", TimeInterval.DAILY);
                case "1wk", "weekly" -> new Interval("1wk", TimeInterval.WEEKLY);
                case "1mo", "monthly" -> new Interval("1mo", TimeInterval.MONTHLY);
                default -> throw new IllegalArgumentException("Demo Trading supports daily, weekly, and monthly analysis.");
            };
        }
    }

    public record CreateCommand(String side, String interval, BigDecimal quantity,
                                BigDecimal notionalValue, String clientRequestId) { }

    public record TradeView(Long id, String symbol, String companyName, String side, String sideLabel,
                            String status, String statusLabel, String analysisInterval, String intervalLabel,
                            BigDecimal entryPrice, Instant entryAt, long entryQuoteTimestamp,
                            long entryCandleTimestamp, String entryQuoteSource, String currency,
                            BigDecimal quantity, BigDecimal notionalValue, BigDecimal evaluatedPositionValue,
                            BigDecimal evaluationPrice,
                            Instant evaluationAt, String evaluationSource, double resultPercent,
                            double directionalPriceDifference, BigDecimal monetaryResult, String outcomeLabel,
                            long trackingDays, String detailUrl) { }

    public record ArchiveView(List<TradeView> trades, String sort, String direction, int totalTrades, int page, int totalPages) {
        public ArchiveView(List<TradeView> trades, String sort, String direction, int totalTrades) {
            this(trades, sort, direction, totalTrades, 0, totalTrades == 0 ? 0 : 1);
        }
        public String groupKey(TradeView trade) {
            return switch (sort) {
                case "ticker" -> trade.symbol();
                case "side" -> trade.side();
                case "status" -> trade.status();
                case "return" -> trade.resultPercent() >= 0 ? "POSITIVE" : "NEGATIVE";
                default -> trade.entryAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
            };
        }
        public String groupLabel(TradeView trade) {
            return switch (sort) {
                case "ticker" -> trade.symbol() + " · " + trade.companyName();
                case "side" -> trade.sideLabel();
                case "status" -> trade.statusLabel();
                case "return" -> trade.resultPercent() >= 0 ? "Favorable outcomes" : "Adverse outcomes";
                default -> trade.entryAt().atZone(ZoneOffset.UTC).toLocalDate()
                        .format(DateTimeFormatter.ofPattern("dd MMMM uuuu"));
            };
        }
    }

    public record TechnicalSnapshot(String version, long capturedAt, String interval, String intervalLabel,
                                    Long candleTimestamp, String freshness,
                                    TechnicalOutlookService.ScoreView headlineScore,
                                    TechnicalOutlookService.ScoreView rawScore,
                                    List<SnapshotIndicator> indicators, List<SnapshotCategory> categories,
                                    List<SnapshotSignal> recentSignals) { }
    public record SnapshotIndicator(String key, String label, String category, String unit, Double value,
                                    int vote, String classification, String explanation, String rule) { }
    public record SnapshotCategory(String key, String label, int vote, String classification,
                                   double averageInputVote, List<String> inputs) { }
    public record SnapshotSignal(Long id, String family, String label, String direction,
                                 String intervalLabel, long timestamp, Integer score,
                                 String lifecycle, String detailUrl) { }
    public record IndicatorComparisonView(String key, String label, String category, String unit,
                                          Double entryValue, String entryClassification,
                                          Double comparisonValue, String comparisonClassification,
                                          String rule, String transition) { }
    public record ResultPointView(int candleNumber, long timestamp, String periodLabel, double close,
                                  double directionalReturnPercent, double directionalPriceDifference) { }
    public record ResultsView(boolean available, int availableForwardCandles, List<ResultPointView> points,
                              ResultPointView best, ResultPointView worst, String bestActionLabel,
                              String outcomeLabel) { }
    public record DetailView(TradeView trade, TechnicalSnapshot entrySnapshot,
                             TechnicalSnapshot comparisonSnapshot,
                             List<IndicatorComparisonView> indicatorComparisons,
                             ResultsView results) { }
}
