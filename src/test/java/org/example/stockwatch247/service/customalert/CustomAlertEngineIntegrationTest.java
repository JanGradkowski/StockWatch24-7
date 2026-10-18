package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.SignalArchiveFilter;
import org.example.stockwatch247.service.TechnicalOutlookCapacityException;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A custom alert applied through a watchlist: baseline without a trigger, a trigger when the rule becomes
 * true on a later candle, none while it stays true, outcomes after it, one digest per check, the watchlist
 * origin and the archive.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false",
        "alerts.max-tracked-stocks-per-user=4"})
@Transactional
class CustomAlertEngineIntegrationTest {
    @Autowired private CustomAlertService alerts;
    @Autowired private CustomAlertEngine engine;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private AlertRuleService archive;
    @Autowired private org.example.stockwatch247.service.AlertCheckJobStore jobs;

    private LocalDate nextDay = LocalDate.of(2026, 1, 5);

    private User user() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long id = jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Custom', 'Alert', true) returning id
                """, Long.class, "custom-alert-" + suffix + "@example.com");
        return users.findById(id).orElseThrow();
    }

    private String stock(String type) {
        String symbol = "ZC" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        jdbc.update("insert into stock_assets (ticker_symbol, company_name, exchange, currency, instrument_type) values (?, ?, 'TEST', 'USD', ?)",
                symbol, symbol + " Inc.", type);
        return symbol;
    }

    private long watchlist(User owner, String name, String... symbols) {
        long id = jdbc.queryForObject("insert into watchlists (user_id, name) values (?, ?) returning id", Long.class, owner.getId(), name);
        for (String symbol : symbols) {
            jdbc.update("insert into watchlist_members (watchlist_id, stock_asset_id) select ?, id from stock_assets where ticker_symbol = ?", id, symbol);
        }
        return id;
    }

    /** Appends weekday daily candles closing at the given prices, to every listed symbol. */
    private void candles(List<String> symbols, double... closes) {
        for (double close : closes) {
            while (nextDay.getDayOfWeek() == DayOfWeek.SATURDAY || nextDay.getDayOfWeek() == DayOfWeek.SUNDAY) nextDay = nextDay.plusDays(1);
            long time = nextDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            for (String symbol : symbols) {
                jdbc.update("""
                        insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                        values (?, '1d', ?, ?, ?, ?, ?, 1000000, current_timestamp)
                        """, symbol, time, close, close + 1, close - 1, close);
            }
            nextDay = nextDay.plusDays(1);
        }
    }

    private SaveCommand command(String name, String rule, String mode, int quiet, long list, boolean notifyExisting) {
        return new SaveCommand(name, "", "DAILY", json.readTree(rule), mode, quiet, 3, true,
                List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), notifyExisting, true, null);
    }

    private static final String CLOSE_ABOVE_105 = """
            {"version":1,"match":"ALL","groups":[{"id":"g1","match":"ALL","conditions":[
              {"id":"c1","kind":"VALUE","subject":{"kind":"PRICE","field":"CLOSE"},"operator":"ABOVE",
               "compare":{"kind":"VALUE","value":105}}]}]}
            """;

    private int triggers(long alertId) {
        return jdbc.queryForObject("select count(*) from custom_alert_events where alert_id = ?", Integer.class, alertId);
    }

    @Test
    void anAlertTriggersWhenItsRuleBecomesTrueOnALaterCandleAndNotWhileItStaysTrue() {
        User owner = user();
        String symbol = stock("EQUITY");
        long list = watchlist(owner, "Breakouts", symbol);
        candles(List.of(symbol), 100, 101, 102);
        var alert = alerts.create(owner, command("Above 105", CLOSE_ABOVE_105, "BECOMES_TRUE", 0, list, false));
        assertThat(alert.stocks()).isEqualTo(1);
        assertThat(alert.checking()).as("waiting for its baseline").isEqualTo(1);

        engine.establishBaselines();
        assertThat(alerts.get(owner, alert.id()).checking()).isZero();
        assertThat(alerts.states(owner, alert.id())).extracting(CustomAlertService.StockStateView::state).containsExactly("NOT_MATCHING");

        candles(List.of(symbol), 106);
        assertThat(engine.evaluate(symbol, TimeInterval.DAILY, null).triggered()).isEqualTo(1);
        candles(List.of(symbol), 107);
        assertThat(engine.evaluate(symbol, TimeInterval.DAILY, null).triggered()).as("still true").isZero();
        candles(List.of(symbol), 104, 108);
        assertThat(engine.evaluate(symbol, TimeInterval.DAILY, null).triggered()).as("false, then true again").isEqualTo(1);
        assertThat(triggers(alert.id())).isEqualTo(2);
        assertThat(alerts.get(owner, alert.id()).matching()).isEqualTo(1);

        // A retried check never records the same candle twice.
        jdbc.update("update custom_alert_states set last_evaluated_timestamp = last_evaluated_timestamp - 86400 * 3 where alert_id = ?", alert.id());
        engine.evaluate(symbol, TimeInterval.DAILY, null);
        assertThat(triggers(alert.id())).isEqualTo(2);

        // The trigger carries its evidence, rule text and the list that covered it.
        long event = jdbc.queryForObject("select min(id) from custom_alert_events where alert_id = ?", Long.class, alert.id());
        var trigger = alerts.trigger(owner, event);
        assertThat(trigger.symbol()).isEqualTo(symbol);
        assertThat(trigger.closePrice()).isEqualTo(106.0);
        assertThat(trigger.ruleText()).contains("Close price is above 105");
        assertThat(trigger.evidence().path("conditions").get(0).path("passed").asBoolean()).isTrue();
        assertThat(trigger.origins()).isEqualTo("Breakouts");
        assertThat(trigger.readAt()).isNull();
        alerts.setRead(owner, event, true);
        assertThat(jdbc.queryForObject("select read_at is not null from custom_alert_events where id = ?", Boolean.class, event)).isTrue();

        // Later candles measure the price after the trigger; three candles complete its outcome window.
        assertThat(jdbc.queryForObject("select outcome_status from custom_alert_events where id = ?", String.class, event)).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("select outcome_return from custom_alert_events where id = ?", Double.class, event))
                .as("106 to 108 three candles later").isEqualTo(1.89);

        // One email decision per check: email delivery is off here, so the triggers are marked skipped.
        engine.sendPendingEmails(0);
        assertThat(jdbc.queryForList("select distinct email_status from custom_alert_events where alert_id = ?", String.class, alert.id()))
                .containsExactly("SKIPPED");

        // The archive filtered to the alert lists its triggers.
        var page = archive.getSignalArchive(owner, "date", "desc", 0,
                new SignalArchiveFilter("all", "", null, Set.of(), alert.id()));
        assertThat(page.signals()).hasSize(2).allSatisfy(entry -> assertThat(entry.trigger().custom()).isTrue());
        var listPage = archive.getSignalArchive(owner, "date", "desc", 0, new SignalArchiveFilter("all", "", list, Set.of("custom"), null));
        assertThat(listPage.signals()).hasSize(2);
    }

    @Test
    void onceModeQuietPeriodsAndMatchesWhenApplied() {
        User owner = user();
        String first = stock("EQUITY");
        String second = stock("EQUITY");
        long list = watchlist(owner, "Quiet", first, second);
        candles(List.of(first, second), 100, 106);
        var once = alerts.create(owner, command("Once", CLOSE_ABOVE_105, "ONCE", 0, list, true));
        var quiet = alerts.create(owner, command("Quiet", CLOSE_ABOVE_105, "BECOMES_TRUE", 3, list, false));
        engine.establishBaselines();
        // Both stocks already match: only the alert that asked to hear about them records it.
        assertThat(jdbc.queryForList("select distinct kind from custom_alert_events where alert_id = ?", String.class, once.id()))
                .containsExactly("MATCHED_ON_APPLY");
        assertThat(triggers(once.id())).isEqualTo(2);
        assertThat(triggers(quiet.id())).isZero();

        candles(List.of(first, second), 100, 107, 100, 108);
        engine.evaluate(first, TimeInterval.DAILY, null);
        assertThat(jdbc.queryForObject("select count(*) from custom_alert_events e join stock_assets s on s.id = e.stock_asset_id "
                + "where e.alert_id = ? and s.ticker_symbol = ?", Integer.class, once.id(), first)).as("once per stock").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from custom_alert_events e join stock_assets s on s.id = e.stock_asset_id "
                + "where e.alert_id = ? and s.ticker_symbol = ?", Integer.class, quiet.id(), first))
                .as("107 triggers; 108 two candles later is inside the quiet period").isEqualTo(1);
    }

    @Test
    void filingsConditionsSkipFundsAndAChangedRuleStartsAgain() {
        User owner = user();
        String fund = stock("ETF");
        long list = watchlist(owner, "Funds", fund);
        candles(List.of(fund), 100, 101);
        String insider = """
                {"version":1,"match":"ALL","groups":[{"id":"g1","match":"ALL","conditions":[
                  {"id":"c1","kind":"INSIDER","transaction":"PURCHASE","days":30}]}]}
                """;
        var alert = alerts.create(owner, command("Insiders", insider, "BECOMES_TRUE", 0, list, false));
        engine.establishBaselines();
        assertThat(alerts.states(owner, alert.id())).extracting(CustomAlertService.StockStateView::state).containsExactly("SKIPPED");

        var updated = alerts.update(owner, alert.id(), command("Insiders", CLOSE_ABOVE_105, "BECOMES_TRUE", 0, list, false));
        assertThat(updated.checking()).as("re-baselined").isEqualTo(1);
        engine.establishBaselines();
        assertThat(alerts.states(owner, alert.id())).extracting(CustomAlertService.StockStateView::state).containsExactly("NOT_MATCHING");
    }

    @Test
    void listsMustBelongToTheUserAndCapacityIsChecked() {
        User owner = user();
        User stranger = user();
        String[] symbols = {stock("EQUITY"), stock("EQUITY"), stock("EQUITY"), stock("EQUITY"), stock("EQUITY")};
        long chosen = watchlist(owner, "Chosen", symbols[0], symbols[1]);
        var selected = alerts.create(owner, new SaveCommand("Chosen", "", "DAILY", json.readTree(CLOSE_ABOVE_105), null, null, null,
                null, List.of(new TargetCommand(chosen, "SELECTED", List.of(symbols[1]))), false, true, null));
        assertThat(selected.stocks()).isEqualTo(1);
        assertThat(selected.targets()).singleElement().satisfies(target -> assertThat(target.symbols()).containsExactly(symbols[1]));
        assertThatThrownBy(() -> alerts.create(owner, new SaveCommand("Missing", "", "DAILY", json.readTree(CLOSE_ABOVE_105), null, null,
                null, null, List.of(new TargetCommand(chosen, "SELECTED", List.of(symbols[3]))), false, true, null)))
                .isInstanceOf(UserInputException.class).hasMessage(symbols[3] + " is not in the watchlist Chosen.");
        assertThatThrownBy(() -> alerts.get(stranger, selected.id())).hasMessage("The custom alert was not found.");
        long foreign = watchlist(stranger, "Not yours", symbols[0]);
        assertThatThrownBy(() -> alerts.create(owner, command("Foreign", CLOSE_ABOVE_105, "BECOMES_TRUE", 0, foreign, false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        // Four instruments per account here; one is already tracked by the first alert.
        long big = watchlist(owner, "Too many", symbols);
        assertThatThrownBy(() -> alerts.create(owner, command("Big", CLOSE_ABOVE_105, "BECOMES_TRUE", 0, big, false)))
                .isInstanceOf(TechnicalOutlookCapacityException.class)
                .hasMessageContaining("would track 4 more instruments, but your account has room for 3");
    }

    @Test
    void theScheduledRunQueuesEveryStockAnActiveAlertCovers() {
        User owner = user();
        String covered = stock("EQUITY");
        String paused = stock("EQUITY");
        alerts.create(owner, command("Covered", CLOSE_ABOVE_105, "BECOMES_TRUE", 0, watchlist(owner, "One", covered), false));
        var off = alerts.create(owner, command("Paused", CLOSE_ABOVE_105, "BECOMES_TRUE", 0, watchlist(owner, "Two", paused), false));
        alerts.setActive(owner, off.id(), false);
        java.time.Instant run = java.time.Instant.parse("2099-01-02T23:00:00Z");

        jobs.enqueueScheduledRun(TimeInterval.DAILY, run);

        List<String> queued = jdbc.queryForList("select symbol from alert_check_jobs where scheduled_for = ?", String.class,
                java.sql.Timestamp.from(run));
        assertThat(queued).contains(covered).doesNotContain(paused);
    }

    @Autowired private CustomAlertActivityTargets activityTargets;
    @Autowired private org.example.stockwatch247.service.insider.InsiderActivityCheckJobStore insiderJobs;

    private static final String INSIDER_BUYS = """
            {"version":1,"match":"ALL","groups":[{"id":"g1","match":"ALL","conditions":[
              {"id":"c1","kind":"INSIDER","transaction":"PURCHASE","days":30}]}]}
            """;

    @Test
    void insiderFilingsAreCollectedForStocksWhoseFilingsAnActiveAlertReads() {
        User owner = user();
        String reads = stock("EQUITY");
        String ignores = stock("EQUITY");
        long filings = alerts.create(owner, command("Insider buys", INSIDER_BUYS, "BECOMES_TRUE", 0,
                watchlist(owner, "Filings", reads), false)).id();
        alerts.create(owner, command("Above 105", CLOSE_ABOVE_105, "BECOMES_TRUE", 0, watchlist(owner, "Prices", ignores), false));
        long readsId = jdbc.queryForObject("select id from stock_assets where ticker_symbol = ?", Long.class, reads);
        long ignoresId = jdbc.queryForObject("select id from stock_assets where ticker_symbol = ?", Long.class, ignores);

        // Nobody follows either stock for insider trades; only the alert's stock is added to the daily check.
        assertThat(activityTargets.assets("INSIDER")).contains(readsId).doesNotContain(ignoresId);
        assertThat(activityTargets.needs(readsId, "INSIDER")).isTrue();
        assertThat(activityTargets.needs(readsId, "CONGRESS")).isFalse();
        java.time.Instant run = java.time.Instant.parse("2031-03-03T00:15:00Z");
        insiderJobs.enqueueScheduledRun(run);
        List<Long> queued = jdbc.queryForList("select stock_asset_id from insider_activity_check_jobs where scheduled_for = ?",
                Long.class, java.sql.Timestamp.from(run));
        assertThat(queued).contains(readsId).doesNotContain(ignoresId);

        alerts.setActive(owner, filings, false);
        assertThat(activityTargets.needs(readsId, "INSIDER")).as("a paused alert collects nothing").isFalse();
    }
}
