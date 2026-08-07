package org.example.stockwatch247.service;

import jakarta.transaction.Transactional;
import org.example.stockwatch247.model.MfaRecoveryCode;
import org.example.stockwatch247.model.SecurityEvent;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.MfaRecoveryCodeRepository;
import org.example.stockwatch247.repository.SecurityEventRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AccountSecurityService {
    private final UserRepository users;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final SecurityEventRepository events;
    private final PasswordEncoder passwordEncoder;
    private final SecurityCryptoService crypto;
    private final TotpService totp;
    private final AlertNotificationService notifications;
    private final SecureRandom random = new SecureRandom();

    public AccountSecurityService(UserRepository users, MfaRecoveryCodeRepository recoveryCodes,
                                  SecurityEventRepository events, PasswordEncoder passwordEncoder,
                                  SecurityCryptoService crypto, TotpService totp,
                                  AlertNotificationService notifications) {
        this.users = users; this.recoveryCodes = recoveryCodes; this.events = events;
        this.passwordEncoder = passwordEncoder; this.crypto = crypto; this.totp = totp;
        this.notifications = notifications;
    }

    public boolean currentPasswordMatches(User user, String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        return passwordEncoder.matches(password, user.getPasswordHash());
    }

    @Transactional
    public long changePassword(Long userId, String currentPassword, String code, String newPassword,
                               PasswordSecurityCodeService codeService) {
        User user = locked(userId);
        SecurityInputValidator.requirePassword(newPassword);
        if (!currentPasswordMatches(user, currentPassword)) throw new IllegalArgumentException("Current password is incorrect.");
        if (passwordEncoder.matches(newPassword, user.getPasswordHash()))
            throw new IllegalArgumentException("Choose a password different from your current password.");
        if (!codeService.consume(userId, PasswordSecurityCodeService.CHANGE, code))
            throw new IllegalArgumentException("The security code is invalid, expired, or has too many failed attempts.");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        users.save(user);
        record(user, "PASSWORD_CHANGED", "Password changed; other sessions were signed out.");
        notice(user, "Your StockWatch password was changed", "Your password was changed and other sessions were signed out.");
        return user.getSecurityVersion();
    }

    @Transactional
    public void resetPassword(Long userId, String code, String newPassword,
                              PasswordSecurityCodeService codeService) {
        User user = locked(userId);
        SecurityInputValidator.requirePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash()))
            throw new IllegalArgumentException("Choose a password different from your current password.");
        if (!codeService.consume(userId, PasswordSecurityCodeService.RESET, code))
            throw new IllegalArgumentException("The security code is invalid, expired, or has too many failed attempts.");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        users.save(user);
        record(user, "PASSWORD_RESET", "Password reset using a verified email code.");
        notice(user, "Your StockWatch password was reset", "Your password was reset and every existing session was signed out.");
    }

    @Transactional
    public List<String> enableMfa(Long userId, String setupSecret, String code) {
        User user = locked(userId);
        if (user.isMfaEnabled()) throw new IllegalArgumentException("Authenticator verification is already enabled.");
        Long acceptedSetupStep = setupSecret == null ? null
                : totp.matchingStep(setupSecret, code, Instant.now().getEpochSecond());
        if (acceptedSetupStep == null)
            throw new IllegalArgumentException("Enter the current six-digit code from your authenticator app.");
        SecurityCryptoService.EncryptedValue encrypted = crypto.encrypt(setupSecret, context(user));
        user.setMfaSecretCiphertext(encrypted.ciphertext());
        user.setMfaSecretIv(encrypted.iv());
        user.setMfaEnabled(true);
        user.setLastAcceptedTotpStep(acceptedSetupStep);
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        users.save(user);
        List<String> rawCodes = replaceRecoveryCodes(user);
        record(user, "MFA_ENABLED", "Authenticator-app verification enabled.");
        notice(user, "Authenticator verification enabled", "Authenticator-app verification was enabled on your account.");
        return rawCodes;
    }

    @Transactional
    public long disableMfa(Long userId, String currentPassword, String factorCode) {
        User user = locked(userId);
        if (!currentPasswordMatches(user, currentPassword)) throw new IllegalArgumentException("Current password is incorrect.");
        if (!verifyFactorLocked(user, factorCode, true)) throw new IllegalArgumentException("The authenticator or recovery code is invalid.");
        user.setMfaEnabled(false);
        user.setMfaSecretCiphertext(null); user.setMfaSecretIv(null); user.setLastAcceptedTotpStep(null);
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        recoveryCodes.deleteByUserId(userId);
        users.save(user);
        record(user, "MFA_DISABLED", "Authenticator-app verification disabled.");
        notice(user, "Authenticator verification disabled", "Authenticator-app verification was disabled on your account.");
        return user.getSecurityVersion();
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(Long userId, String currentPassword, String factorCode) {
        User user = locked(userId);
        if (!currentPasswordMatches(user, currentPassword)) throw new IllegalArgumentException("Current password is incorrect.");
        if (!verifyFactorLocked(user, factorCode, false)) throw new IllegalArgumentException("The authenticator code is invalid.");
        List<String> result = replaceRecoveryCodes(user);
        record(user, "RECOVERY_CODES_REGENERATED", "New MFA recovery codes generated; previous codes revoked.");
        notice(user, "New recovery codes generated", "Your previous authenticator recovery codes were revoked.");
        return result;
    }

    @Transactional
    public boolean verifyLoginFactor(Long userId, String factorCode) {
        User user = locked(userId);
        return user.isMfaEnabled() && verifyFactorLocked(user, factorCode, true);
    }

    @Transactional
    public boolean authorizeSensitiveAction(Long userId, String currentPassword, String factorCode) {
        User user = locked(userId);
        return currentPasswordMatches(user, currentPassword)
                && (!user.isMfaEnabled() || verifyFactorLocked(user, factorCode, true));
    }

    @Transactional
    public long revokeOtherSessions(Long userId) {
        User user = locked(userId);
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        users.save(user);
        record(user, "SESSIONS_REVOKED", "All other signed-in sessions were revoked.");
        notice(user, "Other StockWatch sessions signed out", "All other signed-in sessions were revoked from Settings.");
        return user.getSecurityVersion();
    }

    @Transactional
    public void updateTheme(Long userId, String theme) {
        String normalized = "LIGHT".equalsIgnoreCase(theme) ? "LIGHT" : "DARK";
        User user = locked(userId); user.setThemePreference(normalized); users.save(user);
    }

    @Transactional
    public void updateAppearance(Long userId, String theme, String motiveColor, String correctiveColor) {
        String normalizedTheme = "LIGHT".equalsIgnoreCase(theme) ? "LIGHT" : "DARK";
        String normalizedMotive = SecurityInputValidator.requireHexColor(motiveColor);
        String normalizedCorrective = SecurityInputValidator.requireHexColor(correctiveColor);
        if (normalizedMotive.equals(normalizedCorrective)) {
            throw new IllegalArgumentException("Choose two different Elliott Wave colors.");
        }
        User user = locked(userId);
        user.setThemePreference(normalizedTheme);
        user.setElliottMotiveColor(normalizedMotive);
        user.setElliottCorrectiveColor(normalizedCorrective);
        users.save(user);
    }

    public List<SecurityEvent> recentEvents(Long userId) {
        return events.findTop10ByUserIdOrderByCreatedAtDesc(userId);
    }

    private boolean verifyFactorLocked(User user, String factorCode, boolean allowRecovery) {
        if (!user.isMfaEnabled() || factorCode == null) return false;
        String compact = factorCode.trim().replace(" ", "").replace("-", "");
        if (compact.matches("\\d{6}")) {
            String secret = crypto.decrypt(user.getMfaSecretCiphertext(), user.getMfaSecretIv(), context(user));
            Long step = totp.matchingStep(secret, compact, Instant.now().getEpochSecond());
            if (step == null || user.getLastAcceptedTotpStep() != null && step <= user.getLastAcceptedTotpStep()) return false;
            user.setLastAcceptedTotpStep(step); users.save(user); return true;
        }
        if (!allowRecovery) return false;
        String hash = hashRecovery(compact.toUpperCase());
        for (MfaRecoveryCode recovery : recoveryCodes.findByUserIdAndUsedAtIsNull(user.getId())) {
            if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                    recovery.getCodeHash().getBytes(StandardCharsets.US_ASCII))) {
                recovery.setUsedAt(LocalDateTime.now()); recoveryCodes.save(recovery); return true;
            }
        }
        return false;
    }

    private List<String> replaceRecoveryCodes(User user) {
        recoveryCodes.deleteByUserId(user.getId());
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[10]; random.nextBytes(bytes);
            String code = java.util.HexFormat.of().formatHex(bytes).toUpperCase();
            String display = code.substring(0, 10) + "-" + code.substring(10);
            MfaRecoveryCode entity = new MfaRecoveryCode(); entity.setUser(user);
            entity.setCodeHash(hashRecovery(code)); recoveryCodes.save(entity); raw.add(display);
        }
        return raw;
    }

    private String hashRecovery(String code) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable.", e); }
    }

    private User locked(Long id) { return users.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Account not found.")); }
    private String context(User user) { return "stockwatch-mfa-user:" + user.getId(); }
    private void record(User user, String type, String description) {
        SecurityEvent event = new SecurityEvent(); event.setUser(user); event.setEventType(type);
        event.setDescription(description); events.save(event);
    }
    private void notice(User user, String subject, String body) {
        try { notifications.sendSecurityNotice(user, subject, body); }
        catch (RuntimeException exception) { System.err.println("Security notification email could not be sent for event: " + subject); }
    }
}
