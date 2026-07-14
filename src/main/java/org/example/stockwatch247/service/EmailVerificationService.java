package org.example.stockwatch247.service;

import jakarta.transaction.Transactional;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class EmailVerificationService {
    private final UserRepository userRepository;
    private final AlertNotificationService notificationService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean required;
    private final int ttlHours;
    private final int resendCooldownMinutes;
    private final String publicBaseUrl;

    public EmailVerificationService(UserRepository userRepository,
                                    AlertNotificationService notificationService,
                                     @Value("${security.email-verification.required:true}") boolean required,
                                     @Value("${security.email-verification.ttl-hours:24}") int ttlHours,
                                     @Value("${security.email-verification.resend-cooldown-minutes:15}") int resendCooldownMinutes,
                                     @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.required = required;
        this.ttlHours = Math.max(1, ttlHours);
        this.resendCooldownMinutes = Math.max(1, resendCooldownMinutes);
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    }

    @Transactional
    public void registerNewUser(User user) {
        if (!required) {
            user.setVerified(true);
            user.setVerificationTokenHash(null);
            user.setVerificationExpiresAt(null);
            user.setVerificationLastSentAt(null);
            userRepository.save(user);
            return;
        }
        issueAndSend(user, LocalDateTime.now());
    }

    @Transactional
    public boolean resendAfterPasswordConfirmation(Long userId) {
        if (!required) {
            return false;
        }
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.isVerified()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getVerificationLastSentAt() != null
                && user.getVerificationLastSentAt().isAfter(now.minusMinutes(resendCooldownMinutes))) {
            return false;
        }
        issueAndSend(user, now);
        return true;
    }

    @Transactional
    public boolean verify(String rawToken) {
        if (rawToken == null || rawToken.length() < 32 || rawToken.length() > 128) {
            return false;
        }
        User user = userRepository.findByVerificationTokenHash(hash(rawToken)).orElse(null);
        if (user == null || user.getVerificationExpiresAt() == null
                || user.getVerificationExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }
        user.setVerified(true);
        user.setVerificationTokenHash(null);
        user.setVerificationExpiresAt(null);
        user.setVerificationLastSentAt(null);
        userRepository.save(user);
        return true;
    }

    public boolean isRequired() {
        return required;
    }

    private void issueAndSend(User user, LocalDateTime now) {
        String rawToken = newToken();
        user.setVerified(false);
        user.setVerificationTokenHash(hash(rawToken));
        user.setVerificationExpiresAt(now.plusHours(ttlHours));
        user.setVerificationLastSentAt(now);
        userRepository.save(user);
        notificationService.sendVerificationEmail(
                user,
                publicBaseUrl + "/verify-email?token=" + rawToken);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("A public application URL is required for email verification.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
