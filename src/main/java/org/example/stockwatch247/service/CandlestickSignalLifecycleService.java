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

    @Autowired
    public CandlestickSignalLifecycleService(
            AlertEventRepository alertEventRepository,
            AlertNotificationService notificationService,
            @Value("${alerts.candlestick.lifecycle-window-candles:3}") int confirmationWindowCandles,
            @Value("${alerts.elliott.lifecycle-window-candles:10}") int elliottConfirmationWindowCandles) {
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
    }

    CandlestickSignalLifecycleService(
            AlertEventRepository alertEventRepository,
            AlertNotificationService notificationService,
            int confirmationWindowCandles) {
        this(alertEventRepository, notificationService, confirmationWindowCandles, 10);
    }

    public void initializeTracking(AlertEvent event,
                                   DetectedSignal signal,
                                   List<Candle> chronologicalCandles) {
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

        event.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
        event.setPatternHigh(patternHigh);
        event.setPatternLow(patternLow);
        event.setConfirmationTriggerPrice(
                signal.tradeSignal() == TradeSignal.BUY ? patternHigh : patternLow);
        event.setInvalidationPrice(
                signal.tradeSignal() == TradeSignal.BUY ? patternLow : patternHigh);
        event.setConfirmationWindowCandles(confirmationWindowCandles);
        event.setLifecycleAnchorCandleTimestamp(signal.candleTimestamp());
        event.setLifecycleResolutionReason(null);
        event.setLifecycleUpdatedAt(LocalDateTime.now());
    }

    public boolean initializeElliottTracking(
            AlertEvent event,
            ElliottWaveDetectionService.ElliottWaveStructure structure) {
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
        event.setConfirmationWindowCandles(elliottConfirmationWindowCandles);
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

    @Transactional
    public int initializeUntrackedElliott(
            String symbol,
            TimeInterval interval,
            List<EnrichedCandle> enrichedCandles,
            ElliottWaveDetectionService elliottWaveDetectionService) {
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
            ElliottWaveDetectionService.ElliottWaveStructure structure = elliottWaveDetectionService
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
        return evaluatePending(symbol, interval, availableCandles, List.of(), null);
    }

    @Transactional
    public LifecycleEvaluationResult evaluatePending(
            String symbol,
            TimeInterval interval,
            List<Candle> availableCandles,
            List<EnrichedCandle> enrichedCandles,
            ElliottWaveDetectionService elliottWaveDetectionService) {
        List<AlertEvent> pendingEvents = alertEventRepository.findTrackedLifecycleEvents(
                symbol,
                interval,
                SignalLifecycleStatus.DETECTED
        );
        if (pendingEvents.isEmpty()) {
            return LifecycleEvaluationResult.empty();
        }

        List<Candle> candles = availableCandles == null
                ? List.of()
                : availableCandles.stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
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

            notificationService.sendSignalLifecycleEmail(event);
            if (notificationService.isEmailDeliveryEnabled()) {
                event.setFollowUpSentAt(LocalDateTime.now());
            }
            alertEventRepository.save(event);

            switch (outcome) {
                case CONFIRMED -> confirmed++;
                case INVALIDATED -> invalidated++;
                case EXPIRED -> expired++;
                case DETECTED -> {
                    // DETECTED is never returned as a terminal outcome.
                }
            }
        }
        return new LifecycleEvaluationResult(pendingEvents.size(), confirmed, invalidated, expired);
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
                elliottWaveDetectionService.findLatestStructureForCycle(
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

    private SignalLifecycleStatus evaluate(
            AlertEvent event,
            List<Candle> candles,
            List<EnrichedCandle> enrichedCandles) {
        if (event == null
                || event.getLifecycleStatus() != SignalLifecycleStatus.DETECTED
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
            ElliottWaveSignalLifecyclePolicy.LifecycleResolution resolution =
                    ElliottWaveSignalLifecyclePolicy.resolve(
                            event.getTradeSignal(),
                            event.getConfirmationTriggerPrice(),
                            subsequentCandles,
                            window
                    );
            boolean structureBreaksFirst = structuralInvalidation != null
                    && (resolution == null
                    || structuralInvalidation.timestamp()
                    <= resolution.resolutionCandle().getTimestamp());
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
                resolve(event, resolution.status(), resolution.resolutionCandle(),
                        resolution.candleOffset(), null);
                return resolution.status();
            }
            return null;
        }
        CandlestickSignalLifecyclePolicy.LifecycleResolution resolution =
                CandlestickSignalLifecyclePolicy.resolve(
                        event.getTradeSignal(),
                        event.getConfirmationTriggerPrice(),
                        event.getInvalidationPrice(),
                        subsequentCandles,
                        window
                );
        if (resolution != null) {
            resolve(
                    event,
                    resolution.status(),
                    resolution.resolutionCandle(),
                    resolution.candleOffset(),
                    null
            );
            return resolution.status();
        }
        return null;
    }

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
            int confirmed,
            int invalidated,
            int expired
    ) {
        private static LifecycleEvaluationResult empty() {
            return new LifecycleEvaluationResult(0, 0, 0, 0);
        }

        public int resolved() {
            return confirmed + invalidated + expired;
        }
    }
}
