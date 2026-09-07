package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
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
        service = new CandlestickPatternPreferencesService(repository, tools.jackson.databind.json.JsonMapper.builder().build());
    }

    @Test
    void factoryDefinitionsCoverEverySupportedPatternWithExplanatoryMetadata() {
        var preferences = service.get(user);

        assertThat(preferences.custom()).isFalse();
        assertThat(preferences.rewardRiskRatio(TimeInterval.DAILY)).isEqualTo(2.0);
        assertThat(preferences.rewardRiskRatio(TimeInterval.WEEKLY)).isEqualTo(3.0);
        assertThat(preferences.rewardRiskRatio(TimeInterval.MONTHLY)).isEqualTo(3.0);
        assertThat(preferences.circuitBreaker(TimeInterval.DAILY))
                .isEqualTo(new CandlestickPatternPreferencesService.CircuitBreakerSettings(
                        true, 14, 1.5, 25.0));
        assertThat(preferences.circuitBreaker(TimeInterval.WEEKLY).activationThresholdPercent())
                .isEqualTo(50.0);
        assertThat(preferences.circuitBreaker(TimeInterval.MONTHLY).activationThresholdPercent())
                .isEqualTo(50.0);
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
        form.set("circuitBreaker.weekly.atrPeriod", "21");
        form.set("circuitBreaker.weekly.atrMultiplier", "2.25");
        form.set("circuitBreaker.weekly.activationThresholdPercent", "42.5");
        form.remove("circuitBreaker.monthly.enabled");

        var saved = service.save(user, form).profile(CandlePattern.BULLISH_ENGULFING);
        var reread = service.get(user).profile(CandlePattern.BULLISH_ENGULFING);

        assertThat(saved.factoryProfile()).isFalse();
        assertThat(reread.trendRequirement()).isEqualTo(CandlestickPatternPreferencesService.TrendRequirement.NONE);
        assertThat(reread.value("currentMinPreviousBodyMultiple")).isEqualTo(1.35);
        assertThat(reread.stopLossMode()).isEqualTo(
                CandlestickPatternPreferencesService.StopLossMode.FIXED_ENTRY_PERCENT);
        assertThat(reread.stopLossValuePercent()).isEqualTo(2.75);
        assertThat(service.get(user).rewardRiskRatio(TimeInterval.WEEKLY)).isEqualTo(4.5);
        assertThat(service.get(user).circuitBreaker(TimeInterval.WEEKLY))
                .isEqualTo(new CandlestickPatternPreferencesService.CircuitBreakerSettings(
                        true, 21, 2.25, 42.5));
        assertThat(service.get(user).circuitBreaker(TimeInterval.MONTHLY).enabled()).isFalse();
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
    void migratesThePreviousUntouchedFactoryRewardRiskProfileToTheNewMonthlyDefault() {
        service.save(user, factoryForm());
        UserCandlestickPatternPreferences entity = stored.get();
        String previousPayload = entity.getPreferencesPayload()
                .replace("USER_CANDLESTICK_PATTERNS_V4", "USER_CANDLESTICK_PATTERNS_V2")
                .replace("\"MONTHLY\":3.0", "\"MONTHLY\":4.0");
        assertThat(previousPayload).contains("\"MONTHLY\":4.0");
        entity.setProfileVersion("USER_CANDLESTICK_PATTERNS_V2");
        entity.setPreferencesPayload(previousPayload);

        var migrated = service.get(user);

        assertThat(migrated.rewardRiskRatio(TimeInterval.DAILY)).isEqualTo(2.0);
        assertThat(migrated.rewardRiskRatio(TimeInterval.WEEKLY)).isEqualTo(3.0);
        assertThat(migrated.rewardRiskRatio(TimeInterval.MONTHLY)).isEqualTo(3.0);
        assertThat(migrated.custom()).isFalse();
    }

    @Test
    void addsFactoryCircuitBreakersToVersionThreeProfilesThatPredateTheFeature() throws Exception {
        service.save(user, factoryForm());
        UserCandlestickPatternPreferences entity = stored.get();
        ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var payload = (tools.jackson.databind.node.ObjectNode)
                mapper.readTree(entity.getPreferencesPayload());
        payload.put("version", "USER_CANDLESTICK_PATTERNS_V3");
        payload.remove("circuitBreakers");
        entity.setProfileVersion("USER_CANDLESTICK_PATTERNS_V3");
        entity.setPreferencesPayload(mapper.writeValueAsString(payload));

        var migrated = service.get(user);

        assertThat(migrated.circuitBreaker(TimeInterval.DAILY).activationThresholdPercent())
                .isEqualTo(25.0);
        assertThat(migrated.circuitBreaker(TimeInterval.WEEKLY).activationThresholdPercent())
                .isEqualTo(50.0);
        assertThat(migrated.circuitBreaker(TimeInterval.MONTHLY).activationThresholdPercent())
                .isEqualTo(50.0);
        assertThat(migrated.custom()).isFalse();
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

        LinkedMultiValueMap<String, String> invalidCircuitBreaker = factoryForm();
        invalidCircuitBreaker.set("circuitBreaker.daily.activationThresholdPercent", "100");
        assertThatThrownBy(() -> service.save(user, invalidCircuitBreaker))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Daily circuit-breaker activation threshold");
    }

    private LinkedMultiValueMap<String, String> factoryForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.set("rewardRisk.daily", Double.toString(service.get(user).rewardRiskRatio(TimeInterval.DAILY)));
        form.set("rewardRisk.weekly", Double.toString(service.get(user).rewardRiskRatio(TimeInterval.WEEKLY)));
        form.set("rewardRisk.monthly", Double.toString(service.get(user).rewardRiskRatio(TimeInterval.MONTHLY)));
        for (var profile : service.get(user).circuitBreakerProfiles()) {
            if (profile.enabled()) form.set("circuitBreaker." + profile.key() + ".enabled", "on");
            form.set("circuitBreaker." + profile.key() + ".atrPeriod",
                    Integer.toString(profile.atrPeriod()));
            form.set("circuitBreaker." + profile.key() + ".atrMultiplier",
                    Double.toString(profile.atrMultiplier()));
            form.set("circuitBreaker." + profile.key() + ".activationThresholdPercent",
                    Double.toString(profile.activationThresholdPercent()));
        }
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
