package org.example.stockwatch247.service.channel;

import java.time.Instant;
import java.util.Map;

/**
 * An alert as one channel receives it: already in the channel's language, with an absolute link. This is what the
 * channel outbox stores, encrypted, until the alert is sent. Formatters turn it into a Telegram message, a Discord
 * embed, webhook JSON or a push notification.
 *
 * @param kind      an {@link AlertKind} name
 * @param language  "en" or "pl"
 * @param subject   the email's subject, translated
 * @param body      the email's body, translated line by line, without the email-only settings links
 * @param url       where to see the alert on the site
 * @param direction BUY, SELL or null
 * @param facts     structured values with stable English names (see {@link AlertNotice#facts()})
 */
public record ChannelMessage(String kind, String language, String subject, String body, String url, String symbol,
                             String direction, Map<String, Object> facts, String groupKey, String source, Long sourceId,
                             Instant createdAt) {
    public ChannelMessage {
        facts = facts == null ? Map.of() : facts;
    }

    public AlertKind alertKind() { return AlertKind.parse(kind); }
}
