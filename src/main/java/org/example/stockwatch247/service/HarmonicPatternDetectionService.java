package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Detects completed harmonic formations using application-owned rules.
 * Pivots are confirmed with right-side candles, so confirmationTimestamp is
 * always later than or equal to the terminal point and never uses future data
 * as if it had already been available at D/C.
 */
@Service
public class HarmonicPatternDetectionService {
    public static final String RULE_VERSION = "HARMONIC_V3";

    private final Rules rules;
    private final PatternRuleSet patternRules;

    public HarmonicPatternDetectionService() {
        this(Rules.defaults(), PatternRuleSet.defaults());
    }

    public HarmonicPatternDetectionService(Rules rules) {
        this(rules, PatternRuleSet.defaults());
    }

    public HarmonicPatternDetectionService(Rules rules, PatternRuleSet patternRules) {
        this.rules = rules == null ? Rules.defaults() : rules.validated();
        this.patternRules = patternRules == null ? PatternRuleSet.defaults() : patternRules.validated();
    }

    public HarmonicPatternDetectionService configured(Rules configuredRules) {
        return new HarmonicPatternDetectionService(configuredRules, patternRules);
    }

    public HarmonicPatternDetectionService configured(Rules configuredRules,
                                                      PatternRuleSet configuredPatternRules) {
        return new HarmonicPatternDetectionService(configuredRules, configuredPatternRules);
    }

    public static CandlePattern signalPattern(HarmonicPatternType pattern) {
        if (pattern == null) throw new IllegalArgumentException("A harmonic pattern is required.");
        return switch (pattern) {
            case GARTLEY -> CandlePattern.HARMONIC_GARTLEY;
            case BAT -> CandlePattern.HARMONIC_BAT;
            case BUTTERFLY -> CandlePattern.HARMONIC_BUTTERFLY;
            case CRAB -> CandlePattern.HARMONIC_CRAB;
            case SHARK -> CandlePattern.HARMONIC_SHARK;
            case CYPHER -> CandlePattern.HARMONIC_CYPHER;
        };
    }

    public List<HarmonicFormation> detectHistorical(List<Candle> historicalCandles) {
        List<Candle> candles = normalize(historicalCandles);
        if (candles.size() < rules.pivotWindow() * 2 + 5) return List.of();
        Map<String, HarmonicFormation> bestByCompletion = new LinkedHashMap<>();
        Set<String> scannedHierarchies = new HashSet<>();
        Set<String> scannedGeometries = new HashSet<>();
        List<Double> swingScales = pivotSwingScales(candles);
        for (int pivotWindow : pivotWindows()) {
            List<HarmonicPivot> candidates = pivotCandidates(candles, pivotWindow);
            for (double swingScale : swingScales) {
                List<HarmonicPivot> pivots = compressAlternating(candidates, swingScale);
                if (pivots.size() < 5 || !scannedHierarchies.add(hierarchyKey(pivots))) continue;
                scanGeometries(pivots, scannedGeometries, bestByCompletion);
            }
        }
        List<HarmonicFormation> formations = bestByCompletion.values().stream()
                .sorted(Comparator.comparingLong(HarmonicFormation::confirmationTimestamp)
                        .thenComparing(formation -> formation.pattern().name()))
                .toList();
        return formations.size() <= rules.maximumFormations()
                ? formations
                : formations.subList(formations.size() - rules.maximumFormations(), formations.size());
    }

    /** Classifies one already-confirmed, alternating five-pivot geometry. */
    public Optional<HarmonicFormation> classify(List<HarmonicPivot> geometry) {
        if (geometry == null || geometry.size() != 5 || !validPivotOrder(geometry)) {
            return Optional.empty();
        }
        Direction direction = direction(geometry);
        if (direction == null || !largeEnough(geometry)) return Optional.empty();

        Ratios ratios = Ratios.from(geometry);
        List<Candidate> candidates = new ArrayList<>();
        addCandidate(candidates, gartley(geometry, direction, ratios));
        addCandidate(candidates, bat(geometry, direction, ratios));
        addCandidate(candidates, butterfly(geometry, direction, ratios));
        addCandidate(candidates, crab(geometry, direction, ratios));
        addCandidate(candidates, shark(geometry, direction, ratios));
        addCandidate(candidates, cypher(geometry, direction, ratios));
        return candidates.stream()
                .min(Comparator.comparingDouble(Candidate::classificationError)
                        .thenComparing(candidate -> candidate.pattern().ordinal()))
                .map(candidate -> formation(candidate, geometry, direction, ratios));
    }

    public List<HarmonicPivot> confirmedPivots(List<Candle> historicalCandles) {
        List<Candle> candles = normalize(historicalCandles);
        return compressAlternating(pivotCandidates(candles, rules.pivotWindow()),
                rules.minimumSwingFraction());
    }

