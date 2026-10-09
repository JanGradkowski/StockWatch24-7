import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Defaults to a read-only audit. --apply installs V74 and requeues only identified,
 * expired/original direct-mail failures. Does not run jobs or send messages. */
class RecoveryMaintenance {
    public static void main(String[] args) throws Exception {
        boolean apply = Arrays.asList(args).contains("--apply");
        var p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env"))) { p.load(reader); }
        String url = p.getProperty("DB_URL", "jdbc:postgresql://localhost:5432/uptodate_db");
        String user = p.getProperty("DB_MIGRATION_USERNAME",p.getProperty("DB_USERNAME","uptodate"));
        String password = p.getProperty("DB_MIGRATION_PASSWORD",p.getProperty("DB_PASSWORD"));
        var flyway = Flyway.configure().dataSource(url,user,password)
                .locations("filesystem:src/main/resources/db/migration").target("74").load();
        var pending = Arrays.stream(flyway.info().pending()).map(m -> m.getVersion().getVersion()).toList();
        if (!pending.isEmpty() && !pending.equals(List.of("74")))
            throw new IllegalStateException("Unexpected pending migration versions: " + pending);
        System.out.println("Pending migrations="+pending+" apply="+apply);
        if (apply && !pending.isEmpty()) flyway.migrate();
        try (var db=DriverManager.getConnection(url,user,password)) {
            db.setReadOnly(!apply);
            if (apply) {
                db.setAutoCommit(false);
                try (var query=db.prepareStatement("""
                    delete from asset_provider_symbols p using stock_assets a
                    where p.stock_asset_id=a.id and a.ticker_symbol='HD' and a.mic_code='XNYS'
                      and p.provider='TWELVE_DATA' and p.provider_symbol='HD' and p.mic_code='XLIM'
                    """)) {
                    System.out.println("Removed conflicting Home Depot alias="+query.executeUpdate());
                }
                try (var query=db.prepareStatement("""
                    update alert_check_jobs set status='PENDING',lease_until=null,
                        available_at=current_timestamp,updated_at=current_timestamp,
                        calculation_failures=0,last_error='Recovered after durable lifecycle email migration'
                    where status='FAILED' and last_error='Email could not be sent.'
                    """)) {
                    System.out.println("Reopened direct-mail failures="+query.executeUpdate());
                }
                // Fences any old owner through the status; the next claim increments attempts.
                try (var query=db.prepareStatement("""
                    update alert_check_jobs set status='PENDING',lease_until=null,
                        available_at=current_timestamp,updated_at=current_timestamp,
                        last_error='Recovered expired analysis lease after checkpoint migration'
                    where status='PROCESSING' and lease_until <= current_timestamp
                    """)) {
                    System.out.println("Recovered expired analysis leases="+query.executeUpdate());
                }
                db.commit();
                try (var query=db.prepareStatement("""
                    update alert_check_jobs set status='PENDING',lease_until=null,
                        available_at=current_timestamp,updated_at=current_timestamp,
                        calculation_failures=0,last_error='Recovered historical provider outage; future outages defer'
                    where status='FAILED' and last_error like 'Candle refresh failed:%'
                    """)) {
                    System.out.println("Recovered historical provider failures="+query.executeUpdate());
                }
                db.commit();
                try (var query=db.prepareStatement("""
                    update alert_check_jobs set status='PENDING',lease_until=null,
                        available_at=current_timestamp,updated_at=current_timestamp,
                        calculation_failures=0,last_error='Recovered historical notification identity conflict after publication guard'
                    where status='FAILED'
                      and last_error like '%duplicate key value violates unique constraint "uka98kccxxjcp7ho76x94ifi4lg"%'
                    """)) {
                    System.out.println("Recovered historical notification conflicts="+query.executeUpdate());
                }
                db.commit();
            }
            print(db,"Technical jobs","select status,count(*) from alert_check_jobs group by status order by status");
            print(db,"Failed classifications","""
                select case when last_error='Email could not be sent.' then 'DIRECT_MAIL'
                  when last_error like '%Email could not be sent.%' then 'AGGREGATED_MAIL_NEEDS_REVIEW'
                  when last_error='Quarantined named-watchlist integration fixture' then 'QUARANTINED_TEST_FIXTURE'
                  when last_error like '%duplicate key value violates unique constraint%' then 'HISTORICAL_DUPLICATE_EVENT'
                  else 'OTHER_REQUIRES_REVIEW' end,count(*)
                from alert_check_jobs where status='FAILED' group by 1 order by 1
                """);
            print(db,"Delivery states","""
                select case when delivered_at is not null then 'SMTP_ACCEPTED'
                  when expired_at is not null then 'EXPIRED' else 'PENDING' end,count(*)
                from email_outbox group by 1 order by 1
                """);
            print(db,"SMTP receipt integrity (accepted | retained payloads | missing event receipts | missing outlook receipts)","""
                select count(*) as accepted,
                  count(*) filter (where o.ciphertext is not null or o.iv is not null) as retained_payloads,
                  count(*) filter (where o.alert_event_id is not null and
                    (o.receipt='INITIAL' and e.initial_email_sent_at is null
                     or o.receipt='FOLLOW_UP' and e.follow_up_sent_at is null)) as missing_event_receipts,
                  count(*) filter (where o.outlook_notification_id is not null
                    and n.email_sent_at is null) as missing_outlook_receipts
                from email_outbox o
                left join alert_events e on e.id=o.alert_event_id
                left join technical_outlook_notifications n on n.id=o.outlook_notification_id
                where o.delivered_at is not null
                """);
            print(db,"Asset identity","select ticker_symbol,company_name,exchange,mic_code from stock_assets where ticker_symbol in ('HD','^GSPC','TPG0','CDR') order by ticker_symbol");
            print(db,"Provider aliases","""
                select a.ticker_symbol,p.provider,p.provider_symbol,p.mic_code
                from asset_provider_symbols p join stock_assets a on a.id=p.stock_asset_id
                where a.ticker_symbol in ('HD','^GSPC','TPG0','CDR') order by 1,2
                """);
        }
    }
    static void print(Connection db,String title,String sql)throws Exception {
        System.out.println(title+":");
        try(var s=db.createStatement();var rows=s.executeQuery(sql)) {
            while(rows.next()) {
                var values=new ArrayList<String>();
                for(int i=1;i<=rows.getMetaData().getColumnCount();i++) values.add(rows.getString(i));
                System.out.println(String.join(" | ",values));
            }
        }
    }
}
