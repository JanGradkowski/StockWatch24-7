package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserElliottWavePreferences;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserElliottWavePreferencesRepository;
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

class ElliottWavePreferencesServiceTest {
    private final User user = new User();
    private final AtomicReference<UserElliottWavePreferences> stored = new AtomicReference<>();
    private ElliottWavePreferencesService service;

    @BeforeEach
    void setUp() {
        user.setId(42L);
        UserElliottWavePreferencesRepository repository = mock(UserElliottWavePreferencesRepository.class);
        when(repository.findByUser(user)).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(repository.save(any(UserElliottWavePreferences.class))).thenAnswer(invocation -> {
            UserElliottWavePreferences value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        service = new ElliottWavePreferencesService(repository, tools.jackson.databind.json.JsonMapper.builder().build());
    }

    @Test
    void factoryProfilesExplainEveryValueAndSwitchForAllChartIntervals() {
        var preferences = service.get(user);

        assertThat(preferences.custom()).isFalse();
        assertThat(preferences.profiles()).extracting(ElliottWavePreferencesService.IntervalProfile::interval)
                .containsExactly(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY);
        assertThat(preferences.profiles()).allSatisfy(profile -> {
            assertThat(profile.description()).isNotBlank();
            assertThat(profile.rules()).isEqualTo(ElliottWaveDetectionService.DetectionRules.factory());
            assertThat(profile.rules().requireWaveOneShortest()).isFalse();
            assertThat(profile.numbers()).hasSizeGreaterThan(20).allSatisfy(setting -> {
                assertThat(setting.label()).isNotBlank();
                assertThat(setting.description()).isNotBlank();
                assertThat(setting.effect()).isNotBlank();
            });
            assertThat(profile.switches()).hasSizeGreaterThan(8).allSatisfy(setting -> {
                assertThat(setting.label()).isNotBlank();
                assertThat(setting.description()).isNotBlank();
                assertThat(setting.enabledEffect()).isNotBlank();
            });
        });
    }

    @Test
    void savesDailyWeeklyAndMonthlyDefinitionsIndependentlyAndBuildsDetectorRules() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("daily.waveTwoMaximumPercent", "90");
        form.set("weekly.waveTwoMaximumPercent", "92");
        form.remove("weekly.allowTruncatedFifth");
        form.set("weekly.requireWaveOneShortest", "true");
        form.set("monthly.waveTwoMaximumPercent", "98");

        var saved = service.save(user, form);

        assertThat(saved.profile(TimeInterval.DAILY).rules().waveTwoMaximumRetracement()).isEqualTo(.90);
        assertThat(saved.profile(TimeInterval.WEEKLY).rules().waveTwoMaximumRetracement()).isEqualTo(.92);
        assertThat(saved.profile(TimeInterval.WEEKLY).rules().allowTruncatedFifth()).isFalse();
        assertThat(saved.profile(TimeInterval.WEEKLY).rules().requireWaveOneShortest()).isTrue();
        assertThat(saved.profile(TimeInterval.MONTHLY).rules().waveTwoMaximumRetracement()).isEqualTo(.98);
        assertThat(saved.profile(TimeInterval.MONTHLY).rules().requireWaveOneShortest()).isFalse();
        assertThat(service.get(user).profile(TimeInterval.WEEKLY).factoryProfile()).isFalse();
    }

    @Test
    void olderV1PayloadDefaultsTheNewShortestWaveOneRestrictionToDisabled() {
        service.save(user, factoryForm());
        UserElliottWavePreferences entity = stored.get();
        entity.setPreferencesPayload(entity.getPreferencesPayload()
                .replace(",\"requireWaveOneShortest\":false", "")
                .replace("\"requireWaveOneShortest\":false,", ""));

        var loaded = service.get(user);

        assertThat(entity.getPreferencesPayload()).doesNotContain("requireWaveOneShortest");
        assertThat(loaded.profiles()).allSatisfy(profile -> {
            assertThat(profile.rules().requireWaveOneShortest()).isFalse();
            assertThat(profile.factoryProfile()).isTrue();
        });
    }

    @Test
    void olderWeeklyMonthlyPayloadReceivesDailyFactoryRulesWithoutLosingCustomValues() throws Exception {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("weekly.minimumSignalConfidence", "82");
        service.save(user, form);
        UserElliottWavePreferences entity = stored.get();
        ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(entity.getPreferencesPayload());
        var profiles = (tools.jackson.databind.node.ArrayNode) root.get("profiles");
        profiles.remove(0);
        entity.setPreferencesPayload(mapper.writeValueAsString(root));

        var loaded = service.get(user);

        assertThat(loaded.profile(TimeInterval.DAILY).factoryProfile()).isTrue();
        assertThat(loaded.profile(TimeInterval.WEEKLY).rules().minimumSignalConfidence()).isEqualTo(82);
        assertThat(loaded.profile(TimeInterval.MONTHLY).factoryProfile()).isTrue();
    }

    @Test
    void rejectsContradictoryRangesBeforePersistence() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("weekly.waveTwoPreferredMinPercent", "80");
        form.set("weekly.waveTwoPreferredMaxPercent", "60");

        assertThatThrownBy(() -> service.save(user, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wave II preferred retracement minimum must be below its maximum");
        assertThat(stored.get()).isNull();
    }

    @Test
    void intervalResetPreservesTheOtherIntervalsCustomDefinition() {
        LinkedMultiValueMap<String, String> form = factoryForm();
        form.set("weekly.minimumSignalConfidence", "82");
        form.set("monthly.minimumSignalConfidence", "88");
        service.save(user, form);

        var reset = service.reset(user, TimeInterval.WEEKLY);

        assertThat(reset.profile(TimeInterval.WEEKLY).rules().minimumSignalConfidence()).isEqualTo(75);
        assertThat(reset.profile(TimeInterval.MONTHLY).rules().minimumSignalConfidence()).isEqualTo(88);
    }

    private LinkedMultiValueMap<String, String> factoryForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (var profile : service.get(user).profiles()) {
            profile.numbers().forEach(setting -> form.set(profile.key() + "." + setting.key(),
                    Double.toString(setting.value())));
            profile.switches().stream().filter(ElliottWavePreferencesService.BooleanSetting::value)
                    .forEach(setting -> form.set(profile.key() + "." + setting.key(), "true"));
        }
        return form;
    }
}
