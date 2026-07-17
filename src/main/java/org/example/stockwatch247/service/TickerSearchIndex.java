package org.example.stockwatch247.service;

import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable compressed radix index used for local ticker autocomplete. */
public final class TickerSearchIndex {
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public TickerSearchIndex() {
        rebuild(List.of());
    }

    public void rebuild(List<StockAsset> storedAssets) {
        Map<String, SearchEntry> entriesBySymbol = new LinkedHashMap<>();
        for (MarketIndexCatalog.IndexDefinition index : MarketIndexCatalog.all()) {
            entriesBySymbol.put(index.symbol(), SearchEntry.from(index));
        }
        if (storedAssets != null) {
            for (StockAsset asset : storedAssets) {
                if (asset == null || asset.getTickerSymbol() == null || asset.getTickerSymbol().isBlank()) {
                    continue;
                }
                SearchEntry stored = SearchEntry.from(asset);
                entriesBySymbol.merge(stored.symbol(), stored, SearchEntry::preferStoredMetadata);
            }
        }

        List<SearchEntry> entries = List.copyOf(entriesBySymbol.values());
        MutableNode mutableRoot = new MutableNode();
        for (int id = 0; id < entries.size(); id++) {
            for (String key : entries.get(id).searchKeys()) {
                insert(mutableRoot, key, id);
            }
        }
        snapshot.set(new Snapshot(freeze(mutableRoot), entries));
    }

    public List<Map<String, Object>> search(String rawQuery, int limit) {
        String query = searchKey(rawQuery);
        if (query.isBlank() || limit <= 0) {
            return List.of();
        }
        Snapshot current = snapshot.get();
        List<Integer> candidateIds = findPrefixMatches(current.root(), query);
        return candidateIds.stream()
                .map(current.entries()::get)
                .sorted(Comparator.comparingInt((SearchEntry entry) -> entry.rank(query))
                        .thenComparingInt(entry -> entry.symbol().length())
                        .thenComparing(SearchEntry::symbol))
                .limit(limit)
                .map(SearchEntry::toSuggestion)
                .toList();
    }

    private static void insert(MutableNode root, String rawKey, int entryId) {
        String key = searchKey(rawKey);
        if (key.isBlank()) {
            return;
        }
        MutableNode node = root;
        node.matches.add(entryId);
        for (int index = 0; index < key.length(); index++) {
            node = node.children.computeIfAbsent(key.charAt(index), ignored -> new MutableNode());
            node.matches.add(entryId);
        }
    }

    private static RadixNode freeze(MutableNode mutable) {
        Map<Character, RadixEdge> edges = new HashMap<>();
        for (Map.Entry<Character, MutableNode> childEntry : mutable.children.entrySet()) {
            StringBuilder label = new StringBuilder().append(childEntry.getKey());
            MutableNode child = childEntry.getValue();
            while (child.children.size() == 1) {
                Map.Entry<Character, MutableNode> onlyChild = child.children.entrySet().iterator().next();
                if (!child.matches.equals(onlyChild.getValue().matches)) {
                    break;
                }
                label.append(onlyChild.getKey());
                child = onlyChild.getValue();
            }
            edges.put(label.charAt(0), new RadixEdge(label.toString(), freeze(child)));
        }
        return new RadixNode(Map.copyOf(edges), List.copyOf(mutable.matches));
    }

    private static List<Integer> findPrefixMatches(RadixNode root, String query) {
        RadixNode node = root;
        int queryIndex = 0;
        while (queryIndex < query.length()) {
            RadixEdge edge = node.edges().get(query.charAt(queryIndex));
            if (edge == null) {
                return List.of();
            }
            int labelIndex = 0;
            while (queryIndex < query.length() && labelIndex < edge.label().length()) {
                if (query.charAt(queryIndex) != edge.label().charAt(labelIndex)) {
                    return List.of();
                }
                queryIndex++;
                labelIndex++;
            }
            if (queryIndex == query.length()) {
                return edge.target().matches();
            }
            if (labelIndex < edge.label().length()) {
                return List.of();
            }
            node = edge.target();
        }
        return node.matches();
    }

