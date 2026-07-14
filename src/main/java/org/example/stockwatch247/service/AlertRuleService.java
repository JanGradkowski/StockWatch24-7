package org.example.stockwatch247.service;

import jakarta.transaction.Transactional;
import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertRuleService {
    private static final int DEFAULT_SIGNAL_CANDLES = 5;
    private static final int HIGHER_INTERVAL_SIGNAL_CANDLES = 100;

    private final AlertRuleRepository alertRuleRepository;
    private final AlertEventRepository alertEventRepository;
    private final StockAssetRepository stockAssetRepository;
    private final CandleRepository candleRepository;
    private final TwelveDataService twelveDataService;
    private final JdbcTemplate jdbcTemplate;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService detectionService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;
    private final boolean weeklyElliottEnabled;
    private final boolean monthlyElliottEnabled;
    private final int maxTrackedStocksPerUser;
    private final int maxGlobalTrackedStocks;

    public AlertRuleService(AlertRuleRepository alertRuleRepository,
                            AlertEventRepository alertEventRepository,
                            StockAssetRepository stockAssetRepository,
                             CandleRepository candleRepository,
                             TwelveDataService twelveDataService,
                             JdbcTemplate jdbcTemplate,
                             MarketDataService marketDataService,
                            TechnicalIndicatorEnrichmentService enrichmentService,
                            CandlePatternDetectionService detectionService,
                            ElliottWaveDetectionService elliottWaveDetectionService,
                             @Value("${alerts.elliott.weekly-enabled:true}") boolean weeklyElliottEnabled,
                             @Value("${alerts.elliott.monthly-enabled:true}") boolean monthlyElliottEnabled,
                             @Value("${alerts.max-tracked-stocks-per-user:50}") int maxTrackedStocksPerUser,
                             @Value("${alerts.max-global-tracked-stocks:500}") int maxGlobalTrackedStocks) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertEventRepository = alertEventRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.candleRepository = candleRepository;
        this.twelveDataService = twelveDataService;
        this.jdbcTemplate = jdbcTemplate;
        this.marketDataService = marketDataService;
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
        this.elliottWaveDetectionService = elliottWaveDetectionService;
        this.weeklyElliottEnabled = weeklyElliottEnabled;
        this.monthlyElliottEnabled = monthlyElliottEnabled;
        this.maxTrackedStocksPerUser = Math.max(1, maxTrackedStocksPerUser);
        this.maxGlobalTrackedStocks = Math.max(this.maxTrackedStocksPerUser, maxGlobalTrackedStocks);
    }

    public List<TrackedAlertView> getActiveAlertViews(User user) {
        return alertRuleRepository.findByUserAndIsActiveTrueOrderByStockAsset_TickerSymbolAscIntervalAscPatternFamilyAscTradeSignalAsc(user)
                .stream()
                .map(rule -> new TrackedAlertView(
                        rule.getId(),
                        rule.getStockAsset().getTickerSymbol(),
                        rule.getStockAsset().getCompanyName(),
                        rule.getInterval(),
                        intervalLabel(rule.getInterval()),
                        rule.getPatternFamily(),
                        familyLabel(rule.getPatternFamily()),
                        rule.getTradeSignal(),
                        alertEventRepository.countByAlertRule(rule)
                ))
                .toList();
    }

    public AlertRuleSignalHistory getSignalHistory(User user, Long alertRuleId) {
        AlertRule rule = alertRuleRepository.findByIdAndUserAndIsActiveTrue(alertRuleId, user)
                .orElseThrow(() -> new IllegalArgumentException("Active alert rule not found."));
        List<AlertEventView> events = alertEventRepository.findByAlertRuleOrderBySignalCandleTimestampDesc(rule)
                .stream()
                .map(this::toEventView)
                .toList();

        TrackedAlertView alert = new TrackedAlertView(
                rule.getId(),
                rule.getStockAsset().getTickerSymbol(),
                rule.getStockAsset().getCompanyName(),
                rule.getInterval(),
                intervalLabel(rule.getInterval()),
                rule.getPatternFamily(),
                familyLabel(rule.getPatternFamily()),
                rule.getTradeSignal(),
                events.size()
        );
        return new AlertRuleSignalHistory(alert, events);
    }

    @Transactional
    public AlertRule setAlert(User user,
                              String rawSymbol,
                              TimeInterval interval,
                              TradeSignal signal,
                              AlertPatternFamily patternFamily,
                              boolean active) {
        if (signal == TradeSignal.HOLD) {
            throw new IllegalArgumentException("Only BUY and SELL alert signals can be tracked.");
        }
        AlertPatternFamily family = normalizeFamily(patternFamily);
        validateFamilyInterval(family, interval);

        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset stockAsset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .orElseGet(() -> twelveDataService.upsertStockAsset(symbol, symbol, "UNKNOWN", "USD"));

        if (active) {
            enforceTrackedStockLimit(user, stockAsset);
        }

        AlertRule rule = alertRuleRepository
                .findByUserAndStockAssetAndIntervalAndTradeSignalAndPatternFamily(user, stockAsset, interval, signal, family)
                .orElseGet(AlertRule::new);

        rule.setUser(user);
        rule.setStockAsset(stockAsset);
        rule.setInterval(interval);
        rule.setTradeSignal(signal);
        rule.setPatternFamily(family);
        // Existing databases have a check constraint generated from the original enum values.
        // The tradeSignal column drives the new "any buy/sell pattern" behavior.
        rule.setTargetPattern(defaultTargetPattern(signal, family));
        rule.setActive(active);
        return alertRuleRepository.save(rule);
    }

    public Map<String, Object> checkLatestSignal(User user,
                                                 String rawSymbol,
                                                 TimeInterval interval,
                                                 TradeSignal signal,
                                                 AlertPatternFamily patternFamily) {
        if (signal == TradeSignal.HOLD) {
            throw new IllegalArgumentException("Only BUY and SELL signals can be checked.");
        }
        AlertPatternFamily family = normalizeFamily(patternFamily);
        validateFamilyInterval(family, interval);

        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        String apiInterval = toApiInterval(interval);
        MarketDataService.CandleSyncResult syncResult = marketDataService
                .syncCandles(symbol, apiInterval, null, true);
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed: " + syncResult.failureMessage());
        }

        List<Candle> latest = candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, apiInterval);
        List<EnrichedCandle> enrichedCandles = enrichmentService.enrich(latest, signalCandleCount(interval));
        List<DetectedSignal> detectedSignals = detectSignals(enrichedCandles, interval);
        List<Map<String, Object>> detected = detectedSignals.stream()
                .filter(detectedSignal -> signalFamily(detectedSignal) == family)
                .map(detectedSignal -> Map.<String, Object>of(
                        "pattern", detectedSignal.pattern().name(),
                        "patternFamily", family.name(),
                        "signal", detectedSignal.tradeSignal().name(),
                        "strength", detectedSignal.strength().name(),
                        "confidenceScore", detectedSignal.confidenceScore(),
                        "reasons", detectedSignal.reasons(),
                        "timestamp", detectedSignal.candleTimestamp(),
                        "closePrice", detectedSignal.closePrice()
                ))
                .toList();

        List<String> matchingPatterns = detectedSignals.stream()
                .filter(detectedSignal -> signalFamily(detectedSignal) == family)
                .filter(detectedSignal -> detectedSignal.tradeSignal() == signal)
                .map(detectedSignal -> detectedSignal.pattern().name())
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", symbol);
        response.put("interval", interval.name());
        response.put("signal", signal.name());
        response.put("patternFamily", family.name());
        response.put("candlesChecked", latest.size());
        response.put("enrichedCandlesChecked", enrichedCandles.size());
        response.put("matched", !matchingPatterns.isEmpty());
        response.put("matchingPatterns", matchingPatterns);
        response.put("detectedSignals", detected);
        if (!latest.isEmpty()) {
            Candle newest = latest.get(0);
            response.put("latestTimestamp", newest.getTimestamp());
            response.put("latestClosePrice", newest.getClosePrice());
        }
        return response;
    }

    public Map<String, Object> getAlertState(User user, String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset stockAsset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", symbol);
        response.put("maxTrackedStocks", maxTrackedStocksPerUser);

        Map<String, Map<String, Boolean>> intervals = new LinkedHashMap<>();
        for (TimeInterval interval : List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            Map<String, Boolean> signals = new LinkedHashMap<>();
            signals.put("BUY", false);
            signals.put("SELL", false);
            intervals.put(interval.name(), signals);
        }

        Map<String, Map<String, Map<String, Boolean>>> families = new LinkedHashMap<>();
        for (AlertPatternFamily family : AlertPatternFamily.values()) {
            Map<String, Map<String, Boolean>> familyIntervals = new LinkedHashMap<>();
            for (TimeInterval interval : List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
                Map<String, Boolean> signals = new LinkedHashMap<>();
                signals.put("BUY", false);
                signals.put("SELL", false);
                familyIntervals.put(interval.name(), signals);
            }
            families.put(family.name(), familyIntervals);
        }

        if (stockAsset != null) {
            alertRuleRepository.findByUserAndStockAssetAndIsActiveTrue(user, stockAsset)
                    .forEach(rule -> {
                        if (intervals.containsKey(rule.getInterval().name())
                                && rule.getTradeSignal() != null
                                && rule.getTradeSignal() != TradeSignal.HOLD) {
                            intervals.get(rule.getInterval().name()).put(rule.getTradeSignal().name(), true);
                        }
                        AlertPatternFamily family = rule.getPatternFamily();
                        if (families.containsKey(family.name())
                                && families.get(family.name()).containsKey(rule.getInterval().name())
                                && rule.getTradeSignal() != null
                                && rule.getTradeSignal() != TradeSignal.HOLD) {
                            families.get(family.name())
                                    .get(rule.getInterval().name())
                                    .put(rule.getTradeSignal().name(), true);
                        }
                    });
        }

        response.put("intervals", intervals);
        response.put("families", families);
        response.put("trackedStocks", alertRuleRepository.countDistinctActiveStocksByUser(user));
        return response;
    }

    private void enforceTrackedStockLimit(User user, StockAsset stockAsset) {
        // Serialize quota decisions so concurrent requests cannot race past either cap.
        jdbcTemplate.queryForObject(
                "select lock_name from security_resource_locks where lock_name = ? for update",
                String.class,
                "alert-stock-quota");

        if (alertRuleRepository.existsByUserAndStockAssetAndIsActiveTrue(user, stockAsset)) {
            return;
        }
        if (alertRuleRepository.countDistinctActiveStocksByUser(user) >= maxTrackedStocksPerUser) {
            throw new IllegalStateException(
                    "Per-user tracked market instrument limit reached (" + maxTrackedStocksPerUser + ").");
        }
        if (!alertRuleRepository.existsByStockAssetAndIsActiveTrue(stockAsset)
                && alertRuleRepository.countDistinctActiveStocks() >= maxGlobalTrackedStocks) {
            throw new IllegalStateException("Global market data capacity has been reached.");
        }
    }

    private String toApiInterval(TimeInterval interval) {
        return switch (interval) {
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            default -> "1d";
        };
    }

    private String intervalLabel(TimeInterval interval) {
        return switch (interval) {
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            default -> "1d";
        };
    }

    private String familyLabel(AlertPatternFamily family) {
        return switch (normalizeFamily(family)) {
            case ELLIOTT_WAVE -> "Elliott Wave";
            case CANDLESTICK -> "Candlestick";
        };
    }

    private List<DetectedSignal> detectSignals(List<EnrichedCandle> enrichedCandles, TimeInterval interval) {
        List<DetectedSignal> signals = new java.util.ArrayList<>(detectionService.detect(enrichedCandles));
        if (isElliottEnabled(interval)) {
            signals.addAll(elliottWaveDetectionService.detect(enrichedCandles).stream()
                    .filter(this::isActionableElliottTurningPoint)
                    .toList());
        }
        return List.copyOf(signals);
    }

    private int signalCandleCount(TimeInterval interval) {
        return isHigherInterval(interval) ? HIGHER_INTERVAL_SIGNAL_CANDLES : DEFAULT_SIGNAL_CANDLES;
    }

    private boolean isHigherInterval(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY || interval == TimeInterval.MONTHLY;
    }

    private boolean isElliottEnabled(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY && weeklyElliottEnabled
                || interval == TimeInterval.MONTHLY && monthlyElliottEnabled;
    }

    private AlertPatternFamily normalizeFamily(AlertPatternFamily family) {
        return family == null ? AlertPatternFamily.CANDLESTICK : family;
    }

    private void validateFamilyInterval(AlertPatternFamily family, TimeInterval interval) {
        if (normalizeFamily(family) == AlertPatternFamily.ELLIOTT_WAVE
                && interval != TimeInterval.WEEKLY
                && interval != TimeInterval.MONTHLY) {
            throw new IllegalArgumentException("Elliott Wave alerts are available only for weekly and monthly intervals.");
        }
    }

    private CandlePattern defaultTargetPattern(TradeSignal signal, AlertPatternFamily family) {
        if (normalizeFamily(family) == AlertPatternFamily.ELLIOTT_WAVE) {
            return signal == TradeSignal.BUY
                    ? CandlePattern.ELLIOTT_BULLISH_CORRECTION
                    : CandlePattern.ELLIOTT_BEARISH_CORRECTION;
        }
        return signal == TradeSignal.BUY ? CandlePattern.BULLISH_ENGULFING : CandlePattern.BEARISH_ENGULFING;
    }

    private AlertPatternFamily signalFamily(DetectedSignal signal) {
        return isElliottPattern(signal.pattern()) ? AlertPatternFamily.ELLIOTT_WAVE : AlertPatternFamily.CANDLESTICK;
    }

    private boolean isElliottPattern(CandlePattern pattern) {
        return pattern == CandlePattern.ELLIOTT_BULLISH_IMPULSE
                || pattern == CandlePattern.ELLIOTT_BEARISH_IMPULSE
                || pattern == CandlePattern.ELLIOTT_BULLISH_WAVE_V_END
                || pattern == CandlePattern.ELLIOTT_BEARISH_WAVE_V_END
                || pattern == CandlePattern.ELLIOTT_BULLISH_CORRECTION
                || pattern == CandlePattern.ELLIOTT_BEARISH_CORRECTION;
    }

    private boolean isActionableElliottTurningPoint(DetectedSignal signal) {
        return signal.pattern() == CandlePattern.ELLIOTT_BULLISH_WAVE_V_END
                || signal.pattern() == CandlePattern.ELLIOTT_BEARISH_WAVE_V_END
                || signal.pattern() == CandlePattern.ELLIOTT_BULLISH_CORRECTION
                || signal.pattern() == CandlePattern.ELLIOTT_BEARISH_CORRECTION;
    }

    private AlertEventView toEventView(AlertEvent event) {
        return new AlertEventView(
                event.getPattern(),
                event.getTradeSignal(),
                event.getSignalStrength(),
                event.getConfidenceScore(),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                event.getClosePrice(),
                event.getSentAt()
        );
    }

    private LocalDate signalDate(Long timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public record TrackedAlertView(
            Long id,
            String symbol,
            String companyName,
            TimeInterval interval,
            String intervalLabel,
            AlertPatternFamily patternFamily,
            String familyLabel,
            TradeSignal tradeSignal,
            long eventCount
    ) {
    }

    public record AlertRuleSignalHistory(
            TrackedAlertView alert,
            List<AlertEventView> events
    ) {
    }

    public record AlertEventView(
            CandlePattern pattern,
            TradeSignal tradeSignal,
            SignalStength strength,
            Integer confidenceScore,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            Double closePrice,
            java.time.LocalDateTime sentAt
    ) {
    }
}
