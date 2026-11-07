package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.ActivityCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Condition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Group;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Operand;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.OutlookCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.PatternCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Sequence;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Step;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.ValueCondition;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a rule holds on one completed candle, and records why.
 *
 * <p>Every value is read at or before the evaluated candle, so the answer for a candle never changes when
 * later candles arrive. A condition that cannot apply to an instrument (filings for an ETF) makes the
 * whole result <em>not applicable</em> instead of quietly false.</p>
 */
public final class CustomAlertEvaluator {
    private CustomAlertEvaluator() { }

    /** The data one stock offers a rule, aligned with its completed candles. */
    public interface Facts {
        TimeInterval interval();

        /** Completed candles of the alert's interval, oldest first. */
        List<Candle> candles();

        /** The operand's value on each candle; NaN where it is not known yet. */
        double[] series(Operand operand);

        /** Every detection of the condition's family for this stock, under the owner's detection settings. */
        List<PatternHit> patterns(PatternCondition condition);

        /** Whether a harmonic formation still sits between its stop and first target on the candle. */
        boolean stillValid(PatternHit hit, int index);

        /** The automated outlook of the latest candle, or null when unavailable. */
        OutlookFact outlook();

        /** Insider or congressional filings for this stock; empty when there are none. */
        List<ActivityHit> activity(String source);

        /** Insider and congressional filings exist only for stocks. */
        boolean stock();
    }

    /**
     * One detected pattern. {@code availableAt} is the candle on which it became known (a wave end is known
     * some candles after the turn); {@code anchorAt} is the pattern's own candle or turning point. In a replay
     * a redrawn Elliott count can first appear later than its own confirmation: {@code knownAt} is that first
     * candle, before which the hit does not exist.
     */
    public record PatternHit(String family, String code, String type, boolean bullish, int score, long availableAt,
                             long anchorAt, String status, Long resolvedAt, double price, Object source, long knownAt) {
        public PatternHit(String family, String code, String type, boolean bullish, int score, long availableAt,
                          long anchorAt, String status, Long resolvedAt, double price, Object source) {
            this(family, code, type, bullish, score, availableAt, anchorAt, status, resolvedAt, price, source, availableAt);
        }
    }

    public record OutlookFact(long candleTimestamp, String classification, double score) { }

    /** A filing: the day it became public, who made it, and its dollar amount (Congress: the range's lower bound). */
    public record ActivityHit(long id, LocalDate publishedOn, String person, String detail, String transaction,
                              Double amount, String amountLabel) { }

    /** The checked state of one condition, kept with every trigger as its evidence. */
    public record ConditionResult(String id, String kind, boolean passed, boolean applicable, Map<String, Object> facts) { }

    /** One group's outcome; a sequence also says when it completed ({@code facts} is empty for all/any groups). */
    public record GroupResult(String id, String match, boolean passed, Map<String, Object> facts) { }

    public record Result(boolean matched, boolean applicable, int matchedConditions, int totalConditions,
                         List<ConditionResult> conditions, List<GroupResult> groups) { }

    public static Result evaluate(CustomAlertDefinition definition, Facts facts, int index) {
        List<ConditionResult> results = new ArrayList<>();
        List<GroupResult> groupResults = new ArrayList<>();
        boolean applicable = true;
        for (Group group : definition.groups()) {
            if (group.sequence() != null) {
                SequenceResult sequence = sequence(group, facts, index);
                results.addAll(sequence.steps());
                groupResults.add(new GroupResult(group.id(), group.match(), sequence.passed(), sequence.facts()));
                continue;
            }
            List<Boolean> passed = new ArrayList<>();
            for (Condition condition : group.conditions()) {
                ConditionResult result = condition(condition, facts, index);
                results.add(result);
                applicable &= result.applicable();
                passed.add(result.passed());
            }
            groupResults.add(new GroupResult(group.id(), group.match(),
                    "ANY".equals(group.match()) ? passed.contains(true) : !passed.contains(false), Map.of()));
        }
        List<Boolean> outcomes = groupResults.stream().map(GroupResult::passed).toList();
        boolean matched = applicable && ("ANY".equals(definition.match()) ? outcomes.contains(true) : !outcomes.contains(false));
        int count = (int) results.stream().filter(ConditionResult::passed).count();
        return new Result(matched, applicable, count, results.size(), List.copyOf(results), List.copyOf(groupResults));
    }

