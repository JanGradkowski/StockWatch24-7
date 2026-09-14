package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserSignalScoringPreferences;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserSignalScoringPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignalScoringPreferencesServiceTest {
    private final User user = new User();
    private final AtomicReference<UserSignalScoringPreferences> stored = new AtomicReference<>();
    private SignalScoringPreferencesService service;

    @BeforeEach
    void setUp() {
        user.setId(42L);
        UserSignalScoringPreferencesRepository repository = mock(UserSignalScoringPreferencesRepository.class);
        when(repository.findByUser(user)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(UserSignalScoringPreferences.class))).thenAnswer(invocation -> {
            UserSignalScoringPreferences value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        service = new SignalScoringPreferencesService(repository, tools.jackson.databind.json.JsonMapper.builder().build());
    }

    @Test
    void factoryProfilesAreSharedAcrossIntervalsAndEachTotalOneHundred() {
        SignalScoringPreferencesService.PreferencesView preferences = service.get(user);

        assertThat(preferences.custom()).isFalse();
        assertThat(preferences.profiles()).hasSize(3)
                .allSatisfy(profile -> assertThat(profile.totalPoints()).isEqualTo(100));
        assertThat(preferences.profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY).components())
                .extracting(SignalScoringPreferencesService.ScoringComponent::points)
                .containsExactly(25, 20, 5, 15, 10, 15, 10);
        assertThat(preferences.profile(AlertPatternFamily.ELLIOTT_WAVE, TimeInterval.MONTHLY).components())
                .extracting(SignalScoringPreferencesService.ScoringComponent::points)
                .containsExactly(30, 20, 15, 15, 10, 5, 5);
        assertThat(preferences.profile(AlertPatternFamily.HARMONIC_FORMATION, TimeInterval.DAILY).components())
                .extracting(SignalScoringPreferencesService.ScoringComponent::points)
                .containsExactly(35, 45, 20);
        assertThat(preferences.profiles()).allSatisfy(profile -> assertThat(profile.confluenceRules())
                .hasSize(2)
                .allSatisfy(rule -> {
                    assertThat(rule.included()).isTrue();
                    assertThat(rule.supportingPoints()).isEqualTo(10);
                    assertThat(rule.opposingPoints()).isEqualTo(10);
                }));
    }

    @Test
    void saveRejectsAnyIncludedProfileThatDoesNotTotalExactlyOneHundred() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("candlestick.patternQuality.points", "24");

        assertThatThrownBy(() -> service.save(user, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 100")
                .hasMessageContaining("currently 99");
        assertThat(stored.get()).isNull();
    }

    @Test
    void customProfileCanExcludeCategoriesAndRescoresSavedEvidenceProportionally() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        SignalScoringPreferencesService.Profile daily = service.get(user)
                .profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY);
        daily.components().forEach(component -> {
            String prefix = daily.key() + "." + component.key();
            form.remove(prefix + ".included");
            form.set(prefix + ".points", component.key().equals("patternQuality") ? "100" : "0");
        });
        form.set("candlestick.patternQuality.included", "on");
        SignalScoringPreferencesService.Profile profile = service.save(user, form)
                .profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY);

        var display = service.score(profile, 61, List.of(
                evidence("Pattern quality", "20/25"),
                evidence("Trend indicators", "18/20")
        ));

        assertThat(display.customApplied()).isTrue();
        assertThat(display.score()).isEqualTo(80);
        assertThat(display.sections()).singleElement()
                .satisfies(section -> assertThat(section.scoreLabel()).isEqualTo("80/100"));
    }

    @Test
    void legacyEvidenceWithoutCategoryTotalsKeepsTheRecordedScore() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("elliott.structure.points", "35");
        form.set("elliott.proportions.points", "15");
        SignalScoringPreferencesService.Profile profile = service.save(user, form)
                .profile(AlertPatternFamily.ELLIOTT_WAVE, TimeInterval.WEEKLY);

        var display = service.score(profile, 77, List.of(
                new SignalScoringPreferencesService.EvidenceSection(
                        "Elliott evidence", null, "Evidence", false, false, List.of())));

        assertThat(display.score()).isEqualTo(77);
        assertThat(display.customApplied()).isFalse();
        assertThat(display.profileNote()).contains("original score");
    }

    @Test
    void harmonicProfileReweightsOnlySoftGeometryCategories() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("harmonic.primaryB.points", "20");
        form.set("harmonic.completion.points", "60");
        form.set("harmonic.secondary.points", "20");
        SignalScoringPreferencesService.Profile profile = service.save(user, form)
                .profile(AlertPatternFamily.HARMONIC_FORMATION, TimeInterval.DAILY);

        var display = service.score(profile, 90, List.of(
                evidence("Primary B ratio", "35/35"),
                evidence("Completion ratio", "22.5/45"),
                evidence("Secondary ratios", "20/20"),
                new SignalScoringPreferencesService.EvidenceSection(
                        "Harmonic geometry", null, "Evidence", false, false, List.of())
        ));

        assertThat(display.customApplied()).isTrue();
        assertThat(display.score()).isEqualTo(70);
        assertThat(display.sections()).extracting(SignalScoringPreferencesService.EvidenceSection::category)
                .containsExactly("Primary B ratio", "Completion ratio", "Secondary ratios");
    }

    @Test
    void customComponentScoreReceivesTheStoredCrossPatternAdjustmentAfterReweighting() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        SignalScoringPreferencesService.Profile daily = service.get(user)
                .profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY);
        daily.components().forEach(component -> {
            String prefix = daily.key() + "." + component.key();
            form.remove(prefix + ".included");
            form.set(prefix + ".points", component.key().equals("patternQuality") ? "100" : "0");
        });
        form.set("candlestick.patternQuality.included", "on");
        SignalScoringPreferencesService.Profile profile = service.save(user, form)
                .profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY);

        var display = service.score(profile, 90, List.of(
                evidence("Pattern quality", "20/25"),
                new SignalScoringPreferencesService.EvidenceSection(
                        "Cross-pattern confluence", "+10", "Supporting confluence",
                        false, false, List.of())
        ));

        assertThat(display.score()).isEqualTo(90);
        assertThat(display.sections()).extracting(SignalScoringPreferencesService.EvidenceSection::category)
                .containsExactly("Pattern quality", "Cross-pattern confluence");
    }

    @Test
    void confluenceSourcesCanBeWeightedAndDisabledPerTargetFamilyAcrossAllIntervals() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("candlestick.confluence.elliott.supportingPoints", "18");
        form.set("candlestick.confluence.elliott.opposingPoints", "7");
        form.remove("candlestick.confluence.harmonic.included");
        SignalScoringPreferencesService.Profile profile = service.save(user, form)
                .profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY);

        assertThat(profile.confluenceRule(AlertPatternFamily.ELLIOTT_WAVE))
                .satisfies(rule -> {
                    assertThat(rule.included()).isTrue();
                    assertThat(rule.supportingPoints()).isEqualTo(18);
                    assertThat(rule.opposingPoints()).isEqualTo(7);
                });
        assertThat(profile.confluenceRule(AlertPatternFamily.HARMONIC_FORMATION).included()).isFalse();
        assertThat(service.get(user).profile(AlertPatternFamily.CANDLESTICK, TimeInterval.WEEKLY)
                .confluenceRule(AlertPatternFamily.ELLIOTT_WAVE).supportingPoints()).isEqualTo(18);
    }

    @Test
    void currentConfluenceSettingsRescoreStructuredSavedEvidence() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("candlestick.confluence.elliott.supportingPoints", "18");
        form.remove("candlestick.confluence.harmonic.included");
        SignalScoringPreferencesService.Profile profile = service.save(user, form)
                .profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY);

        var display = service.score(profile, 90, List.of(
                evidence("Pattern quality", "20/25"),
                evidence("Trend indicators", "20/20"),
                evidence("Higher-timeframe trend", "5/5"),
                evidence("Momentum", "15/15"),
                evidence("Bollinger volatility/location", "10/10"),
                evidence("Support/resistance", "15/15"),
                evidence("Volume participation", "10/10"),
                new SignalScoringPreferencesService.EvidenceSection(
                        "Cross-pattern confluence", "+20", "Supporting confluence", false, false,
                        List.of(
                                new SignalScoringPreferencesService.EvidenceDetail(
                                        "Elliott wave", "A bullish signal occurred 2 candles earlier.", "+10"),
                                new SignalScoringPreferencesService.EvidenceDetail(
                                        "Harmonic formation", "A bullish signal occurred 4 candles earlier.", "+10")))));

        assertThat(display.score()).isEqualTo(100);
        assertThat(display.sections().getLast().scoreLabel()).isEqualTo("+18");
        assertThat(display.sections().getLast().details())
                .extracting(SignalScoringPreferencesService.EvidenceDetail::scoreLabel)
                .containsExactly("+18", null);
    }

    @Test
    void legacyV1PayloadKeepsCategoryWeightsAndReceivesFactoryConfluenceDefaults() throws Exception {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("elliott.structure.points", "35");
        form.set("elliott.proportions.points", "15");
        service.save(user, form);

        ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var payload = mapper.readTree(stored.get().getPreferencesPayload());
        ((tools.jackson.databind.node.ObjectNode) payload).put("version", "USER_SIGNAL_SCORING_V1");
        payload.get("profiles").forEach(profile ->
                ((tools.jackson.databind.node.ObjectNode) profile).remove("confluenceRules"));
        stored.get().setProfileVersion("USER_SIGNAL_SCORING_V1");
        stored.get().setPreferencesPayload(mapper.writeValueAsString(payload));

        SignalScoringPreferencesService.Profile migrated = service.get(user)
                .profile(AlertPatternFamily.ELLIOTT_WAVE, TimeInterval.WEEKLY);
        assertThat(migrated.components().getFirst().points()).isEqualTo(35);
        assertThat(migrated.components().get(1).points()).isEqualTo(15);
        assertThat(migrated.confluenceRules()).hasSize(2).allSatisfy(rule -> {
            assertThat(rule.included()).isTrue();
            assertThat(rule.supportingPoints()).isEqualTo(10);
            assertThat(rule.opposingPoints()).isEqualTo(10);
        });
    }

    @Test
    void legacyIntervalProfilesUseDailyWeightsAndFamilyResetAppliesAcrossIntervals() throws Exception {
        var form = factoryForm();
        form.set("candlestick.confluence.elliott.supportingPoints", "18");
        form.set("harmonic.confluence.elliott.supportingPoints", "22");
        service.save(user, form);
        ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var payload = (tools.jackson.databind.node.ObjectNode) mapper.readTree(stored.get().getPreferencesPayload());
        payload.put("version", "USER_SIGNAL_SCORING_V2");
        var profiles = (tools.jackson.databind.node.ArrayNode) payload.get("profiles");
        var weekly = profiles.get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) weekly).put("interval", "WEEKLY");
        ((tools.jackson.databind.node.ObjectNode) weekly.get("confluenceRules").get(0)).put("supportingPoints", 5);
        profiles.insert(0, weekly);
        stored.get().setPreferencesPayload(mapper.writeValueAsString(payload));

        assertThat(service.get(user).profiles()).hasSize(3);
        for (TimeInterval interval : List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            assertThat(service.confluencePolicy(user, AlertPatternFamily.CANDLESTICK, interval))
                    .isEqualTo(service.confluencePolicy(user, AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY));
            assertThat(service.profile(user, AlertPatternFamily.CANDLESTICK, interval)
                    .confluenceRule(AlertPatternFamily.ELLIOTT_WAVE).supportingPoints()).isEqualTo(18);
        }
        var reset = service.reset(user, AlertPatternFamily.CANDLESTICK);
        for (TimeInterval interval : List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            assertThat(reset.profile(AlertPatternFamily.CANDLESTICK, interval).factoryProfile()).isTrue();
            assertThat(reset.profile(AlertPatternFamily.HARMONIC_FORMATION, interval)
                    .confluenceRule(AlertPatternFamily.ELLIOTT_WAVE).supportingPoints()).isEqualTo(22);
        }
        assertThat(mapper.readTree(stored.get().getPreferencesPayload()).get("profiles").size()).isEqualTo(3);
    }

    private LinkedMultiValueMap<String, String> factoryForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (SignalScoringPreferencesService.Profile profile : service.get(user).profiles()) {
            for (SignalScoringPreferencesService.ScoringComponent component : profile.components()) {
                String prefix = profile.key() + "." + component.key();
                form.set(prefix + ".included", "on");
                form.set(prefix + ".points", Integer.toString(component.points()));
            }
            for (SignalScoringPreferencesService.ConfluenceRule rule : profile.confluenceRules()) {
                String prefix = profile.key() + ".confluence." + rule.key();
                form.set(prefix + ".included", "on");
                form.set(prefix + ".supportingPoints", Integer.toString(rule.supportingPoints()));
                form.set(prefix + ".opposingPoints", Integer.toString(rule.opposingPoints()));
            }
        }
        return form;
    }

    private SignalScoringPreferencesService.EvidenceSection evidence(String category, String score) {
        return new SignalScoringPreferencesService.EvidenceSection(
                category, score, "Partial support", false, true, List.of());
    }
}
