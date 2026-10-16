package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityArchiveQueryTest {

    @Test
    void ordersByTheChosenColumnThenNewestDetectionSourceAndId() {
        assertThat(ActivityArchiveQuery.order("date", false))
                .isEqualTo("transaction_date desc nulls first, detected_at desc nulls last, kind asc, id desc");
        assertThat(ActivityArchiveQuery.order("company", true))
                .isEqualTo("lower(company) collate \"C\" asc nulls last, lower(ticker) collate \"C\" asc nulls last, "
                        + "detected_at desc nulls last, kind asc, id desc");
    }

    @Test
    void unknownSortsFallBackToTheTransactionDate() {
        assertThat(ActivityArchiveQuery.order("id; drop table users", true)).startsWith("transaction_date asc");
        assertThat(ActivityArchiveQuery.order(null, true)).startsWith("transaction_date asc");
    }
}
