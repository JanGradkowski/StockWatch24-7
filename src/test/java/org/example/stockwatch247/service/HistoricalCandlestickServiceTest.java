package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HistoricalCandlestickServiceTest {

    @Test
    void dailyScanEvaluatesRecentSignalsAndLeavesNewSignalsPendingWithoutCaching() {
        String symbol = "AAPL";
        CandleRepository candleRepository = mock(CandleRepository.class);
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        TechnicalIndicatorEnrichmentService enrichmentService = mock(TechnicalIndicatorEnrichmentService.class);
        CandlePatternDetectionService detectionService = mock(CandlePatternDetectionService.class);
        CandleCompletionService completionService = mock(CandleCompletionService.class);
        List<Candle> candles = candles(symbol, "1d", 80);
        candles.get(40).setClosePrice(100.0);
        candles.get(50).setClosePrice(95.0);
        candles.get(60).setClosePrice(100.0);
        candles.get(70).setClosePrice(105.0);
        List<EnrichedCandle> enriched = candles.stream().map(this::enriched).toList();
        long successfulSellTimestamp = candles.get(40).getTimestamp();
        long successfulTimestamp = candles.get(60).getTimestamp();
        long pendingTimestamp = candles.get(77).getTimestamp();

        when(marketDataService.syncCandles(symbol, "1d", null))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        when(enrichmentService.requiredInputCandles(68, TimeInterval.DAILY)).thenReturn(267);
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 268))).thenReturn(candles.reversed());
        when(completionService.isComplete(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(TimeInterval.DAILY))).thenReturn(true);
        when(enrichmentService.enrich(candles, candles.size(), TimeInterval.DAILY)).thenReturn(enriched);
        when(detectionService.detectAlertSignals(anyList())).thenAnswer(invocation -> {
            List<EnrichedCandle> context = invocation.getArgument(0);
            long timestamp = context.getLast().timestamp();
            if (timestamp == successfulSellTimestamp) {
                return List.of(signal(
                        CandlePattern.BEARISH_HARAMI,
                        TradeSignal.SELL,
                        timestamp,
                        100.0
                ));
            }
            if (timestamp == successfulTimestamp) {
                return List.of(signal(
                        CandlePattern.BULLISH_ENGULFING,
                        TradeSignal.BUY,
                        timestamp,
                        100.0
                ));
            }
            if (timestamp == pendingTimestamp) {
                return List.of(signal(
                        CandlePattern.BEARISH_HARAMI,
                        TradeSignal.SELL,
                        timestamp,
                        candles.get(77).getClosePrice()
                ));
            }
            return List.of();
        });
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol(symbol);
        asset.setCompanyName("Apple Inc.");
        when(stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)).thenReturn(Optional.of(asset));
        HistoricalCandlestickService service = new HistoricalCandlestickService(
                candleRepository,
                stockAssetRepository,
                marketDataService,
                enrichmentService,
                detectionService,
                completionService,
                "Europe/Brussels"
        );

        HistoricalCandlestickService.HistoricalScan first = service.scan(symbol, "1d", 60);
        HistoricalCandlestickService.HistoricalScan second = service.scan(symbol, "1d", 60);

        assertThat(first.lookbackCandles()).isEqualTo(60);
        assertThat(first.evaluationHorizonCandles()).isEqualTo(10);
        assertThat(first.successThresholdPercent()).isEqualTo(3.0);
        assertThat(first.signals()).hasSize(3);
        assertThat(first.signals()).filteredOn(signal -> signal.signalTimestamp() == successfulSellTimestamp)
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.status())
                            .isEqualTo(HistoricalCandlestickService.HistoricalOutcome.SUCCESS);
                    assertThat(signal.directionalReturnPercent()).isEqualTo(5.0);
                    assertThat(signal.impactLabel()).isEqualTo("Potential loss avoided");
                });
        assertThat(first.signals()).filteredOn(signal -> signal.signalTimestamp() == successfulTimestamp)
                .singleElement()
                .satisfies(signal -> {
            assertThat(signal.status())
                    .isEqualTo(HistoricalCandlestickService.HistoricalOutcome.SUCCESS);
            assertThat(signal.directionalReturnPercent()).isEqualTo(5.0);
            assertThat(signal.impactLabel()).isEqualTo("Potential gain");
        });
        assertThat(first.signals()).filteredOn(signal -> signal.signalTimestamp() == pendingTimestamp)
                .singleElement()
                .satisfies(signal -> {
            assertThat(signal.status())
                    .isEqualTo(HistoricalCandlestickService.HistoricalOutcome.PENDING);
            assertThat(signal.directionalReturnPercent()).isNull();
        });
        assertThat(second.signals()).hasSize(3);
        verify(marketDataService, times(2)).syncCandles(symbol, "1d", null);
        verify(candleRepository, times(2)).findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, "1d", PageRequest.of(0, 268));
    }

    @Test
    void usesTheUserSelectedLookbackForEverySupportedInterval() {
        assertThat(scanProfile("1wk", 72)).satisfies(scan -> {
            assertThat(scan.lookbackCandles()).isEqualTo(72);
            assertThat(scan.lookbackLabel()).isEqualTo("last 72 completed weekly candles");
            assertThat(scan.evaluationHorizonCandles()).isEqualTo(4);
            assertThat(scan.successThresholdPercent()).isEqualTo(4.0);
        });
        assertThat(scanProfile("1mo", 144)).satisfies(scan -> {
            assertThat(scan.lookbackCandles()).isEqualTo(144);
            assertThat(scan.lookbackLabel()).isEqualTo("last 144 completed monthly candles");
            assertThat(scan.evaluationHorizonCandles()).isEqualTo(3);
            assertThat(scan.successThresholdPercent()).isEqualTo(6.0);
        });
    }

    @Test
    void rejectsLookbacksOutsideTheSupportedProviderWindowBeforeRefreshingData() {
        CandleRepository candleRepository = mock(CandleRepository.class);
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        HistoricalCandlestickService service = new HistoricalCandlestickService(
                candleRepository,
                stockAssetRepository,
                marketDataService,
                mock(TechnicalIndicatorEnrichmentService.class),
                mock(CandlePatternDetectionService.class),
                mock(CandleCompletionService.class),
                "Europe/Brussels"
        );

        assertThatThrownBy(() -> service.scan("AAPL", "1mo", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Candle lookback must be between 1 and 750.");
        assertThatThrownBy(() -> service.scan("AAPL", "1mo", 751))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Candle lookback must be between 1 and 750.");
        verifyNoInteractions(marketDataService, candleRepository);
    }

    private HistoricalCandlestickService.HistoricalScan scanProfile(String interval, int lookbackCandles) {
        CandleRepository candleRepository = mock(CandleRepository.class);
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        TechnicalIndicatorEnrichmentService enrichmentService = mock(TechnicalIndicatorEnrichmentService.class);
        CandleCompletionService completionService = mock(CandleCompletionService.class);
        when(marketDataService.syncCandles("AAPL", interval, null))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE, 0, null));
        HistoricalCandlestickService service = new HistoricalCandlestickService(
                candleRepository,
                stockAssetRepository,
                marketDataService,
                enrichmentService,
                mock(CandlePatternDetectionService.class),
                completionService,
                "Europe/Brussels"
        );
        return service.scan("AAPL", interval, lookbackCandles);
    }

    private DetectedSignal signal(CandlePattern pattern,
                                  TradeSignal direction,
                                  long timestamp,
                                  double close) {
        return new DetectedSignal(
                pattern,
                direction,
                SignalStength.MEDIUM_CONFIDENCE,
                78,
                List.of(
                        "Pattern quality +22/25: all mandatory pattern rules passed; established trend context",
                        "Momentum +10/15: RSI(14) was 32.0 (+5/5); RSI change was aligned with the signal"
                ),
                timestamp,
                close
        );
    }

    private List<Candle> candles(String symbol, String interval, int count) {
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double close = 95.0 + index * 0.1;
            candles.add(new Candle(
                    symbol,
                    interval,
                    (index + 1L) * 86_400L,
                    close - 0.5,
                    close + 1.0,
                    close - 1.0,
                    close,
                    1_000L
            ));
        }
        return candles;
    }

    private EnrichedCandle enriched(Candle candle) {
        return new EnrichedCandle(
                candle.getTimestamp(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                candle.getVolume(),
                1_000.0,
                50.0,
                candle.getClosePrice(),
                candle.getClosePrice(),
                candle.getClosePrice(),
                0.0,
                0.0,
                0.0,
                0.0,
                candle.getClosePrice(),
                candle.getClosePrice() - 2.0,
                candle.getClosePrice() + 2.0,
                1.0,
                candle.getClosePrice()
        );
    }
}
