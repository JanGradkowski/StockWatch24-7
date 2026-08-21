package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.HistoricalHarmonicFormationService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalHarmonicFormationPageControllerTest {

    @Test
    void opensHistoricalDetailFromPatternAndEndpointIdentityWithoutCaching() {
        UserRepository users = mock(UserRepository.class);
        HistoricalHarmonicFormationService service = mock(HistoricalHarmonicFormationService.class);
        HistoricalHarmonicFormationService.HistoricalHarmonicDetail detail =
                mock(HistoricalHarmonicFormationService.HistoricalHarmonicDetail.class);
        User user = new User();
        user.setEmail("trader@example.com");
        user.setFirstName("Jan");
        Principal principal = user::getEmail;
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(service.findDetail("MSFT", "1d", HarmonicPatternType.GARTLEY, 1234L))
                .thenReturn(detail);
        HistoricalHarmonicFormationPageController controller =
                new HistoricalHarmonicFormationPageController(users, service);
        ConcurrentModel model = new ConcurrentModel();
        HttpServletResponse response = mock(HttpServletResponse.class);

        String view = controller.historicalHarmonicDetail(
                "msft", "1d", HarmonicPatternType.GARTLEY, 1234L,
                principal, model, response);

        assertThat(view).isEqualTo("historical-harmonic-detail");
        assertThat(model.getAttribute("firstName")).isEqualTo("Jan");
        assertThat(model.getAttribute("harmonic")).isSameAs(detail);
        assertThat(model.getAttribute("returnUrl")).isEqualTo("/stock/MSFT#general");
        verify(response).setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        verify(response).setHeader("Pragma", "no-cache");
    }
}
