package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AnchoredVolumeProfileService;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnchoredVolumeProfileControllerTest {

    @Test
    void authenticatedRequestUsesTheValidatedTickerIntervalAndAnchor() {
        UserRepository users = mock(UserRepository.class);
        AnchoredVolumeProfileService profiles = mock(AnchoredVolumeProfileService.class);
        AnchoredVolumeProfileController controller =
                new AnchoredVolumeProfileController(users, profiles);
        User user = new User();
        user.setId(7L);
        user.setEmail("profile@example.com");
        when(users.findByEmailIgnoreCase("profile@example.com"))
                .thenReturn(Optional.of(user));
        Principal principal = () -> "profile@example.com";

        controller.profile("aapl", "1d", 1_800_000_000L, principal);

        verify(profiles).getProfile(user, "AAPL", "1d", 1_800_000_000L);
    }
}
