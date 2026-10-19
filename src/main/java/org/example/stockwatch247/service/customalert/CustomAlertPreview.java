package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.MarketDataService;
import org.example.stockwatch247.service.TrackedInstrumentGuard;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.WatchlistService;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.ConditionResult;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The builder's preview: how a draft rule reads on a test stock now, when it would have triggered over the
 * stored history, and what applying it would cost in monitoring capacity. Nothing is saved.
 *
 * <p>The history is a replay with the same evaluator and the same trigger mode as the live alert, over
 * candles already stored; it is a guide to how often the rule fires, not a promise of returns.</p>
 */
@Service
public class CustomAlertPreview {
    /** Candles replayed for the history strip: about two years of daily candles. */
    static final int HISTORY = 500;

    private final ChartIndicatorCatalog catalog;
    private final CustomAlertFactsLoader facts;
    private final StockAssetRepository assets;
    private final JdbcTemplate db;
    private final WatchlistService watchlists;
    private MarketDataService marketData;
    private TrackedInstrumentGuard guard;
    /** Time an Elliott replay may spend reading counts as they stood on each candle (newest candles first). */
    private long elliottBudgetMillis = 6000;

    public CustomAlertPreview(ChartIndicatorCatalog catalog, CustomAlertFactsLoader facts, StockAssetRepository assets,
                              JdbcTemplate db, WatchlistService watchlists) {
        this.catalog = catalog;
        this.facts = facts;
        this.assets = assets;
        this.db = db;
        this.watchlists = watchlists;
    }

    @Autowired
    void configureBudget(@org.springframework.beans.factory.annotation.Value("${custom-alerts.preview-elliott-budget-ms:6000}") long elliottBudgetMillis) {
        this.elliottBudgetMillis = Math.max(500, elliottBudgetMillis);
    }

    @Autowired(required = false)
    void configure(MarketDataService marketData, TrackedInstrumentGuard guard) {
        this.marketData = marketData;
        this.guard = guard;
    }

    public record PreviewCommand(String interval, JsonNode definition, String symbol, String triggerMode, Integer quietCandles,
                                 Integer outcomeCandles, List<CustomAlertService.TargetCommand> targets, Long alertId) { }

    public record HistoryPoint(long timestamp, double close, Double outcomeReturn) { }

    public record Coverage(long stocks, long newToMonitoring, long tracked, int maximum, long nonStocks) { }

    /**
     * {@code conditions} are the latest candle's results; {@code history} the replayed triggers with the price
     * change after the outcome window where it is known.
     */
    public record PreviewView(String symbol, String companyName, boolean available, String unavailableReason, Long latestCandle,
                              Double latestClose, boolean matched, boolean applicable, int matchedConditions, int totalConditions,
                              List<ConditionResult> conditions, List<HistoryPoint> history, long firstReplayed, long replayedCandles,
                              Double medianReturn, Double positiveShare, Coverage coverage, String shortenedBy,
                              String elliottHistory) { }

