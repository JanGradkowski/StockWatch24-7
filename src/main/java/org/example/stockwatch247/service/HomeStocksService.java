package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home's "Your stocks" strip: up to six stocks from the user's watchlists (pinned lists first) with the latest
 * completed daily close, the change from the close before it, a sparkline of recent closes and the followed daily
 * outlook. Everything comes from stored candles and outlook snapshots, so opening Home never asks the market data
 * provider for prices.
 */
@Service
public class HomeStocksService {
    static final int LIMIT = 6;
    static final int SPARKLINE_CLOSES = 30;
    // The sparkline is drawn in a 100 x 32 box with 2 units of room above and below the line.
    private static final double WIDTH = 100;
    private static final double TOP = 2;
    private static final double HEIGHT = 28;

    private final JdbcTemplate jdbc;
    private final CandleCompletionService completion;
    private final TechnicalWatchlistService outlooks;

    public HomeStocksService(JdbcTemplate jdbc, CandleCompletionService completion, TechnicalWatchlistService outlooks) {
        this.jdbc = jdbc;
        this.completion = completion;
        this.outlooks = outlooks;
    }

    @Transactional(readOnly = true)
    public List<StockCard> cards(User user) {
        List<Listed> listed = jdbc.query("""
                select a.ticker_symbol, max(a.company_name), max(a.currency), bool_or(w.pinned) pinned, max(w.updated_at) updated
                from watchlists w
                join watchlist_members m on m.watchlist_id = w.id
                join stock_assets a on a.id = m.stock_asset_id
                where w.user_id = ?
                group by a.ticker_symbol
                order by pinned desc, updated desc, a.ticker_symbol
                limit ?
                """, (rs, row) -> new Listed(rs.getString(1), rs.getString(2), rs.getString(3)), user.getId(), LIMIT);
        if (listed.isEmpty()) return List.of();

        String[] symbols = listed.stream().map(Listed::symbol).toArray(String[]::new);
        long completeBefore = completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY);
        Map<String, List<Double>> closes = new HashMap<>();
        Map<String, Long> lastTimestamps = new HashMap<>();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    select s.symbol, c.timestamp, c.close_price from unnest(?::text[]) s(symbol)
                    cross join lateral (
                        select timestamp, close_price from candles where symbol = s.symbol and time_interval = '1d'
                        and timestamp < ? and close_price > 0 and close_price < 'Infinity'::float8
                        order by timestamp desc limit ?
                    ) c
                    order by s.symbol, c.timestamp
                    """);
            statement.setArray(1, connection.createArrayOf("text", symbols));
            statement.setLong(2, completeBefore);
            statement.setInt(3, SPARKLINE_CLOSES);
            return statement;
        }, rs -> {
            String symbol = rs.getString(1);
            closes.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(rs.getDouble(3));
            lastTimestamps.put(symbol, rs.getLong(2));
        });

        Map<String, String> dailyOutlooks = new HashMap<>();
        outlooks.watchlist(user).tickers().forEach(ticker -> ticker.intervals().stream()
                .filter(interval -> interval.interval() == TimeInterval.DAILY && interval.available())
                .findFirst()
                .ifPresent(interval -> dailyOutlooks.put(ticker.symbol(), interval.classification())));

        List<StockCard> cards = new ArrayList<>();
        for (Listed stock : listed) {
            List<Double> series = closes.getOrDefault(stock.symbol(), List.of());
            if (series.size() < 2) continue;
            double close = series.getLast();
            double previous = series.get(series.size() - 2);
            cards.add(new StockCard(stock.symbol(), stock.companyName(), stock.currency() == null ? "USD" : stock.currency(),
                    close, (close / previous - 1) * 100, lastTimestamps.get(stock.symbol()), sparkline(series),
                    dailyOutlooks.get(stock.symbol())));
        }
        return cards;
    }

    /** An SVG path through the closes, oldest on the left, scaled to fill the box's height. */
    static String sparkline(List<Double> closes) {
        if (closes.size() < 2) return null;
        double min = closes.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double max = closes.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < closes.size(); index++) {
            double x = WIDTH * index / (closes.size() - 1);
            double y = max == min ? TOP + HEIGHT / 2 : TOP + (max - closes.get(index)) / (max - min) * HEIGHT;
            path.append(index == 0 ? "M" : " L").append(String.format(Locale.ROOT, "%.1f %.1f", x, y));
        }
        return path.toString();
    }

    private record Listed(String symbol, String companyName, String currency) { }

    /**
     * One stock on Home. {@code changePercent} compares the latest completed close with the one before it;
     * {@code outlook} is the followed daily outlook's classification, or null when it is not followed.
     */
    public record StockCard(String symbol, String companyName, String currency, double close, double changePercent,
                            long closeTimestamp, String sparkline, String outlook) {
        public boolean rising() { return changePercent >= 0; }

        public double changeMagnitude() { return Math.abs(changePercent); }

        public String outlookTone() {
            if (outlook == null) return "neutral";
            String text = outlook.toLowerCase(Locale.ROOT);
            return text.contains("buy") ? "buy" : text.contains("sell") ? "sell" : "neutral";
        }
    }
}
