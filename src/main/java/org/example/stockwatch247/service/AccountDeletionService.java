package org.example.stockwatch247.service;

import jakarta.transaction.Transactional;
import org.example.stockwatch247.model.SecurityEvent;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.SecurityEventRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class AccountDeletionService {
    private java.time.Clock clock = java.time.Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired
    void setClock(java.time.Clock clock) { this.clock = clock; }

    private final UserRepository users;
    private final SecurityEventRepository events;
    private final AlertNotificationService notifications;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public AccountDeletionService(UserRepository users, SecurityEventRepository events,
                                  AlertNotificationService notifications,
                                  @Value("${app.public-base-url:http://localhost:8080}") String baseUrl) {
        this.users = users; this.events = events; this.notifications = notifications;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public void schedule(Long userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new IllegalArgumentException("Account not found."));
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime deadline = LocalDateTime.now(clock).plusDays(7);
        user.setDeletionRequestedAt(LocalDateTime.now(clock));
        user.setDeletionCancelExpiresAt(deadline);
        user.setDeletionCancelTokenHash(hash(token));
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        users.save(user);
        SecurityEvent event = new SecurityEvent(); event.setUser(user); event.setEventType("DELETION_SCHEDULED");
        event.setDescription("Account scheduled for permanent deletion in 7 days."); events.save(event);
        notifications.sendAccountDeletionNotice(user, baseUrl + "/cancel-account-deletion?token=" + token);
    }

    @Transactional
    public boolean cancel(String rawToken) {
        if (rawToken == null || rawToken.length() < 40 || rawToken.length() > 80) return false;
        User user = users.findByDeletionCancelTokenHash(hash(rawToken)).orElse(null);
        if (user == null || user.getDeletionCancelExpiresAt() == null
                || user.getDeletionCancelExpiresAt().isBefore(LocalDateTime.now(clock))) return false;
        user.setDeletionRequestedAt(null); user.setDeletionCancelExpiresAt(null); user.setDeletionCancelTokenHash(null);
        user.setSecurityVersion(user.getSecurityVersion() + 1); users.save(user);
        SecurityEvent event = new SecurityEvent(); event.setUser(user); event.setEventType("DELETION_CANCELLED");
        event.setDescription("Scheduled account deletion cancelled through the verified email link."); events.save(event);
        try { notifications.sendSecurityNotice(user, "StockWatch account deletion cancelled",
                "Your account deletion was cancelled. You can sign in again."); }
        catch (RuntimeException ignored) { log.warn("Account deletion cancellation notice could not be sent."); }
        return true;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccountDeletionService.class);
    private static final int DELETIONS_PER_RUN = 200;
    private TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Each account is deleted in its own transaction: one account whose data cannot be removed is
     * logged and skipped, instead of rolling back the whole batch and blocking every later run.
     */
    @Scheduled(cron = "${security.account-deletion.cleanup-cron:0 30 3 * * *}")
    public void deleteExpiredAccounts() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(7);
        for (Long userId : users.findExpiredDeletionIds(cutoff, DELETIONS_PER_RUN)) {
            try {
                if (transactions == null) deleteIfStillExpired(userId, cutoff);
                else transactions.executeWithoutResult(status -> deleteIfStillExpired(userId, cutoff));
            } catch (RuntimeException failure) {
                log.error("Scheduled deletion of account {} failed; it will be retried on the next run.", userId, failure);
            }
        }
    }

    private void deleteIfStillExpired(Long userId, LocalDateTime cutoff) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        // Re-checked under the lock: the owner may have cancelled since the ids were read.
        if (user == null || user.getDeletionRequestedAt() == null || user.getDeletionRequestedAt().isAfter(cutoff))
            return;
        try { notifications.sendAccountDeletedNotice(user); }
        catch (RuntimeException ignored) { log.warn("Final account deletion notice could not be queued for account {}.", userId); }
        users.delete(user);
        users.flush();
    }

    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable.", e); }
    }
}
