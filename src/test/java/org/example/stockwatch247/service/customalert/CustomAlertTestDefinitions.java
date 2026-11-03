package org.example.stockwatch247.service.customalert;

import java.util.List;

/** Rule JSON for tests: the 25 alerts of the heaviest account the limits allow, and the pieces to build more. */
public final class CustomAlertTestDefinitions {
    public static final String CLOSE = "{\"kind\":\"PRICE\",\"field\":\"CLOSE\"}";

    private CustomAlertTestDefinitions() { }

    public static String value(String subject, String operator, String rest) {
        return "{\"kind\":\"VALUE\",\"subject\":" + subject + ",\"operator\":\"" + operator + "\"" + (rest.isEmpty() ? "" : "," + rest) + "}";
    }

    public static String ind(String type, String parameters, String output) {
        return "{\"kind\":\"INDICATOR\",\"type\":\"" + type + "\",\"parameters\":" + parameters + ",\"output\":\"" + output + "\"}";
    }

    public static String num(double value) {
        return "\"compare\":{\"kind\":\"VALUE\",\"value\":" + value + "}";
    }

    public static String pattern(String family, String direction, String extra) {
        return "{\"kind\":\"PATTERN\",\"family\":\"" + family + "\",\"direction\":\"" + direction + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** Groups of conditions; conditions get their ids here. */
    public static String rule(String match, List<List<String>> groups, List<String> groupMatch) {
        StringBuilder text = new StringBuilder("{\"version\":1,\"match\":\"" + match + "\",\"groups\":[");
        int condition = 0;
        for (int group = 0; group < groups.size(); group++) {
            if (group > 0) text.append(',');
            text.append("{\"id\":\"g").append(group + 1).append("\",\"match\":\"").append(groupMatch.get(group)).append("\",\"conditions\":[");
            List<String> conditions = groups.get(group);
            for (int index = 0; index < conditions.size(); index++) {
                if (index > 0) text.append(',');
                String body = conditions.get(index);
                text.append("{\"id\":\"c").append(++condition).append("\",").append(body, 1, body.length());
            }
            text.append("]}");
        }
        return text.append("]}").toString();
    }

    public static String all(String... conditions) {
        return rule("ALL", List.of(List.of(conditions)), List.of("ALL"));
    }

    public static String any(String... conditions) {
        return rule("ALL", List.of(List.of(conditions)), List.of("ANY"));
    }

    /** Name, trigger mode and rule of each of the 25 alerts. */
    public static List<String[]> twentyFiveAlerts() {
        String sma = ind("SMA", "{\"period\":50}", "value");
        String sma200 = ind("SMA", "{\"period\":200}", "value");
        String rsi = ind("RSI", "{\"period\":14}", "value");
        String macd = ind("MACD", "{}", "histogram");
        String compare = "\"compare\":";
        return List.of(
                new String[]{"Close above SMA 50", "BECOMES_TRUE", all(value(CLOSE, "ABOVE", compare + sma))},
                new String[]{"EMA 20 crosses above EMA 50", "BECOMES_TRUE", all(value(ind("EMA", "{\"period\":20}", "value"), "CROSSES_ABOVE",
                        compare + ind("EMA", "{\"period\":50}", "value")))},
                new String[]{"RSI below 30", "BECOMES_TRUE", all(value(rsi, "BELOW", num(30)))},
                new String[]{"RSI crosses above 70", "BECOMES_TRUE", all(value(rsi, "CROSSES_ABOVE", num(70)))},
                new String[]{"MACD histogram crosses above 0", "BECOMES_TRUE", all(value(macd, "CROSSES_ABOVE", num(0)))},
                new String[]{"Close above upper Bollinger band", "BECOMES_TRUE", all(value(CLOSE, "ABOVE", compare + ind("BOLLINGER", "{}", "upper")))},
                new String[]{"Close crosses above Supertrend", "BECOMES_TRUE", all(value(CLOSE, "CROSSES_ABOVE", compare + ind("SUPERTREND", "{}", "middle")))},
                new String[]{"Strong trend above SMA 200", "BECOMES_TRUE", all(value(ind("ADX_DMI", "{}", "adx"), "ABOVE", num(25)),
                        value(CLOSE, "ABOVE", compare + sma200))},
                new String[]{"Close above 20-week SMA", "BECOMES_TRUE", all(value(CLOSE, "ABOVE", compare + ind("SMA", "{\"period\":20,\"timeframe\":\"1wk\"}", "value")))},
                new String[]{"Up 5% in 5 candles", "BECOMES_TRUE", all(value(CLOSE, "CHANGE_ABOVE", "\"candles\":5,\"percent\":5"))},
                new String[]{"New 20-candle high or low", "BECOMES_TRUE", any(value(CLOSE, "NEW_HIGH", "\"candles\":20"), value(CLOSE, "NEW_LOW", "\"candles\":20"))},
                new String[]{"RSI rising for 3 candles", "BECOMES_TRUE", all(value(rsi, "RISING", "\"candles\":3"))},
                new String[]{"Any candlestick pattern", "BECOMES_TRUE", all(pattern("CANDLESTICK", "ANY", "\"window\":{\"mode\":\"ANY\",\"candles\":1}"))},
                new String[]{"Confirmed bullish candlestick, 5 candles", "BECOMES_TRUE", all(pattern("CANDLESTICK", "BULLISH",
                        "\"confirmedOnly\":true,\"window\":{\"mode\":\"ANY\",\"candles\":5}"))},
                new String[]{"Elliott Wave signal, 3 candles", "BECOMES_TRUE", all(pattern("ELLIOTT_WAVE", "ANY", "\"window\":{\"mode\":\"ANY\",\"candles\":3}"))},
                new String[]{"Bullish Elliott Wave, score 60", "BECOMES_TRUE", all(pattern("ELLIOTT_WAVE", "BULLISH",
                        "\"minScore\":60,\"window\":{\"mode\":\"ANY\",\"candles\":5}"))},
                new String[]{"Harmonic pattern", "BECOMES_TRUE", all(pattern("HARMONIC_FORMATION", "ANY", "\"window\":{\"mode\":\"ANY\",\"candles\":1}"))},
                new String[]{"Harmonic, stop not breached", "BECOMES_TRUE", all(pattern("HARMONIC_FORMATION", "ANY",
                        "\"stillValid\":true,\"window\":{\"mode\":\"ANY\",\"candles\":10}"))},
                new String[]{"Outlook at least moderate buy", "BECOMES_TRUE", all("{\"kind\":\"OUTLOOK\",\"direction\":\"BUY\",\"strength\":\"MODERATE\"}")},
                new String[]{"Insider purchases, 30 days", "BECOMES_TRUE", all("{\"kind\":\"INSIDER\",\"transaction\":\"PURCHASE\",\"days\":30}")},
                new String[]{"Insider sales by 2 people, 90 days", "BECOMES_TRUE", all("{\"kind\":\"INSIDER\",\"transaction\":\"SALE\",\"days\":90,\"minCount\":2}")},
                new String[]{"Congress purchases, 45 days", "BECOMES_TRUE", all("{\"kind\":\"CONGRESS\",\"transaction\":\"PURCHASE\",\"days\":45}")},
                new String[]{"Pullback in uptrend or MACD turn", "BECOMES_TRUE", rule("ANY", List.of(
                        List.of(value(rsi, "BELOW", num(40)), value(CLOSE, "ABOVE", compare + sma200)),
                        List.of(value(macd, "CROSSES_ABOVE", num(0)))), List.of("ALL", "ALL"))},
                new String[]{"Every close", "EVERY_CANDLE", all(value(CLOSE, "ABOVE", num(0.0001)))},
                new String[]{"Below SMA 50 with neutral RSI", "BECOMES_TRUE", all("{\"negate\":true," + value(CLOSE, "ABOVE", compare + sma).substring(1),
                        value(rsi, "BETWEEN", "\"lower\":40,\"upper\":60"))});
    }
}
