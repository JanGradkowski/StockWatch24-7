package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AlertNotificationService;
import org.springframework.stereotype.Component;

/**
 * Tells the owner, once, by email that a channel stopped on its own: Discord or the webhook's receiver removed
 * it, or it kept failing for a day. The email goes out even while alert emails are paused, because it is about the
 * account's own settings. Nothing is sent when the owner stopped it (Telegram /stop, blocking the bot, turning push
 * off) or for push, whose state shows on the device itself. Without SMTP the settings page banner is the notice.
 */
@Component
public class ChannelNotices {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelNotices.class);
    /** Codes a sender reports when the owner ended the channel themselves. */
    public static final java.util.Set<String> OWNER_ENDED = java.util.Set.of("BLOCKED_BY_USER", "STOPPED_BY_USER",
            "USER_REMOVED", "UNSUBSCRIBED");

    private final ChannelStore store;
    private final UserRepository users;
    private final AlertNotificationService notifications;

    public ChannelNotices(ChannelStore store, UserRepository users, AlertNotificationService notifications) {
        this.store = store;
        this.users = users;
        this.notifications = notifications;
    }

    public void stopped(ChannelStore.Channel channel, ChannelStatus status, String code) {
        if (channel.type() == ChannelType.PUSH || OWNER_ENDED.contains(code)) return;
        if (!notifications.isEmailDeliveryEnabled()) return;
        if (!store.claimNotice(channel.id())) return;
        try {
            users.findById(channel.userId()).ifPresent(user ->
                    notifications.sendChannelStoppedNotice(user, channel.label(), channel.type().name(),
                            status == ChannelStatus.REVOKED));
        } catch (RuntimeException failure) {
            log.warn("Channel stop notice could not be queued: channel={} failureType={}", channel.id(),
                    failure.getClass().getSimpleName());
        }
    }
}
