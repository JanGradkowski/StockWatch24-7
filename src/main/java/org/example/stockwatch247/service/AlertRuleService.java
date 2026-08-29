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
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class AlertRuleService {
    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int HIGHER_INTERVAL_SIGNAL_CANDLES = 100;
    private static final int DASHBOARD_LATEST_SIGNAL_LIMIT = 8;
    private static final int SIGNAL_ARCHIVE_PAGE_SIZE = 50;
    private static final int MAX_ALERT_CHANGES_PER_REQUEST = 24;
    private static final int MINIMUM_RESULT_CANDLES = 10;
    private static final List<TimeInterval> TEMPORARY_BULK_INTERVALS =
            List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY);

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
    private final boolean dailyElliottEnabled;
    private final boolean weeklyElliottEnabled;
    private final boolean monthlyElliottEnabled;
    private final int maxTrackedStocksPerUser;
    private final int maxGlobalTrackedStocks;
    private final AnalysisPreferencesService preferencesService;
    private final CandlestickPatternPreferencesService patternPreferencesService;
    private ElliottWavePreferencesService elliottWavePreferencesService;
    private ElliottTradePlanService elliottTradePlanService;
    private HarmonicPatternDetectionService harmonicPatternDetectionService;
    private HarmonicPatternPreferencesService harmonicPatternPreferencesService;

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
                             @Value("${alerts.elliott.daily-enabled:true}") boolean dailyElliottEnabled,
                             @Value("${alerts.elliott.weekly-enabled:true}") boolean weeklyElliottEnabled,
                             @Value("${alerts.elliott.monthly-enabled:true}") boolean monthlyElliottEnabled,
                             @Value("${alerts.max-tracked-stocks-per-user:300}") int maxTrackedStocksPerUser,
                             @Value("${alerts.max-global-tracked-stocks:500}") int maxGlobalTrackedStocks,
                             AnalysisPreferencesService preferencesService,
                             CandlestickPatternPreferencesService patternPreferencesService) {
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
        this.dailyElliottEnabled = dailyElliottEnabled;
        this.weeklyElliottEnabled = weeklyElliottEnabled;
        this.monthlyElliottEnabled = monthlyElliottEnabled;
        this.maxTrackedStocksPerUser = Math.max(1, maxTrackedStocksPerUser);
        this.maxGlobalTrackedStocks = Math.max(this.maxTrackedStocksPerUser, maxGlobalTrackedStocks);
        this.preferencesService = preferencesService;
        this.patternPreferencesService = patternPreferencesService;
    }

    @Autowired(required = false)
    void configureElliottWavePreferences(ElliottWavePreferencesService elliottWavePreferencesService) {
        this.elliottWavePreferencesService = elliottWavePreferencesService;
    }

    @Autowired(required = false)
    void configureElliottTradePlans(ElliottTradePlanService elliottTradePlanService) {
        this.elliottTradePlanService = elliottTradePlanService;
    }

    @Autowired(required = false)
    void configureHarmonicPatternPreferences(
            HarmonicPatternDetectionService harmonicPatternDetectionService,
            HarmonicPatternPreferencesService harmonicPatternPreferencesService) {
        this.harmonicPatternDetectionService = harmonicPatternDetectionService;
        this.harmonicPatternPreferencesService = harmonicPatternPreferencesService;
    }

    AlertRuleService(AlertRuleRepository alertRuleRepository,
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
                     boolean weeklyElliottEnabled,
                     boolean monthlyElliottEnabled,
                     int maxTrackedStocksPerUser,
                     int maxGlobalTrackedStocks) {
        this(alertRuleRepository, alertEventRepository, stockAssetRepository, candleRepository,
                twelveDataService, jdbcTemplate, marketDataService, candleCompletionService,
                enrichmentService, detectionService, elliottWaveDetectionService, true, weeklyElliottEnabled,
                monthlyElliottEnabled, maxTrackedStocksPerUser, maxGlobalTrackedStocks, null, null);
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

    @Transactional
    public int unfollowAllTechnicalRules(User user, String rawSymbol) {
        if (user == null) {
            throw new IllegalArgumentException("User is required.");
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset stockAsset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        if (stockAsset == null) {
            return 0;
        }
        List<AlertRule> activeRules = alertRuleRepository
                .findByUserAndStockAssetAndIsActiveTrue(user, stockAsset);
        activeRules.forEach(rule -> rule.setActive(false));
        if (!activeRules.isEmpty()) {
            alertRuleRepository.saveAll(activeRules);
        }
        return activeRules.size();
    }

    @Transactional
    public int unfollowSelectedTechnicalRules(User user, String rawSymbol, List<Long> rawRuleIds) {
        if (user == null) {
            throw new IllegalArgumentException("User is required.");
        }
        if (rawRuleIds == null || rawRuleIds.isEmpty() || rawRuleIds.size() > MAX_ALERT_CHANGES_PER_REQUEST) {
            throw new IllegalArgumentException("Select between 1 and " + MAX_ALERT_CHANGES_PER_REQUEST + " rules.");
        }
        Set<Long> ruleIds = rawRuleIds.stream()
                .peek(id -> {
                    if (id == null || id <= 0) {
                        throw new IllegalArgumentException("Every selected rule must have a valid identifier.");
                    }
                })
                .collect(Collectors.toSet());
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset stockAsset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        if (stockAsset == null) {
            return 0;
        }
        List<AlertRule> selectedRules = alertRuleRepository
                .findByUserAndStockAssetAndIsActiveTrue(user, stockAsset)
                .stream()
                .filter(rule -> ruleIds.contains(rule.getId()))
                .toList();
        selectedRules.forEach(rule -> rule.setActive(false));
        if (!selectedRules.isEmpty()) {
            alertRuleRepository.saveAll(selectedRules);
        }
        return selectedRules.size();
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
        return getSignalArchive(user, null, requestedSort, requestedDirection, requestedPage);
    }

    public CompanySignalArchive getCompanySignalArchive(User user,
                                                         Long alertRuleId,
                                                         String requestedSort,
                                                         String requestedDirection,
                                                         int requestedPage) {
        AlertRule selectedRule = alertRuleRepository.findByIdAndUserAndIsActiveTrue(alertRuleId, user)
                .orElseThrow(() -> new IllegalArgumentException("Active alert rule not found."));
        StockAsset stockAsset = selectedRule.getStockAsset();
        return new CompanySignalArchive(
                selectedRule.getId(),
                stockAsset.getTickerSymbol(),
                stockAsset.getCompanyName(),
                getSignalArchive(user, stockAsset, requestedSort, requestedDirection, requestedPage)
        );
    }

    private SignalArchivePage getSignalArchive(User user,
                                                StockAsset stockAsset,
                                                String requestedSort,
                                                String requestedDirection,
                                                int requestedPage) {
        String sortKey = normalizeArchiveSort(requestedSort);
        String directionKey = "asc".equalsIgnoreCase(requestedDirection) ? "asc" : "desc";
        Sort.Direction direction = "asc".equals(directionKey) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if (isOutcomeSort(sortKey)) {
            return getOutcomeSortedSignalArchive(
                    user, stockAsset, sortKey, directionKey, direction, requestedPage);
        }
        Sort archiveSort = archiveSort(sortKey, direction);
        int page = Math.max(0, requestedPage);
        Page<AlertEvent> archive = signalArchivePage(user, stockAsset, page, archiveSort);
        if (archive.getTotalPages() > 0 && page >= archive.getTotalPages()) {
            page = archive.getTotalPages() - 1;
            archive = signalArchivePage(user, stockAsset, page, archiveSort);
        }
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

    private Page<AlertEvent> signalArchivePage(User user,
                                               StockAsset stockAsset,
                                               int page,
                                               Sort archiveSort) {
        PageRequest pageRequest = PageRequest.of(page, SIGNAL_ARCHIVE_PAGE_SIZE, archiveSort);
        return stockAsset == null
                ? alertEventRepository.findByAlertRule_User(user, pageRequest)
                : alertEventRepository.findByAlertRule_UserAndStockAsset(user, stockAsset, pageRequest);
    }

    private String normalizeArchiveSort(String requestedSort) {
        if (requestedSort == null) {
            return "date";
        }
        return switch (requestedSort.toLowerCase(Locale.ROOT)) {
            case "ticker", "interval", "confidence", "status", "trade-return" ->
                    requestedSort.toLowerCase(Locale.ROOT);
            case "best-return", "worst-return" -> "trade-return";
            default -> "date";
        };
    }

    private boolean isOutcomeSort(String sortKey) {
        return "trade-return".equals(sortKey);
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

    private SignalArchivePage getOutcomeSortedSignalArchive(User user,
                                                             StockAsset stockAsset,
                                                             String sortKey,
                                                             String directionKey,
                                                             Sort.Direction direction,
                                                             int requestedPage) {
        List<AlertEvent> events = stockAsset == null
                ? alertEventRepository.findAllByAlertRule_User(user)
                : alertEventRepository.findAllByAlertRule_UserAndStockAsset(user, stockAsset);
        List<SignalArchiveEntry> sortedSignals = events
                .stream()
                .map(this::toSignalArchiveEntry)
                .sorted(outcomeComparator(direction))
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

    private Comparator<SignalArchiveEntry> outcomeComparator(Sort.Direction direction) {
        Comparator<Double> numericOrder = direction == Sort.Direction.ASC
                ? Comparator.nullsLast(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<SignalArchiveEntry> resultOrder = Comparator.comparing(
                entry -> entry.outcome().returnPercent(),
                numericOrder
        );
        return resultOrder
                .thenComparing(entry -> entry.signal().sentAt(), Comparator.reverseOrder())
                .thenComparing(entry -> entry.signal().id(), Comparator.reverseOrder());
    }

    private SignalArchiveEntry toSignalArchiveEntry(AlertEvent event) {
        LatestSignalView signal = toLatestSignalView(event);
        return new SignalArchiveEntry(
                signal,
                signalArchiveOutcome(event)
        );
    }

    private SignalArchiveOutcome signalArchiveOutcome(AlertEvent event) {
        if (normalizeFamily(event.getAlertRule().getPatternFamily()) != AlertPatternFamily.CANDLESTICK) {
            return SignalArchiveOutcome.unavailable(
                    "No candlestick trade plan", "Close-based R:R outcomes apply to candlestick signals");
        }
        if (!event.hasCandlestickRiskRewardPlan()
                || event.getTradeEntryPrice() == null
                || event.getStopLossPrice() == null
                || event.getProfitTargetPrice() == null) {
            return SignalArchiveOutcome.unavailable(
                    "Outcome unavailable", "This signal predates the stored risk/reward trade plan");
        }

        double entry = event.getTradeEntryPrice();
        SignalLifecycleStatus status = event.getLifecycleStatus() == null
                ? SignalLifecycleStatus.DETECTED
                : event.getLifecycleStatus();
        return switch (status) {
            case CONFIRMED -> SignalArchiveOutcome.available(
                    "Sold at target",
                    directionalReturnPercent(event.getTradeSignal(), entry, event.getProfitTargetPrice()),
                    event.getProfitTargetPrice(),
                    "Profit target reached");
            case INVALIDATED -> SignalArchiveOutcome.available(
                    "Stop loss reached",
                    directionalReturnPercent(event.getTradeSignal(), entry, event.getStopLossPrice()),
                    event.getStopLossPrice(),
                    "Configured stop-loss price");
            case EXPIRED -> {
                Double exit = event.getResolutionClosePrice();
                yield exit == null || !Double.isFinite(exit)
                        ? SignalArchiveOutcome.unavailable(
                                "Candle 8 time stop", "The stored time-stop close is unavailable")
                        : SignalArchiveOutcome.available(
                                "Candle 8 time stop",
                                directionalReturnPercent(event.getTradeSignal(), entry, exit),
                                exit,
                                "Trade closed at the candle 8 close");
            }
            case DETECTED -> SignalArchiveOutcome.available(
                    "Potential at sell target",
                    directionalReturnPercent(event.getTradeSignal(), entry, event.getProfitTargetPrice()),
                    event.getProfitTargetPrice(),
                    "Open trade · planned target return");
            case POTENTIAL -> SignalArchiveOutcome.unavailable(
                    "Potential candidate", "Awaiting the mandatory next-candle detection gate");
            case REJECTED -> SignalArchiveOutcome.unavailable(
                    "No trade", "Candidate rejected before a trade was opened");
        };
    }

    private MeasurementAnchor measurementAnchor(AlertEvent event, TimeInterval interval) {
        if (isOneCandleReversal(event)) {
            if (event.getLifecycleStatus() == SignalLifecycleStatus.POTENTIAL) {
                return MeasurementAnchor.unavailable("Awaiting next-candle confirmation");
            }
            if (event.getLifecycleStatus() == SignalLifecycleStatus.REJECTED) {
                return MeasurementAnchor.unavailable("No result because the candidate was rejected");
            }
            if (event.getDetectionCandleTimestamp() == null
                    || event.getDetectionClosePrice() == null
                    || !Double.isFinite(event.getDetectionClosePrice())
                    || event.getDetectionClosePrice() <= 0.0) {
                return MeasurementAnchor.unavailable("Detection candle close unavailable");
            }
            return new MeasurementAnchor(
                    true,
                    event.getDetectionCandleTimestamp(),
                    event.getDetectionClosePrice(),
                    "From detection candle close · "
                            + signalPeriodLabel(interval, event.getDetectionCandleTimestamp()),
                    null);
        }
        if (event.getSignalCandleTimestamp() == null || event.getClosePrice() == null
                || !Double.isFinite(event.getClosePrice()) || event.getClosePrice() <= 0.0) {
            return MeasurementAnchor.unavailable("Recorded signal close unavailable");
        }
        return new MeasurementAnchor(
                true,
                event.getSignalCandleTimestamp(),
                event.getClosePrice(),
                "From signal candle close · "
                        + signalPeriodLabel(interval, event.getSignalCandleTimestamp()),
                null);
    }

    private boolean isOneCandleReversal(AlertEvent event) {
        return event != null
                && CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(event.getPattern());
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
                event.getFactoryConfidenceScore(),
                event.getAnalysisProfileVersion(),
                "PERSONALIZED_V1".equals(event.getScoreVersion()) ? "Custom profile" : "Factory profile",
                event.getScoreVersion(),
                event.getElliottV1EligibilityScore(),
                setupExplanation(event.getConfidenceScore(), event.getScoreVersion()),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(rule.getInterval(), event.getSignalCandleTimestamp()),
                event.getClosePrice(),
                event.getSentAt(),
                event.getInitialEmailSentAt(),
                chart,
                observedOutcome,
                results,
                toLifecycleView(event, rule.getInterval(), normalizeFamily(rule.getPatternFamily())),
                elliottTradePlanService == null ? List.of()
                        : elliottTradePlanService.history(event.getId(), user),
                List.copyOf(reasons),
                !reasons.isEmpty()
        );
    }

    private SignalResultsView toSignalResults(AlertEvent event,
                                              AlertRule rule,
                                              SignalChartView chart) {
        MeasurementAnchor anchor = measurementAnchor(event, rule.getInterval());
        if (!anchor.available()) {
            return SignalResultsView.unavailable(anchor.unavailableReason(), 0);
        }
        if (!chart.available()) {
            return SignalResultsView.unavailable(
                    "Results cannot be calculated because the completed measurement candle is not available in the local cache.",
                    0
            );
        }

        List<SignalChartCandleView> candles = chart.candles();
        int measurementIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == anchor.timestamp()) {
                measurementIndex = index;
                break;
            }
        }
        if (measurementIndex < 0) {
            return SignalResultsView.unavailable(
                    "Results cannot be calculated because the measurement-start candle is missing from the local cache.",
                    0
            );
        }

        int availableForwardCandles = candles.size() - measurementIndex - 1;
        if (availableForwardCandles < MINIMUM_RESULT_CANDLES) {
            String candleWord = availableForwardCandles == 1 ? "candle is" : "candles are";
            return SignalResultsView.unavailable(
                    "Results are not available yet. At least " + MINIMUM_RESULT_CANDLES
                            + " completed candles after the measurement start are required to provide a meaningful outcome window. "
                            + availableForwardCandles + " completed cached " + candleWord + " currently available.",
                    availableForwardCandles
            );
        }

        List<SignalResultPointView> points = new ArrayList<>(availableForwardCandles + 1);
        points.add(new SignalResultPointView(
                0,
                anchor.timestamp(),
                signalPeriodLabel(rule.getInterval(), anchor.timestamp()),
                anchor.close(),
                0.0,
                0.0
        ));
        for (int offset = 1; offset <= availableForwardCandles; offset++) {
            SignalChartCandleView candle = candles.get(measurementIndex + offset);
            double rawPriceDifference = candle.close() - anchor.close();
            double directionalPriceDifference = event.getTradeSignal() == TradeSignal.SELL
                    ? -rawPriceDifference
                    : rawPriceDifference;
            points.add(new SignalResultPointView(
                    offset,
                    candle.timestamp(),
                    signalPeriodLabel(rule.getInterval(), candle.timestamp()),
                    candle.close(),
                    directionalPriceDifference / anchor.close() * 100.0,
                    directionalPriceDifference
            ));
        }

        boolean sellSignal = event.getTradeSignal() == TradeSignal.SELL;
        return new SignalResultsView(
                true,
                null,
                MINIMUM_RESULT_CANDLES,
                availableForwardCandles,
                anchor.close(),
                anchor.timestamp(),
                event.getTradeSignal(),
                sellSignal ? "Decline avoided / rise missed" : "Gain / loss",
                sellSignal ? "Best re-entry close" : "Best sell close",
                List.copyOf(points),
                anchor.measurementStartLabel()
        );
    }

    private ObservedPriceOutcomeView toObservedPriceOutcome(AlertEvent event,
                                                            AlertRule rule,
                                                            SignalChartView chart) {
        if (normalizeFamily(rule.getPatternFamily()) != AlertPatternFamily.CANDLESTICK) {
            return ObservedPriceOutcomeView.unavailable(
                    "The close-based risk/reward trade model applies to named candlestick patterns."
            );
        }
        if (!event.hasCandlestickRiskRewardPlan()
                || event.getTradeEntryPrice() == null
                || event.getStopLossPrice() == null
                || event.getProfitTargetPrice() == null) {
            return ObservedPriceOutcomeView.unavailable(
                    "This older signal predates the stored candlestick risk/reward plan, so its trade outcome cannot be reconstructed safely."
            );
        }
        if (!chart.available() || event.getClosePrice() == null
                || !Double.isFinite(event.getClosePrice()) || event.getClosePrice() <= 0.0) {
            return ObservedPriceOutcomeView.unavailable(
                    "Completed candle history is not available for this trade calculation."
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
        MeasurementAnchor anchor = measurementAnchor(event, rule.getInterval());
        if (!anchor.available()) {
            return new ObservedPriceOutcomeView(
                    true, false, anchor.unavailableReason(),
                    event.getLifecycleStatus() == SignalLifecycleStatus.POTENTIAL
                            ? "Potential candidate" : lifecycleLabel(event.getLifecycleStatus()),
                    "pending", anchor.unavailableReason(), "Candle 8 time stop",
                    patternHigh, patternLow, null, null, null, null, null, null,
                    "Trade not open");
        }
        int measurementIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == anchor.timestamp()) {
                measurementIndex = index;
                break;
            }
        }
        if (measurementIndex < 0) {
            return ObservedPriceOutcomeView.unavailable(
                    "The measurement-start candle is missing from the cached chart.");
        }
        int maximumEvaluationIndex = Math.min(
                candles.size() - 1,
                measurementIndex + CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES);
        int resolutionIndex = event.getResolutionCandleTimestamp() == null
                ? -1
                : indexOfChartTimestamp(candles, event.getResolutionCandleTimestamp());
        int evaluationIndex = resolutionIndex > measurementIndex
                ? Math.min(resolutionIndex, maximumEvaluationIndex)
                : maximumEvaluationIndex;
        if (evaluationIndex <= measurementIndex) {
            return new ObservedPriceOutcomeView(
                    true, false, null, lifecycleLabel(event.getLifecycleStatus()), "pending",
                    "The trade is open and no completed outcome candle is available yet.",
                    "Candle 8 time stop", patternHigh, patternLow,
                    null, null, null, null, 0.0, 0.0, "Trade return pending");
        }

        SignalChartCandleView evaluationCandle = candles.get(evaluationIndex);
        List<SignalChartCandleView> futureCandles = candles.subList(
                measurementIndex + 1, evaluationIndex + 1);
        double entryClose = anchor.close();
        double rawReturn = percentMove(entryClose, evaluationCandle.close());
        double directionalReturn = event.getTradeSignal() == TradeSignal.SELL ? -rawReturn : rawReturn;
        double highestClose = futureCandles.stream().mapToDouble(SignalChartCandleView::close).max()
                .orElse(evaluationCandle.close());
        double lowestClose = futureCandles.stream().mapToDouble(SignalChartCandleView::close).min()
                .orElse(evaluationCandle.close());
        double observedBestMove = event.getTradeSignal() == TradeSignal.SELL
                ? -percentMove(entryClose, lowestClose)
                : percentMove(entryClose, highestClose);
        double targetMove = event.getTradeSignal() == TradeSignal.SELL
                ? -percentMove(entryClose, event.getProfitTargetPrice())
                : percentMove(entryClose, event.getProfitTargetPrice());
        double bestMove = event.getLifecycleStatus() == SignalLifecycleStatus.CONFIRMED
                ? targetMove
                : Math.max(0.0, observedBestMove);
        double worstMove = event.getTradeSignal() == TradeSignal.SELL
                ? -percentMove(entryClose, highestClose)
                : percentMove(entryClose, lowestClose);
        boolean terminal = event.getLifecycleStatus() == SignalLifecycleStatus.CONFIRMED
                || event.getLifecycleStatus() == SignalLifecycleStatus.INVALIDATED
                || event.getLifecycleStatus() == SignalLifecycleStatus.EXPIRED;
        String statusLabel = lifecycleLabel(event.getLifecycleStatus());
        String statusClass = event.getLifecycleStatus().name().toLowerCase(Locale.ROOT);
        String summary = tradeOutcomeSummary(event, bestMove, directionalReturn, evaluationIndex - measurementIndex);
        String impactLabel = terminal ? "Entry-to-exit return" : "Directional return so far";
        return new ObservedPriceOutcomeView(
                true,
                terminal,
                null,
                statusLabel,
                statusClass,
                summary,
                "Candle 8 time stop",
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

    private int indexOfChartTimestamp(List<SignalChartCandleView> candles, long timestamp) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp() == timestamp) return index;
        }
        return -1;
    }

    private String tradeOutcomeSummary(AlertEvent event,
                                       double favorableMove,
                                       double directionalReturn,
                                       int observedCandles) {
        return switch (event.getLifecycleStatus()) {
            case CONFIRMED -> String.format(
                    Locale.ROOT,
                    "The profit target closed successfully. The displayed favorable move is fixed at the %.2f%% entry-to-target return.",
                    favorableMove);
            case INVALIDATED -> String.format(
                    Locale.ROOT,
                    "The configured stop loss closed the trade. Before that exit, the best completed-close move was %+.2f%%; entry-to-exit return was %+.2f%%.",
                    favorableMove, directionalReturn);
            case EXPIRED -> String.format(
                    Locale.ROOT,
                    "Candle 8 closed the trade at the time stop. The best completed-close move during the trade was %+.2f%%; entry-to-exit return was %+.2f%%.",
                    favorableMove, directionalReturn);
            case DETECTED -> String.format(
                    Locale.ROOT,
                    "The trade is open. Across %d completed outcome candle%s, the best favorable close is %+.2f%% from entry.",
                    observedCandles, observedCandles == 1 ? "" : "s", favorableMove);
            case POTENTIAL -> "The mandatory next-candle detection gate has not opened a trade yet.";
            case REJECTED -> "The mandatory detection gate failed, so no trade was opened.";
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

        HarmonicChartView harmonic = toSignalHarmonicView(event, rule);
        int patternCandleCount = patternCandleCount(event.getPattern());
        int patternStartIndex = harmonic == null
                ? Math.max(0, signalIndex - patternCandleCount + 1)
                : candleIndex(candles, harmonic.points().getFirst().timestamp());
        if (patternStartIndex < 0) patternStartIndex = Math.max(0, signalIndex - 4);
        int trendContextCandles = signalTrendContextCandles(event, rule);
        int trendContextStartIndex = Math.max(0, patternStartIndex - trendContextCandles);
        int trendStartIndex = trendContextStartIndex;
        if (normalizeFamily(rule.getPatternFamily()) == AlertPatternFamily.CANDLESTICK) {
            AnalysisPreferencesService.IntervalProfile storedProfile = preferencesService == null
                    ? AnalysisPreferencesService.factoryProfile(rule.getInterval())
                    : preferencesService.profileFromSnapshot(
                            event.getAnalysisProfileSnapshot(), rule.getInterval());
            CandlePatternDetectionService.TrendDetectionRules rules = preferencesService == null
                    ? CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(rule.getInterval())
                    : preferencesService.trendDetectionRulesFromSnapshot(
                            event.getAnalysisProfileSnapshot(), rule.getInterval());
            TechnicalIndicatorProfile technicalProfile = preferencesService == null
                    ? TechnicalIndicatorProfile.forInterval(rule.getInterval())
                    : preferencesService.technicalProfile(storedProfile);
            List<EnrichedCandle> enriched = enrichmentService.enrich(
                    candles.subList(0, signalIndex + 1), signalIndex + 1, technicalProfile);
            CandlePatternDetectionService.PriorTrendAssessment assessment =
                    detectionService.assessPriorTrendForLatestPattern(
                            enriched, patternCandleCount, rules, event.getTradeSignal());
            int detectedStart = assessment.trendStartIndex();
            trendStartIndex = detectedStart >= trendContextStartIndex && detectedStart < patternStartIndex
                    ? detectedStart
                    : CandlestickTrendLegLocator.locateStartIndex(
                    candles, trendContextStartIndex, patternStartIndex, event.getTradeSignal());
        }
        long patternStartTimestamp = candles.get(patternStartIndex).getTimestamp();
        Long trendStartTimestamp = harmonic == null && patternStartIndex > 0
                ? candles.get(trendStartIndex).getTimestamp()
                : null;
        String trendLabel = normalizeFamily(rule.getPatternFamily()) == AlertPatternFamily.ELLIOTT_WAVE
                ? "Wave structure context"
                : harmonic != null ? "Harmonic pivot structure"
                : event.getTradeSignal() == TradeSignal.SELL ? "Required uptrend" : "Required downtrend";
        String summary = normalizeFamily(rule.getPatternFamily()) == AlertPatternFamily.ELLIOTT_WAVE
                ? "The marker locates the recorded Elliott detection inside the complete cached interval history."
                : harmonic != null
                ? "The saved harmonic geometry is drawn from its first pivot through D/C, with the separate confirmation candle marking when the signal became knowable."
                : "The highlighted region and amber guide trace the identified trend leg inside the detector's broader completed context; labeled arrows identify every candle that formed the pattern.";

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
                elliottWave,
                harmonic
        );
    }

    private HarmonicChartView toSignalHarmonicView(AlertEvent event, AlertRule rule) {
        if (normalizeFamily(rule.getPatternFamily()) != AlertPatternFamily.HARMONIC_FORMATION
                || event.getHarmonicPointsSnapshot() == null) return null;
        List<HarmonicChartPointView> points = event.getHarmonicPointsSnapshot().lines()
                .map(line -> line.split("\\|", -1))
                .filter(fields -> fields.length == 4)
                .map(fields -> {
                    try {
                        return new HarmonicChartPointView(
                                fields[0], Long.parseLong(fields[1]),
                                Double.parseDouble(fields[2]), fields[3]);
                    } catch (NumberFormatException exception) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (points.size() != 5) return null;
        Map<String, Double> measurements = new LinkedHashMap<>();
        if (event.getHarmonicMeasurementsSnapshot() != null) {
            event.getHarmonicMeasurementsSnapshot().lines().forEach(line -> {
                String[] fields = line.split("\\|", -1);
                if (fields.length != 2) return;
                try {
                    measurements.put(fields[0], Double.parseDouble(fields[1]));
                } catch (NumberFormatException ignored) {
                    // Preserve the geometry when one optional measurement is malformed.
                }
            });
        }
        return new HarmonicChartView(
                event.getTradeSignal() == TradeSignal.BUY ? "BULLISH" : "BEARISH",
                points,
                Map.copyOf(measurements),
                event.getHarmonicEndpointTimestamp(),
                event.getHarmonicEndpointPrice(),
                event.getSignalCandleTimestamp());
    }

    private int candleIndex(List<Candle> candles, long timestamp) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp() == timestamp) return index;
        }
        return -1;
    }

    private int signalTrendContextCandles(AlertEvent event, AlertRule rule) {
        if (normalizeFamily(rule.getPatternFamily()) != AlertPatternFamily.CANDLESTICK) {
            return 5;
        }
        CandlePatternDetectionService.TrendDetectionRules rules = preferencesService == null
                ? CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(rule.getInterval())
                : preferencesService.trendDetectionRulesFromSnapshot(
                event.getAnalysisProfileSnapshot(), rule.getInterval());
        if (!rules.adaptiveFactory()) {
            return rules.lookbackCandles();
        }
        return 30;
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
        ElliottWaveDetectionService detector = elliottDetector(rule.getUser(), rule.getInterval());
        ElliottWaveDetectionService.ElliottWaveStructure structure = detector
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
                .map(event -> toEventView(event, rule.getInterval(), normalizeFamily(rule.getPatternFamily())))
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
                toLifecycleView(event, rule.getInterval(), normalizeFamily(rule.getPatternFamily()))
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

    /**
     * Temporary testing helper that activates every supported technical family,
     * direction, and production interval for a fixed top-200 U.S. universe.
     * This intentionally bypasses the normal per-user 50-instrument quota only
     * for this bounded universe; the global provider-capacity guard remains active.
     */
    @Transactional
    public TemporaryBulkFollowResult followTemporaryTopUsCompanies(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User is required.");
        }

        jdbcTemplate.queryForObject(
                "select lock_name from security_resource_locks where lock_name = ? for update",
                String.class,
                "alert-stock-quota");

        List<StockAsset> assets = TemporaryTopUsCompanyUniverse.SYMBOLS.stream()
                .map(symbol -> stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                        .orElseGet(() -> twelveDataService.upsertStockAsset(
                                symbol, symbol, "US", "USD")))
                .toList();
        long newlyGlobalSymbols = assets.stream()
                .filter(asset -> !alertRuleRepository.existsByStockAssetAndIsActiveTrue(asset))
                .count();
        long globallyTracked = alertRuleRepository.countDistinctActiveStocks();
        if (globallyTracked + newlyGlobalSymbols > maxGlobalTrackedStocks) {
            throw new IllegalStateException(
                    "The temporary top-200 universe would exceed global market data capacity.");
        }

        Map<BulkAlertRuleKey, AlertRule> existing = new LinkedHashMap<>();
        for (AlertRule rule : alertRuleRepository.findByUserAndStockAssetIn(user, assets)) {
            existing.put(new BulkAlertRuleKey(
                    rule.getInterval(), rule.getTradeSignal(), rule.getPatternFamily(),
                    rule.getStockAsset().getId()), rule);
        }

        List<AlertRule> changed = new ArrayList<>();
        int createdRules = 0;
        int reactivatedRules = 0;
        int alreadyActiveRules = 0;
        for (StockAsset asset : assets) {
            for (TimeInterval interval : TEMPORARY_BULK_INTERVALS) {
                for (AlertPatternFamily family : AlertPatternFamily.values()) {
                    validateFamilyInterval(family, interval);
                    for (TradeSignal signal : List.of(TradeSignal.BUY, TradeSignal.SELL)) {
                        BulkAlertRuleKey key = new BulkAlertRuleKey(
                                interval, signal, family, asset.getId());
                        AlertRule rule = existing.get(key);
                        if (rule == null) {
                            rule = new AlertRule();
                            rule.setUser(user);
                            rule.setStockAsset(asset);
                            rule.setInterval(interval);
                            rule.setTradeSignal(signal);
                            rule.setPatternFamily(family);
                            rule.setTargetPattern(defaultTargetPattern(signal, family));
                            rule.setActive(true);
                            changed.add(rule);
                            createdRules++;
                        } else if (!rule.isActive()) {
                            rule.setActive(true);
                            changed.add(rule);
                            reactivatedRules++;
                        } else {
                            alreadyActiveRules++;
                        }
                    }
                }
            }
        }
        if (!changed.isEmpty()) {
            alertRuleRepository.saveAll(changed);
        }
        return new TemporaryBulkFollowResult(
                TemporaryTopUsCompanyUniverse.COMPANY_COUNT,
                TEMPORARY_BULK_INTERVALS.size()
                        * AlertPatternFamily.values().length * 2,
                createdRules,
                reactivatedRules,
                alreadyActiveRules,
                TemporaryTopUsCompanyUniverse.COMPANY_COUNT
                        * TEMPORARY_BULK_INTERVALS.size()
                        * AlertPatternFamily.values().length * 2,
                TemporaryTopUsCompanyUniverse.SNAPSHOT_LABEL);
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
        AnalysisPreferencesService.PreferencesView preferences = preferencesService == null
                ? AnalysisPreferencesService.factoryPreferences() : preferencesService.get(user);
        AnalysisPreferencesService.IntervalProfile profile = preferences.profile(interval);
        TechnicalIndicatorProfile technicalProfile = preferencesService == null
                ? TechnicalIndicatorProfile.forInterval(interval) : preferencesService.technicalProfile(profile);
        List<EnrichedCandle> enrichedCandles = enrichmentService.enrich(latest, signalCandleCount, technicalProfile);
        List<EnrichedCandle> elliottCandles = family == AlertPatternFamily.ELLIOTT_WAVE
                ? enrichedCandles
                : List.of();
        List<DetectedSignal> detectedSignals = detectSignals(
                latest,
                enrichedCandles,
                elliottCandles,
                interval,
                family,
                profile,
                user
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
        response.put("analysisProfile", preferences.profileLabel());
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

        List<AlertRule> activeRules = stockAsset == null
                ? List.of()
                : alertRuleRepository.findByUserAndStockAssetAndIsActiveTrue(user, stockAsset);
        activeRules.forEach(rule -> {
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

        response.put("intervals", intervals);
        response.put("families", families);
        response.put("activeRules", activeRules.stream()
                .sorted(Comparator.comparing(AlertRule::getInterval)
                        .thenComparing(AlertRule::getPatternFamily)
                        .thenComparing(AlertRule::getTradeSignal))
                .map(rule -> new ActiveRuleSummary(
                        rule.getId(),
                        rule.getPatternFamily().name(),
                        familyLabel(rule.getPatternFamily()),
                        rule.getInterval().name(),
                        intervalLabel(rule.getInterval()),
                        rule.getTradeSignal().name()))
                .toList());
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
            case WEEKLY -> "Weekly";
            case MONTHLY -> "Monthly";
            default -> "Daily";
        };
    }

    private String familyLabel(AlertPatternFamily family) {
        return switch (normalizeFamily(family)) {
            case ELLIOTT_WAVE -> "Elliott Wave";
            case CANDLESTICK -> "Candlestick";
            case HARMONIC_FORMATION -> "Harmonic Formation";
        };
    }

    private List<DetectedSignal> detectSignals(List<Candle> candles,
                                               List<EnrichedCandle> enrichedCandles,
                                               List<EnrichedCandle> elliottCandles,
                                               TimeInterval interval,
                                               AlertPatternFamily family,
                                               AnalysisPreferencesService.IntervalProfile profile,
                                               User user) {
        if (family == AlertPatternFamily.CANDLESTICK) {
            return preferencesService == null
                    ? detectionService.detectAlertSignalsFactory(enrichedCandles, interval)
                    : detectionService.detectAlertSignals(
                    enrichedCandles,
                    preferencesService.trendDetectionRules(profile),
                    patternPreferencesService == null
                            ? CandlestickPatternPreferencesService.factoryPreferences()
                            : patternPreferencesService.get(user));
        }
        if (family == AlertPatternFamily.HARMONIC_FORMATION) {
            HarmonicPatternDetectionService detector = harmonicDetector(user);
            return detector.detectHistorical(candles).stream()
                    .map(formation -> {
                        Double closePrice = candles.stream()
                                .filter(candle -> candle.getTimestamp() != null
                                        && candle.getTimestamp() == formation.confirmationTimestamp())
                                .map(Candle::getClosePrice)
                                .findFirst()
                                .orElse(null);
                        return new DetectedSignal(
                                HarmonicPatternDetectionService.signalPattern(formation.pattern()),
                                formation.tradeSignal(),
                                harmonicStrength(formation.qualityScore()),
                                formation.qualityScore(),
                                formation.reasons(),
                                formation.confirmationTimestamp(),
                                closePrice);
                    })
                    .toList();
        }
        if (!isElliottEnabled(interval)) {
            return List.of();
        }
        return elliottDetector(user, interval).detectAlertSignals(elliottCandles).stream()
                .filter(this::isActionableElliottTurningPoint)
                .toList();
    }

    private ElliottWaveDetectionService elliottDetector(User user, TimeInterval interval) {
        if (user == null || elliottWavePreferencesService == null) {
            return elliottWaveDetectionService;
        }
        return elliottWaveDetectionService.configured(
                elliottWavePreferencesService.get(user).profile(interval).rules());
    }

    private HarmonicPatternDetectionService harmonicDetector(User user) {
        HarmonicPatternDetectionService fallback = harmonicPatternDetectionService == null
                ? new HarmonicPatternDetectionService() : harmonicPatternDetectionService;
        return user == null || harmonicPatternPreferencesService == null
                ? fallback
                : harmonicPatternPreferencesService.detector(user, fallback);
    }

    private SignalStength harmonicStrength(int score) {
        if (score >= 85) return SignalStength.HIGH_CONFIDENCE;
        if (score >= 75) return SignalStength.MEDIUM_CONFIDENCE;
        if (score > 0) return SignalStength.LOW_CONFIDENCE;
        return SignalStength.WEAK_IGNORE;
    }

    private int signalCandleCount(TimeInterval interval) {
        return isHigherInterval(interval) ? HIGHER_INTERVAL_SIGNAL_CANDLES : DEFAULT_SIGNAL_CANDLES;
    }

    private boolean isHigherInterval(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY || interval == TimeInterval.MONTHLY;
    }

    private boolean isElliottEnabled(TimeInterval interval) {
        return interval == TimeInterval.DAILY && dailyElliottEnabled
                || interval == TimeInterval.WEEKLY && weeklyElliottEnabled
                || interval == TimeInterval.MONTHLY && monthlyElliottEnabled;
    }

    private AlertPatternFamily normalizeFamily(AlertPatternFamily family) {
        return family == null ? AlertPatternFamily.CANDLESTICK : family;
    }

    private void validateFamilyInterval(AlertPatternFamily family, TimeInterval interval) {
        if (normalizeFamily(family) == AlertPatternFamily.ELLIOTT_WAVE
                && interval != TimeInterval.DAILY
                && interval != TimeInterval.WEEKLY
                && interval != TimeInterval.MONTHLY) {
            throw new IllegalArgumentException("Elliott Wave alerts are available only for daily, weekly, and monthly intervals.");
        }
    }

    private CandlePattern defaultTargetPattern(TradeSignal signal, AlertPatternFamily family) {
        if (normalizeFamily(family) == AlertPatternFamily.HARMONIC_FORMATION) {
            return CandlePattern.ANY;
        }
        if (normalizeFamily(family) == AlertPatternFamily.ELLIOTT_WAVE) {
            return signal == TradeSignal.BUY
                    ? CandlePattern.ELLIOTT_BULLISH_CORRECTION
                    : CandlePattern.ELLIOTT_BEARISH_CORRECTION;
        }
        return signal == TradeSignal.BUY ? CandlePattern.BULLISH_ENGULFING : CandlePattern.BEARISH_ENGULFING;
    }

    private AlertPatternFamily signalFamily(DetectedSignal signal) {
        if (isElliottPattern(signal.pattern())) return AlertPatternFamily.ELLIOTT_WAVE;
        if (isHarmonicPattern(signal.pattern())) return AlertPatternFamily.HARMONIC_FORMATION;
        return AlertPatternFamily.CANDLESTICK;
    }

    private boolean isElliottPattern(CandlePattern pattern) {
        return pattern != null && pattern.name().startsWith("ELLIOTT_");
    }

    private boolean isHarmonicPattern(CandlePattern pattern) {
        return pattern != null && pattern.name().startsWith("HARMONIC_");
    }

    private String scoreVersion(DetectedSignal signal) {
        if (isElliottPattern(signal.pattern())) return ElliottWaveDetectionService.SETUP_SCORE_VERSION;
        if (isHarmonicPattern(signal.pattern())) return HarmonicPatternDetectionService.RULE_VERSION;
        return CandlePatternDetectionService.SETUP_SCORE_VERSION;
    }

    private boolean isActionableElliottTurningPoint(DetectedSignal signal) {
        CandlePattern pattern = signal.pattern();
        return isElliottPattern(pattern)
                && (pattern.name().endsWith("WAVE_V_END") || pattern.name().endsWith("CORRECTION"));
    }

    private AlertEventView toEventView(AlertEvent event,
                                       TimeInterval interval,
                                       AlertPatternFamily patternFamily) {
        return new AlertEventView(
                event.getId(),
                event.getPattern(),
                patternLabel(event.getPattern()),
                event.getTradeSignal(),
                event.getSignalStrength(),
                setupStrengthLabel(event.getSignalStrength(), event.getConfidenceScore()),
                setupBand(event.getConfidenceScore()),
                event.getConfidenceScore(),
                event.getFactoryConfidenceScore(),
                event.getAnalysisProfileVersion(),
                "PERSONALIZED_V1".equals(event.getScoreVersion()) ? "Custom profile" : "Factory profile",
                event.getScoreVersion(),
                event.getSignalCandleTimestamp(),
                signalDate(event.getSignalCandleTimestamp()),
                signalPeriodLabel(interval, event.getSignalCandleTimestamp()),
                event.getClosePrice(),
                event.getSentAt(),
                event.getInitialEmailSentAt(),
                event.isRead(),
                toLifecycleView(event, interval, patternFamily)
        );
    }

    private SignalLifecycleView toLifecycleView(AlertEvent event,
                                                TimeInterval interval,
                                                AlertPatternFamily patternFamily) {
        SignalLifecycleStatus status = event.getLifecycleStatus();
        boolean tracked = event.isLifecycleTracked();
        String resolutionPeriod = event.getResolutionCandleTimestamp() == null
                ? null
                : signalPeriodLabel(interval, event.getResolutionCandleTimestamp());
        String detectionPeriod = event.getDetectionCandleTimestamp() == null
                ? null
                : signalPeriodLabel(interval, event.getDetectionCandleTimestamp());
        String boundaryDirection = event.getTradeSignal() == TradeSignal.BUY ? "above" : "below";
        String invalidationDirection = event.getTradeSignal() == TradeSignal.BUY ? "below" : "above";
        boolean elliottSignal = event.isElliottSignal();
        boolean developingElliottCycle = event.getElliottDevelopmentKey() != null;
        boolean riskRewardPlan = event.hasCandlestickRiskRewardPlan();
        boolean candlestickSignal = patternFamily == AlertPatternFamily.CANDLESTICK;
        boolean harmonicStopPlan = patternFamily == AlertPatternFamily.HARMONIC_FORMATION
                && event.hasHarmonicStopPlan();
        boolean immediateConfirmationRequired = isOneCandleReversal(event);
        String summary;
        if (developingElliottCycle && status == SignalLifecycleStatus.DETECTED) {
            summary = String.format(
                    Locale.ROOT,
                    "%s. Expected move %s from %.4f; projected target %.4f and structural stop %.4f. Correction count: %s.",
                    event.getElliottForecastLabel() == null
                            ? "Developing Elliott impulse" : event.getElliottForecastLabel(),
                    event.getTradeSignal(),
                    event.getTradeEntryPrice(),
                    event.getProfitTargetPrice(),
                    event.getStopLossPrice(),
                    event.getElliottCorrectionType() == null
                            ? "not yet applicable" : event.getElliottCorrectionType());
        } else if (developingElliottCycle && status == SignalLifecycleStatus.INVALIDATED) {
            summary = "Developing Elliott count invalidated: "
                    + (event.getLifecycleResolutionReason() == null
                    ? "a hard structure boundary was crossed."
                    : event.getLifecycleResolutionReason());
        } else if (harmonicStopPlan) {
            summary = "STOPPED".equals(event.getHarmonicStopStatus())
                    ? String.format(
                    Locale.ROOT,
                    "The buffered harmonic stop at %.4f was breached at %.4f. %s",
                    event.getStopLossPrice(),
                    event.getHarmonicStopResolutionPrice(),
                    event.getHarmonicStopResolutionReason())
                    : String.format(
                    Locale.ROOT,
                    "Harmonic stop active from the %.4f confirmation close: exact structural invalidation %.4f, buffered stop %.4f (%s).",
                    event.getTradeEntryPrice(),
                    event.getStructuralStopPrice(),
                    event.getStopLossPrice(),
                    event.getHarmonicStopBasis());
        } else if (!tracked) {
            summary = "Follow-up lifecycle tracking was not recorded for this signal.";
        } else if (candlestickSignal && !riskRewardPlan) {
            summary = "This legacy candlestick record predates the current close-based trade plan. Its old boundary result is not presented as a current CONFIRMED, INVALIDATED, or EXPIRED trade outcome.";
        } else {
            summary = switch (status) {
                case POTENTIAL -> String.format(
                        Locale.ROOT,
                        "Potential %s candidate only. The immediately following candle must have a %s body and close %s the %.4f candidate close before this becomes a signal.",
                        event.getTradeSignal() == TradeSignal.SELL ? "sell/short" : "buy",
                        event.getTradeSignal() == TradeSignal.BUY ? "green" : "red",
                        boundaryDirection,
                        event.getClosePrice());
                case DETECTED -> immediateConfirmationRequired
                        && riskRewardPlan
                        ? String.format(
                                Locale.ROOT,
                                "Trade opened on %s at %.4f after the next-candle gate passed. Target %.4f, stop %.4f, and candle 8 is the time stop.",
                                detectionPeriod,
                                event.getTradeEntryPrice(),
                                event.getProfitTargetPrice(),
                                event.getStopLossPrice())
                        : immediateConfirmationRequired
                        ? String.format(
                                Locale.ROOT,
                                "Detected on %s after the next-candle gate passed at %.4f. The %d-candle outcome window is now measured from that detection close.",
                                detectionPeriod,
                                event.getDetectionClosePrice(),
                                event.getConfirmationWindowCandles())
                        : riskRewardPlan
                        ? String.format(
                                Locale.ROOT,
                                "Trade opened at %.4f. A completed-candle close at %.4f succeeds; a close at %.4f hits the stop; otherwise candle 8 closes the trade.",
                                event.getTradeEntryPrice(),
                                event.getProfitTargetPrice(),
                                event.getStopLossPrice())
                        : elliottSignal
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
                case REJECTED -> String.format(
                        Locale.ROOT,
                        "Rejected on %s because the immediately following candle closed at %.4f without the required %s-body close %s the %.4f candidate close. It never became a signal.",
                        resolutionPeriod,
                        event.getResolutionClosePrice(),
                        event.getTradeSignal() == TradeSignal.BUY ? "green" : "red",
                        boundaryDirection,
                        event.getClosePrice());
                case CONFIRMED -> riskRewardPlan ? String.format(
                        Locale.ROOT,
                        "Trade closed successfully on %s when candle %d closed at %.4f and reached the %.4f profit target.",
                        resolutionPeriod,
                        event.getResolutionCandleOffset(),
                        event.getResolutionClosePrice(),
                        event.getProfitTargetPrice()) : String.format(
                        Locale.ROOT,
                        "Confirmed on %s when candle %d closed at %.4f, %s the %.4f trigger.",
                        resolutionPeriod,
                        event.getResolutionCandleOffset(),
                        event.getResolutionClosePrice(),
                        boundaryDirection,
                        event.getConfirmationTriggerPrice()
                );
                case INVALIDATED -> riskRewardPlan
                        ? String.format(
                                Locale.ROOT,
                                "Stop loss hit on %s when candle %d closed at %.4f beyond the configured %.4f stop.",
                                resolutionPeriod,
                                event.getResolutionCandleOffset(),
                                event.getResolutionClosePrice(),
                                event.getStopLossPrice())
                        : elliottSignal
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
                case EXPIRED -> riskRewardPlan ? String.format(
                        Locale.ROOT,
                        "Time stop reached on candle 8; the trade closed at %.4f on %s.",
                        event.getResolutionClosePrice(),
                        resolutionPeriod) : String.format(
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
                status != SignalLifecycleStatus.POTENTIAL
                        && status != SignalLifecycleStatus.DETECTED,
                immediateConfirmationRequired,
                summary,
                event.getPatternHigh(),
                event.getPatternLow(),
                event.getConfirmationTriggerPrice(),
                event.getInvalidationPrice(),
                event.getConfirmationWindowCandles(),
                event.getTradeEntryPrice(),
                event.getStopLossPrice(),
                event.getProfitTargetPrice(),
                event.getRewardRiskRatio(),
                event.getElliottTargetMidPrice(),
                event.getElliottTargetZoneLow(),
                event.getElliottTargetZoneHigh(),
                event.getElliottTargetBasis(),
                event.getElliottRequiredRewardRiskRatio(),
                event.isElliottTradeActionable(),
                event.getElliottTradePlanStatus(),
                event.getElliottTradeResolutionTimestamp(),
                event.getElliottTradeResolutionClose(),
                event.getElliottTradeResolutionReason(),
                event.getHarmonicStopBasis(),
                event.getHarmonicStopFormula(),
                event.getHarmonicStopBufferAmount(),
                event.getHarmonicStopBufferPercent(),
                event.getHarmonicStopDistancePercent(),
                event.getHarmonicStopStatus(),
                event.getHarmonicStopResolutionTimestamp(),
                event.getHarmonicStopResolutionPrice(),
                event.getHarmonicStopResolutionReason(),
                event.getHarmonicEndpointPrice(),
                event.getStructuralStopPrice(),
                event.getPreCircuitBreakerStopPrice(),
                Boolean.TRUE.equals(event.getAtrCircuitBreakerApplied()),
                event.getAtrCircuitBreakerValue(),
                event.getAtrCircuitBreakerPeriod(),
                event.getAtrCircuitBreakerMultiplier(),
                event.getAtrCircuitBreakerThresholdPercent(),
                event.getLifecycleConfirmationPercent(),
                event.getLifecycleInvalidationPercent(),
                event.getDetectionCandleTimestamp(),
                detectionPeriod,
                event.getDetectionClosePrice(),
                event.getResolutionCandleOffset(),
                event.getResolutionCandleTimestamp(),
                resolutionPeriod,
                event.getResolutionClosePrice(),
                event.getLifecycleUpdatedAt(),
                event.getFollowUpSentAt(),
                event.getElliottEndpointPrice(),
                event.getElliottSignalStage(),
                event.getLifecycleResolutionReason(),
                developingElliottCycle,
                event.getElliottCorrectionType(),
                event.getElliottForecastLabel(),
                elliottTransitionLabels(event.getElliottTransitionHistory())
        );
    }

    private List<String> elliottTransitionLabels(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        List<String> labels = new ArrayList<>();
        for (String row : payload.lines().toList()) {
            String[] fields = row.split("\\|", -1);
            try {
                if (fields.length >= 5 && "INVALIDATED".equals(fields[1])) {
                    labels.add("Invalidated after " + fields[2].replace('_', ' ')
                            + " at close " + String.format(Locale.ROOT, "%.4f", Double.parseDouble(fields[3]))
                            + " — " + fields[4]);
                } else if (fields.length >= 7) {
                    labels.add(fields[1].replace('_', ' ') + " · expected " + fields[2]
                            + " · close " + String.format(Locale.ROOT, "%.4f", Double.parseDouble(fields[3]))
                            + " · stop " + String.format(Locale.ROOT, "%.4f", Double.parseDouble(fields[4]))
                            + " · target " + String.format(Locale.ROOT, "%.4f", Double.parseDouble(fields[5]))
                            + " · " + fields[6]);
                }
            } catch (NumberFormatException ignored) {
                // Keep malformed legacy transition rows out of the user-facing timeline.
            }
        }
        return List.copyOf(labels);
    }

    private String lifecycleLabel(SignalLifecycleStatus status) {
        return switch (status) {
            case POTENTIAL -> "Potential";
            case DETECTED -> "Detected";
            case REJECTED -> "Rejected";
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
        if (HarmonicPatternDetectionService.RULE_VERSION.equals(scoreVersion)) {
            return "Harmonic setup score: every hard structural rule passed. The base geometry score reflects accepted deviations from non-hard Fibonacci and proportion targets; same-ticker, same-interval Elliott and candlestick signals from the preceding eight candles then contribute cross-pattern confluence.";
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
        if (normalized.startsWith("soft ratio compliance")) {
            return "Harmonic ratio compliance";
        }
        if (normalized.startsWith("primary b ratio")) {
            return "Primary B ratio";
        }
        if (normalized.startsWith("completion ratio")) {
            return "Completion ratio";
        }
        if (normalized.startsWith("secondary ratios")) {
            return "Secondary ratios";
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
        String rawName = pattern.name().startsWith("HARMONIC_")
                ? pattern.name().substring("HARMONIC_".length())
                : pattern.name();
        for (String word : rawName.toLowerCase(Locale.ROOT).split("_")) {
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private static String directionDisplayLabel(CandlePattern pattern,
                                                TradeSignal direction,
                                                SignalLifecycleView lifecycle) {
        if (direction == null) return "Signal";
        if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(pattern)
                && lifecycle != null) {
            String directionName = direction == TradeSignal.SELL ? "sell/short" : "buy";
            return switch (lifecycle.status()) {
                case POTENTIAL -> "Potential " + directionName;
                case REJECTED -> "Rejected " + directionName + " candidate";
                case DETECTED -> "Detected " + directionName;
                case CONFIRMED -> "Confirmed " + directionName;
                case INVALIDATED -> "Invalidated " + directionName;
                case EXPIRED -> "Expired " + directionName;
            };
        }
        return direction == TradeSignal.SELL ? "SELL/SHORT" : direction.name();
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

        public String directionLabel() {
            return directionDisplayLabel(pattern, tradeSignal, lifecycle);
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
            Integer factorySetupScore,
            String analysisProfileVersion,
            String analysisProfileLabel,
            String scoreVersion,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            Double closePrice,
            java.time.LocalDateTime sentAt,
            java.time.LocalDateTime initialEmailSentAt,
            boolean hasBeenRead,
            SignalLifecycleView lifecycle
    ) {
        public Integer confidenceScore() {
            return setupScore;
        }

        public String directionLabel() {
            return directionDisplayLabel(pattern, tradeSignal, lifecycle);
        }

    }

    public record CompanySignalArchive(
            Long representativeAlertId,
            String symbol,
            String companyName,
            SignalArchivePage archive
    ) {
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
            SignalArchiveOutcome outcome
    ) {
        private String groupKey(String sortKey) {
            return switch (sortKey) {
                case "ticker" -> signal.symbol();
                case "interval" -> signal.interval().name();
                case "confidence" -> confidenceGroupKey();
                case "status" -> signal.lifecycle().status().name();
                case "trade-return" -> returnGroupKey(outcome.returnPercent());
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
                case "trade-return" -> returnGroupLabel(outcome.returnPercent());
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
                case "trade-return" -> "Close-based trade outcome from the stored trade plan";
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

    public record SignalArchiveOutcome(
            boolean available,
            String label,
            Double returnPercent,
            Double price,
            String detail
    ) {
        private static SignalArchiveOutcome available(String label,
                                                      double returnPercent,
                                                      double price,
                                                      String detail) {
            return new SignalArchiveOutcome(true, label, returnPercent, price, detail);
        }

        private static SignalArchiveOutcome unavailable(String label, String detail) {
            return new SignalArchiveOutcome(false, label, null, null, detail);
        }

        public String valueLabel() {
            if (!available || returnPercent == null) {
                return "N/A";
            }
            if ("Stop loss reached".equals(label) && price != null) {
                return String.format(Locale.ROOT, "%.2f (%+.2f%%)", price, returnPercent);
            }
            return String.format(Locale.ROOT, "%+.2f%%", returnPercent);
        }

        public String priceDetail() {
            if (price == null) {
                return detail;
            }
            return detail + " · " + String.format(Locale.ROOT, "%.2f", price);
        }
    }

    private record MeasurementAnchor(boolean available,
                                     Long timestamp,
                                     Double close,
                                     String measurementStartLabel,
                                     String unavailableReason) {
        private static MeasurementAnchor unavailable(String reason) {
            return new MeasurementAnchor(false, null, null, null, reason);
        }
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
            Integer factorySetupScore,
            String analysisProfileVersion,
            String analysisProfileLabel,
            String scoreVersion,
            Integer elliottV1EligibilityScore,
            String setupExplanation,
            Long signalCandleTimestamp,
            LocalDate signalDate,
            String signalPeriodLabel,
            Double closePrice,
            java.time.LocalDateTime sentAt,
            java.time.LocalDateTime initialEmailSentAt,
            SignalChartView chart,
            ObservedPriceOutcomeView observedOutcome,
            SignalResultsView results,
            SignalLifecycleView lifecycle,
            List<ElliottTradePlanService.StagePlanView> elliottTradePlans,
            List<SignalReasonView> reasons,
            boolean reasonsAvailable
    ) {
        public SignalDetailView {
            elliottTradePlans = elliottTradePlans == null
                    ? List.of() : List.copyOf(elliottTradePlans);
        }

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

        public String directionLabel() {
            return directionDisplayLabel(pattern, tradeSignal, lifecycle);
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
            ElliottWaveChartView elliottWave,
            HarmonicChartView harmonic
    ) {
        private static SignalChartView unavailable(String reason) {
            return new SignalChartView(false, reason, List.of(), null, null, null, 0, null, null, null, null);
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
            List<SignalResultPointView> points,
            String measurementStartLabel
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
                    List.of(),
                    null
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

    public record HarmonicChartView(
            String direction,
            List<HarmonicChartPointView> points,
            Map<String, Double> measurements,
            Long endpointTimestamp,
            Double endpointPrice,
            Long confirmationTimestamp
    ) {
        public HarmonicChartView {
            points = List.copyOf(points);
            measurements = Map.copyOf(measurements);
        }
    }

    public record HarmonicChartPointView(String label, long timestamp, double price, String pivotType) {
    }

    public record ObservedPriceOutcomeView(
            boolean tracked,
            boolean outcomeAvailable,
            String unavailableReason,
            String statusLabel,
            String statusClass,
            String summary,
            String timeStopLabel,
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
                    false, false, reason, "Unavailable", "pending", reason, null,
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
            boolean immediateConfirmationRequired,
            String summary,
            Double patternHigh,
            Double patternLow,
            Double confirmationTriggerPrice,
            Double invalidationPrice,
            Integer confirmationWindowCandles,
            Double entryPrice,
            Double stopLossPrice,
            Double profitTargetPrice,
            Double rewardRiskRatio,
            Double elliottTargetMidPrice,
            Double elliottTargetZoneLow,
            Double elliottTargetZoneHigh,
            String elliottTargetBasis,
            Double elliottRequiredRewardRiskRatio,
            boolean elliottTradeActionable,
            String elliottTradePlanStatus,
            Long elliottTradeResolutionTimestamp,
            Double elliottTradeResolutionClose,
            String elliottTradeResolutionReason,
            String harmonicStopBasis,
            String harmonicStopFormula,
            Double harmonicStopBufferAmount,
            Double harmonicStopBufferPercent,
            Double harmonicStopDistancePercent,
            String harmonicStopStatus,
            Long harmonicStopResolutionTimestamp,
            Double harmonicStopResolutionPrice,
            String harmonicStopResolutionReason,
            Double harmonicEndpointPrice,
            Double structuralStopPrice,
            Double configuredStopLossPrice,
            boolean atrCircuitBreakerApplied,
            Double atrValue,
            Integer atrPeriod,
            Double atrMultiplier,
            Double activationThresholdPercent,
            Double confirmationMovePercent,
            Double invalidationMovePercent,
            Long detectionCandleTimestamp,
            String detectionPeriodLabel,
            Double detectionClosePrice,
            Integer resolutionCandleOffset,
            Long resolutionCandleTimestamp,
            String resolutionPeriodLabel,
            Double resolutionClosePrice,
            java.time.LocalDateTime updatedAt,
            java.time.LocalDateTime followUpSentAt,
            Double elliottEndpointPrice,
            org.example.stockwatch247.model.enums.ElliottSignalStage elliottSignalStage,
            String resolutionReason,
            boolean developingElliottCycle,
            String elliottCorrectionType,
            String elliottForecastLabel,
            List<String> elliottTransitionHistory
    ) {
        public SignalLifecycleView {
            elliottTransitionHistory = elliottTransitionHistory == null
                    ? List.of() : List.copyOf(elliottTransitionHistory);
        }
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

    public record ActiveRuleSummary(
            Long id,
            String patternFamily,
            String familyLabel,
            String interval,
            String intervalLabel,
            String tradeSignal
    ) {
    }

    public record TemporaryBulkFollowResult(
            int companies,
            int rulesPerCompany,
            int createdRules,
            int reactivatedRules,
            int alreadyActiveRules,
            int activeRules,
            String universeSnapshot
    ) {
    }

    private record AlertRuleKey(
            TimeInterval interval,
            TradeSignal signal,
            AlertPatternFamily patternFamily
    ) {
    }

    private record BulkAlertRuleKey(
            TimeInterval interval,
            TradeSignal signal,
            AlertPatternFamily patternFamily,
            Long stockAssetId
    ) {
    }
}
