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
    @org.springframework.beans.factory.annotation.Autowired
    private TradeExecutionService executionService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CandlestickPatternPreferencesService tradePreferences;

    /**
     * The user's shared minimum reward/risk, also used by candlestick plans. Null, which keeps each
     * family's own default, unless the user has personalised their trade-plan settings.
     */
    static Double userRequiredRewardRisk(CandlestickPatternPreferencesService preferences,
                                         AlertEvent event, TimeInterval interval) {
        if (preferences == null || event == null || event.getAlertRule() == null
                || event.getAlertRule().getUser() == null) return null;
        try {
            var view = preferences.get(event.getAlertRule().getUser());
            double chosen = view.rewardRiskRatio(interval);
            return Double.compare(chosen, view.factoryRewardRiskRatio(interval)) == 0 ? null : chosen;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

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
        var result = ElliottTradePlanPolicy.calculate(
                        stage, cycleDirection, expectedMove, entryPrice, points, atr, interval,
                        userRequiredRewardRisk(tradePreferences, event, interval))
                .map(plan -> {
                    applyLatestPlan(event, plan);
                    return new PreparedPlan(entryTimestamp, plan);
                });
        if (result.isEmpty()) {
            event.setTradeEntryPrice(entryPrice);
            event.setStructuralStopPrice(null);
            event.setRewardRiskRatio(null);
            event.setTradeRiskAtr(null);
            event.setTradeRiskPercent(null);
            event.setTradeHorizonCandles(null);
            event.setTradeResolutionPrice(null);
            event.setElliottTargetMidPrice(null);
            event.setElliottTargetZoneLow(null);
            event.setElliottTargetZoneHigh(null);
            event.setElliottTargetBasis(null);
            event.setElliottRequiredRewardRiskRatio(null);
            event.setElliottTradeResolutionTimestamp(null);
            event.setElliottTradeResolutionClose(null);
            event.setElliottTradeResolutionReason(null);
            event.setTradePlanVersion(null);
            event.setTradeFillStatus(null);
            event.setTradeEntryTimestamp(null);
            event.setStopLossPrice(null);
            event.setProfitTargetPrice(null);
            event.setSecondaryTargetPrice(null);
            event.setTradeActionable(false);
            event.setElliottTradeActionable(false);
            event.setElliottTradePlanStatus("PROJECTION_ONLY");
            event.setTradeQualification(projectionOnlyReason(stage, points));
        }
        return result;
    }

    static String projectionOnlyReason(
            ElliottSignalStage stage,
            List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        double room = stage == ElliottSignalStage.WAVE_III_END && points != null
                ? ElliottTargetRules.waveFourRoom(points) : Double.NaN;
        Double waveOne = points == null ? null : ElliottTargetRules.price(points, "I");
        if (Double.isFinite(room) && room > 0.0 && waveOne != null
                && room < ElliottTargetRules.COMMON_WAVE_FOUR_MIN_RETRACEMENT) {
            return String.format(java.util.Locale.ROOT,
                    "Projection only: Wave IV has %.1f%% of Wave III left before Wave I territory at %s; "
                            + "no standard Fibonacci Wave IV target fits, so no trade plan is offered.",
                    room * 100, ElliottTargetRules.formatPrice(waveOne));
        }
        return "Projection only: no valid target within the standard Elliott structural boundaries.";
    }

    private void applyLatestPlan(AlertEvent event, ElliottTradePlanPolicy.TradePlan plan) {
        event.setTradeResolutionPrice(null);
        event.setTradeEntryPrice(plan.entryPrice());
        event.setTradeActionable(plan.actionable());
        event.setTradeQualification(plan.qualification());
        event.setTradeRiskAtr(plan.riskAtr());
        event.setTradeRiskPercent(plan.riskPercent());
        event.setSecondaryTargetPrice(plan.secondaryTarget());
        event.setTradeHorizonCandles(plan.horizon());
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
        boolean nextOpen = ElliottTradePlanPolicy.entersAtNextOpen(plan.version());
        event.setTradeFillStatus(nextOpen ? TradeRiskPolicy.AWAITING_FILL : null);
        event.setTradeEntryTimestamp(null);
    }

    @Transactional
    public ElliottStageTradePlan persist(AlertEvent event, PreparedPlan prepared) {
        if (event == null || event.getId() == null || prepared == null) {
            throw new IllegalArgumentException("A persisted Elliott event and prepared trade plan are required.");
        }
        ElliottTradePlanPolicy.TradePlan projection = prepared.plan();
        retireOpenPlans(event, projection.stage(), prepared.entryTimestamp(), projection.entryPrice());
        LocalDateTime now = LocalDateTime.now();

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
        plan.setSecondaryTargetPrice(projection.secondaryTarget());
        plan.setHorizonCandles(projection.horizon());
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
        plan.setFillStatus(ElliottTradePlanPolicy.entersAtNextOpen(projection.version())
                ? TradeRiskPolicy.AWAITING_FILL : null);
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        return planRepository.save(plan);
    }

    @Transactional
    public void retireOpenPlans(AlertEvent event, ElliottSignalStage stage, long timestamp, double entry) {
        if (event == null || event.getId() == null) return;
        List<ElliottStageTradePlan> history = planRepository.findByAlertEventOrderByStageRevisionAsc(event);
        LocalDateTime now = LocalDateTime.now();
        for (ElliottStageTradePlan previous : history) {
            if (previous.getStatus() != ElliottTradePlanStatus.ACTIVE
                    && previous.getStatus() != ElliottTradePlanStatus.PROJECTION_ONLY) continue;
            if (TradeRiskPolicy.AWAITING_FILL.equals(previous.getFillStatus())) {
                // Superseded before its entry candle opened: there was never a trade to measure.
                previous.setStatus(ElliottTradePlanStatus.NOT_FILLED);
                previous.setFillStatus(TradeRiskPolicy.NOT_FILLED);
                previous.setResolutionTimestamp(timestamp);
                previous.setResolutionClosePrice(entry);
                previous.setResolutionFillPrice(entry);
                previous.setResolutionReason("Not filled: the Elliott count changed before this plan's entry candle opened.");
                previous.setUpdatedAt(now);
                continue;
            }
            previous.setStatus(previous.getStage() == stage
                    ? ElliottTradePlanStatus.REVISED : ElliottTradePlanStatus.STAGE_COMPLETED);
            previous.setResolutionTimestamp(timestamp);
            previous.setResolutionClosePrice(entry);
            previous.setResolutionFillPrice(entry);
            previous.setResolutionReason(previous.getStage() == stage
                    ? "The retained Elliott count revised this stage endpoint and replaced its projection."
                    : "The next validated Elliott stage completed before this projection resolved.");
            previous.setUpdatedAt(now);
        }
        if (!history.isEmpty()) planRepository.saveAll(history);

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
            if (TradeRiskPolicy.AWAITING_FILL.equals(plan.getFillStatus())) {
                Candle fill = candles.stream().filter(c -> c.getTimestamp() > plan.getEntryTimestamp())
                        .findFirst().orElse(null);
                if (fill == null) continue;
                Resolution notFilled = fillAtNextOpen(plan, fill, candles, interval);
                if (notFilled != null) {
                    close(plan, notFilled);
                    resolved++;
                    continue;
                }
                planRepository.save(plan);
                eventRepository.save(plan.getAlertEvent());
                if (plan.getStatus() != ElliottTradePlanStatus.ACTIVE) continue;
            }
            Resolution resolution = resolve(plan, candles);
            if (resolution == null) continue;
            close(plan, resolution);
            resolved++;
        }
        return resolved;
    }

    private void close(ElliottStageTradePlan plan, Resolution resolution) {
        plan.setStatus(resolution.status());
        plan.setResolutionTimestamp(resolution.candle().getTimestamp());
        plan.setResolutionClosePrice(resolution.candle().getClosePrice());
        plan.setResolutionFillPrice(resolution.fillPrice());
        plan.setResolutionReason(resolution.reason());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);
        mirrorResolution(plan.getAlertEvent(), plan, resolution);
        if (resolution.status() != ElliottTradePlanStatus.STRUCTURE_INVALIDATED
                && notificationService.sendElliottTradePlanOutcomeEmail(plan) && !notificationService.usesDurableDelivery()) {
            plan.getAlertEvent().setFollowUpSentAt(LocalDateTime.now());
        }
        eventRepository.save(plan.getAlertEvent());
    }

    /**
     * Enters at the open of the first candle after the notification. The Fibonacci target zone and the
     * structural stop do not depend on the entry, so they are kept; the reward/risk and qualification are
     * recomputed at the real entry. Returns the not-filled resolution, or null when the plan was filled.
     */
    private Resolution fillAtNextOpen(ElliottStageTradePlan plan, Candle fill, List<Candle> candles, TimeInterval interval) {
        double open = fill.getOpenPrice() == null ? Double.NaN : fill.getOpenPrice();
        String unfilled = TradeRiskPolicy.unfilledReason(plan.getExpectedMove(), open,
                plan.getStopLossPrice(), plan.getTargetTriggerPrice());
        AlertEvent event = plan.getAlertEvent();
        boolean currentStage = event != null && event.getElliottSignalStage() == plan.getStage();
        if (unfilled != null) {
            plan.setFillStatus(TradeRiskPolicy.NOT_FILLED);
            if (currentStage) {
                event.setTradeFillStatus(TradeRiskPolicy.NOT_FILLED);
                event.setTradeEntryTimestamp(fill.getTimestamp());
            }
            return new Resolution(ElliottTradePlanStatus.NOT_FILLED, fill, unfilled,
                    Double.isFinite(open) && open > 0 ? open : fill.getClosePrice());
        }
        double atr = averageTrueRange(candles, plan.getEntryTimestamp(), ATR_PERIOD);
        var qualification = TradeRiskPolicy.qualify(plan.getExpectedMove(), open, plan.getStopLossPrice(),
                plan.getTargetTriggerPrice(), atr, interval, plan.getRequiredRewardRiskRatio());
        double risk = TradeRiskPolicy.risk(plan.getExpectedMove(), open, plan.getStopLossPrice());
        double rewardRisk = TradeRiskPolicy.reward(plan.getExpectedMove(), open, plan.getTargetTriggerPrice()) / risk;
        plan.setEntryTimestamp(fill.getTimestamp());
        plan.setEntryPrice(open);
        plan.setActualRewardRiskRatio(rewardRisk);
        plan.setActionable(qualification.actionable());
        plan.setQualification(qualification.reason());
        plan.setFillStatus(TradeRiskPolicy.FILLED);
        if (!qualification.actionable()) plan.setStatus(ElliottTradePlanStatus.PROJECTION_ONLY);
        plan.setUpdatedAt(LocalDateTime.now());
        if (currentStage) {
            event.setTradeFillStatus(TradeRiskPolicy.FILLED);
            event.setTradeEntryTimestamp(fill.getTimestamp());
            event.setTradeEntryPrice(open);
            event.setRewardRiskRatio(rewardRisk);
            event.setTradeActionable(qualification.actionable());
            event.setElliottTradeActionable(qualification.actionable());
            event.setTradeQualification(qualification.reason());
            event.setTradeRiskAtr(qualification.riskAtr());
            event.setTradeRiskPercent(qualification.riskPercent());
            event.setElliottTradePlanStatus(plan.getStatus().name());
        }
        return null;
    }

    private Resolution resolve(ElliottStageTradePlan plan, List<Candle> candles) {
        int observed = 0;
        // A plan filled at an open is exposed for the rest of that candle, so it is evaluated too.
        boolean includesEntryCandle = TradeRiskPolicy.FILLED.equals(plan.getFillStatus());
        for (Candle candle : candles) {
            if (includesEntryCandle ? candle.getTimestamp() < plan.getEntryTimestamp()
                    : candle.getTimestamp() <= plan.getEntryTimestamp()) continue;
            observed++;
            if (ElliottTradePlanPolicy.qualifiedPlan(plan.getPlanVersion())) {
                if (!TradeRiskPolicy.valid(candle)) return null;
                var outcome = executionService == null
                        ? TradeOutcomePolicy.evaluate(plan.getExpectedMove(), plan.getStopLossPrice(), plan.getTargetTriggerPrice(), candle)
                        : executionService.evaluate(plan.getAlertEvent().getAlertRule().getStockAsset().getTickerSymbol(),
                                plan.getAlertEvent().getAlertRule().getInterval(), plan.getExpectedMove(), plan.getStopLossPrice(), plan.getTargetTriggerPrice(), candle);
                if (outcome != null) return new Resolution(outcome.kind() == TradeOutcomePolicy.Kind.STOPPED
                        ? ElliottTradePlanStatus.STOPPED : ElliottTradePlanStatus.TARGET_REACHED,
                        candle, outcome.reason(), outcome.price());
                // Analytical count invalidation is handled by the signal lifecycle.
                // It must not silently tighten the saved protective stop.
                if (plan.getHorizonCandles() != null && observed >= plan.getHorizonCandles())
                    return new Resolution(ElliottTradePlanStatus.TIME_STOPPED, candle,
                            "Saved trade horizon ended at the completed close.", candle.getClosePrice());
                continue;
            }
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
            case WAVE_III_END -> "Projected Wave IV entered Wave I territory, invalidating the standard impulse count.";
        };
    }

    private void mirrorResolution(
            AlertEvent event,
            ElliottStageTradePlan plan,
            Resolution resolution) {
        if (event == null || event.getElliottSignalStage() != plan.getStage()) return;
        event.setElliottTradePlanStatus(resolution.status().name());
        event.setElliottTradeResolutionTimestamp(resolution.candle().getTimestamp());
        event.setElliottTradeResolutionClose(resolution.fillPrice());
        event.setTradeResolutionPrice(resolution.fillPrice());
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
                plan.getResolutionFillPrice() == null ? plan.getResolutionClosePrice() : plan.getResolutionFillPrice(), plan.getResolutionReason(), plan.getSecondaryTargetPrice(), plan.getHorizonCandles(),
                plan.getFillStatus());
    }

    static double averageTrueRange(List<Candle> candles, long throughTimestamp, int period) {
        if (candles == null || period < 2) return Double.NaN;
        List<Candle> chronological = candles.stream().filter(c -> c != null && c.getTimestamp() != null && c.getTimestamp() <= throughTimestamp)
                .sorted(Comparator.comparing(Candle::getTimestamp)).toList();
        return TradeRiskPolicy.atr(chronological, chronological.size() - 1, period);
    }

    record PreparedPlan(long entryTimestamp, ElliottTradePlanPolicy.TradePlan plan) {
    }

    private record Resolution(
            ElliottTradePlanStatus status,
            Candle candle,
            String reason, double fillPrice) {
        Resolution(ElliottTradePlanStatus status, Candle candle, String reason) {
            this(status, candle, reason, candle.getClosePrice());
        }
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
            String resolutionReason, Double secondaryTargetPrice, Integer horizonCandles, String fillStatus) {
        public StagePlanView(ElliottSignalStage stage, int revision, TradeSignal expectedMove,
                             ElliottTradePlanStatus status, long entryTimestamp, double entryPrice,
                             double structuralStopPrice, double stopLossPrice, Double hardInvalidationPrice,
                             double targetMidpoint, double targetZoneLow, double targetZoneHigh,
                             double targetTriggerPrice, String targetBasis, Double fibonacciRatio,
                             double requiredRewardRiskRatio, double actualRewardRiskRatio, boolean actionable,
                             String qualification, Long resolutionTimestamp, Double resolutionClosePrice,
                             String resolutionReason, Double secondaryTargetPrice, Integer horizonCandles) {
            this(stage, revision, expectedMove, status, entryTimestamp, entryPrice, structuralStopPrice, stopLossPrice,
                    hardInvalidationPrice, targetMidpoint, targetZoneLow, targetZoneHigh, targetTriggerPrice, targetBasis,
                    fibonacciRatio, requiredRewardRiskRatio, actualRewardRiskRatio, actionable, qualification,
                    resolutionTimestamp, resolutionClosePrice, resolutionReason, secondaryTargetPrice, horizonCandles, null);
        }

        public boolean awaitingFill() { return TradeRiskPolicy.AWAITING_FILL.equals(fillStatus); }

        public StagePlanView(ElliottSignalStage stage,
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
            this(stage, revision, expectedMove, status, entryTimestamp, entryPrice, structuralStopPrice, stopLossPrice, hardInvalidationPrice, targetMidpoint, targetZoneLow, targetZoneHigh, targetTriggerPrice, targetBasis, fibonacciRatio, requiredRewardRiskRatio, actualRewardRiskRatio, actionable, qualification, resolutionTimestamp, resolutionClosePrice, resolutionReason, null, null, null);
        }

    }
}
