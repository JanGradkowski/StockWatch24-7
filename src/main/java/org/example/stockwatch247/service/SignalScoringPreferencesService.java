package org.example.stockwatch247.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserSignalScoringPreferences;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserSignalScoringPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SignalScoringPreferencesService {
    public static final String PROFILE_VERSION = "USER_SIGNAL_SCORING_V2";
    private static final String LEGACY_PROFILE_VERSION = "USER_SIGNAL_SCORING_V1";
    private static final Pattern SCORE_LABEL = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?)/([0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern SIGNED_ADJUSTMENT = Pattern.compile("^\\s*([+-][0-9]+)\\s*$");

    private static final List<ComponentDefinition> CANDLESTICK_COMPONENTS = List.of(
            new ComponentDefinition("patternQuality", "Pattern quality", "Pattern geometry and the required prior trend.", 25),
            new ComponentDefinition("trendIndicators", "Trend indicators", "EMA order and slope, long SMA, and MACD alignment.", 20),
            new ComponentDefinition("higherTimeframe", "Higher-timeframe trend", "Alignment with completed weekly, monthly, or quarterly candles.", 5),
            new ComponentDefinition("momentum", "Momentum", "RSI and CCI reversal location and turn.", 15),
            new ComponentDefinition("bollinger", "Bollinger volatility/location", "Band test, position, and re-entry evidence.", 10),
            new ComponentDefinition("supportResistance", "Support/resistance", "Proximity, prior touches, and rejection at a price level.", 15),
            new ComponentDefinition("volume", "Volume participation", "Relative volume, VWAP, and volume-profile evidence.", 10)
    );
    private static final List<ComponentDefinition> ELLIOTT_COMPONENTS = List.of(
            new ComponentDefinition("structure", "Structural / pivot quality", "Mandatory Elliott rules and pivot quality.", 30),
            new ComponentDefinition("proportions", "Fibonacci / proportion / alternation", "Retracement zones, wave proportions, and alternation.", 20),
            new ComponentDefinition("momentum", "Momentum / divergence", "RSI and MACD divergence or reversal turns.", 15),
            new ComponentDefinition("confirmation", "Stage-specific confirmation", "Break, candle direction, close location, and EMA confirmation.", 15),
            new ComponentDefinition("levels", "Support / resistance / trend context", "Volatility levels, trend extension, and VWAP context.", 10),
            new ComponentDefinition("volume", "Volume confirmation", "Terminal-leg and confirmation-volume evidence.", 5),
            new ComponentDefinition("timing", "Timing / count stability", "Wave duration and minimum leg spacing.", 5)
    );
    private static final List<ComponentDefinition> HARMONIC_COMPONENTS = List.of(
            new ComponentDefinition("primaryB", "Primary B ratio", "Fit of the B point to the selected formation's preferred retracement or extension.", 35),
            new ComponentDefinition("completion", "Completion ratio", "Fit of terminal D/C to the selected formation's preferred completion target.", 45),
            new ComponentDefinition("secondary", "Secondary ratios", "Supporting retracements, extensions, and AB/CD relationship quality.", 20)
    );

    private final UserSignalScoringPreferencesRepository repository;
    private final ObjectMapper objectMapper;

    public SignalScoringPreferencesService(UserSignalScoringPreferencesRepository repository,
                                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PreferencesView get(User user) {
        return AnalysisComputationScope.memo(java.util.List.of(SignalScoringPreferencesService.class,
                user == null ? "factory" : user.getId() == null ? user : user.getId()), () -> loadPreferences(user));
    }

    private PreferencesView loadPreferences(User user) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        return repository.findByUser(user).map(this::read).orElseGet(this::factoryPreferences);
    }

    @Transactional(readOnly = true)
    public Profile profile(User user, AlertPatternFamily family, TimeInterval interval) {
        return get(user).profile(family, interval);
    }

    public CrossPatternConfluenceService.Policy confluencePolicy(
            User user, AlertPatternFamily family, TimeInterval interval) {
        return confluencePolicy(profile(user, family, interval));
    }

    public CrossPatternConfluenceService.Policy confluencePolicy(Profile profile) {
        if (profile == null) return CrossPatternConfluenceService.Policy.factory(null);
        Map<AlertPatternFamily, CrossPatternConfluenceService.Weight> weights = new java.util.EnumMap<>(
                AlertPatternFamily.class);
        for (ConfluenceRule rule : profile.confluenceRules()) {
            weights.put(rule.sourceFamily(), new CrossPatternConfluenceService.Weight(
                    rule.included(), rule.supportingPoints(), rule.opposingPoints()));
        }
        return new CrossPatternConfluenceService.Policy(profile.family(), weights);
    }

    @Transactional
    public PreferencesView save(User user, MultiValueMap<String, String> form) {
        if (form == null) throw new IllegalArgumentException("Scoring preferences are required.");
        List<Profile> profiles = new ArrayList<>();
        for (AlertPatternFamily family : supportedFamilies()) {
            for (TimeInterval interval : supportedIntervals()) {
                profiles.add(parseProfile(form, family, interval));
            }
        }
        return persist(user, profiles);
    }

    @Transactional
    public PreferencesView reset(User user, AlertPatternFamily family, TimeInterval interval) {
        if (family == null && interval != null) {
            throw new IllegalArgumentException("A signal type is required when resetting one interval.");
        }
        PreferencesView current = get(user);
        List<Profile> profiles = current.profiles().stream()
                .map(profile -> matches(profile, family, interval)
                        ? factoryProfile(profile.family(), profile.interval()) : profile)
                .toList();
        if (family == null && interval == null) {
            repository.findByUser(user).ifPresent(repository::delete);
            return factoryPreferences();
        }
        return persist(user, profiles);
    }

    public DisplayScore score(Profile profile, int fallbackScore, List<EvidenceSection> evidence) {
        List<EvidenceSection> safeEvidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (profile == null || profile.factoryProfile()) {
            List<EvidenceSection> factoryEvidence = safeEvidence;
            if (profile != null && profile.family() == AlertPatternFamily.ELLIOTT_WAVE) {
                double categoryTotal = 0.0;
                int categoryCount = 0;
                for (EvidenceSection section : safeEvidence) {
                    if (componentKey(profile.family(), section.category()) == null) continue;
                    double[] values = parseScore(section.scoreLabel());
                    if (values != null) {
                        categoryTotal += values[0];
                        categoryCount++;
                    }
                }
                if (categoryCount > 0 && Math.round(categoryTotal) != fallbackScore) {
                    factoryEvidence = safeEvidence.stream()
                            .filter(section -> componentKey(profile.family(), section.category()) == null)
                            .toList();
                }
            }
            return display(fallbackScore, false, factoryEvidence);
        }

        Map<String, EvidenceSection> byKey = new LinkedHashMap<>();
        EvidenceSection confluenceSection = null;
        for (EvidenceSection section : safeEvidence) {
            if ("Cross-pattern confluence".equalsIgnoreCase(section.category())) {
                confluenceSection = section;
            }
            String key = componentKey(profile.family(), section.category());
            if (key != null && parseScore(section.scoreLabel()) != null) byKey.putIfAbsent(key, section);
        }
        if (byKey.isEmpty()) {
            return new DisplayScore(fallbackScore, band(fallbackScore), strength(fallbackScore),
                    explanation(fallbackScore), false, safeEvidence, !safeEvidence.isEmpty(),
                    "This saved signal does not contain category totals, so its original score is shown.");
        }

        double total = 0.0;
        List<EvidenceSection> rescored = new ArrayList<>();
        for (ScoringComponent component : profile.components()) {
            if (!component.included()) continue;
            EvidenceSection section = byKey.get(component.key());
            double earned = 0.0;
            if (section != null) {
                double[] original = parseScore(section.scoreLabel());
                if (original != null && original[1] > 0.0) {
                    earned = Math.clamp(original[0] / original[1], 0.0, 1.0) * component.points();
                }
                rescored.add(new EvidenceSection(section.category(), format(earned) + "/" + component.points(),
                        status(earned, component.points()), section.caution(), true, section.details()));
            } else {
                rescored.add(new EvidenceSection(component.label(), "0/" + component.points(),
                        "Evidence unavailable", true, true, List.of()));
            }
            total += earned;
        }
        EvidenceSection rescoredConfluence = rescoreConfluence(profile, confluenceSection);
        int confluenceAdjustment = confluenceAdjustment(profile, rescoredConfluence);
        int score = Math.clamp((int) Math.round(total) + confluenceAdjustment, 0, 100);
        if (rescoredConfluence != null) rescored.add(rescoredConfluence);
        return new DisplayScore(score, band(score), strength(score), explanation(score), true,
                List.copyOf(rescored), !rescored.isEmpty(), "Your scoring settings are applied.");
    }

    private Profile parseProfile(MultiValueMap<String, String> form,
                                 AlertPatternFamily family,
                                 TimeInterval interval) {
        String prefix = familyKey(family) + "." + intervalKey(interval) + ".";
        List<ScoringComponent> components = definitions(family).stream().map(definition -> {
            boolean included = checked(form, prefix + definition.key() + ".included");
            int points = integer(form, prefix + definition.key() + ".points", definition.label());
            if (points < 0 || points > 100) {
                throw new IllegalArgumentException(definition.label() + " points must be between 0 and 100.");
            }
            return new ScoringComponent(definition.key(), definition.label(), definition.description(), included, points);
        }).toList();
        List<ConfluenceRule> confluenceRules = sourceFamilies(family).stream().map(sourceFamily -> {
            String rulePrefix = prefix + "confluence." + familyKey(sourceFamily) + ".";
            boolean included = checked(form, rulePrefix + "included");
            int supportingPoints = integer(form, rulePrefix + "supportingPoints",
                    familyLabel(sourceFamily) + " same-direction confluence");
            int opposingPoints = integer(form, rulePrefix + "opposingPoints",
                    familyLabel(sourceFamily) + " opposite-direction confluence");
            validateConfluencePoints(sourceFamily, supportingPoints, opposingPoints);
            return new ConfluenceRule(sourceFamily, familyLabel(sourceFamily), included,
                    supportingPoints, opposingPoints);
        }).toList();
        validateTotal(family, interval, components);
        return new Profile(family, interval, familyLabel(family), intervalLabel(interval),
                components, confluenceRules, false);
    }

    private PreferencesView persist(User user, List<Profile> profiles) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        validateProfiles(profiles);
        List<Profile> normalizedProfiles = profiles.stream()
                .map(profile -> profile.withFactoryProfile(matchesFactory(profile)))
                .toList();
        StoredPreferences stored = new StoredPreferences(PROFILE_VERSION, normalizedProfiles);
        UserSignalScoringPreferences entity = repository.findByUser(user)
                .orElseGet(UserSignalScoringPreferences::new);
        entity.setUser(user);
        entity.setProfileVersion(PROFILE_VERSION);
        entity.setPreferencesPayload(write(stored));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return new PreferencesView(PROFILE_VERSION,
                normalizedProfiles.stream().anyMatch(profile -> !profile.factoryProfile()),
                stored.profiles(), entity.getUpdatedAt());
    }

    private PreferencesView read(UserSignalScoringPreferences entity) {
        try {
            StoredPreferences stored = objectMapper.readValue(entity.getPreferencesPayload(), StoredPreferences.class);
            stored.validate();
            List<Profile> completeProfiles = completeProfiles(stored.profiles());
            validateProfiles(completeProfiles);
            List<Profile> normalizedProfiles = completeProfiles.stream()
                    .map(profile -> profile.withFactoryProfile(matchesFactory(profile)))
                    .toList();
            return new PreferencesView(PROFILE_VERSION,
                    normalizedProfiles.stream().anyMatch(profile -> !profile.factoryProfile()),
                    normalizedProfiles, entity.getUpdatedAt());
        } catch (JacksonException | IllegalArgumentException exception) {
            return factoryPreferences();
        }
    }

    private PreferencesView factoryPreferences() {
        List<Profile> profiles = new ArrayList<>();
        for (AlertPatternFamily family : supportedFamilies()) {
            for (TimeInterval interval : supportedIntervals()) profiles.add(factoryProfile(family, interval));
        }
        return new PreferencesView(PROFILE_VERSION, false, List.copyOf(profiles), null);
    }

    private static Profile factoryProfile(AlertPatternFamily family, TimeInterval interval) {
        List<ScoringComponent> components = definitions(family).stream()
                .map(item -> new ScoringComponent(item.key(), item.label(), item.description(), true, item.defaultPoints()))
                .toList();
        List<ConfluenceRule> confluenceRules = sourceFamilies(family).stream()
                .map(source -> new ConfluenceRule(source, familyLabel(source), true,
                        CrossPatternConfluenceService.POINTS_PER_FAMILY,
                        CrossPatternConfluenceService.POINTS_PER_FAMILY))
                .toList();
        return new Profile(family, interval, familyLabel(family), intervalLabel(interval),
                components, confluenceRules, true);
    }

    private static void validateProfiles(List<Profile> profiles) {
        int required = supportedFamilies().size() * supportedIntervals().size();
        if (profiles == null || profiles.size() != required) {
            throw new IllegalArgumentException("All " + required + " scoring profiles are required.");
        }
        for (AlertPatternFamily family : supportedFamilies()) {
            for (TimeInterval interval : supportedIntervals()) {
                Profile profile = profiles.stream()
                        .filter(candidate -> candidate.family() == family && candidate.interval() == interval)
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("A scoring profile is missing."));
                validateTotal(family, interval, profile.components());
                validateConfluenceRules(family, profile.confluenceRules());
            }
        }
    }

    private static void validateTotal(AlertPatternFamily family,
                                      TimeInterval interval,
                                      List<ScoringComponent> components) {
        if (components == null || components.size() != definitions(family).size()) {
            throw new IllegalArgumentException("Every scoring parameter must be submitted.");
        }
        int total = components.stream().filter(ScoringComponent::included)
                .mapToInt(ScoringComponent::points).sum();
        if (total != 100) {
            throw new IllegalArgumentException(familyLabel(family) + " "
                    + intervalLabel(interval).toLowerCase(Locale.ROOT)
                    + " included points must total exactly 100 (currently " + total + ").");
        }
    }

    private static void validateConfluenceRules(AlertPatternFamily targetFamily,
                                                List<ConfluenceRule> rules) {
        List<AlertPatternFamily> expected = sourceFamilies(targetFamily);
        if (rules == null || rules.size() != expected.size()) {
            throw new IllegalArgumentException("Every cross-pattern confluence source must be submitted.");
        }
        for (AlertPatternFamily sourceFamily : expected) {
            ConfluenceRule rule = rules.stream()
                    .filter(candidate -> candidate.sourceFamily() == sourceFamily)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            familyLabel(sourceFamily) + " confluence settings are missing."));
            validateConfluencePoints(sourceFamily, rule.supportingPoints(), rule.opposingPoints());
        }
    }

    private static void validateConfluencePoints(AlertPatternFamily sourceFamily,
                                                 int supportingPoints,
                                                 int opposingPoints) {
        if (supportingPoints < 0 || supportingPoints > 100
                || opposingPoints < 0 || opposingPoints > 100) {
            throw new IllegalArgumentException(familyLabel(sourceFamily)
                    + " confluence points must be between 0 and 100.");
        }
    }

    private static boolean matches(Profile profile, AlertPatternFamily family, TimeInterval interval) {
        return (family == null || profile.family() == family)
                && (interval == null || profile.interval() == interval);
    }

    private static boolean matchesFactory(Profile profile) {
        Profile factory = factoryProfile(profile.family(), profile.interval());
        if (profile.components().size() != factory.components().size()) return false;
        for (int index = 0; index < factory.components().size(); index++) {
            ScoringComponent actual = profile.components().get(index);
            ScoringComponent expected = factory.components().get(index);
            if (!actual.key().equals(expected.key()) || actual.included() != expected.included()
                    || actual.points() != expected.points()) return false;
        }
        if (!profile.confluenceRules().equals(factory.confluenceRules())) return false;
        return true;
    }

    private static List<ComponentDefinition> definitions(AlertPatternFamily family) {
        return switch (family) {
            case ELLIOTT_WAVE -> ELLIOTT_COMPONENTS;
            case HARMONIC_FORMATION -> HARMONIC_COMPONENTS;
            default -> CANDLESTICK_COMPONENTS;
        };
    }

    private static String componentKey(AlertPatternFamily family, String category) {
        if (category == null) return null;
        String normalized = category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (ComponentDefinition definition : definitions(family)) {
            String label = definition.label().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (normalized.equals(label)) return definition.key();
        }
        return null;
    }

    private static double[] parseScore(String label) {
        if (label == null) return null;
        Matcher matcher = SCORE_LABEL.matcher(label);
        if (!matcher.matches()) return null;
        try {
            return new double[]{Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int confluenceAdjustment(Profile profile, EvidenceSection section) {
        if (section == null || section.scoreLabel() == null) return 0;
        if (profile == null) {
            Matcher matcher = SIGNED_ADJUSTMENT.matcher(section.scoreLabel());
            return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
        }
        int adjustment = 0;
        for (EvidenceDetail detail : section.details()) {
            AlertPatternFamily sourceFamily = familyFromConfluenceLabel(detail.label());
            if (sourceFamily == null) continue;
            ConfluenceRule rule = profile.confluenceRule(sourceFamily);
            if (!rule.included()) continue;
            Matcher matcher = SIGNED_ADJUSTMENT.matcher(detail.scoreLabel() == null ? "" : detail.scoreLabel());
            if (matcher.matches()) {
                int storedDirection = Integer.parseInt(matcher.group(1));
                adjustment += storedDirection >= 0 ? rule.supportingPoints() : -rule.opposingPoints();
            } else if (detail.text() != null) {
                String text = detail.text().toLowerCase(Locale.ROOT);
                if (text.contains("opposite-direction evidence")) adjustment -= rule.opposingPoints();
                else if (text.contains("same-direction evidence")) adjustment += rule.supportingPoints();
            }
        }
        if (!section.details().isEmpty()) return adjustment;
        Matcher matcher = SIGNED_ADJUSTMENT.matcher(section.scoreLabel());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static EvidenceSection rescoreConfluence(Profile profile, EvidenceSection section) {
        if (profile == null || section == null || section.details().isEmpty()) return section;
        List<EvidenceDetail> details = new ArrayList<>();
        int adjustment = 0;
        for (EvidenceDetail detail : section.details()) {
            AlertPatternFamily sourceFamily = familyFromConfluenceLabel(detail.label());
            if (sourceFamily == null) {
                details.add(detail);
                continue;
            }
            ConfluenceRule rule = profile.confluenceRule(sourceFamily);
            Matcher matcher = SIGNED_ADJUSTMENT.matcher(detail.scoreLabel() == null ? "" : detail.scoreLabel());
            boolean opposing = matcher.matches() && Integer.parseInt(matcher.group(1)) < 0;
            if (!matcher.matches() && detail.text() != null) {
                opposing = detail.text().toLowerCase(Locale.ROOT).contains("opposite-direction evidence");
            }
            int points = !rule.included() ? 0
                    : opposing ? -rule.opposingPoints() : rule.supportingPoints();
            adjustment += points;
            String baseText = detail.text() == null ? "Evidence occurred within the preceding eight candles."
                    : detail.text().replaceFirst("\\s*\\([^)]*(?:points|scoring profile)[^)]*\\)\\.?$", ".");
            String settingText = rule.included()
                    ? " Current scoring settings apply " + String.format(Locale.ROOT, "%+d", points) + " points."
                    : " This source family is disabled in the current scoring settings.";
            details.add(new EvidenceDetail(detail.label(), baseText + settingText,
                    rule.included() ? String.format(Locale.ROOT, "%+d", points) : null));
        }
        String status = adjustment > 0 ? "Supporting signals"
                : adjustment < 0 ? "Opposing signals" : "No adjustment";
        return new EvidenceSection(section.category(), String.format(Locale.ROOT, "%+d", adjustment),
                status, adjustment < 0, false, List.copyOf(details));
    }

    private static String format(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        return rounded == Math.rint(rounded) ? Integer.toString((int) rounded)
                : String.format(Locale.ROOT, "%.1f", rounded);
    }

    private static String status(double earned, int maximum) {
        if (earned <= 0.0001) return "No supporting points";
        if (earned >= maximum - 0.0001) return "Full score";
        return "Partial support";
    }

    private static DisplayScore display(int score, boolean custom, List<EvidenceSection> sections) {
        int normalized = Math.clamp(score, 0, 100);
        return new DisplayScore(normalized, band(normalized), strength(normalized), explanation(normalized),
                custom, sections, !sections.isEmpty(), custom ? "Your scoring settings are applied." : "Default scoring settings.");
    }

    private static String band(int score) { return score >= 85 ? "high" : score >= 75 ? "medium" : "low"; }
    private static String strength(int score) {
        return score >= 85 ? "High support" : score >= 75 ? "Moderate support" : "Low support";
    }
    private static String explanation(int score) {
        if (score >= 85) return "Most selected indicators support this signal.";
        if (score >= 75) return "Several selected indicators support this signal; others are mixed.";
        return "Few selected indicators support this signal.";
    }

    private static int integer(MultiValueMap<String, String> form, String key, String label) {
        String value = form.getFirst(key);
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " points must be a whole number.");
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
            throw new IllegalStateException("Scoring preferences could not be serialized.", exception);
        }
    }

    private static List<AlertPatternFamily> supportedFamilies() {
        return List.of(AlertPatternFamily.CANDLESTICK, AlertPatternFamily.ELLIOTT_WAVE,
                AlertPatternFamily.HARMONIC_FORMATION);
    }
    private static List<AlertPatternFamily> sourceFamilies(AlertPatternFamily targetFamily) {
        return supportedFamilies().stream().filter(family -> family != targetFamily).toList();
    }
    private static List<TimeInterval> supportedIntervals() {
        return List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY);
    }
    private static String familyKey(AlertPatternFamily family) {
        return switch (family) {
            case ELLIOTT_WAVE -> "elliott";
            case HARMONIC_FORMATION -> "harmonic";
            default -> "candlestick";
        };
    }
    private static String familyLabel(AlertPatternFamily family) {
        return switch (family) {
            case ELLIOTT_WAVE -> "Elliott Wave";
            case HARMONIC_FORMATION -> "Harmonic Formation";
            default -> "Candlestick";
        };
    }
    private static AlertPatternFamily familyFromConfluenceLabel(String label) {
        if (label == null) return null;
        String normalized = label.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("candlestick")) return AlertPatternFamily.CANDLESTICK;
        if (normalized.startsWith("elliott")) return AlertPatternFamily.ELLIOTT_WAVE;
        if (normalized.startsWith("harmonic")) return AlertPatternFamily.HARMONIC_FORMATION;
        return null;
    }
    private static String intervalKey(TimeInterval interval) { return interval.name().toLowerCase(Locale.ROOT); }
    private static String intervalLabel(TimeInterval interval) {
        String value = intervalKey(interval);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record StoredPreferences(String version, List<Profile> profiles) {
        private void validate() {
            if ((!PROFILE_VERSION.equals(version) && !LEGACY_PROFILE_VERSION.equals(version))
                    || profiles == null) {
                throw new IllegalArgumentException("Unsupported scoring profile version.");
            }
        }
    }

    private static List<Profile> completeProfiles(List<Profile> stored) {
        List<Profile> complete = new ArrayList<>();
        for (AlertPatternFamily family : supportedFamilies()) {
            for (TimeInterval interval : supportedIntervals()) {
                complete.add(stored.stream()
                        .filter(profile -> profile.family() == family && profile.interval() == interval)
                        .findFirst()
                        .map(SignalScoringPreferencesService::completeProfile)
                        .orElseGet(() -> factoryProfile(family, interval)));
            }
        }
        return List.copyOf(complete);
    }

    private static Profile completeProfile(Profile stored) {
        Profile factory = factoryProfile(stored.family(), stored.interval());
        List<ConfluenceRule> rules = stored.confluenceRules() == null || stored.confluenceRules().isEmpty()
                ? factory.confluenceRules() : sourceFamilies(stored.family()).stream()
                .map(source -> stored.confluenceRules().stream()
                        .filter(rule -> rule.sourceFamily() == source)
                        .findFirst()
                        .orElseGet(() -> factory.confluenceRule(source)))
                .toList();
        return new Profile(stored.family(), stored.interval(), familyLabel(stored.family()),
                intervalLabel(stored.interval()), stored.components(), rules, false);
    }

    private record ComponentDefinition(String key, String label, String description, int defaultPoints) { }

    public record PreferencesView(String version, boolean custom, List<Profile> profiles, Instant updatedAt) {
        public PreferencesView { profiles = profiles == null ? List.of() : List.copyOf(profiles); }
        public Profile profile(AlertPatternFamily family, TimeInterval interval) {
            return profiles.stream().filter(item -> item.family() == family && item.interval() == interval)
                    .findFirst().orElseGet(() -> factoryProfile(family, interval));
        }
    }

    public record Profile(AlertPatternFamily family, TimeInterval interval, String familyLabel,
                          String intervalLabel, List<ScoringComponent> components,
                          List<ConfluenceRule> confluenceRules, boolean factoryProfile) {
        public Profile {
            components = components == null ? List.of() : List.copyOf(components);
            confluenceRules = confluenceRules == null ? List.of() : List.copyOf(confluenceRules);
        }
        public String key() { return familyKey(family) + "." + intervalKey(interval); }
        public int totalPoints() { return components.stream().filter(ScoringComponent::included).mapToInt(ScoringComponent::points).sum(); }
        public ConfluenceRule confluenceRule(AlertPatternFamily sourceFamily) {
            return confluenceRules.stream().filter(rule -> rule.sourceFamily() == sourceFamily)
                    .findFirst().orElseGet(() -> new ConfluenceRule(sourceFamily,
                            SignalScoringPreferencesService.familyLabel(sourceFamily), true,
                            CrossPatternConfluenceService.POINTS_PER_FAMILY,
                            CrossPatternConfluenceService.POINTS_PER_FAMILY));
        }
        private Profile withFactoryProfile(boolean factory) {
            return new Profile(family, interval, familyLabel, intervalLabel, components,
                    confluenceRules, factory);
        }
    }

    public record ScoringComponent(String key, String label, String description, boolean included, int points) { }
    public record ConfluenceRule(AlertPatternFamily sourceFamily, String sourceFamilyLabel,
                                 boolean included, int supportingPoints, int opposingPoints) {
        public String key() { return familyKey(sourceFamily); }
    }
    public record EvidenceDetail(String label, String text, String scoreLabel) { }
    public record EvidenceSection(String category, String scoreLabel, String statusLabel, boolean caution,
                                  boolean scored, List<EvidenceDetail> details) {
        public EvidenceSection { details = details == null ? List.of() : List.copyOf(details); }
    }
    public record DisplayScore(int score, String band, String strengthLabel, String explanation,
                               boolean customApplied, List<EvidenceSection> sections,
                               boolean evidenceAvailable, String profileNote) {
        public DisplayScore { sections = sections == null ? List.of() : List.copyOf(sections); }
    }
}