    private List<HarmonicPivot> pivotCandidates(List<Candle> candles, int window) {
        if (candles.size() < window * 2 + 1) return List.of();
        List<HarmonicPivot> candidates = new ArrayList<>();
        for (int index = window; index < candles.size() - window; index++) {
            Candle current = candles.get(index);
            boolean high = true;
            boolean low = true;
            boolean strictHigh = false;
            boolean strictLow = false;
            for (int offset = 1; offset <= window; offset++) {
                Candle left = candles.get(index - offset);
                Candle right = candles.get(index + offset);
                high &= current.getHighPrice() >= left.getHighPrice()
                        && current.getHighPrice() >= right.getHighPrice();
                low &= current.getLowPrice() <= left.getLowPrice()
                        && current.getLowPrice() <= right.getLowPrice();
                strictHigh |= current.getHighPrice() > left.getHighPrice()
                        || current.getHighPrice() > right.getHighPrice();
                strictLow |= current.getLowPrice() < left.getLowPrice()
                        || current.getLowPrice() < right.getLowPrice();
                if (!high && !low) break;
            }
            if (high && strictHigh && !(low && strictLow)) {
                candidates.add(new HarmonicPivot(index, current.getTimestamp(), current.getHighPrice(),
                        PivotType.HIGH, candles.get(index + window).getTimestamp()));
            } else if (low && strictLow && !(high && strictHigh)) {
                candidates.add(new HarmonicPivot(index, current.getTimestamp(), current.getLowPrice(),
                        PivotType.LOW, candles.get(index + window).getTimestamp()));
            }
        }
        return List.copyOf(candidates);
    }

    private List<HarmonicPivot> compressAlternating(List<HarmonicPivot> candidates,
                                                    double minimumSwingFraction) {
        List<HarmonicPivot> pivots = new ArrayList<>();
        for (HarmonicPivot candidate : candidates) {
            if (pivots.isEmpty()) {
                pivots.add(candidate);
                continue;
            }
            HarmonicPivot previous = pivots.getLast();
            if (previous.type() == candidate.type()) {
                boolean moreExtreme = candidate.type() == PivotType.HIGH
                        ? candidate.price() > previous.price()
                        : candidate.price() < previous.price();
                if (moreExtreme) pivots.set(pivots.size() - 1, candidate);
                continue;
            }
            double moveFraction = Math.abs(candidate.price() - previous.price()) / Math.abs(previous.price());
            if (moveFraction >= minimumSwingFraction) pivots.add(candidate);
        }
        return List.copyOf(pivots);
    }

    private List<Integer> pivotWindows() {
        List<Integer> windows = new ArrayList<>();
        for (int window : List.of(rules.pivotWindow(), 3, 5, 8, 13, 21, 34, 55,
                rules.maximumPivotWindow())) {
            if (window < rules.pivotWindow() || window > rules.maximumPivotWindow()) continue;
            if (!windows.contains(window)) windows.add(window);
        }
        windows.sort(Integer::compareTo);
        return List.copyOf(windows);
    }

    private List<Double> pivotSwingScales(List<Candle> candles) {
        List<Double> scales = new ArrayList<>();
        double volatility = representativeTrueRangeFraction(candles);
        List<Double> candidates = new ArrayList<>(List.of(
                rules.minimumSwingFraction(), .01, .02, .03, .05, .08, .13, .21, .34,
                rules.maximumSwingFraction()));
        for (double multiple : List.of(1.0, 1.5, 2.0, 3.0, 5.0, 8.0)) {
            candidates.add(volatility * multiple);
        }
        candidates.sort(Double::compareTo);
        for (double scale : candidates) {
            if (scale + 1e-12 < rules.minimumSwingFraction()) continue;
            if (scale - 1e-12 > rules.maximumSwingFraction()) continue;
            boolean duplicate = scales.stream()
                    .anyMatch(existing -> Math.abs(existing - scale) < .0005);
            if (!duplicate) scales.add(Math.max(rules.minimumSwingFraction(), scale));
        }
        return List.copyOf(scales);
    }

    private double representativeTrueRangeFraction(List<Candle> candles) {
        List<Double> fractions = new ArrayList<>();
        for (int index = 1; index < candles.size(); index++) {
            Candle candle = candles.get(index);
            double previousClose = candles.get(index - 1).getClosePrice();
            double trueRange = Math.max(candle.getHighPrice() - candle.getLowPrice(),
                    Math.max(Math.abs(candle.getHighPrice() - previousClose),
                            Math.abs(candle.getLowPrice() - previousClose)));
            double denominator = Math.max(Math.abs(previousClose), 1e-9);
            double fraction = trueRange / denominator;
            if (Double.isFinite(fraction) && fraction > 0.0) fractions.add(fraction);
        }
        if (fractions.isEmpty()) return rules.minimumSwingFraction();
        fractions.sort(Double::compareTo);
        return fractions.get(fractions.size() / 2);
    }

