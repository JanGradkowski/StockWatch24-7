package org.example.stockwatch247.service.congress;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class CongressionalProviderRequestBudget {
    private final JdbcTemplate jdbcTemplate;

    public CongressionalProviderRequestBudget(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(String provider, int dailyBudget) {
        if (dailyBudget < 1) {
            throw new IllegalStateException("Congressional data provider request budget is disabled.");
        }
        LocalDate usageDate = LocalDate.now(ZoneOffset.UTC);
        var rows = jdbcTemplate.query("""
                        insert into congressional_provider_daily_usage
                            (provider, usage_date, request_count, updated_at)
                        values (?, ?, 1, current_timestamp)
                        on conflict (provider, usage_date) do update
                        set request_count = congressional_provider_daily_usage.request_count + 1,
                            updated_at = current_timestamp
                        where congressional_provider_daily_usage.request_count < ?
                        returning request_count
                        """,
                (resultSet, rowNumber) -> resultSet.getInt("request_count"),
                provider,
                usageDate,
                dailyBudget);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "The daily congressional data request budget has been reached. Cached data remains available.");
        }
    }
}
