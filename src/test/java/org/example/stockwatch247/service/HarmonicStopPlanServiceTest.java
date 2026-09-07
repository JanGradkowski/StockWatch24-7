package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicStopStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HarmonicStopPlanServiceTest {
    private AlertEventRepository eventRepository;
    private AlertNotificationService notificationService;
    private HarmonicStopPlanService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(AlertEventRepository.class);
        notificationService = mock(AlertNotificationService.class);
        service = new HarmonicStopPlanService(eventRepository, notificationService);
    }

    @Test
    void ignoresConfirmationCandleAndStopsOnFirstLaterLowBreach() {
        AlertEvent event = activeEvent(TradeSignal.BUY, 95.0);
        when(eventRepository.findActiveHarmonicStopPlans("TEST", TimeInterval.DAILY))
                .thenReturn(List.of(event));
        Candle breach = candle(101L, 100, 94.5, 96);
        when(notificationService.sendHarmonicStopOutcomeEmail(event, breach)).thenReturn(true);

        int stopped = service.evaluateActivePlans("TEST", TimeInterval.DAILY, List.of(
                candle(100L, 110, 90, 100), breach));

        assertThat(stopped).isEqualTo(1);
        assertThat(event.getHarmonicStopStatus()).isEqualTo(HarmonicStopStatus.STOPPED.name());
        assertThat(event.getHarmonicStopResolutionTimestamp()).isEqualTo(101L);
        assertThat(event.getHarmonicStopResolutionPrice()).isEqualTo(95.0);
        assertThat(event.getHarmonicStopResolutionReason()).contains("low 94.5000");
        verify(notificationService).sendHarmonicStopOutcomeEmail(event, breach);
        verify(eventRepository).save(event);
    }

    @Test
    void mirrorsHighBasedStopForSellFormation() {
        AlertEvent event = activeEvent(TradeSignal.SELL, 105.0);
        when(eventRepository.findActiveHarmonicStopPlans("TEST", TimeInterval.WEEKLY))
                .thenReturn(List.of(event));

        assertThat(service.evaluateActivePlans("TEST", TimeInterval.WEEKLY,
                List.of(candle(101L, 105.5, 100, 104)))).isEqualTo(1);
        assertThat(event.getHarmonicStopResolutionPrice()).isEqualTo(105.0);
        assertThat(event.getHarmonicStopResolutionReason()).contains("high 105.5000");
    }

    @Test
    void leavesActivePlanUntouchedWithoutLaterBreach() {
        AlertEvent event = activeEvent(TradeSignal.BUY, 95.0);
        when(eventRepository.findActiveHarmonicStopPlans("TEST", TimeInterval.MONTHLY))
                .thenReturn(List.of(event));

        assertThat(service.evaluateActivePlans("TEST", TimeInterval.MONTHLY,
                List.of(candle(101L, 110, 95.1, 100)))).isZero();
        assertThat(event.getHarmonicStopStatus()).isEqualTo(HarmonicStopStatus.ACTIVE.name());
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void closesAtTheEighthCompletedCandleWhenNoStopWasBreached() {
        AlertEvent event = activeEvent(TradeSignal.BUY, 95.0);
        when(eventRepository.findActiveHarmonicStopPlans("TEST", TimeInterval.DAILY))
                .thenReturn(List.of(event));
        List<Candle> candles = java.util.stream.LongStream.rangeClosed(101, 108)
                .mapToObj(timestamp -> candle(timestamp, 110, 96, timestamp == 108 ? 112 : 101))
                .toList();

        assertThat(service.evaluateActivePlans("TEST", TimeInterval.DAILY, candles)).isEqualTo(1);

        assertThat(event.getHarmonicStopStatus()).isEqualTo(HarmonicStopStatus.TIME_STOPPED.name());
        assertThat(event.getHarmonicStopResolutionTimestamp()).isEqualTo(108L);
        assertThat(event.getHarmonicStopResolutionPrice()).isEqualTo(112.0);
        assertThat(event.getHarmonicStopResolutionReason()).contains("candle 8");
        verifyNoInteractions(notificationService);
        verify(eventRepository).save(event);
    }

    @Test
    void ignoresAStopBreachAfterTheEightCandleWindow() {
        AlertEvent event = activeEvent(TradeSignal.BUY, 95.0);
        when(eventRepository.findActiveHarmonicStopPlans("TEST", TimeInterval.DAILY))
                .thenReturn(List.of(event));
        List<Candle> candles = new java.util.ArrayList<>(java.util.stream.LongStream.rangeClosed(101, 108)
                .mapToObj(timestamp -> candle(timestamp, 110, 96, 102))
                .toList());
        candles.add(candle(109, 100, 90, 92));

        service.evaluateActivePlans("TEST", TimeInterval.DAILY, candles);

        assertThat(event.getHarmonicStopStatus()).isEqualTo(HarmonicStopStatus.TIME_STOPPED.name());
        assertThat(event.getHarmonicStopResolutionTimestamp()).isEqualTo(108L);
        assertThat(event.getHarmonicStopResolutionPrice()).isEqualTo(102.0);
    }

    private AlertEvent activeEvent(TradeSignal signal, double stop) {
        AlertEvent event = new AlertEvent();
        event.setTradeSignal(signal);
        event.setSignalCandleTimestamp(100L);
        event.setTradePlanVersion(HarmonicStopPlanPolicy.VERSION);
        event.setTradeEntryPrice(100.0);
        event.setStructuralStopPrice(signal == TradeSignal.BUY ? 96.0 : 104.0);
        event.setStopLossPrice(stop);
        event.setHarmonicStopBasis("Test structural boundary");
        event.setHarmonicStopStatus(HarmonicStopStatus.ACTIVE.name());
        return event;
    }

    private Candle candle(long timestamp, double high, double low, double close) {
        return new Candle("TEST", "1d", timestamp, close, high, low, close, 1_000L);
    }
}
