package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {

    @Test
    void registrationStoresOnlyTokenHashAndSendsRawTokenInHttpsLink() {
        UserRepository repository = mock(UserRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        EmailVerificationService service = new EmailVerificationService(
                repository, notifications, true, 24, 15, "https://stockwatch.example");
        User user = user(7L);

        service.registerNewUser(user);

        assertThat(user.getVerificationTokenHash()).hasSize(64);
        assertThat(user.getVerificationExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(user.getVerificationLastSentAt()).isNotNull();
        var linkCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifications).sendVerificationEmail(eq(user), linkCaptor.capture());
        String link = linkCaptor.getValue();
        assertThat(link).startsWith("https://stockwatch.example/verify-email?token=");
        assertThat(link).doesNotContain(user.getVerificationTokenHash());
    }

    @Test
    void passwordConfirmedResendHonorsCooldownWithoutRotatingToken() {
        UserRepository repository = mock(UserRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        EmailVerificationService service = new EmailVerificationService(
                repository, notifications, true, 24, 15, "https://stockwatch.example");
        User user = user(9L);
        user.setVerificationTokenHash("a".repeat(64));
        user.setVerificationLastSentAt(LocalDateTime.now().minusMinutes(2));
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(user));

        boolean sent = service.resendAfterPasswordConfirmation(9L);

        assertThat(sent).isFalse();
        assertThat(user.getVerificationTokenHash()).isEqualTo("a".repeat(64));
        verify(notifications, never()).sendVerificationEmail(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void eligiblePasswordConfirmedResendRotatesTokenUnderRowLock() {
        UserRepository repository = mock(UserRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        EmailVerificationService service = new EmailVerificationService(
                repository, notifications, true, 24, 15, "https://stockwatch.example");
        User user = user(11L);
        user.setVerificationTokenHash("b".repeat(64));
        user.setVerificationLastSentAt(LocalDateTime.now().minusMinutes(16));
        when(repository.findByIdForUpdate(11L)).thenReturn(Optional.of(user));

        boolean sent = service.resendAfterPasswordConfirmation(11L);

        assertThat(sent).isTrue();
        assertThat(user.getVerificationTokenHash()).hasSize(64).isNotEqualTo("b".repeat(64));
        verify(repository).findByIdForUpdate(11L);
        verify(notifications).sendVerificationEmail(eq(user), org.mockito.ArgumentMatchers.anyString());
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user@example.com");
        user.setVerified(false);
        return user;
    }
}
