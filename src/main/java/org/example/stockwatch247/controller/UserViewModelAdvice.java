package org.example.stockwatch247.controller;

import org.example.stockwatch247.repository.UserRepository;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import java.security.Principal;

@ControllerAdvice
public class UserViewModelAdvice {
    private final UserRepository users;
    public UserViewModelAdvice(UserRepository users) { this.users = users; }

    @ModelAttribute("accountTheme")
    public String accountTheme(Principal principal) {
        if (principal == null) return null;
        return users.findByEmailIgnoreCase(principal.getName())
                .map(user -> user.getThemePreference().toLowerCase()).orElse(null);
    }
}
