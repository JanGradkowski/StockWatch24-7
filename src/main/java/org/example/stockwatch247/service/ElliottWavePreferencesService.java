package org.example.stockwatch247.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserElliottWavePreferences;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserElliottWavePreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.*;

@Service
public class ElliottWavePreferencesService {
    public static final String PROFILE_VERSION = "USER_ELLIOTT_DETECTION_V1";
    private static final List<ProfileDefinition> FACTORY = List.of(
            factory(TimeInterval.DAILY, "Daily", "One Elliott pivot represents completed daily price action."),
            factory(TimeInterval.WEEKLY, "Weekly", "One Elliott pivot represents completed weekly price action."),
            factory(TimeInterval.MONTHLY, "Monthly", "One Elliott pivot represents completed monthly price action."));
    private static final PreferencesView FACTORY_VIEW = materialize(
            FACTORY.stream().map(ElliottWavePreferencesService::stored).toList(), null);

    private final UserElliottWavePreferencesRepository repository;
    private final ObjectMapper objectMapper;

    public ElliottWavePreferencesService(UserElliottWavePreferencesRepository repository,
                                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PreferencesView get(User user) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        return repository.findByUser(user).map(this::read).orElseGet(ElliottWavePreferencesService::factoryPreferences);
    }

    @Transactional
    public PreferencesView save(User user, MultiValueMap<String, String> form) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        if (form == null) throw new IllegalArgumentException("Elliott Wave settings are required.");
        return persist(user, FACTORY.stream().map(definition -> parse(form, definition)).toList());
    }

    @Transactional
    public PreferencesView reset(User user, TimeInterval interval) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        if (interval == null) {
            repository.findByUser(user).ifPresent(repository::delete);
            return factoryPreferences();
        }
        if (interval != TimeInterval.DAILY
                && interval != TimeInterval.WEEKLY
                && interval != TimeInterval.MONTHLY) {
            throw new IllegalArgumentException("Elliott Wave rules are available for daily, weekly, and monthly intervals.");
        }
        PreferencesView current = get(user);
        List<StoredProfile> profiles = current.profiles().stream()
                .map(profile -> profile.interval() == interval
                        ? stored(definition(interval)) : stored(profile))
                .toList();
        return persist(user, profiles);
    }

    public static PreferencesView factoryPreferences() { return FACTORY_VIEW; }

    private StoredProfile parse(MultiValueMap<String, String> form, ProfileDefinition definition) {
        String prefix = definition.interval().name().toLowerCase(Locale.ROOT) + ".";
        Map<String, Double> numbers = new LinkedHashMap<>();
        for (NumericDefinition numeric : definition.numbers()) {
            String raw = required(form, prefix + numeric.key(), definition.label());
            double value;
            try { value = Double.parseDouble(raw); }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException(definition.label() + " — " + numeric.label() + " must be a number.");
            }
            if (!Double.isFinite(value) || value < numeric.min() || value > numeric.max()) {
                throw new IllegalArgumentException(definition.label() + " — " + numeric.label()
                        + " must be between " + display(numeric.min()) + " and " + display(numeric.max())
                        + numeric.unit() + ".");
            }
            numbers.put(numeric.key(), value);
        }
        Map<String, Boolean> switches = new LinkedHashMap<>();
        definition.switches().forEach(setting -> switches.put(setting.key(),
                form.containsKey(prefix + setting.key())));
        validateRelationships(definition.label(), numbers, switches);
        return new StoredProfile(definition.interval(), numbers, switches);
    }

    private PreferencesView persist(User user, List<StoredProfile> profiles) {
        validateStored(profiles);
        UserElliottWavePreferences entity = repository.findByUser(user)
                .orElseGet(UserElliottWavePreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(new StoredPreferences(PROFILE_VERSION, profiles)));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return materialize(profiles, entity.getUpdatedAt());
    }

    private PreferencesView read(UserElliottWavePreferences entity) {
        try {
            StoredPreferences stored = objectMapper.readValue(entity.getPreferencesPayload(), StoredPreferences.class);
            if (!PROFILE_VERSION.equals(stored.version())) return factoryPreferences();
            List<StoredProfile> normalized = normalizeStored(stored.profiles());
            validateStored(normalized);
            return materialize(normalized, entity.getUpdatedAt());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return factoryPreferences();
        }
    }

    private static List<StoredProfile> normalizeStored(List<StoredProfile> profiles) {
        if (profiles == null) return null;
        return FACTORY.stream().map(definition -> {
            StoredProfile profile = profiles.stream()
                    .filter(item -> item.interval() == definition.interval())
                    .findFirst().orElseGet(() -> stored(definition));
            Map<String, Double> numbers = profile.numbers() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(profile.numbers());
            definition.numbers().forEach(setting -> numbers.putIfAbsent(setting.key(), setting.factoryValue()));
            Map<String, Boolean> switches = profile.switches() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(profile.switches());
            definition.switches().forEach(setting -> switches.putIfAbsent(setting.key(), setting.factoryValue()));
            return new StoredProfile(profile.interval(), numbers, switches);
        }).toList();
    }

    private String write(StoredPreferences preferences) {
        try { return objectMapper.writeValueAsString(preferences); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not save Elliott Wave settings.", exception);
        }
    }

    private static PreferencesView materialize(List<StoredProfile> storedProfiles, Instant updatedAt) {
        List<IntervalProfile> profiles = FACTORY.stream().map(definition -> {
            StoredProfile stored = storedProfiles.stream()
                    .filter(item -> item.interval() == definition.interval()).findFirst().orElseThrow();
            List<NumericSetting> numbers = definition.numbers().stream().map(item -> new NumericSetting(
                    item.key(), item.section(), item.label(), item.description(), item.effect(), item.unit(),
                    item.min(), item.max(), item.step(), stored.numbers().get(item.key()), item.factoryValue())).toList();
            List<BooleanSetting> switches = definition.switches().stream().map(item -> new BooleanSetting(
                    item.key(), item.section(), item.label(), item.description(), item.enabledEffect(),
                    stored.switches().get(item.key()), item.factoryValue())).toList();
            boolean factory = numbers.stream().allMatch(item -> Double.compare(item.value(), item.factoryValue()) == 0)
                    && switches.stream().allMatch(item -> item.value() == item.factoryValue());
            return new IntervalProfile(definition.interval(), definition.key(), definition.label(),
                    definition.description(), numbers, switches, factory,
                    factory ? ElliottWaveDetectionService.DetectionRules.factory() : rules(stored));
        }).toList();
        return new PreferencesView(PROFILE_VERSION, profiles.stream().anyMatch(profile -> !profile.factoryProfile()),
                profiles, updatedAt);
    }

    private static void validateStored(List<StoredProfile> profiles) {
        if (profiles == null || profiles.size() != FACTORY.size()) {
            throw new IllegalArgumentException("Daily, weekly, and monthly Elliott Wave profiles are required.");
        }
        for (ProfileDefinition definition : FACTORY) {
            StoredProfile profile = profiles.stream().filter(item -> item.interval() == definition.interval())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("An Elliott Wave interval is missing."));
            if (profile.numbers() == null || profile.switches() == null
                    || profile.numbers().size() != definition.numbers().size()
                    || profile.switches().size() != definition.switches().size()) {
                throw new IllegalArgumentException("An incomplete Elliott Wave profile was saved.");
            }
            for (NumericDefinition numeric : definition.numbers()) {
                Double value = profile.numbers().get(numeric.key());
                if (value == null || !Double.isFinite(value) || value < numeric.min() || value > numeric.max()) {
                    throw new IllegalArgumentException("An Elliott Wave value is outside its supported range.");
                }
            }
            if (definition.switches().stream().anyMatch(setting -> !profile.switches().containsKey(setting.key()))) {
                throw new IllegalArgumentException("An Elliott Wave structural switch is missing.");
            }
            validateRelationships(definition.label(), profile.numbers(), profile.switches());
        }
    }

    private static void validateRelationships(String label, Map<String, Double> n, Map<String, Boolean> b) {
        range(label, "Wave II preferred retracement", n, "waveTwoPreferredMinPercent", "waveTwoPreferredMaxPercent");
        range(label, "Wave II common retracement", n, "waveTwoCommonMinPercent", "waveTwoCommonMaxPercent");
        contained(label, "Wave II common retracement", n, "waveTwoCommonMinPercent", "waveTwoCommonMaxPercent",
                "waveTwoPreferredMinPercent", "waveTwoPreferredMaxPercent");
        range(label, "Wave IV preferred retracement", n, "waveFourPreferredMinPercent", "waveFourPreferredMaxPercent");
        range(label, "Wave IV common retracement", n, "waveFourCommonMinPercent", "waveFourCommonMaxPercent");
        contained(label, "Wave IV common retracement", n, "waveFourCommonMinPercent", "waveFourCommonMaxPercent",
                "waveFourPreferredMinPercent", "waveFourPreferredMaxPercent");
        range(label, "A–B–C preferred retracement", n, "correctionPreferredMinPercent", "correctionPreferredMaxPercent");
        range(label, "A–B–C common retracement", n, "correctionCommonMinPercent", "correctionCommonMaxPercent");
        contained(label, "A–B–C common retracement", n, "correctionCommonMinPercent", "correctionCommonMaxPercent",
                "correctionPreferredMinPercent", "correctionPreferredMaxPercent");
        range(label, "Wave C versus Wave A preferred ratio", n, "waveCPreferredMinPercent", "waveCPreferredMaxPercent");
        range(label, "Wave C versus Wave A common ratio", n, "waveCCommonMinPercent", "waveCCommonMaxPercent");
        contained(label, "Wave C versus Wave A common ratio", n, "waveCCommonMinPercent", "waveCCommonMaxPercent",
                "waveCPreferredMinPercent", "waveCPreferredMaxPercent");
        range(label, "Allowed Wave C versus Wave A ratio", n, "waveCAllowedMinPercent", "waveCAllowedMaxPercent");
        if (n.get("waveTwoPreferredMaxPercent") >= n.get("waveTwoMaximumPercent")) {
            throw new IllegalArgumentException(label + ": the Wave II preferred maximum must remain below the absolute Wave II maximum.");
        }
        if (n.get("waveFourPreferredMaxPercent") >= n.get("waveFourMaximumPercent")) {
            throw new IllegalArgumentException(label + ": the Wave IV preferred maximum must remain below the absolute Wave IV maximum.");
        }
        if (n.get("correctionPreferredMaxPercent") >= n.get("correctionMaximumPercent")) {
            throw new IllegalArgumentException(label + ": the preferred A–B–C maximum must remain below the absolute correction maximum.");
        }
        if (n.get("minimumStructureQuality") > n.get("minimumSignalConfidence")) {
            throw new IllegalArgumentException(label + ": minimum structure quality cannot exceed minimum alert confidence.");
        }
        if (n.get("minimumImpulseSpanCandles") < n.get("minimumLegSpanCandles") * 5) {
            throw new IllegalArgumentException(label + ": the full I–V span must allow five minimum-length wave legs.");
        }
        double previous = 0;
        for (int index = 1; index <= 4; index++) {
            double current = n.get("pivotSensitivity" + index);
            if (current <= previous) {
                throw new IllegalArgumentException(label + ": pivot sensitivity levels must increase from level 1 through level 4.");
            }
            previous = current;
        }
        if (!b.get("allowStandardCorrection") && !b.get("allowExpandedFlat") && !b.get("allowRunningFlat")) {
            throw new IllegalArgumentException(label + ": enable at least one A–B–C correction shape.");
        }
    }

    private static void range(String label, String name, Map<String, Double> values, String min, String max) {
        if (values.get(min) >= values.get(max)) {
            throw new IllegalArgumentException(label + ": " + name + " minimum must be below its maximum.");
        }
    }

    private static void contained(String label, String name, Map<String, Double> values,
                                  String innerMin, String innerMax, String outerMin, String outerMax) {
        if (values.get(innerMin) < values.get(outerMin) || values.get(innerMax) > values.get(outerMax)) {
            throw new IllegalArgumentException(label + ": " + name + " must stay inside the wider preferred range.");
        }
    }

    private static ElliottWaveDetectionService.DetectionRules rules(StoredProfile stored) {
        Map<String, Double> n = stored.numbers();
        Map<String, Boolean> b = stored.switches();
        return new ElliottWaveDetectionService.DetectionRules(
                integer(n, "minimumCandles"), integer(n, "minimumSignalConfidence"),
                integer(n, "minimumStructureQuality"), integer(n, "maximumConfirmationLagCandles"),
                integer(n, "minimumImpulseSpanCandles"), integer(n, "minimumLegSpanCandles"),
                fraction(n, "breakoutBufferPercent"), fraction(n, "reversalFloorPercent"),
                List.of(n.get("pivotSensitivity1"), n.get("pivotSensitivity2"),
                        n.get("pivotSensitivity3"), n.get("pivotSensitivity4")),
                fraction(n, "maximumStructureOverlapPercent"),
                fraction(n, "waveTwoMaximumPercent"), fraction(n, "waveTwoPreferredMinPercent"),
                fraction(n, "waveTwoPreferredMaxPercent"), fraction(n, "waveTwoCommonMinPercent"),
                fraction(n, "waveTwoCommonMaxPercent"), fraction(n, "waveThreePreliminaryMinimumPercent"),
                fraction(n, "waveFourMaximumPercent"), fraction(n, "waveFourPreferredMinPercent"),
                fraction(n, "waveFourPreferredMaxPercent"), fraction(n, "waveFourCommonMinPercent"),
                fraction(n, "waveFourCommonMaxPercent"), b.get("requireWaveFourNoOverlap"),
                b.get("requireWaveThreeNotShortest"), b.get("allowWaveOneLongest"),
                b.get("requireWaveOneShortest"), b.get("allowWaveFiveLongest"),
                b.get("allowTruncatedFifth"),
                fraction(n, "correctionMaximumPercent"), fraction(n, "waveBMaximumRecoveryPercent"),
                fraction(n, "correctionPreferredMinPercent"), fraction(n, "correctionPreferredMaxPercent"),
                fraction(n, "correctionCommonMinPercent"), fraction(n, "correctionCommonMaxPercent"),
                fraction(n, "waveCPreferredMinPercent"), fraction(n, "waveCPreferredMaxPercent"),
                fraction(n, "waveCCommonMinPercent"), fraction(n, "waveCCommonMaxPercent"),
                b.get("requireWaveAWithinOrigin"), b.get("allowStandardCorrection"),
                b.get("allowExpandedFlat"), b.get("allowRunningFlat"), b.get("allowTriangles"),
                b.get("limitWaveCToARatio"), fraction(n, "waveCAllowedMinPercent"),
                fraction(n, "waveCAllowedMaxPercent"));
    }

    private static int integer(Map<String, Double> values, String key) { return (int) Math.round(values.get(key)); }
    private static double fraction(Map<String, Double> values, String key) { return values.get(key) / 100.0; }
    private static String required(MultiValueMap<String, String> form, String key, String label) {
        String value = form.getFirst(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + ": every numeric rule must be submitted.");
        return value.trim();
    }
    private static String display(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }
    private static ProfileDefinition definition(TimeInterval interval) {
        return FACTORY.stream().filter(item -> item.interval() == interval).findFirst().orElseThrow();
    }

    private static StoredProfile stored(ProfileDefinition definition) {
        Map<String, Double> numbers = new LinkedHashMap<>();
        definition.numbers().forEach(setting -> numbers.put(setting.key(), setting.factoryValue()));
        Map<String, Boolean> switches = new LinkedHashMap<>();
        definition.switches().forEach(setting -> switches.put(setting.key(), setting.factoryValue()));
        return new StoredProfile(definition.interval(), numbers, switches);
    }

    private static StoredProfile stored(IntervalProfile profile) {
        Map<String, Double> numbers = new LinkedHashMap<>();
        profile.numbers().forEach(setting -> numbers.put(setting.key(), setting.value()));
        Map<String, Boolean> switches = new LinkedHashMap<>();
        profile.switches().forEach(setting -> switches.put(setting.key(), setting.value()));
        return new StoredProfile(profile.interval(), numbers, switches);
    }

    private static ProfileDefinition factory(TimeInterval interval, String label, String description) {
        List<NumericDefinition> numbers = List.of(
                whole("minimumCandles", Section.PIVOTS, "Minimum completed candles", "How much completed history must exist before Elliott detection runs.", "Raise this to require more context before any structure is considered.", 20, 500, 1, 34),
                whole("minimumStructureQuality", Section.PIVOTS, "Minimum structure quality", "The lowest internal structural-quality score allowed onto charts and historical reconstruction.", "Raise this to hide weaker but structurally valid counts.", 0, 100, 1, 68),
                whole("minimumSignalConfidence", Section.PIVOTS, "Minimum alert confidence", "The minimum confidence required before a detected Elliott turning point can produce an alert.", "Raise this to reduce alerts without removing lower-quality chart overlays.", 0, 100, 1, 75),
                whole("maximumConfirmationLagCandles", Section.PIVOTS, "Maximum confirmation delay", "How many completed candles may pass after Wave V or C ends before its reversal confirmation becomes too late.", "Lower this to require a faster reversal after the endpoint.", 1, 12, 1, 3),
                whole("minimumImpulseSpanCandles", Section.PIVOTS, "Minimum complete I–V duration", "The minimum number of interval candles from the impulse origin through Wave V.", "Raise this to reject compressed five-wave counts.", 5, 100, 1, 15),
                whole("minimumLegSpanCandles", Section.PIVOTS, "Minimum candles in each wave leg", "The minimum number of interval candles between two consecutive Elliott pivots.", "Raise this to prevent very short price swings from becoming separate waves.", 1, 20, 1, 2),
                percent("breakoutBufferPercent", Section.PIVOTS, "Wave V breakout buffer", "How far price must close beyond Wave III before a developing Wave V breakout is confirmed.", "Raise this to require a more decisive break beyond Wave III.", 0, 10, .05, .3),
                percent("reversalFloorPercent", Section.PIVOTS, "Minimum pivot reversal floor", "The percentage of price used as the minimum reversal distance when ATR is smaller.", "Raise this to create fewer, larger swing pivots.", .1, 10, .05, 1.25),
                multiple("pivotSensitivity1", Section.PIVOTS, "Pivot sensitivity — finest", "ATR and reversal-floor multiplier for the most detailed pivot count.", "Raise this to ignore more small price swings.", .1, 10, .05, .75),
                multiple("pivotSensitivity2", Section.PIVOTS, "Pivot sensitivity — detailed", "ATR and reversal-floor multiplier for the second pivot count.", "Raise this to require larger reversals at this degree.", .1, 10, .05, 1.25),
                multiple("pivotSensitivity3", Section.PIVOTS, "Pivot sensitivity — broad", "ATR and reversal-floor multiplier for the third pivot count.", "Raise this to retain only broader reversals.", .1, 10, .05, 2),
                multiple("pivotSensitivity4", Section.PIVOTS, "Pivot sensitivity — broadest", "ATR and reversal-floor multiplier for the least detailed pivot count.", "Raise this to retain only the largest reversals.", .1, 10, .05, 3),
                percent("maximumStructureOverlapPercent", Section.PIVOTS, "Maximum overlap between displayed counts", "The share of the shorter structure that may overlap an already selected Elliott structure.", "Lower this to show fewer competing structures over the same history.", 0, 100, 1, 40),

                percent("waveTwoMaximumPercent", Section.MOTIVE, "Absolute maximum Wave II retracement", "Wave II depth measured against Wave I. At 100%, Wave II may approach but may not erase the Wave I origin.", "Lower this to reject deeper Wave II candidates.", 1, 150, .5, 100),
                percent("waveTwoPreferredMinPercent", Section.MOTIVE, "Preferred Wave II — minimum", "Lower edge of the wider Wave II range that receives normal structural treatment.", "Raise this to treat shallow Wave II retracements as lower quality.", 0, 100, .1, 23.6),
                percent("waveTwoPreferredMaxPercent", Section.MOTIVE, "Preferred Wave II — maximum", "Upper edge of the wider Wave II range that receives normal structural treatment.", "Lower this to treat deep Wave II retracements as lower quality.", 1, 149, .1, 78.6),
                percent("waveTwoCommonMinPercent", Section.MOTIVE, "Common Fibonacci Wave II — minimum", "Lower edge of the narrower Wave II zone that receives an additional quality bonus.", "Raise this to narrow the common Fibonacci zone from below.", 0, 100, .1, 38.2),
                percent("waveTwoCommonMaxPercent", Section.MOTIVE, "Common Fibonacci Wave II — maximum", "Upper edge of the narrower Wave II zone that receives an additional quality bonus.", "Lower this to narrow the common Fibonacci zone from above.", 1, 149, .1, 61.8),
                percent("waveThreePreliminaryMinimumPercent", Section.MOTIVE, "Developing Wave III versus Wave I", "Before Wave V is complete, Wave III length divided by Wave I length. Shorter values reduce confidence.", "Raise this to penalize a comparatively short developing Wave III sooner.", 1, 300, 1, 80),
                percent("waveFourMaximumPercent", Section.MOTIVE, "Absolute maximum Wave IV retracement", "Wave IV depth measured against Wave III.", "Lower this to reject deeper Wave IV candidates.", 1, 150, .5, 100),
                percent("waveFourPreferredMinPercent", Section.MOTIVE, "Preferred Wave IV — minimum", "Lower edge of the wider Wave IV range that receives normal structural treatment.", "Raise this to treat very shallow Wave IV retracements as lower quality.", 0, 100, .1, 14.6),
                percent("waveFourPreferredMaxPercent", Section.MOTIVE, "Preferred Wave IV — maximum", "Upper edge of the wider Wave IV range that receives normal structural treatment.", "Lower this to treat deep Wave IV retracements as lower quality.", 1, 149, .1, 61.8),
                percent("waveFourCommonMinPercent", Section.MOTIVE, "Common Fibonacci Wave IV — minimum", "Lower edge of the narrower Wave IV bonus zone.", "Raise this to narrow the common Fibonacci zone from below.", 0, 100, .1, 23.6),
                percent("waveFourCommonMaxPercent", Section.MOTIVE, "Common Fibonacci Wave IV — maximum", "Upper edge of the narrower Wave IV bonus zone.", "Lower this to narrow the common Fibonacci zone from above.", 1, 149, .1, 50),

                percent("correctionMaximumPercent", Section.CORRECTIVE, "Absolute maximum A–B–C retracement", "The distance from Wave V to Wave C divided by the complete impulse move from origin to Wave V.", "Lower this to reject corrections that retrace more of the preceding impulse.", 1, 200, .5, 100),
                percent("waveBMaximumRecoveryPercent", Section.CORRECTIVE, "Maximum Wave B recovery", "Wave B length from A divided by Wave A length. Values above 100% allow B to pass the Wave V endpoint.", "Lower this to reject more expanded and running-flat candidates.", 1, 500, 1, 200),
                percent("correctionPreferredMinPercent", Section.CORRECTIVE, "Preferred full correction — minimum", "Lower edge of the wider origin-to-V retracement range for the completed A–B–C.", "Raise this to reduce the quality of shallow corrections.", 0, 199, .1, 23.6),
                percent("correctionPreferredMaxPercent", Section.CORRECTIVE, "Preferred full correction — maximum", "Upper edge of the wider origin-to-V retracement range for the completed A–B–C.", "Lower this to reduce the quality of deep corrections.", 1, 199, .1, 78.6),
                percent("correctionCommonMinPercent", Section.CORRECTIVE, "Common correction zone — minimum", "Lower edge of the narrower completed-correction bonus zone.", "Raise this to narrow the common correction zone from below.", 0, 199, .1, 38.2),
                percent("correctionCommonMaxPercent", Section.CORRECTIVE, "Common correction zone — maximum", "Upper edge of the narrower completed-correction bonus zone.", "Lower this to narrow the common correction zone from above.", 1, 199, .1, 61.8),
                percent("waveCPreferredMinPercent", Section.CORRECTIVE, "Preferred Wave C versus Wave A — minimum", "Wave C length divided by Wave A length; 50% means C is half the length of A.", "Raise this to reduce the quality of short Wave C candidates.", 0, 1000, 1, 50),
                percent("waveCPreferredMaxPercent", Section.CORRECTIVE, "Preferred Wave C versus Wave A — maximum", "Upper edge of the wider proportionate Wave C range.", "Lower this to reduce the quality of extended Wave C candidates.", 1, 1000, 1, 200),
                percent("waveCCommonMinPercent", Section.CORRECTIVE, "Near-equality C:A — minimum", "Lower edge of the narrower range where Wave C is considered near equality with Wave A.", "Raise this to demand closer equality from below.", 0, 1000, 1, 80),
                percent("waveCCommonMaxPercent", Section.CORRECTIVE, "Near-equality C:A — maximum", "Upper edge of the narrower range where Wave C is considered near equality with Wave A.", "Lower this to demand closer equality from above.", 1, 1000, 1, 125),
                percent("waveCAllowedMinPercent", Section.CORRECTIVE, "Allowed C:A ratio — minimum", "Hard lower Wave C length when the optional C:A limit switch is enabled.", "Raise this to reject corrections with a shorter Wave C.", 0, 1000, 1, 10),
                percent("waveCAllowedMaxPercent", Section.CORRECTIVE, "Allowed C:A ratio — maximum", "Hard upper Wave C length when the optional C:A limit switch is enabled.", "Lower this to reject corrections with a more extended Wave C.", 1, 1000, 1, 500));

        List<BooleanDefinition> switches = List.of(
                toggle("requireWaveFourNoOverlap", Section.MOTIVE, "Require Wave IV not to overlap Wave I", "When enabled, Wave IV must remain beyond the Wave I endpoint in the impulse direction.", "This is the standard non-diagonal impulse rule.", true),
                toggle("requireWaveThreeNotShortest", Section.MOTIVE, "Require Wave III not to be the shortest", "Compares the price lengths of Waves I, III, and V after Wave V is available.", "Enabled enforces the conventional Elliott hard rule.", true),
                toggle("allowWaveOneLongest", Section.MOTIVE, "Allow Wave I to be the longest actionary wave", "If disabled, Wave I must not be longer than both Waves III and V. This option is ignored when the stricter shortest-Wave-I rule is enabled.", "Enabled reflects conventional Elliott theory: Wave I may be longest as long as Wave III is not shortest.", true),
                toggle("requireWaveOneShortest", Section.MOTIVE, "Require Wave I to be the shortest actionary wave", "Once the Wave V endpoint is available, compares the absolute price lengths of Waves I, III, and V. Wave I must be no longer than both Wave III and Wave V; equal-length ties are accepted.", "Enabled rejects completed counts that violate this strict rule and overrides the permissive ‘Allow Wave I to be longest’ option. Expect substantially fewer detections.", false),
                toggle("allowWaveFiveLongest", Section.MOTIVE, "Allow Wave V to be the longest actionary wave", "If disabled, Wave V must not be longer than both Waves I and III.", "Enabled permits extended fifth waves.", true),
                toggle("allowTruncatedFifth", Section.MOTIVE, "Allow truncated Wave V", "A truncated bullish V may end at or below Wave III; a bearish V may end at or above Wave III.", "Disable this to require Wave V to exceed the Wave III extreme.", true),
                toggle("requireWaveAWithinOrigin", Section.CORRECTIVE, "Require Wave A to stay inside the impulse origin", "Wave A may retrace the impulse but may not cross the point where Wave I began.", "Enabled prevents the first corrective leg from invalidating the entire preceding impulse.", true),
                toggle("allowStandardCorrection", Section.CORRECTIVE, "Allow standard A–B–C corrections", "Wave B stays inside the Wave V endpoint.", "Disable this to accept only enabled flat variants.", true),
                toggle("allowExpandedFlat", Section.CORRECTIVE, "Allow expanded flats", "Wave B passes Wave V and Wave C passes the Wave A extreme.", "Disable this to reject expanded-flat geometry.", true),
                toggle("allowRunningFlat", Section.CORRECTIVE, "Allow running flats", "Wave B passes Wave V but Wave C does not pass the Wave A extreme.", "Disable this to reject running-flat geometry.", true),
                toggle("allowTriangles", Section.CORRECTIVE, "Allow A–B–C–D–E triangle subwaves", "Permits contracting triangle counts inside Wave IV, B, or equivalent corrective parent waves.", "Disable this to show only three-leg corrective subdivisions.", true),
                toggle("limitWaveCToARatio", Section.CORRECTIVE, "Enforce the allowed Wave C versus Wave A range", "Turns the allowed C:A minimum and maximum into hard rejection rules instead of quality guidance.", "Enable this when you want disproportionate C legs rejected completely.", false));
        return new ProfileDefinition(interval, interval.name().toLowerCase(Locale.ROOT), label, description, numbers, switches);
    }

    private static NumericDefinition whole(String key, Section section, String label, String description,
                                           String effect, double min, double max, double step, double value) {
        return new NumericDefinition(key, section, label, description, effect, "", min, max, step, value);
    }
    private static NumericDefinition percent(String key, Section section, String label, String description,
                                             String effect, double min, double max, double step, double value) {
        return new NumericDefinition(key, section, label, description, effect, "%", min, max, step, value);
    }
    private static NumericDefinition multiple(String key, Section section, String label, String description,
                                              String effect, double min, double max, double step, double value) {
        return new NumericDefinition(key, section, label, description, effect, "×", min, max, step, value);
    }
    private static BooleanDefinition toggle(String key, Section section, String label, String description,
                                            String enabledEffect, boolean value) {
        return new BooleanDefinition(key, section, label, description, enabledEffect, value);
    }

    public enum Section {
        PIVOTS("Pivot construction and confirmation"),
        MOTIVE("Motive I–II–III–IV–V definition"),
        CORRECTIVE("Corrective A–B–C definition");
        private final String label;
        Section(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record NumericSetting(String key, Section section, String label, String description, String effect,
                                 String unit, double min, double max, double step, double value, double factoryValue) {}
    public record BooleanSetting(String key, Section section, String label, String description,
                                 String enabledEffect, boolean value, boolean factoryValue) {}
    public record IntervalProfile(TimeInterval interval, String key, String label, String description,
                                  List<NumericSetting> numbers, List<BooleanSetting> switches,
                                  boolean factoryProfile, ElliottWaveDetectionService.DetectionRules rules) {
        public List<NumericSetting> numbers(Section section) {
            return numbers.stream().filter(item -> item.section() == section).toList();
        }
        public List<BooleanSetting> switches(Section section) {
            return switches.stream().filter(item -> item.section() == section).toList();
        }
    }
    public record PreferencesView(String version, boolean custom, List<IntervalProfile> profiles, Instant updatedAt) {
        public IntervalProfile profile(TimeInterval interval) {
            return profiles.stream().filter(item -> item.interval() == interval).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Elliott Wave detection supports daily, weekly, and monthly intervals."));
        }
    }
    private record NumericDefinition(String key, Section section, String label, String description, String effect,
                                     String unit, double min, double max, double step, double factoryValue) {}
    private record BooleanDefinition(String key, Section section, String label, String description,
                                     String enabledEffect, boolean factoryValue) {}
    private record ProfileDefinition(TimeInterval interval, String key, String label, String description,
                                     List<NumericDefinition> numbers, List<BooleanDefinition> switches) {}
    private record StoredProfile(TimeInterval interval, Map<String, Double> numbers, Map<String, Boolean> switches) {}
    private record StoredPreferences(String version, List<StoredProfile> profiles) {}
}
