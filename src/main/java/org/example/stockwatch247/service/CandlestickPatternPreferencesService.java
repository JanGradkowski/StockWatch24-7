package org.example.stockwatch247.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserCandlestickPatternPreferences;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserCandlestickPatternPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.*;

@Service
public class CandlestickPatternPreferencesService {
    public static final String PROFILE_VERSION = "USER_CANDLESTICK_PATTERNS_V4";
    private static final String PREVIOUS_PROFILE_VERSION = "USER_CANDLESTICK_PATTERNS_V3";
    private static final String REWARD_RISK_MIGRATION_VERSION = "USER_CANDLESTICK_PATTERNS_V2";
    private static final String LEGACY_PROFILE_VERSION = "USER_CANDLESTICK_PATTERNS_V1";
    private static final Map<TimeInterval, Double> FACTORY_REWARD_RISK = Map.of(
            TimeInterval.DAILY, 2.0,
            TimeInterval.WEEKLY, 3.0,
            TimeInterval.MONTHLY, 3.0);
    private static final Map<TimeInterval, CircuitBreakerSettings> FACTORY_CIRCUIT_BREAKERS = Map.of(
            TimeInterval.DAILY, new CircuitBreakerSettings(true, 14, 1.5, 25.0),
            TimeInterval.WEEKLY, new CircuitBreakerSettings(true, 14, 1.5, 50.0),
            TimeInterval.MONTHLY, new CircuitBreakerSettings(true, 14, 1.5, 50.0));
    private static final List<PatternDefinition> FACTORY = factoryDefinitions();
    private static final PreferencesView FACTORY_VIEW = materialize(
            FACTORY.stream().map(CandlestickPatternPreferencesService::stored).toList(),
            FACTORY_REWARD_RISK, FACTORY_CIRCUIT_BREAKERS, null);

    private final UserCandlestickPatternPreferencesRepository repository;
    private final ObjectMapper objectMapper;

    public CandlestickPatternPreferencesService(UserCandlestickPatternPreferencesRepository repository,
                                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PreferencesView get(User user) {
        return AnalysisComputationScope.memo(java.util.List.of(CandlestickPatternPreferencesService.class,
                user == null ? "factory" : user.getId() == null ? user : user.getId()), () -> loadPreferences(user));
    }

    private PreferencesView loadPreferences(User user) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        return repository.findByUser(user).map(this::read).orElseGet(CandlestickPatternPreferencesService::factoryPreferences);
    }

