package org.example.stockwatch247.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserAnalysisPreferences;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.UserAnalysisPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class AnalysisPreferencesService {
    public static final String PROFILE_VERSION = "USER_ANALYSIS_V1";

    private final UserAnalysisPreferencesRepository repository;
    private final ObjectMapper objectMapper;

    public AnalysisPreferencesService(UserAnalysisPreferencesRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PreferencesView get(User user) {
        if (user == null) {
            throw new IllegalArgumentException("An account is required.");
        }
        return repository.findByUser(user)
                .map(this::read)
                .orElseGet(AnalysisPreferencesService::factoryPreferences);
    }

    @Transactional(readOnly = true)
    public IntervalProfile profile(User user, TimeInterval interval) {
        return get(user).profile(interval);
    }

    @Transactional
    public PreferencesView save(User user, MultiValueMap<String, String> form) {
        if (form == null) {
            throw new IllegalArgumentException("Analysis preferences are required.");
        }
        PreferencesView current = get(user);
        List<IntervalProfile> profiles = List.of(
                parseProfile(form, "daily", TimeInterval.DAILY, "Daily", current.profile(TimeInterval.DAILY)),
                parseProfile(form, "weekly", TimeInterval.WEEKLY, "Weekly", current.profile(TimeInterval.WEEKLY)),
                parseProfile(form, "monthly", TimeInterval.MONTHLY, "Monthly", current.profile(TimeInterval.MONTHLY))
        );
        EmailPreferences email = new EmailPreferences(
                checked(form, "email.newCandlestick"),
                checked(form, "email.newElliott"),
                checked(form, "email.confirmed"),
                checked(form, "email.invalidated"),
                checked(form, "email.expired"),
                checked(form, "email.insider"),
                checked(form, "email.congressional"),
                checked(form, "email.daily"),
                checked(form, "email.weekly"),
                checked(form, "email.monthly"),
                checked(form, "email.buy"),
                checked(form, "email.sell")
        );
        StoredPreferences stored = new StoredPreferences(PROFILE_VERSION, profiles, email);
        String payload = write(stored);
        UserAnalysisPreferences entity = repository.findByUser(user).orElseGet(UserAnalysisPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(payload);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return new PreferencesView(PROFILE_VERSION, true, profiles, email, entity.getUpdatedAt());
    }

    @Transactional
    public PreferencesView resetAll(User user) {
        PreferencesView current = get(user);
        List<IntervalProfile> profiles = List.of(
                factoryProfile(TimeInterval.DAILY).withDetectionRules(
                        DetectionRules.from(current.profile(TimeInterval.DAILY))),
                factoryProfile(TimeInterval.WEEKLY).withDetectionRules(
                        DetectionRules.from(current.profile(TimeInterval.WEEKLY))),
                factoryProfile(TimeInterval.MONTHLY).withDetectionRules(
                        DetectionRules.from(current.profile(TimeInterval.MONTHLY)))
        );
        return persist(user, profiles, EmailPreferences.factory());
    }

    @Transactional
    public PreferencesView resetInterval(User user, TimeInterval interval) {
        PreferencesView current = get(user);
        IntervalProfile factory = factoryProfile(interval).withDetectionRules(
                DetectionRules.from(current.profile(interval)));
        List<IntervalProfile> profiles = current.profiles().stream()
                .map(profile -> profile.interval() == interval ? factory : profile)
                .toList();
        StoredPreferences stored = new StoredPreferences(PROFILE_VERSION, profiles, current.email());
        UserAnalysisPreferences entity = repository.findByUser(user).orElseGet(UserAnalysisPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(stored));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return new PreferencesView(PROFILE_VERSION, true, profiles, current.email(), entity.getUpdatedAt());
    }

    @Transactional
    public PreferencesView updateIndicatorPeriods(User user,
                                                   TimeInterval interval,
                                                   IndicatorPeriods periods) {
        if (user == null || interval == null || periods == null) {
            throw new IllegalArgumentException("An account, interval, and indicator periods are required.");
        }
        PreferencesView current = get(user);
        IntervalProfile updated = current.profile(interval).withIndicatorPeriods(periods);
        updated.validate();
        List<IntervalProfile> profiles = current.profiles().stream()
                .map(profile -> profile.interval() == interval ? updated : profile)
                .toList();
        StoredPreferences stored = new StoredPreferences(PROFILE_VERSION, profiles, current.email());
        UserAnalysisPreferences entity = repository.findByUser(user).orElseGet(UserAnalysisPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(stored));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return new PreferencesView(PROFILE_VERSION, true, profiles, current.email(), entity.getUpdatedAt());
    }

    @Transactional
    public PreferencesView updateDetectionRules(User user, MultiValueMap<String, String> form) {
        if (user == null || form == null) {
            throw new IllegalArgumentException("An account and candlestick detection rules are required.");
        }
        PreferencesView current = get(user);
        List<IntervalProfile> profiles = current.profiles().stream()
                .map(profile -> profile.withDetectionRules(parseDetectionRules(form, profile.key())))
                .toList();
        profiles.forEach(IntervalProfile::validate);
        return persist(user, profiles, current.email());
    }

    @Transactional
    public PreferencesView resetDetectionRules(User user, TimeInterval interval) {
        if (user == null) {
            throw new IllegalArgumentException("An account is required.");
        }
        PreferencesView current = get(user);
        List<IntervalProfile> profiles = current.profiles().stream()
                .map(profile -> interval == null || profile.interval() == interval
                        ? profile.withDetectionRules(DetectionRules.from(factoryProfile(profile.interval())))
                        : profile)
                .toList();
        return persist(user, profiles, current.email());
    }

    public CandlePatternDetectionService.TrendDetectionRules trendDetectionRules(IntervalProfile profile) {
        return trendDetectionRules(profile, true);
    }

    private CandlePatternDetectionService.TrendDetectionRules trendDetectionRules(
            IntervalProfile profile,
            boolean requireOriginalRule) {
        return CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(
                profile.interval(),
                profile.trendTerminalMedianDistanceAtr(),
                requireOriginalRule,
                profile.trendMinimumCandles(),
                profile.trendLookbackCandles(),
                profile.trendMinimumMovePercent());
    }

    public boolean allowsNewSignalEmail(User user, AlertPatternFamily family,
                                        TimeInterval interval, TradeSignal direction) {
        EmailPreferences email = get(user).email();
        return email.intervalEnabled(interval) && email.directionEnabled(direction)
                && (family == AlertPatternFamily.ELLIOTT_WAVE
                ? email.newElliott() : email.newCandlestick());
    }

    public boolean allowsLifecycleEmail(User user, SignalLifecycleStatus status,
                                        TimeInterval interval, TradeSignal direction) {
        EmailPreferences email = get(user).email();
        if (!email.intervalEnabled(interval) || !email.directionEnabled(direction)) {
            return false;
        }
        return switch (status) {
            case DETECTED, CONFIRMED -> email.confirmed();
            case REJECTED -> email.expired();
            case INVALIDATED -> email.invalidated();
            case EXPIRED -> email.expired();
            case POTENTIAL -> false;
        };
    }

    @Transactional(readOnly = true)
    public boolean allowsInsiderEmail(User user) {
        return get(user).email().insider();
    }

    @Transactional(readOnly = true)
    public boolean allowsCongressionalEmail(Long userId) {
        if (userId == null || userId <= 0) return true;
        return repository.findByUser_Id(userId)
                .map(this::read)
                .orElseGet(AnalysisPreferencesService::factoryPreferences)
                .email().congressional();
    }

    public String snapshot(IntervalProfile profile) {
        return write(profile);
    }

    public CandlePatternDetectionService.TrendDetectionRules trendDetectionRulesFromSnapshot(
            String snapshot,
            TimeInterval interval) {
        IntervalProfile profile = profileFromSnapshot(snapshot, interval);
        return trendDetectionRules(
                profile, Boolean.TRUE.equals(profile.trendDirectionalParticipationEnabled()));
    }

    public IntervalProfile profileFromSnapshot(String snapshot, TimeInterval interval) {
        if (snapshot == null || snapshot.isBlank()) return factoryProfile(interval);
        try {
            IntervalProfile profile = objectMapper.readValue(snapshot, IntervalProfile.class);
            profile = profile.withLegacyDetectionDefaults(false);
            profile.validate();
            if (profile.interval() != interval) {
                throw new IllegalArgumentException("The stored interval profile does not match the signal interval.");
            }
            return profile;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return factoryProfile(interval);
        }
    }

    public TechnicalIndicatorProfile technicalProfile(IntervalProfile profile) {
        return new TechnicalIndicatorProfile(
                profile.interval(), profile.rsiPeriod(), profile.atrPeriod(),
                profile.fastEmaPeriod(), profile.slowEmaPeriod(), profile.longSmaPeriod(),
                profile.macdFastPeriod(), profile.macdSlowPeriod(), profile.macdSignalPeriod(),
                profile.cciPeriod(), profile.bollingerPeriod(), profile.bollingerDeviation(),
                profile.volumePeriod(), profile.vwapPeriod(), profile.volumeProfilePeriod(),
                profile.volumeProfileValueAreaFraction());
    }

    public static PreferencesView factoryPreferences() {
        return new PreferencesView(
                PROFILE_VERSION,
                false,
                List.of(factoryProfile(TimeInterval.DAILY),
                        factoryProfile(TimeInterval.WEEKLY),
                        factoryProfile(TimeInterval.MONTHLY)),
                EmailPreferences.factory(),
                null);
    }

    public static IntervalProfile factoryProfile(TimeInterval interval) {
        return switch (interval) {
            case DAILY -> profile("daily", interval, "Daily", 14, 14, 20, 50, 200,
                    12, 26, 9, 20, 20, 2.0, 20, 20, 60);
            case WEEKLY -> profile("weekly", interval, "Weekly", 10, 10, 8, 21, 40,
                    8, 21, 5, 14, 13, 2.0, 13, 13, 26)
                    .withDetectionRules(new DetectionRules(4, 6, 3.0, 0.0, true));
            case MONTHLY -> profile("monthly", interval, "Monthly", 9, 9, 6, 12, 24,
                    6, 12, 4, 12, 12, 2.0, 12, 12, 24)
                    .withDetectionRules(new DetectionRules(4, 6, 3.0, 0.0, true));
            default -> throw new IllegalArgumentException("Only daily, weekly, and monthly profiles are supported.");
        };
    }

    private static IntervalProfile profile(String key, TimeInterval interval, String label,
                                           int rsi, int atr, int fastEma, int slowEma, int longSma,
                                           int macdFast, int macdSlow, int macdSignal, int cci,
                                           int bollinger, double deviation, int volume, int vwap,
                                           int volumeProfile) {
        return new IntervalProfile(
                key, interval, label,
                3, 10, 0.0, 0.0,
                4, 6, 3.0, 0.25, true,
                rsi, 30.0, 70.0, true,
                atr,
                fastEma, slowEma, 0.25, true,
                longSma, 0.5, true,
                macdFast, macdSlow, macdSignal, 0.05, true,
                cci, -100.0, 100.0, true,
                bollinger, deviation, true,
                volume, 1.5, true,
                vwap, 0.5, true,
                volumeProfile, 0.70, true,
                20, 1.0, true,
                2.0, true,
                10.0, 30.0, 60.0,
                true, true, true);
    }

    private IntervalProfile parseProfile(MultiValueMap<String, String> form,
                                         String key, TimeInterval interval, String label,
                                         IntervalProfile existing) {
        IntervalProfile profile = new IntervalProfile(
                key, interval, label,
                integer(form, key, "candlestickResolutionCandles"),
                integer(form, key, "elliottResolutionCandles"),
                decimal(form, key, "confirmationMovePercent"),
                decimal(form, key, "invalidationMovePercent"),
                existing.trendMinimumCandles(),
                existing.trendLookbackCandles(),
                existing.trendMinimumMovePercent(),
                existing.trendTerminalMedianDistanceAtr(),
                existing.trendDirectionalParticipationEnabled(),
                integer(form, key, "rsiPeriod"),
                decimal(form, key, "rsiBuyThreshold"),
                decimal(form, key, "rsiSellThreshold"),
                checked(form, key + ".scoreRsi"),
                integer(form, key, "atrPeriod"),
                integer(form, key, "fastEmaPeriod"),
                integer(form, key, "slowEmaPeriod"),
                decimal(form, key, "emaThresholdPercent"),
                checked(form, key + ".scoreEma"),
                integer(form, key, "longSmaPeriod"),
                decimal(form, key, "longSmaThresholdPercent"),
                checked(form, key + ".scoreLongSma"),
                integer(form, key, "macdFastPeriod"),
                integer(form, key, "macdSlowPeriod"),
                integer(form, key, "macdSignalPeriod"),
                decimal(form, key, "macdThresholdPercent"),
                checked(form, key + ".scoreMacd"),
                integer(form, key, "cciPeriod"),
                decimal(form, key, "cciBuyThreshold"),
                decimal(form, key, "cciSellThreshold"),
                checked(form, key + ".scoreCci"),
                integer(form, key, "bollingerPeriod"),
                decimal(form, key, "bollingerDeviation"),
                checked(form, key + ".scoreBollinger"),
                integer(form, key, "volumePeriod"),
                decimal(form, key, "relativeVolumeThreshold"),
                checked(form, key + ".scoreRelativeVolume"),
                integer(form, key, "vwapPeriod"),
                decimal(form, key, "vwapThresholdPercent"),
                checked(form, key + ".scoreVwap"),
                integer(form, key, "volumeProfilePeriod"),
                decimal(form, key, "volumeProfileValueAreaFraction"),
                checked(form, key + ".scoreVolumeProfile"),
                integer(form, key, "supportResistancePeriod"),
                decimal(form, key, "supportResistanceAtrDistance"),
                checked(form, key + ".scoreSupportResistance"),
                decimal(form, key, "marketRelativeThresholdPercent"),
                checked(form, key + ".scoreMarketRelative"),
                decimal(form, key, "neutralScorePercent"),
                decimal(form, key, "moderateScorePercent"),
                decimal(form, key, "strongScorePercent"),
                checked(form, key + ".scoreCandlestickSignals"),
                checked(form, key + ".scoreElliottSignals"),
                checked(form, key + ".categoryBalancedHeadline")
        );
        profile.validate();
        return profile;
    }

    private DetectionRules parseDetectionRules(MultiValueMap<String, String> form, String key) {
        return new DetectionRules(
                integer(form, key, "trendMinimumCandles"),
                integer(form, key, "trendLookbackCandles"),
                decimal(form, key, "trendMinimumMovePercent"),
                decimal(form, key, "trendTerminalMedianDistanceAtr"),
                true);
    }

    private static int integer(MultiValueMap<String, String> form, String key, String field) {
        String name = key + "." + field;
        try {
            return Integer.parseInt(required(form, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a whole number for " + readable(field) + ".");
        }
    }

    private static double decimal(MultiValueMap<String, String> form, String key, String field) {
        String name = key + "." + field;
        try {
            double value = Double.parseDouble(required(form, name));
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a valid number for " + readable(field) + ".");
        }
    }

    private static String required(MultiValueMap<String, String> form, String name) {
        String value = form.getFirst(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A value is required for " + readable(name.substring(name.indexOf('.') + 1)) + ".");
        }
        return value.trim();
    }

    private static boolean checked(MultiValueMap<String, String> form, String name) {
        String value = form.getFirst(name);
        return value != null && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equals("1"));
    }

    private static String readable(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }

    private PreferencesView read(UserAnalysisPreferences entity) {
        try {
            StoredPreferences stored = objectMapper.readValue(entity.getPreferencesPayload(), StoredPreferences.class);
            StoredPreferences normalized = stored.withLegacyDetectionDefaults();
            normalized.validate();
            return new PreferencesView(normalized.version(), true, normalized.profiles(),
                    normalized.email(), entity.getUpdatedAt());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return factoryPreferences();
        }
    }

    private PreferencesView persist(User user, List<IntervalProfile> profiles, EmailPreferences email) {
        StoredPreferences stored = new StoredPreferences(PROFILE_VERSION, profiles, email);
        stored.validate();
        UserAnalysisPreferences entity = repository.findByUser(user).orElseGet(UserAnalysisPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(stored));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return new PreferencesView(PROFILE_VERSION, true, profiles, email, entity.getUpdatedAt());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Analysis preferences could not be serialized.", exception);
        }
    }

    private record StoredPreferences(String version, List<IntervalProfile> profiles, EmailPreferences email) {
        private StoredPreferences withLegacyDetectionDefaults() {
            if (profiles == null) return this;
            return new StoredPreferences(version, profiles.stream()
                    .map(IntervalProfile::withLegacyDetectionDefaults)
                    .toList(), email);
        }

        private void validate() {
            if (!PROFILE_VERSION.equals(version) || profiles == null || profiles.size() != 3 || email == null) {
                throw new IllegalArgumentException("Stored analysis preferences are invalid.");
            }
            profiles.forEach(IntervalProfile::validate);
        }
    }

    public record PreferencesView(String version, boolean custom, List<IntervalProfile> profiles,
                                  EmailPreferences email, Instant updatedAt) {
        public PreferencesView {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }

        public IntervalProfile profile(TimeInterval interval) {
            return profiles.stream().filter(profile -> profile.interval() == interval).findFirst()
                    .orElseGet(() -> factoryProfile(interval));
        }

        public String profileLabel() { return custom ? "Custom profile" : "Factory profile"; }
    }

    public record EmailPreferences(boolean newCandlestick, boolean newElliott,
                                   boolean confirmed, boolean invalidated, boolean expired,
                                   boolean insider, boolean congressional,
                                   boolean daily, boolean weekly, boolean monthly,
                                   boolean buy, boolean sell) {
        public static EmailPreferences factory() {
            return new EmailPreferences(true, true, true, true, true, true, true,
                    true, true, true, true, true);
        }

        public boolean intervalEnabled(TimeInterval interval) {
            return switch (interval) {
                case WEEKLY -> weekly;
                case MONTHLY -> monthly;
                default -> daily;
            };
        }

        public boolean directionEnabled(TradeSignal direction) {
            return direction == TradeSignal.SELL ? sell : direction != TradeSignal.BUY || buy;
        }
    }

    public record IntervalProfile(
            String key, TimeInterval interval, String label,
            int candlestickResolutionCandles, int elliottResolutionCandles,
            double confirmationMovePercent, double invalidationMovePercent,
            int trendMinimumCandles, int trendLookbackCandles, double trendMinimumMovePercent,
            Double trendTerminalMedianDistanceAtr,
            Boolean trendDirectionalParticipationEnabled,
            int rsiPeriod, double rsiBuyThreshold, double rsiSellThreshold, boolean scoreRsi,
            int atrPeriod,
            int fastEmaPeriod, int slowEmaPeriod, double emaThresholdPercent, boolean scoreEma,
            int longSmaPeriod, double longSmaThresholdPercent, boolean scoreLongSma,
            int macdFastPeriod, int macdSlowPeriod, int macdSignalPeriod,
            double macdThresholdPercent, boolean scoreMacd,
            int cciPeriod, double cciBuyThreshold, double cciSellThreshold, boolean scoreCci,
            int bollingerPeriod, double bollingerDeviation, boolean scoreBollinger,
            int volumePeriod, double relativeVolumeThreshold, boolean scoreRelativeVolume,
            int vwapPeriod, double vwapThresholdPercent, boolean scoreVwap,
            int volumeProfilePeriod, double volumeProfileValueAreaFraction, boolean scoreVolumeProfile,
            int supportResistancePeriod, double supportResistanceAtrDistance, boolean scoreSupportResistance,
            double marketRelativeThresholdPercent, boolean scoreMarketRelative,
            double neutralScorePercent, double moderateScorePercent, double strongScorePercent,
            boolean scoreCandlestickSignals, boolean scoreElliottSignals,
            boolean categoryBalancedHeadline) {

        public IntervalProfile withIndicatorPeriods(IndicatorPeriods periods) {
            return new IntervalProfile(
                    key, interval, label,
                    candlestickResolutionCandles, elliottResolutionCandles,
                    confirmationMovePercent, invalidationMovePercent,
                    trendMinimumCandles, trendLookbackCandles, trendMinimumMovePercent,
                    trendTerminalMedianDistanceAtr,
                    trendDirectionalParticipationEnabled,
                    periods.rsiPeriod(), rsiBuyThreshold, rsiSellThreshold, scoreRsi,
                    periods.atrPeriod(),
                    periods.fastEmaPeriod(), periods.slowEmaPeriod(), emaThresholdPercent, scoreEma,
                    periods.longSmaPeriod(), longSmaThresholdPercent, scoreLongSma,
                    periods.macdFastPeriod(), periods.macdSlowPeriod(), periods.macdSignalPeriod(),
                    macdThresholdPercent, scoreMacd,
                    periods.cciPeriod(), cciBuyThreshold, cciSellThreshold, scoreCci,
                    periods.bollingerPeriod(), bollingerDeviation, scoreBollinger,
                    periods.volumePeriod(), relativeVolumeThreshold, scoreRelativeVolume,
                    periods.vwapPeriod(), vwapThresholdPercent, scoreVwap,
                    periods.volumeProfilePeriod(), volumeProfileValueAreaFraction, scoreVolumeProfile,
                    periods.supportResistancePeriod(), supportResistanceAtrDistance, scoreSupportResistance,
                    marketRelativeThresholdPercent, scoreMarketRelative,
                    neutralScorePercent, moderateScorePercent, strongScorePercent,
                    scoreCandlestickSignals, scoreElliottSignals, categoryBalancedHeadline);
        }

        public IntervalProfile withDetectionRules(DetectionRules rules) {
            rules.validate();
            return new IntervalProfile(
                    key, interval, label,
                    candlestickResolutionCandles, elliottResolutionCandles,
                    confirmationMovePercent, invalidationMovePercent,
                    rules.minimumCandles(), rules.lookbackCandles(), rules.minimumMovePercent(),
                    rules.terminalMedianDistanceAtr(),
                    rules.directionalParticipationEnabled(),
                    rsiPeriod, rsiBuyThreshold, rsiSellThreshold, scoreRsi,
                    atrPeriod,
                    fastEmaPeriod, slowEmaPeriod, emaThresholdPercent, scoreEma,
                    longSmaPeriod, longSmaThresholdPercent, scoreLongSma,
                    macdFastPeriod, macdSlowPeriod, macdSignalPeriod, macdThresholdPercent, scoreMacd,
                    cciPeriod, cciBuyThreshold, cciSellThreshold, scoreCci,
                    bollingerPeriod, bollingerDeviation, scoreBollinger,
                    volumePeriod, relativeVolumeThreshold, scoreRelativeVolume,
                    vwapPeriod, vwapThresholdPercent, scoreVwap,
                    volumeProfilePeriod, volumeProfileValueAreaFraction, scoreVolumeProfile,
                    supportResistancePeriod, supportResistanceAtrDistance, scoreSupportResistance,
                    marketRelativeThresholdPercent, scoreMarketRelative,
                    neutralScorePercent, moderateScorePercent, strongScorePercent,
                    scoreCandlestickSignals, scoreElliottSignals, categoryBalancedHeadline);
        }

        private IntervalProfile withLegacyDetectionDefaults() {
            return withLegacyDetectionDefaults(true);
        }

        private IntervalProfile withLegacyDetectionDefaults(boolean migrateFactoryParticipation) {
            if (migrateFactoryParticipation && isSupersededFactoryDetectionProfile()) {
                DetectionRules factory = DetectionRules.from(factoryProfile(interval));
                return withDetectionRules(new DetectionRules(
                        factory.minimumCandles(), factory.lookbackCandles(),
                        factory.minimumMovePercent(),
                        trendTerminalMedianDistanceAtr == null
                            ? factory.terminalMedianDistanceAtr()
                            : trendTerminalMedianDistanceAtr,
                        true));
            }
            if (trendMinimumCandles != 0 && trendLookbackCandles != 0
                    && trendMinimumMovePercent != 0.0
                    && trendTerminalMedianDistanceAtr != null
                    && trendDirectionalParticipationEnabled != null) {
                return this;
            }
            DetectionRules factory = DetectionRules.from(factoryProfile(interval));
            return withDetectionRules(new DetectionRules(
                    trendMinimumCandles == 0 ? factory.minimumCandles() : trendMinimumCandles,
                    trendLookbackCandles == 0 ? factory.lookbackCandles() : trendLookbackCandles,
                    trendMinimumMovePercent == 0.0
                            ? factory.minimumMovePercent() : trendMinimumMovePercent,
                    trendTerminalMedianDistanceAtr == null
                            ? factory.terminalMedianDistanceAtr() : trendTerminalMedianDistanceAtr,
                    trendDirectionalParticipationEnabled == null
                            ? migrateFactoryParticipation
                            : migrateFactoryParticipation || trendDirectionalParticipationEnabled));
        }

        private boolean isSupersededFactoryDetectionProfile() {
            if (interval == null) return false;
            return switch (interval) {
                case DAILY -> (trendMinimumCandles == 3 && trendLookbackCandles == 6
                        && Double.compare(trendMinimumMovePercent, 0.5) == 0)
                        || (trendMinimumCandles == 3 && trendLookbackCandles == 5
                        && Double.compare(trendMinimumMovePercent, 1.5) == 0);
                case WEEKLY -> trendMinimumCandles == 4 && trendLookbackCandles == 6
                        && Double.compare(trendMinimumMovePercent, 16.0) == 0;
                case MONTHLY -> trendMinimumCandles == 3 && trendLookbackCandles == 5
                        && Double.compare(trendMinimumMovePercent, 1.5) == 0;
                default -> false;
            };
        }

        public void validate() {
            range(candlestickResolutionCandles, 1, 20, "Candlestick resolution window");
            range(elliottResolutionCandles, 1, 20, "Elliott resolution window");
            range(confirmationMovePercent, 0, 50, "Confirmation move");
            range(invalidationMovePercent, 0, 50, "Invalidation move");
            new DetectionRules(trendMinimumCandles, trendLookbackCandles,
                    trendMinimumMovePercent, trendTerminalMedianDistanceAtr,
                    Boolean.TRUE.equals(trendDirectionalParticipationEnabled)).validate();
            period(rsiPeriod, "RSI period");
            range(rsiBuyThreshold, 1, 49, "RSI buy boundary");
            range(rsiSellThreshold, 51, 99, "RSI sell boundary");
            if (rsiBuyThreshold >= rsiSellThreshold) invalid("RSI buy boundary must be below its sell boundary.");
            period(atrPeriod, "ATR period");
            period(fastEmaPeriod, "Fast EMA period");
            period(slowEmaPeriod, "Slow EMA period");
            period(longSmaPeriod, "Long SMA period");
            if (fastEmaPeriod >= slowEmaPeriod || slowEmaPeriod > longSmaPeriod) {
                invalid("The moving-average order must be fast EMA < slow EMA <= long SMA.");
            }
            range(emaThresholdPercent, 0, 20, "EMA scoring tolerance");
            range(longSmaThresholdPercent, 0, 20, "Long SMA scoring tolerance");
            period(macdFastPeriod, "MACD fast period");
            period(macdSlowPeriod, "MACD slow period");
            period(macdSignalPeriod, "MACD signal period");
            if (macdFastPeriod >= macdSlowPeriod) invalid("MACD fast period must be below its slow period.");
            range(macdThresholdPercent, 0, 20, "MACD scoring tolerance");
            period(cciPeriod, "CCI period");
            range(cciBuyThreshold, -500, -1, "CCI buy boundary");
            range(cciSellThreshold, 1, 500, "CCI sell boundary");
            period(bollingerPeriod, "Bollinger period");
            range(bollingerDeviation, 0.5, 5, "Bollinger deviation");
            period(volumePeriod, "Volume period");
            range(relativeVolumeThreshold, 0.1, 10, "Relative-volume threshold");
            period(vwapPeriod, "VWAP period");
            range(vwapThresholdPercent, 0, 20, "VWAP scoring tolerance");
            period(volumeProfilePeriod, "Volume-profile period");
            range(volumeProfileValueAreaFraction, 0.5, 0.95, "Volume-profile value area");
            period(supportResistancePeriod, "Support/resistance period");
            range(supportResistanceAtrDistance, 0.1, 10, "Support/resistance ATR distance");
            range(marketRelativeThresholdPercent, 0, 25, "Market-relative threshold");
            range(neutralScorePercent, 0, 50, "Neutral score threshold");
            range(moderateScorePercent, 1, 75, "Moderate score threshold");
            range(strongScorePercent, 2, 100, "Strong score threshold");
            if (!(neutralScorePercent < moderateScorePercent && moderateScorePercent < strongScorePercent)) {
                invalid("Outlook thresholds must be ordered neutral < moderate < strong.");
            }
        }

        private static void period(int value, String label) { range(value, 2, 500, label); }
        private static void range(double value, double min, double max, String label) {
            if (!Double.isFinite(value) || value < min || value > max) {
                invalid(label + " must be between " + min + " and " + max + ".");
            }
        }
        private static void invalid(String message) { throw new IllegalArgumentException(message); }
    }

    public record DetectionRules(int minimumCandles,
                                 int lookbackCandles,
                                 double minimumMovePercent,
                                 double terminalMedianDistanceAtr,
                                 boolean directionalParticipationEnabled) {
        public DetectionRules(int minimumCandles, int lookbackCandles, double minimumMovePercent) {
            this(minimumCandles, lookbackCandles, minimumMovePercent, 0.25, false);
        }

        public DetectionRules(int minimumCandles, int lookbackCandles,
                              double minimumMovePercent, double terminalMedianDistanceAtr) {
            this(minimumCandles, lookbackCandles, minimumMovePercent,
                    terminalMedianDistanceAtr, false);
        }

        public static DetectionRules from(IntervalProfile profile) {
            return new DetectionRules(profile.trendMinimumCandles(), profile.trendLookbackCandles(),
                    profile.trendMinimumMovePercent(),
                    profile.trendTerminalMedianDistanceAtr() == null
                            ? factoryProfile(profile.interval()).trendTerminalMedianDistanceAtr()
                            : profile.trendTerminalMedianDistanceAtr(),
                    Boolean.TRUE.equals(profile.trendDirectionalParticipationEnabled()));
        }

        private void validate() {
            new CandlePatternDetectionService.TrendDetectionRules(
                    minimumCandles, lookbackCandles, minimumMovePercent,
                    terminalMedianDistanceAtr);
            if (!Double.isFinite(terminalMedianDistanceAtr)
                    || terminalMedianDistanceAtr < 0.0 || terminalMedianDistanceAtr > 10.0) {
                throw new IllegalArgumentException(
                        "Terminal median distance must be between 0 and 10 ATR.");
            }
        }
    }

    public record IndicatorPeriods(
            int rsiPeriod,
            int atrPeriod,
            int fastEmaPeriod,
            int slowEmaPeriod,
            int longSmaPeriod,
            int macdFastPeriod,
            int macdSlowPeriod,
            int macdSignalPeriod,
            int cciPeriod,
            int bollingerPeriod,
            int volumePeriod,
            int vwapPeriod,
            int volumeProfilePeriod,
            int supportResistancePeriod) {

        public static IndicatorPeriods from(IntervalProfile profile) {
            return new IndicatorPeriods(
                    profile.rsiPeriod(), profile.atrPeriod(),
                    profile.fastEmaPeriod(), profile.slowEmaPeriod(), profile.longSmaPeriod(),
                    profile.macdFastPeriod(), profile.macdSlowPeriod(), profile.macdSignalPeriod(),
                    profile.cciPeriod(), profile.bollingerPeriod(), profile.volumePeriod(),
                    profile.vwapPeriod(), profile.volumeProfilePeriod(), profile.supportResistancePeriod());
        }
    }
}
