package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

@Service
public class ScheduledAlertService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.stockwatch247.service.BackgroundJobDispatcher dispatcher;

    private org.example.stockwatch247.service.JobLeaseGuard leaseGuard;
    @org.springframework.beans.factory.annotation.Autowired
    void setLeaseGuard(org.example.stockwatch247.service.JobLeaseGuard leaseGuard) { this.leaseGuard = leaseGuard; }

    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int HIGHER_INTERVAL_SIGNAL_CANDLES = 100;
    private static final int DEVELOPING_WEEKLY_PARENT_CANDLES = 200;
    private static final int LOWER_DEGREE_ELLIOTT_CACHE_CANDLES = 1_000;

    private final AlertRuleRepository alertRuleRepository;
    private final AlertEventRepository alertEventRepository;
    private final CandleRepository candleRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService detectionService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;
    private HarmonicPatternDetectionService harmonicPatternDetectionService =
            new HarmonicPatternDetectionService();
    private HarmonicPatternPreferencesService harmonicPatternPreferencesService;
    private final AlertNotificationService notificationService;
    private final CandlestickSignalLifecycleService lifecycleService;
    private final AlertCheckJobStore jobStore;
    private final AlertScheduleRecoveryService scheduleRecoveryService;
    private final AnalysisPreferencesService preferencesService;
    private final CandlestickPatternPreferencesService patternPreferencesService;
    private final CrossPatternConfluenceService crossPatternConfluenceService;
    private final SignalScoringPreferencesService scoringPreferencesService;
    private ElliottWavePreferencesService elliottWavePreferencesService;
    private ElliottTradePlanService elliottTradePlanService;
    private ElliottProjectionService elliottProjectionService;
    private HarmonicStopPlanService harmonicStopPlanService;
    private TechnicalOutlookTrackingService technicalOutlookTrackingService;
    private final boolean scheduleEnabled;
    private final boolean dailyElliottEnabled;
    private final boolean weeklyElliottEnabled;
    private final boolean monthlyElliottEnabled;
    private final Duration jobLease;
    private final Duration retryDelay;
    private final int maximumAttempts;

    private java.util.function.Consumer<Runnable> deliveryTransaction = Runnable::run;
    @Autowired
    void setDeliveryTransaction(org.springframework.transaction.PlatformTransactionManager manager) {
        var template = new org.springframework.transaction.support.TransactionTemplate(manager);
        deliveryTransaction = action -> template.executeWithoutResult(status -> { JobLeaseGuard.requireOwnership(); action.run(); });
    }

    @Autowired
    public ScheduledAlertService(AlertRuleRepository alertRuleRepository,
                                 AlertEventRepository alertEventRepository,
                                 CandleRepository candleRepository,
                                 MarketDataService marketDataService,
                                 TechnicalIndicatorEnrichmentService enrichmentService,
                                 CandlePatternDetectionService detectionService,
                                 ElliottWaveDetectionService elliottWaveDetectionService,
                                 AlertNotificationService notificationService,
                                 CandlestickSignalLifecycleService lifecycleService,
                                 AlertCheckJobStore jobStore,
                                 AlertScheduleRecoveryService scheduleRecoveryService,
                                 @Value("${alerts.schedule.enabled:true}") boolean scheduleEnabled,
                                 @Value("${alerts.elliott.daily-enabled:true}") boolean dailyElliottEnabled,
                                 @Value("${alerts.elliott.weekly-enabled:true}") boolean weeklyElliottEnabled,
                                 @Value("${alerts.elliott.monthly-enabled:true}") boolean monthlyElliottEnabled,
                                 @Value("${alerts.schedule.job-lease-seconds:300}") long jobLeaseSeconds,
                                 @Value("${alerts.schedule.retry-delay-seconds:60}") long retryDelaySeconds,
                                 @Value("${alerts.schedule.maximum-attempts:3}") int maximumAttempts,
                                 AnalysisPreferencesService preferencesService,
                                 CandlestickPatternPreferencesService patternPreferencesService,
                                 CrossPatternConfluenceService crossPatternConfluenceService,
                                 SignalScoringPreferencesService scoringPreferencesService) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertEventRepository = alertEventRepository;
        this.candleRepository = candleRepository;
        this.marketDataService = marketDataService;
        this.enrichmentService = enrichmentService;
        this.detectionService = detectionService;
        this.elliottWaveDetectionService = elliottWaveDetectionService;
        this.notificationService = notificationService;
        this.lifecycleService = lifecycleService;
        this.jobStore = jobStore;
        this.scheduleRecoveryService = scheduleRecoveryService;
        this.preferencesService = preferencesService;
        this.patternPreferencesService = patternPreferencesService;
        this.crossPatternConfluenceService = crossPatternConfluenceService == null
                ? new CrossPatternConfluenceService(detectionService)
                : crossPatternConfluenceService;
        this.scoringPreferencesService = scoringPreferencesService;
        this.scheduleEnabled = scheduleEnabled;
        this.dailyElliottEnabled = dailyElliottEnabled;
        this.weeklyElliottEnabled = weeklyElliottEnabled;
        this.monthlyElliottEnabled = monthlyElliottEnabled;
        this.jobLease = Duration.ofSeconds(Math.max(1L, jobLeaseSeconds));
        this.retryDelay = Duration.ofSeconds(Math.max(1L, retryDelaySeconds));
        this.maximumAttempts = Math.max(1, maximumAttempts);
    }

    @Autowired
    void configureElliottWavePreferences(ElliottWavePreferencesService elliottWavePreferencesService) {
        this.elliottWavePreferencesService = elliottWavePreferencesService;
    }

    @Autowired
    void configureElliottTradePlans(ElliottTradePlanService elliottTradePlanService) {
        this.elliottTradePlanService = elliottTradePlanService;
    }

    @Autowired
    void configureElliottProjections(ElliottProjectionService elliottProjectionService) {
        this.elliottProjectionService = elliottProjectionService;
    }

    @Autowired
    void configureHarmonicStopPlans(HarmonicStopPlanService harmonicStopPlanService) {
        this.harmonicStopPlanService = harmonicStopPlanService;
    }

    @Autowired
    void configureHarmonicPatterns(HarmonicPatternDetectionService harmonicPatternDetectionService) {
        if (harmonicPatternDetectionService != null) {
            this.harmonicPatternDetectionService = harmonicPatternDetectionService;
        }
    }

    @Autowired
    void configureHarmonicPatternPreferences(HarmonicPatternPreferencesService preferencesService) {
        this.harmonicPatternPreferencesService = preferencesService;
    }

    @Autowired
    void configureTechnicalOutlookTracking(TechnicalOutlookTrackingService trackingService) {
        this.technicalOutlookTrackingService = trackingService;
    }

    ScheduledAlertService(AlertRuleRepository alertRuleRepository,
                          AlertEventRepository alertEventRepository,
                          CandleRepository candleRepository,
                          MarketDataService marketDataService,
                          TechnicalIndicatorEnrichmentService enrichmentService,
                          CandlePatternDetectionService detectionService,
                          ElliottWaveDetectionService elliottWaveDetectionService,
                          AlertNotificationService notificationService,
                          CandlestickSignalLifecycleService lifecycleService,
                          AlertCheckJobStore jobStore,
                          AlertScheduleRecoveryService scheduleRecoveryService,
                          boolean scheduleEnabled,
                          boolean weeklyElliottEnabled,
                          boolean monthlyElliottEnabled,
                          long jobLeaseSeconds,
                          long retryDelaySeconds,
                          int maximumAttempts) {
        this(alertRuleRepository, alertEventRepository, candleRepository, marketDataService,
                enrichmentService, detectionService, elliottWaveDetectionService, notificationService,
                lifecycleService, jobStore, scheduleRecoveryService, scheduleEnabled,
                true, weeklyElliottEnabled, monthlyElliottEnabled, jobLeaseSeconds, retryDelaySeconds,
                maximumAttempts, null, null, null, null);
    }

    @Scheduled(cron = "${alerts.schedule.daily-cron:0 0 0 * * TUE-SAT}", zone = "${alerts.schedule.zone:Europe/Brussels}")
    public void enqueueDailyChecks() {
        enqueueDueRuns(TimeInterval.DAILY);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverMissedChecksOnStartup() {
        recoverMissedChecks();
    }

    @Scheduled(cron = "${alerts.schedule.weekly-cron:0 0 0 * * SAT}", zone = "${alerts.schedule.zone:Europe/Brussels}")
    public void enqueueWeeklyChecks() {
        enqueueDueRuns(TimeInterval.WEEKLY);
    }

    @Scheduled(cron = "${alerts.schedule.monthly-cron:0 0 0 1 * *}", zone = "${alerts.schedule.zone:Europe/Brussels}")
    public void enqueueMonthlyChecks() {
        enqueueDueRuns(TimeInterval.MONTHLY);
    }

    @Scheduled(fixedDelayString = "${alerts.schedule.recovery-delay-ms:60000}",
            initialDelayString = "${alerts.schedule.recovery-initial-delay-ms:5000}")
    public void recoverMissedChecks() {
        if (!scheduleEnabled) {
            return;
        }
        AlertScheduleRecoveryService.RecoveryResult result = scheduleRecoveryService.enqueueAllDueRuns();
        if (result.scheduledRuns() > 0) {
            System.out.println("Recovered " + result.scheduledRuns() + " due alert schedule(s); queued "
                    + result.queuedJobs() + " symbol check(s).");
        }
    }

    @Scheduled(fixedDelayString = "${alerts.schedule.worker-delay-ms:15000}", initialDelayString = "${alerts.schedule.initial-delay-ms:30000}")
    public void dispatchPending() { dispatcher.submit("alerts", this::processNextQueuedCheck); }

    public void processNextQueuedCheck() {
        if (!scheduleEnabled) {
            return;
        }
        var claimedJob = jobStore.claimNext(jobLease);
        if (claimedJob.isEmpty()) {
            return;
        }
        AlertCheckJobStore.AlertCheckJob job = claimedJob.get();
        try (var claim = leaseGuard == null ? null : leaseGuard.protect(() -> jobStore.renew(job, jobLease), jobLease)) {
            processSymbolInterval(job.symbol(), job.interval(), job.scheduledFor());
            if (!jobStore.complete(job)) throw new IllegalStateException("Job ownership changed before acknowledgement.");
        } catch (RuntimeException e) {
            jobStore.retryOrFail(job, e.getMessage(), maximumAttempts, retryDelay);
            System.err.println("Alert check failed for " + job.symbol() + " " + job.interval() + ": " + e.getMessage());
        } finally {
            int remainingJobs = jobStore.pendingCount();
            if (remainingJobs == 0) {
                System.out.println("All queued alert checks completed.");
            } else {
                System.out.println("Alert check completed for " + job.symbol() + " " + job.interval()
                        + " scheduled for " + job.scheduledFor()
                        + ". Pending jobs: " + remainingJobs);
            }
        }
    }

    private void enqueueDueRuns(TimeInterval interval) {
        if (!scheduleEnabled) {
            return;
        }
        AlertScheduleRecoveryService.RecoveryResult result = scheduleRecoveryService.enqueueDueRuns(interval);
        if (result.scheduledRuns() > 0) {
            System.out.println("Queued " + result.queuedJobs() + " " + interval
                    + " alert check(s) for " + result.scheduledRuns() + " due schedule(s).");
        }
    }

    @Scheduled(cron = "${alerts.schedule.cleanup-cron:0 30 2 * * *}")
    public void cleanupFinishedJobs() {
        if (!scheduleEnabled) {
            return;
        }
        jobStore.removeFinishedBefore(Duration.ofDays(30));
    }

    public void processSymbolInterval(String symbol, TimeInterval interval) {
        processSymbolInterval(symbol, interval, null);
    }

    void processSymbolInterval(String symbol, TimeInterval interval, Instant scheduledFor) {
        try (var scope = AnalysisComputationScope.open()) { processSymbolIntervalScoped(symbol, interval, scheduledFor); }
    }

    private void processSymbolIntervalScoped(String symbol, TimeInterval interval, Instant scheduledFor) {
        String apiInterval = toApiInterval(interval);
        int signalCandleCount = signalCandleCount(interval);
        List<AlertRule> rules = alertRuleRepository
                .findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(symbol, interval);
        int requiredHistory = enrichmentService.requiredInputCandles(signalCandleCount, interval);
        int configuredAtrHistory = rules.stream()
                .filter(rule -> rule.getPatternFamily() == AlertPatternFamily.CANDLESTICK)
                .mapToInt(rule -> circuitBreakerAtrPeriod(rule, interval))
                .max()
                .orElse(0);
        requiredHistory = Math.max(requiredHistory, Math.max(
                configuredAtrHistory,
                lifecycleService.requiredCandlestickAtrHistory(symbol, interval)));
        if (elliottProjectionService != null
                && elliottProjectionService.hasOpenProjections(symbol, interval)) {
            requiredHistory = Math.max(
                    requiredHistory, ElliottProjectionService.REQUIRED_CANDLE_HISTORY);
        }
        if (isElliottEnabled(interval)) {
            requiredHistory = Math.max(
                    requiredHistory,
                    enrichmentService.requiredElliottInputCandles(signalCandleCount, interval)
            );
        }
        MarketDataService.CandleSyncResult syncResult = marketDataService
                .syncCandlesForAnalysis(symbol, apiInterval, requiredHistory);
        if (syncResult.source() == MarketDataService.CandleSource.CACHE_REFRESH_IN_PROGRESS) {
            throw new IllegalStateException("Candle refresh is already running; retrying this alert job later");
        }
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed: " + syncResult.failureMessage());
        }
        PageRequest historyWindow = PageRequest.of(
                0,
                requiredHistory
        );
        List<Candle> storedCandles = scheduledFor == null
                ? candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                        symbol, apiInterval, historyWindow)
                : candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                        symbol, apiInterval, scheduledFor.getEpochSecond(), historyWindow);
        List<Candle> candles = new ArrayDeque<>(storedCandles)
                .stream()
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();

        if (candles.size() < 2) {
            System.out.println("Skipping alert check for " + symbol + " " + interval + ": not enough candles.");
            return;
        }

        List<EnrichedCandle> elliottCandles = isElliottEnabled(interval)
                ? enrichmentService.enrichForElliott(candles, signalCandleCount, interval)
                : List.of();
        List<EnrichedCandle> developingElliottCandles = isElliottEnabled(interval)
                ? enrichmentService.enrichForElliott(
                candles, developingElliottParentCandleCount(interval), interval)
                : List.of();
        if (!elliottCandles.isEmpty()) {
            int initialized = lifecycleService.initializeUntrackedElliott(
                    symbol,
                    interval,
                    elliottCandles,
                    elliottWaveDetectionService
            );
            if (initialized > 0) {
                System.out.println("Initialized lifecycle tracking for " + initialized
                        + " historical Elliott signal(s) on " + symbol + " " + interval + ".");
            }
        }

        CandlestickSignalLifecycleService.LifecycleEvaluationResult lifecycleResult =
                lifecycleService.evaluatePending(
                        symbol,
                        interval,
                        candles,
                        elliottCandles,
                        elliottWaveDetectionService
                );
        if (lifecycleResult.resolved() > 0) {
            System.out.println("Resolved " + lifecycleResult.resolved()
                    + " signal lifecycle follow-up(s) for " + symbol + " " + interval + ".");
        }

        if (elliottTradePlanService != null) {
            int resolvedTradePlans = elliottTradePlanService.evaluateActivePlans(symbol, interval, candles);
            if (resolvedTradePlans > 0) {
                System.out.println("Resolved " + resolvedTradePlans
                        + " Elliott possible-trade plan(s) for " + symbol + " " + interval + ".");
            }
        }
        if (elliottProjectionService != null) {
            int evaluatedProjections = elliottProjectionService.evaluateOpenProjections(
                    symbol, interval, candles);
            if (evaluatedProjections > 0) {
                System.out.println("Evaluated " + evaluatedProjections
                        + " Elliott projection set(s) for " + symbol + " " + interval + ".");
            }
        }
        if (harmonicStopPlanService != null) {
            int resolvedHarmonicPlans = harmonicStopPlanService.evaluateActivePlans(
                    symbol, interval, candles);
            if (resolvedHarmonicPlans > 0) {
                System.out.println("Resolved " + resolvedHarmonicPlans
                        + " harmonic outcome plan(s) for " + symbol + " " + interval + ".");
            }
        }

        if (rules.isEmpty()) {
            evaluateTrackedOutlook(symbol, interval);
            return;
        }

        List<EnrichedCandle> enrichedCandles = enrichmentService.enrich(candles, signalCandleCount, interval);
        long latestCompletedTimestamp = candles.getLast().getTimestamp();
        List<EnrichedCandle> lowerDegreeElliottCandles = loadLowerDegreeElliottCandles(
                symbol, interval, rules, developingElliottCandles, scheduledFor);
        processDevelopingElliott(
                symbol, interval, rules, candles, developingElliottCandles,
                lowerDegreeElliottCandles, latestCompletedTimestamp);
        Map<Long, CrossPatternConfluenceService.Timeline> confluenceTimelines = new java.util.HashMap<>();
        for (AlertRule rule : rules) {
            if (rule.getPatternFamily() == AlertPatternFamily.HARMONIC_FORMATION) {
                List<HarmonicPatternDetectionService.HarmonicFormation> harmonicFormations =
                        AnalysisComputationScope.memo(java.util.List.of("harmonic-history", harmonicSettings(rule)),
                                () -> harmonicDetector(rule).detectHistorical(candles)).stream()
                                .filter(formation -> formation.confirmationTimestamp() == latestCompletedTimestamp)
                                .toList();
                for (HarmonicPatternDetectionService.HarmonicFormation formation : harmonicFormations) {
                    if (rule.getTradeSignal() != formation.tradeSignal()) continue;
                    CandlePattern pattern = HarmonicPatternDetectionService.signalPattern(formation.pattern());
                    if (!alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(
                            rule, pattern, formation.confirmationTimestamp())) {
                        sendAndRecordHarmonic(rule, formation, candles,
                                confluenceTimeline(rule, candles, enrichedCandles, elliottCandles,
                                        interval, confluenceTimelines));
                    }
                }
                continue;
            }
            List<DetectedSignal> detectedSignals = detectSignals(enrichedCandles, elliottCandles, rule, interval);
            for (DetectedSignal signal : detectedSignals) {
                if (rule.getTradeSignal() != signal.tradeSignal()
                        || rule.getPatternFamily() != signalFamily(signal)) {
                    continue;
                }
                if (signalFamily(signal) == AlertPatternFamily.ELLIOTT_WAVE) {
                    ElliottWaveDetectionService detector = elliottDetector(rule);
                    ElliottWaveDetectionService.ElliottWaveStructure structure =
                            detector.findStructureForSignal(
                                            elliottCandles,
                                            signal.pattern(),
                                            signal.candleTimestamp())
                                    .orElse(null);
                    ElliottWaveSignalLifecyclePolicy.LifecycleBoundaries boundaries = structure == null
                            ? null
                            : ElliottWaveSignalLifecyclePolicy.boundaries(
                                            signal.pattern(),
                                            signal.tradeSignal(),
                                            structure)
                                    .orElse(null);
                    if (boundaries == null || hasElliottCycleStageForUser(rule, boundaries)) {
                        continue;
                    }
                    sendAndRecord(rule, signal, candles, structure,
                            confluenceTimeline(rule, candles, enrichedCandles, elliottCandles,
                                    interval, confluenceTimelines));
                    continue;
                }
                if (!alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(
                        rule, signal.pattern(), signal.candleTimestamp())) {
                    sendAndRecord(rule, signal, candles, null,
                            confluenceTimeline(rule, candles, enrichedCandles, elliottCandles,
                                    interval, confluenceTimelines));
                }
            }
        }
        evaluateTrackedOutlook(symbol, interval);
    }

    private void evaluateTrackedOutlook(String symbol, TimeInterval interval) {
        if (technicalOutlookTrackingService == null) return;
        TechnicalOutlookTrackingService.EvaluationResult result =
                technicalOutlookTrackingService.evaluate(symbol, interval);
        if (result.baselinesEstablished() > 0 || result.changesCreated() > 0) {
            System.out.println("Automated technical outlook evaluated for " + symbol + " " + interval
                    + ": " + result.baselinesEstablished() + " baseline(s), "
                    + result.changesCreated() + " change notification(s).");
        }
    }

    void processDevelopingElliott(
            String symbol,
            TimeInterval interval,
            List<AlertRule> rules,
            List<Candle> candles,
            List<EnrichedCandle> elliottCandles,
            long latestCompletedTimestamp) {
        processDevelopingElliott(symbol, interval, rules, candles, elliottCandles,
                List.of(), latestCompletedTimestamp);
    }

    void processDevelopingElliott(
            String symbol,
            TimeInterval interval,
            List<AlertRule> rules,
            List<Candle> candles,
            List<EnrichedCandle> elliottCandles,
            List<EnrichedCandle> lowerDegreeElliottCandles,
            long latestCompletedTimestamp) {
        if (elliottCandles.isEmpty()) return;
        for (AlertRule rule : rules) {
            if (rule.getPatternFamily() != AlertPatternFamily.ELLIOTT_WAVE) continue;
            ElliottWaveDetectionService detector = elliottDetector(rule);
            int minimumConfidence = detector.minimumSignalConfidence();
            for (ElliottWaveDetectionService.DevelopingImpulse candidate
                    : lowerDegreeElliottCandles == null || lowerDegreeElliottCandles.isEmpty()
                    ? detector.findDevelopingImpulses(elliottCandles)
                    : detector.findDevelopingImpulses(
                    elliottCandles, lowerDegreeElliottCandles)) {
                if (candidate.confirmationTimestamp() != latestCompletedTimestamp) continue;
                if (candidate.confidenceScore() < minimumConfidence) continue;
                AlertEvent event = alertEventRepository
                        .findFirstByAlertRuleAndElliottDevelopmentKeyOrderByIdAsc(
                                rule, candidate.developmentKey())
                        .orElse(null);
                if (event == null) {
                    event = alertEventRepository
                            .findFirstByAlertRuleAndPatternAndSignalCandleTimestampOrderByIdAsc(
                                    rule, candidate.pattern(), candidate.confirmationTimestamp())
                            .orElse(null);
                    if (event != null && event.getElliottDevelopmentKey() == null) {
                        event.setElliottDevelopmentKey(candidate.developmentKey());
                    }
                }
                if (event == null) {
                    if (candidate.stage() != ElliottSignalStage.WAVE_II_END
                            || rule.getTradeSignal() != candidate.expectedMove()) continue;
                    event = new AlertEvent();
                    event.setAlertRule(rule);
                    event.setElliottDevelopmentKey(candidate.developmentKey());
                    event.setDetectionCandleTimestamp(candidate.confirmationTimestamp());
                    event.setDetectionClosePrice(candidate.confirmationClose());
                } else if (event.getLifecycleStatus() != SignalLifecycleStatus.DETECTED) {
                    continue;
                }

                ElliottSignalStage previousStage = event.getElliottSignalStage();
                boolean created = previousStage == null;
                boolean stageAdvanced = created
                        || candidate.stage().progressionOrder() > previousStage.progressionOrder();
                boolean endpointRevised = !created && candidate.stage() == previousStage
                        && (event.getElliottEndpointTimestamp() == null
                        || candidate.confirmationTimestamp() > event.getElliottEndpointTimestamp());
                boolean countRevised = !created && candidate.stage() == previousStage
                        && !java.util.Objects.equals(
                        event.getElliottStructureSnapshot(), elliottSnapshot(candidate.points()));
                boolean candidateChanged = stageAdvanced || endpointRevised || countRevised;
                boolean projectionMissing = elliottProjectionService != null
                        && !elliottProjectionService.hasProjection(
                        event, candidate.stage(), candidate.points());
                if (!candidateChanged && !projectionMissing) continue;

                if (candidateChanged) applyDevelopingElliott(event, candidate);
                java.util.Optional<ElliottTradePlanService.PreparedPlan> preparedTradePlan =
                        !candidateChanged || elliottTradePlanService == null
                                ? java.util.Optional.empty()
                                : elliottTradePlanService.prepare(
                                event, candidate.stage(), candidate.direction(), candidate.expectedMove(),
                                candidate.confirmationTimestamp(), candidate.confirmationClose(),
                                candidate.points(), candles, interval);
                java.util.Optional<ElliottProjectionService.PreparedProjection> preparedProjection =
                        elliottProjectionService == null ? java.util.Optional.empty()
                                : elliottProjectionService.prepare(
                                candidate.stage(), candidate.direction(), candidate.expectedMove(),
                                candidate.confirmationTimestamp(), candidate.confirmationClose(),
                                candidate.points(), candles, interval);
                if (candidate.completedStructure() != null) {
                    ElliottWaveSignalLifecyclePolicy.cycleKey(candidate.completedStructure())
                            .ifPresent(event::setElliottCycleKey);
                }
                if (candidate.stage() == ElliottSignalStage.CORRECTION_END
                        && candidate.completedStructure() != null) {
                    AnalysisPreferencesService.IntervalProfile profile = preferencesService == null
                            ? null : preferencesService.get(rule.getUser()).profile(interval);
                    lifecycleService.initializeElliottTracking(
                            event, candidate.completedStructure(), profile);
                    event.setElliottDeveloping(false);
                }
                AlertEvent deliveryEvent = event;
                deliveryTransaction.accept(() -> {
                if (preparedTradePlan.isPresent() || preparedProjection.isPresent()) {
                    alertEventRepository.saveAndFlush(deliveryEvent);
                    if (deliveryEvent.getId() != null && elliottTradePlanService != null
                            && preparedTradePlan.isPresent()) {
                        elliottTradePlanService.persist(deliveryEvent, preparedTradePlan.get());
                    }
                    if (deliveryEvent.getId() != null && elliottProjectionService != null
                            && preparedProjection.isPresent()) {
                        elliottProjectionService.persist(deliveryEvent, preparedProjection.get());
                    }
                } else if (created) {
                    // Establish the database uniqueness key before delivering an email.
                    alertEventRepository.saveAndFlush(deliveryEvent);
                }
                if (stageAdvanced) {
                    DetectedSignal signal = developingSignal(candidate);
                    if (notificationService.sendDevelopingElliottEmail(rule, signal, deliveryEvent, false, created) && !notificationService.usesDurableDelivery()) {
                        if (created) deliveryEvent.setInitialEmailSentAt(java.time.LocalDateTime.now());
                        else deliveryEvent.setFollowUpSentAt(java.time.LocalDateTime.now());
                    }
                }
                alertEventRepository.save(deliveryEvent);
                });
            }
        }
        invalidateBrokenDevelopingElliott(symbol, interval, elliottCandles, candles.getLast());
    }

    private boolean hasElliottCycleStageForUser(
            AlertRule rule,
            ElliottWaveSignalLifecyclePolicy.LifecycleBoundaries boundaries) {
        return !alertEventRepository.findElliottCycleStageForUser(
                rule.getUser(), rule.getStockAsset().getTickerSymbol(), rule.getInterval(),
                boundaries.cycleKey(), boundaries.stage(), PageRequest.of(0, 1)).isEmpty();
    }

    private List<EnrichedCandle> loadLowerDegreeElliottCandles(
            String symbol,
            TimeInterval parentInterval,
            List<AlertRule> rules,
            List<EnrichedCandle> parentCandles,
            Instant scheduledFor) {
        if (parentCandles.isEmpty() || rules.stream().noneMatch(
                rule -> rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE)) {
            return List.of();
        }
        String childApiInterval;
        TimeInterval childInterval;
        switch (parentInterval) {
            case DAILY -> {
                childApiInterval = "60min";
                childInterval = TimeInterval.ONE_HOUR;
            }
            case WEEKLY -> {
                childApiInterval = "1d";
                childInterval = TimeInterval.DAILY;
            }
            case MONTHLY -> {
                childApiInterval = "1wk";
                childInterval = TimeInterval.WEEKLY;
            }
            default -> {
                return List.of();
            }
        }
        if (!hasFreshLowerDegreeCache(symbol, childApiInterval, scheduledFor)) {
            try {
                // Bootstrap a persisted child-series cache once. Later scans request only
                // a small latest-candle delta and share the normal provider cooldown.
                MarketDataService.CandleSyncResult sync = marketDataService
                        .syncCandlesForAnalysis(
                                symbol, childApiInterval, LOWER_DEGREE_ELLIOTT_CACHE_CANDLES);
                if (sync != null && !sync.successful()
                        && sync.source() != MarketDataService.CandleSource.CACHE_REFRESH_IN_PROGRESS) {
                    System.err.println("Lower-degree Elliott refresh failed for " + symbol + " "
                            + childApiInterval + ": " + sync.failureMessage());
                }
            } catch (RuntimeException exception) {
                System.err.println("Lower-degree Elliott refresh failed for " + symbol + " "
                        + childApiInterval + ": " + exception.getMessage());
            }
        }
        long firstParentTimestamp = parentCandles.getFirst().timestamp();
        List<Candle> stored = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol, childApiInterval, firstParentTimestamp);
        if (stored == null || stored.isEmpty()) return List.of();
        long asOfExclusive = scheduledFor == null
                ? Long.MAX_VALUE : scheduledFor.getEpochSecond();
        List<Candle> childCandles = stored.stream()
                .filter(candle -> candle.getTimestamp() < asOfExclusive)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        long firstParentPeriodEnd = parentCandles.size() > 1
                ? parentCandles.get(1).timestamp() : Long.MAX_VALUE;
        if (childCandles.isEmpty()
                || childCandles.getFirst().getTimestamp() >= firstParentPeriodEnd) {
            return List.of();
        }
        try {
            return enrichmentService.enrichForElliott(
                    childCandles, childCandles.size(), childInterval);
        } catch (RuntimeException exception) {
            // Child data improves structural validation, but a bad optional child
            // cache must not abort every alert family for the parent symbol.
            System.err.println("Lower-degree Elliott enrichment failed for " + symbol + " "
                    + childApiInterval + ": " + exception.getMessage()
                    + ". Falling back to parent-series subdivision validation.");
            return List.of();
        }
    }

    private boolean hasFreshLowerDegreeCache(
            String symbol, String childApiInterval, Instant scheduledFor) {
        if (scheduledFor == null
                || candleRepository.countBySymbolAndTimeInterval(symbol, childApiInterval)
                < LOWER_DEGREE_ELLIOTT_CACHE_CANDLES) {
            return false;
        }
        List<Candle> latest = candleRepository
                .findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, childApiInterval);
        if (latest == null || latest.isEmpty()) return false;
        long toleratedAgeSeconds = switch (childApiInterval) {
            case "60min" -> Duration.ofDays(2).toSeconds();
            case "1d" -> Duration.ofDays(4).toSeconds();
            case "1wk" -> Duration.ofDays(14).toSeconds();
            default -> 0L;
        };
        return latest.getFirst().getTimestamp() >= scheduledFor.getEpochSecond() - toleratedAgeSeconds;
    }

    private void applyDevelopingElliott(
            AlertEvent event,
            ElliottWaveDetectionService.DevelopingImpulse candidate) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        event.setPattern(candidate.pattern());
        event.setTradeSignal(candidate.expectedMove());
        event.setSignalCandleTimestamp(candidate.confirmationTimestamp());
        event.setSignalStrength(strength(candidate.confidenceScore()));
        event.setConfidenceScore(candidate.confidenceScore());
        event.setFactoryConfidenceScore(candidate.confidenceScore());
        event.setScoreVersion("ELLIOTT_DEVELOPING_V1");
        event.setConfidenceReasons(candidate.evidence());
        event.setClosePrice(candidate.confirmationClose());
        event.setSentAt(now);
        event.setReadAt(null);
        event.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
        event.setElliottSignalStage(candidate.stage());
        event.setElliottDeveloping(candidate.stage() != ElliottSignalStage.CORRECTION_END);
        event.setElliottEndpointTimestamp(candidate.confirmationTimestamp());
        event.setElliottEndpointPrice(candidate.endpointPrice());
        event.setElliottCorrectionType(candidate.correctionType());
        event.setElliottForecastLabel(candidate.forecastLabel());
        event.setElliottStageUpdatedAt(now);
        event.setElliottStructureSnapshot(elliottSnapshot(candidate.points()));
        double structureHigh = candidate.points().stream()
                .mapToDouble(ElliottWaveDetectionService.ElliottWavePoint::price).max().orElse(candidate.endpointPrice());
        double structureLow = candidate.points().stream()
                .mapToDouble(ElliottWaveDetectionService.ElliottWavePoint::price).min().orElse(candidate.endpointPrice());
        event.setPatternHigh(structureHigh);
        event.setPatternLow(structureLow);
        event.setInvalidationPrice(candidate.stopLossPrice());
        event.setTradeEntryPrice(candidate.confirmationClose());
        event.setStructuralStopPrice(candidate.stopLossPrice());
        event.setStopLossPrice(candidate.stopLossPrice());
        boolean validRawTarget = Double.isFinite(candidate.targetPrice()) && candidate.targetPrice() > 0.0;
        event.setConfirmationTriggerPrice(validRawTarget ? candidate.targetPrice() : null);
        event.setProfitTargetPrice(validRawTarget ? candidate.targetPrice() : null);
        double risk = Math.abs(candidate.confirmationClose() - candidate.stopLossPrice());
        event.setRewardRiskRatio(!validRawTarget || risk <= .000001 ? null
                : Math.abs(candidate.targetPrice() - candidate.confirmationClose()) / risk);
        event.setTradePlanVersion(validRawTarget ? "ELLIOTT_NEXT_WAVE_V1" : null);
        event.setLifecycleResolutionReason(null);
        event.setLifecycleUpdatedAt(now);
        event.appendElliottTransition("%s|%s|%s|%.10f|%.10f|%.10f|%s".formatted(
                now, candidate.stage(), candidate.expectedMove(), candidate.confirmationClose(),
                candidate.stopLossPrice(), candidate.targetPrice(),
                candidate.correctionType() == null ? "NONE" : candidate.correctionType()));
    }

    private DetectedSignal developingSignal(ElliottWaveDetectionService.DevelopingImpulse candidate) {
        return new DetectedSignal(
                candidate.pattern(), candidate.expectedMove(), strength(candidate.confidenceScore()),
                candidate.confidenceScore(), candidate.evidence(), candidate.confirmationTimestamp(),
                candidate.confirmationClose(), candidate.confidenceScore());
    }

    private String elliottSnapshot(List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        return points.stream().map(point -> "%s|%d|%.10f|%s".formatted(
                        point.label(), point.timestamp(), point.price(), point.pivotType()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void invalidateBrokenDevelopingElliott(
            String symbol,
            TimeInterval interval,
            List<EnrichedCandle> elliottCandles,
            Candle latest) {
        for (AlertEvent event : alertEventRepository.findDevelopingElliottEvents(symbol, interval)) {
            List<String> rows = event.getElliottStructureSnapshot() == null
                    ? List.of() : event.getElliottStructureSnapshot().lines().toList();
            if (rows.size() < 3) continue;
            String[] origin = rows.get(0).split("\\|", -1);
            String[] waveOne = rows.get(1).split("\\|", -1);
            if (origin.length < 3 || waveOne.length < 3) continue;
            double originPrice;
            double waveOnePrice;
            try {
                originPrice = Double.parseDouble(origin[2]);
                waveOnePrice = Double.parseDouble(waveOne[2]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            boolean bullish = event.getElliottDevelopmentKey().startsWith("BULLISH:");
            boolean originBroken = bullish
                    ? latest.getLowPrice() <= originPrice : latest.getHighPrice() >= originPrice;
            boolean overlapRuleStillApplies = event.getElliottSignalStage() == ElliottSignalStage.WAVE_III_END
                    || event.getElliottSignalStage() == ElliottSignalStage.WAVE_IV_END;
            boolean overlapBroken = overlapRuleStillApplies
                    && (bullish ? latest.getLowPrice() <= waveOnePrice
                    : latest.getHighPrice() >= waveOnePrice);
            ElliottWaveDetectionService detector = elliottDetector(event.getAlertRule());
            boolean anotherCountRemains = !originBroken && !overlapBroken
                    && detector.hasViableDevelopingHypothesis(
                    elliottCandles, event.getElliottDevelopmentKey(), event.getElliottSignalStage());
            ElliottWaveDetectionService.DevelopingInvalidation mismatch = originBroken || overlapBroken
                    || anotherCountRemains ? null : detector.findActionaryStructureMismatch(
                            elliottCandles, event.getElliottSignalStage(), parseElliottSnapshot(rows))
                            .orElse(null);
            if (!originBroken && !overlapBroken && mismatch == null) continue;
            String reason = originBroken
                    ? "Wave II retraced to or beyond the Wave I origin, invalidating the developing impulse."
                    : overlapBroken
                    ? "Wave IV entered Wave I price territory, invalidating the standard impulse count."
                    : mismatch.reason();
            event.setLifecycleStatus(SignalLifecycleStatus.INVALIDATED);
            event.setLifecycleResolutionReason(reason);
            event.setResolutionCandleTimestamp(latest.getTimestamp());
            event.setResolutionClosePrice(latest.getClosePrice());
            event.setElliottDeveloping(false);
            event.setLifecycleUpdatedAt(java.time.LocalDateTime.now());
            event.setSentAt(java.time.LocalDateTime.now());
            event.setReadAt(null);
            event.appendElliottTransition("%s|INVALIDATED|%s|%.10f|%s".formatted(
                    java.time.LocalDateTime.now(), event.getElliottSignalStage(),
                    latest.getClosePrice(), reason));
            DetectedSignal invalidation = new DetectedSignal(
                    event.getPattern(), event.getTradeSignal(), event.getSignalStrength(),
                    event.getConfidenceScore(), List.of(reason), latest.getTimestamp(),
                    latest.getClosePrice(), event.getElliottV1EligibilityScore() == null
                            ? event.getConfidenceScore() : event.getElliottV1EligibilityScore());
            if (notificationService.sendDevelopingElliottEmail(
                    event.getAlertRule(), invalidation, event, true)) {
                event.setFollowUpSentAt(java.time.LocalDateTime.now());
            }
            alertEventRepository.save(event);
        }
    }

    private List<ElliottWaveDetectionService.ElliottWavePoint> parseElliottSnapshot(List<String> rows) {
        List<ElliottWaveDetectionService.ElliottWavePoint> points = new java.util.ArrayList<>();
        for (String row : rows) {
            String[] fields = row.split("\\|", -1);
            if (fields.length != 4) continue;
            try {
                points.add(new ElliottWaveDetectionService.ElliottWavePoint(
                        fields[0], Long.parseLong(fields[1]), Double.parseDouble(fields[2]), fields[3]));
            } catch (NumberFormatException ignored) {
                return List.of();
            }
        }
        return List.copyOf(points);
    }

    private int circuitBreakerAtrPeriod(AlertRule rule, TimeInterval interval) {
        if (patternPreferencesService == null || rule == null || rule.getUser() == null) {
            return CandlestickPatternPreferencesService.factoryPreferences()
                    .circuitBreaker(interval).atrPeriod();
        }
        CandlestickPatternPreferencesService.PreferencesView preferences =
                patternPreferencesService.get(rule.getUser());
        return (preferences == null
                ? CandlestickPatternPreferencesService.factoryPreferences()
                : preferences).circuitBreaker(interval).atrPeriod();
    }

    private CrossPatternConfluenceService.Timeline confluenceTimeline(
            AlertRule rule,
            List<Candle> candles,
            List<EnrichedCandle> candlestickCandles,
            List<EnrichedCandle> elliottCandles,
            TimeInterval interval,
            Map<Long, CrossPatternConfluenceService.Timeline> timelines) {
        Long userId = rule.getUser() == null || rule.getUser().getId() == null
                ? -1L : rule.getUser().getId();
        return timelines.computeIfAbsent(userId, ignored -> {
            AnalysisPreferencesService.PreferencesView analysisPreferences = preferencesService == null
                    ? AnalysisPreferencesService.factoryPreferences()
                    : preferencesService.get(rule.getUser());
            AnalysisPreferencesService.IntervalProfile intervalProfile = analysisPreferences.profile(interval);
            CandlestickPatternPreferencesService.PreferencesView definitions =
                    patternPreferencesService == null
                            ? CandlestickPatternPreferencesService.factoryPreferences()
                            : patternPreferencesService.get(rule.getUser());
            return crossPatternConfluenceService.buildTimeline(
                    candles,
                    candlestickCandles,
                    elliottCandles,
                    interval,
                    preferencesService == null
                            ? CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval)
                            : preferencesService.trendDetectionRules(intervalProfile),
                    definitions,
                    elliottDetector(rule),
                    harmonicDetector(rule));
        });
    }

    private HarmonicPatternDetectionService harmonicDetector(AlertRule rule) {
        if (harmonicPatternPreferencesService == null || rule == null || rule.getUser() == null) {
            return harmonicPatternDetectionService;
        }
        return harmonicPatternPreferencesService.detector(rule.getUser(), harmonicPatternDetectionService);
    }

    private void sendAndRecordHarmonic(
            AlertRule rule,
            HarmonicPatternDetectionService.HarmonicFormation formation,
            List<Candle> candles,
            CrossPatternConfluenceService.Timeline confluenceTimeline) {
        Candle confirmationCandle = candles.stream()
                .filter(candle -> candle.getTimestamp() == formation.confirmationTimestamp())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The harmonic confirmation candle is missing from the completed cache."));
        CandlePattern pattern = HarmonicPatternDetectionService.signalPattern(formation.pattern());
        DetectedSignal baseSignal = new DetectedSignal(
                pattern,
                formation.tradeSignal(),
                strength(formation.qualityScore()),
                formation.qualityScore(),
                formation.reasons(),
                formation.confirmationTimestamp(),
                confirmationCandle.getClosePrice());
        DetectedSignal signal = crossPatternConfluenceService.apply(
                baseSignal, AlertPatternFamily.HARMONIC_FORMATION, confluenceTimeline,
                confluencePolicy(rule, AlertPatternFamily.HARMONIC_FORMATION));
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(pattern);
        event.setTradeSignal(formation.tradeSignal());
        event.setSignalCandleTimestamp(formation.confirmationTimestamp());
        event.setSignalStrength(signal.strength());
        event.setConfidenceScore(signal.confidenceScore());
        event.setFactoryConfidenceScore(signal.confidenceScore());
        event.setScoreVersion(HarmonicPatternDetectionService.RULE_VERSION);
        event.setConfidenceReasons(signal.reasons());
        event.setClosePrice(confirmationCandle.getClosePrice());
        HarmonicPatternDetectionService.HarmonicPoint endpoint = formation.points().getLast();
        event.setHarmonicEndpointTimestamp(endpoint.timestamp());
        event.setHarmonicEndpointPrice(endpoint.price());
        event.setHarmonicPointsSnapshot(harmonicPointsSnapshot(formation));
        event.setHarmonicMeasurementsSnapshot(harmonicMeasurementsSnapshot(formation));
        if (harmonicStopPlanService != null) {
            harmonicStopPlanService.prepare(
                    event, formation, formation.confirmationTimestamp(), confirmationCandle.getClosePrice());
        }

        deliveryTransaction.accept(() -> {
        alertEventRepository.saveAndFlush(event);
        if (notificationService.sendSignalEmail(rule, signal, event) && !notificationService.usesDurableDelivery()) {
            event.setInitialEmailSentAt(java.time.LocalDateTime.now());
        }
        alertEventRepository.save(event);
        });
    }

    private String harmonicPointsSnapshot(HarmonicPatternDetectionService.HarmonicFormation formation) {
        return formation.points().stream()
                .map(point -> "%s|%d|%.10f|%s".formatted(
                        point.label(), point.timestamp(), point.price(), point.pivotType()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String harmonicMeasurementsSnapshot(HarmonicPatternDetectionService.HarmonicFormation formation) {
        return formation.measurements().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> String.format(Locale.ROOT, "%s|%.10f", entry.getKey(), entry.getValue()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void sendAndRecord(AlertRule rule,
                               DetectedSignal signal,
                               List<Candle> candles,
                               ElliottWaveDetectionService.ElliottWaveStructure elliottStructure,
                               CrossPatternConfluenceService.Timeline confluenceTimeline) {
        AnalysisPreferencesService.PreferencesView preferences = preferencesService == null
                ? AnalysisPreferencesService.factoryPreferences()
                : preferencesService.get(rule.getUser());
        AnalysisPreferencesService.IntervalProfile profile = preferences.profile(rule.getInterval());
        DetectedSignal personalizedBaseSignal = preferences.custom()
                ? personalizeSignal(rule, signal, candles, profile)
                : signal;
        AlertPatternFamily family = signalFamily(signal);
        DetectedSignal personalizedSignal = crossPatternConfluenceService.apply(
                personalizedBaseSignal, family, confluenceTimeline, confluencePolicy(rule, family));
        DetectedSignal factorySignal = crossPatternConfluenceService.apply(
                signal, family, confluenceTimeline, confluencePolicy(rule, family));
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(signal.pattern());
        event.setTradeSignal(signal.tradeSignal());
        event.setSignalCandleTimestamp(signal.candleTimestamp());
        event.setSignalStrength(personalizedSignal.strength());
        event.setConfidenceScore(personalizedSignal.confidenceScore());
        event.setFactoryConfidenceScore(factorySignal.confidenceScore());
        event.setAnalysisProfileVersion(AnalysisPreferencesService.PROFILE_VERSION);
        event.setAnalysisProfileSnapshot(preferencesService == null ? null : preferencesService.snapshot(profile));
        if (signalFamily(signal) == AlertPatternFamily.ELLIOTT_WAVE) {
            event.setElliottV1EligibilityScore(signal.eligibilityScore());
        }
        event.setScoreVersion(preferences.custom() ? "PERSONALIZED_V1" : scoreVersion(signal));
        event.setConfidenceReasons(personalizedSignal.reasons());
        event.setClosePrice(signal.closePrice());
        if (signalFamily(signal) == AlertPatternFamily.CANDLESTICK) {
            CandlestickPatternPreferencesService.PreferencesView patternPreferences =
                    patternPreferencesService == null
                            ? CandlestickPatternPreferencesService.factoryPreferences()
                            : patternPreferencesService.get(rule.getUser());
            lifecycleService.initializeTracking(
                    event, personalizedSignal, candles, profile, patternPreferences);
        } else {
            lifecycleService.initializeElliottTracking(event, elliottStructure, profile);
        }

        java.util.Optional<ElliottTradePlanService.PreparedPlan> preparedTradePlan =
                elliottTradePlanService == null || elliottStructure == null
                        || event.getElliottSignalStage() == null
                        ? java.util.Optional.empty()
                        : elliottTradePlanService.prepare(
                        event, event.getElliottSignalStage(), elliottStructure.direction(),
                        personalizedSignal.tradeSignal(), personalizedSignal.candleTimestamp(),
                        personalizedSignal.closePrice(), elliottStructure.points(), candles,
                        rule.getInterval());
        java.util.Optional<ElliottProjectionService.PreparedProjection> preparedProjection =
                elliottProjectionService == null || elliottStructure == null
                        || event.getElliottSignalStage() == null
                        ? java.util.Optional.empty()
                        : elliottProjectionService.prepare(
                        event.getElliottSignalStage(), elliottStructure.direction(),
                        personalizedSignal.tradeSignal(), personalizedSignal.candleTimestamp(),
                        personalizedSignal.closePrice(), elliottStructure.points(), candles,
                        rule.getInterval());

        deliveryTransaction.accept(() -> {
        alertEventRepository.saveAndFlush(event);
        if (elliottTradePlanService != null && preparedTradePlan.isPresent()) {
            if (event.getId() != null) {
                elliottTradePlanService.persist(event, preparedTradePlan.get());
            }
        }
        if (elliottProjectionService != null && preparedProjection.isPresent()
                && event.getId() != null) {
            elliottProjectionService.persist(event, preparedProjection.get());
        }

        if (notificationService.sendSignalEmail(rule, personalizedSignal, event) && !notificationService.usesDurableDelivery()) {
            event.setInitialEmailSentAt(java.time.LocalDateTime.now());
        }
        alertEventRepository.save(event);
        });
    }

    private DetectedSignal personalizeSignal(AlertRule rule,
                                             DetectedSignal factorySignal,
                                             List<Candle> candles,
                                             AnalysisPreferencesService.IntervalProfile profile) {
        if (preferencesService == null) {
            return factorySignal;
        }
        TechnicalIndicatorProfile technicalProfile = preferencesService.technicalProfile(profile);
        List<EnrichedCandle> personalizedCandles = enrichmentService.enrich(
                candles, signalCandleCount(rule.getInterval()), technicalProfile);
        List<DetectedSignal> candidates = rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE
                ? elliottDetector(rule).detect(personalizedCandles)
                : detectionService.detect(personalizedCandles,
                preferencesService.trendDetectionRules(profile));
        DetectedSignal scored = candidates.stream()
                .filter(candidate -> candidate.pattern() == factorySignal.pattern())
                .filter(candidate -> candidate.tradeSignal() == factorySignal.tradeSignal())
                .filter(candidate -> candidate.candleTimestamp().equals(factorySignal.candleTimestamp()))
                .findFirst()
                .orElse(factorySignal);
        EnrichedCandle current = personalizedCandles.stream()
                .filter(candle -> candle.timestamp().equals(factorySignal.candleTimestamp()))
                .findFirst().orElse(null);
        ProfileAlignment alignment = profileAlignment(
                personalizedCandles, current, factorySignal.tradeSignal(), profile);
        int personalizedScore = Math.clamp(scored.confidenceScore() + alignment.adjustment(), 0, 100);
        List<String> reasons = new java.util.ArrayList<>(scored.reasons());
        reasons.add(alignment.availableVotes() == 0
                ? "Personal profile: no enabled directional indicator was available on the signal candle."
                : "Personal profile: %d aligned, %d neutral, and %d opposed across %d enabled indicators (%+d points)."
                        .formatted(alignment.aligned(), alignment.neutral(), alignment.opposed(),
                                alignment.availableVotes(), alignment.adjustment()));
        return new DetectedSignal(scored.pattern(), scored.tradeSignal(), strength(personalizedScore),
                personalizedScore, List.copyOf(reasons), scored.candleTimestamp(), scored.closePrice(),
                factorySignal.eligibilityScore(), scored.trendStartTimestamp());
    }

    private ProfileAlignment profileAlignment(List<EnrichedCandle> candles,
                                              EnrichedCandle current,
                                              TradeSignal direction,
                                              AnalysisPreferencesService.IntervalProfile profile) {
        if (current == null || direction != TradeSignal.BUY && direction != TradeSignal.SELL) {
            return ProfileAlignment.empty();
        }
        List<Integer> votes = new java.util.ArrayList<>();
        addVote(votes, profile.scoreRsi(), current.rsi(), current.rsi() <= profile.rsiBuyThreshold() ? 1
                : current.rsi() >= profile.rsiSellThreshold() ? -1 : 0);
        double emaDifference = percentDifference(current.fastEma(), current.slowEma());
        addVote(votes, profile.scoreEma(), emaDifference, thresholdVote(emaDifference, profile.emaThresholdPercent()));
        double smaDifference = percentDifference(current.close(), current.longSma());
        addVote(votes, profile.scoreLongSma(), smaDifference,
                thresholdVote(smaDifference, profile.longSmaThresholdPercent()));
        double macdPercent = current.close() == 0 ? Double.NaN
                : current.macdHistogram() / current.close() * 100.0;
        addVote(votes, profile.scoreMacd(), macdPercent,
                thresholdVote(macdPercent, profile.macdThresholdPercent()));
        addVote(votes, profile.scoreCci(), current.cci(), current.cci() <= profile.cciBuyThreshold() ? 1
                : current.cci() >= profile.cciSellThreshold() ? -1 : 0);
        double bandPosition = bandPosition(current.close(), current.lowerBollinger(), current.upperBollinger());
        addVote(votes, profile.scoreBollinger(), bandPosition,
                bandPosition <= 0 ? 1 : bandPosition >= 100 ? -1 : 0);
        double relativeVolume = current.averageVolume() == 0 ? Double.NaN
                : current.volume() / current.averageVolume()
                * (current.close() > current.open() ? 1 : current.close() < current.open() ? -1 : 0);
        addVote(votes, profile.scoreRelativeVolume(), relativeVolume,
                thresholdVote(relativeVolume, profile.relativeVolumeThreshold()));
        double vwapDifference = percentDifference(current.close(), current.rollingVwap());
        addVote(votes, profile.scoreVwap(), vwapDifference,
                thresholdVote(vwapDifference, profile.vwapThresholdPercent()));
        double valueAreaPosition = bandPosition(current.close(), current.volumeProfileValueAreaLow(),
                current.volumeProfileValueAreaHigh());
        addVote(votes, profile.scoreVolumeProfile(), valueAreaPosition,
                valueAreaPosition < 0 ? 1 : valueAreaPosition > 100 ? -1 : 0);
        double boundaryDistance = supportResistanceDistance(candles, current, profile.supportResistancePeriod());
        int boundaryVote = boundaryDistance >= 0 && boundaryDistance <= profile.supportResistanceAtrDistance() ? 1
                : boundaryDistance < 0 && boundaryDistance >= -profile.supportResistanceAtrDistance() ? -1 : 0;
        addVote(votes, profile.scoreSupportResistance(), boundaryDistance, boundaryVote);

        int directionVote = direction == TradeSignal.BUY ? 1 : -1;
        int aligned = (int) votes.stream().filter(vote -> vote == directionVote).count();
        int neutral = (int) votes.stream().filter(vote -> vote == 0).count();
        int opposed = votes.size() - aligned - neutral;
        int adjustment = votes.isEmpty() ? 0
                : (int) Math.round((double) (aligned - opposed) / votes.size() * 10.0);
        return new ProfileAlignment(adjustment, aligned, neutral, opposed, votes.size());
    }

    private void addVote(List<Integer> votes, boolean enabled, double value, int vote) {
        if (enabled && Double.isFinite(value)) votes.add(vote);
    }

    private int thresholdVote(double value, double threshold) {
        return value > threshold ? 1 : value < -threshold ? -1 : 0;
    }

    private double percentDifference(double value, double baseline) {
        return !Double.isFinite(value) || !Double.isFinite(baseline) || baseline == 0
                ? Double.NaN : (value - baseline) / Math.abs(baseline) * 100.0;
    }

    private double bandPosition(double value, double low, double high) {
        return !Double.isFinite(value) || !Double.isFinite(low) || !Double.isFinite(high) || high <= low
                ? Double.NaN : (value - low) / (high - low) * 100.0;
    }

    private double supportResistanceDistance(List<EnrichedCandle> candles,
                                             EnrichedCandle current,
                                             int lookback) {
        int currentIndex = candles.indexOf(current);
        if (currentIndex < lookback - 1 || !Double.isFinite(current.atr()) || current.atr() <= 0) {
            return Double.NaN;
        }
        List<EnrichedCandle> window = candles.subList(currentIndex - lookback + 1, currentIndex + 1);
        double support = window.stream().mapToDouble(EnrichedCandle::low).min().orElse(Double.NaN);
        double resistance = window.stream().mapToDouble(EnrichedCandle::high).max().orElse(Double.NaN);
        double supportDistance = (current.close() - support) / current.atr();
        double resistanceDistance = (resistance - current.close()) / current.atr();
        return supportDistance <= resistanceDistance ? supportDistance : -resistanceDistance;
    }

    private record ProfileAlignment(int adjustment, int aligned, int neutral, int opposed, int availableVotes) {
        private static ProfileAlignment empty() { return new ProfileAlignment(0, 0, 0, 0, 0); }
    }

    private SignalStength strength(int score) {
        if (score >= 85) return SignalStength.HIGH_CONFIDENCE;
        if (score >= 75) return SignalStength.MEDIUM_CONFIDENCE;
        if (score > 0) return SignalStength.LOW_CONFIDENCE;
        return SignalStength.WEAK_IGNORE;
    }

    private String toApiInterval(TimeInterval interval) {
        return interval.analysisApiValue();
    }

    private List<DetectedSignal> detectSignals(List<EnrichedCandle> candlestickCandles,
            List<EnrichedCandle> elliottCandles, AlertRule rule, TimeInterval interval) {
        if (rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE) {
            if (elliottCandles.isEmpty()) return List.of();
            Object settings = elliottWavePreferencesService == null ? "factory"
                    : elliottWavePreferencesService.get(rule.getUser()).profile(interval).rules();
            return AnalysisComputationScope.memo(java.util.List.of("elliott-signals", settings),
                    () -> elliottDetector(rule).detectAlertSignals(elliottCandles).stream()
                            .filter(this::isActionableElliottTurningPoint).toList());
        }
        var trend = preferencesService == null
                ? CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval)
                : preferencesService.trendDetectionRules(preferencesService.profile(rule.getUser(), interval));
        var definitions = patternPreferencesService == null ? CandlestickPatternPreferencesService.factoryPreferences()
                : patternPreferencesService.get(rule.getUser());
        return AnalysisComputationScope.memo(java.util.List.of("candlestick-signals", trend, definitions),
                () -> preferencesService == null ? detectionService.detectAlertSignalsFactory(candlestickCandles, interval)
                        : detectionService.detectAlertSignals(candlestickCandles, trend, definitions));
    }

    private Object harmonicSettings(AlertRule rule) {
        if (harmonicPatternPreferencesService == null) return "factory";
        var preferences = harmonicPatternPreferencesService.get(rule.getUser());
        return java.util.List.of(preferences.rules(), preferences.patternRules());
    }

    private ElliottWaveDetectionService elliottDetector(AlertRule rule) {
        if (rule == null || rule.getUser() == null || elliottWavePreferencesService == null) {
            return elliottWaveDetectionService;
        }
        return elliottWaveDetectionService.configured(
                elliottWavePreferencesService.get(rule.getUser()).profile(rule.getInterval()).rules());
    }

    private int signalCandleCount(TimeInterval interval) {
        return isHigherInterval(interval) ? HIGHER_INTERVAL_SIGNAL_CANDLES : DEFAULT_SIGNAL_CANDLES;
    }

    private int developingElliottParentCandleCount(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY
                ? DEVELOPING_WEEKLY_PARENT_CANDLES : signalCandleCount(interval);
    }

    private boolean isHigherInterval(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY || interval == TimeInterval.MONTHLY;
    }

    private boolean isElliottEnabled(TimeInterval interval) {
        return interval == TimeInterval.DAILY && dailyElliottEnabled
                || interval == TimeInterval.WEEKLY && weeklyElliottEnabled
                || interval == TimeInterval.MONTHLY && monthlyElliottEnabled;
    }

    private AlertPatternFamily signalFamily(DetectedSignal signal) {
        return AlertPatternFamily.forPattern(signal.pattern());
    }

    private CrossPatternConfluenceService.Policy confluencePolicy(
            AlertRule rule, AlertPatternFamily targetFamily) {
        if (scoringPreferencesService == null || rule == null || rule.getUser() == null) {
            return CrossPatternConfluenceService.Policy.factory(targetFamily);
        }
        return scoringPreferencesService.confluencePolicy(
                rule.getUser(), targetFamily, rule.getInterval());
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
}
