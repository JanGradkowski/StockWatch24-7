package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.ConflictException;
import org.example.stockwatch247.service.TrackedInstrumentGuard;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.WatchlistService;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creating, applying and reading custom alerts. Evaluation lives in {@link CustomAlertEngine}.
 *
 * <p>An alert is applied through watchlists: to all members of a list (following later membership changes)
 * or to chosen members. Applying an alert from a stock page adds the stock to the chosen lists first, as
 * built-in follows do. Saving never evaluates synchronously; the engine sets each new stock's baseline from
 * stored candles within moments, and the pages show those stocks as being checked meanwhile.</p>
 */
@Service
public class CustomAlertService {
    static final int MAX_NAME = 80;
    static final int MAX_DESCRIPTION = 500;
    private static final Set<String> TRIGGER_MODES = Set.of("BECOMES_TRUE", "ONCE", "EVERY_CANDLE");

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ChartIndicatorCatalog catalog;
    private final WatchlistService watchlists;
    private final StockAssetRepository assets;
    private final CustomAlertFactsLoader facts;
    private final int maximumActive;
    private TrackedInstrumentGuard guard;

    public CustomAlertService(JdbcTemplate db, ObjectMapper json, ChartIndicatorCatalog catalog, WatchlistService watchlists,
                              StockAssetRepository assets, CustomAlertFactsLoader facts,
                              @Value("${custom-alerts.max-active-per-user:25}") int maximumActive) {
        this.db = db;
        this.json = json;
        this.catalog = catalog;
        this.watchlists = watchlists;
        this.assets = assets;
        this.facts = facts;
        this.maximumActive = Math.max(1, maximumActive);
    }

    @Autowired(required = false)
    void configureGuard(TrackedInstrumentGuard guard) {
        this.guard = guard;
    }

    private org.springframework.context.ApplicationEventPublisher events;

    @Autowired(required = false)
    void configureEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    public int maximumActive() { return maximumActive; }

    // ------------------------------------------------------------------ views

    public record TargetCommand(Long watchlistId, String scope, List<String> symbols) { }

    public record SaveCommand(String name, String description, String interval, JsonNode definition, String triggerMode,
                              Integer quietCandles, Integer outcomeCandles, Boolean emailEnabled, List<TargetCommand> targets,
                              Boolean notifyExisting, Boolean active, Long sourceChartAlertId) { }

    public record TargetView(long watchlistId, String watchlistName, String scope, long stocks, List<String> symbols) { }

    /**
     * One alert with where it stands: {@code matching} stocks match on their latest checked candle,
     * {@code checking} wait for their first check, {@code skipped} cannot use one of the conditions.
     */
    public record AlertView(long id, String name, String description, String interval, JsonNode definition,
                            String triggerMode, int quietCandles, int outcomeCandles, boolean emailEnabled, boolean active,
                            boolean pinned, Long sourceChartAlertId, List<TargetView> targets, long stocks, long matching,
                            long checking, long skipped, long unread, long triggers, Instant lastTriggeredAt,
                            Instant createdAt, boolean activityOnly) { }

    /** A covered stock and the alert's state for it. */
    public record StockStateView(String symbol, String companyName, String instrumentType, String state, String detail,
                                 Long lastEvaluated, Long lastTriggered, boolean excluded) { }

    public record TriggerSummary(long id, long alertId, String alertName, String symbol, String companyName, TimeInterval interval,
                                 long candleTimestamp, int matchedConditions, int totalConditions, String kind,
                                 Instant createdAt, boolean read, Double outcomeReturn) { }

    public record TriggerView(long id, long alertId, String alertName, boolean alertActive, boolean ruleChanged, String symbol,
                              String companyName, TimeInterval interval, long candleTimestamp, double closePrice, String kind,
                              Instant createdAt, Instant readAt, int matchedConditions, int totalConditions, JsonNode evidence,
                              JsonNode forwardReturns, Double outcomeReturn, int outcomeCandles, String outcomeStatus,
                              String ruleText, String origins) { }

    /** How the alert's past triggers moved afterwards; informational only. */
    public record Performance(long triggers, long completed, long positive, Double medianReturn, Double averageReturn) {
        public Double positiveShare() { return completed == 0 ? null : Math.round(positive * 1000.0 / completed) / 10.0; }
    }

    public record ArchiveHeader(AlertView alert, Performance performance, String watchlistName) { }

    /** A watchlist's view of one alert applied to it. */
    public record WatchlistAlertView(long id, String name, String interval, boolean active, String scope, long stocks,
                                     long matching, long unread, JsonNode definition) { }

    /** A stock page's view of one alert: whether it covers the stock, and through which lists. */
    public record StockAlertView(long id, String name, String interval, boolean active, boolean applied, String state,
                                 String detail, List<Long> watchlistIds, JsonNode definition) { }

    private static final String ALERT_COLUMNS = """
            a.id, a.name, a.description, a.interval, a.definition, a.trigger_mode, a.quiet_candles, a.outcome_candles,
            a.email_enabled, a.active, a.pinned, a.source_chart_alert_id, a.last_triggered_at, a.created_at,
            (select count(*) from custom_alert_effective_targets t where t.alert_id = a.id) stocks,
            (select count(*) from custom_alert_states s join custom_alert_effective_targets t
                on t.alert_id = s.alert_id and t.stock_asset_id = s.stock_asset_id
             where s.alert_id = a.id and s.revision = a.revision and s.last_evaluated_timestamp is not null and s.last_result) matching,
            (select count(*) from custom_alert_effective_targets t left join custom_alert_states s
                on s.alert_id = t.alert_id and s.stock_asset_id = t.stock_asset_id
             where t.alert_id = a.id and (s.alert_id is null or s.revision <> a.revision or s.last_evaluated_timestamp is null)
               and coalesce(s.status, 'OK') in ('OK', 'FAILED')) checking,
            (select count(*) from custom_alert_states s join custom_alert_effective_targets t
                on t.alert_id = s.alert_id and t.stock_asset_id = s.stock_asset_id
             where s.alert_id = a.id and s.status in ('SKIPPED', 'INSUFFICIENT_HISTORY')) skipped,
            (select count(*) from custom_alert_events e where e.alert_id = a.id and e.deleted_at is null and e.read_at is null) unread,
            (select count(*) from custom_alert_events e where e.alert_id = a.id and e.deleted_at is null) triggers
            """;

