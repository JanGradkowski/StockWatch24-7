package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.channel.ChannelSender.Item;
import org.example.stockwatch247.service.channel.ChannelSender.Mode;
import org.example.stockwatch247.service.channel.ChannelSender.Outgoing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordFormatterTest {
    private final DiscordFormatter formatter = new DiscordFormatter(new ChannelText(null));

    private static ChannelMessage message(String language, String subject, String body, String direction) {
        return new ChannelMessage("SIGNAL_CANDLESTICK", language, subject, body, "https://stockwatch.example/alerts/signals/7",
                "AAPL", direction, Map.of("entry", 101.5, "stop", 99.25, "target", 106.0), "point:1", "ALERT_EVENT", 7L,
                Instant.parse("2026-11-20T21:00:00Z"));
    }

    private static Item item(ChannelMessage message, String lateSince) {
        return new Item(UUID.randomUUID(), message, message.createdAt(), lateSince, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAlertIsOneEmbedWithItsLinkTradeFieldsAndNoMentions() {
        var payloads = formatter.messages(new Outgoing(Mode.SINGLE, List.of(item(
                message("en", "StockWatch pattern detected: Hammer on AAPL", "*bold* @everyone\n> quote\n# heading", "BUY"),
                null)), 0));
        assertThat(payloads).hasSize(1);
        var payload = payloads.getFirst();
        assertThat(payload.get("allowed_mentions")).isEqualTo(Map.of("parse", List.of()));
        var embed = ((List<Map<String, Object>>) payload.get("embeds")).getFirst();
        assertThat(embed).containsEntry("title", "StockWatch pattern detected: Hammer on AAPL")
                .containsEntry("url", "https://stockwatch.example/alerts/signals/7").containsEntry("color", 0x3DDC97);
        assertThat((String) embed.get("description")).isEqualTo("\\*bold\\* @everyone\n\\> quote\n\\# heading");
        assertThat((List<Map<String, Object>>) embed.get("fields")).extracting(field -> field.get("name") + "=" + field.get("value"))
                .containsExactly("Entry=101.50", "Stop=99.25", "Target=106.00");
        assertThat(((Map<String, Object>) embed.get("footer")).get("text")).isEqualTo("StockWatch 24/7 · not financial advice");
    }

    @Test
    @SuppressWarnings("unchecked")
    void polishMessagesUseDecimalCommasAndALateAlertSaysSo() {
        var embed = ((List<Map<String, Object>>) formatter.messages(new Outgoing(Mode.SINGLE,
                List.of(item(message("pl", "Temat", "Treść", "SELL"), "14:02")), 0)).getFirst().get("embeds")).getFirst();
        assertThat((List<Map<String, Object>>) embed.get("fields")).extracting(field -> field.get("name") + "=" + field.get("value"))
                .containsExactly("Wejście=101,50", "Stop=99,25", "Cel=106,00");
        assertThat(embed).containsEntry("color", 0xFF6B6B);
        assertThat(((Map<String, Object>) embed.get("footer")).get("text"))
                .isEqualTo("StockWatch 24/7 · to nie jest porada finansowa · Dostarczono z opóźnieniem (w kolejce od 14:02)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBigGroupIsSplitWithinDiscordLimitsAndIntroducedOnce() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) items.add(item(message("en", "Signal " + i, "x".repeat(1500), "BUY"), null));
        var payloads = formatter.messages(new Outgoing(Mode.GROUP, items, 0));
        assertThat(payloads.size()).isGreaterThan(2);
        assertThat(payloads.getFirst().get("content")).isEqualTo("These 12 signals on AAPL end at the same point.");
        assertThat(payloads.subList(1, payloads.size())).allSatisfy(payload -> assertThat(payload).doesNotContainKey("content"));
        int embeds = 0;
        for (var payload : payloads) {
            var list = (List<Map<String, Object>>) payload.get("embeds");
            embeds += list.size();
            assertThat(list.size()).isLessThanOrEqualTo(10);
            assertThat(list.stream().mapToInt(DiscordFormatter::size).sum()).isLessThanOrEqualTo(6000);
        }
        assertThat(embeds).isEqualTo(12);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBurstBecomesOneListOfLinkedSubjects() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 25; i++) items.add(item(message("en", "Signal [" + i + "]", "body", "BUY"), null));
        var embed = ((List<Map<String, Object>>) formatter.messages(new Outgoing(Mode.ROLL_UP, items, 0)).getFirst()
                .get("embeds")).getFirst();
        assertThat(embed.get("title")).isEqualTo("25 new StockWatch alerts");
        String description = (String) embed.get("description");
        assertThat(description).startsWith("• [Signal \\[0\\]](https://stockwatch.example/alerts/signals/7)").endsWith("and 5 more");
        assertThat(description.lines().count()).isEqualTo(21);
    }

    @Test
    void pastedWebhookUrlsAreRecognisedInTheirKnownForms() {
        assertThat(DiscordSender.parse("https://discord.com/api/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789")).isPresent();
        assertThat(DiscordSender.parse(" https://discordapp.com/api/v10/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789/ "))
                .get().extracting(DiscordSender.Webhook::url)
                .isEqualTo("https://discord.com/api/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789");
        assertThat(DiscordSender.parse("https://discord.com.evil.example/api/webhooks/1234567/abcDEF_ghi-jkl0123456789")).isEmpty();
        assertThat(DiscordSender.parse("https://discord.com@evil.example/api/webhooks/1234567/abcDEF_ghi-jkl0123456789")).isEmpty();
        assertThat(DiscordSender.parse("http://discord.com/api/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789")).isEmpty();
    }
}
