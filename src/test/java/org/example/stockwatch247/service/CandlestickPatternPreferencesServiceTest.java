package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserCandlestickPatternPreferences;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.repository.UserCandlestickPatternPreferencesRepository;
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

class CandlestickPatternPreferencesServiceTest {
    private final User user = new User();
    private final AtomicReference<UserCandlestickPatternPreferences> stored = new AtomicReference<>();
    private CandlestickPatternPreferencesService service;

    @BeforeEach
    void setUp() {
        user.setId(42L);
        UserCandlestickPatternPreferencesRepository repository = mock(UserCandlestickPatternPreferencesRepository.class);
        when(repository.findByUser(user)).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(repository.save(any(UserCandlestickPatternPreferences.class))).thenAnswer(invocation -> {
            UserCandlestickPatternPreferences value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        service = new CandlestickPatternPreferencesService(repository, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void factoryDefinitionsCoverEverySupportedPatternWithExplanatoryMetadata() {
        var preferences = service.get(user);

        assertThat(preferences.custom()).isFalse();
        assertThat(preferences.profiles()).hasSize(15)
                .allSatisfy(profile -> {
                    assertThat(profile.description()).isNotBlank();
                    assertThat(profile.fixedRules()).isNotBlank();
                    assertThat(profile.settings()).allSatisfy(setting -> {
                        assertThat(setting.label()).doesNotContainIgnoringCase("size setting");
                        assertThat(setting.description()).containsAnyOf("body", "shadow", "close", "candle");
                        assertThat(setting.effect()).isNotBlank();
                    });
                });
    }

    @Test
    void savesIndependentGeometryAndTrendRequirementAndReadsThemBack() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("bullish_engulfing.trendRequirement", "NONE");
        form.set("bullish_engulfing.currentMinPreviousBodyMultiple", "1.35");

        var saved = service.save(user, form).profile(CandlePattern.BULLISH_ENGULFING);
        var reread = service.get(user).profile(CandlePattern.BULLISH_ENGULFING);

        assertThat(saved.factoryProfile()).isFalse();
        assertThat(reread.trendRequirement()).isEqualTo(CandlestickPatternPreferencesService.TrendRequirement.NONE);
        assertThat(reread.value("currentMinPreviousBodyMultiple")).isEqualTo(1.35);
    }

    @Test
    void rejectsAmbiguousOrImpossibleThresholdsBeforePersistence() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("hammer.minBodyPercent", "40");
        form.set("hammer.maxBodyPercent", "20");

        assertThatThrownBy(() -> service.save(user, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum body size cannot exceed maximum body size");
        assertThat(stored.get()).isNull();
    }

    @Test
    void onePatternResetLeavesOtherCustomDefinitionsUntouched() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("hammer.maxBodyPercent", "25");
        form.set("doji.maxBodyPercent", "7");
        service.save(user, form);

        var reset = service.reset(user, CandlePattern.HAMMER);

        assertThat(reset.profile(CandlePattern.HAMMER).value("maxBodyPercent")).isEqualTo(30);
        assertThat(reset.profile(CandlePattern.DOJI).value("maxBodyPercent")).isEqualTo(7);
    }

    private LinkedMultiValueMap<String, String> factoryForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (var profile : service.get(user).profiles()) {
            form.set(profile.key() + ".trendRequirement", profile.trendRequirement().name());
            profile.settings().forEach(setting -> form.set(profile.key() + "." + setting.key(),
                    Double.toString(setting.value())));
        }
        return form;
    }
}
