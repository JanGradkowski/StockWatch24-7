package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.customalert.CustomAlertService.SaveCommand;
import org.example.stockwatch247.service.customalert.CustomAlertService.TargetCommand;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A trigger's chart: the candles around it, the indicator line its rule read with each condition pointing at it,
 * the filings an insider condition counted, and the alert's other triggers on the stock.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false",
        "alerts.max-tracked-stocks-per-user=4"})
@Transactional
class CustomAlertTriggerChartIntegrationTest {
    @Autowired private CustomAlertService alerts;
    @Autowired private CustomAlertEngine engine;
    @Autowired private CustomAlertTriggerChart charts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;

    private LocalDate nextDay = LocalDate.of(2026, 1, 5);
    private final List<Long> days = new ArrayList<>();

    private static final String RULE = """
            {"version":1,"match":"ALL","groups":[{"id":"g1","match":"ALL","conditions":[
              {"id":"c1","kind":"VALUE","subject":{"kind":"INDICATOR","type":"RSI","parameters":{"period":14},"output":"value"},
               "operator":"ABOVE","compare":{"kind":"VALUE","value":30}},
              {"id":"c2","kind":"VALUE","subject":{"kind":"PRICE","field":"CLOSE"},"operator":"ABOVE","compare":{"kind":"VALUE","value":105}},
              {"id":"c3","kind":"INSIDER","transaction":"PURCHASE","days":30,"minCount":1,"roles":[]}]}]}
            """;

    private User user() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long id = jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Trigger', 'Chart', true) returning id
                """, Long.class, "trigger-chart-" + suffix + "@example.com");
        return users.findById(id).orElseThrow();
    }

    /** Appends weekday daily candles closing at the given prices. */
    private void candles(String symbol, double... closes) {
        for (double close : closes) {
            while (nextDay.getDayOfWeek() == DayOfWeek.SATURDAY || nextDay.getDayOfWeek() == DayOfWeek.SUNDAY) nextDay = nextDay.plusDays(1);
            long time = nextDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            jdbc.update("""
                    insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                    values (?, '1d', ?, ?, ?, ?, ?, 1000000, current_timestamp)
                    """, symbol, time, close - 0.5, close + 1, close - 1, close);
            days.add(time);
            nextDay = nextDay.plusDays(1);
        }
    }

    @Test
    void theChartCarriesTheCandlesTheLinesTheFilingsAndTheOtherTriggersOfARule() {
        User owner = user();
        String symbol = "ZT" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        long asset = jdbc.queryForObject("""
                insert into stock_assets (ticker_symbol, company_name, exchange, currency, instrument_type)
                values (?, 'Trigger Chart Inc.', 'TEST', 'USD', 'EQUITY') returning id
                """, Long.class, symbol);
        long list = jdbc.queryForObject("insert into watchlists (user_id, name) values (?, 'Charts') returning id", Long.class, owner.getId());
        jdbc.update("insert into watchlist_members (watchlist_id, stock_asset_id) values (?, ?)", list, asset);
        double[] rising = new double[30];
        for (int day = 0; day < rising.length; day++) rising[day] = 75 + day + (day % 3 == 0 ? -1.5 : 0);
        candles(symbol, rising);
        long filing = jdbc.queryForObject("""
                insert into insider_trades (stock_asset_id, provider, provider_fingerprint, ticker_symbol, insider_name, owner_role,
                    transaction_type, transaction_code, transaction_date, filing_date, shares, transaction_price)
                values (?, 'TEST', ?, ?, 'Jane Doe', 'Director', 'PURCHASE', 'P', ?, ?, 1000, 100) returning id
                """, Long.class, asset, UUID.randomUUID().toString(), symbol, nextDay.minusDays(5), nextDay.minusDays(5));
        var alert = alerts.create(owner, new SaveCommand("RSI breakout", "", List.of("DAILY"), json.readTree(RULE), "BECOMES_TRUE", 0, 3, true,
                List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), false, true, null));
        engine.establishBaselines();

        candles(symbol, 106);
        assertThat(engine.evaluate(symbol, TimeInterval.DAILY, null).triggered()).isEqualTo(1);
        candles(symbol, 104, 107);
        assertThat(engine.evaluate(symbol, TimeInterval.DAILY, null).triggered()).as("true again after a candle below").isEqualTo(1);
        candles(symbol, 108, 109);
        List<Long> events = jdbc.queryForList("select id from custom_alert_events where alert_id = ? order by candle_timestamp", Long.class, alert.id());
        assertThat(events).hasSize(2);
        long first = events.getFirst();

        var chart = charts.chart(owner, first);
        long triggerCandle = days.get(30);
        assertThat(chart.interval()).isEqualTo("DAILY");
        assertThat(chart.trigger()).isEqualTo(triggerCandle);
        assertThat(chart.candles()).extracting(candle -> candle[0].longValue()).containsExactlyElementsOf(days);
        assertThat(chart.candles().get(30)).containsExactly(triggerCandle, 105.5, 107.0, 105.0, 106.0, 1_000_000L);

        // The RSI line is drawn in its own pane from its warm-up on; the close test reads the candles themselves.
        assertThat(chart.conditions().get("c1").subject()).isEqualTo("s0");
        assertThat(chart.conditions()).doesNotContainKey("c2");
        assertThat(chart.series()).singleElement().satisfies(line -> {
            assertThat(line.id()).isEqualTo("s0");
            assertThat(line.placement()).isEqualTo("PANEL");
            assertThat(line.error()).isNull();
            assertThat(line.points()).isNotEmpty().allSatisfy(point -> assertThat(days).contains(point[0].longValue()));
            assertThat(line.points()).anySatisfy(point -> assertThat(point[0].longValue()).isEqualTo(triggerCandle));
        });

        assertThat(chart.conditions().get("c3").filings()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(filing);
            assertThat(item.person()).isEqualTo("Jane Doe");
            assertThat(item.transaction()).isEqualTo("PURCHASE");
            assertThat(item.amount()).isEqualTo("$100,000");
        });
        assertThat(chart.triggers()).extracting(CustomAlertTriggerChart.OtherTrigger::id).containsExactly(events.get(1));

        User stranger = user();
        assertThatThrownBy(() -> charts.chart(stranger, first)).isInstanceOf(UserInputException.class);
    }
}
