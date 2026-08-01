package org.example.stockwatch247.service.insider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.enums.InsiderTradeType;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class ApiNinjasInsiderClient implements InsiderTradeProvider {
    public static final String PROVIDER_NAME = "API_NINJAS";
    private static final int FREE_TIER_RESULT_LIMIT = 10;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RequestRateLimiter requestRateLimiter;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final int sharedHourlyLimit;

    public ApiNinjasInsiderClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            RequestRateLimiter requestRateLimiter,
            @Value("${insider-activity.enabled:true}") boolean enabled,
            @Value("${insider-activity.api-ninjas.base-url:https://api.api-ninjas.com/v1}")
            String baseUrl,
            @Value("${insider-activity.api-ninjas.api-key:}") String apiKey,
            @Value("${insider-activity.api-ninjas.shared-hourly-limit:100}")
            int sharedHourlyLimit) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.requestRateLimiter = requestRateLimiter;
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.sharedHourlyLimit = Math.max(1, Math.min(sharedHourlyLimit, 100));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<ProviderTrade> fetchTickerTrades(String ticker) {
        requireConfigured();
        String normalizedTicker = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        if (normalizedTicker.isBlank()) {
            throw new IllegalArgumentException("A ticker is required.");
        }
        if (!requestRateLimiter.tryAcquire(
                "api-ninjas-insider-shared-hour",
                sharedHourlyLimit,
                Duration.ofHours(1))) {
            throw new InsiderDataUnavailableException(
                    "The shared API Ninjas insider-data limit of "
                            + sharedHourlyLimit + " requests per hour has been reached.");
        }

        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("insidertransactions")
                .queryParam("ticker", normalizedTicker)
                .build()
                .encode()
                .toUri();
        return parseResponse(request(uri)).stream()
                .filter(trade -> normalizedTicker.equalsIgnoreCase(trade.ticker()))
                .limit(FREE_TIER_RESULT_LIMIT)
                .toList();
    }

    private String request(URI uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", apiKey);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
            String payload = response.getBody();
            if (payload == null || payload.isBlank()) {
                throw new InsiderDataUnavailableException(
                        "API Ninjas returned an empty insider-data response.");
            }
            return payload;
        } catch (HttpClientErrorException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new InsiderDataUnavailableException(
                        "API Ninjas rejected the insider API credential.");
            }
            if (status == 429) {
                throw new InsiderDataUnavailableException(
                        "The API Ninjas insider-data quota has been reached.");
            }
            throw new InsiderDataUnavailableException(
                    "API Ninjas insider data is temporarily unavailable.", exception);
        } catch (InsiderDataUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new InsiderDataUnavailableException(
                    "API Ninjas insider data is temporarily unavailable.", exception);
        }
    }

    private List<ProviderTrade> parseResponse(String payload) {
        try {
            JsonNode rows = objectMapper.readTree(payload);
            if (!rows.isArray()) {
                throw new InsiderDataUnavailableException(
                        "API Ninjas returned an unexpected insider response.");
            }
            List<ProviderTrade> trades = new ArrayList<>();
            rows.forEach(node -> parseTrade(node).ifPresent(trades::add));
            return List.copyOf(trades);
        } catch (java.io.IOException exception) {
            throw new InsiderDataUnavailableException(
                    "API Ninjas returned unreadable insider data.", exception);
        }
    }

    private Optional<ProviderTrade> parseTrade(JsonNode node) {
        String transactionCode = clean(node.path("transaction_code").asText(null));
        String transactionType = clean(node.path("transaction_type").asText(null));
        Optional<InsiderTradeType> type = InsiderTradeType.fromProviderValue(
                transactionCode == null ? transactionType : transactionCode);
        String ticker = clean(node.path("ticker").asText(null));
        String insiderName = clean(node.path("insider_name").asText(null));
        LocalDate filingDate = parseDate(node.path("filing_date").asText(null));
        if (type.isEmpty() || ticker == null || insiderName == null || filingDate == null) {
            return Optional.empty();
        }

        // API Ninjas' free response does not expose transaction_date. Filing date
        // is deliberately used as the effective date and disclosed in the UI.
        return Optional.of(new ProviderTrade(
                ticker.toUpperCase(Locale.ROOT),
                insiderName,
                clean(node.path("insider_position").asText(null)),
                type.get(),
                transactionCode == null ? type.get().name() : transactionCode,
                filingDate,
                filingDate,
                positiveDecimal(node.get("shares")),
                positiveDecimal(node.get("transaction_price")),
                nonNegativeDecimal(node.get("remaining_shares")),
                clean(node.path("transaction_name").asText(null)),
                safeSecUrl(node.path("sec_filing_url").asText(null))));
    }

    private BigDecimal positiveDecimal(JsonNode node) {
        BigDecimal value = decimal(node);
        return value != null && value.signum() > 0 ? value : null;
    }

    private BigDecimal nonNegativeDecimal(JsonNode node) {
        BigDecimal value = decimal(node);
        return value != null && value.signum() >= 0 ? value : null;
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String safeSecUrl(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        try {
            URI uri = new URI(cleaned);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("sec.gov")
                    || host.toLowerCase(Locale.ROOT).endsWith(".sec.gov"))
                    ? uri.toString()
                    : null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private String clean(String value) {
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
            throw new IllegalArgumentException("API Ninjas base URL must not be blank.");
        }
        return normalized;
    }

    private void requireConfigured() {
        if (!enabled) {
            throw new InsiderDataUnavailableException("Insider activity tracking is disabled.");
        }
        if (apiKey.isBlank()) {
            throw new InsiderDataUnavailableException(
                    "API Ninjas insider activity is not configured.");
        }
    }
}
