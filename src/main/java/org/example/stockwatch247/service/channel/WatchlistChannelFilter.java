package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.WatchlistEmailPolicy;
import org.springframework.stereotype.Component;

/** Drops a queued signal or outlook change when the account's watchlists stopped following it meanwhile. */
@Component
public class WatchlistChannelFilter implements ChannelDeliveryFilter {
    private final WatchlistEmailPolicy watchlists;

    public WatchlistChannelFilter(WatchlistEmailPolicy watchlists) {
        this.watchlists = watchlists;
    }

    @Override
    public boolean allows(ChannelStore.Channel channel, ChannelMessage message) {
        if (message.sourceId() == null || message.source() == null) return true;
        return switch (message.source()) {
            case "ALERT_EVENT" -> watchlists.followsQueuedEvent(message.sourceId());
            case "OUTLOOK" -> watchlists.followsQueuedOutlook(message.sourceId());
            default -> true;
        };
    }
}
