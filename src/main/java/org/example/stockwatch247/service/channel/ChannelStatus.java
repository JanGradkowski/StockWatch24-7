package org.example.stockwatch247.service.channel;

/** Where a channel stands. Only ACTIVE channels receive alerts. */
public enum ChannelStatus {
    /** Added but not confirmed yet: a Telegram chat waiting for "This is me", a webhook waiting for its first test. */
    PENDING,
    ACTIVE,
    /** Paused by its owner. */
    PAUSED,
    /** Stopped after failing for more than a day; "Retry now" sends a test and makes it active again. */
    FAILING,
    /** The provider says the target is gone (a blocked bot, a deleted webhook, an ended push subscription). */
    REVOKED
}
