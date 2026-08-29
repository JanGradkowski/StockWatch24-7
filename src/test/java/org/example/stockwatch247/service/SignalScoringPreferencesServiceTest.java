package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        service = new SignalScoringPreferencesService(repository, new ObjectMapper());
    }

    @Test
    void factoryProfilesAreSeparateByFamilyAndIntervalAndEachTotalOneHundred() {
        SignalScoringPreferencesService.PreferencesView preferences = service.get(user);

        assertThat(preferences.custom()).isFalse();
        assertThat(preferences.profiles()).hasSize(9)
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
        form.set("candlestick.daily.patternQuality.points", "24");

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
        form.set("candlestick.daily.patternQuality.included", "on");
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
        form.set("elliott.weekly.structure.points", "35");
        form.set("elliott.weekly.proportions.points", "15");
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
        form.set("harmonic.daily.primaryB.points", "20");
        form.set("harmonic.daily.completion.points", "60");
        form.set("harmonic.daily.secondary.points", "20");
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
        form.set("candlestick.daily.patternQuality.included", "on");
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
    void confluenceSourcesCanBeWeightedAndDisabledPerTargetFamilyAndInterval() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("candlestick.daily.confluence.elliott.supportingPoints", "18");
        form.set("candlestick.daily.confluence.elliott.opposingPoints", "7");
        form.remove("candlestick.daily.confluence.harmonic.included");
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
                .confluenceRule(AlertPatternFamily.ELLIOTT_WAVE).supportingPoints()).isEqualTo(10);
    }

    @Test
    void currentConfluenceSettingsRescoreStructuredSavedEvidence() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("candlestick.daily.confluence.elliott.supportingPoints", "18");
        form.remove("candlestick.daily.confluence.harmonic.included");
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
        form.set("elliott.weekly.structure.points", "35");
        form.set("elliott.weekly.proportions.points", "15");
        service.save(user, form);

        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.readTree(stored.get().getPreferencesPayload());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("version", "USER_SIGNAL_SCORING_V1");
        payload.get("profiles").forEach(profile ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) profile).remove("confluenceRules"));
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
