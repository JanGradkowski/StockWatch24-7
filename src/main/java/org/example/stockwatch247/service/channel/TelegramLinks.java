package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.i18n.Messages;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.TotpService;
import org.example.stockwatch247.service.UserInputException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Linking a private Telegram chat to an account, and what the bot does with its commands.
 *
 * <p>The page creates a one-time start token (32 random bytes, valid 15 minutes, only its hash stored) and shows it as
 * a t.me link and QR code. When someone presses Start with it, the chat becomes a channel that is only
 * <em>waiting</em>: the account's owner must confirm on the site that the chat is theirs. That closes the forwarded-
 * link attack, where someone else presses Start first and would otherwise receive the owner's alerts. /stop, or
 * blocking the bot, removes every channel of that chat.
 */
@Service
public class TelegramLinks {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TelegramLinks.class);
    static final Duration LIFETIME = Duration.ofMinutes(15);

    public record Link(UUID requestId, String deepLink, String qrDataUri, long minutes) { }

    public enum State { WAITING, READY, EXPIRED }

    public record Status(State state, UUID channel) { }

    private final JdbcTemplate jdbc;
    private final ChannelStore store;
    private final ChannelSettingsService settings;
    private final TelegramApi api;
    private final RequestRateLimiter limiter;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    @Autowired(required = false)
    private TotpService qr;
    @Autowired(required = false)
    private Messages messages;

    public TelegramLinks(JdbcTemplate jdbc, ChannelStore store, ChannelSettingsService settings, TelegramApi api,
                         RequestRateLimiter limiter, UserRepository users) {
        this.jdbc = jdbc;
        this.store = store;
        this.settings = settings;
        this.api = api;
        this.limiter = limiter;
        this.users = users;
    }

    public boolean available() {
        return settings.available(ChannelType.TELEGRAM);
    }

    /** A new link for the account: at most three open at once and five an hour. */
    public Link create(User user) {
        if (!available()) throw new UserInputException("This kind of channel is not set up on this server.");
        Integer open = jdbc.queryForObject("select count(*) from telegram_link_requests where user_id = ? "
                + "and used_at is null and expires_at > current_timestamp", Integer.class, user.getId());
        if (open != null && open >= 3 || !limiter.tryAcquire("telegram-link:" + user.getId(), 5, Duration.ofHours(1))) {
            throw new UserInputException("Too many Telegram links. Use the last one, or try again in an hour.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into telegram_link_requests(id, token_hash, user_id, expires_at) values (?, ?, ?, ?)",
                id, hash(token), user.getId(), Timestamp.from(Instant.now().plus(LIFETIME)));
        String deepLink = "https://t.me/" + api.botUsername() + "?start=" + token;
        return new Link(id, deepLink, qr == null ? null : qr.qrDataUri(deepLink), LIFETIME.toMinutes());
    }

    /** Where a link stands, for the page that waits for it; empty for someone else's link. */
    public java.util.Optional<Status> status(User user, UUID requestId) {
        return jdbc.query("""
                select r.used_at is not null, r.expires_at <= current_timestamp, c.public_id
                from telegram_link_requests r left join alert_channels c on c.id = r.channel_id
                where r.id = ? and r.user_id = ?
                """, (rs, row) -> new Status(rs.getBoolean(1) ? State.READY : rs.getBoolean(2) ? State.EXPIRED : State.WAITING,
                rs.getObject(3, UUID.class)), requestId, user.getId()).stream().findFirst();
    }

    /** /start: only from a private chat with its own user, ten tries an hour per chat. */
    @Transactional
    public void onStart(long chatId, long fromId, String chatType, String username, String firstName, String languageCode,
                        String token) {
        Locale locale = "pl".equalsIgnoreCase(languageCode == null ? "" : languageCode.substring(0, Math.min(2, languageCode.length())))
                ? AppLanguage.PL.locale() : AppLanguage.EN.locale();
        if (!"private".equals(chatType) || chatId != fromId) {
            reply(chatId, locale, "telegram.bot.privateOnly");
            return;
        }
        if (!limiter.tryAcquire("telegram-start:" + chatId, 10, Duration.ofHours(1))) return;
        if (token == null || token.isBlank()) {
            reply(chatId, locale, "telegram.bot.help");
            return;
        }
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, user_id from telegram_link_requests where token_hash = ? and used_at is null
                  and expires_at > current_timestamp for update
                """, hash(token.strip()));
        if (found.isEmpty()) {
            // The same answer for a wrong, used or expired token: no hint about which tokens exist.
            reply(chatId, locale, "telegram.bot.invalid");
            return;
        }
        UUID requestId = (UUID) found.getFirst().get("id");
        long userId = ((Number) found.getFirst().get("user_id")).longValue();
        User user = users.findById(userId).orElse(null);
        if (user == null) return;
        String lookup = "telegram:" + chatId;
        var existing = store.byLookup(ChannelType.TELEGRAM, store.lookupHash(ChannelType.TELEGRAM, lookup)).stream()
                .filter(channel -> channel.userId() == userId).findFirst();
        ChannelStore.Channel channel;
        if (existing.isPresent()) {
            channel = existing.get();
        } else {
            String label = username != null && !username.isBlank() ? "@" + username
                    : firstName != null && !firstName.isBlank() ? firstName : "Telegram";
            if (label.length() > 80) label = label.substring(0, 80);
            try {
                channel = store.create(new ChannelStore.NewChannel(userId, ChannelType.TELEGRAM, label,
                        username != null && !username.isBlank() ? "@" + username : null, TelegramSender.SCOPE,
                        ChannelStatus.PENDING, settings.defaultKinds(user), settings.defaultDirections(user), null,
                        Map.of("chatId", Long.toString(chatId), "username", username == null ? "" : username), lookup));
            } catch (UserInputException quota) {
                reply(chatId, locale, "telegram.bot.tooMany");
                return;
            }
        }
        jdbc.update("update telegram_link_requests set used_at = current_timestamp, channel_id = ? where id = ?",
                channel.id(), requestId);
        reply(chatId, locale, channel.status() == ChannelStatus.ACTIVE ? "telegram.bot.alreadyLinked" : "telegram.bot.confirmOnSite");
    }

    /** /stop: every channel of this chat stops at once. */
    public void onStop(long chatId, String languageCode) {
        int stopped = stopChat(chatId, "STOPPED_BY_USER");
        reply(chatId, "pl".equalsIgnoreCase(languageCode) ? AppLanguage.PL.locale() : AppLanguage.EN.locale(),
                stopped > 0 ? "telegram.bot.stopped" : "telegram.bot.help");
    }

    /** The user blocked the bot: its chats can no longer be reached. */
    public void onBlocked(long chatId) {
        stopChat(chatId, "BLOCKED_BY_USER");
    }

    private int stopChat(long chatId, String reason) {
        int stopped = 0;
        for (var channel : store.byLookup(ChannelType.TELEGRAM, store.lookupHash(ChannelType.TELEGRAM, "telegram:" + chatId))) {
            if (store.stop(channel.id(), ChannelStatus.REVOKED, reason)) stopped++;
        }
        return stopped;
    }

    /** The owner confirmed on the site that the chat is theirs. */
    public void confirm(User user, ChannelStore.Channel channel) {
        if (channel.type() != ChannelType.TELEGRAM || channel.status() != ChannelStatus.PENDING) return;
        store.activate(channel);
        settings.announce(user, channel);
        Map<String, Object> config = store.config(channel);
        Object chatId = config.get("chatId");
        if (chatId != null) reply(Long.parseLong(chatId.toString()), settings.channelLocale(user, channel), "telegram.bot.linked");
    }

    /** The owner did not recognise the chat: it is dropped and told so. */
    public void reject(User user, ChannelStore.Channel channel) {
        if (channel.type() != ChannelType.TELEGRAM || channel.status() != ChannelStatus.PENDING) return;
        Object chatId = store.config(channel).get("chatId");
        store.delete(channel);
        if (chatId != null) reply(Long.parseLong(chatId.toString()), settings.channelLocale(user, channel), "telegram.bot.refused");
    }

    private void reply(long chatId, Locale locale, String key) {
        try {
            Messages text = messages != null ? messages : Messages.standalone();
            api.call("sendMessage", Map.of("chat_id", chatId, "text", text.get(locale, key)));
        } catch (Exception failure) {
            log.info("Telegram reply not sent: key={} failureType={}", key, failure.getClass().getSimpleName());
        }
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
    }

    /** Old link requests go a day after they expire. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${alert-channels.cleanup-cron:0 7 * * * *}")
    public void cleanup() {
        try {
            jdbc.update("delete from telegram_link_requests where expires_at < current_timestamp - interval '1 day'");
        } catch (RuntimeException failure) {
            log.warn("Telegram link cleanup deferred: {}", failure.getClass().getSimpleName());
        }
    }
}
