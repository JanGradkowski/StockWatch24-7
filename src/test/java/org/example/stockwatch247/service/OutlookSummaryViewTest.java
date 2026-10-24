package org.example.stockwatch247.service;

import org.example.stockwatch247.service.TechnicalOutlookService.CategoryView;
import org.example.stockwatch247.service.TechnicalOutlookService.MethodologyView;
import org.example.stockwatch247.service.TechnicalOutlookService.OutlookSummaryView;
import org.example.stockwatch247.service.TechnicalOutlookService.OutlookView;
import org.example.stockwatch247.service.TechnicalOutlookService.RecentSignalView;
import org.example.stockwatch247.service.TechnicalOutlookService.ScoreView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class OutlookSummaryViewTest {

    @Test
    void keepsTheVerdictCategoriesAndNewestSignalsButNotTheChartData() {
        ScoreView headline = new ScoreView(2, 2, 3, -1, 7, -1 / 7.0, "Slight sell outlook");
        List<CategoryView> categories = List.of(
                new CategoryView("TREND", "Trend", 1, "BUY", 0.33, List.of("EMA 20 / EMA 50")),
                new CategoryView("VOLUME", "Volume", -1, "SELL", -0.5, List.of("Rolling VWAP 20")));
        List<RecentSignalView> signals = IntStream.range(0, 5)
                .mapToObj(index -> new RecentSignalView((long) index, "CANDLESTICK", "Shooting star", "SELL", "DAILY",
                        "Daily", 1_791_849_600L - index * 86_400L, 43, "Low confidence", "Detected",
                        "Pending observation", "/alerts/signals/" + index))
                .toList();
        OutlookView outlook = new OutlookView("MSFT", "Microsoft Corp.", "1d", "Daily", true, 1_792_713_600L,
                "Current completed candle", headline, headline, categories, List.of(), List.of(), signals, List.of(),
                null, new MethodologyView("", "", "", ""));

        OutlookSummaryView summary = OutlookSummaryView.of(outlook);

        assertThat(summary.symbol()).isEqualTo("MSFT");
        assertThat(summary.interval()).isEqualTo("1d");
        assertThat(summary.intervalLabel()).isEqualTo("Daily");
        assertThat(summary.available()).isTrue();
        assertThat(summary.candleTimestamp()).isEqualTo(1_792_713_600L);
        assertThat(summary.freshness()).isEqualTo("Current completed candle");
        assertThat(summary.headlineScore()).isEqualTo(headline);
        assertThat(summary.categories()).isEqualTo(categories);
        assertThat(summary.recentSignals()).extracting(RecentSignalView::id).containsExactly(0L, 1L, 2L);
    }

    @Test
    void anUnavailableOutlookStaysUnavailable() {
        ScoreView empty = new ScoreView(0, 0, 0, 0, 0, 0, "Neutral outlook");
        OutlookView outlook = new OutlookView("MSFT", "MSFT", "1wk", "Weekly", false, null,
                "No completed candles are available", empty, empty, List.of(), List.of(), List.of(), List.of(),
                List.of(), null, new MethodologyView("", "", "", ""));

        OutlookSummaryView summary = OutlookSummaryView.of(outlook);

        assertThat(summary.available()).isFalse();
        assertThat(summary.categories()).isEmpty();
        assertThat(summary.recentSignals()).isEmpty();
    }
}