    @Transactional
    public PreferencesView save(User user, MultiValueMap<String, String> form) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        if (form == null) throw new IllegalArgumentException("Candlestick pattern settings are required.");
        List<StoredProfile> profiles = FACTORY.stream().map(definition -> parse(form, definition)).toList();
        return persist(user, profiles, parseRewardRisk(form), parseCircuitBreakers(form));
    }

    @Transactional
    public PreferencesView reset(User user, CandlePattern pattern) {
        if (pattern == null) {
            repository.findByUser(user).ifPresent(repository::delete);
            return factoryPreferences();
        }
        PatternDefinition factory = definition(pattern);
        PreferencesView current = get(user);
        List<StoredProfile> profiles = current.profiles().stream()
                .map(profile -> profile.pattern() == pattern ? stored(factory) : stored(profile))
                .toList();
        return persist(user, profiles, current.rewardRiskRatios(), current.circuitBreakers());
    }

    public static PreferencesView factoryPreferences() {
        return FACTORY_VIEW;
    }

    private StoredProfile parse(MultiValueMap<String, String> form, PatternDefinition definition) {
        String prefix = definition.key() + ".";
        TrendRequirement trend;
        try {
            trend = TrendRequirement.valueOf(required(form, prefix + "trendRequirement").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(definition.label() + ": choose a valid prior-trend requirement.");
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (NumericDefinition numeric : definition.settings()) {
            String raw = required(form, prefix + numeric.key());
            double value;
            try { value = Double.parseDouble(raw); }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException(definition.label() + " — " + numeric.label() + " must be a number.");
            }
            if (!Double.isFinite(value) || value < numeric.min() || value > numeric.max()) {
                throw new IllegalArgumentException(definition.label() + " — " + numeric.label()
                        + " must be between " + display(numeric.min()) + " and " + display(numeric.max()) + ".");
            }
            values.put(numeric.key(), value);
        }
        validateRelationships(definition, values);
        StopLossMode stopLossMode = StopLossMode.STRUCTURAL_BUFFER;
        double stopLossValuePercent = 0.0;
        if (definition.pattern() != CandlePattern.DOJI) {
            try {
                stopLossMode = StopLossMode.valueOf(required(form, prefix + "stopLossMode")
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(definition.label() + ": choose a valid stop-loss method.");
            }
            stopLossValuePercent = decimal(form, prefix + "stopLossValuePercent",
                    definition.label() + " stop-loss value");
            validateStopLoss(definition.label(), stopLossMode, stopLossValuePercent);
        }
        return new StoredProfile(definition.pattern(), trend, values, stopLossMode, stopLossValuePercent);
    }

    private Map<TimeInterval, Double> parseRewardRisk(MultiValueMap<String, String> form) {
        Map<TimeInterval, Double> ratios = new EnumMap<>(TimeInterval.class);
        for (TimeInterval interval : List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            double value = decimal(form, "rewardRisk." + interval.name().toLowerCase(Locale.ROOT),
                    intervalLabel(interval) + " risk-to-reward ratio");
            if (value < 0.1 || value > 20.0) {
                throw new IllegalArgumentException(intervalLabel(interval)
                        + " risk-to-reward ratio must be between 0.1 and 20.");
            }
            ratios.put(interval, value);
        }
        return Map.copyOf(ratios);
    }

    private Map<TimeInterval, CircuitBreakerSettings> parseCircuitBreakers(MultiValueMap<String, String> form) {
        Map<TimeInterval, CircuitBreakerSettings> profiles = new EnumMap<>(TimeInterval.class);
        for (TimeInterval interval : List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            String prefix = "circuitBreaker." + interval.name().toLowerCase(Locale.ROOT) + ".";
            int atrPeriod = integer(form, prefix + "atrPeriod", intervalLabel(interval) + " ATR period");
            double atrMultiplier = decimal(form, prefix + "atrMultiplier",
                    intervalLabel(interval) + " ATR multiplier");
            double activationThreshold = decimal(form, prefix + "activationThresholdPercent",
                    intervalLabel(interval) + " circuit-breaker activation threshold");
            CircuitBreakerSettings profile = new CircuitBreakerSettings(
                    form.containsKey(prefix + "enabled"), atrPeriod, atrMultiplier, activationThreshold);
            validateCircuitBreaker(interval, profile);
            profiles.put(interval, profile);
        }
        return Map.copyOf(profiles);
    }

    private PreferencesView persist(User user, List<StoredProfile> profiles,
                                    Map<TimeInterval, Double> rewardRiskRatios,
                                    Map<TimeInterval, CircuitBreakerSettings> circuitBreakers) {
        validateStored(profiles, rewardRiskRatios, circuitBreakers);
        UserCandlestickPatternPreferences entity = repository.findByUser(user)
                .orElseGet(UserCandlestickPatternPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(new StoredPreferences(
                PROFILE_VERSION, profiles, rewardRiskRatios, circuitBreakers)));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return materialize(profiles, rewardRiskRatios, circuitBreakers, entity.getUpdatedAt());
    }

    private PreferencesView read(UserCandlestickPatternPreferences entity) {
        try {
            StoredPreferences stored = objectMapper.readValue(entity.getPreferencesPayload(), StoredPreferences.class);
            if (!PROFILE_VERSION.equals(stored.version())
                    && !PREVIOUS_PROFILE_VERSION.equals(stored.version())
                    && !REWARD_RISK_MIGRATION_VERSION.equals(stored.version())
                    && !LEGACY_PROFILE_VERSION.equals(stored.version())) {
                return factoryPreferences();
            }
            List<StoredProfile> profiles = normalizeProfiles(stored.profiles());
            Map<TimeInterval, Double> ratios = migratedRewardRisk(stored.version(), stored.rewardRiskRatios());
            Map<TimeInterval, CircuitBreakerSettings> circuitBreakers =
                    normalizeCircuitBreakers(stored.circuitBreakers());
            validateStored(profiles, ratios, circuitBreakers);
            return materialize(profiles, ratios, circuitBreakers, entity.getUpdatedAt());
        } catch (JacksonException | IllegalArgumentException exception) {
            return factoryPreferences();
        }
    }

    private String write(StoredPreferences stored) {
        try { return objectMapper.writeValueAsString(stored); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not save candlestick settings.", exception); }
    }

    private static PreferencesView materialize(List<StoredProfile> storedProfiles,
                                               Map<TimeInterval, Double> rewardRiskRatios,
                                               Map<TimeInterval, CircuitBreakerSettings> circuitBreakers,
                                               Instant updatedAt) {
        List<PatternProfile> profiles = FACTORY.stream().map(definition -> {
            StoredProfile stored = storedProfiles.stream().filter(item -> item.pattern() == definition.pattern())
                    .findFirst().orElseThrow();
            List<NumericSetting> settings = definition.settings().stream().map(item -> new NumericSetting(
                    item.key(), item.label(), item.description(), item.unit(), item.effect(), item.min(), item.max(),
                    item.step(), stored.values().get(item.key()), item.factoryValue())).toList();
            boolean factory = stored.trendRequirement() == definition.factoryTrendRequirement()
                    && definition.settings().stream().allMatch(item ->
                    Double.compare(stored.values().get(item.key()), item.factoryValue()) == 0)
                    && stored.stopLossMode() == StopLossMode.STRUCTURAL_BUFFER
                    && Double.compare(stored.stopLossValuePercent(), 0.0) == 0;
            return new PatternProfile(definition.pattern(), definition.key(), definition.label(), definition.formation(),
                    definition.description(), definition.fixedRules(), stored.trendRequirement(),
                    definition.factoryTrendRequirement(), settings, stored.stopLossMode(),
                    stored.stopLossValuePercent(), factory);
        }).toList();
        boolean customRatios = FACTORY_REWARD_RISK.entrySet().stream().anyMatch(entry ->
                Double.compare(rewardRiskRatios.get(entry.getKey()), entry.getValue()) != 0);
        boolean customCircuitBreakers = FACTORY_CIRCUIT_BREAKERS.entrySet().stream().anyMatch(entry ->
                !entry.getValue().equals(circuitBreakers.get(entry.getKey())));
        return new PreferencesView(PROFILE_VERSION,
                customRatios || customCircuitBreakers
                        || profiles.stream().anyMatch(profile -> !profile.factoryProfile()),
                profiles, rewardRiskRatios, circuitBreakers, updatedAt);
    }

    private static void validateStored(List<StoredProfile> profiles,
                                       Map<TimeInterval, Double> rewardRiskRatios,
                                       Map<TimeInterval, CircuitBreakerSettings> circuitBreakers) {
        if (profiles == null || profiles.size() != FACTORY.size())
            throw new IllegalArgumentException("Every candlestick pattern definition is required.");
        for (PatternDefinition definition : FACTORY) {
            StoredProfile profile = profiles.stream().filter(item -> item.pattern() == definition.pattern())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("A candlestick pattern definition is missing."));
            if (profile.trendRequirement() == null || profile.values() == null
                    || profile.values().size() != definition.settings().size())
                throw new IllegalArgumentException("An incomplete candlestick pattern definition was saved.");
            if (profile.stopLossMode() == null || profile.stopLossValuePercent() == null) {
                throw new IllegalArgumentException("An incomplete candlestick stop-loss rule was saved.");
            }
            if (definition.pattern() != CandlePattern.DOJI) {
                validateStopLoss(definition.label(), profile.stopLossMode(), profile.stopLossValuePercent());
            }
            for (NumericDefinition numeric : definition.settings()) {
                Double value = profile.values().get(numeric.key());
                if (value == null || !Double.isFinite(value) || value < numeric.min() || value > numeric.max())
                    throw new IllegalArgumentException("A candlestick threshold is outside its supported range.");
            }
            validateRelationships(definition, profile.values());
        }
        if (rewardRiskRatios == null || !rewardRiskRatios.keySet().containsAll(FACTORY_REWARD_RISK.keySet())) {
            throw new IllegalArgumentException("Every candlestick risk-to-reward ratio is required.");
        }
        FACTORY_REWARD_RISK.keySet().forEach(interval -> {
            Double value = rewardRiskRatios.get(interval);
            if (value == null || !Double.isFinite(value) || value < 0.1 || value > 20.0) {
                throw new IllegalArgumentException("A candlestick risk-to-reward ratio is outside its supported range.");
            }
        });
        if (circuitBreakers == null
                || !circuitBreakers.keySet().containsAll(FACTORY_CIRCUIT_BREAKERS.keySet())) {
            throw new IllegalArgumentException("Every candlestick ATR circuit-breaker profile is required.");
        }
        FACTORY_CIRCUIT_BREAKERS.keySet().forEach(interval ->
                validateCircuitBreaker(interval, circuitBreakers.get(interval)));
    }

    private static List<StoredProfile> normalizeProfiles(List<StoredProfile> profiles) {
        if (profiles == null) return List.of();
        return profiles.stream().map(profile -> new StoredProfile(
                profile.pattern(), profile.trendRequirement(), profile.values(),
                profile.stopLossMode() == null ? StopLossMode.STRUCTURAL_BUFFER : profile.stopLossMode(),
                profile.stopLossValuePercent() == null ? 0.0 : profile.stopLossValuePercent())).toList();
    }

    private static Map<TimeInterval, Double> normalizeRewardRisk(Map<TimeInterval, Double> ratios) {
        Map<TimeInterval, Double> normalized = new EnumMap<>(TimeInterval.class);
        normalized.putAll(FACTORY_REWARD_RISK);
        if (ratios != null) ratios.forEach((interval, value) -> {
            if (interval != null && value != null) normalized.put(interval, value);
        });
        return Map.copyOf(normalized);
    }

    private static Map<TimeInterval, CircuitBreakerSettings> normalizeCircuitBreakers(
            Map<TimeInterval, CircuitBreakerSettings> profiles) {
        Map<TimeInterval, CircuitBreakerSettings> normalized = new EnumMap<>(TimeInterval.class);
        normalized.putAll(FACTORY_CIRCUIT_BREAKERS);
        if (profiles != null) profiles.forEach((interval, profile) -> {
            if (interval != null && profile != null) normalized.put(interval, profile);
        });
        return Map.copyOf(normalized);
    }

    private static Map<TimeInterval, Double> migratedRewardRisk(
            String storedVersion,
            Map<TimeInterval, Double> storedRatios) {
        Map<TimeInterval, Double> normalized = new EnumMap<>(normalizeRewardRisk(storedRatios));
        if (REWARD_RISK_MIGRATION_VERSION.equals(storedVersion)
                && Double.compare(normalized.get(TimeInterval.DAILY), 2.0) == 0
                && Double.compare(normalized.get(TimeInterval.WEEKLY), 3.0) == 0
                && Double.compare(normalized.get(TimeInterval.MONTHLY), 4.0) == 0) {
            normalized.put(TimeInterval.MONTHLY, FACTORY_REWARD_RISK.get(TimeInterval.MONTHLY));
        }
        return Map.copyOf(normalized);
    }

    private static void validateCircuitBreaker(TimeInterval interval, CircuitBreakerSettings profile) {
        if (profile == null || profile.atrPeriod() < 2 || profile.atrPeriod() > 500) {
            throw new IllegalArgumentException(intervalLabel(interval)
                    + " ATR period must be between 2 and 500 candles.");
        }
        if (!Double.isFinite(profile.atrMultiplier())
                || profile.atrMultiplier() < 0.1 || profile.atrMultiplier() > 10.0) {
            throw new IllegalArgumentException(intervalLabel(interval)
                    + " ATR multiplier must be between 0.1 and 10.");
        }
        if (!Double.isFinite(profile.activationThresholdPercent())
                || profile.activationThresholdPercent() < 1.0
                || profile.activationThresholdPercent() > 95.0) {
            throw new IllegalArgumentException(intervalLabel(interval)
                    + " circuit-breaker activation threshold must be between 1 and 95 percent.");
        }
    }

    private static void validateStopLoss(String label, StopLossMode mode, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " stop-loss value must be a number.");
        }
        double minimum = mode == StopLossMode.FIXED_ENTRY_PERCENT ? 0.1 : 0.0;
        if (value < minimum || value > 50.0) {
            throw new IllegalArgumentException(label + " stop-loss value must be between "
                    + display(minimum) + " and 50 percent.");
        }
    }

    private static void validateRelationships(PatternDefinition definition, Map<String, Double> values) {
        if (values.containsKey("minBodyPercent") && values.containsKey("maxBodyPercent")
                && values.get("minBodyPercent") > values.get("maxBodyPercent"))
            throw new IllegalArgumentException(definition.label() + ": minimum body size cannot exceed maximum body size.");
    }

    private static StoredProfile stored(PatternDefinition definition) {
        Map<String, Double> values = new LinkedHashMap<>();
        definition.settings().forEach(setting -> values.put(setting.key(), setting.factoryValue()));
        return new StoredProfile(definition.pattern(), definition.factoryTrendRequirement(), values,
                StopLossMode.STRUCTURAL_BUFFER, 0.0);
    }
    private static StoredProfile stored(PatternProfile profile) {
        Map<String, Double> values = new LinkedHashMap<>();
        profile.settings().forEach(setting -> values.put(setting.key(), setting.value()));
        return new StoredProfile(profile.pattern(), profile.trendRequirement(), values,
                profile.stopLossMode(), profile.stopLossValuePercent());
    }
    private static PatternDefinition definition(CandlePattern pattern) {
        return FACTORY.stream().filter(item -> item.pattern() == pattern).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose a configurable candlestick pattern."));
    }
    private static String required(MultiValueMap<String, String> form, String key) {
        String value = form.getFirst(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Every candlestick setting must be submitted.");
        return value.trim();
    }
    private static double decimal(MultiValueMap<String, String> form, String key, String label) {
        try {
            double value = Double.parseDouble(required(form, key));
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }
    private static int integer(MultiValueMap<String, String> form, String key, String label) {
        try {
            return Integer.parseInt(required(form, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }
    private static String intervalLabel(TimeInterval interval) {
        return interval.name().substring(0, 1) + interval.name().substring(1).toLowerCase(Locale.ROOT);
    }
    private static String display(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }

    private static NumericDefinition percent(String key, String label, String description, String effect, double value) {
        return new NumericDefinition(key, label, description, "%", effect, 0, 100, 0.5, value);
    }
    private static NumericDefinition multiple(String key, String label, String description, String effect, double value) {
        return new NumericDefinition(key, label, description, "×", effect, 0, 10, 0.05, value);
    }
    private static List<NumericDefinition> wick(double min, double longMultiple, double shortMultiple, double max) {
        return List.of(
                percent("minBodyPercent", "Smallest permitted real body", "The distance from open to close, divided by the candle's full high-to-low range.", "Raise this to reject extremely thin, doji-like bodies.", min),
                multiple("minLongShadowBodyMultiple", "Minimum long-shadow length", "The defining long shadow must be at least this many times the real-body height.", "Raise this to demand a more pronounced rejection wick.", longMultiple),
                multiple("maxShortShadowBodyMultiple", "Maximum opposite-shadow length", "The short shadow on the opposite side may be no more than this many times the real-body height.", "Lower this to require a cleaner candle with less wick on the opposite side.", shortMultiple),
                percent("maxBodyPercent", "Largest permitted real body", "The distance from open to close, divided by the candle's full high-to-low range.", "Lower this to require a smaller body relative to the candle's complete range.", max));
    }
    private static List<NumericDefinition> engulfing() {
        return List.of(
                percent("previousMinBodyPercent", "Minimum first-candle body", "The first candle's open-to-close body as a percentage of its full high-to-low range.", "Raise this to require a more decisive first candle.", 20),
                multiple("currentMinPreviousBodyMultiple", "Minimum second body versus first body", "The second candle's real body must be at least this many times the first candle's real body.", "Raise this to require the engulfing candle to overpower the first candle by a larger margin.", 1),
                percent("currentMinBodyPercent", "Minimum second-candle body", "The engulfing candle's open-to-close body as a percentage of its own full high-to-low range.", "Raise this to require a stronger directional engulfing candle.", 45));
    }
    private static List<NumericDefinition> reversalPair() {
        return List.of(
                percent("previousMinBodyPercent", "Minimum first-candle body", "The first candle's real body as a percentage of its full range.", "Raise this to require a stronger first candle.", 55),
                multiple("previousMinMedianMultiple", "First body versus recent median", "The first body must be at least this many times the median real-body size in the preceding candles.", "Raise this to make 'long first candle' more selective.", 1.1),
                percent("currentMinBodyPercent", "Minimum reversal-candle body", "The second candle's real body as a percentage of its own full range.", "Raise this to require a more forceful reversal candle.", 50),
                multiple("currentMinMedianMultiple", "Reversal body versus recent median", "The second body must be at least this many times the recent median real-body size.", "Raise this to demand a larger reversal body.", .9),
                percent("penetrationPercent", "Minimum close into the first body", "How far the second close must travel through the first candle's real body, measured from the reversal side.", "Raise this to require deeper reversal confirmation.", 50));
    }
    private static List<NumericDefinition> harami() {
        return List.of(
                percent("firstMinBodyPercent", "Minimum outside-candle body", "The first candle's real body as a percentage of its full range.", "Raise this to require a stronger outside candle.", 55),
                multiple("firstMinMedianMultiple", "Outside body versus recent median", "The first body must be at least this many times the median body of preceding candles.", "Raise this to make the required outside candle more exceptional.", 1.1),
                percent("secondMaxFirstBodyPercent", "Maximum inside body versus outside body", "The inside candle's body may be no more than this percentage of the first candle's body.", "Lower this to demand a more compact inside candle.", 45),
                multiple("secondMaxMedianMultiple", "Maximum inside body versus recent median", "The inside body may be no more than this many times the recent median body.", "Lower this to make the inside candle smaller relative to recent trading.", .75));
    }
    private static List<NumericDefinition> star() {
        return List.of(
                percent("firstMinBodyPercent", "Minimum first-candle body", "The first candle's body as a percentage of its complete high-to-low range.", "Raise this to require a more decisive opening candle.", 55),
                multiple("firstMinMedianMultiple", "First body versus recent median", "The first body must be at least this many times the median body in preceding candles.", "Raise this to make the first candle more exceptional.", 1.1),
                percent("middleMaxBodyPercent", "Maximum star-candle body", "The middle candle's body as a percentage of its own full range.", "Lower this to require a smaller, more indecisive middle candle.", 30),
                multiple("middleMaxMedianMultiple", "Star body versus recent median", "The middle body may be no more than this many times the preceding median body.", "Lower this to require stronger contraction in the middle candle.", .75),
                percent("currentMinBodyPercent", "Minimum confirmation-candle body", "The final candle's body as a percentage of its own full range.", "Raise this to require stronger reversal confirmation.", 50),
                multiple("currentMinMedianMultiple", "Confirmation body versus recent median", "The final body must be at least this many times the preceding median body.", "Raise this to demand a larger confirmation candle.", .9),
                percent("penetrationPercent", "Minimum close into the first body", "How far the final close must travel through the first candle's real body.", "Raise this to require deeper confirmation into the first candle.", 50));
    }
    private static List<NumericDefinition> threeCandleRun() {
        return List.of(
                percent("candleMinBodyPercent", "Minimum body for each of the three candles", "Every candle's real body as a percentage of that candle's full high-to-low range.", "Raise this to require all three candles to close more decisively.", 50),
                multiple("candleMinMedianMultiple", "Each body versus recent median", "Every body must be at least this many times the median body in preceding candles.", "Raise this to require consistently larger candles.", .8),
                multiple("maxDirectionalShadowBodyMultiple", "Maximum wick beyond each close", "The wick past each candle's close, in the direction opposite the run, may be no more than this many times its body.", "Lower this to require closes nearer each candle's extreme.", .3));
    }

    private static List<PatternDefinition> factoryDefinitions() {
        return List.of(
                p(CandlePattern.DOJI, "Doji", "One-candle indecision", "Open and close are nearly equal.", "The candle must have a non-zero high-to-low range.", TrendRequirement.NONE,
                        List.of(percent("maxBodyPercent", "Maximum real-body size", "The open-to-close distance as a percentage of the candle's full high-to-low range.", "Lower this to accept only thinner, more indecisive bodies.", 10))),
                p(CandlePattern.HAMMER, "Hammer", "One-candle bullish rejection", "A small body near the high with a long lower shadow after weakness.", "Body color is unrestricted; the lower shadow is the defining wick.", TrendRequirement.DOWN, wick(2,2,.5,30)),
                p(CandlePattern.INVERTED_HAMMER, "Inverted Hammer", "One-candle bullish rejection", "A small body near the low with a long upper shadow after weakness.", "Body color is unrestricted; the upper shadow is the defining wick.", TrendRequirement.DOWN, wick(2,2,.5,30)),
                p(CandlePattern.HANGING_MAN, "Hanging Man", "One-candle bearish warning", "A small body near the high with a long lower shadow after strength.", "Body color is unrestricted; the lower shadow is the defining wick.", TrendRequirement.UP, wick(2,2,.5,30)),
                p(CandlePattern.SHOOTING_STAR, "Shooting Star", "One-candle bearish rejection", "A small body near the low with a long upper shadow after strength.", "Body color is unrestricted; the upper shadow is the defining wick.", TrendRequirement.UP, wick(2,2,.5,30)),
                p(CandlePattern.BULLISH_ENGULFING, "Bullish Engulfing", "Two-candle bullish reversal", "A bullish second real body fully contains the prior bearish real body.", "The first candle must close below its open, the second above its open, and the second body must cross both first-body edges.", TrendRequirement.DOWN, engulfing()),
                p(CandlePattern.BEARISH_ENGULFING, "Bearish Engulfing", "Two-candle bearish reversal", "A bearish second real body fully contains the prior bullish real body.", "The first candle must close above its open, the second below its open, and the second body must cross both first-body edges.", TrendRequirement.UP, engulfing()),
                p(CandlePattern.PIERCING_LINE, "Piercing Line", "Two-candle bullish reversal", "A bullish candle opens below a long bearish candle and closes deeply inside its body.", "The first candle must be bearish; the second must be bullish and open below the first close without closing above the first open.", TrendRequirement.DOWN, reversalPair()),
                p(CandlePattern.DARK_CLOUD_COVER, "Dark Cloud Cover", "Two-candle bearish reversal", "A bearish candle opens above a long bullish candle and closes deeply inside its body.", "The first candle must be bullish; the second must be bearish and open above the first close without closing below the first open.", TrendRequirement.UP, reversalPair()),
                p(CandlePattern.BULLISH_HARAMI, "Bullish Harami", "Two-candle bullish reversal", "A small bullish body sits completely inside a long bearish real body.", "The first candle must be bearish, the second bullish, and both second-body edges must stay inside the first body.", TrendRequirement.DOWN, harami()),
                p(CandlePattern.BEARISH_HARAMI, "Bearish Harami", "Two-candle bearish reversal", "A small bearish body sits completely inside a long bullish real body.", "The first candle must be bullish, the second bearish, and both second-body edges must stay inside the first body.", TrendRequirement.UP, harami()),
                p(CandlePattern.MORNING_STAR, "Morning Star", "Three-candle bullish reversal", "A long bearish candle, a small star, then a strong bullish close into the first body.", "The first candle must be bearish and the final candle bullish.", TrendRequirement.DOWN, star()),
                p(CandlePattern.EVENING_STAR, "Evening Star", "Three-candle bearish reversal", "A long bullish candle, a small star, then a strong bearish close into the first body.", "The first candle must be bullish and the final candle bearish.", TrendRequirement.UP, star()),
                p(CandlePattern.THREE_WHITE_SOLDIERS, "Three White Soldiers", "Three-candle bullish sequence", "Three strong bullish candles with rising closes and compact upper shadows.", "All three candles must be bullish; closes must rise and each later open must remain inside the preceding real body.", TrendRequirement.DOWN_OR_BASE, threeCandleRun()),
                p(CandlePattern.THREE_BLACK_CROWS, "Three Black Crows", "Three-candle bearish sequence", "Three strong bearish candles with falling closes and compact lower shadows.", "All three candles must be bearish; closes must fall and each later open must remain inside the preceding real body.", TrendRequirement.UP, threeCandleRun()));
    }
    private static PatternDefinition p(CandlePattern pattern, String label, String formation, String description,
                                       String fixedRules, TrendRequirement trend, List<NumericDefinition> settings) {
        return new PatternDefinition(pattern, pattern.name().toLowerCase(Locale.ROOT), label, formation,
                description, fixedRules, trend, settings);
    }

    public enum TrendRequirement {
        NONE("No prior trend required"), DOWN("Required downtrend"), UP("Required uptrend"),
        DOWN_OR_BASE("Required downtrend or basing structure");
        private final String label;
        TrendRequirement(String label) { this.label = label; }
        public String label() { return label; }
    }
    public enum StopLossMode {
        STRUCTURAL_BUFFER("Textbook structural price + buffer",
                "Starts at the pattern's textbook invalidation high or low, then moves farther away by the selected percentage of entry price."),
        FIXED_ENTRY_PERCENT("Fixed percentage from entry",
                "Places the stop the selected adverse percentage away from the detection entry close.");
        private final String label;
        private final String description;
        StopLossMode(String label, String description) {
            this.label = label;
            this.description = description;
        }
        public String label() { return label; }
        public String description() { return description; }
    }
    public record NumericSetting(String key, String label, String description, String unit, String effect,
                                 double min, double max, double step, double value, double factoryValue) {}
    public record PatternProfile(CandlePattern pattern, String key, String label, String formation,
                                 String description, String fixedRules, TrendRequirement trendRequirement,
                                 TrendRequirement factoryTrendRequirement, List<NumericSetting> settings,
                                 StopLossMode stopLossMode, double stopLossValuePercent,
                                 boolean factoryProfile) {
        public PatternProfile(CandlePattern pattern, String key, String label, String formation,
                              String description, String fixedRules, TrendRequirement trendRequirement,
                              TrendRequirement factoryTrendRequirement, List<NumericSetting> settings,
                              boolean factoryProfile) {
            this(pattern, key, label, formation, description, fixedRules, trendRequirement,
                    factoryTrendRequirement, settings, StopLossMode.STRUCTURAL_BUFFER, 0.0,
                    factoryProfile);
        }
        public double value(String key) { return settings.stream().filter(item -> item.key().equals(key))
                .findFirst().orElseThrow().value(); }
        public double fraction(String key) { return value(key) / 100.0; }
        public boolean directional() { return pattern != CandlePattern.DOJI; }
        public String stopLossValueLabel() {
            return stopLossMode == StopLossMode.STRUCTURAL_BUFFER
                    ? "Additional buffer (%)" : "Distance from entry (%)";
        }
        public String factoryStopLossLabel() { return "Textbook structural price (0% buffer)"; }
    }
    public record PreferencesView(String version, boolean custom, List<PatternProfile> profiles,
                                  Map<TimeInterval, Double> rewardRiskRatios,
                                  Map<TimeInterval, CircuitBreakerSettings> circuitBreakers,
                                  Instant updatedAt) {
        public PreferencesView(String version, boolean custom, List<PatternProfile> profiles,
                               Map<TimeInterval, Double> rewardRiskRatios, Instant updatedAt) {
            this(version, custom, profiles, rewardRiskRatios, FACTORY_CIRCUIT_BREAKERS, updatedAt);
        }
        public PreferencesView(String version, boolean custom, List<PatternProfile> profiles,
                               Instant updatedAt) {
            this(version, custom, profiles, FACTORY_REWARD_RISK, FACTORY_CIRCUIT_BREAKERS, updatedAt);
        }
        public PreferencesView {
            profiles = List.copyOf(profiles);
            rewardRiskRatios = normalizeRewardRisk(rewardRiskRatios);
            circuitBreakers = normalizeCircuitBreakers(circuitBreakers);
        }
        public PatternProfile profile(CandlePattern pattern) { return profiles.stream().filter(item -> item.pattern() == pattern)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported candlestick pattern.")); }
        public double rewardRiskRatio(TimeInterval interval) {
            return rewardRiskRatios.getOrDefault(interval, FACTORY_REWARD_RISK.get(TimeInterval.DAILY));
        }
        public double factoryRewardRiskRatio(TimeInterval interval) {
            return FACTORY_REWARD_RISK.get(interval);
        }
        public CircuitBreakerSettings circuitBreaker(TimeInterval interval) {
            return circuitBreakers.getOrDefault(interval, FACTORY_CIRCUIT_BREAKERS.get(TimeInterval.DAILY));
        }
        public List<RewardRiskProfile> rewardRiskProfiles() {
            return List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY).stream()
                    .map(interval -> new RewardRiskProfile(
                            interval, interval.name().toLowerCase(Locale.ROOT), intervalLabel(interval),
                            rewardRiskRatio(interval), factoryRewardRiskRatio(interval)))
                    .toList();
        }
        public List<CircuitBreakerProfile> circuitBreakerProfiles() {
            return List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY).stream()
                    .map(interval -> {
                        CircuitBreakerSettings value = circuitBreaker(interval);
                        CircuitBreakerSettings factory = FACTORY_CIRCUIT_BREAKERS.get(interval);
                        return new CircuitBreakerProfile(
                                interval, interval.name().toLowerCase(Locale.ROOT), intervalLabel(interval),
                                value.enabled(), value.atrPeriod(), value.atrMultiplier(),
                                value.activationThresholdPercent(), factory);
                    }).toList();
        }
    }
    public record RewardRiskProfile(TimeInterval interval, String key, String label,
                                    double value, double factoryValue) {}
    public record CircuitBreakerSettings(boolean enabled, int atrPeriod, double atrMultiplier,
                                         double activationThresholdPercent) {}
    public record CircuitBreakerProfile(TimeInterval interval, String key, String label,
                                        boolean enabled, int atrPeriod, double atrMultiplier,
                                        double activationThresholdPercent,
                                        CircuitBreakerSettings factory) {}
    private record NumericDefinition(String key, String label, String description, String unit, String effect,
                                     double min, double max, double step, double factoryValue) {}
    private record PatternDefinition(CandlePattern pattern, String key, String label, String formation,
                                     String description, String fixedRules, TrendRequirement factoryTrendRequirement,
                                     List<NumericDefinition> settings) {}
    private record StoredProfile(CandlePattern pattern, TrendRequirement trendRequirement,
                                 Map<String, Double> values, StopLossMode stopLossMode,
                                 Double stopLossValuePercent) {}
    private record StoredPreferences(String version, List<StoredProfile> profiles,
                                     Map<TimeInterval, Double> rewardRiskRatios,
                                     Map<TimeInterval, CircuitBreakerSettings> circuitBreakers) {}
}
