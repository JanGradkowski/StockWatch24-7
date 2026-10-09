package org.example.stockwatch247.i18n;

import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * JSON responses carry labels that services build in English (they are cached and shared between users).
 * The converter that writes HTTP responses translates those display-only fields into the request's
 * language; codes, names and the mapper that services use for storage are left alone.
 */
@Configuration
public class DisplayTextJsonConfiguration {
    /** Display text whose name does not end in one of the {@link #SUFFIXES}. */
    static final Set<String> FIELDS = Set.of("label", "summary", "explanation", "rule", "unit", "detail", "note",
            "lifecycle", "strength", "observedResult", "freshness", "inputs", "title", "description", "message",
            "ownerRole", "actorRole", "chamber", "deliveryStatus", "notes", "warnings", "reasons", "disclaimer",
            "thresholds", "evidence", "decisiveDrivers", "error", "benchmarkName");
    static final List<String> SUFFIXES = List.of("Label", "Summary", "Disclaimer", "Notice", "Explanation", "Note",
            "Detail", "Methodology", "Reason", "Rule");
    /** Labels that scripts compare against English values. */
    static final Set<String> EXCLUDED = Set.of("structureLabel");

    /** Runs after Spring Boot's own JSON customizer (order 0), so this converter replaces it. */
    @Bean
    @Order(1)
    ServerHttpMessageConvertersCustomizer displayTextJsonConverter(JsonMapper jsonMapper) {
        JsonMapper responses = jsonMapper.rebuild().addModule(module()).build();
        return builder -> builder.withJsonConverter(new JacksonJsonHttpMessageConverter(responses));
    }

    static SimpleModule module() {
        SimpleModule module = new SimpleModule("display-text");
        module.setSerializerModifier(new ValueSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription.Supplier bean,
                                                             List<BeanPropertyWriter> properties) {
                for (BeanPropertyWriter property : properties) {
                    if (!isDisplayText(property.getName())) continue;
                    JavaType type = property.getType();
                    if (type.hasRawClass(String.class)) {
                        property.assignSerializer(TRANSLATED_TEXT);
                    } else if (type.isCollectionLikeType() && type.getContentType().hasRawClass(String.class)) {
                        property.assignSerializer(TRANSLATED_TEXTS);
                    }
                }
                return properties;
            }
        });
        return module;
    }

    static boolean isDisplayText(String name) {
        if (EXCLUDED.contains(name)) return false;
        return FIELDS.contains(name) || SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static final ValueSerializer<Object> TRANSLATED_TEXT = new ValueSerializer<>() {
        @Override
        public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(ServerMessages.translate((String) value));
        }
    };

    private static final ValueSerializer<Object> TRANSLATED_TEXTS = new ValueSerializer<>() {
        @Override
        public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
            generator.writeStartArray();
            for (Object item : (Collection<?>) value) {
                if (item == null) generator.writeNull();
                else generator.writeString(ServerMessages.translate(item.toString()));
            }
            generator.writeEndArray();
        }
    };
}
