package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.VirtualTrade;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.VirtualTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VirtualTradeServiceTest {
    private VirtualTradeRepository tradeRepository;
    private CandleRepository candleRepository;
    private LivePricingService pricingService;
    private TechnicalOutlookService outlookService;
    private VirtualTradeService service;
    private User user;
    private StockAsset asset;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(VirtualTradeRepository.class);
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        candleRepository = mock(CandleRepository.class);
        pricingService = mock(LivePricingService.class);
        outlookService = mock(TechnicalOutlookService.class);
        user = new User();
        user.setId(7L);
        asset = new StockAsset();
        asset.setId(11L);
        asset.setTickerSymbol("AAPL");
        asset.setCompanyName("Apple Inc.");
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");
        asset.setInstrumentType(InstrumentType.EQUITY);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("AAPL")).thenReturn(Optional.of(asset));
        when(tradeRepository.findByUserAndClientRequestId(any(), any())).thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(100);
        when(tradeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            VirtualTrade trade = invocation.getArgument(0);
            if (trade.getId() == null) trade.setId(ids.getAndIncrement());
            return trade;
        });
        when(outlookService.getOutlook(any(), any(), any())).thenReturn(outlook("Moderate buy outlook", 3));
        service = new VirtualTradeService(tradeRepository, stockAssetRepository, candleRepository,
                pricingService, outlookService, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void capturesExactQuoteSizingAndImmutableEntryTechnicalSnapshot() {
        when(pricingService.getLatestPrice("AAPL")).thenReturn(quote(100.0, 1_770_000_000L));

        VirtualTradeService.TradeView view = service.create(user, "AAPL",
                new VirtualTradeService.CreateCommand("BUY", "1d", null,
                        new BigDecimal("2500"), "bfc830df-4d13-4c8e-9820-f75f0f963ec8"));

        ArgumentCaptor<VirtualTrade> captor = ArgumentCaptor.forClass(VirtualTrade.class);
        org.mockito.Mockito.verify(tradeRepository).saveAndFlush(captor.capture());
        VirtualTrade stored = captor.getValue();
        assertThat(view.sideLabel()).isEqualTo("Virtual Buy");
        assertThat(view.entryPrice()).isEqualByComparingTo("100.00000000");
        assertThat(view.quantity()).isEqualByComparingTo("25.00000000");
        assertThat(stored.getEntryQuoteSource()).isEqualTo("Test quote");
        assertThat(stored.getEntrySnapshot()).contains("TECHNICAL_OUTLOOK_V1", "Moderate buy outlook", "RSI");
        assertThat(stored.getStatus().name()).isEqualTo("TRACKING");
    }

    @Test
    void virtualSellReportsAvoidedLossWithoutPretendingToBeAShortSale() {
        when(pricingService.getLatestPrice("AAPL")).thenReturn(quote(100.0, 1_770_000_000L));
        service.create(user, "AAPL", new VirtualTradeService.CreateCommand("SELL", "1d",
                BigDecimal.ONE, null, "751f3f30-d381-4d30-a781-4ec2e2821205"));
        ArgumentCaptor<VirtualTrade> captor = ArgumentCaptor.forClass(VirtualTrade.class);
        org.mockito.Mockito.verify(tradeRepository).saveAndFlush(captor.capture());
        VirtualTrade stored = captor.getValue();
        when(tradeRepository.findAllForUserAndStockAsset(user, asset)).thenReturn(List.of(stored));
        when(candleRepository.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc("AAPL", "1d"))
                .thenReturn(List.of(new Candle("AAPL", "1d", 1_771_000_000L,
                        81, 82, 79, 80.0, 1_000_000L)));

        VirtualTradeService.TradeView view = service.companyTrades(user, "AAPL").getFirst();

        assertThat(view.resultPercent()).isEqualTo(20.0);
        assertThat(view.outcomeLabel()).isEqualTo("Avoided loss");
        assertThat(view.monetaryResult()).isPositive();
    }

    @Test
    void closingTradeFreezesQuoteAndTechnicalSnapshot() {
        when(pricingService.getLatestPrice("AAPL"))
                .thenReturn(quote(100.0, 1_770_000_000L), quote(110.0, 1_771_000_000L));
        service.create(user, "AAPL", new VirtualTradeService.CreateCommand("SELL", "1wk",
                null, null, "8e11fc0e-8388-4e9a-be3c-0a1210801bb3"));
        ArgumentCaptor<VirtualTrade> captor = ArgumentCaptor.forClass(VirtualTrade.class);
        org.mockito.Mockito.verify(tradeRepository).saveAndFlush(captor.capture());
        VirtualTrade stored = captor.getValue();
        when(tradeRepository.findOwnedById(stored.getId(), user)).thenReturn(Optional.of(stored));

        VirtualTradeService.TradeView closed = service.close(user, stored.getId());

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.resultPercent()).isEqualTo(-10.0);
        assertThat(closed.outcomeLabel()).isEqualTo("Missed upside");
        assertThat(stored.getExitPrice()).isEqualByComparingTo("110.00000000");
        assertThat(stored.getExitSnapshot()).contains("TECHNICAL_OUTLOOK_V1");
    }

    private Map<String, Object> quote(double price, long timestamp) {
        return Map.of("price", price, "timestamp", timestamp, "source", "Test quote");
    }

    private TechnicalOutlookService.OutlookView outlook(String classification, int net) {
        TechnicalOutlookService.ScoreView headline = new TechnicalOutlookService.ScoreView(
                5, 2, 1, net, 8, net / 8.0, classification);
        TechnicalOutlookService.ScoreView raw = new TechnicalOutlookService.ScoreView(
                8, 3, 2, 6, 13, 6.0 / 13.0, "Moderate buy outlook");
        TechnicalOutlookService.IndicatorView rsi = new TechnicalOutlookService.IndicatorView(
                "rsi", "RSI", "MOMENTUM", "index points", 27.4, 1, "BUY", true,
                "RSI is oversold.", "Buy at RSI ≤ 30.", 1_769_900_000L, 3,
                false, List.of(), List.of(30.0, 70.0));
        return new TechnicalOutlookService.OutlookView(
                "AAPL", "Apple Inc.", "1d", "Daily", true, 1_769_900_000L,
                "Current completed candle", headline, raw, List.of(), List.of(rsi), List.of(),
                List.of(), List.of(),
                new TechnicalOutlookService.MarketComparisonView(false, "^GSPC", "S&P 500",
                        "UNAVAILABLE", 0, 0, "UNAVAILABLE", List.of(), List.of()),
                new TechnicalOutlookService.MethodologyView("raw", "category", "thresholds", "descriptive"));
    }
}
