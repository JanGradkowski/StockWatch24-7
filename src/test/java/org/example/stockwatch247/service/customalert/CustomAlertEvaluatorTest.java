package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Operand;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.PatternCondition;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.ActivityHit;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.OutlookFact;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.PatternHit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomAlertEvaluatorTest {
    private static final ChartIndicatorCatalog CATALOG = new ChartIndicatorCatalog();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long DAY = 86_400;
    private static final long START = LocalDate.of(2026, 3, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

    /** Facts with prepared series; the close comes from the candles. */
    private static final class Stub implements CustomAlertEvaluator.Facts {
        final List<Candle> candles = new ArrayList<>();
        final Map<String, double[]> series = new HashMap<>();
        final List<PatternHit> patterns = new ArrayList<>();
        final Map<String, List<ActivityHit>> activity = new HashMap<>();
        OutlookFact outlook;
        boolean stock = true;

        Stub(double... closes) {
            for (int index = 0; index < closes.length; index++) {
                candles.add(new Candle("TEST", "1d", START + index * DAY, closes[index], closes[index] + 1, closes[index] - 1, closes[index], 1000L));
            }
        }

        @Override public TimeInterval interval() { return TimeInterval.DAILY; }
        @Override public List<Candle> candles() { return candles; }
        @Override public double[] series(Operand operand) {
            if ("PRICE".equals(operand.kind())) return candles.stream().mapToDouble(Candle::getClosePrice).toArray();
            return series.getOrDefault(operand.type() + ":" + operand.output(), new double[candles.size()]);
        }
        @Override public List<PatternHit> patterns(PatternCondition condition) {
            return patterns.stream().filter(hit -> hit.family().equals(condition.family())).toList();
        }
        @Override public boolean stillValid(PatternHit hit, int index) { return true; }
        @Override public OutlookFact outlook() { return outlook; }
        @Override public List<ActivityHit> activity(String source) { return activity.getOrDefault(source, List.of()); }
        @Override public boolean stock() { return stock; }

        long at(int index) { return candles.get(index).getTimestamp(); }
    }

    private static CustomAlertDefinition rule(String json) {
        return CustomAlertDefinition.parse(JSON.readTree(json), CATALOG, TimeInterval.DAILY);
    }

    private static String single(String condition) {
        return "{\"version\":1,\"match\":\"ALL\",\"groups\":[{\"id\":\"g1\",\"match\":\"ALL\",\"conditions\":[" + condition + "]}]}";
    }

    private static final String RSI = "{\"kind\":\"INDICATOR\",\"type\":\"RSI\",\"parameters\":{\"period\":14},\"output\":\"value\"}";

    @Test
    void aLevelConditionHoldsOnlyOnTheCandleWhereTheValueIsBelowIt() {
        Stub facts = new Stub(10, 10, 10, 10, 10);
        facts.series.put("RSI:value", new double[]{40, 35, 28, 33, 29});
        var definition = rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":" + RSI
                + ",\"operator\":\"BELOW\",\"compare\":{\"kind\":\"VALUE\",\"value\":30}}"));

        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 2).matched()).isTrue();
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 3).matched()).isFalse();
        var result = CustomAlertEvaluator.evaluate(definition, facts, 4);
        assertThat(result.matched()).isTrue();
        assertThat(result.conditions().getFirst().facts()).containsEntry("value", 29.0).containsEntry("compare", 30.0);
    }

    @Test
    void windowsLookBackForAnyOrEveryCandle() {
        Stub facts = new Stub(10, 10, 10, 10, 10);
        facts.series.put("RSI:value", new double[]{28, 35, 36, 25, 26});
        var any = rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":" + RSI
                + ",\"operator\":\"BELOW\",\"compare\":{\"kind\":\"VALUE\",\"value\":30},\"window\":{\"mode\":\"ANY\",\"candles\":3}}"));
        var every = rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":" + RSI
                + ",\"operator\":\"BELOW\",\"compare\":{\"kind\":\"VALUE\",\"value\":30},\"window\":{\"mode\":\"EVERY\",\"candles\":2}}"));

        assertThat(CustomAlertEvaluator.evaluate(any, facts, 2).matched()).as("28 is three candles back").isTrue();
        assertThat(CustomAlertEvaluator.evaluate(any, facts, 3).matched()).isTrue();
        assertThat(CustomAlertEvaluator.evaluate(every, facts, 3).matched()).as("36 then 25").isFalse();
        assertThat(CustomAlertEvaluator.evaluate(every, facts, 4).matched()).isTrue();
    }

    @Test
    void crossesAndMovesUseTheCandleBeforeAndNothingAfter() {
        Stub facts = new Stub(100, 102, 98, 104, 106, 107, 108);
        var crossUp = rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},"
                + "\"operator\":\"CROSSES_ABOVE\",\"compare\":{\"kind\":\"VALUE\",\"value\":103}}"));
        var rising = rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},"
                + "\"operator\":\"RISING\",\"candles\":3}"));
        var change = rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},"
                + "\"operator\":\"CHANGE_ABOVE\",\"candles\":2,\"percent\":8}"));
        var high = rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},"
                + "\"operator\":\"NEW_HIGH\",\"candles\":4}"));

        assertThat(CustomAlertEvaluator.evaluate(crossUp, facts, 3).matched()).isTrue();
        assertThat(CustomAlertEvaluator.evaluate(crossUp, facts, 4).matched()).as("already above").isFalse();
        assertThat(CustomAlertEvaluator.evaluate(rising, facts, 5).matched()).isTrue();
        assertThat(CustomAlertEvaluator.evaluate(rising, facts, 3).matched()).isFalse();
        assertThat(CustomAlertEvaluator.evaluate(change, facts, 4).matched()).as("98 to 106 is 8.2%").isTrue();
        assertThat(CustomAlertEvaluator.evaluate(change, facts, 6).matched()).isFalse();
        assertThat(CustomAlertEvaluator.evaluate(high, facts, 4).matched()).isTrue();
    }

    @Test
    void groupsCombineWithAllAndAnyAndNegationFlipsACondition() {
        Stub facts = new Stub(10, 10, 10);
        facts.series.put("RSI:value", new double[]{50, 50, 25});
        String low = "{\"id\":\"low\",\"kind\":\"VALUE\",\"subject\":" + RSI + ",\"operator\":\"BELOW\",\"compare\":{\"kind\":\"VALUE\",\"value\":30}}";
        String high = "{\"id\":\"high\",\"kind\":\"VALUE\",\"subject\":" + RSI + ",\"operator\":\"ABOVE\",\"compare\":{\"kind\":\"VALUE\",\"value\":70}}";
        String notHigh = "{\"id\":\"nothigh\",\"negate\":true,\"kind\":\"VALUE\",\"subject\":" + RSI
                + ",\"operator\":\"ABOVE\",\"compare\":{\"kind\":\"VALUE\",\"value\":70}}";
        var any = rule("{\"version\":1,\"match\":\"ALL\",\"groups\":[{\"id\":\"g1\",\"match\":\"ANY\",\"conditions\":[" + low + "," + high + "]}]}");
        var both = rule("{\"version\":1,\"match\":\"ALL\",\"groups\":[{\"id\":\"g1\",\"match\":\"ALL\",\"conditions\":[" + low + "," + high + "]}]}");
        var negated = rule("{\"version\":1,\"match\":\"ALL\",\"groups\":[{\"id\":\"g1\",\"match\":\"ALL\",\"conditions\":[" + low + "]},"
                + "{\"id\":\"g2\",\"match\":\"ALL\",\"conditions\":[" + notHigh + "]}]}");

        assertThat(CustomAlertEvaluator.evaluate(any, facts, 2).matched()).isTrue();
        var result = CustomAlertEvaluator.evaluate(both, facts, 2);
        assertThat(result.matched()).isFalse();
        assertThat(result.matchedConditions()).isEqualTo(1);
        assertThat(result.totalConditions()).isEqualTo(2);
        assertThat(CustomAlertEvaluator.evaluate(negated, facts, 2).matched()).isTrue();
    }

    @Test
    void aPatternCountsFromTheCandleItBecameKnownWithItsDirectionTypeAndScore() {
        Stub facts = new Stub(10, 10, 10, 10, 10, 10, 10, 10);
        // Wave II ended on candle 1 but was only recognisable on candle 4.
        facts.patterns.add(new PatternHit("ELLIOTT_WAVE", "ELLIOTT_BULLISH_WAVE_II_END", "WAVE_II_END", true, 72,
                facts.at(4), facts.at(1), null, null, 9.5, null));
        String condition = "{\"id\":\"c1\",\"kind\":\"PATTERN\",\"family\":\"ELLIOTT_WAVE\",\"direction\":\"BULLISH\","
                + "\"patterns\":[\"WAVE_II_END\"],\"minScore\":60,\"window\":{\"mode\":\"ANY\",\"candles\":3}}";
        var definition = rule(single(condition));

        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 3).matched()).as("not known yet").isFalse();
        var result = CustomAlertEvaluator.evaluate(definition, facts, 5);
        assertThat(result.matched()).isTrue();
        assertThat(result.conditions().getFirst().facts()).containsEntry("candlesAgo", 1).containsEntry("score", 72);
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 7).matched()).as("out of the window").isFalse();
        assertThat(CustomAlertEvaluator.evaluate(rule(single(condition.replace("\"minScore\":60", "\"minScore\":80"))), facts, 5).matched())
                .isFalse();
        assertThat(CustomAlertEvaluator.evaluate(rule(single(condition.replace("BULLISH", "BEARISH"))), facts, 5).matched()).isFalse();
    }

    @Test
    void aRedrawnWaveCountDoesNotExistBeforeTheCandleThatFirstShowedIt() {
        Stub facts = new Stub(10, 10, 10, 10, 10, 10, 10, 10);
        // Confirmed on candle 2, but only the count as of candle 5 contained it (a replay reads counts as they stood).
        facts.patterns.add(new PatternHit("ELLIOTT_WAVE", "ELLIOTT_BULLISH_WAVE_II_END", "WAVE_II_END", true, 72,
                facts.at(2), facts.at(1), null, null, 9.5, null, facts.at(5)));
        var definition = rule(single("{\"id\":\"c1\",\"kind\":\"PATTERN\",\"family\":\"ELLIOTT_WAVE\",\"direction\":\"ANY\","
                + "\"window\":{\"mode\":\"ANY\",\"candles\":5}}"));
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 3).matched()).as("confirmed, but not in the count yet").isFalse();
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 5).matched()).as("in the count, confirmed within 5 candles").isTrue();
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 7).matched()).as("confirmation left the window").isFalse();
    }

    private static String candlestick(String status, int candles) {
        return single("{\"id\":\"c1\",\"kind\":\"PATTERN\",\"family\":\"CANDLESTICK\"" + (status == null ? "" : ",\"status\":\"" + status + "\"")
                + ",\"direction\":\"BULLISH\",\"window\":{\"mode\":\"ANY\",\"candles\":" + candles + "}}");
    }

    @Test
    void aConfirmedCandlestickCountsFromTheCandleThatReachedItsTarget() {
        Stub facts = new Stub(10, 10, 10, 10, 10, 10, 10, 10);
        // Recognised on candle 1, target reached on candle 4.
        facts.patterns.add(new PatternHit("CANDLESTICK", "BULLISH_ENGULFING", "BULLISH_ENGULFING", true, 80,
                facts.at(1), facts.at(1), "CONFIRMED", facts.at(4), 10, null));
        var confirmed = rule(candlestick("CONFIRMED", 2));

        assertThat(CustomAlertEvaluator.evaluate(confirmed, facts, 3).matched()).as("target not reached yet").isFalse();
        var result = CustomAlertEvaluator.evaluate(confirmed, facts, 5);
        assertThat(result.matched()).as("recognised four candles back, confirmed one back").isTrue();
        assertThat(result.conditions().getFirst().facts()).containsEntry("detectedAt", facts.at(1))
                .containsEntry("confirmedAt", facts.at(4)).containsEntry("candlesAgo", 1);
        assertThat(CustomAlertEvaluator.evaluate(confirmed, facts, 6).matched()).as("confirmation left the window").isFalse();
        assertThat(CustomAlertEvaluator.evaluate(rule(candlestick("DETECTED", 2)), facts, 5).matched())
                .as("recognised outside the window").isFalse();
    }

    @Test
    void aDetectedCandlestickCountsFromItsRecognitionWhateverHappensNext() {
        Stub facts = new Stub(10, 10, 10, 10);
        facts.patterns.add(new PatternHit("CANDLESTICK", "BULLISH_ENGULFING", "BULLISH_ENGULFING", true, 80,
                facts.at(1), facts.at(1), "INVALIDATED", facts.at(2), 10, null));
        assertThat(CustomAlertEvaluator.evaluate(rule(candlestick("DETECTED", 2)), facts, 2).matched()).isTrue();
        assertThat(CustomAlertEvaluator.evaluate(rule(candlestick("CONFIRMED", 2)), facts, 2).matched()).isFalse();
    }

    @Test
    void candlestickRulesSavedWithoutAStatusKeepTheirMeaning() {
        PatternCondition plain = (PatternCondition) rule(candlestick(null, 3)).conditions().getFirst();
        PatternCondition ticked = (PatternCondition) rule(candlestick(null, 3).replace("\"direction\"", "\"confirmedOnly\":true,\"direction\""))
                .conditions().getFirst();
        assertThat(plain.status()).isEqualTo("DETECTED");
        assertThat(ticked.status()).isEqualTo("CONFIRMED");
        assertThat(((PatternCondition) rule(candlestick("detected", 3)).conditions().getFirst()).status()).isEqualTo("DETECTED");
        assertThatThrownBy(() -> rule(candlestick("MAYBE", 3))).isInstanceOf(UserInputException.class);

        String written = rule(candlestick(null, 3).replace("\"direction\"", "\"confirmedOnly\":true,\"direction\"")).toJson().toString();
        assertThat(written).contains("\"family\":\"CANDLESTICK\",\"status\":\"CONFIRMED\",\"direction\"").doesNotContain("confirmedOnly");
        String elliott = rule(single("{\"id\":\"c1\",\"kind\":\"PATTERN\",\"family\":\"ELLIOTT_WAVE\",\"status\":\"CONFIRMED\","
                + "\"direction\":\"ANY\"}")).toJson().toString();
        assertThat(elliott).as("only candlesticks have a status").doesNotContain("status");
    }

    @Test
    void filingsCountDistinctPeopleAndAmountsInTheirWindowAndDoNotApplyToFunds() {
        Stub facts = new Stub(10, 10, 10);
        LocalDate day = Instant.ofEpochSecond(facts.at(2)).atZone(ZoneOffset.UTC).toLocalDate();
        facts.activity.put("INSIDER", List.of(
                new ActivityHit(1, day.minusDays(3), "Ann Smith", "Chief Executive Officer", "PURCHASE", 200_000d, null),
                new ActivityHit(2, day.minusDays(10), "Bob Jones", "Director", "PURCHASE", 90_000d, null),
                new ActivityHit(3, day.minusDays(40), "Cy Lee", "Director", "PURCHASE", 900_000d, null),
                new ActivityHit(4, day.minusDays(2), "Dee Fox", "Director", "SALE", 50_000d, null)));
        String cluster = "{\"id\":\"c1\",\"kind\":\"INSIDER\",\"transaction\":\"PURCHASE\",\"days\":30,\"minTotal\":250000,\"minCount\":2}";
        String noSales = "{\"id\":\"c2\",\"kind\":\"INSIDER\",\"negate\":true,\"transaction\":\"SALE\",\"days\":30}";

        var result = CustomAlertEvaluator.evaluate(rule(single(cluster)), facts, 2);
        assertThat(result.matched()).isTrue();
        assertThat(result.conditions().getFirst().facts()).containsEntry("people", 2).containsEntry("total", 290_000.0);
        assertThat(CustomAlertEvaluator.evaluate(rule(single(cluster.replace("250000", "300000"))), facts, 2).matched()).isFalse();
        assertThat(CustomAlertEvaluator.evaluate(rule(single(noSales)), facts, 2).matched()).as("one sale two days ago").isFalse();
        assertThat(CustomAlertEvaluator.evaluate(rule(single(cluster.replace("\"minCount\":2", "\"minCount\":1,\"roles\":[\"DIRECTOR\"]")
                .replace("250000", "50000"))), facts, 2).matched()).isTrue();

        facts.stock = false;
        var fund = CustomAlertEvaluator.evaluate(rule(single(cluster)), facts, 2);
        assertThat(fund.applicable()).isFalse();
        assertThat(fund.matched()).isFalse();
    }

    @Test
    void theOutlookIsReadOnlyOnTheCandleItWasComputedFor() {
        Stub facts = new Stub(10, 10, 10);
        facts.outlook = new OutlookFact(facts.at(2), "Moderate buy outlook", 0.31);
        var moderate = rule(single("{\"id\":\"c1\",\"kind\":\"OUTLOOK\",\"direction\":\"BUY\",\"strength\":\"MODERATE\"}"));
        var strong = rule(single("{\"id\":\"c1\",\"kind\":\"OUTLOOK\",\"direction\":\"BUY\",\"strength\":\"STRONG\"}"));
        assertThat(CustomAlertEvaluator.evaluate(moderate, facts, 2).matched()).isTrue();
        assertThat(CustomAlertEvaluator.evaluate(strong, facts, 2).matched()).isFalse();
        assertThat(CustomAlertEvaluator.evaluate(moderate, facts, 1).matched()).isFalse();
    }

    @Test
    void aResultNeverChangesWhenLaterCandlesArrive() {
        double[] closes = {100, 99, 97, 101, 104, 103, 99, 95, 98, 103, 108, 104};
        String condition = "{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},"
                + "\"operator\":\"CROSSES_ABOVE\",\"compare\":{\"kind\":\"VALUE\",\"value\":100},\"window\":{\"mode\":\"ANY\",\"candles\":2}}";
        var definition = rule(single(condition));
        Stub full = new Stub(closes);
        for (int length = 3; length < closes.length; length++) {
            Stub prefix = new Stub(java.util.Arrays.copyOf(closes, length));
            for (int index = 1; index < length; index++) {
                assertThat(CustomAlertEvaluator.evaluate(definition, prefix, index).matched())
                        .isEqualTo(CustomAlertEvaluator.evaluate(definition, full, index).matched());
            }
        }
    }

    @Test
    void invalidRulesAreRejectedWithAnExplanation() {
        assertThatThrownBy(() -> rule("{\"version\":1,\"groups\":[]}")).isInstanceOf(UserInputException.class)
                .hasMessage("Add at least one condition.");
        assertThatThrownBy(() -> rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"INDICATOR\",\"type\":\"RSI\","
                + "\"parameters\":{\"period\":1},\"output\":\"value\"},\"operator\":\"BELOW\",\"compare\":{\"kind\":\"VALUE\",\"value\":30}}")))
                .isInstanceOf(UserInputException.class);
        assertThatThrownBy(() -> rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"INDICATOR\",\"type\":\"ICHIMOKU\","
                + "\"output\":\"lagging\"},\"operator\":\"BELOW\",\"compare\":{\"kind\":\"VALUE\",\"value\":30}}")))
                .hasMessage("Choose the indicator line the alert watches.");
        assertThatThrownBy(() -> rule(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},"
                + "\"operator\":\"BETWEEN\",\"lower\":5,\"upper\":3}"))).hasMessage("The lower level must be below the upper one.");
        assertThatThrownBy(() -> rule(single("{\"id\":\"c1\",\"kind\":\"PATTERN\",\"family\":\"HARMONIC_FORMATION\",\"patterns\":[\"DOJI\"]}")))
                .hasMessage("Choose patterns from the list.");
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < 13; index++) {
            if (index > 0) many.append(',');
            many.append("{\"id\":\"c").append(index).append("\",\"kind\":\"OUTLOOK\",\"direction\":\"BUY\",\"strength\":\"SLIGHT\"}");
        }
        assertThatThrownBy(() -> rule(single(many.toString()))).hasMessage("An alert can have at most 12 conditions.");
    }

    // ------------------------------------------------------------------ sequences

    private static String rsi(String id, String operator, double value) {
        return "{\"id\":\"" + id + "\",\"kind\":\"VALUE\",\"subject\":" + RSI + ",\"operator\":\"" + operator
                + "\",\"compare\":{\"kind\":\"VALUE\",\"value\":" + value + "}}";
    }

    private static String step(String condition, int candles, Integer within) {
        return condition.substring(0, condition.length() - 1) + ",\"step\":{\"candles\":" + candles
                + (within == null ? "" : ",\"within\":" + within) + "}}";
    }

    private static String then(Integer span, String window, String... steps) {
        return "{\"version\":1,\"match\":\"ALL\",\"groups\":[{\"id\":\"g1\",\"match\":\"THEN\"" + (span == null ? "" : ",\"span\":" + span)
                + (window == null ? "" : ",\"window\":" + window) + ",\"conditions\":[" + String.join(",", steps) + "]}]}";
    }

    /** RSI below 30 for at least 5 candles in a row, then within 3 candles RSI above 30; all within 20 candles. */
    private static String recovery(int within, int span, String window) {
        return then(span, window, step(rsi("c1", "BELOW", 30), 5, null), step(rsi("c2", "ABOVE", 30), 1, within));
    }

    private static List<Integer> matches(CustomAlertDefinition definition, Stub facts) {
        List<Integer> found = new ArrayList<>();
        for (int index = 0; index < facts.candles.size(); index++) {
            if (CustomAlertEvaluator.evaluate(definition, facts, index).matched()) found.add(index);
        }
        return found;
    }

    private static Stub flat(int candles) {
        double[] closes = new double[candles];
        java.util.Arrays.fill(closes, 10);
        return new Stub(closes);
    }

    @Test
    void aSequenceCompletesOnTheCandleItsLastStepFirstHappens() {
        // The worked example of docs/custom-alert-sequences-plan.md (its candles 1–12 are 0–11 here).
        Stub facts = flat(12);
        facts.series.put("RSI:value", new double[]{35, 29, 27, 25, 24, 26, 28, 31, 33, 29, 32, 34});
        var definition = rule(recovery(3, 20, null));

        assertThat(matches(definition, facts)).as("once: a later candle above 30 is not a new recovery, one oversold candle does not re-arm")
                .containsExactly(7);
        var result = CustomAlertEvaluator.evaluate(definition, facts, 7);
        assertThat(result.matchedConditions()).isEqualTo(2);
        assertThat(result.groups().getFirst().facts()).containsEntry("completedAt", facts.at(7)).containsEntry("candlesAgo", 0)
                .containsEntry("span", 6);
        assertThat(result.conditions().get(0).facts()).containsEntry("from", facts.at(1)).containsEntry("to", facts.at(6))
                .containsEntry("candles", 6).containsEntry("low", 24.0).containsEntry("high", 29.0);
        assertThat(result.conditions().get(1).facts()).containsEntry("at", facts.at(7)).containsEntry("after", 1).containsEntry("value", 31.0);

        assertThat(matches(rule(recovery(3, 20, "{\"mode\":\"ANY\",\"candles\":3}")), facts)).as("completed in the last 3 candles")
                .containsExactly(7, 8, 9);
    }

    @Test
    void theNextStepMustStartWithinItsAllowanceAfterThePreviousWasLastTrue() {
        Stub facts = flat(12);
        // Oversold on candles 1–6, exactly 30 (neither below nor above) on 7–9, above on 10.
        facts.series.put("RSI:value", new double[]{35, 29, 28, 27, 26, 25, 24, 30, 30, 30, 31, 32});
        assertThat(matches(rule(recovery(3, 20, null)), facts)).as("4 candles after the last oversold one").isEmpty();
        assertThat(matches(rule(recovery(4, 20, null)), facts)).containsExactly(10);
    }

    @Test
    void theWholeSequenceMustFitInItsSpan() {
        Stub facts = flat(10);
        // Oversold on candles 1–5, 30 on 6–7, above on 8: from the first oversold candle to the recovery is 8 candles.
        facts.series.put("RSI:value", new double[]{35, 29, 28, 27, 26, 25, 30, 30, 31, 32});
        assertThat(matches(rule(recovery(3, 8, null)), facts)).containsExactly(8);
        assertThat(matches(rule(recovery(3, 7, null)), facts)).isEmpty();
    }

    @Test
    void aLaterStepDoesNotWaitForTheEarlierOneToEnd() {
        Stub facts = new Stub(90, 90, 90, 90, 90, 101, 102, 103, 104, 105);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 25, 25, 25, 25, 25, 25, 40});
        String close = "{\"id\":\"c2\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},\"operator\":\"ABOVE\","
                + "\"compare\":{\"kind\":\"VALUE\",\"value\":100}}";
        var definition = rule(then(20, null, step(rsi("c1", "BELOW", 30), 3, null), step(close, 1, 5)));
        // The close goes above 100 while RSI is still oversold; it stays complete while both hold, then one candle more.
        assertThat(matches(definition, facts)).containsExactly(5, 6, 7, 8, 9);

        Stub dips = flat(6);
        dips.series.put("RSI:value", new double[]{40, 25, 25, 25, 25, 40});
        var twice = rule(then(20, null, step(rsi("c1", "BELOW", 30), 2, null), step(rsi("c2", "BELOW", 30), 1, 3)));
        assertThat(matches(twice, dips)).as("the same condition counts right after the first stretch, never on its candle").containsExactly(3, 4);
    }

    @Test
    void aPatternStepCountsOnTheCandleItsPatternCountsOn() {
        Stub facts = flat(10);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 25, 40, 40, 40, 40, 40, 40});
        // Recognised on candle 4, target reached on candle 6.
        facts.patterns.add(new PatternHit("CANDLESTICK", "BULLISH_ENGULFING", "BULLISH_ENGULFING", true, 80,
                facts.at(4), facts.at(4), "CONFIRMED", facts.at(6), 10, null));
        String candle = "{\"id\":\"c2\",\"kind\":\"PATTERN\",\"family\":\"CANDLESTICK\",\"status\":\"CONFIRMED\",\"direction\":\"BULLISH\"}";
        var confirmed = rule(then(20, null, step(rsi("c1", "BELOW", 30), 3, null), step(candle, 1, 5)));

        assertThat(matches(confirmed, facts)).containsExactly(6);
        assertThat(matches(rule(then(20, null, step(rsi("c1", "BELOW", 30), 3, null), step(candle.replace("CONFIRMED", "DETECTED"), 1, 5))), facts))
                .containsExactly(4);
        assertThat(matches(rule(then(20, null, step(rsi("c1", "BELOW", 30), 3, null), step(candle, 1, 2))), facts))
                .as("confirmed 3 candles after the last oversold one").isEmpty();
        assertThat(CustomAlertEvaluator.evaluate(confirmed, facts, 6).conditions().get(1).facts())
                .containsEntry("pattern", "BULLISH_ENGULFING").containsEntry("detectedAt", facts.at(4)).containsEntry("confirmedAt", facts.at(6));
    }

    @Test
    void aPatternStepIsReadAsItWasKnownOnTheEvaluatedCandle() {
        Stub facts = flat(10);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 25, 40, 40, 40, 40, 40, 40});
        // Recognisable on candle 4, but only the count as of candle 6 contained it.
        facts.patterns.add(new PatternHit("ELLIOTT_WAVE", "ELLIOTT_BULLISH_WAVE_II_END", "WAVE_II_END", true, 72,
                facts.at(4), facts.at(2), null, null, 9.5, null, facts.at(6)));
        String wave = "{\"id\":\"c2\",\"kind\":\"PATTERN\",\"family\":\"ELLIOTT_WAVE\",\"direction\":\"BULLISH\"}";
        var recent = rule(then(20, "{\"mode\":\"ANY\",\"candles\":3}", step(rsi("c1", "BELOW", 30), 3, null), step(wave, 1, 5)));

        assertThat(matches(recent, facts)).as("completed on candle 4, known from candle 6, within the last 3 candles").containsExactly(6);
        assertThat(matches(rule(then(20, null, step(rsi("c1", "BELOW", 30), 3, null), step(wave, 1, 5))), facts))
                .as("on candle 4 nobody knew yet").isEmpty();
    }

    @Test
    void threeStepsChainInOrder() {
        Stub facts = flat(11);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 25, 35, 36, 37, 38, 39, 40, 41});
        facts.patterns.add(new PatternHit("CANDLESTICK", "HAMMER", "HAMMER", true, 70, facts.at(7), facts.at(7), "OPEN", null, 10, null));
        String candle = "{\"id\":\"c3\",\"kind\":\"PATTERN\",\"family\":\"CANDLESTICK\",\"status\":\"DETECTED\",\"direction\":\"BULLISH\"}";
        var definition = rule(then(25, null, step(rsi("c1", "BELOW", 30), 3, null), step(rsi("c2", "ABOVE", 30), 1, 3), step(candle, 1, 5)));
        // RSI recovered on candle 4 and is still above 30 when the hammer appears on candle 7.
        assertThat(matches(definition, facts)).containsExactly(7);
        var result = CustomAlertEvaluator.evaluate(definition, facts, 7);
        assertThat(result.conditions().get(1).facts()).containsEntry("from", facts.at(4)).containsEntry("to", facts.at(6));
        assertThat(result.conditions().get(2).facts()).containsEntry("after", 1);
    }

    @Test
    void anUnfinishedSequenceSaysHowFarItGot() {
        Stub facts = flat(11);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 26, 27, 28, 29, 30, 30, 30, 30});
        var definition = rule(recovery(3, 20, null));

        var building = CustomAlertEvaluator.evaluate(definition, facts, 3);
        assertThat(building.conditions().get(0).passed()).isFalse();
        assertThat(building.conditions().get(0).facts()).containsEntry("soFar", 3).containsEntry("needed", 5);
        assertThat(building.groups().getFirst().facts()).containsEntry("done", 0);

        var waiting = CustomAlertEvaluator.evaluate(definition, facts, 6);
        assertThat(waiting.matched()).isFalse();
        assertThat(waiting.matchedConditions()).isEqualTo(1);
        assertThat(waiting.conditions().get(0).facts()).containsEntry("from", facts.at(1)).containsEntry("candles", 6);
        assertThat(waiting.conditions().get(1).facts()).containsEntry("waiting", true).containsEntry("candlesLeft", 3);
        assertThat(waiting.groups().getFirst().facts()).containsEntry("done", 1).containsEntry("candlesLeft", 3);
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 8).conditions().get(1).facts()).containsEntry("candlesLeft", 1);
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 9).groups().getFirst().facts()).as("too late").containsEntry("done", 0);

        Stub done = flat(12);
        done.series.put("RSI:value", new double[]{35, 29, 27, 25, 24, 26, 28, 31, 33, 29, 32, 34});
        assertThat(CustomAlertEvaluator.evaluate(definition, done, 8).groups().getFirst().facts())
                .containsEntry("completedAt", done.at(7)).containsEntry("candlesAgo", 1).containsEntry("outsideWindow", true);
    }

    @Test
    void aSequenceWhoseNextStepAlreadyHadItsChanceIsNotHalfway() {
        // Step 2 (RSI crosses above 30) happens on candle 4; step 3 (a close above 100) had to follow on candle 5 and did
        // not. Step 2 counts only the first time after step 1, so nothing is still waiting: the chain is over.
        Stub facts = new Stub(90, 90, 90, 90, 90, 90, 90, 90, 90, 90);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 25, 35, 36, 37, 38, 39, 40});
        String close = "{\"id\":\"c3\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},\"operator\":\"ABOVE\","
                + "\"compare\":{\"kind\":\"VALUE\",\"value\":100}}";
        var definition = rule(then(30, null, step(rsi("c1", "BELOW", 30), 3, null), step(rsi("c2", "CROSSES_ABOVE", 30), 1, 5),
                step(close, 1, 1)));

        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 4).groups().getFirst().facts())
                .as("step 3 may still come on the next candle").containsEntry("done", 2).containsEntry("candlesLeft", 1);
        for (int index = 5; index < 10; index++) {
            assertThat(CustomAlertEvaluator.evaluate(definition, facts, index).groups().getFirst().facts()).as("candle %d", index)
                    .containsEntry("done", 0);
        }
    }

    @Test
    void aStepIsFalseWhileItsIndicatorWarmsUpReversedOrNot() {
        Stub facts = flat(24);
        double[] rsi = new double[24];
        java.util.Arrays.fill(rsi, 50);
        java.util.Arrays.fill(rsi, 0, 14, Double.NaN);
        facts.series.put("RSI:value", rsi);
        String notAbove = rsi("c1", "ABOVE", 70).replace("\"kind\":\"VALUE\"", "\"kind\":\"VALUE\",\"negate\":true");
        var reversed = rule(then(20, null, step(notAbove, 5, null), step(rsi("c2", "BELOW", 55), 1, 3)));
        var plain = rule(then(20, null, step(rsi("c1", "BELOW", 70), 5, null), step(rsi("c2", "BELOW", 55), 1, 3)));

        // Known from candle 14: five candles in a row end on 18, and the next step counts on 19.
        assertThat(matches(reversed, facts).getFirst()).isEqualTo(19);
        assertThat(matches(plain, facts).getFirst()).isEqualTo(19);
    }

    @Test
    void aNextStepTrueOnlyAlongsideThePreviousOneHasNotHadItsChanceYet() {
        // The close is above 100 on candle 3, the last candle of step 1, and not after: step 2 has not happened after
        // step 1, so it is still due.
        Stub facts = new Stub(90, 90, 90, 101, 90, 90);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 25, 40, 40});
        String close = "{\"id\":\"c2\",\"kind\":\"VALUE\",\"subject\":{\"kind\":\"PRICE\",\"field\":\"CLOSE\"},\"operator\":\"ABOVE\","
                + "\"compare\":{\"kind\":\"VALUE\",\"value\":100}}";
        var definition = rule(then(20, null, step(rsi("c1", "BELOW", 30), 3, null), step(close, 1, 3)));
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 4).groups().getFirst().facts())
                .containsEntry("done", 1).containsEntry("candlesLeft", 2);
    }

    @Test
    void unknownValuesAStepLooksBackAtOrComparesWithMakeItFalse() {
        String anything = rsi("c2", "ABOVE", 0);
        // A reversed cross reads the candle before: on the first known candle it cannot be decided.
        Stub crossing = flat(10);
        crossing.series.put("RSI:value", new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 40, 41, 42, 43, 44});
        String notCrossing = rsi("c1", "CROSSES_ABOVE", 30).replace("\"kind\":\"VALUE\"", "\"kind\":\"VALUE\",\"negate\":true");
        assertThat(matches(rule(then(20, null, step(notCrossing, 1, null), step(anything, 1, 1))), crossing).getFirst()).isEqualTo(7);

        // A reversed comparison with another indicator is unknown while that indicator warms up.
        Stub compared = flat(14);
        double[] ema = new double[14];
        java.util.Arrays.fill(ema, 30);
        java.util.Arrays.fill(ema, 0, 10, Double.NaN);
        double[] rsi = new double[14];
        java.util.Arrays.fill(rsi, 20);
        compared.series.put("EMA:value", ema);
        compared.series.put("RSI:value", rsi);
        String notAboveAverage = ("{\"id\":\"c1\",\"kind\":\"VALUE\",\"negate\":true,\"subject\":" + RSI + ",\"operator\":\"ABOVE\","
                + "\"compare\":{\"kind\":\"INDICATOR\",\"type\":\"EMA\",\"parameters\":{\"period\":3},\"output\":\"value\"}}");
        assertThat(matches(rule(then(20, null, step(notAboveAverage, 1, null), step(anything, 1, 1))), compared).getFirst()).isEqualTo(11);

        // A change reads only its two ends: an unknown candle in between does not matter.
        Stub change = flat(10);
        change.series.put("RSI:value", new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, 14, Double.NaN, 16, 17, 18, 19});
        String rose = "{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":" + RSI + ",\"operator\":\"CHANGE_ABOVE\",\"candles\":2,\"percent\":1}";
        assertThat(matches(rule(then(20, null, step(rose, 1, null), step(anything, 1, 1))), change).getFirst()).isEqualTo(7);
    }

    @Test
    void theSpanLimitsAChainAlsoWhenItCountsAsRecent() {
        Stub facts = flat(12);
        // Oversold on candles 1–5, 30 on 6–7, above on 8: from the first oversold candle to the recovery is 8 candles.
        facts.series.put("RSI:value", new double[]{35, 29, 28, 27, 26, 25, 30, 30, 31, 32, 33, 34});
        String recent = "{\"mode\":\"ANY\",\"candles\":3}";
        assertThat(matches(rule(recovery(3, 8, recent)), facts)).containsExactly(8, 9, 10);
        assertThat(matches(rule(recovery(3, 7, recent)), facts)).as("7 candles from step 1's first candle is too short").isEmpty();
    }

    @Test
    void halfwayCountsTheCandlesTheSpanLeaves() {
        Stub facts = flat(4);
        facts.series.put("RSI:value", new double[]{40, 25, 25, 25});
        // Step 2 may come up to 10 candles after step 1, but the whole sequence must fit in 6 from candle 1: candle 6 at the latest.
        var definition = rule(then(6, null, step(rsi("c1", "BELOW", 30), 3, null), step(rsi("c2", "ABOVE", 30), 1, 10)));
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 3).groups().getFirst().facts())
                .containsEntry("done", 1).containsEntry("candlesLeft", 3);
    }

    @Test
    void crossesAndRisesTakeNoCandlesInARow() {
        var definition = rule(then(30, null, step(rsi("c1", "CROSSES_BELOW", 30), 3, null),
                step("{\"id\":\"c2\",\"kind\":\"VALUE\",\"subject\":" + RSI + ",\"operator\":\"RISING\",\"candles\":2}", 4, 3),
                step(rsi("c3", "ABOVE", 40), 2, 3)));
        assertThat(definition.groups().getFirst().sequence().steps()).extracting(CustomAlertDefinition.Step::candles).containsExactly(1, 1, 2);
        String reversed = CustomAlertText.rule(rule(then(20, null, step(rsi("c1", "ABOVE", 30).replace("\"kind\":\"VALUE\"",
                "\"kind\":\"VALUE\",\"negate\":true"), 5, null), step(rsi("c2", "ABOVE", 40), 1, 3))), CATALOG);
        assertThat(reversed).contains("- Step 1: Not: RSI (14) is above 30 on each of at least 5 candles in a row");
    }

    @Test
    void aReversedStepHoldsWhileItsConditionDoesNot() {
        Stub facts = flat(8);
        facts.series.put("RSI:value", new double[]{50, 28, 30, 29, 45, 45, 45, 45});
        String notAbove = rsi("c1", "ABOVE", 30).replace("\"kind\":\"VALUE\"", "\"kind\":\"VALUE\",\"negate\":true");
        var definition = rule(then(20, null, step(notAbove, 3, null), step(rsi("c2", "ABOVE", 40), 1, 2)));
        assertThat(matches(definition, facts)).as("at most 30 on candles 1–3, above 40 on candle 4").containsExactly(4);
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 4).conditions().getFirst().facts()).containsEntry("negated", true);
    }

    @Test
    void aSequenceNeverChangesItsAnswerWhenLaterCandlesArrive() {
        double[] rsi = {50, 28, 26, 24, 31, 33, 29, 27, 25, 26, 34, 36, 29, 28, 27, 26, 31, 35, 38, 33};
        var definition = rule(then(12, "{\"mode\":\"ANY\",\"candles\":2}", step(rsi("c1", "BELOW", 30), 3, null),
                step(rsi("c2", "ABOVE", 30), 1, 2), step(rsi("c3", "ABOVE", 34), 1, 3)));
        Stub full = flat(rsi.length);
        full.series.put("RSI:value", rsi);
        for (int length = 3; length < rsi.length; length++) {
            Stub prefix = flat(length);
            prefix.series.put("RSI:value", java.util.Arrays.copyOf(rsi, length));
            for (int index = 0; index < length; index++) {
                assertThat(CustomAlertEvaluator.evaluate(definition, prefix, index).matched()).as("candle %d of %d", index, length)
                        .isEqualTo(CustomAlertEvaluator.evaluate(definition, full, index).matched());
            }
        }
        assertThat(matches(definition, full)).isNotEmpty();
    }

    @Test
    void sequencesAreValidatedAndReadBackTheSame() {
        String outlook = "{\"id\":\"c2\",\"kind\":\"OUTLOOK\",\"direction\":\"BUY\",\"strength\":\"SLIGHT\"}";
        String candle = "{\"id\":\"c2\",\"kind\":\"PATTERN\",\"negate\":true,\"family\":\"CANDLESTICK\",\"direction\":\"BULLISH\"}";
        assertThatThrownBy(() -> rule(then(20, null, step(rsi("c1", "BELOW", 30), 5, null))))
                .hasMessage("A sequence needs 2 to 4 steps.");
        assertThatThrownBy(() -> rule(then(20, null, rsi("a", "BELOW", 30), rsi("b", "BELOW", 30), rsi("c", "BELOW", 30),
                rsi("d", "BELOW", 30), rsi("e", "BELOW", 30)))).hasMessage("A sequence needs 2 to 4 steps.");
        assertThatThrownBy(() -> rule(then(20, null, rsi("c1", "BELOW", 30), outlook)))
                .hasMessage("Outlook, insider and Congress conditions can't be steps of a sequence.");
        assertThatThrownBy(() -> rule(then(20, null, rsi("c1", "BELOW", 30), candle))).hasMessage("A pattern step can't be reversed.");
        assertThatThrownBy(() -> rule(recovery(3, 5, null))).hasMessage("The whole sequence needs at least 6 candles.");
        assertThatThrownBy(() -> rule(then(20, null, step(rsi("c1", "BELOW", 30), 61, null), rsi("c2", "ABOVE", 30))))
                .hasMessage("Choose 1 to 60 candles in a row.");
        assertThatThrownBy(() -> rule(then(121, null, rsi("c1", "BELOW", 30), rsi("c2", "ABOVE", 30))))
                .hasMessage("Choose 2 to 120 candles.");
        assertThatThrownBy(() -> rule(recovery(3, 20, null).replace("THEN", "SOMETIMES")))
                .hasMessage("Choose whether all, any or these in order must be true.");

        var parsed = rule(recovery(3, 20, null));
        String written = parsed.toJson().toString();
        assertThat(written).contains("\"match\":\"THEN\",\"span\":20,\"window\":{\"mode\":\"LATEST\"}")
                .contains("\"step\":{\"candles\":5}").contains("\"step\":{\"candles\":1,\"within\":3}");
        assertThat(CustomAlertDefinition.parse(parsed.toJson(), CATALOG, TimeInterval.DAILY).toJson()).isEqualTo(parsed.toJson());
        assertThat(parsed.lookbackCandles()).isGreaterThanOrEqualTo(21);

        // A step's own window is replaced by the step settings; without a span each step gets its full allowance.
        var defaults = rule(then(null, null, rsi("c1", "BELOW", 30).replace("}}", "},\"window\":{\"mode\":\"EVERY\",\"candles\":4}}"),
                step("{\"id\":\"c2\",\"kind\":\"PATTERN\",\"family\":\"CANDLESTICK\",\"direction\":\"BULLISH\",\"window\":{\"mode\":\"ANY\",\"candles\":9}}", 3, 40)));
        assertThat(defaults.groups().getFirst().sequence().span()).isEqualTo(42);
        assertThat(defaults.groups().getFirst().sequence().steps()).containsExactly(new CustomAlertDefinition.Step(1, 0),
                new CustomAlertDefinition.Step(1, 40));
        assertThat(((PatternCondition) defaults.conditions().get(1)).window().span()).isEqualTo(1);
        assertThat(defaults.patternLookbackCandles()).isEqualTo(43);
    }

    @Test
    void aSequenceReadsAsNumberedSteps() {
        var recovery = rule(recovery(3, 20, null));
        assertThat(CustomAlertText.rule(recovery, CATALOG)).isEqualTo("""
                In this order, all within 20 candles:
                - Step 1: RSI (14) is below 30 for at least 5 candles in a row
                - Step 2, within 3 candles: RSI (14) is above 30""");
        String candle = "{\"id\":\"c3\",\"kind\":\"PATTERN\",\"family\":\"CANDLESTICK\",\"status\":\"CONFIRMED\",\"direction\":\"BULLISH\","
                + "\"patterns\":[\"HAMMER\"]}";
        var withPattern = rule(then(25, "{\"mode\":\"ANY\",\"candles\":3}", step(rsi("c1", "BELOW", 30), 5, null),
                step(rsi("c2", "ABOVE", 30), 1, 1), step(candle, 1, 5)));
        assertThat(CustomAlertText.rule(withPattern, CATALOG)).isEqualTo("""
                In this order, all within 25 candles, completed in the last 3 candles:
                - Step 1: RSI (14) is below 30 for at least 5 candles in a row
                - Step 2, on the next candle: RSI (14) is above 30
                - Step 3, within 5 candles: Bullish candlestick pattern confirmed. Patterns: Hammer.""");
    }

    @Test
    void theNormalizedRuleReadsBackTheSame() {
        JsonNode original = JSON.readTree(single("{\"id\":\"c1\",\"kind\":\"VALUE\",\"subject\":" + RSI
                + ",\"operator\":\"CROSSES_BELOW\",\"compare\":{\"kind\":\"VALUE\",\"value\":30},\"window\":{\"mode\":\"ANY\",\"candles\":3}}"));
        var parsed = CustomAlertDefinition.parse(original, CATALOG, TimeInterval.DAILY);
        var again = CustomAlertDefinition.parse(parsed.toJson(), CATALOG, TimeInterval.DAILY);
        assertThat(again.toJson()).isEqualTo(parsed.toJson());
        assertThat(CustomAlertText.rule(parsed)).contains("RSI (14").contains("crosses below 30 at least once in the last 3 candles");
        // Stored rules carry every setting; the wording names the length and only settings changed from the defaults.
        assertThat(CustomAlertText.rule(parsed, CATALOG)).isEqualTo("- RSI (14) crosses below 30 at least once in the last 3 candles");
    }
}
