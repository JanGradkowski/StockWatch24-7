package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.ActivityCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Condition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Group;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Operand;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.OutlookCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.PatternCondition;
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
     * some candles after the turn); {@code anchorAt} is the pattern's own candle or turning point.
     */
    public record PatternHit(String family, String code, String type, boolean bullish, int score, long availableAt,
                             long anchorAt, String status, Long resolvedAt, double price, Object source) { }

    public record OutlookFact(long candleTimestamp, String classification, double score) { }

    /** A filing: the day it became public, who made it, and its dollar amount (Congress: the range's lower bound). */
    public record ActivityHit(long id, LocalDate publishedOn, String person, String detail, String transaction,
                              Double amount, String amountLabel) { }

    /** The checked state of one condition, kept with every trigger as its evidence. */
    public record ConditionResult(String id, String kind, boolean passed, boolean applicable, Map<String, Object> facts) { }

    public record Result(boolean matched, boolean applicable, int matchedConditions, int totalConditions,
                         List<ConditionResult> conditions) { }

    public static Result evaluate(CustomAlertDefinition definition, Facts facts, int index) {
        List<ConditionResult> results = new ArrayList<>();
        List<Boolean> groupResults = new ArrayList<>();
        boolean applicable = true;
        for (Group group : definition.groups()) {
            List<Boolean> passed = new ArrayList<>();
            for (Condition condition : group.conditions()) {
                ConditionResult result = condition(condition, facts, index);
                results.add(result);
                applicable &= result.applicable();
                passed.add(result.passed());
            }
            groupResults.add("ANY".equals(group.match()) ? passed.contains(true) : !passed.contains(false));
        }
        boolean matched = applicable && ("ANY".equals(definition.match()) ? groupResults.contains(true) : !groupResults.contains(false));
        int count = (int) results.stream().filter(ConditionResult::passed).count();
        return new Result(matched, applicable, count, results.size(), List.copyOf(results));
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
        double[] compare = condition.compare() == null ? null
                : "VALUE".equals(condition.compare().kind()) ? constant(subject.length, condition.compare().value())
                : facts.series(condition.compare());
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
        for (PatternHit hit : facts.patterns(condition)) {
            if (hit.availableAt() < from || hit.availableAt() > to) continue;
            if (!"ANY".equals(condition.direction()) && hit.bullish() != "BULLISH".equals(condition.direction())) continue;
            if (!condition.patterns().isEmpty() && !condition.patterns().contains(hit.type())) continue;
            if (condition.minScore() != null && hit.score() < condition.minScore()) continue;
            if (condition.confirmedOnly() && !("CONFIRMED".equals(hit.status()) && hit.resolvedAt() != null && hit.resolvedAt() <= to)) continue;
            if (condition.stillValid() && !facts.stillValid(hit, index)) continue;
            if (best == null || hit.availableAt() > best.availableAt()
                    || hit.availableAt() == best.availableAt() && hit.score() > best.score()) {
                best = hit;
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (best != null) {
            details.put("pattern", best.code());
            details.put("type", best.type());
            details.put("bullish", best.bullish());
            details.put("score", best.score());
            details.put("detectedAt", best.availableAt());
            details.put("anchorAt", best.anchorAt());
            details.put("price", finite(best.price()));
            if (best.status() != null) details.put("status", best.status());
            details.put("candlesAgo", candlesBetween(candles, best.availableAt(), index));
        }
        return new ConditionResult(condition.id(), condition.kind(), best != null, true, details);
    }

    private static int candlesBetween(List<Candle> candles, long timestamp, int index) {
        int count = 0;
        for (int at = index; at >= 0 && candles.get(at).getTimestamp() > timestamp; at--) count++;
        return count;
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
