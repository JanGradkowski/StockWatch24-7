package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps stored history on one price basis when a provider retroactively adjusts for a split.
 *
 * <p>Providers return split-adjusted series, but routine refreshes only rewrite the newest bars.
 * Without this, a 4:1 split leaves older stored bars four times higher than newer ones and every
 * indicator, pattern, stop and target downstream reads a fake crash.</p>
 */
@Service
public class CorporateActionService {
    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);
    private static final double UNCHANGED_TOLERANCE = 0.002;
    private static final double SPLIT_THRESHOLD = 0.01;
    private static final double CONSISTENCY_TOLERANCE = 0.005;
    private static final int MAXIMUM_SPLIT_TERM = 50;

    private final JdbcTemplate jdbc;

    public CorporateActionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SplitAdjustment(long cutoffTimestamp, double factor, int numerator, int denominator) {
        public String label() {
            return numerator + ":" + denominator;
        }
    }

    /**
     * Compares completed bars that are both stored and freshly fetched. A run of the oldest
     * overlapping bars that all differ by the same clean split ratio, followed only by unchanged
     * bars, is a split. Anything less consistent is left alone and reported.
     *
     * @param completedBefore exclusive timestamp of the first bar that may still be forming
     */
    static Optional<SplitAdjustment> detect(List<MarketDataBar> fetched,
                                            Map<Long, Candle> stored,
                                            long completedBefore) {
        List<double[]> overlap = new ArrayList<>();
        fetched.stream()
                .filter(bar -> bar.timestamp() < completedBefore && bar.hasValidPrices())
                .sorted(Comparator.comparingLong(MarketDataBar::timestamp))
                .forEach(bar -> {
                    Candle candle = stored.get(bar.timestamp());
                    if (candle != null && candle.getClosePrice() != null
                            && Double.isFinite(candle.getClosePrice()) && candle.getClosePrice() > 0) {
                        overlap.add(new double[]{bar.timestamp(), candle.getClosePrice() / bar.close()});
                    }
                });
        if (overlap.size() < 3) return Optional.empty();

        int prefix = 0;
        while (prefix < overlap.size() && Math.abs(overlap.get(prefix)[1] - 1.0) > SPLIT_THRESHOLD) prefix++;
        if (prefix < 2) return Optional.empty();
        for (int index = prefix; index < overlap.size(); index++) {
            if (Math.abs(overlap.get(index)[1] - 1.0) > UNCHANGED_TOLERANCE) return Optional.empty();
        }
        double[] ratios = overlap.subList(0, prefix).stream().mapToDouble(row -> row[1]).sorted().toArray();
        double median = ratios[ratios.length / 2];
        for (double ratio : ratios) {
            if (Math.abs(ratio - median) / median > CONSISTENCY_TOLERANCE) return Optional.empty();
        }
        Optional<int[]> fraction = cleanFraction(median);
        if (fraction.isEmpty()) return Optional.empty();
        int numerator = fraction.get()[0];
        int denominator = fraction.get()[1];
        return Optional.of(new SplitAdjustment((long) overlap.get(prefix - 1)[0],
                (double) numerator / denominator, numerator, denominator));
    }

    /**
     * Splits and reverse splits use small whole-number terms (2:1, 3:2, 5:4, 1:10...). Ratios such
     * as 27:25 look like currency or listing changes and are deliberately not treated as splits.
     */
    static Optional<int[]> cleanFraction(double ratio) {
        int[] best = null;
        double bestError = Double.MAX_VALUE;
        for (int denominator = 1; denominator <= MAXIMUM_SPLIT_TERM; denominator++) {
            int numerator = (int) Math.round(ratio * denominator);
            if (numerator < 1 || numerator > MAXIMUM_SPLIT_TERM || numerator == denominator) continue;
            if (gcd(numerator, denominator) != 1) continue;
            boolean wholeSplit = numerator == 1 || denominator == 1;
            boolean fractionalSplit = Math.min(numerator, denominator) <= 4 && Math.max(numerator, denominator) <= 10;
            if (!wholeSplit && !fractionalSplit) continue;
            double error = Math.abs((double) numerator / denominator - ratio) / ratio;
            if (error < CONSISTENCY_TOLERANCE && error < bestError) {
                best = new int[]{numerator, denominator};
                bestError = error;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int gcd(int left, int right) {
        return right == 0 ? left : gcd(right, left % right);
    }

    /**
     * Rescales stored history and every stored price on the old basis. Returns false when this
     * split was already applied for the series.
     */
    @Transactional
    public boolean apply(String symbol, String interval, SplitAdjustment split) {
        List<Long> inserted = jdbc.queryForList("""
                insert into corporate_actions(symbol, time_interval, cutoff_timestamp, factor, numerator, denominator)
                values (?, ?, ?, ?, ?, ?)
                on conflict (symbol, time_interval, cutoff_timestamp) do nothing
                returning id
                """, Long.class, symbol, interval, split.cutoffTimestamp(), split.factor(),
                split.numerator(), split.denominator());
        if (inserted.isEmpty()) return false;
        double f = split.factor();
        long cutoff = split.cutoffTimestamp();
        int candles = jdbc.update("""
                update candles set open_price = open_price / ?, high_price = high_price / ?,
                    low_price = low_price / ?, close_price = close_price / ?,
                    volume = case when volume is null then null else round(volume * ?)::bigint end
                where symbol = ? and time_interval = ? and timestamp <= ?
                """, f, f, f, f, f, symbol, interval, cutoff);

        String ruleInterval = switch (interval.toLowerCase(Locale.ROOT)) {
            case "1d" -> "DAILY";
            case "1wk" -> "WEEKLY";
            case "1mo" -> "MONTHLY";
            default -> null;
        };
        int events = 0;
        if (ruleInterval != null) {
            events = rescaleSignals(symbol, ruleInterval, cutoff, f);
        }
        if ("1d".equalsIgnoreCase(interval)) {
            rescaleDemoTrades(symbol, cutoff, f);
            jdbc.update("delete from live_quote_cache where symbol = ?", symbol);
        }
        log.warn("Applied {} split adjustment to {} {} through {}: {} candles and {} signals rescaled",
                split.label(), symbol, interval, cutoff, candles, events);
        return true;
    }

    private int rescaleSignals(String symbol, String ruleInterval, long cutoff, double f) {
        String scope = """
                from alert_rules r join stock_assets a on a.id = r.stock_asset_id
                where e.alert_rule_id = r.id and a.ticker_symbol = ? and r.interval = ?
                  and e.signal_candle_timestamp <= ?
                """;
        int events = jdbc.update("""
                update alert_events e set
                    close_price = close_price / ?, pattern_high = pattern_high / ?, pattern_low = pattern_low / ?,
                    confirmation_trigger_price = confirmation_trigger_price / ?,
                    invalidation_price = invalidation_price / ?, trade_entry_price = trade_entry_price / ?,
                    stop_loss_price = stop_loss_price / ?, profit_target_price = profit_target_price / ?,
                    structural_stop_price = structural_stop_price / ?,
                    pre_circuit_breaker_stop_price = pre_circuit_breaker_stop_price / ?,
                    atr_circuit_breaker_value = atr_circuit_breaker_value / ?,
                    detection_close_price = detection_close_price / ?,
                    resolution_close_price = resolution_close_price / ?,
                    elliott_endpoint_price = elliott_endpoint_price / ?,
                    elliott_target_mid_price = elliott_target_mid_price / ?,
                    elliott_target_zone_low = elliott_target_zone_low / ?,
                    elliott_target_zone_high = elliott_target_zone_high / ?,
                    elliott_trade_resolution_close = elliott_trade_resolution_close / ?,
                    harmonic_endpoint_price = harmonic_endpoint_price / ?,
                    harmonic_stop_buffer_amount = harmonic_stop_buffer_amount / ?,
                    harmonic_stop_resolution_price = harmonic_stop_resolution_price / ?,
                    secondary_target_price = secondary_target_price / ?,
                    trade_resolution_price = trade_resolution_price / ?
                """ + scope, divisors(f, 23, symbol, ruleInterval, cutoff));

        // Pivot snapshots drive invalidation checks and chart overlays, so they move with the candles.
        record Snapshots(long id, String elliott, String harmonic) { }
        List<Snapshots> snapshots = jdbc.query("""
                select e.id, e.elliott_structure_snapshot, e.harmonic_points_snapshot
                from alert_events e join alert_rules r on r.id = e.alert_rule_id
                join stock_assets a on a.id = r.stock_asset_id
                where a.ticker_symbol = ? and r.interval = ? and e.signal_candle_timestamp <= ?
                  and (e.elliott_structure_snapshot is not null or e.harmonic_points_snapshot is not null)
                """, (row, number) -> new Snapshots(row.getLong(1), row.getString(2), row.getString(3)),
                symbol, ruleInterval, cutoff);
        for (Snapshots snapshot : snapshots) {
            jdbc.update("update alert_events set elliott_structure_snapshot = ?, harmonic_points_snapshot = ? where id = ?",
                    rescaleSnapshot(snapshot.elliott(), 2, f), rescaleSnapshot(snapshot.harmonic(), 2, f), snapshot.id());
        }

        jdbc.update("""
                update elliott_stage_trade_plans p set
                    entry_price = entry_price / ?, structural_stop_price = structural_stop_price / ?,
                    stop_loss_price = stop_loss_price / ?, stop_buffer = stop_buffer / ?,
                    hard_invalidation_price = hard_invalidation_price / ?, target_midpoint = target_midpoint / ?,
                    target_zone_low = target_zone_low / ?, target_zone_high = target_zone_high / ?,
                    target_trigger_price = target_trigger_price / ?,
                    resolution_close_price = resolution_close_price / ?,
                    secondary_target_price = secondary_target_price / ?,
                    resolution_fill_price = resolution_fill_price / ?
                from alert_events e join alert_rules r on r.id = e.alert_rule_id join stock_assets a on a.id = r.stock_asset_id
                where p.alert_event_id = e.id and a.ticker_symbol = ? and r.interval = ? and p.entry_timestamp <= ?
                """, divisors(f, 12, symbol, ruleInterval, cutoff));

        List<Long> sets = jdbc.queryForList("""
                select p.id from elliott_projection_sets p
                join alert_events e on e.id = p.alert_event_id join alert_rules r on r.id = e.alert_rule_id
                join stock_assets a on a.id = r.stock_asset_id
                where a.ticker_symbol = ? and r.interval = ? and p.source_timestamp <= ?
                """, Long.class, symbol, ruleInterval, cutoff);
        record Path(long id, int index, String path) { }
        for (Long set : sets) {
            jdbc.update("update elliott_projection_sets set source_price = source_price / ? where id = ?", f, set);
            List<Path> scenarios = jdbc.query("select id, projected_path from elliott_projection_scenarios where projection_set_id = ?",
                    (row, number) -> new Path(row.getLong(1), 0, row.getString(2)), set);
            for (Path scenario : scenarios) {
                jdbc.update("""
                        update elliott_projection_scenarios set target_zone_low = target_zone_low / ?,
                            target_zone_high = target_zone_high / ?, target_midpoint = target_midpoint / ?,
                            hard_invalidation_price = hard_invalidation_price / ?,
                            scenario_invalidation_price = scenario_invalidation_price / ?, projected_path = ?
                        where id = ?
                        """, f, f, f, f, f, rescaleSnapshot(scenario.path(), 2, f), scenario.id());
                List<Path> revisions = jdbc.query(
                        "select scenario_id, revision_index, projected_path from elliott_projection_revisions where scenario_id = ?",
                        (row, number) -> new Path(row.getLong(1), row.getInt(2), row.getString(3)), scenario.id());
                for (Path revision : revisions) {
                    jdbc.update("""
                            update elliott_projection_revisions set target_zone_low = target_zone_low / ?,
                                target_zone_high = target_zone_high / ?, projected_path = ?
                            where scenario_id = ? and revision_index = ?
                            """, f, f, rescaleSnapshot(revision.path(), 2, f), revision.id(), revision.index());
                }
            }
        }
        return events;
    }

    private void rescaleDemoTrades(String symbol, long cutoff, double f) {
        // A demo position bought before the split owns f times as many shares at 1/f the price.
        java.sql.Timestamp splitTakesEffect = java.sql.Timestamp.from(
                java.time.Instant.ofEpochSecond(cutoff).plus(java.time.Duration.ofDays(1)));
        jdbc.update("""
                update virtual_trades set entry_price = entry_price / ?,
                    quantity = case when quantity is null then null else quantity * ? end
                where stock_asset_id = (select id from stock_assets where ticker_symbol = ?) and entry_at < ?
                """, f, f, symbol, splitTakesEffect);
        jdbc.update("""
                update virtual_trades set exit_price = exit_price / ?
                where stock_asset_id = (select id from stock_assets where ticker_symbol = ?)
                  and exit_price is not null and exit_at < ?
                """, f, symbol, splitTakesEffect);
    }

    private static Object[] divisors(double factor, int count, Object... trailing) {
        Object[] arguments = new Object[count + trailing.length];
        java.util.Arrays.fill(arguments, 0, count, factor);
        System.arraycopy(trailing, 0, arguments, count, trailing.length);
        return arguments;
    }

    /** Rescales the price field of pipe-separated snapshot rows, leaving labels and timestamps intact. */
    static String rescaleSnapshot(String snapshot, int priceField, double factor) {
        if (snapshot == null || snapshot.isBlank()) return snapshot;
        return snapshot.lines().map(line -> {
            String[] fields = line.split("\\|", -1);
            if (fields.length <= priceField) return line;
            try {
                fields[priceField] = String.format(Locale.ROOT, "%.10f", Double.parseDouble(fields[priceField]) / factor);
            } catch (NumberFormatException ignored) {
                return line;
            }
            return String.join("|", fields);
        }).collect(java.util.stream.Collectors.joining("\n"));
    }
}
