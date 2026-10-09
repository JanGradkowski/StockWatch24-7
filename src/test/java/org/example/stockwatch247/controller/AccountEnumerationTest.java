package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.AccountDeletionService;
import org.example.stockwatch247.service.AccountSecurityService;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.EmailVerificationService;
import org.example.stockwatch247.service.PasswordResetRequestQueue;
import org.example.stockwatch247.service.PasswordSecurityCodeService;
import org.example.stockwatch247.service.congress.CongressionalActivityService;
import org.example.stockwatch247.service.insider.InsiderActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountEnumerationTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final EmailVerificationService verification = mock(EmailVerificationService.class);

    private AuthController auth() {
        when(encoder.encode(anyString())).thenReturn("hash");
        return new AuthController(users, mock(AlertRuleService.class), mock(CongressionalActivityService.class),
                mock(InsiderActivityService.class), encoder, verification);
    }

    private String signup(AuthController controller) {
        return controller.processSignup("Ada", "Lovelace", "ada@example.com", "a-long-enough-password",
                "#2563EB", "#DC2626", new ExtendedModelMap());
    }

    @Test
    void signupForAnExistingAddressDoesTheSameHashingWorkAndAnswersIdentically() {
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.empty());
        String fresh = signup(auth());
        User existing = new User();
        existing.setId(4L);
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(existing));
        String duplicate = signup(auth());

        assertThat(duplicate).isEqualTo(fresh);
        verify(encoder, times(2)).encode("a-long-enough-password");
        verify(verification, times(1)).registerNewUser(any());
    }

    @Test
    void concurrentDuplicateSignupIsTreatedAsAnExistingAccount() {
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("duplicate email")).when(verification).registerNewUser(any());
        when(verification.isRequired()).thenReturn(true);

        assertThat(signup(auth())).isEqualTo("redirect:/login?verificationSent=true");
    }

    @Test
    void forgotPasswordOnlyQueuesTheRequestAndNeverLooksUpTheAccountInline() {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        PasswordSecurityCodeService codes = mock(PasswordSecurityCodeService.class);
        var controller = new PasswordRecoveryController(users, codes, mock(AccountSecurityService.class),
                mock(AccountDeletionService.class), limiter);
        PasswordResetRequestQueue queue = mock(PasswordResetRequestQueue.class);
        ReflectionTestUtils.setField(controller, "resetQueue", queue);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");

        assertThat(controller.request("Someone@Example.com", request)).isEqualTo("redirect:/reset-password?requested=true");

        verify(queue).enqueue("someone@example.com", "203.0.113.5");
        verify(users, never()).findByEmailIgnoreCase(anyString());
        verify(codes, never()).issue(any(), anyString(), anyString());
    }
}
