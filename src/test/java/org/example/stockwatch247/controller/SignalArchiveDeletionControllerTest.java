package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.SignalArchiveDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignalArchiveDeletionControllerTest {

    @Test
    void companyArchiveDeletionReturnsToTheSameFilteredArchive() {
        UserRepository users = mock(UserRepository.class);
        SignalArchiveDeletionService deletionService = mock(SignalArchiveDeletionService.class);
        SignalArchiveDeletionController controller = new SignalArchiveDeletionController(users, deletionService);
        User user = new User();
        user.setEmail("company-delete@example.com");
        Principal principal = user::getEmail;
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(deletionService.deleteTechnicalSignals(user, List.of(17L, 23L))).thenReturn(2);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.deleteCompanyTechnicalSignals(
                9L, List.of(17L, 23L), null, "confidence", "asc", 2, "unread", " aapl ", principal, redirect);

        assertThat(view).isEqualTo("redirect:/alerts/9");
        assertThat(redirect.getFlashAttributes().get("signalDeleteMessage")).isEqualTo("2 signals deleted.");
        assertThat(redirect.getAttribute("sort")).isEqualTo("confidence");
        assertThat(redirect.getAttribute("direction")).isEqualTo("asc");
        assertThat(redirect.getAttribute("page")).isEqualTo("2");
        assertThat(redirect.getAttribute("state")).isEqualTo("unread");
        assertThat(redirect.getAttribute("ticker")).isEqualTo("AAPL");
        verify(deletionService).deleteTechnicalSignals(user, List.of(17L, 23L));
    }
}
