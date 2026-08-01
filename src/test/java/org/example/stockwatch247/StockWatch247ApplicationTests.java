package org.example.stockwatch247;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@AutoConfigureMockMvc
class StockWatch247ApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StockAssetRepository stockAssetRepository;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void authenticatedUserCanRenderSettingsAndPersistAnAccountTheme() throws Exception {
        String email = "settings-" + Long.toString(System.nanoTime(), 36) + "@example.com";
        User account = new User();
        account.setEmail(email);
        account.setPasswordHash("test-only-password-hash");
        account.setFirstName("Settings");
        account.setLastName("Tester");
        account.setVerified(true);
        userRepository.saveAndFlush(account);

        mockMvc.perform(get("/settings").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(content().string(containsString("General settings")))
                .andExpect(content().string(containsString("Authenticator app")))
                .andExpect(content().string(containsString("Danger zone")));

        mockMvc.perform(post("/settings/theme").with(user(email)).with(csrf()).param("theme", "LIGHT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/settings?themeSaved=true"));
        assertThat(userRepository.findById(account.getId()).orElseThrow().getThemePreference()).isEqualTo("LIGHT");
    }

    @Test
    void publicResponsesContainSecurityHeaders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src-attr 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void aboutAndFunctionalitiesPageIsPublic() throws Exception {
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(view().name("about"))
                .andExpect(content().string(containsString("Technical signals")))
                .andExpect(content().string(containsString("CANDLE_V4_EXPERIMENTAL")))
                .andExpect(content().string(containsString("ELLIOTT_V1")));
    }

