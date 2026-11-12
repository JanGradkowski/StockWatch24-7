package org.example.stockwatch247.controller;

import org.example.stockwatch247.service.ActivityArchiveQuery;
import org.example.stockwatch247.service.SignalArchiveFilter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The signal archives' links keep the list's filters, start from the first page and leave the defaults out. */
class ArchiveLinksTest {
    @Test void anArchiveLinkKeepsTheFiltersAndLeavesTheDefaultsOut() {
        assertThat(AuthController.archiveUrl("/signals", new SignalArchiveFilter("all", ""), "date", "desc", 0)).isEqualTo("/signals");
        assertThat(AuthController.archiveUrl("/signals", new SignalArchiveFilter("unread", "BRK.B"), "ticker", "asc", 0))
                .isEqualTo("/signals?sort=ticker&direction=asc&state=unread&ticker=BRK.B");
        assertThat(AuthController.archiveUrl("/signals", new SignalArchiveFilter("all", "", 7L, Set.of("harmonic"), null), "date", "desc", 2))
                .isEqualTo("/signals?watchlistId=7&type=harmonic&page=2");
        assertThat(AuthController.archiveUrl("/alerts/42", new SignalArchiveFilter("active", ""), "date", "asc", 0))
                .isEqualTo("/alerts/42?sort=date&direction=asc&state=active");
    }

    @Test void aColumnTitleSortsInItsNaturalDirectionOrReversesTheCurrentSort() {
        var natural = Map.of("ticker", "asc", "date", "desc", "confidence", "desc");
        var links = AuthController.sortLinks(natural, "ticker", "asc", (key, direction) -> key + ":" + direction);
        assertThat(links).containsEntry("ticker", "ticker:desc").containsEntry("date", "date:desc").containsEntry("confidence", "confidence:desc");
        assertThat(AuthController.sortLinks(natural, "date", "desc", (key, direction) -> key + ":" + direction))
                .containsEntry("date", "date:asc").containsEntry("ticker", "ticker:asc");
    }

    @Test void aTickerAlertsLinkKeepsItsTabAndTicker() {
        assertThat(AuthController.activityUrl(ActivityArchiveQuery.Filter.all(), "date", "desc", 0)).isEqualTo("/activity-signals");
        assertThat(AuthController.activityUrl(new ActivityArchiveQuery.Filter("purchase", "aapl"), "actor", "asc", 1))
                .isEqualTo("/activity-signals?sort=actor&direction=asc&state=purchase&ticker=AAPL&page=1");
        assertThat(new ActivityArchiveQuery.Filter("nonsense", null).state()).isEqualTo("all");
    }
}
