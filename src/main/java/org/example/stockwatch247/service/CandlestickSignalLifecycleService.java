package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tracks a directional candlestick or Elliott turning-point detection until
 * the first close-based confirmation, invalidation, or expiry. Detection
 * remains an immediate alert; this service only adds one terminal follow-up.
 */
@Service
public class CandlestickSignalLifecycleService {
    private static final int MAXIMUM_CONFIRMATION_WINDOW = 20;

    private final AlertEventRepository alertEventRepository;
    private final AlertNotificationService notificationService;
    private final int confirmationWindowCandles;
    private final int elliottConfirmationWindowCandles;
    private final int oneCandleOutcomeWindowCandles;
    private ElliottWavePreferencesService elliottWavePreferencesService;

    @Autowired
    public CandlestickSignalLifecycleService(
            AlertEventRepository alertEventRepository,
            AlertNotificationService notificationService,
            @Value("${alerts.candlestick.lifecycle-window-candles:8}") int confirmationWindowCandles,
            @Value("${alerts.elliott.lifecycle-window-candles:10}") int elliottConfirmationWindowCandles,
            @Value("${alerts.candlestick.one-candle-outcome-window-candles:8}") int oneCandleOutcomeWindowCandles) {
        this.alertEventRepository = alertEventRepository;
        this.notificationService = notificationService;
        this.confirmationWindowCandles = Math.clamp(
                confirmationWindowCandles,
                1,
                MAXIMUM_CONFIRMATION_WINDOW
        );
        this.elliottConfirmationWindowCandles = Math.clamp(
                elliottConfirmationWindowCandles,
                1,
                MAXIMUM_CONFIRMATION_WINDOW
        );
        this.oneCandleOutcomeWindowCandles = Math.clamp(
                oneCandleOutcomeWindowCandles, 1, MAXIMUM_CONFIRMATION_WINDOW);
    }

    @Autowired
    void configureElliottWavePreferences(ElliottWavePreferencesService elliottWavePreferencesService) {
        this.elliottWavePreferencesService = elliottWavePreferencesService;
    }

    CandlestickSignalLifecycleService(
            AlertEventRepository alertEventRepository,
            AlertNotificationService notificationService,
            int confirmationWindowCandles) {
        this(alertEventRepository, notificationService, confirmationWindowCandles, 10, 10);
    }

    CandlestickSignalLifecycleService(
            AlertEventRepository alertEventRepository,
            AlertNotificationService notificationService,
            int confirmationWindowCandles,
            int elliottConfirmationWindowCandles) {
        this(alertEventRepository, notificationService, confirmationWindowCandles,
                elliottConfirmationWindowCandles, 10);
    }

    public void initializeTracking(AlertEvent event,
                                   DetectedSignal signal,
                                   List<Candle> chronologicalCandles) {
        initializeTracking(event, signal, chronologicalCandles, null);
    }

    public void initializeTracking(AlertEvent event,
                                   DetectedSignal signal,
                                   List<Candle> chronologicalCandles,
                                   AnalysisPreferencesService.IntervalProfile profile) {
        initializeTracking(event, signal, chronologicalCandles, profile,
                CandlestickPatternPreferencesService.factoryPreferences());
    }