    @Transactional(readOnly = true)
    public List<AlertView> list(User user) {
        Map<Long, List<TargetView>> targets = targets(user.getId(), null);
        return db.query("select " + ALERT_COLUMNS + " from custom_alerts a where a.user_id = ? "
                        + "order by a.pinned desc, a.active desc, coalesce(a.last_triggered_at, a.created_at) desc, a.id desc",
                (row, index) -> alertView(row, targets), user.getId());
    }

    @Transactional(readOnly = true)
    public AlertView get(User user, long id) {
        Map<Long, List<TargetView>> targets = targets(user.getId(), id);
        List<AlertView> found = db.query("select " + ALERT_COLUMNS + " from custom_alerts a where a.id = ? and a.user_id = ?",
                (row, index) -> alertView(row, targets), id, user.getId());
        if (found.isEmpty()) throw new UserInputException("The custom alert was not found.");
        return found.getFirst();
    }

    private AlertView alertView(ResultSet row, Map<Long, List<TargetView>> targets) throws SQLException {
        long id = row.getLong("id");
        JsonNode definition = json.readTree(row.getString("definition"));
        boolean activityOnly = false;
        try {
            activityOnly = CustomAlertDefinition.parse(definition, catalog, TimeInterval.valueOf(row.getString("interval"))).activityOnly();
        } catch (RuntimeException unreadable) {
            // Shown as stored; the engine reports an unreadable rule on the alert's stocks.
        }
        return new AlertView(id, row.getString("name"), row.getString("description"), row.getString("interval"), definition,
                row.getString("trigger_mode"), row.getInt("quiet_candles"), row.getInt("outcome_candles"),
                row.getBoolean("email_enabled"), row.getBoolean("active"), row.getBoolean("pinned"),
                (Long) row.getObject("source_chart_alert_id"), targets.getOrDefault(id, List.of()), row.getLong("stocks"),
                row.getLong("matching"), row.getLong("checking"), row.getLong("skipped"), row.getLong("unread"),
                row.getLong("triggers"), instant(row.getTimestamp("last_triggered_at")), instant(row.getTimestamp("created_at")),
                activityOnly);
    }

    private Map<Long, List<TargetView>> targets(long userId, Long alertId) {
        Map<Long, List<String>> symbols = new HashMap<>();
        db.query("""
                select tm.target_id, s.ticker_symbol from custom_alert_target_members tm
                join custom_alert_targets t on t.id = tm.target_id join custom_alerts a on a.id = t.alert_id
                join stock_assets s on s.id = tm.stock_asset_id
                where a.user_id = ? and (cast(? as bigint) is null or a.id = ?) order by s.ticker_symbol
                """, row -> {
            symbols.computeIfAbsent(row.getLong(1), key -> new ArrayList<>()).add(row.getString(2));
        }, userId, alertId, alertId);
        Map<Long, List<TargetView>> result = new LinkedHashMap<>();
        db.query("""
                select t.id, t.alert_id, t.watchlist_id, w.name, t.scope,
                       case when t.scope = 'ALL_MEMBERS' then (select count(*) from watchlist_members m where m.watchlist_id = t.watchlist_id)
                            else (select count(*) from custom_alert_target_members tm where tm.target_id = t.id) end stocks
                from custom_alert_targets t join custom_alerts a on a.id = t.alert_id join watchlists w on w.id = t.watchlist_id
                where a.user_id = ? and (cast(? as bigint) is null or a.id = ?) order by w.name, t.id
                """, row -> {
            result.computeIfAbsent(row.getLong("alert_id"), key -> new ArrayList<>()).add(new TargetView(row.getLong("watchlist_id"),
                    row.getString("name"), row.getString("scope"), row.getLong("stocks"),
                    List.copyOf(symbols.getOrDefault(row.getLong("id"), List.of()))));
        }, userId, alertId, alertId);
        return result;
    }

    /** Every stock the alert covers (and those stopped for it), with its state; matching stocks first. */
    @Transactional(readOnly = true)
    public List<StockStateView> states(User user, long id) {
        requireOwned(user, id);
        return db.query("""
                select s.ticker_symbol, s.company_name, s.instrument_type, st.revision, a.revision alert_revision,
                       st.last_evaluated_timestamp, st.last_result, st.status, st.status_detail, st.last_triggered_timestamp,
                       false excluded
                from custom_alert_effective_targets t join custom_alerts a on a.id = t.alert_id
                join stock_assets s on s.id = t.stock_asset_id
                left join custom_alert_states st on st.alert_id = t.alert_id and st.stock_asset_id = t.stock_asset_id
                where t.alert_id = ?
                union all
                select s.ticker_symbol, s.company_name, s.instrument_type, null, null, null, null, null, null, null, true
                from custom_alert_exclusions x join stock_assets s on s.id = x.stock_asset_id where x.alert_id = ?
                """, (row, index) -> {
            boolean excluded = row.getBoolean("excluded");
            String state;
            String detail = row.getString("status_detail");
            Integer revision = (Integer) row.getObject("revision");
            Long evaluated = (Long) row.getObject("last_evaluated_timestamp");
            String status = row.getString("status");
            if (excluded) state = "STOPPED";
            else if ("SKIPPED".equals(status)) state = "SKIPPED";
            else if ("INSUFFICIENT_HISTORY".equals(status)) state = "WAITING";
            else if (revision == null || !revision.equals(row.getObject("alert_revision")) || evaluated == null) state = "CHECKING";
            else state = Boolean.TRUE.equals(row.getObject("last_result")) ? "MATCHING" : "NOT_MATCHING";
            return new StockStateView(row.getString("ticker_symbol"), row.getString("company_name"),
                    row.getString("instrument_type"), state, detail, evaluated, (Long) row.getObject("last_triggered_timestamp"), excluded);
        }, id, id).stream().sorted(java.util.Comparator.comparingInt((StockStateView view) -> switch (view.state()) {
            case "MATCHING" -> 0;
            case "NOT_MATCHING" -> 1;
            case "CHECKING" -> 2;
            case "WAITING" -> 3;
            case "SKIPPED" -> 4;
            default -> 5;
        }).thenComparing(StockStateView::symbol)).toList();
    }

