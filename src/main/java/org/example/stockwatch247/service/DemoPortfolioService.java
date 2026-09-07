package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Portfolio accounting for sized demo BUYs; SELL decisions are not holdings or short positions. */
@Service
public class DemoPortfolioService {
    private static final MathContext MONEY = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CandleCompletionService completion;

    public DemoPortfolioService(JdbcTemplate jdbc, Clock clock, CandleCompletionService completion) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.completion = completion;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PortfolioView portfolio(long userId, String requestedPeriod) {
        String selected = normalizePeriod(requestedPeriod);
        Instant now = clock.instant();
        // Read only accounting columns, never the large technical snapshot payloads.
        List<Position> rows = jdbc.query("""
                select a.ticker_symbol, a.company_name, t.currency, t.side, t.entry_at, t.exit_at,
                       t.entry_price, t.exit_price, t.quantity, t.notional_value, t.status
                from virtual_trades t join stock_assets a on a.id = t.stock_asset_id
                where t.user_id = ? and t.deleted_at is null and t.entry_at <= ?
                order by t.id
                """, (rs, row) -> new Position(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11)),
                userId, java.sql.Timestamp.from(now));
        List<Position> positions = rows.stream().filter(Position::eligible).toList();
        if (positions.isEmpty()) return new PortfolioView(List.of(), selected, rows.size());

