package org.example.stockwatch247.service.congress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.enums.CongressionalTradeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CongressInvestsClient implements CongressionalTradeProvider {
    public static final String PROVIDER_NAME = "CONGRESS_INVESTS";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CongressionalProviderRequestBudget requestBudget;
    private final boolean enabled;
    private final String baseUrl;
    private final int pageSize;
    private final int maximumHistoryPages;
    private final int recentTradeLimit;
    private final int dailyRequestBudget;
    private final String apiKey;

    public CongressInvestsClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            CongressionalProviderRequestBudget requestBudget,
            @Value("${congressional-activity.enabled:true}") boolean enabled,
            @Value("${congressional-activity.congress-invests.base-url:https://congressinfor-production.up.railway.app}")
            String baseUrl,
            @Value("${congressional-activity.congress-invests.page-size:500}") int pageSize,
            @Value("${congressional-activity.congress-invests.maximum-history-pages:3}") int maximumHistoryPages,
            @Value("${congressional-activity.congress-invests.recent-trade-limit:500}") int recentTradeLimit,
            @Value("${congressional-activity.congress-invests.daily-request-budget:90}") int dailyRequestBudget,
            @Value("${congressional-activity.congress-invests.api-key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.requestBudget = requestBudget;
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.pageSize = Math.max(1, Math.min(pageSize, 500));
        this.maximumHistoryPages = Math.max(1, maximumHistoryPages);
        this.recentTradeLimit = Math.max(1, Math.min(recentTradeLimit, 500));
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.dailyRequestBudget = this.apiKey.isBlank()
                ? Math.max(1, Math.min(dailyRequestBudget, 99))
                : Math.max(1, dailyRequestBudget);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderBatch fetchTickerHistory(String ticker) {
        requireEnabled();
        String normalizedTicker = normalizeTicker(ticker);
        Map<String, ProviderTrade> uniqueTrades = new LinkedHashMap<>();
        Instant providerUpdatedAt = null;
        int offset = 0;

        for (int page = 0; page < maximumHistoryPages; page++) {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .pathSegment("trades", normalizedTicker)
                    .queryParam("limit", pageSize)
                    .queryParam("offset", offset)
                    .build()
                    .encode()
                    .toUri();
            JsonNode response = request(uri);
            ParsedResponse parsed = parseResponse(response);
            providerUpdatedAt = latest(providerUpdatedAt, parsed.providerUpdatedAt());
            parsed.trades().forEach(trade -> uniqueTrades.putIfAbsent(identityKey(trade), trade));
            if (!parsed.hasMore() || parsed.returnedCount() == 0) {
                break;
            }
            offset += parsed.returnedCount();
        }

        return new ProviderBatch(new ArrayList<>(uniqueTrades.values()), providerUpdatedAt);
    }

    @Override
    public ProviderBatch fetchRecentTrades() {
        requireEnabled();
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("trades")
                .queryParam("limit", recentTradeLimit)
                .build()
                .encode()
                .toUri();
        ParsedResponse response = parseResponse(request(uri));
        return new ProviderBatch(response.trades(), response.providerUpdatedAt());
    }

    private JsonNode request(URI uri) {
        requestBudget.consume(PROVIDER_NAME, dailyRequestBudget);
        try {
            HttpHeaders headers = new HttpHeaders();
            if (!apiKey.isBlank()) {
                headers.set("X-Api-Key", apiKey);
            }
            String payload = restTemplate.exchange(
                            uri,
                            HttpMethod.GET,
                            new HttpEntity<>(headers),
                            String.class)
                    .getBody();
            if (payload == null || payload.isBlank()) {
                throw new IllegalStateException("CongressInvests returned an empty response.");
            }
            return objectMapper.readTree(payload);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RestClientException | java.io.IOException exception) {
            throw new IllegalStateException("CongressInvests data is temporarily unavailable.", exception);
        }
    }

    private ParsedResponse parseResponse(JsonNode root) {
        JsonNode tradesNode = root.path("trades");
        if (!tradesNode.isArray()) {
            throw new IllegalStateException("CongressInvests returned an unexpected response.");
        }
        List<ProviderTrade> trades = new ArrayList<>();
        for (JsonNode tradeNode : tradesNode) {
            parseTrade(tradeNode).ifPresent(trades::add);
        }
        return new ParsedResponse(
                List.copyOf(trades),
                root.path("has_more").asBoolean(false),
                tradesNode.size(),
                parseInstant(root.path("last_updated").asText(null)));
    }

    private java.util.Optional<ProviderTrade> parseTrade(JsonNode node) {
        String ticker = normalizeTicker(node.path("ticker").asText(""));
        String member = cleanRequired(node.path("member").asText(null));
        String chamber = cleanRequired(node.path("chamber").asText(null));
        String amount = cleanRequired(node.path("amount").asText(null));
        LocalDate transactionDate = parseDate(node.path("tx_date").asText(null));
        LocalDate disclosureDate = parseDate(node.path("disclosed").asText(null));
        var type = CongressionalTradeType.fromProviderValue(node.path("trade_type").asText(null));
        if (ticker.isBlank() || member == null || chamber == null || amount == null
                || transactionDate == null || disclosureDate == null || type.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ProviderTrade(
                member,
                chamber,
                ticker,
                type.get(),
                amount,
                transactionDate,
                disclosureDate,
                cleanOptional(node.path("asset").asText(null)),
                safeHttpUrl(node.path("link").asText(null))));
    }

    private LocalDate parseDate(String rawValue) {
        try {
            return rawValue == null || rawValue.isBlank() ? null : LocalDate.parse(rawValue.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Instant parseInstant(String rawValue) {
        try {
            return rawValue == null || rawValue.isBlank() ? null : Instant.parse(rawValue.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String safeHttpUrl(String rawValue) {
        String value = cleanOptional(rawValue);
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean officialDisclosureHost = host != null
                    && (host.equalsIgnoreCase("efdsearch.senate.gov")
                    || host.toLowerCase(Locale.ROOT).endsWith(".senate.gov")
                    || host.equalsIgnoreCase("disclosures-clerk.house.gov")
                    || host.toLowerCase(Locale.ROOT).endsWith(".house.gov"));
            return "https".equalsIgnoreCase(scheme) && officialDisclosureHost
                    ? uri.toString()
                    : null;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private String identityKey(ProviderTrade trade) {
        return String.join("|",
                trade.ticker(),
                trade.memberName().toUpperCase(Locale.ROOT),
                trade.chamber().toUpperCase(Locale.ROOT),
                trade.transactionType().name(),
                trade.amountRange(),
                trade.transactionDate().toString(),
                trade.disclosureDate().toString(),
                trade.sourceUrl() == null ? "" : trade.sourceUrl());
    }

    private Instant latest(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Congressional activity tracking is disabled.");
        }
    }

    private String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private String cleanRequired(String value) {
        String cleaned = cleanOptional(value);
        return cleaned == null || cleaned.isBlank() ? null : cleaned;
    }

    private String cleanOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String stripTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("CongressInvests base URL must not be blank.");
        }
        return normalized;
    }

    private record ParsedResponse(
            List<ProviderTrade> trades,
            boolean hasMore,
            int returnedCount,
            Instant providerUpdatedAt) {
    }
}
