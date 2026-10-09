package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorporateActionServiceTest {
    private static final long DAY = 86_400L;
    private static final long START = 1_759_708_800L; // 2025-10-06T00:00:00Z

    private static MarketDataBar bar(int day, double close) {
        return new MarketDataBar("ACME", START + day * DAY, close, close * 1.01, close * 0.99, close, 1_000);
    }

    private static Candle stored(int day, double close) {
        return new Candle("ACME", "1d", START + day * DAY, close, close * 1.01, close * 0.99, close, 1_000L);
    }

    @Test
    void fourForOneSplitInsideTheOverlapIsDetectedAtTheLastOldBasisBar() {
        List<MarketDataBar> fetched = new ArrayList<>();
        Map<Long, Candle> existing = new LinkedHashMap<>();
        for (int day = 0; day < 6; day++) {
            fetched.add(bar(day, 100 + day));
            // Days 0-3 were stored before the split at four times today's adjusted prices.
            existing.put(START + day * DAY, stored(day, day < 4 ? (100 + day) * 4 : 100 + day));
        }

        var split = CorporateActionService.detect(fetched, existing, START + 10 * DAY).orElseThrow();

        assertThat(split.numerator()).isEqualTo(4);
        assertThat(split.denominator()).isEqualTo(1);
        assertThat(split.cutoffTimestamp()).isEqualTo(START + 3 * DAY);
    }

    @Test
    void reverseSplitIsDetected() {
        List<MarketDataBar> fetched = new ArrayList<>();
        Map<Long, Candle> existing = new LinkedHashMap<>();
        for (int day = 0; day < 4; day++) {
            fetched.add(bar(day, 50));
            existing.put(START + day * DAY, stored(day, 5));
        }
        var split = CorporateActionService.detect(fetched, existing, START + 10 * DAY).orElseThrow();
        assertThat(split.label()).isEqualTo("1:10");
    }

    @Test
    void currencyLikeOrInconsistentRevisionsAreNotTreatedAsSplits() {
        List<MarketDataBar> fetched = new ArrayList<>();
        Map<Long, Candle> fxLike = new LinkedHashMap<>();
        Map<Long, Candle> noisy = new LinkedHashMap<>();
        for (int day = 0; day < 5; day++) {
            fetched.add(bar(day, 100));
            fxLike.put(START + day * DAY, stored(day, 108)); // 27:25, e.g. a USD/EUR listing swap
            noisy.put(START + day * DAY, stored(day, day % 2 == 0 ? 200 : 150));
        }
        assertThat(CorporateActionService.detect(fetched, fxLike, START + 10 * DAY)).isEmpty();
        assertThat(CorporateActionService.detect(fetched, noisy, START + 10 * DAY)).isEmpty();
    }

    @Test
    void aRevisedFormingBarIsNotASplit() {
        List<MarketDataBar> fetched = new ArrayList<>();
        Map<Long, Candle> existing = new LinkedHashMap<>();
        for (int day = 0; day < 5; day++) {
            fetched.add(bar(day, 100));
            existing.put(START + day * DAY, stored(day, day == 4 ? 50 : 100));
        }
        // Day 4 is still forming, so it is outside the completed overlap.
        assertThat(CorporateActionService.detect(fetched, existing, START + 4 * DAY)).isEmpty();
    }

    @Test
    void snapshotPricesAreRescaledWithoutTouchingLabelsOrTimestamps() {
        String snapshot = "0|100|400.0000000000|LOW\nI|200|480.0000000000|HIGH";
        assertThat(CorporateActionService.rescaleSnapshot(snapshot, 2, 4.0))
                .isEqualTo("0|100|100.0000000000|LOW\nI|200|120.0000000000|HIGH");
    }

    @Test
    void outageGapsAreMeasuredInTradingDays() {
        long friday = 1_759_449_600L; // 2025-10-03
        long followingWednesday = friday + 5 * DAY;
        assertThat(MarketDataService.expectedPeriodsBetween("1d", friday, followingWednesday)).isEqualTo(3);
        assertThat(MarketDataService.expectedPeriodsBetween("1wk", START, START + 21 * DAY)).isEqualTo(3);
    }
}
