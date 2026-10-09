package org.example.stockwatch247.service;

import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Tells an account owner, at most hourly, that sign-in to their account is being throttled. */
@Service
public class LoginThrottleNotices {
    private static final Logger log = LoggerFactory.getLogger(LoginThrottleNotices.class);
    private final UserRepository users;
    private final AlertNotificationService notifications;
    private final RequestRateLimiter rateLimiter;

    public LoginThrottleNotices(UserRepository users, AlertNotificationService notifications,
                                RequestRateLimiter rateLimiter) {
        this.users = users;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
    }

    public void accountLimitReached(String normalizedEmail) {
        if (!rateLimiter.tryAcquire("login-throttle-notice:" + normalizedEmail, 1, Duration.ofHours(1))) return;
        users.findByEmailIgnoreCase(normalizedEmail).ifPresent(user -> {
            try {
                notifications.sendSecurityNotice(user, "Many failed sign-in attempts on your StockWatch account",
                        "Sign-in to your account received an unusually large number of attempts and is paused for "
                                + "browsers that have not signed in to it before. Browsers you already use can still sign in.");
            } catch (RuntimeException failure) {
                log.warn("Login throttle notice could not be queued for user {}: {}",
                        user.getId(), failure.getClass().getSimpleName());
            }
        });
    }
}
