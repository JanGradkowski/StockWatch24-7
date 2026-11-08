package org.example.stockwatch247.service.customalert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant writes custom alerts from assistant/custom-alerts/reference.md. Every value the rule parser accepts must
 * be documented there, so a new operator, window or condition kind cannot be added without telling the assistant.
 */
class CustomAlertAssistantReferenceTest {
    @Test
    void theReferenceDocumentsEveryValueTheParserAccepts() throws IOException {
        String reference;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("assistant/custom-alerts/reference.md")) {
            assertThat(in).isNotNull();
            reference = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Set<String>> lists = List.of(CustomAlertDefinition.MATCHES, CustomAlertDefinition.GROUP_MATCHES,
                CustomAlertDefinition.OPERATORS, CustomAlertDefinition.PRICE_FIELDS, CustomAlertDefinition.DIRECTIONS,
                CustomAlertDefinition.PATTERN_STATUSES, CustomAlertDefinition.STRENGTHS, CustomAlertDefinition.TRANSACTIONS,
                CustomAlertDefinition.ROLES, CustomAlertDefinition.CHAMBERS, Set.copyOf(CustomAlertDefinition.FAMILIES),
                Set.of("VALUE", "PATTERN", "OUTLOOK", "INSIDER", "CONGRESS", "LATEST", "ANY", "EVERY", "PRICE", "INDICATOR"),
                Set.of("BECOMES_TRUE", "ONCE", "EVERY_CANDLE", "DAILY", "WEEKLY", "MONTHLY"));
        String body = reference;
        lists.stream().flatMap(Set::stream).forEach(value -> assertThat(Pattern.compile("\\b" + value + "\\b").matcher(body).find())
                .as(value + " is documented in assistant/custom-alerts/reference.md").isTrue());
        Stream.of("\"step\"", "\"within\"", "\"span\"", "\"minScore\"", "\"stillValid\"", "\"minTotal\"", "\"minCount\"", "`timeframe`")
                .forEach(field -> assertThat(body).as(field).contains(field));
    }
}
