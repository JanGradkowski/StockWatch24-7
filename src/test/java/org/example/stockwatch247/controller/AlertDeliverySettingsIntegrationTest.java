package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.example.stockwatch247.service.channel.AlertKind;
import org.example.stockwatch247.service.channel.ChannelStatus;
import org.example.stockwatch247.service.channel.ChannelStore;
import org.example.stockwatch247.service.channel.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No channel type can send here, so nothing in these tests reaches a real provider.
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alert-channels.enabled=true",
        "alert-channels.worker-enabled=false", "alert-channels.discord.enabled=false",
        "alert-channels.webhook.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class AlertDeliverySettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelStore store;
    private User account;

    @BeforeEach
    void account() {
        account = new User();
        account.setEmail("delivery-" + UUID.randomUUID() + "@example.com");
        account.setFirstName("Jan");
        account.setLastName("Test");
        account.setVerified(true);
        account.setPasswordHash(encoder.encode("Current-password-for-test42!"));
        users.saveAndFlush(account);
    }

    private ChannelStore.Channel channel(User owner, String label) {
        String url = "https://discord.com/api/webhooks/1/" + UUID.randomUUID();
        return store.create(new ChannelStore.NewChannel(owner.getId(), ChannelType.DISCORD, label, "discord.com · …/1",
                "discord", ChannelStatus.ACTIVE, EnumSet.of(AlertKind.SIGNAL_CANDLESTICK), Set.of("BUY", "SELL"), null,
                Map.of("webhookUrl", url), url));
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AccountSession.SECURITY_VERSION, account.getSecurityVersion());
        return request.with(user(account.getEmail())).session(session);
    }

    @Test
    void theTabListsChannelsInBothLanguagesWithAFormTokenOnEveryFormAndNoInlineStyles() throws Exception {
        channel(account, "Trading desk");
        String html = mvc.perform(signedIn(get("/settings/alert-delivery")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your channels")))
                .andExpect(content().string(containsString("Trading desk")))
                .andExpect(content().string(containsString("Discord · discord.com · …/1")))
                .andExpect(content().string(containsString("No other channels are set up on this server yet.")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain(" style=\"").doesNotContain("discord.com/api/webhooks");
        long forms = Pattern.compile("<form[^>]*method=\"post\"").matcher(html).results().count();
        long tokens = Pattern.compile("name=\"_csrf\"").matcher(html).results().count();
        assertThat(forms).isGreaterThan(4);
        assertThat(tokens).isGreaterThanOrEqualTo(forms);

        mvc.perform(signedIn(get("/settings/alert-delivery")).header("Accept-Language", "pl-PL,pl;q=0.9"))
                .andExpect(content().string(containsString("Twoje kanały")))
                .andExpect(content().string(containsString("Dostarczanie alertów")));
    }

    @Test
    void withNoChannelsThePageExplainsWhatTheyAre() throws Exception {
        mvc.perform(signedIn(get("/settings/alert-delivery")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alerts reach you by email and on the website.")));
        mvc.perform(signedIn(get("/settings/analysis-alerts")))
                .andExpect(content().string(containsString("href=\"/settings/alert-delivery\"")));
    }

    @Test
    void anotherAccountsChannelAndMadeUpIdsAreNotFound() throws Exception {
        User other = new User();
        other.setEmail("delivery-other-" + UUID.randomUUID() + "@example.com");
        other.setFirstName("Other");
        other.setLastName("Test");
        other.setPasswordHash("unused");
        users.saveAndFlush(other);
        var theirs = channel(other, "Their desk");
        mvc.perform(signedIn(post("/settings/alert-delivery/" + theirs.publicId() + "/pause")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(signedIn(post("/settings/alert-delivery/not-a-uuid/remove")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(signedIn(post("/settings/alert-delivery/" + theirs.publicId() + "/pause")))
                .andExpect(status().isForbidden());
        assertThat(store.byId(theirs.id()).orElseThrow().status()).isEqualTo(ChannelStatus.ACTIVE);
    }

    @Test
    void aChannelCanBePausedResumedEditedAndRemoved() throws Exception {
        var channel = channel(account, "Desk");
        String base = "/settings/alert-delivery/" + channel.publicId();
        mvc.perform(signedIn(post(base + "/pause")).with(csrf()))
                .andExpect(redirectedUrl("/settings/alert-delivery#channel-" + channel.publicId()))
                .andExpect(flash().attribute("success", "Channel paused. Alerts that were waiting for it were dropped."));
        assertThat(store.byId(channel.id()).orElseThrow().status()).isEqualTo(ChannelStatus.PAUSED);
        mvc.perform(signedIn(post(base + "/resume")).with(csrf()));
        assertThat(store.byId(channel.id()).orElseThrow().status()).isEqualTo(ChannelStatus.ACTIVE);

        mvc.perform(signedIn(post(base + "/update")).with(csrf()).param("label", "Shorts desk")
                        .param("kinds", "OUTLOOK", "CHART").param("directions", "SELL").param("language", "pl"))
                .andExpect(flash().attribute("success", "Channel saved."));
        var edited = store.byId(channel.id()).orElseThrow();
        assertThat(edited.label()).isEqualTo("Shorts desk");
        assertThat(edited.kinds()).containsExactlyInAnyOrder(AlertKind.OUTLOOK, AlertKind.CHART);
        assertThat(edited.directions()).containsExactly("SELL");
        assertThat(edited.language()).isEqualTo("pl");

        mvc.perform(signedIn(post(base + "/update")).with(csrf()).param("label", "Shorts desk").param("directions", "SELL"))
                .andExpect(flash().attribute("error", "Choose at least one kind of alert."));
        mvc.perform(signedIn(post(base + "/update")).with(csrf()).param("label", "Shorts desk").param("preset", "email")
                        .param("language", "auto"))
                .andExpect(flash().attribute("success", "Channel saved."));
        assertThat(store.byId(channel.id()).orElseThrow().kinds()).hasSizeGreaterThan(2);

        mvc.perform(signedIn(post("/settings/alert-delivery/pause-all")).with(csrf()))
                .andExpect(flash().attribute("success", "All channels paused."));
        mvc.perform(signedIn(post("/settings/alert-delivery/resume-all")).with(csrf()))
                .andExpect(flash().attribute("success", "Paused channels resumed."));
        mvc.perform(signedIn(post(base + "/remove")).with(csrf()))
                .andExpect(flash().attribute("success", "Channel removed."));
        assertThat(store.byId(channel.id())).isEmpty();
    }

    @Test
    void aTestOfAKindThisServerCannotSendSaysSo() throws Exception {
        var channel = channel(account, "Desk");
        mvc.perform(signedIn(post("/settings/alert-delivery/" + channel.publicId() + "/test")).with(csrf()))
                .andExpect(flash().attribute("error", "This kind of channel is not set up on this server."));
    }

    @Test
    void aChannelThatStoppedOnItsOwnIsAnnouncedOnTheHomePage() throws Exception {
        var channel = channel(account, "Desk");
        store.stop(channel.id(), ChannelStatus.FAILING, "TIMEOUT");
        mvc.perform(signedIn(get("/home")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alerts to Desk stopped after failing for more than a day.")))
                .andExpect(content().string(containsString("/settings/alert-delivery#channel-" + channel.publicId())));
        mvc.perform(signedIn(get("/settings/alert-delivery")))
                .andExpect(content().string(containsString("Stopped after failing for more than a day (the receiver did not answer in time).")));
    }
}
