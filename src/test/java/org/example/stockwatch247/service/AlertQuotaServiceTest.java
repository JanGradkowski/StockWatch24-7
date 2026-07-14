package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertQuotaServiceTest {

    @Test
    void oneUserCannotConsumeAnotherUsersQuota() {
        Fixture fixture = fixture();
        when(fixture.rules.countDistinctActiveStocksByUser(fixture.user)).thenReturn(50L);

        assertThatThrownBy(() -> fixture.service.setAlert(
                fixture.user, "AAPL", TimeInterval.DAILY, TradeSignal.BUY,
                AlertPatternFamily.CANDLESTICK, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Per-user");

        verify(fixture.rules, never()).save(any());
    }

    @Test
    void globallySharedSymbolDoesNotConsumeAdditionalGlobalCapacity() {
        Fixture fixture = fixture();
        when(fixture.rules.countDistinctActiveStocksByUser(fixture.user)).thenReturn(2L);
        when(fixture.rules.existsByStockAssetAndIsActiveTrue(fixture.asset)).thenReturn(true);
        when(fixture.rules.save(any(AlertRule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AlertRule saved = fixture.service.setAlert(
                fixture.user, "AAPL", TimeInterval.DAILY, TradeSignal.BUY,
                AlertPatternFamily.CANDLESTICK, true);

        assertThat(saved.getUser()).isSameAs(fixture.user);
        verify(fixture.rules, never()).countDistinctActiveStocks();
    }

    @Test
    void globalProviderCapacityStillProtectsTheSystem() {
        Fixture fixture = fixture();
        when(fixture.rules.countDistinctActiveStocksByUser(fixture.user)).thenReturn(2L);
        when(fixture.rules.existsByStockAssetAndIsActiveTrue(fixture.asset)).thenReturn(false);
        when(fixture.rules.countDistinctActiveStocks()).thenReturn(500L);

        assertThatThrownBy(() -> fixture.service.setAlert(
                fixture.user, "AAPL", TimeInterval.DAILY, TradeSignal.BUY,
                AlertPatternFamily.CANDLESTICK, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Global");
    }

    private Fixture fixture() {
        AlertRuleRepository rules = mock(AlertRuleRepository.class);
        StockAssetRepository assets = mock(StockAssetRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        User user = new User();
        user.setId(1L);
        user.setEmail("one@example.com");
        StockAsset asset = new StockAsset();
        asset.setId(10L);
        asset.setTickerSymbol("AAPL");
        asset.setCompanyName("Apple Inc.");
        asset.setExchange("NASDAQ");
        when(assets.findByTickerSymbolIgnoreCase("AAPL")).thenReturn(Optional.of(asset));
        when(rules.existsByUserAndStockAssetAndIsActiveTrue(user, asset)).thenReturn(false);
        when(rules.findByUserAndStockAssetAndIntervalAndTradeSignalAndPatternFamily(
                user, asset, TimeInterval.DAILY, TradeSignal.BUY, AlertPatternFamily.CANDLESTICK))
                .thenReturn(Optional.empty());
        AlertRuleService service = new AlertRuleService(
                rules,
                mock(AlertEventRepository.class),
                assets,
                mock(CandleRepository.class),
                mock(TwelveDataService.class),
                jdbc,
                mock(MarketDataService.class),
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                new ElliottWaveDetectionService(),
                true,
                true,
                50,
                500);
        return new Fixture(service, rules, user, asset);
    }

    private record Fixture(AlertRuleService service,
                           AlertRuleRepository rules,
                           User user,
                           StockAsset asset) {
    }
}
