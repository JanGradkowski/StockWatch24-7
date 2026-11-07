package org.example.stockwatch247.service.customalert;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.CandleCompletionService;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorBatchRequest;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorConfiguration;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.HarmonicPatternDetectionService;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Operand;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.ValueCondition;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.PatternHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a trigger's chart draws: the candles around the trigger, the indicator lines its rule read, and for each
 * condition what decided it (a pattern's candles or turning points, the filings it counted). The rule is read as
 * it stood when the trigger was recorded, from the definition kept with the evidence, and patterns are read as of
 * the trigger candle under the owner's detection settings.
 */
@Service
public class CustomAlertTriggerChart {
    private static final Logger log = LoggerFactory.getLogger(CustomAlertTriggerChart.class);
    /** Candles shown before the trigger, and at most after it. */
    static final int BEFORE = 120;
    static final int AFTER = 40;
    /** A wave count, formation or filing that began earlier widens the chart to it, up to this many candles. */
    static final int MAX_BEFORE = 400;

    private final CustomAlertService alerts;
    private final JdbcTemplate db;
    private final ChartIndicatorCatalog catalog;
    private final StockAssetRepository assets;
    private final CustomAlertFactsLoader facts;
    private final CandleCompletionService completion;
    private ChartTechnicalIndicatorService indicators;

    public CustomAlertTriggerChart(CustomAlertService alerts, JdbcTemplate db, ChartIndicatorCatalog catalog,
                                   StockAssetRepository assets, CustomAlertFactsLoader facts, CandleCompletionService completion) {
        this.alerts = alerts;
        this.db = db;
        this.catalog = catalog;
        this.assets = assets;
        this.facts = facts;
        this.completion = completion;
    }

    @Autowired(required = false)
    void configure(ChartTechnicalIndicatorService indicators) {
        this.indicators = indicators;
    }

    /**
     * {@code candles}: [time, open, high, low, close, volume] oldest first, completed candles only. {@code series}:
     * the indicator lines the rule read. {@code conditions}: by condition id, what to draw for it. {@code triggers}:
     * this alert's other triggers on the stock within the chart.
     */
    public record ChartView(String interval, long trigger, List<Number[]> candles, List<SeriesView> series,
                            Map<String, ConditionView> conditions, List<OtherTrigger> triggers) { }

    /** One indicator line: drawn over the candles (PRICE), in its own pane (PANEL) or over volume (VOLUME). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeriesView(String id, String placement, String style, List<Number[]> points, List<Double> references,
                             String error) { }

    /**
     * Which series a value condition reads ({@code subject}, {@code compare}); how many candles a candlestick
     * pattern spans; a wave count's or formation's turning points; the filings an activity condition counted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConditionView(String subject, String compare, Integer formationCandles, List<PointView> points,
                                List<FilingView> filings) { }

    public record PointView(String label, long time, double price, String pivot) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FilingView(long id, String date, String person, String role, String transaction, String amount) { }

    public record OtherTrigger(long id, long candle) { }

    public ChartView chart(User user, long eventId) {
        CustomAlertService.TriggerView trigger = alerts.trigger(user, eventId);
        StockAsset asset = assets.findByTickerSymbolIgnoreCase(trigger.symbol())
                .orElseThrow(() -> new UserInputException("The custom alert trigger was not found."));
        String interval = CustomAlertFactsLoader.apiInterval(trigger.interval());
        long at = trigger.candleTimestamp();
        List<Number[]> before = candles("timestamp <= ? order by timestamp desc", trigger.symbol(), interval, at, MAX_BEFORE + 1);
        Collections.reverse(before);
        List<Number[]> after = candles("timestamp > ? and timestamp < " + completion.firstIncompleteCandleTimestamp(trigger.interval())
                + " order by timestamp", trigger.symbol(), interval, at, AFTER);

        JsonNode evidence = trigger.evidence() == null ? MissingNode.getInstance() : trigger.evidence();
        Map<String, String> families = new LinkedHashMap<>();
        for (JsonNode group : evidence.path("definition").path("groups")) {
            for (JsonNode condition : group.path("conditions")) {
                families.put(condition.path("id").asString(""), condition.path("family").asString(""));
            }
        }
        Map<String, ConditionView> conditions = new LinkedHashMap<>();
        PatternSource patterns = new PatternSource(asset, trigger, user);
        long earliest = Long.MAX_VALUE;
        for (JsonNode result : evidence.path("conditions")) {
            String id = result.path("id").asString("");
            JsonNode found = result.path("facts");
            ConditionView view = switch (result.path("kind").asString("")) {
                case "PATTERN" -> pattern(families.getOrDefault(id, ""), found, patterns);
                case "INSIDER", "CONGRESS" -> filings(result.path("kind").asString(""), found, asset.getId());
                default -> null;
            };
            // A step of a sequence: its whole stretch is drawn, from its first candle.
            if (found.path("from").isNumber()) earliest = Math.min(earliest, found.path("from").asLong());
            if (view == null) continue;
            conditions.put(id, view);
            if (view.points() != null) for (PointView point : view.points()) earliest = Math.min(earliest, point.time());
            if (view.filings() != null) for (FilingView filing : view.filings()) earliest = Math.min(earliest, epoch(filing.date()));
        }
        // The usual context, widened to the first point of whatever the conditions drew.
        int first = Math.max(0, before.size() - 1 - BEFORE);
        while (first > 0 && before.get(first)[0].longValue() > earliest) first--;
        List<Number[]> shown = new ArrayList<>(before.subList(first, before.size()));
        shown.addAll(after);

        List<SeriesView> series = new ArrayList<>();
        CustomAlertDefinition definition = definition(evidence, trigger);
        if (definition != null && !shown.isEmpty()) {
            series = series(definition, trigger.symbol(), interval, shown, conditions);
        }
        List<OtherTrigger> others = shown.isEmpty() ? List.of() : db.query("""
                select id, candle_timestamp from custom_alert_events where alert_id = ? and stock_asset_id = ? and id <> ?
                  and deleted_at is null and candle_timestamp between ? and ? order by candle_timestamp, id
                """, (row, index) -> new OtherTrigger(row.getLong(1), row.getLong(2)), trigger.alertId(), asset.getId(), trigger.id(),
                shown.getFirst()[0].longValue(), shown.getLast()[0].longValue());
        return new ChartView(trigger.interval().name(), at, shown, series, conditions, others);
    }

    private List<Number[]> candles(String where, String symbol, String interval, long at, int limit) {
        return new ArrayList<>(db.query("""
                select timestamp, open_price, high_price, low_price, close_price, volume from candles
                where symbol = ? and time_interval = ? and open_price is not null and high_price is not null
                  and low_price is not null and close_price is not null and\s""" + where + " limit ?",
                (row, index) -> new Number[]{row.getLong(1), row.getDouble(2), row.getDouble(3), row.getDouble(4), row.getDouble(5),
                        row.getObject(6) == null ? null : row.getLong(6)}, symbol, interval, at, limit));
    }

    /** The rule as recorded with the trigger; null when it no longer reads (an indicator left the catalog). */
    private CustomAlertDefinition definition(JsonNode evidence, CustomAlertService.TriggerView trigger) {
        if (!evidence.path("definition").isObject()) return null;
        try {
            return CustomAlertDefinition.parse(evidence.path("definition"), catalog, trigger.interval());
        } catch (RuntimeException unreadable) {
            log.debug("Trigger {} rule no longer reads: {}", trigger.id(), unreadable.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ indicator lines

    /** Each indicator the value conditions read, once, and which of them each condition reads. */
    private List<SeriesView> series(CustomAlertDefinition definition, String symbol, String interval, List<Number[]> shown,
                                    Map<String, ConditionView> conditions) {
        Map<String, Operand> operands = new LinkedHashMap<>();
        Map<String, IndicatorConfiguration> configurations = new LinkedHashMap<>();
        Map<String, String> configurationOf = new LinkedHashMap<>();
        for (CustomAlertDefinition.Condition condition : definition.conditions()) {
            if (!(condition instanceof ValueCondition value)) continue;
            String subject = register(value.subject(), operands, configurations, configurationOf);
            String compare = value.compare() == null ? null : register(value.compare(), operands, configurations, configurationOf);
            if (subject != null || compare != null) conditions.put(value.id(), new ConditionView(subject, compare, null, null, null));
        }
        if (operands.isEmpty()) return List.of();
        long from = shown.getFirst()[0].longValue();
        long to = shown.getLast()[0].longValue();
        Map<String, ChartTechnicalIndicatorService.IndicatorConfigurationView> views = new LinkedHashMap<>();
        if (indicators != null) {
            try {
                indicators.calculate(symbol, interval, new IndicatorBatchRequest(from, to, List.copyOf(configurations.values())))
                        .indicators().forEach(view -> views.put(view.id(), view));
            } catch (RuntimeException unavailable) {
                log.warn("Trigger chart indicators unavailable for {} {}", symbol, interval, unavailable);
            }
        }
        List<SeriesView> series = new ArrayList<>();
        operands.forEach((id, operand) -> {
            var view = views.get(configurationOf.get(id));
            if (view == null || view.error() != null) {
                series.add(new SeriesView(id, view == null ? null : view.placement(), null, List.of(), List.of(),
                        view == null ? "unavailable" : view.error()));
                return;
            }
            var line = view.series().stream().filter(item -> item.key().equals(operand.output())).findFirst().orElse(null);
            List<Number[]> points = line == null ? List.of() : line.points().stream()
                    .filter(point -> point.timestamp() >= from && point.timestamp() <= to && Double.isFinite(point.value()))
                    .map(point -> new Number[]{point.timestamp(), point.value()}).toList();
            series.add(new SeriesView(id, view.placement(), line == null ? null : line.style(), points, view.references(), null));
        });
        return series;
    }

    private static String register(Operand operand, Map<String, Operand> operands, Map<String, IndicatorConfiguration> configurations,
                                   Map<String, String> configurationOf) {
        if (operand == null || !operand.isIndicator()) return null;
        for (Map.Entry<String, Operand> known : operands.entrySet()) {
            if (known.getValue().seriesKey().equals(operand.seriesKey())) return known.getKey();
        }
        String id = "s" + operands.size();
        // Lines of one indicator with the same settings (MACD and its signal) come from one calculation.
        String settings = operand.type() + ":" + new TreeMap<>(operand.parameters());
        IndicatorConfiguration configuration = configurations.computeIfAbsent(settings,
                key -> new IndicatorConfiguration("c" + configurations.size(), operand.type(), operand.parameters()));
        operands.put(id, operand);
        configurationOf.put(id, configuration.id());
        return id;
    }

    // ------------------------------------------------------------------ patterns

    private ConditionView pattern(String family, JsonNode found, PatternSource source) {
        if (!found.hasNonNull("pattern")) return null;
        String code = found.path("pattern").asString("");
        String type = found.path("type").asString("");
        long detectedAt = found.path("detectedAt").asLong(0);
        long anchorAt = found.path("anchorAt").asLong(0);
        return switch (family) {
            case "CANDLESTICK" -> {
                int candles;
                try {
                    candles = HistoricalCandlestickService.formationCandles(CandlePattern.valueOf(type));
                } catch (IllegalArgumentException unknown) {
                    candles = 0;
                }
                yield new ConditionView(null, null, Math.max(1, candles), null, null);
            }
            case "ELLIOTT_WAVE" -> new ConditionView(null, null, null, source.wave(code, type, detectedAt), null);
            case "HARMONIC_FORMATION" -> new ConditionView(null, null, null, source.formation(type, anchorAt, detectedAt), null);
            default -> null;
        };
    }

    /** Wave counts and formations as of the trigger candle, loaded once for every condition that needs them. */
    private final class PatternSource {
        private final StockAsset asset;
        private final CustomAlertService.TriggerView trigger;
        private final User owner;
        private CustomAlertFactsLoader.StockData data;

        PatternSource(StockAsset asset, CustomAlertService.TriggerView trigger, User owner) {
            this.asset = asset;
            this.trigger = trigger;
            this.owner = owner;
        }

        private CustomAlertFactsLoader.StockData data() {
            if (data == null) data = facts.load(asset, trigger.interval(), trigger.candleTimestamp() + 1);
            return data;
        }

        /** The wave count that confirmed {@code stage}, as the trigger candle's count (or, redrawn since, its own) showed it. */
        List<PointView> wave(String code, String stage, long confirmedAt) {
            try {
                var inputs = data().elliottInputs(owner);
                if (inputs == null) return null;
                for (long at : List.of(trigger.candleTimestamp(), confirmedAt)) {
                    for (ElliottWaveDetectionService.DevelopingImpulse impulse : inputs.countAt(at)) {
                        if (!impulse.pattern().name().equals(code) || !impulse.stage().name().equals(stage)
                                || impulse.confirmationTimestamp() != confirmedAt) continue;
                        return impulse.points().stream().filter(point -> point.timestamp() != null)
                                .map(point -> new PointView(point.label(), point.timestamp(), point.price(), point.pivotType())).toList();
                    }
                }
            } catch (RuntimeException unavailable) {
                log.warn("Trigger {} wave count unavailable", trigger.id(), unavailable);
            }
            return null;
        }

        /** The formation completed at {@code anchorAt}, as the alert's own detection found it. */
        List<PointView> formation(String type, long anchorAt, long confirmedAt) {
            try {
                for (PatternHit hit : data().harmonicFormations(owner)) {
                    if (!hit.type().equals(type) || hit.anchorAt() != anchorAt || hit.availableAt() != confirmedAt) continue;
                    if (hit.source() instanceof HarmonicPatternDetectionService.HarmonicFormation formation) {
                        return formation.points().stream().map(point -> new PointView(point.label(), point.timestamp(), point.price(),
                                point.pivotType() == null ? null : point.pivotType().name())).toList();
                    }
                }
            } catch (RuntimeException unavailable) {
                log.warn("Trigger {} harmonic formation unavailable", trigger.id(), unavailable);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ filings

    /** The filings the condition counted (their ids are kept with the evidence), limited to this stock. */
    private ConditionView filings(String source, JsonNode found, long assetId) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode id : found.path("ids")) if (id.isIntegralNumber()) ids.add(id.asLong());
        if (ids.isEmpty()) return new ConditionView(null, null, null, null, List.of());
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> arguments = new ArrayList<>(ids);
        arguments.addFirst(assetId);
        List<FilingView> filings = "INSIDER".equals(source)
                ? db.query("select id, filing_date, insider_name, owner_role, transaction_type, shares, transaction_price from insider_trades"
                        + " where stock_asset_id = ? and id in (" + placeholders + ") order by filing_date, id", (row, index) -> {
                    BigDecimal shares = row.getBigDecimal("shares");
                    BigDecimal price = row.getBigDecimal("transaction_price");
                    Double value = shares == null || price == null ? null : shares.multiply(price).abs().doubleValue();
                    return new FilingView(row.getLong("id"), row.getDate("filing_date").toLocalDate().toString(), row.getString("insider_name"),
                            row.getString("owner_role"), row.getString("transaction_type"),
                            value == null ? null : "$" + String.format(java.util.Locale.ROOT, "%,.0f", value));
                }, arguments.toArray())
                : db.query("select id, disclosure_date, member_name, chamber, transaction_type, amount_range from congressional_trades"
                        + " where stock_asset_id = ? and id in (" + placeholders + ") order by disclosure_date, id",
                (row, index) -> new FilingView(row.getLong("id"), row.getDate("disclosure_date").toLocalDate().toString(),
                        row.getString("member_name"), row.getString("chamber"), row.getString("transaction_type"),
                        row.getString("amount_range")), arguments.toArray());
        return new ConditionView(null, null, null, null, filings);
    }

    private static long epoch(String date) {
        return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }
}
