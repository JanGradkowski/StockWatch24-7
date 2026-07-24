package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Tracks a directional candlestick detection until the first close-based
 * confirmation, invalidation, or expiry. Detection remains an immediate alert;
 * this service only adds one terminal follow-up.
 */
@Service
public class CandlestickSignalLifecycleService {
    private static final int MAXIMUM_CONFIRMATION_WINDOW = 20;

    private final AlertEventRepository alertEventRepository;
    private final AlertNotificationService notificationService;
    private final int confirmationWindowCandles;

    public CandlestickSignalLifecycleService(
            AlertEventRepository alertEventRepository,
            AlertNotificationService notificationService,
            @Value("${alerts.candlestick.lifecycle-window-candles:3}") int confirmationWindowCandles) {
        this.alertEventRepository = alertEventRepository;
        this.notificationService = notificationService;
        this.confirmationWindowCandles = Math.clamp(
                confirmationWindowCandles,
                1,
                MAXIMUM_CONFIRMATION_WINDOW
        );
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
        event.setLifecycleUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public LifecycleEvaluationResult evaluatePending(String symbol,
                                                     TimeInterval interval,
                                                     List<Candle> availableCandles) {
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
            SignalLifecycleStatus outcome = evaluate(event, candles);
            if (outcome == null) {
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

    private SignalLifecycleStatus evaluate(AlertEvent event, List<Candle> candles) {
        if (event == null
                || event.getLifecycleStatus() != SignalLifecycleStatus.DETECTED
                || !event.isLifecycleTracked()
                || event.getSignalCandleTimestamp() == null
                || event.getTradeSignal() == null) {
            return null;
        }

        boolean signalCandleAvailable = candles.stream()
                .anyMatch(candle -> candle.getTimestamp().equals(event.getSignalCandleTimestamp()));
        if (!signalCandleAvailable) {
            return null;
        }

        int window = event.getConfirmationWindowCandles();
        List<Candle> subsequentCandles = candles.stream()
                .filter(candle -> candle.getTimestamp() > event.getSignalCandleTimestamp())
                .limit(window)
                .toList();
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
                    resolution.candleOffset()
            );
            return resolution.status();
        }
        return null;
    }

    private void resolve(AlertEvent event,
                         SignalLifecycleStatus outcome,
                         Candle resolutionCandle,
                         int candleOffset) {
        event.setLifecycleStatus(outcome);
        event.setResolutionCandleTimestamp(resolutionCandle.getTimestamp());
        event.setResolutionCandleOffset(candleOffset);
        event.setResolutionClosePrice(resolutionCandle.getClosePrice());
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
