package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TechnicalOutlookServiceTest {
    private CandleRepository candleRepository;
    private StockAssetRepository stockAssetRepository;
    private TechnicalOutlookService service;
    private User user;

    @BeforeEach
    void setUp() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        candleRepository = mock(CandleRepository.class);
        stockAssetRepository = mock(StockAssetRepository.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        CongressionalTradeDeliveryRepository congressionalRepository =
                mock(CongressionalTradeDeliveryRepository.class);
        InsiderTradeDeliveryRepository insiderRepository = mock(InsiderTradeDeliveryRepository.class);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(alertEventRepository.findAllByAlertRule_User(any())).thenReturn(List.of());
        when(congressionalRepository.findAllForUser(any())).thenReturn(List.of());
        when(insiderRepository.findAllForUser(any())).thenReturn(List.of());
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(anyString(), anyString()))
                .thenReturn(List.of());

        service = new TechnicalOutlookService(
                marketDataService,
                candleRepository,
                stockAssetRepository,
                new TechnicalIndicatorEnrichmentService(),
                alertEventRepository,
                congressionalRepository,
                insiderRepository);
        user = new User();
    }

    @Test
    void buildsAuditableRawAndCategoryBalancedScoresFromCompletedCandles() {
        List<Candle> candles = risingDailyCandles(260);
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc("AAPL", "1d"))
                .thenReturn(candles);

        TechnicalOutlookService.OutlookView outlook = service.getOutlook(user, "aapl", "1d");

        assertThat(outlook.available()).isTrue();
        assertThat(outlook.intervalLabel()).isEqualTo("Daily");
        assertThat(outlook.candles()).hasSize(260);
        assertThat(outlook.indicators()).extracting(TechnicalOutlookService.IndicatorView::key)
                .contains("rsi", "ema", "sma", "macd", "cci", "bollinger", "atr", "vwap",
                        "relativeVolume", "volumeProfile", "supportResistance");
        assertThat(outlook.rawScore().denominator()).isEqualTo(11);
        assertThat(outlook.categories()).extracting(TechnicalOutlookService.CategoryView::key)
                .containsExactly("TREND", "MOMENTUM", "VOLATILITY", "VOLUME", "PRICE_LOCATION");
        assertThat(outlook.headlineScore().denominator()).isEqualTo(5);
        assertThat(outlook.indicators().stream().filter(indicator -> indicator.key().equals("atr")).findFirst())
                .hasValueSatisfying(atr -> {
                    assertThat(atr.vote()).isZero();
                    assertThat(atr.rule()).contains("always neutral");
                });
        assertThat(outlook.methodology().thresholds()).contains("10%", "30%", "60%");
        assertThat(outlook.methodology().disclaimer()).contains("not probabilities");
    }

    @Test
    void returnsAnExplicitUnavailableViewInsteadOfCountingMissingInputsAsNeutral() {
        TechnicalOutlookService.OutlookView outlook = service.getOutlook(user, "EMPTY", "weekly");

        assertThat(outlook.available()).isFalse();
        assertThat(outlook.rawScore().denominator()).isZero();
        assertThat(outlook.headlineScore().denominator()).isZero();
        assertThat(outlook.indicators()).isEmpty();
    }

    private List<Candle> risingDailyCandles(int count) {
        Instant first = Instant.parse("2025-01-02T21:00:00Z");
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double close = 100 + index * 0.35 + Math.sin(index / 6.0);
            double open = close - 0.25;
            candles.add(new Candle(
                    "AAPL",
                    "1d",
                    first.plus(index, ChronoUnit.DAYS).getEpochSecond(),
                    open,
                    close + 1.1,
                    open - 1.0,
                    close,
                    1_000_000L + index * 1_000L));
        }
        return candles;
    }
}
