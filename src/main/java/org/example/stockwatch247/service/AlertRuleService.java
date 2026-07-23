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
import org.example.stockwatch247.model.enums.InstrumentType;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AlertRuleService {
    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int HIGHER_INTERVAL_SIGNAL_CANDLES = 100;
    private static final int DASHBOARD_LATEST_SIGNAL_LIMIT = 8;
    private static final int MAX_ALERT_CHANGES_PER_REQUEST = 16;

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
                .map(rule -> toTrackedAlertView(rule, alertEventRepository.countByAlertRule(rule)))
                .toList();
    }

    public List<TrackedCompanyView> getActiveCompanyViews(User user) {
        List<AlertRule> activeRules = alertRuleRepository
                .findByUserAndIsActiveTrueOrderByStockAsset_TickerSymbolAscIntervalAscPatternFamilyAscTradeSignalAsc(user);
        Map<String, List<AlertRule>> rulesBySymbol = new LinkedHashMap<>();
        for (AlertRule rule : activeRules) {
            rulesBySymbol.computeIfAbsent(
                    rule.getStockAsset().getTickerSymbol(),
                    ignored -> new ArrayList<>()
            ).add(rule);
        }
        return rulesBySymbol.values().stream()
                .map(this::toTrackedCompanyView)
                .toList();
    }

    public List<LatestSignalView> getLatestSignalViews(User user) {
        return alertEventRepository
                .findByAlertRule_UserAndAlertRule_IsActiveTrueOrderBySentAtDescIdDesc(
                        user,
                        PageRequest.of(0, DASHBOARD_LATEST_SIGNAL_LIMIT)
                )
                .stream()
                .map(this::toLatestSignalView)
                .toList();
    }

    public AlertRuleSignalHistory getSignalHistory(User user, Long alertRuleId) {
        AlertRule rule = alertRuleRepository.findByIdAndUserAndIsActiveTrue(alertRuleId, user)
                .orElseThrow(() -> new IllegalArgumentException("Active alert rule not found."));
        return toSignalHistory(rule);
    }

    public CompanySignalHistory getCompanySignalHistory(User user, Long alertRuleId) {
        AlertRule selectedRule = alertRuleRepository.findByIdAndUserAndIsActiveTrue(alertRuleId, user)
                .orElseThrow(() -> new IllegalArgumentException("Active alert rule not found."));
        List<AlertRuleSignalHistory> columns = alertRuleRepository
                .findByUserAndStockAssetAndIsActiveTrue(user, selectedRule.getStockAsset())
                .stream()
                .sorted(Comparator.comparing(AlertRule::getInterval)
                        .thenComparing(AlertRule::getPatternFamily)
                        .thenComparing(AlertRule::getTradeSignal))
                .map(this::toSignalHistory)
                .toList();
        long totalEventCount = columns.stream()
                .mapToLong(column -> column.events().size())
                .sum();
        return new CompanySignalHistory(
                selectedRule.getStockAsset().getTickerSymbol(),
                selectedRule.getStockAsset().getCompanyName(),
                columns.size(),
                totalEventCount,
                columns
        );
    }

    public SignalDetailView getSignalDetail(User user, Long alertEventId) {
        AlertEvent event = alertEventRepository.findOwnedByIdAndUser(alertEventId, user)
                .orElseThrow(() -> new IllegalArgumentException("Signal event not found."));
        AlertRule rule = event.getAlertRule();
        CandlestickHorizonGuidance.Guidance horizonGuidance = CandlestickHorizonGuidance
                .forSignal(rule.getPatternFamily(), rule.getInterval())
                .orElse(null);
        List<String> storedReasons = event.getConfidenceReasons();
        List<SignalReasonView> reasons = new ArrayList<>();
        for (int index = 0; index < storedReasons.size(); index++) {
            String reason = storedReasons.get(index);
            reasons.add(new SignalReasonView(
                    index + 1,
                    reasonCategory(reason),
                    reason,
                    isCautionReason(reason)
            ));
        }

        return new SignalDetailView(
                event.getId(),
                rule.getId(),
                rule.getStockAsset().getTickerSymbol(),
                rule.getStockAsset().getCompanyName(),
                rule.getInterval(),
                intervalLabel(rule.getInterval()),
                horizonGuidance == null ? null : horizonGuidance.label(),
                horizonGuidance == null ? null : horizonGuidance.summary(),
                horizonGuidance == null ? null : horizonGuidance.disclaimer(),
                normalizeFamily(rule.getPatternFamily()),
                familyLabel(rule.getPatternFamily()),
                event.getPattern(),
                patternLabel(event.getPattern()),
                event.getTradeSignal(),
                setupStrengthLabel(event.getSignalStrength(), event.getConfidenceScore()),
                setupBand(event.getConfidenceScore()),
                event.getConfidenceScore(),
                setupExplanation(event.getConfidenceScore()),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(rule.getInterval(), event.getSignalCandleTimestamp()),
                event.getClosePrice(),
                event.getSentAt(),
                List.copyOf(reasons),
                !reasons.isEmpty()
        );
    }

    private AlertRuleSignalHistory toSignalHistory(AlertRule rule) {
        List<AlertEventView> events = alertEventRepository.findByAlertRuleOrderBySignalCandleTimestampDesc(rule)
                .stream()
                .map(event -> toEventView(event, rule.getInterval()))
                .toList();
        TrackedAlertView alert = toTrackedAlertView(rule, events.size());
        return new AlertRuleSignalHistory(alert, events);
    }

    private TrackedCompanyView toTrackedCompanyView(List<AlertRule> rules) {
        AlertRule representativeRule = rules.getFirst();
        InstrumentType instrumentType = representativeRule.getStockAsset().getInstrumentType() == null
                ? InstrumentType.EQUITY
                : representativeRule.getStockAsset().getInstrumentType();
        List<String> intervalLabels = rules.stream()
                .map(rule -> intervalLabel(rule.getInterval()))
                .distinct()
                .toList();
        List<String> familyLabels = rules.stream()
                .map(rule -> familyLabel(rule.getPatternFamily()))
                .distinct()
                .toList();
        List<TradeSignal> tradeSignals = rules.stream()
                .map(AlertRule::getTradeSignal)
                .distinct()
                .toList();
        long eventCount = rules.stream()
                .mapToLong(alertEventRepository::countByAlertRule)
                .sum();
        return new TrackedCompanyView(
                representativeRule.getId(),
                representativeRule.getStockAsset().getTickerSymbol(),
                representativeRule.getStockAsset().getCompanyName(),
                instrumentType,
                instrumentTypeLabel(instrumentType),
                instrumentGroup(instrumentType),
                rules.size(),
                intervalLabels,
                familyLabels,
                tradeSignals,
                eventCount
        );
    }

    private String instrumentTypeLabel(InstrumentType instrumentType) {
        return switch (instrumentType) {
            case EQUITY -> "Stock";
            case ETF -> "ETF";
            case INDEX -> "Index";
            case OTHER -> "Other";
        };
    }

    private String instrumentGroup(InstrumentType instrumentType) {
        return instrumentType == InstrumentType.INDEX || instrumentType == InstrumentType.ETF
                ? "funds"
                : "stocks";
    }

    private TrackedAlertView toTrackedAlertView(AlertRule rule, long eventCount) {
        CandlestickHorizonGuidance.Guidance horizonGuidance = CandlestickHorizonGuidance
                .forSignal(rule.getPatternFamily(), rule.getInterval())
                .orElse(null);
        return new TrackedAlertView(
                rule.getId(),
                rule.getStockAsset().getTickerSymbol(),
                rule.getStockAsset().getCompanyName(),
                rule.getInterval(),
                intervalLabel(rule.getInterval()),
                horizonGuidance == null ? null : horizonGuidance.label(),
                horizonGuidance == null ? null : horizonGuidance.summary(),
                horizonGuidance == null ? null : horizonGuidance.disclaimer(),
                rule.getPatternFamily(),
                familyLabel(rule.getPatternFamily()),
                rule.getTradeSignal(),
                eventCount
        );
    }

    private LatestSignalView toLatestSignalView(AlertEvent event) {
        AlertRule rule = event.getAlertRule();
        CandlestickHorizonGuidance.Guidance horizonGuidance = CandlestickHorizonGuidance
                .forSignal(rule.getPatternFamily(), rule.getInterval())
                .orElse(null);
        return new LatestSignalView(
                event.getId(),
                rule.getStockAsset().getTickerSymbol(),
                rule.getStockAsset().getCompanyName(),
                normalizeFamily(rule.getPatternFamily()),
                familyLabel(rule.getPatternFamily()),
                event.getPattern(),
                patternLabel(event.getPattern()),
                event.getTradeSignal(),
                rule.getInterval(),
                intervalLabel(rule.getInterval()),
                horizonGuidance == null ? null : horizonGuidance.label(),
                horizonGuidance == null ? null : horizonGuidance.summary(),
                horizonGuidance == null ? null : horizonGuidance.disclaimer(),
                event.getConfidenceScore(),
                setupBand(event.getConfidenceScore()),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(rule.getInterval(), event.getSignalCandleTimestamp()),
                event.getSentAt()
        );
    }

    @Transactional
    public AlertRule setAlert(User user,
                              String rawSymbol,
                              TimeInterval interval,
                              TradeSignal signal,
                              AlertPatternFamily patternFamily,
                              boolean active) {
        AlertRuleChange change = validateAlertChange(
                new AlertRuleChange(interval, signal, patternFamily, active));

        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset stockAsset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .orElseGet(() -> twelveDataService.upsertStockAsset(symbol, symbol, "UNKNOWN", "USD"));

        if (active) {
            enforceTrackedStockLimit(user, stockAsset);
        }

        return upsertAlertRule(user, stockAsset, change);
    }

    @Transactional
    public void applyAlertChanges(User user,
                                  String rawSymbol,
                                  List<AlertRuleChange> requestedChanges) {
        if (requestedChanges == null || requestedChanges.isEmpty()
                || requestedChanges.size() > MAX_ALERT_CHANGES_PER_REQUEST) {
            throw new IllegalArgumentException("An alert change batch must contain between 1 and "
                    + MAX_ALERT_CHANGES_PER_REQUEST + " entries.");
        }

        Map<AlertRuleKey, AlertRuleChange> changesByRule = new LinkedHashMap<>();
        for (AlertRuleChange requestedChange : requestedChanges) {
            AlertRuleChange change = validateAlertChange(requestedChange);
            AlertRuleKey key = new AlertRuleKey(
                    change.interval(), change.signal(), change.patternFamily());
            if (changesByRule.putIfAbsent(key, change) != null) {
                throw new IllegalArgumentException("Duplicate alert rule in change batch.");
            }
        }

        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset stockAsset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .orElseGet(() -> twelveDataService.upsertStockAsset(symbol, symbol, "UNKNOWN", "USD"));

        if (changesByRule.values().stream().anyMatch(AlertRuleChange::active)) {
            enforceTrackedStockLimit(user, stockAsset);
        }

        changesByRule.values().forEach(change -> upsertAlertRule(user, stockAsset, change));
    }

    private AlertRuleChange validateAlertChange(AlertRuleChange change) {
        if (change == null || change.interval() == null || change.signal() == null) {
            throw new IllegalArgumentException("Alert interval and signal are required.");
        }
        if (change.signal() == TradeSignal.HOLD) {
            throw new IllegalArgumentException("Only BUY and SELL alert signals can be tracked.");
        }
        AlertPatternFamily family = normalizeFamily(change.patternFamily());
        validateFamilyInterval(family, change.interval());
        return new AlertRuleChange(change.interval(), change.signal(), family, change.active());
    }

    private AlertRule upsertAlertRule(User user,
                                      StockAsset stockAsset,
                                      AlertRuleChange change) {
        TimeInterval interval = change.interval();
        TradeSignal signal = change.signal();
        AlertPatternFamily family = change.patternFamily();

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
        rule.setActive(change.active());
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

        int signalCandleCount = signalCandleCount(interval);
        List<Candle> latest = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol,
                apiInterval,
                PageRequest.of(0, enrichmentService.requiredInputCandles(signalCandleCount))
        );
        List<EnrichedCandle> enrichedCandles = enrichmentService.enrich(latest, signalCandleCount);
        List<DetectedSignal> detectedSignals = detectSignals(enrichedCandles, interval);
        List<Map<String, Object>> detected = detectedSignals.stream()
                .filter(detectedSignal -> signalFamily(detectedSignal) == family)
                .map(detectedSignal -> Map.<String, Object>of(
                        "pattern", detectedSignal.pattern().name(),
                        "patternFamily", family.name(),
                        "signal", detectedSignal.tradeSignal().name(),
                        "strength", detectedSignal.strength().name(),
                        "setupScore", detectedSignal.setupScore(),
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
        CandlestickHorizonGuidance.forSignal(family, interval).ifPresent(guidance -> {
            response.put("researchHorizonLabel", guidance.label());
            response.put("researchHorizonSummary", guidance.summary());
            response.put("researchHorizonDisclaimer", guidance.disclaimer());
        });
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

    private List<DetectedSignal> detectSignals(List<EnrichedCandle> enrichedCandles,
                                               TimeInterval interval) {
        List<DetectedSignal> signals = new java.util.ArrayList<>(
                detectionService.detectAlertSignals(enrichedCandles)
        );
        if (isElliottEnabled(interval)) {
            signals.addAll(elliottWaveDetectionService.detectAlertSignals(enrichedCandles).stream()
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
        return pattern != null && pattern.name().startsWith("ELLIOTT_");
    }

    private boolean isActionableElliottTurningPoint(DetectedSignal signal) {
        CandlePattern pattern = signal.pattern();
        return isElliottPattern(pattern)
                && (pattern.name().endsWith("WAVE_V_END") || pattern.name().endsWith("CORRECTION"));
    }

    private AlertEventView toEventView(AlertEvent event, TimeInterval interval) {
        return new AlertEventView(
                event.getId(),
                event.getPattern(),
                patternLabel(event.getPattern()),
                event.getTradeSignal(),
                event.getSignalStrength(),
                setupStrengthLabel(event.getSignalStrength(), event.getConfidenceScore()),
                setupBand(event.getConfidenceScore()),
                event.getConfidenceScore(),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(interval, event.getSignalCandleTimestamp()),
                event.getClosePrice(),
                event.getSentAt()
        );
    }

    private String setupStrengthLabel(SignalStength strength, Integer setupScore) {
        if (strength != null) {
            return switch (strength) {
                case WEAK_IGNORE -> "Very weak setup";
                case HIGH_CONFIDENCE -> "Strong setup";
                case MEDIUM_CONFIDENCE -> "Moderate setup";
                case LOW_CONFIDENCE -> "Weak setup";
            };
        }
        return switch (setupBand(setupScore)) {
            case "high" -> "Strong setup";
            case "medium" -> "Moderate setup";
            case "low" -> "Weak setup";
            default -> "Unrated";
        };
    }

    private String setupBand(Integer setupScore) {
        if (setupScore == null) {
            return "unrated";
        }
        if (setupScore >= 85) {
            return "high";
        }
        return setupScore >= 75 ? "medium" : "low";
    }

    private String setupExplanation(Integer setupScore) {
        if (setupScore == null) {
            return "This signal does not have a recorded setup score.";
        }
        if (setupScore >= 85) {
            return "Strong setup (85-100): broad confluence across the stock's pattern quality and technical evidence.";
        }
        if (setupScore >= 75) {
            return "Moderate setup (75-84): several factors align, with some mixed or unavailable evidence.";
        }
        return "Weak setup (below 75): the pattern is valid, but supporting confluence is limited.";
    }

    private String reasonCategory(String reason) {
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("pattern geometry")) {
            return "Pattern geometry";
        }
        if (normalized.startsWith("trend context")) {
            return "Trend context";
        }
        if (normalized.startsWith("location")) {
            return "Location";
        }
        if (normalized.startsWith("momentum")) {
            return "Momentum";
        }
        if (normalized.startsWith("volume")) {
            return "Volume";
        }
        if (containsAny(normalized, "calibrat", "precision", "backtest")) {
            return "Calibration";
        }
        if (containsAny(normalized, "elliott", "wave", "pivot", "retracement", "fibonacci", "impulse", "correction")) {
            return "Wave structure";
        }
        if (containsAny(normalized, "geometry", "body", "wick", "engulf", "doji", "hammer", "shooting star", "pattern")) {
            return "Pattern geometry";
        }
        if (containsAny(normalized, "rsi", "ema", "momentum")) {
            return "Momentum";
        }
        if (containsAny(normalized, "bollinger", "volatility")) {
            return "Volatility";
        }
        if (normalized.contains("volume")) {
            return "Volume";
        }
        if (containsAny(normalized, "trend", "pressure")) {
            return "Trend context";
        }
        if (containsAny(normalized, "breakout", "breakdown", "close", "reversal", "price")) {
            return "Price action";
        }
        return "Market context";
    }

    private boolean isCautionReason(String reason) {
        String normalized = reason.toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "+0/",
                "lowered confidence",
                "below the calibrated",
                "was unavailable",
                "insufficient",
                "not confirmed",
                "without confirmation",
                "weak evidence",
                "missing data",
                "confidence reduced",
                "penalty");
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String patternLabel(CandlePattern pattern) {
        if (pattern == null) {
            return "Unknown pattern";
        }
        StringBuilder label = new StringBuilder();
        for (String word : pattern.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private LocalDate signalDate(Long timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private String signalPeriodLabel(TimeInterval interval, Long timestamp) {
        return SignalPeriodFormatter.format(timestamp, interval, ZoneId.systemDefault());
    }

    public record TrackedAlertView(
            Long id,
            String symbol,
            String companyName,
            TimeInterval interval,
            String intervalLabel,
            String researchHorizonLabel,
            String researchHorizonSummary,
            String researchHorizonDisclaimer,
            AlertPatternFamily patternFamily,
            String familyLabel,
            TradeSignal tradeSignal,
            long eventCount
    ) {
    }

    public record TrackedCompanyView(
            Long representativeAlertId,
            String symbol,
            String companyName,
            InstrumentType instrumentType,
            String instrumentTypeLabel,
            String instrumentGroup,
            int ruleCount,
            List<String> intervalLabels,
            List<String> familyLabels,
            List<TradeSignal> tradeSignals,
            long eventCount
    ) {
    }

    public record LatestSignalView(
            Long id,
            String symbol,
            String companyName,
            AlertPatternFamily patternFamily,
            String familyLabel,
            CandlePattern pattern,
            String patternLabel,
            TradeSignal tradeSignal,
            TimeInterval interval,
            String intervalLabel,
            String researchHorizonLabel,
            String researchHorizonSummary,
            String researchHorizonDisclaimer,
            Integer setupScore,
            String setupBand,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            java.time.LocalDateTime sentAt
    ) {
        public Integer confidenceScore() {
            return setupScore;
        }

        public String confidenceBand() {
            return setupBand;
        }
    }

    public record AlertRuleSignalHistory(
            TrackedAlertView alert,
            List<AlertEventView> events
    ) {
    }

    public record CompanySignalHistory(
            String symbol,
            String companyName,
            int ruleCount,
            long eventCount,
            List<AlertRuleSignalHistory> columns
    ) {
    }

    public record AlertEventView(
            Long id,
            CandlePattern pattern,
            String patternLabel,
            TradeSignal tradeSignal,
            SignalStength strength,
            String setupStrengthLabel,
            String setupBand,
            Integer setupScore,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            Double closePrice,
            java.time.LocalDateTime sentAt
    ) {
        public Integer confidenceScore() {
            return setupScore;
        }
    }

    public record SignalDetailView(
            Long id,
            Long alertRuleId,
            String symbol,
            String companyName,
            TimeInterval interval,
            String intervalLabel,
            String researchHorizonLabel,
            String researchHorizonSummary,
            String researchHorizonDisclaimer,
            AlertPatternFamily patternFamily,
            String familyLabel,
            CandlePattern pattern,
            String patternLabel,
            TradeSignal tradeSignal,
            String setupStrengthLabel,
            String setupBand,
            Integer setupScore,
            String setupExplanation,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            Double closePrice,
            java.time.LocalDateTime sentAt,
            List<SignalReasonView> reasons,
            boolean reasonsAvailable
    ) {
        public String strengthLabel() {
            return setupStrengthLabel;
        }

        public String confidenceBand() {
            return setupBand;
        }

        public Integer confidenceScore() {
            return setupScore;
        }

        public String confidenceExplanation() {
            return setupExplanation;
        }
    }

    public record SignalReasonView(
            int order,
            String category,
            String text,
            boolean caution
    ) {
    }

    public record AlertRuleChange(
            TimeInterval interval,
            TradeSignal signal,
            AlertPatternFamily patternFamily,
            boolean active
    ) {
    }

    private record AlertRuleKey(
            TimeInterval interval,
            TradeSignal signal,
            AlertPatternFamily patternFamily
    ) {
    }
}