    static ConditionResult condition(Condition condition, Facts facts, int index) {
        ConditionResult raw = switch (condition) {
            case ValueCondition value -> value(value, facts, index);
            case PatternCondition pattern -> pattern(pattern, facts, index);
            case OutlookCondition outlook -> outlook(outlook, facts, index);
            case ActivityCondition activity -> activity(activity, facts, index);
        };
        if (!condition.negate() || !raw.applicable()) return raw;
        Map<String, Object> negated = new LinkedHashMap<>(raw.facts());
        negated.put("negated", true);
        return new ConditionResult(raw.id(), raw.kind(), !raw.passed(), true, negated);
    }

    // ------------------------------------------------------------------ values

    private static ConditionResult value(ValueCondition condition, Facts facts, int index) {
        double[] subject = facts.series(condition.subject());
        double[] compare = compareSeries(condition, facts, subject.length);
        String mode = condition.window().mode();
        int span = condition.window().span();
        Integer matchedAt = null;
        boolean every = "EVERY".equals(mode);
        boolean passed = every;
        for (int offset = 0; offset < span; offset++) {
            int at = index - offset;
            boolean holds = at >= 0 && holds(condition, subject, compare, at);
            if (every) {
                if (!holds) { passed = false; break; }
            } else if (holds) {
                passed = true;
                matchedAt = at;
                break;
            }
        }
        int shown = matchedAt != null ? matchedAt : index;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("value", finite(at(subject, shown)));
        if (compare != null) details.put("compare", finite(at(compare, shown)));
        if (condition.lower() != null) details.put("lower", condition.lower());
        if (condition.upper() != null) details.put("upper", condition.upper());
        if (condition.percent() != null) {
            int from = shown - condition.candles();
            details.put("change", from >= 0 ? finite(change(at(subject, from), at(subject, shown))) : null);
        }
        if (!facts.candles().isEmpty() && shown >= 0 && shown < facts.candles().size()) {
            details.put("at", facts.candles().get(shown).getTimestamp());
        }
        if (!Double.isFinite(at(subject, index))) details.put("unavailable", true);
        return new ConditionResult(condition.id(), condition.kind(), passed, true, details);
    }

    private static double[] compareSeries(ValueCondition condition, Facts facts, int length) {
        if (condition.compare() == null) return null;
        return "VALUE".equals(condition.compare().kind()) ? constant(length, condition.compare().value()) : facts.series(condition.compare());
    }

    static boolean holds(ValueCondition condition, double[] subject, double[] compare, int at) {
        double now = at(subject, at);
        if (!Double.isFinite(now)) return false;
        return switch (condition.operator()) {
            case "ABOVE" -> finiteBoth(now, at(compare, at)) && now > at(compare, at);
            case "BELOW" -> finiteBoth(now, at(compare, at)) && now < at(compare, at);
            case "CROSSES_ABOVE" -> at > 0 && finiteBoth(at(subject, at - 1), at(compare, at - 1)) && finiteBoth(now, at(compare, at))
                    && at(subject, at - 1) <= at(compare, at - 1) && now > at(compare, at);
            case "CROSSES_BELOW" -> at > 0 && finiteBoth(at(subject, at - 1), at(compare, at - 1)) && finiteBoth(now, at(compare, at))
                    && at(subject, at - 1) >= at(compare, at - 1) && now < at(compare, at);
            case "BETWEEN" -> now >= condition.lower() && now <= condition.upper();
            case "OUTSIDE" -> now < condition.lower() || now > condition.upper();
            case "RISING", "FALLING" -> {
                int count = condition.candles();
                if (at - count < 0) yield false;
                for (int step = 0; step < count; step++) {
                    double later = at(subject, at - step);
                    double earlier = at(subject, at - step - 1);
                    if (!finiteBoth(later, earlier)) yield false;
                    if ("RISING".equals(condition.operator()) ? later <= earlier : later >= earlier) yield false;
                }
                yield true;
            }
            case "CHANGE_ABOVE", "CHANGE_BELOW" -> {
                int from = at - condition.candles();
                if (from < 0) yield false;
                double change = change(at(subject, from), now);
                if (!Double.isFinite(change)) yield false;
                yield "CHANGE_ABOVE".equals(condition.operator()) ? change >= condition.percent() : change <= -condition.percent();
            }
            case "NEW_HIGH", "NEW_LOW" -> {
                int count = condition.candles();
                if (at - count < 0) yield false;
                for (int previous = at - count; previous < at; previous++) {
                    double earlier = at(subject, previous);
                    if (!Double.isFinite(earlier)) yield false;
                    if ("NEW_HIGH".equals(condition.operator()) ? now <= earlier : now >= earlier) yield false;
                }
                yield true;
            }
            default -> false;
        };
    }