    public PreviewView preview(User user, PreviewCommand command) {
        if (command == null) throw new UserInputException("Add at least one condition.");
        TimeInterval interval = CustomAlertService.interval(command.interval());
        CustomAlertDefinition definition = CustomAlertDefinition.parse(command.definition(), catalog, interval);
        Coverage coverage = coverage(user, command.targets(), definition);
        if (command.symbol() == null || command.symbol().isBlank()) {
            return empty(null, null, "Choose a stock to test the rule on.", coverage);
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(command.symbol().trim().toUpperCase(Locale.ROOT));
        StockAsset asset = assets.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        if (asset == null) return empty(symbol, null, "Open this stock's chart once so its history is stored, then test again.", coverage);
        CustomAlertFactsLoader.StockData data = facts.load(asset, interval, null);
        if (data.candles().size() < 2 && marketData != null) {
            // A stock nobody has opened yet: load its history once, as opening its chart would.
            try {
                marketData.syncCandles(symbol, CustomAlertFactsLoader.apiInterval(interval), null);
                data = facts.load(asset, interval, null);
            } catch (RuntimeException unavailable) {
                // Reported below as unavailable.
            }
        }
        List<Candle> candles = data.candles();
        if (candles.size() < 2) {
            return empty(symbol, asset.getCompanyName(), "No completed candles are stored for this stock yet.", coverage);
        }
        int first = Math.max(1, candles.size() - HISTORY);
        data.prepare(definition.operands(), candles.size() - first + definition.lookbackCandles());
        data.coverPatterns(candles.size() - first + definition.lookbackCandles());
        data.rememberScans();
        data.replayFrom(first, elliottBudgetMillis);
        CustomAlertEvaluator.Facts ownerFacts = data.forOwner(user);
        int last = candles.size() - 1;
        Result latest = CustomAlertEvaluator.evaluate(definition, ownerFacts, last);
        // An Elliott count is replayed as it stood on each candle, within a time budget: replay only what it covered.
        Long elliottFrom = data.elliottReplayedFrom();
        boolean shortened = false;
        if (elliottFrom != null) {
            for (int index = first; index <= last; index++) {
                if (candles.get(index).getTimestamp() >= elliottFrom) {
                    shortened = index > first;
                    first = Math.max(first, index);
                    break;
                }
            }
        }

        String mode = command.triggerMode() == null ? "BECOMES_TRUE" : command.triggerMode().trim().toUpperCase(Locale.ROOT);
        int quiet = command.quietCandles() == null ? 0 : Math.max(0, Math.min(60, command.quietCandles()));
        int window = command.outcomeCandles() == null ? 10 : Math.max(1, Math.min(60, command.outcomeCandles()));
        List<HistoryPoint> history = new ArrayList<>();
        boolean previous = CustomAlertEvaluator.evaluate(definition, ownerFacts, first - 1).matched();
        Integer lastTrigger = null;
        boolean once = false;
        for (int index = first; index <= last; index++) {
            boolean matched = CustomAlertEvaluator.evaluate(definition, ownerFacts, index).matched();
            boolean fire = switch (mode) {
                case "EVERY_CANDLE" -> matched;
                case "ONCE" -> matched && !previous && !once;
                default -> matched && !previous;
            };
            if (fire && lastTrigger != null && quiet > 0 && index - lastTrigger <= quiet) fire = false;
            if (fire) {
                double close = candles.get(index).getClosePrice();
                Double outcome = index + window <= last
                        ? Math.round((candles.get(index + window).getClosePrice() - close) / close * 10_000.0) / 100.0 : null;
                history.add(new HistoryPoint(candles.get(index).getTimestamp(), close, outcome));
                lastTrigger = index;
                once = true;
            }
            previous = matched;
        }
        List<Double> outcomes = history.stream().map(HistoryPoint::outcomeReturn).filter(java.util.Objects::nonNull).sorted().toList();
        Double median = outcomes.isEmpty() ? null : outcomes.size() % 2 == 1 ? outcomes.get(outcomes.size() / 2)
                : Math.round((outcomes.get(outcomes.size() / 2 - 1) + outcomes.get(outcomes.size() / 2)) * 50.0) / 100.0;
        Double positive = outcomes.isEmpty() ? null
                : Math.round(outcomes.stream().filter(value -> value > 0).count() * 1000.0 / outcomes.size()) / 10.0;
        return new PreviewView(symbol, asset.getCompanyName(), true, null, candles.get(last).getTimestamp(),
                candles.get(last).getClosePrice(), latest.matched(), latest.applicable(), latest.matchedConditions(),
                latest.totalConditions(), latest.conditions(), history, candles.get(first).getTimestamp(), last - first + 1L,
                median, positive, coverage, shortened ? "ELLIOTT_WAVE" : null, data.elliottHistoryStatus());
    }

    private static PreviewView empty(String symbol, String company, String reason, Coverage coverage) {
        return new PreviewView(symbol, company, false, reason, null, null, false, true, 0, 0, List.of(), List.of(), 0, 0,
                null, null, coverage, null, null);
    }

    /** How many stocks the draft would cover, how many are new to monitoring, and the room left. */
    Coverage coverage(User user, List<CustomAlertService.TargetCommand> targets, CustomAlertDefinition definition) {
        Set<Long> covered = new LinkedHashSet<>();
        if (targets != null) {
            for (CustomAlertService.TargetCommand target : targets) {
                if (target == null || target.watchlistId() == null) continue;
                watchlists.requireOwned(user, target.watchlistId());
                if ("SELECTED".equalsIgnoreCase(target.scope())) {
                    for (String raw : target.symbols() == null ? List.<String>of() : target.symbols()) {
                        String symbol = SecurityInputValidator.requireMarketSymbol(raw);
                        covered.addAll(db.queryForList("""
                                select m.stock_asset_id from watchlist_members m join stock_assets s on s.id = m.stock_asset_id
                                where m.watchlist_id = ? and upper(s.ticker_symbol) = ?
                                """, Long.class, target.watchlistId(), symbol));
                    }
                } else {
                    covered.addAll(db.queryForList("select stock_asset_id from watchlist_members where watchlist_id = ?",
                            Long.class, target.watchlistId()));
                }
            }
        }
        long tracked = guard == null ? 0 : guard.trackedCount(user);
        int maximum = guard == null ? 0 : guard.maximumPerUser();
        long newToMonitoring = 0;
        long nonStocks = 0;
        if (!covered.isEmpty()) {
            Long[] ids = covered.toArray(Long[]::new);
            newToMonitoring = count("""
                    select count(*) from unnest(?) id where id not in (
                        select stock_asset_id from alert_rules where user_id = ? and is_active
                        union select stock_asset_id from technical_outlook_subscriptions where user_id = ? and is_active
                        union select t.stock_asset_id from custom_alert_effective_targets t join custom_alerts a on a.id = t.alert_id
                              where a.user_id = ? and a.active)
                    """, ids, user.getId(), 3);
            if (definition.usesActivity()) {
                nonStocks = count("select count(*) from stock_assets where id = any(?) and coalesce(instrument_type, 'EQUITY') <> '"
                        + InstrumentType.EQUITY.name() + "'", ids, user.getId(), 0);
            }
        }
        return new Coverage(covered.size(), newToMonitoring, tracked, maximum, nonStocks);
    }

    private long count(String sql, Long[] ids, long userId, int userParameters) {
        Long value = db.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            for (int index = 0; index < userParameters; index++) statement.setLong(index + 2, userId);
            return statement;
        }, result -> result.next() ? result.getLong(1) : 0L);
        return value == null ? 0 : value;
    }
}
