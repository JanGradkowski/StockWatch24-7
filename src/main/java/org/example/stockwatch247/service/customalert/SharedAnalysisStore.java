package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.BackgroundJobDispatcher;
import org.example.stockwatch247.service.ElliottAlertInputs;
import org.example.stockwatch247.service.ElliottWavePreferencesService;
import org.example.stockwatch247.service.InfrastructureRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Prepares the shared analysis store (docs/shared-analysis-store-plan.md) ahead of need. Each stock's latest
 * snapshot with factory settings (the latest Elliott count, alert-mode harmonics, recent candlesticks) is worked
 * out in the background for stocks people follow, and for stocks someone merely opened, so a new custom alert
 * reads it instead of scanning. Opening a stock never follows it: its snapshot is kept current only while someone
 * follows it, through the scheduled checks.
 *
 * <p>Work is queued in {@code analysis_requests}, best priority first, and done in time slices that pause while
 * the scheduled alert checks run. Stocks nobody follows share an hourly budget; a person can queue a limited
 * number of new stocks per hour, and the queue has a cap, so opening many stocks cannot swamp the machine.</p>
 */
@Component
public class SharedAnalysisStore {
    private static final Logger log = LoggerFactory.getLogger(SharedAnalysisStore.class);
    /** Priorities: followed stocks first; stocks several people opened; stocks opened once. */
    public static final int FOLLOWED = 0;
    public static final int POPULAR = 2;
    public static final int VIEWED = 3;
    /** Elliott history priorities (V92 coverage): previews 0, popular stocks 2, followed stocks 3. */
    static final int HISTORY_POPULAR = 2;
    static final int HISTORY_FOLLOWED = 3;
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int MINIMUM_CANDLES = 30;

    private final JdbcTemplate db;
    private final CustomAlertFactsLoader facts;
    private final StockAssetRepository assets;
    private final ElliottHistoryStore histories;
    private final boolean enabled;
    private final boolean lookupPrecompute;
    private final boolean followedSnapshots;
    private final boolean followedHistories;
    private final int lookupsPerUserHour;
    private final int maxPending;
    private final long viewedBudgetNanos;
    private final int popularViewers;
    private final long sliceMillis;
    private final String owner = "analysis-store-" + UUID.randomUUID();
    private BackgroundJobDispatcher dispatcher;
    private long budgetHour = -1;
    private long budgetUsedNanos;

    public SharedAnalysisStore(JdbcTemplate db, CustomAlertFactsLoader facts, StockAssetRepository assets, ElliottHistoryStore histories,
                               @Value("${analysis-store.enabled:${custom-alerts.background-enabled:${alerts.schedule.enabled:true}}}") boolean enabled,
                               @Value("${analysis-store.lookup-precompute:true}") boolean lookupPrecompute,
                               @Value("${analysis-store.followed-snapshots:true}") boolean followedSnapshots,
                               @Value("${analysis-store.followed-histories:true}") boolean followedHistories,
                               @Value("${analysis-store.lookups-per-user-hour:30}") int lookupsPerUserHour,
                               @Value("${analysis-store.max-pending:500}") int maxPending,
                               @Value("${analysis-store.viewed-budget-seconds-per-hour:300}") long viewedBudgetSeconds,
                               @Value("${analysis-store.popular-viewers:2}") int popularViewers,
                               @Value("${analysis-store.slice-ms:20000}") long sliceMillis) {
        this.db = db;
        this.facts = facts;
        this.assets = assets;
        this.histories = histories;
        this.enabled = enabled;
        this.lookupPrecompute = lookupPrecompute;
        this.followedSnapshots = followedSnapshots;
        this.followedHistories = followedHistories;
        this.lookupsPerUserHour = Math.max(0, lookupsPerUserHour);
        this.maxPending = Math.max(1, maxPending);
        this.viewedBudgetNanos = Math.max(0, viewedBudgetSeconds) * 1_000_000_000L;
        this.popularViewers = Math.max(1, popularViewers);
        this.sliceMillis = Math.max(1000, sliceMillis);
    }

