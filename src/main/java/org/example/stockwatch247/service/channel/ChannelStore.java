package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.SecurityCryptoService;
import org.example.stockwatch247.service.UserInputException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The account's alert channels. The target (endpoint, chat id, webhook URL and secret) is kept encrypted and bound
 * to its row; a keyed hash of it finds duplicates and inbound lookups without storing it in the clear.
 */
@Service
public class ChannelStore {
    public static final Set<String> DIRECTIONS = Set.of("BUY", "SELL");

    public record Channel(long id, UUID publicId, long userId, ChannelType type, String label, String displayHint,
                          String providerScope, ChannelStatus status, Set<AlertKind> kinds, Set<String> directions,
                          String deliveryMode, String language, int consecutiveFailures, Instant lastSuccessAt,
                          Instant lastFailureAt, String lastFailureCode, String disabledReason, Instant disabledAt,
                          Instant createdAt, boolean quietHours) {
        public boolean accepts(AlertKind kind, String direction) {
            return kinds.contains(kind) && (direction == null || directions.contains(direction));
        }
    }

    /** A channel to create. {@code lookupValue} is the target that identifies it (an endpoint, a chat id, a URL). */
    public record NewChannel(long userId, ChannelType type, String label, String displayHint, String providerScope,
                             ChannelStatus status, Set<AlertKind> kinds, Set<String> directions, String language,
                             Map<String, Object> config, String lookupValue) { }

    private static final String COLUMNS = """
            id, public_id, user_id, channel_type, label, display_hint, provider_scope, status, alert_kinds, directions,
            delivery_mode, language, consecutive_failures, last_success_at, last_failure_at, last_failure_code,
            disabled_reason, disabled_at, created_at, quiet_hours""";

    private final JdbcTemplate jdbc;
    private final SecurityCryptoService crypto;
    private final ObjectMapper mapper;