    @Test
    void protectedApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/stocks/search").param("query", "AAPL"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void missingFaviconReturnsNotFoundInsteadOfInternalServerError() throws Exception {
        mockMvc.perform(get("/favicon.ico").with(user("favicon-test@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void authenticatedStateChangeWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/alerts/AAPL")
                        .with(user("security-test@example.com"))
                        .contentType("application/json")
                        .content("{\"interval\":\"DAILY\",\"signal\":\"BUY\",\"active\":true}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/alerts/AAPL")
                        .with(user("security-test@example.com"))
                        .contentType("application/json")
                        .content("{\"changes\":[{\"interval\":\"DAILY\",\"signal\":\"BUY\",\"active\":true}]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/congressional-activity/AAPL/subscription")
                        .with(user("security-test@example.com"))
                        .contentType("application/json")
                .content("{\"active\":true}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/insider-activity/AAPL/subscription")
                        .with(user("security-test@example.com"))
                        .contentType("application/json")
                        .content("{\"active\":true}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/insider-activity/AAPL/history/refresh")
                        .with(user("security-test@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    void congressionalActivityStateIsAvailableForStocksAndRejectedForFunds() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
        String email = "congress-state-" + suffix.toLowerCase() + "@example.com";
        User userEntity = new User();
        userEntity.setEmail(email);
        userEntity.setPasswordHash("test-only-password-hash");
        userEntity.setFirstName("Congress");
        userEntity.setLastName("State");
        userEntity.setVerified(true);
        userRepository.save(userEntity);

        StockAsset equity = new StockAsset();
        equity.setTickerSymbol("C" + suffix);
        equity.setCompanyName("Congress State Equity");
        equity.setExchange("NASDAQ");
        equity.setCurrency("USD");
        equity.setInstrumentType(InstrumentType.EQUITY);
        stockAssetRepository.save(equity);

        StockAsset etf = new StockAsset();
        etf.setTickerSymbol("F" + suffix);
        etf.setCompanyName("Congress State ETF");
        etf.setExchange("NYSE");
        etf.setCurrency("USD");
        etf.setInstrumentType(InstrumentType.ETF);
        stockAssetRepository.save(etf);

        mockMvc.perform(get("/api/congressional-activity/{symbol}/state", equity.getTickerSymbol())
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.historyDays").value(365))
                .andExpect(jsonPath("$.relevanceNotice", containsString("last 365 days")))
                .andExpect(jsonPath("$.alertBaselineNotice", containsString("never generates old email alerts")));

        mockMvc.perform(get("/api/congressional-activity/{symbol}/state", etf.getTickerSymbol())
                        .with(user(email)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void insiderActivityStateIsAvailableForStocksAndRejectedForFunds() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
        String email = "insider-state-" + suffix.toLowerCase() + "@example.com";
        User userEntity = new User();
        userEntity.setEmail(email);
        userEntity.setPasswordHash("test-only-password-hash");
        userEntity.setFirstName("Insider");
        userEntity.setLastName("State");
        userEntity.setVerified(true);
        userRepository.save(userEntity);

        StockAsset equity = new StockAsset();
        equity.setTickerSymbol("I" + suffix);
        equity.setCompanyName("Insider State Equity");
        equity.setExchange("NASDAQ");
        equity.setCurrency("USD");
        equity.setInstrumentType(InstrumentType.EQUITY);
        stockAssetRepository.save(equity);

        StockAsset etf = new StockAsset();
        etf.setTickerSymbol("J" + suffix);
        etf.setCompanyName("Insider State ETF");
        etf.setExchange("NYSE");
        etf.setCurrency("USD");
        etf.setInstrumentType(InstrumentType.ETF);
        stockAssetRepository.save(etf);

        mockMvc.perform(get("/api/insider-activity/{symbol}/state", equity.getTickerSymbol())
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.historyDays").value(730));

        mockMvc.perform(get("/api/insider-activity/{symbol}/state", etf.getTickerSymbol())
                        .with(user(email)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void appliesAnAlertDraftThroughTheBatchEndpoint() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
        String symbol = "D" + suffix;
        String email = "draft-" + suffix.toLowerCase() + "@example.com";

        User userEntity = new User();
        userEntity.setEmail(email);
        userEntity.setPasswordHash("test-only-password-hash");
        userEntity.setFirstName("Draft");
        userEntity.setLastName("Tester");
        userEntity.setVerified(true);
        userRepository.save(userEntity);

        StockAsset stockAsset = new StockAsset();
        stockAsset.setTickerSymbol(symbol);
        stockAsset.setCompanyName("Alert Draft Test Company");
        stockAsset.setExchange("NASDAQ");
        stockAsset.setCurrency("USD");
        stockAsset.setInstrumentType(InstrumentType.EQUITY);
        stockAssetRepository.save(stockAsset);

        mockMvc.perform(put("/api/alerts/{symbol}", symbol)
                        .with(user(email))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"changes":[
                                  {"interval":"DAILY","signal":"BUY","patternFamily":"CANDLESTICK","active":true},
                                  {"interval":"MONTHLY","signal":"SELL","patternFamily":"ELLIOTT_WAVE","active":true}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.families.CANDLESTICK.DAILY.BUY").value(true))
                .andExpect(jsonPath("$.families.ELLIOTT_WAVE.MONTHLY.SELL").value(true))
                .andExpect(jsonPath("$.trackedStocks").value(1));
    }

    @Test
    void indexAliasIsSearchableAndNavigatesWithTheCanonicalSymbol() throws Exception {
        mockMvc.perform(get("/api/stocks/search")
                        .param("q", "SPX")
                        .with(user("index-test@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("^GSPC"))
                .andExpect(jsonPath("$[0].instrumentType").value("INDEX"));

        mockMvc.perform(get("/stock/SPX").with(user("index-test@example.com")))
                .andExpect(status().isOk())
                .andExpect(view().name("stock"))
                .andExpect(model().attribute("symbol", "^GSPC"));
    }

    @Test
    @Transactional
    void groupedCompanyDashboardAndDynamicHistoryBoardRender() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
        String symbol = "TST" + suffix;
        String email = symbol.toLowerCase() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("test-only-password-hash");
        user.setFirstName("Board");
        user.setLastName("Tester");
        user.setVerified(true);
        user = userRepository.save(user);

        StockAsset stockAsset = new StockAsset();
        stockAsset.setTickerSymbol(symbol);
        stockAsset.setCompanyName("Grouped Board Test Company");
        stockAsset.setExchange("NASDAQ");
        stockAsset.setCurrency("USD");
        stockAsset.setInstrumentType(InstrumentType.EQUITY);
        stockAsset = stockAssetRepository.save(stockAsset);

        StockAsset indexAsset = new StockAsset();
        indexAsset.setTickerSymbol("I" + suffix);
        indexAsset.setCompanyName("Dashboard Test Index");
        indexAsset.setExchange("INDEX");
        indexAsset.setCurrency("USD");
        indexAsset.setInstrumentType(InstrumentType.INDEX);
        indexAsset = stockAssetRepository.save(indexAsset);

        StockAsset etfAsset = new StockAsset();
        etfAsset.setTickerSymbol("E" + suffix);
        etfAsset.setCompanyName("Dashboard Test ETF");
        etfAsset.setExchange("NYSE");
        etfAsset.setCurrency("USD");
        etfAsset.setInstrumentType(InstrumentType.ETF);
        etfAsset = stockAssetRepository.save(etfAsset);

        AlertRule candleBuy = alertRule(user, stockAsset, TimeInterval.WEEKLY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.BUY, CandlePattern.BULLISH_ENGULFING);
        AlertRule elliottSell = alertRule(user, stockAsset, TimeInterval.MONTHLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL, CandlePattern.ELLIOTT_BEARISH_CORRECTION);
        candleBuy = alertRuleRepository.save(candleBuy);
        alertRuleRepository.save(elliottSell);
        alertRuleRepository.save(alertRule(user, indexAsset, TimeInterval.WEEKLY,
                AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY, CandlePattern.ELLIOTT_BULLISH_CORRECTION));
        alertRuleRepository.save(alertRule(user, etfAsset, TimeInterval.DAILY,
                AlertPatternFamily.CANDLESTICK, TradeSignal.SELL, CandlePattern.BEARISH_ENGULFING));

        AlertEvent event = new AlertEvent();
        event.setAlertRule(candleBuy);
        event.setPattern(CandlePattern.BULLISH_ENGULFING);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(Instant.parse("2026-07-13T00:00:00Z").getEpochSecond());
        event.setSignalStrength(SignalStength.HIGH_CONFIDENCE);
        event.setConfidenceScore(88);
        event.setScoreVersion(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        event.setConfidenceReasons(List.of(
                "strict bullish candle-pattern geometry",
                "RSI is rising versus the previous candle",
                "volume is at least 20% above its 20-period average"
        ));
        event.setClosePrice(19.42);
        event.setSentAt(LocalDateTime.of(2026, 7, 17, 22, 15));
        event.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
        event.setPatternHigh(20.0);
        event.setPatternLow(18.0);
        event.setConfirmationTriggerPrice(20.0);
        event.setInvalidationPrice(18.0);
        event.setConfirmationWindowCandles(3);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-20T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(1);
        event.setResolutionClosePrice(20.75);
        event.setLifecycleUpdatedAt(LocalDateTime.of(2026, 7, 24, 22, 15));
        event.setFollowUpSentAt(LocalDateTime.of(2026, 7, 24, 22, 16));
        event = alertEventRepository.save(event);

        mockMvc.perform(get("/home").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(model().attributeExists("latestSignals"))
                .andExpect(model().attribute("stockCompanyCount", 1L))
                .andExpect(model().attribute("indexEtfCompanyCount", 2L))
                .andExpect(content().string(containsString("Grouped Board Test Company")))
                .andExpect(content().string(containsString("Dashboard Test Index")))
                .andExpect(content().string(containsString("Dashboard Test ETF")))
                .andExpect(content().string(containsString("Watched rules")))
                .andExpect(content().string(containsString("Indexes / ETFs")))
                .andExpect(content().string(containsString("data-instrument-group=\"stocks\"")))
                .andExpect(content().string(containsString("data-instrument-group=\"funds\"")))
                .andExpect(content().string(containsString("aria-pressed=\"true\"")))
                .andExpect(content().string(containsString("Latest signals")))
                .andExpect(content().string(containsString("Bullish Engulfing")))
                .andExpect(content().string(containsString("CANDLE_V4_EXPERIMENTAL")))
                .andExpect(content().string(containsString("13\u201317 Jul 2026")))
                .andExpect(content().string(containsString("Lifecycle status")))
                .andExpect(content().string(containsString("Confirmed")))
                .andExpect(content().string(containsString("Setup score 88 out of 100")))
                .andExpect(content().string(containsString("/alerts/signals/" + event.getId())));

        mockMvc.perform(get("/alerts/{id}", candleBuy.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("alert-history"))
                .andExpect(content().string(containsString("Followed combinations")))
                .andExpect(content().string(containsString("Candlestick")))
                .andExpect(content().string(containsString("Elliott Wave")))
                .andExpect(content().string(containsString("Bullish Engulfing")))
                .andExpect(content().string(containsString("Lifecycle status")))
                .andExpect(content().string(containsString("Resolved 20\u201324 Jul 2026")))
                .andExpect(content().string(containsString("Confirmed")))
                .andExpect(content().string(containsString("/alerts/signals/" + event.getId())));

        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("signal-detail"))
                .andExpect(model().attributeExists("signal"))
                .andExpect(content().string(containsString("Why this score")))
                .andExpect(content().string(containsString("Signal lifecycle timeline")))
                .andExpect(content().string(containsString("Detection")))
                .andExpect(content().string(containsString("Detected")))
                .andExpect(content().string(containsString("Terminal update")))
                .andExpect(content().string(containsString("Confirmed")))
                .andExpect(content().string(containsString("20\u201324 Jul 2026")))
                .andExpect(content().string(containsString("Lifecycle processed")))
                .andExpect(content().string(containsString("Setup score 88 out of 100")))
                .andExpect(content().string(containsString("Strict bullish candle-pattern geometry.")))
                .andExpect(content().string(containsString("RSI is rising versus the previous candle")))
                .andExpect(content().string(containsString("How the setup score works")));

        User otherUser = new User();
        otherUser.setEmail("other-" + email);
        otherUser.setPasswordHash("test-only-password-hash");
        otherUser.setFirstName("Other");
        otherUser.setLastName("Tester");
        otherUser.setVerified(true);
        userRepository.save(otherUser);

        mockMvc.perform(get("/home").with(user(otherUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("/alerts/signals/" + event.getId()))));

        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(otherUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"error\":\"Invalid request.\"}"));
    }

    private AlertRule alertRule(User user,
                                StockAsset stockAsset,
                                TimeInterval interval,
                                AlertPatternFamily family,
                                TradeSignal signal,
                                CandlePattern pattern) {
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(stockAsset);
        rule.setInterval(interval);
        rule.setPatternFamily(family);
        rule.setTradeSignal(signal);
        rule.setTargetPattern(pattern);
        rule.setActive(true);
        return rule;
    }

}