    // ------------------------------------------------------------------ saving

    @Transactional
    public AlertView create(User user, SaveCommand command) {
        if (command == null) throw new UserInputException("Add at least one condition.");
        watchlists.lock(user);
        TimeInterval interval = interval(command.interval());
        CustomAlertDefinition definition = CustomAlertDefinition.parse(command.definition(), catalog, interval);
        boolean active = command.active() == null || command.active();
        if (active) requireRoomForActive(user, null);
        Long sourceChartAlert = command.sourceChartAlertId();
        if (sourceChartAlert != null && !Boolean.TRUE.equals(db.queryForObject(
                "select exists(select 1 from chart_alerts where id = ? and user_id = ?)", Boolean.class, sourceChartAlert, user.getId()))) {
            sourceChartAlert = null;
        }
        Long id = db.queryForObject("""
                insert into custom_alerts (user_id, name, description, interval, definition, trigger_mode, quiet_candles,
                                           outcome_candles, email_enabled, active, source_chart_alert_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, user.getId(), name(command.name()), description(command.description()), interval.name(),
                json.writeValueAsString(definition.toJson()), triggerMode(command.triggerMode()), quiet(command.quietCandles()),
                outcome(command.outcomeCandles()), command.emailEnabled() == null || command.emailEnabled(), active, sourceChartAlert);
        applyTargets(user, id, command.targets() == null ? List.of() : command.targets(), Boolean.TRUE.equals(command.notifyExisting()));
        // The chart alert it grew from is replaced, so the same move is not reported twice.
        if (sourceChartAlert != null) {
            db.update("update chart_alerts set active = false, updated_at = current_timestamp where id = ? and user_id = ?",
                    sourceChartAlert, user.getId());
        }
        return get(user, id);
    }

    @Transactional
    public AlertView update(User user, long id, SaveCommand command) {
        if (command == null) throw new UserInputException("Add at least one condition.");
        watchlists.lock(user);
        Map<String, Object> existing = owned(user, id);
        TimeInterval interval = interval(command.interval());
        CustomAlertDefinition definition = CustomAlertDefinition.parse(command.definition(), catalog, interval);
        String stored = json.writeValueAsString(definition.toJson());
        String mode = triggerMode(command.triggerMode());
        boolean ruleChanged = !stored.equals(existing.get("definition")) || !interval.name().equals(existing.get("interval"))
                || !mode.equals(existing.get("trigger_mode"));
        boolean notify = Boolean.TRUE.equals(command.notifyExisting());
        db.update("""
                update custom_alerts set name = ?, description = ?, interval = ?, definition = ?, trigger_mode = ?,
                       quiet_candles = ?, outcome_candles = ?, email_enabled = ?, revision = revision + ?,
                       updated_at = current_timestamp where id = ?
                """, name(command.name()), description(command.description()), interval.name(), stored, mode,
                quiet(command.quietCandles()), outcome(command.outcomeCandles()),
                command.emailEnabled() == null || command.emailEnabled(), ruleChanged ? 1 : 0, id);
        if (ruleChanged) {
            // A changed rule starts again from the next candle for every stock; history keeps the old rule text.
            db.update("""
                    update custom_alert_states s set revision = a.revision, last_evaluated_timestamp = null, last_result = null,
                           status = 'OK', status_detail = null, triggered_once = false, notify_on_baseline = ?,
                           updated_at = current_timestamp
                    from custom_alerts a where a.id = s.alert_id and s.alert_id = ?
                    """, notify, id);
        }
        if (command.targets() != null) applyTargets(user, id, command.targets(), notify);
        if (command.active() != null && command.active() != Boolean.TRUE.equals(existing.get("active"))) setActive(user, id, command.active());
        return get(user, id);
    }

    /**
     * Replaces the alert's lists and chosen members. Lists must belong to the user and allow custom alerts;
     * chosen stocks must already be members. Newly covered stocks must fit the monitoring capacity.
     */
    private void applyTargets(User user, long id, List<TargetCommand> commands, boolean notifyExisting) {
        Set<Long> before = new LinkedHashSet<>(db.queryForList(
                "select stock_asset_id from custom_alert_effective_targets where alert_id = ?", Long.class, id));
        Set<Long> lists = new LinkedHashSet<>();
        for (TargetCommand command : commands) {
            if (command == null || command.watchlistId() == null) throw new UserInputException("Choose valid watchlists.");
            if (!lists.add(command.watchlistId())) continue;
            watchlists.requireOwned(user, command.watchlistId());
            String listName = watchlists.name(user, command.watchlistId());
            if (Boolean.TRUE.equals(db.queryForObject("""
                    select exists(select 1 from watchlist_disallowed_signals where watchlist_id = ? and signal_type = 'CUSTOM')
                    """, Boolean.class, command.watchlistId()))) {
                throw new UserInputException("The watchlist " + listName
                        + " does not allow custom alerts. Allow them in its settings or choose another list.");
            }
            String scope = command.scope() == null ? "ALL_MEMBERS" : command.scope().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ALL_MEMBERS", "SELECTED").contains(scope)) throw new UserInputException("Choose all members or chosen members.");
            Long target = db.queryForObject("""
                    insert into custom_alert_targets (alert_id, watchlist_id, scope) values (?, ?, ?)
                    on conflict (alert_id, watchlist_id) do update set scope = excluded.scope returning id
                    """, Long.class, id, command.watchlistId(), scope);
            db.update("delete from custom_alert_target_members where target_id = ?", target);
            if ("SELECTED".equals(scope)) {
                List<String> symbols = command.symbols() == null ? List.of() : command.symbols();
                if (symbols.isEmpty()) throw new UserInputException("Choose at least one stock from the watchlist " + listName + ".");
                if (symbols.size() > 2000) throw new UserInputException("Choose at most 2000 stocks per watchlist.");
                for (String raw : new LinkedHashSet<>(symbols)) {
                    String symbol = SecurityInputValidator.requireMarketSymbol(raw);
                    int added = db.update("""
                            insert into custom_alert_target_members (target_id, watchlist_id, stock_asset_id)
                            select ?, m.watchlist_id, m.stock_asset_id from watchlist_members m join stock_assets s on s.id = m.stock_asset_id
                            where m.watchlist_id = ? and upper(s.ticker_symbol) = ? on conflict do nothing
                            """, target, command.watchlistId(), symbol);
                    if (added == 0) throw new UserInputException(symbol + " is not in the watchlist " + listName + ".");
                }
            }
        }
        if (lists.isEmpty()) {
            db.update("delete from custom_alert_targets where alert_id = ?", id);
        } else {
            db.update(connection -> {
                var statement = connection.prepareStatement("delete from custom_alert_targets where alert_id = ? and not (watchlist_id = any(?))");
                statement.setLong(1, id);
                statement.setArray(2, connection.createArrayOf("bigint", lists.toArray(Long[]::new)));
                return statement;
            });
        }
        // A stock chosen again is covered again.
        db.update("""
                delete from custom_alert_exclusions x where x.alert_id = ? and exists (
                    select 1 from custom_alert_target_members tm join custom_alert_targets t on t.id = tm.target_id
                    where t.alert_id = x.alert_id and tm.stock_asset_id = x.stock_asset_id)
                """, id);
        List<Long> after = db.queryForList("select stock_asset_id from custom_alert_effective_targets where alert_id = ?", Long.class, id);
        // Only stocks the alert did not already cover can push the account over its capacity.
        if (guard != null && isActive(id) && after.stream().anyMatch(asset -> !before.contains(asset))) {
            guard.enforceCapacity(user, after, id);
        }
        syncStates(id, notifyExisting);
    }

    /** States follow the covered stocks: new ones wait for a baseline, uncovered ones are dropped. */
    private void syncStates(long id, boolean notifyExisting) {
        db.update("""
                delete from custom_alert_states s where s.alert_id = ? and not exists (
                    select 1 from custom_alert_effective_targets t where t.alert_id = s.alert_id and t.stock_asset_id = s.stock_asset_id)
                """, id);
        db.update("""
                insert into custom_alert_states (alert_id, stock_asset_id, revision, notify_on_baseline)
                select t.alert_id, t.stock_asset_id, a.revision, ? from custom_alert_effective_targets t
                join custom_alerts a on a.id = t.alert_id where t.alert_id = ?
                on conflict (alert_id, stock_asset_id) do nothing
                """, notifyExisting, id);
        if (events != null) events.publishEvent(new CustomAlertsChanged(id));
    }

    @Transactional
    public AlertView setActive(User user, long id, boolean active) {
        watchlists.lock(user);
        Map<String, Object> existing = owned(user, id);
        boolean current = Boolean.TRUE.equals(existing.get("active"));
        if (active && !current) {
            requireRoomForActive(user, id);
            if (guard != null) guard.enforceCapacity(user, db.queryForList(
                    "select stock_asset_id from custom_alert_effective_targets where alert_id = ?", Long.class, id), id);
            // Candles that completed while the alert was paused cannot trigger it.
            db.update("""
                    update custom_alert_states set last_evaluated_timestamp = null, last_result = null, status = 'OK',
                           status_detail = null, notify_on_baseline = false, updated_at = current_timestamp where alert_id = ?
                    """, id);
            syncStates(id, false);
        }
        db.update("update custom_alerts set active = ?, updated_at = current_timestamp where id = ?", active, id);
        return get(user, id);
    }

    @Transactional
    public AlertView setEmail(User user, long id, boolean enabled) {
        owned(user, id);
        db.update("update custom_alerts set email_enabled = ?, updated_at = current_timestamp where id = ?", enabled, id);
        return get(user, id);
    }

    @Transactional
    public AlertView setPinned(User user, long id, boolean pinned) {
        owned(user, id);
        db.update("update custom_alerts set pinned = ?, updated_at = current_timestamp where id = ?", pinned, id);
        return get(user, id);
    }

    /** Deletes the alert and its triggers; watchlist names stay attached to history through their origins. */
    @Transactional
    public void delete(User user, long id) {
        owned(user, id);
        db.update("delete from custom_alerts where id = ? and user_id = ?", id, user.getId());
    }

    /** A copy with the same rule and lists, optionally on another interval, starting paused if no room is left. */
    @Transactional
    public AlertView duplicate(User user, long id, String rawInterval) {
        watchlists.lock(user);
        Map<String, Object> existing = owned(user, id);
        TimeInterval interval = rawInterval == null || rawInterval.isBlank()
                ? TimeInterval.valueOf((String) existing.get("interval")) : interval(rawInterval);
        CustomAlertDefinition definition = CustomAlertDefinition.parse(json.readTree((String) existing.get("definition")), catalog, interval);
        boolean room = activeCount(user, null) < maximumActive;
        String copyName = copyName((String) existing.get("name"), interval, !interval.name().equals(existing.get("interval")));
        Long copy = db.queryForObject("""
                insert into custom_alerts (user_id, name, description, interval, definition, trigger_mode, quiet_candles,
                                           outcome_candles, email_enabled, active)
                select user_id, ?, description, ?, ?, trigger_mode, quiet_candles, outcome_candles, email_enabled, ?
                from custom_alerts where id = ? returning id
                """, Long.class, copyName, interval.name(), json.writeValueAsString(definition.toJson()), room, id);
        db.update("insert into custom_alert_targets (alert_id, watchlist_id, scope) select ?, watchlist_id, scope from custom_alert_targets where alert_id = ?",
                copy, id);
        db.update("""
                insert into custom_alert_target_members (target_id, watchlist_id, stock_asset_id)
                select copy.id, tm.watchlist_id, tm.stock_asset_id from custom_alert_target_members tm
                join custom_alert_targets original on original.id = tm.target_id
                join custom_alert_targets copy on copy.alert_id = ? and copy.watchlist_id = original.watchlist_id
                where original.alert_id = ?
                """, copy, id);
        db.update("insert into custom_alert_exclusions (alert_id, stock_asset_id) select ?, stock_asset_id from custom_alert_exclusions where alert_id = ?",
                copy, id);
        if (room && guard != null) guard.enforceCapacity(user, db.queryForList(
                "select stock_asset_id from custom_alert_effective_targets where alert_id = ?", Long.class, copy), copy);
        syncStates(copy, false);
        return get(user, copy);
    }

    private static String copyName(String name, TimeInterval interval, boolean intervalChanged) {
        String suffix = intervalChanged ? " (" + interval.analysisLabel().toLowerCase(Locale.ROOT) + ")" : " (copy)";
        String base = name.length() + suffix.length() > MAX_NAME ? name.substring(0, MAX_NAME - suffix.length()) : name;
        return base + suffix;
    }

    /** Stops the alert for one stock of a list it covers in full, or brings the stock back. */
    @Transactional
    public AlertView setExcluded(User user, long id, String rawSymbol, boolean excluded) {
        owned(user, id);
        StockAsset asset = assets.findByTickerSymbolIgnoreCase(SecurityInputValidator.requireMarketSymbol(rawSymbol))
                .orElseThrow(() -> new UserInputException("The custom alert was not found."));
        if (excluded) {
            db.update("insert into custom_alert_exclusions (alert_id, stock_asset_id) values (?, ?) on conflict do nothing", id, asset.getId());
            db.update("""
                    delete from custom_alert_target_members tm using custom_alert_targets t
                    where t.id = tm.target_id and t.alert_id = ? and tm.stock_asset_id = ?
                    """, id, asset.getId());
        } else {
            db.update("delete from custom_alert_exclusions where alert_id = ? and stock_asset_id = ?", id, asset.getId());
        }
        syncStates(id, false);
        return get(user, id);
    }

    // ------------------------------------------------------------------ stock page

    /** Every alert of the user, marking those covering this stock and the lists that make them cover it. */
    @Transactional(readOnly = true)
    public List<StockAlertView> forStock(User user, String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        Long assetId = assets.findByTickerSymbolIgnoreCase(symbol).map(StockAsset::getId).orElse(null);
        return db.query("""
                select a.id, a.name, a.interval, a.active, a.definition, a.revision, t.watchlist_ids,
                       st.revision state_revision, st.last_evaluated_timestamp, st.last_result, st.status, st.status_detail
                from custom_alerts a
                left join custom_alert_effective_targets t on t.alert_id = a.id and t.stock_asset_id = ?
                left join custom_alert_states st on st.alert_id = a.id and st.stock_asset_id = ?
                where a.user_id = ? order by a.name, a.id
                """, (row, index) -> {
            java.sql.Array lists = row.getArray("watchlist_ids");
            List<Long> ids = lists == null ? List.of() : java.util.Arrays.stream((Long[]) lists.getArray()).toList();
            boolean applied = !ids.isEmpty();
            Integer revision = (Integer) row.getObject("state_revision");
            String state = !applied ? "NOT_APPLIED"
                    : "SKIPPED".equals(row.getString("status")) ? "SKIPPED"
                    : "INSUFFICIENT_HISTORY".equals(row.getString("status")) ? "WAITING"
                    : revision == null || revision != row.getInt("revision") || row.getObject("last_evaluated_timestamp") == null ? "CHECKING"
                    : Boolean.TRUE.equals(row.getObject("last_result")) ? "MATCHING" : "NOT_MATCHING";
            return new StockAlertView(row.getLong("id"), row.getString("name"), row.getString("interval"), row.getBoolean("active"),
                    applied, state, row.getString("status_detail"), ids, json.readTree(row.getString("definition")));
        }, assetId, assetId, user.getId());
    }

    /**
     * Applies or removes alerts for one stock from its page. Applying adds the stock to the chosen lists
     * (as built-in follows do) and to each alert's chosen members there; removing takes the stock out of
     * every list's chosen members and stops it where a whole list is covered.
     */
    @Transactional
    public List<StockAlertView> applyToStock(User user, String rawSymbol, List<Long> applyIds, List<Long> removeIds,
                                             List<Long> watchlistIds, String newWatchlistName) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        List<Long> applying = applyIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(applyIds));
        List<Long> removing = removeIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(removeIds));
        applying.forEach(id -> owned(user, id));
        removing.forEach(id -> owned(user, id));
        if (!applying.isEmpty()) {
            List<Long> chosen = new ArrayList<>(watchlistIds == null ? List.of() : watchlistIds);
            if (chosen.isEmpty() && (newWatchlistName == null || newWatchlistName.isBlank())) {
                throw new UserInputException("Select a watchlist or create one.");
            }
            watchlists.attach(user, symbol, chosen, newWatchlistName);
            List<Long> lists = new ArrayList<>(watchlists.memberships(user, symbol));
            if (!chosen.isEmpty()) lists.retainAll(chosen);
            else lists = lists.subList(lists.size() - 1, lists.size());
            StockAsset asset = assets.findByTickerSymbolIgnoreCase(symbol).orElseThrow();
            for (long id : applying) {
                db.update("delete from custom_alert_exclusions where alert_id = ? and stock_asset_id = ?", id, asset.getId());
                List<Long> before = db.queryForList("select stock_asset_id from custom_alert_effective_targets where alert_id = ?", Long.class, id);
                for (long list : lists) {
                    String listName = watchlists.name(user, list);
                    if (Boolean.TRUE.equals(db.queryForObject("""
                            select exists(select 1 from watchlist_disallowed_signals where watchlist_id = ? and signal_type = 'CUSTOM')
                            """, Boolean.class, list))) {
                        throw new UserInputException("The watchlist " + listName
                                + " does not allow custom alerts. Allow them in its settings or choose another list.");
                    }
                    Long target = db.queryForObject("""
                            insert into custom_alert_targets (alert_id, watchlist_id, scope) values (?, ?, 'SELECTED')
                            on conflict (alert_id, watchlist_id) do update set scope = custom_alert_targets.scope returning id
                            """, Long.class, id, list);
                    db.update("""
                            insert into custom_alert_target_members (target_id, watchlist_id, stock_asset_id) values (?, ?, ?)
                            on conflict do nothing
                            """, target, list, asset.getId());
                }
                if (guard != null && isActive(id) && !before.contains(asset.getId())) {
                    guard.enforceCapacity(user, db.queryForList(
                            "select stock_asset_id from custom_alert_effective_targets where alert_id = ?", Long.class, id), id);
                }
                syncStates(id, false);
            }
        }
        if (!removing.isEmpty()) {
            StockAsset asset = assets.findByTickerSymbolIgnoreCase(symbol).orElse(null);
            if (asset != null) {
                for (long id : removing) {
                    db.update("""
                            delete from custom_alert_target_members tm using custom_alert_targets t
                            where t.id = tm.target_id and t.alert_id = ? and tm.stock_asset_id = ?
                            """, id, asset.getId());
                    // Lists left with no chosen member no longer belong to the alert.
                    db.update("""
                            delete from custom_alert_targets t where t.alert_id = ? and t.scope = 'SELECTED'
                            and not exists (select 1 from custom_alert_target_members tm where tm.target_id = t.id)
                            """, id);
                    if (Boolean.TRUE.equals(db.queryForObject(
                            "select exists(select 1 from custom_alert_effective_targets where alert_id = ? and stock_asset_id = ?)",
                            Boolean.class, id, asset.getId()))) {
                        db.update("insert into custom_alert_exclusions (alert_id, stock_asset_id) values (?, ?) on conflict do nothing", id, asset.getId());
                    }
                    syncStates(id, false);
                }
            }
        }
        return forStock(user, symbol);
    }

    // ------------------------------------------------------------------ watchlists

    @Transactional(readOnly = true)
    public List<WatchlistAlertView> forWatchlist(User user, long watchlistId) {
        watchlists.requireOwned(user, watchlistId);
        return db.query("""
                select a.id, a.name, a.interval, a.active, a.definition, t.scope,
                       (select count(*) from custom_alert_effective_targets e join watchlist_members m
                            on m.stock_asset_id = e.stock_asset_id and m.watchlist_id = t.watchlist_id
                        where e.alert_id = a.id) stocks,
                       (select count(*) from custom_alert_states s join watchlist_members m
                            on m.stock_asset_id = s.stock_asset_id and m.watchlist_id = t.watchlist_id
                        join custom_alert_effective_targets e on e.alert_id = s.alert_id and e.stock_asset_id = s.stock_asset_id
                        where s.alert_id = a.id and s.revision = a.revision and s.last_evaluated_timestamp is not null and s.last_result) matching,
                       (select count(*) from custom_alert_events ev join watchlist_notification_origins o
                            on o.kind = 'CUSTOM' and o.notification_id = ev.id and o.watchlist_id = t.watchlist_id
                        where ev.alert_id = a.id and ev.deleted_at is null and ev.read_at is null) unread
                from custom_alert_targets t join custom_alerts a on a.id = t.alert_id
                where t.watchlist_id = ? and a.user_id = ? order by a.active desc, a.name, a.id
                """, (row, index) -> new WatchlistAlertView(row.getLong("id"), row.getString("name"), row.getString("interval"),
                row.getBoolean("active"), row.getString("scope"), row.getLong("stocks"), row.getLong("matching"),
                row.getLong("unread"), json.readTree(row.getString("definition"))), watchlistId, user.getId());
    }

    /** Applies the alert to a whole list, or removes it from the list. */
    @Transactional
    public AlertView setWatchlist(User user, long id, long watchlistId, boolean applied) {
        watchlists.lock(user);
        owned(user, id);
        List<TargetCommand> current = new ArrayList<>();
        for (TargetView target : get(user, id).targets()) {
            if (target.watchlistId() == watchlistId) continue;
            current.add(new TargetCommand(target.watchlistId(), target.scope(), target.symbols()));
        }
        if (applied) current.add(new TargetCommand(watchlistId, "ALL_MEMBERS", List.of()));
        applyTargets(user, id, current, false);
        return get(user, id);
    }

    /** The custom alerts covering each listed member, for the watchlist table. */
    @Transactional(readOnly = true)
    public Map<String, List<Map<String, Object>>> memberAlerts(User user, long watchlistId, java.util.Collection<String> symbols) {
        if (symbols.isEmpty()) return Map.of();
        watchlists.requireOwned(user, watchlistId);
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        List<Object> arguments = new ArrayList<>(List.of(watchlistId, user.getId()));
        arguments.addAll(symbols);
        db.query("""
                select s.ticker_symbol, a.id, a.name, a.interval, a.active from custom_alert_effective_targets e
                join custom_alerts a on a.id = e.alert_id join stock_assets s on s.id = e.stock_asset_id
                join custom_alert_targets t on t.alert_id = a.id and t.watchlist_id = ?
                where a.user_id = ? and (t.scope = 'ALL_MEMBERS' or exists (select 1 from custom_alert_target_members tm
                    where tm.target_id = t.id and tm.stock_asset_id = e.stock_asset_id))
                  and s.ticker_symbol in (%s) order by a.name
                """.formatted(String.join(",", Collections.nCopies(symbols.size(), "?"))), row -> {
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("id", row.getLong("id"));
            alert.put("name", row.getString("name"));
            alert.put("interval", row.getString("interval"));
            alert.put("active", row.getBoolean("active"));
            result.computeIfAbsent(row.getString("ticker_symbol"), key -> new ArrayList<>()).add(alert);
        }, arguments.toArray());
        return result;
    }

    // ------------------------------------------------------------------ triggers

    private static final String TRIGGER_QUERY = """
            select e.id, e.alert_id, a.name, a.active, a.interval, e.revision, a.revision alert_revision, s.ticker_symbol,
                   s.company_name, e.candle_timestamp, e.close_price, e.kind, e.created_at, e.read_at, e.matched_conditions,
                   e.total_conditions, e.evidence, e.forward_returns, e.outcome_return, e.outcome_candles, e.outcome_status, e.rule_text
            from custom_alert_events e join custom_alerts a on a.id = e.alert_id join stock_assets s on s.id = e.stock_asset_id
            """;

    @Transactional(readOnly = true)
    public List<TriggerSummary> latestUnread(User user, int limit) {
        return db.query(TRIGGER_QUERY + " where a.user_id = ? and e.read_at is null and e.deleted_at is null "
                + "order by e.created_at desc, e.id desc limit ?", (row, index) -> summary(row), user.getId(), Math.max(1, Math.min(50, limit)));
    }

    private static TriggerSummary summary(ResultSet row) throws SQLException {
        return new TriggerSummary(row.getLong("id"), row.getLong("alert_id"), row.getString("name"), row.getString("ticker_symbol"),
                row.getString("company_name"), TimeInterval.valueOf(row.getString("interval")), row.getLong("candle_timestamp"),
                row.getInt("matched_conditions"), row.getInt("total_conditions"), row.getString("kind"),
                instant(row.getTimestamp("created_at")), row.getTimestamp("read_at") != null,
                row.getObject("outcome_return") == null ? null : row.getDouble("outcome_return"));
    }

    /** One trigger with its evidence. Reading it changes nothing; the page marks it read with a POST once shown. */
    @Transactional(readOnly = true)
    public TriggerView trigger(User user, long eventId) {
        List<TriggerView> found = db.query(TRIGGER_QUERY + " where e.id = ? and a.user_id = ? and e.deleted_at is null",
                (row, index) -> new TriggerView(row.getLong("id"), row.getLong("alert_id"), row.getString("name"), row.getBoolean("active"),
                        row.getInt("revision") != row.getInt("alert_revision"), row.getString("ticker_symbol"), row.getString("company_name"),
                        TimeInterval.valueOf(row.getString("interval")), row.getLong("candle_timestamp"), row.getDouble("close_price"),
                        row.getString("kind"), instant(row.getTimestamp("created_at")), instant(row.getTimestamp("read_at")),
                        row.getInt("matched_conditions"), row.getInt("total_conditions"), json.readTree(row.getString("evidence")),
                        row.getString("forward_returns") == null ? null : json.readTree(row.getString("forward_returns")),
                        row.getObject("outcome_return") == null ? null : row.getDouble("outcome_return"),
                        row.getInt("outcome_candles"), row.getString("outcome_status"), row.getString("rule_text"), null),
                eventId, user.getId());
        if (found.isEmpty()) throw new UserInputException("The custom alert trigger was not found.");
        TriggerView view = found.getFirst();
        String origins = db.query("""
                select string_agg(name || case when watchlist_id is null then ' (deleted)' else '' end, ' · ' order by name)
                from watchlist_notification_origins where user_id = ? and kind = 'CUSTOM' and notification_id = ?
                """, result -> result.next() ? result.getString(1) : null, user.getId(), eventId);
        return new TriggerView(view.id(), view.alertId(), view.alertName(), view.alertActive(), view.ruleChanged(), view.symbol(),
                view.companyName(), view.interval(), view.candleTimestamp(), view.closePrice(), view.kind(), view.createdAt(),
                view.readAt(), view.matchedConditions(), view.totalConditions(), view.evidence(), view.forwardReturns(),
                view.outcomeReturn(), view.outcomeCandles(), view.outcomeStatus(), view.ruleText(), origins);
    }

    /** Stored closes around a trigger (60 candles before, up to 30 after) for its price sketch: [time, close]. */
    @Transactional(readOnly = true)
    public List<double[]> pricesAround(TriggerView trigger) {
        String interval = CustomAlertFactsLoader.apiInterval(trigger.interval());
        List<double[]> before = db.query("""
                select timestamp, close_price from candles where symbol = ? and time_interval = ? and timestamp <= ?
                  and close_price is not null order by timestamp desc limit 60
                """, (row, index) -> new double[]{row.getLong(1), row.getDouble(2)}, trigger.symbol(), interval, trigger.candleTimestamp());
        List<double[]> after = db.query("""
                select timestamp, close_price from candles where symbol = ? and time_interval = ? and timestamp > ?
                  and close_price is not null order by timestamp limit 30
                """, (row, index) -> new double[]{row.getLong(1), row.getDouble(2)}, trigger.symbol(), interval, trigger.candleTimestamp());
        List<double[]> prices = new ArrayList<>(before.reversed());
        prices.addAll(after);
        return prices;
    }

    @Transactional
    public void setRead(User user, long eventId, boolean read) {
        int updated = db.update("""
                update custom_alert_events e set read_at = case when ? then coalesce(e.read_at, current_timestamp) else null end
                from custom_alerts a where a.id = e.alert_id and e.id = ? and a.user_id = ? and e.deleted_at is null
                """, read, eventId, user.getId());
        if (updated == 0) throw new UserInputException("The custom alert trigger was not found.");
    }

    /** Marks every unread trigger read: of one alert, or of every custom alert. */
    @Transactional
    public int markAllRead(User user, Long alertId) {
        if (alertId != null) owned(user, alertId);
        return db.update("""
                update custom_alert_events e set read_at = current_timestamp from custom_alerts a
                where a.id = e.alert_id and a.user_id = ? and (cast(? as bigint) is null or a.id = ?)
                  and e.read_at is null and e.deleted_at is null
                """, user.getId(), alertId, alertId);
    }

    @Transactional(readOnly = true)
    public ArchiveHeader archiveHeader(User user, long alertId, Long watchlistId) {
        AlertView alert = get(user, alertId);
        Performance performance = db.query("""
                select count(*) triggers, count(*) filter (where outcome_status = 'COMPLETE') completed,
                       count(*) filter (where outcome_status = 'COMPLETE' and outcome_return > 0) positive,
                       percentile_cont(0.5) within group (order by outcome_return) filter (where outcome_status = 'COMPLETE') median,
                       avg(outcome_return) filter (where outcome_status = 'COMPLETE') average
                from custom_alert_events where alert_id = ? and deleted_at is null
                """, result -> {
            result.next();
            return new Performance(result.getLong("triggers"), result.getLong("completed"), result.getLong("positive"),
                    round(result.getObject("median")), round(result.getObject("average")));
        }, alertId);
        String list = watchlistId == null ? null : watchlists.name(user, watchlistId);
        return new ArchiveHeader(alert, performance, list);
    }

    // ------------------------------------------------------------------ catalog

    public record IndicatorOption(String type, String shortName, String category, List<IndicatorDefinition.InputSpec> inputs,
                                  List<String> outputs, IndicatorDefinition.Zones zones) { }

    public record Catalog(List<IndicatorOption> indicators, Map<String, List<String>> patterns, int maxConditions, int maxGroups,
                          int maxIndicators, int maxWindow, int maximumActive) { }

    /** What the builder offers: alertable indicators with their lines, and the pattern names of each family. */
    public Catalog catalog() {
        List<IndicatorOption> indicators = catalog.all().stream().filter(CustomAlertIndicators::alertable)
                .map(definition -> new IndicatorOption(definition.type(), definition.shortName(), definition.category().name(),
                        definition.inputs().stream().filter(input -> !"offset".equals(input.key())).toList(),
                        CustomAlertIndicators.outputs(definition), definition.zones()))
                .toList();
        Map<String, List<String>> patterns = new LinkedHashMap<>();
        for (String family : CustomAlertDefinition.FAMILIES) {
            patterns.put(family, switch (family) {
                case "CANDLESTICK" -> java.util.Arrays.stream(org.example.stockwatch247.model.enums.CandlePattern.values())
                        .map(Enum::name).filter(CustomAlertDefinition.patternNames(family)::contains).toList();
                case "ELLIOTT_WAVE" -> java.util.Arrays.stream(org.example.stockwatch247.model.enums.ElliottSignalStage.values()).map(Enum::name).toList();
                default -> java.util.Arrays.stream(org.example.stockwatch247.model.enums.HarmonicPatternType.values()).map(Enum::name).toList();
            });
        }
        return new Catalog(indicators, patterns, CustomAlertDefinition.MAX_CONDITIONS, CustomAlertDefinition.MAX_GROUPS,
                CustomAlertDefinition.MAX_INDICATORS, CustomAlertDefinition.MAX_WINDOW_CANDLES, maximumActive);
    }

    // ------------------------------------------------------------------ helpers

    public void requireOwned(User user, long id) {
        owned(user, id);
    }

    private Map<String, Object> owned(User user, long id) {
        List<Map<String, Object>> rows = db.queryForList(
                "select id, definition, interval, trigger_mode, active, name from custom_alerts where id = ? and user_id = ?", id, user.getId());
        if (rows.isEmpty()) throw new UserInputException("The custom alert was not found.");
        return rows.getFirst();
    }

    private boolean isActive(long id) {
        return Boolean.TRUE.equals(db.queryForObject("select active from custom_alerts where id = ?", Boolean.class, id));
    }

    private long activeCount(User user, Long except) {
        Long count = db.queryForObject("select count(*) from custom_alerts where user_id = ? and active and (cast(? as bigint) is null or id <> ?)",
                Long.class, user.getId(), except, except);
        return count == null ? 0 : count;
    }

    private void requireRoomForActive(User user, Long except) {
        if (activeCount(user, except) >= maximumActive) {
            throw new ConflictException("You already have " + maximumActive + " active custom alerts. Pause or delete one first.");
        }
    }

    static TimeInterval interval(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "DAILY", "1D" -> TimeInterval.DAILY;
            case "WEEKLY", "1WK" -> TimeInterval.WEEKLY;
            case "MONTHLY", "1MO" -> TimeInterval.MONTHLY;
            default -> throw new UserInputException("Choose daily, weekly or monthly candles.");
        };
    }

    private static String name(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_NAME || value.chars().anyMatch(Character::isISOControl)) {
            throw new UserInputException("Name the alert in 1 to 80 characters.");
        }
        return value;
    }

    private static String description(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > MAX_DESCRIPTION || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new UserInputException("Use at most 500 characters for the description.");
        }
        return value;
    }

    private static String triggerMode(String raw) {
        String value = raw == null || raw.isBlank() ? "BECOMES_TRUE" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TRIGGER_MODES.contains(value)) throw new UserInputException("Choose when the alert triggers.");
        return value;
    }

    private static int quiet(Integer raw) {
        int value = raw == null ? 0 : raw;
        if (value < 0 || value > 60) throw new UserInputException("Choose a quiet period of 0 to 60 candles.");
        return value;
    }

    private static int outcome(Integer raw) {
        int value = raw == null ? 10 : raw;
        if (value < 1 || value > 60) throw new UserInputException("Choose 1 to 60 candles to follow the price.");
        return value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Double round(Object value) {
        return value instanceof Number number ? Math.round(number.doubleValue() * 100.0) / 100.0 : null;
    }
}
