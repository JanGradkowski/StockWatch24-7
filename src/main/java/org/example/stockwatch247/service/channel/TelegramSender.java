package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.i18n.Messages;
import org.example.stockwatch247.security.GuardedHttpClient;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends to a linked private Telegram chat. A follow-up replies to the message that announced its signal. Telegram
 * allows about one message a second per chat (the worker paces each channel) and about 30 a second per bot (this
 * server stays under 25). A 429 waits out Telegram's retry_after, and a long one pauses the whole bot. A blocked bot
 * or a deleted chat removes the channel; a rejected bot token pauses Telegram without touching anyone's channel.
 */
@Component
public class TelegramSender implements ChannelSender {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TelegramSender.class);
    static final String SCOPE = "telegram";

    private final TelegramApi api;
    private final RequestRateLimiter limiter;
    private final TelegramFormatter formatter;
    long partGapMillis = 1100;

    public TelegramSender(TelegramApi api, RequestRateLimiter limiter, ObjectProvider<Messages> messages) {
        this.api = api;
        this.limiter = limiter;
        this.formatter = new TelegramFormatter(new ChannelText(messages.getIfAvailable()));
    }

    @Override public ChannelType type() { return ChannelType.TELEGRAM; }
    @Override public boolean ready() { return api.ready(); }

    @Override
    public SendResult send(Target target, Outgoing outgoing, PartProgress progress) {
        String chatId = target.configString("chatId");
        if (chatId == null) return new SendResult.ChannelGone("TELEGRAM_CHAT_GONE");
        List<String> parts = formatter.messages(outgoing);
        String firstId = null;
        for (int part = Math.min(outgoing.partsSent(), parts.size()); part < parts.size(); part++) {
            if (part > outgoing.partsSent() && !pause()) return new SendResult.Retry("INTERRUPTED", null);
            if (!limiter.tryAcquire("telegram-global-pace", 25, Duration.ofSeconds(1))) {
                return new SendResult.RateLimited(Duration.ofSeconds(1), null, "LOCAL_PACE");
            }
            String replyTo = part == 0 && outgoing.mode() != Mode.ROLL_UP ? outgoing.first().replyTo() : null;
            TelegramApi.Result result;
            try {
                result = sendMessage(chatId, parts.get(part), true, replyTo);
                if (!result.ok() && result.errorCode() == 400
                        && result.description().toLowerCase(Locale.ROOT).contains("can't parse entities")) {
                    log.warn("Telegram could not parse a message; sending it as plain text: channel={}", target.id());
                    result = sendMessage(chatId, TelegramFormatter.plain(parts.get(part)), false, replyTo);
                }
            } catch (GuardedHttpClient.OutboundException failure) {
                return new SendResult.Retry(failure.failure().name(), null);
            }
            if (result.ok()) {
                String id = result.body().path("result").path("message_id").asString("");
                if (firstId == null && !id.isEmpty()) firstId = id;
                progress.sent(part + 1, id.isEmpty() ? null : id);
                continue;
            }
            return failure(result, target);
        }
        return new SendResult.Delivered(firstId, null);
    }

    private TelegramApi.Result sendMessage(String chatId, String text, boolean html, String replyTo)
            throws GuardedHttpClient.OutboundException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("chat_id", chatId);
        parameters.put("text", text);
        if (html) parameters.put("parse_mode", "HTML");
        parameters.put("link_preview_options", Map.of("is_disabled", true));
        if (replyTo != null) {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("message_id", Long.parseLong(replyTo));
            reply.put("allow_sending_without_reply", true);
            parameters.put("reply_parameters", reply);
        }
        return api.call("sendMessage", parameters);
    }

    private SendResult failure(TelegramApi.Result result, Target target) {
        int code = result.errorCode();
        String description = result.description().toLowerCase(Locale.ROOT);
        if (code == 429) {
            Duration after = result.retryAfter();
            return new SendResult.RateLimited(after.plusMillis(250),
                    after.compareTo(Duration.ofSeconds(30)) > 0 ? SCOPE : null, "TELEGRAM_429");
        }
        if (code == 403) return new SendResult.ChannelGone(description.contains("blocked") ? "BLOCKED_BY_USER" : "TELEGRAM_CHAT_GONE");
        if (code == 400 && (description.contains("chat not found") || description.contains("user not found")))
            return new SendResult.ChannelGone("TELEGRAM_CHAT_GONE");
        if (code == 401 || code == 404) return new SendResult.ConfigError("TELEGRAM_UNAUTHORIZED");
        if (code >= 500 || result.status() >= 500) return new SendResult.Retry("HTTP_" + code, null);
        log.warn("Telegram refused a message: code={} channel={}", code, target.id());
        return new SendResult.Rejected("TELEGRAM_" + code, false);
    }

    /** Telegram allows about one message a second per chat, so the parts of one message wait for each other. */
    private boolean pause() {
        try {
            Thread.sleep(partGapMillis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