    private void scanGeometries(List<HarmonicPivot> pivots,
                                Set<String> scannedGeometries,
                                Map<String, HarmonicFormation> bestByCompletion) {
        int structuralWindow = 5 + rules.maximumSkippedPivots();
        for (int d = 4; d < pivots.size(); d++) {
            int first = Math.max(0, d - structuralWindow + 1);
            for (int x = first; x <= d - 4; x++) {
                for (int a = x + 1; a <= d - 3; a++) {
                    if (pivots.get(a).type() == pivots.get(x).type()) continue;
                    for (int b = a + 1; b <= d - 2; b++) {
                        if (pivots.get(b).type() != pivots.get(x).type()) continue;
                        for (int c = b + 1; c <= d - 1; c++) {
                            if (pivots.get(c).type() != pivots.get(a).type()
                                    || pivots.get(d).type() != pivots.get(x).type()) continue;
                            List<HarmonicPivot> geometry = List.of(
                                    pivots.get(x), pivots.get(a), pivots.get(b), pivots.get(c), pivots.get(d));
                            if (!scannedGeometries.add(geometryKey(geometry))) continue;
                            classify(geometry).ifPresent(formation -> bestByCompletion.merge(
                                    completionKey(formation), formation, this::betterFormation));
                        }
                    }
                }
            }
        }
    }

    private String hierarchyKey(List<HarmonicPivot> pivots) {
        StringBuilder key = new StringBuilder();
        for (HarmonicPivot pivot : pivots) key.append(pivot.timestamp()).append(':');
        return key.toString();
    }

    private String geometryKey(List<HarmonicPivot> geometry) {
        return geometry.stream().map(pivot -> Long.toString(pivot.timestamp()))
                .reduce((left, right) -> left + ":" + right).orElse("");
    }

