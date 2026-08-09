package org.example.stockwatch247.service;

import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.springframework.stereotype.Service;

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
import java.util.Objects;
import java.util.function.ToDoubleFunction;

@Service
public class TechnicalOutlookService {
    private static final int CHART_CANDLES = 260;
    private static final int ANALYSIS_CANDLES = 320;
    private static final double NEUTRAL_LIMIT = 0.10;
    private static final double MODERATE_LIMIT = 0.30;
    private static final double STRONG_LIMIT = 0.60;

    private final MarketDataService marketDataService;
    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final AlertEventRepository alertEventRepository;
    private final CongressionalTradeDeliveryRepository congressionalDeliveryRepository;
    private final InsiderTradeDeliveryRepository insiderDeliveryRepository;

    public TechnicalOutlookService(MarketDataService marketDataService,
                                   CandleRepository candleRepository,
                                   StockAssetRepository stockAssetRepository,
                                   TechnicalIndicatorEnrichmentService enrichmentService,
                                   AlertEventRepository alertEventRepository,
                                   CongressionalTradeDeliveryRepository congressionalDeliveryRepository,
                                   InsiderTradeDeliveryRepository insiderDeliveryRepository) {
        this.marketDataService = marketDataService;
        this.candleRepository = candleRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.enrichmentService = enrichmentService;
        this.alertEventRepository = alertEventRepository;
        this.congressionalDeliveryRepository = congressionalDeliveryRepository;
        this.insiderDeliveryRepository = insiderDeliveryRepository;
    }

    public OutlookView getOutlook(User user, String symbol, String rawInterval) {
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        refresh(normalizedSymbol, interval.apiValue());

        List<Candle> rawCandles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(normalizedSymbol, interval.apiValue());
        if (rawCandles.isEmpty()) {
            return OutlookView.unavailable(normalizedSymbol, interval);
        }

        List<EnrichedCandle> candles = enrichmentService.enrich(
                rawCandles,
                Math.min(ANALYSIS_CANDLES, rawCandles.size()),
                interval.timeInterval());
        if (candles.isEmpty()) {
            return OutlookView.unavailable(normalizedSymbol, interval);
        }

        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(normalizedSymbol).orElse(null);
        MarketComparisonView market = marketComparison(normalizedSymbol, asset);
        List<RecentSignalView> recentSignals = recentSignals(user, normalizedSymbol);
        List<IndicatorView> indicators = indicatorViews(candles);

        List<VoteInput> rawVotes = new ArrayList<>();
        indicators.stream()
                .filter(IndicatorView::scored)
                .forEach(indicator -> rawVotes.add(new VoteInput(
                        indicator.label(), indicator.category(), indicator.vote())));
        if (market.available()) {
            rawVotes.add(new VoteInput("Market-relative strength", "MARKET_RELATIVE", market.vote()));
        }
        signalVote(recentSignals, AlertPatternFamily.CANDLESTICK)
                .ifPresent(vote -> rawVotes.add(new VoteInput("Recent candlestick signals", "CANDLESTICK", vote)));
        signalVote(recentSignals, AlertPatternFamily.ELLIOTT_WAVE)
                .ifPresent(vote -> rawVotes.add(new VoteInput("Recent Elliott signals", "ELLIOTT", vote)));

        ScoreView rawScore = score(rawVotes.stream().map(VoteInput::vote).toList());
        List<CategoryView> categories = categoryViews(rawVotes);
        ScoreView categoryScore = score(categories.stream().map(CategoryView::vote).toList());
        List<ChartCandleView> chart = candles.stream()
                .skip(Math.max(0, candles.size() - CHART_CANDLES))
                .map(TechnicalOutlookService::chartCandle)
                .toList();
        List<ActivityMarkerView> activity = activityMarkers(user, normalizedSymbol, chart);
        EnrichedCandle latest = candles.getLast();

        return new OutlookView(
                normalizedSymbol,
                asset == null ? normalizedSymbol : asset.getCompanyName(),
                interval.apiValue(),
                interval.label(),
                true,
                latest.timestamp(),
                freshness(latest.timestamp(), interval),
                categoryScore,
                rawScore,
                categories,
                indicators,
                chart,
                recentSignals,
                activity,
                market,
                new MethodologyView(
                        "+1 buy, 0 neutral, -1 sell; unavailable inputs are excluded.",
                        "Each category receives one equal vote after its available inputs are averaged.",
                        "Neutral: |score| < 10%; slight: 10-30%; moderate: 30-60%; strong: at least 60%.",
                        "These are symmetric descriptive rules, not probabilities or investment advice."));
    }