    static String searchKey(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        return Normalizer.normalize(rawValue, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replace("'", "")
                .replace("’", "")
                .replaceAll("[^\\p{L}\\p{N}^]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static Set<String> wordPrefixes(String name) {
        String normalized = searchKey(name);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (!normalized.isBlank()) {
            keys.add(normalized);
            for (String word : normalized.split(" ")) {
                if (!word.isBlank()) {
                    keys.add(word);
                }
            }
        }
        return Set.copyOf(keys);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String initials(String symbol) {
        String display = symbol.startsWith("^") ? symbol.substring(1) : symbol;
        return display.length() > 1 ? display.substring(0, 2) : display;
    }

    private record Snapshot(RadixNode root, List<SearchEntry> entries) {
        private static Snapshot empty() {
            return new Snapshot(new RadixNode(Map.of(), List.of()), List.of());
        }
    }

    private record RadixNode(Map<Character, RadixEdge> edges, List<Integer> matches) {
    }

    private record RadixEdge(String label, RadixNode target) {
    }

    private static final class MutableNode {
        private final Map<Character, MutableNode> children = new HashMap<>();
        private final LinkedHashSet<Integer> matches = new LinkedHashSet<>();
    }

    private record SearchEntry(String symbol,
                               String name,
                               String region,
                               String currency,
                               InstrumentType instrumentType,
                               Set<String> tickerKeys,
                               Set<String> nameKeys,
                               Set<String> searchKeys) {
        private static SearchEntry from(MarketIndexCatalog.IndexDefinition index) {
            LinkedHashSet<String> tickerKeys = new LinkedHashSet<>();
            tickerKeys.add(searchKey(index.symbol()));
            index.tickerAliases().stream().map(TickerSearchIndex::searchKey).forEach(tickerKeys::add);
            LinkedHashSet<String> nameKeys = new LinkedHashSet<>(wordPrefixes(index.name()));
            index.searchAliases().stream().map(TickerSearchIndex::searchKey).forEach(nameKeys::add);
            LinkedHashSet<String> allKeys = new LinkedHashSet<>(tickerKeys);
            allKeys.addAll(nameKeys);
            return new SearchEntry(index.symbol(), index.name(), index.exchange(), index.currency(),
                    InstrumentType.INDEX, Set.copyOf(tickerKeys), Set.copyOf(nameKeys), Set.copyOf(allKeys));
        }

        private static SearchEntry from(StockAsset asset) {
            String symbol = asset.getTickerSymbol().trim().toUpperCase(Locale.ROOT);
            Set<String> tickerKeys = Set.of(searchKey(symbol));
            Set<String> nameKeys = wordPrefixes(asset.getCompanyName());
            LinkedHashSet<String> allKeys = new LinkedHashSet<>(tickerKeys);
            allKeys.addAll(nameKeys);
            return new SearchEntry(symbol,
                    defaultIfBlank(asset.getCompanyName(), symbol),
                    defaultIfBlank(asset.getExchange(), "UNKNOWN"),
                    defaultIfBlank(asset.getCurrency(), "USD"),
                    asset.getInstrumentType() == null ? InstrumentType.EQUITY : asset.getInstrumentType(),
                    tickerKeys, nameKeys, Set.copyOf(allKeys));
        }

        private static SearchEntry preferStoredMetadata(SearchEntry catalog, SearchEntry stored) {
            boolean genericStoredName = stored.name().equalsIgnoreCase(stored.symbol());
            String name = genericStoredName ? catalog.name() : stored.name();
            String region = "UNKNOWN".equalsIgnoreCase(stored.region()) ? catalog.region() : stored.region();
            String currency = stored.currency().isBlank() ? catalog.currency() : stored.currency();
            LinkedHashSet<String> tickerKeys = new LinkedHashSet<>(catalog.tickerKeys());
            tickerKeys.addAll(stored.tickerKeys());
            LinkedHashSet<String> nameKeys = new LinkedHashSet<>(catalog.nameKeys());
            nameKeys.addAll(wordPrefixes(name));
            LinkedHashSet<String> allKeys = new LinkedHashSet<>(tickerKeys);
            allKeys.addAll(nameKeys);
            return new SearchEntry(catalog.symbol(), name, region, currency, catalog.instrumentType(),
                    Set.copyOf(tickerKeys), Set.copyOf(nameKeys), Set.copyOf(allKeys));
        }

        private int rank(String query) {
            if (tickerKeys.contains(query)) {
                return 0;
            }
            if (tickerKeys.stream().anyMatch(key -> key.startsWith(query))) {
                return 1;
            }
            if (nameKeys.stream().anyMatch(key -> key.startsWith(query))) {
                return 2;
            }
            return 3;
        }

        private Map<String, Object> toSuggestion() {
            Map<String, Object> suggestion = new LinkedHashMap<>();
            suggestion.put("symbol", symbol);
            suggestion.put("name", name);
            suggestion.put("region", region);
            suggestion.put("currency", currency);
            suggestion.put("instrumentType", instrumentType.name());
            suggestion.put("initials", initials(symbol));
            return suggestion;
        }
    }
}
