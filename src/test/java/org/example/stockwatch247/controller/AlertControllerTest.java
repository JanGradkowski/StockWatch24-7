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
                                "MONTHLY", "SELL", "ELLIOTT_WAVE", false)
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
                                AlertPatternFamily.ELLIOTT_WAVE, false)
                ))));
    }
}
