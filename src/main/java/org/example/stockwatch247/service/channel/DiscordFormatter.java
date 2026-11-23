package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.channel.ChannelSender.Item;
import org.example.stockwatch247.service.channel.ChannelSender.Mode;
import org.example.stockwatch247.service.channel.ChannelSender.Outgoing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Discord webhook messages: one embed per alert (title, link, body, entry/stop/target fields, a colour by
 * direction), signals at the same point as up to ten embeds under one introduction, a burst as one list. Every
 * message stays within Discord's limits (10 embeds, 6,000 characters), so a big group becomes several messages.
 * Markdown is escaped and mentions are switched off, so nothing in an alert can ping a server.
 */
public final class DiscordFormatter {
    static final int TITLE = 256;
    static final int DESCRIPTION = 4096;
    static final int MESSAGE_TOTAL = 6000;
    static final int EMBEDS = 10;
    static final int ROLL_UP_LINES = 20;
    private static final int BUY = 0x3DDC97;
    private static final int SELL = 0xFF6B6B;
    private static final int NEUTRAL = 0x7B8CFF;

    private final ChannelText text;

    public DiscordFormatter(ChannelText text) {
        this.text = text;
    }

    /** The webhook payloads to post, in order. */
    public List<Map<String, Object>> messages(Outgoing outgoing) {
        if (outgoing.mode() == Mode.ROLL_UP) return List.of(payload(null, List.of(rollUp(outgoing.items()))));
        List<Map<String, Object>> embeds = outgoing.items().stream().map(this::embed).toList();
        String introduction = outgoing.mode() == Mode.GROUP && outgoing.items().size() > 1 ? introduction(outgoing) : null;
        List<Map<String, Object>> payloads = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        int size = introduction == null ? 0 : introduction.length();
        for (Map<String, Object> embed : embeds) {
            int embedSize = size(embed);
            if (!current.isEmpty() && (current.size() == EMBEDS || size + embedSize > MESSAGE_TOTAL)) {
                payloads.add(payload(payloads.isEmpty() ? introduction : null, current));
                current = new ArrayList<>();
                size = 0;
            }
            current.add(embed);
            size += embedSize;
        }
        if (!current.isEmpty()) payloads.add(payload(payloads.isEmpty() ? introduction : null, current));
        return payloads;
    }

    private String introduction(Outgoing outgoing) {
        ChannelMessage first = outgoing.first().message();
        Locale locale = ChannelText.locale(first);
        return escape(text.get(locale, "channel.samePoint", outgoing.items().size(),
                first.symbol() == null ? "" : first.symbol()));
    }

    private Map<String, Object> payload(String content, List<Map<String, Object>> embeds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", "StockWatch 24/7");
        if (content != null) payload.put("content", content);
        payload.put("embeds", embeds);
        payload.put("allowed_mentions", Map.of("parse", List.of()));
        return payload;
    }

    Map<String, Object> embed(Item item) {
        ChannelMessage message = item.message();
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", ChannelText.fit(message.subject(), TITLE).replace('\n', ' '));
        if (message.url() != null) embed.put("url", message.url());
        embed.put("description", ChannelText.fit(escape(message.body()), DESCRIPTION));
        embed.put("color", "BUY".equals(message.direction()) ? BUY : "SELL".equals(message.direction()) ? SELL : NEUTRAL);
        List<Map<String, Object>> fields = new ArrayList<>();
        text.tradeFields(message).forEach((name, value) -> fields.add(Map.of("name", name, "value", value, "inline", true)));
        if (!fields.isEmpty()) embed.put("fields", fields);
        embed.put("footer", Map.of("text", text.footer(item)));
        if (message.createdAt() != null) embed.put("timestamp", message.createdAt().toString());
        return embed;
    }

    private Map<String, Object> rollUp(List<Item> items) {
        Item first = items.getFirst();
        Locale locale = ChannelText.locale(first.message());
        StringBuilder lines = new StringBuilder();
        int shown = Math.min(ROLL_UP_LINES, items.size());
        for (int i = 0; i < shown; i++) {
            ChannelMessage message = items.get(i).message();
            String subject = escape(message.subject());
            lines.append("• ").append(message.url() == null ? subject : "[" + subject + "](" + message.url() + ")").append('\n');
        }
        if (items.size() > shown) lines.append(text.get(locale, "channel.rollUp.more", items.size() - shown));
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", text.get(locale, "channel.rollUp.title", items.size()));
        embed.put("description", ChannelText.fit(lines.toString().stripTrailing(), DESCRIPTION));
        embed.put("color", NEUTRAL);
        embed.put("footer", Map.of("text", text.footer(first)));
        return embed;
    }

    @SuppressWarnings("unchecked")
    static int size(Map<String, Object> embed) {
        int size = String.valueOf(embed.getOrDefault("title", "")).length()
                + String.valueOf(embed.getOrDefault("description", "")).length();
        Object footer = embed.get("footer");
        if (footer instanceof Map<?, ?> map) size += String.valueOf(map.get("text")).length();
        Object fields = embed.get("fields");
        if (fields instanceof List<?> list) {
            for (Object field : list) {
                Map<String, Object> f = (Map<String, Object>) field;
                size += String.valueOf(f.get("name")).length() + String.valueOf(f.get("value")).length();
            }
        }
        return size;
    }

    /** Discord markdown off: emphasis, code, spoilers, quotes, headings and links come out as typed. */
    static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        boolean lineStart = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\*_~`|[]".indexOf(c) >= 0 || lineStart && (c == '>' || c == '#')) out.append('\\');
            out.append(c);
            lineStart = c == '\n' || lineStart && c == ' ';
        }
        return out.toString();
    }
}
