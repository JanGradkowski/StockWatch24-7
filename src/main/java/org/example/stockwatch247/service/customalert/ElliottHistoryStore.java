package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.PatternHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The day-by-day Elliott Wave history of custom alert previews (V92): for each stock, interval and detection
 * settings, the signals each completed candle's own wave count showed, over a contiguous range of candles.
 */
@Component
public class ElliottHistoryStore {
    private final JdbcTemplate db;

    public ElliottHistoryStore(JdbcTemplate db) {
        this.db = db;
    }

    /** The stored range of one history, and where its build stands. */
    public record Coverage(String status, Long coveredFrom, Long coveredTo, Long userId, Instant updatedAt) {
        boolean ready() { return "READY".equals(status); }
        boolean building() { return "PENDING".equals(status) || "RUNNING".equals(status); }
    }

    /**
     * A claimed build: which history to extend and from where, and the candles its stored range was read from (a
     * fingerprint of all but the last five, and one per candle for those five).
     */
    public record Claim(long stockAssetId, TimeInterval interval, String settingsKey, Long userId, Long coveredFrom,
                        Long coveredTo, String owner, String prefixFingerprint, String tailFingerprints) {
        Claim covering(Long from, Long to) {
            return new Claim(stockAssetId, interval, settingsKey, userId, from, to, owner, null, null);
        }
    }

    /** One signal a candle's count showed. */
    public record Signal(String pattern, String stage, boolean bullish, int score, long confirmedAt, long knownAt,
                         long anchorAt, double price) { }

