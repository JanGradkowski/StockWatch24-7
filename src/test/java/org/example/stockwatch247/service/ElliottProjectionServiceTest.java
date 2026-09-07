package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.ElliottProjectionScenario;
import org.example.stockwatch247.model.ElliottProjectionSet;
import org.example.stockwatch247.model.enums.ElliottProjectionScenarioStatus;
import org.example.stockwatch247.model.enums.ElliottProjectionSetStatus;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.ElliottProjectionScenarioRepository;
import org.example.stockwatch247.repository.ElliottProjectionSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ElliottProjectionServiceTest {
    private ElliottProjectionSetRepository setRepository;
    private ElliottProjectionScenarioRepository scenarioRepository;
    private ElliottProjectionService service;

    @BeforeEach
    void setUp() {
        setRepository = mock(ElliottProjectionSetRepository.class);
        scenarioRepository = mock(ElliottProjectionScenarioRepository.class);
        service = new ElliottProjectionService(setRepository, scenarioRepository);
        when(setRepository.save(any(ElliottProjectionSet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void completedCandleHardRuleBreachInvalidatesEveryPathButKeepsAuditRows() {
        ElliottProjectionSet set = projectionSet();
        ElliottProjectionScenario preferred = scenario(set, 1, 60);
        ElliottProjectionScenario alternate = scenario(set, 2, 50);
        when(setRepository.findForEvaluation(
                "TEST", TimeInterval.DAILY, ElliottProjectionSetStatus.ACTIVE))
                .thenReturn(List.of(set));
        when(scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(set))
                .thenReturn(List.of(preferred, alternate));

        int changed = service.evaluateOpenProjections("TEST", TimeInterval.DAILY,
                List.of(candle(101, 103, 89, 96)));

        assertThat(changed).isEqualTo(1);
        assertThat(preferred.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.INVALIDATED);
        assertThat(alternate.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.INVALIDATED);
        assertThat(set.getStatus()).isEqualTo(ElliottProjectionSetStatus.INVALIDATED);
        verify(scenarioRepository).saveAll(List.of(preferred, alternate));
        verify(scenarioRepository, never()).delete(any());
    }

    @Test
    void overdueScenarioIsDisfavoredRatherThanInvalidated() {
        ElliottProjectionSet set = projectionSet();
        ElliottProjectionScenario scenario = scenario(set, 1, 60);
        scenario.setMaximumCandles(2);
        when(setRepository.findForEvaluation(
                "TEST", TimeInterval.DAILY, ElliottProjectionSetStatus.ACTIVE))
                .thenReturn(List.of(set));
        when(scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(set))
                .thenReturn(List.of(scenario));

        service.evaluateOpenProjections("TEST", TimeInterval.DAILY, List.of(
                candle(101, 103, 96, 101), candle(102, 104, 97, 102),
                candle(103, 105, 98, 103)));

        assertThat(scenario.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.DISFAVORED);
        assertThat(scenario.getCurrentConfidence()).isLessThanOrEqualTo(24);
        assertThat(set.getStatus()).isEqualTo(ElliottProjectionSetStatus.ACTIVE);
    }

    @Test
    void nextValidatedStageCompletesOldPathsAndCreatesAReplacementSet() {
        AlertEvent event = new AlertEvent();
        event.setId(7L);
        ElliottProjectionSet previous = projectionSet();
        previous.setAlertEvent(event);
        ElliottProjectionScenario oldScenario = scenario(previous, 1, 60);
        when(setRepository.findByAlertEventAndStatusInOrderByCreatedAtAscIdAsc(
                event, List.of(ElliottProjectionSetStatus.ACTIVE)))
                .thenReturn(List.of(previous));
        when(scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(previous))
                .thenReturn(List.of(oldScenario));
        when(setRepository.countByAlertEventAndStage(event, ElliottSignalStage.WAVE_III_END))
                .thenReturn(0L);
        List<ElliottProjectionPolicy.Scenario> replacements = ElliottProjectionPolicy.generate(
                ElliottSignalStage.WAVE_III_END, "BULLISH", TradeSignal.SELL,
                105L, 125.0, wavePoints(), List.of(), TimeInterval.DAILY);

        ElliottProjectionSet created = service.persist(event,
                new ElliottProjectionService.PreparedProjection(
                        ElliottSignalStage.WAVE_III_END, 105L, 125.0, replacements));

        assertThat(previous.getStatus()).isEqualTo(ElliottProjectionSetStatus.COMPLETED);
        assertThat(oldScenario.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.COMPLETED);
        assertThat(created.getStatus()).isEqualTo(ElliottProjectionSetStatus.ACTIVE);
        assertThat(created.getStage()).isEqualTo(ElliottSignalStage.WAVE_III_END);
        verify(scenarioRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    void historicalBackfillIsCreatedOnceFromTheSavedEndpoint() {
        AlertEvent event = new AlertEvent();
        event.setId(9L);
        List<ElliottWaveDetectionService.ElliottWavePoint> points = wavePoints().subList(0, 3);
        when(setRepository.existsByAlertEventAndStageAndSourceTimestamp(
                event, ElliottSignalStage.WAVE_II_END, 102L)).thenReturn(false, true);

        boolean created = service.backfillIfMissing(
                event, ElliottSignalStage.WAVE_II_END, "BULLISH", TradeSignal.BUY,
                103L, 106.0, points,
                List.of(candle(100, 101, 99, 100), candle(101, 111, 109, 110),
                        candle(102, 106, 104, 105)), TimeInterval.DAILY);
        boolean duplicate = service.backfillIfMissing(
                event, ElliottSignalStage.WAVE_II_END, "BULLISH", TradeSignal.BUY,
                103L, 106.0, points, List.of(), TimeInterval.DAILY);

        assertThat(created).isTrue();
        assertThat(duplicate).isFalse();
        verify(scenarioRepository, times(1)).saveAll(anyList());
    }

    private ElliottProjectionSet projectionSet() {
        ElliottProjectionSet set = new ElliottProjectionSet();
        set.setStage(ElliottSignalStage.WAVE_II_END);
        set.setSourceTimestamp(100L);
        set.setSourcePrice(100.0);
        set.setStatus(ElliottProjectionSetStatus.ACTIVE);
        set.setLastEvaluatedTimestamp(100L);
        return set;
    }

    private ElliottProjectionScenario scenario(ElliottProjectionSet set, int rank, int confidence) {
        ElliottProjectionScenario scenario = new ElliottProjectionScenario();
        scenario.setProjectionSet(set);
        scenario.setScenarioKey("SCENARIO_" + rank);
        scenario.setDisplayRank(rank);
        scenario.setExpectedMove(TradeSignal.BUY);
        scenario.setStatus(ElliottProjectionScenarioStatus.ACTIVE);
        scenario.setInitialConfidence(confidence);
        scenario.setCurrentConfidence(confidence);
        scenario.setTargetMidpoint(120.0 + rank);
        scenario.setTargetZoneLow(118.0);
        scenario.setTargetZoneHigh(123.0);
        scenario.setMinimumCandles(2);
        scenario.setMaximumCandles(8);
        scenario.setHardInvalidationPrice(90.0);
        scenario.setHardInvalidationSide("BELOW");
        scenario.setScenarioInvalidationPrice(130.0);
        scenario.setScenarioInvalidationSide("ABOVE");
        return scenario;
    }

    private Candle candle(long timestamp, double high, double low, double close) {
        return new Candle("TEST", "1d", timestamp, close, high, low, close, 1_000L);
    }

    private List<ElliottWaveDetectionService.ElliottWavePoint> wavePoints() {
        return List.of(
                new ElliottWaveDetectionService.ElliottWavePoint("0", 100L, 100, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("I", 101L, 110, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("II", 102L, 105, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("III", 105L, 125, "HIGH"));
    }
}