    private Candidate gartley(List<HarmonicPivot> p, Direction direction, Ratios r) {
        HarmonicPatternType pattern = HarmonicPatternType.GARTLEY;
        if (!enabled(pattern)) return null;
        if (!insideCompletion(p, direction)
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886))
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cdOfBc(), ratio(pattern, "extensionMin", 1.13), ratio(pattern, "extensionMax", 1.618))) return null;
        Match b = closest(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bTarget", .618));
        Match d = closest(pattern, RuleGroup.COMPLETION, r.adOfXa(), ratio(pattern, "completion", .786));
        Match leg = legClosest(pattern, r.cdOfAb(),
                ratio(pattern, "legPrimary", 1.0), ratio(pattern, "legAlternate", 1.27));
        if (!b.accepted() || !d.accepted() || !leg.accepted()) return null;
        return candidate(pattern, b, d,
                secondaryError(r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886),
                        r.cdOfBc(), ratio(pattern, "extensionMin", 1.13), ratio(pattern, "extensionMax", 1.618),
                        leg.error()), 0.0, 0.0,
                "Inside completion; AB=CD or the 1.27 alternate AB=CD relationship is present.");
    }

    private Candidate bat(List<HarmonicPivot> p, Direction direction, Ratios r) {
        HarmonicPatternType pattern = HarmonicPatternType.BAT;
        if (!enabled(pattern)) return null;
        if (!insideCompletion(p, direction)
                || !within(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bMin", .382), ratio(pattern, "bMax", .5))
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886))
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cdOfBc(), ratio(pattern, "cdBcMin", 1.618), ratio(pattern, "cdBcMax", 2.618))) return null;
        Match b = rangeMatch(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bMin", .382),
                ratio(pattern, "bMax", .5), ratio(pattern, "bMax", .5));
        Match d = closest(pattern, RuleGroup.COMPLETION, r.adOfXa(), ratio(pattern, "completion", .886));
        Match leg = legClosest(pattern, r.cdOfAb(),
                ratio(pattern, "legPrimary", 1.0), ratio(pattern, "legAlternate", 1.27));
        if (!b.accepted() || !d.accepted() || !leg.accepted()) return null;
        return candidate(pattern, b, d,
                secondaryError(r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886),
                        r.cdOfBc(), ratio(pattern, "cdBcMin", 1.618), ratio(pattern, "cdBcMax", 2.618),
                        leg.error()), 0.0, 0.0,
                "Inside completion; AB=CD or the typical 1.27 alternate AB=CD relationship is present.");
    }

    private Candidate butterfly(List<HarmonicPivot> p, Direction direction, Ratios r) {
        HarmonicPatternType pattern = HarmonicPatternType.BUTTERFLY;
        if (!enabled(pattern)) return null;
        if (!outsideCompletion(p, direction)
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886))
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cdOfBc(), ratio(pattern, "extensionMin", 1.618), ratio(pattern, "extensionMax", 2.24))) return null;
        Match b = closest(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bTarget", .786));
        Match d = closest(pattern, RuleGroup.COMPLETION, r.adOfXa(), ratio(pattern, "completion", 1.27));
        Match leg = legClosest(pattern, r.cdOfAb(),
                ratio(pattern, "legPrimary", 1.0), ratio(pattern, "legAlternate", 1.27));
        if (!b.accepted() || !d.accepted() || !leg.accepted()) return null;
        return candidate(pattern, b, d,
                secondaryError(r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886),
                        r.cdOfBc(), ratio(pattern, "extensionMin", 1.618), ratio(pattern, "extensionMax", 2.24),
                        leg.error()), 0.0, 0.0,
                "1.27 XA completion; AB=CD or the 1.27 alternate AB=CD relationship is present.");
    }

    private Candidate crab(List<HarmonicPivot> p, Direction direction, Ratios r) {
        HarmonicPatternType pattern = HarmonicPatternType.CRAB;
        if (!enabled(pattern)) return null;
        if (!outsideCompletion(p, direction)
                || !within(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bMin", .382), ratio(pattern, "bMax", .618))
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886))
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.cdOfBc(), ratio(pattern, "extensionMin", 2.618), ratio(pattern, "extensionMax", 3.618))
                || !materiallyDifferent(pattern, r.cdOfAb())) return null;
        Match b = rangeMatch(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bMin", .382),
                ratio(pattern, "bMax", .618), ratio(pattern, "bMax", .618));
        Match d = closest(pattern, RuleGroup.COMPLETION, r.adOfXa(), ratio(pattern, "completion", 1.618));
        if (!b.accepted() || !d.accepted()) return null;
        return candidate(pattern, b, d,
                secondaryError(r.cOfAb(), ratio(pattern, "cMin", .382), ratio(pattern, "cMax", .886),
                        r.cdOfBc(), ratio(pattern, "extensionMin", 2.618), ratio(pattern, "extensionMax", 3.618),
                        differenceError(r.cdOfAb())), 0.0, 0.0,
                "Extreme 1.618 XA completion; CD is materially different from AB.");
    }

    private Candidate shark(List<HarmonicPivot> p, Direction direction, Ratios r) {
        HarmonicPatternType pattern = HarmonicPatternType.SHARK;
        if (!enabled(pattern)) return null;
        // Shark uses 0-X-A-B-C labels. Its terminal C is on the far side of A;
        // this corrects the contradictory 'C above A' draft rule and follows
        // the official 0.886 retracement / 1.13 extension completion geometry.
        if (!sharkPointOrder(p, direction)
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.sharkAOf0X(), ratio(pattern, "aMin", .32), ratio(pattern, "aMax", .618))
                || !within(pattern, RuleGroup.PRIMARY_B, r.sharkAbOfXa(), ratio(pattern, "bMin", 1.13), ratio(pattern, "bMax", 1.618))
                || !within(pattern, RuleGroup.SECONDARY_RATIOS, r.sharkBcOfAb(), ratio(pattern, "extensionMin", 1.618), ratio(pattern, "extensionMax", 2.24))) return null;
        Match b = rangeMatch(pattern, RuleGroup.PRIMARY_B, r.sharkAbOfXa(), ratio(pattern, "bMin", 1.13),
                ratio(pattern, "bMax", 1.618), ratio(pattern, "bMax", 1.618));
        Match d = closest(pattern, RuleGroup.COMPLETION, r.sharkCompletionOf0X(),
                ratio(pattern, "completionPrimary", .886), ratio(pattern, "completionStretch", 1.13));
        if (!b.accepted() || !d.accepted()) return null;
        return candidate(pattern, b, d,
                secondaryError(r.sharkAOf0X(), ratio(pattern, "aMin", .32), ratio(pattern, "aMax", .618),
                        r.sharkBcOfAb(), ratio(pattern, "extensionMin", 1.618), ratio(pattern, "extensionMax", 2.24), 0.0),
                0.0, d.target() == ratio(pattern, "completionPrimary", .886) ? 0.0 : .14,
                "0-X-A-B-C structure; C completes at the 0.886 retracement or 1.13 extension.");
    }

    private Candidate cypher(List<HarmonicPivot> p, Direction direction, Ratios r) {
        HarmonicPatternType pattern = HarmonicPatternType.CYPHER;
        if (!enabled(pattern)) return null;
        double extensionMinimum = ratio(pattern, "extensionMin", 1.272);
        double extensionMaximum = ratio(pattern, "extensionMax", 1.414);
        double extensionMaximumTolerance = hard(pattern, RuleGroup.SECONDARY_RATIOS)
                ? 0.0 : softTolerance(pattern);
        if (!cypherPointOrder(p, direction)
                || !within(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bMin", .382), ratio(pattern, "bMax", .618))
                || r.xcOfXa() < .0
                || r.xcOfXa() < extensionMinimum * (1.0 - tolerance(pattern, RuleGroup.SECONDARY_RATIOS))
                || r.xcOfXa() > extensionMaximum * (1.0 + extensionMaximumTolerance)) return null;
        Match b = rangeMatch(pattern, RuleGroup.PRIMARY_B, r.bOfXa(), ratio(pattern, "bMin", .382),
                ratio(pattern, "bMax", .618), ratio(pattern, "bMax", .618));
        Match d = closest(pattern, RuleGroup.COMPLETION, r.cdOfXc(), ratio(pattern, "completion", .786));
        if (!b.accepted() || !d.accepted()) return null;
        return candidate(pattern, b, d,
                rangeError(r.xcOfXa(), extensionMinimum, extensionMaximum), 0.0, 0.0,
                "C extends beyond A but never beyond 1.414 XA; D retraces 0.786 of XC.");
    }

    private Candidate candidate(HarmonicPatternType pattern,
                                Match primaryB,
                                Match primaryD,
                                double secondaryError,
                                double bStretchPenalty,
                                double dStretchPenalty,
                                String note) {
        double error = primaryD.error() * .45 + primaryB.error() * .35
                + secondaryError * .20 + bStretchPenalty + dStretchPenalty;
        return new Candidate(pattern, error, primaryB.error(), primaryD.error(), secondaryError,
                bStretchPenalty, dStretchPenalty, primaryB.target(), primaryD.target(), note);
    }

    private HarmonicFormation formation(Candidate candidate,
                                        List<HarmonicPivot> geometry,
                                        Direction direction,
                                        Ratios ratios) {
        String[] labels = candidate.pattern() == HarmonicPatternType.SHARK
                ? new String[]{"0", "X", "A", "B", "C"}
                : new String[]{"X", "A", "B", "C", "D"};
        List<HarmonicPoint> points = new ArrayList<>();
        for (int index = 0; index < geometry.size(); index++) {
            HarmonicPivot pivot = geometry.get(index);
            points.add(new HarmonicPoint(labels[index], pivot.timestamp(), pivot.price(), pivot.type()));
        }
        double bEarned = Math.clamp(35.0 - 24.0
                * (candidate.bError() * .35 + candidate.bStretchPenalty()), 0.0, 35.0);
        double completionEarned = Math.clamp(45.0 - 24.0
                * (candidate.completionError() * .45 + candidate.dStretchPenalty()), 0.0, 45.0);
        double secondaryEarned = Math.clamp(20.0 - 24.0
                * candidate.secondaryError() * .20, 0.0, 20.0);
        int quality = Math.clamp((int) Math.round(bEarned + completionEarned + secondaryEarned), 0, 100);
        Map<String, Double> measurements = new LinkedHashMap<>();
        measurements.put("B_XA", ratios.bOfXa());
        measurements.put("C_AB", ratios.cOfAb());
        measurements.put("CD_BC", ratios.cdOfBc());
        measurements.put("CD_AB", ratios.cdOfAb());
        measurements.put("AD_XA", ratios.adOfXa());
        if (candidate.pattern() == HarmonicPatternType.SHARK) {
            measurements.put("A_0X", ratios.sharkAOf0X());
            measurements.put("AB_XA", ratios.sharkAbOfXa());
            measurements.put("BC_AB", ratios.sharkBcOfAb());
            measurements.put("C_0X_COMPLETION", ratios.sharkCompletionOf0X());
        } else if (candidate.pattern() == HarmonicPatternType.CYPHER) {
            measurements.put("XC_XA", ratios.xcOfXa());
            measurements.put("CD_XC", ratios.cdOfXc());
        }
        HarmonicPivot terminal = geometry.getLast();
        String hardRuleEvidence = "Hard-rule gate: all enabled hard Fibonacci groups and all locked direction, "
                + "alternating-pivot, point-order, and completion-boundary rules passed.";
        String bEvidence = String.format(Locale.ROOT,
                "Primary B ratio +%s/35: measured %.4f versus preferred %.3f; normalized deviation %.4f",
                score(bEarned), primaryBRatio(candidate.pattern(), ratios), candidate.bTarget(), candidate.bError());
        String completionEvidence = String.format(Locale.ROOT,
                "Completion ratio +%s/45: measured %.4f versus preferred %.3f; normalized deviation %.4f",
                score(completionEarned), primaryCompletionRatio(candidate.pattern(), ratios),
                candidate.dTarget(), candidate.completionError());
        String secondaryEvidence = String.format(Locale.ROOT,
                "Secondary ratios +%s/20: secondary Fibonacci ranges and leg relationship; normalized deviation %.4f",
                score(secondaryEarned), candidate.secondaryError());
        return new HarmonicFormation(
                candidate.pattern(),
                direction == Direction.BULLISH ? TradeSignal.BUY : TradeSignal.SELL,
                direction,
                List.copyOf(points),
                terminal.confirmationTimestamp(),
                quality,
                candidate.classificationError(),
                Map.copyOf(measurements),
                List.of(bEvidence, completionEvidence, secondaryEvidence, hardRuleEvidence, candidate.note(),
                        String.format(Locale.ROOT,
                                "Primary B target %.3f; primary completion target %.3f.",
                                candidate.bTarget(), candidate.dTarget())),
                RULE_VERSION
        );
    }

    private double primaryBRatio(HarmonicPatternType pattern, Ratios ratios) {
        return pattern == HarmonicPatternType.SHARK ? ratios.sharkAbOfXa() : ratios.bOfXa();
    }

    private double primaryCompletionRatio(HarmonicPatternType pattern, Ratios ratios) {
        return switch (pattern) {
            case SHARK -> ratios.sharkCompletionOf0X();
            case CYPHER -> ratios.cdOfXc();
            default -> ratios.adOfXa();
        };
    }

    private String score(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        return rounded == Math.rint(rounded)
                ? Integer.toString((int) rounded)
                : String.format(Locale.ROOT, "%.1f", rounded);
    }

    private boolean validPivotOrder(List<HarmonicPivot> pivots) {
        for (int index = 0; index < pivots.size(); index++) {
            HarmonicPivot pivot = pivots.get(index);
            if (pivot == null || !Double.isFinite(pivot.price()) || pivot.price() <= 0.0
                    || pivot.timestamp() <= 0 || pivot.confirmationTimestamp() < pivot.timestamp()) return false;
            if (index > 0 && (pivot.index() <= pivots.get(index - 1).index()
                    || pivot.timestamp() <= pivots.get(index - 1).timestamp()
                    || pivot.type() == pivots.get(index - 1).type())) return false;
        }
        return true;
    }

    private Direction direction(List<HarmonicPivot> p) {
        boolean bullish = p.get(0).type() == PivotType.LOW
                && p.get(1).type() == PivotType.HIGH
                && p.get(2).type() == PivotType.LOW
                && p.get(3).type() == PivotType.HIGH
                && p.get(4).type() == PivotType.LOW
                && p.get(1).price() > p.get(0).price()
                && p.get(2).price() < p.get(1).price()
                && p.get(3).price() > p.get(2).price()
                && p.get(4).price() < p.get(3).price();
        boolean bearish = p.get(0).type() == PivotType.HIGH
                && p.get(1).type() == PivotType.LOW
                && p.get(2).type() == PivotType.HIGH
                && p.get(3).type() == PivotType.LOW
                && p.get(4).type() == PivotType.HIGH
                && p.get(1).price() < p.get(0).price()
                && p.get(2).price() > p.get(1).price()
                && p.get(3).price() < p.get(2).price()
                && p.get(4).price() > p.get(3).price();
        return bullish ? Direction.BULLISH : bearish ? Direction.BEARISH : null;
    }

    private boolean largeEnough(List<HarmonicPivot> p) {
        for (int index = 1; index < p.size(); index++) {
            double fraction = Math.abs(p.get(index).price() - p.get(index - 1).price())
                    / Math.abs(p.get(index - 1).price());
            if (fraction < rules.minimumSwingFraction()) return false;
        }
        return true;
    }

    private boolean insideCompletion(List<HarmonicPivot> p, Direction direction) {
        double x = p.get(0).price();
        double a = p.get(1).price();
        double c = p.get(3).price();
        double d = p.get(4).price();
        return direction == Direction.BULLISH
                ? c <= a && d > x && d < a
                : c >= a && d < x && d > a;
    }

    private boolean outsideCompletion(List<HarmonicPivot> p, Direction direction) {
        double x = p.get(0).price();
        double a = p.get(1).price();
        double c = p.get(3).price();
        double d = p.get(4).price();
        return direction == Direction.BULLISH
                ? c <= a && d < x
                : c >= a && d > x;
    }

    private boolean sharkPointOrder(List<HarmonicPivot> p, Direction direction) {
        double x = p.get(1).price();
        double a = p.get(2).price();
        double b = p.get(3).price();
        double c = p.get(4).price();
        return direction == Direction.BULLISH
                ? b > x && c < a && c <= x && c < b && c > 0.0
                : b < x && c > a && c >= x && c > b;
    }

    private boolean cypherPointOrder(List<HarmonicPivot> p, Direction direction) {
        double x = p.get(0).price();
        double a = p.get(1).price();
        double b = p.get(2).price();
        double c = p.get(3).price();
        double d = p.get(4).price();
        return direction == Direction.BULLISH
                ? c > a && c > b && d < b && x < a
                : c < a && c < b && d > b && x > a;
    }

    private Match closest(HarmonicPatternType pattern, RuleGroup group, double value, double... targets) {
        return closestWithTolerance(pattern, group, value, rules.fibonacciTolerance(), targets);
    }

    private Match legClosest(HarmonicPatternType pattern, double value, double... targets) {
        return closestWithTolerance(pattern, RuleGroup.LEG_RELATIONSHIP, value,
                rules.legEqualityTolerance(), targets);
    }

    private Match closestWithTolerance(HarmonicPatternType pattern,
                                       RuleGroup group,
                                       double value,
                                       double baseTolerance,
                                       double... targets) {
        double bestTarget = targets[0];
        double bestError = Double.POSITIVE_INFINITY;
        for (double target : targets) {
            double error = Math.abs(value - target) / (target * baseTolerance);
            if (error < bestError) {
                bestError = error;
                bestTarget = target;
            }
        }
        double permitted = hard(pattern, group)
                ? 1.0 : 1.0 + softTolerance(pattern) / baseTolerance;
        return new Match(bestError <= permitted, bestError, bestTarget);
    }

    private Match rangeMatch(HarmonicPatternType pattern, RuleGroup group, double value,
                             double idealMin, double idealMax, double stretchedMax) {
        if (!within(pattern, group, value, idealMin, stretchedMax)) {
            return new Match(false, Double.POSITIVE_INFINITY, idealMax);
        }
        double target = value < idealMin ? idealMin : value > idealMax ? idealMax : value;
        double error = value >= idealMin && value <= idealMax
                ? 0.0
                : Math.abs(value - target) / (Math.max(target, .000001) * rules.fibonacciTolerance());
        return new Match(true, error, target);
    }

    private boolean within(HarmonicPatternType pattern, RuleGroup group,
                           double value, double minimum, double maximum) {
        double tolerance = tolerance(pattern, group);
        return value >= minimum * (1.0 - tolerance)
                && value <= maximum * (1.0 + tolerance);
    }

    private boolean materiallyDifferent(HarmonicPatternType pattern, double cdOfAb) {
        double requiredDifference = rules.materialLegDifference()
                - (hard(pattern, RuleGroup.LEG_RELATIONSHIP) ? 0.0 : softTolerance(pattern));
        return Math.abs(cdOfAb - 1.0) >= Math.max(0.0, requiredDifference);
    }

    private double secondaryError(double firstValue,
                                  double firstMin,
                                  double firstMax,
                                  double secondValue,
                                  double secondMin,
                                  double secondMax,
                                  double thirdError) {
        return (rangeError(firstValue, firstMin, firstMax)
                + rangeError(secondValue, secondMin, secondMax)
                + thirdError) / 3.0;
    }

    private double rangeError(double value, double minimum, double maximum) {
        if (value >= minimum && value <= maximum) return 0.0;
        double boundary = value < minimum ? minimum : maximum;
        return Math.abs(value - boundary) / (boundary * rules.fibonacciTolerance());
    }

    private double differenceError(double value) {
        return Math.abs(value - 1.0) >= rules.materialLegDifference() ? 0.0 : 1.0;
    }

    private boolean enabled(HarmonicPatternType pattern) {
        return patternRules.profile(pattern).enabled();
    }

    private double ratio(HarmonicPatternType pattern, String key, double fallback) {
        return patternRules.profile(pattern).ratios().getOrDefault(key, fallback);
    }

    private boolean hard(HarmonicPatternType pattern, RuleGroup group) {
        return patternRules.profile(pattern).hardRules().getOrDefault(group, true);
    }

    private double softTolerance(HarmonicPatternType pattern) {
        return patternRules.profile(pattern).softViolationTolerance();
    }

    private double tolerance(HarmonicPatternType pattern, RuleGroup group) {
        return rules.fibonacciTolerance() + (hard(pattern, group) ? 0.0 : softTolerance(pattern));
    }

    private void addCandidate(List<Candidate> candidates, Candidate candidate) {
        if (candidate != null && Double.isFinite(candidate.classificationError())) candidates.add(candidate);
    }

    private String completionKey(HarmonicFormation formation) {
        HarmonicPoint terminal = formation.points().getLast();
        return formation.pattern() + ":" + formation.direction() + ":" + terminal.timestamp();
    }

    private HarmonicFormation betterFormation(HarmonicFormation first, HarmonicFormation second) {
        return first.classificationError() <= second.classificationError() ? first : second;
    }

    private List<Candle> normalize(List<Candle> historicalCandles) {
        if (historicalCandles == null) return List.of();
        return historicalCandles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null
                        && candle.getHighPrice() != null && candle.getLowPrice() != null
                        && candle.getHighPrice() > 0.0 && candle.getLowPrice() > 0.0)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
    }

    public record Rules(double fibonacciTolerance,
                        double legEqualityTolerance,
                        double materialLegDifference,
                        double minimumSwingFraction,
                        int pivotWindow,
                        int maximumFormations,
                        int maximumPivotWindow,
                        double maximumSwingFraction,
                        int maximumSkippedPivots) {
        public Rules(double fibonacciTolerance,
                     double legEqualityTolerance,
                     double materialLegDifference,
                     double minimumSwingFraction,
                     int pivotWindow,
                     int maximumFormations) {
            this(fibonacciTolerance, legEqualityTolerance, materialLegDifference,
                    minimumSwingFraction, pivotWindow, maximumFormations, 55, .34, 0);
        }

        public static Rules defaults() {
            return new Rules(.03, .03, .10, .005, 2, 250, 55, .34, 0);
        }

        private Rules validated() {
            if (!Double.isFinite(fibonacciTolerance) || fibonacciTolerance <= 0 || fibonacciTolerance > .10
                    || !Double.isFinite(legEqualityTolerance) || legEqualityTolerance <= 0 || legEqualityTolerance > .20
                    || !Double.isFinite(materialLegDifference) || materialLegDifference <= legEqualityTolerance
                    || !Double.isFinite(minimumSwingFraction) || minimumSwingFraction < 0 || minimumSwingFraction > .25
                    || pivotWindow < 1 || pivotWindow > 10 || maximumFormations < 1 || maximumFormations > 250
                    || maximumPivotWindow < pivotWindow || maximumPivotWindow > 89
                    || !Double.isFinite(maximumSwingFraction)
                    || maximumSwingFraction < Math.max(.05, minimumSwingFraction)
                    || maximumSwingFraction > .50
                    || maximumSkippedPivots < 0 || maximumSkippedPivots > 8) {
                throw new IllegalArgumentException("Invalid harmonic detection rules.");
            }
            return this;
        }
    }

    public enum RuleGroup {
        PRIMARY_B,
        COMPLETION,
        SECONDARY_RATIOS,
        LEG_RELATIONSHIP
    }

    public record PatternRuleSet(Map<HarmonicPatternType, PatternRuleProfile> profiles) {
        public PatternRuleSet {
            profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
        }

        public static PatternRuleSet defaults() {
            Map<HarmonicPatternType, PatternRuleProfile> defaults = new LinkedHashMap<>();
            for (HarmonicPatternType pattern : HarmonicPatternType.values()) {
                defaults.put(pattern, PatternRuleProfile.defaults());
            }
            return new PatternRuleSet(defaults);
        }

        public PatternRuleProfile profile(HarmonicPatternType pattern) {
            return profiles.getOrDefault(pattern, PatternRuleProfile.defaults());
        }

        private PatternRuleSet validated() {
            for (HarmonicPatternType pattern : HarmonicPatternType.values()) {
                profile(pattern).validated();
            }
            return this;
        }
    }

    public record PatternRuleProfile(boolean enabled,
                                     Map<String, Double> ratios,
                                     Map<RuleGroup, Boolean> hardRules,
                                     double softViolationTolerance) {
        public PatternRuleProfile {
            ratios = ratios == null ? Map.of() : Map.copyOf(ratios);
            hardRules = hardRules == null ? Map.of() : Map.copyOf(hardRules);
        }

        public static PatternRuleProfile defaults() {
            return new PatternRuleProfile(true, Map.of(), Map.of(
                    RuleGroup.PRIMARY_B, true,
                    RuleGroup.COMPLETION, true,
                    RuleGroup.SECONDARY_RATIOS, true,
                    RuleGroup.LEG_RELATIONSHIP, true), .05);
        }

        private PatternRuleProfile validated() {
            if (!Double.isFinite(softViolationTolerance)
                    || softViolationTolerance < 0.0 || softViolationTolerance > .25) {
                throw new IllegalArgumentException("Invalid harmonic soft-rule tolerance.");
            }
            for (Map.Entry<String, Double> entry : ratios.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || !Double.isFinite(entry.getValue())
                        || entry.getValue() < 0.0 || entry.getValue() > 10.0) {
                    throw new IllegalArgumentException("Invalid harmonic ratio override.");
                }
            }
            return this;
        }
    }

    public enum PivotType { HIGH, LOW }

    public enum Direction { BULLISH, BEARISH }

    public record HarmonicPivot(int index,
                                long timestamp,
                                double price,
                                PivotType type,
                                long confirmationTimestamp) {
    }

    public record HarmonicPoint(String label,
                                long timestamp,
                                double price,
                                PivotType pivotType) {
    }

    public record HarmonicFormation(HarmonicPatternType pattern,
                                    TradeSignal tradeSignal,
                                    Direction direction,
                                    List<HarmonicPoint> points,
                                    long confirmationTimestamp,
                                    int qualityScore,
                                    double classificationError,
                                    Map<String, Double> measurements,
                                    List<String> reasons,
                                    String ruleVersion) {
        public HarmonicFormation {
            points = List.copyOf(points);
            measurements = Map.copyOf(measurements);
            reasons = List.copyOf(reasons);
        }
    }

    private record Match(boolean accepted, double error, double target) {
    }

    private record Candidate(HarmonicPatternType pattern,
                             double classificationError,
                             double bError,
                             double completionError,
                             double secondaryError,
                             double bStretchPenalty,
                             double dStretchPenalty,
                             double bTarget,
                             double dTarget,
                             String note) {
    }

    private record Ratios(double bOfXa,
                          double cOfAb,
                          double cdOfBc,
                          double cdOfAb,
                          double adOfXa,
                          double xcOfXa,
                          double cdOfXc,
                          double sharkAOf0X,
                          double sharkAbOfXa,
                          double sharkBcOfAb,
                          double sharkCompletionOf0X) {
        private static Ratios from(List<HarmonicPivot> p) {
            double l01 = distance(p, 0, 1);
            double l12 = distance(p, 1, 2);
            double l23 = distance(p, 2, 3);
            double l34 = distance(p, 3, 4);
            double xc = distance(p, 0, 3);
            return new Ratios(
                    ratio(l12, l01),
                    ratio(l23, l12),
                    ratio(l34, l23),
                    ratio(l34, l12),
                    ratio(distance(p, 1, 4), l01),
                    ratio(xc, l01),
                    ratio(l34, xc),
                    ratio(l12, l01),
                    ratio(l23, l12),
                    ratio(l34, l23),
                    ratio(distance(p, 1, 4), l01)
            );
        }

        private static double distance(List<HarmonicPivot> p, int first, int second) {
            return Math.abs(p.get(second).price() - p.get(first).price());
        }

        private static double ratio(double numerator, double denominator) {
            return denominator <= 0.0 ? Double.NaN : numerator / denominator;
        }
    }
}
