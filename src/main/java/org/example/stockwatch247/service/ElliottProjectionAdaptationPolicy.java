package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.ElliottProjectionScenario;
import org.example.stockwatch247.model.ElliottProjectionSet;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.model.enums.ElliottProjectionScenarioStatus;

import java.util.ArrayList;
import java.util.List;

/** Conservative engineering thresholds, not calibrated probabilities. All input ends at the decision candle. */
final class ElliottProjectionAdaptationPolicy {
    static final int REQUIRED_CLOSES = 3;
    static final int REVISION_COOLDOWN = 5;
    static final int PROMOTION_MARGIN = 8;
    private ElliottProjectionAdaptationPolicy() { }

    record Decision(String deviation, int streak, int structuralSupport,
                    List<ElliottProjectionPolicy.ProjectedPoint> path,
                    int minimumCandles, int maximumCandles, boolean unresolved, String reason) {
        boolean revised() { return !path.isEmpty(); }
    }

    static Decision assess(ElliottProjectionSet set, ElliottProjectionScenario scenario,
                           List<Candle> history, int elapsed, TimeInterval interval) {
        Candle latest = history.getLast();
        double distance = scenario.getTargetMidpoint() - set.getSourcePrice();
        double sign = scenario.getExpectedMove() == TradeSignal.BUY ? 1 : -1;
        double atr = recentAtr(history);
        if (!Double.isFinite(atr) || atr <= 0) atr = Math.abs(distance) * .05;
        double tolerance = Math.max(1.5 * atr, Math.abs(distance) * .18);
        var swings = confirmedSwings(history, set.getSourceTimestamp());
        int support = structuralSupport(scenario.getScenarioKey(), swings, set.getSourcePrice(), sign, atr);
        var current = ElliottProjectionService.parsePath(scenario.getProjectedPath());
        if (current.size() < 2 || Math.abs(distance) < .000001) return keep(support);

        int lifetime = lifetime(scenario);
        if (elapsed >= lifetime) {
            return new Decision("EXHAUSTED", REQUIRED_CLOSES, support, List.of(), 0, 0, true,
                    "No supported endpoint within the bounded monitoring window; awaiting a validated wave stage.");
        }
        double expected = priceAt(current, elapsed);
        double error = sign * (latest.getClosePrice() - expected);
        String deviation = support < 0 ? "STRUCTURE"
                : support > 0 && (scenario.getStatus() == ElliottProjectionScenarioStatus.UNRESOLVED
                        || scenario.getStatus() == ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION
                        || scenario.getRevisions().size() <= 1) ? "SUPPORTED_ALTERNATIVE"
                : elapsed > scenario.getMaximumCandles() ? "TIMING"
                : error > tolerance ? "FASTER"
                : error < -tolerance ? "SLOWER" : null;
        if (deviation == null) return keep(support);
        int streak = deviation.equals(scenario.getDeviationKind()) ? scenario.getDeviationStreak() + 1 : 1;
        if (streak < REQUIRED_CLOSES || elapsed - scenario.getLastRevisionCandleCount() < REVISION_COOLDOWN) {
            return new Decision(deviation, streak, support, List.of(), 0, 0, false, "Awaiting sustained evidence.");
        }
        double progress = (latest.getClosePrice() - set.getSourcePrice()) / distance;
        if ("STRUCTURE".equals(deviation) || progress <= .1 || progress >= 1) {
            return new Decision(deviation, streak, support, List.of(), 0, 0, true,
                    "Observed development no longer supports this path; awaiting a supported alternative or wave confirmation.");
        }
        // Estimate the remaining time from progress without resetting the original wave's age.
        int remaining = Math.max(3, (int) Math.ceil(elapsed * (1 - progress) / progress));
        int maximum = Math.min(lifetime, elapsed + remaining);
        if (maximum - elapsed < 2) return keep(support);
        int minimum = elapsed + Math.max(1, (maximum - elapsed) / 2);
        var original = scenario.getRevisions().isEmpty() ? current
                : ElliottProjectionService.parsePath(scenario.getRevisions().getFirst().getProjectedPath());
        var path = remainingPath(original, latest, elapsed, maximum, set.getSourcePrice(),
                scenario.getTargetMidpoint(), interval, support > 0 ? swings.size() : 0);
        // A redraw cannot make the stored hard constraints more permissive.
        if (path.stream().anyMatch(point -> beyond(point.price(), scenario.getHardInvalidationPrice(),
                scenario.getHardInvalidationSide()))) {
            return new Decision(deviation, streak, support, List.of(), 0, 0, true,
                    "A replacement path cannot satisfy the retained count's structural boundary.");
        }
        String reason = switch (deviation) {
            case "FASTER" -> "Three completed closes exceeded the prior path's price tolerance; remaining timing accelerated.";
            case "TIMING" -> "Three completed candles exceeded the prior timing window; duration extended within the original lifetime limit.";
            case "SUPPORTED_ALTERNATIVE" -> "Confirmed swings consistently support this corrective alternative; remaining timing and turns revised.";
            default -> "Three completed closes lagged the prior path beyond its price tolerance; remaining timing and turns revised.";
        };
        return new Decision(deviation, streak, support, path, minimum, maximum, false, reason);
    }

