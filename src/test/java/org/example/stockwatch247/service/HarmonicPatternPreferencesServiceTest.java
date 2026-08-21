package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserHarmonicPatternPreferences;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.repository.UserHarmonicPatternPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarmonicPatternPreferencesServiceTest {
    private final User user = new User();
    private final AtomicReference<UserHarmonicPatternPreferences> stored = new AtomicReference<>();
    private HarmonicPatternPreferencesService service;

    @BeforeEach
    void setUp() {
        user.setId(12L);
        UserHarmonicPatternPreferencesRepository repository =
                mock(UserHarmonicPatternPreferencesRepository.class);
        when(repository.findByUser(user)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(UserHarmonicPatternPreferences.class))).thenAnswer(invocation -> {
            UserHarmonicPatternPreferences value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        service = new HarmonicPatternPreferencesService(repository, new ObjectMapper());
    }

    @Test
    void factoryProfileContainsEveryPatternAndBuildsDetectorRules() {
        var preferences = service.get(user);

        assertThat(preferences.custom()).isFalse();
        assertThat(preferences.patterns()).hasSize(HarmonicPatternType.values().length);
        assertThat(preferences.rules().fibonacciTolerance()).isEqualTo(.04);
        assertThat(preferences.rules().pivotWindow()).isEqualTo(2);
        assertThat(preferences.patternRules().profile(HarmonicPatternType.GARTLEY).enabled()).isTrue();
        assertThat(preferences.patternRules().profile(HarmonicPatternType.GARTLEY).ratios())
                .containsEntry("bPrimary", .618);
    }

    @Test
    void savesRatioHardnessAndSoftAllowanceAsOneUserProfile() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.remove("pattern.gartley.hard.SECONDARY_RATIOS");
        form.set("pattern.gartley.softViolationPercent", "8.5");
        form.set("pattern.gartley.bPrimary", "62.0");

        var saved = service.save(user, form);
        var gartley = saved.patternRules().profile(HarmonicPatternType.GARTLEY);

        assertThat(saved.custom()).isTrue();
        assertThat(gartley.hardRules().get(HarmonicPatternDetectionService.RuleGroup.SECONDARY_RATIOS))
                .isFalse();
        assertThat(gartley.softViolationTolerance()).isEqualTo(.085);
        assertThat(gartley.ratios()).containsEntry("bPrimary", .62);
        assertThat(service.get(user).patterns().stream()
                .filter(profile -> profile.pattern() == HarmonicPatternType.GARTLEY)
                .findFirst().orElseThrow().factoryProfile()).isFalse();
    }

    @Test
    void rejectsContradictoryGlobalLegTolerances() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("global.legEqualityTolerancePercent", "12");
        form.set("global.materialLegDifferencePercent", "10");

        assertThatThrownBy(() -> service.save(user, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visual leg difference");
    }

    private LinkedMultiValueMap<String, String> factoryForm() {
        var preferences = service.get(user);
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        preferences.globals().forEach(setting ->
                form.set("global." + setting.key(), Double.toString(setting.value())));
        preferences.patterns().forEach(profile -> {
            String prefix = "pattern." + profile.key() + ".";
            if (profile.enabled()) form.set(prefix + "enabled", "on");
            form.set(prefix + "softViolationPercent", Double.toString(profile.softViolationPercent()));
            profile.ratios().forEach(setting ->
                    form.set(prefix + setting.key(), Double.toString(setting.value())));
            profile.hardRules().forEach(setting -> {
                if (setting.hard()) form.set(prefix + "hard." + setting.group().name(), "on");
            });
        });
        return form;
    }
}
