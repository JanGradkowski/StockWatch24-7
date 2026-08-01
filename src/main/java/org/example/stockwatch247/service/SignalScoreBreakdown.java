package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TradeSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the compact, persisted score-reason format into presentation-friendly
 * sections. Keeping this conversion at the presentation boundary lets existing
 * alert events remain readable without changing the stored scoring evidence.
 */
final class SignalScoreBreakdown {
    private static final Pattern SCORED_REASON = Pattern.compile(
            "^(.+?)\\s+\\+([0-9]+(?:\\.[0-9]+)?)/([0-9]+(?:\\.[0-9]+)?):\\s*(.+)$"
    );
    private static final Pattern DETAIL_SCORE = Pattern.compile(
            "\\s*\\(\\+([0-9]+(?:\\.[0-9]+)?)/([0-9]+(?:\\.[0-9]+)?)(?:\\s+[^)]*)?\\)"
    );
    private static final Pattern PROFILE_PREFIX = Pattern.compile(
            "^(daily|weekly|monthly) profile:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SETTINGS_PREFIX = Pattern.compile(
            "^(Bollinger\\([^)]*\\)):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EMA_PAIR = Pattern.compile(
            "EMA\\((\\d+)\\)/EMA\\((\\d+)\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EMA_PERIOD = Pattern.compile("EMA\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SMA_PERIOD = Pattern.compile("SMA\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MACD_PERIODS = Pattern.compile("MACD\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RSI_PERIOD = Pattern.compile("RSI\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CCI_PERIOD = Pattern.compile("CCI\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOLLINGER_SETTINGS = Pattern.compile(
            "Bollinger\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern VWAP_PERIOD = Pattern.compile("VWAP\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME_AVERAGE_PERIOD = Pattern.compile(
            "(\\d+)-bar average",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern VOLUME_PROFILE_PERIOD = Pattern.compile(
            "(\\d+)-bar OHLCV volume-profile",
            Pattern.CASE_INSENSITIVE
    );

    private SignalScoreBreakdown() {
    }

    static Section parse(String reason, String fallbackCategory, TradeSignal direction) {
        String normalizedReason = reason == null ? "" : reason.trim();
        Matcher scoreMatcher = SCORED_REASON.matcher(normalizedReason);
        if (!scoreMatcher.matches()) {
            return new Section(
                    fallbackCategory == null || fallbackCategory.isBlank() ? "Evidence" : fallbackCategory,
                    null,
                    null,
                    "Evidence",
                    List.of(new Detail("Observation", sentence(normalizedReason), null)),
                    false
            );
        }

        String category = scoreMatcher.group(1).trim();
        String earned = scoreMatcher.group(2);
        String maximum = scoreMatcher.group(3);
        List<Detail> details = parseDetails(scoreMatcher.group(4), direction);
        double earnedValue = Double.parseDouble(earned);
        double maximumValue = Double.parseDouble(maximum);
        String status = earnedValue <= 0.0
                ? "No supporting points"
                : earnedValue >= maximumValue
                ? "Full score"
                : "Partial support";
        return new Section(category, earned, maximum, status, details, true);
    }

    static String formatEmail(List<String> reasons, TradeSignal direction) {
        if (reasons == null || reasons.isEmpty()) {
            return "- Detailed scoring evidence was not available.";
        }

        StringBuilder result = new StringBuilder();
        for (String reason : reasons) {
            Section section = parse(reason, "Evidence", direction);
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append("- ").append(section.category());
            if (section.scored()) {
                result.append(": ").append(section.earned()).append('/').append(section.maximum())
                        .append(" (").append(section.status().toLowerCase(Locale.ROOT)).append(')');
            }
            for (Detail detail : section.details()) {
                result.append("\n  - ").append(detail.label());
                if (detail.score() != null) {
                    result.append(" [").append(detail.score()).append(" pts]");
                }
                result.append(": ").append(detail.text());
            }
        }
        return result.toString();
    }

    private static List<Detail> parseDetails(String compactDetails, TradeSignal direction) {
        List<Detail> details = new ArrayList<>();
        IndicatorContext indicatorContext = IndicatorContext.from(compactDetails);
        String[] fragments = compactDetails.split(";\\s*");
        for (String rawFragment : fragments) {
            String fragment = rawFragment.trim();
            if (fragment.isEmpty()) {
                continue;
            }

            Matcher profileMatcher = PROFILE_PREFIX.matcher(fragment);
            if (profileMatcher.matches()) {
                details.add(new Detail(
                        "Indicator profile",
                        sentence(capitalize(profileMatcher.group(1)) + " settings were used"),
                        null
                ));
                fragment = profileMatcher.group(2).trim();
            }

            Matcher settingsMatcher = SETTINGS_PREFIX.matcher(fragment);
            if (settingsMatcher.matches()) {
                details.add(new Detail(
                        "Settings",
                        sentence(settingsMatcher.group(1).replace(",", ", ")),
                        null
                ));
                fragment = settingsMatcher.group(2).trim();
            }

            Matcher detailScoreMatcher = DETAIL_SCORE.matcher(fragment);
            String detailScore = null;
            if (detailScoreMatcher.find()) {
                detailScore = detailScoreMatcher.group(1) + "/" + detailScoreMatcher.group(2);
                fragment = detailScoreMatcher.replaceAll("").trim();
            }

            details.add(new Detail(
                    detailLabel(fragment, indicatorContext),
                    sentence(humanizeAlignment(fragment, direction)),
                    detailScore
            ));
        }
        return List.copyOf(details);
    }

    private static String detailLabel(String detail, IndicatorContext context) {
        String normalized = detail.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("all mandatory")) {
            return "Pattern rules";
        }
        if (normalized.startsWith("established ")
                || normalized.startsWith("compact basing")
                || normalized.startsWith("compact topping")) {
            return "Prior trend";
        }
        if (normalized.startsWith("ema(") && normalized.contains("slope")) {
            return indicatorName(detail, EMA_PERIOD, 1, "EMA") + " slope";
        }
        if (normalized.startsWith("ema(")) {
            return context.fastEma() != null && context.slowEma() != null
                    ? context.fastEma() + " vs " + context.slowEma()
                    : "EMA alignment";
        }
        if (normalized.startsWith("fast ema")) {
            return context.fastEma() == null ? "Fast EMA slope" : context.fastEma() + " slope";
        }
        if (normalized.startsWith("slow ema")) {
            return context.slowEma() == null ? "Slow EMA slope" : context.slowEma() + " slope";
        }
        if (normalized.startsWith("close versus sma")) {
            return context.sma() == null ? "SMA position" : context.sma() + " position";
        }
        if (normalized.startsWith("macd") && normalized.contains("histogram")) {
            return context.macd() == null ? "MACD histogram" : context.macd() + " histogram";
        }
        if (normalized.startsWith("macd(") || normalized.startsWith("macd line")) {
            return context.macd() == null ? "MACD line vs signal" : context.macd() + " line vs signal";
        }
        if (normalized.startsWith("completed weekly")
                || normalized.startsWith("completed monthly")
                || normalized.startsWith("completed quarterly")) {
            return "Higher timeframe";
        }
        if (normalized.startsWith("rsi(") && normalized.contains("change")) {
            return indicatorName(detail, RSI_PERIOD, 1, "RSI") + " change";
        }
        if (normalized.startsWith("rsi(")) {
            return (context.rsi() == null ? "RSI" : context.rsi()) + " level";
        }
        if (normalized.startsWith("rsi change")) {
            return (context.rsi() == null ? "RSI" : context.rsi()) + " change";
        }
        if (normalized.startsWith("cci(") && normalized.contains("change")) {
            return indicatorName(detail, CCI_PERIOD, 1, "CCI") + " change";
        }
        if (normalized.startsWith("cci(")) {
            return (context.cci() == null ? "CCI" : context.cci()) + " level";
        }
        if (normalized.startsWith("cci change")) {
            return (context.cci() == null ? "CCI" : context.cci()) + " change";
        }
        if (normalized.contains("tested the lower band") || normalized.contains("tested the upper band")) {
            return contextualLabel(context.bollinger(), "band test", "Bollinger band test");
        }
        if (normalized.startsWith("bollinger %b")) {
            return contextualLabel(context.bollinger(), "%B position", "Bollinger %B position");
        }
        if (normalized.startsWith("the close moved back inside")) {
            return contextualLabel(context.bollinger(), "re-entry", "Bollinger re-entry");
        }
        if (normalized.startsWith("bandwidth")) {
            return contextualLabel(context.bollinger(), "band width", "Bollinger band width");
        }
        if (normalized.startsWith("pattern extreme")) {
            return "Level proximity";
        }
        if (normalized.contains("prior level touch")) {
            return "Level history";
        }
        if (normalized.startsWith("close rejected")) {
            return "Price rejection";
        }
        if (normalized.startsWith("volume was") || normalized.startsWith("relative volume")) {
            return context.volumeAveragePeriod() == null
                    ? "Relative volume"
                    : "Relative volume (" + context.volumeAveragePeriod() + "-bar average)";
        }
        if (normalized.startsWith("close versus rolling vwap")) {
            return contextualLabel(context.vwap(), "position", "VWAP position");
        }
        if (normalized.startsWith("rolling vwap") && normalized.contains("slope")) {
            return contextualLabel(context.vwap(), "slope", "VWAP slope");
        }
        if (normalized.contains("point-of-control")) {
            return context.volumeProfilePeriod() == null
                    ? "Volume profile"
                    : context.volumeProfilePeriod() + "-bar volume profile";
        }
        if (normalized.startsWith("close was") && normalized.contains("value-area")) {
            return context.volumeProfilePeriod() == null
                    ? "Value area"
                    : context.volumeProfilePeriod() + "-bar value area";
        }
        if (normalized.contains("volume-profile")) {
            return context.volumeProfilePeriod() == null
                    ? "Volume profile"
                    : context.volumeProfilePeriod() + "-bar volume profile";
        }
        if (normalized.contains("unavailable")) {
            return "Data availability";
        }
        return "Observation";
    }

    private static String contextualLabel(String indicator, String suffix, String fallback) {
        return indicator == null ? fallback : indicator + " " + suffix;
    }

    private static String indicatorName(String detail,
                                        Pattern pattern,
                                        int group,
                                        String fallback) {
        Matcher matcher = pattern.matcher(detail);
        return matcher.find() ? fallback + "(" + matcher.group(group) + ")" : fallback;
    }

    private static String humanizeAlignment(String detail, TradeSignal direction) {
        String directionLabel = direction == TradeSignal.BUY ? "bullish" : "bearish";
        return detail
                .replace("was not aligned with the signal", "did not support the " + directionLabel + " direction")
                .replace("was aligned with the signal", "supported the " + directionLabel + " direction");
    }

    private static String sentence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "Not available.";
        }
        String capitalized = capitalize(trimmed);
        return capitalized.endsWith(".") ? capitalized : capitalized + ".";
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record IndicatorContext(
            String fastEma,
            String slowEma,
            String sma,
            String macd,
            String rsi,
            String cci,
            String bollinger,
            String vwap,
            String volumeAveragePeriod,
            String volumeProfilePeriod
    ) {
        private static IndicatorContext from(String details) {
            Matcher emaMatcher = EMA_PAIR.matcher(details);
            String fastEma = null;
            String slowEma = null;
            if (emaMatcher.find()) {
                fastEma = "EMA(" + emaMatcher.group(1) + ")";
                slowEma = "EMA(" + emaMatcher.group(2) + ")";
            }
            return new IndicatorContext(
                    fastEma,
                    slowEma,
                    matchedIndicator(SMA_PERIOD, details, "SMA"),
                    matchedIndicator(MACD_PERIODS, details, "MACD"),
                    matchedIndicator(RSI_PERIOD, details, "RSI"),
                    matchedIndicator(CCI_PERIOD, details, "CCI"),
                    matchedIndicator(BOLLINGER_SETTINGS, details, "Bollinger"),
                    matchedIndicator(VWAP_PERIOD, details, "VWAP"),
                    matchedValue(VOLUME_AVERAGE_PERIOD, details),
                    matchedValue(VOLUME_PROFILE_PERIOD, details)
            );
        }

        private static String matchedIndicator(Pattern pattern, String details, String name) {
            String value = matchedValue(pattern, details);
            return value == null ? null : name + "(" + value.replace(",", ", ") + ")";
        }

        private static String matchedValue(Pattern pattern, String details) {
            Matcher matcher = pattern.matcher(details);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    record Section(
            String category,
            String earned,
            String maximum,
            String status,
            List<Detail> details,
            boolean scored
    ) {
        Section {
            details = List.copyOf(details);
        }

        String scoreLabel() {
            return scored ? earned + "/" + maximum : null;
        }
    }

    record Detail(String label, String text, String score) {
    }
}
