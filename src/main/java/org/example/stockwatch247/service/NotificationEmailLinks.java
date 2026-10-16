package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Links and headers for alert and notification emails: the signed one-click unsubscribe address
 * (RFC 8058) and the account's switch that pauses all such emails. The signature names only the
 * account id, so the link keeps working after a password change and reveals no email address.
 */
@Service
public class NotificationEmailLinks {
    static final String CONTEXT = "notification-email-unsubscribe";
    public static final String MANAGE_PATH = "/settings/analysis-alerts";

    private final SecurityCryptoService crypto;
    private final UserRepository users;
    private final String baseUrl;

    public NotificationEmailLinks(SecurityCryptoService crypto, UserRepository users,
                                  @Value("${app.public-base-url:http://localhost:8080}") String baseUrl) {
        this.crypto = crypto;
        this.users = users;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String unsubscribeUrl(long userId) {
        return baseUrl + "/email/unsubscribe?u=" + userId + "&t=" + crypto.sign(Long.toString(userId), CONTEXT);
    }

    /** The recipient's account, when the address belongs to one. */
    public Optional<User> account(String recipientEmail) {
        return recipientEmail == null ? Optional.empty() : users.findByEmailIgnoreCase(recipientEmail);
    }

    public boolean verifies(String userId, String token) {
        return userId != null && userId.matches("[0-9]{1,19}") && token != null && token.length() <= 64
                && crypto.verify(userId, CONTEXT, token);
    }

    /** Pauses or resumes every alert and notification email for the account. */
    @Transactional
    public boolean setPaused(long userId, boolean paused) {
        return users.findById(userId).map(user -> {
            user.setNotificationEmailsPaused(paused);
            users.save(user);
            return true;
        }).orElse(false);
    }
}
