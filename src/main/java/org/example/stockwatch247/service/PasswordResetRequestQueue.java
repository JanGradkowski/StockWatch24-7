package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Accepts forgot-password requests in constant time. Whether an account exists, is verified,
 * or receives a code is decided later by the worker, never while the browser waits.
 */
@Service
public class PasswordResetRequestQueue {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetRequestQueue.class);
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordSecurityCodeService codes;

    public PasswordResetRequestQueue(JdbcTemplate jdbc, UserRepository users, PasswordSecurityCodeService codes) {
        this.jdbc = jdbc;
        this.users = users;
        this.codes = codes;
    }

    public void enqueue(String normalizedEmail, String clientKey) {
        jdbc.update("insert into password_reset_requests(email, client_key) values (?, ?)",
                normalizedEmail, clientKey == null ? "unknown" : clientKey.substring(0, Math.min(64, clientKey.length())));
    }

    @Scheduled(fixedDelayString = "${security.password-reset.worker-delay-ms:3000}",
            initialDelayString = "${security.password-reset.worker-initial-delay-ms:10000}")
    public void processPending() {
        for (int processed = 0; processed < 25; processed++) {
            if (!processNext()) return;
        }
    }

    /** Claiming deletes the row atomically, so concurrent workers never issue two codes for one request. */
    public boolean processNext() {
        record Request(long id, String email, String clientKey) { }
        List<Request> claimed = jdbc.query("""
                delete from password_reset_requests
                where id = (select id from password_reset_requests order by created_at, id
                            for update skip locked limit 1)
                returning id, email, client_key
                """, (row, number) -> new Request(row.getLong(1), row.getString(2), row.getString(3)));
        if (claimed.isEmpty()) return false;
        Request request = claimed.getFirst();
        User user = users.findByEmailIgnoreCase(request.email()).orElse(null);
        if (user != null && user.isVerified() && user.getDeletionRequestedAt() == null) {
            try {
                codes.issue(user, PasswordSecurityCodeService.RESET, request.clientKey());
            } catch (RuntimeException failure) {
                log.warn("Password reset code could not be issued for request {}: {}",
                        request.id(), failure.getClass().getSimpleName());
            }
        }
        return true;
    }

    @Scheduled(cron = "${security.password-reset.cleanup-cron:0 50 * * * *}")
    public void removeAbandoned() {
        jdbc.update("delete from password_reset_requests where created_at < current_timestamp - interval '1 hour'");
    }
}
