package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@Transactional
class ArchiveQueryRegressionIntegrationTest {
    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired AlertRuleRepository rules;
    @Autowired AlertEventRepository events;
    @Autowired SignalArchiveQuery query;
    @Autowired AlertRuleService archives;

    @Test void outcomePaginationSortsBeforeLimitingAndKeepsUnavailableOutcomesLast() {
        var user = new User(); user.setEmail("archive-" + UUID.randomUUID() + "@example.com");
        user.setFirstName("Archive"); user.setLastName("Review"); user.setPasswordHash("test-only"); user.setVerified(true); users.saveAndFlush(user);
        var asset = new StockAsset(); asset.setTickerSymbol("Q" + UUID.randomUUID().toString().substring(0, 8));
        asset.setCompanyName("Archive fixture"); asset.setExchange("NASDAQ"); assets.saveAndFlush(asset);
        var rule = new AlertRule(); rule.setUser(user); rule.setStockAsset(asset); rule.setInterval(TimeInterval.DAILY); rules.saveAndFlush(rule);
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 61; i++) {
            var event = new AlertEvent(); event.setAlertRule(rule); event.setPattern(CandlePattern.ANY); event.setTradeSignal(TradeSignal.BUY);
            event.setSignalCandleTimestamp(1_600_000_000L + i); event.setTradeEntryPrice(100.0); event.setClosePrice(100.0);
            event.setConfidenceScore(i);
            if (i % 2 == 0) event.setReadAt(java.time.LocalDateTime.now());
            if (i < 60) {
                event.setTradePlanVersion("CANDLE_RR_V1"); event.setStopLossPrice(90.0); event.setProfitTargetPrice(101.0 + i);
                event.setRewardRiskRatio(2.0); event.setConfirmationWindowCandles(8); event.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
            }
            events.saveAndFlush(event); ids.add(event.getId());
        }
        var first = query.page(user.getId(), null, false, 0, 50);
        var expected = new ArrayList<>(ids.subList(0, 60)); Collections.reverse(expected); expected.add(ids.getLast());
        assertThat(first.ids()).containsExactlyElementsOf(expected.subList(0, 50));
        assertThat(first.count()).isEqualTo(61);
        assertThat(query.page(user.getId(), asset.getId(), false, 1, 50).ids()).containsExactlyElementsOf(expected.subList(50, 61));
        var rendered = archives.getSignalArchive(user, "trade-return", "desc", 0);
        assertThat(rendered.signals().getFirst().outcome().returnPercent()).isEqualTo(60.0);
        assertThat(query.page(-1, null, false, 0, 50).ids()).isEmpty();
        var completed = new SignalArchiveFilter("completed", asset.getTickerSymbol().toLowerCase());
        var completedPage = query.page(user.getId(), null, "confidence", false, 1, 50, completed);
        assertThat(completedPage.count()).isEqualTo(60);
        assertThat(completedPage.ids()).containsExactlyElementsOf(expected.subList(50, 60));
        assertThat(query.page(user.getId(), null, "date", false, 0, 50,
                new SignalArchiveFilter("active", "")).ids()).containsExactly(ids.getLast());
        var unread = query.page(user.getId(), null, "confidence", true, 0, 50,
                new SignalArchiveFilter("unread", ""));
        assertThat(unread.count()).isEqualTo(30);
        assertThat(unread.ids()).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 61)
                .filter(i -> i % 2 == 1).mapToObj(ids::get).toList());
        assertThat(query.page(user.getId(), null, "trade-return", false, 0, 50, completed).count()).isEqualTo(60);
        assertThat(query.page(-1, null, "date", false, 0, 50, completed).ids()).isEmpty();
        assertThat(query.page(user.getId(), null, "date", false, 0, 50,
                new SignalArchiveFilter("all", "%")).ids()).isEmpty();
    }
}