    // ------------------------------------------------------------------ patterns

    private static ConditionResult pattern(PatternCondition condition, Facts facts, int index) {
        List<Candle> candles = facts.candles();
        int first = Math.max(0, index - condition.window().span() + 1);
        long from = candles.get(first).getTimestamp();
        long to = candles.get(index).getTimestamp();
        PatternHit best = null;
        long bestAt = 0;
        for (PatternHit hit : facts.patterns(condition)) {
            Long at = countsAt(condition, hit);
            if (at == null || at < from || at > to || hit.knownAt() > to) continue;
            if (condition.stillValid() && !facts.stillValid(hit, index)) continue;
            if (best == null || at > bestAt || at == bestAt && hit.score() > best.score()) {
                best = hit;
                bestAt = at;
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (best != null) {
            details.putAll(hitFacts(best, condition.confirmed(), bestAt));
            details.put("candlesAgo", candlesBetween(candles, bestAt, index));
        }
        return new ConditionResult(condition.id(), condition.kind(), best != null, true, details);
    }

    /** The time a hit counts on for this condition, or null when the hit is not one the condition asks for. */
    private static Long countsAt(PatternCondition condition, PatternHit hit) {
        boolean confirmed = condition.confirmed();
        if (confirmed && !("CONFIRMED".equals(hit.status()) && hit.resolvedAt() != null)) return null;
        if (!"ANY".equals(condition.direction()) && hit.bullish() != "BULLISH".equals(condition.direction())) return null;
        if (!condition.patterns().isEmpty() && !condition.patterns().contains(hit.type())) return null;
        if (condition.minScore() != null && hit.score() < condition.minScore()) return null;
        // A confirmed condition counts the candle that reached the target; any other, the one it became known on.
        return confirmed ? hit.resolvedAt() : hit.availableAt();
    }

    private static Map<String, Object> hitFacts(PatternHit hit, boolean confirmed, long at) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pattern", hit.code());
        details.put("type", hit.type());
        details.put("bullish", hit.bullish());
        details.put("score", hit.score());
        details.put("detectedAt", hit.availableAt());
        if (confirmed) details.put("confirmedAt", at);
        details.put("anchorAt", hit.anchorAt());
        details.put("price", finite(hit.price()));
        if (hit.status() != null) details.put("status", hit.status());
        return details;
    }

    private static int candlesBetween(List<Candle> candles, long timestamp, int index) {
        int count = 0;
        for (int at = index; at >= 0 && candles.get(at).getTimestamp() > timestamp; at--) count++;
        return count;
    }

    // ------------------------------------------------------------------ sequences

    private record SequenceResult(boolean passed, List<ConditionResult> steps, Map<String, Object> facts) { }

    private static final int NONE = Integer.MIN_VALUE;

    /**
     * A THEN group: its steps happened in order, each within its allowance after the one before, all within the span,
     * the last one on the evaluated candle or within the window before it (docs/custom-alert-sequences-plan.md, "Precise
     * rules"). When it did not, the steps say how far it got.
     *
     * <p>Each step is read once on every candle a chain ending in the window could use. Then, oldest candle first, the
     * search keeps for each step and candle the latest first candle of a valid chain that reaches the step there
     * ({@code reached}) or still holds it there ({@code holding}). A later start is always the better one, because only
     * the chain's span depends on it. Candles are counted from {@code from}.</p>
     */
    private static SequenceResult sequence(Group group, Facts facts, int index) {
        Sequence sequence = group.sequence();
        List<Condition> conditions = group.conditions();
        List<Step> steps = sequence.steps();
        int count = conditions.size();
        int window = sequence.window().span();
        int from = Math.max(0, index - window - sequence.span() + 2);
        int size = index - from + 1;
        boolean[][] truth = new boolean[count][];
        int[][] run = new int[count][size];
        for (int step = 0; step < count; step++) {
            truth[step] = truth(conditions.get(step), facts, from, index);
            for (int at = 0; at < size; at++) run[step][at] = truth[step][at] ? (at > 0 ? run[step][at - 1] : 0) + 1 : 0;
        }
        int[][] reached = new int[count][size];
        int[][] holding = new int[count][size];
        // For "reached": where the previous step was last true. For "holding": the candle the step was reached on.
        int[][] previousEnd = new int[count][size];
        int[][] reachedOn = new int[count][size];
        for (int step = 0; step < count; step++) {
            int need = steps.get(step).candles();
            int lastReach = NONE;
            for (int at = 0; at < size; at++) {
                reached[step][at] = NONE;
                previousEnd[step][at] = NONE;
                if (run[step][at] >= need) {
                    int start = at - need + 1;
                    if (step == 0) {
                        reached[step][at] = start;
                    } else {
                        // The previous step was last true at most "within" candles before this stretch starts, and no
                        // stretch of this step was complete in between: a step counts the first time after the last.
                        int low = Math.max(0, Math.max(start - steps.get(step).within(), lastReach == NONE ? 0 : lastReach - need + 1));
                        for (int end = start - 1; end >= low; end--) {
                            if (holding[step - 1][end] > reached[step][at]) {
                                reached[step][at] = holding[step - 1][end];
                                previousEnd[step][at] = end;
                            }
                        }
                    }
                    lastReach = at;
                }
                holding[step][at] = reached[step][at];
                reachedOn[step][at] = at;
                if (at > 0 && truth[step][at] && holding[step][at - 1] > holding[step][at]) {
                    holding[step][at] = holding[step][at - 1];
                    reachedOn[step][at] = reachedOn[step][at - 1];
                }
            }
        }

        int last = count - 1;
        int completed = NONE;
        for (int at = size - 1; at >= 0 && completed == NONE; at--) {
            if (reached[last][at] != NONE && reached[last][at] >= at - sequence.span() + 1) completed = at;
        }
        List<Candle> candles = facts.candles();
        int now = size - 1;
        List<ConditionResult> results = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        if (completed != NONE && completed > now - window) {
            int[][] chain = chain(previousEnd, reachedOn, last, completed, completed);
            details.put("completedAt", candles.get(from + completed).getTimestamp());
            details.put("candlesAgo", now - completed);
            details.put("span", completed - (chain[0][0] - steps.getFirst().candles() + 1) + 1);
            for (int step = 0; step < count; step++) {
                results.add(stepResult(conditions.get(step), true, stepFacts(conditions.get(step), steps.get(step), facts, from, index,
                        truth[step], chain[step][0], chain[step][1], step == 0 ? null : chain[step - 1][1])));
            }
            return new SequenceResult(true, results, details);
        }

        // Not complete in the window: how far a chain that can still complete got. A chain whose next step already had its
        // chance is over (below), so one that completed earlier is not reported again.
        int since = completed == NONE ? -1 : completed;
        for (int step = last - 1; step >= 0; step--) {
            int end = NONE;
            for (int at = now; at >= 0 && end == NONE; at--) if (holding[step][at] != NONE) end = at;
            if (end == NONE) continue;
            Step next = steps.get(step + 1);
            // The next step already had its one chance after this one (a step counts the first time it is reached after
            // the step before): this chain moved on, or died there; it is not still waiting here.
            boolean consumed = false;
            for (int at = end + next.candles(); at <= now && !consumed; at++) consumed = run[step + 1][at] >= next.candles();
            if (consumed) continue;
            int remaining = 0;
            for (int later = step + 1; later < count; later++) remaining += steps.get(later).candles();
            // The next step must start within its allowance and early enough for the rest to fit in the span.
            int deadline = Math.min(end + next.within(), holding[step][end] + sequence.span() - remaining);
            int nextRun = run[step + 1][now];
            boolean building = nextRun > 0 && now - nextRun + 1 > end && now - nextRun + 1 <= deadline;
            if (deadline <= now && !building) continue;
            int[][] chain = chain(previousEnd, reachedOn, step, reachedOn[step][end], end);
            for (int done = 0; done <= step; done++) {
                results.add(stepResult(conditions.get(done), true, stepFacts(conditions.get(done), steps.get(done), facts, from, index,
                        truth[done], chain[done][0], chain[done][1], done == 0 ? null : chain[done - 1][1])));
            }
            Map<String, Object> waiting = new LinkedHashMap<>();
            waiting.put("waiting", true);
            if (deadline > now) waiting.put("candlesLeft", deadline - now);
            if (building) waiting.put("soFar", Math.min(nextRun, next.candles()));
            waiting.put("needed", next.candles());
            results.add(stepResult(conditions.get(step + 1), false, waiting));
            for (int later = step + 2; later < count; later++) results.add(stepResult(conditions.get(later), false, Map.of()));
            details.put("done", step + 1);
            if (deadline > now) details.put("candlesLeft", deadline - now);
            return new SequenceResult(false, results, details);
        }
        int firstRun = run[0][now];
        if (completed != NONE && !(firstRun > 0 && now - firstRun + 1 > since)) {
            details.put("completedAt", candles.get(from + completed).getTimestamp());
            details.put("candlesAgo", now - completed);
            details.put("outsideWindow", true);
            for (Condition condition : conditions) results.add(stepResult(condition, false, Map.of()));
            return new SequenceResult(false, results, details);
        }
        Map<String, Object> first = new LinkedHashMap<>();
        if (firstRun > 0) first.put("soFar", Math.min(firstRun, steps.getFirst().candles()));
        first.put("needed", steps.getFirst().candles());
        results.add(stepResult(conditions.getFirst(), false, first));
        for (int later = 1; later < count; later++) results.add(stepResult(conditions.get(later), false, Map.of()));
        details.put("done", 0);
        return new SequenceResult(false, results, details);
    }

    private static ConditionResult stepResult(Condition condition, boolean passed, Map<String, Object> facts) {
        Map<String, Object> details = facts;
        if (condition.negate()) {
            details = new LinkedHashMap<>(facts);
            details.put("negated", true);
        }
        return new ConditionResult(condition.id(), condition.kind(), passed, true, details);
    }

    /** For steps 0..{@code step}: the candle each was reached on and the candle it was last true before the next one. */
    private static int[][] chain(int[][] previousEnd, int[][] reachedOn, int step, int reach, int end) {
        int[][] chain = new int[step + 1][];
        chain[step] = new int[]{reach, end};
        for (int at = step; at > 0; at--) {
            int before = previousEnd[at][chain[at][0]];
            chain[at - 1] = new int[]{reachedOn[at - 1][before], before};
        }
        return chain;
    }

    /** Whether a step's condition holds on each candle from {@code from} to {@code index}, as known on {@code index}. */
    private static boolean[] truth(Condition condition, Facts facts, int from, int index) {
        boolean[] truth = new boolean[index - from + 1];
        switch (condition) {
            case ValueCondition value -> {
                double[] subject = facts.series(value.subject());
                double[] compare = compareSeries(value, facts, subject.length);
                // A candle whose values are not known yet (an indicator warming up) is false either way, so it breaks a run.
                for (int at = from; at <= index; at++) {
                    truth[at - from] = decidable(value, subject, compare, at) && holds(value, subject, compare, at) != value.negate();
                }
            }
            case PatternCondition pattern -> {
                for (PatternHit hit : facts.patterns(pattern)) {
                    int at = stepCandle(pattern, hit, facts, from, index);
                    if (at >= 0) truth[at - from] = true;
                }
            }
            default -> { }
        }
        return truth;
    }

    /**
     * Whether every value the test reads on candle {@code at} is known: the candle itself, the one before for a cross,
     * every candle of a rise, fall or new high, and the two ends of a change.
     */
    static boolean decidable(ValueCondition condition, double[] subject, double[] compare, int at) {
        int back = switch (condition.operator()) {
            case "CROSSES_ABOVE", "CROSSES_BELOW" -> 1;
            case "RISING", "FALLING", "CHANGE_ABOVE", "CHANGE_BELOW", "NEW_HIGH", "NEW_LOW" -> condition.candles();
            default -> 0;
        };
        if (at - back < 0) return false;
        boolean comparesAlong = CustomAlertDefinition.COMPARISONS.contains(condition.operator());
        boolean endsOnly = condition.operator().startsWith("CHANGE_");
        for (int candle = at - back; candle <= at; candle++) {
            if (endsOnly && candle != at - back && candle != at) continue;
            if (!Double.isFinite(at(subject, candle)) || comparesAlong && !Double.isFinite(at(compare, candle))) return false;
        }
        return true;
    }

    /**
     * The candle a pattern step counts a hit on, or -1: the hit must be one the condition asks for, count on a candle
     * from {@code from} to {@code index}, be known by {@code index} and (when asked) not have hit its stop by then.
     */
    private static int stepCandle(PatternCondition condition, PatternHit hit, Facts facts, int from, int index) {
        List<Candle> candles = facts.candles();
        Long at = countsAt(condition, hit);
        long to = candles.get(index).getTimestamp();
        if (at == null || at < candles.get(from).getTimestamp() || at > to || hit.knownAt() > to) return -1;
        if (condition.stillValid() && !facts.stillValid(hit, index)) return -1;
        int low = from;
        int high = index;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (candles.get(middle).getTimestamp() <= at) low = middle; else high = middle - 1;
        }
        return low;
    }

