package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserAnalysisPreferences;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.UserAnalysisPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AnalysisPreferencesServiceTest {

    @Test
    void migratesLegacyDailyFactoryDetectionValuesButPreservesTheStoredTerminalMargin() throws Exception {
        UserAnalysisPreferencesRepository repository = mock(UserAnalysisPreferencesRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AnalysisPreferencesService service = new AnalysisPreferencesService(repository, objectMapper);
        User user = new User();
        UserAnalysisPreferences entity = new UserAnalysisPreferences();
        entity.setUser(user);
        entity.setProfileVersion(AnalysisPreferencesService.PROFILE_VERSION);
        String payload = objectMapper.writeValueAsString(Map.of(
                "version", AnalysisPreferencesService.PROFILE_VERSION,
                "profiles", List.of(
                        AnalysisPreferencesService.factoryProfile(TimeInterval.DAILY),
                        AnalysisPreferencesService.factoryProfile(TimeInterval.WEEKLY),
                        AnalysisPreferencesService.factoryProfile(TimeInterval.MONTHLY)),
                "email", AnalysisPreferencesService.EmailPreferences.factory())).replace(
                "\"trendMinimumCandles\":4,\"trendLookbackCandles\":6,\"trendMinimumMovePercent\":3.0,\"trendTerminalMedianDistanceAtr\":0.25,\"trendDirectionalParticipationEnabled\":true",
                "\"trendMinimumCandles\":3,\"trendLookbackCandles\":5,\"trendMinimumMovePercent\":1.5,\"trendTerminalMedianDistanceAtr\":0.40");
        entity.setPreferencesPayload(payload);
        when(repository.findByUser(user)).thenReturn(Optional.of(entity));

        AnalysisPreferencesService.PreferencesView migrated = service.get(user);

        assertThat(migrated.profile(TimeInterval.DAILY).trendMinimumCandles()).isEqualTo(4);
        assertThat(migrated.profile(TimeInterval.DAILY).trendLookbackCandles()).isEqualTo(6);
        assertThat(migrated.profile(TimeInterval.DAILY).trendMinimumMovePercent()).isEqualTo(3.0);
        assertThat(migrated.profile(TimeInterval.DAILY).trendTerminalMedianDistanceAtr()).isEqualTo(0.40);
        assertThat(migrated.profile(TimeInterval.DAILY).trendDirectionalParticipationEnabled()).isTrue();

        String legacySignalSnapshot = objectMapper.writeValueAsString(
                AnalysisPreferencesService.factoryProfile(TimeInterval.DAILY)).replace(
                "\"trendMinimumCandles\":4,\"trendLookbackCandles\":6,\"trendMinimumMovePercent\":3.0,\"trendTerminalMedianDistanceAtr\":0.25,\"trendDirectionalParticipationEnabled\":true",
                "\"trendMinimumCandles\":3,\"trendLookbackCandles\":5,\"trendMinimumMovePercent\":1.5,\"trendTerminalMedianDistanceAtr\":0.40");
        AnalysisPreferencesService.IntervalProfile historical =
                service.profileFromSnapshot(legacySignalSnapshot, TimeInterval.DAILY);
        assertThat(historical.trendMinimumCandles()).isEqualTo(3);
        assertThat(historical.trendLookbackCandles()).isEqualTo(5);
        assertThat(historical.trendMinimumMovePercent()).isEqualTo(1.5);
        assertThat(historical.trendTerminalMedianDistanceAtr()).isEqualTo(0.40);
        assertThat(historical.trendDirectionalParticipationEnabled()).isFalse();
    }

    @Test
    void savesAndReadsPerIntervalIndicatorAndDeliveryPreferences() {
        UserAnalysisPreferencesRepository repository = mock(UserAnalysisPreferencesRepository.class);
        AnalysisPreferencesService service = new AnalysisPreferencesService(repository, new ObjectMapper());
        User user = new User();
        user.setEmail("profile@example.com");
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        MultiValueMap<String, String> form = factoryForm();
        form.set("daily.rsiPeriod", "11");
        form.set("daily.rsiBuyThreshold", "28");
        form.set("daily.rsiSellThreshold", "74");
        form.remove("email.newElliott");
        form.remove("email.newHarmonic");
        form.remove("email.sell");

        AnalysisPreferencesService.PreferencesView saved = service.save(user, form);

        assertThat(saved.custom()).isTrue();
        assertThat(saved.profile(TimeInterval.DAILY).rsiPeriod()).isEqualTo(11);
        assertThat(saved.profile(TimeInterval.DAILY).rsiBuyThreshold()).isEqualTo(28.0);
        assertThat(saved.profile(TimeInterval.WEEKLY).rsiPeriod()).isEqualTo(10);
        assertThat(saved.email().newElliott()).isFalse();
        assertThat(saved.email().harmonicEnabled()).isFalse();
        assertThat(saved.email().sell()).isFalse();

        ArgumentCaptor<UserAnalysisPreferences> captor = ArgumentCaptor.forClass(UserAnalysisPreferences.class);
        verify(repository).save(captor.capture());
        UserAnalysisPreferences stored = captor.getValue();
        when(repository.findByUser(user)).thenReturn(Optional.of(stored));

        AnalysisPreferencesService.PreferencesView reloaded = service.get(user);
        assertThat(reloaded.profile(TimeInterval.DAILY).rsiPeriod()).isEqualTo(11);
        assertThat(reloaded.profile(TimeInterval.DAILY).rsiSellThreshold()).isEqualTo(74.0);
        assertThat(reloaded.email().newElliott()).isFalse();
        assertThat(service.allowsNewSignalEmail(user, AlertPatternFamily.CANDLESTICK,
                TimeInterval.DAILY, TradeSignal.BUY)).isTrue();
        assertThat(service.allowsNewSignalEmail(user, AlertPatternFamily.ELLIOTT_WAVE,
                TimeInterval.DAILY, TradeSignal.BUY)).isFalse();
        assertThat(service.allowsNewSignalEmail(user, AlertPatternFamily.HARMONIC_FORMATION,
                TimeInterval.DAILY, TradeSignal.BUY)).isFalse();
        assertThat(service.allowsNewSignalEmail(user, AlertPatternFamily.CANDLESTICK,
                TimeInterval.DAILY, TradeSignal.SELL)).isFalse();
    }

    @Test
    void rejectsUnsafeMovingAverageOrdering() {
        AnalysisPreferencesService service = new AnalysisPreferencesService(
                mock(UserAnalysisPreferencesRepository.class), new ObjectMapper());
        MultiValueMap<String, String> form = factoryForm();
        form.set("daily.fastEmaPeriod", "60");
        form.set("daily.slowEmaPeriod", "50");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.save(new User(), form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fast EMA < slow EMA");
    }

    @Test
    void updatesOnlyTheSelectedIntervalsIndicatorPeriodsFromTheOutlookPage() {
        UserAnalysisPreferencesRepository repository = mock(UserAnalysisPreferencesRepository.class);
        AnalysisPreferencesService service = new AnalysisPreferencesService(repository, new ObjectMapper());
        User user = new User();
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        AnalysisPreferencesService.IndicatorPeriods periods = new AnalysisPreferencesService.IndicatorPeriods(
                11, 13, 15, 35, 120,
                10, 24, 7, 18, 22, 25, 16, 55, 30);

        AnalysisPreferencesService.PreferencesView updated = service.updateIndicatorPeriods(
                user, TimeInterval.DAILY, periods);

        assertThat(updated.custom()).isTrue();
        assertThat(updated.profile(TimeInterval.DAILY).rsiPeriod()).isEqualTo(11);
        assertThat(updated.profile(TimeInterval.DAILY).fastEmaPeriod()).isEqualTo(15);
        assertThat(updated.profile(TimeInterval.DAILY).slowEmaPeriod()).isEqualTo(35);
        assertThat(updated.profile(TimeInterval.DAILY).longSmaPeriod()).isEqualTo(120);
        assertThat(updated.profile(TimeInterval.DAILY).supportResistancePeriod()).isEqualTo(30);
        assertThat(updated.profile(TimeInterval.WEEKLY))
                .isEqualTo(AnalysisPreferencesService.factoryProfile(TimeInterval.WEEKLY));
        assertThat(updated.email()).isEqualTo(AnalysisPreferencesService.EmailPreferences.factory());
        verify(repository).save(any(UserAnalysisPreferences.class));
    }

    @Test
    void updatesAndResetsOnlyPerIntervalCandlestickDetectionRules() {
        UserAnalysisPreferencesRepository repository = mock(UserAnalysisPreferencesRepository.class);
        AnalysisPreferencesService service = new AnalysisPreferencesService(repository, new ObjectMapper());
        User user = new User();
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        MultiValueMap<String, String> analysisForm = factoryForm();
        analysisForm.set("daily.rsiPeriod", "11");
        service.save(user, analysisForm);

        ArgumentCaptor<UserAnalysisPreferences> captor = ArgumentCaptor.forClass(UserAnalysisPreferences.class);
        verify(repository).save(captor.capture());
        when(repository.findByUser(user)).thenReturn(Optional.of(captor.getValue()));

        MultiValueMap<String, String> detectionForm = detectionForm();
        detectionForm.set("daily.trendMinimumCandles", "4");
        detectionForm.set("daily.trendLookbackCandles", "6");
        detectionForm.set("daily.trendMinimumMovePercent", "2.4");
        detectionForm.set("daily.trendTerminalMedianDistanceAtr", "0.55");
        detectionForm.set("daily.trendDirectionalParticipationEnabled", "on");
        AnalysisPreferencesService.PreferencesView updated =
                service.updateDetectionRules(user, detectionForm);

        assertThat(updated.profile(TimeInterval.DAILY).trendMinimumCandles()).isEqualTo(4);
        assertThat(updated.profile(TimeInterval.DAILY).trendLookbackCandles()).isEqualTo(6);
        assertThat(updated.profile(TimeInterval.DAILY).trendMinimumMovePercent()).isEqualTo(2.4);
        assertThat(updated.profile(TimeInterval.DAILY).trendTerminalMedianDistanceAtr()).isEqualTo(0.55);
        assertThat(service.trendDetectionRules(updated.profile(TimeInterval.DAILY)).adaptiveFactory()).isTrue();
        assertThat(service.trendDetectionRules(updated.profile(TimeInterval.DAILY))
                .directionalParticipationEnabled()).isTrue();
        assertThat(updated.profile(TimeInterval.DAILY).rsiPeriod()).isEqualTo(11);
        assertThat(updated.profile(TimeInterval.WEEKLY).trendMinimumCandles()).isEqualTo(4);
        assertThat(updated.profile(TimeInterval.WEEKLY).trendLookbackCandles()).isEqualTo(6);
        assertThat(updated.profile(TimeInterval.WEEKLY).trendMinimumMovePercent()).isEqualTo(3.0);
        assertThat(updated.profile(TimeInterval.WEEKLY).trendTerminalMedianDistanceAtr()).isZero();
        assertThat(service.trendDetectionRules(updated.profile(TimeInterval.WEEKLY)).adaptiveFactory())
                .isTrue();
        assertThat(service.trendDetectionRules(updated.profile(TimeInterval.WEEKLY))
                .directionalParticipationEnabled()).isTrue();

        verify(repository, times(2)).save(captor.capture());
        UserAnalysisPreferences updatedEntity = captor.getAllValues().getLast();
        when(repository.findByUser(user)).thenReturn(Optional.of(updatedEntity));
        AnalysisPreferencesService.PreferencesView reset =
                service.resetDetectionRules(user, TimeInterval.DAILY);

        assertThat(reset.profile(TimeInterval.DAILY).trendMinimumCandles()).isEqualTo(4);
        assertThat(reset.profile(TimeInterval.DAILY).trendLookbackCandles()).isEqualTo(6);
        assertThat(reset.profile(TimeInterval.DAILY).trendMinimumMovePercent()).isEqualTo(3.0);
        assertThat(reset.profile(TimeInterval.DAILY).trendTerminalMedianDistanceAtr()).isEqualTo(0.25);
        assertThat(reset.profile(TimeInterval.DAILY).trendDirectionalParticipationEnabled()).isTrue();
        assertThat(service.trendDetectionRules(reset.profile(TimeInterval.DAILY)).adaptiveFactory())
                .isTrue();
        assertThat(reset.profile(TimeInterval.DAILY).rsiPeriod()).isEqualTo(11);
    }

    private MultiValueMap<String, String> factoryForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        addProfile(form, AnalysisPreferencesService.factoryProfile(TimeInterval.DAILY));
        addProfile(form, AnalysisPreferencesService.factoryProfile(TimeInterval.WEEKLY));
        addProfile(form, AnalysisPreferencesService.factoryProfile(TimeInterval.MONTHLY));
        for (String name : new String[]{"newCandlestick", "newElliott", "newHarmonic", "confirmed", "invalidated",
                "expired", "insider", "congressional", "daily", "weekly", "monthly", "buy", "sell"}) {
            form.add("email." + name, "on");
        }
        return form;
    }

    private MultiValueMap<String, String> detectionForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (TimeInterval interval : new TimeInterval[]{TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY}) {
            AnalysisPreferencesService.IntervalProfile profile = AnalysisPreferencesService.factoryProfile(interval);
            String key = profile.key() + ".";
            value(form, key, "trendMinimumCandles", profile.trendMinimumCandles());
            value(form, key, "trendLookbackCandles", profile.trendLookbackCandles());
            value(form, key, "trendMinimumMovePercent", profile.trendMinimumMovePercent());
            value(form, key, "trendTerminalMedianDistanceAtr",
                    profile.trendTerminalMedianDistanceAtr());
            if (Boolean.TRUE.equals(profile.trendDirectionalParticipationEnabled())) {
                form.add(key + "trendDirectionalParticipationEnabled", "on");
            }
        }
        return form;
    }

    private void addProfile(MultiValueMap<String, String> form,
                            AnalysisPreferencesService.IntervalProfile profile) {
        String key = profile.key() + ".";
        value(form, key, "candlestickResolutionCandles", profile.candlestickResolutionCandles());
        value(form, key, "elliottResolutionCandles", profile.elliottResolutionCandles());
        value(form, key, "confirmationMovePercent", profile.confirmationMovePercent());
        value(form, key, "invalidationMovePercent", profile.invalidationMovePercent());
        value(form, key, "rsiPeriod", profile.rsiPeriod());
        value(form, key, "rsiBuyThreshold", profile.rsiBuyThreshold());
        value(form, key, "rsiSellThreshold", profile.rsiSellThreshold());
        value(form, key, "atrPeriod", profile.atrPeriod());
        value(form, key, "fastEmaPeriod", profile.fastEmaPeriod());
        value(form, key, "slowEmaPeriod", profile.slowEmaPeriod());
        value(form, key, "emaThresholdPercent", profile.emaThresholdPercent());
        value(form, key, "longSmaPeriod", profile.longSmaPeriod());
        value(form, key, "longSmaThresholdPercent", profile.longSmaThresholdPercent());
        value(form, key, "macdFastPeriod", profile.macdFastPeriod());
        value(form, key, "macdSlowPeriod", profile.macdSlowPeriod());
        value(form, key, "macdSignalPeriod", profile.macdSignalPeriod());
        value(form, key, "macdThresholdPercent", profile.macdThresholdPercent());
        value(form, key, "cciPeriod", profile.cciPeriod());
        value(form, key, "cciBuyThreshold", profile.cciBuyThreshold());
        value(form, key, "cciSellThreshold", profile.cciSellThreshold());
        value(form, key, "bollingerPeriod", profile.bollingerPeriod());
        value(form, key, "bollingerDeviation", profile.bollingerDeviation());
        value(form, key, "volumePeriod", profile.volumePeriod());
        value(form, key, "relativeVolumeThreshold", profile.relativeVolumeThreshold());
        value(form, key, "vwapPeriod", profile.vwapPeriod());
        value(form, key, "vwapThresholdPercent", profile.vwapThresholdPercent());
        value(form, key, "volumeProfilePeriod", profile.volumeProfilePeriod());
        value(form, key, "volumeProfileValueAreaFraction", profile.volumeProfileValueAreaFraction());
        value(form, key, "supportResistancePeriod", profile.supportResistancePeriod());
        value(form, key, "supportResistanceAtrDistance", profile.supportResistanceAtrDistance());
        value(form, key, "marketRelativeThresholdPercent", profile.marketRelativeThresholdPercent());
        value(form, key, "neutralScorePercent", profile.neutralScorePercent());
        value(form, key, "moderateScorePercent", profile.moderateScorePercent());
        value(form, key, "strongScorePercent", profile.strongScorePercent());
        for (String checkbox : new String[]{"scoreRsi", "scoreEma", "scoreLongSma", "scoreMacd",
                "scoreCci", "scoreBollinger", "scoreRelativeVolume", "scoreVwap",
                "scoreVolumeProfile", "scoreSupportResistance", "scoreMarketRelative",
                "scoreCandlestickSignals", "scoreElliottSignals", "categoryBalancedHeadline"}) {
            form.add(key + checkbox, "on");
        }
    }

    private void value(MultiValueMap<String, String> form, String key, String field, Object value) {
        form.add(key + field, String.valueOf(value));
    }
}