    static int lifetime(ElliottProjectionScenario scenario) {
        int original = scenario.getOriginalMaximumCandles() > 0
                ? scenario.getOriginalMaximumCandles() : scenario.getMaximumCandles();
        return Math.max(original, Math.min(240, original * 2));
    }

    private static Decision keep(int support) {
        return new Decision(null, 0, support, List.of(), 0, 0, false, "Current path retained.");
    }

    private static double priceAt(List<ElliottProjectionService.ProjectedPointView> path, int elapsed) {
        var previous = path.getFirst();
        for (var point : path) {
            if (point.candleOffset() >= elapsed) {
                int length = point.candleOffset() - previous.candleOffset();
                return length <= 0 ? point.price() : previous.price() + (point.price() - previous.price())
                        * Math.clamp((elapsed - previous.candleOffset()) / (double) length, 0, 1);
            }
            previous = point;
        }
        return previous.price();
    }

    private static List<ElliottProjectionPolicy.ProjectedPoint> remainingPath(
            List<ElliottProjectionService.ProjectedPointView> original, Candle latest,
            int elapsed, int maximum, double source, double target, TimeInterval interval, int supportedTurns) {
        List<ElliottProjectionService.ProjectedPointView> nodes = original.stream()
                .filter(p -> !p.label().isBlank() || p == original.getFirst() || p == original.getLast()).toList();
        if (nodes.size() < 2) nodes = original;
        double progress = (latest.getClosePrice() - source) / (target - source);
        int consumed = Math.min(supportedTurns, nodes.size() - 2);
        // Consume only forward milestones already passed, never invent an observed reversal.
        for (int i = 1; i < nodes.size() - 1; i++) {
            double nodeProgress = (nodes.get(i).price() - source) / (target - source);
            double previousProgress = (nodes.get(i - 1).price() - source) / (target - source);
            if (nodeProgress > previousProgress && nodeProgress <= progress) consumed = Math.max(consumed, i);
        }
        double from = nodes.get(consumed).price();
        double scale = (target - latest.getClosePrice()) / (target - from);
        int remainingNodes = nodes.size() - consumed - 1;
        List<ElliottProjectionPolicy.ProjectedPoint> result = new ArrayList<>();
        result.add(new ElliottProjectionPolicy.ProjectedPoint(elapsed, latest.getTimestamp(),
                latest.getClosePrice(), "Provisional anchor"));
        // Collapse to the endpoint when the remaining candle window cannot contain every turn.
        for (int offset = 1; offset <= maximum - elapsed; offset++) {
            double position = offset * remainingNodes / (double) (maximum - elapsed);
            int segment = Math.min(remainingNodes - 1, (int) Math.floor(position));
            double fraction = position - segment;
            double first = nodes.get(consumed + segment).price();
            double last = nodes.get(consumed + segment + 1).price();
            double price = latest.getClosePrice() + (first + (last - first) * fraction - from) * scale;
            String label = "";
            for (int node = 1; node < remainingNodes; node++) {
                if (offset == Math.round((maximum - elapsed) * node / (double) remainingNodes)) {
                    label = "Conditional turn";
                }
            }
            result.add(new ElliottProjectionPolicy.ProjectedPoint(elapsed + offset,
                    ElliottProjectionPolicy.futureTimestamp(latest.getTimestamp(), offset, 86_400, interval),
                    Math.max(.000001, price), offset == maximum - elapsed ? "Conditional endpoint" : label));
        }
        return List.copyOf(result);
    }

