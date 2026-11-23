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
    @Autowired(required = false)
    private DiscordSender discord;
    @Autowired(required = false)
    private WebhookSender webhooks;
    @Autowired(required = false)
    private org.example.stockwatch247.security.GuardedHttpClient http;

    /** A webhook just added or given a new secret, with the secret shown this once. */
    public record WebhookSecret(ChannelStore.Channel channel, String secret, boolean rotated) { }

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

    /**
     * Adds a Discord channel webhook: the pasted URL must be a Discord webhook, and Discord must know it. Its name
     * becomes the channel's name unless one was given.
     */
    public ChannelStore.Channel addDiscord(User user, String pasted, String label) {
        if (!available(ChannelType.DISCORD) || discord == null) {
            throw new UserInputException("This kind of channel is not set up on this server.");
        }
        DiscordSender.Webhook webhook = DiscordSender.parse(pasted).orElseThrow(() -> new UserInputException(
                "That isn't a Discord webhook URL. It starts with https://discord.com/api/webhooks/."));
        String lookup = "discord:" + webhook.id();
        boolean known = store.byLookup(ChannelType.DISCORD, store.lookupHash(ChannelType.DISCORD, lookup)).stream()
                .anyMatch(channel -> channel.userId() == user.getId());
        if (known) throw new UserInputException("This Discord webhook is already one of your channels.");
        java.util.Optional<String> name;
        try {
            name = discord.lookUp(webhook);
        } catch (org.example.stockwatch247.security.GuardedHttpClient.OutboundException | RuntimeException unreachable) {
            throw new UserInputException("Discord could not be reached. Try again in a minute.");
        }
        if (name.isEmpty()) throw new UserInputException("Discord doesn't know this webhook. Copy it again.");
        String chosen = label == null || label.isBlank() ? name.get() : label.strip();
        if (chosen.length() > 80) chosen = chosen.substring(0, 80);
        return create(user, new ChannelStore.NewChannel(user.getId(), ChannelType.DISCORD, chosen, webhook.hint(),
                DiscordSender.SCOPE, ChannelStatus.ACTIVE, defaultKinds(user), defaultDirections(user), null,
                Map.of("webhookUrl", webhook.url(), "webhookId", webhook.id()), lookup));
    }

    /**
     * Adds a webhook, waiting for its first test: the address must be HTTPS (port 443 or 8443 in production) on a
     * host that resolves only to public addresses. Returns the signing secret, which is shown once.
     */
    public WebhookSecret addWebhook(User user, String url, String label) {
        if (!available(ChannelType.WEBHOOK) || webhooks == null || http == null) {
            throw new UserInputException("This kind of channel is not set up on this server.");
        }
        java.net.URI uri;
        try {
            uri = http.policy().validateUrl(url);
            http.policy().checkedAddresses(uri.getHost());
        } catch (org.example.stockwatch247.security.OutboundTargetPolicy.TargetRejected rejected) {
            throw new UserInputException(switch (rejected.problem()) {
                case NOT_HTTPS -> "Use an https:// address.";
                case CREDENTIALS -> "Remove the user name and password from the address.";
                case IP_LITERAL -> "Use a host name, not an IP address.";
                case PORT -> "Use port 443 or 8443.";
                case PRIVATE_ADDRESS, OWN_HOST -> "The address points to a private network, which StockWatch can't reach.";
                default -> "That is not a valid web address.";
            });
        } catch (java.net.UnknownHostException unknown) {
            throw new UserInputException("StockWatch could not find that host. Check the address.");
        }
        String lookup = "webhook:" + uri;
        boolean known = store.byLookup(ChannelType.WEBHOOK, store.lookupHash(ChannelType.WEBHOOK, lookup)).stream()
                .anyMatch(channel -> channel.userId() == user.getId());
        if (known) throw new UserInputException("This address is already one of your webhooks.");
        String secret = webhooks.newSecret();
        String name = label == null || label.isBlank() ? uri.getHost() : label.strip();
        if (name.length() > 80) name = name.substring(0, 80);
        ChannelStore.Channel channel = create(user, new ChannelStore.NewChannel(user.getId(), ChannelType.WEBHOOK, name,
                uri.getHost(), null, ChannelStatus.PENDING, defaultKinds(user), defaultDirections(user), null,
                Map.of("url", uri.toString(), "secret", secret), lookup));
        return new WebhookSecret(channel, secret, false);
    }

    /** A new signing secret; the old one keeps signing alongside it for 24 hours. */
    public WebhookSecret rotateSecret(ChannelStore.Channel channel) {
        if (channel.type() != ChannelType.WEBHOOK || channel.status() == ChannelStatus.REVOKED || webhooks == null) {
            throw new UserInputException("Only a webhook has a signing secret.");
        }
        Map<String, Object> config = new java.util.LinkedHashMap<>(store.config(channel));
        String secret = webhooks.newSecret();
        config.put("previousSecret", config.get("secret"));
        config.put("previousSecretUntil", Instant.now().plus(Duration.ofHours(24)).toString());
        config.put("secret", secret);
        store.replaceConfig(channel, config, null, null);
        return new WebhookSecret(channel, secret, true);
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

    @Autowired(required = false)
    private ChannelSummaries summaries;

    public void update(User user, ChannelStore.Channel channel, String label, Set<AlertKind> kinds, Set<String> directions,
                       String language, boolean sameAsEmail) {
        update(user, channel, label, kinds, directions, language, sameAsEmail, null, channel.quietHours());
    }

    /**
     * Saves the channel's name, alert choices and language, and for chats how alerts arrive (one by one or one summary
     * per check run). Switching a summary back to one by one sends what it was holding at once.
     */
    public void update(User user, ChannelStore.Channel channel, String label, Set<AlertKind> kinds, Set<String> directions,
                       String language, boolean sameAsEmail, String deliveryMode, boolean quietHours) {
        if (deliveryMode != null && channel.type().chat() && !deliveryMode.equals(channel.deliveryMode())) {
            store.setDeliveryMode(channel, deliveryMode);
            if (deliveryMode.equals("EACH") && summaries != null) summaries.releaseNow(channel.id());
        }
        if (channel.type() != ChannelType.WEBHOOK && quietHours != channel.quietHours()) store.setQuietHours(channel, quietHours);
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
