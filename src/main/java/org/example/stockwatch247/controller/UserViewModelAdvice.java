package org.example.stockwatch247.controller;

import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.model.User;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import java.security.Principal;

@ControllerAdvice
public class UserViewModelAdvice {
    private final UserRepository users;
    public UserViewModelAdvice(UserRepository users) { this.users = users; }

    @ModelAttribute
    public void accountPreferences(Principal principal, Model model) {
        User user = principal == null ? null : users.findByEmailIgnoreCase(principal.getName()).orElse(null);
        model.addAttribute("accountTheme", user == null ? null : user.getThemePreference().toLowerCase());
        model.addAttribute("elliottMotiveColor", user == null
                ? User.DEFAULT_ELLIOTT_MOTIVE_COLOR : user.getElliottMotiveColor());
        model.addAttribute("elliottCorrectiveColor", user == null
                ? User.DEFAULT_ELLIOTT_CORRECTIVE_COLOR : user.getElliottCorrectiveColor());
    }
}
