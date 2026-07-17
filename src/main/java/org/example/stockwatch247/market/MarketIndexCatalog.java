package org.example.stockwatch247.market;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class MarketIndexCatalog {
    private static final List<IndexDefinition> INDEXES = List.of(
            index("^GSPC", "S&P 500", "SNP", "USD",
                    List.of("S&P 500", "S AND P 500", "SP 500", "SP500", "SPX", "GSPC"),
                    List.of("SPX", "GSPC")),
            index("^DJI", "Dow Jones Industrial Average", "DJI", "USD",
                    List.of("DOW JONES", "DOW JONES INDUSTRIAL AVERAGE", "DJIA", "DJI"),
                    List.of("DJIA", "DJI")),
            index("^IXIC", "NASDAQ Composite", "NASDAQ", "USD",
                    List.of("NASDAQ COMPOSITE", "IXIC"),
                    List.of("IXIC")),
            index("^NDX", "NASDAQ-100", "NASDAQ", "USD",
                    List.of("NASDAQ 100", "NASDAQ-100", "NDX"),
                    List.of("NDX")),
            index("^RUT", "Russell 2000", "RUSSELL", "USD",
                    List.of("RUSSELL 2000", "RUT"),
                    List.of("RUT")),
            index("^VIX", "CBOE Volatility Index", "CBOE", "USD",
                    List.of("VIX", "VOLATILITY INDEX", "CBOE VOLATILITY INDEX"),
                    List.of("VIX")),
            index("^FTSE", "FTSE 100", "FTSE", "GBP",
                    List.of("FTSE", "FTSE 100", "UK 100"),
                    List.of("FTSE")),
            index("^GDAXI", "DAX Performance Index", "XETRA", "EUR",
                    List.of("DAX", "DAX 40", "GERMANY 40", "GDAXI"),
                    List.of("GDAXI")),
            index("^FCHI", "CAC 40", "EURONEXT PARIS", "EUR",
                    List.of("CAC", "CAC 40", "FCHI"),
                    List.of("FCHI")),
            index("^N225", "Nikkei 225", "OSE", "JPY",
                    List.of("NIKKEI", "NIKKEI 225", "N225"),
                    List.of("N225")),
            index("^HSI", "Hang Seng Index", "HKSE", "HKD",
                    List.of("HANG SENG", "HANG SENG INDEX", "HSI"),
                    List.of("HSI")),
            index("^STOXX50E", "EURO STOXX 50", "STOXX", "EUR",
                    List.of("EURO STOXX", "EURO STOXX 50", "STOXX 50", "STOXX50E"),
                    List.of("STOXX50E"))
    );

    private static final Map<String, IndexDefinition> BY_SYMBOL = buildBySymbol();
    private static final Map<String, String> TICKER_ALIASES = buildTickerAliases();

    private MarketIndexCatalog() {
    }

    public static String canonicalTickerSymbol(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        return TICKER_ALIASES.getOrDefault(symbol, symbol);
    }

    public static Optional<IndexDefinition> findBySymbol(String rawSymbol) {
        return Optional.ofNullable(BY_SYMBOL.get(canonicalTickerSymbol(rawSymbol)));
    }

    public static boolean isIndexSymbol(String rawSymbol) {
        String symbol = canonicalTickerSymbol(rawSymbol);
        return symbol.startsWith("^") || BY_SYMBOL.containsKey(symbol);
    }

    public static List<IndexDefinition> all() {
        return INDEXES;
    }

    public static List<IndexDefinition> search(String rawQuery) {
        String query = searchKey(rawQuery);
        if (query.isBlank()) {
            return List.of();
        }
        return INDEXES.stream()
                .filter(index -> matchRank(index, query) < 3)
                .sorted(java.util.Comparator.comparingInt(index -> matchRank(index, query)))
                .toList();
    }

    private static int matchRank(IndexDefinition index, String query) {
        List<String> values = new java.util.ArrayList<>();
        values.add(index.symbol());
        values.add(index.name());
        values.addAll(index.searchAliases());
        if (values.stream().map(MarketIndexCatalog::searchKey).anyMatch(query::equals)) {
            return 0;
        }
        if (values.stream().map(MarketIndexCatalog::searchKey).anyMatch(value -> value.startsWith(query))) {
            return 1;
        }
        if (values.stream().map(MarketIndexCatalog::searchKey).anyMatch(value -> value.contains(query))) {
            return 2;
        }
        return 3;
    }

    private static String searchKey(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        return rawValue.toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replaceAll("[^A-Z0-9^]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static IndexDefinition index(String symbol,
                                         String name,
                                         String exchange,
                                         String currency,
                                         List<String> searchAliases,
                                         List<String> tickerAliases) {
        return new IndexDefinition(symbol, name, exchange, currency,
                List.copyOf(searchAliases), List.copyOf(tickerAliases));
    }

    private static Map<String, IndexDefinition> buildBySymbol() {
        Map<String, IndexDefinition> definitions = new LinkedHashMap<>();
        INDEXES.forEach(index -> definitions.put(index.symbol(), index));
        return Map.copyOf(definitions);
    }

    private static Map<String, String> buildTickerAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (IndexDefinition index : INDEXES) {
            aliases.put(index.symbol(), index.symbol());
            index.tickerAliases().forEach(alias ->
                    aliases.put(alias.toUpperCase(Locale.ROOT), index.symbol()));
        }
        return Map.copyOf(aliases);
    }

    public record IndexDefinition(String symbol,
                                  String name,
                                  String exchange,
                                  String currency,
                                  List<String> searchAliases,
                                  List<String> tickerAliases) {
    }
}
