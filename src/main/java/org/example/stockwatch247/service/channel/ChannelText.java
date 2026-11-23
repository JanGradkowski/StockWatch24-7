package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.i18n.Messages;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/** Shared wording and number formats for channel messages, in the message's own language. */
public final class ChannelText {
    private final Messages messages;

    public ChannelText(Messages messages) {
        this.messages = messages == null ? Messages.standalone() : messages;
    }

    public static Locale locale(ChannelMessage message) {
        AppLanguage language = AppLanguage.fromCode(message.language());
        return (language == null ? AppLanguage.DEFAULT : language).locale();
    }

    public String get(Locale locale, String key, Object... args) {
        return messages.get(locale, key, args);
    }

    /** A price as the site shows it: two to four decimals, in the language's own separators. */
    public static String number(Object value, Locale locale) {
        if (!(value instanceof Number number)) return value == null ? null : value.toString();
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(4);
        format.setGroupingUsed(false);
        return format.format(number.doubleValue());
    }

    /** Entry, stop and target as label/value pairs, when the alert has them. */
    public Map<String, String> tradeFields(ChannelMessage message) {
        Locale locale = locale(message);
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"entry", "stop", "target"}) {
            Object value = message.facts().get(key);
            if (value instanceof Number) fields.put(get(locale, "channel.field." + key), number(value, locale));
        }
        return fields;
    }

    public String footer(ChannelSender.Item item) {
        Locale locale = locale(item.message());
        String footer = get(locale, "channel.footer");
        return item.lateSince() == null ? footer : footer + " · " + get(locale, "channel.late", item.lateSince());
    }

    /** Cuts text at a line boundary so it fits {@code limit} characters, ending with an ellipsis line. */
    public static String fit(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        int cut = text.lastIndexOf('\n', limit - 2);
        if (cut < limit / 2) cut = limit - 2;
        return text.substring(0, cut).stripTrailing() + "\n…";
    }
}
