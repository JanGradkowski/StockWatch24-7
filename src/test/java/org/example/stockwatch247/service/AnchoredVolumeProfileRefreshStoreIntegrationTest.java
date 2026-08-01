package org.example.stockwatch247.service;

import org.example.stockwatch247.service.AnchoredVolumeProfileRefreshStore.CandleSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@Transactional
class AnchoredVolumeProfileRefreshStoreIntegrationTest {
    @Autowired
    private AnchoredVolumeProfileRefreshStore refreshStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void atomicallyStopsRecordingAfterTwoLiveRefreshesForTheActiveCandle() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Long userId = jdbcTemplate.queryForObject(
                """
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Volume', 'Profile', true)
                returning id
                """,
                Long.class,
                "volume-profile-" + suffix + "@example.com");
        CandleSnapshot firstSnapshot =
                new CandleSnapshot(
                        "15min", 1_800_000_000L, 100, 105, 98, 103, 1_000);
        CandleSnapshot secondSnapshot =
                new CandleSnapshot(
                        "15min", 1_800_000_000L, 100, 107, 97, 106, 1_500);

        var first = refreshStore.recordProviderRefresh(
                userId,
                "AAPL",
                "1d",
                1_800_000_000L,
                2,
                "TWELVE_DATA",
                firstSnapshot);
        var second = refreshStore.recordProviderRefresh(
                userId,
                "AAPL",
                "1d",
                1_800_000_000L,
                2,
                "YAHOO_FINANCE",
                secondSnapshot);
        var third = refreshStore.recordProviderRefresh(
                userId,
                "AAPL",
                "1d",
                1_800_000_000L,
                2,
                "TWELVE_DATA",
                new CandleSnapshot(
                        "15min", 1_800_000_000L, 100, 110, 95, 109, 2_000));

        assertThat(first).hasValueSatisfying(state ->
                assertThat(state.refreshCount()).isEqualTo(1));
        assertThat(second).hasValueSatisfying(state -> {
            assertThat(state.refreshCount()).isEqualTo(2);
            assertThat(state.snapshot()).isEqualTo(secondSnapshot);
        });
        assertThat(third).isEmpty();
        assertThat(refreshStore.find(userId, "AAPL", "1d", 1_800_000_000L))
                .hasValueSatisfying(state -> {
                    assertThat(state.refreshCount()).isEqualTo(2);
                    assertThat(state.snapshot()).isEqualTo(secondSnapshot);
                });
    }
}
