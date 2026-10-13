package org.example.stockwatch247.service.chartindicator;

import java.util.List;

/** The bar values an indicator can read, named as in TradingView's source input. */
public final class PriceSource {
    public static final List<String> KEYS = List.of("close", "open", "high", "low", "hl2", "hlc3", "ohlc4", "hlcc4");
    /** A source taken from another indicator of the same request: "ind:{indicatorId}:{outputKey}". */
    public static final String DEPENDENCY_PREFIX = "ind:";

    private PriceSource() { }

    public static boolean isPrice(String key) { return KEYS.contains(key); }

    public static boolean isDependency(String key) {
        return key != null && key.startsWith(DEPENDENCY_PREFIX) && key.indexOf(':', DEPENDENCY_PREFIX.length()) > DEPENDENCY_PREFIX.length();
    }

    /** The indicator id a dependency source refers to. */
    public static String dependencyId(String key) {
        return key.substring(DEPENDENCY_PREFIX.length(), key.indexOf(':', DEPENDENCY_PREFIX.length()));
    }

    public static String dependencyOutput(String key) {
        return key.substring(key.indexOf(':', DEPENDENCY_PREFIX.length()) + 1);
    }

    static double[] values(String key, double[] open, double[] high, double[] low, double[] close) {
        int size = close.length;
        double[] result = new double[size];
        for (int index = 0; index < size; index++) {
            result[index] = switch (key) {
                case "open" -> open[index];
                case "high" -> high[index];
                case "low" -> low[index];
                case "hl2" -> (high[index] + low[index]) / 2;
                case "hlc3" -> (high[index] + low[index] + close[index]) / 3;
                case "ohlc4" -> (open[index] + high[index] + low[index] + close[index]) / 4;
                case "hlcc4" -> (high[index] + low[index] + 2 * close[index]) / 4;
                default -> close[index];
            };
        }
        return result;
    }
}
