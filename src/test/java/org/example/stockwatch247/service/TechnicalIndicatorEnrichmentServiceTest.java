package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.technical.TechnicalResearchSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TechnicalIndicatorEnrichmentServiceTest {
    private final TechnicalIndicatorEnrichmentService service = new TechnicalIndicatorEnrichmentService();

    @Test
    void dailyProfileUsesRsi14Ema20And50AndSma200() {
        ProfileResult result = enrichProfile(TimeInterval.DAILY, "1d", 86_400L);

        assertThat(result.requiredCandles()).isEqualTo(299);
        assertThat(result.first().timestamp()).isEqualTo(200L * 86_400L);
        assertThat(result.first().longSma()).isCloseTo(100.5, within(0.000_000_1));
        assertThat(result.last().longSma()).isCloseTo(199.5, within(0.000_000_1));
        assertFullyWarmed(result);
    }

    @Test
    void weeklyProfileUsesShorterNativeBarPeriodsAndFortyBarSma() {
        ProfileResult result = enrichProfile(TimeInterval.WEEKLY, "1wk", 7L * 86_400L);

        assertThat(result.requiredCandles()).isEqualTo(139);
        assertThat(result.first().longSma()).isCloseTo(20.5, within(0.000_000_1));
        assertThat(result.last().longSma()).isCloseTo(119.5, within(0.000_000_1));
        assertFullyWarmed(result);
    }

    @Test
    void monthlyProfileUsesNineBarRsiAndTwentyFourBarSma() {
        ProfileResult result = enrichProfile(TimeInterval.MONTHLY, "1mo", 30L * 86_400L);

        assertThat(result.requiredCandles()).isEqualTo(123);
        assertThat(result.first().longSma()).isCloseTo(12.5, within(0.000_000_1));
        assertThat(result.last().longSma()).isCloseTo(111.5, within(0.000_000_1));
        assertFullyWarmed(result);
    }

    @Test
    void elliottKeepsItsFrozenLegacyIndicatorPeriods() {
        int signalCandles = 100;
        int required = service.requiredElliottInputCandles(signalCandles, TimeInterval.WEEKLY);
        List<Candle> history = IntStream.rangeClosed(1, required)
                .mapToObj(index -> candle(index, "1wk", 7L * 86_400L))
                .toList();

        List<EnrichedCandle> enriched =
                service.enrichForElliott(history, signalCandles, TimeInterval.WEEKLY);

        assertThat(required).isEqualTo(299);
        assertThat(enriched).hasSize(signalCandles);
        assertThat(enriched.getFirst().longSma()).isCloseTo(100.5, within(0.000_000_1));
        assertThat(enriched.getLast().longSma()).isCloseTo(199.5, within(0.000_000_1));
        assertThat(enriched).allSatisfy(candle -> {
            assertThat(candle.rsi()).isFinite();
            assertThat(candle.fastEma()).isFinite();
            assertThat(candle.atr()).isFinite();
            assertThat(candle.averageVolume()).isFinite();
        });
    }

    @Test
    void elliottDailyFractalUsesTheFrozenNativeBarProfile() {
        int signalCandles = 100;
        int required = service.requiredElliottInputCandles(signalCandles, TimeInterval.DAILY);
        List<Candle> history = IntStream.rangeClosed(1, required)
                .mapToObj(index -> candle(index, "1d", 86_400L))
                .toList();

        List<EnrichedCandle> enriched =
                service.enrichForElliott(history, signalCandles, TimeInterval.DAILY);

        assertThat(required).isEqualTo(299);
        assertThat(enriched).hasSize(signalCandles);
        assertThat(enriched.getFirst().longSma()).isCloseTo(100.5, within(0.000_000_1));
        assertThat(enriched.getLast().longSma()).isCloseTo(199.5, within(0.000_000_1));
    }

    @Test
    void rejectsCandlesWhoseDeclaredIntervalDoesNotMatchTheRequestedProfile() {
        List<Candle> daily = IntStream.rangeClosed(1, 30)
                .mapToObj(day -> candle(day, "1d", 86_400L))
                .toList();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.enrich(daily, 20, TimeInterval.WEEKLY)
                )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match requested WEEKLY");
    }

    @Test
    void calculatesNewTa4jIndicatorsInAResearchOnlySnapshot() {
        List<Candle> history = IntStream.rangeClosed(1, 320)
                .mapToObj(this::oscillatingCandle)
                .toList();

        List<TechnicalResearchSnapshot> research = service.research(
                history, 100, TechnicalIndicatorProfile.forInterval(TimeInterval.DAILY));

        assertThat(research).hasSize(100);
        TechnicalResearchSnapshot latest = research.getLast();
        assertThat(latest.adx()).isFinite();
        assertThat(latest.plusDi()).isFinite();
        assertThat(latest.minusDi()).isFinite();
        assertThat(latest.stochasticK()).isFinite();
        assertThat(latest.stochasticD()).isFinite();
        assertThat(latest.stochasticRsi()).isFinite();
        assertThat(latest.obv()).isFinite();
        assertThat(latest.moneyFlowIndex()).isFinite();
        assertThat(latest.donchianLower()).isLessThanOrEqualTo(latest.donchianUpper());
        assertThat(latest.keltnerLower()).isLessThan(latest.keltnerUpper());
        assertThat(latest.volumeProfileKdeMode()).isFinite();
        assertThat(research).allSatisfy(snapshot ->
                assertThat(snapshot.upTrend() && snapshot.downTrend()).isFalse());
    }

    private ProfileResult enrichProfile(TimeInterval interval, String providerInterval, long secondsPerBar) {
        int signalCandles = 100;
        int required = service.requiredInputCandles(signalCandles, interval);
        List<Candle> history = IntStream.rangeClosed(1, required)
                .mapToObj(index -> candle(index, providerInterval, secondsPerBar))
                .toList();

        List<EnrichedCandle> enriched = service.enrich(history, signalCandles, interval);
        assertThat(enriched).hasSize(signalCandles);
        return new ProfileResult(required, enriched);
    }

    private void assertFullyWarmed(ProfileResult result) {
        assertThat(result.enriched()).allSatisfy(candle -> {
            assertThat(candle.averageVolume()).isFinite();
            assertThat(candle.rsi()).isFinite();
            assertThat(candle.fastEma()).isFinite();
            assertThat(candle.slowEma()).isFinite();
            assertThat(candle.longSma()).isFinite();
            assertThat(candle.macdLine()).isFinite();
            assertThat(candle.macdSignal()).isFinite();
            assertThat(candle.macdHistogram()).isFinite();
            assertThat(candle.cci()).isFinite();
            assertThat(candle.bollingerMiddle()).isFinite();
            assertThat(candle.lowerBollinger()).isFinite();
            assertThat(candle.upperBollinger()).isFinite();
            assertThat(candle.atr()).isFinite();
            assertThat(candle.rollingVwap()).isFinite();
            assertThat(candle.volumeProfilePointOfControl()).isFinite();
            assertThat(candle.volumeProfileValueAreaLow()).isFinite();
            assertThat(candle.volumeProfileValueAreaHigh()).isFinite();
            assertThat(candle.volumeProfileValueAreaLow())
                    .isLessThanOrEqualTo(candle.volumeProfilePointOfControl());
            assertThat(candle.volumeProfilePointOfControl())
                    .isLessThanOrEqualTo(candle.volumeProfileValueAreaHigh());
        });
    }

    private Candle candle(int index, String interval, long secondsPerBar) {
        double close = index;
        return new Candle(
                "VST",
                interval,
                index * secondsPerBar,
                close - 0.5,
                close + 1.0,
                close - 1.0,
                close,
                1_000L
        );
    }

    private Candle oscillatingCandle(int index) {
        double close = 100.0 + index * 0.08 + Math.sin(index * 0.31) * 3.0;
        return new Candle(
                "VST",
                "1d",
                index * 86_400L,
                close - Math.sin(index * 0.17) * 0.4,
                close + 1.0,
                close - 1.0,
                close,
                1_000L + (index % 11) * 100L
        );
    }

    private record ProfileResult(int requiredCandles, List<EnrichedCandle> enriched) {
        private EnrichedCandle first() {
            return enriched.getFirst();
        }

        private EnrichedCandle last() {
            return enriched.getLast();
        }
    }
}
