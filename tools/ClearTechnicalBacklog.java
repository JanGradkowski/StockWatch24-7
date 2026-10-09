import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Explicit operator maintenance; does not start application workers or deliver mail. */
class ClearTechnicalBacklog {
    public static void main(String[] args) throws Exception {
        boolean apply = Arrays.asList(args).contains("--apply");
        var p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env"))) { p.load(reader); }
        String url = p.getProperty("DB_URL", "jdbc:postgresql://localhost:5432/uptodate_db");
        String user = p.getProperty("DB_MIGRATION_USERNAME", p.getProperty("DB_USERNAME", "uptodate"));
        String password = p.getProperty("DB_MIGRATION_PASSWORD", p.getProperty("DB_PASSWORD"));
        if (!apply) {
            try (var db = DriverManager.getConnection(url, user, password)) {
                db.setReadOnly(true);
                try (var s = db.createStatement()) {
                    for (String sql : List.of(
                            "select status,count(*) as jobs from alert_check_jobs group by status order by status",
                            "select cancelled_through from alert_schedule_control",
                            "select count(*) as unfinished_checkpoints from analysis_checkpoints where jsonb_array_length(coalesce(payload::jsonb->'pending','[]'::jsonb)) > 0")) {
                        try (var rows = s.executeQuery(sql)) {
                            while (rows.next()) {
                                var values = new LinkedHashMap<String,Object>();
                                for (int i=1;i<=rows.getMetaData().getColumnCount();i++)
                                    values.put(rows.getMetaData().getColumnLabel(i),rows.getObject(i));
                                System.out.println(values);
                            }
                        }
                    }
                }
            }
            return;
        }
        Flyway.configure().dataSource(url, user, password)
                .locations("filesystem:src/main/resources/db/migration").target("75").load().migrate();
        try (var db = DriverManager.getConnection(url, user, password)) {
            db.setAutoCommit(false);
            try (var s = db.createStatement()) {
                s.execute("lock table alert_check_jobs, alert_schedule_runs in exclusive mode");
                s.executeUpdate("""
                    insert into alert_schedule_control(id,cancelled_through) values(1,current_timestamp)
                    on conflict(id) do update set cancelled_through=excluded.cancelled_through
                    """);
                System.out.println("Cancelled outstanding technical jobs=" + s.executeUpdate("""
                    update alert_check_jobs set status='CANCELLED',lease_until=null,
                        last_error='Cancelled by operator before technical performance remediation',updated_at=current_timestamp
                    where status in ('PENDING','PROCESSING')
                    """));
                System.out.println("Removed unfinished analysis checkpoints=" + s.executeUpdate("""
                    delete from analysis_checkpoints where jsonb_array_length(coalesce(payload::jsonb->'pending','[]'::jsonb)) > 0
                    """));
                // Existing binaries consult this ledger. Record skipped cron instants, never completed jobs.
                for (var entry : Map.of("DAILY", "0 0 0 * * TUE-SAT", "WEEKLY", "0 0 0 * * SAT", "MONTHLY", "0 0 0 1 * *").entrySet()) {
                    var cron = org.springframework.scheduling.support.CronExpression.parse(entry.getValue());
                    var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Brussels"));
                    var next = cron.next(now.minusDays(370));
                    java.time.ZonedDateTime latest = null;
                    while (next != null && !next.isAfter(now)) { latest=next; next=cron.next(next); }
                    try (var q=db.prepareStatement("insert into alert_schedule_runs(interval,scheduled_for) values(?,?) on conflict do nothing")) {
                        q.setString(1,entry.getKey()); q.setObject(2,latest.toOffsetDateTime()); q.executeUpdate();
                    }
                }
                try (var rows=s.executeQuery("select count(*) from alert_check_jobs where status in ('PENDING','PROCESSING')")) {
                    rows.next(); if(rows.getInt(1)!=0) throw new IllegalStateException("Outstanding jobs remain");
                    System.out.println("Verified outstanding technical jobs=0");
                }
                try (var rows=s.executeQuery("select cancelled_through from alert_schedule_control")) {
                    rows.next(); System.out.println("Cancellation cutoff="+rows.getString(1));
                }
            }
            db.commit();
        }
    }
}
