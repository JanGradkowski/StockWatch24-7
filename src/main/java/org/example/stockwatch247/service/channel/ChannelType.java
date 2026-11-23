package org.example.stockwatch247.service.channel;

import java.time.Duration;

/**
 * A kind of channel with its limits: how long an undelivered alert stays worth sending, how many an account may
 * add, how far apart two messages to one channel go, and whether a burst is rolled up into one message.
 */
public enum ChannelType {
    /** A day-old "entry at next open" notification misleads, so push gives up after 24 hours. */
    PUSH(Duration.ofHours(24), 10, Duration.ofSeconds(1), 3),
    TELEGRAM(Duration.ofHours(48), 3, Duration.ofMillis(1100), 8),
    DISCORD(Duration.ofHours(48), 5, Duration.ofSeconds(2), 8),
    /** Receivers may be automation that wants catch-up, and every alert is its own POST. */
    WEBHOOK(Duration.ofHours(72), 5, Duration.ofSeconds(1), Integer.MAX_VALUE);

    private final Duration lifetime;
    private final int perAccount;
    private final Duration pacing;
    private final int rollUpAbove;

    ChannelType(Duration lifetime, int perAccount, Duration pacing, int rollUpAbove) {
        this.lifetime = lifetime;
        this.perAccount = perAccount;
        this.pacing = pacing;
        this.rollUpAbove = rollUpAbove;
    }

    public Duration lifetime() { return lifetime; }
    public int perAccount() { return perAccount; }
    public Duration pacing() { return pacing; }
    /** More due alerts than this go out as one message: a list for chats, a count for push. */
    public int rollUpAbove() { return rollUpAbove; }
    public boolean chat() { return this == TELEGRAM || this == DISCORD; }

    public static ChannelType parse(String value) {
        try {
            return value == null ? null : valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
