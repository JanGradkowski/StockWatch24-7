package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataFallbackPipelineTest {

    @Test
    void usesTwelveDataWithoutCallingYahooWhenPrimaryProviderSucceeds() {
        TestContext context = context(List.of(bar(1, 100, 105, 99, 104)), false);
        when(context.twelveDataService().getTimeSeries("SAP.DE", "1day", 1000))
                .thenReturn(List.of(bar(1, 100, 105, 99, 104)));

        MarketDataService.CandleSyncResult result = context.marketDataService()
                .syncCandles("SAP.DE", "1d", null, true);

        assertThat(result.source()).isEqualTo(MarketDataService.CandleSource.TWELVE_DATA);
        assertThat(result.candlesSynced()).isEqualTo(1);
        verify(context.yahooFinanceService(), never()).getTimeSeries(anyString(), anyString(), anyInt());
    }

    @Test
    void yahooFallbackCandlesStillProduceCandlestickPatternAndConfidenceScore() {
        List<MarketDataBar> yahooBars = List.of(
                bar(1, 100, 105, 88, 90),
                bar(2, 89, 103, 87, 101)
        );
        TestContext context = context(yahooBars, true);

        MarketDataService.CandleSyncResult result = context.marketDataService()
                .syncCandles("SAP.DE", "1d", null, true);
        List<EnrichedCandle> enriched = new TechnicalIndicatorEnrichmentService()
                .enrich(context.savedCandles(), 5);
        List<DetectedSignal> signals = new CandlePatternDetectionService().detect(enriched);

        assertThat(result.source()).isEqualTo(MarketDataService.CandleSource.YAHOO_FINANCE);
        assertThat(context.savedCandles()).hasSize(2);
        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.BULLISH_ENGULFING);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.confidenceScore()).isBetween(0, 100);
            assertThat(signal.candleTimestamp()).isEqualTo(2L * 86_400L);
        });
    }

    @Test
    void yahooFallbackCandlesStillProduceElliottWaveSignal() {
        List<MarketDataBar> yahooBars = syntheticElliottBars();
        TestContext context = context(yahooBars, true);

        MarketDataService.CandleSyncResult result = context.marketDataService()
                .syncCandles("SAP.DE", "1mo", null, true);
        List<EnrichedCandle> enriched = new TechnicalIndicatorEnrichmentService()
                .enrich(context.savedCandles(), 80);
        List<DetectedSignal> signals = new ElliottWaveDetectionService().detect(enriched);

        assertThat(result.source()).isEqualTo(MarketDataService.CandleSource.YAHOO_FINANCE);
        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_IMPULSE);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.confidenceScore()).isGreaterThanOrEqualTo(75);
        });
    }

    private TestContext context(List<MarketDataBar> yahooBars, boolean failTwelveData) {
        CandleRepository candleRepository = mock(CandleRepository.class);
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        TwelveDataService twelveDataService = mock(TwelveDataService.class);
        YahooFinanceService yahooFinanceService = mock(YahooFinanceService.class);
        List<Candle> savedCandles = new ArrayList<>();

        when(twelveDataService.normalizeSymbol(anyString()))
                .thenAnswer(invocation -> invocation.<String>getArgument(0).trim().toUpperCase());

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("SAP.DE");
        asset.setCompanyName("SAP SE");
        asset.setExchange("XETRA");
        asset.setCurrency("EUR");
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("SAP.DE")).thenReturn(Optional.of(asset));
        when(candleRepository.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(anyString(), anyString()))
                .thenReturn(List.of());
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampIn(
                anyString(), anyString(), anyCollection())).thenReturn(List.of());
        when(candleRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<Candle> candles = invocation.getArgument(0);
            candles.forEach(savedCandles::add);
            return savedCandles;
        });
        if (failTwelveData) {
            when(twelveDataService.getTimeSeries(anyString(), anyString(), anyInt()))
                    .thenThrow(new IllegalStateException("plan does not include this market"));
        }
        when(yahooFinanceService.getTimeSeries("SAP.DE", "1d", 1000)).thenReturn(yahooBars);
        when(yahooFinanceService.getTimeSeries("SAP.DE", "1mo", 1000)).thenReturn(yahooBars);

        MarketDataService service = new MarketDataService(
                candleRepository,
                stockAssetRepository,
                twelveDataService,
                yahooFinanceService,
                new InMemoryMarketDataSyncCoordinator(() -> 1_800_000_000L),
                60,
                600,
                3_600,
                180);
        return new TestContext(service, twelveDataService, yahooFinanceService, savedCandles);
    }

    private List<MarketDataBar> syntheticElliottBars() {
        List<Anchor> anchors = List.of(
                new Anchor(1, 112.0),
                new Anchor(6, 100.0),
                new Anchor(14, 121.0),
                new Anchor(24, 110.0),
                new Anchor(38, 143.0),
                new Anchor(54, 126.0),
                new Anchor(75, 142.0),
                new Anchor(76, 146.0)
        ).stream().sorted(Comparator.comparingInt(Anchor::index)).toList();
        List<MarketDataBar> bars = new ArrayList<>();
        for (int anchorIndex = 0; anchorIndex < anchors.size() - 1; anchorIndex++) {
            Anchor start = anchors.get(anchorIndex);
            Anchor end = anchors.get(anchorIndex + 1);
            int from = anchorIndex == 0 ? start.index() : start.index() + 1;
            for (int index = from; index <= end.index(); index++) {
                double progress = (index - start.index()) / (double) (end.index() - start.index());
                double close = start.price() + (end.price() - start.price()) * progress;
                bars.add(new MarketDataBar(
                        "SAP.DE", index * 86_400L, close - 0.4, close + 0.6, close - 0.6, close, 1_500));
            }
        }
        return bars;
    }

    private MarketDataBar bar(int day, double open, double high, double low, double close) {
        return new MarketDataBar("SAP.DE", day * 86_400L, open, high, low, close, 1_000);
    }

    private record TestContext(MarketDataService marketDataService,
                               TwelveDataService twelveDataService,
                               YahooFinanceService yahooFinanceService,
                               List<Candle> savedCandles) {
    }

    private record Anchor(int index, double price) {
    }
}
