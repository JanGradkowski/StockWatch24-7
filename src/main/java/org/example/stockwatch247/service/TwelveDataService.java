package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class TwelveDataService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StockAssetRepository stockAssetRepository;
    private final String apiKey;
    private final String baseUrl;
    private final BoundedTtlCache<String, List<Map<String, Object>>> searchCache =
            new BoundedTtlCache<>(1_000, 900);

    public TwelveDataService(RestTemplate restTemplate,
                             ObjectMapper objectMapper,
                             StockAssetRepository stockAssetRepository,
                             @Value("${twelve-data.api-key:${TWELVE_DATA_API_KEY:}}") String apiKey,
                             @Value("${twelve-data.base-url:https://api.twelvedata.com}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.stockAssetRepository = stockAssetRepository;
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    public List<MarketDataBar> getTimeSeries(String rawSymbol, String interval, int outputSize) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/time_series")
                .queryParam("symbol", symbol)
                .queryParam("interval", interval)
                .queryParam("outputsize", outputSize)
                .queryParam("format", "JSON")
                .queryParam("timezone", "UTC")
                .queryParam("adjust", "splits")
                .queryParam("apikey", apiKey)
                .build()
                .encode()
                .toUri();

        JsonNode root = query(uri);
        updateAssetFromMeta(symbol, root.path("meta"));
        JsonNode values = root.path("values");
        if (!values.isArray()) {
            return List.of();
        }

        java.util.ArrayList<MarketDataBar> bars = new java.util.ArrayList<>();
        for (JsonNode value : values) {
            Optional<MarketDataBar> bar = toBar(rawSymbol, value);
            bar.ifPresent(bars::add);
        }
        return bars;
    }

    public StockAsset refreshStockAssetMetadata(String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);

        Optional<MarketIndexCatalog.IndexDefinition> knownIndex = MarketIndexCatalog.findBySymbol(symbol);
        if (knownIndex.isPresent()) {
            MarketIndexCatalog.IndexDefinition index = knownIndex.get();
            return upsertStockAsset(index.symbol(), index.name(), index.exchange(), index.currency(),
                    InstrumentType.INDEX);
        }

        try {
            Optional<StockAsset> seriesAsset = findTimeSeriesMetaAsset(symbol);
            if (seriesAsset.isPresent()) {
                return seriesAsset.get();
            }
        } catch (RuntimeException e) {
            System.err.println("Twelve Data time-series metadata unavailable for " + symbol + ": " + e.getMessage());
        }

        try {
            Optional<StockAsset> providerAsset = findProviderAsset(symbol);
            if (providerAsset.isPresent()) {
                return providerAsset.get();
            }
        } catch (RuntimeException e) {
            System.err.println("Twelve Data metadata refresh unavailable for " + symbol + ": " + e.getMessage());
        }

        return stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .orElseGet(() -> upsertStockAsset(symbol, symbol, "UNKNOWN", "USD"));
    }

    public Optional<TwelveDataQuote> getQuote(String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/quote")
                .queryParam("symbol", symbol)
                .queryParam("apikey", apiKey)
                .build()
                .encode()
                .toUri();

        JsonNode root = query(uri);
        if (root.path("close").isMissingNode()) {
            return Optional.empty();
        }

        return Optional.of(new TwelveDataQuote(
                SecurityInputValidator.requireMarketSymbol(root.path("symbol").asText(symbol)),
                parseDouble(root.path("close").asText("0")),
                parseDouble(root.path("percent_change").asText("0")),
                root.path("timestamp").asLong(Instant.now().getEpochSecond())
        ));
    }

    public List<Map<String, Object>> searchSymbols(String rawQuery) {
        String query = normalizeSymbol(SecurityInputValidator.requireSearchQuery(rawQuery));
        if (query.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> indexMatches = MarketIndexCatalog.search(query).stream()
                .map(this::toSuggestion)
                .toList();
        List<Map<String, Object>> localMatches = searchLocalSymbols(query);
        List<Map<String, Object>> builtInMatches = mergeSuggestions(indexMatches, localMatches);
        if (query.length() < 2) {
            return builtInMatches;
        }

        long now = Instant.now().getEpochSecond();
        List<Map<String, Object>> cached = searchCache.get(query, now);
        if (cached != null) {
            return cached;
        }

        List<Map<String, Object>> results = builtInMatches;
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/symbol_search")
                    .queryParam("symbol", query)
                    .queryParam("apikey", apiKey)
                    .build()
                    .encode()
                    .toUri();
            JsonNode data = query(uri).path("data");
            if (!data.isArray()) {
                results = builtInMatches;
            } else {
                Map<String, ProviderAssetCandidate> bestBySymbol = new LinkedHashMap<>();
                for (JsonNode item : data) {
                    String symbol = validProviderSymbol(item.path("symbol").asText(""));
                    if (symbol == null) {
                        continue;
                    }
                    ProviderAssetCandidate candidate = toCandidate(symbol, item);
                    bestBySymbol.merge(symbol, candidate, (existing, replacement) ->
                            candidateRank(query, replacement) < candidateRank(query, existing) ? replacement : existing);
                }
                List<Map<String, Object>> providerMatches = bestBySymbol.values().stream()
                        .sorted(Comparator.comparingInt(candidate -> candidateRank(query, candidate)))
                        .limit(8)
                        .map(this::toSuggestion)
                        .toList();
                results = mergeSuggestions(indexMatches, localMatches, providerMatches);
            }
        } catch (RuntimeException e) {
            System.err.println("Twelve Data symbol search unavailable: " + e.getMessage());
            results = builtInMatches;
        }

        searchCache.put(query, results, now);
        return results;
    }

    public StockAsset upsertStockAsset(String rawSymbol, String companyName, String exchange, String currency) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        InstrumentType inferredType = MarketIndexCatalog.isIndexSymbol(symbol)
                ? InstrumentType.INDEX
                : InstrumentType.EQUITY;
        return upsertStockAsset(symbol, companyName, exchange, currency, inferredType);
    }

    public StockAsset upsertStockAsset(String rawSymbol,
                                       String companyName,
                                       String exchange,
                                       String currency,
                                       InstrumentType instrumentType) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        Optional<StockAsset> existing = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol);
        StockAsset asset = existing.orElseGet(StockAsset::new);

        asset.setTickerSymbol(symbol);
        asset.setCompanyName(safeProviderText(companyName, symbol, 255));
        asset.setExchange(safeProviderText(exchange, "UNKNOWN", 255));
        String safeCurrency = normalizeSymbol(defaultIfBlank(currency, "USD"));
        asset.setCurrency(safeCurrency.matches("[A-Z0-9]{1,10}") ? safeCurrency : "USD");
        InstrumentType resolvedType = MarketIndexCatalog.isIndexSymbol(symbol)
                ? InstrumentType.INDEX
                : instrumentType;
        if (resolvedType == null || resolvedType == InstrumentType.OTHER) {
            resolvedType = asset.getInstrumentType() == null ? InstrumentType.EQUITY : asset.getInstrumentType();
        }
        asset.setInstrumentType(resolvedType);

        return stockAssetRepository.save(asset);
    }

    public String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private JsonNode query(URI uri) {
        validateApiKey();
        try {
            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);
            failOnApiError(root);
            return root;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Twelve Data request failed: " + describeHttpError(e), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Twelve Data request failed.", e);
        }
    }

    private void failOnApiError(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            throw new IllegalStateException("Twelve Data returned an empty response.");
        }
        String status = root.path("status").asText("");
        if ("error".equalsIgnoreCase(status)) {
            throw new IllegalStateException(root.path("message").asText("Twelve Data request failed."));
        }
    }

    private Optional<MarketDataBar> toBar(String fallbackSymbol, JsonNode value) {
        String datetime = value.path("datetime").asText("");
        if (datetime.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new MarketDataBar(
                normalizeSymbol(fallbackSymbol),
                parseTimestamp(datetime),
                parseDouble(value.path("open").asText("0")),
                parseDouble(value.path("high").asText("0")),
                parseDouble(value.path("low").asText("0")),
                parseDouble(value.path("close").asText("0")),
                parseLong(value.path("volume").asText("0"))
        ));
    }

    private Optional<StockAsset> findProviderAsset(String symbol) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/symbol_search")
                .queryParam("symbol", symbol)
                .queryParam("apikey", apiKey)
                .build()
                .encode()
                .toUri();
        JsonNode data = query(uri).path("data");
        if (!data.isArray()) {
            return Optional.empty();
        }

        java.util.ArrayList<ProviderAssetCandidate> candidates = new java.util.ArrayList<>();
        for (JsonNode item : data) {
            String providerSymbol = validProviderSymbol(item.path("symbol").asText(""));
            if (providerSymbol == null) {
                continue;
            }
            candidates.add(toCandidate(providerSymbol, item));
        }

        return candidates.stream()
                .min(Comparator.comparingInt(candidate -> candidateRank(symbol, candidate)))
                .map(candidate -> upsertStockAsset(
                        candidate.symbol(),
                        candidate.name(),
                        candidate.exchange(),
                        candidate.currency(),
                        candidate.instrumentType()
                ));
    }

    private Map<String, Object> toSuggestion(JsonNode item) {
        String symbol = normalizeSymbol(item.path("symbol").asText(""));
        return toSuggestion(toCandidate(symbol, item));
    }

    private Map<String, Object> toSuggestion(ProviderAssetCandidate candidate) {
        StockAsset asset = upsertStockAsset(
                candidate.symbol(),
                candidate.name(),
                candidate.exchange(),
                candidate.currency(),
                candidate.instrumentType()
        );

        Map<String, Object> suggestion = new HashMap<>();
        suggestion.put("symbol", asset.getTickerSymbol());
        suggestion.put("name", asset.getCompanyName());
        suggestion.put("region", asset.getExchange());
        suggestion.put("currency", asset.getCurrency());
        suggestion.put("instrumentType", asset.getInstrumentType().name());
        suggestion.put("initials", initials(asset.getTickerSymbol()));
        return suggestion;
    }

    private ProviderAssetCandidate toCandidate(String symbol, JsonNode item) {
        return new ProviderAssetCandidate(
                symbol,
                defaultIfBlank(item.path("instrument_name").asText(""), symbol),
                defaultIfBlank(item.path("exchange").asText(""), item.path("mic_code").asText("UNKNOWN")),
                defaultIfBlank(item.path("currency").asText(""), "USD"),
                instrumentTypeFor(symbol, defaultIfBlank(
                        item.path("instrument_type").asText(""), item.path("type").asText("")))
        );
    }

    private String validProviderSymbol(String rawSymbol) {
        try {
            return SecurityInputValidator.requireMarketSymbol(rawSymbol);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String safeProviderText(String rawValue, String fallback, int maximumLength) {
        String value = defaultIfBlank(rawValue, fallback).replaceAll("\\p{Cntrl}", " ").trim();
        if (value.isBlank()) {
            value = fallback;
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private Optional<StockAsset> findTimeSeriesMetaAsset(String symbol) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/time_series")
                .queryParam("symbol", symbol)
                .queryParam("interval", "1day")
                .queryParam("outputsize", 1)
                .queryParam("format", "JSON")
                .queryParam("apikey", apiKey)
                .build()
                .encode()
                .toUri();

        JsonNode meta = query(uri).path("meta");
        if (meta == null || meta.isMissingNode() || meta.path("currency").asText("").isBlank()) {
            return Optional.empty();
        }

        return Optional.of(upsertStockAsset(
                normalizeSymbol(defaultIfBlank(meta.path("symbol").asText(""), symbol)),
                defaultIfBlank(meta.path("name").asText(""), symbol),
                defaultIfBlank(meta.path("exchange").asText(""), meta.path("mic_code").asText("UNKNOWN")),
                meta.path("currency").asText(),
                instrumentTypeFor(symbol, defaultIfBlank(
                        meta.path("instrument_type").asText(""), meta.path("type").asText("")))
        ));
    }

    private void updateAssetFromMeta(String fallbackSymbol, JsonNode meta) {
        if (meta == null || meta.isMissingNode()) {
            return;
        }

        String symbol = normalizeSymbol(defaultIfBlank(meta.path("symbol").asText(""), fallbackSymbol));
        if (symbol.isBlank()) {
            return;
        }

        String name = defaultIfBlank(meta.path("name").asText(""), symbol);
        String exchange = defaultIfBlank(meta.path("exchange").asText(""), meta.path("mic_code").asText("UNKNOWN"));
        String currency = defaultIfBlank(meta.path("currency").asText(""), "");
        if (currency.isBlank()) {
            return;
        }

        upsertStockAsset(symbol, name, exchange, currency, instrumentTypeFor(symbol, defaultIfBlank(
                meta.path("instrument_type").asText(""), meta.path("type").asText(""))));
    }

    private List<Map<String, Object>> searchLocalSymbols(String query) {
        Map<String, StockAsset> matches = new LinkedHashMap<>();
        stockAssetRepository.findByTickerSymbolStartsWithIgnoreCase(query)
                .forEach(asset -> matches.put(asset.getTickerSymbol(), asset));
        stockAssetRepository.findByCompanyNameContainingIgnoreCase(query)
                .forEach(asset -> matches.put(asset.getTickerSymbol(), asset));

        return matches.values().stream()
                .limit(8)
                .map(this::toSuggestion)
                .toList();
    }

    private Map<String, Object> toSuggestion(StockAsset asset) {
        String symbol = normalizeSymbol(asset.getTickerSymbol());
        Map<String, Object> suggestion = new HashMap<>();
        suggestion.put("symbol", symbol);
        suggestion.put("name", defaultIfBlank(asset.getCompanyName(), symbol));
        suggestion.put("region", defaultIfBlank(asset.getExchange(), "UNKNOWN"));
        suggestion.put("currency", defaultIfBlank(asset.getCurrency(), "USD"));
        suggestion.put("instrumentType", asset.getInstrumentType() == null
                ? InstrumentType.EQUITY.name()
                : asset.getInstrumentType().name());
        suggestion.put("initials", initials(symbol));
        return suggestion;
    }

    private Map<String, Object> toSuggestion(MarketIndexCatalog.IndexDefinition index) {
        return toSuggestion(upsertStockAsset(index.symbol(), index.name(), index.exchange(), index.currency(),
                InstrumentType.INDEX));
    }

    @SafeVarargs
    private final List<Map<String, Object>> mergeSuggestions(List<Map<String, Object>>... groups) {
        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<>();
        for (List<Map<String, Object>> group : groups) {
            for (Map<String, Object> suggestion : group) {
                String symbol = String.valueOf(suggestion.getOrDefault("symbol", ""));
                if (!symbol.isBlank()) {
                    bySymbol.putIfAbsent(symbol, suggestion);
                }
            }
        }
        return bySymbol.values().stream().limit(8).toList();
    }

    private InstrumentType instrumentTypeFor(String symbol, String providerValue) {
        if (MarketIndexCatalog.isIndexSymbol(symbol)) {
            return InstrumentType.INDEX;
        }
        return InstrumentType.fromProviderValue(providerValue);
    }

    private long parseTimestamp(String datetime) {
        String normalized = datetime.contains(" ")
                ? datetime.replace(' ', 'T')
                : datetime;
        if (!normalized.contains("T")) {
            normalized = normalized + "T00:00:00";
        }
        return java.time.LocalDateTime.parse(normalized)
                .atZone(java.time.ZoneOffset.UTC)
                .toEpochSecond();
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void validateApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Twelve Data API key is not configured.");
        }
    }

    private String describeHttpError(RestClientResponseException e) {
        String response = e.getResponseBodyAsString();
        if (response == null || response.isBlank()) {
            return e.getStatusCode().value() + " " + e.getStatusText();
        }
        return e.getStatusCode().value() + " " + response.replaceAll("\\s+", " ").trim();
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String initials(String symbol) {
        String displaySymbol = symbol.startsWith("^") ? symbol.substring(1) : symbol;
        return displaySymbol.length() > 1 ? displaySymbol.substring(0, 2) : displaySymbol;
    }

    private int candidateRank(String requestedSymbol, ProviderAssetCandidate candidate) {
        int rank = 0;
        if (!candidate.symbol().equals(requestedSymbol)) {
            rank += 100;
        }
        if (isPlainTicker(requestedSymbol) && !isUsExchange(candidate.exchange())) {
            rank += 10;
        }
        if (isPlainTicker(requestedSymbol) && "USD".equalsIgnoreCase(candidate.currency())) {
            rank -= 3;
        }
        return rank;
    }

    private boolean isPlainTicker(String symbol) {
        return symbol != null && symbol.matches("[A-Z0-9-]+");
    }

    private boolean isUsExchange(String exchange) {
        String normalized = normalizeSymbol(exchange);
        return normalized.contains("NASDAQ")
                || normalized.contains("NYSE")
                || normalized.contains("AMEX")
                || normalized.contains("CBOE")
                || normalized.contains("IEX")
                || normalized.contains("OTC");
    }

    public record TwelveDataQuote(String symbol, double price, double percentChange, long timestamp) {
    }

    private record ProviderAssetCandidate(String symbol,
                                          String name,
                                          String exchange,
                                          String currency,
                                          InstrumentType instrumentType) {
    }
}