    record Swing(long timestamp, double price, boolean high) { }

    /** Fixed lookback gives identical evidence when the caller's older history window rolls. */
    static double recentAtr(List<Candle> history) {
        double total = 0;
        int start = Math.max(1, history.size() - 14);
        for (int i = start; i < history.size(); i++) {
            Candle candle = history.get(i);
            double previous = history.get(i - 1).getClosePrice();
            total += Math.max(candle.getHighPrice() - candle.getLowPrice(),
                    Math.max(Math.abs(candle.getHighPrice() - previous), Math.abs(candle.getLowPrice() - previous)));
        }
        return history.size() <= start ? Double.NaN : total / (history.size() - start);
    }

    /** Two right-side candles are required; ambiguous outside bars are excluded. */
    static List<Swing> confirmedSwings(List<Candle> history, long source) {
        List<Swing> result = new ArrayList<>();
        for (int i = 2; i + 2 < history.size(); i++) {
            Candle c = history.get(i);
            if (c.getTimestamp() <= source) continue;
            boolean high = true, low = true;
            for (int j = i - 2; j <= i + 2; j++) {
                if (j == i) continue;
                high &= c.getHighPrice() > history.get(j).getHighPrice();
                low &= c.getLowPrice() < history.get(j).getLowPrice();
            }
            if (high == low) continue;
            Swing swing = new Swing(c.getTimestamp(), high ? c.getHighPrice() : c.getLowPrice(), high);
            if (!result.isEmpty()) {
                Swing previous = result.getLast();
                if (previous.high() == high) continue;
                // A pivot's noise threshold is fixed when its two confirmation candles become available.
                double pivotAtr = recentAtr(history.subList(0, i + 3));
                if (Double.isFinite(pivotAtr) && Math.abs(swing.price() - previous.price()) < pivotAtr) continue;
            }
            result.add(swing);
        }
        return List.copyOf(result);
    }

    private static int structuralSupport(String key, List<Swing> swings, double source, double sign, double atr) {
        if (key == null || swings.size() < 2) return 0;
        Swing a = swings.get(0), b = swings.get(1);
        double excursion = sign * (a.price() - source);
        if (excursion < atr || a.high() != (sign > 0)) return 0;
        double retracement = sign * (a.price() - b.price()) / excursion;
        if (key.contains("ZIGZAG")) return retracement < .70 ? 18 : retracement > .9 ? -18 : 0;
        if (key.contains("EXPANDED_FLAT")) return retracement > 1.05 ? 18 : retracement < .70 ? -18 : 0;
        if (key.contains("FLAT")) return retracement >= .8 && retracement <= 1.05 ? 18 : 0;
        if (swings.size() >= 4) {
            double secondExcursion = Math.abs(swings.get(2).price() - b.price());
            double thirdExcursion = Math.abs(swings.get(3).price() - swings.get(2).price());
            boolean contracting = secondExcursion < Math.abs(a.price() - b.price())
                    && thirdExcursion < secondExcursion;
            if (key.contains("TRIANGLE")) return contracting ? 18 : -18;
            if (key.contains("COMBINATION")) return !contracting && retracement >= .7 ? 18 : 0;
        }
        return 0;
    }

    static boolean beyond(double price, Double boundary, String side) {
        return boundary != null && Double.isFinite(boundary)
                && ("ABOVE".equals(side) ? price >= boundary : "BELOW".equals(side) && price <= boundary);
    }
}
