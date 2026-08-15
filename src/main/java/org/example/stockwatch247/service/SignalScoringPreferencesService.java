package org.example.stockwatch247.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public static final String PROFILE_VERSION = "USER_SIGNAL_SCORING_V1";
    private static final Pattern SCORE_LABEL = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?)/([0-9]+(?:\\.[0-9]+)?)\\s*$");

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

    private final UserSignalScoringPreferencesRepository repository;
    private final ObjectMapper objectMapper;

    public SignalScoringPreferencesService(UserSignalScoringPreferencesRepository repository,
                                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PreferencesView get(User user) {
        if (user == null) throw new IllegalArgumentException("An account is required.");
        return repository.findByUser(user).map(this::read).orElseGet(this::factoryPreferences);
    }

    @Transactional(readOnly = true)
    public Profile profile(User user, AlertPatternFamily family, TimeInterval interval) {
        return get(user).profile(family, interval);
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
        for (EvidenceSection section : safeEvidence) {
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
        int score = Math.clamp((int) Math.round(total), 0, 100);
        return new DisplayScore(score, band(score), strength(score), explanation(score), true,
                List.copyOf(rescored), !rescored.isEmpty(), "Custom scoring profile applied.");
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
        validateTotal(family, interval, components);
        return new Profile(family, interval, familyLabel(family), intervalLabel(interval), components, false);
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
            List<Profile> normalizedProfiles = stored.profiles().stream()
                    .map(profile -> profile.withFactoryProfile(matchesFactory(profile)))
                    .toList();
            return new PreferencesView(PROFILE_VERSION,
                    normalizedProfiles.stream().anyMatch(profile -> !profile.factoryProfile()),
                    normalizedProfiles, entity.getUpdatedAt());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
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
        return new Profile(family, interval, familyLabel(family), intervalLabel(interval), components, true);
    }

    private static void validateProfiles(List<Profile> profiles) {
        if (profiles == null || profiles.size() != 6) {
            throw new IllegalArgumentException("All six scoring profiles are required.");
        }
        for (AlertPatternFamily family : supportedFamilies()) {
            for (TimeInterval interval : supportedIntervals()) {
                Profile profile = profiles.stream()
                        .filter(candidate -> candidate.family() == family && candidate.interval() == interval)
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("A scoring profile is missing."));
                validateTotal(family, interval, profile.components());
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
        return true;
    }

    private static List<ComponentDefinition> definitions(AlertPatternFamily family) {
        return family == AlertPatternFamily.ELLIOTT_WAVE ? ELLIOTT_COMPONENTS : CANDLESTICK_COMPONENTS;
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
                custom, sections, !sections.isEmpty(), custom ? "Custom scoring profile applied." : "Factory scoring profile.");
    }

    private static String band(int score) { return score >= 85 ? "high" : score >= 75 ? "medium" : "low"; }
    private static String strength(int score) {
        return score >= 85 ? "High confluence" : score >= 75 ? "Moderate confluence" : "Low confluence";
    }
    private static String explanation(int score) {
        if (score >= 85) return "High heuristic confluence: broad alignment across the selected technical evidence.";
        if (score >= 75) return "Moderate heuristic confluence: several selected factors align, with some mixed evidence.";
        return "Low heuristic confluence: supporting evidence in the selected scoring parameters is limited.";
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
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Scoring preferences could not be serialized.", exception);
        }
    }

    private static List<AlertPatternFamily> supportedFamilies() {
        return List.of(AlertPatternFamily.CANDLESTICK, AlertPatternFamily.ELLIOTT_WAVE);
    }
    private static List<TimeInterval> supportedIntervals() {
        return List.of(TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY);
    }
    private static String familyKey(AlertPatternFamily family) {
        return family == AlertPatternFamily.ELLIOTT_WAVE ? "elliott" : "candlestick";
    }
    private static String familyLabel(AlertPatternFamily family) {
        return family == AlertPatternFamily.ELLIOTT_WAVE ? "Elliott Wave" : "Candlestick";
    }
    private static String intervalKey(TimeInterval interval) { return interval.name().toLowerCase(Locale.ROOT); }
    private static String intervalLabel(TimeInterval interval) {
        String value = intervalKey(interval);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record StoredPreferences(String version, List<Profile> profiles) {
        private void validate() {
            if (!PROFILE_VERSION.equals(version)) throw new IllegalArgumentException("Unsupported scoring profile version.");
            validateProfiles(profiles);
        }
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
                          String intervalLabel, List<ScoringComponent> components, boolean factoryProfile) {
        public Profile { components = components == null ? List.of() : List.copyOf(components); }
        public String key() { return familyKey(family) + "." + intervalKey(interval); }
        public int totalPoints() { return components.stream().filter(ScoringComponent::included).mapToInt(ScoringComponent::points).sum(); }
        private Profile withFactoryProfile(boolean factory) {
            return new Profile(family, interval, familyLabel, intervalLabel, components, factory);
        }
    }

    public record ScoringComponent(String key, String label, String description, boolean included, int points) { }
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