    private void refresh(String symbol, String interval) {
        try {
            marketDataService.syncCandles(symbol, interval, null);
        } catch (RuntimeException exception) {
            // A previously cached completed-candle set is still useful and is
            // explicitly identified as stale by the response freshness label.
        }
    }

    private List<IndicatorView> indicatorViews(List<EnrichedCandle> candles) {
        List<IndicatorView> views = new ArrayList<>();
        views.add(indicator(candles, "rsi", "RSI", "MOMENTUM", "index points", false,
                EnrichedCandle::rsi,
                (candle, value) -> value <= 30 ? 1 : value >= 70 ? -1 : 0,
                value -> value <= 30
                        ? "RSI is in the traditionally oversold region."
                        : value >= 70 ? "RSI is in the traditionally overbought region."
                        : "RSI is between the oversold and overbought thresholds.",
                "Buy at RSI ≤ 30; sell at RSI ≥ 70; otherwise neutral.",
                List.of(30.0, 50.0, 70.0)));
        views.add(indicator(candles, "ema", "Fast / slow EMA", "TREND", "price", true,
                candle -> percentDifference(candle.fastEma(), candle.slowEma()),
                (candle, value) -> value > 0.25 ? 1 : value < -0.25 ? -1 : 0,
                value -> value > 0.25 ? "The fast EMA is clearly above the slow EMA."
                        : value < -0.25 ? "The fast EMA is clearly below the slow EMA."
                        : "The two EMAs are within the neutral tolerance band.",
                "Buy when fast EMA is >0.25% above slow EMA; sell below -0.25%; otherwise neutral.",
                List.of(0.0)));
        views.add(indicator(candles, "sma", "Price vs long SMA", "TREND", "%", true,
                candle -> percentDifference(candle.close(), candle.longSma()),
                (candle, value) -> value > 0.5 ? 1 : value < -0.5 ? -1 : 0,
                value -> value > 0.5 ? "Price is trading above its long-term average."
                        : value < -0.5 ? "Price is trading below its long-term average."
                        : "Price is close to its long-term average.",
                "Buy above +0.5% from the long SMA; sell below -0.5%; otherwise neutral.",
                List.of(0.0)));
        views.add(indicator(candles, "macd", "MACD histogram", "TREND", "% of price", false,
                candle -> candle.close() == 0 ? Double.NaN : candle.macdHistogram() / candle.close() * 100.0,
                (candle, value) -> value > 0.05 ? 1 : value < -0.05 ? -1 : 0,
                value -> value > 0.05 ? "MACD momentum is positive."
                        : value < -0.05 ? "MACD momentum is negative."
                        : "MACD is close to its signal line.",
                "Buy above +0.05% of price; sell below -0.05%; otherwise neutral.",
                List.of(0.0)));
        views.add(indicator(candles, "cci", "CCI", "MOMENTUM", "index points", false,
                EnrichedCandle::cci,
                (candle, value) -> value <= -100 ? 1 : value >= 100 ? -1 : 0,
                value -> value <= -100 ? "CCI indicates an unusually low price location."
                        : value >= 100 ? "CCI indicates an unusually high price location."
                        : "CCI remains inside its central range.",
                "Buy at CCI ≤ -100; sell at CCI ≥ 100; otherwise neutral.",
                List.of(-100.0, 0.0, 100.0)));
        views.add(indicator(candles, "bollinger", "Bollinger Bands", "VOLATILITY", "% band position", true,
                candle -> bandPosition(candle.close(), candle.lowerBollinger(), candle.upperBollinger()),
                (candle, value) -> value <= 0 ? 1 : value >= 100 ? -1 : 0,
                value -> value <= 0 ? "Price closed at or below the lower band."
                        : value >= 100 ? "Price closed at or above the upper band."
                        : "Price remains inside the Bollinger envelope.",
                "Buy at or below the lower band; sell at or above the upper band; otherwise neutral.",
                List.of(0.0, 50.0, 100.0)));
        views.add(indicator(candles, "atr", "ATR", "VOLATILITY", "% of price", false,
                candle -> candle.close() == 0 ? Double.NaN : candle.atr() / candle.close() * 100.0,
                (candle, value) -> 0,
                value -> "ATR measures movement size, not direction, so it is shown as neutral context.",
                "ATR is always neutral in the directional vote; it describes volatility only.",
                List.of()));
        views.add(indicator(candles, "vwap", "Rolling VWAP", "VOLUME", "%", true,
                candle -> percentDifference(candle.close(), candle.rollingVwap()),
                (candle, value) -> value > 0.5 ? 1 : value < -0.5 ? -1 : 0,
                value -> value > 0.5 ? "Price is holding above volume-weighted value."
                        : value < -0.5 ? "Price is below volume-weighted value."
                        : "Price is close to rolling VWAP.",
                "Buy above +0.5% from VWAP; sell below -0.5%; otherwise neutral.",
                List.of(0.0)));
        views.add(indicator(candles, "relativeVolume", "Relative volume", "VOLUME", "× average", false,
                candle -> candle.averageVolume() == 0 ? Double.NaN
                        : candle.volume() / candle.averageVolume()
                        * (candle.close() > candle.open() ? 1 : candle.close() < candle.open() ? -1 : 0),
                (candle, value) -> value >= 1.5 ? 1 : value <= -1.5 ? -1 : 0,
                value -> Math.abs(value) >= 1.5 ? "Volume is elevated and confirms the latest candle direction."
                        : "Volume is not elevated enough to cast a directional vote.",
                "At ≥1.5× average volume, a green candle buys and a red candle sells; otherwise neutral. Negative values represent red candles.",
                List.of(-1.5, 0.0, 1.5)));
        views.add(indicator(candles, "volumeProfile", "Volume-profile location", "PRICE_LOCATION", "% value-area position", true,
                candle -> bandPosition(candle.close(), candle.volumeProfileValueAreaLow(), candle.volumeProfileValueAreaHigh()),
                (candle, value) -> value < 0 ? 1 : value > 100 ? -1 : 0,
                value -> value < 0 ? "Price is below the estimated value area."
                        : value > 100 ? "Price is above the estimated value area."
                        : "Price is inside the estimated value area.",
                "Buy below estimated VAL; sell above estimated VAH; otherwise neutral.",
                List.of(0.0, 50.0, 100.0)));
        views.add(supportResistance(candles));
        return List.copyOf(views);
    }