    /** What a completed step shows: when it held (its whole unbroken stretch), and its values or pattern. */
    private static Map<String, Object> stepFacts(Condition condition, Step step, Facts facts, int from, int index, boolean[] truth,
                                                 int reach, int end, Integer previousEnd) {
        List<Candle> candles = facts.candles();
        int start = reach - step.candles() + 1;
        int runStart = start;
        int floor = previousEnd == null ? 0 : previousEnd + 1;
        while (runStart - 1 >= floor && truth[runStart - 1]) runStart--;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", candles.get(from + runStart).getTimestamp());
        details.put("to", candles.get(from + end).getTimestamp());
        details.put("at", candles.get(from + reach).getTimestamp());
        details.put("candles", end - runStart + 1);
        if (previousEnd != null) details.put("after", start - previousEnd);
        switch (condition) {
            case ValueCondition value -> {
                double[] subject = facts.series(value.subject());
                details.put("value", finite(at(subject, from + reach)));
                if (value.compare() != null && !"VALUE".equals(value.compare().kind())) {
                    details.put("compare", finite(at(facts.series(value.compare()), from + reach)));
                }
                double low = Double.POSITIVE_INFINITY;
                double high = Double.NEGATIVE_INFINITY;
                for (int at = runStart; at <= end; at++) {
                    double current = at(subject, from + at);
                    if (!Double.isFinite(current)) continue;
                    low = Math.min(low, current);
                    high = Math.max(high, current);
                }
                if (Double.isFinite(low)) {
                    details.put("low", finite(low));
                    details.put("high", finite(high));
                }
            }
            case PatternCondition pattern -> {
                PatternHit best = null;
                for (PatternHit hit : facts.patterns(pattern)) {
                    if (stepCandle(pattern, hit, facts, from, index) == from + reach && (best == null || hit.score() > best.score())) best = hit;
                }
                if (best != null) details.putAll(hitFacts(best, pattern.confirmed(), countsAt(pattern, best)));
            }
            default -> { }
        }
        return details;
    }

