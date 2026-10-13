package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@Transactional
class ChartStateServiceIntegrationTest {
    @Autowired
    private ChartStateService state;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private long user() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Chart', 'State', true) returning id
                """, Long.class, "chart-state-" + suffix + "@example.com");
    }

    @Test
    void drawingsArePerAccountAndSymbolAndKeepTheirVersions() {
        long owner = user();
        long other = user();
        String payload = "{\"id\":\"d1\",\"type\":\"trendLine\",\"points\":[{\"time\":1,\"price\":2}]}";
        var saved = state.saveDrawing(owner, "aapl", "d1", payload, null);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(state.drawings(owner, "AAPL")).singleElement().satisfies(drawing -> {
            assertThat(drawing.id()).isEqualTo("d1");
            assertThat(drawing.payload().path("type").asString()).isEqualTo("trendLine");
        });
        assertThat(state.drawings(owner, "MSFT")).isEmpty();
        assertThat(state.drawings(other, "AAPL")).isEmpty();

        var updated = state.saveDrawing(owner, "AAPL", "d1", payload.replace("2}", "3}"), 1);
        assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(() -> state.saveDrawing(owner, "AAPL", "d1", payload, 1))
                .isInstanceOf(ConflictException.class).hasMessageContaining("another device");

        state.deleteDrawing(owner, "AAPL", "d1");
        assertThat(state.drawings(owner, "AAPL")).isEmpty();
    }

    @Test
    void invalidDrawingsAreRejected() {
        long owner = user();
        assertThatThrownBy(() -> state.saveDrawing(owner, "AAPL", "d1", "[1,2]", null)).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> state.saveDrawing(owner, "AAPL", "d1", "{\"id\":\"d1\"}", null)).hasMessageContaining("type");
        assertThatThrownBy(() -> state.saveDrawing(owner, "AAPL", "d2", "{\"id\":\"d1\",\"type\":\"x\"}", null)).hasMessageContaining("id");
        assertThatThrownBy(() -> state.saveDrawing(owner, "AAPL", "../x", "{\"id\":\"../x\",\"type\":\"x\"}", null)).hasMessageContaining("Invalid");
        assertThatThrownBy(() -> state.saveDrawing(owner, "AAPL", "d1", "{\"id\":\"d1\",\"type\":\"x\",\"text\":\""
                + "a".repeat(ChartStateService.MAX_DRAWING_BYTES) + "\"}", null)).hasMessageContaining("too large");
        assertThatThrownBy(() -> state.saveDrawing(owner, "<bad>", "d1", "{\"id\":\"d1\",\"type\":\"x\"}", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importAddsOnlyDrawingsThatAreNotStoredYet() {
        long owner = user();
        state.saveDrawing(owner, "AAPL", "kept", "{\"id\":\"kept\",\"type\":\"fibRetracement\",\"version\":2}", null);
        int imported = state.importDrawings(owner, "AAPL", List.of(
                json.readTree("{\"id\":\"kept\",\"type\":\"fibRetracement\",\"version\":1}"),
                json.readTree("{\"id\":\"new\",\"type\":\"longPosition\"}")));
        assertThat(imported).isEqualTo(1);
        assertThat(state.drawings(owner, "AAPL")).extracting(ChartStateService.StoredDrawing::id).containsExactly("kept", "new");
        assertThat(state.drawings(owner, "AAPL").getFirst().payload().path("version").asInt()).isEqualTo(2);
    }

    @Test
    void layoutsAndTemplatesRoundTripAndAreRemovedWithTheAccount() {
        long owner = user();
        assertThat(state.layout(owner, "stock")).isNull();
        state.saveLayout(owner, "stock", "{\"indicators\":[]}");
        var layout = state.saveLayout(owner, "stock", "{\"indicators\":[{\"type\":\"EMA\"}]}");
        assertThat(layout.version()).isEqualTo(2);
        assertThat(state.layout(owner, "stock").payload().path("indicators").size()).isEqualTo(1);
        assertThatThrownBy(() -> state.saveLayout(owner, "Bad Scope", "{}")).hasMessageContaining("scope");

        var template = state.saveTemplate(owner, "drawing", "trendLine", " Thick red ", "{\"color\":\"#ff0000\",\"width\":3}");
        assertThat(template.name()).isEqualTo("Thick red");
        state.saveTemplate(owner, "DRAWING", "trendLine", "Thick red", "{\"color\":\"#ff0000\",\"width\":4}");
        assertThat(state.templates(owner, "DRAWING", "trendLine")).singleElement()
                .satisfies(saved -> assertThat(saved.payload().path("width").asInt()).isEqualTo(4));
        assertThatThrownBy(() -> state.saveTemplate(owner, "SCRIPT", "x", "y", "{}")).hasMessageContaining("kind");
        state.deleteTemplate(owner, template.id());
        assertThat(state.templates(owner, "DRAWING", null)).isEmpty();

        state.saveDrawing(owner, "AAPL", "d1", "{\"id\":\"d1\",\"type\":\"trendLine\"}", null);
        jdbc.update("delete from users where id=?", owner);
        assertThat(jdbc.queryForObject("select count(*) from user_chart_drawings where user_id=?", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_chart_layouts where user_id=?", Integer.class, owner)).isZero();
    }
}
