package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.verify;
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
                pricingService, outlookService, tools.jackson.databind.json.JsonMapper.builder().build());
        clock.at(1_770_000_060L);
        service.setClock(clock);
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
        assertThat(view.sideLabel()).isEqualTo("Demo buy");
        assertThat(view.entryPrice()).isEqualByComparingTo("100.00000000");
        assertThat(view.quantity()).isEqualByComparingTo("25.00000000");
        assertThat(view.notionalValue()).isEqualByComparingTo("2500.00000000");
        assertThat(view.evaluatedPositionValue()).isEqualByComparingTo("2500.00000000");
        assertThat(stored.getEntryQuoteSource()).isEqualTo("Test quote");
        assertThat(stored.getEntrySnapshot()).contains("TECHNICAL_OUTLOOK_V1", "Moderate buy outlook", "RSI");
        assertThat(stored.getStatus().name()).isEqualTo("TRACKING");

        when(tradeRepository.findAllForUserAndStockAsset(user, asset)).thenReturn(List.of(stored));
        when(candleRepository.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc("AAPL", "1d"))
                .thenReturn(List.of(new Candle("AAPL", "1d", 1_771_000_000L,
                        81, 82, 79, 80.0, 1_000_000L)));
        VirtualTradeService.TradeView repriced = service.companyTrades(user, "AAPL").getFirst();
        assertThat(repriced.notionalValue()).isEqualByComparingTo("2500.00000000");
        assertThat(repriced.evaluatedPositionValue()).isEqualByComparingTo("2000.00000000");
    }

    @Test
    void storesAnExitPlanOnTheCorrectSideOfTheEntryAndRejectsOneOnTheWrongSide() {
        when(pricingService.getLatestPrice("AAPL")).thenReturn(quote(100.0, 1_770_000_000L));

        service.create(user, "AAPL", new VirtualTradeService.CreateCommand("BUY", "1d", null, null,
                "5d0c1e39-2f4e-4b1a-9d41-7a2b3c4d5e6f", new BigDecimal("95"), new BigDecimal("110"), null));
        ArgumentCaptor<VirtualTrade> captor = ArgumentCaptor.forClass(VirtualTrade.class);
        verify(tradeRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStopPrice()).isEqualByComparingTo("95");
        assertThat(captor.getValue().getTargetPrice()).isEqualByComparingTo("110");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user, "AAPL",
                        new VirtualTradeService.CreateCommand("BUY", "1d", null, null,
                                "6e1d2f4a-3a5f-4c2b-8e52-8b3c4d5e6f70", new BigDecimal("101"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("For a demo buy, the stop loss must be below the current price of 100.");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user, "AAPL",
                        new VirtualTradeService.CreateCommand("SELL", "1d", null, null,
                                "7f2e3a5b-4b6a-4d3c-9f63-9c4d5e6f7081", null, new BigDecimal("105"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("For a demo sell, the target must be below the current price of 100.");
    }

    @Test
    void planExitsFillAtTheLevelOrAtAGappedOpenAndPreferTheStopWhenOneCandleReachesBoth() {
        var buy = org.example.stockwatch247.model.enums.VirtualTradeSide.BUY;
        var sell = org.example.stockwatch247.model.enums.VirtualTradeSide.SELL;
        BigDecimal stop = new BigDecimal("95"), target = new BigDecimal("110");
        assertThat(VirtualTradeService.planExit(buy, stop, target, candle(100, 104, 96, 103))).isNull();
        assertThat(VirtualTradeService.planExit(buy, stop, target, candle(100, 101, 94, 96)))
                .isEqualTo(new VirtualTradeService.PlanExit("STOP", stop));
        assertThat(VirtualTradeService.planExit(buy, stop, target, candle(92, 93, 90, 91)).price()).isEqualByComparingTo("92");
        assertThat(VirtualTradeService.planExit(buy, stop, target, candle(100, 111, 99, 109)))
                .isEqualTo(new VirtualTradeService.PlanExit("TARGET", target));
        assertThat(VirtualTradeService.planExit(buy, stop, target, candle(112, 115, 111, 114)).price()).isEqualByComparingTo("112");
        assertThat(VirtualTradeService.planExit(buy, stop, target, candle(100, 112, 93, 100)).reason()).isEqualTo("STOP");
        assertThat(VirtualTradeService.planExit(buy, null, target, candle(100, 101, 50, 60))).isNull();
        // A sell is stopped by a rise and reaches its target by a fall.
        assertThat(VirtualTradeService.planExit(sell, new BigDecimal("105"), new BigDecimal("90"), candle(100, 106, 99, 104)).reason())
                .isEqualTo("STOP");
        assertThat(VirtualTradeService.planExit(sell, new BigDecimal("105"), new BigDecimal("90"), candle(100, 101, 89, 92)).reason())
                .isEqualTo("TARGET");
    }

    private static Candle candle(double open, double high, double low, double close) {
        return new Candle("AAPL", "1d", 1_771_000_000L, open, high, low, close, 1_000L);
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
        assertThat(view.notionalValue()).isEqualByComparingTo("100.00000000");
        assertThat(view.evaluatedPositionValue()).isEqualByComparingTo("80.00000000");
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
        clock.at(1_771_000_060L);

        VirtualTradeService.TradeView closed = service.close(user, stored.getId());

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.resultPercent()).isEqualTo(-10.0);
        assertThat(closed.outcomeLabel()).isEqualTo("Missed gain");
        assertThat(stored.getExitPrice()).isEqualByComparingTo("110.00000000");
        assertThat(stored.getExitSnapshot()).contains("TECHNICAL_OUTLOOK_V1");
    }

    @Test
    void deletingTradeSoftDeletesOnlyAnOwnedVisibleTrade() {
        VirtualTrade trade = new VirtualTrade();
        trade.setId(123L);
        trade.setUpdatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        when(tradeRepository.findOwnedById(123L, user)).thenReturn(Optional.of(trade));

        service.delete(user, 123L);

        assertThat(trade.getDeletedAt()).isNotNull();
        assertThat(trade.getUpdatedAt()).isEqualTo(trade.getDeletedAt());
        verify(tradeRepository).saveAndFlush(trade);
    }

    @Test
    void aQuoteOlderThanTwentyMinutesWhileTheMarketTradesIsRefused() {
        when(pricingService.getLatestPrice("AAPL")).thenReturn(quote(100.0, 1_770_000_060L - 21 * 60));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user, "AAPL",
                        new VirtualTradeService.CreateCommand("BUY", "1d", BigDecimal.ONE, null,
                                "4b0f1e6a-8a49-4a07-9d0a-3c1f0e9d2b11")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("out of date");
    }

    @Test
    void whileTheMarketIsClosedTheSessionCloseIsUsedAndLabelledAsSuch() {
        when(pricingService.getLatestPrice("AAPL")).thenReturn(Map.of("price", 100.0,
                "timestamp", 1_769_900_000L, "source", "Twelve Data", "marketOpen", false));

        service.create(user, "AAPL", new VirtualTradeService.CreateCommand("BUY", "1d", BigDecimal.ONE, null,
                "0d6d5c0e-3f7a-4c84-8f3c-5f1c9a1e2d33"));

        ArgumentCaptor<VirtualTrade> captor = ArgumentCaptor.forClass(VirtualTrade.class);
        org.mockito.Mockito.verify(tradeRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEntryQuoteSource()).isEqualTo("Last close (market closed)");
    }

    @Test
    void aStoredCloseFromBeforeTheLatestFinishedSessionsIsRefused() {
        var completion = mock(CandleCompletionService.class);
        when(completion.firstIncompleteCandleTimestamp(org.example.stockwatch247.model.enums.TimeInterval.DAILY))
                .thenReturn(1_770_000_000L);
        service.setCompletion(completion);
        when(pricingService.getLatestPrice("AAPL")).thenReturn(Map.of("price", 100.0,
                "timestamp", 1_770_000_000L - 10 * 86_400L, "source", "Stored candle"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user, "AAPL",
                        new VirtualTradeService.CreateCommand("BUY", "1d", BigDecimal.ONE, null,
                                "9c3b2a1d-6e5f-4a7b-8c9d-0e1f2a3b4c5d")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("outdated");
    }

    private final MutableClock clock = new MutableClock();

    private static final class MutableClock extends java.time.Clock {
        private Instant now = Instant.EPOCH;
        void at(long epochSecond) { now = Instant.ofEpochSecond(epochSecond); }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
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
