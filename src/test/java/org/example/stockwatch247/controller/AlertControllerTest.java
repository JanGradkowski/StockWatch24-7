package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AlertRuleService;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertControllerTest {

    @Test
    void unfollowsEveryTechnicalRuleForTheAuthenticatedUser() {
        AlertRuleService service = mock(AlertRuleService.class);
        UserRepository users = mock(UserRepository.class);
        AlertController controller = new AlertController(service, users);
        User user = new User();
        user.setEmail("unfollow-all@example.com");
        Principal principal = user::getEmail;
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(service.unfollowAllTechnicalRules(user)).thenReturn(3_600);

        var response = controller.unfollowAllTechnicalRules(principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("unfollowedRules", 3_600));
        verify(service).unfollowAllTechnicalRules(user);
    }

    @Test
    void unfollowsEveryTechnicalRuleForTheCompany() {
        AlertRuleService service = mock(AlertRuleService.class);
        UserRepository users = mock(UserRepository.class);
        AlertController controller = new AlertController(service, users);
        User user = new User();
        user.setEmail("unfollow@example.com");
        Principal principal = user::getEmail;
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(service.unfollowAllTechnicalRules(user, "AAPL")).thenReturn(3);

        var response = controller.unfollowAllTechnicalRules("AAPL", principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("symbol", "AAPL", "unfollowedRules", 3));
        verify(service).unfollowAllTechnicalRules(user, "AAPL");
    }

    @Test
    void unfollowsOnlySelectedTechnicalRulesForTheCompany() {
        AlertRuleService service = mock(AlertRuleService.class);
        UserRepository users = mock(UserRepository.class);
        AlertController controller = new AlertController(service, users);
        User user = new User();
        user.setEmail("selected-unfollow@example.com");
        Principal principal = user::getEmail;
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(service.unfollowSelectedTechnicalRules(user, "NFLX", List.of(4L, 7L))).thenReturn(2);

        var response = controller.unfollowSelectedTechnicalRules(
                "NFLX", new AlertController.RuleSelectionRequest(List.of(4L, 7L)), principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("symbol", "NFLX", "unfollowedRules", 2));
        verify(service).unfollowSelectedTechnicalRules(user, "NFLX", List.of(4L, 7L));
    }

    @Test
    void appliesTheDraftWithOneBatchRequestAndReturnsThePersistedState() {
        AlertRuleService service = mock(AlertRuleService.class);
        UserRepository users = mock(UserRepository.class);
        AlertController controller = new AlertController(service, users);
        User user = new User();
        user.setEmail("draft@example.com");
        Principal principal = user::getEmail;
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        Map<String, Object> state = Map.of("trackedStocks", 1, "maxTrackedStocks", 50);
        when(service.getAlertState(user, "AAPL")).thenReturn(state);

        var response = controller.applyAlertChanges(
                "AAPL",
                new AlertController.AlertBatchRequest(List.of(
                        new AlertController.AlertToggleRequest(
                                "DAILY", "BUY", "CANDLESTICK", true),
                        new AlertController.AlertToggleRequest(
                                "MONTHLY", "SELL", "ELLIOTT_WAVE", false),
                        new AlertController.AlertToggleRequest(
                                "WEEKLY", "BUY", "HARMONIC_FORMATION", true)
                )),
                principal
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(state);
        verify(service).applyAlertChanges(eq(user), eq("AAPL"), argThat(changes ->
                changes.equals(List.of(
                        new AlertRuleService.AlertRuleChange(
                                TimeInterval.DAILY, TradeSignal.BUY,
                                AlertPatternFamily.CANDLESTICK, true),
                        new AlertRuleService.AlertRuleChange(
                                TimeInterval.MONTHLY, TradeSignal.SELL,
                                AlertPatternFamily.ELLIOTT_WAVE, false),
                        new AlertRuleService.AlertRuleChange(
                                TimeInterval.WEEKLY, TradeSignal.BUY,
                                AlertPatternFamily.HARMONIC_FORMATION, true)
                ))));
    }

    @Test
    void followsTemporaryTopUsUniverseForTheAuthenticatedUser() {
        AlertRuleService service = mock(AlertRuleService.class);
        UserRepository users = mock(UserRepository.class);
        AlertController controller = new AlertController(service, users);
        User user = new User();
        user.setEmail("bulk@example.com");
        Principal principal = user::getEmail;
        AlertRuleService.TemporaryBulkFollowResult result =
                new AlertRuleService.TemporaryBulkFollowResult(
                        200, 18, 3_600, 0, 0, 3_600, "test snapshot");
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(service.followTemporaryTopUsCompanies(user)).thenReturn(result);

        var response = controller.followTemporaryTopUsCompanies(principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(result);
        verify(service).followTemporaryTopUsCompanies(user);
    }
}
