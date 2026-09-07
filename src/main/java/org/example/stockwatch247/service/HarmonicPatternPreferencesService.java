package org.example.stockwatch247.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserHarmonicPatternPreferences;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.repository.UserHarmonicPatternPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class HarmonicPatternPreferencesService {
    public static final String PROFILE_VERSION = "USER_HARMONIC_RULES_V3";
    private static final String LEGACY_PROFILE_VERSION_V1 = "USER_HARMONIC_RULES_V1";
    private static final String LEGACY_PROFILE_VERSION_V2 = "USER_HARMONIC_RULES_V2";

    private static final List<NumericDefinition> GLOBALS = List.of(
            number("fibonacciTolerancePercent", "Base Fibonacci tolerance", "Variance allowed around exact Fibonacci targets and range boundaries.", "%", .5, 10, .1, 3),
            number("legEqualityTolerancePercent", "AB/CD target tolerance", "Variance allowed around the AB=CD and 1.27 alternate AB=CD targets.", "%", 1, 20, .1, 3),
            number("materialLegDifferencePercent", "Crab AB/CD separation", "Minimum AB versus CD difference required by the Crab structure.", "%", 2, 40, .1, 10),
            number("minimumSwingPercent", "Minimum pivot-to-pivot move", "Filters tiny alternating swings before harmonic classification.", "%", 0, 25, .1, .5),
            number("pivotWindow", "Pivot confirmation candles", "Completed candles required on both sides of a swing endpoint.", "candles", 1, 10, 1, 2),
            number("maximumFormations", "Maximum overlay formations", "Newest formations retained in one historical overlay response.", "formations", 1, 250, 1, 250),
            number("maximumPivotWindow", "Largest structural pivot window", "Longest left/right candle window used to retain broad structural extrema.", "candles", 2, 89, 1, 55),
            number("maximumSwingPercent", "Largest swing hierarchy", "Maximum price reversal scale used to suppress counter-swings inside long formations.", "%", 5, 50, .5, 34),
            number("maximumSkippedPivots", "Internal pivots allowed", "Advanced override for lower-level pivots XABCD may skip. The textbook-strict default requires consecutive pivots at one hierarchy level.", "pivots", 0, 8, 1, 0)
    );

    private static final Map<HarmonicPatternType, PatternDefinition> PATTERNS = definitions();

    private final UserHarmonicPatternPreferencesRepository repository;
    private final ObjectMapper objectMapper;

    public HarmonicPatternPreferencesService(UserHarmonicPatternPreferencesRepository repository,
                                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PreferencesView get(User user) {
        return AnalysisComputationScope.memo(java.util.List.of(HarmonicPatternPreferencesService.class,
                user == null ? "factory" : user.getId() == null ? user : user.getId()), () -> loadPreferences(user));
    }

    private PreferencesView loadPreferences(User user) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        return repository.findByUser(user).map(this::read).orElseGet(HarmonicPatternPreferencesService::factoryPreferences);
    }

    @Transactional
    public PreferencesView save(User user, MultiValueMap<String, String> form) {
        if (user == null || form == null) throw new IllegalArgumentException("Harmonic preferences are required.");
        Map<String, Double> globals = new LinkedHashMap<>();
        for (NumericDefinition definition : GLOBALS) {
            globals.put(definition.key(), decimal(form, "global." + definition.key(), definition));
        }
        List<StoredPattern> patterns = new ArrayList<>();
        for (PatternDefinition definition : PATTERNS.values()) {
            String prefix = "pattern." + definition.key() + ".";
            Map<String, Double> ratios = new LinkedHashMap<>();
            for (NumericDefinition ratio : definition.ratios()) {
                ratios.put(ratio.key(), decimal(form, prefix + ratio.key(), ratio));
            }
            Map<HarmonicPatternDetectionService.RuleGroup, Boolean> hardRules = new EnumMap<>(HarmonicPatternDetectionService.RuleGroup.class);
            for (HardRuleDefinition hardRule : definition.hardRules()) {
                hardRules.put(hardRule.group(), checked(form, prefix + "hard." + hardRule.group().name()));
            }
            patterns.add(new StoredPattern(definition.pattern(), checked(form, prefix + "enabled"),
                    decimal(form, prefix + "softViolationPercent", SOFT_TOLERANCE), ratios, hardRules));
        }
        StoredPreferences stored = new StoredPreferences(PROFILE_VERSION, globals, patterns);
        stored.validate();
        return persist(user, stored);
    }

    @Transactional
    public PreferencesView reset(User user, HarmonicPatternType pattern) {
        if (pattern == null) {
            repository.findByUser(user).ifPresent(repository::delete);
            return factoryPreferences();
        }
        StoredPreferences current = stored(get(user));
        List<StoredPattern> patterns = current.patterns().stream()
                .map(item -> item.pattern() == pattern ? factoryPattern(PATTERNS.get(pattern)) : item)
                .toList();
        return persist(user, new StoredPreferences(PROFILE_VERSION, current.globals(), patterns));
    }

    public HarmonicPatternDetectionService detector(User user,
                                                     HarmonicPatternDetectionService fallback) {
        PreferencesView preferences = get(user);
        return (fallback == null ? new HarmonicPatternDetectionService() : fallback)
                .configured(preferences.rules(), preferences.patternRules());
    }

    private PreferencesView persist(User user, StoredPreferences stored) {
        stored.validate();
        UserHarmonicPatternPreferences entity = repository.findByUser(user)
                .orElseGet(UserHarmonicPatternPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(stored));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return view(stored, entity.getUpdatedAt());
    }

    private PreferencesView read(UserHarmonicPatternPreferences entity) {
        try {
            StoredPreferences stored = objectMapper.readValue(entity.getPreferencesPayload(), StoredPreferences.class);
            if (LEGACY_PROFILE_VERSION_V1.equals(stored.version())) stored = migrateLegacy(stored, true);
            else if (LEGACY_PROFILE_VERSION_V2.equals(stored.version())) stored = migrateLegacy(stored, false);
            stored.validate();
            return view(stored, entity.getUpdatedAt());
        } catch (JacksonException | IllegalArgumentException exception) {
            return factoryPreferences();
        }
    }

    private StoredPreferences migrateLegacy(StoredPreferences legacy, boolean correctV1Ratios) {
        Map<String, Double> globals = new LinkedHashMap<>();
        GLOBALS.forEach(definition -> globals.put(definition.key(),
                legacy.globals().getOrDefault(definition.key(), definition.factoryValue())));
        if (globals.getOrDefault("maximumFormations", 40.0) == 40.0) {
            globals.put("maximumFormations", 250.0);
        }
        List<StoredPattern> patterns = new ArrayList<>();
        for (PatternDefinition definition : PATTERNS.values()) {
            StoredPattern old = legacy.patterns().stream()
                    .filter(pattern -> pattern.pattern() == definition.pattern())
                    .findFirst().orElse(null);
            if (old == null) {
                patterns.add(factoryPattern(definition));
                continue;
            }
            Map<String, Double> ratios = new LinkedHashMap<>();
            for (NumericDefinition ratio : definition.ratios()) {
                double value = old.ratios().getOrDefault(ratio.key(), ratio.factoryValue());
                if (correctV1Ratios && correctedLegacyKey(definition.pattern(), ratio.key())) {
                    value = ratio.factoryValue();
                }
                ratios.put(ratio.key(), value);
            }
            if (correctV1Ratios) {
                copyLegacyAlias(old.ratios(), ratios, definition.pattern(), "bTarget", "bPrimary");
                copyLegacyAlias(old.ratios(), ratios, definition.pattern(), "completion", "completionPrimary");
                copyLegacyAlias(old.ratios(), ratios, definition.pattern(), "bMax", "bPreferredMax");
            }
            patterns.add(new StoredPattern(definition.pattern(), old.enabled(), old.softViolationPercent(),
                    ratios, old.hardRules()));
        }
        return new StoredPreferences(PROFILE_VERSION, globals, patterns);
    }

    private boolean correctedLegacyKey(HarmonicPatternType pattern, String key) {
        return (pattern == HarmonicPatternType.GARTLEY && "extensionMin".equals(key))
                || (pattern == HarmonicPatternType.BAT && "cdBcMin".equals(key))
                || (pattern == HarmonicPatternType.BUTTERFLY && "extensionMax".equals(key))
                || (pattern == HarmonicPatternType.CRAB && "extensionMin".equals(key));
    }

    private void copyLegacyAlias(Map<String, Double> old,
                                 Map<String, Double> migrated,
                                 HarmonicPatternType pattern,
                                 String replacement,
                                 String legacyKey) {
        if (!PATTERNS.get(pattern).ratios().stream().anyMatch(item -> item.key().equals(replacement))) return;
        Double value = old.get(legacyKey);
        if (value != null) migrated.put(replacement, value);
    }

    public static PreferencesView factoryPreferences() {
        return view(factoryStored(), null);
    }

    private static PreferencesView view(StoredPreferences stored, Instant updatedAt) {
        List<NumericSetting> globals = GLOBALS.stream()
                .map(definition -> setting(definition, stored.globals().get(definition.key())))
                .toList();
        List<PatternProfile> patterns = PATTERNS.values().stream().map(definition -> {
            StoredPattern saved = stored.pattern(definition.pattern());
            List<NumericSetting> ratios = definition.ratios().stream()
                    .map(item -> setting(item, saved.ratios().get(item.key())))
                    .toList();
            List<HardRuleSetting> hardRules = definition.hardRules().stream()
                    .map(item -> new HardRuleSetting(item.group(), item.label(), item.description(),
                            saved.hardRules().getOrDefault(item.group(), true), true))
                    .toList();
            boolean factory = saved.equals(factoryPattern(definition));
            return new PatternProfile(definition.pattern(), definition.key(), definition.label(),
                    definition.description(), saved.enabled(), saved.softViolationPercent(), ratios,
                    hardRules, factory);
        }).toList();
        HarmonicPatternDetectionService.Rules rules = rules(stored.globals());
        Map<HarmonicPatternType, HarmonicPatternDetectionService.PatternRuleProfile> configured = new EnumMap<>(HarmonicPatternType.class);
        for (StoredPattern pattern : stored.patterns()) {
            Map<String, Double> ratios = new LinkedHashMap<>();
            pattern.ratios().forEach((key, value) -> ratios.put(key, value / 100.0));
            configured.put(pattern.pattern(), new HarmonicPatternDetectionService.PatternRuleProfile(
                    pattern.enabled(), ratios, pattern.hardRules(), pattern.softViolationPercent() / 100.0));
        }
        boolean custom = !stored.equals(factoryStored());
        return new PreferencesView(PROFILE_VERSION, custom, globals, patterns, updatedAt, rules,
                new HarmonicPatternDetectionService.PatternRuleSet(configured));
    }

    private static HarmonicPatternDetectionService.Rules rules(Map<String, Double> values) {
        return new HarmonicPatternDetectionService.Rules(
                values.get("fibonacciTolerancePercent") / 100.0,
                values.get("legEqualityTolerancePercent") / 100.0,
                values.get("materialLegDifferencePercent") / 100.0,
                values.get("minimumSwingPercent") / 100.0,
                values.get("pivotWindow").intValue(),
                values.get("maximumFormations").intValue(),
                values.get("maximumPivotWindow").intValue(),
                values.get("maximumSwingPercent") / 100.0,
                values.get("maximumSkippedPivots").intValue());
    }

    private StoredPreferences stored(PreferencesView view) {
        Map<String, Double> globals = new LinkedHashMap<>();
        view.globals().forEach(setting -> globals.put(setting.key(), setting.value()));
        List<StoredPattern> patterns = view.patterns().stream().map(profile -> {
            Map<String, Double> ratios = new LinkedHashMap<>();
            profile.ratios().forEach(setting -> ratios.put(setting.key(), setting.value()));
            Map<HarmonicPatternDetectionService.RuleGroup, Boolean> hard = new EnumMap<>(HarmonicPatternDetectionService.RuleGroup.class);
            profile.hardRules().forEach(setting -> hard.put(setting.group(), setting.hard()));
            return new StoredPattern(profile.pattern(), profile.enabled(), profile.softViolationPercent(), ratios, hard);
        }).toList();
        return new StoredPreferences(PROFILE_VERSION, globals, patterns);
    }

    private static StoredPreferences factoryStored() {
        Map<String, Double> globals = new LinkedHashMap<>();
        GLOBALS.forEach(item -> globals.put(item.key(), item.factoryValue()));
        return new StoredPreferences(PROFILE_VERSION, globals,
                PATTERNS.values().stream().map(HarmonicPatternPreferencesService::factoryPattern).toList());
    }

    private static StoredPattern factoryPattern(PatternDefinition definition) {
        Map<String, Double> ratios = new LinkedHashMap<>();
        definition.ratios().forEach(item -> ratios.put(item.key(), item.factoryValue()));
        Map<HarmonicPatternDetectionService.RuleGroup, Boolean> hard = new EnumMap<>(HarmonicPatternDetectionService.RuleGroup.class);
        definition.hardRules().forEach(item -> hard.put(item.group(), true));
        return new StoredPattern(definition.pattern(), true, SOFT_TOLERANCE.factoryValue(), ratios, hard);
    }

    private static NumericSetting setting(NumericDefinition definition, Double value) {
        return new NumericSetting(definition.key(), definition.label(), definition.description(), definition.unit(),
                definition.min(), definition.max(), definition.step(), value == null ? definition.factoryValue() : value,
                definition.factoryValue());
    }

    private static final NumericDefinition SOFT_TOLERANCE = number("softViolationPercent",
            "Extra soft-rule allowance", "Additional variance accepted only for ratio groups marked soft. Larger deviations are still rejected.",
            "%", 0, 25, .1, 5);

    private static Map<HarmonicPatternType, PatternDefinition> definitions() {
        Map<HarmonicPatternType, PatternDefinition> result = new LinkedHashMap<>();
        result.put(HarmonicPatternType.GARTLEY, pattern(HarmonicPatternType.GARTLEY,
                "0.618 B and 0.786 XA completion with AB=CD or 1.27 alternate AB=CD.", List.of(
                        ratio("bTarget", "B retracement of XA", 61.8),
                        ratio("completion", "D completion of XA", 78.6),
                        ratio("cMin", "C retracement minimum of AB", 38.2), ratio("cMax", "C retracement maximum of AB", 88.6),
                        ratio("extensionMin", "CD extension minimum of BC", 113), ratio("extensionMax", "CD extension maximum of BC", 161.8),
                        ratio("legPrimary", "AB=CD target", 100), ratio("legAlternate", "Alternate AB=CD target", 127)), true));
        result.put(HarmonicPatternType.BAT, pattern(HarmonicPatternType.BAT,
                "0.382-0.50 B and 0.886 XA completion with AB=CD or its typical 1.27 alternate.", List.of(
                        ratio("bMin", "B retracement minimum of XA", 38.2), ratio("bMax", "B retracement maximum of XA", 50),
                        ratio("completion", "D completion of XA", 88.6),
                        ratio("cMin", "C retracement minimum of AB", 38.2), ratio("cMax", "C retracement maximum of AB", 88.6),
                        ratio("cdBcMin", "CD/BC minimum", 161.8), ratio("cdBcMax", "CD/BC maximum", 261.8),
                        ratio("legPrimary", "AB=CD target", 100), ratio("legAlternate", "Alternate AB=CD target", 127)), true));
        result.put(HarmonicPatternType.BUTTERFLY, pattern(HarmonicPatternType.BUTTERFLY,
                "0.786 B and 1.27 XA completion; 1.414 XA is an invalidation boundary, not a completion.", List.of(
                        ratio("bTarget", "B retracement of XA", 78.6), ratio("completion", "D completion of XA", 127),
                        ratio("cMin", "C retracement minimum of AB", 38.2),
                        ratio("cMax", "C retracement maximum of AB", 88.6), ratio("extensionMin", "CD extension minimum of BC", 161.8),
                        ratio("extensionMax", "CD extension maximum of BC", 224),
                        ratio("legPrimary", "AB=CD target", 100), ratio("legAlternate", "Alternate AB=CD target", 127)), true));
        result.put(HarmonicPatternType.CRAB, pattern(HarmonicPatternType.CRAB,
                "Outside 1.618 completion with a visibly unequal AB/CD relationship.", List.of(
                        ratio("bMin", "B retracement minimum of XA", 38.2), ratio("bMax", "B retracement maximum of XA", 61.8),
                        ratio("completion", "D completion of XA", 161.8),
                        ratio("cMin", "C retracement minimum of AB", 38.2), ratio("cMax", "C retracement maximum of AB", 88.6),
                        ratio("extensionMin", "CD extension minimum of BC", 261.8), ratio("extensionMax", "CD extension maximum of BC", 361.8)), true));
        result.put(HarmonicPatternType.SHARK, pattern(HarmonicPatternType.SHARK,
                "Official 0-X-A-B-C notation mapped to the common five-pivot structure.", List.of(
                        ratio("aMin", "A retracement minimum of 0X", 32), ratio("aMax", "A retracement maximum of 0X", 61.8),
                        ratio("bMin", "B extension minimum of XA", 113), ratio("bMax", "B extension maximum of XA", 161.8),
                        ratio("extensionMin", "C extension minimum of AB", 161.8), ratio("extensionMax", "C extension maximum of AB", 224),
                        ratio("completionPrimary", "Primary C completion of 0X", 88.6), ratio("completionStretch", "Stretched C completion of 0X", 113)), false));
        result.put(HarmonicPatternType.CYPHER, pattern(HarmonicPatternType.CYPHER,
                "C extends beyond A while D completes near the 0.786 retracement of XC.", List.of(
                        ratio("bMin", "B retracement minimum of XA", 38.2), ratio("bMax", "B retracement maximum of XA", 61.8),
                        ratio("extensionMin", "C extension minimum of XA", 127.2), ratio("extensionMax", "C extension maximum of XA", 141.4),
                        ratio("completion", "D retracement of XC", 78.6)), false));
        return Collections.unmodifiableMap(result);
    }

    private static PatternDefinition pattern(HarmonicPatternType pattern, String description,
                                             List<NumericDefinition> ratios, boolean legRelationship) {
        List<HardRuleDefinition> hard = new ArrayList<>(List.of(
                hard(HarmonicPatternDetectionService.RuleGroup.PRIMARY_B, "B-point ratio is hard", "Reject when B exceeds the base tolerance."),
                hard(HarmonicPatternDetectionService.RuleGroup.COMPLETION, "Completion ratio is hard", "Reject when D/C exceeds the base tolerance."),
                hard(HarmonicPatternDetectionService.RuleGroup.SECONDARY_RATIOS, "Secondary ratios are hard", "Reject when supporting retracements or extensions exceed their ranges.")));
        if (legRelationship) hard.add(hard(HarmonicPatternDetectionService.RuleGroup.LEG_RELATIONSHIP,
                "AB/CD relationship is hard", "Reject when equality or visual-difference rules exceed their base tolerance."));
        return new PatternDefinition(pattern, pattern.name().toLowerCase(Locale.ROOT), pattern.displayName(), description,
                ratios, List.copyOf(hard));
    }

    private static NumericDefinition number(String key, String label, String description, String unit,
                                            double min, double max, double step, double value) {
        return new NumericDefinition(key, label, description, unit, min, max, step, value);
    }
    private static NumericDefinition ratio(String key, String label, double value) {
        return number(key, label, "Price-length ratio used by the " + label.toLowerCase(Locale.ROOT) + " rule.", "%", 0, 1000, .1, value);
    }
    private static HardRuleDefinition hard(HarmonicPatternDetectionService.RuleGroup group,
                                           String label, String description) {
        return new HardRuleDefinition(group, label, description);
    }

    private double decimal(MultiValueMap<String, String> form, String key, NumericDefinition definition) {
        String raw = form.getFirst(key);
        try {
            double value = Double.parseDouble(raw == null ? "" : raw.trim().replace(',', '.'));
            if (!Double.isFinite(value) || value < definition.min() || value > definition.max()) throw new NumberFormatException();
            if (("pivotWindow".equals(definition.key()) || "maximumFormations".equals(definition.key())
                    || "maximumPivotWindow".equals(definition.key())
                    || "maximumSkippedPivots".equals(definition.key()))
                    && value != Math.rint(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(definition.label() + " must be between " + definition.min() + " and " + definition.max() + ".");
        }
    }

    private static boolean checked(MultiValueMap<String, String> form, String key) {
        String value = form.getFirst(key);
        return value != null && (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equals("1"));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Harmonic preferences could not be serialized.", exception);
        }
    }

    public record NumericSetting(String key, String label, String description, String unit,
                                 double min, double max, double step, double value, double factoryValue) { }
    public record HardRuleSetting(HarmonicPatternDetectionService.RuleGroup group, String label,
                                  String description, boolean hard, boolean factoryHard) { }
    public record PatternProfile(HarmonicPatternType pattern, String key, String label, String description,
                                 boolean enabled, double softViolationPercent, List<NumericSetting> ratios,
                                 List<HardRuleSetting> hardRules, boolean factoryProfile) {
        public PatternProfile { ratios = List.copyOf(ratios); hardRules = List.copyOf(hardRules); }
    }
    public record PreferencesView(String version, boolean custom, List<NumericSetting> globals,
                                  List<PatternProfile> patterns, Instant updatedAt,
                                  HarmonicPatternDetectionService.Rules rules,
                                  HarmonicPatternDetectionService.PatternRuleSet patternRules) {
        public PreferencesView { globals = List.copyOf(globals); patterns = List.copyOf(patterns); }
    }

    private record NumericDefinition(String key, String label, String description, String unit,
                                     double min, double max, double step, double factoryValue) { }
    private record HardRuleDefinition(HarmonicPatternDetectionService.RuleGroup group, String label,
                                      String description) { }
    private record PatternDefinition(HarmonicPatternType pattern, String key, String label, String description,
                                     List<NumericDefinition> ratios, List<HardRuleDefinition> hardRules) { }
    private record StoredPattern(HarmonicPatternType pattern, boolean enabled, double softViolationPercent,
                                 Map<String, Double> ratios,
                                 Map<HarmonicPatternDetectionService.RuleGroup, Boolean> hardRules) {
        private StoredPattern {
            ratios = ratios == null ? Map.of() : Map.copyOf(ratios);
            hardRules = hardRules == null ? Map.of() : Map.copyOf(hardRules);
        }
    }
    private record StoredPreferences(String version, Map<String, Double> globals, List<StoredPattern> patterns) {
        private StoredPreferences {
            globals = globals == null ? Map.of() : Map.copyOf(globals);
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
        }
        private StoredPattern pattern(HarmonicPatternType type) {
            return patterns.stream().filter(item -> item.pattern() == type).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("A harmonic pattern profile is missing."));
        }
        private void validate() {
            if (!PROFILE_VERSION.equals(version) || globals.size() != GLOBALS.size()
                    || patterns.size() != HarmonicPatternType.values().length) {
                throw new IllegalArgumentException("Stored harmonic preferences are invalid.");
            }
            for (NumericDefinition definition : GLOBALS) {
                Double value = globals.get(definition.key());
                if (value == null || !Double.isFinite(value) || value < definition.min() || value > definition.max())
                    throw new IllegalArgumentException("Stored harmonic global rule is invalid.");
            }
            if (globals.get("materialLegDifferencePercent") <= globals.get("legEqualityTolerancePercent")) {
                throw new IllegalArgumentException("Required visual leg difference must exceed the equality tolerance.");
            }
            for (PatternDefinition definition : PATTERNS.values()) {
                StoredPattern pattern = pattern(definition.pattern());
                if (!Double.isFinite(pattern.softViolationPercent()) || pattern.softViolationPercent() < 0 || pattern.softViolationPercent() > 25
                        || pattern.ratios().size() != definition.ratios().size()
                        || pattern.hardRules().size() != definition.hardRules().size()) {
                    throw new IllegalArgumentException("Stored harmonic pattern rules are invalid.");
                }
                for (NumericDefinition ratio : definition.ratios()) {
                    Double value = pattern.ratios().get(ratio.key());
                    if (value == null || !Double.isFinite(value) || value < ratio.min() || value > ratio.max())
                        throw new IllegalArgumentException("Stored harmonic ratio is invalid.");
                }
                validateRange(pattern.ratios(), "bMin", "bMax");
                validateRange(pattern.ratios(), "bMin", "bPreferredMax");
                validateRange(pattern.ratios(), "bPreferredMax", "bStretchMax");
                validateRange(pattern.ratios(), "cMin", "cMax");
                validateRange(pattern.ratios(), "aMin", "aMax");
                validateRange(pattern.ratios(), "extensionMin", "extensionMax");
                validateRange(pattern.ratios(), "completionMin", "completionMax");
                validateRange(pattern.ratios(), "cdAbMin", "cdAbMax");
                validateRange(pattern.ratios(), "cdBcMin", "cdBcMax");
            }
            new HarmonicPatternDetectionService(rules(globals));
        }
        private static void validateRange(Map<String, Double> values, String minimum, String maximum) {
            if (values.containsKey(minimum) && values.containsKey(maximum)
                    && values.get(minimum) > values.get(maximum)) {
                throw new IllegalArgumentException(minimum + " must not exceed " + maximum + ".");
            }
        }
    }
}
