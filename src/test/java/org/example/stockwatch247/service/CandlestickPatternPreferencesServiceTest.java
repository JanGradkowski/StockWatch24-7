package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserCandlestickPatternPreferences;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TimeInterval;
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
        assertThat(preferences.rewardRiskRatio(TimeInterval.DAILY)).isEqualTo(2.0);
        assertThat(preferences.rewardRiskRatio(TimeInterval.WEEKLY)).isEqualTo(3.0);
        assertThat(preferences.rewardRiskRatio(TimeInterval.MONTHLY)).isEqualTo(4.0);
        assertThat(preferences.profiles()).hasSize(15)
                .allSatisfy(profile -> {
                    assertThat(profile.description()).isNotBlank();
                    assertThat(profile.fixedRules()).isNotBlank();
                    if (profile.directional()) {
                        assertThat(profile.stopLossMode()).isEqualTo(
                                CandlestickPatternPreferencesService.StopLossMode.STRUCTURAL_BUFFER);
                        assertThat(profile.stopLossValuePercent()).isZero();
                    }
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
        form.set("bullish_engulfing.stopLossMode", "FIXED_ENTRY_PERCENT");
        form.set("bullish_engulfing.stopLossValuePercent", "2.75");
        form.set("rewardRisk.weekly", "4.5");

        var saved = service.save(user, form).profile(CandlePattern.BULLISH_ENGULFING);
        var reread = service.get(user).profile(CandlePattern.BULLISH_ENGULFING);

        assertThat(saved.factoryProfile()).isFalse();
        assertThat(reread.trendRequirement()).isEqualTo(CandlestickPatternPreferencesService.TrendRequirement.NONE);
        assertThat(reread.value("currentMinPreviousBodyMultiple")).isEqualTo(1.35);
        assertThat(reread.stopLossMode()).isEqualTo(
                CandlestickPatternPreferencesService.StopLossMode.FIXED_ENTRY_PERCENT);
        assertThat(reread.stopLossValuePercent()).isEqualTo(2.75);
        assertThat(service.get(user).rewardRiskRatio(TimeInterval.WEEKLY)).isEqualTo(4.5);
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
        form.set("hammer.stopLossMode", "FIXED_ENTRY_PERCENT");
        form.set("hammer.stopLossValuePercent", "3");
        form.set("doji.maxBodyPercent", "7");
        form.set("rewardRisk.daily", "2.5");
        service.save(user, form);

        var reset = service.reset(user, CandlePattern.HAMMER);

        assertThat(reset.profile(CandlePattern.HAMMER).value("maxBodyPercent")).isEqualTo(30);
        assertThat(reset.profile(CandlePattern.HAMMER).stopLossMode()).isEqualTo(
                CandlestickPatternPreferencesService.StopLossMode.STRUCTURAL_BUFFER);
        assertThat(reset.profile(CandlePattern.HAMMER).stopLossValuePercent()).isZero();
        assertThat(reset.profile(CandlePattern.DOJI).value("maxBodyPercent")).isEqualTo(7);
        assertThat(reset.rewardRiskRatio(TimeInterval.DAILY)).isEqualTo(2.5);
    }

    @Test
    void rejectsInvalidFixedEntryStopAndRewardRiskRatio() {
        LinkedMultiValueMap<String, String> invalidStopForm = factoryForm();
        invalidStopForm.set("hammer.stopLossMode", "FIXED_ENTRY_PERCENT");
        invalidStopForm.set("hammer.stopLossValuePercent", "0");

        assertThatThrownBy(() -> service.save(user, invalidStopForm))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hammer stop-loss value");

        LinkedMultiValueMap<String, String> invalidRatioForm = factoryForm();
        invalidRatioForm.set("rewardRisk.monthly", "25");
        assertThatThrownBy(() -> service.save(user, invalidRatioForm))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Monthly risk-to-reward ratio");
    }

    private LinkedMultiValueMap<String, String> factoryForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.set("rewardRisk.daily", Double.toString(service.get(user).rewardRiskRatio(TimeInterval.DAILY)));
        form.set("rewardRisk.weekly", Double.toString(service.get(user).rewardRiskRatio(TimeInterval.WEEKLY)));
        form.set("rewardRisk.monthly", Double.toString(service.get(user).rewardRiskRatio(TimeInterval.MONTHLY)));
        for (var profile : service.get(user).profiles()) {
            form.set(profile.key() + ".trendRequirement", profile.trendRequirement().name());
            if (profile.directional()) {
                form.set(profile.key() + ".stopLossMode", profile.stopLossMode().name());
                form.set(profile.key() + ".stopLossValuePercent",
                        Double.toString(profile.stopLossValuePercent()));
            }
            profile.settings().forEach(setting -> form.set(profile.key() + "." + setting.key(),
                    Double.toString(setting.value())));
        }
        return form;
    }
}
