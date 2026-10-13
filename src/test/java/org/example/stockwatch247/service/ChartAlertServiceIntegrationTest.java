package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false"})
@Transactional
class ChartAlertServiceIntegrationTest {
    @Autowired
    private ChartAlertService alerts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private long user() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Chart', 'Alert', true) returning id
                """, Long.class, "chart-alert-" + suffix + "@example.com");
    }

    private String symbol() {
        return "ZA" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }

    /** Weekday candles from 5 January 2026, closing at the given prices. */
    private List<Long> candles(String symbol, int startDay, double... closes) {
        List<Long> times = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 5);
        int weekday = 0;
        while (times.size() < closes.length) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                if (weekday >= startDay) {
                    long time = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                    double close = closes[times.size()];
                    jdbc.update("""
                            insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                            values (?, '1d', ?, ?, ?, ?, ?, 1000000, current_timestamp)
                            """, symbol, time, close, close + 1, close - 1, close);
                    times.add(time);
                }
                weekday++;
            }
            date = date.plusDays(1);
        }
        return times;
    }

    private JsonNode node(String text) {
        return json.readTree(text);
    }

    @Test
    void aLineAlertTriggersOnceWhenALaterCandleClosesAcrossIt() {
        long owner = user();
        String symbol = symbol();
        candles(symbol, 0, 100, 101, 102, 101, 100, 102, 103, 102, 101, 103);
        var created = alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1d", "DRAWING", "line-1", "Resistance",
                "CROSSES_UP", "ONCE", node("{\"geometry\":\"HORIZONTAL\",\"price\":105}")));
        assertThat(created.active()).isTrue();

        // Candles already on the chart when the alert was set never trigger it.
        assertThat(alerts.evaluate(symbol, TimeInterval.DAILY).triggered()).isZero();

        candles(symbol, 10, 104, 106, 104, 107);
        var result = alerts.evaluate(symbol, TimeInterval.DAILY);
        assertThat(result.triggered()).isEqualTo(1);
        var stored = alerts.list(owner, symbol).getFirst();
        assertThat(stored.active()).isFalse();
        assertThat(stored.triggerCount()).isEqualTo(1);

        var latest = alerts.latestUnread(owner, 8);
        assertThat(latest).singleElement().satisfies(event -> {
            assertThat(event.symbol()).isEqualTo(symbol);
            assertThat(event.label()).isEqualTo("Resistance");
            assertThat(event.closePrice()).isEqualTo(106.0);
            assertThat(event.level()).isEqualTo(105.0);
            assertThat(event.condition()).isEqualTo("CROSSES_UP");
        });
        long eventId = latest.getFirst().id();
        assertThat(latest.getFirst().detailUrl()).isEqualTo("/stock/" + symbol + "?chartAlert=" + eventId + "#general");
        // Reading a trigger changes nothing; the chart page marks it read afterwards.
        assertThat(alerts.event(owner, eventId).label()).isEqualTo("Resistance");
        assertThat(alerts.latestUnread(owner, 8)).hasSize(1);
        alerts.markRead(owner, eventId);
        assertThat(alerts.latestUnread(owner, 8)).isEmpty();
        long stranger = user();
        assertThatThrownBy(() -> alerts.event(stranger, eventId)).hasMessageContaining("not found");
        assertThatThrownBy(() -> alerts.markRead(stranger, eventId)).hasMessageContaining("not found");
    }

    @Test
    void everyBarAlertsFollowASlopedLineAndRectangle() {
        long owner = user();
        String symbol = symbol();
        List<Long> times = candles(symbol, 0, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100);
        // A rising line: 100 at the first candle, 109 at the tenth, extended to the right (1 per bar).
        alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1d", "DRAWING", "trend-1", "Rising support", "BELOW",
                "EVERY_BAR", node("{\"geometry\":\"LINE\",\"a\":{\"time\":" + times.get(0) + ",\"price\":100},\"b\":{\"time\":"
                        + times.get(9) + ",\"price\":109},\"extendLeft\":false,\"extendRight\":true}")));
        alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1d", "DRAWING", "box-1", "Range", "ENTERS",
                "EVERY_BAR", node("{\"geometry\":\"BOX\",\"from\":" + times.get(0) + ",\"to\":" + (times.get(9) + 30L * 86_400)
                        + ",\"top\":115,\"bottom\":110}")));
        // Next candles: the line is at 110, 111, 112. Closes 111 (above it, entering the box), 110.5 (below it,
        // still in the box), 112.5 (above it, in the box).
        candles(symbol, 10, 111, 110.5, 112.5);

        assertThat(alerts.evaluate(symbol, TimeInterval.DAILY).triggered()).isEqualTo(2);
        var events = alerts.latestUnread(owner, 8);
        assertThat(events).extracting(ChartAlertService.LatestChartAlertView::label).containsExactlyInAnyOrder("Rising support", "Range");
        assertThat(events).filteredOn(event -> event.label().equals("Rising support")).singleElement()
                .satisfies(event -> assertThat(event.level()).isCloseTo(111.0, org.assertj.core.data.Offset.offset(1e-6)));
        // Every-bar alerts stay on.
        assertThat(alerts.list(owner, symbol)).allSatisfy(alert -> assertThat(alert.active()).isTrue());
    }

    @Test
    void indicatorAlertsCompareAnOutputWithAValue() {
        long owner = user();
        String symbol = symbol();
        candles(symbol, 0, 100, 100, 100, 100, 100, 100);
        alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1d", "INDICATOR", "sma-1", "SMA 2", "CROSSES_UP", "ONCE",
                node("{\"indicators\":[{\"id\":\"sma-1\",\"type\":\"SMA\",\"parameters\":{\"period\":2}}],\"target\":\"sma-1\","
                        + "\"output\":\"value\",\"compare\":\"VALUE\",\"value\":103}")));
        candles(symbol, 6, 104, 108);

        assertThat(alerts.evaluate(symbol, TimeInterval.DAILY).triggered()).isEqualTo(1);
        assertThat(alerts.latestUnread(owner, 8)).singleElement().satisfies(event -> {
            assertThat(event.value()).isEqualTo(106.0);
            assertThat(event.level()).isEqualTo(103.0);
        });
    }

    @Test
    void invalidAlertsAreRejectedWithAReason() {
        long owner = user();
        String symbol = symbol();
        assertThatThrownBy(() -> alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1h", "DRAWING", "d1", "Line",
                "CROSSES", "ONCE", node("{\"geometry\":\"HORIZONTAL\",\"price\":1}")))).hasMessageContaining("daily, weekly and monthly");
        assertThatThrownBy(() -> alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1d", "DRAWING", "d1", "Box",
                "CROSSES", "ONCE", node("{\"geometry\":\"BOX\",\"from\":1,\"to\":2,\"top\":2,\"bottom\":1}"))))
                .hasMessageContaining("does not apply");
        assertThatThrownBy(() -> alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1d", "DRAWING", "d1", " ",
                "CROSSES", "ONCE", node("{\"geometry\":\"HORIZONTAL\",\"price\":1}")))).hasMessageContaining("Name the alert");
        assertThatThrownBy(() -> alerts.create(owner, new ChartAlertService.CreateCommand(symbol, "1d", "INDICATOR", "i1", "RSI",
                "ENTERS", "ONCE", node("{\"indicators\":[{\"id\":\"i1\",\"type\":\"RSI\",\"parameters\":{}}],\"output\":\"value\","
                        + "\"compare\":\"CHANNEL\",\"lower\":70,\"upper\":30}")))).hasMessageContaining("lower channel level");
    }

    @Test
    void theBarClockCountsTradingDaysBeyondTheLastCandle() {
        long friday = LocalDate.of(2026, 1, 9).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long[] times = new long[10];
        LocalDate date = LocalDate.of(2025, 12, 29);
        for (int index = 0; index < times.length; date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            times[index++] = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        }
        var clock = new ChartAlertService.BarClock(times, "1d");
        assertThat(times[9]).isEqualTo(friday);
        long tuesday = LocalDate.of(2026, 1, 13).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        assertThat(clock.index(tuesday)).isEqualTo(11.0);
        assertThat(clock.index(times[3])).isEqualTo(3.0);
    }
}
