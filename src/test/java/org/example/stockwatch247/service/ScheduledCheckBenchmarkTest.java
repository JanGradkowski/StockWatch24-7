package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.customalert.CustomAlertService;
import org.example.stockwatch247.service.customalert.CustomAlertService.SaveCommand;
import org.example.stockwatch247.service.customalert.CustomAlertService.TargetCommand;
import org.example.stockwatch247.service.customalert.CustomAlertTestDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real scheduled checks ({@link ScheduledAlertService#processNextQueuedCheck}) as production runs them: durable
 * checkpoints on, built-in rules of three accounts on every family and interval, 25 custom alerts, and the
 * signals, plans and projections earlier checks left open. Candles, filings and companies are copied from the
 * local database's public schema, pinned before 3 November 2026 and frozen in their own schema at the first run
 * (see {@link #inputSchema}), so every run sees the same inputs.
 * Daily, weekly and monthly checks of the last few periods are replayed in schedule order; each check's time
 * is split by stage (see {@link CheckTimings}).
 * <p>
 * Providers are replaced by stored candles (no market data request is made; downloads are not timed here), and
 * emails stay in the outbox (never sent), so their deduplication keys can be compared. After the run, the
 * signals, trade plans, projections, custom-alert states and triggers, and email keys are written as "golden"
 * output; {@code -DbenchmarkGolden=<file>} compares them with an earlier run's.
 * <p>
 * Run alone, it commits to the test schema: {@code -DscheduledCheckBenchmark=true}, optionally
 * {@code -DbenchmarkLabel=<name>}, {@code -DbenchmarkDays=8}, {@code -DbenchmarkWeeks=3}, {@code -DbenchmarkMonths=2},
 * {@code -DbenchmarkAssertLimit=true} (fail when a check took longer than 5 s). Reports go to
 * target/scheduled-check-benchmark/.
 */
@SpringBootTest(properties = {
        // Test contexts run no timers; the test calls the worker itself.
        "alerts.schedule.enabled=true",
        // Emails are queued with their deduplication keys and never delivered.
        "alerts.email.enabled=true", "email-outbox.worker-enabled=false",
        "spring.mail.host=127.0.0.1", "spring.mail.port=9",
        "custom-alerts.background-enabled=false", "analysis-store.enabled=true",
        "insider-activity.schedule-enabled=false", "alerts.max-tracked-stocks-per-user=300",
        // No provider is reachable: the market data services are replaced below, and other clients point nowhere.
        "twelve-data.api-key=", "twelve-data.base-url=http://127.0.0.1:9", "yahoo-finance.enabled=false",
        "insider-activity.api-ninjas.base-url=http://127.0.0.1:9",
        "congressional-activity.congress-invests.base-url=http://127.0.0.1:9"})
@EnabledIfSystemProperty(named = "scheduledCheckBenchmark", matches = "true")
class ScheduledCheckBenchmarkTest {
    /** Stocks checked; MSFT, AAPL and CSCO have the longest histories, NVDA a short daily one. */
    private static final List<String> STOCKS = List.of(System.getProperty("benchmarkStocks",
            "AAPL,CSCO,MSFT,KO,NVDA,AMZN").split(","));
    /** Checked before the others, so the measured checks run in a warmed-up JVM (as production's do). */
    private static final String WARM_UP = "PEP";
    /** Inputs end here: the newest daily candle is 2 November 2026. */
    private static final Instant CUTOFF = Instant.parse("2026-11-03T00:00:00Z");
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Europe/Brussels");
    private static final Path OUTPUT = Path.of("target", "scheduled-check-benchmark");

    @Autowired private ScheduledAlertService checks;
    @Autowired private AlertCheckJobStore jobs;
    @Autowired private CustomAlertService customAlerts;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @MockitoBean private TwelveDataService twelveData;
    @MockitoBean private YahooFinanceService yahoo;
    @MockitoBean private JavaMailSender mailSender;

    private final StringBuilder report = new StringBuilder();

    private void line(String format, Object... values) {
        String text = values.length == 0 ? format : String.format(java.util.Locale.ROOT, format, values);
        report.append(text).append('\n');
        System.out.println(text);
    }

    @Test
    void scheduledChecks() throws Exception {
        org.assertj.core.api.Assumptions.assumeThat(jdbc.queryForObject("select to_regclass('public.candles') is not null", Boolean.class))
                .as("needs a local database with cached candles").isTrue();
        Files.createDirectories(OUTPUT);
        String label = System.getProperty("benchmarkLabel", "run");
        List<ScheduledAlertService.CheckReport> reports = Collections.synchronizedList(new ArrayList<>());
        checks.onCheckCompleted(reports::add);
        try {
            setUp();
            int days = Integer.getInteger("benchmarkDays", 8);
            int weeks = Integer.getInteger("benchmarkWeeks", 3);
            int months = Integer.getInteger("benchmarkMonths", 2);
            List<LocalDate> tradingDays = jdbc.queryForList("""
                    select distinct timestamp from candles where symbol = 'MSFT' and time_interval = '1d' order by timestamp desc limit ?
                    """, Long.class, days + 2).stream()
                    .map(timestamp -> Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate()).sorted().toList();
            // Warm-up: two daily checks of another stock, scheduled before everything else.
            for (LocalDate day : tradingDays.subList(0, 2)) jobs.enqueue(WARM_UP, TimeInterval.DAILY, dailyRun(day));
            for (LocalDate day : tradingDays.subList(2, tradingDays.size())) {
                for (String stock : STOCKS) jobs.enqueue(stock, TimeInterval.DAILY, dailyRun(day));
            }
            LocalDate saturday = LocalDate.of(2026, 10, 31);
            for (int week = weeks - 1; week >= 0; week--) {
                for (String stock : STOCKS) jobs.enqueue(stock, TimeInterval.WEEKLY, saturday.minusWeeks(week).atStartOfDay(SCHEDULE_ZONE).toInstant());
            }
            LocalDate first = LocalDate.of(2026, 11, 1);
            for (int month = months - 1; month >= 0; month--) {
                for (String stock : STOCKS) jobs.enqueue(stock, TimeInterval.MONTHLY, first.minusMonths(month).atStartOfDay(SCHEDULE_ZONE).toInstant());
            }
            long started = System.nanoTime();
            drain();
            line("Ran %d checks in %.1f s", reports.size(), (System.nanoTime() - started) / 1e9);
            summarize(reports);
            String golden = golden();
            Files.writeString(OUTPUT.resolve(label + "-golden.json"), golden);
            Files.writeString(OUTPUT.resolve(label + "-checks.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
            String expected = System.getProperty("benchmarkGolden");
            if (expected != null && !expected.isBlank()) compareGolden(Files.readString(Path.of(expected)), golden);
            assertThat(jdbc.queryForObject("select count(*) from alert_check_jobs where status <> 'COMPLETED'", Long.class))
                    .as("every check completed").isZero();
            if (Boolean.getBoolean("benchmarkAssertLimit")) {
                assertThat(measured(reports)).allSatisfy(check -> assertThat(check.elapsedMs())
                        .as("%s %s %s", check.symbol(), check.interval(), check.scheduledFor())
                        .isLessThanOrEqualTo(ScheduledAlertService.CHECK_TIME_LIMIT_MS));
            }
        } finally {
            checks.onCheckCompleted(null);
            Files.writeString(OUTPUT.resolve(label + ".txt"), report);
        }
    }

    /** A daily run starts at midnight in the schedule's zone after the trading day. */
    private static Instant dailyRun(LocalDate tradingDay) {
        return tradingDay.plusDays(1).atStartOfDay(SCHEDULE_ZONE).toInstant();
    }

    private static List<ScheduledAlertService.CheckReport> measured(List<ScheduledAlertService.CheckReport> reports) {
        return reports.stream().filter(check -> !WARM_UP.equals(check.symbol())).toList();
    }

    /** Works through the queue as the worker does, one check after another. */
    private void drain() {
        while (true) {
            int pending = jobs.pendingCount();
            if (pending == 0) return;
            long available = jdbc.queryForObject("""
                    select count(*) from alert_check_jobs where status = 'PENDING' and available_at <= current_timestamp
                    """, Long.class);
            if (available == 0) {
                throw new AssertionError("Checks are waiting without being runnable: " + jdbc.queryForList(
                        "select symbol, interval, scheduled_for, status, last_error from alert_check_jobs where status <> 'COMPLETED'"));
            }
            checks.processNextQueuedCheck();
        }
    }

    // ------------------------------------------------------------------ setup

    private void setUp() {
        List<String> symbols = new ArrayList<>(STOCKS);
        symbols.add(WARM_UP);
        String[] tickers = symbols.toArray(String[]::new);
        long copyStarted = System.nanoTime();
        source = inputSchema(tickers);
        line("Inputs copied from schema %s", source);
        copy("stock_assets", "p.ticker_symbol = any(?) and not exists (select 1 from stock_assets x where x.ticker_symbol = p.ticker_symbol)", (Object) tickers);
        long cutoff = CUTOFF.getEpochSecond();
        long weekStart = LocalDate.of(2026, 11, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long monthStart = LocalDate.of(2026, 11, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        // Only completed candles: the week and month that were still running at the cutoff are left out.
        int candles = copy("candles", """
                p.symbol = any(?) and (p.time_interval in ('1d', '60min') and p.timestamp < ?
                    or p.time_interval = '1wk' and p.timestamp < ? or p.time_interval = '1mo' and p.timestamp < ?)
                """, tickers, cutoff, weekStart, monthStart);
        int insiders = copy("insider_trades", "ps.ticker_symbol = any(?) and p.filing_date < date '2026-11-03'", (Object) tickers);
        int congress = copy("congressional_trades", "ps.ticker_symbol = any(?) and p.disclosure_date < date '2026-11-03'", (Object) tickers);
        line("Processors available to this JVM: %d", Runtime.getRuntime().availableProcessors());
        line("Copied %d candles, %d insider and %d congressional filings for %s in %.1f s", candles, insiders, congress, symbols,
                (System.nanoTime() - copyStarted) / 1e9);
        for (Map<String, Object> row : jdbc.queryForList("""
                select symbol, time_interval, count(*) n from candles group by symbol, time_interval order by symbol, time_interval
                """)) {
            line("  %-5s %-6s %6s candles", row.get("symbol"), row.get("time_interval"), row.get("n"));
        }

        // Providers answer from the stored candles, so a refresh finds nothing new.
        when(twelveData.getTimeSeries(anyString(), anyString(), anyInt())).thenAnswer(call ->
                storedBars(call.getArgument(0), call.getArgument(1), call.getArgument(2)));

        List<User> accounts = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            long id = jdbc.queryForObject("""
                    insert into users (email, password_hash, first_name, last_name, is_verified)
                    values (?, 'test-hash', 'Benchmark', ?, true) returning id
                    """, Long.class, "check-benchmark-" + name + "@example.com", name.toUpperCase());
            accounts.add(users.findById(id).orElseThrow());
        }
        // Every account follows every stock on every interval with all six built-in rules.
        for (User account : accounts) {
            for (String symbol : symbols) {
                if (symbol.equals(WARM_UP) && !account.equals(accounts.getFirst())) continue;
                for (String interval : symbol.equals(WARM_UP) ? List.of("DAILY") : List.of("DAILY", "WEEKLY", "MONTHLY")) {
                    for (String[] rule : List.of(
                            new String[]{"ELLIOTT_WAVE", "BUY", "ELLIOTT_BULLISH_CORRECTION"},
                            new String[]{"ELLIOTT_WAVE", "SELL", "ELLIOTT_BEARISH_CORRECTION"},
                            new String[]{"HARMONIC_FORMATION", "BUY", "ANY"},
                            new String[]{"HARMONIC_FORMATION", "SELL", "ANY"},
                            new String[]{"CANDLESTICK", "BUY", "BULLISH_ENGULFING"},
                            new String[]{"CANDLESTICK", "SELL", "BEARISH_ENGULFING"})) {
                        jdbc.update("""
                                insert into alert_rules (is_active, created_at, stock_asset_id, user_id, interval, target_pattern, trade_signal, pattern_family)
                                select true, current_timestamp, id, ?, ?, ?, ?, ? from stock_assets where ticker_symbol = ?
                                """, account.getId(), interval, rule[2], rule[1], rule[0], symbol);
                    }
                }
            }
        }
        // The heaviest custom-alert account: 25 daily alerts over a watchlist of the checked stocks.
        User owner = accounts.getFirst();
        long list = jdbc.queryForObject("insert into watchlists (user_id, name) values (?, 'Benchmark') returning id", Long.class, owner.getId());
        jdbc.update("insert into watchlist_members (watchlist_id, stock_asset_id) select ?, id from stock_assets where ticker_symbol = any(?)",
                list, STOCKS.toArray(String[]::new));
        for (String[] definition : CustomAlertTestDefinitions.twentyFiveAlerts()) {
            customAlerts.create(owner, new SaveCommand(definition[0], "", "DAILY", json.readTree(definition[2]), definition[1], 0, 3, true,
                    List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), false, true, null));
        }
        line("Accounts: %d, built-in rules: %s, custom alerts: %s", accounts.size(),
                jdbc.queryForObject("select count(*) from alert_rules", Long.class),
                jdbc.queryForObject("select count(*) from custom_alerts", Long.class));
        // Planner statistics for the rows just added, as a live database has them.
        jdbc.execute("""
                analyze candles, stock_assets, alert_rules, users, watchlists, watchlist_members, custom_alerts, custom_alert_targets,
                    custom_alert_target_members, custom_alert_states, insider_trades, congressional_trades
                """);
    }

    private List<MarketDataBar> storedBars(String symbol, String providerInterval, int outputSize) {
        String interval = switch (providerInterval) {
            case "1week" -> "1wk";
            case "1month" -> "1mo";
            case "1h" -> "60min";
            default -> "1d";
        };
        List<MarketDataBar> bars = jdbc.query("""
                select timestamp, open_price, high_price, low_price, close_price, volume from candles
                where symbol = ? and time_interval = ? and volume is not null order by timestamp desc limit ?
                """, (row, index) -> new MarketDataBar(symbol, row.getLong(1), row.getDouble(2), row.getDouble(3),
                row.getDouble(4), row.getDouble(5), row.getLong(6)), symbol, interval, outputSize);
        return bars.reversed();
    }

    /** Copies rows of the public schema into the test schema; stock references follow the ticker symbol. */
    private int copy(String table, String where, Object... args) {
        String schema = jdbc.queryForObject("select current_schema()", String.class);
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns where table_schema = ? and table_name = ?
                  and column_name <> 'id' and is_generated = 'NEVER' order by ordinal_position
                """, String.class, schema, table);
        String names = String.join(", ", columns.stream().map(column -> '"' + column + '"').toList());
        String values = String.join(", ", columns.stream().map(column -> column.equals("stock_asset_id") ? "t.id" : "p.\"" + column + '"').toList());
        String join = columns.contains("stock_asset_id")
                ? " join " + source + ".stock_assets ps on ps.id = p.stock_asset_id join stock_assets t on t.ticker_symbol = ps.ticker_symbol" : "";
        return jdbc.update("insert into " + table + " (" + names + ") select " + values + " from " + source + "." + table + " p" + join + " where " + where, args);
    }

    /** The schema the inputs are copied from (see {@link #inputSchema}). */
    private String source = "public";

    /**
     * The running application revises stored candles (a provider's later correction, a refresh from another
     * provider), so runs on different days would read different inputs. The first run therefore freezes the
     * inputs in a schema of their own, benchmark_snapshot, which later runs copy from; drop that schema to take a
     * new snapshot. {@code -DbenchmarkSnapshot=false} reads the public schema directly.
     */
    private String inputSchema(String[] tickers) {
        if (!Boolean.parseBoolean(System.getProperty("benchmarkSnapshot", "true"))) return "public";
        if (Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('benchmark_snapshot.candles') is not null", Boolean.class))) {
            return "benchmark_snapshot";
        }
        String list = String.join(",", java.util.Arrays.stream(tickers).peek(ticker -> {
            if (!ticker.matches("[A-Z0-9.]{1,12}")) throw new IllegalArgumentException("Unexpected ticker " + ticker);
        }).map(ticker -> "'" + ticker + "'").toList());
        jdbc.execute("create schema benchmark_snapshot");
        jdbc.execute("create table benchmark_snapshot.stock_assets as select * from public.stock_assets where ticker_symbol in (" + list + ")");
        jdbc.execute("create table benchmark_snapshot.candles as select * from public.candles where symbol in (" + list + ")"
                + " and timestamp < " + CUTOFF.getEpochSecond());
        for (String table : List.of("insider_trades", "congressional_trades")) {
            jdbc.execute("create table benchmark_snapshot." + table + " as select p.* from public." + table + " p"
                    + " where p.stock_asset_id in (select id from benchmark_snapshot.stock_assets)");
        }
        line("Froze the inputs in schema benchmark_snapshot");
        return "benchmark_snapshot";
    }

    // ------------------------------------------------------------------ timings

    private void summarize(List<ScheduledAlertService.CheckReport> all) {
        List<ScheduledAlertService.CheckReport> reports = measured(all);
        line("");
        line("%-5s %-7s %-10s %8s %8s  %s", "Stock", "Check", "Run", "ms", "cpu ms", "largest stages (ms)");
        for (ScheduledAlertService.CheckReport check : all) {
            String stages = check.stagesMs().entrySet().stream().filter(entry -> entry.getValue() >= 20).limit(7)
                    .map(entry -> entry.getKey() + " " + entry.getValue()).reduce((left, right) -> left + ", " + right).orElse("");
            line("%-5s %-7s %-10s %8d %8d  %s%s", check.symbol(), check.interval(),
                    check.scheduledFor().atZone(SCHEDULE_ZONE).toLocalDate(), check.elapsedMs(), check.cpuMs(), stages,
                    WARM_UP.equals(check.symbol()) ? "  (warm-up, not counted)" : "");
        }
        line("");
        for (TimeInterval interval : List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            List<Long> times = reports.stream().filter(check -> check.interval() == interval)
                    .map(ScheduledAlertService.CheckReport::elapsedMs).sorted().toList();
            if (times.isEmpty()) continue;
            line("%-8s %3d checks: max %6d ms, p95 %6d ms, median %6d ms, mean %6.0f ms, over 5 s: %d", interval, times.size(),
                    times.getLast(), times.get(Math.min(times.size() - 1, (int) Math.floor(0.95 * times.size()))),
                    times.get(times.size() / 2), times.stream().mapToLong(Long::longValue).average().orElse(0),
                    times.stream().filter(time -> time > ScheduledAlertService.CHECK_TIME_LIMIT_MS).count());
        }
        ScheduledAlertService.CheckReport slowest = reports.stream().max(Comparator.comparingLong(ScheduledAlertService.CheckReport::elapsedMs)).orElseThrow();
        line("Slowest: %s %s %s, %d ms: %s", slowest.symbol(), slowest.interval(), slowest.scheduledFor().atZone(SCHEDULE_ZONE).toLocalDate(),
                slowest.elapsedMs(), slowest.stagesMs());
        line("Every check at or under 5 s: %s", reports.stream().allMatch(check -> check.elapsedMs() <= ScheduledAlertService.CHECK_TIME_LIMIT_MS));
        line("");
        line("Time per stage over all %d measured checks (sum, share, largest in one check):", reports.size());
        Map<String, long[]> stages = new TreeMap<>();
        for (ScheduledAlertService.CheckReport check : reports) {
            check.stagesMs().forEach((stage, millis) -> {
                long[] totals = stages.computeIfAbsent(stage, ignored -> new long[2]);
                totals[0] += millis;
                totals[1] = Math.max(totals[1], millis);
            });
        }
        long total = reports.stream().mapToLong(ScheduledAlertService.CheckReport::elapsedMs).sum();
        stages.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, long[]> entry) -> entry.getValue()[0]).reversed())
                .forEach(entry -> line("  %-40s %9d ms %5.1f%%  max %7d ms", entry.getKey(), entry.getValue()[0],
                        100.0 * entry.getValue()[0] / Math.max(1, total), entry.getValue()[1]));
        Map<String, Long> counts = new TreeMap<>();
        reports.forEach(check -> check.counts().forEach((counter, value) -> counts.merge(counter, value, Long::sum)));
        line("Counters over all measured checks: %s", counts);
        line("Open signals afterwards: %s developing Elliott, %s other open, %s active Elliott plans, %s active projections",
                jdbc.queryForObject("select count(*) from alert_events where elliott_developing", Long.class),
                jdbc.queryForObject("select count(*) from alert_events where lifecycle_status in ('POTENTIAL', 'DETECTED') and not coalesce(elliott_developing, false)", Long.class),
                jdbc.queryForObject("select count(*) from elliott_stage_trade_plans where status = 'ACTIVE'", Long.class),
                jdbc.queryForObject("select count(*) from elliott_projection_sets where status = 'ACTIVE'", Long.class));
    }

    // ------------------------------------------------------------------ golden output

    private static final Pattern WALL_CLOCK = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");

    /**
     * Everything the checks decided, without database ids and wall-clock times: rows are named by what they are
     * about (account, stock, rule, pattern, candle) and sorted, so two runs compare line by line.
     */
    private String golden() {
        Map<Long, String> rules = new LinkedHashMap<>();
        jdbc.query("""
                select r.id, u.email, a.ticker_symbol, r.interval, r.pattern_family, r.trade_signal from alert_rules r
                join users u on u.id = r.user_id join stock_assets a on a.id = r.stock_asset_id
                """, row -> {
            rules.put(row.getLong(1), String.join("/", row.getString(2).replace("check-benchmark-", "").replace("@example.com", ""),
                    row.getString(3), row.getString(4), row.getString(5), row.getString(6)));
        });
        Map<Long, String> events = new LinkedHashMap<>();
        jdbc.query("select id, alert_rule_id, pattern, signal_candle_timestamp from alert_events", row -> {
            events.put(row.getLong(1), rules.get(row.getLong(2)) + "/" + row.getString(3) + "/" + row.getLong(4));
        });
        Map<Long, String> plans = new LinkedHashMap<>();
        jdbc.query("select id, alert_event_id, elliott_stage, stage_revision from elliott_stage_trade_plans", row -> {
            plans.put(row.getLong(1), events.get(row.getLong(2)) + "/" + row.getString(3) + "/" + row.getInt(4));
        });
        Map<Long, String> projections = new LinkedHashMap<>();
        jdbc.query("select id, alert_event_id, elliott_stage, stage_revision from elliott_projection_sets", row -> {
            projections.put(row.getLong(1), events.get(row.getLong(2)) + "/" + row.getString(3) + "/" + row.getInt(4));
        });
        Map<Long, String> scenarios = new LinkedHashMap<>();
        jdbc.query("select id, projection_set_id, scenario_key from elliott_projection_scenarios", row -> {
            scenarios.put(row.getLong(1), projections.get(row.getLong(2)) + "/" + row.getString(3));
        });
        Map<Long, String> alerts = new LinkedHashMap<>();
        jdbc.query("select id, name from custom_alerts", row -> {
            alerts.put(row.getLong(1), row.getString(2));
        });
        Map<Long, String> assets = new LinkedHashMap<>();
        jdbc.query("select id, ticker_symbol from stock_assets", row -> {
            assets.put(row.getLong(1), row.getString(2));
        });

        ObjectNode root = json.createObjectNode();
        root.set("signals", rows("alert_events", Map.of("alert_rule_id", rules), events));
        root.set("tradePlans", rows("elliott_stage_trade_plans", Map.of("alert_event_id", events), plans));
        root.set("projections", rows("elliott_projection_sets", Map.of("alert_event_id", events), projections));
        root.set("projectionScenarios", rows("elliott_projection_scenarios", Map.of("projection_set_id", projections), scenarios));
        root.set("projectionRevisions", rows("elliott_projection_revisions", Map.of("scenario_id", scenarios), null));
        root.set("customAlertStates", rows("custom_alert_states", Map.of("alert_id", alerts, "stock_asset_id", assets), null));
        root.set("customAlertTriggers", rows("custom_alert_events", Map.of("alert_id", alerts, "stock_asset_id", assets), null));
        var keys = json.createArrayNode();
        List<String> emailKeys = new ArrayList<>();
        for (String table : List.of("email_outbox", "email_digest_items")) {
            jdbc.query("select deduplication_key, alert_event_id, receipt from " + table, row -> {
                Long event = (Long) row.getObject(2);
                String key = row.getString(1);
                // Ids are named by what they are: the signal (or plan) the email is about.
                if (event != null) key = key.replace(":" + event + ":", ":{signal}:").replaceAll(":" + event + "$", ":{signal}");
                java.util.regex.Matcher plan = Pattern.compile("^elliott-plan:(\\d+):").matcher(key);
                if (plan.find()) key = "elliott-plan:{" + plans.get(Long.parseLong(plan.group(1))) + "}:" + key.substring(plan.end());
                emailKeys.add(table + " " + key + " " + row.getString(3) + (event == null ? "" : " " + events.get(event)));
            });
        }
        emailKeys.stream().sorted().forEach(keys::add);
        root.set("emailKeys", keys);
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** A table's rows with ids replaced by names, wall-clock columns left out, sorted by their text. */
    private JsonNode rows(String table, Map<String, Map<Long, String>> references, Map<Long, String> names) {
        Map<String, String> types = new LinkedHashMap<>();
        jdbc.query("select column_name, data_type from information_schema.columns where table_schema = current_schema() and table_name = ?",
                row -> {
                    types.put(row.getString(1), row.getString(2));
                }, table);
        List<String> rows = new ArrayList<>();
        jdbc.query("select * from " + table, row -> {
            ObjectNode node = json.createObjectNode();
            if (names != null) node.put("_", names.get(row.getLong("id")));
            for (var column : new TreeMap<>(types).entrySet()) {
                String name = column.getKey();
                if (name.equals("id") || column.getValue().startsWith("timestamp") || name.equals("ciphertext") || name.equals("iv")) continue;
                Object value = row.getObject(name);
                if (value == null) {
                    node.putNull(name);
                } else if (references.containsKey(name)) {
                    node.put(name, references.get(name).get(((Number) value).longValue()));
                } else if (value instanceof Number number && !(value instanceof java.math.BigDecimal)) {
                    if (value instanceof Double || value instanceof Float) node.put(name, number.doubleValue());
                    else node.put(name, number.longValue());
                } else if (value instanceof Boolean flag) {
                    node.put(name, flag);
                } else {
                    node.put(name, WALL_CLOCK.matcher(value.toString()).replaceAll("<time>"));
                }
            }
            rows.add(json.writeValueAsString(node));
        });
        var array = json.createArrayNode();
        rows.stream().sorted().forEach(text -> array.add(json.readTree(text)));
        return array;
    }

    private void compareGolden(String expected, String actual) throws Exception {
        JsonNode before = json.readTree(expected);
        JsonNode after = json.readTree(actual);
        List<String> differences = new ArrayList<>();
        for (String section : (Iterable<String>) () -> before.propertyNames().iterator()) {
            List<String> old = new ArrayList<>();
            before.get(section).forEach(node -> old.add(json.writeValueAsString(node)));
            List<String> now = new ArrayList<>();
            if (after.has(section)) after.get(section).forEach(node -> now.add(json.writeValueAsString(node)));
            List<String> removed = new ArrayList<>(old);
            now.forEach(removed::remove);
            List<String> added = new ArrayList<>(now);
            old.forEach(added::remove);
            if (removed.isEmpty() && added.isEmpty()) {
                line("Golden %-20s identical (%d rows)", section, old.size());
                continue;
            }
            line("Golden %-20s DIFFERS: %d rows only before, %d only after (of %d before)", section, removed.size(), added.size(), old.size());
            removed.stream().limit(5).forEach(row -> line("  - %s", row));
            added.stream().limit(5).forEach(row -> line("  + %s", row));
            differences.add(section);
        }
        Files.writeString(OUTPUT.resolve(System.getProperty("benchmarkLabel", "run") + "-golden-diff.txt"), String.join("\n", differences));
        assertThat(differences).as("golden output sections that changed").isEmpty();
    }
}
