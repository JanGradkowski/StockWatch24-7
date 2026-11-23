package org.example.stockwatch247.service.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends alerts to one kind of channel (Telegram, Discord, a webhook, Web Push). The worker hands it what is due for
 * one channel and records the result; a sender never touches the outbox tables itself.
 */
public interface ChannelSender {
    ChannelType type();

    /** False while the channel kind is not configured (no bot token, no VAPID keys); its alerts then wait. */
    default boolean ready() { return true; }

    /**
     * Sends {@code outgoing} to {@code target}. A message sent in parts reports each finished part through
     * {@code progress}, so a crash resumes after the last one; {@link Outgoing#partsSent()} says where to resume.
     */
    SendResult send(Target target, Outgoing outgoing, PartProgress progress);

    /** A channel with its decrypted target. */
    record Target(long id, UUID publicId, long userId, ChannelType type, String label, String providerScope,
                  Map<String, Object> config, String language) {
        public String configString(String key) {
            Object value = config.get(key);
            return value == null ? null : value.toString();
        }
    }

    /** What to send: one alert, signals that end at the same point, or a roll-up of a burst. */
    record Outgoing(Mode mode, List<Item> items, int partsSent) {
        public Outgoing {
            items = List.copyOf(items);
        }

        public Item first() { return items.getFirst(); }

        public static Outgoing test(ChannelMessage message) {
            return new Outgoing(Mode.TEST, List.of(new Item(UUID.randomUUID(), message, message.createdAt(), null, null)), 0);
        }
    }

    enum Mode { SINGLE, GROUP, ROLL_UP, TEST }

    /**
     * @param lateSince when the alert waited over an hour (an outage, a provider block), the time it was queued in
     *                  the recipient's time zone ("14:02"), for a "Delivered late" footer; otherwise null
     * @param replyTo   the provider's id of the message this follow-up is about (Telegram replies to it)
     */
    record Item(UUID deliveryId, ChannelMessage message, Instant queuedAt, String lateSince, String replyTo) { }

    interface PartProgress {
        void sent(int partsSent, String providerMessageId);

        PartProgress NONE = (parts, id) -> { };
    }

    /** How a send ended. The worker turns each into the right retry, pause or removal. */
    sealed interface SendResult {
        /** Accepted. {@code pace}, when set, is how long the provider asks us to wait before the next message. */
        record Delivered(String providerMessageId, Duration pace) implements SendResult { }

        /** A temporary failure (timeout, 5xx). {@code after} is the provider's wait, or null for the usual backoff. */
        record Retry(String code, Duration after) implements SendResult { }

        /** Too many requests: wait, without counting a failure. A {@code scope} blocks every channel of the provider. */
        record RateLimited(Duration after, String scope, String code) implements SendResult { }

        /** The target is gone for good (a blocked bot, a deleted webhook, an ended push subscription). */
        record ChannelGone(String code) implements SendResult { }

        /**
         * This message was refused and retrying will not help. {@code channelFault}: the receiver refused it (a webhook
         * answering 400), which counts as a channel failure; otherwise it is our formatting bug.
         */
        record Rejected(String code, boolean channelFault) implements SendResult { }

        /** Our own configuration is wrong (a revoked bot token, bad VAPID keys): pause the provider, never the user's channel. */
        record ConfigError(String code) implements SendResult { }

        static SendResult delivered() { return new Delivered(null, null); }
    }
}
