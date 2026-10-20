package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorBatchRequest;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorBatchView;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorConfiguration;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorConfigurationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Alerts set on chart drawings and indicators.
 *
 * <p>A drawing alert compares each completed candle's close with the drawn line, channel or rectangle,
 * using the same bar-by-bar geometry as the chart. An indicator alert compares an indicator output
 * with a number, another output or the price. The scheduled checks call {@link #evaluate} after they
 * refresh a symbol's candles; a trigger is stored once per candle, shown in Latest signals and emailed
 * when the user's email alerts for that interval are on. Only candles that complete after the alert was
 * created can trigger it.</p>
 */
@Service
public class ChartAlertService {
    private static final Logger log = LoggerFactory.getLogger(ChartAlertService.class);
    static final int MAX_ACTIVE_PER_USER = 200;
    static final int MAX_DEFINITION_LENGTH = 16_000;
    static final int MAX_LABEL_LENGTH = 160;
    /** Candles evaluated per alert in one check; older ones after a long outage are skipped. */
    static final int MAX_CATCH_UP = 60;
    private static final long DAY = 86_400;
    private static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Set<String> LEVEL_CONDITIONS = Set.of("CROSSES", "CROSSES_UP", "CROSSES_DOWN", "ABOVE", "BELOW");
    private static final Set<String> BAND_CONDITIONS = Set.of("ENTERS", "EXITS", "INSIDE", "OUTSIDE");
    private static final Map<String, TimeInterval> INTERVALS = Map.of(
            "1d", TimeInterval.DAILY, "1wk", TimeInterval.WEEKLY, "1mo", TimeInterval.MONTHLY);
    private static final DateTimeFormatter CANDLE_DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final CandleRepository candles;
    private final CandleCompletionService completion;
    private final UserRepository users;
    private final String publicBaseUrl;
    private ChartTechnicalIndicatorService indicators;
    private AlertNotificationService notifications;

    public ChartAlertService(JdbcTemplate db, ObjectMapper json, CandleRepository candles, CandleCompletionService completion,
                             UserRepository users, @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.db = db;
        this.json = json;
        this.candles = candles;
        this.completion = completion;
        this.users = users;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    @Autowired(required = false)
    void configureIndicators(ChartTechnicalIndicatorService service) {
        this.indicators = service;
    }

    @Autowired(required = false)
    void configureNotifications(AlertNotificationService service) {
        this.notifications = service;
    }

    public record CreateCommand(String symbol, String interval, String sourceKind, String sourceId, String label,
                                String condition, String frequency, JsonNode definition) { }

    public record AlertView(long id, String symbol, String interval, String sourceKind, String sourceId, String label,
                            String condition, String frequency, JsonNode definition, boolean active, int triggerCount,
                            LocalDateTime lastTriggeredAt, LocalDateTime createdAt) { }

    /** One trigger, for Latest signals. */
    public record LatestChartAlertView(long id, String symbol, String companyName, TimeInterval interval, String label,
                                       String sourceKind, String condition, Double value, Double level, double closePrice,
                                       long candleTimestamp, LocalDateTime createdAt) {
        /** The chart the alert was set on; the page shows the trigger's candle and then marks it read. */
        public String detailUrl() {
            return "/stock/" + org.springframework.web.util.UriUtils.encodePathSegment(symbol, java.nio.charset.StandardCharsets.UTF_8)
                    + "?chartAlert=" + id + "#general";
        }

        public LocalDate candleDate() { return LocalDate.ofInstant(Instant.ofEpochSecond(candleTimestamp), ZoneOffset.UTC); }
    }

    public record EvaluationResult(int checked, int triggered) { }

    // ------------------------------------------------------------------ management

    @Transactional(readOnly = true)
    public List<AlertView> list(long userId, String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        return db.query("""
                select id, symbol, interval, source_kind, source_id, label, condition, frequency, definition, active,
                       trigger_count, last_triggered_at, created_at
                from chart_alerts where user_id = ? and symbol = ? order by created_at desc, id desc
                """, (row, index) -> new AlertView(row.getLong("id"), row.getString("symbol"),
                chartInterval(TimeInterval.valueOf(row.getString("interval"))), row.getString("source_kind"),
                row.getString("source_id"), row.getString("label"), row.getString("condition"), row.getString("frequency"),
                read(row.getString("definition")), row.getBoolean("active"), row.getInt("trigger_count"),
                toLocal(row.getTimestamp("last_triggered_at")), toLocal(row.getTimestamp("created_at"))), userId, symbol);
    }

    /** Every chart alert of the account, for the Custom alerts page; newest first. */
    @Transactional(readOnly = true)
    public List<AlertView> listAll(long userId) {
        return db.query("""
                select id, symbol, interval, source_kind, source_id, label, condition, frequency, definition, active,
                       trigger_count, last_triggered_at, created_at
                from chart_alerts where user_id = ? order by active desc, created_at desc, id desc limit 500
                """, (row, index) -> new AlertView(row.getLong("id"), row.getString("symbol"),
                chartInterval(TimeInterval.valueOf(row.getString("interval"))), row.getString("source_kind"),
                row.getString("source_id"), row.getString("label"), row.getString("condition"), row.getString("frequency"),
                read(row.getString("definition")), row.getBoolean("active"), row.getInt("trigger_count"),
                toLocal(row.getTimestamp("last_triggered_at")), toLocal(row.getTimestamp("created_at"))), userId);
    }

    /** One chart alert of the account, for turning it into a custom alert. */
    @Transactional(readOnly = true)
    public AlertView get(long userId, long id) {
        return find(userId, id);
    }

    @Transactional
    public AlertView create(long userId, CreateCommand command) {
        if (command == null) throw new UserInputException("Describe the alert.");
        String symbol = SecurityInputValidator.requireMarketSymbol(command.symbol());
        TimeInterval interval = interval(command.interval());
        String kind = upper(command.sourceKind());
        if (!Set.of("DRAWING", "INDICATOR").contains(kind)) throw new UserInputException("Alerts can be set on drawings and indicators.");
        if (command.sourceId() == null || !SOURCE_ID.matcher(command.sourceId()).matches()) throw new IllegalArgumentException("Invalid alert source.");
        String label = label(command.label());
        String condition = upper(command.condition());
        String frequency = upper(command.frequency());
        if (!Set.of("ONCE", "EVERY_BAR").contains(frequency)) throw new UserInputException("Choose how often the alert can trigger.");
        String definition = definition(command.definition());
        Rule rule = rule(kind, read(definition));
        requireCondition(rule, condition);
        Integer active = db.queryForObject("select count(*) from chart_alerts where user_id = ? and active", Integer.class, userId);
        if (active != null && active >= MAX_ACTIVE_PER_USER) {
            throw new ConflictException("You already have " + MAX_ACTIVE_PER_USER + " active chart alerts. Delete or switch off one first.");
        }
        Long id = db.queryForObject("""
                insert into chart_alerts (user_id, symbol, interval, source_kind, source_id, label, condition, definition,
                                          frequency, last_checked_timestamp, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, userId, symbol, interval.name(), kind, command.sourceId(), label, condition, definition,
                frequency, latestCompletedTimestamp(symbol, interval), LocalDateTime.now(), LocalDateTime.now());
        return find(userId, id);
    }

    /** A drawing was moved or edited: the alert follows its new shape from the next candle on. */
    @Transactional
    public AlertView update(long userId, long id, String rawLabel, JsonNode rawDefinition) {
        AlertView existing = find(userId, id);
        String label = rawLabel == null ? existing.label() : label(rawLabel);
        String definition = rawDefinition == null ? write(existing.definition()) : definition(rawDefinition);
        requireCondition(rule(existing.sourceKind(), read(definition)), existing.condition());
        db.update("update chart_alerts set label = ?, definition = ?, updated_at = current_timestamp where id = ? and user_id = ?",
                label, definition, id, userId);
        return find(userId, id);
    }

    @Transactional
    public AlertView setActive(long userId, long id, boolean active) {
        AlertView existing = find(userId, id);
        if (active && !existing.active()) {
            Integer count = db.queryForObject("select count(*) from chart_alerts where user_id = ? and active", Integer.class, userId);
            if (count != null && count >= MAX_ACTIVE_PER_USER) {
                throw new ConflictException("You already have " + MAX_ACTIVE_PER_USER + " active chart alerts. Delete or switch off one first.");
            }
            // Switching an alert back on starts from now; candles it missed while off cannot trigger it.
            TimeInterval interval = interval(existing.interval());
            db.update("update chart_alerts set active = true, last_checked_timestamp = ?, updated_at = current_timestamp where id = ?",
                    latestCompletedTimestamp(existing.symbol(), interval), id);
        } else if (!active) {
            db.update("update chart_alerts set active = false, updated_at = current_timestamp where id = ?", id);
        }
        return find(userId, id);
    }

    @Transactional
    public void delete(long userId, long id) {
        db.update("delete from chart_alerts where id = ? and user_id = ?", id, userId);
    }

    /** Alerts set on a drawing that no longer exists are removed with it. */
    @Transactional
    public int deleteForSource(long userId, String rawSymbol, String sourceId) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        return db.update("delete from chart_alerts where user_id = ? and symbol = ? and source_id = ?", userId, symbol, sourceId);
    }

    private AlertView find(long userId, long id) {
        List<AlertView> found = db.query("""
                select id, symbol, interval, source_kind, source_id, label, condition, frequency, definition, active,
                       trigger_count, last_triggered_at, created_at
                from chart_alerts where id = ? and user_id = ?
                """, (row, index) -> new AlertView(row.getLong("id"), row.getString("symbol"),
                chartInterval(TimeInterval.valueOf(row.getString("interval"))), row.getString("source_kind"),
                row.getString("source_id"), row.getString("label"), row.getString("condition"), row.getString("frequency"),
                read(row.getString("definition")), row.getBoolean("active"), row.getInt("trigger_count"),
                toLocal(row.getTimestamp("last_triggered_at")), toLocal(row.getTimestamp("created_at"))), id, userId);
        if (found.isEmpty()) throw new UserInputException("The chart alert was not found.");
        return found.getFirst();
    }

    // ------------------------------------------------------------------ Latest signals

    private static final String EVENT_QUERY = """
            select e.id, a.symbol, s.company_name, a.interval, a.label, a.source_kind, a.condition, e.value, e.level,
                   e.close_price, e.candle_timestamp, e.created_at
            from chart_alert_events e
            join chart_alerts a on a.id = e.alert_id
            left join stock_assets s on upper(s.ticker_symbol) = a.symbol
            """;

    @Transactional(readOnly = true)
    public List<LatestChartAlertView> latestUnread(long userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        return db.query(EVENT_QUERY + " where a.user_id = ? and e.read_at is null and e.deleted_at is null order by e.created_at desc, e.id desc limit ?",
                ChartAlertService::eventView, userId, limit);
    }

    /** One trigger of the user's alerts, for the chart that shows it. */
    @Transactional(readOnly = true)
    public LatestChartAlertView event(long userId, long eventId) {
        List<LatestChartAlertView> found = db.query(EVENT_QUERY + " where e.id = ? and a.user_id = ?",
                ChartAlertService::eventView, eventId, userId);
        if (found.isEmpty()) throw new UserInputException("The chart alert was not found.");
        return found.getFirst();
    }

    @Transactional
    public void markRead(long userId, long eventId) {
        int updated = db.update("""
                update chart_alert_events e set read_at = coalesce(e.read_at, current_timestamp) from chart_alerts a
                where a.id = e.alert_id and e.id = ? and a.user_id = ?
                """, eventId, userId);
        if (updated == 0) throw new UserInputException("The chart alert was not found.");
    }

    private static LatestChartAlertView eventView(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new LatestChartAlertView(row.getLong("id"), row.getString("symbol"), row.getString("company_name"),
                TimeInterval.valueOf(row.getString("interval")), row.getString("label"), row.getString("source_kind"),
                row.getString("condition"), nullable(row.getObject("value")), nullable(row.getObject("level")),
                row.getDouble("close_price"), row.getLong("candle_timestamp"), toLocal(row.getTimestamp("created_at")));
    }

    @Transactional
    public int markAllRead(long userId) {
        return db.update("""
                update chart_alert_events e set read_at = current_timestamp from chart_alerts a
                where a.id = e.alert_id and a.user_id = ? and e.read_at is null and e.deleted_at is null
                """, userId);
    }

    // ------------------------------------------------------------------ evaluation

    /** Called by the scheduled check after the symbol's candles were refreshed. */
    @Transactional
    public EvaluationResult evaluate(String rawSymbol, TimeInterval interval) {
        if (!INTERVALS.containsValue(interval)) return new EvaluationResult(0, 0);
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        List<StoredAlert> alerts = db.query("""
                select id, user_id, label, source_kind, condition, frequency, definition, last_checked_timestamp
                from chart_alerts where symbol = ? and interval = ? and active order by id
                """, (row, index) -> new StoredAlert(row.getLong("id"), row.getLong("user_id"), row.getString("label"),
                row.getString("source_kind"), row.getString("condition"), row.getString("frequency"),
                row.getString("definition"), (Long) row.getObject("last_checked_timestamp")), symbol, interval.name());
        if (alerts.isEmpty()) return new EvaluationResult(0, 0);
        List<Candle> series = completedCandles(symbol, interval);
        if (series.size() < 2) return new EvaluationResult(0, 0);
        long latest = series.getLast().getTimestamp();
        int triggered = 0;
        for (StoredAlert alert : alerts) {
            try {
                triggered += evaluate(alert, symbol, interval, series);
            } catch (RuntimeException failure) {
                log.warn("Chart alert {} for {} {} could not be evaluated", alert.id(), symbol, interval, failure);
            }
            db.update("update chart_alerts set last_checked_timestamp = greatest(coalesce(last_checked_timestamp, 0), ?) where id = ?",
                    latest, alert.id());
        }
        return new EvaluationResult(alerts.size(), triggered);
    }

    private int evaluate(StoredAlert alert, String symbol, TimeInterval interval, List<Candle> series) {
        // A new alert only watches candles that complete after it was set.
        if (alert.lastChecked() == null) return 0;
        int last = series.size() - 1;
        int start = last + 1;
        for (int index = last; index >= 0 && series.get(index).getTimestamp() > alert.lastChecked(); index--) start = index;
        if (start > last) return 0;
        start = Math.max(1, Math.max(start, last - MAX_CATCH_UP + 1));
        Rule rule = rule(alert.sourceKind(), read(alert.definition()));
        Series values = series(rule, symbol, interval, series, start - 1);
        int triggered = 0;
        for (int index = start; index <= last; index++) {
            if (!matches(alert.condition(), values, index)) continue;
            Candle candle = series.get(index);
            double value = values.value()[index];
            double level = values.level() == null ? Double.NaN : values.level()[index];
            List<Long> inserted = db.queryForList("""
                    insert into chart_alert_events (alert_id, candle_timestamp, close_price, value, level, created_at)
                    values (?, ?, ?, ?, ?, ?) on conflict (alert_id, candle_timestamp) do nothing returning id
                    """, Long.class, alert.id(), candle.getTimestamp(), candle.getClosePrice(),
                    Double.isFinite(value) ? value : null, Double.isFinite(level) ? level : null, LocalDateTime.now());
            if (inserted.isEmpty()) continue;
            triggered++;
            boolean once = "ONCE".equals(alert.frequency());
            db.update("""
                    update chart_alerts set trigger_count = trigger_count + 1, last_triggered_at = ?,
                           active = case when ? then false else active end, updated_at = current_timestamp where id = ?
                    """, LocalDateTime.now(), once, alert.id());
            email(alert, symbol, interval, candle, value, level, inserted.getFirst(), once);
            if (once) break;
        }
        return triggered;
    }

    /** Values per candle: what is compared (the close or an indicator output) and its level or band. */
    record Series(double[] value, double[] level, double[] lower, double[] upper) { }

    static boolean matches(String condition, Series series, int index) {
        double now = series.value()[index];
        double before = series.value()[index - 1];
        return switch (condition) {
            case "CROSSES_UP" -> crossesUp(before, now, series.level()[index - 1], series.level()[index]);
            case "CROSSES_DOWN" -> crossesDown(before, now, series.level()[index - 1], series.level()[index]);
            case "CROSSES" -> crossesUp(before, now, series.level()[index - 1], series.level()[index])
                    || crossesDown(before, now, series.level()[index - 1], series.level()[index]);
            case "ABOVE" -> finite(now, series.level()[index]) && now > series.level()[index];
            case "BELOW" -> finite(now, series.level()[index]) && now < series.level()[index];
            case "INSIDE" -> Boolean.TRUE.equals(inside(series, index));
            case "OUTSIDE" -> Boolean.FALSE.equals(inside(series, index));
            case "ENTERS" -> Boolean.FALSE.equals(inside(series, index - 1)) && Boolean.TRUE.equals(inside(series, index));
            case "EXITS" -> Boolean.TRUE.equals(inside(series, index - 1)) && Boolean.FALSE.equals(inside(series, index));
            default -> false;
        };
    }

    private static boolean crossesUp(double before, double now, double levelBefore, double levelNow) {
        return finite(before, now) && finite(levelBefore, levelNow) && before <= levelBefore && now > levelNow;
    }

    private static boolean crossesDown(double before, double now, double levelBefore, double levelNow) {
        return finite(before, now) && finite(levelBefore, levelNow) && before >= levelBefore && now < levelNow;
    }

    /** Null where the band does not exist at that candle (outside a rectangle's dates). */
    private static Boolean inside(Series series, int index) {
        double value = series.value()[index];
        double low = series.lower()[index];
        double high = series.upper()[index];
        if (!finite(value, low) || !Double.isFinite(high)) return null;
        return value >= Math.min(low, high) && value <= Math.max(low, high);
    }

    private static boolean finite(double first, double second) {
        return Double.isFinite(first) && Double.isFinite(second);
    }

    private Series series(Rule rule, String symbol, TimeInterval interval, List<Candle> series, int from) {
        int size = series.size();
        double[] close = series.stream().mapToDouble(Candle::getClosePrice).toArray();
        if (rule instanceof IndicatorRule indicator) return indicatorSeries(indicator, symbol, interval, series, close, from);
        BarClock clock = new BarClock(series.stream().mapToLong(Candle::getTimestamp).toArray(), chartInterval(interval));
        double[] level = filled(size);
        double[] lower = filled(size);
        double[] upper = filled(size);
        for (int index = Math.max(0, from); index < size; index++) {
            switch (rule) {
                case LineRule line -> level[index] = line.valueAt(clock, index);
                case ChannelRule channel -> {
                    lower[index] = channel.lower().valueAt(clock, index);
                    upper[index] = channel.upper().valueAt(clock, index);
                }
                case BoxRule box -> {
                    if (box.covers(clock, index)) {
                        lower[index] = box.bottom();
                        upper[index] = box.top();
                    }
                }
                default -> { }
            }
        }
        return new Series(close, level, lower, upper);
    }

    private Series indicatorSeries(IndicatorRule rule, String symbol, TimeInterval interval, List<Candle> series,
                                   double[] close, int from) {
        if (indicators == null) throw new IllegalStateException("Chart indicators are unavailable.");
        int size = series.size();
        IndicatorBatchView batch = indicators.calculate(symbol, chartInterval(interval),
                new IndicatorBatchRequest(series.get(Math.max(0, from)).getTimestamp(), series.getLast().getTimestamp(), rule.configurations()));
        IndicatorConfigurationView view = batch.indicators().stream().filter(item -> rule.targetId().equals(item.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The indicator was not calculated."));
        if (view.error() != null) throw new IllegalStateException("The indicator could not be calculated: " + view.error());
        Map<Long, Integer> indexByTime = new HashMap<>();
        for (int index = 0; index < size; index++) indexByTime.put(series.get(index).getTimestamp(), index);
        double[] value = outputValues(view, rule.output(), indexByTime, size);
        double[] level = filled(size);
        double[] lower = filled(size);
        double[] upper = filled(size);
        switch (rule.compare()) {
            case "VALUE" -> Arrays.fill(level, rule.value());
            case "PRICE" -> System.arraycopy(close, 0, level, 0, size);
            case "OUTPUT" -> level = outputValues(view, rule.otherOutput(), indexByTime, size);
            case "CHANNEL" -> {
                Arrays.fill(lower, rule.lower());
                Arrays.fill(upper, rule.upper());
            }
            default -> throw new IllegalStateException("Unknown comparison " + rule.compare());
        }
        return new Series(value, level, lower, upper);
    }

    private static double[] outputValues(IndicatorConfigurationView view, String output, Map<Long, Integer> indexByTime, int size) {
        double[] values = filled(size);
        view.series().stream().filter(series -> series.key().equals(output)).findFirst().ifPresent(series ->
                series.points().forEach(point -> {
                    Integer index = indexByTime.get(point.timestamp());
                    if (index != null) values[index] = point.value();
                }));
        return values;
    }

    private static double[] filled(int size) {
        double[] values = new double[size];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private List<Candle> completedCandles(String symbol, TimeInterval interval) {
        long boundary = completion.firstIncompleteCandleTimestamp(interval);
        return candles.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, chartInterval(interval)).stream()
                .filter(candle -> candle.getTimestamp() < boundary && candle.getClosePrice() != null
                        && Double.isFinite(candle.getClosePrice()))
                .toList();
    }

    private Long latestCompletedTimestamp(String symbol, TimeInterval interval) {
        List<Candle> series = completedCandles(symbol, interval);
        return series.isEmpty() ? null : series.getLast().getTimestamp();
    }

    // ------------------------------------------------------------------ email

    private void email(StoredAlert alert, String symbol, TimeInterval interval, Candle candle, double value, double level,
                       long eventId, boolean once) {
        if (notifications == null) return;
        User user = users.findById(alert.userId()).orElse(null);
        if (user == null) return;
        StringBuilder body = new StringBuilder()
                .append("Chart alert: ").append(alert.label()).append('\n')
                .append(conditionSentence(alert.condition(), alert.sourceKind())).append('\n')
                .append("Completed candle: ").append(CANDLE_DATE.format(LocalDate.ofInstant(Instant.ofEpochSecond(candle.getTimestamp()), ZoneOffset.UTC))).append('\n')
                .append("Close: ").append(number(candle.getClosePrice())).append('\n');
        if ("INDICATOR".equals(alert.sourceKind()) && Double.isFinite(value)) body.append("Indicator value: ").append(number(value)).append('\n');
        if (Double.isFinite(level)) body.append("Level: ").append(number(level)).append('\n');
        body.append(once ? "This alert is now switched off." : "This alert stays on and can trigger again on later candles.").append('\n')
                .append("Open the chart: ").append(publicBaseUrl).append("/stock/").append(symbol);
        try {
            if (notifications.sendChartAlertEmail(user, interval, "Chart alert for " + symbol + ": " + alert.label(), body.toString(), eventId)) {
                db.update("update chart_alert_events set email_queued_at = current_timestamp where id = ?", eventId);
            }
        } catch (RuntimeException failure) {
            log.warn("Chart alert email for event {} could not be queued", eventId, failure);
        }
    }

    static String conditionSentence(String condition, String sourceKind) {
        boolean drawing = "DRAWING".equals(sourceKind);
        return switch (condition) {
            case "CROSSES" -> drawing ? "The close crossed the line." : "The indicator crossed the level.";
            case "CROSSES_UP" -> drawing ? "The close crossed above the line." : "The indicator crossed above the level.";
            case "CROSSES_DOWN" -> drawing ? "The close crossed below the line." : "The indicator crossed below the level.";
            case "ABOVE" -> drawing ? "The close is above the line." : "The indicator is above the level.";
            case "BELOW" -> drawing ? "The close is below the line." : "The indicator is below the level.";
            case "ENTERS" -> drawing ? "The close entered the shape." : "The indicator entered the channel.";
            case "EXITS" -> drawing ? "The close left the shape." : "The indicator left the channel.";
            case "INSIDE" -> drawing ? "The close is inside the shape." : "The indicator is inside the channel.";
            case "OUTSIDE" -> drawing ? "The close is outside the shape." : "The indicator is outside the channel.";
            default -> "The alert condition was met.";
        };
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, Math.abs(value) < 1 ? "%.4f" : "%.2f", value);
    }

    // ------------------------------------------------------------------ rules

    record StoredAlert(long id, long userId, String label, String sourceKind, String condition, String frequency,
                       String definition, Long lastChecked) { }

    sealed interface Rule permits LineRule, ChannelRule, BoxRule, IndicatorRule { }

    record Anchor(long time, double price) { }

    /** A straight line through two anchors in bar-by-bar space, as the chart draws it; horizontal when {@code a} has no time. */
    record LineRule(Anchor a, Anchor b, boolean extendLeft, boolean extendRight, boolean horizontal) implements Rule {
        double valueAt(BarClock clock, int index) {
            if (horizontal) {
                return a.time() == Long.MIN_VALUE || index >= clock.index(a.time()) - 1e-9 ? a.price() : Double.NaN;
            }
            double first = clock.index(a.time());
            double second = clock.index(b.time());
            if (!Double.isFinite(first) || !Double.isFinite(second) || Math.abs(second - first) < 1e-9) return Double.NaN;
            if (!extendLeft && index < Math.min(first, second) - 1e-9) return Double.NaN;
            if (!extendRight && index > Math.max(first, second) + 1e-9) return Double.NaN;
            return a.price() + (b.price() - a.price()) * (index - first) / (second - first);
        }
    }

    record ChannelRule(LineRule upper, LineRule lower) implements Rule { }

    record BoxRule(long from, long to, double top, double bottom, boolean extendLeft, boolean extendRight) implements Rule {
        boolean covers(BarClock clock, int index) {
            double first = clock.index(Math.min(from, to));
            double last = clock.index(Math.max(from, to));
            return (extendLeft || index >= first - 1e-9) && (extendRight || index <= last + 1e-9);
        }
    }

    record IndicatorRule(List<IndicatorConfiguration> configurations, String targetId, String output, String compare,
                         double value, String otherOutput, double lower, double upper) implements Rule { }

    Rule rule(String kind, JsonNode definition) {
        if (definition == null || !definition.isObject()) throw new UserInputException("The alert definition is incomplete.");
        if ("INDICATOR".equals(kind)) return indicatorRule(definition);
        String geometry = definition.path("geometry").asString("");
        boolean extendLeft = definition.path("extendLeft").asBoolean(false);
        boolean extendRight = definition.path("extendRight").asBoolean(false);
        return switch (geometry) {
            case "HORIZONTAL" -> {
                double price = price(definition.path("price"));
                long from = definition.path("from").isNumber() ? time(definition.path("from")) : Long.MIN_VALUE;
                yield new LineRule(new Anchor(from, price), new Anchor(from, price), false, true, true);
            }
            case "LINE" -> line(definition.path("a"), definition.path("b"), extendLeft, extendRight);
            case "CHANNEL" -> new ChannelRule(line(definition.path("upper").path("a"), definition.path("upper").path("b"), extendLeft, extendRight),
                    line(definition.path("lower").path("a"), definition.path("lower").path("b"), extendLeft, extendRight));
            case "BOX" -> {
                long from = time(definition.path("from"));
                long to = time(definition.path("to"));
                if (from == to) throw new UserInputException("The rectangle needs a width to set an alert on it.");
                yield new BoxRule(from, to, price(definition.path("top")), price(definition.path("bottom")), extendLeft, extendRight);
            }
            default -> throw new UserInputException("Alerts can be set on lines, channels and rectangles.");
        };
    }

    private LineRule line(JsonNode first, JsonNode second, boolean extendLeft, boolean extendRight) {
        Anchor a = new Anchor(time(first.path("time")), price(first.path("price")));
        Anchor b = new Anchor(time(second.path("time")), price(second.path("price")));
        if (a.time() == b.time()) throw new UserInputException("A vertical line cannot be crossed by the price.");
        return new LineRule(a, b, extendLeft, extendRight, false);
    }

    private IndicatorRule indicatorRule(JsonNode definition) {
        JsonNode list = definition.path("indicators");
        if (!list.isArray() || list.isEmpty() || list.size() > 8) throw new UserInputException("The alert definition is incomplete.");
        List<IndicatorConfiguration> configurations = new ArrayList<>();
        for (JsonNode node : list) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            node.path("parameters").properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                parameters.put(entry.getKey(), value.isNumber() ? (Object) value.numberValue()
                        : value.isBoolean() ? (Object) value.asBoolean() : value.asString());
            });
            configurations.add(new IndicatorConfiguration(node.path("id").asString(""), node.path("type").asString(""), parameters));
        }
        String target = definition.path("target").asString(configurations.getLast().id());
        if (configurations.stream().noneMatch(config -> config.id().equals(target))) throw new UserInputException("The alert definition is incomplete.");
        String output = definition.path("output").asString("");
        if (output.isBlank()) throw new UserInputException("Choose the indicator line the alert watches.");
        String compare = definition.path("compare").asString("");
        double value = Double.NaN;
        double lower = Double.NaN;
        double upper = Double.NaN;
        String other = null;
        switch (compare) {
            case "VALUE" -> value = number(definition.path("value"), "Enter the value the indicator is compared with.");
            case "OUTPUT" -> {
                other = definition.path("otherOutput").asString("");
                if (other.isBlank() || other.equals(output)) throw new UserInputException("Choose another indicator line to compare with.");
            }
            case "PRICE" -> { }
            case "CHANNEL" -> {
                lower = number(definition.path("lower"), "Enter both channel levels.");
                upper = number(definition.path("upper"), "Enter both channel levels.");
                if (lower >= upper) throw new UserInputException("The lower channel level must be below the upper one.");
            }
            default -> throw new UserInputException("Choose what the indicator is compared with.");
        }
        return new IndicatorRule(List.copyOf(configurations), target, output, compare, value, other, lower, upper);
    }

    private static void requireCondition(Rule rule, String condition) {
        boolean band = rule instanceof ChannelRule || rule instanceof BoxRule
                || rule instanceof IndicatorRule indicator && "CHANNEL".equals(indicator.compare());
        if (!(band ? BAND_CONDITIONS : LEVEL_CONDITIONS).contains(condition)) {
            throw new UserInputException("This condition does not apply to the chosen line or shape.");
        }
    }

    private static long time(JsonNode node) {
        if (!node.isNumber()) throw new UserInputException("The alert definition is incomplete.");
        long value = node.asLong();
        if (value < 0 || value > 32_503_680_000L) throw new UserInputException("The alert definition is incomplete.");
        return value;
    }

    private static double price(JsonNode node) {
        if (!node.isNumber() || !Double.isFinite(node.asDouble())) throw new UserInputException("The alert definition is incomplete.");
        return node.asDouble();
    }

    private static double number(JsonNode node, String message) {
        if (!node.isNumber() || !Double.isFinite(node.asDouble())) throw new UserInputException(message);
        return node.asDouble();
    }

    // ------------------------------------------------------------------ bar clock

    /**
     * Bar positions of timestamps, matching the chart's own mapping: candles are bars, times between them
     * interpolate, and times beyond the data count trading days (or weeks, or months) from the last bar.
     */
    static final class BarClock {
        private final long[] times;
        private final String mode;
        private final double step;

        BarClock(long[] times, String interval) {
            this.times = times;
            String detected = "fixed";
            double median = DAY;
            if (times.length >= 2) {
                List<Long> gaps = new ArrayList<>();
                for (int index = Math.max(1, times.length - 120); index < times.length; index++) gaps.add(times[index] - times[index - 1]);
                gaps.sort(Long::compare);
                median = gaps.get(gaps.size() / 2);
                if (median <= 1.5 * DAY) {
                    detected = gaps.stream().anyMatch(gap -> gap >= 2.5 * DAY && gap <= 4.5 * DAY) ? "business" : "fixed";
                } else if (median >= 5.5 * DAY && median <= 8.5 * DAY) {
                    detected = "week";
                } else if (median >= 26 * DAY && median <= 33 * DAY) {
                    detected = "month";
                }
            }
            this.mode = detected;
            this.step = median;
            if (!"1d".equals(interval) && !"1wk".equals(interval) && !"1mo".equals(interval)) {
                throw new IllegalArgumentException("Unsupported interval " + interval);
            }
        }

        double index(long seconds) {
            int last = times.length - 1;
            if (last < 0) return Double.NaN;
            if (seconds <= times[0]) return seconds == times[0] ? 0 : -steps(seconds, times[0]);
            if (seconds >= times[last]) return last + steps(times[last], seconds);
            int low = 0;
            int high = last;
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (times[middle] <= seconds) low = middle; else high = middle;
            }
            long span = times[high] - times[low];
            return low + (span > 0 ? (double) (seconds - times[low]) / span : 0);
        }

        private double steps(long from, long to) {
            if (to <= from) return 0;
            return switch (mode) {
                case "business" -> {
                    long days = (to - from) / DAY;
                    long count = days / 7 * 5;
                    long cursor = from + days / 7 * 7 * DAY;
                    while (cursor + DAY <= to) {
                        cursor += DAY;
                        DayOfWeek day = Instant.ofEpochSecond(cursor).atZone(ZoneOffset.UTC).getDayOfWeek();
                        if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) count++;
                    }
                    yield count + (double) ((to - from) % DAY) / DAY;
                }
                case "week" -> (double) (to - from) / (7 * DAY);
                case "month" -> {
                    LocalDate a = LocalDate.ofInstant(Instant.ofEpochSecond(from), ZoneOffset.UTC);
                    LocalDate b = LocalDate.ofInstant(Instant.ofEpochSecond(to), ZoneOffset.UTC);
                    yield (b.getYear() - a.getYear()) * 12 + (b.getMonthValue() - a.getMonthValue()) + (b.getDayOfMonth() - a.getDayOfMonth()) / 30.44;
                }
                default -> (to - from) / step;
            };
        }
    }

    // ------------------------------------------------------------------ helpers

    private static TimeInterval interval(String raw) {
        TimeInterval interval = raw == null ? null : INTERVALS.get(raw.trim());
        if (interval == null) throw new UserInputException("Alerts can be set on daily, weekly and monthly charts.");
        return interval;
    }

    static String chartInterval(TimeInterval interval) {
        return switch (interval) {
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            default -> "1d";
        };
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String label(String raw) {
        String label = raw == null ? "" : raw.strip();
        if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH || label.chars().anyMatch(Character::isISOControl)) {
            throw new UserInputException("Name the alert in 1 to " + MAX_LABEL_LENGTH + " characters.");
        }
        return label;
    }

    private String definition(JsonNode node) {
        if (node == null || !node.isObject()) throw new UserInputException("The alert definition is incomplete.");
        String text = write(node);
        if (text.length() > MAX_DEFINITION_LENGTH) throw new UserInputException("The alert definition is too large.");
        return text;
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("A stored chart alert is not valid JSON.", invalid);
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("The chart alert could not be saved.", invalid);
        }
    }

    private static LocalDateTime toLocal(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Double nullable(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
