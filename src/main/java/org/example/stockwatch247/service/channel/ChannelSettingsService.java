package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.i18n.LanguagePreferenceService;
import org.example.stockwatch247.i18n.Messages;
import org.example.stockwatch247.model.SecurityEvent;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.SecurityEventRepository;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.AlertNotificationService;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.channel.ChannelSender.SendResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the Alert delivery settings page does with an account's channels: lists them with their recent activity,
 * adds them within the quotas, sends test messages, pauses, edits and removes them. Every new channel is recorded as
 * a security event and announced by email, so a channel someone else completed does not go unnoticed.
 */
@Service
public class ChannelSettingsService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelSettingsService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm");

    public enum TestOutcome { SENT, NOT_AVAILABLE, TOO_MANY, GONE, SLOW_DOWN, BLOCKED, FAILED }

    public record TestResult(TestOutcome outcome, String code) { }

    /** One channel on the page: the channel, 30-day counts and its latest deliveries. */
    public record ChannelView(ChannelStore.Channel channel, int delivered, int waiting, int failed,
                              String lastSent, String lastFailure, List<DeliveryView> recent) {
        public String id() { return channel.publicId().toString(); }
        public boolean hasKind(String kind) { return channel.kinds().contains(AlertKind.valueOf(kind)); }
        public boolean hasDirection(String direction) { return channel.directions().contains(direction); }
    }

    public record DeliveryView(String when, String kind, String outcome) { }

    private final ChannelStore store;
    private final ChannelDeliveryWorker worker;
    private final JdbcTemplate jdbc;
    private final RequestRateLimiter limiter;
    private final SecurityEventRepository events;
    private final ZoneId zone;
    private final String baseUrl;
    private final boolean enabled;

    @Autowired(required = false)
    private AnalysisPreferencesService preferences;
    @Autowired(required = false)
    private AlertNotificationService notifications;
    @Autowired(required = false)
    private Messages messages;

    public ChannelSettingsService(ChannelStore store, ChannelDeliveryWorker worker, JdbcTemplate jdbc,
                                  RequestRateLimiter limiter, SecurityEventRepository events,
                                  @Value("${alerts.email.time-zone:${alerts.schedule.zone:Europe/Brussels}}") String zone,
                                  @Value("${app.public-base-url:http://localhost:8080}") String baseUrl,
                                  @Value("${alert-channels.enabled:false}") boolean enabled) {
        this.store = store;
        this.worker = worker;
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.events = events;
        this.zone = ZoneId.of(zone);
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** Whether this server can send to the channel type (Telegram needs a bot token, push needs VAPID keys). */
    public boolean available(ChannelType type) {
        return enabled && worker.sender(type) != null;
    }

    public List<ChannelView> views(long userId, Locale locale) {
        return store.list(userId).stream().map(channel -> view(channel, locale)).toList();
    }

    private ChannelView view(ChannelStore.Channel channel, Locale locale) {
        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) filter (where outcome = 'DELIVERED') delivered,
                       count(*) filter (where delivered_at is null and expired_at is null) waiting,
                       count(*) filter (where outcome in ('FAILED','REJECTED','EXPIRED','CHANNEL_GONE')) failed
                from alert_channel_deliveries where channel_id = ? and created_at > current_timestamp - interval '30 days'
                """, channel.id());
        List<DeliveryView> recent = jdbc.query("""
                select coalesce(delivered_at, expired_at, created_at) at, alert_kind,
                       coalesce(outcome, 'WAITING') outcome
                from alert_channel_deliveries where channel_id = ? order by created_at desc, id limit 10
                """, (rs, row) -> new DeliveryView(when(rs.getTimestamp(1).toInstant(), locale), rs.getString(2),
                rs.getString(3)), channel.id());
        return new ChannelView(channel, number(counts.get("delivered")), number(counts.get("waiting")),
                number(counts.get("failed")), when(channel.lastSuccessAt(), locale), when(channel.lastFailureAt(), locale),
                recent);
    }

    private String when(Instant instant, Locale locale) {
        return instant == null ? null : WHEN.withLocale(locale).format(instant.atZone(zone));
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** The kinds a new channel starts with: the account's email choices, or everything when those are all off. */
    public Set<AlertKind> defaultKinds(User user) {
        if (preferences == null) return EnumSet.allOf(AlertKind.class);
        var email = preferences.get(user).email();
        Set<AlertKind> kinds = EnumSet.noneOf(AlertKind.class);
        if (email.newCandlestick()) kinds.add(AlertKind.SIGNAL_CANDLESTICK);
        if (email.newElliott()) kinds.add(AlertKind.SIGNAL_ELLIOTT);
        if (Boolean.TRUE.equals(email.newHarmonic())) kinds.add(AlertKind.SIGNAL_HARMONIC);
        if (email.confirmed()) kinds.add(AlertKind.OUTCOME_CONFIRMED);
        if (email.invalidated()) kinds.add(AlertKind.OUTCOME_INVALIDATED);
        if (email.expired()) kinds.add(AlertKind.OUTCOME_EXPIRED);
        if (email.daily()) kinds.add(AlertKind.OUTLOOK);
        if (Boolean.TRUE.equals(email.chartAlerts())) kinds.add(AlertKind.CHART);
        if (Boolean.TRUE.equals(email.customAlerts())) kinds.add(AlertKind.CUSTOM);
        if (email.insider()) kinds.add(AlertKind.INSIDER);
        if (email.congressional()) kinds.add(AlertKind.CONGRESS);
        return kinds.isEmpty() ? EnumSet.allOf(AlertKind.class) : kinds;
    }

    public Set<String> defaultDirections(User user) {
        if (preferences == null) return Set.of("BUY", "SELL");
        var email = preferences.get(user).email();
        Set<String> directions = new LinkedHashSet<>();
        if (email.buy()) directions.add("BUY");
        if (email.sell()) directions.add("SELL");
        return directions.isEmpty() ? Set.of("BUY", "SELL") : directions;
    }

    /** Adds a channel; ten additions an hour per account at most. */
    public ChannelStore.Channel create(User user, ChannelStore.NewChannel channel) {
        if (!enabled) throw new UserInputException("Alert channels are not available on this server.");
        if (!limiter.tryAcquire("channel-create:" + user.getId(), 10, Duration.ofHours(1))) {
            throw new UserInputException("Too many channels added in the last hour. Try again later.");
        }
        ChannelStore.Channel created = store.create(channel);
        if (created.status() == ChannelStatus.ACTIVE) announce(user, created);
        return created;
    }

    /** Records the new channel under Sessions and activity and emails the owner, best effort. */
    public void announce(User user, ChannelStore.Channel channel) {
        SecurityEvent event = new SecurityEvent();
        event.setUser(user);
        event.setEventType("NOTIFICATION_CHANNEL_ADDED");
        event.setDescription("Alerts now also go to " + channel.type().name() + " channel \"" + channel.label() + "\".");
        events.save(event);
        if (notifications == null || !notifications.isEmailDeliveryEnabled()) return;
        try {
            notifications.sendChannelAddedNotice(user, channel.label(), channel.type().name());
        } catch (RuntimeException failure) {
            log.warn("Channel added notice could not be queued: channel={} failureType={}", channel.id(),
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Sends a test message now and reports what the provider answered. Five tests per ten minutes per account. A test
     * that arrives confirms a pending webhook and brings a stopped channel back.
     */
    public TestResult test(User user, ChannelStore.Channel channel) {
        ChannelSender sender = enabled ? worker.sender(channel.type()) : null;
        if (sender == null || channel.status() == ChannelStatus.REVOKED) return new TestResult(TestOutcome.NOT_AVAILABLE, null);
        if (!limiter.tryAcquire("channel-test:" + user.getId(), 5, Duration.ofMinutes(10))) {
            return new TestResult(TestOutcome.TOO_MANY, null);
        }
        Locale locale = channelLocale(user, channel);
        Messages text = messages != null ? messages : Messages.standalone();
        ChannelMessage message = new ChannelMessage(AlertKind.OUTLOOK.name(), AppLanguage.of(locale).code(),
                text.get(locale, "channel.test.subject"), text.get(locale, "channel.test.body", channel.label()),
                baseUrl + "/settings/alert-delivery", null, null, Map.of("test", true), null, null, null, Instant.now());
        var target = new ChannelSender.Target(channel.id(), channel.publicId(), channel.userId(), channel.type(),
                channel.label(), channel.providerScope(), store.config(channel), channel.language());
        SendResult result;
        try {
            result = sender.send(target, ChannelSender.Outgoing.test(message), ChannelSender.PartProgress.NONE);
        } catch (RuntimeException failure) {
            log.warn("Channel test failed: channel={} failureType={}", channel.id(), failure.getClass().getSimpleName());
            result = new SendResult.Retry("ERROR", null);
        }
        return switch (result) {
            case SendResult.Delivered delivered -> {
                if (channel.status() == ChannelStatus.PENDING || channel.status() == ChannelStatus.FAILING) {
                    store.activate(channel);
                    if (channel.status() == ChannelStatus.PENDING) announce(user, channel);
                }
                jdbc.update("update alert_channels set last_success_at = current_timestamp, last_failure_code = null "
                        + "where id = ?", channel.id());
                yield new TestResult(TestOutcome.SENT, null);
            }
            case SendResult.ChannelGone gone -> {
                store.stop(channel.id(), ChannelStatus.REVOKED, gone.code());
                yield new TestResult(TestOutcome.GONE, gone.code());
            }
            case SendResult.RateLimited limited -> new TestResult(TestOutcome.SLOW_DOWN, limited.code());
            case SendResult.Rejected rejected -> recordTestFailure(channel, rejected.code());
            case SendResult.Retry retry -> recordTestFailure(channel, retry.code());
            case SendResult.ConfigError error -> new TestResult(TestOutcome.NOT_AVAILABLE, error.code());
        };
    }

    private TestResult recordTestFailure(ChannelStore.Channel channel, String code) {
        jdbc.update("update alert_channels set last_failure_at = current_timestamp, last_failure_code = ? where id = ?",
                code == null ? null : code.substring(0, Math.min(40, code.length())), channel.id());
        return new TestResult(code != null && code.startsWith("TARGET_BLOCKED") ? TestOutcome.BLOCKED : TestOutcome.FAILED, code);
    }

    public Locale channelLocale(User user, ChannelStore.Channel channel) {
        AppLanguage chosen = AppLanguage.fromCode(channel.language());
        return chosen != null ? chosen.locale() : LanguagePreferenceService.localeFor(user);
    }

    public boolean pause(ChannelStore.Channel channel) {
        return channel.status() == ChannelStatus.ACTIVE && store.stop(channel.id(), ChannelStatus.PAUSED, "USER_PAUSED");
    }

    public boolean resume(ChannelStore.Channel channel) {
        return channel.status() == ChannelStatus.PAUSED && store.activate(channel);
    }

    public void update(User user, ChannelStore.Channel channel, String label, Set<AlertKind> kinds, Set<String> directions,
                       String language, boolean sameAsEmail) {
        if (label != null && !label.equals(channel.label())) store.rename(channel, label);
        if (sameAsEmail) store.updateChoices(channel, defaultKinds(user), defaultDirections(user), language);
        else store.updateChoices(channel, kinds, directions, language);
    }

    public void remove(ChannelStore.Channel channel) {
        store.delete(channel);
    }

    /** Channels that stopped on their own (removed by the provider, or failing), for the banner on the home page. */
    public List<ChannelStore.Channel> stoppedOnTheirOwn(long userId) {
        if (!enabled) return List.of();
        return store.list(userId).stream()
                .filter(channel -> channel.status() == ChannelStatus.FAILING || (channel.status() == ChannelStatus.REVOKED
                        && !ChannelNotices.OWNER_ENDED.contains(channel.disabledReason()) && channel.type() != ChannelType.PUSH))
                .toList();
    }
}