    public ChannelStore(JdbcTemplate jdbc, SecurityCryptoService crypto, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public String lookupHash(ChannelType type, String value) {
        return crypto.sign(value, "channel-lookup:" + type.name());
    }

    /** The account's channels, newest first; removed (revoked) ones stay listed for 30 days to explain why. */
    public List<Channel> list(long userId) {
        return jdbc.query("select " + COLUMNS + " from alert_channels where user_id = ? order by created_at desc, id desc",
                MAPPER, userId);
    }

    public Optional<Channel> owned(long userId, UUID publicId) {
        return jdbc.query("select " + COLUMNS + " from alert_channels where user_id = ? and public_id = ?", MAPPER,
                userId, publicId).stream().findFirst();
    }

    public Optional<Channel> byId(long id) {
        return jdbc.query("select " + COLUMNS + " from alert_channels where id = ?", MAPPER, id).stream().findFirst();
    }

    /** Channels that are not removed and whose target hashes to {@code lookupHash}, across accounts. */
    public List<Channel> byLookup(ChannelType type, String lookupHash) {
        return jdbc.query("select " + COLUMNS + " from alert_channels where channel_type = ? and lookup_hash = ? "
                + "and status <> 'REVOKED'", MAPPER, type.name(), lookupHash);
    }

    /** The account's active channels that take this kind of alert in this direction. */
    public List<Channel> accepting(long userId, AlertKind kind, String direction) {
        return jdbc.query("select " + COLUMNS + " from alert_channels where user_id = ? and status = 'ACTIVE' "
                + "and ? = any(alert_kinds) and (cast(? as text) is null or cast(? as text) = any(directions)) order by id",
                MAPPER, userId, kind.name(), direction, direction);
    }

    public boolean anyAccepting(long userId, AlertKind kind, String direction) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from alert_channels where user_id = ? "
                + "and status = 'ACTIVE' and ? = any(alert_kinds) "
                + "and (cast(? as text) is null or cast(? as text) = any(directions)))",
                Boolean.class, userId, kind.name(), direction, direction));
    }

    public int count(long userId, ChannelType type) {
        Integer count = jdbc.queryForObject("select count(*) from alert_channels where user_id = ? and channel_type = ? "
                + "and status <> 'REVOKED'", Integer.class, userId, type.name());
        return count == null ? 0 : count;
    }

    /** Creates the channel, or throws {@link UserInputException} when the account already has as many as allowed. */
    @Transactional
    public Channel create(NewChannel channel) {
        // One account adding channels from two tabs must not pass the quota twice.
        jdbc.queryForObject("select id from users where id = ? for update", Long.class, channel.userId());
        if (count(channel.userId(), channel.type()) >= channel.type().perAccount()) {
            throw new UserInputException("You already have the most channels of this kind.");
        }
        UUID publicId = UUID.randomUUID();
        var encrypted = crypto.encrypt(json(channel.config()), "alert-channel:" + publicId);
        String lookup = channel.lookupValue() == null ? null : lookupHash(channel.type(), channel.lookupValue());
        Long id = jdbc.queryForObject("""
                insert into alert_channels(public_id, user_id, channel_type, label, display_hint, provider_scope, status,
                    alert_kinds, directions, language, config_ciphertext, config_iv, lookup_hash)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as text[]), cast(? as text[]), ?, ?, ?, ?) returning id
                """, Long.class, publicId, channel.userId(), channel.type().name(), label(channel.label()),
                channel.displayHint(), channel.providerScope(), channel.status().name(), kindsArray(channel.kinds()),
                directionsArray(channel.directions()), language(channel.language()), encrypted.ciphertext(),
                encrypted.iv(), lookup);
        return byId(id).orElseThrow();
    }

    /** The decrypted target; empty for a removed channel. */
    public Map<String, Object> config(Channel channel) {
        var stored = jdbc.query("select config_ciphertext, config_iv from alert_channels where id = ?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, channel.id());
        if (stored.isEmpty() || stored.getFirst()[0] == null) return Map.of();
        String plain = crypto.decrypt(stored.getFirst()[0], stored.getFirst()[1], "alert-channel:" + channel.publicId());
        return mapper.readValue(plain, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    public void replaceConfig(Channel channel, Map<String, Object> config, String lookupValue, String displayHint) {
        var encrypted = crypto.encrypt(json(config), "alert-channel:" + channel.publicId());
        jdbc.update("""
                update alert_channels set config_ciphertext = ?, config_iv = ?,
                    lookup_hash = coalesce(?, lookup_hash), display_hint = coalesce(?, display_hint),
                    updated_at = current_timestamp where id = ?
                """, encrypted.ciphertext(), encrypted.iv(),
                lookupValue == null ? null : lookupHash(channel.type(), lookupValue), displayHint, channel.id());
    }

    public void rename(Channel channel, String label) {
        jdbc.update("update alert_channels set label = ?, updated_at = current_timestamp where id = ?", label(label),
                channel.id());
    }

    public void updateChoices(Channel channel, Set<AlertKind> kinds, Set<String> directions, String language) {
        if (kinds.isEmpty()) throw new UserInputException("Choose at least one kind of alert.");
        if (directions.isEmpty()) throw new UserInputException("Choose Buy, Sell/Short or both.");
        jdbc.update("""
                update alert_channels set alert_kinds = cast(? as text[]), directions = cast(? as text[]), language = ?,
                    updated_at = current_timestamp where id = ?
                """, kindsArray(kinds), directionsArray(directions), language(language), channel.id());
        // Alerts already queued that the channel no longer wants are dropped when they come up.
    }

    public void setDeliveryMode(Channel channel, String mode) {
        if (!mode.equals("EACH") && !mode.equals("SUMMARY")) throw new UserInputException("Unknown delivery mode.");
        jdbc.update("update alert_channels set delivery_mode = ?, updated_at = current_timestamp where id = ?", mode,
                channel.id());
    }

    public void setQuietHours(Channel channel, boolean quiet) {
        jdbc.update("update alert_channels set quiet_hours = ?, updated_at = current_timestamp where id = ?", quiet, channel.id());
    }

    /** Makes the channel active again: alerts reach it from now on, and failure counters start over. */
    public boolean activate(Channel channel) {
        return jdbc.update("""
                update alert_channels set status = 'ACTIVE', consecutive_failures = 0, first_failure_at = null,
                    disabled_reason = null, disabled_at = null, notice_queued_at = null, available_at = current_timestamp,
                    updated_at = current_timestamp where id = ? and status <> 'REVOKED'
                """, channel.id()) == 1;
    }

    /**
     * Stops the channel. Alerts already waiting for it are dropped rather than kept, so resuming sends no burst of
     * stale alerts. A revoked channel also loses its stored target at once.
     */
    @Transactional
    public boolean stop(long channelId, ChannelStatus status, String reason) {
        if (status == ChannelStatus.ACTIVE || status == ChannelStatus.PENDING) throw new IllegalArgumentException("Not a stop.");
        int changed = jdbc.update("""
                update alert_channels set status = ?, disabled_reason = ?, disabled_at = current_timestamp,
                    updated_at = current_timestamp,
                    config_ciphertext = case when ? then null else config_ciphertext end,
                    config_iv = case when ? then null else config_iv end
                where id = ? and status <> 'REVOKED'
                """, status.name(), reason, status == ChannelStatus.REVOKED, status == ChannelStatus.REVOKED, channelId);
        if (changed == 1) {
            jdbc.update("""
                    update alert_channel_deliveries set expired_at = current_timestamp, ciphertext = null, iv = null,
                        owner = null, lease_until = null, outcome = ?
                    where channel_id = ? and delivered_at is null and expired_at is null
                    """, status == ChannelStatus.REVOKED ? "CHANNEL_GONE" : "PAUSED", channelId);
        }
        return changed == 1;
    }

    /** Pauses every active channel of the account ("Pause all channels"). */
    @Transactional
    public int pauseAll(long userId) {
        List<Long> ids = jdbc.queryForList("select id from alert_channels where user_id = ? and status = 'ACTIVE'",
                Long.class, userId);
        ids.forEach(id -> stop(id, ChannelStatus.PAUSED, "USER_PAUSED"));
        return ids.size();
    }

    /** Resumes every channel the account paused itself. */
    public int resumeAll(long userId) {
        return jdbc.update("""
                update alert_channels set status = 'ACTIVE', disabled_reason = null, disabled_at = null,
                    available_at = current_timestamp, updated_at = current_timestamp
                where user_id = ? and status = 'PAUSED'
                """, userId);
    }

    /** Removes the channel and everything queued for it. */
    public void delete(Channel channel) {
        jdbc.update("delete from alert_channels where id = ?", channel.id());
    }

    /** Claims the one stop notice for this disable; false when it was already sent. */
    public boolean claimNotice(long channelId) {
        return jdbc.update("update alert_channels set notice_queued_at = current_timestamp where id = ? "
                + "and notice_queued_at is null", channelId) == 1;
    }

    private String json(Map<String, Object> config) {
        return mapper.writeValueAsString(config == null ? Map.of() : config);
    }

    static String kindsArray(Collection<AlertKind> kinds) {
        return kinds.stream().map(Enum::name).sorted().collect(Collectors.joining(",", "{", "}"));
    }

    static String directionsArray(Collection<String> directions) {
        for (String direction : directions) {
            if (!DIRECTIONS.contains(direction)) throw new UserInputException("Unknown direction.");
        }
        return directions.stream().sorted().collect(Collectors.joining(",", "{", "}"));
    }

    private static String language(String language) {
        if (language == null || language.isBlank() || language.equals("auto")) return null;
        if (!language.equals("en") && !language.equals("pl")) throw new UserInputException("Unsupported language.");
        return language;
    }

    private static String label(String label) {
        String value = label == null ? "" : label.strip();
        if (value.isEmpty()) throw new UserInputException("Give the channel a name.");
        if (value.length() > 80) throw new UserInputException("Use at most 80 characters for the name.");
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) throw new UserInputException("Give the channel a name.");
        }
        return value;
    }

    private static final RowMapper<Channel> MAPPER = (rs, row) -> new Channel(
            rs.getLong("id"), rs.getObject("public_id", UUID.class), rs.getLong("user_id"),
            ChannelType.valueOf(rs.getString("channel_type")), rs.getString("label"), rs.getString("display_hint"),
            rs.getString("provider_scope"), ChannelStatus.valueOf(rs.getString("status")),
            kinds(rs.getArray("alert_kinds")), strings(rs.getArray("directions")), rs.getString("delivery_mode"),
            rs.getString("language"), rs.getInt("consecutive_failures"), instant(rs.getTimestamp("last_success_at")),
            instant(rs.getTimestamp("last_failure_at")), rs.getString("last_failure_code"),
            rs.getString("disabled_reason"), instant(rs.getTimestamp("disabled_at")),
            instant(rs.getTimestamp("created_at")), rs.getBoolean("quiet_hours"));

    private static Set<AlertKind> kinds(Array array) throws SQLException {
        Set<AlertKind> kinds = EnumSet.noneOf(AlertKind.class);
        for (String value : strings(array)) {
            AlertKind kind = AlertKind.parse(value);
            if (kind != null) kinds.add(kind);
        }
        return kinds;
    }

    private static Set<String> strings(Array array) throws SQLException {
        if (array == null) return Set.of();
        return new LinkedHashSet<>(Arrays.asList((String[]) array.getArray()));
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
