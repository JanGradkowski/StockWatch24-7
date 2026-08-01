package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.MarketDataProvider;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class YahooFinanceService {
    private static final long WEEKLY_LOOKBACK_BUFFER_CANDLES = 8L;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final List<String> COMMON_EUROPEAN_SUFFIXES = List.of(
            ".WA", ".DE", ".BR", ".PA", ".AS", ".L", ".MI", ".MC", ".SW",
            ".VI", ".CO", ".ST", ".HE", ".OL", ".LS", ".F");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StockAssetRepository stockAssetRepository;
    private final ProviderSymbolRegistry providerSymbolRegistry;
    private final String baseUrl;
    private final boolean enabled;

    @Autowired
    public YahooFinanceService(RestTemplate restTemplate,
                               ObjectMapper objectMapper,
                               StockAssetRepository stockAssetRepository,
                               ProviderSymbolRegistry providerSymbolRegistry,
                               @Value("${yahoo-finance.base-url:https://query1.finance.yahoo.com}") String baseUrl,
                               @Value("${yahoo-finance.enabled:true}") boolean enabled) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.stockAssetRepository = stockAssetRepository;
        this.providerSymbolRegistry = providerSymbolRegistry;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.enabled = enabled;
    }

    YahooFinanceService(RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        StockAssetRepository stockAssetRepository,
                        String baseUrl,
                        boolean enabled) {
        this(restTemplate, objectMapper, stockAssetRepository, null, baseUrl, enabled);
    }

    public List<MarketDataBar> getTimeSeries(String rawSymbol, String appInterval, int outputSize) {
        return getTimeSeries(rawSymbol, appInterval, outputSize, null);
    }

    public List<MarketDataBar> getTimeSeriesBefore(String rawSymbol,
                                                   String appInterval,
                                                   int outputSize,
                                                   long beforeExclusive) {
        if (beforeExclusive <= 0L) {
            throw new IllegalArgumentException("Invalid historical candle cursor.");
        }
        return getTimeSeries(rawSymbol, appInterval, outputSize, beforeExclusive);
    }

    private List<MarketDataBar> getTimeSeries(String rawSymbol,
                                              String appInterval,
                                              int outputSize,
                                              Long beforeExclusive) {
        if (!enabled) {
            throw new IllegalStateException("Yahoo Finance fallback is disabled.");
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        Optional<StockAsset> asset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol);

        RuntimeException lastFailure = null;
        Set<String> candidates = initialCandidates(symbol, asset);
        for (String candidate : candidates) {
            try {
                ParsedSeries series = requestSeries(candidate, appInterval, outputSize, beforeExclusive,
                        beforeExclusive != null);
                verifyCandidateCompatibility(asset.orElse(null), candidate, series.meta());
                updateAssetMetadata(symbol, candidate, series.meta(), "DIRECT_OR_ALIAS");
                return series.bars();
            } catch (UnexpectedGranularityException e) {
                throw e;
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : searchCandidateSymbols(symbol, asset)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, appInterval, outputSize, beforeExclusive,
                        beforeExclusive != null);
                verifyCandidateCompatibility(asset.orElse(null), candidate, series.meta());
                updateAssetMetadata(symbol, candidate, series.meta(), "VERIFIED_SEARCH");
                return series.bars();
            } catch (UnexpectedGranularityException e) {
                throw e;
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : fallbackCandidateSymbols(symbol, asset)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, appInterval, outputSize, beforeExclusive,
                        beforeExclusive != null);
                verifyCandidateCompatibility(asset.orElse(null), candidate, series.meta());
                updateAssetMetadata(symbol, candidate, series.meta(), "SUFFIX_FALLBACK");
                return series.bars();
            } catch (UnexpectedGranularityException e) {
                throw e;
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        throw noDataFailure("candle data", symbol, candidates, lastFailure);
    }

    public StockAsset refreshStockAssetMetadata(String rawSymbol) {
        if (!enabled) {
            throw new IllegalStateException("Yahoo Finance fallback is disabled.");
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        Optional<StockAsset> asset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol);

        RuntimeException lastFailure = null;
        Set<String> candidates = initialCandidates(symbol, asset);
        for (String candidate : candidates) {
            try {
                ParsedSeries series = requestSeries(candidate, "1d", 1, null, false);
                verifyCandidateCompatibility(asset.orElse(null), candidate, series.meta());
                return updateAssetMetadata(symbol, candidate, series.meta(), "DIRECT_OR_ALIAS");
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : searchCandidateSymbols(symbol, asset)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, "1d", 1, null, false);
                verifyCandidateCompatibility(asset.orElse(null), candidate, series.meta());
                return updateAssetMetadata(symbol, candidate, series.meta(), "VERIFIED_SEARCH");
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : fallbackCandidateSymbols(symbol, asset)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, "1d", 1, null, false);
                verifyCandidateCompatibility(asset.orElse(null), candidate, series.meta());
                return updateAssetMetadata(symbol, candidate, series.meta(), "SUFFIX_FALLBACK");
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        throw noDataFailure("metadata", symbol, candidates, lastFailure);
    }

    private ParsedSeries requestSeries(String symbol,
                                       String appInterval,
                                       int outputSize,
                                       Long beforeExclusive,
                                       boolean allowEmpty) {
        String yahooInterval = toYahooInterval(appInterval);
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("v8", "finance", "chart", symbol)
                .queryParam("interval", yahooInterval);
        if (beforeExclusive != null
                || "1wk".equals(yahooInterval)
                || "1mo".equals(yahooInterval)) {
            long period2 = beforeExclusive == null ? Instant.now().getEpochSecond() : beforeExclusive;
            long requestedCandles = Math.max(1L, outputSize);
            long lookbackDays = historicalLookbackDays(yahooInterval, requestedCandles);
            long period1 = Instant.ofEpochSecond(period2)
                    .minus(lookbackDays, ChronoUnit.DAYS)
                    .getEpochSecond();
            uriBuilder
                    .queryParam("period1", period1)
                    .queryParam("period2", period2);
        } else {
            uriBuilder.queryParam("range", rangeFor(yahooInterval));
        }
        URI uri = uriBuilder
                .queryParam("events", "div,splits")
                .queryParam("includeAdjustedClose", true)
                .build()
                .encode()
                .toUri();

        JsonNode root = query(uri);
        JsonNode chart = root.path("chart");
        JsonNode error = chart.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException(error.path("description").asText("Yahoo Finance request failed."));
        }
        JsonNode results = chart.path("result");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("Yahoo Finance returned no chart result for " + symbol + ".");
        }

        JsonNode result = results.get(0);
        JsonNode meta = result.path("meta");
        validateReturnedGranularity(symbol, yahooInterval, meta);
        JsonNode timestamps = result.path("timestamp");
        JsonNode quotes = result.path("indicators").path("quote");
        if (!timestamps.isArray() || !quotes.isArray() || quotes.isEmpty()) {
            if (allowEmpty) {
                return new ParsedSeries(List.of(), meta);
            }
            throw new IllegalStateException("Yahoo Finance returned no OHLC data for " + symbol + ".");
        }

        JsonNode quote = quotes.get(0);
        JsonNode opens = quote.path("open");
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        JsonNode closes = quote.path("close");
        JsonNode volumes = quote.path("volume");
        String providerSymbol = normalizeSymbol(meta.path("symbol").asText(symbol));
        ZoneId exchangeZone = parseZone(meta.path("exchangeTimezoneName").asText("UTC"));

        List<MarketDataBar> bars = new ArrayList<>();
        for (int index = 0; index < timestamps.size(); index++) {
            Optional<Double> open = numberAt(opens, index);
            Optional<Double> high = numberAt(highs, index);
            Optional<Double> low = numberAt(lows, index);
            Optional<Double> close = numberAt(closes, index);
            if (open.isEmpty() || high.isEmpty() || low.isEmpty() || close.isEmpty()) {
                continue;
            }
            if (!isValidOhlc(open.get(), high.get(), low.get(), close.get())) {
                continue;
            }

            long timestamp = timestamps.get(index).asLong();
            timestamp = canonicalTimestamp(timestamp, yahooInterval, exchangeZone);
            long volume = numberAt(volumes, index).map(Double::longValue).orElse(0L);
            bars.add(new MarketDataBar(
                    providerSymbol,
                    timestamp,
                    open.get(),
                    high.get(),
                    low.get(),
                    close.get(),
                    Math.max(0L, volume)
            ));
        }
        if (bars.isEmpty()) {
            if (allowEmpty) {
                return new ParsedSeries(List.of(), meta);
            }
            throw new IllegalStateException("Yahoo Finance returned only incomplete candles for " + symbol + ".");
        }

        int first = Math.max(0, bars.size() - Math.max(1, outputSize));
        return new ParsedSeries(List.copyOf(bars.subList(first, bars.size())), meta);
    }

    private Set<String> initialCandidates(String symbol, Optional<StockAsset> asset) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (!hasExchangeSuffix(symbol) && asset.map(this::hasUsListingIdentity).orElse(false)) {
            candidates.add(symbol);
        }
        if (providerSymbolRegistry != null && asset.isPresent()) {
            providerSymbolRegistry.find(asset.get(), MarketDataProvider.YAHOO_FINANCE)
                    .map(ProviderSymbolRegistry.ProviderSymbolReference::symbol)
                    .ifPresent(candidates::add);
        }
        if (hasExchangeSuffix(symbol)) {
            candidates.add(symbol);
            return candidates;
        }

        asset.map(this::expectedYahooSuffix)
                .filter(suffix -> !suffix.isBlank())
                .map(suffix -> symbol + suffix)
                .ifPresent(candidates::add);
        candidates.add(symbol);
        return candidates;
    }

    private List<String> searchCandidateSymbols(String symbol, Optional<StockAsset> asset) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        asset.map(StockAsset::getCompanyName)
                .filter(name -> hasMeaningfulCompanyName(name, symbol))
                .ifPresent(queries::add);
        queries.add(symbol);

        for (String searchQuery : queries) {
            List<String> matches = querySearchCandidates(searchQuery, symbol, asset);
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        return List.of();
    }

    private List<String> querySearchCandidates(String searchQuery,
                                               String requestedSymbol,
                                               Optional<StockAsset> asset) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("v1", "finance", "search")
                .queryParam("q", searchQuery)
                .queryParam("quotesCount", 20)
                .queryParam("newsCount", 0)
                .build()
                .encode()
                .toUri();
        try {
            JsonNode quotes = query(uri).path("quotes");
            if (!quotes.isArray()) {
                return List.of();
            }
            List<YahooSearchCandidate> candidates = new ArrayList<>();
            for (JsonNode quote : quotes) {
                String quoteType = quote.path("quoteType").asText("");
                String candidateSymbol = normalizeSymbol(quote.path("symbol").asText(""));
                YahooSearchCandidate candidate = new YahooSearchCandidate(
                        candidateSymbol,
                        firstNonBlank(quote.path("longname").asText(""), quote.path("shortname").asText("")),
                        firstNonBlank(quote.path("exchDisp").asText(""), quote.path("exchange").asText("")),
                        quoteType
                );
                if (isSupportedQuoteType(quoteType)
                        && isCandidateEligible(requestedSymbol, asset.orElse(null), candidate)) {
                    candidates.add(candidate);
                }
            }
            return candidates.stream()
                    .sorted(Comparator.comparingInt(candidate ->
                            yahooCandidateRank(requestedSymbol, asset.orElse(null), candidate)))
                    .map(YahooSearchCandidate::symbol)
                    .distinct()
                    .toList();
        } catch (RuntimeException e) {
            System.err.println("Yahoo Finance symbol search unavailable for " + searchQuery + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<String> fallbackCandidateSymbols(String symbol, Optional<StockAsset> asset) {
        if (hasExchangeSuffix(symbol)) {
            return List.of();
        }
        if (asset.isPresent()) {
            String expectedSuffix = expectedYahooSuffix(asset.get());
            if (!expectedSuffix.isBlank()) {
                return List.of(symbol + expectedSuffix);
            }
            if (hasMeaningfulCompanyName(asset.get().getCompanyName(), symbol)) {
                return List.of();
            }
        }
        return COMMON_EUROPEAN_SUFFIXES.stream()
                .map(suffix -> symbol + suffix)
                .toList();
    }

    private JsonNode query(URI uri) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
            headers.set(HttpHeaders.REFERER, "https://finance.yahoo.com/");
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            String response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getBody();
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Yahoo Finance returned an empty response.");
            }
            return objectMapper.readTree(response);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Yahoo Finance request failed: " + describeHttpError(e), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Yahoo Finance request failed.", e);
        }
    }

    private StockAsset updateAssetMetadata(String requestedSymbol,
                                           String requestedProviderSymbol,
                                           JsonNode meta,
                                           String resolutionSource) {
        Optional<StockAsset> existingAsset = stockAssetRepository.findByTickerSymbolIgnoreCase(requestedSymbol);
        StockAsset asset = existingAsset.orElseGet(StockAsset::new);
        boolean authoritativeExactUsSymbol = normalizeSymbol(requestedProviderSymbol)
                .equals(normalizeSymbol(requestedSymbol))
                && existingAsset.map(this::hasUsListingIdentity).orElse(true);
        boolean preserveCanonicalListing = existingAsset.filter(this::hasReliableListingIdentity).isPresent()
                && !authoritativeExactUsSymbol;
        String name = firstNonBlank(meta.path("longName").asText(""), meta.path("shortName").asText(""));
        String exchange = firstNonBlank(meta.path("fullExchangeName").asText(""), meta.path("exchangeName").asText(""));
        String currency = meta.path("currency").asText("");
        String providerSymbol = normalizeSymbol(defaultIfBlank(
                meta.path("symbol").asText(""), requestedProviderSymbol));
        asset.setTickerSymbol(requestedSymbol);
        asset.setCompanyName(name.isBlank() ? requestedSymbol : name);
        if (!preserveCanonicalListing) {
            asset.setExchange(exchange.isBlank() ? "UNKNOWN" : exchange);
        }
        asset.setCurrency(currency.isBlank() ? "USD" : currency);
        InstrumentType providerType = InstrumentType.fromProviderValue(firstNonBlank(
                meta.path("instrumentType").asText(""), meta.path("quoteType").asText("")));
        if (MarketIndexCatalog.isIndexSymbol(requestedSymbol)) {
            providerType = InstrumentType.INDEX;
        } else if (providerType == InstrumentType.OTHER) {
            providerType = asset.getInstrumentType() == null ? InstrumentType.EQUITY : asset.getInstrumentType();
        }
        asset.setInstrumentType(providerType);
        StockAsset saved = stockAssetRepository.save(asset);
        StockAsset persisted = saved == null ? asset : saved;
        if (providerSymbolRegistry != null) {
            providerSymbolRegistry.remember(
                    persisted,
                    MarketDataProvider.YAHOO_FINANCE,
                    providerSymbol,
                    yahooMic(meta),
                    resolutionSource
            );
        }
        return persisted;
    }

    private void verifyCandidateCompatibility(StockAsset asset,
                                              String providerSymbol,
                                              JsonNode meta) {
        if (asset == null || !hasReliableListingIdentity(asset)) {
            return;
        }

        boolean authoritativeExactUsSymbol = normalizeSymbol(providerSymbol)
                .equals(normalizeSymbol(asset.getTickerSymbol()))
                && hasUsListingIdentity(asset);
        String expectedSuffix = expectedYahooSuffix(asset);
        if (!authoritativeExactUsSymbol
                && !expectedSuffix.isBlank()
                && !providerSymbol.endsWith(expectedSuffix)) {
            throw new IllegalStateException("Yahoo candidate " + providerSymbol
                    + " does not match the expected " + expectedSuffix + " exchange listing.");
        }

        String returnedName = firstNonBlank(
                meta.path("longName").asText(""),
                meta.path("shortName").asText("")
        );
        if (!authoritativeExactUsSymbol
                && !returnedName.isBlank()
                && !sameCompany(asset.getCompanyName(), returnedName)) {
            throw new IllegalStateException("Yahoo candidate " + providerSymbol
                    + " belongs to a different company.");
        }

        String returnedCurrency = normalizeSymbol(meta.path("currency").asText(""));
        String expectedCurrency = normalizeSymbol(asset.getCurrency());
        if (!authoritativeExactUsSymbol
                && !returnedCurrency.isBlank() && !expectedCurrency.isBlank()
                && !returnedCurrency.equals(expectedCurrency)) {
            throw new IllegalStateException("Yahoo candidate " + providerSymbol
                    + " uses " + returnedCurrency + " instead of " + expectedCurrency + ".");
        }

        InstrumentType returnedType = InstrumentType.fromProviderValue(firstNonBlank(
                meta.path("instrumentType").asText(""), meta.path("quoteType").asText("")));
        if (returnedType != InstrumentType.OTHER
                && asset.getInstrumentType() != null
                && returnedType != asset.getInstrumentType()) {
            throw new IllegalStateException("Yahoo candidate " + providerSymbol
                    + " has a different instrument type.");
        }
    }

    private boolean isCandidateEligible(String requestedSymbol,
                                        StockAsset asset,
                                        YahooSearchCandidate candidate) {
        if (candidate.symbol().isBlank()) {
            return false;
        }
        if (candidate.symbol().equals(requestedSymbol)
                || candidate.symbol().startsWith(requestedSymbol + ".")) {
            return true;
        }
        if (asset == null || !hasReliableListingIdentity(asset)
                || !sameCompany(asset.getCompanyName(), candidate.name())) {
            return false;
        }
        String expectedSuffix = expectedYahooSuffix(asset);
        return expectedSuffix.isBlank() || candidate.symbol().endsWith(expectedSuffix);
    }

    private int yahooCandidateRank(String requestedSymbol,
                                   StockAsset asset,
                                   YahooSearchCandidate candidate) {
        if (candidate.symbol().equals(requestedSymbol)) {
            return 0;
        }
        if (candidate.symbol().startsWith(requestedSymbol + ".")) {
            return 10;
        }
        boolean companyMatch = sameCompany(asset == null ? null : asset.getCompanyName(), candidate.name());
        int rank = companyMatch
                ? 20 + Math.min(20, tickerDistance(requestedSymbol, candidate.symbol()))
                : 100;
        String expectedSuffix = asset == null ? "" : expectedYahooSuffix(asset);
        if (!expectedSuffix.isBlank() && !candidate.symbol().endsWith(expectedSuffix)) {
            rank += 1_000;
        }
        return rank + secondaryVenuePenalty(candidate.exchange());
    }

    private boolean isSupportedQuoteType(String quoteType) {
        return "EQUITY".equalsIgnoreCase(quoteType)
                || "ETF".equalsIgnoreCase(quoteType)
                || "INDEX".equalsIgnoreCase(quoteType);
    }

    private boolean hasReliableListingIdentity(StockAsset asset) {
        return asset != null
                && hasMeaningfulCompanyName(asset.getCompanyName(), asset.getTickerSymbol())
                && asset.getExchange() != null
                && !asset.getExchange().isBlank()
                && !"UNKNOWN".equalsIgnoreCase(asset.getExchange());
    }

    private boolean hasUsListingIdentity(StockAsset asset) {
        if (asset == null) {
            return false;
        }
        String mic = normalizeSymbol(asset.getMicCode());
        if (Set.of(
                "XNAS", "XNCM", "XNMS", "XNGS", "XNYS",
                "XASE", "ARCX", "BATS", "IEXG", "OTCM").contains(mic)) {
            return true;
        }
        String country = normalizeSymbol(asset.getCountry());
        return country.equals("US")
                || country.equals("USA")
                || country.contains("UNITED STATES");
    }

    private boolean hasMeaningfulCompanyName(String companyName, String symbol) {
        return companyName != null
                && !companyName.isBlank()
                && !companyName.equalsIgnoreCase(symbol);
    }

    private boolean sameCompany(String left, String right) {
        String leftKey = companyKey(left);
        String rightKey = companyKey(right);
        return !leftKey.isBlank() && leftKey.equals(rightKey);
    }

    private String companyKey(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = Normalizer.normalize(rawName, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();
        List<List<String>> legalSuffixes = List.of(
                List.of("PUBLIC", "LIMITED", "COMPANY"),
                List.of("SOCIETE", "ANONYME"),
                List.of("SOCIETA", "PER", "AZIONI"),
                List.of("NAAMLOZE", "VENNOOTSCHAP"),
                List.of("SPOLKA", "AKCYJNA"),
                List.of("S", "A"),
                List.of("N", "V"),
                List.of("B", "V"),
                List.of("S", "E"),
                List.of("SA"),
                List.of("SE"),
                List.of("AG"),
                List.of("AKTIENGESELLSCHAFT"),
                List.of("NV"),
                List.of("PLC"),
                List.of("INC"),
                List.of("INCORPORATED"),
                List.of("CORP"),
                List.of("CORPORATION"),
                List.of("LTD"),
                List.of("LIMITED"),
                List.of("SPA"),
                List.of("SAS"),
                List.of("BV")
        );
        List<String> tokens = new ArrayList<>(List.of(normalized.split("\\s+")));
        boolean removed;
        do {
            removed = false;
            for (List<String> suffix : legalSuffixes) {
                if (endsWith(tokens, suffix)) {
                    for (int index = 0; index < suffix.size(); index++) {
                        tokens.removeLast();
                    }
                    removed = true;
                    break;
                }
            }
        } while (removed);
        return String.join(" ", tokens);
    }

    private boolean endsWith(List<String> tokens, List<String> suffix) {
        if (tokens.size() < suffix.size()) {
            return false;
        }
        int offset = tokens.size() - suffix.size();
        for (int index = 0; index < suffix.size(); index++) {
            if (!tokens.get(offset + index).equals(suffix.get(index))) {
                return false;
            }
        }
        return true;
    }

    private String expectedYahooSuffix(StockAsset asset) {
        String fromMic = suffixForExchange(asset.getMicCode());
        return fromMic.isBlank() ? suffixForExchange(asset.getExchange()) : fromMic;
    }

    private int tickerDistance(String requestedSymbol, String candidateSymbol) {
        String requested = comparableTickerRoot(requestedSymbol);
        String candidate = comparableTickerRoot(candidateSymbol);
        if (requested.isBlank() || candidate.isBlank()) {
            return 20;
        }
        int[] previous = new int[candidate.length() + 1];
        for (int column = 0; column <= candidate.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= requested.length(); row++) {
            int[] current = new int[candidate.length() + 1];
            current[0] = row;
            for (int column = 1; column <= candidate.length(); column++) {
                int substitution = requested.charAt(row - 1) == candidate.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitution
                );
            }
            previous = current;
        }
        return previous[candidate.length()];
    }

    private String comparableTickerRoot(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol.startsWith("^")) {
            symbol = symbol.substring(1);
        }
        int suffix = symbol.indexOf('.');
        if (suffix > 0) {
            symbol = symbol.substring(0, suffix);
        }
        return symbol.replaceAll("[^A-Z0-9]", "");
    }

    private int secondaryVenuePenalty(String rawExchange) {
        String exchange = normalizeSymbol(rawExchange);
        if (exchange.contains("OTC") || exchange.equals("PNK") || exchange.contains("PINK")) {
            return 40;
        }
        if (exchange.equals("IOB") || exchange.contains("INTERNATIONAL ORDERBOOK")) {
            return 25;
        }
        if (Set.of("FRA", "DUS", "MUN", "STU", "BER", "HAM").contains(exchange)) {
            return 10;
        }
        return 0;
    }

    private String yahooMic(JsonNode meta) {
        String explicitMic = normalizeSymbol(firstNonBlank(
                meta.path("mic").asText(""), meta.path("micCode").asText("")));
        if (explicitMic.matches("[A-Z0-9]{4,12}")) {
            return explicitMic;
        }
        String exchange = normalizeSymbol(
                meta.path("exchangeName").asText("") + " "
                        + meta.path("fullExchangeName").asText(""));
        if (exchange.contains("WSE") || exchange.contains("WARSAW")) return "XWAR";
        if (exchange.contains("NASDAQ") || exchange.contains("NMS")
                || exchange.contains("NMQ") || exchange.contains("NGM")) return "XNAS";
        if (exchange.contains("NYSE") || exchange.contains("NYQ")) return "XNYS";
        if (exchange.contains("XETRA") || exchange.equals("GER")) return "XETR";
        if (exchange.contains("FRANKFURT") || exchange.contains("FRA")) return "XFRA";
        if (exchange.contains("LONDON") || exchange.contains("LSE")) return "XLON";
        if (exchange.contains("AMSTERDAM")) return "XAMS";
        if (exchange.contains("BRUSSELS")) return "XBRU";
        if (exchange.contains("PARIS")) return "XPAR";
        if (exchange.contains("MILAN")) return "XMIL";
        if (exchange.contains("MADRID")) return "XMAD";
        if (exchange.contains("SWISS") || exchange.contains("SIX")) return "XSWX";
        if (exchange.contains("VIENNA")) return "XWBO";
        if (exchange.contains("COPENHAGEN")) return "XCSE";
        if (exchange.contains("STOCKHOLM")) return "XSTO";
        if (exchange.contains("HELSINKI")) return "XHEL";
        if (exchange.contains("OSLO")) return "XOSL";
        if (exchange.contains("LISBON")) return "XLIS";
        return null;
    }

    private IllegalStateException noDataFailure(String dataType,
                                                String symbol,
                                                Set<String> attemptedSymbols,
                                                RuntimeException cause) {
        String attempts = attemptedSymbols.isEmpty()
                ? ""
                : " Tried provider symbols: " + String.join(", ", attemptedSymbols) + ".";
        String failureDetail = cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? ""
                : " Last provider failure: " + cause.getMessage();
        return new IllegalStateException(
                "Yahoo Finance has no " + dataType + " for " + symbol + "." + attempts + failureDetail,
                cause
        );
    }

    private String toYahooInterval(String interval) {
        return switch (interval) {
            case "1wk", "1week" -> "1wk";
            case "1mo", "1month" -> "1mo";
            case "1min", "5min", "15min", "30min" -> interval.replace("min", "m");
            case "60min", "1h" -> "60m";
            default -> "1d";
        };
    }

    private String rangeFor(String interval) {
        return switch (interval) {
            case "1m" -> "7d";
            case "5m", "15m", "30m", "60m" -> "60d";
            default -> "5y";
        };
    }

    private long historicalLookbackDays(String interval, long requestedCandles) {
        return switch (interval) {
            case "1wk" -> Math.multiplyExact(requestedCandles + WEEKLY_LOOKBACK_BUFFER_CANDLES, 7L);
            case "1mo" -> Math.multiplyExact(requestedCandles + 4L, 35L);
            case "1d" -> Math.addExact(Math.multiplyExact(requestedCandles, 2L), 30L);
            default -> Math.addExact(requestedCandles, 7L);
        };
    }

    private void validateReturnedGranularity(String symbol, String requestedInterval, JsonNode meta) {
        if (!Set.of("1d", "1wk", "1mo").contains(requestedInterval)) {
            return;
        }
        String returnedInterval = meta.path("dataGranularity").asText("").trim().toLowerCase(Locale.ROOT);
        if (!requestedInterval.equals(returnedInterval)) {
            String actual = returnedInterval.isBlank() ? "an unspecified interval" : returnedInterval;
            throw new UnexpectedGranularityException(
                    "Yahoo Finance returned " + actual + " candles for " + symbol
                            + " when " + requestedInterval + " was requested.");
        }
    }

    private long canonicalTimestamp(long timestamp, String interval, ZoneId exchangeZone) {
        if (interval.endsWith("m")) {
            return timestamp;
        }
        LocalDate exchangeDate = Instant.ofEpochSecond(timestamp).atZone(exchangeZone).toLocalDate();
        return exchangeDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private Optional<Double> numberAt(JsonNode values, int index) {
        if (!values.isArray() || index >= values.size()) {
            return Optional.empty();
        }
        JsonNode value = values.get(index);
        if (value == null || value.isNull() || !value.isNumber()) {
            return Optional.empty();
        }
        double number = value.asDouble();
        return Double.isFinite(number) ? Optional.of(number) : Optional.empty();
    }

    private boolean isValidOhlc(double open, double high, double low, double close) {
        return open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0
                && high >= Math.max(open, close)
                && low <= Math.min(open, close)
                && high >= low;
    }

    private boolean hasExchangeSuffix(String symbol) {
        return symbol.contains(".") || symbol.contains("=") || symbol.startsWith("^");
    }

    private String suffixForExchange(String exchange) {
        String value = normalizeSymbol(exchange);
        if (value.contains("XETRA") || value.contains("XETR") || value.contains("GERMAN") || value.equals("GER")) return ".DE";
        if (value.contains("FRANKFURT") || value.contains("XFRA")) return ".F";
        if (value.contains("LONDON") || value.contains("LSE") || value.contains("XLON")) return ".L";
        if (value.contains("AMSTERDAM") || value.contains("XAMS")) return ".AS";
        if (value.contains("BRUSSELS") || value.contains("XBRU")) return ".BR";
        if (value.contains("PARIS") || value.contains("XPAR")) return ".PA";
        if (value.contains("MILAN") || value.contains("ITAL") || value.contains("XMIL")) return ".MI";
        if (value.contains("MADRID") || value.contains("SPAIN") || value.contains("XMAD")) return ".MC";
        if (value.contains("SWISS") || value.contains("SIX") || value.contains("XSWX")) return ".SW";
        if (value.contains("VIENNA") || value.contains("XWBO")) return ".VI";
        if (value.contains("COPENHAGEN") || value.contains("XCSE")) return ".CO";
        if (value.contains("STOCKHOLM") || value.contains("XSTO")) return ".ST";
        if (value.contains("HELSINKI") || value.contains("XHEL")) return ".HE";
        if (value.contains("OSLO") || value.contains("XOSL")) return ".OL";
        if (value.contains("WARSAW") || value.contains("WARSZAWA") || value.contains("XWAR")
                || value.equals("WSE") || value.equals("GPW") || value.contains("POLISH")) return ".WA";
        if (value.contains("LISBON") || value.contains("XLIS")) return ".LS";
        return "";
    }

    private ZoneId parseZone(String zoneName) {
        try {
            return ZoneId.of(zoneName);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://query1.finance.yahoo.com";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String describeHttpError(RestClientResponseException e) {
        String response = e.getResponseBodyAsString();
        if (response == null || response.isBlank()) {
            return e.getStatusCode().value() + " " + e.getStatusText();
        }
        return e.getStatusCode().value() + " " + response.replaceAll("\\s+", " ").trim();
    }

    private record ParsedSeries(List<MarketDataBar> bars, JsonNode meta) {
    }

    private record YahooSearchCandidate(String symbol,
                                        String name,
                                        String exchange,
                                        String quoteType) {
    }

    private static final class UnexpectedGranularityException extends IllegalStateException {
        private UnexpectedGranularityException(String message) {
            super(message);
        }
    }
}
