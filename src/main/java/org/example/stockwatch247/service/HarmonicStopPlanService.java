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
    @org.springframework.beans.factory.annotation.Autowired
    private TradeExecutionService executionService;

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
        return prepare(event, formation, entryTimestamp, entryPrice, List.of(), TimeInterval.DAILY);
    }

    Optional<HarmonicStopPlanPolicy.StopPlan> prepare(AlertEvent event,
            HarmonicPatternDetectionService.HarmonicFormation formation,
            long entryTimestamp, double entryPrice, List<Candle> candles, TimeInterval interval) {
        if (event == null) return Optional.empty();
        double atr = ElliottTradePlanService.averageTrueRange(candles, entryTimestamp, 14);
        return HarmonicStopPlanPolicy.calculate(formation, entryPrice, atr, interval)
                .map(plan -> {
                    apply(event, entryTimestamp, plan);
                    return plan;
                }).or(() -> {
                    event.setTradeActionable(false);
                    event.setTradeQualification("Projection only: no valid positive harmonic stop and target at this entry.");
                    return Optional.empty();
                });
    }

    private void apply(
            AlertEvent event,
            long entryTimestamp,
            HarmonicStopPlanPolicy.StopPlan plan) {
        event.setTradeResolutionPrice(null);
        event.setTradeEntryPrice(plan.entryPrice());
        event.setStructuralStopPrice(plan.structuralInvalidationPrice());
        event.setStopLossPrice(plan.stopLossPrice());
        event.setProfitTargetPrice(plan.primaryTarget());
        event.setRewardRiskRatio(plan.rewardRisk());
        event.setSecondaryTargetPrice(plan.secondaryTarget());
        event.setTradeActionable(plan.actionable());
        event.setTradeQualification(plan.qualification() + " " + plan.targetBasis());
        event.setTradeRiskAtr(plan.riskAtr());
        event.setTradeRiskPercent(plan.stopDistancePercent());
        event.setTradeHorizonCandles(plan.horizon());
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
        event.setHarmonicStopStatus(plan.actionable() ? HarmonicStopStatus.ACTIVE.name() : HarmonicStopStatus.PROJECTION_ONLY.name());
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
            if (HarmonicStopPlanPolicy.VERSION.equals(event.getTradePlanVersion())) {
                if (!Boolean.TRUE.equals(event.getTradeActionable())) continue;
                int horizon = event.getTradeHorizonCandles();
                List<Candle> following = candles.stream().filter(c -> c.getTimestamp() > event.getDetectionCandleTimestamp())
                        .limit(horizon).toList();
                boolean done = false;
                boolean invalidData = false;
                for (Candle bar : following) {
                    if (!TradeRiskPolicy.valid(bar)) { invalidData = true; break; }
                    var outcome = executionService == null
                            ? TradeOutcomePolicy.evaluate(event.getTradeSignal(), event.getStopLossPrice(), event.getProfitTargetPrice(), bar)
                            : executionService.evaluate(symbol, interval, event.getTradeSignal(), event.getStopLossPrice(), event.getProfitTargetPrice(), bar);
                    if (outcome == null) continue;
                    event.setHarmonicStopStatus(outcome.kind() == TradeOutcomePolicy.Kind.STOPPED ? "STOPPED" : "TARGET_REACHED");
                    event.setHarmonicStopResolutionTimestamp(bar.getTimestamp());
                    event.setHarmonicStopResolutionPrice(outcome.price());
                    event.setTradeResolutionPrice(outcome.price());
                    event.setHarmonicStopResolutionReason(outcome.reason());
                    markResolved(event);
                    if (notificationService.sendHarmonicStopOutcomeEmail(event, bar)) event.setFollowUpSentAt(LocalDateTime.now());
                    done = true;
                    break;
                }
                if (!done && !invalidData && following.size() == horizon) { resolveTimeStop(event, following.getLast()); done = true; }
                if (done) { eventRepository.save(event); resolved++; }
                continue;
            }
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
        if (HarmonicStopPlanPolicy.VERSION.equals(event.getTradePlanVersion())) event.setTradeResolutionPrice(eighthCandle.getClosePrice());
        event.setHarmonicStopResolutionReason(
                "The saved harmonic trade horizon ended at the completed close.");
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