    // ------------------------------------------------------------------ outlook

    private static ConditionResult outlook(OutlookCondition condition, Facts facts, int index) {
        OutlookFact outlook = facts.outlook();
        Map<String, Object> details = new LinkedHashMap<>();
        long at = facts.candles().get(index).getTimestamp();
        // The outlook is computed for the latest candle only; an older candle has no reading.
        if (outlook == null || outlook.candleTimestamp() != at) {
            details.put("unavailable", true);
            return new ConditionResult(condition.id(), condition.kind(), false, true, details);
        }
        details.put("classification", outlook.classification());
        details.put("score", finite(outlook.score() * 100));
        String text = outlook.classification().toLowerCase(Locale.ROOT);
        boolean direction = text.contains("BUY".equals(condition.direction()) ? " buy" : " sell");
        int rank = text.startsWith("strong") ? 3 : text.startsWith("moderate") ? 2 : text.startsWith("slight") ? 1 : 0;
        int required = switch (condition.strength()) {
            case "STRONG" -> 3;
            case "MODERATE" -> 2;
            default -> 1;
        };
        return new ConditionResult(condition.id(), condition.kind(), direction && rank >= required, true, details);
    }

    // ------------------------------------------------------------------ filings

    private static ConditionResult activity(ActivityCondition condition, Facts facts, int index) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (!facts.stock()) {
            details.put("notApplicable", true);
            return new ConditionResult(condition.id(), condition.kind(), false, false, details);
        }
        LocalDate asOf = periodEnd(facts.candles().get(index).getTimestamp(), facts.interval());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (asOf.isAfter(today)) asOf = today;
        LocalDate from = asOf.minusDays(condition.days() - 1L);
        Set<String> people = new LinkedHashSet<>();
        double total = 0;
        List<Long> ids = new ArrayList<>();
        for (ActivityHit hit : facts.activity(condition.source())) {
            if (!condition.transaction().equals(hit.transaction())) continue;
            if (hit.publishedOn().isBefore(from) || hit.publishedOn().isAfter(asOf)) continue;
            if (!condition.roles().isEmpty() && !roleMatches(hit.detail(), condition.roles())) continue;
            if (!condition.chambers().isEmpty() && !condition.chambers().contains(chamber(hit.detail()))) continue;
            people.add(hit.person().trim().toUpperCase(Locale.ROOT));
            if (hit.amount() != null) total += hit.amount();
            ids.add(hit.id());
        }
        boolean passed = !ids.isEmpty() && people.size() >= condition.minCount()
                && (condition.minTotal() == null || total >= condition.minTotal());
        details.put("filings", ids.size());
        details.put("people", people.size());
        details.put("total", total);
        details.put("ids", ids.size() > 20 ? ids.subList(0, 20) : ids);
        details.put("from", from.toString());
        details.put("to", asOf.toString());
        return new ConditionResult(condition.id(), condition.kind(), passed, true, details);
    }

    /** The last calendar day a candle covers: its own day, its week, or its month. */
    static LocalDate periodEnd(long timestamp, TimeInterval interval) {
        LocalDate day = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
        return switch (interval) {
            case WEEKLY -> day.plusDays(6);
            case MONTHLY -> day.with(TemporalAdjusters.lastDayOfMonth());
            default -> day;
        };
    }

    /** Insider roles as filed ("Chief Executive Officer", "Director", "10% Owner") in three groups. */
    static boolean roleMatches(String filed, List<String> roles) {
        String role = filed == null ? "" : filed.toLowerCase(Locale.ROOT);
        boolean officer = role.contains("officer") || role.contains("ceo") || role.contains("cfo") || role.contains("coo")
                || role.contains("president") || role.contains("chief") || role.contains("vp") || role.contains("treasurer")
                || role.contains("secretary");
        boolean director = role.contains("director");
        boolean owner = role.contains("10%") || role.contains("owner");
        return roles.contains("OFFICER") && officer || roles.contains("DIRECTOR") && director || roles.contains("OWNER") && owner;
    }

    static String chamber(String filed) {
        String value = filed == null ? "" : filed.toLowerCase(Locale.ROOT);
        return value.contains("senat") ? "SENATE" : "HOUSE";
    }

    // ------------------------------------------------------------------ helpers

    private static double[] constant(int size, double value) {
        double[] values = new double[size];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static double at(double[] values, int index) {
        return values == null || index < 0 || index >= values.length ? Double.NaN : values[index];
    }

    private static boolean finiteBoth(double first, double second) {
        return Double.isFinite(first) && Double.isFinite(second);
    }

    private static double change(double from, double to) {
        return Double.isFinite(from) && Double.isFinite(to) && from != 0 ? (to - from) / Math.abs(from) * 100 : Double.NaN;
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? Math.round(value * 10_000d) / 10_000d : null;
    }
}
