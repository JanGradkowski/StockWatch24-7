package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.MarketDataService;
import org.example.stockwatch247.service.SignalArchiveFilter;
import org.example.stockwatch247.service.customalert.CustomAlertService.SaveCommand;
import org.example.stockwatch247.service.customalert.CustomAlertService.TargetCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.stockwatch247.service.customalert.CustomAlertTestDefinitions.all;
import static org.example.stockwatch247.service.customalert.CustomAlertTestDefinitions.ind;
import static org.example.stockwatch247.service.customalert.CustomAlertTestDefinitions.num;
import static org.example.stockwatch247.service.customalert.CustomAlertTestDefinitions.pattern;
import static org.example.stockwatch247.service.customalert.CustomAlertTestDefinitions.value;

/**
 * The heaviest account the limits allow: 25 active custom alerts of every kind over a watchlist of up to 300
 * stocks, on the stored candles of the local database (no market data requests). Times the baselines, a daily
 * check of every stock, a 20-candle catch-up and the pages that list the alerts; everything is rolled back.
 * Run with {@code -DcustomAlertsLoadTest=true} ({@code -DcustomAlertsLoadTestStocks=60} for a shorter run); the report goes
 * to target/custom-alert-load-test.txt.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false", "custom-alerts.background-enabled=false",
        "insider-activity.schedule-enabled=false", "alerts.max-tracked-stocks-per-user=300",
        // Short runs, so this one-transaction test can let go of each run's candles between them (see endCheck).
        "custom-alerts.baseline-slice-ms=1000", "analysis-store.slice-ms=1000", "analysis-store.enabled=true"})
@EnabledIfSystemProperty(named = "customAlertsLoadTest", matches = "true")
@Transactional
class CustomAlertLoadTest {
    @Autowired private CustomAlertService alerts;
    @Autowired private CustomAlertEngine engine;
    @Autowired private CustomAlertFactsLoader facts;
    @Autowired private ElliottHistoryStore elliottHistory;
    @Autowired private MarketDataService marketData;
    @Autowired private AlertRuleService archive;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    private final StringBuilder report = new StringBuilder();

    /**
     * Scheduled checks are not transactional: each repository read has its own short session. This test runs in one
     * transaction (rolled back), so the candles each stock loaded are let go after it, as they are in production.
     */
    private void endCheck() {
        entityManager.flush();
        entityManager.clear();
    }

    @Autowired private SharedAnalysisStore shared;
    @Autowired private org.example.stockwatch247.service.HistoricalSignalCacheService cache;

    /** Runs baselines until none are waiting, as the background job does; returns the seconds it took. */
    private double baselines(String label, long pairs) {
        long started = System.nanoTime();
        int runs = 0;
        while (engine.establishBaselines() > 0) {
            endCheck();
            runs++;
        }
        endCheck();
        double seconds = (System.nanoTime() - started) / 1e9;
        line("%-50s %7.1f s  (%d alert-stock pairs, %d runs)", label, seconds, pairs, runs);
        return seconds;
    }

    private void line(String format, Object... values) {
        String text = values.length == 0 ? format : String.format(format, values);
        report.append(text).append('\n');
        System.out.println(text);
    }

    /** Name, trigger mode and rule of each of the 25 alerts. */
    private static List<String[]> definitions() {
        return CustomAlertTestDefinitions.twentyFiveAlerts();
    }

    private <T> T timed(String label, Supplier<T> work) {
        long started = System.nanoTime();
        T result = work.get();
        line("%-58s %8.0f ms", label, (System.nanoTime() - started) / 1e6);
        return result;
    }

    private static double percentile(List<Double> sorted, double share) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(share * sorted.size())));
    }

    private void summarize(String label, Map<String, Double> perStock) {
        List<Double> sorted = perStock.values().stream().sorted().toList();
        double total = sorted.stream().mapToDouble(Double::doubleValue).sum();
        line("%s: %d stocks, total %.1f s, mean %.0f ms, median %.0f ms, p95 %.0f ms, max %.0f ms", label, sorted.size(), total / 1000,
                total / Math.max(1, sorted.size()), percentile(sorted, 0.5), percentile(sorted, 0.95), sorted.isEmpty() ? 0 : sorted.getLast());
        line("  slowest: %s", perStock.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(6)
                .map(entry -> entry.getKey() + " " + Math.round(entry.getValue()) + " ms").toList());
    }

    private void rewind(List<Long> alertIds, int candles, Set<String> only) {
        jdbc.update("""
                update custom_alert_states s set last_result = false, last_triggered_timestamp = null,
                    last_evaluated_timestamp = (select c.timestamp from candles c join stock_assets a on a.ticker_symbol = c.symbol
                        where a.id = s.stock_asset_id and c.time_interval = '1d' order by c.timestamp desc offset ? limit 1)
                where s.alert_id = any(?) and s.last_evaluated_timestamp is not null
                  and (? or s.stock_asset_id in (select id from stock_assets where ticker_symbol = any(?)))
                """, candles, alertIds.toArray(Long[]::new), only == null, only == null ? new String[0] : only.toArray(String[]::new));
    }

    @Test
    void twentyFiveAlertsOverThreeHundredStocks() throws Exception {
        // Stored candles only: no market data requests from the engine or the Elliott inputs.
        engine.configureMarketData(null);
        facts.configureElliott(null, elliottHistory);
        try {
            run();
        } finally {
            engine.configureMarketData(marketData);
            facts.configureElliott(marketData, elliottHistory);
            Files.writeString(Path.of("target", "custom-alert-load-test.txt"), report);
        }
    }

    /**
     * Copies rows of the local database's public schema into this test's own schema (rolled back with the test);
     * stock references follow the ticker symbol.
     */
    private int copy(String table, String where, Object... args) {
        String schema = jdbc.queryForObject("select current_schema()", String.class);
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns where table_schema = ? and table_name = ?
                  and column_name <> 'id' and is_generated = 'NEVER' order by ordinal_position
                """, String.class, schema, table);
        String names = String.join(", ", columns.stream().map(column -> '"' + column + '"').toList());
        String values = String.join(", ", columns.stream().map(column -> column.equals("stock_asset_id") ? "t.id" : "p.\"" + column + '"').toList());
        String join = columns.contains("stock_asset_id")
                ? " join public.stock_assets ps on ps.id = p.stock_asset_id join stock_assets t on t.ticker_symbol = ps.ticker_symbol" : "";
        return jdbc.update("insert into " + table + " (" + names + ") select " + values + " from public." + table + " p" + join + " where " + where, args);
    }

    private void run() {
        org.assertj.core.api.Assumptions.assumeThat(jdbc.queryForObject("select to_regclass('public.candles') is not null", Boolean.class))
                .as("needs a local database with cached candles").isTrue();
        List<String> symbols = jdbc.queryForList("""
                select c.symbol from public.candles c join public.stock_assets a on a.ticker_symbol = c.symbol
                where c.time_interval = '1d' and c.symbol not like 'ZZ%' group by c.symbol having count(*) >= 300 order by c.symbol limit ?
                """, String.class, Integer.getInteger("customAlertsLoadTestStocks", 300));
        line("Processors available to this JVM: %d", Runtime.getRuntime().availableProcessors());
        line("Stocks with at least 300 stored daily candles: %d", symbols.size());
        assertThat(symbols).as("needs a local database with cached candles").hasSizeGreaterThan(50);
        String[] tickers = symbols.toArray(String[]::new);
        long copyStarted = System.nanoTime();
        copy("stock_assets", "p.ticker_symbol = any(?) and not exists (select 1 from stock_assets x where x.ticker_symbol = p.ticker_symbol)", (Object) tickers);
        int candles = copy("candles", "p.symbol = any(?) and p.time_interval in ('1d', '1wk', '1mo', '60min')", (Object) tickers);
        int insiders = copy("insider_trades", "ps.ticker_symbol = any(?)", (Object) tickers);
        int congress = copy("congressional_trades", "ps.ticker_symbol = any(?)", (Object) tickers);
        line("Copied %d candles, %d insider and %d congressional filings in %.1f s", candles, insiders, congress, (System.nanoTime() - copyStarted) / 1e9);
        long withChildren = jdbc.queryForObject("select count(distinct symbol) from candles where time_interval = '60min' and symbol = any(?)",
                Long.class, (Object) symbols.toArray(String[]::new));
        line("  of which with 60-minute candles (Elliott subdivisions): %d", withChildren);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long userId = jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Load', 'Test', true) returning id
                """, Long.class, "custom-alert-load-" + suffix + "@example.com");
        User owner = users.findById(userId).orElseThrow();
        long list = jdbc.queryForObject("insert into watchlists (user_id, name) values (?, 'Load test') returning id", Long.class, userId);
        jdbc.update("insert into watchlist_members (watchlist_id, stock_asset_id) select ?, id from stock_assets where ticker_symbol = any(?)",
                list, symbols.toArray(String[]::new));

        List<Long> ids = new ArrayList<>();
        timed("Create 25 alerts", () -> {
            for (String[] definition : definitions()) {
                ids.add(alerts.create(owner, new SaveCommand(definition[0], "", List.of("DAILY"), json.readTree(definition[2]), definition[1], 0, 3, true,
                        List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), false, true, null)).id());
            }
            return null;
        });

        // Planner statistics for the rows this test added, as a live database has them (without, the queries that find
        // waiting baselines plan badly and dominate the timings).
        jdbc.execute("""
                analyze candles, stock_assets, watchlist_members, custom_alerts, custom_alert_targets, custom_alert_target_members,
                    custom_alert_exclusions, custom_alert_states, insider_trades, congressional_trades
                """);
        // Baselines with an empty shared store: every scan is computed (and stored).
        double cold = baselines("Baselines, empty store, 25 alerts", symbols.size() * 25L);
        line("  per stock %.2f s", cold / symbols.size());
        for (Map<String, Object> row : jdbc.queryForList("""
                select status, count(*) n from custom_alert_states where alert_id = any(?) group by status order by status
                """, (Object) ids.toArray(Long[]::new))) {
            line("  states %s: %s", row.get("status"), row.get("n"));
        }
        long matchingCold = jdbc.queryForObject(
                "select count(*) from custom_alert_states where alert_id = any(?) and last_result", Long.class, (Object) ids.toArray(Long[]::new));
        line("  matching right after the baselines: %s of %s", matchingCold, 25L * symbols.size());

        // The same 25 alerts set up again on followed stocks whose snapshots are stored (a rule edit re-baselines every stock).
        jdbc.update("update custom_alerts set revision = revision + 1 where id = any(?)", (Object) ids.toArray(Long[]::new));
        double warm = baselines("Baselines, snapshots stored, 25 alerts", symbols.size() * 25L);
        line("  per stock %.2f s; %.1fx faster than an empty store", warm / symbols.size(), cold / Math.max(0.001, warm));
        assertThat(jdbc.queryForObject("select count(*) from custom_alert_states where alert_id = any(?) and last_result",
                Long.class, (Object) ids.toArray(Long[]::new))).as("the stored snapshots give the same answers").isEqualTo(matchingCold);

        // One new alert on the whole list, with and without stored snapshots (25 is the active limit: one is paused for it).
        jdbc.update("update custom_alerts set active = false where id = ?", ids.get(24));
        String elliottAndRsi = all(pattern("ELLIOTT_WAVE", "ANY", "\"window\":{\"mode\":\"ANY\",\"candles\":5}"),
                value(ind("RSI", "{\"period\":14}", "value"), "BELOW", num(60)));
        long single = alerts.create(owner, new SaveCommand("New: Elliott and RSI", "", List.of("DAILY"), json.readTree(elliottAndRsi), "BECOMES_TRUE",
                0, 3, true, List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), false, true, null)).id();
        baselines("One new alert, snapshots stored", symbols.size());
        jdbc.update("delete from custom_alerts where id = ?", single);
        jdbc.update("delete from historical_signal_cache");
        single = alerts.create(owner, new SaveCommand("New: Elliott and RSI", "", List.of("DAILY"), json.readTree(elliottAndRsi), "BECOMES_TRUE",
                0, 3, true, List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), false, true, null)).id();
        baselines("One new alert, empty store", symbols.size());
        jdbc.update("delete from custom_alerts where id = ?", single);
        String rsiOnly = all(value(ind("RSI", "{\"period\":14}", "value"), "BELOW", num(30)));
        single = alerts.create(owner, new SaveCommand("New: RSI only", "", List.of("DAILY"), json.readTree(rsiOnly), "BECOMES_TRUE",
                0, 3, true, List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), false, true, null)).id();
        baselines("One new indicator-only alert", symbols.size());
        jdbc.update("delete from custom_alerts where id = ?", single);
        jdbc.update("update custom_alerts set active = true where id = ?", ids.get(24));

        // Keeping followed stocks' factory snapshots current: the background work after a day's checks (new candles).
        jdbc.update("delete from historical_signal_cache");
        symbols.forEach(symbol -> shared.request(jdbc.queryForObject("select id from stock_assets where ticker_symbol = ?", Long.class, symbol),
                TimeInterval.DAILY, SharedAnalysisStore.FOLLOWED));
        long workStarted = System.nanoTime();
        int snapshots = 0;
        for (int done; (done = shared.work()) > 0; ) {
            snapshots += done;
            endCheck();
        }
        double workSeconds = (System.nanoTime() - workStarted) / 1e9;
        line("Background snapshots for %d followed stocks: %.1f s, %.2f s per stock", snapshots, workSeconds, workSeconds / Math.max(1, snapshots));
        jdbc.update("update custom_alerts set revision = revision + 1 where id = any(?)", (Object) ids.toArray(Long[]::new));
        double afterWorker = baselines("Baselines after the background work, 25 alerts", symbols.size() * 25L);
        line("  per stock %.2f s", afterWorker / symbols.size());

        // A daily check: one new candle on every stock (the state rewound by one candle, every rule false before it);
        // a new candle means nothing stored applies yet.
        jdbc.update("delete from historical_signal_cache");
        rewind(ids, 1, null);
        long eventsBefore = jdbc.queryForObject("select coalesce(max(id), 0) from custom_alert_events", Long.class);
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();
        Map<String, Double> daily = new LinkedHashMap<>();
        int triggered = 0;
        for (String symbol : symbols) {
            long started = System.nanoTime();
            triggered += engine.evaluate(symbol, TimeInterval.DAILY, null).triggered();
            endCheck();
            daily.put(symbol, (System.nanoTime() - started) / 1e6);
        }
        long heapAfter = runtime.totalMemory() - runtime.freeMemory();
        summarize("Daily check, 25 alerts per stock", daily);
        line("  triggers: %d; heap grew %.0f MB during the run (max heap %.0f MB)", triggered, (heapAfter - heapBefore) / 1e6, runtime.maxMemory() / 1e6);
        // The same check without the shared store, in the same run (this machine's speed varies between runs).
        facts.configureStore(null);
        try {
            rewind(ids, 1, null);
            // The triggers of the pass above are recorded again, as they were then.
            jdbc.update("delete from custom_alert_events where id > ?", eventsBefore);
            Map<String, Double> withoutStore = new LinkedHashMap<>();
            for (String symbol : symbols) {
                long started = System.nanoTime();
                engine.evaluate(symbol, TimeInterval.DAILY, null);
                endCheck();
                withoutStore.put(symbol, (System.nanoTime() - started) / 1e6);
            }
            summarize("Daily check without the shared store", withoutStore);
        } finally {
            facts.configureStore(cache);
        }
        line("  outcome window open: %s", jdbc.queryForObject("select count(*) from custom_alert_events where alert_id = any(?) and outcome_status = 'PENDING'",
                Long.class, (Object) ids.toArray(Long[]::new)));

        // Without the Elliott alerts: what the waves cost.
        jdbc.update("update custom_alerts set active = false where id = any(?)", (Object) new Long[]{ids.get(14), ids.get(15)});
        jdbc.update("delete from historical_signal_cache");
        rewind(ids, 1, null);
        Map<String, Double> noElliott = new LinkedHashMap<>();
        for (String symbol : symbols) {
            long started = System.nanoTime();
            engine.evaluate(symbol, TimeInterval.DAILY, null);
            endCheck();
            noElliott.put(symbol, (System.nanoTime() - started) / 1e6);
        }
        summarize("Daily check without the 2 Elliott alerts", noElliott);
        jdbc.update("update custom_alerts set active = true where id = any(?)", (Object) new Long[]{ids.get(14), ids.get(15)});

        // A missed month: 20 candles to catch up on 30 stocks.
        Set<String> sample = Set.copyOf(symbols.subList(0, Math.min(30, symbols.size())));
        jdbc.update("delete from historical_signal_cache");
        rewind(ids, 20, sample);
        Map<String, Double> catchUp = new LinkedHashMap<>();
        for (String symbol : symbols.stream().filter(sample::contains).toList()) {
            long started = System.nanoTime();
            engine.evaluate(symbol, TimeInterval.DAILY, null);
            endCheck();
            catchUp.put(symbol, (System.nanoTime() - started) / 1e6);
        }
        summarize("Catch-up of 20 candles, 25 alerts per stock", catchUp);

        long events = jdbc.queryForObject("select count(*) from custom_alert_events where alert_id = any(?)", Long.class, (Object) ids.toArray(Long[]::new));
        line("Stored triggers after the runs: %d", events);
        timed("Email decisions for every pending trigger", () -> engine.sendPendingEmails(0));

        // Pages that read the alerts, once the planner has statistics for the rows this test added (a live database has them).
        line("");
        timed("Watchlist panel before the tables were analyzed", () -> alerts.forWatchlist(owner, list));
        jdbc.execute("""
                analyze custom_alerts, custom_alert_targets, custom_alert_target_members, custom_alert_exclusions, custom_alert_states,
                    custom_alert_events, watchlist_members, watchlist_notification_origins, stock_assets
                """);
        timed("Alerts page list (25 alerts)", () -> alerts.list(owner));
        timed("One alert's stock states (" + symbols.size() + " rows)", () -> alerts.states(owner, ids.get(23)));
        timed("Stock page panel (" + symbols.getFirst() + ")", () -> alerts.forStock(owner, symbols.getFirst()));
        timed("Watchlist panel", () -> alerts.forWatchlist(owner, list));
        timed("Watchlist member badges (" + symbols.size() + " stocks)", () -> alerts.memberAlerts(owner, list, symbols));
        timed("All signals filtered to the busiest alert", () -> archive.getSignalArchive(owner, "date", "desc", 0,
                new SignalArchiveFilter("all", "", null, Set.of(), ids.get(23))));
        timed("All signals, custom alerts only", () -> archive.getSignalArchive(owner, "date", "desc", 0,
                new SignalArchiveFilter("all", "", null, Set.of("custom"), null)));
        assertThat(daily).hasSize(symbols.size());
        assertThat(jdbc.queryForObject("select count(*) from custom_alert_states where alert_id = any(?) and status = 'FAILED'",
                Long.class, (Object) ids.toArray(Long[]::new))).as("no check failed").isZero();
        line("Slowest stocks by candle count: %s", jdbc.queryForList("""
                select symbol || ' ' || count(*) from candles where time_interval = '1d' and symbol = any(?) group by symbol order by count(*) desc limit 3
                """, String.class, (Object) symbols.toArray(String[]::new)));
        daily.entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).ifPresent(entry -> line("Slowest daily check: %s", entry));
    }
}
