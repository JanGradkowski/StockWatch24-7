package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.channel.ChannelSender.Item;
import org.example.stockwatch247.service.channel.ChannelSender.Mode;
import org.example.stockwatch247.service.channel.ChannelSender.Outgoing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Telegram messages in HTML parse mode: the subject in bold, the email's body as plain lines, a link to the alert
 * and the footer. Every dynamic string is escaped. A message stays under Telegram's 4,096 characters: a long body is
 * cut at a line with a link to the full alert, and signals at the same point are split between alerts.
 */
public final class TelegramFormatter {
    static final int LIMIT = 4096;
    static final int BODY = 3500;
    static final int ROLL_UP_LINES = 20;

    private final ChannelText text;

    public TelegramFormatter(ChannelText text) {
        this.text = text;
    }

    public List<String> messages(Outgoing outgoing) {
        if (outgoing.mode() == Mode.ROLL_UP) return List.of(rollUp(outgoing.items()));
        List<String> blocks = outgoing.items().stream().map(this::render).toList();
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        if (outgoing.mode() == Mode.GROUP && outgoing.items().size() > 1) {
            ChannelMessage first = outgoing.first().message();
            current.append("<b>").append(escape(text.get(ChannelText.locale(first), "channel.samePoint",
                    outgoing.items().size(), first.symbol() == null ? "" : first.symbol()))).append("</b>");
        }
        for (String block : blocks) {
            String separator = current.isEmpty() ? "" : "\n\n";
            if (!current.isEmpty() && current.length() + separator.length() + block.length() > LIMIT) {
                messages.add(current.toString());
                current = new StringBuilder();
                separator = "";
            }
            current.append(separator).append(block);
        }
        if (!current.isEmpty()) messages.add(current.toString());
        return messages;
    }

    String render(Item item) {
        ChannelMessage message = item.message();
        Locale locale = ChannelText.locale(message);
        StringBuilder out = new StringBuilder();
        out.append("<b>").append(escape(message.subject())).append("</b>");
        String body = ChannelText.fit(message.body(), BODY);
        if (!body.isBlank()) out.append("\n\n").append(escape(body));
        if (message.url() != null) {
            out.append("\n\n<a href=\"").append(attribute(message.url())).append("\">")
                    .append(escape(text.get(locale, "channel.open"))).append("</a>");
        }
        out.append("\n<i>").append(escape(text.footer(item))).append("</i>");
        return out.toString();
    }

    private String rollUp(List<Item> items) {
        Item first = items.getFirst();
        Locale locale = ChannelText.locale(first.message());
        StringBuilder out = new StringBuilder("<b>")
                .append(escape(text.get(locale, "channel.rollUp.title", items.size()))).append("</b>\n");
        int shown = Math.min(ROLL_UP_LINES, items.size());
        for (int i = 0; i < shown; i++) {
            ChannelMessage message = items.get(i).message();
            out.append("\n• ");
            if (message.url() == null) out.append(escape(message.subject()));
            else out.append("<a href=\"").append(attribute(message.url())).append("\">").append(escape(message.subject())).append("</a>");
        }
        if (items.size() > shown) out.append('\n').append(escape(text.get(locale, "channel.rollUp.more", items.size() - shown)));
        out.append("\n<i>").append(escape(text.footer(first))).append("</i>");
        return out.length() <= LIMIT ? out.toString() : out.substring(0, LIMIT);
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String attribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }

    /** The same text without markup, for a resend when Telegram cannot parse the HTML. */
    static String plain(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&amp;", "&");
    }
}
