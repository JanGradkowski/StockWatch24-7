package org.example.stockwatch247;

import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.CongressionalTrade;
import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.CongressionalTradeSubscription;
import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.VirtualTrade;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.CongressionalDeliveryStatus;
import org.example.stockwatch247.model.enums.CongressionalTradeType;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.InsiderTradeType;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.model.enums.VirtualTradeSide;
import org.example.stockwatch247.model.enums.VirtualTradeStatus;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.CongressionalTradeRepository;
import org.example.stockwatch247.repository.CongressionalTradeSubscriptionRepository;
import org.example.stockwatch247.repository.InsiderTradeRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.repository.VirtualTradeRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService;
import org.example.stockwatch247.service.TechnicalOutlookService;
import org.example.stockwatch247.service.VirtualTradeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@org.springframework.context.annotation.Import(org.example.stockwatch247.support.DesignPreviewCapture.class)
@AutoConfigureMockMvc
class StockWatch247ApplicationTests {
    private final List<Long> sessionFixtureUsers = new ArrayList<>();
    private org.springframework.test.web.servlet.request.RequestPostProcessor user(String email) {
        User account = userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User fixture = new User(); fixture.setEmail(email); fixture.setFirstName("Security"); fixture.setLastName("Fixture");
            fixture.setPasswordHash("test-only-password-hash"); fixture.setVerified(true);
            fixture = userRepository.saveAndFlush(fixture); sessionFixtureUsers.add(fixture.getId()); return fixture;
        });
        return request -> {
            request.getSession().setAttribute(org.example.stockwatch247.security.AccountSession.SECURITY_VERSION, account.getSecurityVersion());
            return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(email).postProcessRequest(request);
        };
    }
    @org.junit.jupiter.api.AfterEach void removeSessionFixtureUsers() { userRepository.deleteAllById(sessionFixtureUsers); }


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

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private CongressionalTradeRepository congressionalTradeRepository;

    @Autowired
    private CongressionalTradeSubscriptionRepository congressionalTradeSubscriptionRepository;

    @Autowired
    private CongressionalTradeDeliveryRepository congressionalTradeDeliveryRepository;

    @Autowired
    private InsiderTradeRepository insiderTradeRepository;

    @Autowired
    private VirtualTradeRepository virtualTradeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void authenticatedUserCanOpenTheFullTechnicalOutlookWorkspace() throws Exception {
        String email = "technical-outlook-" + Long.toString(System.nanoTime(), 36) + "@example.com";
        User account = new User();
        account.setEmail(email);
        account.setPasswordHash("test-only-password-hash");
        account.setFirstName("Technical");
        account.setLastName("Outlook");
        account.setVerified(true);
        userRepository.saveAndFlush(account);

        mockMvc.perform(get("/stock/AAPL/technical-outlook").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("technical-outlook"))
                .andExpect(model().attribute("symbol", "AAPL"))
                .andExpect(content().string(containsString("Technical Outlook")))
                .andExpect(content().string(containsString("General outlook")))
                .andExpect(content().string(containsString("Score report")))
                .andExpect(content().string(containsString("Market comparison")));
    }

    @Test
    @Transactional
    void authenticatedUserCanRenderVirtualTradeArchiveAndClosedTradeDetail() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
        String email = "virtual-trade-" + suffix.toLowerCase() + "@example.com";
        User account = new User();
        account.setEmail(email);
        account.setPasswordHash("test-only-password-hash");
        account.setFirstName("Virtual");
        account.setLastName("Trader");
        account.setVerified(true);
        userRepository.saveAndFlush(account);

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("V" + suffix.substring(0, Math.min(7, suffix.length())));
        asset.setCompanyName("Virtual Trade Test Company");
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");
        asset.setInstrumentType(InstrumentType.EQUITY);
        stockAssetRepository.saveAndFlush(asset);

        TechnicalOutlookService.ScoreView score = new TechnicalOutlookService.ScoreView(
                4, 3, 1, 3, 8, 0.375, "Moderate buy outlook");
        VirtualTradeService.TechnicalSnapshot snapshot = new VirtualTradeService.TechnicalSnapshot(
                "TECHNICAL_OUTLOOK_V1", Instant.now().getEpochSecond(), "1d", "Daily", Instant.now().getEpochSecond(),
                "Current completed candle", score, score, List.of(), List.of(), List.of());
        Instant entryAt = Instant.now().minusSeconds(86_400);
        VirtualTrade trade = new VirtualTrade();
        trade.setUser(account);
        trade.setStockAsset(asset);
        trade.setSide(VirtualTradeSide.SELL);
        trade.setStatus(VirtualTradeStatus.CLOSED);
        trade.setAnalysisInterval(TimeInterval.DAILY);
        trade.setEntryPrice(new BigDecimal("100.00000000"));
        trade.setEntryAt(entryAt);
        trade.setEntryQuoteTimestamp(entryAt.getEpochSecond());
        trade.setEntryCandleTimestamp(entryAt.getEpochSecond());
        trade.setEntryQuoteSource("Integration quote");
        trade.setCurrency("USD");
        trade.setQuantity(new BigDecimal("10.00000000"));
        trade.setNotionalValue(new BigDecimal("1000.00000000"));
        trade.setEntrySnapshot(objectMapper.writeValueAsString(snapshot));
        trade.setExitPrice(new BigDecimal("90.00000000"));
        trade.setExitAt(Instant.now());
        trade.setExitQuoteTimestamp(Instant.now().getEpochSecond());
        trade.setExitQuoteSource("Integration close");
        trade.setExitSnapshot(objectMapper.writeValueAsString(snapshot));
        trade.setClientRequestId(java.util.UUID.randomUUID().toString());
        trade.setCreatedAt(entryAt);
        trade.setUpdatedAt(Instant.now());
        virtualTradeRepository.saveAndFlush(trade);

        mockMvc.perform(get("/virtual-trades").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("virtual-trades"))
                .andExpect(content().string(containsString("Demo Trading")))
                .andExpect(content().string(containsString("Avoided loss")))
                .andExpect(content().string(containsString("Final reference value 900.00 USD")))
                .andExpect(content().string(containsString("class=\"virtual-trade-monetary-result\"")))
                .andExpect(content().string(containsString("Delete")))
                .andExpect(content().string(containsString("/virtual-trades/" + trade.getId() + "/delete")));

        mockMvc.perform(get("/virtual-trades/{id}", trade.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("virtual-trade"))
                .andExpect(content().string(containsString("Technical comparison")))
                .andExpect(content().string(containsString("Results")))
                .andExpect(content().string(containsString("Value at sell decision")))
                .andExpect(content().string(containsString("Final reference value")))
                .andExpect(content().string(containsString("900.00 USD")))
                .andExpect(content().string(containsString("not a short sale")));

        mockMvc.perform(post("/virtual-trades/{id}/delete", trade.getId())
                        .param("sort", "ticker")
                        .param("direction", "asc")
                        .param("period", "month")
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/virtual-trades?sort=ticker&direction=asc&period=month"))
                .andExpect(flash().attribute("virtualTradeDeleteMessage", "Demo trade deleted."));

        assertThat(virtualTradeRepository.findById(trade.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(virtualTradeRepository.findAllForUser(account)).isEmpty();

        mockMvc.perform(get("/virtual-trades/{id}", trade.getId()).with(user(email)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void authenticatedUserCanRenderAndSortTheActivitySignalArchive() throws Exception {
        String email = "activity-archive-" + Long.toString(System.nanoTime(), 36) + "@example.com";
        User account = new User();
        account.setEmail(email);
        account.setPasswordHash("test-only-password-hash");
        account.setFirstName("Activity");
        account.setLastName("Tester");
        account.setVerified(true);
        userRepository.saveAndFlush(account);

        mockMvc.perform(get("/activity-signals")
                        .param("sort", "actor")
                        .param("direction", "asc")
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("all-activity-signals"))
                .andExpect(model().attributeExists("archive"))
                .andExpect(content().string(containsString("All ticker alerts")))
                .andExpect(content().string(containsString("Buyer / seller name")))
                .andExpect(content().string(containsString("No activity signals have been received yet")));
    }

    @Test
    @Transactional
    void authenticatedUserCanOpenStoredActivityFromStockHistory() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
        String email = "stored-activity-" + suffix.toLowerCase() + "@example.com";
        User account = new User();
        account.setEmail(email);
        account.setPasswordHash("test-only-password-hash");
        account.setFirstName("History");
        account.setLastName("Viewer");
        account.setVerified(true);
        userRepository.saveAndFlush(account);

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("H" + suffix.substring(0, Math.min(7, suffix.length())));
        asset.setCompanyName("Stored Activity Company");
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");
        asset.setInstrumentType(InstrumentType.EQUITY);
        stockAssetRepository.saveAndFlush(asset);

        Instant now = Instant.now();
        CongressionalTrade congressionalTrade = new CongressionalTrade();
        congressionalTrade.setStockAsset(asset);
        congressionalTrade.setProvider("TEST");
        congressionalTrade.setProviderFingerprint(String.format("%064d", System.nanoTime()));
        congressionalTrade.setMemberName("Stored Congressional Member");
        congressionalTrade.setChamber("Senate");
        congressionalTrade.setTickerSymbol(asset.getTickerSymbol());
        congressionalTrade.setAssetName(asset.getCompanyName());
        congressionalTrade.setTransactionType(CongressionalTradeType.PURCHASE);
        congressionalTrade.setAmountRange("$15,001 - $50,000");
        congressionalTrade.setTransactionDate(LocalDate.now().minusDays(10));
        congressionalTrade.setDisclosureDate(LocalDate.now().minusDays(2));
        congressionalTrade.setSourceUrl("https://example.com/congressional-filing");
        congressionalTrade.setFirstSeenAt(now);
        congressionalTrade.setLastSeenAt(now);
        congressionalTradeRepository.saveAndFlush(congressionalTrade);

        InsiderTrade insiderTrade = new InsiderTrade();
        insiderTrade.setStockAsset(asset);
        insiderTrade.setProvider("TEST");
        insiderTrade.setProviderFingerprint(String.format("%064d", System.nanoTime() + 1));
        insiderTrade.setTickerSymbol(asset.getTickerSymbol());
        insiderTrade.setInsiderName("Stored Corporate Insider");
        insiderTrade.setOwnerRole("Chief Financial Officer");
        insiderTrade.setTransactionType(InsiderTradeType.SALE);
        insiderTrade.setTransactionCode("S");
        insiderTrade.setTransactionDate(LocalDate.now().minusDays(8));
        insiderTrade.setFilingDate(LocalDate.now().minusDays(7));
        insiderTrade.setShares(new BigDecimal("1250"));
        insiderTrade.setTransactionPrice(new BigDecimal("42.50"));
        insiderTrade.setSourceUrl("https://example.com/insider-filing");
        insiderTrade.setFirstSeenAt(now);
        insiderTrade.setLastSeenAt(now);
        insiderTradeRepository.saveAndFlush(insiderTrade);

        mockMvc.perform(get("/activity-signals/congressional/trades/{id}", congressionalTrade.getId())
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("activity-signal-detail"))
                .andExpect(content().string(containsString("Stored Congressional Member")));

        mockMvc.perform(get("/activity-signals/insider/trades/{id}", insiderTrade.getId())
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("activity-signal-detail"))
                .andExpect(content().string(containsString("Stored Corporate Insider")));
    }

    @Test
    @Transactional
    void activityNotificationCanBeReadWithoutLeavingThePermanentArchive() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
        String email = "activity-read-" + suffix.toLowerCase() + "@example.com";
        User account = new User();
        account.setEmail(email);
        account.setPasswordHash("test-only-password-hash");
        account.setFirstName("Activity");
        account.setLastName("Owner");
        account.setVerified(true);
        userRepository.saveAndFlush(account);

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("R" + suffix.substring(0, Math.min(8, suffix.length())));
        asset.setCompanyName("Archive Test Company " + suffix);
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");
        asset.setInstrumentType(InstrumentType.EQUITY);
        stockAssetRepository.saveAndFlush(asset);

        Instant now = Instant.now();
        CongressionalTradeSubscription subscription = new CongressionalTradeSubscription();
        subscription.setUser(account);
        subscription.setStockAsset(asset);
        subscription.setActive(true);
        subscription.setActivatedAt(now.minusSeconds(3600));
        subscription.setBaselineCompletedAt(now.minusSeconds(3500));
        subscription.setCreatedAt(now.minusSeconds(3600));
        subscription.setUpdatedAt(now.minusSeconds(3500));
        congressionalTradeSubscriptionRepository.saveAndFlush(subscription);

        CongressionalTrade trade = new CongressionalTrade();
        trade.setStockAsset(asset);
        trade.setProvider("TEST");
        trade.setProviderFingerprint(String.format("%064d", System.nanoTime()));
        trade.setMemberName("Archive Test Member");
        trade.setChamber("House");
        trade.setTickerSymbol(asset.getTickerSymbol());
        trade.setAssetName(asset.getCompanyName());
        trade.setTransactionType(CongressionalTradeType.PURCHASE);
        trade.setAmountRange("$1,001 - $15,000");
        trade.setTransactionDate(LocalDate.now().minusDays(20));
        trade.setDisclosureDate(LocalDate.now().minusDays(1));
        trade.setSourceUrl("https://example.com/filing");
        trade.setFirstSeenAt(now);
        trade.setLastSeenAt(now);
        congressionalTradeRepository.saveAndFlush(trade);

        CongressionalTradeDelivery delivery = new CongressionalTradeDelivery();
        delivery.setSubscription(subscription);
        delivery.setTrade(trade);
        delivery.setStatus(CongressionalDeliveryStatus.SENT);
        delivery.setAttempts(1);
        delivery.setAvailableAt(now);
        delivery.setSentAt(now);
        delivery.setCreatedAt(now);
        delivery.setUpdatedAt(now);
        congressionalTradeDeliveryRepository.saveAndFlush(delivery);
        String notificationKey = "CONGRESSIONAL-" + delivery.getId();

        LocalDate firstCandleDate = LocalDate.now().minusDays(30);
        for (int day = 0; day < 30; day++) {
            LocalDate candleDate = firstCandleDate.plusDays(day);
            double price = 90.0 + day;
            candleRepository.save(new Candle(
                    asset.getTickerSymbol(),
                    "1d",
                    candleDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                    price - 1,
                    price + 2,
                    price - 2,
                    price,
                    100_000L + day));
        }
        candleRepository.flush();

        mockMvc.perform(get("/home").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("congressionalUnreadCount", 1L))
                .andExpect(model().attribute("tickerNotificationUnreadCount", 1L))
                .andExpect(content().string(containsString(notificationKey)))
                .andExpect(content().string(containsString("Mark as read")))
                .andExpect(content().string(containsString("View signal")));

        mockMvc.perform(get("/activity-signals/congressional/{id}", delivery.getId())
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("activity-signal-detail"))
                .andExpect(model().attributeExists("signal"))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("style-src-attr 'unsafe-inline'")))
                .andExpect(content().string(containsString("Chart")))
                .andExpect(content().string(containsString("Technical analysis")))
                .andExpect(content().string(containsString("Transaction details")))
                .andExpect(content().string(containsString("id=\"signalChart\"")))
                .andExpect(content().string(containsString("data-chart-kind=\"activity\"")))
                .andExpect(content().string(containsString("data-detail-interval=\"1wk\"")))
                .andExpect(content().string(containsString("data-alternate-detail-chart")))
                .andExpect(content().string(containsString("id=\"signalResultsChart\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("Chart context is unavailable for this signal"))))
                .andExpect(content().string(containsString("Archive Test Member")))
                .andExpect(content().string(containsString("Daily close proxy")));

        mockMvc.perform(post("/api/congressional-activity/notifications/{id}/read", delivery.getId())
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(congressionalTradeDeliveryRepository.findById(delivery.getId())
                .orElseThrow().getReadAt()).isNotNull();

        mockMvc.perform(get("/home").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("congressionalUnreadCount", 0L))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(notificationKey))));

        mockMvc.perform(get("/activity-signals")
                        .param("sort", "company")
                        .param("direction", "asc")
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Archive Test Member")))
                .andExpect(content().string(containsString("Archive Test Company")))
                .andExpect(content().string(containsString("Select this page")))
                .andExpect(content().string(containsString("name=\"signalKeys\"")))
                .andExpect(content().string(containsString("name=\"singleSignalKey\"")))
                .andExpect(content().string(containsString("(read)")));

        User otherAccount = new User();
        otherAccount.setEmail("other-" + email);
        otherAccount.setPasswordHash("test-only-password-hash");
        otherAccount.setFirstName("Other");
        otherAccount.setLastName("Account");
        otherAccount.setVerified(true);
        userRepository.saveAndFlush(otherAccount);
        mockMvc.perform(post("/api/congressional-activity/notifications/{id}/read", delivery.getId())
                        .with(user(otherAccount.getEmail()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/activity-signals/congressional/{id}", delivery.getId())
                        .with(user(otherAccount.getEmail())))
                .andExpect(status().isBadRequest());

        delivery.setReadAt(null);
        congressionalTradeDeliveryRepository.saveAndFlush(delivery);
        mockMvc.perform(post("/api/congressional-activity/notifications/read-all")
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().isOk());
        assertThat(congressionalTradeDeliveryRepository.findById(delivery.getId())
                .orElseThrow().getReadAt()).isNotNull();

        mockMvc.perform(post("/activity-signals/delete")
                        .param("singleSignalKey", "CONGRESSIONAL:" + delivery.getId())
                        .with(user(otherAccount.getEmail()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/activity-signals"))
                .andExpect(flash().attributeExists("signalDeleteError"));
        assertThat(congressionalTradeDeliveryRepository.existsById(delivery.getId())).isTrue();

        mockMvc.perform(post("/activity-signals/delete")
                        .param("singleSignalKey", "CONGRESSIONAL:" + delivery.getId())
                        .with(user(email)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/activity-signals/delete")
                        .param("singleSignalKey", "CONGRESSIONAL:" + delivery.getId())
                        .param("sort", "company")
                        .param("direction", "asc")
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/activity-signals"))
                .andExpect(flash().attribute("signalDeleteMessage", "1 activity signal deleted."));
        assertThat(congressionalTradeDeliveryRepository.findById(delivery.getId())
                .orElseThrow().getDeletedAt()).isNotNull();
        mockMvc.perform(get("/activity-signals").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("Archive Test Member"))));
        mockMvc.perform(get("/activity-signals/congressional/{id}", delivery.getId())
                        .with(user(email)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void authenticatedUserCanRenderSettingsAndPersistAppearancePreferences() throws Exception {
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
                .andExpect(content().string(containsString(
                        "<h1 id=\"settingsPageTitle\"><span>General settings</span></h1>")))
                .andExpect(content().string(containsString("General settings")))
                .andExpect(content().string(containsString("Authenticator app")))
                .andExpect(content().string(containsString("Danger zone")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("Analysis settings"))));

        mockMvc.perform(get("/settings/appearance").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(content().string(containsString(
                        "<h1 id=\"settingsPageTitle\"><span>Appearance</span></h1>")))
                .andExpect(content().string(containsString("Choose your theme and chart colours.")))
                .andExpect(content().string(containsString("Motive I–V")))
                .andExpect(content().string(containsString("Corrective A–B–C")))
                .andExpect(content().string(containsString("Apply changes")));

        mockMvc.perform(get("/settings/analysis-alerts").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(content().string(containsString(
                        "<h1 id=\"settingsPageTitle\"><span>Analysis &amp; Alerts</span></h1>")))
                .andExpect(content().string(containsString("Analysis settings")))
                .andExpect(content().string(containsString("Elliott lifecycle tracking")))
                .andExpect(content().string(containsString("RSI period")))
                .andExpect(content().string(containsString("Reset analysis and email settings")));

        mockMvc.perform(get("/settings/detection").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(content().string(containsString(
                        "<h1 id=\"settingsPageTitle\"><span>Detection settings</span></h1>")))
                .andExpect(content().string(containsString("Candlestick trend detection")))
                .andExpect(content().string(containsString("Minimum trend move (%)")))
                .andExpect(content().string(containsString("Required directional confirmations")))
                .andExpect(content().string(containsString("Price structure, minimum move and directional candles")))
                .andExpect(content().string(containsString("opposite results reject it")))
                .andExpect(content().string(containsString("value=\"3.0\"")))
                .andExpect(content().string(containsString("Original-rule window candles")))
                .andExpect(content().string(containsString("Restore detection defaults")));

        mockMvc.perform(get("/settings/scoring").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(content().string(containsString(
                        "<h1 id=\"settingsPageTitle\"><span>Scoring settings</span></h1>")))
                .andExpect(content().string(containsString("Signal detail scoring")))
                .andExpect(content().string(containsString("Candlestick")))
                .andExpect(content().string(containsString("Elliott Wave")))
                .andExpect(content().string(containsString("Included point total")))
                .andExpect(content().string(containsString("100 / 100")))
                .andExpect(content().string(containsString("Apply changes")));

        mockMvc.perform(get("/settings/candlestick-patterns").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(content().string(containsString(
                        "<h1 id=\"settingsPageTitle\"><span>Candlestick Patterns</span></h1>")))
                .andExpect(content().string(containsString("Candlestick pattern definitions")))
                .andExpect(content().string(containsString("Risk-to-reward by interval")))
                .andExpect(content().string(containsString("Stop-loss rule")))
                .andExpect(content().string(containsString("Fixed percentage from entry")))
                .andExpect(content().string(containsString("Minimum second body versus first body")))
                .andExpect(content().string(containsString("Raise this to require the engulfing candle")))
                .andExpect(content().string(containsString("Restore candlestick defaults")));

        mockMvc.perform(post("/settings/appearance").with(user(email)).with(csrf())
                        .param("theme", "LIGHT")
                        .param("elliottMotiveColor", "#2563EB")
                        .param("elliottCorrectiveColor", "#9333EA")
                        .param("elliottSubwaveColor", "#F59E0B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/settings/appearance"));
        User updated = userRepository.findById(account.getId()).orElseThrow();
        assertThat(updated.getThemePreference()).isEqualTo("LIGHT");
        assertThat(updated.getElliottMotiveColor()).isEqualTo("#2563EB");
        assertThat(updated.getElliottCorrectiveColor()).isEqualTo("#9333EA");
        assertThat(updated.getElliottSubwaveColor()).isEqualTo("#F59E0B");

        mockMvc.perform(post("/settings/appearance").with(user(email)).with(csrf())
                        .param("theme", "DARK")
                        .param("elliottMotiveColor", "#123456")
                        .param("elliottCorrectiveColor", "#123456")
                        .param("elliottSubwaveColor", "#F59E0B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Choose three different Elliott Wave colors."));
    }

    @Test
    void publicResponsesContainSecurityHeaders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src-attr 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void aboutAndFunctionalitiesPageIsPublic() throws Exception {
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(view().name("about"))
                .andExpect(content().string(containsString("Technical analysis")))
                .andExpect(content().string(containsString("A pattern must meet its shape and prior-trend rules")))
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
                .andExpect(model().attribute("symbol", "^GSPC"))
                .andExpect(content().string(containsString("class=\"price-header-demo-trading\"")))
                .andExpect(content().string(containsString("aria-controls=\"historicalCandlestickLookbackDialog\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Pattern research"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("showHistoricalCandlestickPatternsBtn"))))
                .andExpect(content().string(containsString("class=\"alert-eye-input\"")))
                .andExpect(content().string(containsString("class=\"alert-panel alert-star-panel\"")))
                .andExpect(content().string(containsString("Filled stars indicate alerts you follow.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("check-alert-btn"))))
                .andExpect(content().string(containsString("id=\"rsiOverlayToggle\"")))
                .andExpect(content().string(containsString("id=\"volumeChartToggle\"")))
                .andExpect(content().string(containsString("id=\"volumeChartPanel\"")))
                .andExpect(content().string(containsString("data-chart-resize-target=\"priceChartStage\"")))
                .andExpect(content().string(containsString("data-chart-resize-target=\"rsiChartContainer\"")))
                .andExpect(content().string(containsString("data-chart-resize-target=\"volumeChartContainer\"")))
                .andExpect(content().string(containsString("id=\"rsiPeriodDialog\"")))
                .andExpect(content().string(containsString("id=\"rsiChartContainer\"")))
                .andExpect(content().string(containsString("RSI 14 with 70/30 boundaries is the classic default")))
                .andExpect(content().string(containsString("id=\"rsiOverboughtInput\"")))
                .andExpect(content().string(containsString("id=\"rsiOversoldInput\"")));
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
        event.setConfirmationWindowCandles(8);
        event.setTradeEntryPrice(19.42);
        event.setStopLossPrice(19.00);
        event.setProfitTargetPrice(20.68);
        event.setRewardRiskRatio(3.0);
        event.setTradePlanVersion("CANDLE_RR_V1");
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-20T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(1);
        event.setResolutionClosePrice(20.75);
        event.setLifecycleUpdatedAt(LocalDateTime.of(2026, 7, 24, 22, 15));
        event.setFollowUpSentAt(LocalDateTime.of(2026, 7, 24, 22, 16));
        event = alertEventRepository.save(event);

        List<Candle> chartCandles = new ArrayList<>();
        long chartStart = Instant.parse("2026-06-01T00:00:00Z").getEpochSecond();
        for (int index = 0; index < 6; index++) {
            long timestamp = chartStart + index * 7L * 86_400L;
            double close = 28.0 - index * 1.5;
            chartCandles.add(new Candle(
                    symbol, "1wk", timestamp, close + 0.8, close + 1.1, close - 0.6, close, 18_000L));
        }
        chartCandles.add(new Candle(
                symbol,
                "1wk",
                Instant.parse("2026-07-13T00:00:00Z").getEpochSecond(),
                18.50,
                20.00,
                18.00,
                19.42,
                24_000L
        ));
        candleRepository.saveAll(chartCandles);
        candleRepository.save(new Candle(
                symbol,
                "1wk",
                Instant.parse("2026-07-20T00:00:00Z").getEpochSecond(),
                19.50,
                21.50,
                18.50,
                20.75,
                25_000L
        ));

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
                .andExpect(content().string(containsString("data-unread-signal-count=\"1\"")))
                .andExpect(content().string(containsString("is-unread\"")))
                .andExpect(content().string(containsString("href=\"/signals\"")))
                .andExpect(content().string(containsString("/alerts/signals/" + event.getId())));

        mockMvc.perform(get("/alerts/{id}", candleBuy.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("all-signals"))
                .andExpect(model().attributeExists("archive", "companyArchive"))
                .andExpect(content().string(containsString("Company signal archive")))
                .andExpect(content().string(containsString(symbol + " signals")))
                .andExpect(content().string(containsString("Sort by")))
                .andExpect(content().string(containsString("Select this page")))
                .andExpect(content().string(containsString("Trade outcome")))
                .andExpect(content().string(containsString("Sold at target")))
                .andExpect(content().string(containsString("+6.49%")))
                .andExpect(content().string(containsString("Bullish Engulfing")))
                .andExpect(content().string(containsString("Signal status")))
                .andExpect(content().string(containsString("Confirmed")))
                .andExpect(content().string(containsString("(unread)")))
                .andExpect(content().string(containsString("/alerts/" + candleBuy.getId() + "/signals/delete")))
                .andExpect(content().string(containsString("/alerts/signals/" + event.getId())));

        mockMvc.perform(get("/signals")
                        .param("sort", "ticker")
                        .param("direction", "asc")
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("all-signals"))
                .andExpect(model().attributeExists("archive"))
                .andExpect(content().string(containsString("Technical signals")))
                .andExpect(content().string(containsString("Sort by")))
                .andExpect(content().string(containsString("Signal status")))
                .andExpect(content().string(containsString("Setup score")))
                .andExpect(content().string(containsString("value=\"confidence\"")))
                .andExpect(content().string(containsString("value=\"status\"")))
                .andExpect(content().string(containsString("value=\"trade-return\"")))
                .andExpect(content().string(containsString("Trade outcome")))
                .andExpect(content().string(containsString("Sold at target")))
                .andExpect(content().string(containsString("+6.49%")))
                .andExpect(content().string(containsString("(unread)")))
                .andExpect(content().string(containsString("/alerts/signals/" + event.getId())));

        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(email))
                        .param("returnTo", "/signals?state=unread&ticker=AAPL&page=2"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("archiveReturnUrl", "/signals?state=unread&ticker=AAPL&page=2"))
                .andExpect(content().string(containsString("Back to filtered signals")));
        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(email))
                        .param("returnTo", "https://example.com"))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("archiveReturnUrl"));

        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("signal-detail"))
                .andExpect(model().attributeExists("signal"))
                .andExpect(content().string(containsString("Chart")))
                .andExpect(content().string(containsString("Score report")))
                .andExpect(content().string(containsString("id=\"signalChart\"")))
                .andExpect(content().string(containsString("data-chart-kind=\"technical\"")))
                .andExpect(content().string(containsString("data-native-interval-notice")))
                .andExpect(content().string(containsString("data-detail-interval=\"1mo\"")))
                .andExpect(content().string(containsString("Required downtrend")))
                .andExpect(content().string(containsString("Available price history")))
                .andExpect(content().string(containsString("Why this score")))
                .andExpect(content().string(containsString("Signal lifecycle timeline")))
                .andExpect(content().string(containsString("Close-based trade outcome")))
                .andExpect(content().string(containsString("Detection")))
                .andExpect(content().string(containsString("Detected")))
                .andExpect(content().string(containsString("Terminal update")))
                .andExpect(content().string(containsString("Confirmed")))
                .andExpect(content().string(containsString("20\u201324 Jul 2026")))
                .andExpect(content().string(containsString("Outcome checked")))
                .andExpect(content().string(containsString("Setup score 88 out of 100")))
                .andExpect(content().string(containsString("Strict bullish candle-pattern geometry.")))
                .andExpect(content().string(containsString("RSI is rising versus the previous candle")))
                .andExpect(content().string(containsString("How the setup score works")));

        assertThat(alertEventRepository.findById(event.getId()).orElseThrow().getReadAt()).isNotNull();

        mockMvc.perform(get("/home").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-unread-signal-count=\"0\"")))
                .andExpect(content().string(containsString("No unread signals")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("/alerts/signals/" + event.getId()))));

        mockMvc.perform(get("/signals").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(read)")))
                .andExpect(content().string(containsString("/alerts/signals/" + event.getId())));

        candleBuy.setActive(false);
        alertRuleRepository.saveAndFlush(candleBuy);

        mockMvc.perform(get("/signals").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/alerts/signals/" + event.getId())));

        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"history-back-button\"")))
                .andExpect(content().string(containsString("data-history-back")))
                .andExpect(content().string(containsString("/js/history-back-")));

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

        mockMvc.perform(get("/signals").with(user(otherUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("/alerts/signals/" + event.getId()))));

        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(otherUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"error\":\"Invalid request.\"}"));

        mockMvc.perform(post("/signals/delete")
                        .param("singleSignalId", event.getId().toString())
                        .with(user(otherUser.getEmail()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/signals"))
                .andExpect(flash().attributeExists("signalDeleteError"));
        assertThat(alertEventRepository.existsById(event.getId())).isTrue();

        mockMvc.perform(post("/signals/delete")
                        .param("singleSignalId", event.getId().toString())
                        .with(user(email)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/signals/delete")
                        .param("singleSignalId", event.getId().toString())
                        .param("sort", "ticker")
                        .param("direction", "asc")
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/signals"))
                .andExpect(flash().attribute("signalDeleteMessage", "1 signal deleted."));
        assertThat(alertEventRepository.findById(event.getId())
                .orElseThrow().getDeletedAt()).isNotNull();
        mockMvc.perform(get("/signals").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("/alerts/signals/" + event.getId()))));
        mockMvc.perform(get("/alerts/signals/{id}", event.getId()).with(user(email)))
                .andExpect(status().isBadRequest());
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