    private IndicatorView supportResistance(List<EnrichedCandle> candles) {
        List<ValueAtCandle> values = new ArrayList<>();
        for (int index = 0; index < candles.size(); index++) {
            EnrichedCandle candle = candles.get(index);
            if (index < 19 || !Double.isFinite(candle.atr())) {
                values.add(new ValueAtCandle(candle, Double.NaN));
                continue;
            }
            double support = candles.subList(index - 19, index + 1).stream()
                    .mapToDouble(EnrichedCandle::low).min().orElse(Double.NaN);
            double resistance = candles.subList(index - 19, index + 1).stream()
                    .mapToDouble(EnrichedCandle::high).max().orElse(Double.NaN);
            double supportDistance = (candle.close() - support) / candle.atr();
            double resistanceDistance = (resistance - candle.close()) / candle.atr();
            double value = supportDistance <= resistanceDistance ? supportDistance : -resistanceDistance;
            values.add(new ValueAtCandle(candle, value));
        }
        return indicatorFromValues("supportResistance", "Support / resistance", "PRICE_LOCATION",
                "ATR distance", true, values,
                value -> value >= 0 && value <= 1 ? 1 : value < 0 && value >= -1 ? -1 : 0,
                value -> value >= 0 && value <= 1 ? "Price is within one ATR of 20-bar support."
                        : value < 0 && value >= -1 ? "Price is within one ATR of 20-bar resistance."
                        : "Price is not close enough to either 20-bar boundary.",
                "Buy within 1 ATR of support; sell within 1 ATR of resistance; otherwise neutral.",
                List.of(-1.0, 0.0, 1.0));
    }

