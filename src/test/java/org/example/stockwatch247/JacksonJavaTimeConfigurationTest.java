package org.example.stockwatch247;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thymeleaf.standard.serializer.StandardJavaScriptSerializer;

import java.io.StringWriter;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonJavaTimeConfigurationTest {

    private static final LocalDateTime SAMPLE_TIME =
            LocalDateTime.of(2026, 9, 3, 15, 53);

    @Test
    void applicationMapperSerializesJavaTimeValues() throws Exception {
        ObjectMapper mapper = new StockWatch247Application().objectMapper();

        assertThat(mapper.writeValueAsString(SAMPLE_TIME))
                .isEqualTo("\"2026-09-03T15:53:00\"");
    }

    @Test
    void thymeleafJavaScriptSerializerSerializesJavaTimeValues() {
        StandardJavaScriptSerializer serializer = new StandardJavaScriptSerializer(true);
        StringWriter output = new StringWriter();

        serializer.serializeValue(SAMPLE_TIME, output);

        assertThat(output.toString()).isEqualTo("\"2026-09-03T15:53:00\"");
    }
}
