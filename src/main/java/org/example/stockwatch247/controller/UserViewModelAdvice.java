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
    public void accountPreferences(Principal principal, Model model, jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")) return;
        User user = principal == null ? null : org.example.stockwatch247.security.CurrentAccount.find(users, principal.getName()).orElse(null);
        model.addAttribute("showAppNavigation", user != null);
        if (user != null) model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("navigationSection", navigationSection(request.getRequestURI().substring(request.getContextPath().length())));
        model.addAttribute("accountTheme", user == null ? null : user.getThemePreference().toLowerCase());
        model.addAttribute("elliottMotiveColor", user == null
                ? User.DEFAULT_ELLIOTT_MOTIVE_COLOR : user.getElliottMotiveColor());
        model.addAttribute("elliottCorrectiveColor", user == null
                ? User.DEFAULT_ELLIOTT_CORRECTIVE_COLOR : user.getElliottCorrectiveColor());
        model.addAttribute("elliottSubwaveColor", user == null
                ? User.DEFAULT_ELLIOTT_SUBWAVE_COLOR : user.getElliottSubwaveColor());
        model.addAttribute("harmonicFormationColor", user == null
                ? User.DEFAULT_HARMONIC_FORMATION_COLOR : user.getHarmonicFormationColor());
    }

    static String navigationSection(String path) {
        if (path.equals("/settings") || path.startsWith("/settings/")) return "settings";
        if (path.equals("/virtual-trades") || path.startsWith("/virtual-trades/")) return "demo";
        if (path.equals("/activity-signals") || path.startsWith("/activity-signals/")) return "alerts";
        if (path.equals("/about")) return "help";
        if (path.equals("/signals") || path.startsWith("/alerts/")
                || path.matches("/stock/[^/]+/.+")) return "signals";
        return "home";
    }
}
