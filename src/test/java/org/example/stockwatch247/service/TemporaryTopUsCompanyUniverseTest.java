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
import org.example.stockwatch247.security.SecurityInputValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporaryTopUsCompanyUniverseTest {

    @Test
    void fixedSnapshotContainsExactlyTwoHundredUniqueValidSymbols() {
        assertThat(TemporaryTopUsCompanyUniverse.SYMBOLS)
                .hasSize(200)
                .doesNotHaveDuplicates()
                .allSatisfy(symbol ->
                        assertThat(SecurityInputValidator.requireMarketSymbol(symbol)).isEqualTo(symbol));
    }

    @Test
    void bulkFollowCreatesEveryMissingRuleWithoutDuplicatingAnExistingRule() {
        AlertRuleRepository rules = mock(AlertRuleRepository.class);
        StockAssetRepository assets = mock(StockAssetRepository.class);
        TwelveDataService twelveData = mock(TwelveDataService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        User user = new User();
        user.setId(7L);
        user.setEmail("bulk-test@example.com");
        AtomicLong ids = new AtomicLong(1);
        when(assets.findByTickerSymbolIgnoreCase(anyString())).thenAnswer(invocation -> {
            StockAsset asset = new StockAsset();
            asset.setId(ids.getAndIncrement());
            asset.setTickerSymbol(invocation.getArgument(0));
            asset.setCompanyName(invocation.getArgument(0));
            asset.setExchange("US");
            return Optional.of(asset);
        });
        when(rules.existsByStockAssetAndIsActiveTrue(any(StockAsset.class))).thenReturn(false);
        when(rules.countDistinctActiveStocks()).thenReturn(0L);
        when(rules.findByUserAndStockAssetIn(eq(user), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<StockAsset> selectedAssets = invocation.getArgument(1);
            AlertRule alreadyActive = new AlertRule();
            alreadyActive.setUser(user);
            alreadyActive.setStockAsset(selectedAssets.getFirst());
            alreadyActive.setInterval(TimeInterval.DAILY);
            alreadyActive.setTradeSignal(TradeSignal.BUY);
            alreadyActive.setPatternFamily(AlertPatternFamily.CANDLESTICK);
            alreadyActive.setActive(true);
            return List.of(alreadyActive);
        });
        when(rules.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        AlertRuleService service = new AlertRuleService(
                rules,
                mock(AlertEventRepository.class),
                assets,
                mock(CandleRepository.class),
                twelveData,
                jdbc,
                mock(MarketDataService.class),
                mock(CandleCompletionService.class),
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                new ElliottWaveDetectionService(),
                true,
                true,
                50,
                500);

        AlertRuleService.TemporaryBulkFollowResult result =
                service.followTemporaryTopUsCompanies(user);

        assertThat(result.companies()).isEqualTo(200);
        assertThat(result.rulesPerCompany()).isEqualTo(18);
        assertThat(result.createdRules()).isEqualTo(3_599);
        assertThat(result.alreadyActiveRules()).isEqualTo(1);
        assertThat(result.activeRules()).isEqualTo(3_600);
        ArgumentCaptor<List<AlertRule>> saved = ArgumentCaptor.forClass(List.class);
        verify(rules).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(3_599);
        assertThat(saved.getValue()).allSatisfy(rule -> {
            assertThat(rule.getUser()).isSameAs(user);
            assertThat(rule.isActive()).isTrue();
            assertThat(rule.getPatternFamily()).isNotNull();
        });
        verify(twelveData, org.mockito.Mockito.never())
                .upsertStockAsset(anyString(), anyString(), anyString(), anyString());
    }
}