        String[] symbols = positions.stream().map(Position::symbol).distinct().toArray(String[]::new);
        long completeBefore = completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY);
        Map<String, Price> latest = new LinkedHashMap<>();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    select s.symbol, c.timestamp, c.close_price from unnest(?::text[]) s(symbol)
                    cross join lateral (
                        select timestamp, close_price from candles where symbol = s.symbol and time_interval = '1d'
                        and timestamp < ? and close_price > 0 and close_price < 'Infinity'::float8
                        order by timestamp desc limit 1
                    ) c
                    """);
            statement.setArray(1, connection.createArrayOf("text", symbols));
            statement.setLong(2, completeBefore);
            return statement;
        }, rs -> {
            latest.put(rs.getString(1), new Price(rs.getLong(2), rs.getBigDecimal(3)));
        });
        long anchor = latest.values().stream().mapToLong(Price::timestamp).max().orElse(completeBefore - 86_400);
        LocalDate anchorDate = Instant.ofEpochSecond(anchor).atZone(ZoneOffset.UTC).toLocalDate();
        Map<String, Instant> starts = new LinkedHashMap<>();
        starts.put("overall", Instant.EPOCH);
        starts.put("day", anchorDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        starts.put("week", anchorDate.minusWeeks(1).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        starts.put("month", anchorDate.minusMonths(1).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        Map<String, Map<String, Price>> baselines = new LinkedHashMap<>();
        // One indexed lookup per symbol/cutoff, in a single round trip, independent of archive pagination.
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    select s.symbol, period.key, c.timestamp, c.close_price from unnest(?::text[]) s(symbol)
                    cross join (values ('day', ?::bigint), ('week', ?::bigint), ('month', ?::bigint)) period(key, cutoff)
                    cross join lateral (
                        select timestamp, close_price from candles where symbol = s.symbol and time_interval = '1d'
                        and timestamp < period.cutoff and close_price > 0 and close_price < 'Infinity'::float8
                        order by timestamp desc limit 1
                    ) c
                    """);
            statement.setArray(1, connection.createArrayOf("text", symbols));
            statement.setLong(2, starts.get("day").getEpochSecond());
            statement.setLong(3, starts.get("week").getEpochSecond());
            statement.setLong(4, starts.get("month").getEpochSecond());
            return statement;
        }, rs -> {
            baselines.computeIfAbsent(rs.getString(2), key -> new LinkedHashMap<>())
                    .put(rs.getString(1), new Price(rs.getLong(3), rs.getBigDecimal(4)));
        });
        Map<String, List<Position>> currencies = new TreeMap<>();
        positions.forEach(position -> currencies.computeIfAbsent(position.currency().toUpperCase(Locale.ROOT),
                key -> new ArrayList<>()).add(position));
        List<CurrencyView> views = currencies.entrySet().stream().map(entry ->
                currency(entry.getKey(), entry.getValue(), latest, baselines, starts, selected)).toList();
        return new PortfolioView(views, selected, rows.size() - positions.size());
    }

    private CurrencyView currency(String currency, List<Position> positions, Map<String, Price> latest,
                                  Map<String, Map<String, Price>> baselines, Map<String, Instant> starts, String selected) {
        BigDecimal open = BigDecimal.ZERO, cash = BigDecimal.ZERO, invested = BigDecimal.ZERO;
        int missing = 0, atEntry = 0;
        for (Position position : positions) {
            invested = invested.add(position.cost());
            BigDecimal value = position.endValue(latest.get(position.symbol()));
            if (value == null) missing++;
            else if (position.closed()) cash = cash.add(value);
            else {
                open = open.add(value);
                if (latest.get(position.symbol()) != null
                        && !position.entryAt().isBefore(latest.get(position.symbol()).availableAt())) atEntry++;
            }
        }
        List<PeriodView> periods = new ArrayList<>();
        for (var period : starts.entrySet()) {
            Total total = new Total();
            Map<String, Total> stocks = new TreeMap<>();
            for (Position position : positions) {
                BigDecimal end = position.endValue(latest.get(position.symbol()));
                BigDecimal base = position.startValue(period.getKey(), period.getValue(),
                        baselines.getOrDefault(period.getKey(), Map.of()).get(position.symbol()));
                total.add(base, end);
                boolean exposed = "overall".equals(period.getKey()) || !position.closed()
                        || position.exitAt().isAfter(period.getValue());
                if (exposed) {
                    Total stock = stocks.computeIfAbsent(position.symbol(), key -> new Total());
                    stock.name = position.name();
                    stock.add(base, end);
                }
            }
            List<StockView> ranked = stocks.entrySet().stream().filter(entry -> entry.getValue().percent() != null)
                    .map(entry -> new StockView(entry.getKey(), entry.getValue().name, entry.getValue().percent(),
                            entry.getValue().gain())).toList();
            Comparator<StockView> ascending = Comparator.comparing(StockView::returnPercent).thenComparing(StockView::symbol);
            Comparator<StockView> descending = Comparator.comparing(StockView::returnPercent, Comparator.reverseOrder())
                    .thenComparing(StockView::symbol);
            String label = switch (period.getKey()) {
                case "day" -> "Last trading day";
                case "week" -> "Last week";
                case "month" -> "Last month";
                default -> "Overall";
            };
            String comparison = "overall".equals(period.getKey()) ? "Since each buy was opened"
                    : "Since the close on or before " + DATE.format(period.getValue().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1));
            periods.add(new PeriodView(period.getKey(), label, total.percent(), total.gain(),
                    ranked.stream().sorted(descending).limit(3).toList(), ranked.stream().sorted(ascending).limit(3).toList(),
                    stocks.size() - ranked.size(), comparison));
        }
        List<Long> dates = positions.stream().filter(position -> !position.closed()).map(position -> latest.get(position.symbol()))
                .filter(java.util.Objects::nonNull).map(Price::timestamp).distinct().sorted().toList();
        String priceDates = dates.isEmpty()
                ? (positions.stream().allMatch(Position::closed) ? "Recorded exit prices for closed buys" : "Daily-close prices unavailable")
                : "Daily-close prices: "
                + date(dates.getFirst()) + (dates.size() > 1 ? " to " + date(dates.getLast()) : "");
        return new CurrencyView(currency, missing == 0 ? open.add(cash) : null, invested,
                missing == 0 ? open : null, cash, positions.size(), missing, atEntry, priceDates, List.copyOf(periods),
                periods.stream().filter(period -> period.key().equals(selected)).findFirst().orElseThrow());
    }

    public static String normalizePeriod(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "day" -> "day";
            case "week" -> "week";
            case "month" -> "month";
            default -> "overall";
        };
    }

    private static String date(long timestamp) { return DATE.format(Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC)); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }

    private record Price(long timestamp, BigDecimal close) {
        Instant availableAt() { return Instant.ofEpochSecond(timestamp).plusSeconds(86_400); }
    }

    private record Position(String symbol, String name, String currency, String side, Instant entryAt, Instant exitAt,
                            BigDecimal entry, BigDecimal exit, BigDecimal quantity, BigDecimal notional, String status) {
        boolean eligible() { return "BUY".equals(side) && positive(entry) && (positive(quantity) || positive(notional)); }
        boolean closed() { return "CLOSED".equals(status) && exitAt != null; }
        BigDecimal units() { return positive(quantity) ? quantity : notional.divide(entry, MONEY); }
        BigDecimal cost() { return positive(quantity) ? quantity.multiply(entry, MONEY) : notional; }
        BigDecimal at(Price mark) {
            if (mark == null) return null;
            return !entryAt.isBefore(mark.availableAt()) ? cost() : units().multiply(mark.close(), MONEY);
        }
        BigDecimal endValue(Price mark) { return closed() ? (positive(exit) ? units().multiply(exit, MONEY) : null) : at(mark); }
        BigDecimal startValue(String key, Instant start, Price mark) {
            if ("overall".equals(key) || !entryAt.isBefore(start)) return cost();
            if (closed() && !exitAt.isAfter(start)) return positive(exit) ? units().multiply(exit, MONEY) : null;
            return at(mark);
        }
    }

    private static final class Total {
        BigDecimal base = BigDecimal.ZERO, end = BigDecimal.ZERO;
        boolean complete = true;
        String name;
        void add(BigDecimal opening, BigDecimal closing) {
            if (opening == null || closing == null) complete = false;
            else { base = base.add(opening); end = end.add(closing); }
        }
        BigDecimal gain() { return complete ? end.subtract(base) : null; }
        BigDecimal percent() { return complete && base.signum() > 0 ? gain().multiply(HUNDRED).divide(base, MONEY) : null; }
    }

    public record PortfolioView(List<CurrencyView> currencies, String selectedPeriod, int excludedTrades) { }
    public record CurrencyView(String currency, BigDecimal value, BigDecimal invested, BigDecimal openValue, BigDecimal cash,
                               int trades, int missingPrices, int entryValuations, String priceDates,
                               List<PeriodView> periods, PeriodView selected) { }
    public record PeriodView(String key, String label, BigDecimal returnPercent, BigDecimal gain,
                             List<StockView> best, List<StockView> worst, int unavailableStocks, String comparison) { }
    public record StockView(String symbol, String companyName, BigDecimal returnPercent, BigDecimal gain) { }
}
