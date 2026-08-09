package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.CongressionalTrade;
import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.CongressionalTradeRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class ActivitySignalDetailService {
    private static final int MINIMUM_RESULT_CANDLES = 10;
    private static final int CHART_CANDLES_BEFORE = 180;
    private static final int CHART_CANDLES_AFTER = 120;
    private static final DateTimeFormatter PERIOD_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final CongressionalTradeDeliveryRepository congressionalDeliveryRepository;
    private final InsiderTradeDeliveryRepository insiderDeliveryRepository;
    private final CongressionalTradeRepository congressionalTradeRepository;
    private final InsiderTradeRepository insiderTradeRepository;
    private final CandleRepository candleRepository;
    private final CandleCompletionService candleCompletionService;
    private final TechnicalIndicatorEnrichmentService indicatorService;
    private final AlertEventRepository alertEventRepository;

    public ActivitySignalDetailService(
            CongressionalTradeDeliveryRepository congressionalDeliveryRepository,
            InsiderTradeDeliveryRepository insiderDeliveryRepository,
            CongressionalTradeRepository congressionalTradeRepository,
            InsiderTradeRepository insiderTradeRepository,
            CandleRepository candleRepository,
            CandleCompletionService candleCompletionService,
            TechnicalIndicatorEnrichmentService indicatorService,
            AlertEventRepository alertEventRepository) {
        this.congressionalDeliveryRepository = congressionalDeliveryRepository;
        this.insiderDeliveryRepository = insiderDeliveryRepository;
        this.congressionalTradeRepository = congressionalTradeRepository;
        this.insiderTradeRepository = insiderTradeRepository;
        this.candleRepository = candleRepository;
        this.candleCompletionService = candleCompletionService;
        this.indicatorService = indicatorService;
        this.alertEventRepository = alertEventRepository;
    }

    @Transactional
    public ActivitySignalDetailView getDetail(User user, String requestedSource, Long deliveryId) {
        String source = requestedSource == null ? "" : requestedSource.trim().toUpperCase(Locale.ROOT);
        return switch (source) {
            case "CONGRESSIONAL" -> congressionalDetail(user, deliveryId);
            case "INSIDER" -> insiderDetail(user, deliveryId);
            default -> throw new IllegalArgumentException("Unknown activity signal type.");
        };
    }

    @Transactional(readOnly = true)
    public ActivitySignalDetailView getTradeDetail(User user, String requestedSource, Long tradeId) {
        String source = requestedSource == null ? "" : requestedSource.trim().toUpperCase(Locale.ROOT);
        return switch (source) {
            case "CONGRESSIONAL" -> congressionalTradeDetail(user, tradeId);
            case "INSIDER" -> insiderTradeDetail(user, tradeId);
            default -> throw new IllegalArgumentException("Unknown activity signal type.");
        };
    }

    private ActivitySignalDetailView congressionalDetail(User user, Long deliveryId) {
        CongressionalTradeDelivery delivery = congressionalDeliveryRepository
                .findOwnedByIdAndUser(deliveryId, user)
                .orElseThrow(() -> new IllegalArgumentException("Activity signal not found."));
        delivery.markRead(Instant.now());
        return congressionalTradeDetail(user, delivery.getTrade(), delivery.getId(), delivery.getCreatedAt());
    }

    private ActivitySignalDetailView congressionalTradeDetail(User user, Long tradeId) {
        CongressionalTrade trade = congressionalTradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Activity signal not found."));
        return congressionalTradeDetail(user, trade, trade.getId(), trade.getFirstSeenAt());
    }

    private ActivitySignalDetailView congressionalTradeDetail(
            User user,
            CongressionalTrade trade,
            Long detailId,
            Instant detectedAt) {
        StockAsset asset = trade.getStockAsset();
        return buildDetail(
                user,
                detailId,
                "CONGRESSIONAL",
                "Congressional activity",
                asset,
                trade.getMemberName(),
                trade.getChamber(),
                trade.getTransactionType().name(),
                trade.getTransactionType().getLabel(),
                trade.getTransactionDate(),
                trade.getDisclosureDate(),
                null,
                null,
                null,
                trade.getAmountRange(),
                trade.getSourceUrl(),
                detectedAt,
                "Congressional disclosures report a value range, not exact shares or execution price. "
                        + "Chart returns therefore use the completed daily close as a proxy entry price.");
    }

    private ActivitySignalDetailView insiderDetail(User user, Long deliveryId) {
        InsiderTradeDelivery delivery = insiderDeliveryRepository
                .findOwnedByIdAndUser(deliveryId, user)
                .orElseThrow(() -> new IllegalArgumentException("Activity signal not found."));
        delivery.markRead(Instant.now());
        return insiderTradeDetail(user, delivery.getTrade(), delivery.getId(), delivery.getCreatedAt());
    }

    private ActivitySignalDetailView insiderTradeDetail(User user, Long tradeId) {
        InsiderTrade trade = insiderTradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Activity signal not found."));
        return insiderTradeDetail(user, trade, trade.getId(), trade.getFirstSeenAt());
    }

    private ActivitySignalDetailView insiderTradeDetail(
            User user,
            InsiderTrade trade,
            Long detailId,
            Instant detectedAt) {
        StockAsset asset = trade.getStockAsset();
        BigDecimal value = trade.getShares() != null && trade.getTransactionPrice() != null
                ? trade.getShares().multiply(trade.getTransactionPrice())
                : null;
        return buildDetail(
                user,
                detailId,
                "INSIDER",
                "Corporate insider activity",
                asset,
                trade.getInsiderName(),
                trade.getOwnerRole(),
                trade.getTransactionType().name(),
                trade.getTransactionType().getLabel(),
                trade.getTransactionDate(),
                trade.getFilingDate(),
                trade.getShares(),
                trade.getTransactionPrice(),
                value,
                null,
                trade.getSourceUrl(),
                detectedAt,
                trade.getTransactionPrice() == null
                        ? "The filing does not contain a usable execution price. Chart returns use the completed daily close as a proxy."
                        : "Results use the execution price reported in the filing as the entry price.");
    }

    private ActivitySignalDetailView buildDetail(
            User user,
            Long id,
            String source,
            String sourceLabel,
            StockAsset asset,
            String actorName,
            String actorRole,
            String transactionType,
            String transactionTypeLabel,
            LocalDate transactionDate,
            LocalDate filingDate,
            BigDecimal shares,
            BigDecimal transactionPrice,
            BigDecimal transactionValue,
            String amountRange,
            String filingUrl,
            Instant detectedAt,
            String disclosureNotice) {
        List<Candle> completedCandles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(asset.getTickerSymbol(), "1d")
                .stream()
                .filter(this::hasPrices)
                .filter(candle -> candleCompletionService.isComplete(candle.getTimestamp(), TimeInterval.DAILY))
                .toList();
        int signalIndex = findSignalIndex(completedCandles, transactionDate);
        Candle signalCandle = signalIndex >= 0 ? completedCandles.get(signalIndex) : null;
        Double entryPrice = null;
        if (validPrice(transactionPrice)) {
            entryPrice = transactionPrice.doubleValue();
        } else if (signalCandle != null) {
            entryPrice = signalCandle.getClosePrice();
        }
        boolean proxyPrice = !validPrice(transactionPrice);

        ActivityChartView chart = chart(completedCandles, signalIndex, entryPrice, transactionTypeLabel);
        ActivityResultsView results = results(
                completedCandles,
                signalIndex,
                entryPrice,
                "PURCHASE".equals(transactionType));
        BigDecimal generalReturn = generalReturn(
                completedCandles,
                signalIndex,
                entryPrice,
                "PURCHASE".equals(transactionType));
        TechnicalSnapshotView technical = technicalSnapshot(completedCandles, signalIndex, asset.getCurrency());
        RelatedSignalsView related = relatedSignals(userEvents(user, asset, transactionDate));

        return new ActivitySignalDetailView(
                id,
                source,
                sourceLabel,
                asset.getTickerSymbol(),
                asset.getCompanyName(),
                asset.getCurrency() == null || asset.getCurrency().isBlank() ? "USD" : asset.getCurrency(),
                actorName,
                actorRole,
                transactionType,
                transactionTypeLabel,
                transactionDate,
                filingDate,
                shares,
                transactionPrice,
                transactionValue,
                amountRange,
                filingUrl,
                detectedAt,
                entryPrice,
                proxyPrice,
                generalReturn,
                disclosureNotice,
                chart,
                technical,
                related,
                results);
    }

    private List<AlertEvent> userEvents(User user, StockAsset asset, LocalDate transactionDate) {
        return alertEventRepository.findAllByAlertRule_User(
                        user)
                .stream()
                .filter(event -> event.getAlertRule().getStockAsset().getId().equals(asset.getId()))
                .filter(event -> isNear(event, transactionDate))
                .sorted(Comparator.comparing(AlertEvent::getSignalCandleTimestamp).reversed())
                .toList();
    }

    private RelatedSignalsView relatedSignals(List<AlertEvent> events) {
        List<RelatedSignalView> candlesticks = new ArrayList<>();
        List<RelatedSignalView> elliott = new ArrayList<>();
        for (AlertEvent event : events) {
            RelatedSignalView view = new RelatedSignalView(
                    event.getId(),
                    humanize(event.getPattern().name()),
                    humanize(event.getTradeSignal().name()),
                    humanize(event.getAlertRule().getInterval().name()),
                    epochDate(event.getSignalCandleTimestamp()),
                    "/alerts/signals/" + event.getId());
            if (event.getAlertRule().getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE
                    || event.isElliottSignal()) {
                elliott.add(view);
            } else {
                candlesticks.add(view);
            }
        }
        return new RelatedSignalsView(candlesticks, elliott);
    }

    private boolean isNear(AlertEvent event, LocalDate transactionDate) {
        if (event.getSignalCandleTimestamp() == null) {
            return false;
        }
        LocalDate signalDate = epochDate(event.getSignalCandleTimestamp());
        TimeInterval interval = event.getAlertRule().getInterval();
        long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(transactionDate, signalDate));
        return switch (interval) {
            case DAILY -> days <= 5;
            case WEEKLY -> days <= 10;
            case MONTHLY -> days <= 35;
            default -> false;
        };
    }

    private ActivityChartView chart(List<Candle> candles, int signalIndex, Double entryPrice, String action) {
        if (signalIndex < 0 || entryPrice == null) {
            return ActivityChartView.unavailable("No completed daily candle is available around the transaction date.");
        }
        int from = Math.max(0, signalIndex - CHART_CANDLES_BEFORE);
        int to = Math.min(candles.size(), signalIndex + CHART_CANDLES_AFTER + 1);
        List<ActivityChartCandleView> points = candles.subList(from, to).stream()
                .map(candle -> new ActivityChartCandleView(
                        candle.getTimestamp(), candle.getOpenPrice(), candle.getHighPrice(),
                        candle.getLowPrice(), candle.getClosePrice()))
                .toList();
        return new ActivityChartView(
                true, null, points, candles.get(signalIndex).getTimestamp(), entryPrice, action);
    }

    private TechnicalSnapshotView technicalSnapshot(
            List<Candle> candles,
            int signalIndex,
            String currency) {
        if (signalIndex < 0) {
            return TechnicalSnapshotView.unavailable(
                    "Technical indicators are unavailable because no matching completed daily candle was found.");
        }
        List<EnrichedCandle> enriched = indicatorService.enrich(candles, candles.size(), TimeInterval.DAILY);
        long timestamp = candles.get(signalIndex).getTimestamp();
        EnrichedCandle snapshot = enriched.stream()
                .filter(candle -> candle.timestamp().equals(timestamp))
                .findFirst()
                .orElse(null);
        if (snapshot == null) {
            return TechnicalSnapshotView.unavailable("Technical indicators could not be reconstructed for this date.");
        }
        List<IndicatorView> indicators = new ArrayList<>();
        addIndicator(indicators, "RSI", snapshot.rsi(), "", rsiContext(snapshot.rsi()));
        addIndicator(indicators, "Fast EMA", snapshot.fastEma(), currency, priceContext(snapshot.close(), snapshot.fastEma()));
        addIndicator(indicators, "Slow EMA", snapshot.slowEma(), currency, priceContext(snapshot.close(), snapshot.slowEma()));
        addIndicator(indicators, "Long SMA", snapshot.longSma(), currency, priceContext(snapshot.close(), snapshot.longSma()));
        addIndicator(indicators, "MACD", snapshot.macdLine(), "", macdContext(snapshot.macdHistogram()));
        addIndicator(indicators, "MACD signal", snapshot.macdSignal(), "", macdContext(snapshot.macdHistogram()));
        addIndicator(indicators, "MACD histogram", snapshot.macdHistogram(), "", macdContext(snapshot.macdHistogram()));
        addIndicator(indicators, "CCI", snapshot.cci(), "", cciContext(snapshot.cci()));
        addIndicator(indicators, "Bollinger middle", snapshot.bollingerMiddle(), currency,
                priceContext(snapshot.close(), snapshot.bollingerMiddle()));
        addIndicator(indicators, "Bollinger lower", snapshot.lowerBollinger(), currency, "Daily lower band");
        addIndicator(indicators, "Bollinger upper", snapshot.upperBollinger(), currency, "Daily upper band");
        addIndicator(indicators, "ATR", snapshot.atr(), currency, "Daily average true range");
        addIndicator(indicators, "Rolling VWAP", snapshot.rollingVwap(), currency,
                priceContext(snapshot.close(), snapshot.rollingVwap()));
        if (Double.isFinite(snapshot.averageVolume()) && snapshot.averageVolume() != 0) {
            double ratio = snapshot.volume() / snapshot.averageVolume();
            indicators.add(new IndicatorView(
                    "Volume vs average",
                    String.format(Locale.ROOT, "%.2fx", ratio),
                    ratio >= 1.25 ? "Above normal volume" : ratio <= .75 ? "Below normal volume" : "Near normal volume"));
        }
        return new TechnicalSnapshotView(
                true,
                null,
                epochDate(timestamp),
                indicators);
    }

    private void addIndicator(List<IndicatorView> indicators, String name, double value, String currency, String context) {
        if (!Double.isFinite(value)) {
            return;
        }
        String formatted = currency == null || currency.isBlank()
                ? String.format(Locale.ROOT, "%.2f", value)
                : currency + " " + String.format(Locale.ROOT, "%,.2f", value);
        indicators.add(new IndicatorView(name, formatted, context));
    }

    private ActivityResultsView results(
            List<Candle> candles,
            int signalIndex,
            Double entryPrice,
            boolean purchase) {
        int forward = signalIndex < 0 ? 0 : candles.size() - signalIndex - 1;
        if (signalIndex < 0 || entryPrice == null || entryPrice <= 0 || forward < MINIMUM_RESULT_CANDLES) {
            return ActivityResultsView.unavailable(
                    "At least " + MINIMUM_RESULT_CANDLES
                            + " completed daily candles after the trade are required.",
                    forward);
        }
        List<ActivityResultPointView> points = new ArrayList<>();
        Candle signalCandle = candles.get(signalIndex);
        points.add(new ActivityResultPointView(0, signalCandle.getTimestamp(),
                epochDate(signalCandle.getTimestamp()).format(PERIOD_FORMAT), entryPrice, 0, 0));
        for (int index = signalIndex + 1; index < candles.size(); index++) {
            Candle candle = candles.get(index);
            double priceDifference = candle.getClosePrice() - entryPrice;
            if (!purchase) {
                priceDifference = -priceDifference;
            }
            double returnPercent = priceDifference / entryPrice * 100.0;
            int candleNumber = index - signalIndex;
            points.add(new ActivityResultPointView(
                    candleNumber,
                    candle.getTimestamp(),
                    epochDate(candle.getTimestamp()).format(PERIOD_FORMAT),
                    candle.getClosePrice(),
                    returnPercent,
                    priceDifference));
        }
        ActivityResultPointView latest = points.get(points.size() - 1);
        return new ActivityResultsView(
                true,
                null,
                MINIMUM_RESULT_CANDLES,
                forward,
                entryPrice,
                signalCandle.getTimestamp(),
                purchase ? "Purchase-direction gain / loss" : "Sale-direction gain / loss",
                purchase ? "Best sell close" : "Best buy-back close",
                points);
    }

    private BigDecimal generalReturn(
            List<Candle> candles,
            int signalIndex,
            Double entryPrice,
            boolean purchase) {
        if (signalIndex < 0 || entryPrice == null || entryPrice <= 0 || signalIndex >= candles.size() - 1) {
            return null;
        }
        double difference = candles.get(candles.size() - 1).getClosePrice() - entryPrice;
        double value = (purchase ? difference : -difference) / entryPrice * 100.0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private int findSignalIndex(List<Candle> candles, LocalDate transactionDate) {
        for (int index = 0; index < candles.size(); index++) {
            if (!epochDate(candles.get(index).getTimestamp()).isBefore(transactionDate)) {
                return index;
            }
        }
        return -1;
    }

    private boolean hasPrices(Candle candle) {
        return candle.getTimestamp() != null
                && finitePositive(candle.getOpenPrice())
                && finitePositive(candle.getHighPrice())
                && finitePositive(candle.getLowPrice())
                && finitePositive(candle.getClosePrice());
    }

    private boolean finitePositive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private boolean validPrice(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private LocalDate epochDate(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private String humanize(String value) {
        String[] words = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (!label.isEmpty()) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private String rsiContext(double rsi) {
        if (!Double.isFinite(rsi)) return "Unavailable";
        if (rsi >= 70) return "Overbought zone";
        if (rsi <= 30) return "Oversold zone";
        return "Neutral momentum zone";
    }

    private String macdContext(double histogram) {
        if (!Double.isFinite(histogram)) return "Momentum comparison unavailable";
        return histogram >= 0 ? "Positive MACD momentum" : "Negative MACD momentum";
    }

    private String cciContext(double cci) {
        if (!Double.isFinite(cci)) return "Unavailable";
        if (cci >= 100) return "Strong positive deviation";
        if (cci <= -100) return "Strong negative deviation";
        return "Inside the typical range";
    }

    private String priceContext(double price, double reference) {
        if (!Double.isFinite(reference)) return "Comparison unavailable";
        return price >= reference ? "Price was above this level" : "Price was below this level";
    }

    public record ActivitySignalDetailView(
            Long id,
            String source,
            String sourceLabel,
            String symbol,
            String companyName,
            String currency,
            String actorName,
            String actorRole,
            String transactionType,
            String transactionTypeLabel,
            LocalDate transactionDate,
            LocalDate filingDate,
            BigDecimal shares,
            BigDecimal transactionPrice,
            BigDecimal transactionValue,
            String amountRange,
            String filingUrl,
            Instant detectedAt,
            Double chartEntryPrice,
            boolean proxyEntryPrice,
            BigDecimal generalReturnPercent,
            String disclosureNotice,
            ActivityChartView chart,
            TechnicalSnapshotView technical,
            RelatedSignalsView relatedSignals,
            ActivityResultsView results) {
    }

    public record ActivityChartView(
            boolean available,
            String unavailableReason,
            List<ActivityChartCandleView> candles,
            Long signalTimestamp,
            Double entryPrice,
            String actionLabel) {
        private static ActivityChartView unavailable(String reason) {
            return new ActivityChartView(false, reason, List.of(), null, null, null);
        }
        public ActivityChartView { candles = List.copyOf(candles); }
    }

    public record ActivityChartCandleView(long timestamp, double open, double high, double low, double close) {
    }

    public record TechnicalSnapshotView(
            boolean available,
            String unavailableReason,
            LocalDate snapshotDate,
            List<IndicatorView> indicators) {
        private static TechnicalSnapshotView unavailable(String reason) {
            return new TechnicalSnapshotView(false, reason, null, List.of());
        }
        public TechnicalSnapshotView { indicators = List.copyOf(indicators); }
    }

    public record IndicatorView(String name, String value, String context) {
    }

    public record RelatedSignalsView(
            List<RelatedSignalView> candlestickSignals,
            List<RelatedSignalView> elliottSignals) {
        public RelatedSignalsView {
            candlestickSignals = List.copyOf(candlestickSignals);
            elliottSignals = List.copyOf(elliottSignals);
        }
    }

    public record RelatedSignalView(
            Long id,
            String patternLabel,
            String tradeSignalLabel,
            String intervalLabel,
            LocalDate signalDate,
            String detailUrl) {
    }

    public record ActivityResultsView(
            boolean available,
            String unavailableReason,
            int minimumForwardCandles,
            int availableForwardCandles,
            Double entryPrice,
            Long signalTimestamp,
            String outcomeLabel,
            String bestActionLabel,
            List<ActivityResultPointView> points) {
        private static ActivityResultsView unavailable(String reason, int availableForwardCandles) {
            return new ActivityResultsView(false, reason, MINIMUM_RESULT_CANDLES,
                    Math.max(0, availableForwardCandles), null, null,
                    "Outcome unavailable", "Best move unavailable", List.of());
        }
        public ActivityResultsView { points = List.copyOf(points); }
    }

    public record ActivityResultPointView(
            int candleNumber,
            long timestamp,
            String periodLabel,
            double close,
            double directionalReturnPercent,
            double directionalPriceDifference) {
    }
}
