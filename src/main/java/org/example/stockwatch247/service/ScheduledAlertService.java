package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
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
import java.time.Duration;
import java.time.Instant;

@Service
public class ScheduledAlertService {
    private static final int DEFAULT_SIGNAL_CANDLES = 100;
    private static final int HIGHER_INTERVAL_SIGNAL_CANDLES = 100;

    private final AlertRuleRepository alertRuleRepository;
    private final AlertEventRepository alertEventRepository;
    private final CandleRepository candleRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService detectionService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;
    private final AlertNotificationService notificationService;
    private final CandlestickSignalLifecycleService lifecycleService;
    private final AlertCheckJobStore jobStore;
    private final AlertScheduleRecoveryService scheduleRecoveryService;
    private final AnalysisPreferencesService preferencesService;
    private final CandlestickPatternPreferencesService patternPreferencesService;
    private final boolean scheduleEnabled;
    private final boolean weeklyElliottEnabled;
    private final boolean monthlyElliottEnabled;
    private final Duration jobLease;
    private final Duration retryDelay;
    private final int maximumAttempts;

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
                                 @Value("${alerts.elliott.weekly-enabled:true}") boolean weeklyElliottEnabled,
                                 @Value("${alerts.elliott.monthly-enabled:true}") boolean monthlyElliottEnabled,
                                 @Value("${alerts.schedule.job-lease-seconds:300}") long jobLeaseSeconds,
                                 @Value("${alerts.schedule.retry-delay-seconds:60}") long retryDelaySeconds,
                                 @Value("${alerts.schedule.maximum-attempts:3}") int maximumAttempts,
                                 AnalysisPreferencesService preferencesService,
                                 CandlestickPatternPreferencesService patternPreferencesService) {
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
        this.scheduleEnabled = scheduleEnabled;
        this.weeklyElliottEnabled = weeklyElliottEnabled;
        this.monthlyElliottEnabled = monthlyElliottEnabled;
        this.jobLease = Duration.ofSeconds(Math.max(1L, jobLeaseSeconds));
        this.retryDelay = Duration.ofSeconds(Math.max(1L, retryDelaySeconds));
        this.maximumAttempts = Math.max(1, maximumAttempts);
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
                weeklyElliottEnabled, monthlyElliottEnabled, jobLeaseSeconds, retryDelaySeconds,
                maximumAttempts, null, null);
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

    @Scheduled(fixedDelayString = "${alerts.schedule.worker-delay-ms:60000}", initialDelayString = "${alerts.schedule.initial-delay-ms:30000}")
    public void processNextQueuedCheck() {
        if (!scheduleEnabled) {
            return;
        }
        var claimedJob = jobStore.claimNext(jobLease);
        if (claimedJob.isEmpty()) {
            return;
        }
        AlertCheckJobStore.AlertCheckJob job = claimedJob.get();
        try {
            processSymbolInterval(job.symbol(), job.interval(), job.scheduledFor());
            jobStore.complete(job.id());
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
        String apiInterval = toApiInterval(interval);
        MarketDataService.CandleSyncResult syncResult = marketDataService
                .syncCandles(symbol, apiInterval, null, true);
        if (syncResult.source() == MarketDataService.CandleSource.CACHE_REFRESH_IN_PROGRESS) {
            throw new IllegalStateException("Candle refresh is already running; retrying this alert job later");
        }
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed: " + syncResult.failureMessage());
        }

        int signalCandleCount = signalCandleCount(interval);
        int requiredHistory = enrichmentService.requiredInputCandles(signalCandleCount, interval);
        if (isElliottEnabled(interval)) {
            requiredHistory = Math.max(
                    requiredHistory,
                    enrichmentService.requiredElliottInputCandles(signalCandleCount, interval)
            );
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

        List<AlertRule> rules = alertRuleRepository
                .findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndIsActiveTrue(symbol, interval);
        if (rules.isEmpty()) {
            return;
        }

        List<EnrichedCandle> enrichedCandles = enrichmentService.enrich(candles, signalCandleCount, interval);
        for (AlertRule rule : rules) {
            List<DetectedSignal> detectedSignals = detectSignals(enrichedCandles, elliottCandles, rule, interval);
            for (DetectedSignal signal : detectedSignals) {
                if (rule.getTradeSignal() != signal.tradeSignal()
                        || rule.getPatternFamily() != signalFamily(signal)) {
                    continue;
                }
                if (signalFamily(signal) == AlertPatternFamily.ELLIOTT_WAVE) {
                    ElliottWaveDetectionService.ElliottWaveStructure structure =
                            elliottWaveDetectionService.findStructureForSignal(
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
                    if (boundaries == null || alertEventRepository
                            .findFirstByAlertRuleAndElliottCycleKeyAndElliottSignalStageOrderByIdAsc(
                                    rule,
                                    boundaries.cycleKey(),
                                    boundaries.stage())
                            .isPresent()) {
                        continue;
                    }
                    sendAndRecord(rule, signal, candles, structure);
                    continue;
                }
                if (!alertEventRepository.existsByAlertRuleAndPatternAndSignalCandleTimestamp(
                        rule, signal.pattern(), signal.candleTimestamp())) {
                    sendAndRecord(rule, signal, candles, null);
                }
            }
        }
    }

    private void sendAndRecord(AlertRule rule,
                               DetectedSignal signal,
                               List<Candle> candles,
                               ElliottWaveDetectionService.ElliottWaveStructure elliottStructure) {
        AnalysisPreferencesService.PreferencesView preferences = preferencesService == null
                ? AnalysisPreferencesService.factoryPreferences()
                : preferencesService.get(rule.getUser());
        AnalysisPreferencesService.IntervalProfile profile = preferences.profile(rule.getInterval());
        DetectedSignal personalizedSignal = preferences.custom()
                ? personalizeSignal(rule, signal, candles, profile)
                : signal;
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(signal.pattern());
        event.setTradeSignal(signal.tradeSignal());
        event.setSignalCandleTimestamp(signal.candleTimestamp());
        event.setSignalStrength(personalizedSignal.strength());
        event.setConfidenceScore(personalizedSignal.confidenceScore());
        event.setFactoryConfidenceScore(signal.confidenceScore());
        event.setAnalysisProfileVersion(AnalysisPreferencesService.PROFILE_VERSION);
        event.setAnalysisProfileSnapshot(preferencesService == null ? null : preferencesService.snapshot(profile));
        if (signalFamily(signal) == AlertPatternFamily.ELLIOTT_WAVE) {
            event.setElliottV1EligibilityScore(signal.eligibilityScore());
        }
        event.setScoreVersion(preferences.custom() ? "PERSONALIZED_V1" : scoreVersion(signal));
        event.setConfidenceReasons(personalizedSignal.reasons());
        event.setClosePrice(signal.closePrice());
        if (signalFamily(signal) == AlertPatternFamily.CANDLESTICK) {
            lifecycleService.initializeTracking(event, personalizedSignal, candles, profile);
        } else {
            lifecycleService.initializeElliottTracking(event, elliottStructure, profile);
        }

        if (notificationService.sendSignalEmail(rule, personalizedSignal, event)) {
            event.setInitialEmailSentAt(java.time.LocalDateTime.now());
        }
        alertEventRepository.save(event);
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
                ? elliottWaveDetectionService.detect(personalizedCandles)
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
        return switch (interval) {
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            default -> "1d";
        };
    }

    private List<DetectedSignal> detectSignals(List<EnrichedCandle> candlestickCandles,
                                               List<EnrichedCandle> elliottCandles,
                                               AlertRule rule,
                                               TimeInterval interval) {
        List<DetectedSignal> candlestickSignals;
        if (preferencesService == null) {
            candlestickSignals = detectionService.detectAlertSignalsFactory(
                    candlestickCandles, interval);
        } else {
            AnalysisPreferencesService.IntervalProfile profile = preferencesService
                    .profile(rule.getUser(), interval);
            candlestickSignals = detectionService.detectAlertSignals(
                    candlestickCandles,
                    preferencesService.trendDetectionRules(profile),
                    patternPreferencesService == null
                            ? CandlestickPatternPreferencesService.factoryPreferences()
                            : patternPreferencesService.get(rule.getUser()));
        }
        List<DetectedSignal> signals = new java.util.ArrayList<>(candlestickSignals);
        if (!elliottCandles.isEmpty()) {
            signals.addAll(elliottWaveDetectionService.detectAlertSignals(elliottCandles).stream()
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
}
