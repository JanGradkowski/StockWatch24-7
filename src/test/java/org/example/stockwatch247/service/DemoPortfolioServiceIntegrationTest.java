package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.*;
import org.example.stockwatch247.security.AccountSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@AutoConfigureMockMvc
@Transactional
class DemoPortfolioServiceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired CandleRepository candles;
    @Autowired VirtualTradeRepository trades;
    @Autowired MockMvc mvc;
    private DemoPortfolioService service;
    private User account;

    @BeforeEach void setup() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC);
        service = new DemoPortfolioService(jdbc, clock, new CandleCompletionService("UTC", clock));
        account = account();
    }

    @Test void depositsAreNotGainsAndClosedProceedsRemainCashWithSeparateCurrencies() {
        StockAsset stock = asset("USD");
        price(stock, "2026-08-11", 100); price(stock, "2026-09-04", 110);
        price(stock, "2026-09-10", 115); price(stock, "2026-09-11", 120);
        trade(account, stock, "2026-07-01T12:00:00Z", 100, 10, null, null);
        trade(account, stock, "2026-09-11T12:00:00Z", 100, 10, null, null);
        StockAsset closed = asset("USD");
        trade(account, closed, "2026-06-01T12:00:00Z", 100, 5, "2026-08-01T12:00:00Z", 110);
        StockAsset euro = asset("EUR");
        trade(account, euro, "2026-06-01T12:00:00Z", 10, 1, "2026-08-01T12:00:00Z", 20);
        var sell = trade(account, stock, "2026-07-01T12:00:00Z", 100, 999, null, null);
        sell.setSide(VirtualTradeSide.SELL); trades.saveAndFlush(sell);
        var unsized = trade(account, stock, "2026-07-01T12:00:00Z", 100, 1, null, null);
        unsized.setQuantity(null); unsized.setNotionalValue(null); trades.saveAndFlush(unsized);
        var deleted = trade(account, stock, "2026-07-01T12:00:00Z", 100, 999, null, null);
        deleted.markDeleted(Instant.parse("2026-09-01T00:00:00Z")); trades.saveAndFlush(deleted);
        trade(account(), stock, "2026-07-01T12:00:00Z", 100, 999, null, null);

        var portfolio = service.portfolio(account.getId(), "day");
        assertThat(portfolio.excludedTrades()).isEqualTo(2);
        assertThat(portfolio.currencies()).hasSize(2);
        var usd = portfolio.currencies().stream().filter(balance -> balance.currency().equals("USD")).findFirst().orElseThrow();
        assertThat(usd.value()).isEqualByComparingTo("2950");
        assertThat(usd.openValue()).isEqualByComparingTo("2400");
        assertThat(usd.cash()).isEqualByComparingTo("550");
        assertThat(usd.invested()).isEqualByComparingTo("2500");
        assertThat(period(usd, "overall").returnPercent()).isEqualByComparingTo("18");
        assertThat(period(usd, "day").gain()).isEqualByComparingTo("250");
        assertThat(period(usd, "day").returnPercent().doubleValue()).isCloseTo(250.0 / 2700 * 100, within(1e-10));
        assertThat(period(usd, "week").gain()).isEqualByComparingTo("300");
        assertThat(period(usd, "month").gain()).isEqualByComparingTo("400");
        assertThat(usd.selected().best()).extracting(DemoPortfolioService.StockView::symbol).containsExactly(stock.getTickerSymbol());
        assertThat(portfolio.currencies().getFirst().value()).isEqualByComparingTo("20");
    }

    @Test void missingDataIsUnavailableAndNewerEntriesUseTheirCapturedPrice() {
        StockAsset stock = asset("USD");
        price(stock, "2026-09-11", 120);
        var old = trade(account, stock, "2026-07-01T12:00:00Z", 100, 10, null, null);
        var balance = service.portfolio(account.getId(), "week").currencies().getFirst();
        assertThat(balance.value()).isEqualByComparingTo("1200");
        assertThat(balance.selected().returnPercent()).isNull();
        assertThat(balance.selected().best()).isEmpty();
        assertThat(balance.selected().unavailableStocks()).isEqualTo(1);
        old.markDeleted(Instant.now()); trades.saveAndFlush(old);
        trade(account, stock, "2026-09-12T12:00:00Z", 130, 2, null, null);
        balance = service.portfolio(account.getId(), "day").currencies().getFirst();
        assertThat(balance.value()).isEqualByComparingTo("260");
        assertThat(balance.entryValuations()).isEqualTo(1);
        assertThat(balance.selected().gain()).isEqualByComparingTo("0");
        trade(account, asset("USD"), "2026-07-01T12:00:00Z", 100, 1, null, null);
        balance = service.portfolio(account.getId(), "overall").currencies().getFirst();
        assertThat(balance.value()).isNull();
        assertThat(balance.selected().returnPercent()).isNull();
        assertThat(balance.missingPrices()).isEqualTo(1);
    }

    @Test void ranksDistinctStocksAndWeightsRepeatedBuysByCapital() {
        var symbols = new java.util.ArrayList<String>();
        for (int close : List.of(70, 80, 90, 110, 120, 130, 140)) {
            StockAsset stock = asset("USD"); symbols.add(stock.getTickerSymbol());
            price(stock, "2026-08-11", 100); price(stock, "2026-09-04", 100);
            price(stock, "2026-09-10", 100); price(stock, "2026-09-11", close);
            trade(account, stock, "2026-07-01T12:00:00Z", 100, 1, null, null);
        }
        StockAsset combined = asset("USD");
        price(combined, "2026-09-11", 180);
        trade(account, combined, "2026-07-01T12:00:00Z", 100, 1, null, null);
        trade(account, combined, "2026-07-02T12:00:00Z", 200, 1, null, null);
        var periods = service.portfolio(account.getId(), "overall").currencies().getFirst().periods();
        for (var period : periods) {
            assertThat(period.best()).hasSize(3);
            assertThat(period.worst()).extracting(DemoPortfolioService.StockView::symbol).containsExactlyElementsOf(symbols.subList(0, 3));
            assertThat(period.best().getFirst().symbol()).isEqualTo(symbols.getLast());
            assertThat(period.best().getFirst().returnPercent()).isEqualByComparingTo("40");
        }
        // 2 shares at 180 against 300 invested = 20%, not the 35% arithmetic mean of trade returns.
        var isolated = account();
        trade(isolated, combined, "2026-07-01T12:00:00Z", 100, 1, null, null);
        trade(isolated, combined, "2026-07-02T12:00:00Z", 200, 1, null, null);
        var result = service.portfolio(isolated.getId(), "overall").currencies().getFirst();
        assertThat(result.selected().best().getFirst().returnPercent()).isEqualByComparingTo("20");
        assertThat(result.selected().best()).hasSize(1);
    }

    @Test void renderedPortfolioCoversAllPagesAndRetainsSelectedPeriod() throws Exception {
        StockAsset stock = asset("USD");
        for (int i = 0; i < 51; i++) trade(account, stock, "2020-01-01T12:00:00Z", 100, 1, "2020-02-01T12:00:00Z", 110);
        var result = mvc.perform(get("/virtual-trades").with(user(account.getEmail()))
                        .sessionAttr(AccountSession.SECURITY_VERSION, account.getSecurityVersion())
                        .param("page", "1").param("period", "month"))
                .andExpect(status().isOk()).andExpect(view().name("virtual-trades"))
                .andExpect(content().string(containsString("Value and performance")))
                .andExpect(content().string(containsString("Last month stock performance")))
                .andExpect(content().string(containsString("Top 3 performers")))
                .andExpect(content().string(containsString("Bottom 3 performers")))
                .andExpect(content().string(containsString("#demoPortfolio")))
                .andReturn();
        var summary = (DemoPortfolioService.PortfolioView) result.getModelAndView().getModel().get("portfolio");
        assertThat(summary.selectedPeriod()).isEqualTo("month");
        assertThat(summary.currencies().getFirst().value()).isEqualByComparingTo("5610");
        assertThat(summary.currencies().getFirst().trades()).isEqualTo(51);
    }

    @Test void emptyAccountHasNoInventedReturnOrCurrency() throws Exception {
        assertThat(service.portfolio(account.getId(), "invalid").selectedPeriod()).isEqualTo("overall");
        assertThat(service.portfolio(account.getId(), "overall").currencies()).isEmpty();
        mvc.perform(get("/virtual-trades").with(user(account.getEmail()))
                        .sessionAttr(AccountSession.SECURITY_VERSION, account.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("No funded demo buys yet")));
    }

    private DemoPortfolioService.PeriodView period(DemoPortfolioService.CurrencyView balance, String key) {
        return balance.periods().stream().filter(period -> period.key().equals(key)).findFirst().orElseThrow();
    }
    private User account() {
        var user = new User(); user.setEmail("portfolio-" + UUID.randomUUID() + "@example.com");
        user.setFirstName("Portfolio"); user.setLastName("Test"); user.setVerified(true); user.setPasswordHash("test-only");
        return users.saveAndFlush(user);
    }
    private StockAsset asset(String currency) {
        var asset = new StockAsset(); asset.setTickerSymbol("P" + UUID.randomUUID().toString().substring(0, 8));
        asset.setCompanyName("Portfolio test company"); asset.setExchange("NASDAQ"); asset.setCurrency(currency);
        return assets.saveAndFlush(asset);
    }
    private void price(StockAsset asset, String date, double close) {
        candles.saveAndFlush(new Candle(asset.getTickerSymbol(), "1d", LocalDate.parse(date).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                close, close, close, close, 100L));
    }
    private VirtualTrade trade(User user, StockAsset asset, String entryAt, int entry, int quantity, String exitAt, Integer exit) {
        var trade = new VirtualTrade(); trade.setUser(user); trade.setStockAsset(asset); trade.setSide(VirtualTradeSide.BUY);
        trade.setStatus(exitAt == null ? VirtualTradeStatus.TRACKING : VirtualTradeStatus.CLOSED);
        trade.setAnalysisInterval(TimeInterval.DAILY); trade.setEntryAt(Instant.parse(entryAt));
        trade.setEntryPrice(BigDecimal.valueOf(entry)); trade.setEntryQuoteTimestamp(trade.getEntryAt().getEpochSecond());
        trade.setEntryCandleTimestamp(trade.getEntryAt().getEpochSecond()); trade.setEntryQuoteSource("Test quote");
        trade.setCurrency(asset.getCurrency()); trade.setQuantity(BigDecimal.valueOf(quantity));
        trade.setNotionalValue(BigDecimal.valueOf((long) entry * quantity)); trade.setEntrySnapshot("{}");
        trade.setClientRequestId(UUID.randomUUID().toString()); trade.setCreatedAt(trade.getEntryAt()); trade.setUpdatedAt(trade.getEntryAt());
        if (exitAt != null) {
            trade.setExitAt(Instant.parse(exitAt)); trade.setExitPrice(BigDecimal.valueOf(exit));
            trade.setExitQuoteTimestamp(trade.getExitAt().getEpochSecond()); trade.setExitQuoteSource("Test exit");
        }
        return trades.saveAndFlush(trade);
    }
}
