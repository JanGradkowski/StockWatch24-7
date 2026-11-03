package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every archived signal has an outcome: the stored plan's result, 0% when the plan opened no position,
 * or the horizon measurement when there is no usable plan. Only a signal waiting for its next candle is
 * pending. The database ordering by trade outcome uses the same values the archive renders.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false",
        "watchlists.imports.worker-enabled=false"})
@AutoConfigureMockMvc
@Transactional
class SignalOutcomeCoverageIntegrationTest {
    private static final long DAY = 86_400L;
    private static final long START = 1_704_067_200L; // 1 January 2024

    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired AlertRuleRepository rules;
    @Autowired AlertEventRepository events;
    @Autowired CandleRepository candles;
    @Autowired AlertRuleService archives;
    @Autowired MockMvc mvc;

    private User user;
    private StockAsset asset;
    private final Map<AlertPatternFamily, AlertRule> rulesByFamily = new HashMap<>();
    private int saved;

    @Test
    void everySignalHasAnOutcomeAndTheDatabaseOrdersByTheSameValues() throws Exception {
        user = new User();
        user.setEmail("outcomes-" + UUID.randomUUID() + "@example.com");
        user.setFirstName("Outcome");
        user.setLastName("Coverage");
        user.setPasswordHash("test-only");
        user.setVerified(true);
        users.saveAndFlush(user);
        asset = new StockAsset();
        asset.setTickerSymbol("OC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        asset.setCompanyName("Outcome coverage");
        asset.setExchange("NASDAQ");
        assets.saveAndFlush(asset);
        // Day i opens at 100 + i and closes half a point higher.
        for (int day = 0; day < 40; day++) {
            candles.save(new Candle(asset.getTickerSymbol(), "1d", at(day),
                    100 + day, 101.5 + day, 99 + day, 100.5 + day, 1_000L));
        }
        candles.flush();

        // Candlestick signals from before stored plans: the next open to the close 8 candles later.
        AlertEvent legacy = candlestick(5, TradeSignal.BUY, event -> { });
        // A projection whose stop and target could not be built: the same measurement, marked as one.
        AlertEvent projectionWithoutPlan = candlestick(30, TradeSignal.SELL, event -> {
            event.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
            event.setTradeActionable(false);
            event.setTradeQualification("Projection only: no valid positive stop/target.");
        });
        AlertEvent stillRunning = candlestick(35, TradeSignal.BUY, event -> { });
        AlertEvent notFilled = candlestick(12, TradeSignal.BUY, event -> {
            plan(event, "CANDLE_RR_V5", 112.5, 108.0, 121.5);
            event.setTradeFillStatus("NOT_FILLED");
            event.setTradeEntryTimestamp(at(13));
            event.setLifecycleStatus(SignalLifecycleStatus.REJECTED);
            event.setLifecycleResolutionReason("Not filled: the next candle opened at or beyond the target, so the move happened before any entry.");
        });
        AlertEvent candidate = candlestick(39, TradeSignal.BUY, event ->
                event.setLifecycleStatus(SignalLifecycleStatus.POTENTIAL));
        AlertEvent qualified = candlestick(8, TradeSignal.BUY, event -> {
            plan(event, "CANDLE_RR_V4", 100.0, 94.8, 110.4);
            event.setTradeActionable(true);
            event.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
        });
        AlertEvent openProjection = candlestick(19, TradeSignal.BUY, event -> {
            plan(event, "CANDLE_RR_V5", 120.0, 110.0, 140.0);
            event.setTradeFillStatus("FILLED");
            event.setTradeEntryTimestamp(at(20));
            event.setTradeActionable(false);
            event.setTradeQualification("Projection only: the primary target does not meet the minimum reward/risk.");
            event.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
        });
        // A harmonic formation without a positive stop and target: 12 daily candles.
        AlertEvent harmonicWithoutPlan = signal(AlertPatternFamily.HARMONIC_FORMATION, CandlePattern.HARMONIC_BAT,
                10, TradeSignal.BUY, event -> {
                    event.setTradeActionable(false);
                    event.setTradeQualification("Projection only: no valid positive harmonic stop and target at this entry.");
                });
        AlertEvent harmonicNotFilled = signal(AlertPatternFamily.HARMONIC_FORMATION, CandlePattern.HARMONIC_BAT,
                14, TradeSignal.BUY, event -> {
                    event.setTradePlanVersion("HARMONIC_TRADE_V3");
                    event.setTradeEntryPrice(114.5);
                    event.setStopLossPrice(110.0);
                    event.setProfitTargetPrice(116.0);
                    event.setTradeActionable(true);
                    event.setTradeQualification("Qualified against the primary target and interval risk limits.");
                    event.setTradeHorizonCandles(12);
                    event.setTradeFillStatus("NOT_FILLED");
                    event.setTradeEntryTimestamp(at(15));
                    event.setDetectionCandleTimestamp(at(14));
                    event.setHarmonicStopStatus("NOT_FILLED");
                    event.setHarmonicStopResolutionTimestamp(at(15));
                    event.setHarmonicStopResolutionPrice(115.0);
                    event.setHarmonicStopResolutionReason("Not filled: the next candle opened at or beyond the stop, so no entry was taken.");
                });
        // An Elliott count without a valid target has no plan rows: 20 daily candles.
        AlertEvent elliottWithoutPlan = signal(AlertPatternFamily.ELLIOTT_WAVE, CandlePattern.ELLIOTT_BULLISH_WAVE_II_END,
                2, TradeSignal.BUY, event -> {
                    event.setTradeActionable(false);
                    event.setElliottTradeActionable(false);
                    event.setElliottTradePlanStatus("PROJECTION_ONLY");
                    event.setTradeQualification("Projection only: no valid target within the standard Elliott structural boundaries.");
                });
        events.flush();

        Map<Long, AlertRuleService.SignalArchiveOutcome> outcomes = new HashMap<>();
        List<Double> descending = new ArrayList<>();
        for (var entry : archives.getSignalArchive(user, "trade-return", "desc", 0).signals()) {
            outcomes.put(entry.signal().id(), entry.outcome());
            descending.add(entry.outcome().returnPercent());
        }
        assertThat(outcomes).hasSize(saved);

        expect(outcomes.get(legacy.getId()), "Close after 8 candles", (113.5 - 106) / 106 * 100);
        expect(outcomes.get(projectionWithoutPlan.getId()), "Projection · Close after 8 candles", -(138.5 - 131) / 131 * 100);
        expect(outcomes.get(stillRunning.getId()), "Open · current return", (139.5 - 136) / 136 * 100);
        expect(outcomes.get(notFilled.getId()), "Not filled", 0.0);
        expect(outcomes.get(qualified.getId()), "Sold at target", 10.4);
        expect(outcomes.get(openProjection.getId()), "Projection · Open · current return", (139.5 - 120) / 120 * 100);
        expect(outcomes.get(harmonicWithoutPlan.getId()), "Projection · Close after 12 candles", (122.5 - 111) / 111 * 100);
        expect(outcomes.get(harmonicNotFilled.getId()), "Not filled", 0.0);
        expect(outcomes.get(elliottWithoutPlan.getId()), "Projection · Close after 20 candles", (122.5 - 103) / 103 * 100);
        assertThat(outcomes.get(notFilled.getId()).valueLabel()).isEqualTo("0.00%");
        // Only the candidate still waiting for its confirming candle has no value yet.
        AlertRuleService.SignalArchiveOutcome waiting = outcomes.get(candidate.getId());
        assertThat(waiting.available()).isFalse();
        assertThat(waiting.pending()).isTrue();
        assertThat(outcomes.values()).filteredOn(outcome -> !outcome.available())
                .allMatch(AlertRuleService.SignalArchiveOutcome::pending);
        assertThat(outcomes.values()).noneMatch(outcome -> "Projection only".equals(outcome.label()));

        // The database computed the same returns, so its order is the rendered values' order.
        assertOrdered(descending, false);
        List<Double> ascending = archives.getSignalArchive(user, "trade-return", "asc", 0).signals().stream()
                .map(entry -> entry.outcome().returnPercent()).toList();
        assertOrdered(ascending, true);

        // The page shows a value for every signal; only the waiting candidate reads "Pending".
        String html = mvc.perform(get("/signals").param("sort", "trade-return").with(user(user.getEmail()))
                        .sessionAttr(AccountSession.SECURITY_VERSION, user.getSecurityVersion()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Close after 8 candles", "Projection · Close after 20 candles", "0.00%",
                        "class=\"unavailable\">Pending</strong>")
                .doesNotContain(">Projection only<", ">N/A<");
    }

    private static void expect(AlertRuleService.SignalArchiveOutcome outcome, String label, double returnPercent) {
        assertThat(outcome.label()).isEqualTo(label);
        assertThat(outcome.available()).isTrue();
        assertThat(outcome.returnPercent()).isCloseTo(returnPercent, within(1e-9));
    }

    /** Values in order with every missing value last, as the archive sorts them. */
    private static void assertOrdered(List<Double> values, boolean ascending) {
        int firstMissing = values.indexOf(null);
        List<Double> present = firstMissing < 0 ? values : values.subList(0, firstMissing);
        if (firstMissing >= 0) assertThat(values.subList(firstMissing, values.size())).containsOnlyNulls();
        for (int index = 1; index < present.size(); index++) {
            double previous = present.get(index - 1);
            double current = present.get(index);
            if (ascending) assertThat(current).as("ascending at %d of %s", index, values).isGreaterThanOrEqualTo(previous - 1e-9);
            else assertThat(current).as("descending at %d of %s", index, values).isLessThanOrEqualTo(previous + 1e-9);
        }
    }

    private AlertEvent candlestick(int day, TradeSignal signal, Consumer<AlertEvent> setup) {
        return signal(AlertPatternFamily.CANDLESTICK, CandlePattern.BULLISH_ENGULFING, day, signal, setup);
    }

    private AlertEvent signal(AlertPatternFamily family, CandlePattern pattern, int day, TradeSignal signal,
                              Consumer<AlertEvent> setup) {
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rulesByFamily.computeIfAbsent(family, this::rule));
        event.setPattern(pattern);
        event.setTradeSignal(signal);
        event.setSignalCandleTimestamp(at(day));
        event.setClosePrice(100.5 + day);
        event.setConfidenceScore(70);
        event.setSentAt(LocalDateTime.of(2024, 3, 1, 9, 0).plusMinutes(saved));
        setup.accept(event);
        saved++;
        return events.save(event);
    }

    private AlertRule rule(AlertPatternFamily family) {
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(family);
        return rules.saveAndFlush(rule);
    }

    private static void plan(AlertEvent event, String version, double entry, double stop, double target) {
        event.setTradePlanVersion(version);
        event.setTradeEntryPrice(entry);
        event.setStopLossPrice(stop);
        event.setStructuralStopPrice(stop);
        event.setProfitTargetPrice(target);
        event.setRewardRiskRatio(2.0);
    }

    private static long at(int day) {
        return START + day * DAY;
    }
}
