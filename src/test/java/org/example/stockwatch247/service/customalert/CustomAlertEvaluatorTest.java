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

    @Test
    void confirmedCandlesticksCountOnlyOnceTheirConfirmingCloseHasHappened() {
        Stub facts = new Stub(10, 10, 10, 10, 10, 10);
        facts.patterns.add(new PatternHit("CANDLESTICK", "BULLISH_ENGULFING", "BULLISH_ENGULFING", true, 80,
                facts.at(1), facts.at(1), "CONFIRMED", facts.at(3), 10, null));
        var definition = rule(single("{\"id\":\"c1\",\"kind\":\"PATTERN\",\"family\":\"CANDLESTICK\",\"direction\":\"BULLISH\","
                + "\"confirmedOnly\":true,\"window\":{\"mode\":\"ANY\",\"candles\":5}}"));
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 2).matched()).isFalse();
        assertThat(CustomAlertEvaluator.evaluate(definition, facts, 3).matched()).isTrue();
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
