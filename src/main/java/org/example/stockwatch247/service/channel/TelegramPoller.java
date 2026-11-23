package org.example.stockwatch247.service.channel;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads what people send the bot (/start, /stop, /help) and notices when it is blocked, by asking Telegram for
 * updates: there is no inbound endpoint, so this works on localhost and behind any firewall. One instance at a time
 * holds the lease in telegram_bot_state, and the offset stored there means a restart or failover neither loses nor
 * replays updates. The long poll runs on its own daemon thread, never on the shared background workers.
 */
@Component
public class TelegramPoller {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TelegramPoller.class);
    static final Duration WAIT = Duration.ofSeconds(20);

    private final JdbcTemplate jdbc;
    private final TelegramApi api;
    private final TelegramLinks links;
    private final boolean pollingEnabled;
    private final UUID owner = UUID.randomUUID();
    private volatile boolean running;
    private Thread thread;

    public TelegramPoller(JdbcTemplate jdbc, TelegramApi api, TelegramLinks links,
                          @Value("${alert-channels.telegram.polling-enabled:true}") boolean pollingEnabled) {
        this.jdbc = jdbc;
        this.api = api;
        this.links = links;
        this.pollingEnabled = pollingEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!pollingEnabled || !links.available() || thread != null) return;
        running = true;
        thread = Thread.ofPlatform().daemon(true).name("telegram-poller").start(this::loop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                if (!pollOnce(WAIT)) Thread.sleep(15_000);
            } catch (InterruptedException interrupted) {
                return;
            } catch (RuntimeException failure) {
                log.warn("Telegram update poll failed: {}", failure.getClass().getSimpleName());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /** One poll under the lease; false when another instance holds it or Telegram did not answer. */
    public boolean pollOnce(Duration wait) {
        List<Long> offset = jdbc.queryForList("""
                update telegram_bot_state set lease_owner = ?, lease_until = current_timestamp + interval '60 seconds'
                where id = 1 and (lease_until is null or lease_until < current_timestamp or lease_owner = ?)
                returning next_update_id
                """, Long.class, owner, owner);
        if (offset.isEmpty()) return false;
        TelegramApi.Result result;
        try {
            result = api.call("getUpdates", Map.of("offset", offset.getFirst(), "timeout", wait.toSeconds(),
                    "allowed_updates", List.of("message", "my_chat_member")), wait.plusSeconds(10));
        } catch (Exception unreachable) {
            log.info("Telegram updates not read: {}", unreachable.getClass().getSimpleName());
            return false;
        }
        if (!result.ok()) {
            // 409: a webhook is set or another poller uses this token (use a separate bot for development).
            log.warn("Telegram refused getUpdates: code={}", result.errorCode());
            return false;
        }
        for (JsonNode update : result.body().path("result")) {
            long id = update.path("update_id").asLong();
            try {
                handle(update);
            } catch (RuntimeException failure) {
                log.warn("Telegram update {} could not be handled: {}", id, failure.getClass().getSimpleName());
            }
            jdbc.update("update telegram_bot_state set next_update_id = greatest(next_update_id, ?) where id = 1 and lease_owner = ?",
                    id + 1, owner);
        }
        return true;
    }

    void handle(JsonNode update) {
        JsonNode member = update.path("my_chat_member");
        if (!member.isMissingNode()) {
            String status = member.path("new_chat_member").path("status").asString("");
            if (status.equals("kicked") || status.equals("left")) links.onBlocked(member.path("chat").path("id").asLong());
            return;
        }
        JsonNode message = update.path("message");
        String text = message.path("text").asString("").strip();
        if (!text.startsWith("/")) return;
        JsonNode chat = message.path("chat");
        JsonNode from = message.path("from");
        String command = text.split("\\s+", 2)[0].replaceFirst("@.*$", "").toLowerCase(java.util.Locale.ROOT);
        String argument = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).strip() : null;
        switch (command) {
            case "/start" -> links.onStart(chat.path("id").asLong(), from.path("id").asLong(), chat.path("type").asString(""),
                    from.path("username").asString(null), from.path("first_name").asString(null),
                    from.path("language_code").asString(null), argument);
            case "/stop" -> links.onStop(chat.path("id").asLong(), from.path("language_code").asString(""));
            case "/help" -> links.onStart(chat.path("id").asLong(), from.path("id").asLong(), chat.path("type").asString(""),
                    null, null, from.path("language_code").asString(null), null);
            default -> { }
        }
    }
}
