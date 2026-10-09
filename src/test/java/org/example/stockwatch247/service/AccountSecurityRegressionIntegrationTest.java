package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.PasswordSecurityCode;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.repository.PasswordSecurityCodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
class AccountSecurityRegressionIntegrationTest {
    @Autowired AccountSecurityService security;
    @Autowired PasswordSecurityCodeService codes;
    @Autowired PasswordSecurityCodeRepository challenges;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SecurityCryptoService crypto;
    @MockitoBean AlertNotificationService notifications;
    @MockitoBean TotpService totp;
    private User account;

    @BeforeEach void createAccount() {
        account = new User(); account.setEmail("review-" + UUID.randomUUID() + "@example.com");
        account.setFirstName("Review"); account.setLastName("Test"); account.setVerified(true);
        account.setPasswordHash(encoder.encode("Old-password-for-review42!"));
        account = users.saveAndFlush(account);
    }
    @AfterEach void removeAccount() { users.deleteById(account.getId()); }

    @Test void rejectedPasswordCodesCommitTheirAttemptCountAndStayLockedAfterFiveFailures() {
        PasswordSecurityCode challenge = new PasswordSecurityCode(); challenge.setUser(account);
        challenge.setPurpose(PasswordSecurityCodeService.RESET); challenge.setCodeHash(encoder.encode("12345678"));
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(5)); challenge.setLastSentAt(LocalDateTime.now());
        challenge = challenges.saveAndFlush(challenge);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(security.resetPassword(account.getId(), "00000000", "New-password-for-review42!", codes).successful()).isFalse();
            assertThat(challenges.findById(challenge.getId()).orElseThrow().getFailedAttempts()).isEqualTo(attempt);
        }
        assertThat(security.resetPassword(account.getId(), "12345678", "New-password-for-review42!", codes).successful()).isFalse();
        assertThat(users.findById(account.getId()).orElseThrow().getSecurityVersion()).isZero();
    }

    @Test void aWrongCodeWithTheCurrentPasswordGetsTheGenericAnswerAndDoesNotRevealThePassword() {
        PasswordSecurityCode challenge = resetCode("12345678");

        assertThat(security.resetPassword(account.getId(), "00000000", "Old-password-for-review42!", codes).successful()).isFalse();

        assertThat(challenges.findById(challenge.getId()).orElseThrow().getFailedAttempts()).isEqualTo(1);
    }

    @Test void reusingTheCurrentPasswordWithAValidCodeExplainsWhyAndKeepsTheCodeForAnotherTry() {
        PasswordSecurityCode challenge = resetCode("12345678");

        assertThatThrownBy(() -> security.resetPassword(account.getId(), "12345678", "Old-password-for-review42!", codes))
                .isInstanceOf(UserInputException.class)
                .hasMessageContaining("different from your current password");
        assertThat(challenges.findById(challenge.getId())).isPresent();

        assertThat(security.resetPassword(account.getId(), "12345678", "New-password-for-review42!", codes).successful()).isTrue();
        assertThat(challenges.findById(challenge.getId())).isEmpty();
        assertThat(security.resetPassword(account.getId(), "12345678", "Another-password-for-review42!", codes).successful()).isFalse();
    }

    @Test void aPasswordThatBreaksThePolicyIsReportedBeforeTheCodeIsTouched() {
        PasswordSecurityCode challenge = resetCode("12345678");

        assertThatThrownBy(() -> security.resetPassword(account.getId(), "12345678", "short", codes))
                .isInstanceOf(UserInputException.class)
                .hasMessageContaining("at least 15 characters");
        assertThat(challenges.findById(challenge.getId()).orElseThrow().getFailedAttempts()).isZero();
    }

    private PasswordSecurityCode resetCode(String rawCode) {
        PasswordSecurityCode challenge = new PasswordSecurityCode(); challenge.setUser(account);
        challenge.setPurpose(PasswordSecurityCodeService.RESET); challenge.setCodeHash(encoder.encode(rawCode));
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(5)); challenge.setLastSentAt(LocalDateTime.now());
        return challenges.saveAndFlush(challenge);
    }

    @Test void pendingMfaIsBoundToAccountVersionAndStatusAndAnAcceptedStepCannotReplay() {
        long now = Instant.now().getEpochSecond();
        var protectedSecret = crypto.encrypt("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", "stockwatch-mfa-user:" + account.getId());
        account.setMfaEnabled(true); account.setMfaSecretCiphertext(protectedSecret.ciphertext()); account.setMfaSecretIv(protectedSecret.iv());
        account.setSecurityVersion(4); users.saveAndFlush(account);
        when(totp.matchingStep(anyString(), anyString(), anyLong())).thenReturn(now / 30);
        assertThat(security.verifyLoginFactor(account.getId(), 3, now, "123456")).isEmpty();
        assertThat(security.verifyLoginFactor(account.getId(), 4, now - 300, "123456")).isEmpty();
        assertThat(security.verifyLoginFactor(account.getId(), 4, now + 60, "123456")).isEmpty();
        account.setDeletionRequestedAt(LocalDateTime.now()); users.saveAndFlush(account);
        assertThat(security.verifyLoginFactor(account.getId(), 4, now, "123456")).isEmpty();
        account.setDeletionRequestedAt(null); account.setVerified(false); users.saveAndFlush(account);
        assertThat(security.verifyLoginFactor(account.getId(), 4, now, "123456")).isEmpty();
        verifyNoInteractions(totp);
        account.setVerified(true); users.saveAndFlush(account);
        assertThat(security.verifyLoginFactor(account.getId(), 4, now, "123456")).isPresent();
        assertThat(security.verifyLoginFactor(account.getId(), 4, now, "123456")).isEmpty();
    }

    @Test void twentyWrongCodesInADayPauseCodeEntryRecordTheEventAndTellTheOwner() {
        long now = Instant.now().getEpochSecond();
        var protectedSecret = crypto.encrypt("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", "stockwatch-mfa-user:" + account.getId());
        account.setMfaEnabled(true); account.setMfaSecretCiphertext(protectedSecret.ciphertext()); account.setMfaSecretIv(protectedSecret.iv());
        users.saveAndFlush(account);
        when(totp.matchingStep(anyString(), eq("000000"), anyLong())).thenReturn(null);
        when(totp.matchingStep(anyString(), eq("123456"), anyLong())).thenReturn(now / 30);

        for (int attempt = 1; attempt < AccountSecurityService.MFA_FAILURES_BEFORE_PAUSE; attempt++) {
            assertThat(security.verifyLoginFactor(account.getId(), 0, now, "000000")).isEmpty();
        }
        assertThat(security.loginFactorPaused(account.getId())).isFalse();
        assertThat(security.verifyLoginFactor(account.getId(), 0, now, "000000")).isEmpty();

        assertThat(security.loginFactorPaused(account.getId())).isTrue();
        assertThat(security.verifyLoginFactor(account.getId(), 0, now, "123456")).isEmpty();
        assertThat(security.recentEvents(account.getId())).anyMatch(event -> "MFA_PAUSED".equals(event.getEventType()));
        verify(notifications).sendSecurityNotice(any(User.class), eq("Repeated wrong authenticator codes"), anyString());
    }
}
