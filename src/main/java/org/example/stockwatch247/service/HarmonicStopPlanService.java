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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CandlestickPatternPreferencesService tradePreferences;

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
        return HarmonicStopPlanPolicy.calculate(formation, entryPrice, atr, interval,
                        ElliottTradePlanService.userRequiredRewardRisk(tradePreferences, event, interval))
                .map(plan -> {
                    apply(event, entryTimestamp, plan);
                    return plan;
                }).or(() -> {
                    event.setTradeActionable(false);
                    event.setTradeFillStatus(null);
                    event.setTradeEntryTimestamp(null);
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
        event.setTradeFillStatus(HarmonicStopPlanPolicy.entersAtNextOpen(plan.version()) ? TradeRiskPolicy.AWAITING_FILL : null);
        event.setTradeEntryTimestamp(null);
    }

    /**
     * Enters at the open of the first candle after confirmation. The stop and the Fibonacci targets do
     * not depend on the entry, so they are kept; reward/risk and qualification are recomputed at the real
     * entry. Returns false when that open is already beyond the stop or the primary target. A plan announced
     * as projection only stays a projection, even when the real open would qualify it.
     */
    private boolean fillAtNextOpen(AlertEvent event, Candle fill, List<Candle> candles, TimeInterval interval,
                                   boolean projection) {
        double open = fill.getOpenPrice() == null ? Double.NaN : fill.getOpenPrice();
        String unfilled = TradeRiskPolicy.unfilledReason(event.getTradeSignal(), open,
                event.getStopLossPrice(), event.getProfitTargetPrice());
        event.setTradeEntryTimestamp(fill.getTimestamp());
        if (unfilled != null) {
            event.setTradeFillStatus(TradeRiskPolicy.NOT_FILLED);
            event.setHarmonicStopStatus(HarmonicStopStatus.NOT_FILLED.name());
            event.setHarmonicStopResolutionTimestamp(fill.getTimestamp());
            event.setHarmonicStopResolutionPrice(Double.isFinite(open) && open > 0 ? open : fill.getClosePrice());
            event.setHarmonicStopResolutionReason(unfilled);
            markResolved(event, projection);
            return false;
        }
        String announcedReason = event.getTradeQualification();
        double atr = ElliottTradePlanService.averageTrueRange(candles, event.getDetectionCandleTimestamp(), 14);
        double blended = HarmonicStopPlanPolicy.blendedTarget(event.getProfitTargetPrice(), event.getSecondaryTargetPrice());
        var qualification = TradeRiskPolicy.qualify(event.getTradeSignal(), open, event.getStopLossPrice(),
                blended, atr, interval, requiredRewardRisk(event, interval));
        double risk = TradeRiskPolicy.risk(event.getTradeSignal(), open, event.getStopLossPrice());
        boolean actionable = !projection && qualification.actionable();
        event.setTradeEntryPrice(open);
        event.setRewardRiskRatio(TradeRiskPolicy.reward(event.getTradeSignal(), open, blended) / risk);
        event.setTradeActionable(actionable);
        event.setTradeQualification(projection ? announcedReason : qualification.reason());
        event.setTradeRiskAtr(qualification.riskAtr());
        event.setTradeRiskPercent(qualification.riskPercent());
        event.setHarmonicStopDistancePercent(risk / open * 100.0);
        event.setTradeFillStatus(TradeRiskPolicy.FILLED);
        if (!actionable) event.setHarmonicStopStatus(HarmonicStopStatus.PROJECTION_ONLY.name());
        return true;
    }

    @Transactional
    public int evaluateActivePlans(
            String symbol,
            TimeInterval interval,
            List<Candle> availableCandles) {
        JobLeaseGuard.requireOwnership();
        if (symbol == null || symbol.isBlank() || interval == null
                || availableCandles == null || availableCandles.isEmpty()) return 0;
        List<Candle> candles = usable(availableCandles);
        int resolved = 0;
        for (AlertEvent event : eventRepository.findActiveHarmonicStopPlans(symbol, interval)) {
            // A projection-only plan is followed as a paper trade with the same rules, so the archive can show
            // what it would have returned. One older than the loaded candles is left to catchUpStaleProjections.
            boolean qualified = HarmonicStopPlanPolicy.qualifiedPlan(event.getTradePlanVersion());
            boolean projection = projection(event);
            if (projection && (!followable(event)
                    || candles.isEmpty() || candles.getFirst().getTimestamp() > event.getDetectionCandleTimestamp())) continue;
            if (qualified) {
                if (evaluateQualified(event, symbol, interval, candles, projection)) resolved++;
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

    /** Fills and follows one current-version plan; true when it was resolved. */
    private boolean evaluateQualified(AlertEvent event, String symbol, TimeInterval interval,
                                      List<Candle> candles, boolean projection) {
        if (event.awaitingFill()) {
            Candle fill = candles.stream().filter(c -> c.getTimestamp() > event.getDetectionCandleTimestamp())
                    .findFirst().orElse(null);
            if (fill == null) return false;
            boolean filled = fillAtNextOpen(event, fill, candles, interval, projection);
            if (!filled && !projection && notificationService.sendHarmonicStopOutcomeEmail(event, fill)
                    && !notificationService.usesDurableDelivery()) event.setFollowUpSentAt(LocalDateTime.now());
            eventRepository.save(event);
            if (!filled) return true;
            projection = !Boolean.TRUE.equals(event.getTradeActionable());
        }
        int horizon = event.getTradeHorizonCandles();
        if (HarmonicStopPlanPolicy.scalesOut(event.getTradePlanVersion())
                && TradeRiskPolicy.FILLED.equals(event.getTradeFillStatus()) && event.getTradeEntryTimestamp() != null) {
            List<Candle> sinceEntry = candles.stream()
                    .filter(c -> c.getTimestamp() >= event.getTradeEntryTimestamp()).limit(horizon).toList();
            ScaleOutExit exit = replayScaleOut(event.getTradeSignal(), event.getTradeEntryPrice(),
                    event.getStopLossPrice(), event.getProfitTargetPrice(), event.getSecondaryTargetPrice(),
                    sinceEntry, horizon);
            if (exit == null) return false;
            event.setHarmonicStopStatus(exit.status().name());
            event.setHarmonicStopResolutionTimestamp(exit.candle().getTimestamp());
            event.setHarmonicStopResolutionPrice(exit.averageExit());
            event.setTradeResolutionPrice(exit.averageExit());
            event.setHarmonicStopResolutionReason(exit.reason());
            markResolved(event, projection);
            if (!projection && exit.status() != HarmonicStopStatus.TIME_STOPPED
                    && notificationService.sendHarmonicStopOutcomeEmail(event, exit.candle())
                    && !notificationService.usesDurableDelivery()) event.setFollowUpSentAt(LocalDateTime.now());
            eventRepository.save(event);
            return true;
        }
        // A next-open fill is exposed for the rest of its entry candle, so that candle counts as the first.
        boolean includesEntryCandle = TradeRiskPolicy.FILLED.equals(event.getTradeFillStatus())
                && event.getTradeEntryTimestamp() != null;
        List<Candle> following = candles.stream().filter(c -> includesEntryCandle
                        ? c.getTimestamp() >= event.getTradeEntryTimestamp()
                        : c.getTimestamp() > event.getDetectionCandleTimestamp())
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
            markResolved(event, projection);
            if (!projection && notificationService.sendHarmonicStopOutcomeEmail(event, bar)
                    && !notificationService.usesDurableDelivery()) event.setFollowUpSentAt(LocalDateTime.now());
            done = true;
            break;
        }
        if (!done && !invalidData && following.size() == horizon) {
            resolveTimeStop(event, following.getLast(), projection);
            done = true;
        }
        if (done) eventRepository.save(event);
        return done;
    }

    private static boolean projection(AlertEvent event) {
        return HarmonicStopStatus.PROJECTION_ONLY.name().equals(event.getHarmonicStopStatus())
                || HarmonicStopPlanPolicy.qualifiedPlan(event.getTradePlanVersion())
                && !Boolean.TRUE.equals(event.getTradeActionable());
    }

    /** A projection of a current version with the stop, target and horizon needed to follow it. */
    private static boolean followable(AlertEvent event) {
        return HarmonicStopPlanPolicy.qualifiedPlan(event.getTradePlanVersion())
                && event.getDetectionCandleTimestamp() != null && event.getStopLossPrice() != null
                && event.getProfitTargetPrice() != null && event.getTradeHorizonCandles() != null;
    }

    private List<AlertEvent> staleProjections(String symbol, TimeInterval interval, long loadedFrom) {
        return eventRepository.findActiveHarmonicStopPlans(symbol, interval).stream()
                .filter(event -> projection(event) && followable(event)
                        && event.getDetectionCandleTimestamp() < loadedFrom)
                .toList();
    }

    /** The earliest entry of a followable projection older than {@code loadedFrom}, or null. */
    Long oldestStaleProjection(String symbol, TimeInterval interval, long loadedFrom) {
        return staleProjections(symbol, interval, loadedFrom).stream()
                .map(AlertEvent::getDetectionCandleTimestamp).min(Long::compare).orElse(null);
    }

    /**
     * Follows projections older than the regularly loaded candles through the stored history that reaches
     * back before their entry. Projection results never send emails or mark the signal unread.
     */
    @Transactional
    public int catchUpStaleProjections(String symbol, TimeInterval interval, List<Candle> history, long loadedFrom) {
        JobLeaseGuard.requireOwnership();
        if (history == null || history.isEmpty()) return 0;
        List<Candle> candles = usable(history);
        int resolved = 0;
        for (AlertEvent event : staleProjections(symbol, interval, loadedFrom)) {
            if (candles.isEmpty() || candles.getFirst().getTimestamp() > event.getDetectionCandleTimestamp()) continue;
            if (evaluateQualified(event, symbol, interval, candles, true)) resolved++;
        }
        return resolved;
    }

    private static List<Candle> usable(List<Candle> availableCandles) {
        return availableCandles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null
                        && candle.getHighPrice() != null && candle.getLowPrice() != null
                        && candle.getClosePrice() != null)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
    }

    private void resolveStop(AlertEvent event, Candle breached) {
        double observedExtreme = event.getTradeSignal() == TradeSignal.BUY
                ? breached.getLowPrice() : breached.getHighPrice();
        event.setHarmonicStopStatus(HarmonicStopStatus.STOPPED.name());
        event.setHarmonicStopResolutionTimestamp(breached.getTimestamp());
        event.setHarmonicStopResolutionPrice(event.getStopLossPrice());
        event.setHarmonicStopResolutionReason(stopReason(event, breached, observedExtreme));
        markResolved(event);
        if (notificationService.sendHarmonicStopOutcomeEmail(event, breached) && !notificationService.usesDurableDelivery()) {
            event.setFollowUpSentAt(LocalDateTime.now());
        }
    }

    private void resolveTimeStop(AlertEvent event, Candle eighthCandle) {
        resolveTimeStop(event, eighthCandle, false);
    }

    private void resolveTimeStop(AlertEvent event, Candle eighthCandle, boolean projection) {
        event.setHarmonicStopStatus(HarmonicStopStatus.TIME_STOPPED.name());
        event.setHarmonicStopResolutionTimestamp(eighthCandle.getTimestamp());
        event.setHarmonicStopResolutionPrice(eighthCandle.getClosePrice());
        if (HarmonicStopPlanPolicy.qualifiedPlan(event.getTradePlanVersion())) event.setTradeResolutionPrice(eighthCandle.getClosePrice());
        event.setHarmonicStopResolutionReason(
                "The saved harmonic trade horizon ended at the completed close.");
        markResolved(event, projection);
    }

    private double requiredRewardRisk(AlertEvent event, TimeInterval interval) {
        Double user = ElliottTradePlanService.userRequiredRewardRisk(tradePreferences, event, interval);
        return user != null && user > 0 ? user : HarmonicStopPlanPolicy.scaleOutRequiredRewardRisk(interval);
    }

    record ScaleOutExit(HarmonicStopStatus status, Candle candle, double averageExit, String reason) {
    }

    /**
     * Replays a scale-out plan from its entry candle: half exits at the first target and the stop moves
     * to the entry; the other half exits at the second target, at breakeven, or at the horizon close.
     * Within one candle the order of touches is unknown, so the less favourable order is assumed.
     * Returns null while the plan is still open.
     */
    static ScaleOutExit replayScaleOut(TradeSignal side, double entry, double stop, double first, Double second,
                                       List<Candle> following, int horizon) {
        boolean buy = side == TradeSignal.BUY;
        boolean firstTaken = false;
        for (int index = 0; index < following.size() && index < horizon; index++) {
            Candle bar = following.get(index);
            if (!TradeRiskPolicy.valid(bar)) return null;
            if (!firstTaken) {
                var outcome = TradeOutcomePolicy.evaluate(side, stop, first, bar);
                if (outcome == null) continue;
                if (outcome.kind() == TradeOutcomePolicy.Kind.STOPPED) {
                    return new ScaleOutExit(HarmonicStopStatus.STOPPED, bar, outcome.price(), outcome.reason());
                }
                if (second == null) {
                    return new ScaleOutExit(HarmonicStopStatus.TARGET_REACHED, bar, first, outcome.reason());
                }
                firstTaken = true;
                // Rest of the same candle after the first target: assume a return to breakeven came first.
                boolean backToEntry = buy ? bar.getLowPrice() <= entry : bar.getHighPrice() >= entry;
                boolean secondTouched = buy ? bar.getHighPrice() >= second : bar.getLowPrice() <= second;
                if (backToEntry) {
                    return new ScaleOutExit(HarmonicStopStatus.TARGET_REACHED, bar, (first + entry) / 2.0,
                            "First target reached; the remaining half returned to breakeven in the same candle.");
                }
                if (secondTouched) {
                    return new ScaleOutExit(HarmonicStopStatus.TARGET_REACHED, bar, (first + second) / 2.0,
                            "Both targets reached in the same candle; half exited at each.");
                }
                continue;
            }
            var outcome = TradeOutcomePolicy.evaluate(side, entry, second, bar);
            if (outcome == null) continue;
            return outcome.kind() == TradeOutcomePolicy.Kind.STOPPED
                    ? new ScaleOutExit(HarmonicStopStatus.TARGET_REACHED, bar, (first + outcome.price()) / 2.0,
                            "First target reached; the remaining half was stopped at breakeven.")
                    : new ScaleOutExit(HarmonicStopStatus.TARGET_REACHED, bar, (first + second) / 2.0,
                            "Both targets reached; half exited at each.");
        }
        if (following.size() < horizon) return null;
        Candle last = following.get(horizon - 1);
        double close = last.getClosePrice();
        return new ScaleOutExit(HarmonicStopStatus.TIME_STOPPED, last, firstTaken ? (first + close) / 2.0 : close,
                firstTaken ? "First target reached; the remaining half closed at the end of the saved horizon."
                        : "The saved harmonic trade horizon ended at the completed close.");
    }

    private void markResolved(AlertEvent event) {
        markResolved(event, false);
    }

    /** A trade result returns the signal to the top as unread; a projection's modeled result is recorded quietly. */
    private void markResolved(AlertEvent event, boolean projection) {
        if (!projection) {
            event.setSentAt(LocalDateTime.now());
            event.setReadAt(null);
        }
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
