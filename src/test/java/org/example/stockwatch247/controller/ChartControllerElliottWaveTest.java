package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.LivePricingService;
import org.example.stockwatch247.service.MarketDataService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.example.stockwatch247.service.TwelveDataService;
import org.example.stockwatch247.service.YahooFinanceService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChartControllerElliottWaveTest {

    @Test
    void exposesCursorPaginationMetadataWithoutPersistenceIds() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        Candle candle = new Candle("MSFT", "1d", 900L, 100, 103, 99, 102.0, 1_000L);
        when(marketDataService.loadCandlePage("MSFT", "1d", 1_000L, 500))
                .thenReturn(new MarketDataService.CandlePage(
                        List.of(candle), 900L, true, MarketDataService.CandleSource.TWELVE_DATA, null));
        ChartController controller = new ChartController(
                mock(CandleRepository.class), mock(LivePricingService.class), marketDataService,
                mock(StockAssetRepository.class), mock(TwelveDataService.class), mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(), new ElliottWaveDetectionService());

        ChartController.CandlePageResponse page =
                controller.getHistoricalCandles("msft", "1d", 1_000L, 500);

        assertThat(page.nextCursor()).isEqualTo(900L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.source()).isEqualTo("TWELVE_DATA");
        assertThat(page.candles()).singleElement().satisfies(response -> {
            assertThat(response.symbol()).isEqualTo("MSFT");
            assertThat(response.timestamp()).isEqualTo(900L);
        });
        verify(marketDataService).loadCandlePage("MSFT", "1d", 1_000L, 500);
    }

    @Test
    void returnsUppercaseMonthlyAndLowercaseWeeklyWaveLabels() {
        String symbol = "SAP.DE";
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        ChartController controller = new ChartController(
                candleRepository,
                mock(LivePricingService.class),
                marketDataService,
                mock(StockAssetRepository.class),
                mock(TwelveDataService.class),
                mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(),
                new ElliottWaveDetectionService());

        when(marketDataService.syncCandles(symbol, "1mo", null))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(marketDataService.syncCandles(symbol, "1wk", null))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1mo"))
                .thenReturn(candles(symbol, "1mo").reversed());
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1wk"))
                .thenReturn(candles(symbol, "1wk").reversed());

        ChartController.ElliottWaveOverlay monthly = controller.getElliottWaves(symbol, "1mo");
        ChartController.ElliottWaveOverlay weekly = controller.getElliottWaves(symbol, "1wk");

        assertThat(monthly.labelStyle()).isEqualTo("UPPERCASE");
        assertThat(monthly.points()).extracting(ChartController.ElliottWavePointView::label)
                .containsExactly("", "I", "II", "III", "IV", "V", "A", "B", "C");
        assertThat(monthly.confirmationTimestamp()).isEqualTo(87L * 86_400L);
        assertThat(monthly.qualityScore()).isGreaterThanOrEqualTo(68);
        assertThat(weekly.labelStyle()).isEqualTo("LOWERCASE");
        assertThat(weekly.points()).extracting(ChartController.ElliottWavePointView::label)
                .containsExactly("", "i", "ii", "iii", "iv", "v", "a", "b", "c");
    }

    @Test
    void returnsFiveWaveOverlayBeforeAbcExists() {
        String symbol = "SAP.DE";
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        ChartController controller = new ChartController(
                candleRepository, mock(LivePricingService.class), marketDataService,
                mock(StockAssetRepository.class), mock(TwelveDataService.class), mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(), new ElliottWaveDetectionService());
        when(marketDataService.syncCandles(symbol, "1mo", null))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1mo"))
                .thenReturn(waveVCandles(symbol, "1mo").reversed());

        ChartController.ElliottWaveOverlay overlay = controller.getElliottWaves(symbol, "1mo");

        assertThat(overlay.correctionComplete()).isFalse();
        assertThat(overlay.points()).extracting(ChartController.ElliottWavePointView::label)
                .containsExactly("", "I", "II", "III", "IV", "V");
    }

    @Test
    void exposesDeepWaveTwoWarningMetadataForTheChart() {
        String symbol = "MSFT";
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        ChartController controller = new ChartController(
                candleRepository, mock(LivePricingService.class), marketDataService,
                mock(StockAssetRepository.class), mock(TwelveDataService.class), mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(), new ElliottWaveDetectionService());
        when(marketDataService.syncCandles(symbol, "1wk", null))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1wk"))
                .thenReturn(deepWaveTwoCandles(symbol, "1wk").reversed());

        ChartController.ElliottWaveOverlay overlay = controller.getElliottWaves(symbol, "1wk");

        assertThat(overlay.direction()).isEqualTo("BEARISH");
        assertThat(overlay.deepWaveTwo()).isTrue();
        assertThat(overlay.waveTwoRetracement()).isBetween(0.95, 0.99);
        assertThat(overlay.qualityScore()).isGreaterThanOrEqualTo(68);
        assertThat(overlay.impulseVariant())
                .isEqualTo(ElliottWaveDetectionService.ImpulseVariant.STANDARD);
        assertThat(overlay.qualityWarnings())
                .anyMatch(warning -> warning.contains("Deep Wave II"));
        assertThat(overlay.points()).extracting(ChartController.ElliottWavePointView::label)
                .containsExactly("", "i", "ii", "iii", "iv", "v");
    }

    @Test
    void returnsDeepCandlesAndHistoricStructuresForSelectedIntervalButRejectsDaily() {
        String symbol = "SAP.DE";
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        ChartController controller = new ChartController(
                candleRepository, mock(LivePricingService.class), marketDataService,
                mock(StockAssetRepository.class), mock(TwelveDataService.class), mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(), new ElliottWaveDetectionService());
        List<Candle> monthlyCandles = candles(symbol, "1mo");
        when(marketDataService.syncCandles(symbol, "1mo", null))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, "1mo"))
                .thenReturn(monthlyCandles);

        ChartController.ElliottWaveHistoryOverlay history =
                controller.getHistoricalElliottWaves(symbol, "1mo", null);

        assertThat(history.interval()).isEqualTo("1mo");
        assertThat(history.fromTimestamp()).isNull();
        assertThat(history.structures()).isNotEmpty();
        assertThat(history.structures()).anySatisfy(structure ->
                assertThat(structure.points().getLast().label()).isEqualTo("C"));
        assertThat(history.structures()).allSatisfy(structure -> {
            assertThat(structure.structureId()).isNotBlank();
            assertThat(structure.confirmationTimestamp()).isNotNull();
            assertThat(structure.qualityScore()).isGreaterThanOrEqualTo(68);
        });
        assertThatThrownBy(() -> controller.getHistoricalElliottWaves(symbol, "1d", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historicalElliottAnalysisStartsAtTheLoadedChartCursor() {
        String symbol = "MSFT";
        long from = 86_400L;
        CandleRepository candleRepository = mock(CandleRepository.class);
        List<Candle> loadedCandles = candles(symbol, "1wk");
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                symbol, "1wk", from)).thenReturn(loadedCandles);
        ChartController controller = new ChartController(
                candleRepository, mock(LivePricingService.class), mock(MarketDataService.class),
                mock(StockAssetRepository.class), mock(TwelveDataService.class), mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(), new ElliottWaveDetectionService());

        ChartController.ElliottWaveHistoryOverlay history =
                controller.getHistoricalElliottWaves(symbol, "1wk", from);

        assertThat(history.fromTimestamp()).isEqualTo(from);
        assertThat(history.structures()).isNotEmpty();
        verify(candleRepository).findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                symbol, "1wk", from);
    }

    @Test
    void indexAliasUsesTheExistingCandleAndElliottPipeline() {
        String canonicalSymbol = "^GSPC";
        CandleRepository candleRepository = mock(CandleRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        ChartController controller = new ChartController(
                candleRepository, mock(LivePricingService.class), marketDataService,
                mock(StockAssetRepository.class), mock(TwelveDataService.class), mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(), new ElliottWaveDetectionService());
        when(marketDataService.syncCandles(canonicalSymbol, "1mo", null))
                .thenReturn(new MarketDataService.CandleSyncResult(MarketDataService.CandleSource.CACHE, 0, null));
        when(candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(canonicalSymbol, "1mo"))
                .thenReturn(candles(canonicalSymbol, "1mo").reversed());

        ChartController.ElliottWaveOverlay overlay = controller.getElliottWaves("SPX", "1mo");

        assertThat(overlay.points()).extracting(ChartController.ElliottWavePointView::label)
                .containsExactly("", "I", "II", "III", "IV", "V", "A", "B", "C");
        verify(marketDataService).syncCandles(canonicalSymbol, "1mo", null);
    }

    @Test
    void indexMetadataIsExposedToTheChartUi() {
        TwelveDataService twelveDataService = mock(TwelveDataService.class);
        StockAsset index = new StockAsset();
        index.setTickerSymbol("^GSPC");
        index.setCompanyName("S&P 500");
        index.setExchange("SNP");
        index.setCurrency("USD");
        index.setInstrumentType(InstrumentType.INDEX);
        when(twelveDataService.refreshStockAssetMetadata("^GSPC")).thenReturn(index);
        ChartController controller = new ChartController(
                mock(CandleRepository.class), mock(LivePricingService.class), mock(MarketDataService.class),
                mock(StockAssetRepository.class), twelveDataService, mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(), new ElliottWaveDetectionService());

        Map<String, String> metadata = controller.getStockMetadata("SPX");

        assertThat(metadata).containsEntry("symbol", "^GSPC")
                .containsEntry("name", "S&P 500")
                .containsEntry("instrumentType", "INDEX");
    }

    private List<Candle> candles(String symbol, String interval) {
        List<Anchor> anchors = List.of(
                new Anchor(1, 112.0), new Anchor(6, 100.0), new Anchor(14, 121.0),
                new Anchor(24, 110.0), new Anchor(38, 143.0), new Anchor(54, 126.0),
                new Anchor(68, 150.0), new Anchor(74, 134.0), new Anchor(80, 144.0),
                new Anchor(86, 130.0), new Anchor(87, 133.0)
        ).stream().sorted(Comparator.comparingInt(Anchor::index)).toList();
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

    private List<Candle> waveVCandles(String symbol, String interval) {
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

    private List<Candle> deepWaveTwoCandles(String symbol, String interval) {
        List<Anchor> anchors = List.of(
                new Anchor(1, 520.0), new Anchor(6, 555.45), new Anchor(14, 492.37),
                new Anchor(24, 553.72), new Anchor(38, 356.28), new Anchor(54, 466.32),
                new Anchor(68, 349.20), new Anchor(69, 390.49)
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

    private record Anchor(int index, double price) { }
}
