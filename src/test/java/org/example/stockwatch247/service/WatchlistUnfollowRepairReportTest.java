package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prints the dry-run report from {@code tools/watchlist-unfollow-repair.sql} for the real schema,
 * read-only. Run with {@code -Dwatchlist.repair.report=true} (schema: {@code -Dbacktest.source.schema},
 * default {@code public}).
 */
@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "watchlist.repair.report", matches = "true")
class WatchlistUnfollowRepairReportTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void printsStaleWatchlistMonitoringRows() throws Exception {
        String schema = System.getProperty("backtest.source.schema", "public");
        if (!schema.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name.");
        String script = Files.readString(Path.of("tools/watchlist-unfollow-repair.sql"));
        String report = script.substring(script.indexOf("with stale as"), script.indexOf(";", script.indexOf("with stale as")));
        for (String table : new String[]{"watchlist_member_signals", "watchlists", "alert_rules", "technical_outlook_subscriptions"}) {
            report = report.replaceAll("\\b(from|join) " + table + "\\b", "$1 " + schema + "." + table);
        }
        System.out.println("=== Stale watchlist monitoring rows in schema " + schema + " ===");
        jdbc.query(report, row -> {
            System.out.printf("%s: %d rows, %d users, %d instruments%n", row.getString(1), row.getLong(2),
                    row.getLong(3), row.getLong(4));
        });
        System.out.printf("Context: %d watchlist signal rows, %d active rules, %d watchlists%n",
                jdbc.queryForObject("select count(*) from " + schema + ".watchlist_member_signals", Long.class),
                jdbc.queryForObject("select count(*) from " + schema + ".alert_rules where is_active", Long.class),
                jdbc.queryForObject("select count(*) from " + schema + ".watchlists", Long.class));
        System.out.println("=== end of report ===");
    }
}
