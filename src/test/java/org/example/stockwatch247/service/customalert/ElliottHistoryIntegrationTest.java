package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day-by-day Elliott Wave history behind previews: a preview asks for it, the background builder reads it
 * up to the latest candle, and later previews read the stored signals, each from the candle that first showed it.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false", "custom-alerts.background-enabled=false"})
@Transactional
class ElliottHistoryIntegrationTest {
    @Autowired private CustomAlertPreview preview;
    @Autowired private ElliottHistoryBuilder builder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;

    private static final String ELLIOTT_IN_LAST_5 = """
            {"version":1,"match":"ALL","groups":[{"id":"g1","match":"ALL","conditions":[
              {"id":"c1","kind":"PATTERN","family":"ELLIOTT_WAVE","direction":"ANY","window":{"mode":"ANY","candles":5}}]}]}
            """;

    private User user() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long id = jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Wave', 'History', true) returning id
                """, Long.class, "elliott-history-" + suffix + "@example.com");
        return users.findById(id).orElseThrow();
    }

    /** A stock with {@code count} weekday candles on a gentle, steady rise (no wave structure of its own). */
    private List<Long> stock(String symbol, int count) {
        jdbc.update("insert into stock_assets (ticker_symbol, company_name, exchange, currency, instrument_type) values (?, ?, 'TEST', 'USD', 'EQUITY')",
                symbol, symbol + " Inc.");
        List<Long> times = new ArrayList<>();
        LocalDate day = LocalDate.of(2025, 1, 6);
        for (int index = 0; index < count; index++) {
            while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.plusDays(1);
            long time = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            double close = 100 + index * 0.25;
            jdbc.update("""
                    insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                    values (?, '1d', ?, ?, ?, ?, ?, 1000000, current_timestamp)
                    """, symbol, time, close - 0.1, close + 0.2, close - 0.2, close);
            times.add(time);
            day = day.plusDays(1);
        }
        return times;
    }

    @Test
    void aPreviewAsksForTheHistoryTheBuilderFillsItAndStoredSignalsCountFromTheCandleThatShowedThem() {
        User owner = user();
        String symbol = "ZEH" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        List<Long> times = stock(symbol, 90);
        var command = new CustomAlertPreview.PreviewCommand("DAILY", json.readTree(ELLIOTT_IN_LAST_5), symbol, "EVERY_CANDLE",
                0, 1, List.of(), null);

        var first = preview.preview(owner, command);
        assertThat(first.available()).isTrue();
        Map<String, Object> requested = jdbc.queryForMap("""
                select c.status, c.user_id from custom_alert_elliott_coverage c join stock_assets s on s.id = c.stock_asset_id
                where s.ticker_symbol = ?
                """, symbol);
        assertThat(requested).containsEntry("status", "PENDING").containsEntry("user_id", owner.getId());

        int slices = 0;
        while (builder.buildNextSlice() && slices < 20) slices++;
        Map<String, Object> built = jdbc.queryForMap("""
                select c.status, c.covered_from, c.covered_to, c.settings_key from custom_alert_elliott_coverage c
                join stock_assets s on s.id = c.stock_asset_id where s.ticker_symbol = ?
                """, symbol);
        assertThat(built).containsEntry("status", "READY").containsEntry("covered_from", times.get(1))
                .containsEntry("covered_to", times.getLast());

        // A wave confirmed on candle 60 that only candle 62's count showed (a redrawn count).
        jdbc.update("""
                insert into custom_alert_elliott_signals (stock_asset_id, interval, settings_key, pattern, stage, bullish, score,
                    confirmed_at, known_at, anchor_at, price)
                select id, 'DAILY', ?, 'ELLIOTT_BULLISH_WAVE_III', 'WAVE_III_IN_PROGRESS', true, 90, ?, ?, ?, 115 from stock_assets
                where ticker_symbol = ?
                """, built.get("settings_key"), times.get(60), times.get(62), times.get(55), symbol);
        var second = preview.preview(owner, command);
        assertThat(second.elliottHistory()).isEqualTo("READY");
        assertThat(second.replayedCandles()).isEqualTo(89);
        List<Long> fired = second.history().stream().map(CustomAlertPreview.HistoryPoint::timestamp).toList();
        assertThat(fired).as("from the candle that showed it, while its confirmation is in the 5-candle window")
                .containsExactly(times.get(62), times.get(63), times.get(64));
    }

    @Autowired private ElliottHistoryStore histories;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    /** Runs the builder until nothing waits, reading candles afresh (this test is one transaction with one session). */
    private void build() {
        entityManager.flush();
        entityManager.clear();
        int slices = 0;
        while (builder.buildNextSlice() && slices < 20) slices++;
    }

    private void marker(String symbol, String key, String stage, long confirmed, long known) {
        jdbc.update("""
                insert into custom_alert_elliott_signals (stock_asset_id, interval, settings_key, pattern, stage, bullish, score,
                    confirmed_at, known_at, anchor_at, price)
                select id, 'DAILY', ?, 'ELLIOTT_BULLISH_WAVE_III', ?, true, 90, ?, ?, ?, 115 from stock_assets where ticker_symbol = ?
                """, key, stage, confirmed, known, confirmed, symbol);
    }

    private List<String> markers(String symbol) {
        return jdbc.queryForList("""
                select g.stage from custom_alert_elliott_signals g join stock_assets s on s.id = g.stock_asset_id
                where s.ticker_symbol = ? order by g.stage
                """, String.class, symbol);
    }

    @Test
    void aLateCorrectionIsReadAgainFromThatCandleAndAnOlderChangeRebuildsTheHistory() {
        User owner = user();
        String symbol = "ZEC" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        List<Long> times = stock(symbol, 90);
        preview.preview(owner, new CustomAlertPreview.PreviewCommand("DAILY", json.readTree(ELLIOTT_IN_LAST_5), symbol, "EVERY_CANDLE",
                0, 1, List.of(), null));
        build();
        Map<String, Object> built = jdbc.queryForMap("""
                select c.stock_asset_id, c.settings_key, c.status, c.covered_to, c.prefix_fingerprint, c.tail_fingerprints
                from custom_alert_elliott_coverage c join stock_assets s on s.id = c.stock_asset_id where s.ticker_symbol = ?
                """, symbol);
        assertThat(built).containsEntry("status", "READY").containsEntry("covered_to", times.getLast());
        assertThat((String) built.get("prefix_fingerprint")).hasSize(64);
        assertThat(((String) built.get("tail_fingerprints")).split(",")).as("one per candle for the last five").hasSize(5);
        String key = (String) built.get("settings_key");
        long assetId = (Long) built.get("stock_asset_id");
        // One wave first shown by the second-to-last candle, one first shown long before.
        marker(symbol, key, "LATE", times.get(86), times.get(88));
        marker(symbol, key, "EARLY", times.get(38), times.get(40));

        // The provider corrects the second-to-last candle after the close.
        jdbc.update("update candles set close_price = close_price + 0.05 where symbol = ? and timestamp = ?", symbol, times.get(88));
        histories.request(assetId, org.example.stockwatch247.model.enums.TimeInterval.DAILY, key, owner.getId());
        build();
        assertThat(markers(symbol)).as("what that candle showed is read again; earlier signals stay").containsExactly("EARLY");
        assertThat(jdbc.queryForMap("select status, covered_from, covered_to from custom_alert_elliott_coverage where stock_asset_id = ?", assetId))
                .containsEntry("status", "READY").containsEntry("covered_from", times.get(1)).containsEntry("covered_to", times.getLast());

        // A candle far back changes (a split, a corrected print): the history starts again.
        jdbc.update("update candles set close_price = close_price + 0.05 where symbol = ? and timestamp = ?", symbol, times.get(20));
        histories.request(assetId, org.example.stockwatch247.model.enums.TimeInterval.DAILY, key, owner.getId());
        build();
        assertThat(markers(symbol)).as("rebuilt from scratch").isEmpty();
        assertThat(jdbc.queryForMap("select status, covered_from, covered_to from custom_alert_elliott_coverage where stock_asset_id = ?", assetId))
                .containsEntry("status", "READY").containsEntry("covered_from", times.get(1)).containsEntry("covered_to", times.getLast());

        // A new candle extends the history without reading the old ones again.
        marker(symbol, key, "KEPT", times.get(50), times.get(52));
        histories.extend(assetId, org.example.stockwatch247.model.enums.TimeInterval.DAILY);
        build();
        assertThat(markers(symbol)).containsExactly("KEPT");
    }
}
