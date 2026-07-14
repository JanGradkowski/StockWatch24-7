package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertRuleServiceTest {

    @Test
    void checkLatestSignalDetectsCurrentMonthlyElliottBuySignal() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleService service = service(candleRepository, marketDataService);
        User user = new User();
        user.setEmail("alerts@example.com");

        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE, 76, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1mo"))
                .thenReturn(syntheticElliottCandles(symbol).reversed());

        Map<String, Object> response = service.checkLatestSignal(
                user,
                symbol,
                TimeInterval.MONTHLY,
                TradeSignal.BUY,
                AlertPatternFamily.ELLIOTT_WAVE
        );

        assertThat(response)
                .containsEntry("symbol", symbol)
                .containsEntry("interval", "MONTHLY")
                .containsEntry("signal", "BUY")
                .containsEntry("patternFamily", "ELLIOTT_WAVE")
                .containsEntry("matched", true);
        assertThat(response.get("matchingPatterns"))
                .asList()
                .contains("ELLIOTT_BULLISH_CORRECTION");
        assertThat(response.get("detectedSignals"))
                .asList()
                .anySatisfy(signal -> {
                    Map<?, ?> signalMap = (Map<?, ?>) signal;
                    assertThat(signalMap.get("patternFamily")).isEqualTo("ELLIOTT_WAVE");
                    assertThat(signalMap.get("signal")).isEqualTo("BUY");
                    assertThat(signalMap.get("pattern")).isEqualTo("ELLIOTT_BULLISH_CORRECTION");
                });
    }

    @Test
    void checkLatestSignalSupportsCurrentWeeklyEndOfWaveC() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleService service = service(candleRepository, marketDataService);

        when(marketDataService.syncCandles(symbol, "1wk", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1wk"))
                .thenReturn(syntheticElliottCandles(symbol, "1wk").reversed());

        Map<String, Object> response = service.checkLatestSignal(
                new User(), symbol, TimeInterval.WEEKLY, TradeSignal.BUY, AlertPatternFamily.ELLIOTT_WAVE);

        assertThat(response).containsEntry("matched", true).containsEntry("interval", "WEEKLY");
        assertThat(response.get("matchingPatterns")).asList().contains("ELLIOTT_BULLISH_CORRECTION");
    }

    @Test
    void checkLatestSignalDetectsCurrentBullishWaveVEndAsSell() {
        String symbol = "SAP.DE";
        MarketDataService marketDataService = mock(MarketDataService.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        AlertRuleService service = service(candleRepository, marketDataService);
        when(marketDataService.syncCandles(symbol, "1mo", null, true))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1mo"))
                .thenReturn(syntheticWaveVEndCandles(symbol, "1mo").reversed());

        Map<String, Object> response = service.checkLatestSignal(
                new User(), symbol, TimeInterval.MONTHLY, TradeSignal.SELL, AlertPatternFamily.ELLIOTT_WAVE);

        assertThat(response).containsEntry("matched", true);
        assertThat(response.get("matchingPatterns")).asList().contains("ELLIOTT_BULLISH_WAVE_V_END");
    }

    private AlertRuleService service(CandleRepository candleRepository,
                                     MarketDataService marketDataService) {
        return new AlertRuleService(
                mock(AlertRuleRepository.class),
                mock(AlertEventRepository.class),
                mock(StockAssetRepository.class),
                candleRepository,
                mock(TwelveDataService.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                marketDataService,
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                new ElliottWaveDetectionService(),
                true,
                true,
                50,
                500
        );
    }

    private List<Candle> syntheticElliottCandles(String symbol) {
        return syntheticElliottCandles(symbol, "1mo");
    }

    private List<Candle> syntheticElliottCandles(String symbol, String interval) {
        List<Anchor> anchors = List.of(
                new Anchor(1, 112.0),
                new Anchor(6, 100.0),
                new Anchor(14, 121.0),
                new Anchor(24, 110.0),
                new Anchor(38, 143.0),
                new Anchor(54, 126.0),
                new Anchor(68, 150.0),
                new Anchor(74, 134.0),
                new Anchor(80, 144.0),
                new Anchor(86, 130.0),
                new Anchor(87, 133.0)
        ).stream().sorted(Comparator.comparingInt(Anchor::index)).toList();
        List<Candle> candles = new ArrayList<>();
        for (int anchorIndex = 0; anchorIndex < anchors.size() - 1; anchorIndex++) {
            Anchor start = anchors.get(anchorIndex);
            Anchor end = anchors.get(anchorIndex + 1);
            int from = anchorIndex == 0 ? start.index() : start.index() + 1;
            for (int index = from; index <= end.index(); index++) {
                double progress = (index - start.index()) / (double) (end.index() - start.index());
                double close = start.price() + (end.price() - start.price()) * progress;
                candles.add(new Candle(
                        symbol, interval, index * 86_400L, close - 0.4, close + 0.6, close - 0.6, close, 1_500L));
            }
        }
        return candles;
    }

    private List<Candle> syntheticWaveVEndCandles(String symbol, String interval) {
        List<Anchor> anchors = List.of(
                new Anchor(1, 112.0), new Anchor(6, 100.0), new Anchor(14, 121.0),
                new Anchor(24, 110.0), new Anchor(38, 143.0), new Anchor(54, 126.0),
                new Anchor(68, 150.0), new Anchor(69, 147.0)
        );
        List<Candle> candles = new ArrayList<>();
        for (int anchorIndex = 0; anchorIndex < anchors.size() - 1; anchorIndex++) {
            Anchor start = anchors.get(anchorIndex);
            Anchor end = anchors.get(anchorIndex + 1);
            int from = anchorIndex == 0 ? start.index() : start.index() + 1;
            for (int index = from; index <= end.index(); index++) {
                double progress = (index - start.index()) / (double) (end.index() - start.index());
                double close = start.price() + (end.price() - start.price()) * progress;
                candles.add(new Candle(symbol, interval, index * 86_400L,
                        close - 0.4, close + 0.6, close - 0.6, close, 1_500L));
            }
        }
        return candles;
    }

    private record Anchor(int index, double price) {
    }
}
