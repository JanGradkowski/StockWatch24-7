package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.i18n.ServerMessages;
import org.example.stockwatch247.service.SecurityCryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Hands an alert to every channel of the account that takes it. It runs in the caller's transaction, next to the
 * alert's event and email, so an alert reaches its channels exactly when it is recorded. Each channel gets its own
 * row with the message in the channel's language, encrypted; the delivery worker sends it.
 */
@Service
public class ChannelFanout {
    private final JdbcTemplate jdbc;
    private final ChannelStore store;
    private final SecurityCryptoService crypto;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String baseUrl;

    public ChannelFanout(JdbcTemplate jdbc, ChannelStore store, SecurityCryptoService crypto, ObjectMapper mapper,
                         @Value("${alert-channels.enabled:false}") boolean enabled,
                         @Value("${app.public-base-url:http://localhost:8080}") String baseUrl) {
        this.jdbc = jdbc;
        this.store = store;
        this.crypto = crypto;
        this.mapper = mapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public boolean enabled() { return enabled; }

    /** True when at least one active channel of the account would take this alert. */
    public boolean wants(long userId, AlertKind kind, String direction) {
        return enabled && store.anyAccepting(userId, kind, direction) && !pendingDeletion(userId);
    }

    public boolean wants(AlertNotice notice) {
        return wants(notice.userId(), notice.kind(), notice.direction());
    }

    /** Queues the alert for every channel that takes it; returns how many rows were written. */
    @Transactional
    public int publish(AlertNotice notice) {
        return publish(notice, Duration.ZERO);
    }

    /**
     * As {@link #publish(AlertNotice)}, with the rows held back for {@code hold}: new signals wait while their stock
     * check is still running, so signals that end at the same point can go out as one message.
     */
    @Transactional
    public int publish(AlertNotice notice, Duration hold) {
        if (!enabled) return 0;
        List<ChannelStore.Channel> channels = store.accepting(notice.userId(), notice.kind(), notice.direction());
        if (channels.isEmpty() || pendingDeletion(notice.userId())) return 0;
        Locale accountLocale = accountLocale(notice.userId());
        Instant now = Instant.now();
        Map<Locale, ChannelMessage> rendered = new HashMap<>();
        int written = 0;
        for (ChannelStore.Channel channel : channels) {
            AppLanguage chosen = AppLanguage.fromCode(channel.language());
            Locale locale = chosen == null ? accountLocale : chosen.locale();
            ChannelMessage message = rendered.computeIfAbsent(locale, key -> render(notice, key, now));
            UUID id = UUID.randomUUID();
            var encrypted = crypto.encrypt(mapper.writeValueAsString(message), "channel-delivery:" + id);
            String sourceColumn = notice.source() == null ? null : notice.source().column();
            String sql = "insert into alert_channel_deliveries(id, channel_id, deduplication_key, alert_kind, group_key, "
                    + (sourceColumn == null ? "" : sourceColumn + ", ")
                    + "ciphertext, iv, available_at, expires_at) values (?, ?, ?, ?, ?, "
                    + (sourceColumn == null ? "" : "?, ")
                    + "?, ?, ?, ?) on conflict (channel_id, deduplication_key) do nothing";
            Timestamp available = Timestamp.from(now.plus(hold));
            Timestamp expires = Timestamp.from(now.plus(channel.type().lifetime()));
            written += sourceColumn == null
                    ? jdbc.update(sql, id, channel.id(), notice.deduplicationKey(), notice.kind().name(), notice.groupKey(),
                        encrypted.ciphertext(), encrypted.iv(), available, expires)
                    : jdbc.update(sql, id, channel.id(), notice.deduplicationKey(), notice.kind().name(), notice.groupKey(),
                        notice.sourceId(), encrypted.ciphertext(), encrypted.iv(), available, expires);
        }
        return written;
    }

    /** Lets held rows go: their check finished, so whatever ends at the same point has been queued. */
    public int release(java.util.Collection<String> deduplicationKeys, long userId) {
        if (!enabled || deduplicationKeys.isEmpty()) return 0;
        return jdbc.update("""
                update alert_channel_deliveries d set available_at = current_timestamp
                from alert_channels c
                where c.id = d.channel_id and c.user_id = ? and d.deduplication_key = any(cast(? as text[]))
                  and d.delivered_at is null and d.expired_at is null and d.available_at > current_timestamp
                """, userId, textArray(deduplicationKeys));
    }

    ChannelMessage render(AlertNotice notice, Locale locale, Instant now) {
        String path = notice.viewPath() == null || !notice.viewPath().startsWith("/") ? "/home" : notice.viewPath();
        return new ChannelMessage(notice.kind().name(), AppLanguage.of(locale).code(),
                ServerMessages.translate(notice.subject(), locale), ServerMessages.translateLines(notice.body(), locale),
                baseUrl + path, notice.symbol(), notice.direction(), notice.facts(), notice.groupKey(),
                notice.source() == null ? null : notice.source().name(), notice.sourceId(), now);
    }

    private boolean pendingDeletion(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from users where id = ? and deletion_requested_at is not null)", Boolean.class, userId));
    }

    private Locale accountLocale(long userId) {
        List<Locale> found = jdbc.query("select preferred_language, detected_language from users where id = ?",
                (rs, row) -> {
                    AppLanguage saved = AppLanguage.fromCode(rs.getString(1));
                    AppLanguage seen = AppLanguage.fromCode(rs.getString(2));
                    return (saved != null ? saved : seen != null ? seen : AppLanguage.DEFAULT).locale();
                }, userId);
        return found.isEmpty() ? AppLanguage.DEFAULT.locale() : found.getFirst();
    }

    static String textArray(java.util.Collection<String> values) {
        StringBuilder array = new StringBuilder("{");
        for (String value : values) {
            if (array.length() > 1) array.append(',');
            array.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return array.append('}').toString();
    }
}
