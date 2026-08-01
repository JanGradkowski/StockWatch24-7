package org.example.stockwatch247.service;

import jakarta.transaction.Transactional;
import org.example.stockwatch247.model.SecurityEvent;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.SecurityEventRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class AccountDeletionService {
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
        LocalDateTime deadline = LocalDateTime.now().plusDays(7);
        user.setDeletionRequestedAt(LocalDateTime.now());
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
                || user.getDeletionCancelExpiresAt().isBefore(LocalDateTime.now())) return false;
        user.setDeletionRequestedAt(null); user.setDeletionCancelExpiresAt(null); user.setDeletionCancelTokenHash(null);
        user.setSecurityVersion(user.getSecurityVersion() + 1); users.save(user);
        SecurityEvent event = new SecurityEvent(); event.setUser(user); event.setEventType("DELETION_CANCELLED");
        event.setDescription("Scheduled account deletion cancelled through the verified email link."); events.save(event);
        try { notifications.sendSecurityNotice(user, "StockWatch account deletion cancelled",
                "Your account deletion was cancelled. You can sign in again."); }
        catch (RuntimeException ignored) { System.err.println("Account deletion cancellation notice could not be sent."); }
        return true;
    }

    @Scheduled(cron = "${security.account-deletion.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void deleteExpiredAccounts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        users.findByDeletionRequestedAtLessThanEqual(cutoff).forEach(user -> {
            try { notifications.sendAccountDeletedNotice(user); }
            catch (RuntimeException ignored) { System.err.println("Final account deletion notice could not be sent."); }
            users.delete(user);
        });
    }

    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable.", e); }
    }
}