    private IndicatorView indicator(List<EnrichedCandle> candles,
                                    String key,
                                    String label,
                                    String category,
                                    String unit,
                                    boolean overlay,
                                    ToDoubleFunction<EnrichedCandle> extractor,
                                    CandleVoteRule rule,
                                    Explanation explanation,
                                    String ruleText,
                                    List<Double> references) {
        List<ValueAtCandle> values = candles.stream()
                .map(candle -> new ValueAtCandle(candle, extractor.applyAsDouble(candle)))
                .toList();
        List<ValueAtCandle> available = values.stream().filter(item -> Double.isFinite(item.value())).toList();
        if (available.isEmpty()) {
            return new IndicatorView(key, label, category, unit, null, 0, "UNAVAILABLE", false,
                    "Not enough completed candles are available for this indicator.", ruleText,
                    null, null, overlay, List.of(), references);
        }
        ValueAtCandle latest = available.getLast();
        int latestVote = rule.vote(latest.candle(), latest.value());
        int changedIndex = available.size() - 1;
        while (changedIndex > 0) {
            ValueAtCandle previous = available.get(changedIndex - 1);
            if (rule.vote(previous.candle(), previous.value()) != latestVote) {
                break;
            }
            changedIndex--;
        }
        ValueAtCandle changed = available.get(changedIndex);
        List<IndicatorPointView> series = available.stream()
                .map(item -> new IndicatorPointView(item.candle().timestamp(), finite(item.value()),
                        rule.vote(item.candle(), item.value())))
                .toList();
        return new IndicatorView(key, label, category, unit, finite(latest.value()), latestVote,
                voteLabel(latestVote), true, explanation.text(latest.value()), ruleText,
                changed.candle().timestamp(), available.size() - changedIndex - 1,
                overlay, series, references);
    }

    private IndicatorView indicatorFromValues(String key,
                                              String label,
                                              String category,
                                              String unit,
                                              boolean overlay,
                                              List<ValueAtCandle> values,
                                              ValueVoteRule rule,
                                              Explanation explanation,
                                              String ruleText,
                                              List<Double> references) {
        List<ValueAtCandle> available = values.stream().filter(item -> Double.isFinite(item.value())).toList();
        if (available.isEmpty()) {
            return new IndicatorView(key, label, category, unit, null, 0, "UNAVAILABLE", false,
                    "Not enough completed candles are available for this indicator.", ruleText,
                    null, null, overlay, List.of(), references);
        }
        ValueAtCandle latest = available.getLast();
        int latestVote = rule.vote(latest.value());
        int changedIndex = available.size() - 1;
        while (changedIndex > 0 && rule.vote(available.get(changedIndex - 1).value()) == latestVote) {
            changedIndex--;
        }
        ValueAtCandle changed = available.get(changedIndex);
        List<IndicatorPointView> series = available.stream()
                .map(item -> new IndicatorPointView(item.candle().timestamp(), finite(item.value()),
                        rule.vote(item.value())))
                .toList();
        return new IndicatorView(key, label, category, unit, finite(latest.value()), latestVote,
                voteLabel(latestVote), true, explanation.text(latest.value()), ruleText,
                changed.candle().timestamp(), available.size() - changedIndex - 1,
                overlay, series, references);
    }

