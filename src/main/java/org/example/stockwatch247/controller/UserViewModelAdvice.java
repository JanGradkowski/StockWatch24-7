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
    private org.example.stockwatch247.service.UnreadCountService unreadCounts;
    public UserViewModelAdvice(UserRepository users) { this.users = users; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureUnreadCounts(org.example.stockwatch247.service.UnreadCountService counts) { this.unreadCounts = counts; }

    @ModelAttribute
    public void accountPreferences(Principal principal, Model model, jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")) return;
        User user = principal == null ? null : org.example.stockwatch247.security.CurrentAccount.find(users, principal.getName()).orElse(null);
        model.addAttribute("showAppNavigation", user != null);
        if (user != null) {
            model.addAttribute("firstName", user.getFirstName());
            // Sidebar badges: what is waiting on each page, without opening it.
            model.addAttribute("navigationUnread", unreadCounts == null ? null : unreadCounts.counts(user));
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // One custom alert's triggers live in All signals but belong to the Custom alerts section.
        model.addAttribute("navigationSection", path.equals("/signals") && request.getParameter("customAlertId") != null
                ? "custom" : navigationSection(path));
        model.addAttribute("accountTheme", user == null ? null : user.getThemePreference().toLowerCase());
        model.addAttribute("learningMode", user != null && user.isLearningMode());
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
        if (path.equals("/watchlists") || path.startsWith("/watchlists/")) return "lists";
        if (path.equals("/custom-alerts") || path.startsWith("/custom-alerts/")) return "custom";
        if (path.equals("/technical-watchlist") || path.startsWith("/technical-outlook/changes/")
                || path.matches("/stock/[^/]+/technical-outlook")) return "watchlist";
        if (path.equals("/settings") || path.startsWith("/settings/")) return "settings";
        if (path.equals("/virtual-trades") || path.startsWith("/virtual-trades/")) return "demo";
        if (path.equals("/ticker-alerts") || path.equals("/activity-signals")
                || path.startsWith("/activity-signals/")) return "alerts";
        if (path.equals("/about")) return "help";
        if (path.equals("/signals") || path.startsWith("/alerts/")
                || path.matches("/stock/[^/]+/.+")) return "signals";
        // A stock workspace is reached from search, not from a sidebar section.
        if (path.matches("/stock/[^/]+")) return "stock";
        return "home";
    }
}
