package org.example.stockwatch247.service;

import jakarta.persistence.EntityManager;
import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.ElliottProjectionScenarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false"})
@Transactional
class ElliottProjectionPersistenceIntegrationTest {
    @Autowired ElliottProjectionService service;
    @Autowired ElliottProjectionScenarioRepository scenarios;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @Test
    void adaptiveSnapshotsSurviveReloadAndNextStageCompletion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String symbol = "EP" + suffix;
        Long userId = jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name)
                values (?, 'test-only', 'Projection', 'Test') returning id
                """, Long.class, suffix + "@projection.test");
        Long assetId = jdbc.queryForObject("""
                insert into stock_assets (ticker_symbol, company_name, exchange)
                values (?, 'Projection test', 'TEST') returning id
                """, Long.class, symbol);
        Long ruleId = jdbc.queryForObject("""
                insert into alert_rules (user_id, stock_asset_id, interval, target_pattern, pattern_family, trade_signal)
                values (?, ?, 'DAILY', 'ELLIOTT_BULLISH_WAVE_II_END', 'ELLIOTT_WAVE', 'BUY') returning id
                """, Long.class, userId, assetId);
        long source = 1_600_000_000L;
        Long eventId = jdbc.queryForObject("""
                insert into alert_events (alert_rule_id, pattern, trade_signal, signal_candle_timestamp, close_price)
                values (?, 'ELLIOTT_BULLISH_WAVE_II_END', 'BUY', ?, 100) returning id
                """, Long.class, ruleId, source);
        AlertEvent event = em.find(AlertEvent.class, eventId);
        var points = List.of(
                new ElliottWaveDetectionService.ElliottWavePoint("0", source - 28 * 86400, 90, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("I", source - 14 * 86400, 120, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("II", source, 100, "LOW"));
        var prepared = service.prepare(ElliottSignalStage.WAVE_II_END, "BULLISH", TradeSignal.BUY,
                source, 100, points, List.of(), TimeInterval.DAILY).orElseThrow();
        var set = service.persist(event, prepared);
        em.flush();
        Long setId = set.getId();
        List<Candle> candles = new ArrayList<>();
        for (int i = -15; i <= 0; i++) candles.add(candle(symbol, source + i * 86400L, 100));
        double[] closes = {103, 108, 118, 128, 136};
        for (int i = 0; i < closes.length; i++) candles.add(candle(symbol, source + (i + 1) * 86400L, closes[i]));
        service.evaluateOpenProjections(symbol, TimeInterval.DAILY, candles);
        em.flush();
        em.clear();

        var reloaded = em.find(ElliottProjectionSet.class, setId);
        var standard = scenarios.findByProjectionSetOrderByDisplayRankAscIdAsc(reloaded).stream()
                .filter(s -> s.getScenarioKey().equals("STANDARD_WAVE_III")).findFirst().orElseThrow();
        assertThat(standard.getRevisions()).hasSize(2);
        String original = standard.getRevisions().getFirst().getProjectedPath();
        assertThat(standard.getProjectedPath()).isNotEqualTo(original);
        assertThat(standard.getLastRevisionCandleCount()).isEqualTo(5);
        assertThat(reloaded.getAvailableFromTimestamp()).isEqualTo(source);
        assertThat(service.evaluateOpenProjections(symbol, TimeInterval.DAILY, candles)).isZero();

        // The next validated stage retires paths, but their revisions remain accessible to the owner.
        event = em.find(AlertEvent.class, eventId);
        service.persist(event, new ElliottProjectionService.PreparedProjection(ElliottSignalStage.WAVE_III_END,
                source + 7 * 86400L, 140, prepared.scenarios(), source + 9 * 86400L));
        em.flush();
        em.clear();
        var history = service.history(eventId, em.find(User.class, userId));
        var previous = history.stream().filter(v -> v.stage() == ElliottSignalStage.WAVE_II_END).findFirst().orElseThrow();
        assertThat(previous.status()).isEqualTo(ElliottProjectionSetStatus.COMPLETED);
        assertThat(previous.resolutionTimestamp()).isEqualTo(source + 9 * 86400L);
        assertThat(previous.scenarios()).anySatisfy(s -> {
            assertThat(s.key()).isEqualTo("STANDARD_WAVE_III");
            assertThat(s.revisions()).hasSize(2);
            assertThat(s.revisions().getFirst().path()).isEqualTo(ElliottProjectionService.parsePath(original));
        });
        Long otherUserId = jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name)
                values (?, 'test-only', 'Other', 'Owner') returning id
                """, Long.class, suffix + "-other@projection.test");
        assertThat(service.history(eventId, em.find(User.class, otherUserId))).isEmpty();
    }

    private Candle candle(String symbol, long time, double close) {
        return new Candle(symbol, "1d", time, close, close + 1, close - 1, close, 1000L);
    }
}
