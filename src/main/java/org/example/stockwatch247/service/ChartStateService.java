package org.example.stockwatch247.service;

import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stores the chart workspace's drawings, indicator layouts and templates per account. The browser
 * owns the document format; this service validates size and shape, keeps documents per account and
 * symbol, and applies per-account limits.
 */
@Service
public class ChartStateService {
    static final int MAX_DRAWING_BYTES = 32_768;
    static final int MAX_LAYOUT_BYTES = 49_152;
    static final int MAX_TEMPLATE_BYTES = 16_384;
    static final int MAX_DRAWINGS_PER_SYMBOL = 1_000;
    static final int MAX_DRAWINGS_PER_ACCOUNT = 20_000;
    static final int MAX_LAYOUTS = 200;
    static final int MAX_TEMPLATES = 500;
    private static final Pattern DRAWING_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9][a-z0-9:._-]{0,63}");
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_:.-]{1,64}");
    private static final Set<String> TEMPLATE_KINDS = Set.of("DRAWING", "INDICATOR", "INDICATOR_SET");

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ChartStateService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record StoredDrawing(String id, int version, JsonNode payload) { }

    public record StoredLayout(String scope, int version, JsonNode payload) { }

    public record StoredTemplate(long id, String kind, String subject, String name, JsonNode payload) { }

    public List<StoredDrawing> drawings(long userId, String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        return db.query("select drawing_id, version, payload from user_chart_drawings where user_id=? and symbol=? order by id",
                (row, index) -> new StoredDrawing(row.getString(1), row.getInt(2), parse(row.getString(3))), userId, symbol);
    }

    /**
     * Inserts or replaces one drawing. When {@code expectedVersion} is given and the stored drawing
     * has moved on (edited on another device), nothing is written and a conflict is reported.
     */
    @Transactional
    public StoredDrawing saveDrawing(long userId, String rawSymbol, String drawingId, String payload, Integer expectedVersion) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        requireDrawingId(drawingId);
        JsonNode document = document(payload, MAX_DRAWING_BYTES, "drawing");
        if (!document.path("type").isString() || document.path("type").asString().length() > 48) {
            throw new IllegalArgumentException("A drawing needs a type.");
        }
        if (!drawingId.equals(document.path("id").asString(""))) throw new IllegalArgumentException("The drawing id does not match.");
        String locked = "select version from user_chart_drawings where user_id=? and symbol=? and drawing_id=? for update";
        List<Integer> current = db.queryForList(locked, Integer.class, userId, symbol, drawingId);
        if (current.isEmpty()) {
            requireCapacity(userId, symbol);
            // Two first saves of one drawing (two tabs, a retry) can race: the loser updates instead of failing.
            int inserted = db.update("insert into user_chart_drawings (user_id, symbol, drawing_id, payload) values (?,?,?,?) "
                    + "on conflict (user_id, symbol, drawing_id) do nothing", userId, symbol, drawingId, payload);
            if (inserted == 1) return new StoredDrawing(drawingId, 1, document);
            current = db.queryForList(locked, Integer.class, userId, symbol, drawingId);
        }
        int version = current.getFirst();
        if (expectedVersion != null && expectedVersion != version) {
            throw new ConflictException("This drawing was changed on another device. Reload the chart to see the latest version.");
        }
        db.update("update user_chart_drawings set payload=?, version=version+1, updated_at=current_timestamp where user_id=? and symbol=? and drawing_id=?",
                payload, userId, symbol, drawingId);
        return new StoredDrawing(drawingId, version + 1, document);
    }

    public void deleteDrawing(long userId, String rawSymbol, String drawingId) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        requireDrawingId(drawingId);
        db.update("delete from user_chart_drawings where user_id=? and symbol=? and drawing_id=?", userId, symbol, drawingId);
    }

    public int deleteAllDrawings(long userId, String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        return db.update("delete from user_chart_drawings where user_id=? and symbol=?", userId, symbol);
    }

    /** Adds drawings that are not stored yet (used once to bring over drawings kept only in the browser). */
    @Transactional
    public int importDrawings(long userId, String rawSymbol, List<JsonNode> documents) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        if (documents.size() > 200) throw new UserInputException("At most 200 drawings can be imported at once.");
        int imported = 0;
        for (JsonNode document : documents) {
            String id = document.path("id").asString("");
            requireDrawingId(id);
            String payload = json.writeValueAsString(document);
            document(payload, MAX_DRAWING_BYTES, "drawing");
            Integer existing = db.queryForObject("select count(*) from user_chart_drawings where user_id=? and symbol=? and drawing_id=?",
                    Integer.class, userId, symbol, id);
            if (existing != null && existing > 0) continue;
            requireCapacity(userId, symbol);
            imported += db.update("insert into user_chart_drawings (user_id, symbol, drawing_id, payload) values (?,?,?,?) "
                    + "on conflict (user_id, symbol, drawing_id) do nothing", userId, symbol, id, payload);
        }
        return imported;
    }

    public StoredLayout layout(long userId, String scope) {
        requireScope(scope);
        List<StoredLayout> rows = db.query("select scope, version, payload from user_chart_layouts where user_id=? and scope=?",
                (row, index) -> new StoredLayout(row.getString(1), row.getInt(2), parse(row.getString(3))), userId, scope);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public StoredLayout saveLayout(long userId, String scope, String payload) {
        requireScope(scope);
        JsonNode document = document(payload, MAX_LAYOUT_BYTES, "layout");
        int updated = db.update("update user_chart_layouts set payload=?, version=version+1, updated_at=current_timestamp where user_id=? and scope=?",
                payload, userId, scope);
        if (updated == 0) {
            Integer count = db.queryForObject("select count(*) from user_chart_layouts where user_id=?", Integer.class, userId);
            if (count != null && count >= MAX_LAYOUTS) throw new ConflictException("Too many saved chart layouts.");
            // A concurrent first save of the same layout turns into an update instead of a key violation.
            db.update("insert into user_chart_layouts (user_id, scope, payload) values (?,?,?) on conflict (user_id, scope) "
                    + "do update set payload = excluded.payload, version = user_chart_layouts.version + 1, "
                    + "updated_at = current_timestamp", userId, scope, payload);
            return layout(userId, scope);
        }
        return layout(userId, scope);
    }

    public List<StoredTemplate> templates(long userId, String kind, String subject) {
        String normalizedKind = requireKind(kind);
        if (subject == null) {
            return db.query("select id, kind, subject, name, payload from user_chart_templates where user_id=? and kind=? order by subject, name",
                    (row, index) -> template(row), userId, normalizedKind);
        }
        requireSubject(subject);
        return db.query("select id, kind, subject, name, payload from user_chart_templates where user_id=? and kind=? and subject=? order by name",
                (row, index) -> template(row), userId, normalizedKind, subject);
    }

    @Transactional
    public StoredTemplate saveTemplate(long userId, String kind, String subject, String rawName, String payload) {
        String normalizedKind = requireKind(kind);
        requireSubject(subject);
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw new UserInputException("A template name must have 1 to 80 characters.");
        }
        JsonNode document = document(payload, MAX_TEMPLATE_BYTES, "template");
        int updated = db.update("update user_chart_templates set payload=?, updated_at=current_timestamp where user_id=? and kind=? and subject=? and name=?",
                payload, userId, normalizedKind, subject, name);
        if (updated == 0) {
            Integer count = db.queryForObject("select count(*) from user_chart_templates where user_id=?", Integer.class, userId);
            if (count != null && count >= MAX_TEMPLATES) throw new ConflictException("Too many saved templates. Delete one first.");
            db.update("insert into user_chart_templates (user_id, kind, subject, name, payload) values (?,?,?,?,?)",
                    userId, normalizedKind, subject, name, payload);
        }
        Long id = db.queryForObject("select id from user_chart_templates where user_id=? and kind=? and subject=? and name=?",
                Long.class, userId, normalizedKind, subject, name);
        return new StoredTemplate(id == null ? 0 : id, normalizedKind, subject, name, document);
    }

    public void deleteTemplate(long userId, long templateId) {
        db.update("delete from user_chart_templates where user_id=? and id=?", userId, templateId);
    }

    private StoredTemplate template(java.sql.ResultSet row) throws java.sql.SQLException {
        return new StoredTemplate(row.getLong(1), row.getString(2), row.getString(3), row.getString(4), parse(row.getString(5)));
    }

    private void requireCapacity(long userId, String symbol) {
        Integer perSymbol = db.queryForObject("select count(*) from user_chart_drawings where user_id=? and symbol=?", Integer.class, userId, symbol);
        if (perSymbol != null && perSymbol >= MAX_DRAWINGS_PER_SYMBOL) {
            throw new ConflictException("This chart already has the maximum of " + MAX_DRAWINGS_PER_SYMBOL + " drawings.");
        }
        Integer total = db.queryForObject("select count(*) from user_chart_drawings where user_id=?", Integer.class, userId);
        if (total != null && total >= MAX_DRAWINGS_PER_ACCOUNT) {
            throw new ConflictException("Your account already has the maximum number of saved drawings.");
        }
    }

    private JsonNode document(String payload, int maxBytes, String what) {
        if (payload == null || payload.length() > maxBytes) throw new UserInputException("The " + what + " is too large.");
        try {
            JsonNode node = json.readTree(payload);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("The " + what + " must be a JSON object.");
            return node;
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("The " + what + " is not valid JSON.");
        }
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (JacksonException invalid) {
            return json.createObjectNode();
        }
    }

    private static void requireDrawingId(String drawingId) {
        if (drawingId == null || !DRAWING_ID.matcher(drawingId).matches()) throw new IllegalArgumentException("Invalid drawing id.");
    }

    private static void requireScope(String scope) {
        if (scope == null || !SCOPE.matcher(scope).matches()) throw new IllegalArgumentException("Invalid layout scope.");
    }

    private static void requireSubject(String subject) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) throw new IllegalArgumentException("Invalid template subject.");
    }

    private static String requireKind(String kind) {
        String normalized = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        if (!TEMPLATE_KINDS.contains(normalized)) throw new IllegalArgumentException("Invalid template kind.");
        return normalized;
    }
}
