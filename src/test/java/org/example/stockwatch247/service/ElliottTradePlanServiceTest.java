package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.ElliottStageTradePlan;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.ElliottTradePlanStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.ElliottStageTradePlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ElliottTradePlanServiceTest {
    private ElliottStageTradePlanRepository planRepository;
    private AlertEventRepository eventRepository;
    private AlertNotificationService notificationService;
    private ElliottTradePlanService service;

    @BeforeEach
    void setUp() {
        planRepository = mock(ElliottStageTradePlanRepository.class);
        eventRepository = mock(AlertEventRepository.class);
        notificationService = mock(AlertNotificationService.class);
        service = new ElliottTradePlanService(planRepository, eventRepository, notificationService);
    }

    @Test
    void resolvesTargetOnlyFromACandleAfterEntryAndMirrorsOutcome() {
        ElliottStageTradePlan plan = activeBuyPlan();
        when(planRepository.findOpenPlans("TEST", TimeInterval.DAILY,
                List.of(ElliottTradePlanStatus.ACTIVE))).thenReturn(List.of(plan));
        when(notificationService.sendElliottTradePlanOutcomeEmail(plan)).thenReturn(true);

        int resolved = service.evaluateActivePlans("TEST", TimeInterval.DAILY, List.of(
                candle(100L, 111, 90, 111),
                candle(101L, 111, 109, 110.5)));

        assertThat(resolved).isEqualTo(1);
        assertThat(plan.getStatus()).isEqualTo(ElliottTradePlanStatus.TARGET_REACHED);
        assertThat(plan.getAlertEvent().getElliottTradePlanStatus()).isEqualTo("TARGET_REACHED");
        assertThat(plan.getAlertEvent().getElliottTradeResolutionTimestamp()).isEqualTo(101L);
        verify(notificationService).sendElliottTradePlanOutcomeEmail(plan);
        verify(eventRepository).save(plan.getAlertEvent());
    }

    @Test
    void hardRuleWickInvalidationTakesPriorityOverCloseBasedBufferedStop() {
        ElliottStageTradePlan plan = activeBuyPlan();
        when(planRepository.findOpenPlans("TEST", TimeInterval.DAILY,
                List.of(ElliottTradePlanStatus.ACTIVE))).thenReturn(List.of(plan));

        int resolved = service.evaluateActivePlans("TEST", TimeInterval.DAILY,
                List.of(candle(101L, 98, 89, 96)));

        assertThat(resolved).isEqualTo(1);
        assertThat(plan.getStatus()).isEqualTo(ElliottTradePlanStatus.STRUCTURE_INVALIDATED);
        assertThat(plan.getResolutionReason()).contains("Wave II crossed the Wave I origin");
    }

    @Test
    void projectionOnlyPlansAreNotEvaluatedAsTrades() {
        when(planRepository.findOpenPlans("TEST", TimeInterval.WEEKLY,
                List.of(ElliottTradePlanStatus.ACTIVE))).thenReturn(List.of());

        assertThat(service.evaluateActivePlans("TEST", TimeInterval.WEEKLY,
                List.of(candle(101L, 120, 80, 100)))).isZero();
        verifyNoInteractions(notificationService);
    }

    @Test
    void protectiveVersionPreservesStopDespiteCountBoundaryAndModelsGapFill() {
        var plan = activeBuyPlan();
        plan.setPlanVersion(ElliottTradePlanPolicy.VERSION);
        plan.setHorizonCandles(3);
        plan.setHardInvalidationPrice(96.0);
        when(planRepository.findOpenPlans("TEST", TimeInterval.DAILY,
                List.of(ElliottTradePlanStatus.ACTIVE))).thenReturn(List.of(plan));
        assertThat(service.evaluateActivePlans("TEST", TimeInterval.DAILY,
                List.of(new Candle("TEST", "1d", 101L, 100, 102, 95, 100.0, 100L)))).isZero();
        assertThat(service.evaluateActivePlans("TEST", TimeInterval.DAILY,
                List.of(new Candle("TEST", "1d", 102L, 90, 99, 89, 98.0, 100L)))).isEqualTo(1);
        assertThat(plan.getStatus()).isEqualTo(ElliottTradePlanStatus.STOPPED);
        assertThat(plan.getResolutionFillPrice()).isEqualTo(90);
        assertThat(plan.getResolutionClosePrice()).isEqualTo(98);
        assertThat(plan.getAlertEvent().getTradeResolutionPrice()).isEqualTo(90);
    }

    @Test
    void protectiveVersionUsesSavedHorizon() {
        var plan = activeBuyPlan();
        plan.setPlanVersion(ElliottTradePlanPolicy.VERSION);
        plan.setHorizonCandles(2);
        when(planRepository.findOpenPlans("TEST", TimeInterval.DAILY,
                List.of(ElliottTradePlanStatus.ACTIVE))).thenReturn(List.of(plan));
        assertThat(service.evaluateActivePlans("TEST", TimeInterval.DAILY,
                List.of(candle(101L, 105, 96, 102), candle(102L, 104, 97, 101)))).isEqualTo(1);
        assertThat(plan.getStatus()).isEqualTo(ElliottTradePlanStatus.TIME_STOPPED);
        assertThat(plan.getResolutionFillPrice()).isEqualTo(101);
    }

    @Test
    void invalidOhlcCannotExpireProtectivePlan() {
        var plan = activeBuyPlan();
        plan.setPlanVersion(ElliottTradePlanPolicy.VERSION);
        plan.setHorizonCandles(1);
        when(planRepository.findOpenPlans("TEST", TimeInterval.DAILY,
                List.of(ElliottTradePlanStatus.ACTIVE))).thenReturn(List.of(plan));
        assertThat(service.evaluateActivePlans("TEST", TimeInterval.DAILY,
                List.of(candle(101L, 99, 101, 100)))).isZero();
        assertThat(plan.getStatus()).isEqualTo(ElliottTradePlanStatus.ACTIVE);
    }

    private ElliottStageTradePlan activeBuyPlan() {
        AlertEvent event = new AlertEvent();
        event.setElliottSignalStage(ElliottSignalStage.WAVE_II_END);
        ElliottStageTradePlan plan = new ElliottStageTradePlan();
        plan.setAlertEvent(event);
        plan.setStage(ElliottSignalStage.WAVE_II_END);
        plan.setExpectedMove(TradeSignal.BUY);
        plan.setStatus(ElliottTradePlanStatus.ACTIVE);
        plan.setEntryTimestamp(100L);
        plan.setEntryPrice(100);
        plan.setStructuralStopPrice(95);
        plan.setStopLossPrice(94.8);
        plan.setHardInvalidationPrice(90.0);
        plan.setHardInvalidationSide("BELOW");
        plan.setTargetTriggerPrice(110);
        return plan;
    }

    private Candle candle(long timestamp, double high, double low, double close) {
        return new Candle("TEST", "1d", timestamp, close, high, low, close, 1_000L);
    }
}
