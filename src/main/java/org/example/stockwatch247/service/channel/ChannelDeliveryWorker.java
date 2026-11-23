package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.BackgroundJobDispatcher;
import org.example.stockwatch247.service.SecurityCryptoService;
import org.example.stockwatch247.service.channel.ChannelSender.Item;
import org.example.stockwatch247.service.channel.ChannelSender.Mode;
import org.example.stockwatch247.service.channel.ChannelSender.Outgoing;
import org.example.stockwatch247.service.channel.ChannelSender.SendResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Sends what the channel outbox holds. Each pass picks channels with due alerts, claims one channel at a time by
 * moving its {@code available_at} forward (one instance sends to a channel at a time, in order and paced), leases
 * the due rows with an owner fence as the email outbox does, and sends outside any transaction. Up to 16 channels
 * are sent to at once on virtual threads. Chats and push run on one dispatcher queue and webhooks on another, so
 * a slow receiver never holds up a Telegram message.
 */
@Component
public class ChannelDeliveryWorker {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelDeliveryWorker.class);
    static final Set<ChannelType> CHATS_AND_PUSH = EnumSet.of(ChannelType.TELEGRAM, ChannelType.DISCORD, ChannelType.PUSH);
    static final Set<ChannelType> WEBHOOKS = EnumSet.of(ChannelType.WEBHOOK);
    static final int CHANNELS_PER_PASS = 50;
    static final int PARALLEL_SENDS = 16;
    static final int CLAIM_LIMIT = 50;
    /** Discord takes at most 10 embeds per message. */
    static final int GROUP_LIMIT = 10;
    static final int WEBHOOK_ATTEMPTS = 8;
    static final int FAILING_AFTER = 8;
    static final Duration LATE_AFTER = Duration.ofHours(1);
    static final Duration CONFIG_PAUSE = Duration.ofMinutes(15);
    /** A push channel that already got this many notifications in the last hour gets a count instead. */
    static final int PUSH_HOURLY_BEFORE_COUNT = 20;
    private static final DateTimeFormatter QUEUED_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final ChannelStore store;
    private final SecurityCryptoService crypto;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Map<ChannelType, ChannelSender> senders = new EnumMap<>(ChannelType.class);
    private final ChannelNotices notices;
    private final ZoneId zone;
    private final boolean enabled;

    @Autowired(required = false)
    private BackgroundJobDispatcher dispatcher;
    @Autowired(required = false)
    private ChannelDeliveryFilter filter;
    @Value("${alert-channels.worker-enabled:true}")
    private boolean workerEnabled = true;

    @Autowired
    public ChannelDeliveryWorker(JdbcTemplate jdbc, ChannelStore store, SecurityCryptoService crypto, ObjectMapper mapper,
                                 PlatformTransactionManager manager,
                                 org.springframework.beans.factory.ObjectProvider<ChannelSender> senders,
                                 ChannelNotices notices,
                                 @Value("${alerts.email.time-zone:${alerts.schedule.zone:Europe/Brussels}}") String zone,
                                 @Value("${alert-channels.enabled:false}") boolean enabled) {
        this(jdbc, store, crypto, mapper, manager, senders.orderedStream().toList(), notices, zone, enabled);
    }

    ChannelDeliveryWorker(JdbcTemplate jdbc, ChannelStore store, SecurityCryptoService crypto, ObjectMapper mapper,
                          PlatformTransactionManager manager, List<ChannelSender> senders, ChannelNotices notices,
                          String zone, boolean enabled) {
        this.jdbc = jdbc;
        this.store = store;
        this.crypto = crypto;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        senders.forEach(sender -> this.senders.put(sender.type(), sender));
        this.notices = notices;
        this.zone = ZoneId.of(zone);
        this.enabled = enabled;
    }

    public ChannelSender sender(ChannelType type) {
        ChannelSender sender = senders.get(type);
        return sender != null && sender.ready() ? sender : null;
    }

    @Scheduled(fixedDelayString = "${alert-channels.worker-delay-ms:2000}",
            initialDelayString = "${alert-channels.worker-initial-delay-ms:15000}")
    public void dispatch() {
        if (!enabled || !workerEnabled || dispatcher == null) return;
        dispatcher.submit("channels", () -> deliver(CHATS_AND_PUSH));
        dispatcher.submit("webhooks", () -> deliver(WEBHOOKS));
    }

    /** One pass over channels of these types with due alerts; returns how many channels were sent to. */
    public int deliver(Set<ChannelType> types) {
        String ready = types.stream().filter(type -> sender(type) != null).map(Enum::name)
                .collect(Collectors.joining(",", "{", "}"));
        if (ready.equals("{}")) return 0;
        List<Long> channels = jdbc.queryForList("""
                select c.id from alert_channels c
                where c.status = 'ACTIVE' and c.channel_type = any(cast(? as text[])) and c.available_at <= current_timestamp
                  and not exists (select 1 from users u where u.id = c.user_id and u.deletion_requested_at is not null)
                  and (c.provider_scope is null or not exists (select 1 from alert_channel_provider_state p
                       where p.scope = c.provider_scope and p.blocked_until > current_timestamp))
                  and exists (select 1 from alert_channel_deliveries d where d.channel_id = c.id
                       and d.delivered_at is null and d.expired_at is null and not d.held
                       and d.available_at <= current_timestamp and d.expires_at > current_timestamp
                       and (d.lease_until is null or d.lease_until < current_timestamp))
                order by c.available_at limit ?
                """, Long.class, ready, CHANNELS_PER_PASS);
        if (channels.isEmpty()) return 0;
        var sent = new java.util.concurrent.atomic.AtomicInteger();
        Semaphore gate = new Semaphore(PARALLEL_SENDS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long channel : channels) {
                executor.submit(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        if (deliverChannel(channel)) sent.incrementAndGet();
                    } catch (RuntimeException failure) {
                        log.warn("Channel delivery pass failed: channel={} failureType={}", channel,
                                failure.getClass().getSimpleName());
                    } finally {
                        gate.release();
                    }
                });
            }
        }
        return sent.get();
    }

    record Row(UUID id, String groupKey, AlertKind kind, Instant createdAt, int partsSent, Long alertEventId,
               String ciphertext, String iv, boolean sourceDeleted, int attempts) { }

    record Claim(ChannelStore.Channel channel, Mode mode, List<Row> rows) {
        List<UUID> ids() { return rows.stream().map(Row::id).toList(); }
    }

    /** Claims and sends what is due for one channel; false when another instance had it or nothing was due. */
    boolean deliverChannel(long channelId) {
        UUID owner = UUID.randomUUID();
        Claim claim = transactions.execute(status -> claim(channelId, owner));
        if (claim == null) return false;
        ChannelStore.Channel channel = claim.channel();
        ChannelSender sender = sender(channel.type());
        if (sender == null) {
            release(claim.ids(), owner);
            return false;
        }
        List<Item> items = new ArrayList<>();
        List<UUID> keep = new ArrayList<>();
        List<UUID> filtered = new ArrayList<>();
        List<UUID> unreadable = new ArrayList<>();
        for (Row row : claim.rows()) {
            ChannelMessage message;
            try {
                message = mapper.readValue(crypto.decrypt(row.ciphertext(), row.iv(), "channel-delivery:" + row.id()),
                        ChannelMessage.class);
            } catch (RuntimeException failure) {
                unreadable.add(row.id());
                continue;
            }
            if (row.kind() == null || !channel.accepts(row.kind(), message.direction()) || row.sourceDeleted()
                    || filter != null && !filter.allows(channel, row.alertEventId(), message)) {
                filtered.add(row.id());
                continue;
            }
            keep.add(row.id());
            items.add(new Item(row.id(), message, row.createdAt(), late(row.createdAt()), replyTo(channel, row)));
        }
        if (!unreadable.isEmpty()) {
            log.warn("Channel deliveries could not be read and were dropped: channel={} count={}", channelId, unreadable.size());
            finish(unreadable, owner, "REJECTED", null);
        }
        finish(filtered, owner, "FILTERED", null);
        if (items.isEmpty()) return false;
        // Re-check the lease after decrypting: a long pause may have let another instance reclaim the rows.
        if (renewLease(keep, owner) != keep.size()) {
            release(keep, owner);
            return false;
        }
        Mode mode = claim.mode() == Mode.GROUP && items.size() == 1 ? Mode.SINGLE : claim.mode();
        int partsSent = claim.rows().stream().filter(row -> keep.contains(row.id())).mapToInt(Row::partsSent).min().orElse(0);
        var target = new ChannelSender.Target(channel.id(), channel.publicId(), channel.userId(), channel.type(),
                channel.label(), channel.providerScope(), store.config(channel), channel.language());
        SendResult result;
        try {
            result = sender.send(target, new Outgoing(mode, items, partsSent),
                    (parts, providerId) -> recordParts(keep, owner, parts, providerId));
        } catch (RuntimeException failure) {
            log.warn("Channel sender failed: channel={} type={} failureType={}", channelId, channel.type(),
                    failure.getClass().getSimpleName());
            result = new SendResult.Retry("ERROR", null);
        }
        apply(channel, keep, owner, result);
        return result instanceof SendResult.Delivered;
    }

    private Claim claim(long channelId, UUID owner) {
        int paced = jdbc.update("""
                update alert_channels set available_at = current_timestamp + (? * interval '1 millisecond')
                where id = ? and status = 'ACTIVE' and available_at <= current_timestamp
                """, paceFor(channelId), channelId);
        if (paced != 1) return null;
        ChannelStore.Channel channel = store.byId(channelId).orElse(null);
        if (channel == null) return null;
        List<Row> due = jdbc.query("""
                select d.id, d.group_key, d.alert_kind, d.created_at, d.parts_sent, d.alert_event_id, d.ciphertext, d.iv,
                    d.attempts,
                    (d.alert_event_id is not null and exists (select 1 from alert_events e
                        where e.id = d.alert_event_id and e.deleted_at is not null))
                    or (d.custom_alert_event_id is not null and exists (select 1 from custom_alert_events e
                        where e.id = d.custom_alert_event_id and e.deleted_at is not null))
                    or (d.insider_delivery_id is not null and exists (select 1 from insider_trade_deliveries e
                        where e.id = d.insider_delivery_id and e.deleted_at is not null))
                    or (d.congressional_delivery_id is not null and exists (select 1 from congressional_trade_deliveries e
                        where e.id = d.congressional_delivery_id and e.deleted_at is not null)) as source_deleted
                from alert_channel_deliveries d
                where d.channel_id = ? and d.delivered_at is null and d.expired_at is null and not d.held
                  and d.available_at <= current_timestamp and d.expires_at > current_timestamp
                  and (d.lease_until is null or d.lease_until < current_timestamp)
                order by d.available_at, d.created_at, d.id
                limit ? for update of d skip locked
                """, (rs, index) -> new Row(rs.getObject("id", UUID.class), rs.getString("group_key"),
                AlertKind.parse(rs.getString("alert_kind")), rs.getTimestamp("created_at").toInstant(),
                rs.getInt("parts_sent"), rs.getObject("alert_event_id", Long.class), rs.getString("ciphertext"),
                rs.getString("iv"), rs.getBoolean("source_deleted"), rs.getInt("attempts")), channelId, CLAIM_LIMIT + 1);
        if (due.isEmpty()) return null;
        Mode mode;
        List<Row> picked;
        boolean countInstead = channel.type() == ChannelType.PUSH && deliveredLastHour(channelId) >= PUSH_HOURLY_BEFORE_COUNT;
        if (due.size() > channel.type().rollUpAbove() || countInstead && due.size() > 1) {
            mode = Mode.ROLL_UP;
            picked = due.subList(0, Math.min(CLAIM_LIMIT, due.size()));
        } else {
            Row first = due.getFirst();
            if (channel.type() == ChannelType.WEBHOOK || first.groupKey() == null) {
                mode = Mode.SINGLE;
                picked = List.of(first);
            } else {
                mode = Mode.GROUP;
                picked = due.stream().filter(row -> first.groupKey().equals(row.groupKey())).limit(GROUP_LIMIT).toList();
            }
        }
        jdbc.update("update alert_channel_deliveries set owner = ?, lease_until = current_timestamp + interval '2 minutes', "
                + "attempts = attempts + 1 where id in (" + placeholders(picked.size()) + ")",
                prepend(owner, picked.stream().map(Row::id).toList()));
        return new Claim(channel, mode, picked);
    }

    private long paceFor(long channelId) {
        String type = jdbc.queryForObject("select channel_type from alert_channels where id = ?", String.class, channelId);
        ChannelType parsed = ChannelType.parse(type);
        return parsed == null ? 1000 : parsed.pacing().toMillis();
    }

    private int deliveredLastHour(long channelId) {
        Integer count = jdbc.queryForObject("select count(*) from alert_channel_deliveries where channel_id = ? "
                + "and delivered_at > current_timestamp - interval '1 hour'", Integer.class, channelId);
        return count == null ? 0 : count;
    }

    private String late(Instant queuedAt) {
        return queuedAt.isBefore(Instant.now().minus(LATE_AFTER)) ? QUEUED_TIME.format(queuedAt.atZone(zone)) : null;
    }

    /** A follow-up replies to the message that announced its signal on this channel, where the provider supports it. */
    private String replyTo(ChannelStore.Channel channel, Row row) {
        if (channel.type() != ChannelType.TELEGRAM || row.alertEventId() == null || row.kind() == null || row.kind().signal())
            return null;
        List<String> found = jdbc.queryForList("""
                select provider_message_id from alert_channel_deliveries where channel_id = ? and alert_event_id = ?
                  and provider_message_id is not null and id <> ? order by created_at limit 1
                """, String.class, channel.id(), row.alertEventId(), row.id());
        return found.isEmpty() ? null : found.getFirst();
    }

    void apply(ChannelStore.Channel channel, List<UUID> ids, UUID owner, SendResult result) {
        switch (result) {
            case SendResult.Delivered delivered -> {
                finish(ids, owner, "DELIVERED", delivered.providerMessageId());
                jdbc.update("""
                        update alert_channels set last_success_at = current_timestamp, consecutive_failures = 0,
                            first_failure_at = null, last_failure_code = null where id = ?
                        """, channel.id());
                if (delivered.pace() != null) pace(channel.id(), delivered.pace());
            }
            case SendResult.Retry retry -> {
                retry(channel, ids, owner, retry.after());
                failed(channel, retry.code());
            }
            case SendResult.RateLimited limited -> {
                Duration after = positive(limited.after());
                wait(ids, owner, after);
                pace(channel.id(), after);
                if (limited.scope() != null) block(limited.scope(), after, limited.code());
            }
            case SendResult.ChannelGone gone -> {
                finish(ids, owner, "CHANNEL_GONE", null);
                if (store.stop(channel.id(), ChannelStatus.REVOKED, gone.code()))
                    notices.stopped(channel, ChannelStatus.REVOKED, gone.code());
            }
            case SendResult.Rejected rejected -> {
                finish(ids, owner, rejected.channelFault() ? "FAILED" : "REJECTED", null);
                if (rejected.channelFault()) failed(channel, rejected.code());
                else log.warn("Channel message rejected: channel={} type={} code={}", channel.id(), channel.type(),
                        rejected.code());
            }
            case SendResult.ConfigError error -> {
                wait(ids, owner, CONFIG_PAUSE);
                if (channel.providerScope() != null) block(channel.providerScope(), CONFIG_PAUSE, error.code());
                log.error("Alert channel provider misconfigured: type={} code={}", channel.type(), error.code());
            }
        }
    }

    private static Duration positive(Duration after) {
        return after == null || after.isNegative() || after.isZero() ? Duration.ofSeconds(1) : after;
    }

    private void finish(Collection<UUID> ids, UUID owner, String outcome, String providerMessageId) {
        if (ids.isEmpty()) return;
        boolean delivered = outcome.equals("DELIVERED");
        List<Object> args = new ArrayList<>(List.of(delivered, delivered, outcome, providerMessageId == null ? "" : providerMessageId));
        args.addAll(ids);
        args.add(owner);
        jdbc.update("""
                update alert_channel_deliveries set
                    delivered_at = case when ? then current_timestamp else delivered_at end,
                    expired_at = case when ? then expired_at else current_timestamp end,
                    outcome = ?, provider_message_id = coalesce(nullif(?, ''), provider_message_id),
                    ciphertext = null, iv = null, owner = null, lease_until = null
                where id in (""" + placeholders(ids.size()) + ") and owner = ?", args.toArray());
    }

    private void retry(ChannelStore.Channel channel, Collection<UUID> ids, UUID owner, Duration after) {
        if (ids.isEmpty()) return;
        boolean capped = channel.type() == ChannelType.WEBHOOK;
        List<Object> args = new ArrayList<>(List.of(capped, WEBHOOK_ATTEMPTS, capped, WEBHOOK_ATTEMPTS,
                capped, WEBHOOK_ATTEMPTS, capped, WEBHOOK_ATTEMPTS, after == null ? -1L : after.toMillis(), after == null ? -1L : after.toMillis()));
        args.addAll(ids);
        args.add(owner);
        jdbc.update("""
                update alert_channel_deliveries set owner = null, lease_until = null,
                    expired_at = case when ? and attempts >= ? then current_timestamp else null end,
                    outcome = case when ? and attempts >= ? then 'FAILED' else null end,
                    ciphertext = case when ? and attempts >= ? then null else ciphertext end,
                    iv = case when ? and attempts >= ? then null else iv end,
                    available_at = case when ? >= 0 then current_timestamp + (? * interval '1 millisecond')
                        else current_timestamp + ((least(3600, 15 * power(2, least(attempts, 8))) + floor(random() * 6))
                            * interval '1 second') end
                where id in (""" + placeholders(ids.size()) + ") and owner = ?", args.toArray());
    }

    /** Back in the queue after {@code after}, without counting the attempt. */
    private void wait(Collection<UUID> ids, UUID owner, Duration after) {
        if (ids.isEmpty()) return;
        List<Object> args = new ArrayList<>(List.of(after.toMillis()));
        args.addAll(ids);
        args.add(owner);
        jdbc.update("""
                update alert_channel_deliveries set owner = null, lease_until = null, attempts = greatest(0, attempts - 1),
                    available_at = current_timestamp + (? * interval '1 millisecond')
                where id in (""" + placeholders(ids.size()) + ") and owner = ?", args.toArray());
    }

    private void release(Collection<UUID> ids, UUID owner) {
        wait(ids, owner, Duration.ZERO);
    }

    private int renewLease(Collection<UUID> ids, UUID owner) {
        if (ids.isEmpty()) return 0;
        List<Object> args = new ArrayList<>(ids);
        args.add(owner);
        return jdbc.update("update alert_channel_deliveries set lease_until = current_timestamp + interval '2 minutes' "
                + "where id in (" + placeholders(ids.size()) + ") and owner = ? and lease_until > current_timestamp",
                args.toArray());
    }

    private void recordParts(Collection<UUID> ids, UUID owner, int parts, String providerMessageId) {
        List<Object> args = new ArrayList<>(List.of(parts, providerMessageId == null ? "" : providerMessageId));
        args.addAll(ids);
        args.add(owner);
        jdbc.update("""
                update alert_channel_deliveries set parts_sent = ?,
                    provider_message_id = coalesce(provider_message_id, nullif(?, '')),
                    lease_until = current_timestamp + interval '2 minutes'
                where id in (""" + placeholders(ids.size()) + ") and owner = ?", args.toArray());
    }

    private void pace(long channelId, Duration after) {
        jdbc.update("update alert_channels set available_at = greatest(available_at, current_timestamp "
                + "+ (? * interval '1 millisecond')) where id = ?", after.toMillis(), channelId);
    }

    /** Blocks every channel of a provider, shared by all instances. */
    void block(String scope, Duration after, String reason) {
        jdbc.update("""
                insert into alert_channel_provider_state(scope, blocked_until, reason)
                values (?, current_timestamp + (? * interval '1 millisecond'), ?)
                on conflict (scope) do update set blocked_until = greatest(alert_channel_provider_state.blocked_until,
                    excluded.blocked_until), reason = excluded.reason, updated_at = current_timestamp
                """, scope, after.toMillis(), reason == null ? null : reason.substring(0, Math.min(40, reason.length())));
    }

    public boolean blocked(String scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from alert_channel_provider_state "
                + "where scope = ? and blocked_until > current_timestamp)", Boolean.class, scope));
    }

    /** Counts a failure; a channel failing for more than a day stops, and its owner is told once. */
    private void failed(ChannelStore.Channel channel, String code) {
        List<Boolean> stop = jdbc.query("""
                update alert_channels set consecutive_failures = consecutive_failures + 1,
                    first_failure_at = coalesce(first_failure_at, current_timestamp),
                    last_failure_at = current_timestamp, last_failure_code = ?
                where id = ? and status = 'ACTIVE'
                returning consecutive_failures >= ? and first_failure_at <= current_timestamp - interval '24 hours'
                """, (rs, row) -> rs.getBoolean(1), code == null ? null : code.substring(0, Math.min(40, code.length())),
                channel.id(), FAILING_AFTER);
        if (!stop.isEmpty() && stop.getFirst() && store.stop(channel.id(), ChannelStatus.FAILING, code)) {
            notices.stopped(channel, ChannelStatus.FAILING, code);
        }
    }

    /**
     * Hourly: rows past their lifetime expire unsent, finished rows go after 30 days, removed channels after 30 days
     * (their page explains them until then), and old provider blocks are forgotten.
     */
    @Scheduled(cron = "${alert-channels.cleanup-cron:0 7 * * * *}")
    public void cleanup() {
        try {
            jdbc.update("""
                    update alert_channel_deliveries set expired_at = current_timestamp, outcome = 'EXPIRED',
                        ciphertext = null, iv = null, owner = null, lease_until = null
                    where delivered_at is null and expired_at is null and expires_at <= current_timestamp
                      and (lease_until is null or lease_until <= current_timestamp)
                    """);
            jdbc.update("delete from alert_channel_deliveries where coalesce(delivered_at, expired_at) "
                    + "< current_timestamp - interval '30 days'");
            jdbc.update("delete from alert_channels where status = 'REVOKED' and disabled_at < current_timestamp - interval '30 days'");
            jdbc.update("delete from alert_channel_provider_state where blocked_until < current_timestamp - interval '1 day'");
        } catch (RuntimeException failure) {
            log.warn("Alert channel cleanup deferred to the next run: {}", failure.getClass().getSimpleName());
        }
    }

    static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] prepend(Object first, List<?> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }
}
