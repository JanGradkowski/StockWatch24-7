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
        assertThat(preferences.profiles()).hasSize(6)
                .allSatisfy(profile -> assertThat(profile.totalPoints()).isEqualTo(100));
        assertThat(preferences.profile(AlertPatternFamily.CANDLESTICK, TimeInterval.DAILY).components())
                .extracting(SignalScoringPreferencesService.ScoringComponent::points)
                .containsExactly(25, 20, 5, 15, 10, 15, 10);
        assertThat(preferences.profile(AlertPatternFamily.ELLIOTT_WAVE, TimeInterval.MONTHLY).components())
                .extracting(SignalScoringPreferencesService.ScoringComponent::points)
                .containsExactly(30, 20, 15, 15, 10, 5, 5);
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

    private LinkedMultiValueMap<String, String> factoryForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (SignalScoringPreferencesService.Profile profile : service.get(user).profiles()) {
            for (SignalScoringPreferencesService.ScoringComponent component : profile.components()) {
                String prefix = profile.key() + "." + component.key();
                form.set(prefix + ".included", "on");
                form.set(prefix + ".points", Integer.toString(component.points()));
            }
        }
        return form;
    }

    private SignalScoringPreferencesService.EvidenceSection evidence(String category, String score) {
        return new SignalScoringPreferencesService.EvidenceSection(
                category, score, "Partial support", false, true, List.of());
    }
}
