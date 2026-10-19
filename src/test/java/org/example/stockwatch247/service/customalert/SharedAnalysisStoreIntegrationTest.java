package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.CandleCompletionService;
import org.example.stockwatch247.service.HistoricalSignalCacheService;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.PatternHit;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The shared analysis store (docs/shared-analysis-store-plan.md) on real cached candles copied into this test's
 * schema: stored snapshots equal direct scans and are shared by everyone on factory settings; only completed candles
 * invalidate them; opened stocks are queued within their limits; the background work pauses for scheduled checks and
 * keeps its budget; and reading only the latest Elliott count gives the same answers as reading two.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false", "custom-alerts.background-enabled=false",
        "insider-activity.schedule-enabled=false"})
@Transactional
class SharedAnalysisStoreIntegrationTest {
    private static final String REAL = "NVDA";
    private static final List<String> STORED_FAMILIES = List.of("ELLIOTT_LATEST", "HARMONIC_ALERTS", "CANDLESTICK_RECENT");

    @Autowired private CustomAlertFactsLoader facts;
    @Autowired private HistoricalSignalCacheService cache;
    @Autowired private SharedAnalysisStore shared;
    @Autowired private ElliottHistoryStore histories;
    @Autowired private CustomAlertEngine engine;
    @Autowired private CustomAlertService alerts;
    @Autowired private CandleCompletionService completion;
    @Autowired private ChartIndicatorCatalog catalog;
    @Autowired private StockAssetRepository assets;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private User user() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long id = jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Shared', 'Store', true) returning id
                """, Long.class, "shared-store-" + suffix + "@example.com");
        return users.findById(id).orElseThrow();
    }

    /** Copies a stock and its daily and 60-minute candles from the local database's public schema. */
    private StockAsset copy(String symbol) {
        Integer stored = jdbc.queryForObject("select count(*) from public.candles where symbol = ? and time_interval = '1d'", Integer.class, symbol);
        assumeThat(stored).as("needs " + symbol + "'s cached candles in the local database").isGreaterThan(300);
        jdbc.update("""
                insert into stock_assets (ticker_symbol, company_name, exchange, currency, instrument_type)
                select ticker_symbol, company_name, exchange, currency, instrument_type from public.stock_assets p
                where p.ticker_symbol = ? and not exists (select 1 from stock_assets x where x.ticker_symbol = p.ticker_symbol)
                """, symbol);
        jdbc.update("""
                insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                select symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at
                from public.candles where symbol = ? and time_interval in ('1d', '60min')
                """, symbol);
        return assets.findByTickerSymbolIgnoreCase(symbol).orElseThrow();
    }

    /** A stock with {@code count} weekday candles on a gentle rise. */
    private StockAsset synthetic(int count) {
        String symbol = "ZSA" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        jdbc.update("insert into stock_assets (ticker_symbol, company_name, exchange, currency, instrument_type) values (?, ?, 'TEST', 'USD', 'EQUITY')",
                symbol, symbol + " Inc.");
        LocalDate day = LocalDate.of(2025, 1, 6);
        for (int index = 0; index < count; index++) {
            while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.plusDays(1);
            double close = 100 + index * 0.25;
            jdbc.update("""
                    insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                    values (?, '1d', ?, ?, ?, ?, ?, 1000000, current_timestamp)
                    """, symbol, day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), close - 0.1, close + 0.2, close - 0.2, close);
            day = day.plusDays(1);
        }
        return assets.findByTickerSymbolIgnoreCase(symbol).orElseThrow();
    }

    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    /** A fresh read of the stock's candles, as a new check makes (this test is one transaction with one session). */
    private CustomAlertFactsLoader.StockData load(StockAsset asset) {
        entityManager.flush();
        entityManager.clear();
        CustomAlertFactsLoader.StockData data = facts.load(asset, TimeInterval.DAILY, null);
        data.evaluatesLatestOnly(true);
        return data;
    }

    private Map<String, List<PatternHit>> hits(CustomAlertFactsLoader.StockData data, User owner) {
        Map<String, List<PatternHit>> found = new LinkedHashMap<>();
        for (String family : CustomAlertDefinition.FAMILIES) found.put(family, data.hits(family, owner));
        return found;
    }

    private int storedRows(String symbol) {
        return jdbc.queryForObject("select count(*) from historical_signal_cache where symbol = ? and signal_family = any(?)",
                Integer.class, symbol, STORED_FAMILIES.toArray(String[]::new));
    }

    /** Replaces every stored snapshot of the stock with one marker hit: a read that returns it came from the store. */
    private void markStored(String symbol) {
        jdbc.update("""
                update historical_signal_cache set payload = cast(? as jsonb) where symbol = ? and signal_family = any(?)
                """, "{\"hits\":[{\"family\":\"MARKER\",\"code\":\"MARKER\",\"type\":\"MARKER\",\"bullish\":true,\"score\":1,"
                + "\"availableAt\":1,\"anchorAt\":1,\"knownAt\":1}]}", symbol, STORED_FAMILIES.toArray(String[]::new));
    }

    private static boolean marked(Map<String, List<PatternHit>> hits) {
        return hits.values().stream().allMatch(list -> list.size() == 1 && "MARKER".equals(list.getFirst().code()));
    }

    @Test
    void storedSnapshotsEqualDirectScansAndEveryoneOnFactorySettingsSharesThem() {
        StockAsset asset = copy(REAL);
        User defaultUser = user();
        facts.configureStore(null);
        Map<String, List<PatternHit>> direct;
        try {
            direct = hits(load(asset), null);
        } finally {
            facts.configureStore(cache);
        }
        assertThat(direct.values().stream().mapToInt(List::size).sum()).as("the real stock shows some patterns").isPositive();

        assertThat(hits(load(asset), null)).as("computed and stored").isEqualTo(direct);
        assertThat(storedRows(REAL)).isEqualTo(3);
        assertThat(hits(load(asset), defaultUser)).as("read back, harmonic formations included, under a default user's settings")
                .isEqualTo(direct);
        assertThat(storedRows(REAL)).as("a default user shares the factory entries").isEqualTo(3);

        markStored(REAL);
        assertThat(marked(hits(load(asset), defaultUser))).as("served from the store, not scanned again").isTrue();
    }

    @Test
    void onlyCompletedCandlesInvalidateASnapshot() {
        StockAsset asset = copy(REAL);
        hits(load(asset), null);
        markStored(REAL);
        // Today's forming candle, then an intraday update of it: not an input of any snapshot.
        long forming = completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY);
        jdbc.update("""
                insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                values (?, '1d', ?, 100, 101, 99, 100, 10, current_timestamp)
                on conflict do nothing
                """, REAL, forming);
        jdbc.update("update candles set close_price = 100.5 where symbol = ? and time_interval = '1d' and timestamp = ?", REAL, forming);
        assertThat(marked(hits(load(asset), null))).as("the forming candle changes nothing").isTrue();

        // A provider corrects the latest completed candle: the snapshot is computed again.
        jdbc.update("""
                update candles set close_price = close_price * 1.001 where symbol = ? and time_interval = '1d'
                  and timestamp = (select max(timestamp) from candles where symbol = ? and time_interval = '1d' and timestamp < ?)
                """, REAL, REAL, forming);
        assertThat(marked(hits(load(asset), null))).as("a corrected completed candle invalidates it").isFalse();
    }

    @Test
    void theChartCacheSurvivesIntradayUpdatesOfTheFormingCandle() {
        copy(REAL);
        AtomicInteger runs = new AtomicInteger();
        record Value(int run) { }
        java.util.function.Supplier<Value> compute = () -> new Value(runs.incrementAndGet());
        assertThat(cache.getOrCompute(HistoricalSignalCacheService.Family.CANDLESTICK, REAL, "1d", "store-test", "factory", Value.class, compute).run())
                .isEqualTo(1);
        long forming = completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY);
        jdbc.update("""
                insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                values (?, '1d', ?, 100, 101, 99, 100, 10, current_timestamp) on conflict do nothing
                """, REAL, forming);
        jdbc.update("update candles set close_price = 101 where symbol = ? and time_interval = '1d' and timestamp = ?", REAL, forming);
        assertThat(cache.getOrCompute(HistoricalSignalCacheService.Family.CANDLESTICK, REAL, "1d", "store-test", "factory", Value.class, compute).run())
                .as("reused through intraday syncs").isEqualTo(1);
        jdbc.update("""
                update candles set volume = volume + 1 where symbol = ? and time_interval = '1d'
                  and timestamp = (select min(timestamp) from candles where symbol = ? and time_interval = '1d')
                """, REAL, REAL);
        assertThat(cache.getOrCompute(HistoricalSignalCacheService.Family.CANDLESTICK, REAL, "1d", "store-test", "factory", Value.class, compute).run())
                .as("recomputed after a completed candle changed").isEqualTo(2);
    }

    private SharedAnalysisStore limited(int perUserHour, int maxPending, long viewedBudgetSeconds) {
        return new SharedAnalysisStore(jdbc, facts, assets, histories, true, true, true, true, perUserHour, maxPending, viewedBudgetSeconds, 2, 20_000);
    }

    private Map<Long, Integer> requests() {
        Map<Long, Integer> found = new LinkedHashMap<>();
        jdbc.query("select stock_asset_id, priority from analysis_requests order by stock_asset_id",
                row -> { found.put(row.getLong(1), row.getInt(2)); });
        return found;
    }

    @Test
    void openedStocksAreQueuedWithinTheirLimitsAndByPriority() {
        jdbc.update("delete from analysis_requests");
        SharedAnalysisStore store = limited(3, 4, 300);
        List<StockAsset> stocks = java.util.stream.IntStream.range(0, 6).mapToObj(index -> synthetic(40)).toList();
        StockAsset tooShort = synthetic(10);
        User first = user(), second = user(), third = user();

        stocks.subList(0, 5).forEach(stock -> store.lookup(first, stock));
        store.lookup(first, tooShort);
        assertThat(requests()).as("three new stocks per person per hour").hasSize(3)
                .allSatisfy((stock, priority) -> assertThat(priority).isEqualTo(SharedAnalysisStore.VIEWED));
        store.lookup(first, stocks.getFirst());
        assertThat(requests()).as("once per person, stock and day").hasSize(3);

        // A second person opens the first stock: it is now popular, and its Elliott history is asked for.
        store.lookup(second, stocks.getFirst());
        assertThat(requests()).containsEntry(stocks.getFirst().getId(), SharedAnalysisStore.POPULAR);
        assertThat(jdbc.queryForObject("select priority from custom_alert_elliott_coverage where stock_asset_id = ?", Integer.class,
                stocks.getFirst().getId())).isEqualTo(SharedAnalysisStore.HISTORY_POPULAR);

        // A followed stock is never dropped; past the cap the lowest-priority, oldest requests go.
        store.request(stocks.get(5).getId(), TimeInterval.DAILY, SharedAnalysisStore.FOLLOWED);
        stocks.subList(3, 5).forEach(stock -> store.lookup(second, stock));
        store.lookup(third, stocks.get(1));
        Map<Long, Integer> queued = requests();
        assertThat(queued).containsEntry(stocks.get(5).getId(), SharedAnalysisStore.FOLLOWED);
        assertThat(queued.values().stream().filter(priority -> priority != SharedAnalysisStore.FOLLOWED)).hasSize(4);
        assertThat(queued).containsEntry(stocks.getFirst().getId(), SharedAnalysisStore.POPULAR)
                .containsEntry(stocks.get(1).getId(), SharedAnalysisStore.POPULAR);
        assertThat(jdbc.queryForObject("select count(*) from analysis_lookups where stock_asset_id = ?", Integer.class, tooShort.getId()))
                .as("a stock without stored candles is not recorded").isZero();
    }

    @Test
    void theBackgroundWorkPausesForScheduledChecksAndKeepsItsBudget() {
        jdbc.update("delete from analysis_requests");
        StockAsset followed = synthetic(60), viewed = synthetic(60);
        SharedAnalysisStore noBudget = limited(30, 500, 0);
        noBudget.request(followed.getId(), TimeInterval.DAILY, SharedAnalysisStore.FOLLOWED);
        noBudget.request(viewed.getId(), TimeInterval.DAILY, SharedAnalysisStore.VIEWED);

        jdbc.update("insert into alert_check_jobs (symbol, interval, status, scheduled_for) values ('ZZWAIT', 'DAILY', 'PENDING', current_timestamp)");
        assertThat(noBudget.work()).as("paused while a scheduled check waits").isZero();
        // The Elliott history builder likewise leaves followed stocks' histories for later, but not one a preview waits for.
        histories.request(followed.getId(), TimeInterval.DAILY, "followed-history", null, SharedAnalysisStore.HISTORY_FOLLOWED);
        var lease = java.time.Duration.ofMinutes(1);
        assertThat(histories.claimNext("test", lease, histories.checksRunning() ? 0 : Integer.MAX_VALUE)).isEmpty();
        histories.request(viewed.getId(), TimeInterval.DAILY, "previewed-history", null, 0);
        assertThat(histories.claimNext("test", lease, histories.checksRunning() ? 0 : Integer.MAX_VALUE))
                .hasValueSatisfying(claim -> assertThat(claim.settingsKey()).isEqualTo("previewed-history"));
        jdbc.update("delete from alert_check_jobs where symbol = 'ZZWAIT'");
        assertThat(histories.claimNext("test", lease, histories.checksRunning() ? 0 : Integer.MAX_VALUE))
                .hasValueSatisfying(claim -> assertThat(claim.settingsKey()).isEqualTo("followed-history"));
        jdbc.update("delete from custom_alert_elliott_coverage where settings_key in ('followed-history', 'previewed-history')");

        assertThat(noBudget.work()).as("only the followed stock without a budget for the rest").isEqualTo(1);
        assertThat(requests()).containsOnlyKeys(viewed.getId());
        assertThat(storedRows(followed.getTickerSymbol())).isEqualTo(3);
        assertThat(limited(30, 500, 300).work()).isEqualTo(1);
        assertThat(requests()).isEmpty();
        assertThat(storedRows(viewed.getTickerSymbol())).isEqualTo(3);
    }

    @Test
    void readingOnlyTheLatestElliottCountGivesTheSameAnswersAsReadingTwo() {
        User owner = user();
        int matching = 0;
        facts.configureStore(null);
        try {
            for (String symbol : List.of(REAL, "ORCL", "JNJ")) {
                StockAsset asset = copy(symbol);
                List<CustomAlertDefinition> definitions = new java.util.ArrayList<>();
                for (int window : new int[]{1, 3, 5, 20}) {
                    for (String direction : List.of("ANY", "BULLISH", "BEARISH")) {
                        definitions.add(CustomAlertDefinition.parse(json.readTree("""
                                {"version":1,"match":"ALL","groups":[{"id":"g1","match":"ALL","conditions":[
                                  {"id":"c1","kind":"PATTERN","family":"ELLIOTT_WAVE","direction":"%s","window":{"mode":"ANY","candles":%d}}]}]}
                                """.formatted(direction, window)), catalog, TimeInterval.DAILY));
                    }
                }
                // One load per mode: a loaded stock reads its counts once for every rule.
                var latestOnly = facts.load(asset, TimeInterval.DAILY, null);
                latestOnly.evaluatesLatestOnly(true);
                var both = facts.load(asset, TimeInterval.DAILY, null);
                int last = both.candles().size() - 1;
                for (CustomAlertDefinition definition : definitions) {
                    var one = CustomAlertEvaluator.evaluate(definition, latestOnly.forOwner(owner), last);
                    var two = CustomAlertEvaluator.evaluate(definition, both.forOwner(owner), last);
                    assertThat(one.matched()).as(symbol + " " + definition.toJson()).isEqualTo(two.matched());
                    assertThat(one.conditions().getFirst().facts()).as(symbol + " " + definition.toJson())
                            .isEqualTo(two.conditions().getFirst().facts());
                    if (two.matched()) matching++;
                }
                // Switching to earlier candles mid-check reads the waves again with the candle that first showed them.
                latestOnly.needEarlierCandles();
                for (CustomAlertDefinition definition : definitions) {
                    for (int index = last - 5; index <= last; index++) {
                        assertThat(CustomAlertEvaluator.evaluate(definition, latestOnly.forOwner(owner), index).matched())
                                .isEqualTo(CustomAlertEvaluator.evaluate(definition, both.forOwner(owner), index).matched());
                    }
                }
            }
        } finally {
            facts.configureStore(cache);
        }
        assertThat(matching).as("some of the rules match a real stock's latest candle").isPositive();
    }

    @Test
    void oneBaselineRunHandlesMoreThanOneBatchOfStocks() {
        User owner = user();
        List<StockAsset> stocks = java.util.stream.IntStream.range(0, 25).mapToObj(index -> synthetic(40)).toList();
        long list = jdbc.queryForObject("insert into watchlists (user_id, name) values (?, 'Batch') returning id", Long.class, owner.getId());
        stocks.forEach(stock -> jdbc.update("insert into watchlist_members (watchlist_id, stock_asset_id) values (?, ?)", list, stock.getId()));
        var alert = alerts.create(owner, new SaveCommand("Above 105", "", "DAILY", json.readTree("""
                {"version":1,"match":"ALL","groups":[{"id":"g1","match":"ALL","conditions":[
                  {"id":"c1","kind":"VALUE","subject":{"kind":"PRICE","field":"CLOSE"},"operator":"ABOVE",
                   "compare":{"kind":"VALUE","value":105}}]}]}
                """), "BECOMES_TRUE", 0, 3, true, List.of(new TargetCommand(list, "ALL_MEMBERS", List.of())), false, true, null));
        assertThat(alerts.get(owner, alert.id()).checking()).isEqualTo(25);
        assertThat(engine.establishBaselines()).as("all 25 in one run, not 20 per tick").isEqualTo(25);
        assertThat(alerts.get(owner, alert.id()).checking()).isZero();
    }
}
