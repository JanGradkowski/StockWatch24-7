package org.example.stockwatch247.service.channel;

/**
 * A last check when a queued alert comes up for sending, like the email outbox's watchlist check: false drops it,
 * for example because the watchlist that followed the signal no longer does.
 */
public interface ChannelDeliveryFilter {
    boolean allows(ChannelStore.Channel channel, ChannelMessage message);
}
