package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicStopStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class HarmonicStopPlanService {
    static final int OUTCOME_WINDOW_CANDLES = 8;

    private final AlertEventRepository eventRepository;
    private final AlertNotificationService notificationService;

    public HarmonicStopPlanService(
            AlertEventRepository eventRepository,
            AlertNotificationService notificationService) {
        this.eventRepository = eventRepository;
        this.notificationService = notificationService;
    }

    Optional<HarmonicStopPlanPolicy.StopPlan> prepare(
            AlertEvent event,
            HarmonicPatternDetectionService.HarmonicFormation formation,
            long entryTimestamp,
            double entryPrice) {
        if (event == null) return Optional.empty();
        return HarmonicStopPlanPolicy.calculate(formation, entryPrice)
                .map(plan -> {
                    apply(event, entryTimestamp, plan);
                    return plan;
                });
    }

    private void apply(
            AlertEvent event,
            long entryTimestamp,
            HarmonicStopPlanPolicy.StopPlan plan) {
        event.setTradeEntryPrice(plan.entryPrice());
        event.setStructuralStopPrice(plan.structuralInvalidationPrice());
        event.setStopLossPrice(plan.stopLossPrice());
        event.setProfitTargetPrice(null);
        event.setRewardRiskRatio(null);
        event.setTradePlanVersion(plan.version());
        event.setStopLossMode("STRUCTURAL_BUFFER");
        event.setStopLossValuePercent(plan.bufferPercent());
        event.setPreCircuitBreakerStopPrice(plan.stopLossPrice());
        event.setAtrCircuitBreakerEnabled(false);
        event.setAtrCircuitBreakerApplied(false);
        event.setAtrCircuitBreakerValue(null);
        event.setAtrCircuitBreakerPeriod(null);
        event.setAtrCircuitBreakerMultiplier(null);
        event.setAtrCircuitBreakerThresholdPercent(null);
        event.setDetectionCandleTimestamp(entryTimestamp);
        event.setDetectionClosePrice(plan.entryPrice());
        event.setHarmonicStopBasis(plan.basis());
        event.setHarmonicStopFormula(plan.formula());
        event.setHarmonicStopBufferAmount(plan.bufferAmount());
        event.setHarmonicStopBufferPercent(plan.bufferPercent());
        event.setHarmonicStopDistancePercent(plan.stopDistancePercent());
        event.setHarmonicStopStatus(HarmonicStopStatus.ACTIVE.name());
        event.setHarmonicStopResolutionTimestamp(null);
        event.setHarmonicStopResolutionPrice(null);
        event.setHarmonicStopResolutionReason(null);
    }

    @Transactional
    public int evaluateActivePlans(
            String symbol,
            TimeInterval interval,
            List<Candle> availableCandles) {
        JobLeaseGuard.requireOwnership();
        if (symbol == null || symbol.isBlank() || interval == null
                || availableCandles == null || availableCandles.isEmpty()) return 0;
        List<Candle> candles = availableCandles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null
                        && candle.getHighPrice() != null && candle.getLowPrice() != null
                        && candle.getClosePrice() != null)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        int resolved = 0;
        for (AlertEvent event : eventRepository.findActiveHarmonicStopPlans(symbol, interval)) {
            List<Candle> outcomeCandles = candles.stream()
                    .filter(candle -> event.getSignalCandleTimestamp() != null
                            && candle.getTimestamp() > event.getSignalCandleTimestamp())
                    .limit(OUTCOME_WINDOW_CANDLES)
                    .toList();
            Candle breached = firstStopBreach(event, outcomeCandles);
            if (breached != null) {
                resolveStop(event, breached);
            } else if (outcomeCandles.size() == OUTCOME_WINDOW_CANDLES) {
                resolveTimeStop(event, outcomeCandles.getLast());
            } else {
                continue;
            }
            eventRepository.save(event);
            resolved++;
        }
        return resolved;
    }

    private void resolveStop(AlertEvent event, Candle breached) {
        double observedExtreme = event.getTradeSignal() == TradeSignal.BUY
                ? breached.getLowPrice() : breached.getHighPrice();
        event.setHarmonicStopStatus(HarmonicStopStatus.STOPPED.name());
        event.setHarmonicStopResolutionTimestamp(breached.getTimestamp());
        event.setHarmonicStopResolutionPrice(event.getStopLossPrice());
        event.setHarmonicStopResolutionReason(stopReason(event, breached, observedExtreme));
        markResolved(event);
        if (notificationService.sendHarmonicStopOutcomeEmail(event, breached)) {
            event.setFollowUpSentAt(LocalDateTime.now());
        }
    }

    private void resolveTimeStop(AlertEvent event, Candle eighthCandle) {
        event.setHarmonicStopStatus(HarmonicStopStatus.TIME_STOPPED.name());
        event.setHarmonicStopResolutionTimestamp(eighthCandle.getTimestamp());
        event.setHarmonicStopResolutionPrice(eighthCandle.getClosePrice());
        event.setHarmonicStopResolutionReason(
                "The harmonic outcome window ended at candle 8's completed close.");
        markResolved(event);
    }

    private void markResolved(AlertEvent event) {
        event.setSentAt(LocalDateTime.now());
        event.setReadAt(null);
        event.setLifecycleUpdatedAt(LocalDateTime.now());
    }

    private Candle firstStopBreach(AlertEvent event, List<Candle> candles) {
        if (event == null || event.getSignalCandleTimestamp() == null
                || event.getStopLossPrice() == null) return null;
        for (Candle candle : candles) {
            boolean breached = event.getTradeSignal() == TradeSignal.BUY
                    ? candle.getLowPrice() <= event.getStopLossPrice()
                    : candle.getHighPrice() >= event.getStopLossPrice();
            if (breached) return candle;
        }
        return null;
    }

    private String stopReason(AlertEvent event, Candle candle, double observedExtreme) {
        String side = event.getTradeSignal() == TradeSignal.BUY ? "low" : "high";
        return "The completed candle's %s %.4f breached the buffered harmonic stop %.4f (%s; structural boundary %.4f)."
                .formatted(side, observedExtreme, event.getStopLossPrice(),
                        event.getHarmonicStopBasis(), event.getStructuralStopPrice());
    }
}
