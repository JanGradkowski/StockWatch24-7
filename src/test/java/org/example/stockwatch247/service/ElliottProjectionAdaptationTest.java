package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.ElliottProjectionScenarioRepository;
import org.example.stockwatch247.repository.ElliottProjectionSetRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ElliottProjectionAdaptationTest {
    private static final long BASE = Instant.parse("2020-01-02T00:00:00Z").getEpochSecond();

    @Test
    void sustainedAccelerationRevisesFuturePathWithoutChangingOriginalCountOrTarget() {
        Fixture f = new Fixture(false);
        String original = f.primary.getProjectedPath();
        f.evaluate(f.prices(103, 108, 118, 128, 136));

        assertThat(f.primary.getRevisions()).hasSize(2);
        assertThat(f.primary.getRevisions().getFirst().getProjectedPath()).isEqualTo(original);
        assertThat(f.primary.getRevisions().getLast().getDecisionTimestamp()).isEqualTo(time(5));
        assertThat(f.primary.getRevisions().getLast().getReason()).contains("accelerated");
        assertThat(f.primary.getMaximumCandles()).isLessThan(28);
        var path = ElliottProjectionService.parsePath(f.primary.getProjectedPath());
        assertThat(path.getFirst().timestamp()).isEqualTo(time(5));
        assertThat(path.getFirst().price()).isEqualTo(136);
        assertThat(path.getFirst().label()).isEqualTo("Provisional anchor");
        assertThat(path.getLast().price()).isEqualTo(150);
        assertThat(path).allMatch(p -> p.timestamp() >= time(5));
        assertThat(f.set.getSourceTimestamp()).isEqualTo(BASE);
        assertThat(f.set.getSourcePrice()).isEqualTo(100);
        assertThat(f.primary.getHardInvalidationPrice()).isEqualTo(90);
        assertThat(f.primary.getMinimumCandles()).isGreaterThan(5);
    }

    @Test
    void bearishAccelerationUsesTheSameCausalRules() {
        Fixture f = new Fixture(true);
        f.evaluate(f.prices(103, 108, 118, 128, 136));
        var path = ElliottProjectionService.parsePath(f.primary.getProjectedPath());
        assertThat(f.primary.getRevisions()).hasSize(2);
        assertThat(path.getFirst().price()).isEqualTo(264);
        assertThat(path.getLast().price()).isEqualTo(250);
        assertThat(f.primary.getHardInvalidationPrice()).isEqualTo(310);
    }

    @Test
    void ordinaryNoiseOrOneLargeCloseDoesNotRedraw() {
        Fixture f = new Fixture(false);
        String original = f.primary.getProjectedPath();
        f.evaluate(f.prices(103, 105, 136, 108, 109, 111));
        assertThat(f.primary.getProjectedPath()).isEqualTo(original);
        assertThat(f.primary.getRevisions()).hasSize(1);
    }

    @Test
    void batchAndIncrementalReplayRetainExactlyTheSameRevisionsAndRanking() {
        Fixture batch = new Fixture(false), incremental = new Fixture(false);
        List<Candle> prices = batch.prices(103, 108, 118, 128, 136, 140, 142, 144, 145, 146);
        batch.evaluate(prices);
        for (int i = 1; i <= prices.size(); i++) incremental.evaluate(prices.subList(0, i));
        assertThat(incremental.primary).usingRecursiveComparison().ignoringFields(
                "createdAt", "updatedAt", "projectionSet.createdAt", "projectionSet.updatedAt")
                .isEqualTo(batch.primary);
        int revisions = batch.primary.getRevisions().size();
        batch.evaluate(prices);
        assertThat(batch.primary.getRevisions()).hasSize(revisions);
        assertThat(batch.set.getEvaluatedCandleCount()).isEqualTo(10);
    }

    @Test
    void duplicateCandlesAndIncompletePeriodsCannotSupplyConfirmationVotes() {
        Fixture f = new Fixture(false);
        List<Candle> candles = f.prices(103, 108, 130);
        f.evaluate(List.of(candles.get(0), candles.get(1), candles.get(2), candles.get(2), candles.get(2),
                new Candle("TEST", "1d", Instant.parse("2021-01-01T00:00:00Z").getEpochSecond(),
                        140., 141., 139., 140., 1000L)));
        assertThat(f.set.getEvaluatedCandleCount()).isEqualTo(3);
        assertThat(f.primary.getRevisions()).hasSize(1);
    }

    @Test
    void reachingTargetDoesNotCompleteWaveOrInventAnotherPathToTheSameTarget() {
        Fixture f = new Fixture(false);
        f.evaluate(f.prices(110, 125, 149));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION);
        assertThat(f.primary.getTargetReachedTimestamp()).isEqualTo(time(3));
        assertThat(f.set.getStatus()).isEqualTo(ElliottProjectionSetStatus.ACTIVE);
        assertThat(f.primary.getResolutionTimestamp()).isNull();
        assertThat(f.primary.getRevisions()).hasSize(1);
        f.evaluate(f.prices(110, 125, 149, 145, 140));
        assertThat(f.primary.getTargetReachedTimestamp()).isEqualTo(time(3));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void endpointOvershootNeedsThreeClosesButHardBoundaryNeedsOnlyOneCompletedCandle() {
        Fixture f = new Fixture(false);
        f.evaluate(f.prices(170));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION);
        f.evaluate(f.prices(170, 171, 172));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.INVALIDATED);
        assertThat(f.primary.getResolutionTimestamp()).isEqualTo(time(3));
        assertThat(f.primary.getTargetReachedTimestamp()).isEqualTo(time(1));

        Fixture hard = new Fixture(false);
        hard.evaluate(List.of(new Candle("TEST", "1d", time(1), 101., 102., 89., 101., 1000L)));
        assertThat(hard.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.INVALIDATED);
        assertThat(hard.primary.getRevisions()).hasSize(1);
    }

    @Test
    void extendedScenarioSurvivesStandardOvershootAndBecomesPreferred() {
        Fixture f = new Fixture(false);
        ElliottProjectionScenario extension = f.addAlternative("EXTENDED_WAVE_III", 180, 2, 52);
        f.evaluate(f.prices(135, 150, 170, 171, 172));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.INVALIDATED);
        assertThat(extension.getDisplayRank()).isEqualTo(1);
        assertThat(extension.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.ACTIVE);
        assertThat(extension.getTargetMidpoint()).isEqualTo(180);
        assertThat(f.set.getStatus()).isEqualTo(ElliottProjectionSetStatus.ACTIVE);
    }

    @Test
    void alternativeRequiresSustainedMaterialAdvantageBeforeReplacingAValidPreferredPath() {
        Fixture f = new Fixture(false);
        ElliottProjectionScenario alternate = f.addAlternative("ALTERNATE", 150, 2, 72);
        f.evaluate(f.prices(103, 105));
        assertThat(f.primary.getDisplayRank()).isEqualTo(1);
        f.evaluate(f.prices(103, 105, 106));
        assertThat(alternate.getDisplayRank()).isEqualTo(1);
    }

    @Test
    void durationCanStretchButCannotResetTheOriginalLifetimeForever() {
        Fixture f = new Fixture(false);
        f.primary.setMaximumCandles(8);
        f.primary.setMinimumCandles(2);
        f.primary.setOriginalMaximumCandles(8);
        // A plausible but slow advance is still short of the target after the initial window.
        f.evaluate(f.prices(102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122));
        assertThat(f.primary.getMaximumCandles()).isBetween(9, 16);
        assertThat(f.primary.getRevisions()).hasSizeGreaterThan(1);
        f.evaluate(f.prices(102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 123, 124, 125, 126, 127));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.UNRESOLVED);
        assertThat(f.primary.getMaximumCandles()).isLessThanOrEqualTo(16);
        assertThat(f.set.getEvaluatedCandleCount()).isEqualTo(16);
    }

    @Test
    void decisionsCannotPredateStageConfirmationAndSerializationIgnoresMachineLocale() {
        Fixture f = new Fixture(false);
        f.set.setAvailableFromTimestamp(time(8));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            f.evaluate(f.prices(103, 108, 118, 128, 136, 139, 140, 141, 142, 143));
            assertThat(f.primary.getRevisions()).hasSize(2);
            assertThat(f.primary.getRevisions()).allMatch(r -> r.getDecisionTimestamp() >= time(8));
            assertThat(ElliottProjectionService.parsePath(f.primary.getProjectedPath())).isNotEmpty();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void correctiveEvidenceUsesConfirmedSwingsAndSelectsOnlyAnExistingEligibleAlternative() {
        Fixture f = new Fixture(false);
        f.set.setStage(ElliottSignalStage.WAVE_V_END);
        f.primary.setScenarioKey("ZIGZAG_CORRECTION");
        ElliottProjectionScenario flat = f.addAlternative("EXPANDED_FLAT_CORRECTION", 150, 2, 51);
        flat.setHardInvalidationPrice(null);
        f.primary.setHardInvalidationPrice(null);
        List<Candle> prices = f.prices(104, 110, 120, 115, 106, 98, 94, 98, 102, 106, 110);
        // The rebound beyond the source is not known to be a pivot until two subsequent candles close.
        f.evaluate(prices.subList(0, 8));
        assertThat(f.primary.getDisplayRank()).isEqualTo(1);
        f.evaluate(prices);
        assertThat(flat.getDisplayRank()).isEqualTo(1);
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.UNRESOLVED);
        assertThat(f.scenarios).hasSize(2);
    }

    @Test
    void terminalPathsAndEveryRevisionRemainReadableInTheHistoryTemplate() {
        Fixture f = new Fixture(false);
        f.evaluate(f.prices(103, 108, 118, 128, 136, 89));
        User user = new User();
        when(f.sets.findOwnedHistory(7L, user)).thenReturn(List.of(f.set));
        assertThat(f.service.latestVisible(7L, user)).isEmpty();
        var history = f.service.history(7L, user);
        assertThat(history.getFirst().scenarios()).hasSize(1);
        var scenario = history.getFirst().scenarios().getFirst();
        assertThat(scenario.drawable()).isFalse();
        assertThat(scenario.label()).isEqualTo("STANDARD_WAVE_III");
        assertThat(scenario.revisions()).hasSize(2);

        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var context = new org.thymeleaf.context.Context();
        context.setVariable("signal", new HistoryModel(history));
        String rendered = engine.process(new org.thymeleaf.TemplateSpec("signal-detail",
                java.util.Set.of("elliottProjectionHistory"), org.thymeleaf.templatemode.TemplateMode.HTML, null), context);
        assertThat(rendered).contains("Original path", "Path revision 1", "2020-01-07", "Provisional anchor", "INVALIDATED");
        assertThat(rendered).doesNotContain("th:text", "? known on");
    }

    public record HistoryModel(List<ElliottProjectionService.ProjectionSetView> elliottProjectionHistory) { }

    public record ProjectionModel(ElliottProjectionService.ProjectionSetView elliottProjection, boolean alertRuleActive) { }

    @Test
    void currentCardsExplainRevisionsAndChartDataHidesTargetsAwaitingConfirmation() {
        Fixture f = new Fixture(false);
        User user = new User();
        when(f.sets.findOwnedHistory(7L, user)).thenReturn(List.of(f.set));
        f.evaluate(f.prices(103, 108, 118, 128, 136));
        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var context = new org.thymeleaf.context.Context();
        context.setVariable("signal", new ProjectionModel(f.service.latestVisible(7L, user).orElseThrow(), true));
        var spec = new org.thymeleaf.TemplateSpec("signal-detail",
                java.util.Set.of("elliottProjectionScenarios", "elliottProjectionData"),
                org.thymeleaf.templatemode.TemplateMode.HTML, null);
        String rendered = engine.process(spec, context);
        assertThat(rendered).contains("Revised 2020-01-07", "data-projection-point", "provisional anchor");
        f.evaluate(f.prices(103, 108, 118, 128, 136, 149));
        context.setVariable("signal", new ProjectionModel(f.service.latestVisible(7L, user).orElseThrow(), true));
        rendered = engine.process(spec, context);
        assertThat(rendered).contains("Monitoring", "AWAITING CONFIRMATION");
        assertThat(rendered).doesNotContain("data-projection-point");
    }

    @Test
    void existingProjectionRecoversEarlierTargetContactWithoutBackdatingARevision() {
        Fixture f = new Fixture(false);
        f.set.setLastEvaluatedTimestamp(time(5));
        f.set.setEvaluatedCandleCount(5);
        f.evaluate(f.prices(110, 125, 149, 140, 135, 130));
        assertThat(f.primary.getTargetReachedTimestamp()).isEqualTo(time(3));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION);
        assertThat(f.primary.getRevisions()).hasSize(1);
        assertThat(f.primary.isTargetHistoryChecked()).isTrue();
    }

    @Test
    void aConfirmedCorrectiveReboundCanSupportAReturnToAnEarlierTouchedTarget() {
        Fixture f = new Fixture(false);
        f.set.setStage(ElliottSignalStage.WAVE_V_END);
        f.primary.setScenarioKey("EXPANDED_FLAT_CORRECTION");
        f.primary.setHardInvalidationPrice(null);
        List<Candle> prices = f.prices(110, 130, 149, 130, 110, 100, 94, 100, 110, 116, 120);
        f.evaluate(prices.subList(0, 3));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION);
        f.evaluate(prices);
        assertThat(f.primary.getTargetReachedTimestamp()).isEqualTo(time(3));
        assertThat(f.primary.getStatus()).isEqualTo(ElliottProjectionScenarioStatus.ACTIVE);
        assertThat(f.primary.getRevisions()).hasSize(2);
        assertThat(f.primary.getRevisions().getLast().getReason()).contains("Confirmed swings");
        assertThat(ElliottProjectionService.parsePath(f.primary.getProjectedPath()).getFirst().price()).isEqualTo(120);
    }

    @Test
    void revisionCooldownAndRollingHistoryDoNotChangeRecordedDecisions() {
        Fixture batch = new Fixture(false), rolling = new Fixture(false);
        List<Candle> prices = batch.prices(103, 108, 118, 128, 136, 138, 137, 136, 135, 134, 133, 132);
        List<Candle> full = new ArrayList<>();
        for (int i = -300; i <= 0; i++) {
            full.add(new Candle("TEST", "1d", time(i), 100., 101., 99., 100., 1000L));
        }
        full.addAll(prices);
        batch.service.evaluateOpenProjections("TEST", TimeInterval.DAILY, full);
        for (int i = 302; i <= full.size(); i++) {
            rolling.service.evaluateOpenProjections("TEST", TimeInterval.DAILY, full.subList(i - 260, i));
        }
        assertThat(rolling.primary.getProjectedPath()).isEqualTo(batch.primary.getProjectedPath());
        assertThat(rolling.primary.getRevisions()).usingRecursiveComparison().isEqualTo(batch.primary.getRevisions());
        var revisions = batch.primary.getRevisions();
        for (int i = 2; i < revisions.size(); i++) {
            assertThat(revisions.get(i).getDecisionTimestamp() - revisions.get(i - 1).getDecisionTimestamp())
                    .isGreaterThanOrEqualTo(5 * 86400L);
        }
    }

    private static long time(int offset) { return BASE + offset * 86_400L; }

    private static class Fixture {
        final boolean bearish;
        final ElliottProjectionSet set = new ElliottProjectionSet();
        final List<ElliottProjectionScenario> scenarios = new ArrayList<>();
        final ElliottProjectionSetRepository sets = mock(ElliottProjectionSetRepository.class);
        final ElliottProjectionScenarioRepository rows = mock(ElliottProjectionScenarioRepository.class);
        final ElliottProjectionService service = new ElliottProjectionService(sets, rows,
                new CandleCompletionService("UTC", Clock.fixed(Instant.parse("2021-01-01T12:00:00Z"), ZoneOffset.UTC)));
        final ElliottProjectionScenario primary;

        Fixture(boolean bearish) {
            this.bearish = bearish;
            set.setStage(ElliottSignalStage.WAVE_II_END);
            set.setSourceTimestamp(BASE);
            set.setAvailableFromTimestamp(BASE);
            set.setSourcePrice(price(100));
            set.setStatus(ElliottProjectionSetStatus.ACTIVE);
            set.setLastEvaluatedTimestamp(BASE);
            primary = addAlternative("STANDARD_WAVE_III", 150, 1, 62);
            when(sets.findForEvaluation("TEST", TimeInterval.DAILY, ElliottProjectionSetStatus.ACTIVE)).thenReturn(List.of(set));
            when(rows.findByProjectionSetOrderByDisplayRankAscIdAsc(set)).thenReturn(scenarios);
        }

        ElliottProjectionScenario addAlternative(String key, double target, int rank, int confidence) {
            ElliottProjectionScenario s = new ElliottProjectionScenario();
            s.setProjectionSet(set);
            s.setScenarioKey(key);
            s.setLabel("Preferred · " + key);
            s.setExpectedMove(bearish ? TradeSignal.SELL : TradeSignal.BUY);
            s.setStatus(ElliottProjectionScenarioStatus.ACTIVE);
            s.setDisplayRank(rank);
            s.setInitialConfidence(confidence);
            s.setCurrentConfidence(confidence);
            s.setTargetMidpoint(price(target));
            s.setTargetZoneLow(price(target) - 2);
            s.setTargetZoneHigh(price(target) + 2);
            s.setHardInvalidationPrice(price(90));
            s.setHardInvalidationSide(bearish ? "ABOVE" : "BELOW");
            s.setScenarioInvalidationPrice(price(target + 10));
            s.setScenarioInvalidationSide(bearish ? "BELOW" : "ABOVE");
            s.setMinimumCandles(12);
            s.setMaximumCandles(28);
            s.setOriginalMaximumCandles(28);
            double[] progress = {0, .24, .15, .64, .50, 1};
            int[] offsets = {0, 6, 11, 17, 22, 28};
            List<String> points = new ArrayList<>();
            for (int i = 0; i < offsets.length; i++) {
                points.add(String.format(Locale.ROOT, "%d|%d|%.10f|%s", offsets[i], time(offsets[i]),
                        price(100 + (target - 100) * progress[i]), i == 0 ? "" : String.valueOf(i)));
            }
            s.setProjectedPath(String.join("\n", points));
            scenarios.add(s);
            return s;
        }

        double price(double value) { return bearish ? 400 - value : value; }

        List<Candle> prices(double... closes) {
            List<Candle> result = new ArrayList<>();
            for (int i = 0; i < closes.length; i++) {
                double close = price(closes[i]);
                result.add(new Candle("TEST", "1d", time(i + 1), close, close + 1, close - 1, close, 1000L));
            }
            return result;
        }

        void evaluate(List<Candle> candles) {
            List<Candle> context = new ArrayList<>();
            for (int i = -15; i <= 0; i++) {
                double close = price(100);
                context.add(new Candle("TEST", "1d", time(i), close, close + 1, close - 1, close, 1000L));
            }
            context.addAll(candles);
            service.evaluateOpenProjections("TEST", TimeInterval.DAILY, context);
        }
    }
}
