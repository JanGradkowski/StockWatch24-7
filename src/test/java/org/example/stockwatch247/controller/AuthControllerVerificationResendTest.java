package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerVerificationResendTest {

    @Test
    void resendRequiresTheExistingAccountPasswordAndKeepsGenericResponse() {
        UserRepository users = mock(UserRepository.class);
        EmailVerificationService verification = mock(EmailVerificationService.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = new User();
        user.setId(42L);
        user.setEmail("owner@example.com");
        user.setPasswordHash(encoder.encode("correct horse battery staple"));
        user.setVerified(false);
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(user));
        AuthController controller = new AuthController(
                users, mock(AlertRuleService.class), encoder, verification);

        String wrongPassword = controller.processVerificationResend(
                "owner@example.com", "incorrect password value");
        verify(verification, never()).resendAfterPasswordConfirmation(42L);

        String correctPassword = controller.processVerificationResend(
                "owner@example.com", "correct horse battery staple");
        verify(verification).resendAfterPasswordConfirmation(42L);
        assertThat(wrongPassword).isEqualTo(correctPassword)
                .isEqualTo("redirect:/login?verificationResendRequested=true");
    }
}
