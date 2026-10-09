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
                    List.of("STOXX50E")),
            // Local relative-strength benchmarks; search aliases only, so no stock ticker is shadowed.
            index("^GSPTSE", "S&P/TSX Composite", "TSX", "CAD", List.of("S&P/TSX", "TSX COMPOSITE", "GSPTSE"), List.of()),
            index("^AXJO", "S&P/ASX 200", "ASX", "AUD", List.of("ASX 200", "S&P/ASX 200", "AXJO"), List.of()),
            index("^SSMI", "Swiss Market Index", "SIX", "CHF", List.of("SMI", "SWISS MARKET INDEX", "SSMI"), List.of()),
            index("^OMX", "OMX Stockholm 30", "OMX STOCKHOLM", "SEK", List.of("OMXS30", "OMX STOCKHOLM 30"), List.of()),
            index("^AEX", "AEX", "EURONEXT AMSTERDAM", "EUR", List.of("AEX INDEX", "AMSTERDAM EXCHANGE INDEX"), List.of()),
            index("^BFX", "BEL 20", "EURONEXT BRUSSELS", "EUR", List.of("BEL 20", "BEL20"), List.of()),
            index("FTSEMIB.MI", "FTSE MIB", "BORSA ITALIANA", "EUR", List.of("FTSE MIB", "FTSEMIB"), List.of()),
            index("^IBEX", "IBEX 35", "BME", "EUR", List.of("IBEX", "IBEX 35"), List.of()),
            index("^OMXC25", "OMX Copenhagen 25", "OMX COPENHAGEN", "DKK", List.of("OMXC25", "OMX COPENHAGEN 25"), List.of()),
            index("^OMXH25", "OMX Helsinki 25", "OMX HELSINKI", "EUR", List.of("OMXH25", "OMX HELSINKI 25"), List.of()),
            index("^ATX", "ATX", "WIENER BOERSE", "EUR", List.of("ATX INDEX", "AUSTRIAN TRADED INDEX"), List.of()),
            index("PSI20.LS", "PSI", "EURONEXT LISBON", "EUR", List.of("PSI 20", "PSI20"), List.of()),
            index("^ISEQ", "ISEQ All Share", "EURONEXT DUBLIN", "EUR", List.of("ISEQ", "ISEQ ALL SHARE"), List.of())
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
