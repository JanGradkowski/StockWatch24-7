package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AlertNotificationService;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.BackgroundJobDispatcher;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs custom alerts.
 *
 * <p>The scheduled check of a symbol and interval calls {@link #evaluate} after refreshing its candles. For
 * every active alert covering the stock, each completed candle since the last check is evaluated oldest
 * first and the alert's trigger mode decides whether it fires. A stock newly covered by an alert, or an
 * alert whose rule changed, first gets a baseline: the latest candle is evaluated without triggering (unless
 * the user asked to hear about stocks that already match), so only later candles can trigger. Baselines are
 * also set in the background from stored candles, so "matching now" shows within moments of saving.</p>
 */
@Service
public class CustomAlertEngine {
    private static final Logger log = LoggerFactory.getLogger(CustomAlertEngine.class);
    /** Candles evaluated per stock in one check; older ones after a long outage are skipped. */
    static final int MAX_CATCH_UP = 60;
    static final int[] FORWARD_CANDLES = {1, 5, 10, 20};
    private static final DateTimeFormatter EMAIL_DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final CustomAlertFactsLoader facts;
    private final ChartIndicatorCatalog catalog;
    private final StockAssetRepository assets;
    private final UserRepository users;
    private final TransactionTemplate transactions;
    private AlertNotificationService notifications;
    private AnalysisPreferencesService preferences;
    private BackgroundJobDispatcher dispatcher;
    private final boolean scheduleEnabled;

    public CustomAlertEngine(JdbcTemplate db, ObjectMapper json, CustomAlertFactsLoader facts, ChartIndicatorCatalog catalog,
                             StockAssetRepository assets, UserRepository users, TransactionTemplate transactions,
                             @Value("${custom-alerts.background-enabled:${alerts.schedule.enabled:true}}") boolean scheduleEnabled) {
        this.db = db;
        this.json = json;
        this.facts = facts;
        this.catalog = catalog;
        this.assets = assets;
        this.users = users;
        this.transactions = transactions;
        this.scheduleEnabled = scheduleEnabled;
    }

    @Autowired(required = false)
    void configure(AlertNotificationService notifications, AnalysisPreferencesService preferences) {
        this.notifications = notifications;
        this.preferences = preferences;
    }

    @Autowired(required = false)
    void configureDispatcher(BackgroundJobDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public record EvaluationResult(int checked, int triggered) { }

    /** One alert covering one stock, with where its state stands. */
    record Target(long alertId, long userId, String name, String definition, int revision, String triggerMode,
                  int quietCandles, int outcomeCandles, boolean emailEnabled, Integer stateRevision, Long lastEvaluated,
                  Boolean lastResult, Long lastTriggered, boolean triggeredOnce, boolean notifyOnBaseline) {
        boolean needsBaseline() {
            return stateRevision == null || stateRevision != revision || lastEvaluated == null;
        }
    }

    private static final String TARGETS = """
            select a.id, a.user_id, a.name, a.definition, a.revision, a.trigger_mode, a.quiet_candles, a.outcome_candles,
                   a.email_enabled, s.revision state_revision, s.last_evaluated_timestamp, s.last_result,
                   s.last_triggered_timestamp, coalesce(s.triggered_once, false) triggered_once,
                   coalesce(s.notify_on_baseline, false) notify_on_baseline
            from custom_alert_effective_targets t
            join custom_alerts a on a.id = t.alert_id
            left join custom_alert_states s on s.alert_id = t.alert_id and s.stock_asset_id = t.stock_asset_id
            where t.stock_asset_id = ? and a.interval = ? and a.active
            """;

    private List<Target> targets(long assetId, TimeInterval interval, Long alertId) {
        return db.query(TARGETS + (alertId == null ? "" : " and a.id = " + alertId) + " order by a.id", (row, index) -> new Target(
                row.getLong("id"), row.getLong("user_id"), row.getString("name"), row.getString("definition"),
                row.getInt("revision"), row.getString("trigger_mode"), row.getInt("quiet_candles"), row.getInt("outcome_candles"),
                row.getBoolean("email_enabled"), (Integer) row.getObject("state_revision"),
                (Long) row.getObject("last_evaluated_timestamp"), (Boolean) row.getObject("last_result"),
                (Long) row.getObject("last_triggered_timestamp"), row.getBoolean("triggered_once"),
                row.getBoolean("notify_on_baseline")), assetId, interval.name());
    }

    // ------------------------------------------------------------------ scheduled checks

    /**
     * Called by the scheduled check after the symbol's candles were refreshed. {@code scheduledFor} limits
     * a missed check to the candles that existed at its original time.
     */
    public EvaluationResult evaluate(String symbol, TimeInterval interval, Instant scheduledFor) {
        if (interval != TimeInterval.DAILY && interval != TimeInterval.WEEKLY && interval != TimeInterval.MONTHLY) {
            return new EvaluationResult(0, 0);
        }
        StockAsset asset = assets.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        if (asset == null) return new EvaluationResult(0, 0);
        List<Target> targets = targets(asset.getId(), interval, null);
        CustomAlertFactsLoader.StockData data = null;
        int triggered = 0;
        if (!targets.isEmpty()) {
            data = facts.load(asset, interval, scheduledFor == null ? null : scheduledFor.getEpochSecond());
            triggered = evaluateTargets(data, targets, false);
        }
        updateOutcomes(asset, interval, data);
        return new EvaluationResult(targets.size(), triggered);
    }

    private int evaluateTargets(CustomAlertFactsLoader.StockData data, List<Target> targets, boolean baselineOnly) {
        Map<Long, CustomAlertDefinition> definitions = new LinkedHashMap<>();
        for (Target target : targets) {
            try {
                definitions.put(target.alertId(), CustomAlertDefinition.parse(json.readTree(target.definition()), catalog, data.interval()));
            } catch (RuntimeException invalid) {
                log.warn("Custom alert {} has an unreadable rule", target.alertId(), invalid);
                saveState(target, data.asset().getId(), null, false, "FAILED", "The rule could not be read.", false);
            }
        }
        // Every indicator setting of every alert on this stock, computed in one batch.
        int lookback = definitions.values().stream().mapToInt(CustomAlertDefinition::lookbackCandles).max().orElse(1) + MAX_CATCH_UP;
        try {
            data.prepare(definitions.values().stream().flatMap(definition -> definition.operands().stream()).toList(), lookback);
        } catch (RuntimeException unavailable) {
            log.warn("Custom alert indicators unavailable for {} {}", data.asset().getTickerSymbol(), data.interval(), unavailable);
        }
        Map<Long, User> owners = new HashMap<>();
        int triggered = 0;
        for (Target target : targets) {
            CustomAlertDefinition definition = definitions.get(target.alertId());
            if (definition == null) continue;
            if (baselineOnly && !target.needsBaseline()) continue;
            User owner = owners.computeIfAbsent(target.userId(), id -> users.findById(id).orElse(null));
            if (owner == null) continue;
            try {
                triggered += Optional.ofNullable(transactions.execute(status ->
                        evaluateTarget(data, target, definition, owner))).orElse(0);
            } catch (RuntimeException failure) {
                log.warn("Custom alert {} could not be evaluated for {}", target.alertId(), data.asset().getTickerSymbol(), failure);
            }
        }
        return triggered;
    }

    private int evaluateTarget(CustomAlertFactsLoader.StockData data, Target target, CustomAlertDefinition definition, User owner) {
        long assetId = data.asset().getId();
        // The state row is locked so a background baseline and a scheduled check cannot both handle a candle.
        db.update("""
                insert into custom_alert_states (alert_id, stock_asset_id, revision) values (?, ?, ?)
                on conflict (alert_id, stock_asset_id) do nothing
                """, target.alertId(), assetId, target.revision());
        Target current = db.query("""
                select s.revision, s.last_evaluated_timestamp, s.last_result, s.last_triggered_timestamp, s.triggered_once,
                       s.notify_on_baseline from custom_alert_states s where s.alert_id = ? and s.stock_asset_id = ? for update
                """, (row, index) -> new Target(target.alertId(), target.userId(), target.name(), target.definition(),
                target.revision(), target.triggerMode(), target.quietCandles(), target.outcomeCandles(), target.emailEnabled(),
                row.getInt("revision"), (Long) row.getObject("last_evaluated_timestamp"), (Boolean) row.getObject("last_result"),
                (Long) row.getObject("last_triggered_timestamp"), row.getBoolean("triggered_once"),
                row.getBoolean("notify_on_baseline")), target.alertId(), assetId).getFirst();
        List<Candle> candles = data.candles();
        if (candles.size() < 2) {
            saveState(current, assetId, null, false, "INSUFFICIENT_HISTORY", "Not enough completed candles are stored yet.",
                    current.notifyOnBaseline() && current.stateRevision() == current.revision());
            return 0;
        }
        CustomAlertEvaluator.Facts ownerFacts = data.forOwner(owner);
        int last = candles.size() - 1;
        if (current.needsBaseline()) {
            Result result = CustomAlertEvaluator.evaluate(definition, ownerFacts, last);
            int triggered = 0;
            if (result.matched() && current.notifyOnBaseline() && current.stateRevision() == current.revision()) {
                triggered = record(data, current, definition, result, last, "MATCHED_ON_APPLY") ? 1 : 0;
            }
            saveBaseline(current, assetId, candles.get(last).getTimestamp(), result, triggered > 0);
            return triggered;
        }
        int start = last + 1;
        for (int index = last; index >= 0 && candles.get(index).getTimestamp() > current.lastEvaluated(); index--) start = index;
        if (start > last) return 0;
        start = Math.max(1, Math.max(start, last - MAX_CATCH_UP + 1));
        boolean previous = Boolean.TRUE.equals(current.lastResult());
        Long lastTriggered = current.lastTriggered();
        boolean once = current.triggeredOnce();
        int triggered = 0;
        Result result = null;
        for (int index = start; index <= last; index++) {
            result = CustomAlertEvaluator.evaluate(definition, ownerFacts, index);
            if (!result.applicable()) {
                previous = false;
                continue;
            }
            boolean fire = switch (current.triggerMode()) {
                case "EVERY_CANDLE" -> result.matched();
                case "ONCE" -> result.matched() && !previous && !once;
                default -> result.matched() && !previous;
            };
            if (fire && lastTriggered != null && current.quietCandles() > 0
                    && candlesSince(candles, lastTriggered, index) <= current.quietCandles()) {
                fire = false;
            }
            if (fire && record(data, current, definition, result, index, "TRIGGERED")) {
                triggered++;
                lastTriggered = candles.get(index).getTimestamp();
                if ("ONCE".equals(current.triggerMode())) once = true;
            }
            previous = result.matched();
        }
        String status = result != null && !result.applicable() ? "SKIPPED" : "OK";
        db.update("""
                update custom_alert_states set last_evaluated_timestamp = ?, last_result = ?, status = ?, status_detail = ?,
                       last_triggered_timestamp = ?, triggered_once = ?, updated_at = current_timestamp
                where alert_id = ? and stock_asset_id = ?
                """, candles.get(last).getTimestamp(), previous, status,
                "SKIPPED".equals(status) ? "A condition does not apply to this instrument." : null,
                lastTriggered, once, current.alertId(), assetId);
        return triggered;
    }

    private static int candlesSince(List<Candle> candles, long timestamp, int index) {
        int count = 0;
        for (int at = index; at >= 0 && candles.get(at).getTimestamp() > timestamp; at--) count++;
        return count;
    }

    private void saveBaseline(Target target, long assetId, long candle, Result result, boolean triggered) {
        String status = result.applicable() ? "OK" : "SKIPPED";
        db.update("""
                update custom_alert_states set revision = ?, last_evaluated_timestamp = ?, last_result = ?, status = ?,
                       status_detail = ?, last_triggered_timestamp = case when ? then ? else null end, triggered_once = ?,
                       notify_on_baseline = false, updated_at = current_timestamp
                where alert_id = ? and stock_asset_id = ?
                """, target.revision(), candle, result.matched(), status,
                result.applicable() ? null : "A condition does not apply to this instrument.",
                triggered, candle, triggered && "ONCE".equals(target.triggerMode()), target.alertId(), assetId);
    }

    private void saveState(Target target, long assetId, Long candle, boolean result, String status, String detail, boolean keepNotify) {
        db.update("""
                insert into custom_alert_states (alert_id, stock_asset_id, revision, last_evaluated_timestamp, last_result,
                                                 status, status_detail, notify_on_baseline)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (alert_id, stock_asset_id) do update set revision = excluded.revision,
                    last_evaluated_timestamp = excluded.last_evaluated_timestamp,
                    last_result = excluded.last_result, status = excluded.status, status_detail = excluded.status_detail,
                    notify_on_baseline = excluded.notify_on_baseline, updated_at = current_timestamp
                """, target.alertId(), assetId, target.revision(), candle, result, status, detail, keepNotify);
    }

    /** Stores a trigger once per alert, stock and candle; true when it is new. */
    private boolean record(CustomAlertFactsLoader.StockData data, Target target, CustomAlertDefinition definition, Result result,
                           int index, String kind) {
        Candle candle = data.candles().get(index);
        ObjectNode evidence = json.createObjectNode();
        evidence.put("candle", candle.getTimestamp());
        evidence.put("close", candle.getClosePrice());
        evidence.set("definition", definition.toJson());
        ArrayNode conditions = evidence.putArray("conditions");
        for (var condition : result.conditions()) {
            ObjectNode node = conditions.addObject();
            node.put("id", condition.id());
            node.put("kind", condition.kind());
            node.put("passed", condition.passed());
            node.put("applicable", condition.applicable());
            node.set("facts", json.valueToTree(condition.facts()));
        }
        List<Long> inserted = db.queryForList("""
                insert into custom_alert_events (alert_id, stock_asset_id, candle_timestamp, kind, close_price, revision, rule_text,
                                                 evidence, matched_conditions, total_conditions, outcome_candles, email_status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (alert_id, stock_asset_id, candle_timestamp) do nothing returning id
                """, Long.class, target.alertId(), data.asset().getId(), candle.getTimestamp(), kind, candle.getClosePrice(),
                target.revision(), CustomAlertText.rule(definition, catalog), json.writeValueAsString(evidence),
                result.matchedConditions(), result.totalConditions(), target.outcomeCandles(),
                target.emailEnabled() ? "PENDING" : "NONE");
        if (inserted.isEmpty()) return false;
        db.update("update custom_alerts set last_triggered_at = current_timestamp where id = ?", target.alertId());
        log.info("Custom alert {} triggered for {} on candle {}", target.alertId(), data.asset().getTickerSymbol(), candle.getTimestamp());
        return true;
    }

    // ------------------------------------------------------------------ outcomes

    /** Fills in the price change after each pending trigger of this stock as later candles complete. */
    private void updateOutcomes(StockAsset asset, TimeInterval interval, CustomAlertFactsLoader.StockData loaded) {
        List<Object[]> pending = db.query("""
                select e.id, e.candle_timestamp, e.close_price, e.outcome_candles from custom_alert_events e
                join custom_alerts a on a.id = e.alert_id
                where e.stock_asset_id = ? and a.interval = ? and e.outcome_status = 'PENDING' and e.deleted_at is null
                order by e.id limit 500
                """, (row, index) -> new Object[]{row.getLong(1), row.getLong(2), row.getDouble(3), row.getInt(4)},
                asset.getId(), interval.name());
        if (pending.isEmpty()) return;
        CustomAlertFactsLoader.StockData data = loaded != null ? loaded : facts.load(asset, interval, null);
        List<Candle> candles = data.candles();
        Map<Long, Integer> indexByTime = new HashMap<>();
        for (int at = 0; at < candles.size(); at++) indexByTime.put(candles.get(at).getTimestamp(), at);
        for (Object[] event : pending) {
            Integer start = indexByTime.get((Long) event[1]);
            if (start == null) continue;
            double entry = (Double) event[2];
            int window = (Integer) event[3];
            ObjectNode returns = json.createObjectNode();
            for (int offset : FORWARD_CANDLES) {
                if (start + offset < candles.size()) returns.put(String.valueOf(offset), change(entry, candles.get(start + offset).getClosePrice()));
            }
            int available = candles.size() - 1 - start;
            Double outcome = available > 0 ? change(entry, candles.get(Math.min(start + window, candles.size() - 1)).getClosePrice()) : null;
            boolean complete = available >= window;
            db.update("update custom_alert_events set forward_returns = ?, outcome_return = ?, outcome_status = ? where id = ?",
                    returns.isEmpty() ? null : json.writeValueAsString(returns), outcome, complete ? "COMPLETE" : "PENDING", event[0]);
        }
    }

    private static double change(double entry, Double close) {
        if (close == null || entry == 0) return 0;
        return Math.round((close - entry) / entry * 100 * 100.0) / 100.0;
    }

    // ------------------------------------------------------------------ background baselines

    @Scheduled(fixedDelayString = "${custom-alerts.baseline-delay-ms:15000}", initialDelayString = "${custom-alerts.baseline-initial-delay-ms:20000}")
    public void dispatchBaselines() {
        if (!scheduleEnabled || dispatcher == null) return;
        dispatcher.submit("custom-alerts", this::establishBaselines);
    }

    /**
     * Sets baselines for stocks newly covered by an alert (or after a rule change) from stored candles, so
     * the alert can say which stocks match right away. Stocks with no stored history wait for their check.
     */
    public int establishBaselines() {
        List<Object[]> pending = db.query("""
                select t.stock_asset_id, a.interval from custom_alert_effective_targets t
                join custom_alerts a on a.id = t.alert_id
                left join custom_alert_states s on s.alert_id = t.alert_id and s.stock_asset_id = t.stock_asset_id
                where a.active and (s.alert_id is null or s.revision <> a.revision
                    or (s.last_evaluated_timestamp is null and s.updated_at < current_timestamp - interval '6 hours')
                    or (s.last_evaluated_timestamp is null and s.status = 'OK'))
                group by t.stock_asset_id, a.interval order by min(t.alert_id) limit 20
                """, (row, index) -> new Object[]{row.getLong(1), row.getString(2)});
        int handled = 0;
        for (Object[] item : pending) {
            StockAsset asset = assets.findById((Long) item[0]).orElse(null);
            if (asset == null) continue;
            TimeInterval interval = TimeInterval.valueOf((String) item[1]);
            List<Target> targets = targets(asset.getId(), interval, null).stream().filter(Target::needsBaseline).toList();
            if (targets.isEmpty()) continue;
            try {
                evaluateTargets(facts.load(asset, interval, null), targets, true);
            } catch (RuntimeException failure) {
                log.warn("Custom alert baselines failed for {} {}", asset.getTickerSymbol(), interval, failure);
                // Retried after a pause, so one broken stock cannot hold up the others.
                targets.forEach(target -> saveState(target, asset.getId(), null, false, "FAILED",
                        "The stock could not be checked yet.", target.notifyOnBaseline()));
            }
            handled += targets.size();
        }
        return handled;
    }

    // ------------------------------------------------------------------ email

    @Scheduled(fixedDelayString = "${custom-alerts.email-delay-ms:60000}", initialDelayString = "${custom-alerts.email-initial-delay-ms:45000}")
    public void dispatchEmails() {
        if (!scheduleEnabled || dispatcher == null) return;
        dispatcher.submit("custom-alerts", this::sendPendingEmails);
    }

    /**
     * One email per alert for the triggers of one check: an alert covering a large watchlist can trigger for
     * many stocks at once. Triggers wait until no new one arrived for {@code quietMinutes}, then go together.
     */
    public int sendPendingEmails() {
        return sendPendingEmails(3);
    }

    int sendPendingEmails(int quietMinutes) {
        List<Long> alerts = db.queryForList("""
                select alert_id from custom_alert_events where email_status = 'PENDING' and deleted_at is null
                group by alert_id having max(created_at) <= current_timestamp - (? * interval '1 minute') order by alert_id limit 50
                """, Long.class, quietMinutes);
        int sent = 0;
        for (long alertId : alerts) {
            try {
                Boolean queued = transactions.execute(status -> sendDigest(alertId));
                if (Boolean.TRUE.equals(queued)) sent++;
            } catch (RuntimeException failure) {
                log.warn("Custom alert {} email could not be queued", alertId, failure);
            }
        }
        return sent;
    }

    private boolean sendDigest(long alertId) {
        Map<String, Object> alert = db.queryForMap("select user_id, name, interval, email_enabled from custom_alerts where id = ?", alertId);
        List<Map<String, Object>> events = db.queryForList("""
                select e.id, e.candle_timestamp, e.close_price, e.matched_conditions, e.total_conditions, e.evidence, e.rule_text,
                       s.ticker_symbol, s.company_name
                from custom_alert_events e join stock_assets s on s.id = e.stock_asset_id
                where e.alert_id = ? and e.email_status = 'PENDING' and e.deleted_at is null
                order by s.ticker_symbol for update of e skip locked
                """, alertId);
        if (events.isEmpty()) return false;
        Long[] ids = events.stream().map(row -> ((Number) row.get("id")).longValue()).toArray(Long[]::new);
        User owner = users.findById(((Number) alert.get("user_id")).longValue()).orElse(null);
        boolean allowed = owner != null && Boolean.TRUE.equals(alert.get("email_enabled")) && notifications != null
                && (preferences == null || preferences.allowsCustomAlertEmail(owner));
        boolean sent = false;
        if (allowed) {
            String name = (String) alert.get("name");
            TimeInterval interval = TimeInterval.valueOf((String) alert.get("interval"));
            String subject = events.size() == 1
                    ? "Custom alert: " + name + " for " + events.getFirst().get("ticker_symbol")
                    : "Custom alert: " + name + " for " + events.size() + " stocks";
            sent = notifications.sendCustomAlertEmail(owner, subject, digestBody(name, interval, events),
                    events.size() == 1 ? "/custom-alerts/triggers/" + ids[0] : "/signals?customAlertId=" + alertId,
                    "custom-alert:" + alertId + ":" + ids[ids.length - 1]);
        }
        boolean queued = sent;
        db.update(connection -> {
            var statement = connection.prepareStatement("""
                    update custom_alert_events set email_status = ?, email_queued_at = case when ? then current_timestamp else null end
                    where id = any(?)
                    """);
            statement.setString(1, queued ? "QUEUED" : "SKIPPED");
            statement.setBoolean(2, queued);
            statement.setArray(3, connection.createArrayOf("bigint", ids));
            return statement;
        });
        return queued;
    }

    private String digestBody(String name, TimeInterval interval, List<Map<String, Object>> events) {
        StringBuilder body = new StringBuilder()
                .append("Custom alert: ").append(name).append('\n')
                .append("Interval: ").append(interval.analysisLabel()).append('\n');
        String rule = (String) events.getFirst().get("rule_text");
        body.append('\n').append("Rule:").append('\n').append(rule).append('\n').append('\n');
        int shown = 0;
        for (Map<String, Object> event : events) {
            if (shown++ == 25) {
                body.append("And ").append(events.size() - 25).append(" more").append('\n');
                break;
            }
            long candle = ((Number) event.get("candle_timestamp")).longValue();
            String date = EMAIL_DATE.format(LocalDate.ofInstant(Instant.ofEpochSecond(candle), ZoneOffset.UTC));
            body.append(event.get("ticker_symbol")).append(" · ").append(event.get("company_name") == null ? "" : event.get("company_name"))
                    .append('\n')
                    .append("Completed candle: ").append(date).append('\n')
                    .append("Close: ").append(CustomAlertText.number(((Number) event.get("close_price")).doubleValue())).append('\n')
                    .append("Conditions met: ").append(event.get("matched_conditions")).append(" of ").append(event.get("total_conditions"))
                    .append('\n').append('\n');
        }
        body.append("Informational only, not financial advice.");
        return body.toString();
    }
}
