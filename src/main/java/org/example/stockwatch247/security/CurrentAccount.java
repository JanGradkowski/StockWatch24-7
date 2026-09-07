package org.example.stockwatch247.security;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Optional;

/** Reuses the account validated for this request, never across requests. */
public final class CurrentAccount {
    private CurrentAccount() { }
    public static Optional<User> find(UserRepository users, String email) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object cached = attributes.getRequest().getAttribute(AccountSession.REQUEST_USER);
            if (cached instanceof User user && user.getEmail().equalsIgnoreCase(email)) return Optional.of(user);
        }
        return users.findByEmailIgnoreCase(email);
    }
}
