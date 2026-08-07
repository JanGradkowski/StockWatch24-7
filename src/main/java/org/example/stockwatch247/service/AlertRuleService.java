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
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AlertRuleService {
    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int HIGHER_INTERVAL_SIGNAL_CANDLES = 100;
    private static final int DASHBOARD_LATEST_SIGNAL_LIMIT = 8;
    private static final int SIGNAL_ARCHIVE_PAGE_SIZE = 50;
    private static final int MAX_ALERT_CHANGES_PER_REQUEST = 16;
    private static final int SIGNAL_CHART_TREND_CANDLES = 5;
    private static final int MINIMUM_RESULT_CANDLES = 10;

    private final AlertRuleRepository alertRuleRepository;
    private final AlertEventRepository alertEventRepository;
    private final StockAssetRepository stockAssetRepository;
    private final CandleRepository candleRepository;
    private final TwelveDataService twelveDataService;
    private final JdbcTemplate jdbcTemplate;
    private final MarketDataService marketDataService;
    private final CandleCompletionService candleCompletionService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService detectionService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;
    private final boolean weeklyElliottEnabled;
    private final boolean monthlyElliottEnabled;
    private final int maxTrackedStocksPerUser;
    private final int maxGlobalTrackedStocks;

    @Autowired
    public AlertRuleService(AlertRuleRepository alertRuleRepository,
                            AlertEventRepository alertEventRepository,
                            StockAssetRepository stockAssetRepository,
                             CandleRepository candleRepository,
                            TwelveDataService twelveDataService,
                            JdbcTemplate jdbcTemplate,
                            MarketDataService marketDataService,
                            CandleCompletionService candleCompletionService,
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
        this.candleCompletionService = candleCompletionService;
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
                .findByAlertRule_UserAndAlertRule_IsActiveTrueAndReadAtIsNullOrderBySentAtDescIdDesc(
                        user,
                        PageRequest.of(0, DASHBOARD_LATEST_SIGNAL_LIMIT)
                )
                .stream()
                .map(this::toLatestSignalView)
                .toList();
    }

    @Transactional
    public List<ElliottWaveSignalCard> getElliottWaveSignalCards(
            User user,
            String rawSymbol,
            TimeInterval interval) {
        if (user == null) {
            throw new IllegalArgumentException("User is required.");
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        validateFamilyInterval(AlertPatternFamily.ELLIOTT_WAVE, interval);

        List<AlertEvent> events = alertEventRepository.findElliottSignalCards(
                user,
                symbol,
                interval,
                AlertPatternFamily.ELLIOTT_WAVE
        );
        if (events.isEmpty()) {
            return List.of();
        }

        long firstIncompleteTimestamp = candleCompletionService.firstIncompleteCandleTimestamp(interval);
        TreeMap<Long, Candle> completedCandles = new TreeMap<>();
        candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, toApiInterval(interval))
                .stream()
                .filter(this::validChartCandle)
                .filter(candle -> candle.getTimestamp() < firstIncompleteTimestamp)
                .forEach(candle -> completedCandles.put(candle.getTimestamp(), candle));

        Map<String, AlertEvent> firstEventByStage = new LinkedHashMap<>();
        for (AlertEvent event : events) {
            String key = event.getElliottCycleKey() + ':' + event.getElliottSignalStage();
            firstEventByStage.putIfAbsent(key, event);
        }
        return firstEventByStage.values().stream()
                .map(event -> toElliottWaveSignalCard(event, interval, completedCandles))
                .toList();
    }

    private ElliottWaveSignalCard toElliottWaveSignalCard(
            AlertEvent event,
            TimeInterval interval,
            TreeMap<Long, Candle> completedCandles) {
        Long signalTimestamp = event.getSignalCandleTimestamp();
        Candle signalCandle = signalTimestamp == null ? null : completedCandles.get(signalTimestamp);
        List<Candle> forwardCandles = signalTimestamp == null
                ? List.of()
                : completedCandles.tailMap(signalTimestamp, false).values().stream().toList();
        int availableForwardCandles = forwardCandles.size();
        boolean outcomeAvailable = validChartCandle(signalCandle)
                && availableForwardCandles >= MINIMUM_RESULT_CANDLES;
        Double bestDirectionalReturnPercent = null;
        Double windowEndDirectionalReturnPercent = null;
        String unavailableReason = null;

        if (outcomeAvailable) {
            double signalClose = signalCandle.getClosePrice();
            List<Candle> outcomeWindow = forwardCandles.subList(0, MINIMUM_RESULT_CANDLES);
            List<Double> returns = outcomeWindow.stream()
                    .map(candle -> directionalReturnPercent(
                            event.getTradeSignal(),
                            signalClose,
                            candle.getClosePrice()))
                    .toList();
            bestDirectionalReturnPercent = returns.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            windowEndDirectionalReturnPercent = returns.getLast();
        } else if (!validChartCandle(signalCandle)) {
            unavailableReason = "The completed signal candle is not available in the local cache.";
        } else {
            int remaining = MINIMUM_RESULT_CANDLES - availableForwardCandles;
            unavailableReason = "Waiting for " + remaining + " more completed "
                    + intervalLabel(interval) + " candle" + (remaining == 1 ? "" : "s") + ".";
        }

        ElliottSignalStage stage = event.getElliottSignalStage();
        SignalLifecycleStatus status = event.getLifecycleStatus();
        boolean sellSignal = event.getTradeSignal() == TradeSignal.SELL;
        return new ElliottWaveSignalCard(
                event.getId(),
                event.getElliottCycleKey(),
                stage,
                stage == ElliottSignalStage.CORRECTION_END
                        ? "ABC correction ending"
                        : "Wave V ending",
                event.getTradeSignal(),
                status,
                status.name().toLowerCase(Locale.ROOT),
                signalTimestamp,
                signalPeriodLabel(interval, signalTimestamp),
                outcomeAvailable,
                MINIMUM_RESULT_CANDLES,
                availableForwardCandles,
                bestDirectionalReturnPercent,
                windowEndDirectionalReturnPercent,
                sellSignal ? "Largest close-based loss avoided" : "Best close-based return",
                "Hypothetical hindsight over the first 10 completed " + intervalLabel(interval) + " candles.",
                unavailableReason,
                "/alerts/signals/" + event.getId()
        );
    }

    private double directionalReturnPercent(TradeSignal signal, double entryClose, double laterClose) {
        double priceMove = laterClose - entryClose;
        return (signal == TradeSignal.SELL ? -priceMove : priceMove) / entryClose * 100.0;
    }

    public SignalArchivePage getSignalArchive(User user,
                                               String requestedSort,
                                               String requestedDirection,
                                               int requestedPage) {
        String sortKey = normalizeArchiveSort(requestedSort);
        String directionKey = "asc".equalsIgnoreCase(requestedDirection) ? "asc" : "desc";
        Sort.Direction direction = "asc".equals(directionKey) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if (isReturnSort(sortKey)) {
            return getReturnSortedSignalArchive(user, sortKey, directionKey, direction, requestedPage);
        }
        Sort archiveSort = archiveSort(sortKey, direction);
        Page<AlertEvent> archive = alertEventRepository.findByAlertRule_User(
                user,
                PageRequest.of(Math.max(0, requestedPage), SIGNAL_ARCHIVE_PAGE_SIZE, archiveSort)
        );
        return new SignalArchivePage(
                archive.getContent().stream().map(this::toSignalArchiveEntry).toList(),
                archive.getNumber(),
                archive.getTotalPages(),
                archive.getTotalElements(),
                sortKey,
                directionKey,
                archive.hasPrevious(),
                archive.hasNext()
        );
    }

    private String normalizeArchiveSort(String requestedSort) {
        if (requestedSort == null) {
            return "date";
        }
        return switch (requestedSort.toLowerCase(Locale.ROOT)) {
            case "ticker", "interval", "confidence", "status", "best-return", "worst-return" ->
                    requestedSort.toLowerCase(Locale.ROOT);
            default -> "date";
        };
    }

    private boolean isReturnSort(String sortKey) {
        return "best-return".equals(sortKey) || "worst-return".equals(sortKey);
    }

    private Sort archiveSort(String sortKey, Sort.Direction direction) {
        if ("date".equals(sortKey)) {
            return Sort.by(direction, "sentAt").and(Sort.by(direction, "id"));
        }
        Sort primary = switch (sortKey) {
            case "ticker" -> Sort.by(direction, "alertRule.stockAsset.tickerSymbol");
            case "interval" -> Sort.by(direction, "alertRule.interval");
            case "confidence" -> Sort.by(new Sort.Order(direction, "confidenceScore").nullsLast());
            case "status" -> Sort.by(direction, "lifecycleStatus");
            default -> throw new IllegalArgumentException("Unsupported signal archive sort: " + sortKey);
        };
        return primary
                .and(Sort.by(Sort.Direction.DESC, "sentAt"))
                .and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private SignalArchivePage getReturnSortedSignalArchive(User user,
                                                            String sortKey,
                                                            String directionKey,
                                                            Sort.Direction direction,
                                                            int requestedPage) {
        List<SignalArchiveEntry> sortedSignals = alertEventRepository.findAllByAlertRule_User(user)
                .stream()
                .map(this::toSignalArchiveEntry)
                .sorted(returnComparator(sortKey, direction))
                .toList();
        int totalPages = (sortedSignals.size() + SIGNAL_ARCHIVE_PAGE_SIZE - 1) / SIGNAL_ARCHIVE_PAGE_SIZE;
        int lastPage = Math.max(0, totalPages - 1);
        int page = Math.min(Math.max(0, requestedPage), lastPage);
        int fromIndex = Math.min(page * SIGNAL_ARCHIVE_PAGE_SIZE, sortedSignals.size());
        int toIndex = Math.min(fromIndex + SIGNAL_ARCHIVE_PAGE_SIZE, sortedSignals.size());
        return new SignalArchivePage(
                sortedSignals.subList(fromIndex, toIndex),
                page,
                totalPages,
                sortedSignals.size(),
                sortKey,
                directionKey,
                page > 0,
                page + 1 < totalPages
        );
    }

    private Comparator<SignalArchiveEntry> returnComparator(String sortKey, Sort.Direction direction) {
        Comparator<Double> numericOrder = direction == Sort.Direction.ASC
                ? Comparator.nullsLast(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<SignalArchiveEntry> resultOrder = Comparator.comparing(
                entry -> "worst-return".equals(sortKey)
                        ? entry.worstDirectionalMovePercent()
                        : entry.bestDirectionalMovePercent(),
                numericOrder
        );
        return resultOrder
                .thenComparing(entry -> entry.signal().sentAt(), Comparator.reverseOrder())
                .thenComparing(entry -> entry.signal().id(), Comparator.reverseOrder());
    }

    private SignalArchiveEntry toSignalArchiveEntry(AlertEvent event) {
        LatestSignalView signal = toLatestSignalView(event);
        SignalResultExcursion result = calculateSignalResultExcursion(event);
        return new SignalArchiveEntry(
                signal,
                result.bestDirectionalMovePercent(),
                result.worstDirectionalMovePercent(),
                result.available(),
                result.windowLabel()
        );
    }

    private SignalResultExcursion calculateSignalResultExcursion(AlertEvent event) {
        if (!event.isLifecycleTracked()) {
            return SignalResultExcursion.unavailable("Lifecycle result not tracked");
        }
        if (event.getClosePrice() == null || !Double.isFinite(event.getClosePrice())
                || event.getClosePrice() <= 0.0 || event.getSignalCandleTimestamp() == null) {
            return SignalResultExcursion.unavailable("Recorded close unavailable");
        }
        AlertRule rule = event.getAlertRule();
        int window = event.getConfirmationWindowCandles();
        if (window <= 0) {
            return SignalResultExcursion.unavailable("Lifecycle result window unavailable");
        }
        long firstIncompleteTimestamp = candleCompletionService
                .firstIncompleteCandleTimestamp(rule.getInterval());
        List<Candle> resultCandles = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanAndTimestampLessThanOrderByTimestampAsc(
                        rule.getStockAsset().getTickerSymbol(),
                        toApiInterval(rule.getInterval()),
                        event.getSignalCandleTimestamp(),
                        firstIncompleteTimestamp,
                        PageRequest.of(0, window)
                )
                .stream()
                .filter(candle -> candle.getTimestamp() != null
                        && candle.getHighPrice() != null && Double.isFinite(candle.getHighPrice())
                        && candle.getLowPrice() != null && Double.isFinite(candle.getLowPrice()))
                .filter(candle -> event.getResolutionCandleTimestamp() == null
                        || candle.getTimestamp() <= event.getResolutionCandleTimestamp())
                .toList();
        if (resultCandles.isEmpty()) {
            return SignalResultExcursion.unavailable("Waiting for a completed result candle");
        }

        double entry = event.getClosePrice();
        double highestHigh = resultCandles.stream().mapToDouble(Candle::getHighPrice).max().orElse(entry);
        double lowestLow = resultCandles.stream().mapToDouble(Candle::getLowPrice).min().orElse(entry);
        double bestMove = event.getTradeSignal() == TradeSignal.SELL
                ? -percentMove(entry, lowestLow)
                : percentMove(entry, highestHigh);
        double worstMove = event.getTradeSignal() == TradeSignal.SELL
                ? -percentMove(entry, highestHigh)
                : percentMove(entry, lowestLow);
        String windowLabel = event.getResolutionCandleTimestamp() == null
                ? resultCandles.size() + " of " + window + " completed candles"
                : "Through resolution candle " + resultCandles.size() + " of " + window;
        return new SignalResultExcursion(bestMove, worstMove, true, windowLabel);
    }

    private double percentMove(double entry, double exit) {
        return ((exit - entry) / entry) * 100.0;
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

    @Transactional
    public SignalDetailView getSignalDetail(User user, Long alertEventId) {
        AlertEvent event = alertEventRepository.findOwnedByIdAndUser(alertEventId, user)
                .orElseThrow(() -> new IllegalArgumentException("Signal event not found."));
        event.markRead(LocalDateTime.now());
        AlertRule rule = event.getAlertRule();
        CandlestickHorizonGuidance.Guidance horizonGuidance = CandlestickHorizonGuidance
                .forSignal(rule.getPatternFamily(), rule.getInterval())
                .orElse(null);
        List<String> storedReasons = event.getConfidenceReasons();
        List<SignalReasonView> reasons = new ArrayList<>();
        for (int index = 0; index < storedReasons.size(); index++) {
            String reason = storedReasons.get(index);
            SignalScoreBreakdown.Section section = SignalScoreBreakdown.parse(
                    reason,
                    reasonCategory(reason),
                    event.getTradeSignal()
            );
            reasons.add(new SignalReasonView(
                    index + 1,
                    section.category(),
                    reason,
                    isCautionReason(reason),
                    section.scoreLabel(),
                    section.status(),
                    section.details().stream()
                            .map(detail -> new SignalReasonDetailView(
                                    detail.label(),
                                    detail.text(),
                                    detail.score()
                            ))
                            .toList(),
                    section.scored()
            ));
        }
        SignalChartView chart = toSignalChartView(event, rule);
        ObservedPriceOutcomeView observedOutcome = toObservedPriceOutcome(event, rule, chart);
        SignalResultsView results = toSignalResults(event, rule, chart);

        return new SignalDetailView(
                event.getId(),
                rule.getId(),
                rule.isActive(),
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
                event.getScoreVersion(),
                event.getElliottV1EligibilityScore(),
                setupExplanation(event.getConfidenceScore(), event.getScoreVersion()),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(rule.getInterval(), event.getSignalCandleTimestamp()),
                event.getClosePrice(),
                event.getSentAt(),
                chart,
                observedOutcome,
                results,
                toLifecycleView(event, rule.getInterval()),
                List.copyOf(reasons),
                !reasons.isEmpty()
        );
    }

    private SignalResultsView toSignalResults(AlertEvent event,
                                              AlertRule rule,
                                              SignalChartView chart) {
        double signalClose = event.getClosePrice() == null ? Double.NaN : event.getClosePrice();
        if (!chart.available() || event.getSignalCandleTimestamp() == null
                || !Double.isFinite(signalClose) || signalClose <= 0.0) {
            return SignalResultsView.unavailable(
                    "Results cannot be calculated because the completed signal candle is not available in the local cache.",
                    0
            );
        }

        List<SignalChartCandleView> candles = chart.candles();
        int signalIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == event.getSignalCandleTimestamp()) {
                signalIndex = index;
                break;
            }
        }
        if (signalIndex < 0) {
            return SignalResultsView.unavailable(
                    "Results cannot be calculated because the signal candle is missing from the local cache.",
                    0
            );
        }

        int availableForwardCandles = candles.size() - signalIndex - 1;
        if (availableForwardCandles < MINIMUM_RESULT_CANDLES) {
            String candleWord = availableForwardCandles == 1 ? "candle is" : "candles are";
            return SignalResultsView.unavailable(
                    "Results are not available yet. At least " + MINIMUM_RESULT_CANDLES
                            + " completed candles after the signal are required to provide a meaningful outcome window. "
                            + availableForwardCandles + " completed cached " + candleWord + " currently available.",
                    availableForwardCandles
            );
        }

        List<SignalResultPointView> points = new ArrayList<>(availableForwardCandles + 1);
        points.add(new SignalResultPointView(
                0,
                event.getSignalCandleTimestamp(),
                signalPeriodLabel(rule.getInterval(), event.getSignalCandleTimestamp()),
                signalClose,
                0.0,
                0.0
        ));
        for (int offset = 1; offset <= availableForwardCandles; offset++) {
            SignalChartCandleView candle = candles.get(signalIndex + offset);
            double rawPriceDifference = candle.close() - signalClose;
            double directionalPriceDifference = event.getTradeSignal() == TradeSignal.SELL
                    ? -rawPriceDifference
                    : rawPriceDifference;
            points.add(new SignalResultPointView(
                    offset,
                    candle.timestamp(),
                    signalPeriodLabel(rule.getInterval(), candle.timestamp()),
                    candle.close(),
                    directionalPriceDifference / signalClose * 100.0,
                    directionalPriceDifference
            ));
        }

        boolean sellSignal = event.getTradeSignal() == TradeSignal.SELL;
        return new SignalResultsView(
                true,
                null,
                MINIMUM_RESULT_CANDLES,
                availableForwardCandles,
                signalClose,
                event.getSignalCandleTimestamp(),
                event.getTradeSignal(),
                sellSignal ? "Decline avoided / rise missed" : "Gain / loss",
                sellSignal ? "Best re-entry close" : "Best sell close",
                List.copyOf(points)
        );
    }

    private ObservedPriceOutcomeView toObservedPriceOutcome(AlertEvent event,
                                                            AlertRule rule,
                                                            SignalChartView chart) {
        if (normalizeFamily(rule.getPatternFamily()) != AlertPatternFamily.CANDLESTICK) {
            return ObservedPriceOutcomeView.unavailable(
                    "The directional outcome model currently applies to named candlestick patterns."
            );
        }
        if (!chart.available() || event.getClosePrice() == null
                || !Double.isFinite(event.getClosePrice()) || event.getClosePrice() <= 0.0) {
            return ObservedPriceOutcomeView.unavailable(
                    "Completed candle history is not available for this outcome calculation."
            );
        }
        ObservedOutcomeProfile profile = observedOutcomeProfile(rule.getInterval());
        if (profile == null) {
            return ObservedPriceOutcomeView.unavailable(
                    "No observed-outcome profile is configured for this interval."
            );
        }

        List<SignalChartCandleView> candles = chart.candles();
        int signalIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == event.getSignalCandleTimestamp()) {
                signalIndex = index;
                break;
            }
        }
        if (signalIndex < 0) {
            return ObservedPriceOutcomeView.unavailable("The signal candle is missing from the cached chart.");
        }

        int patternStartIndex = Math.max(0, signalIndex - chart.patternCandleCount() + 1);
        List<SignalChartCandleView> patternCandles = candles.subList(patternStartIndex, signalIndex + 1);
        double patternHigh = patternCandles.stream().mapToDouble(SignalChartCandleView::high).max()
                .orElse(event.getClosePrice());
        double patternLow = patternCandles.stream().mapToDouble(SignalChartCandleView::low).min()
                .orElse(event.getClosePrice());
        int availableForwardCandles = candles.size() - signalIndex - 1;
        if (availableForwardCandles < profile.forwardCandles()) {
            int remaining = profile.forwardCandles() - availableForwardCandles;
            return new ObservedPriceOutcomeView(
                    true,
                    false,
                    null,
                    "Awaiting outcome",
                    "pending",
                    "Needs " + remaining + " more completed " + intervalLabel(rule.getInterval())
                            + (remaining == 1 ? " candle" : " candles")
                            + " before the " + profile.horizonLabel() + " result is known.",
                    profile.horizonLabel(),
                    profile.minimumMovePercent(),
                    patternHigh,
                    patternLow,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Outcome pending"
            );
        }

        int evaluationIndex = signalIndex + profile.forwardCandles();
        SignalChartCandleView evaluationCandle = candles.get(evaluationIndex);
        List<SignalChartCandleView> futureCandles = candles.subList(signalIndex + 1, evaluationIndex + 1);
        double entryClose = event.getClosePrice();
        double rawReturn = percentMove(entryClose, evaluationCandle.close());
        double directionalReturn = event.getTradeSignal() == TradeSignal.SELL ? -rawReturn : rawReturn;
        double highestHigh = futureCandles.stream().mapToDouble(SignalChartCandleView::high).max()
                .orElse(evaluationCandle.close());
        double lowestLow = futureCandles.stream().mapToDouble(SignalChartCandleView::low).min()
                .orElse(evaluationCandle.close());
        double bestMove = event.getTradeSignal() == TradeSignal.SELL
                ? -percentMove(entryClose, lowestLow)
                : percentMove(entryClose, highestHigh);
        double worstMove = event.getTradeSignal() == TradeSignal.SELL
                ? -percentMove(entryClose, highestHigh)
                : percentMove(entryClose, lowestLow);
        String statusLabel;
        String statusClass;
        if (directionalReturn >= profile.minimumMovePercent()) {
            statusLabel = "Successful";
            statusClass = "success";
        } else if (directionalReturn <= -profile.minimumMovePercent()) {
            statusLabel = "Unsuccessful";
            statusClass = "failure";
        } else {
            statusLabel = "Inconclusive";
            statusClass = "inconclusive";
        }
        String summary = String.format(
                Locale.ROOT,
                "%+.2f%% directional return at the %s close. Success requires at least %.1f%% in the expected direction.",
                directionalReturn,
                profile.horizonLabel(),
                profile.minimumMovePercent()
        );
        String impactLabel = event.getTradeSignal() == TradeSignal.BUY
                ? directionalReturn >= 0.0 ? "Potential gain" : "Potential loss"
                : directionalReturn >= 0.0 ? "Potential loss avoided" : "Price rose instead";
        return new ObservedPriceOutcomeView(
                true,
                true,
                null,
                statusLabel,
                statusClass,
                summary,
                profile.horizonLabel(),
                profile.minimumMovePercent(),
                patternHigh,
                patternLow,
                evaluationCandle.timestamp(),
                signalPeriodLabel(rule.getInterval(), evaluationCandle.timestamp()),
                evaluationCandle.close(),
                directionalReturn,
                bestMove,
                worstMove,
                impactLabel
        );
    }

    private ObservedOutcomeProfile observedOutcomeProfile(TimeInterval interval) {
        return switch (interval) {
            case DAILY -> new ObservedOutcomeProfile(10, "10-session horizon", 3.0);
            case WEEKLY -> new ObservedOutcomeProfile(4, "4-week horizon", 4.0);
            case MONTHLY -> new ObservedOutcomeProfile(3, "3-month horizon", 6.0);
            default -> null;
        };
    }

    private SignalChartView toSignalChartView(AlertEvent event, AlertRule rule) {
        Long signalTimestamp = event.getSignalCandleTimestamp();
        if (signalTimestamp == null) {
            return SignalChartView.unavailable("The signal candle timestamp was not recorded.");
        }

        String symbol = rule.getStockAsset().getTickerSymbol();
        String interval = toApiInterval(rule.getInterval());
        long firstIncompleteTimestamp = candleCompletionService.firstIncompleteCandleTimestamp(rule.getInterval());
        TreeMap<Long, Candle> orderedCandles = new TreeMap<>();
        candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval)
                .stream()
                .filter(this::validChartCandle)
                .filter(candle -> candle.getTimestamp() < firstIncompleteTimestamp)
                .forEach(candle -> orderedCandles.put(candle.getTimestamp(), candle));
        Candle signalCandle = orderedCandles.get(signalTimestamp);
        if (!validChartCandle(signalCandle)) {
            return SignalChartView.unavailable(
                    "The original OHLC candle is not present in the local cache, so the chart cannot be reconstructed safely."
            );
        }

        List<Candle> candles = List.copyOf(orderedCandles.values());
        int signalIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp().equals(signalTimestamp)) {
                signalIndex = index;
                break;
            }
        }
        if (signalIndex < 0) {
            return SignalChartView.unavailable("The cached signal candle could not be located.");
        }

        int patternCandleCount = patternCandleCount(event.getPattern());
        int patternStartIndex = Math.max(0, signalIndex - patternCandleCount + 1);
        int trendStartIndex = Math.max(0, patternStartIndex - SIGNAL_CHART_TREND_CANDLES);
        long patternStartTimestamp = candles.get(patternStartIndex).getTimestamp();
        Long trendStartTimestamp = patternStartIndex > 0
                ? candles.get(trendStartIndex).getTimestamp()
                : null;
        String trendLabel = normalizeFamily(rule.getPatternFamily()) == AlertPatternFamily.ELLIOTT_WAVE
                ? "Wave structure context"
                : event.getTradeSignal() == TradeSignal.SELL ? "Required uptrend" : "Required downtrend";
        String summary = normalizeFamily(rule.getPatternFamily()) == AlertPatternFamily.ELLIOTT_WAVE
                ? "The marker locates the recorded Elliott detection inside the complete cached interval history."
                : "The highlighted region and amber guide mark the five-candle prior-trend window used by the detector; labeled arrows identify every candle that formed the pattern.";

        ElliottWaveChartView elliottWave = toSignalElliottWaveView(event, rule, candles, signalIndex);
        if (normalizeFamily(rule.getPatternFamily()) == AlertPatternFamily.ELLIOTT_WAVE) {
            summary = elliottWave == null
                    ? "The cached history no longer reconstructs the Elliott pivots recorded for this signal."
                    : "The recorded Elliott structure is drawn pivot to pivot using the same motive and corrective overlay as the stock chart.";
        }

        List<SignalChartCandleView> chartCandles = candles.stream()
                .map(candle -> new SignalChartCandleView(
                        candle.getTimestamp(),
                        candle.getOpenPrice(),
                        candle.getHighPrice(),
                        candle.getLowPrice(),
                        candle.getClosePrice()
                ))
                .toList();
        return new SignalChartView(
                true,
                null,
                chartCandles,
                trendStartTimestamp,
                patternStartTimestamp,
                signalTimestamp,
                Math.min(patternCandleCount, signalIndex + 1),
                trendLabel,
                summary,
                elliottWave
        );
    }

    private ElliottWaveChartView toSignalElliottWaveView(AlertEvent event,
                                                         AlertRule rule,
                                                         List<Candle> candles,
                                                         int signalIndex) {
        if (normalizeFamily(rule.getPatternFamily()) != AlertPatternFamily.ELLIOTT_WAVE
                || event.getPattern() == null || signalIndex < 0) {
            return null;
        }
        List<Candle> detectionHistory = List.copyOf(candles.subList(0, signalIndex + 1));
        List<EnrichedCandle> enriched = enrichmentService.enrichForElliott(
                detectionHistory,
                detectionHistory.size(),
                rule.getInterval()
        );
        ElliottWaveDetectionService.ElliottWaveStructure structure = elliottWaveDetectionService
                .findHistoricalWaveStructures(enriched)
                .stream()
                .filter(candidate -> matchesRecordedElliottPattern(event.getPattern(), candidate))
                .filter(candidate -> candidate.confirmationTimestamp() != null
                        && candidate.confirmationTimestamp() <= event.getSignalCandleTimestamp())
                .max(Comparator.comparingLong(
                                (ElliottWaveDetectionService.ElliottWaveStructure candidate) ->
                                        candidate.confirmationTimestamp())
                        .thenComparingInt(ElliottWaveDetectionService.ElliottWaveStructure::qualityScore)
                        .thenComparingLong(candidate -> elliottStructureSpan(candidate.points())))
                .orElse(null);
        if (structure == null) {
            return null;
        }
        return new ElliottWaveChartView(
                structure.direction(),
                structure.correctionComplete(),
                structure.points().stream()
                        .map(point -> new ElliottWaveChartPointView(
                                rule.getInterval() == TimeInterval.WEEKLY
                                        ? point.label().toLowerCase(Locale.ROOT)
                                        : point.label(),
                                point.timestamp(),
                                point.price(),
                                point.pivotType()
                        ))
                        .toList(),
                structure.confirmationTimestamp(),
                structure.waveTwoRetracement(),
                structure.waveFourRetracement(),
                structure.impulseVariant(),
                structure.correctionVariant()
        );
    }

    private boolean matchesRecordedElliottPattern(
            CandlePattern pattern,
            ElliottWaveDetectionService.ElliottWaveStructure structure) {
        String patternName = pattern.name();
        String expectedDirection = patternName.contains("BEARISH") ? "BEARISH" : "BULLISH";
        boolean expectedCorrection = patternName.endsWith("CORRECTION");
        if (!expectedDirection.equals(structure.direction())
                || expectedCorrection != structure.correctionComplete()) {
            return false;
        }
        if (patternName.contains("TRUNCATED")) {
            return structure.impulseVariant() == ElliottWaveDetectionService.ImpulseVariant.TRUNCATED_FIFTH;
        }
        if (patternName.contains("EXPANDED_FLAT")) {
            return structure.correctionVariant() == ElliottWaveDetectionService.CorrectionVariant.EXPANDED_FLAT;
        }
        if (patternName.contains("RUNNING_FLAT")) {
            return structure.correctionVariant() == ElliottWaveDetectionService.CorrectionVariant.RUNNING_FLAT;
        }
        if (expectedCorrection) {
            return structure.correctionVariant() == ElliottWaveDetectionService.CorrectionVariant.STANDARD;
        }
        return structure.impulseVariant() == ElliottWaveDetectionService.ImpulseVariant.STANDARD;
    }

    private long elliottStructureSpan(List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        return points.isEmpty() ? 0L : points.getLast().timestamp() - points.getFirst().timestamp();
    }

    private boolean validChartCandle(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null && Double.isFinite(candle.getOpenPrice())
                && candle.getHighPrice() != null && Double.isFinite(candle.getHighPrice())
                && candle.getLowPrice() != null && Double.isFinite(candle.getLowPrice())
                && candle.getClosePrice() != null && Double.isFinite(candle.getClosePrice());
    }

    private int patternCandleCount(CandlePattern pattern) {
        if (pattern == null) {
            return 1;
        }
        return switch (pattern) {
            case MORNING_STAR, EVENING_STAR, THREE_WHITE_SOLDIERS, THREE_BLACK_CROWS -> 3;
            case BULLISH_ENGULFING, BEARISH_ENGULFING, PIERCING_LINE, DARK_CLOUD_COVER,
                    BULLISH_HARAMI, BEARISH_HARAMI -> 2;
            default -> 1;
        };
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
        long unreadSignalCount = rules.stream()
                .mapToLong(alertEventRepository::countByAlertRuleAndReadAtIsNull)
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
                unreadSignalCount
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
                event.getScoreVersion(),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(rule.getInterval(), event.getSignalCandleTimestamp()),
                event.getSentAt(),
                event.isRead(),
                toLifecycleView(event, rule.getInterval())
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
        int requiredHistory = family == AlertPatternFamily.ELLIOTT_WAVE
                ? enrichmentService.requiredElliottInputCandles(signalCandleCount, interval)
                : enrichmentService.requiredInputCandles(signalCandleCount, interval);
        long firstIncompleteTimestamp =
                candleCompletionService.firstIncompleteCandleTimestamp(interval);
        List<Candle> latest = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol,
                apiInterval,
                firstIncompleteTimestamp,
                PageRequest.of(0, requiredHistory)
        );
        List<EnrichedCandle> enrichedCandles = family == AlertPatternFamily.ELLIOTT_WAVE
                ? enrichmentService.enrichForElliott(latest, signalCandleCount, interval)
                : enrichmentService.enrich(latest, signalCandleCount, interval);
        List<EnrichedCandle> elliottCandles = family == AlertPatternFamily.ELLIOTT_WAVE
                ? enrichmentService.enrichForElliott(latest, signalCandleCount, interval)
                : List.of();
        List<DetectedSignal> detectedSignals = detectSignals(
                enrichedCandles,
                elliottCandles,
                interval,
                family
        );
        List<Map<String, Object>> detected = detectedSignals.stream()
                .filter(detectedSignal -> signalFamily(detectedSignal) == family)
                .map(detectedSignal -> Map.<String, Object>of(
                        "pattern", detectedSignal.pattern().name(),
                        "patternFamily", family.name(),
                        "signal", detectedSignal.tradeSignal().name(),
                        "strength", detectedSignal.strength().name(),
                        "setupScore", detectedSignal.setupScore(),
                        "scoreVersion", scoreVersion(detectedSignal),
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
        response.put("firstIncompleteTimestamp", firstIncompleteTimestamp);
        if (!latest.isEmpty()) {
            Candle newest = latest.get(0);
            response.put("latestTimestamp", newest.getTimestamp());
            response.put("latestCompletedTimestamp", newest.getTimestamp());
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
                                               List<EnrichedCandle> elliottCandles,
                                               TimeInterval interval,
                                               AlertPatternFamily family) {
        if (family == AlertPatternFamily.CANDLESTICK) {
            return detectionService.detectAlertSignals(enrichedCandles);
        }
        if (!isElliottEnabled(interval)) {
            return List.of();
        }
        return elliottWaveDetectionService.detectAlertSignals(elliottCandles).stream()
                .filter(this::isActionableElliottTurningPoint)
                .toList();
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

    private String scoreVersion(DetectedSignal signal) {
        return isElliottPattern(signal.pattern())
                ? ElliottWaveDetectionService.SETUP_SCORE_VERSION
                : CandlePatternDetectionService.SETUP_SCORE_VERSION;
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
                event.getScoreVersion(),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(interval, event.getSignalCandleTimestamp()),
                event.getClosePrice(),
                event.getSentAt(),
                event.isRead(),
                toLifecycleView(event, interval)
        );
    }

    private SignalLifecycleView toLifecycleView(AlertEvent event, TimeInterval interval) {
        SignalLifecycleStatus status = event.getLifecycleStatus();
        boolean tracked = event.isLifecycleTracked();
        String resolutionPeriod = event.getResolutionCandleTimestamp() == null
                ? null
                : signalPeriodLabel(interval, event.getResolutionCandleTimestamp());
        String boundaryDirection = event.getTradeSignal() == TradeSignal.BUY ? "above" : "below";
        String invalidationDirection = event.getTradeSignal() == TradeSignal.BUY ? "below" : "above";
        boolean elliottSignal = event.isElliottSignal();
        String summary;
        if (!tracked) {
            summary = "Follow-up lifecycle tracking was not recorded for this signal.";
        } else {
            summary = switch (status) {
                case DETECTED -> elliottSignal
                        ? String.format(
                                Locale.ROOT,
                                "Waiting for a completed candle to close %s %.4f. Wave V/C extensions revise this same cycle silently; the %d-candle window restarts from the latest endpoint.",
                                boundaryDirection,
                                event.getConfirmationTriggerPrice(),
                                event.getConfirmationWindowCandles())
                        : String.format(
                                Locale.ROOT,
                                "Waiting for a completed candle to close %s %.4f. The setup expires after %d candles unless it confirms or invalidates first.",
                                boundaryDirection,
                                event.getConfirmationTriggerPrice(),
                                event.getConfirmationWindowCandles());
                case CONFIRMED -> String.format(
                        Locale.ROOT,
                        "Confirmed on %s when candle %d closed at %.4f, %s the %.4f trigger.",
                        resolutionPeriod,
                        event.getResolutionCandleOffset(),
                        event.getResolutionClosePrice(),
                        boundaryDirection,
                        event.getConfirmationTriggerPrice()
                );
                case INVALIDATED -> elliottSignal
                        ? String.format(
                                Locale.ROOT,
                                "Invalidated on %s because %s",
                                resolutionPeriod,
                                event.getLifecycleResolutionReason() == null
                                        ? "the stored wave structure stopped satisfying its hard rules."
                                        : event.getLifecycleResolutionReason())
                        : String.format(
                                Locale.ROOT,
                                "Invalidated on %s when candle %d closed at %.4f, %s the %.4f boundary.",
                                resolutionPeriod,
                                event.getResolutionCandleOffset(),
                                event.getResolutionClosePrice(),
                                invalidationDirection,
                                event.getInvalidationPrice());
                case EXPIRED -> String.format(
                        Locale.ROOT,
                        elliottSignal
                                ? "Expired after %d completed candles from the latest endpoint revision without structural confirmation."
                                : "Expired after %d completed candles without a close beyond either lifecycle boundary.",
                        event.getConfirmationWindowCandles()
                );
            };
        }
        return new SignalLifecycleView(
                status,
                lifecycleLabel(status),
                status.name().toLowerCase(Locale.ROOT),
                tracked,
                status != SignalLifecycleStatus.DETECTED,
                summary,
                event.getPatternHigh(),
                event.getPatternLow(),
                event.getConfirmationTriggerPrice(),
                event.getInvalidationPrice(),
                event.getConfirmationWindowCandles(),
                event.getResolutionCandleOffset(),
                resolutionPeriod,
                event.getResolutionClosePrice(),
                event.getLifecycleUpdatedAt(),
                event.getFollowUpSentAt(),
                event.getElliottEndpointPrice(),
                event.getElliottSignalStage(),
                event.getLifecycleResolutionReason()
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

    private String setupStrengthLabel(SignalStength strength, Integer setupScore) {
        if (strength != null) {
            return switch (strength) {
                case WEAK_IGNORE -> "Minimal confluence";
                case HIGH_CONFIDENCE -> "High confluence";
                case MEDIUM_CONFIDENCE -> "Moderate confluence";
                case LOW_CONFIDENCE -> "Low confluence";
            };
        }
        return switch (setupBand(setupScore)) {
            case "high" -> "High confluence";
            case "medium" -> "Moderate confluence";
            case "low" -> "Low confluence";
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

    private String setupExplanation(Integer setupScore, String scoreVersion) {
        if (setupScore == null) {
            return "This signal does not have a recorded setup score.";
        }
        String validationNote = CandlePatternDetectionService.SETUP_SCORE_VERSION.equals(scoreVersion)
                ? " This V4 score is experimental and has not demonstrated stable out-of-sample predictive ordering."
                : "";
        if (setupScore >= 85) {
            return "High heuristic confluence (85-100): broad alignment across the recorded technical evidence."
                    + validationNote;
        }
        if (setupScore >= 75) {
            return "Moderate heuristic confluence (75-84): several factors align, with some mixed or unavailable evidence."
                    + validationNote;
        }
        return "Low heuristic confluence (below 75): the pattern is valid, but supporting confluence is limited."
                + validationNote;
    }

    private String reasonCategory(String reason) {
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("pattern quality")) {
            return "Pattern quality";
        }
        if (normalized.startsWith("trend indicators")
                || normalized.startsWith("higher-timeframe trend")) {
            return "Trend evidence";
        }
        if (normalized.startsWith("bollinger volatility/location")) {
            return "Bollinger";
        }
        if (normalized.startsWith("support/resistance")) {
            return "Support/resistance";
        }
        if (normalized.startsWith("volume participation")) {
            return "Volume";
        }
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
            long unreadSignalCount
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
            String scoreVersion,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            java.time.LocalDateTime sentAt,
            boolean hasBeenRead,
            SignalLifecycleView lifecycle
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
            String scoreVersion,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            Double closePrice,
            java.time.LocalDateTime sentAt,
            boolean hasBeenRead,
            SignalLifecycleView lifecycle
    ) {
        public Integer confidenceScore() {
            return setupScore;
        }
    }

    public record SignalArchivePage(
            List<SignalArchiveEntry> signals,
            int page,
            int totalPages,
            long totalSignals,
            String sort,
            String direction,
            boolean hasPrevious,
            boolean hasNext
    ) {
        public int displayPage() {
            return totalPages == 0 ? 0 : page + 1;
        }

        public String groupKey(SignalArchiveEntry entry) {
            return entry.groupKey(sort);
        }

        public String groupLabel(SignalArchiveEntry entry) {
            return entry.groupLabel(sort);
        }

        public String groupDetail(SignalArchiveEntry entry) {
            return entry.groupDetail(sort);
        }
    }

    public record SignalArchiveEntry(
            LatestSignalView signal,
            Double bestDirectionalMovePercent,
            Double worstDirectionalMovePercent,
            boolean resultAvailable,
            String resultWindowLabel
    ) {
        private String groupKey(String sortKey) {
            return switch (sortKey) {
                case "ticker" -> signal.symbol();
                case "interval" -> signal.interval().name();
                case "confidence" -> confidenceGroupKey();
                case "status" -> signal.lifecycle().status().name();
                case "best-return" -> returnGroupKey(bestDirectionalMovePercent);
                case "worst-return" -> returnGroupKey(worstDirectionalMovePercent);
                default -> signal.sentAt().toLocalDate().toString();
            };
        }

        private String groupLabel(String sortKey) {
            return switch (sortKey) {
                case "ticker" -> signal.symbol();
                case "interval" -> signal.intervalLabel();
                case "confidence" -> switch (confidenceGroupKey()) {
                    case "high" -> "High score · 85–100";
                    case "medium" -> "Moderate score · 75–84";
                    case "low" -> "Lower score · 0–74";
                    default -> "Score unavailable";
                };
                case "status" -> signal.lifecycle().label();
                case "best-return" -> returnGroupLabel(bestDirectionalMovePercent);
                case "worst-return" -> returnGroupLabel(worstDirectionalMovePercent);
                default -> signal.sentAt().format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
            };
        }

        private String groupDetail(String sortKey) {
            return switch (sortKey) {
                case "ticker" -> signal.companyName();
                case "interval" -> switch (signal.interval()) {
                    case DAILY -> "Daily signals";
                    case WEEKLY -> "Weekly signals";
                    case MONTHLY -> "Monthly signals";
                    default -> "Signals for this interval";
                };
                case "confidence" -> "Rows sorted by exact confidence score";
                case "status" -> "Signal lifecycle status";
                case "best-return" -> "Best directional result from the signal close";
                case "worst-return" -> "Worst directional result from the signal close";
                default -> null;
            };
        }

        private String confidenceGroupKey() {
            return signal.setupBand() == null ? "unrated" : signal.setupBand();
        }

        private String returnGroupKey(Double value) {
            if (value == null) {
                return "unavailable";
            }
            if (value > 0.0) {
                return "positive";
            }
            return value < 0.0 ? "negative" : "flat";
        }

        private String returnGroupLabel(Double value) {
            return switch (returnGroupKey(value)) {
                case "positive" -> "Positive return";
                case "negative" -> "Negative return";
                case "flat" -> "Flat return";
                default -> "Return unavailable";
            };
        }
    }

    private record SignalResultExcursion(
            Double bestDirectionalMovePercent,
            Double worstDirectionalMovePercent,
            boolean available,
            String windowLabel
    ) {
        private static SignalResultExcursion unavailable(String reason) {
            return new SignalResultExcursion(null, null, false, reason);
        }
    }

    private record ObservedOutcomeProfile(
            int forwardCandles,
            String horizonLabel,
            double minimumMovePercent
    ) {
    }

    public record SignalDetailView(
            Long id,
            Long alertRuleId,
            boolean alertRuleActive,
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
            String scoreVersion,
            Integer elliottV1EligibilityScore,
            String setupExplanation,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            Double closePrice,
            java.time.LocalDateTime sentAt,
            SignalChartView chart,
            ObservedPriceOutcomeView observedOutcome,
            SignalResultsView results,
            SignalLifecycleView lifecycle,
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

    public record SignalChartView(
            boolean available,
            String unavailableReason,
            List<SignalChartCandleView> candles,
            Long trendStartTimestamp,
            Long patternStartTimestamp,
            Long signalTimestamp,
            int patternCandleCount,
            String trendLabel,
            String summary,
            ElliottWaveChartView elliottWave
    ) {
        private static SignalChartView unavailable(String reason) {
            return new SignalChartView(false, reason, List.of(), null, null, null, 0, null, null, null);
        }

        public SignalChartView {
            candles = List.copyOf(candles);
        }
    }

    public record SignalChartCandleView(
            long timestamp,
            double open,
            double high,
            double low,
            double close
    ) {
    }

    public record SignalResultsView(
            boolean available,
            String unavailableReason,
            int minimumForwardCandles,
            int availableForwardCandles,
            Double signalClose,
            Long signalTimestamp,
            TradeSignal tradeSignal,
            String outcomeLabel,
            String bestActionLabel,
            List<SignalResultPointView> points
    ) {
        private static SignalResultsView unavailable(String reason, int availableForwardCandles) {
            return new SignalResultsView(
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

        public SignalResultsView {
            points = List.copyOf(points);
        }
    }

    public record SignalResultPointView(
            int candleNumber,
            long timestamp,
            String periodLabel,
            double close,
            double directionalReturnPercent,
            double directionalPriceDifference
    ) {
    }

    public record ElliottWaveSignalCard(
            Long eventId,
            String cycleKey,
            ElliottSignalStage stage,
            String stageLabel,
            TradeSignal tradeSignal,
            SignalLifecycleStatus status,
            String statusClass,
            Long signalTimestamp,
            String signalPeriodLabel,
            boolean outcomeAvailable,
            int requiredForwardCandles,
            int availableForwardCandles,
            Double bestDirectionalReturnPercent,
            Double windowEndDirectionalReturnPercent,
            String outcomeLabel,
            String outcomeNote,
            String unavailableReason,
            String detailUrl
    ) {
    }

    public record ElliottWaveChartView(
            String direction,
            boolean correctionComplete,
            List<ElliottWaveChartPointView> points,
            Long confirmationTimestamp,
            double waveTwoRetracement,
            double waveFourRetracement,
            ElliottWaveDetectionService.ImpulseVariant impulseVariant,
            ElliottWaveDetectionService.CorrectionVariant correctionVariant
    ) {
        public ElliottWaveChartView {
            points = List.copyOf(points);
        }
    }

    public record ElliottWaveChartPointView(
            String label,
            Long timestamp,
            double price,
            String pivotType
    ) {
    }

    public record ObservedPriceOutcomeView(
            boolean tracked,
            boolean outcomeAvailable,
            String unavailableReason,
            String statusLabel,
            String statusClass,
            String summary,
            String evaluationHorizonLabel,
            double successThresholdPercent,
            Double patternHigh,
            Double patternLow,
            Long evaluationTimestamp,
            String evaluationPeriodLabel,
            Double evaluationClose,
            Double directionalReturnPercent,
            Double bestDirectionalMovePercent,
            Double worstDirectionalMovePercent,
            String impactLabel
    ) {
        private static ObservedPriceOutcomeView unavailable(String reason) {
            return new ObservedPriceOutcomeView(
                    false, false, reason, "Unavailable", "pending", reason, null, 0.0,
                    null, null, null, null, null, null, null, null, "Outcome unavailable"
            );
        }
    }

    public record SignalLifecycleView(
            SignalLifecycleStatus status,
            String label,
            String cssClass,
            boolean tracked,
            boolean terminal,
            String summary,
            Double patternHigh,
            Double patternLow,
            Double confirmationTriggerPrice,
            Double invalidationPrice,
            Integer confirmationWindowCandles,
            Integer resolutionCandleOffset,
            String resolutionPeriodLabel,
            Double resolutionClosePrice,
            java.time.LocalDateTime updatedAt,
            java.time.LocalDateTime followUpSentAt,
            Double elliottEndpointPrice,
            org.example.stockwatch247.model.enums.ElliottSignalStage elliottSignalStage,
            String resolutionReason
    ) {
    }

    public record SignalReasonView(
            int order,
            String category,
            String text,
            boolean caution,
            String scoreLabel,
            String statusLabel,
            List<SignalReasonDetailView> details,
            boolean scored
    ) {
        public SignalReasonView {
            details = List.copyOf(details);
        }
    }

    public record SignalReasonDetailView(
            String label,
            String text,
            String scoreLabel
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
