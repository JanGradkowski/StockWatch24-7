package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.ActivityCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Condition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Group;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Operand;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.OutlookCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.PatternCondition;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.ValueCondition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A rule and its evidence in plain English, for emails and the history kept with each trigger. Every
 * sentence follows a template in i18n/server-labels.txt, so emails go out translated (pages describe the
 * rule in the browser, in the reader's language).
 */
public final class CustomAlertText {
    private static final Set<String> CHART_ONLY_INPUTS = Set.of("offset", "upper", "lower");

    private CustomAlertText() { }

    /** One line per condition, grouped: "All of these:", "- RSI (14) is below 30", ... */
    public static String rule(CustomAlertDefinition definition) {
        return rule(definition, null);
    }

    /** As {@link #rule(CustomAlertDefinition)}; with the catalog, indicator settings left at their defaults are not listed. */
    public static String rule(CustomAlertDefinition definition, ChartIndicatorCatalog catalog) {
        List<String> lines = new ArrayList<>();
        boolean grouped = definition.groups().size() > 1;
        if (grouped) lines.add("ALL".equals(definition.match()) ? "Every group must be true:" : "At least one group must be true:");
        for (Group group : definition.groups()) {
            if (group.conditions().size() > 1) {
                lines.add(("ALL".equals(group.match()) ? "All of these:" : "Any of these:"));
            }
            for (Condition condition : group.conditions()) lines.add("- " + describe(condition, catalog));
        }
        return String.join("\n", lines);
    }

    public static String describe(Condition condition) {
        return describe(condition, null);
    }

    static String describe(Condition condition, ChartIndicatorCatalog catalog) {
        String text = switch (condition) {
            case ValueCondition value -> value(value, catalog);
            case PatternCondition pattern -> pattern(pattern);
            case OutlookCondition outlook -> outlook(outlook);
            case ActivityCondition activity -> activity(activity);
        };
        return condition.negate() ? "Not: " + text : text;
    }

    private static String value(ValueCondition condition, ChartIndicatorCatalog catalog) {
        String subject = operand(condition.subject(), catalog);
        String sentence = switch (condition.operator()) {
            case "ABOVE" -> subject + " is above " + operand(condition.compare(), catalog);
            case "BELOW" -> subject + " is below " + operand(condition.compare(), catalog);
            case "CROSSES_ABOVE" -> subject + " crosses above " + operand(condition.compare(), catalog);
            case "CROSSES_BELOW" -> subject + " crosses below " + operand(condition.compare(), catalog);
            case "BETWEEN" -> subject + " is between " + number(condition.lower()) + " and " + number(condition.upper());
            case "OUTSIDE" -> subject + " is outside " + number(condition.lower()) + " to " + number(condition.upper());
            case "RISING" -> subject + " rises for " + condition.candles() + " candles in a row";
            case "FALLING" -> subject + " falls for " + condition.candles() + " candles in a row";
            case "CHANGE_ABOVE" -> subject + " rises at least " + number(condition.percent()) + "% over " + condition.candles() + " candles";
            case "CHANGE_BELOW" -> subject + " falls at least " + number(condition.percent()) + "% over " + condition.candles() + " candles";
            case "NEW_HIGH" -> subject + " makes a new " + condition.candles() + "-candle high";
            case "NEW_LOW" -> subject + " makes a new " + condition.candles() + "-candle low";
            default -> subject;
        };
        return switch (condition.window().mode()) {
            case "ANY" -> condition.window().candles() > 1
                    ? sentence + " at least once in the last " + condition.window().candles() + " candles" : sentence;
            case "EVERY" -> sentence + " on each of the last " + condition.window().candles() + " candles";
            default -> sentence;
        };
    }

    static String operand(Operand operand) {
        return operand(operand, null);
    }

    static String operand(Operand operand, ChartIndicatorCatalog catalog) {
        if (operand == null) return "";
        return switch (operand.kind()) {
            case "VALUE" -> number(operand.value());
            case "PRICE" -> switch (operand.field()) {
                case "OPEN" -> "Open price";
                case "HIGH" -> "High price";
                case "LOW" -> "Low price";
                case "VOLUME" -> "Volume";
                default -> "Close price";
            };
            default -> indicatorLabel(operand, catalog);
        };
    }

    /**
     * "RSI (14)", "MACD signal (12, 9)": the indicator, its line when it has several, its numbers. With the catalog
     * the main length is always named and other settings only when changed from their defaults.
     */
    static String indicatorLabel(Operand operand, ChartIndicatorCatalog catalog) {
        Map<String, Object> defaults = new HashMap<>();
        String length = null;
        IndicatorDefinition definition = catalog == null ? null : catalog.find(operand.type()).orElse(null);
        if (definition != null) {
            for (var input : definition.inputs()) {
                defaults.put(input.key(), input.defaultValue());
                if (length == null && input.kind() == IndicatorDefinition.InputKind.INTEGER) length = input.key();
            }
        }
        String main = length;
        String numbers = operand.parameters().entrySet().stream()
                // "upper"/"lower" are the chart's shaded zone levels (RSI 70/30): they never change the line.
                .filter(entry -> entry.getValue() instanceof Number && !CHART_ONLY_INPUTS.contains(entry.getKey()))
                .filter(entry -> definition == null || entry.getKey().equals(main) || !sameNumber(entry.getValue(), defaults.get(entry.getKey())))
                .map(entry -> number(((Number) entry.getValue()).doubleValue()))
                .limit(4).collect(Collectors.joining(", "));
        String name = operand.type().replace('_', ' ');
        String output = operand.output() == null || "value".equals(operand.output())
                || operand.output().equalsIgnoreCase(operand.type()) ? "" : " " + operand.output();
        Object timeframe = operand.parameters().get("timeframe");
        String suffix = timeframe == null || "chart".equals(timeframe) ? "" : "1wk".equals(timeframe) ? ", weekly" : ", monthly";
        return name + output + (numbers.isEmpty() ? "" : " (" + numbers + suffix + ")");
    }

    private static boolean sameNumber(Object value, Object fallback) {
        return value instanceof Number number && fallback instanceof Number other && number.doubleValue() == other.doubleValue();
    }

    /**
     * "Bullish Elliott Wave signal detected in the last 5 candles. Patterns: Wave II end. Score at least 60."
     * Short sentences, so each is translated on its own.
     */
    private static String pattern(PatternCondition condition) {
        String family = switch (condition.family()) {
            case "ELLIOTT_WAVE" -> "Elliott Wave signal";
            case "HARMONIC_FORMATION" -> "harmonic pattern";
            default -> "candlestick pattern";
        };
        String lead = switch (condition.direction()) {
            case "BULLISH" -> "Bullish " + family;
            case "BEARISH" -> "Bearish " + family;
            default -> Character.toUpperCase(family.charAt(0)) + family.substring(1);
        };
        List<String> sentences = new ArrayList<>();
        sentences.add(lead + " detected in the last " + condition.window().span() + " candles.");
        if (!condition.patterns().isEmpty()) {
            sentences.add("Patterns: " + condition.patterns().stream().map(name -> patternName(condition.family(), name))
                    .collect(Collectors.joining(", ")) + ".");
        }
        if (condition.minScore() != null) sentences.add("Score at least " + condition.minScore() + ".");
        if (condition.confirmedOnly()) sentences.add("Confirmed patterns only.");
        if (condition.stillValid()) sentences.add("Stop not breached.");
        return String.join(" ", sentences);
    }

    static String patternName(String family, String name) {
        return switch (family) {
            case "ELLIOTT_WAVE" -> switch (name) {
                case "WAVE_II_END" -> "Wave II end";
                case "WAVE_III_END" -> "Wave III end";
                case "WAVE_IV_END" -> "Wave IV end";
                case "WAVE_V_END" -> "Wave V end";
                default -> "correction end";
            };
            case "HARMONIC_FORMATION" -> HarmonicPatternType.valueOf(name).displayName();
            default -> title(CandlePattern.valueOf(name).name());
        };
    }

    private static String outlook(OutlookCondition condition) {
        return "Automated outlook at least " + condition.strength().toLowerCase(Locale.ROOT) + " "
                + ("BUY".equals(condition.direction()) ? "buy" : "sell");
    }

    /** "Insiders bought in the last 30 days. At least 2 people. At least $250,000 in total." */
    private static String activity(ActivityCondition condition) {
        boolean insider = "INSIDER".equals(condition.source());
        String who = insider ? "Insiders" : "Members of Congress";
        String what = "PURCHASE".equals(condition.transaction()) ? "bought" : "sold";
        List<String> sentences = new ArrayList<>();
        sentences.add(who + " " + what + " in the last " + condition.days() + " days.");
        if (condition.minCount() > 1) sentences.add("At least " + condition.minCount() + " people.");
        if (condition.minTotal() != null && condition.minTotal() > 0) {
            sentences.add("At least $" + String.format(Locale.ROOT, "%,.0f", condition.minTotal()) + " in total.");
        }
        if (!condition.roles().isEmpty()) {
            sentences.add("Roles: " + condition.roles().stream().map(role -> switch (role) {
                case "OFFICER" -> "officers";
                case "DIRECTOR" -> "directors";
                default -> "10% owners";
            }).collect(Collectors.joining(", ")) + ".");
        }
        if (!condition.chambers().isEmpty() && condition.chambers().size() < 2) {
            sentences.add(condition.chambers().contains("SENATE") ? "Senate only." : "House only.");
        }
        return String.join(" ", sentences);
    }

    static String number(Double value) {
        if (value == null || !Double.isFinite(value)) return "";
        double rounded = Math.round(value * 10_000d) / 10_000d;
        return rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15
                ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /** BULLISH_ENGULFING as "Bullish Engulfing", the interface's own name for it. */
    private static String title(String name) {
        return java.util.Arrays.stream(name.toLowerCase(Locale.ROOT).split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1)).collect(Collectors.joining(" "));
    }
}
