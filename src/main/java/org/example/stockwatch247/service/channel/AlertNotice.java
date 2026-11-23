package org.example.stockwatch247.service.channel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One alert for an account's channels, built next to its email: the email's English subject and body (captured
 * before the email is localized), where to see it on the site, the email's deduplication key, the row it comes
 * from, and structured facts (pattern, entry, stop, target …) for push titles and webhook JSON.
 *
 * @param direction BUY, SELL, or null when the alert has no direction (an outlook change, a chart alert)
 * @param groupKey  signals that end at the same point share it and go out as one message
 */
public record AlertNotice(long userId, AlertKind kind, String direction, String symbol, String subject, String body,
                          String viewPath, String deduplicationKey, Source source, Long sourceId, String groupKey,
                          Map<String, Object> facts) {

    /** The row an alert comes from; deleting it removes the alert's pending channel messages. */
    public enum Source {
        ALERT_EVENT("alert_event_id"), OUTLOOK("outlook_notification_id"), CHART_ALERT_EVENT("chart_alert_event_id"),
        CUSTOM_ALERT_EVENT("custom_alert_event_id"), INSIDER_DELIVERY("insider_delivery_id"),
        CONGRESS_DELIVERY("congressional_delivery_id");

        private final String column;

        Source(String column) { this.column = column; }

        public String column() { return column; }
    }

    public AlertNotice {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        body = body == null ? "" : body;
        facts = facts == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        if (direction != null && !direction.equals("BUY") && !direction.equals("SELL")) direction = null;
        if (source == null) sourceId = null;
    }

    public AlertNotice withGroupKey(String key) {
        return new AlertNotice(userId, kind, direction, symbol, subject, body, viewPath, deduplicationKey, source, sourceId,
                key, facts);
    }

    public AlertNotice withKind(AlertKind other) {
        return new AlertNotice(userId, other, direction, symbol, subject, body, viewPath, deduplicationKey, source, sourceId,
                groupKey, facts);
    }
}
