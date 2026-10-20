package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chart alert triggers stay in All signals after they are read, filter by type, delete, and count truly. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class ChartAlertArchiveIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;

    private User account() {
        User user = new User();
        user.setEmail("chart-archive-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setFirstName("Chart");
        user.setLastName("Archive");
        user.setPasswordHash("test-only-password-hash");
        user.setVerified(true);
        return users.saveAndFlush(user);
    }

    private RequestPostProcessor as(User account) {
        return request -> {
            request.getSession().setAttribute(org.example.stockwatch247.security.AccountSession.SECURITY_VERSION, account.getSecurityVersion());
            return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(account.getEmail()).postProcessRequest(request);
        };
    }

    private long alert(User owner, String symbol, String label) {
        return jdbc.queryForObject("""
                insert into chart_alerts (user_id, symbol, interval, source_kind, source_id, label, condition, definition, frequency)
                values (?, ?, 'DAILY', 'DRAWING', 'line-1', ?, 'CROSSES_UP', '{"geometry":"HORIZONTAL","price":20}', 'EVERY_BAR')
                returning id
                """, Long.class, owner.getId(), symbol, label);
    }

    private long trigger(long alertId, long candle, boolean read) {
        return jdbc.queryForObject("""
                insert into chart_alert_events (alert_id, candle_timestamp, close_price, value, level, read_at)
                values (?, ?, 20.75, 20.75, 20.0, ?) returning id
                """, Long.class, alertId, candle, read ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
    }

    @Test
    void aReadChartAlertTriggerStaysInAllSignalsAndCanBeFilteredAndDeleted() throws Exception {
        User owner = account();
        long alert = alert(owner, "ZCHRT", "Breakout over twenty");
        long read = trigger(alert, 1_784_073_600L, true);

        mvc.perform(get("/signals").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Breakout over twenty")))
                .andExpect(content().string(containsString("/stock/ZCHRT?chartAlert=" + read)))
                .andExpect(content().string(containsString("CHART:" + read)));

        // Only pattern families: the chart alert trigger is hidden; only chart alerts: it is back.
        mvc.perform(get("/signals").param("type", "candlestick", "elliott").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Breakout over twenty"))));
        mvc.perform(get("/signals").param("type", "chart").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Breakout over twenty")));
        // A read trigger is not in Unread.
        mvc.perform(get("/signals").param("state", "unread").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Breakout over twenty"))));

        mvc.perform(post("/signals/mixed/delete").param("singleSignalKey", "CHART:" + read)
                        .param("type", "chart").with(csrf()).with(as(owner)))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select deleted_at is not null from chart_alert_events where id = ?", Boolean.class, read)).isTrue();
        mvc.perform(get("/signals").with(as(owner)))
                .andExpect(content().string(not(containsString("Breakout over twenty"))));
    }

    @Test
    void anotherAccountCannotDeleteATrigger() throws Exception {
        User owner = account();
        User stranger = account();
        long event = trigger(alert(owner, "ZOWNR", "Owner line"), 1_784_073_600L, false);

        mvc.perform(post("/signals/mixed/delete").param("singleSignalKey", "CHART:" + event).with(csrf()).with(as(stranger)))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select deleted_at is null from chart_alert_events where id = ?", Boolean.class, event)).isTrue();
    }

    @Test
    void theDashboardCountsEveryUnreadTriggerNotJustTheListedOnes() throws Exception {
        User owner = account();
        long alert = alert(owner, "ZCNT", "Count line");
        for (int day = 0; day < 11; day++) trigger(alert, 1_784_073_600L + day * 86_400L, false);

        mvc.perform(get("/home").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<span class=\"panel-count\">11</span>")))
                // The sidebar badge says the same.
                .andExpect(content().string(containsString("app-sidebar-badge")));
    }
}
