package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.ElliottStageTradePlan;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.ElliottTradePlanStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.ElliottStageTradePlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ElliottTradePlanService {
    private static final int ATR_PERIOD = 14;

    private final ElliottStageTradePlanRepository planRepository;
    private final AlertEventRepository eventRepository;
    private final AlertNotificationService notificationService;

    public ElliottTradePlanService(ElliottStageTradePlanRepository planRepository,
                                   AlertEventRepository eventRepository,
                                   AlertNotificationService notificationService) {
        this.planRepository = planRepository;
        this.eventRepository = eventRepository;
        this.notificationService = notificationService;
    }

    Optional<PreparedPlan> prepare(
            AlertEvent event,
            ElliottSignalStage stage,
            String cycleDirection,
            TradeSignal expectedMove,
            long entryTimestamp,
            double entryPrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            List<Candle> candles,
            TimeInterval interval) {
        double atr = averageTrueRange(candles, entryTimestamp, ATR_PERIOD);
        return ElliottTradePlanPolicy.calculate(
                        stage, cycleDirection, expectedMove, entryPrice, points, atr, interval)
                .map(plan -> {
                    applyLatestPlan(event, plan);
                    return new PreparedPlan(entryTimestamp, plan);
                });
    }

    private void applyLatestPlan(AlertEvent event, ElliottTradePlanPolicy.TradePlan plan) {
        event.setTradeEntryPrice(plan.entryPrice());
        event.setStructuralStopPrice(plan.structuralStopPrice());
        event.setStopLossPrice(plan.stopLossPrice());
        event.setProfitTargetPrice(plan.targetTriggerPrice());
        event.setRewardRiskRatio(plan.actualRewardRiskRatio());
        event.setTradePlanVersion(plan.version());
        event.setPreCircuitBreakerStopPrice(plan.stopLossPrice());
        event.setAtrCircuitBreakerEnabled(false);
        event.setAtrCircuitBreakerApplied(false);
        event.setAtrCircuitBreakerValue(null);
        event.setAtrCircuitBreakerPeriod(ATR_PERIOD);
        event.setAtrCircuitBreakerMultiplier(.10);
        event.setAtrCircuitBreakerThresholdPercent(null);
        event.setElliottTargetMidPrice(plan.targetMidpoint());
        event.setElliottTargetZoneLow(plan.targetZoneLow());
        event.setElliottTargetZoneHigh(plan.targetZoneHigh());
        event.setElliottTargetBasis(plan.targetBasis());
        event.setElliottRequiredRewardRiskRatio(plan.requiredRewardRiskRatio());
        event.setElliottTradeActionable(plan.actionable());
        event.setElliottTradePlanStatus(plan.actionable()
                ? ElliottTradePlanStatus.ACTIVE.name()
                : ElliottTradePlanStatus.PROJECTION_ONLY.name());
        event.setElliottTradeResolutionTimestamp(null);
        event.setElliottTradeResolutionClose(null);
        event.setElliottTradeResolutionReason(plan.qualification());
    }

    @Transactional
    public ElliottStageTradePlan persist(AlertEvent event, PreparedPlan prepared) {
        if (event == null || event.getId() == null || prepared == null) {
            throw new IllegalArgumentException("A persisted Elliott event and prepared trade plan are required.");
        }
        ElliottTradePlanPolicy.TradePlan projection = prepared.plan();
        List<ElliottStageTradePlan> history = planRepository.findByAlertEventOrderByStageRevisionAsc(event);
        LocalDateTime now = LocalDateTime.now();
        for (ElliottStageTradePlan previous : history) {
            if (previous.getStatus() != ElliottTradePlanStatus.ACTIVE
                    && previous.getStatus() != ElliottTradePlanStatus.PROJECTION_ONLY) continue;
            previous.setStatus(previous.getStage() == projection.stage()
                    ? ElliottTradePlanStatus.REVISED : ElliottTradePlanStatus.STAGE_COMPLETED);
            previous.setResolutionTimestamp(prepared.entryTimestamp());
            previous.setResolutionClosePrice(projection.entryPrice());
            previous.setResolutionReason(previous.getStage() == projection.stage()
                    ? "The retained Elliott count revised this stage endpoint and replaced its projection."
                    : "The next validated Elliott stage completed before this projection resolved.");
            previous.setUpdatedAt(now);
        }
        if (!history.isEmpty()) planRepository.saveAll(history);

        ElliottStageTradePlan plan = new ElliottStageTradePlan();
        plan.setAlertEvent(event);
        plan.setStage(projection.stage());
        plan.setStageRevision((int) planRepository.countByAlertEventAndStage(event, projection.stage()) + 1);
        plan.setExpectedMove(projection.expectedMove());
        plan.setStatus(projection.actionable()
                ? ElliottTradePlanStatus.ACTIVE : ElliottTradePlanStatus.PROJECTION_ONLY);
        plan.setPlanVersion(projection.version());
        plan.setEntryTimestamp(prepared.entryTimestamp());
        plan.setEntryPrice(projection.entryPrice());
        plan.setStructuralStopPrice(projection.structuralStopPrice());
        plan.setStopLossPrice(projection.stopLossPrice());
        plan.setStopBuffer(projection.stopBuffer());
        plan.setHardInvalidationPrice(projection.hardInvalidationPrice());
        plan.setHardInvalidationSide(projection.hardInvalidationSide() == null
                ? null : projection.hardInvalidationSide().name());
        plan.setTargetMidpoint(projection.targetMidpoint());
        plan.setTargetZoneLow(projection.targetZoneLow());
        plan.setTargetZoneHigh(projection.targetZoneHigh());
        plan.setTargetTriggerPrice(projection.targetTriggerPrice());
        plan.setTargetBasis(projection.targetBasis());
        plan.setFibonacciRatio(projection.fibonacciRatio());
        plan.setTargetZonePercent(projection.targetZonePercent());
        plan.setRequiredRewardRiskRatio(projection.requiredRewardRiskRatio());
        plan.setActualRewardRiskRatio(projection.actualRewardRiskRatio());
        plan.setActionable(projection.actionable());
        plan.setQualification(projection.qualification());
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        return planRepository.save(plan);
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
        for (ElliottStageTradePlan plan : planRepository.findOpenPlans(
                symbol, interval, List.of(ElliottTradePlanStatus.ACTIVE))) {
            Resolution resolution = resolve(plan, candles);
            if (resolution == null) continue;
            plan.setStatus(resolution.status());
            plan.setResolutionTimestamp(resolution.candle().getTimestamp());
            plan.setResolutionClosePrice(resolution.candle().getClosePrice());
            plan.setResolutionReason(resolution.reason());
            plan.setUpdatedAt(LocalDateTime.now());
            planRepository.save(plan);
            mirrorResolution(plan.getAlertEvent(), plan, resolution);
            if (resolution.status() != ElliottTradePlanStatus.STRUCTURE_INVALIDATED
                    && notificationService.sendElliottTradePlanOutcomeEmail(plan)) {
                plan.getAlertEvent().setFollowUpSentAt(LocalDateTime.now());
            }
            eventRepository.save(plan.getAlertEvent());
            resolved++;
        }
        return resolved;
    }

    private Resolution resolve(ElliottStageTradePlan plan, List<Candle> candles) {
        for (Candle candle : candles) {
            if (candle.getTimestamp() <= plan.getEntryTimestamp()) continue;
            if (hardInvalidated(plan, candle)) {
                return new Resolution(ElliottTradePlanStatus.STRUCTURE_INVALIDATED, candle,
                        hardInvalidationReason(plan));
            }
            boolean stopReached = plan.getExpectedMove() == TradeSignal.BUY
                    ? candle.getClosePrice() <= plan.getStopLossPrice()
                    : candle.getClosePrice() >= plan.getStopLossPrice();
            if (stopReached) {
                return new Resolution(ElliottTradePlanStatus.STOPPED, candle,
                        "A completed candle closed at or beyond the buffered Elliott trade stop.");
            }
            boolean targetReached = plan.getExpectedMove() == TradeSignal.BUY
                    ? candle.getClosePrice() >= plan.getTargetTriggerPrice()
                    : candle.getClosePrice() <= plan.getTargetTriggerPrice();
            if (targetReached) {
                return new Resolution(ElliottTradePlanStatus.TARGET_REACHED, candle,
                        "A completed candle closed inside or beyond the conservative edge of the Fibonacci target zone.");
            }
        }
        return null;
    }

    private boolean hardInvalidated(ElliottStageTradePlan plan, Candle candle) {
        if (plan.getHardInvalidationPrice() == null || plan.getHardInvalidationSide() == null) return false;
        return "BELOW".equals(plan.getHardInvalidationSide())
                ? candle.getLowPrice() <= plan.getHardInvalidationPrice()
                : candle.getHighPrice() >= plan.getHardInvalidationPrice();
    }

    private String hardInvalidationReason(ElliottStageTradePlan plan) {
        return switch (plan.getStage()) {
            case WAVE_II_END -> "Wave II crossed the Wave I origin, invalidating the Elliott count and trade.";
            case WAVE_IV_END -> "Wave IV entered Wave I price territory, invalidating the standard impulse count and trade.";
            case WAVE_V_END -> "Wave V extended far enough to make Wave III the shortest motive wave, invalidating the count and trade.";
            case CORRECTION_END -> "The correction crossed the stored impulse origin, invalidating the connected count and trade.";
            case WAVE_III_END -> "Price crossed the stored impulse origin, invalidating the developing count and trade.";
        };
    }

    private void mirrorResolution(
            AlertEvent event,
            ElliottStageTradePlan plan,
            Resolution resolution) {
        if (event == null || event.getElliottSignalStage() != plan.getStage()) return;
        event.setElliottTradePlanStatus(resolution.status().name());
        event.setElliottTradeResolutionTimestamp(resolution.candle().getTimestamp());
        event.setElliottTradeResolutionClose(resolution.candle().getClosePrice());
        event.setElliottTradeResolutionReason(resolution.reason());
        event.setSentAt(LocalDateTime.now());
        event.setReadAt(null);
    }

    @Transactional(readOnly = true)
    public java.util.Map<Long, List<StagePlanView>> histories(List<Long> ids, User user) {
        if (ids.isEmpty()) return java.util.Map.of();
        return planRepository.findOwnedHistories(ids, user).stream().collect(java.util.stream.Collectors.groupingBy(
                plan -> plan.getAlertEvent().getId(), java.util.stream.Collectors.mapping(this::view, java.util.stream.Collectors.toList())));
    }

    @Transactional(readOnly = true)
    public List<StagePlanView> history(Long eventId, User user) {
        if (eventId == null || user == null) return List.of();
        return planRepository.findOwnedHistory(eventId, user).stream()
                .map(this::view)
                .toList();
    }

    Optional<StagePlanView> latestPlan(AlertEvent event) {
        if (event == null || event.getId() == null) return Optional.empty();
        return planRepository
                .findFirstByAlertEventOrderByEntryTimestampDescStageRevisionDescIdDesc(event)
                .map(this::view);
    }

    private StagePlanView view(ElliottStageTradePlan plan) {
        return new StagePlanView(
                plan.getStage(), plan.getStageRevision(), plan.getExpectedMove(), plan.getStatus(),
                plan.getEntryTimestamp(), plan.getEntryPrice(), plan.getStructuralStopPrice(),
                plan.getStopLossPrice(), plan.getHardInvalidationPrice(),
                plan.getTargetMidpoint(), plan.getTargetZoneLow(), plan.getTargetZoneHigh(),
                plan.getTargetTriggerPrice(), plan.getTargetBasis(), plan.getFibonacciRatio(),
                plan.getRequiredRewardRiskRatio(), plan.getActualRewardRiskRatio(),
                plan.isActionable(), plan.getQualification(), plan.getResolutionTimestamp(),
                plan.getResolutionClosePrice(), plan.getResolutionReason());
    }

    static double averageTrueRange(List<Candle> candles, long throughTimestamp, int period) {
        if (candles == null || period < 2) return Double.NaN;
        List<Candle> chronological = candles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null
                        && candle.getTimestamp() <= throughTimestamp)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        if (chronological.size() < period) return Double.NaN;
        int start = chronological.size() - period;
        double total = 0.0;
        Double previousClose = start == 0 ? null : chronological.get(start - 1).getClosePrice();
        for (int index = start; index < chronological.size(); index++) {
            Candle candle = chronological.get(index);
            if (candle.getHighPrice() == null || candle.getLowPrice() == null
                    || candle.getClosePrice() == null) return Double.NaN;
            double trueRange = candle.getHighPrice() - candle.getLowPrice();
            if (previousClose != null) {
                trueRange = Math.max(trueRange, Math.abs(candle.getHighPrice() - previousClose));
                trueRange = Math.max(trueRange, Math.abs(candle.getLowPrice() - previousClose));
            }
            total += trueRange;
            previousClose = candle.getClosePrice();
        }
        double atr = total / period;
        return Double.isFinite(atr) && atr > 0.0 ? atr : Double.NaN;
    }

    record PreparedPlan(long entryTimestamp, ElliottTradePlanPolicy.TradePlan plan) {
    }

    private record Resolution(
            ElliottTradePlanStatus status,
            Candle candle,
            String reason) {
    }

    public record StagePlanView(
            ElliottSignalStage stage,
            int revision,
            TradeSignal expectedMove,
            ElliottTradePlanStatus status,
            long entryTimestamp,
            double entryPrice,
            double structuralStopPrice,
            double stopLossPrice,
            Double hardInvalidationPrice,
            double targetMidpoint,
            double targetZoneLow,
            double targetZoneHigh,
            double targetTriggerPrice,
            String targetBasis,
            Double fibonacciRatio,
            double requiredRewardRiskRatio,
            double actualRewardRiskRatio,
            boolean actionable,
            String qualification,
            Long resolutionTimestamp,
            Double resolutionClosePrice,
            String resolutionReason) {
    }
}
