package org.example.stockwatch247.service;

import jakarta.transaction.Transactional;
import org.example.stockwatch247.model.PasswordSecurityCode;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.PasswordSecurityCodeRepository;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class PasswordSecurityCodeService {
    private java.time.Clock clock = java.time.Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired
    void setClock(java.time.Clock clock) { this.clock = clock; }

    public static final String CHANGE = "PASSWORD_CHANGE";
    public static final String RESET = "PASSWORD_RESET";
    private final PasswordSecurityCodeRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AlertNotificationService notifications;
    private final RequestRateLimiter rateLimiter;
    private final SecureRandom random = new SecureRandom();

    public PasswordSecurityCodeService(PasswordSecurityCodeRepository repository,
                                       PasswordEncoder passwordEncoder,
                                       AlertNotificationService notifications,
                                       RequestRateLimiter rateLimiter) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public boolean issue(User user, String purpose, String clientKey) {
        if (!validPurpose(purpose)) throw new IllegalArgumentException("Unsupported security-code purpose.");
        if (!rateLimiter.tryAcquire("password-code:account:" + user.getId() + ":" + purpose, 3, Duration.ofMinutes(15))
                || !rateLimiter.tryAcquire("password-code:client:" + clientKey, 10, Duration.ofMinutes(15))) return false;
        String rawCode = String.format(java.util.Locale.ROOT, "%08d", random.nextInt(100_000_000));
        LocalDateTime now = LocalDateTime.now(clock);
        PasswordSecurityCode challenge = repository.findByUserIdAndPurpose(user.getId(), purpose)
                .orElseGet(PasswordSecurityCode::new);
        challenge.setUser(user);
        challenge.setPurpose(purpose);
        challenge.setCodeHash(passwordEncoder.encode(rawCode));
        challenge.setExpiresAt(now.plusMinutes(5));
        challenge.setLastSentAt(now);
        challenge.setFailedAttempts(0);
        repository.save(challenge);
        try {
            notifications.sendPasswordSecurityCode(user, rawCode, RESET.equals(purpose));
            return true;
        } catch (RuntimeException exception) {
            repository.delete(challenge);
            throw exception;
        }
    }

    @Transactional
    public boolean consume(Long userId, String purpose, String rawCode) {
        PasswordSecurityCode challenge = repository.findForUpdate(userId, purpose).orElse(null);
        if (challenge == null || !challenge.getExpiresAt().isAfter(LocalDateTime.now(clock))
                || challenge.getFailedAttempts() >= 5) return false;
        if (rawCode == null || !rawCode.trim().matches("[0-9]{8}")
                || !passwordEncoder.matches(rawCode.trim(), challenge.getCodeHash())) {
            challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
            repository.save(challenge);
            return false;
        }
        repository.delete(challenge);
        return true;
    }

    public java.util.Optional<java.time.Instant> activeChangeCodeExpiry(Long userId) {
        return repository.findByUserIdAndPurpose(userId, CHANGE)
                .filter(code -> code.getFailedAttempts() < 5
                        && code.getExpiresAt().isAfter(LocalDateTime.now(clock)))
                .map(code -> code.getExpiresAt().atZone(clock.getZone()).toInstant());
    }

    private boolean validPurpose(String purpose) { return CHANGE.equals(purpose) || RESET.equals(purpose); }
}