    private List<CategoryView> categoryViews(List<VoteInput> votes) {
        Map<String, List<VoteInput>> grouped = new LinkedHashMap<>();
        for (String category : List.of("TREND", "MOMENTUM", "VOLATILITY", "VOLUME",
                "PRICE_LOCATION", "MARKET_RELATIVE", "CANDLESTICK", "ELLIOTT")) {
            grouped.put(category, new ArrayList<>());
        }
        votes.forEach(vote -> grouped.computeIfAbsent(vote.category(), ignored -> new ArrayList<>()).add(vote));
        return grouped.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> {
                    double average = entry.getValue().stream().mapToInt(VoteInput::vote).average().orElse(0);
                    int vote = average > NEUTRAL_LIMIT ? 1 : average < -NEUTRAL_LIMIT ? -1 : 0;
                    return new CategoryView(entry.getKey(), categoryLabel(entry.getKey()), vote,
                            voteLabel(vote), average,
                            entry.getValue().stream().map(VoteInput::label).toList());
                }).toList();
    }

    private java.util.Optional<Integer> signalVote(List<RecentSignalView> signals,
                                                   AlertPatternFamily family) {
        return signals.stream()
                .filter(signal -> signal.family().equals(family.name()))
                .max(Comparator.comparingLong(RecentSignalView::timestamp))
                .map(signal -> signal.direction().equals("BUY") ? 1
                        : signal.direction().equals("SELL") ? -1 : 0);
    }

    private List<RecentSignalView> recentSignals(User user, String symbol) {
        Map<TimeInterval, Long> cutoffs = new LinkedHashMap<>();
        cutoffs.put(TimeInterval.DAILY, cutoff(symbol, "1d", 10));
        cutoffs.put(TimeInterval.WEEKLY, cutoff(symbol, "1wk", 10));
        cutoffs.put(TimeInterval.MONTHLY, cutoff(symbol, "1mo", 10));
        return alertEventRepository.findAllByAlertRule_User(user).stream()
                .filter(event -> event.getAlertRule().getStockAsset().getTickerSymbol().equalsIgnoreCase(symbol))
                .filter(event -> event.getSignalCandleTimestamp() >= cutoffs.getOrDefault(
                        event.getAlertRule().getInterval(), Long.MAX_VALUE))
                .sorted(Comparator.comparingLong(AlertEvent::getSignalCandleTimestamp).reversed())
                .map(event -> new RecentSignalView(
                        event.getId(),
                        event.getAlertRule().getPatternFamily().name(),
                        humanize(event.getPattern().name()),
                        event.getTradeSignal().name(),
                        event.getAlertRule().getInterval().name(),
                        intervalLabel(event.getAlertRule().getInterval()),
                        event.getSignalCandleTimestamp(),
                        event.getConfidenceScore(),
                        event.getSignalStrength() == null ? "Unrated" : humanize(event.getSignalStrength().name()),
                        humanize(event.getLifecycleStatus().name()),
                        observedResult(event),
                        "/alerts/signals/" + event.getId()))
                .toList();
    }

    private long cutoff(String symbol, String interval, int candles) {
        List<Candle> values = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval);
        if (values.isEmpty()) {
            return Long.MAX_VALUE;
        }
        return values.get(Math.max(0, values.size() - candles)).getTimestamp();
    }

    private String observedResult(AlertEvent event) {
        if (event.getResolutionClosePrice() == null || event.getClosePrice() == null || event.getClosePrice() == 0) {
            return "Pending observation";
        }
        double raw = (event.getResolutionClosePrice() - event.getClosePrice()) / event.getClosePrice() * 100.0;
        double directional = event.getTradeSignal().name().equals("SELL") ? -raw : raw;
        return String.format(Locale.ROOT, "%+.2f%% directional", directional);
    }

    private List<ActivityMarkerView> activityMarkers(User user,
                                                     String symbol,
                                                     List<ChartCandleView> chart) {
        if (chart.isEmpty()) {
            return List.of();
        }
        LocalDate firstDate = Instant.ofEpochSecond(chart.getFirst().timestamp())
                .atZone(ZoneOffset.UTC).toLocalDate();
        List<ActivityMarkerView> markers = new ArrayList<>();
        congressionalDeliveryRepository.findAllForUser(user).stream()
                .filter(delivery -> delivery.getTrade().getTickerSymbol().equalsIgnoreCase(symbol))
                .filter(delivery -> !delivery.getTrade().getTransactionDate().isBefore(firstDate))
                .forEach(delivery -> markers.add(congressionalMarker(delivery)));
        insiderDeliveryRepository.findAllForUser(user).stream()
                .filter(delivery -> delivery.getTrade().getTickerSymbol().equalsIgnoreCase(symbol))
                .filter(delivery -> !delivery.getTrade().getTransactionDate().isBefore(firstDate))
                .forEach(delivery -> markers.add(insiderMarker(delivery)));
        return markers.stream().sorted(Comparator.comparing(ActivityMarkerView::date)).toList();
    }

    private ActivityMarkerView congressionalMarker(CongressionalTradeDelivery delivery) {
        var trade = delivery.getTrade();
        return new ActivityMarkerView(trade.getTransactionDate().toString(), "CONGRESSIONAL",
                trade.getTransactionType().name().contains("PURCHASE") ? "BUY" : "SELL",
                trade.getMemberName(), humanize(trade.getTransactionType().name()),
                "/activity-signals/congressional/" + delivery.getId());
    }

    private ActivityMarkerView insiderMarker(InsiderTradeDelivery delivery) {
        var trade = delivery.getTrade();
        String type = trade.getTransactionType().name();
        return new ActivityMarkerView(trade.getTransactionDate().toString(), "INSIDER",
                type.contains("PURCHASE") || type.contains("BUY") ? "BUY" : "SELL",
                trade.getInsiderName(), humanize(type),
                "/activity-signals/insider/" + delivery.getId());
    }

    private MarketComparisonView marketComparison(String symbol, StockAsset asset) {
        Benchmark benchmark = benchmarkFor(asset);
        if (!benchmark.symbol().equalsIgnoreCase(symbol)) {
            refresh(benchmark.symbol(), "1d");
        }
        List<Candle> stock = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, "1d");
        List<Candle> market = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(benchmark.symbol(), "1d");
        if (stock.size() < 2 || market.size() < 2) {
            return MarketComparisonView.unavailable(benchmark);
        }
        LocalDate latestDate = min(date(stock.getLast()), date(market.getLast()));
        List<HorizonView> horizons = List.of(
                horizon("1 month", latestDate.minusMonths(1), stock, market),
                horizon("3 months", latestDate.minusMonths(3), stock, market),
                horizon("6 months", latestDate.minusMonths(6), stock, market),
                horizon("1 year", latestDate.minusYears(1), stock, market));
        Map<LocalDate, Candle> marketByDate = new LinkedHashMap<>();
        market.forEach(candle -> marketByDate.put(date(candle), candle));
        List<RawRatio> allRatios = stock.stream()
                .filter(candle -> !date(candle).isAfter(latestDate))
                .filter(candle -> marketByDate.containsKey(date(candle)))
                .map(candle -> new RawRatio(candle.getTimestamp(),
                        candle.getClosePrice() / marketByDate.get(date(candle)).getClosePrice()))
                .filter(value -> Double.isFinite(value.ratio()) && value.ratio() > 0)
                .toList();
        List<RawRatio> ratios = allRatios.stream()
                .skip(Math.max(0, allRatios.size() - CHART_CANDLES))
                .toList();
        if (ratios.size() < 2) {
            return MarketComparisonView.unavailable(benchmark);
        }
        double base = ratios.getFirst().ratio();
        List<RatioPointView> normalized = ratios.stream()
                .map(point -> new RatioPointView(point.timestamp(), point.ratio() / base * 100.0))
                .toList();
        int trendLookback = Math.min(20, normalized.size() - 1);
        double ratioChange = percentDifference(normalized.getLast().value(),
                normalized.get(normalized.size() - 1 - trendLookback).value());
        String trend = ratioChange > 2 ? "IMPROVING" : ratioChange < -2 ? "DETERIORATING" : "STABLE";
        HorizonView threeMonth = horizons.get(1);
        int vote = threeMonth.available() && threeMonth.excessReturn() > 2 && trend.equals("IMPROVING") ? 1
                : threeMonth.available() && threeMonth.excessReturn() < -2 && trend.equals("DETERIORATING") ? -1 : 0;
        return new MarketComparisonView(true, benchmark.symbol(), benchmark.name(), trend, ratioChange,
                vote, voteLabel(vote), horizons, normalized);
    }

    private HorizonView horizon(String label,
                                LocalDate cutoff,
                                List<Candle> stock,
                                List<Candle> market) {
        LocalDate latestCommonDate = min(date(stock.getLast()), date(market.getLast()));
        Candle latestStock = atOrBefore(stock, latestCommonDate);
        Candle latestMarket = atOrBefore(market, latestCommonDate);
        Candle startStock = atOrBefore(stock, cutoff);
        Candle startMarket = atOrBefore(market, cutoff);
        if (startStock == null || startMarket == null || startStock.getClosePrice() == 0 || startMarket.getClosePrice() == 0) {
            return new HorizonView(label, false, null, null, null);
        }
        double stockReturn = percentDifference(latestStock.getClosePrice(), startStock.getClosePrice());
        double marketReturn = percentDifference(latestMarket.getClosePrice(), startMarket.getClosePrice());
        return new HorizonView(label, true, stockReturn, marketReturn, stockReturn - marketReturn);
    }

    private Candle atOrBefore(List<Candle> candles, LocalDate cutoff) {
        return candles.stream().filter(candle -> !date(candle).isAfter(cutoff)).reduce((a, b) -> b).orElse(null);
    }

    private Benchmark benchmarkFor(StockAsset asset) {
        String country = asset == null || asset.getCountry() == null ? "" : asset.getCountry().toUpperCase(Locale.ROOT);
        String currency = asset == null || asset.getCurrency() == null ? "" : asset.getCurrency().toUpperCase(Locale.ROOT);
        if (country.contains("UNITED KINGDOM") || country.equals("UK") || currency.equals("GBP")) return new Benchmark("^FTSE", "FTSE 100");
        if (country.contains("GERMAN")) return new Benchmark("^GDAXI", "DAX Performance Index");
        if (country.contains("FRANCE")) return new Benchmark("^FCHI", "CAC 40");
        if (country.contains("JAPAN") || currency.equals("JPY")) return new Benchmark("^N225", "Nikkei 225");
        if (country.contains("HONG KONG") || currency.equals("HKD")) return new Benchmark("^HSI", "Hang Seng Index");
        if (currency.equals("EUR") || country.contains("BELGI") || country.contains("NETHERLAND")) return new Benchmark("^STOXX50E", "EURO STOXX 50");
        return new Benchmark("^GSPC", "S&P 500");
    }

    private static ScoreView score(List<Integer> votes) {
        int buy = (int) votes.stream().filter(vote -> vote > 0).count();
        int neutral = (int) votes.stream().filter(vote -> vote == 0).count();
        int sell = (int) votes.stream().filter(vote -> vote < 0).count();
        int net = votes.stream().mapToInt(Integer::intValue).sum();
        int denominator = votes.size();
        double normalized = denominator == 0 ? 0 : (double) net / denominator;
        return new ScoreView(buy, neutral, sell, net, denominator, normalized,
                classification(normalized));
    }

    private static String classification(double score) {
        double magnitude = Math.abs(score);
        if (magnitude < NEUTRAL_LIMIT) return "Neutral outlook";
        String direction = score > 0 ? "buy" : "sell";
        if (magnitude >= STRONG_LIMIT) return "Strong " + direction + " outlook";
        if (magnitude >= MODERATE_LIMIT) return "Moderate " + direction + " outlook";
        return "Slight " + direction + " outlook";
    }

    private static ChartCandleView chartCandle(EnrichedCandle candle) {
        return new ChartCandleView(candle.timestamp(), candle.open(), candle.high(), candle.low(), candle.close(),
                candle.volume(), finite(candle.fastEma()), finite(candle.slowEma()), finite(candle.longSma()),
                finite(candle.lowerBollinger()), finite(candle.bollingerMiddle()), finite(candle.upperBollinger()),
                finite(candle.rollingVwap()), finite(candle.volumeProfileValueAreaLow()),
                finite(candle.volumeProfilePointOfControl()), finite(candle.volumeProfileValueAreaHigh()));
    }

    private static String freshness(long timestamp, IntervalDefinition interval) {
        long ageSeconds = Math.max(0, Instant.now().getEpochSecond() - timestamp);
        long expected = switch (interval.timeInterval()) {
            case DAILY -> 4L * 86_400;
            case WEEKLY -> 12L * 86_400;
            case MONTHLY -> 40L * 86_400;
            default -> Long.MAX_VALUE;
        };
        String date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'UTC'"));
        return (ageSeconds <= expected ? "Current completed candle · " : "Potentially stale · ") + date;
    }

    private static String voteLabel(int vote) {
        return vote > 0 ? "BUY" : vote < 0 ? "SELL" : "NEUTRAL";
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "MARKET_RELATIVE" -> "Market-relative strength";
            case "PRICE_LOCATION" -> "Price location";
            case "CANDLESTICK" -> "Candlestick signals";
            case "ELLIOTT" -> "Elliott signals";
            default -> humanize(category);
        };
    }

    private static String intervalLabel(TimeInterval interval) {
        return switch (interval) {
            case DAILY -> "Daily";
            case WEEKLY -> "Weekly";
            case MONTHLY -> "Monthly";
            default -> humanize(interval.name());
        };
    }

    private static String humanize(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static double percentDifference(double value, double baseline) {
        return !Double.isFinite(value) || !Double.isFinite(baseline) || baseline == 0
                ? Double.NaN : (value - baseline) / baseline * 100.0;
    }

    private static double bandPosition(double value, double lower, double upper) {
        return !Double.isFinite(value) || !Double.isFinite(lower) || !Double.isFinite(upper) || upper == lower
                ? Double.NaN : (value - lower) / (upper - lower) * 100.0;
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static LocalDate date(Candle candle) {
        return Instant.ofEpochSecond(candle.getTimestamp()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private interface CandleVoteRule { int vote(EnrichedCandle candle, double value); }
    private interface ValueVoteRule { int vote(double value); }
    private interface Explanation { String text(double value); }
    private record ValueAtCandle(EnrichedCandle candle, double value) { }
    private record VoteInput(String label, String category, int vote) { }
    private record RawRatio(long timestamp, double ratio) { }
    private record Benchmark(String symbol, String name) { }

    private record IntervalDefinition(String apiValue, String label, TimeInterval timeInterval) {
        private static IntervalDefinition parse(String raw) {
            String value = raw == null ? "1d" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "1d", "daily" -> new IntervalDefinition("1d", "Daily", TimeInterval.DAILY);
                case "1wk", "1w", "weekly" -> new IntervalDefinition("1wk", "Weekly", TimeInterval.WEEKLY);
                case "1mo", "monthly" -> new IntervalDefinition("1mo", "Monthly", TimeInterval.MONTHLY);
                default -> throw new IllegalArgumentException("Technical outlook supports daily, weekly, and monthly intervals.");
            };
        }
    }

    public record OutlookView(String symbol, String companyName, String interval, String intervalLabel,
                              boolean available, Long candleTimestamp, String freshness,
                              ScoreView headlineScore, ScoreView rawScore, List<CategoryView> categories,
                              List<IndicatorView> indicators, List<ChartCandleView> candles,
                              List<RecentSignalView> recentSignals, List<ActivityMarkerView> activityMarkers,
                              MarketComparisonView marketComparison, MethodologyView methodology) {
        private static OutlookView unavailable(String symbol, IntervalDefinition interval) {
            ScoreView score = TechnicalOutlookService.score(List.of());
            return new OutlookView(symbol, symbol, interval.apiValue(), interval.label(), false, null,
                    "No completed candles are available", score, score, List.of(), List.of(), List.of(),
                    List.of(), List.of(), MarketComparisonView.unavailable(new Benchmark("^GSPC", "S&P 500")),
                    new MethodologyView("Unavailable inputs are excluded.", "Categories are equally weighted.",
                            "Thresholds are symmetric.", "Descriptive rules only."));
        }
    }

    public record ScoreView(int buy, int neutral, int sell, int net, int denominator,
                            double normalizedScore, String classification) { }
    public record CategoryView(String key, String label, int vote, String classification,
                               double averageInputVote, List<String> inputs) { }
    public record IndicatorView(String key, String label, String category, String unit, Double currentValue,
                                int vote, String classification, boolean scored, String explanation,
                                String rule, Long stateChangedAt, Integer candlesSinceStateChange,
                                boolean overlay, List<IndicatorPointView> series, List<Double> referenceLines) { }
    public record IndicatorPointView(long timestamp, Double value, int vote) { }
    public record ChartCandleView(long timestamp, double open, double high, double low, double close,
                                  double volume, Double fastEma, Double slowEma, Double longSma,
                                  Double bollingerLower, Double bollingerMiddle, Double bollingerUpper,
                                  Double vwap, Double valueAreaLow, Double pointOfControl, Double valueAreaHigh) { }
    public record RecentSignalView(Long id, String family, String label, String direction, String interval,
                                   String intervalLabel, long timestamp, Integer score, String strength,
                                   String lifecycle, String observedResult, String detailUrl) { }
    public record ActivityMarkerView(String date, String source, String direction, String person,
                                     String label, String detailUrl) { }
    public record HorizonView(String label, boolean available, Double stockReturn, Double benchmarkReturn,
                              Double excessReturn) { }
    public record RatioPointView(long timestamp, double value) { }
    public record MarketComparisonView(boolean available, String benchmarkSymbol, String benchmarkName,
                                       String relativeTrend, double ratioChange, int vote,
                                       String classification, List<HorizonView> horizons,
                                       List<RatioPointView> ratioSeries) {
        private static MarketComparisonView unavailable(Benchmark benchmark) {
            return new MarketComparisonView(false, benchmark.symbol(), benchmark.name(), "UNAVAILABLE",
                    0, 0, "UNAVAILABLE", List.of(), List.of());
        }
    }
    public record MethodologyView(String rawVoteRule, String categoryRule, String thresholds,
                                  String disclaimer) { }
}
