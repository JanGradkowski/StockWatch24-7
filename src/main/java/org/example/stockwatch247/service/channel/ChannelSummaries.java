package org.example.stockwatch247.service.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Summaries for chat channels set to "One summary per check run", with the email summary's timing: signals, their
 * follow-ups and outlook changes wait while the account's checks are busy, and go out together once none has arrived
 * for 10 minutes, or an hour after the first. Releasing them gives them one group key; the worker sends a group whose
 * key starts with "summary:" as one list.
 */
@Component
public class ChannelSummaries {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelSummaries.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    @Value("${email-digest.quiet-seconds:600}")
    long quietSeconds = 600;
    @Value("${email-digest.max-wait-seconds:3600}")
    long maxWaitSeconds = 3600;

    public ChannelSummaries(JdbcTemplate jdbc, @Value("${alert-channels.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /** Releases every channel's summary that is ready; returns how many alerts were released. */
    @Scheduled(fixedDelayString = "${email-digest.delay-ms:60000}", initialDelayString = "${email-digest.initial-delay-ms:30000}")
    public int flush() {
        if (!enabled) return 0;
        try {
            return jdbc.update("""
                    with ready as (
                        select channel_id, min(id::text) as first_id from alert_channel_deliveries
                        where held and delivered_at is null and expired_at is null
                        group by channel_id
                        having max(created_at) <= current_timestamp - (? * interval '1 second')
                            or min(created_at) <= current_timestamp - (? * interval '1 second')
                    )
                    update alert_channel_deliveries d set held = false, available_at = current_timestamp,
                        group_key = 'summary:' || d.channel_id || ':' || r.first_id
                    from ready r
                    where d.channel_id = r.channel_id and d.held and d.delivered_at is null and d.expired_at is null
                    """, quietSeconds, maxWaitSeconds);
        } catch (RuntimeException failure) {
            log.warn("Channel summaries deferred to the next run: {}", failure.getClass().getSimpleName());
            return 0;
        }
    }

    /** A channel switched back to one message per alert: what it was holding goes now, as one summary. */
    public void releaseNow(long channelId) {
        jdbc.update("""
                update alert_channel_deliveries set held = false, available_at = current_timestamp,
                    group_key = 'summary:' || channel_id || ':' || (select min(id::text) from alert_channel_deliveries
                        where channel_id = ? and held and delivered_at is null and expired_at is null)
                where channel_id = ? and held and delivered_at is null and expired_at is null
                """, channelId, channelId);
    }
}
