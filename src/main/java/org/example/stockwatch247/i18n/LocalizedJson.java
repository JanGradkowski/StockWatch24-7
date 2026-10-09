package org.example.stockwatch247.i18n;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON for data that a page inlines into its script, with display text in the request's language,
 * exactly as the JSON responses of the API (see {@link DisplayTextJsonConfiguration}).
 */
@Component
public class LocalizedJson {
    private final JsonMapper mapper;

    public LocalizedJson(JsonMapper jsonMapper) {
        this.mapper = jsonMapper.rebuild().addModule(DisplayTextJsonConfiguration.module()).build();
    }

    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }
}
