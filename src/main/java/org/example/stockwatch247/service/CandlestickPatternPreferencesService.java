package org.example.stockwatch247.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserCandlestickPatternPreferences;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.repository.UserCandlestickPatternPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.*;

@Service
public class CandlestickPatternPreferencesService {
    public static final String PROFILE_VERSION = "USER_CANDLESTICK_PATTERNS_V1";
    private static final List<PatternDefinition> FACTORY = factoryDefinitions();
    private static final PreferencesView FACTORY_VIEW = materialize(
            FACTORY.stream().map(CandlestickPatternPreferencesService::stored).toList(), null);

    private final UserCandlestickPatternPreferencesRepository repository;
    private final ObjectMapper objectMapper;

    public CandlestickPatternPreferencesService(UserCandlestickPatternPreferencesRepository repository,
                                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PreferencesView get(User user) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        return repository.findByUser(user).map(this::read).orElseGet(CandlestickPatternPreferencesService::factoryPreferences);
    }

    @Transactional
    public PreferencesView save(User user, MultiValueMap<String, String> form) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        if (form == null) throw new IllegalArgumentException("Candlestick pattern settings are required.");
        List<StoredProfile> profiles = FACTORY.stream().map(definition -> parse(form, definition)).toList();
        return persist(user, profiles);
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
        return persist(user, profiles);
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
        return new StoredProfile(definition.pattern(), trend, values);
    }

    private PreferencesView persist(User user, List<StoredProfile> profiles) {
        validateStored(profiles);
        UserCandlestickPatternPreferences entity = repository.findByUser(user)
                .orElseGet(UserCandlestickPatternPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(new StoredPreferences(PROFILE_VERSION, profiles)));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return materialize(profiles, entity.getUpdatedAt());
    }

    private PreferencesView read(UserCandlestickPatternPreferences entity) {
        try {
            StoredPreferences stored = objectMapper.readValue(entity.getPreferencesPayload(), StoredPreferences.class);
            if (!PROFILE_VERSION.equals(stored.version())) return factoryPreferences();
            validateStored(stored.profiles());
            return materialize(stored.profiles(), entity.getUpdatedAt());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return factoryPreferences();
        }
    }

    private String write(StoredPreferences stored) {
        try { return objectMapper.writeValueAsString(stored); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not save candlestick settings.", exception); }
    }

    private static PreferencesView materialize(List<StoredProfile> storedProfiles, Instant updatedAt) {
        List<PatternProfile> profiles = FACTORY.stream().map(definition -> {
            StoredProfile stored = storedProfiles.stream().filter(item -> item.pattern() == definition.pattern())
                    .findFirst().orElseThrow();
            List<NumericSetting> settings = definition.settings().stream().map(item -> new NumericSetting(
                    item.key(), item.label(), item.description(), item.unit(), item.effect(), item.min(), item.max(),
                    item.step(), stored.values().get(item.key()), item.factoryValue())).toList();
            boolean factory = stored.trendRequirement() == definition.factoryTrendRequirement()
                    && definition.settings().stream().allMatch(item ->
                    Double.compare(stored.values().get(item.key()), item.factoryValue()) == 0);
            return new PatternProfile(definition.pattern(), definition.key(), definition.label(), definition.formation(),
                    definition.description(), definition.fixedRules(), stored.trendRequirement(),
                    definition.factoryTrendRequirement(), settings, factory);
        }).toList();
        return new PreferencesView(PROFILE_VERSION, profiles.stream().anyMatch(profile -> !profile.factoryProfile()),
                profiles, updatedAt);
    }

    private static void validateStored(List<StoredProfile> profiles) {
        if (profiles == null || profiles.size() != FACTORY.size())
            throw new IllegalArgumentException("Every candlestick pattern definition is required.");
        for (PatternDefinition definition : FACTORY) {
            StoredProfile profile = profiles.stream().filter(item -> item.pattern() == definition.pattern())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("A candlestick pattern definition is missing."));
            if (profile.trendRequirement() == null || profile.values() == null
                    || profile.values().size() != definition.settings().size())
                throw new IllegalArgumentException("An incomplete candlestick pattern definition was saved.");
            for (NumericDefinition numeric : definition.settings()) {
                Double value = profile.values().get(numeric.key());
                if (value == null || !Double.isFinite(value) || value < numeric.min() || value > numeric.max())
                    throw new IllegalArgumentException("A candlestick threshold is outside its supported range.");
            }
            validateRelationships(definition, profile.values());
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
        return new StoredProfile(definition.pattern(), definition.factoryTrendRequirement(), values);
    }
    private static StoredProfile stored(PatternProfile profile) {
        Map<String, Double> values = new LinkedHashMap<>();
        profile.settings().forEach(setting -> values.put(setting.key(), setting.value()));
        return new StoredProfile(profile.pattern(), profile.trendRequirement(), values);
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
    public record NumericSetting(String key, String label, String description, String unit, String effect,
                                 double min, double max, double step, double value, double factoryValue) {}
    public record PatternProfile(CandlePattern pattern, String key, String label, String formation,
                                 String description, String fixedRules, TrendRequirement trendRequirement,
                                 TrendRequirement factoryTrendRequirement, List<NumericSetting> settings,
                                 boolean factoryProfile) {
        public double value(String key) { return settings.stream().filter(item -> item.key().equals(key))
                .findFirst().orElseThrow().value(); }
        public double fraction(String key) { return value(key) / 100.0; }
    }
    public record PreferencesView(String version, boolean custom, List<PatternProfile> profiles, Instant updatedAt) {
        public PatternProfile profile(CandlePattern pattern) { return profiles.stream().filter(item -> item.pattern() == pattern)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported candlestick pattern.")); }
    }
    private record NumericDefinition(String key, String label, String description, String unit, String effect,
                                     double min, double max, double step, double factoryValue) {}
    private record PatternDefinition(CandlePattern pattern, String key, String label, String formation,
                                     String description, String fixedRules, TrendRequirement factoryTrendRequirement,
                                     List<NumericDefinition> settings) {}
    private record StoredProfile(CandlePattern pattern, TrendRequirement trendRequirement, Map<String, Double> values) {}
    private record StoredPreferences(String version, List<StoredProfile> profiles) {}
}
