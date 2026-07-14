package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class YahooFinanceService {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final List<String> COMMON_EUROPEAN_SUFFIXES = List.of(
            ".WA", ".DE", ".BR", ".PA", ".AS", ".L", ".MI", ".MC", ".SW",
            ".VI", ".CO", ".ST", ".HE", ".OL", ".LS", ".F");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StockAssetRepository stockAssetRepository;
    private final String baseUrl;
    private final boolean enabled;

    public YahooFinanceService(RestTemplate restTemplate,
                               ObjectMapper objectMapper,
                               StockAssetRepository stockAssetRepository,
                               @Value("${yahoo-finance.base-url:https://query1.finance.yahoo.com}") String baseUrl,
                               @Value("${yahoo-finance.enabled:true}") boolean enabled) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.stockAssetRepository = stockAssetRepository;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.enabled = enabled;
    }

    public List<MarketDataBar> getTimeSeries(String rawSymbol, String appInterval, int outputSize) {
        if (!enabled) {
            throw new IllegalStateException("Yahoo Finance fallback is disabled.");
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);

        RuntimeException lastFailure = null;
        Set<String> candidates = initialCandidates(symbol);
        for (String candidate : candidates) {
            try {
                ParsedSeries series = requestSeries(candidate, appInterval, outputSize);
                updateAssetMetadata(symbol, series.meta());
                return series.bars();
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : searchCandidateSymbols(symbol)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, appInterval, outputSize);
                updateAssetMetadata(symbol, series.meta());
                return series.bars();
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : fallbackCandidateSymbols(symbol)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, appInterval, outputSize);
                updateAssetMetadata(symbol, series.meta());
                return series.bars();
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        throw new IllegalStateException("Yahoo Finance has no candle data for " + symbol + ".", lastFailure);
    }

    public StockAsset refreshStockAssetMetadata(String rawSymbol) {
        if (!enabled) {
            throw new IllegalStateException("Yahoo Finance fallback is disabled.");
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);

        RuntimeException lastFailure = null;
        Set<String> candidates = initialCandidates(symbol);
        for (String candidate : candidates) {
            try {
                ParsedSeries series = requestSeries(candidate, "1d", 1);
                return updateAssetMetadata(symbol, series.meta());
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : searchCandidateSymbols(symbol)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, "1d", 1);
                return updateAssetMetadata(symbol, series.meta());
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        for (String candidate : fallbackCandidateSymbols(symbol)) {
            if (!candidates.add(candidate)) {
                continue;
            }
            try {
                ParsedSeries series = requestSeries(candidate, "1d", 1);
                return updateAssetMetadata(symbol, series.meta());
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        throw new IllegalStateException("Yahoo Finance has no metadata for " + symbol + ".", lastFailure);
    }

    private ParsedSeries requestSeries(String symbol, String appInterval, int outputSize) {
        String yahooInterval = toYahooInterval(appInterval);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("v8", "finance", "chart", symbol)
                .queryParam("interval", yahooInterval)
                .queryParam("range", rangeFor(yahooInterval))
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
        JsonNode timestamps = result.path("timestamp");
        JsonNode quotes = result.path("indicators").path("quote");
        if (!timestamps.isArray() || !quotes.isArray() || quotes.isEmpty()) {
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
            throw new IllegalStateException("Yahoo Finance returned only incomplete candles for " + symbol + ".");
        }

        int first = Math.max(0, bars.size() - Math.max(1, outputSize));
        return new ParsedSeries(List.copyOf(bars.subList(first, bars.size())), meta);
    }

    private Set<String> initialCandidates(String symbol) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (hasExchangeSuffix(symbol)) {
            candidates.add(symbol);
            return candidates;
        }

        stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .map(StockAsset::getExchange)
                .map(this::suffixForExchange)
                .filter(suffix -> !suffix.isBlank())
                .map(suffix -> symbol + suffix)
                .ifPresent(candidates::add);
        candidates.add(symbol);
        return candidates;
    }

    private List<String> searchCandidateSymbols(String symbol) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("v1", "finance", "search")
                .queryParam("q", symbol)
                .queryParam("quotesCount", 10)
                .queryParam("newsCount", 0)
                .build()
                .encode()
                .toUri();
        try {
            JsonNode quotes = query(uri).path("quotes");
            if (!quotes.isArray()) {
                return List.of();
            }
            List<String> candidates = new ArrayList<>();
            for (JsonNode quote : quotes) {
                String quoteType = quote.path("quoteType").asText("");
                String candidate = normalizeSymbol(quote.path("symbol").asText(""));
                if (("EQUITY".equalsIgnoreCase(quoteType) || "ETF".equalsIgnoreCase(quoteType)
                        || "INDEX".equalsIgnoreCase(quoteType))
                        && (candidate.equals(symbol) || candidate.startsWith(symbol + "."))) {
                    candidates.add(candidate);
                }
            }
            return candidates;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<String> fallbackCandidateSymbols(String symbol) {
        if (hasExchangeSuffix(symbol)) {
            return List.of();
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

    private StockAsset updateAssetMetadata(String requestedSymbol, JsonNode meta) {
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(requestedSymbol)
                .orElseGet(StockAsset::new);
        String name = firstNonBlank(meta.path("longName").asText(""), meta.path("shortName").asText(""));
        String exchange = firstNonBlank(meta.path("fullExchangeName").asText(""), meta.path("exchangeName").asText(""));
        String currency = meta.path("currency").asText("");
        asset.setTickerSymbol(requestedSymbol);
        asset.setCompanyName(name.isBlank() ? requestedSymbol : name);
        asset.setExchange(exchange.isBlank() ? "UNKNOWN" : exchange);
        asset.setCurrency(currency.isBlank() ? "USD" : currency);
        InstrumentType providerType = InstrumentType.fromProviderValue(firstNonBlank(
                meta.path("instrumentType").asText(""), meta.path("quoteType").asText("")));
        if (MarketIndexCatalog.isIndexSymbol(requestedSymbol)) {
            providerType = InstrumentType.INDEX;
        } else if (providerType == InstrumentType.OTHER) {
            providerType = asset.getInstrumentType() == null ? InstrumentType.EQUITY : asset.getInstrumentType();
        }
        asset.setInstrumentType(providerType);
        return stockAssetRepository.save(asset);
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
            case "1mo" -> "max";
            case "1wk" -> "max";
            default -> "5y";
        };
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

    private String describeHttpError(RestClientResponseException e) {
        String response = e.getResponseBodyAsString();
        if (response == null || response.isBlank()) {
            return e.getStatusCode().value() + " " + e.getStatusText();
        }
        return e.getStatusCode().value() + " " + response.replaceAll("\\s+", " ").trim();
    }

    private record ParsedSeries(List<MarketDataBar> bars, JsonNode meta) {
    }
}