    /** Fingerprint of everything a stored count depends on: the detector's rule version, the rules, the child timeframe. */
    public static String settingsKey(Object rules, String childApiInterval) {
        String text = ElliottWaveDetectionService.DETECTION_RULE_VERSION + "|" + rules + "|" + childApiInterval + "|history-v1";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public Optional<Coverage> coverage(long stockAssetId, TimeInterval interval, String settingsKey) {
        return db.query("""
                select status, covered_from, covered_to, user_id, updated_at from custom_alert_elliott_coverage
                where stock_asset_id = ? and interval = ? and settings_key = ?
                """, (row, index) -> new Coverage(row.getString("status"), (Long) row.getObject("covered_from"),
                (Long) row.getObject("covered_to"), (Long) row.getObject("user_id"),
                row.getTimestamp("updated_at").toInstant()), stockAssetId, interval.name(), settingsKey).stream().findFirst();
    }

    /**
     * Asks for a history to be built or brought up to date, and notes that a preview used it (unused histories
     * are removed after a while). A failed build is retried after an hour.
     */
    public void request(long stockAssetId, TimeInterval interval, String settingsKey, Long userId) {
        request(stockAssetId, interval, settingsKey, userId, 0);
    }

    /**
     * As above with a queue priority: 0 for previews (someone is waiting), 2 for stocks several people opened, 3 for
     * followed stocks. A waiting history keeps the better of its priorities.
     */
    public void request(long stockAssetId, TimeInterval interval, String settingsKey, Long userId, int priority) {
        db.update("""
                insert into custom_alert_elliott_coverage (stock_asset_id, interval, settings_key, user_id, priority)
                values (?, ?, ?, ?, ?)
                on conflict (stock_asset_id, interval, settings_key) do update set
                    used_at = current_timestamp,
                    priority = case when custom_alert_elliott_coverage.status in ('READY', 'FAILED') then excluded.priority
                        else least(custom_alert_elliott_coverage.priority, excluded.priority) end,
                    user_id = coalesce(custom_alert_elliott_coverage.user_id, excluded.user_id),
                    status = case
                        when custom_alert_elliott_coverage.status = 'READY' then 'PENDING'
                        when custom_alert_elliott_coverage.status = 'FAILED'
                             and custom_alert_elliott_coverage.updated_at < current_timestamp - interval '1 hour' then 'PENDING'
                        else custom_alert_elliott_coverage.status end,
                    requested_at = case when custom_alert_elliott_coverage.status in ('READY', 'FAILED')
                        then current_timestamp else custom_alert_elliott_coverage.requested_at end
                """, stockAssetId, interval.name(), settingsKey, userId, priority);
    }

    /**
     * After a scheduled check of a followed stock: every finished history of it is extended to the new candle (one
     * scan per new candle), so a preview finds it current. Extending does not count as use.
     */
    public int extend(long stockAssetId, TimeInterval interval) {
        return db.update("""
                update custom_alert_elliott_coverage set status = 'PENDING', requested_at = current_timestamp,
                    updated_at = current_timestamp
                where stock_asset_id = ? and interval = ? and status = 'READY'
                """, stockAssetId, interval.name());
    }

    /** A candle the history was read from changed too far back to patch: the history starts again. */
    public void reset(Claim claim) {
        db.update("delete from custom_alert_elliott_signals where stock_asset_id = ? and interval = ? and settings_key = ?",
                claim.stockAssetId(), claim.interval().name(), claim.settingsKey());
        db.update("""
                update custom_alert_elliott_coverage set covered_from = null, covered_to = null, prefix_fingerprint = null,
                    tail_fingerprints = null, updated_at = current_timestamp
                where stock_asset_id = ? and interval = ? and settings_key = ? and lease_owner = ?
                """, claim.stockAssetId(), claim.interval().name(), claim.settingsKey(), claim.owner());
    }

    /**
     * One of the last candles the history was read from changed (a provider corrected it after the close): what was
     * first shown from {@code changedFrom} on is read again, from the candle before it ({@code coveredTo}).
     */
    public void truncate(Claim claim, long changedFrom, long coveredTo) {
        db.update("""
                delete from custom_alert_elliott_signals where stock_asset_id = ? and interval = ? and settings_key = ? and known_at >= ?
                """, claim.stockAssetId(), claim.interval().name(), claim.settingsKey(), changedFrom);
        db.update("""
                update custom_alert_elliott_coverage set covered_to = ?, prefix_fingerprint = null, tail_fingerprints = null,
                    updated_at = current_timestamp
                where stock_asset_id = ? and interval = ? and settings_key = ? and lease_owner = ?
                """, coveredTo, claim.stockAssetId(), claim.interval().name(), claim.settingsKey(), claim.owner());
    }

    /** Notes which candles the stored range was read from. */
    public void fingerprint(Claim claim, String prefix, String tail) {
        db.update("""
                update custom_alert_elliott_coverage set prefix_fingerprint = ?, tail_fingerprints = ?
                where stock_asset_id = ? and interval = ? and settings_key = ? and lease_owner = ?
                """, prefix, tail, claim.stockAssetId(), claim.interval().name(), claim.settingsKey(), claim.owner());
    }

    /** Notes that a preview used a history without asking for more. */
    public void touch(long stockAssetId, TimeInterval interval, String settingsKey) {
        db.update("""
                update custom_alert_elliott_coverage set used_at = current_timestamp
                where stock_asset_id = ? and interval = ? and settings_key = ?
                """, stockAssetId, interval.name(), settingsKey);
    }

    /** The signals stored for candles confirmed from {@code from} to {@code to}, as pattern hits. */
    public List<PatternHit> signals(long stockAssetId, TimeInterval interval, String settingsKey, long from, long to) {
        return db.query("""
                select pattern, stage, bullish, score, confirmed_at, known_at, anchor_at, price
                from custom_alert_elliott_signals
                where stock_asset_id = ? and interval = ? and settings_key = ? and confirmed_at between ? and ?
                order by confirmed_at
                """, (row, index) -> new PatternHit("ELLIOTT_WAVE", row.getString("pattern"), row.getString("stage"),
                row.getBoolean("bullish"), row.getInt("score"), row.getLong("confirmed_at"), row.getLong("anchor_at"),
                null, null, row.getDouble("price"), null, row.getLong("known_at")),
                stockAssetId, interval.name(), settingsKey, from, to);
    }

    /** Claims the best waiting build (previews first, then the oldest), or one whose worker stopped, for {@code lease}. */
    public Optional<Claim> claimNext(String owner, Duration lease) {
        return claimNext(owner, lease, Integer.MAX_VALUE);
    }

    /** As above, among builds of at most {@code maxPriority} (0: only those a preview is waiting for). */
    public Optional<Claim> claimNext(String owner, Duration lease, int maxPriority) {
        return db.query("""
                update custom_alert_elliott_coverage c set status = 'RUNNING', lease_owner = ?, lease_until = ?,
                    attempts = c.attempts + 1, updated_at = current_timestamp
                from (select stock_asset_id, interval, settings_key from custom_alert_elliott_coverage
                      where (status = 'PENDING' or (status = 'RUNNING' and lease_until < current_timestamp)) and priority <= ?
                      order by priority, requested_at limit 1 for update skip locked) next
                where c.stock_asset_id = next.stock_asset_id and c.interval = next.interval and c.settings_key = next.settings_key
                returning c.stock_asset_id, c.interval, c.settings_key, c.user_id, c.covered_from, c.covered_to,
                    c.prefix_fingerprint, c.tail_fingerprints
                """, (row, index) -> new Claim(row.getLong("stock_asset_id"), TimeInterval.valueOf(row.getString("interval")),
                row.getString("settings_key"), (Long) row.getObject("user_id"), (Long) row.getObject("covered_from"),
                (Long) row.getObject("covered_to"), owner, row.getString("prefix_fingerprint"), row.getString("tail_fingerprints")),
                owner, Timestamp.from(Instant.now().plus(lease)), maxPriority).stream().findFirst();
    }

    /** Scheduled alert checks are waiting or running: background work that nobody waits for gives them the processor. */
    public boolean checksRunning() {
        return Boolean.TRUE.equals(db.queryForObject("""
                select exists(select 1 from alert_check_jobs
                              where status = 'PROCESSING' or (status = 'PENDING' and available_at <= current_timestamp))
                """, Boolean.class));
    }

    /**
     * Stores what a slice of candles showed and extends the covered range to {@code coveredTo}. A signal already
     * stored keeps its first known candle.
     */
    public boolean progress(Claim claim, List<Signal> signals, long coveredFrom, long coveredTo, Duration lease) {
        for (Signal signal : signals) {
            db.update("""
                    insert into custom_alert_elliott_signals (stock_asset_id, interval, settings_key, pattern, stage, bullish,
                        score, confirmed_at, known_at, anchor_at, price)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (stock_asset_id, interval, settings_key, pattern, stage, confirmed_at) do nothing
                    """, claim.stockAssetId(), claim.interval().name(), claim.settingsKey(), signal.pattern(), signal.stage(),
                    signal.bullish(), signal.score(), signal.confirmedAt(), signal.knownAt(), signal.anchorAt(),
                    Double.isFinite(signal.price()) ? signal.price() : null);
        }
        return db.update("""
                update custom_alert_elliott_coverage set covered_from = ?, covered_to = ?, lease_until = ?,
                    updated_at = current_timestamp
                where stock_asset_id = ? and interval = ? and settings_key = ? and lease_owner = ? and status = 'RUNNING'
                """, coveredFrom, coveredTo, Timestamp.from(Instant.now().plus(lease)), claim.stockAssetId(),
                claim.interval().name(), claim.settingsKey(), claim.owner()) == 1;
    }

    /** Ends a slice: READY when the history reaches the latest candle, otherwise back in the queue for the next slice. */
    public void release(Claim claim, boolean complete) {
        db.update("""
                update custom_alert_elliott_coverage set status = ?, lease_owner = null, lease_until = null,
                    attempts = case when ? then 0 else attempts end, last_error = null, updated_at = current_timestamp
                where stock_asset_id = ? and interval = ? and settings_key = ? and lease_owner = ?
                """, complete ? "READY" : "PENDING", complete, claim.stockAssetId(), claim.interval().name(),
                claim.settingsKey(), claim.owner());
    }

    public void fail(Claim claim, String reason) {
        db.update("""
                update custom_alert_elliott_coverage set status = 'FAILED', lease_owner = null, lease_until = null,
                    last_error = ?, updated_at = current_timestamp
                where stock_asset_id = ? and interval = ? and settings_key = ? and lease_owner = ?
                """, reason == null ? null : reason.substring(0, Math.min(500, reason.length())), claim.stockAssetId(),
                claim.interval().name(), claim.settingsKey(), claim.owner());
    }

    /** Removes histories no preview has used for {@code unused}. */
    public int removeUnused(Duration unused) {
        return db.update("delete from custom_alert_elliott_coverage where used_at < ? and status <> 'RUNNING'",
                Timestamp.from(Instant.now().minus(unused)));
    }
}