    @Autowired(required = false)
    void configureDispatcher(BackgroundJobDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    // ------------------------------------------------------------------ requests

    /** Asks for a stock's latest snapshot; a pending request keeps the better of the two priorities. */
    public void request(long stockAssetId, TimeInterval interval, int priority) {
        if (interval != TimeInterval.DAILY && interval != TimeInterval.WEEKLY && interval != TimeInterval.MONTHLY) return;
        db.update("""
                insert into analysis_requests (stock_asset_id, interval, priority) values (?, ?, ?)
                on conflict (stock_asset_id, interval) do update set priority = least(analysis_requests.priority, excluded.priority)
                """, stockAssetId, interval.name(), priority);
        if (priority != FOLLOWED) trim();
    }

    /**
     * Keeps the queue of stocks nobody follows within its cap: the lowest-priority, oldest requests go first.
     * Followed stocks are never dropped (their number is bounded by the follow limits).
     */
    private void trim() {
        db.update("""
                delete from analysis_requests r using (
                    select stock_asset_id, interval from analysis_requests where priority <> ? and lease_owner is null
                    order by priority, requested_at desc offset ?) dropped
                where r.stock_asset_id = dropped.stock_asset_id and r.interval = dropped.interval
                """, FOLLOWED, maxPending);
    }

    /**
     * Called by every scheduled check of a followed stock: its snapshot is brought up to the new candle, a stored
     * Elliott history is extended to it, and (when enabled) a history is built for it if it has none.
     */
    public void followed(StockAsset asset, TimeInterval interval) {
        if (!enabled || asset == null) return;
        if (followedSnapshots) request(asset.getId(), interval, FOLLOWED);
        histories.extend(asset.getId(), interval);
        if (followedHistories) requestFactoryHistory(asset.getId(), interval, HISTORY_FOLLOWED);
    }

    /**
     * A signed-in person opened a stock page. When its candles are stored, its snapshot is queued (the page already
     * refreshed the daily candles; nothing else is fetched). Each person can queue a limited number of new stocks per
     * hour. A stock several people opened this week also gets an Elliott history.
     */
    public void lookup(User user, StockAsset asset) {
        if (!enabled || !lookupPrecompute || user == null || user.getId() == null || asset == null) return;
        try {
            Integer stored = db.queryForObject("""
                    select count(*) from (select 1 from candles where symbol = ? and time_interval = '1d' limit ?) recent
                    """, Integer.class, asset.getTickerSymbol(), MINIMUM_CANDLES);
            if (stored == null || stored < MINIMUM_CANDLES) return;
            db.update("insert into analysis_lookups (user_id, stock_asset_id) values (?, ?) on conflict do nothing",
                    user.getId(), asset.getId());
            Integer recent = db.queryForObject("""
                    select count(*) from analysis_lookups where user_id = ? and queued_at > current_timestamp - interval '1 hour'
                    """, Integer.class, user.getId());
            if (recent != null && recent >= lookupsPerUserHour) return;
            // Once per person, stock and day.
            int queued = db.update("""
                    update analysis_lookups set queued_at = current_timestamp
                    where user_id = ? and stock_asset_id = ? and viewed_on = current_date and queued_at is null
                    """, user.getId(), asset.getId());
            if (queued == 0) return;
            Integer viewers = db.queryForObject("""
                    select count(distinct user_id) from analysis_lookups where stock_asset_id = ? and viewed_on > current_date - 7
                    """, Integer.class, asset.getId());
            boolean popular = viewers != null && viewers >= popularViewers;
            request(asset.getId(), TimeInterval.DAILY, popular ? POPULAR : VIEWED);
            if (popular) requestFactoryHistory(asset.getId(), TimeInterval.DAILY, HISTORY_POPULAR);
        } catch (RuntimeException failure) {
            if (InfrastructureRecovery.isDatabaseUnavailable(failure)) throw failure;
            // Opening a stock page never fails because of the store.
            log.warn("Shared analysis lookup not recorded for {}: {}", asset.getTickerSymbol(), failure.toString());
        }
    }

    /** The factory-settings Elliott history of a stock (asking for it counts as use, so a followed one never ages out). */
    private void requestFactoryHistory(long stockAssetId, TimeInterval interval, int priority) {
        String child = ElliottAlertInputs.childApiInterval(interval);
        if (child == null) return;
        String key = ElliottHistoryStore.settingsKey(ElliottWavePreferencesService.factoryPreferences().profile(interval).rules(), child);
        histories.request(stockAssetId, interval, key, null, priority);
    }

    // ------------------------------------------------------------------ background work

    @Scheduled(fixedDelayString = "${analysis-store.delay-ms:10000}", initialDelayString = "${analysis-store.initial-delay-ms:60000}")
    public void dispatch() {
        if (enabled && dispatcher != null) dispatcher.submit("analysis-store", this::work);
    }

    @Scheduled(cron = "${analysis-store.cleanup-cron:0 50 3 * * *}")
    public void cleanup() {
        if (!enabled) return;
        db.update("delete from analysis_lookups where viewed_on < current_date - 7");
        db.update("delete from analysis_requests where attempts >= 3 and requested_at < current_timestamp - interval '1 day'");
    }

    /**
     * One time slice of queued snapshots, best priority first. Pauses while scheduled alert checks are waiting or
     * running; stocks nobody follows wait once the hour's budget is spent. Returns how many were handled.
     */
    public int work() {
        if (checksRunning()) return 0;
        long deadline = System.nanoTime() + sliceMillis * 1_000_000L;
        int handled = 0;
        while (System.nanoTime() < deadline) {
            Optional<Request> next = claimNext(budgetLeft());
            if (next.isEmpty()) break;
            Request request = next.get();
            long started = System.nanoTime();
            try {
                snapshot(request);
                db.update("delete from analysis_requests where stock_asset_id = ? and interval = ? and lease_owner = ?",
                        request.stockAssetId(), request.interval().name(), owner);
            } catch (RuntimeException failure) {
                if (InfrastructureRecovery.isDatabaseUnavailable(failure)) throw failure;
                log.warn("Shared analysis snapshot failed for stock {} {}", request.stockAssetId(), request.interval(), failure);
                db.update("""
                        update analysis_requests set lease_owner = null, lease_until = null, last_error = ?
                        where stock_asset_id = ? and interval = ? and lease_owner = ?
                        """, failure.getClass().getSimpleName(), request.stockAssetId(), request.interval().name(), owner);
            }
            if (request.priority() != FOLLOWED) spend(System.nanoTime() - started);
            handled++;
        }
        return handled;
    }

    /** Works out a stock's factory-settings snapshot through the facts loader, which files it in the shared store. */
    void snapshot(Request request) {
        StockAsset asset = assets.findById(request.stockAssetId()).orElse(null);
        if (asset == null) return;
        CustomAlertFactsLoader.StockData data = facts.load(asset, request.interval(), null);
        if (data.candles().size() < MINIMUM_CANDLES) return;
        data.evaluatesLatestOnly(true);
        data.warmSharedStore();
    }

    private boolean checksRunning() {
        return histories.checksRunning();
    }

    record Request(long stockAssetId, TimeInterval interval, int priority) { }

    /** Claims the best waiting request (or one whose worker stopped); only followed stocks once the budget is spent. */
    private Optional<Request> claimNext(boolean budgetLeft) {
        return db.query("""
                update analysis_requests r set lease_owner = ?, lease_until = ?, attempts = r.attempts + 1
                from (select stock_asset_id, interval from analysis_requests
                      where (lease_owner is null or lease_until < current_timestamp) and attempts < 3 and (? or priority = ?)
                      order by priority, requested_at limit 1 for update skip locked) next
                where r.stock_asset_id = next.stock_asset_id and r.interval = next.interval
                returning r.stock_asset_id, r.interval, r.priority
                """, (row, index) -> new Request(row.getLong(1), TimeInterval.valueOf(row.getString(2)), row.getInt(3)),
                owner, Timestamp.from(Instant.now().plus(LEASE)), budgetLeft, FOLLOWED).stream().findFirst();
    }

    private synchronized boolean budgetLeft() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        if (hour != budgetHour) {
            budgetHour = hour;
            budgetUsedNanos = 0;
        }
        return budgetUsedNanos < viewedBudgetNanos;
    }

    private synchronized void spend(long nanos) {
        budgetUsedNanos += nanos;
    }
}