    public void initializeTracking(AlertEvent event,
                                   DetectedSignal signal,
                                   List<Candle> chronologicalCandles,
                                   AnalysisPreferencesService.IntervalProfile profile,
                                   CandlestickPatternPreferencesService.PreferencesView tradePreferences) {
        if (event == null || signal == null || isElliottPattern(signal.pattern())) {
            return;
        }
        if (signal.tradeSignal() != TradeSignal.BUY && signal.tradeSignal() != TradeSignal.SELL) {
            return;
        }

        int patternCandleCount = CandlestickSignalLifecyclePolicy.patternCandleCount(signal.pattern());
        if (patternCandleCount == 0) {
            throw new IllegalStateException("Unsupported candlestick lifecycle pattern: " + signal.pattern());
        }

        List<Candle> candles = chronologicalCandles == null
                ? List.of()
                : chronologicalCandles.stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        int signalIndex = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp().equals(signal.candleTimestamp())) {
                signalIndex = index;
                break;
            }
        }
        int patternStartIndex = signalIndex - patternCandleCount + 1;
        if (signalIndex < 0 || patternStartIndex < 0) {
            throw new IllegalStateException("The detected candlestick range is unavailable for lifecycle tracking.");
        }

        List<Candle> patternCandles = candles.subList(patternStartIndex, signalIndex + 1);
        double patternHigh = patternCandles.stream()
                .mapToDouble(Candle::getHighPrice)
                .max()
                .orElseThrow();
        double patternLow = patternCandles.stream()
                .mapToDouble(Candle::getLowPrice)
                .min()
                .orElseThrow();
        if (!(patternHigh > patternLow)) {
            throw new IllegalStateException("The detected candlestick range is not valid for lifecycle tracking.");
        }

        boolean oneCandleCandidate =
                CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern());
        TimeInterval interval = event.getAlertRule() != null && event.getAlertRule().getInterval() != null
                ? event.getAlertRule().getInterval()
                : profile != null ? profile.interval() : TimeInterval.DAILY;
        CandlestickPatternPreferencesService.PreferencesView effectivePreferences = tradePreferences == null
                ? CandlestickPatternPreferencesService.factoryPreferences() : tradePreferences;
        CandlestickPatternPreferencesService.PatternProfile patternProfile =
                effectivePreferences.profile(signal.pattern());
        double structuralStop = CandlestickSignalLifecyclePolicy.structuralStopPrice(
                signal.pattern(), patternCandles);
        double stopLoss = CandlestickSignalLifecyclePolicy.configuredStopPrice(
                signal.tradeSignal(), signal.closePrice(), structuralStop,
                patternProfile.stopLossMode(), patternProfile.stopLossValuePercent());
        double rewardRiskRatio = effectivePreferences.rewardRiskRatio(interval);
        CandlestickPatternPreferencesService.CircuitBreakerSettings circuitBreaker =
                effectivePreferences.circuitBreaker(interval);
        event.setLifecycleStatus(oneCandleCandidate
                ? SignalLifecycleStatus.POTENTIAL
                : SignalLifecycleStatus.DETECTED);
        event.setPatternHigh(patternHigh);
        event.setPatternLow(patternLow);
        event.setConfirmationTriggerPrice(
                oneCandleCandidate
                        ? signal.closePrice()
                        : null);
        event.setInvalidationPrice(stopLoss);
        event.setStopLossPrice(stopLoss);
        event.setStructuralStopPrice(structuralStop);
        event.setStopLossMode(patternProfile.stopLossMode().name());
        event.setStopLossValuePercent(patternProfile.stopLossValuePercent());
        event.setPreCircuitBreakerStopPrice(stopLoss);
        event.setAtrCircuitBreakerEnabled(circuitBreaker.enabled());
        event.setAtrCircuitBreakerApplied(false);
        event.setAtrCircuitBreakerValue(null);
        event.setAtrCircuitBreakerPeriod(circuitBreaker.atrPeriod());
        event.setAtrCircuitBreakerMultiplier(circuitBreaker.atrMultiplier());
        event.setAtrCircuitBreakerThresholdPercent(circuitBreaker.activationThresholdPercent());
        event.setRewardRiskRatio(rewardRiskRatio);
        event.setTradePlanVersion(CandlestickSignalLifecyclePolicy.RISK_REWARD_VERSION);
        event.setConfirmationWindowCandles(CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES);
        event.setLifecycleConfirmationPercent(null);
        event.setLifecycleInvalidationPercent(null);
        event.setLifecycleAnchorCandleTimestamp(signal.candleTimestamp());
        event.setDetectionCandleTimestamp(oneCandleCandidate ? null : signal.candleTimestamp());
        event.setDetectionClosePrice(oneCandleCandidate ? null : signal.closePrice());
        if (!oneCandleCandidate) {
            double atr = CandlestickSignalLifecyclePolicy.averageTrueRange(
                    candles, signalIndex, circuitBreaker.atrPeriod());
            applyTradePlan(event, CandlestickSignalLifecyclePolicy.tradePlan(
                    signal.tradeSignal(), signal.closePrice(), stopLoss, interval, rewardRiskRatio,
                    atr, circuitBreaker));
        }
        event.setLifecycleResolutionReason(null);
        event.setLifecycleUpdatedAt(LocalDateTime.now());
    }

    public boolean initializeElliottTracking(
            AlertEvent event,
            ElliottWaveDetectionService.ElliottWaveStructure structure) {
        return initializeElliottTracking(event, structure, null);
    }

    public boolean initializeElliottTracking(
            AlertEvent event,
            ElliottWaveDetectionService.ElliottWaveStructure structure,
            AnalysisPreferencesService.IntervalProfile profile) {
        if (event == null || structure == null || !isElliottPattern(event.getPattern())) {
            return false;
        }
        ElliottWaveSignalLifecyclePolicy.LifecycleBoundaries boundaries =
                ElliottWaveSignalLifecyclePolicy.boundaries(
                                event.getPattern(),
                                event.getTradeSignal(),
                                structure)
                        .orElse(null);
        if (boundaries == null) {
            return false;
        }

        event.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
        event.setPatternHigh(boundaries.structureHigh());
        event.setPatternLow(boundaries.structureLow());
        event.setConfirmationTriggerPrice(boundaries.confirmationTrigger());
        event.setInvalidationPrice(boundaries.invalidationBoundary());
        event.setConfirmationWindowCandles(profile == null
                ? elliottConfirmationWindowCandles : profile.elliottResolutionCandles());
        applyPercentageSnapshot(event, profile);
        event.setElliottCycleKey(boundaries.cycleKey());
        event.setElliottSignalStage(boundaries.stage());
        event.setElliottEndpointTimestamp(boundaries.endpointTimestamp());
        event.setElliottEndpointPrice(boundaries.endpointPrice());
        event.setElliottTerminalAnchorTimestamp(boundaries.terminalAnchorTimestamp());
        event.setLifecycleAnchorCandleTimestamp(boundaries.lifecycleAnchorTimestamp());
        event.setLifecycleResolutionReason(null);
        event.setLifecycleUpdatedAt(LocalDateTime.now());
        return true;
    }

    private void applyPercentageSnapshot(AlertEvent event,
                                         AnalysisPreferencesService.IntervalProfile profile) {
        event.setLifecycleConfirmationPercent(profile == null || profile.confirmationMovePercent() <= 0
                ? null : profile.confirmationMovePercent());
        event.setLifecycleInvalidationPercent(profile == null || profile.invalidationMovePercent() <= 0
                ? null : profile.invalidationMovePercent());
    }

    @Transactional
    public int initializeUntrackedElliott(
            String symbol,
            TimeInterval interval,
            List<EnrichedCandle> enrichedCandles,
            ElliottWaveDetectionService elliottWaveDetectionService) {
        JobLeaseGuard.requireOwnership();
        if (symbol == null || symbol.isBlank() || interval == null
                || enrichedCandles == null || enrichedCandles.isEmpty()
                || elliottWaveDetectionService == null) {
            return 0;
        }
        List<AlertEvent> events = alertEventRepository.findUntrackedLifecycleEvents(
                symbol,
                interval,
                AlertPatternFamily.ELLIOTT_WAVE,
                SignalLifecycleStatus.DETECTED
        );
        int initialized = 0;
        for (AlertEvent event : events) {
            ElliottWaveDetectionService detector = elliottDetector(event, elliottWaveDetectionService);
            ElliottWaveDetectionService.ElliottWaveStructure structure = detector
                    .findStructureForSignal(
                            enrichedCandles,
                            event.getPattern(),
                            event.getSignalCandleTimestamp())
                    .orElse(null);
            if (initializeElliottTracking(event, structure)) {
                alertEventRepository.save(event);
                initialized++;
            }
        }
        return initialized;
    }

    @Transactional
    public LifecycleEvaluationResult evaluatePending(String symbol,
                                                     TimeInterval interval,
                                                     List<Candle> availableCandles) {
        JobLeaseGuard.requireOwnership();
        return evaluatePending(symbol, interval, availableCandles, List.of(), null);
    }

    int requiredCandlestickAtrHistory(String symbol, TimeInterval interval) {
        List<AlertEvent> pending = new ArrayList<>();
        pending.addAll(alertEventRepository.findTrackedLifecycleEvents(
                symbol, interval, SignalLifecycleStatus.POTENTIAL));
        pending.addAll(alertEventRepository.findTrackedLifecycleEvents(
                symbol, interval, SignalLifecycleStatus.DETECTED));
        return pending.stream()
                .filter(AlertEvent::hasCandlestickRiskRewardPlan)
                .map(AlertEvent::getAtrCircuitBreakerPeriod)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    @Transactional
    public LifecycleEvaluationResult evaluatePending(
            String symbol,
            TimeInterval interval,
            List<Candle> availableCandles,
            List<EnrichedCandle> enrichedCandles,
            ElliottWaveDetectionService elliottWaveDetectionService) {
        JobLeaseGuard.requireOwnership();
        List<AlertEvent> pendingEvents = new ArrayList<>();
        pendingEvents.addAll(alertEventRepository.findTrackedLifecycleEvents(
                symbol, interval, SignalLifecycleStatus.POTENTIAL));
        pendingEvents.addAll(alertEventRepository.findTrackedLifecycleEvents(
                symbol, interval, SignalLifecycleStatus.DETECTED));
        pendingEvents.sort(Comparator.comparing(AlertEvent::getSignalCandleTimestamp)
                .thenComparing(event -> event.getId() == null ? Long.MIN_VALUE : event.getId()));
        if (pendingEvents.isEmpty()) {
            return LifecycleEvaluationResult.empty();
        }

        List<Candle> candles = availableCandles == null
                ? List.of()
                : availableCandles.stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        int detected = 0;
        int rejected = 0;
        int confirmed = 0;
        int invalidated = 0;
        int expired = 0;

        for (AlertEvent event : pendingEvents) {
            boolean revised = refreshElliottEndpoint(
                    event,
                    enrichedCandles,
                    elliottWaveDetectionService
            );
            SignalLifecycleStatus outcome = evaluate(event, candles, enrichedCandles);
            if (outcome == null) {
                if (revised) {
                    alertEventRepository.save(event);
                }
                continue;
            }

            if (notificationService.sendSignalLifecycleEmail(event)) {
                event.setFollowUpSentAt(LocalDateTime.now());
            }
            alertEventRepository.save(event);

            switch (outcome) {
                case DETECTED -> detected++;
                case REJECTED -> rejected++;
                case CONFIRMED -> confirmed++;
                case INVALIDATED -> invalidated++;
                case EXPIRED -> expired++;
                case POTENTIAL -> {
                    // POTENTIAL is never returned as a completed transition.
                }
            }
        }
        return new LifecycleEvaluationResult(
                pendingEvents.size(), detected, rejected, confirmed, invalidated, expired);
    }

    private boolean refreshElliottEndpoint(
            AlertEvent event,
            List<EnrichedCandle> enrichedCandles,
            ElliottWaveDetectionService elliottWaveDetectionService) {
        if (event == null || !event.isElliottSignal()
                || event.getElliottCycleKey() == null
                || event.getElliottSignalStage() == null
                || enrichedCandles == null || enrichedCandles.isEmpty()
                || elliottWaveDetectionService == null) {
            return false;
        }
        ElliottWaveDetectionService.ElliottWaveStructure revisedStructure =
                elliottDetector(event, elliottWaveDetectionService).findLatestStructureForCycle(
                                enrichedCandles,
                                event.getElliottCycleKey(),
                                event.getElliottSignalStage())
                        .orElse(null);
        if (revisedStructure == null) {
            return false;
        }
        ElliottWaveSignalLifecyclePolicy.LifecycleBoundaries revised =
                ElliottWaveSignalLifecyclePolicy.boundaries(
                                event.getPattern(),
                                event.getTradeSignal(),
                                revisedStructure)
                        .orElse(null);
        if (revised == null
                || !event.getElliottCycleKey().equals(revised.cycleKey())
                || event.getElliottEndpointTimestamp() != null
                && revised.endpointTimestamp() <= event.getElliottEndpointTimestamp()) {
            return false;
        }

        event.setPatternHigh(revised.structureHigh());
        event.setPatternLow(revised.structureLow());
        event.setConfirmationTriggerPrice(revised.confirmationTrigger());
        event.setInvalidationPrice(revised.invalidationBoundary());
        event.setElliottEndpointTimestamp(revised.endpointTimestamp());
        event.setElliottEndpointPrice(revised.endpointPrice());
        event.setElliottTerminalAnchorTimestamp(revised.terminalAnchorTimestamp());
        event.setLifecycleAnchorCandleTimestamp(revised.lifecycleAnchorTimestamp());
        event.setLifecycleResolutionReason(null);
        event.setLifecycleUpdatedAt(LocalDateTime.now());
        return true;
    }

    private ElliottWaveDetectionService elliottDetector(
            AlertEvent event,
            ElliottWaveDetectionService fallback) {
        if (event == null || event.getAlertRule() == null || event.getAlertRule().getUser() == null
                || elliottWavePreferencesService == null) {
            return fallback;
        }
        TimeInterval interval = event.getAlertRule().getInterval();
        if (interval != TimeInterval.WEEKLY && interval != TimeInterval.MONTHLY) {
            return fallback;
        }
        return fallback.configured(elliottWavePreferencesService.get(event.getAlertRule().getUser())
                .profile(interval).rules());
    }

    private SignalLifecycleStatus evaluate(
            AlertEvent event,
            List<Candle> candles,
            List<EnrichedCandle> enrichedCandles) {
        if (event == null
                || event.getLifecycleStatus() != SignalLifecycleStatus.POTENTIAL
                        && event.getLifecycleStatus() != SignalLifecycleStatus.DETECTED
                || !event.isLifecycleTracked()
                || event.getSignalCandleTimestamp() == null
                || event.getTradeSignal() == null) {
            return null;
        }

        Long lifecycleAnchor = event.getLifecycleEvaluationAnchorTimestamp();
        boolean lifecycleAnchorAvailable = lifecycleAnchor != null && candles.stream()
                .anyMatch(candle -> candle.getTimestamp().equals(lifecycleAnchor));
        if (!lifecycleAnchorAvailable) {
            return null;
        }

        int window = event.getConfirmationWindowCandles();
        List<Candle> subsequentCandles = candles.stream()
                .filter(candle -> candle.getTimestamp() > lifecycleAnchor)
                .limit(window)
                .toList();
        if (event.getLifecycleStatus() == SignalLifecycleStatus.POTENTIAL) {
            LifecycleDecision gate = decision(CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                    event.getPattern(), event.getTradeSignal(), event.getClosePrice(), subsequentCandles));
            if (gate == null) {
                return null;
            }
            if (gate.status() == SignalLifecycleStatus.REJECTED) {
                resolve(event, SignalLifecycleStatus.REJECTED, gate.candle(), 1,
                        "The immediately following candle did not close with a confirming body in the signal direction.");
                return SignalLifecycleStatus.REJECTED;
            }
            activateCandidate(event, gate.candle(), candles);
            return SignalLifecycleStatus.DETECTED;
        }
        if (event.isElliottSignal()) {
            long lastObservedTimestamp = subsequentCandles.isEmpty()
                    ? lifecycleAnchor
                    : subsequentCandles.getLast().getTimestamp();
            ElliottWaveSignalLifecyclePolicy.StructuralInvalidation structuralInvalidation =
                    ElliottWaveSignalLifecyclePolicy.structuralInvalidation(
                                    event.getElliottCycleKey(),
                                    event.getElliottSignalStage(),
                                    event.getInvalidationPrice(),
                                    lifecycleAnchor,
                                    enrichedCandles == null
                                            ? List.of()
                                            : enrichedCandles.stream()
                                                    .filter(candle -> candle.timestamp() <= lastObservedTimestamp)
                                                    .toList())
                            .orElse(null);
            LifecycleDecision resolution = hasPercentageRules(event)
                    ? resolveWithSnapshot(event, subsequentCandles, false)
                    : decision(ElliottWaveSignalLifecyclePolicy.resolve(
                            event.getTradeSignal(), event.getConfirmationTriggerPrice(),
                            subsequentCandles, window));
            boolean structureBreaksFirst = structuralInvalidation != null
                    && (resolution == null
                    || structuralInvalidation.timestamp()
                    <= resolution.candle().getTimestamp());
            if (structureBreaksFirst) {
                Candle resolutionCandle = candles.stream()
                        .filter(candle -> candle.getTimestamp().equals(structuralInvalidation.timestamp()))
                        .findFirst()
                        .orElse(null);
                if (resolutionCandle == null) {
                    return null;
                }
                int offset = (int) candles.stream()
                        .filter(candle -> candle.getTimestamp() > lifecycleAnchor)
                        .filter(candle -> candle.getTimestamp() <= structuralInvalidation.timestamp())
                        .count();
                resolve(event, SignalLifecycleStatus.INVALIDATED, resolutionCandle, offset,
                        structuralInvalidation.reason());
                return SignalLifecycleStatus.INVALIDATED;
            }
            if (resolution != null) {
                resolve(event, resolution.status(), resolution.candle(),
                        resolution.offset(), null);
                return resolution.status();
            }
            return null;
        }
        LifecycleDecision resolution = event.hasCandlestickRiskRewardPlan()
                ? decision(CandlestickSignalLifecyclePolicy.resolve(
                        event.getTradeSignal(), event.getProfitTargetPrice(),
                        event.getStopLossPrice(), subsequentCandles,
                        CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES))
                : hasPercentageRules(event)
                ? resolveWithSnapshot(event, subsequentCandles, true)
                : decision(CandlestickSignalLifecyclePolicy.resolve(
                        event.getTradeSignal(), event.getConfirmationTriggerPrice(),
                        event.getInvalidationPrice(), subsequentCandles, window));
        if (resolution != null) {
            resolve(
                    event,
                    resolution.status(),
                    resolution.candle(),
                    resolution.offset(),
                    null
            );
            return resolution.status();
        }
        return null;
    }

    private void activateCandidate(AlertEvent event, Candle detectionCandle, List<Candle> chronologicalCandles) {
        event.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
        event.setDetectionCandleTimestamp(detectionCandle.getTimestamp());
        event.setDetectionClosePrice(detectionCandle.getClosePrice());
        event.setLifecycleAnchorCandleTimestamp(detectionCandle.getTimestamp());
        if (event.hasCandlestickRiskRewardPlan()) {
            TimeInterval interval = event.getAlertRule() == null || event.getAlertRule().getInterval() == null
                    ? TimeInterval.DAILY : event.getAlertRule().getInterval();
            double stopLoss = event.getStopLossPrice();
            if (event.getStructuralStopPrice() != null
                    && event.getStopLossMode() != null
                    && event.getStopLossValuePercent() != null) {
                CandlestickPatternPreferencesService.StopLossMode mode;
                try {
                    mode = CandlestickPatternPreferencesService.StopLossMode.valueOf(event.getStopLossMode());
                } catch (IllegalArgumentException exception) {
                    mode = CandlestickPatternPreferencesService.StopLossMode.STRUCTURAL_BUFFER;
                }
                stopLoss = CandlestickSignalLifecyclePolicy.configuredStopPrice(
                        event.getTradeSignal(), detectionCandle.getClosePrice(),
                        event.getStructuralStopPrice(), mode, event.getStopLossValuePercent());
            }
            CandlestickPatternPreferencesService.CircuitBreakerSettings circuitBreaker =
                    circuitBreakerSettings(event, interval);
            event.setAtrCircuitBreakerEnabled(circuitBreaker.enabled());
            int detectionIndex = chronologicalCandles.indexOf(detectionCandle);
            double atr = CandlestickSignalLifecyclePolicy.averageTrueRange(
                    chronologicalCandles, detectionIndex, circuitBreaker.atrPeriod());
            applyTradePlan(event, CandlestickSignalLifecyclePolicy.tradePlan(
                    event.getTradeSignal(), detectionCandle.getClosePrice(),
                    stopLoss, interval, event.getRewardRiskRatio(), atr, circuitBreaker));
        } else {
            event.setConfirmationTriggerPrice(event.getTradeSignal() == TradeSignal.BUY
                    ? event.getPatternHigh() : event.getPatternLow());
            event.setInvalidationPrice(event.getTradeSignal() == TradeSignal.BUY
                    ? event.getPatternLow() : event.getPatternHigh());
        }
        event.setResolutionCandleTimestamp(null);
        event.setResolutionCandleOffset(null);
        event.setResolutionClosePrice(null);
        event.setLifecycleResolutionReason(
                "The immediately following candle confirmed the potential pattern; outcome tracking started from this close.");
        event.setLifecycleUpdatedAt(LocalDateTime.now());
    }

    private void applyTradePlan(AlertEvent event,
                                CandlestickSignalLifecyclePolicy.TradePlan plan) {
        event.setTradeEntryPrice(plan.entryPrice());
        event.setStopLossPrice(plan.stopLossPrice());
        event.setProfitTargetPrice(plan.profitTargetPrice());
        event.setRewardRiskRatio(plan.rewardRiskRatio());
        event.setPreCircuitBreakerStopPrice(plan.configuredStopLossPrice());
        event.setAtrCircuitBreakerApplied(plan.atrCircuitBreakerApplied());
        event.setAtrCircuitBreakerValue(plan.atrValue());
        event.setAtrCircuitBreakerPeriod(plan.atrPeriod());
        event.setAtrCircuitBreakerMultiplier(plan.atrMultiplier());
        event.setAtrCircuitBreakerThresholdPercent(plan.activationThresholdPercent());
        event.setTradePlanVersion(event.getStructuralStopPrice() != null
                && event.getStopLossMode() != null && event.getStopLossValuePercent() != null
                ? CandlestickSignalLifecyclePolicy.RISK_REWARD_VERSION : "CANDLE_RR_V1");
        event.setConfirmationWindowCandles(plan.timeStopCandles());
        // Retain these generic columns for old API consumers and for the
        // shared tracked-lifecycle predicate. Candlestick UI uses the explicit
        // trade-plan fields; Elliott continues to use boundary semantics.
        event.setConfirmationTriggerPrice(plan.profitTargetPrice());
        event.setInvalidationPrice(plan.stopLossPrice());
    }

    private CandlestickPatternPreferencesService.CircuitBreakerSettings circuitBreakerSettings(
            AlertEvent event,
            TimeInterval interval) {
        if (event.getAtrCircuitBreakerEnabled() == null
                || event.getAtrCircuitBreakerPeriod() == null
                || event.getAtrCircuitBreakerMultiplier() == null
                || event.getAtrCircuitBreakerThresholdPercent() == null) {
            return CandlestickPatternPreferencesService.factoryPreferences().circuitBreaker(interval);
        }
        return new CandlestickPatternPreferencesService.CircuitBreakerSettings(
                event.getAtrCircuitBreakerEnabled(),
                event.getAtrCircuitBreakerPeriod(),
                event.getAtrCircuitBreakerMultiplier(),
                event.getAtrCircuitBreakerThresholdPercent());
    }

    private boolean hasPercentageRules(AlertEvent event) {
        return event.getLifecycleConfirmationPercent() != null
                || event.getLifecycleInvalidationPercent() != null;
    }

    private LifecycleDecision resolveWithSnapshot(AlertEvent event,
                                                  List<Candle> subsequentCandles,
                                                  boolean useFixedInvalidationBoundary) {
        int window = event.getConfirmationWindowCandles();
        int observed = Math.min(window, subsequentCandles.size());
        Double baselineClose = CandlestickSignalLifecyclePolicy
                .requiresNextCandleConfirmation(event.getPattern())
                ? event.getDetectionClosePrice()
                : event.getClosePrice();
        double entry = baselineClose == null ? Double.NaN : baselineClose;
        for (int index = 0; index < observed; index++) {
            Candle candle = subsequentCandles.get(index);
            double close = candle.getClosePrice();
            double directionalMove = !Double.isFinite(entry) || entry <= 0 ? 0
                    : (event.getTradeSignal() == TradeSignal.SELL ? entry - close : close - entry) / entry * 100.0;
            boolean fixedInvalidation = useFixedInvalidationBoundary && event.getInvalidationPrice() != null
                    && (event.getTradeSignal() == TradeSignal.BUY
                    ? close < event.getInvalidationPrice() : close > event.getInvalidationPrice());
            boolean percentInvalidation = event.getLifecycleInvalidationPercent() != null
                    && directionalMove <= -event.getLifecycleInvalidationPercent();
            if (fixedInvalidation || percentInvalidation) {
                return new LifecycleDecision(SignalLifecycleStatus.INVALIDATED, candle, index + 1);
            }
            boolean structuralConfirmation = event.getTradeSignal() == TradeSignal.BUY
                    ? close > event.getConfirmationTriggerPrice()
                    : close < event.getConfirmationTriggerPrice();
            boolean percentConfirmation = event.getLifecycleConfirmationPercent() == null
                    || directionalMove >= event.getLifecycleConfirmationPercent();
            if (structuralConfirmation && percentConfirmation) {
                return new LifecycleDecision(SignalLifecycleStatus.CONFIRMED, candle, index + 1);
            }
        }
        if (observed >= window) {
            return new LifecycleDecision(SignalLifecycleStatus.EXPIRED,
                    subsequentCandles.get(window - 1), window);
        }
        return null;
    }

    private LifecycleDecision decision(CandlestickSignalLifecyclePolicy.LifecycleResolution value) {
        return value == null ? null : new LifecycleDecision(
                value.status(), value.resolutionCandle(), value.candleOffset());
    }

    private LifecycleDecision decision(ElliottWaveSignalLifecyclePolicy.LifecycleResolution value) {
        return value == null ? null : new LifecycleDecision(
                value.status(), value.resolutionCandle(), value.candleOffset());
    }

    private record LifecycleDecision(SignalLifecycleStatus status, Candle candle, int offset) { }

    private void resolve(AlertEvent event,
                         SignalLifecycleStatus outcome,
                         Candle resolutionCandle,
                         int candleOffset,
                         String resolutionReason) {
        event.setLifecycleStatus(outcome);
        event.setResolutionCandleTimestamp(resolutionCandle.getTimestamp());
        event.setResolutionCandleOffset(candleOffset);
        event.setResolutionClosePrice(resolutionCandle.getClosePrice());
        event.setLifecycleResolutionReason(resolutionReason);
        event.setLifecycleUpdatedAt(LocalDateTime.now());
    }

    private boolean isElliottPattern(CandlePattern pattern) {
        return pattern != null && pattern.name().startsWith("ELLIOTT_");
    }

    private boolean hasCompletePriceData(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null
                && candle.getHighPrice() != null
                && candle.getLowPrice() != null
                && candle.getClosePrice() != null;
    }

    public record LifecycleEvaluationResult(
            int pendingEvents,
            int detected,
            int rejected,
            int confirmed,
            int invalidated,
            int expired
    ) {
        private static LifecycleEvaluationResult empty() {
            return new LifecycleEvaluationResult(0, 0, 0, 0, 0, 0);
        }

        public int resolved() {
            return rejected + confirmed + invalidated + expired;
        }

        public int transitioned() {
            return detected + resolved();
        }
    }
}
