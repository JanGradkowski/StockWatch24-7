package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class SettingsController {
    private final UserRepository users;
    private final AlertRuleRepository alertRules;
    private final AccountSecurityService security;
    private final PasswordSecurityCodeService passwordCodes;
    private final AccountDeletionService deletion;
    private final TotpService totp;
    private final RequestRateLimiter rateLimiter;

    public SettingsController(UserRepository users, AlertRuleRepository alertRules,
                              AccountSecurityService security, PasswordSecurityCodeService passwordCodes,
                              AccountDeletionService deletion, TotpService totp,
                              RequestRateLimiter rateLimiter) {
        this.users = users; this.alertRules = alertRules; this.security = security;
        this.passwordCodes = passwordCodes; this.deletion = deletion; this.totp = totp;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/settings")
    public String page(Model model, Principal principal, HttpSession session) {
        User user = current(principal);
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("user", user);
        model.addAttribute("securityEvents", security.recentEvents(user.getId()));
        Object setup = session.getAttribute(AccountSession.MFA_SETUP_SECRET);
        Object setupAt = session.getAttribute(AccountSession.MFA_SETUP_AT);
        if (setup instanceof String secret && setupAt instanceof Long started
                && started >= Instant.now().minusSeconds(600).getEpochSecond() && !user.isMfaEnabled()) {
            String uri = totp.provisioningUri(user.getEmail(), secret);
            model.addAttribute("mfaSetupSecret", secret);
            model.addAttribute("mfaQrDataUri", totp.qrDataUri(uri));
        } else {
            session.removeAttribute(AccountSession.MFA_SETUP_SECRET);
            session.removeAttribute(AccountSession.MFA_SETUP_AT);
        }
        return "settings";
    }

    @PostMapping("/settings/theme")
    public String theme(@RequestParam String theme, Principal principal) {
        security.updateTheme(current(principal).getId(), theme);
        return "redirect:/settings?themeSaved=true";
    }

    @PostMapping(path = "/api/settings/theme", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, String> themeApi(@RequestParam String theme, Principal principal) {
        security.updateTheme(current(principal).getId(), theme);
        return Map.of("theme", "light".equalsIgnoreCase(theme) ? "light" : "dark");
    }

    @PostMapping("/settings/password/code")
    public String passwordCode(@RequestParam String currentPassword, Principal principal,
                               HttpServletRequest request, RedirectAttributes redirect) {
        User user = current(principal);
        if (!security.currentPasswordMatches(user, currentPassword)) {
            redirect.addFlashAttribute("error", "Current password is incorrect.");
        } else try {
            boolean sent = passwordCodes.issue(user, PasswordSecurityCodeService.CHANGE, request.getRemoteAddr());
            redirect.addFlashAttribute(sent ? "success" : "error", sent
                    ? "A one-time code was sent to your verified email. It expires in 5 minutes."
                    : "Too many code requests. Please wait before trying again.");
        } catch (IllegalStateException exception) {
            redirect.addFlashAttribute("error", "Email delivery is unavailable. Check the SMTP configuration.");
        }
        return "redirect:/settings#password";
    }

    @PostMapping("/settings/password")
    public String changePassword(@RequestParam String currentPassword, @RequestParam String code,
                                 @RequestParam String newPassword, @RequestParam String confirmPassword,
                                 Principal principal, HttpSession session, RedirectAttributes redirect) {
        if (!newPassword.equals(confirmPassword)) {
            redirect.addFlashAttribute("error", "The new passwords do not match."); return "redirect:/settings#password";
        }
        try {
            long version = security.changePassword(current(principal).getId(), currentPassword, code, newPassword, passwordCodes);
            session.setAttribute(AccountSession.SECURITY_VERSION, version);
            redirect.addFlashAttribute("success", "Password changed. All other sessions were signed out.");
        } catch (IllegalArgumentException exception) { redirect.addFlashAttribute("error", exception.getMessage()); }
        return "redirect:/settings#password";
    }

    @PostMapping("/settings/mfa/start")
    public String startMfa(@RequestParam String currentPassword, Principal principal,
                           HttpSession session, RedirectAttributes redirect) {
        User user = current(principal);
        if (!security.currentPasswordMatches(user, currentPassword)) {
            redirect.addFlashAttribute("error", "Current password is incorrect.");
        } else if (user.isMfaEnabled()) {
            redirect.addFlashAttribute("error", "Authenticator verification is already enabled.");
        } else {
            session.setAttribute(AccountSession.MFA_SETUP_SECRET, totp.newSecret());
            session.setAttribute(AccountSession.MFA_SETUP_AT, Instant.now().getEpochSecond());
            redirect.addFlashAttribute("success", "Scan the QR code, then enter the current authenticator code.");
        }
        return "redirect:/settings#mfa";
    }

    @PostMapping("/settings/mfa/confirm")
    public String confirmMfa(@RequestParam String code, Principal principal, HttpSession session,
                             HttpServletRequest request,
                             RedirectAttributes redirect) {
        Object secret = session.getAttribute(AccountSession.MFA_SETUP_SECRET);
        Object started = session.getAttribute(AccountSession.MFA_SETUP_AT);
        if (!(secret instanceof String rawSecret) || !(started instanceof Long at)
                || at < Instant.now().minusSeconds(600).getEpochSecond()) {
            redirect.addFlashAttribute("error", "Authenticator setup expired. Start again.");
            return "redirect:/settings#mfa";
        }
        Long userId = current(principal).getId();
        if (!allowFactorAttempt(userId, request)) {
            redirect.addFlashAttribute("error", "Too many verification attempts. Please wait before trying again.");
            return "redirect:/settings#mfa";
        }
        try {
            List<String> recoveryCodes = security.enableMfa(userId, rawSecret, code);
            session.removeAttribute(AccountSession.MFA_SETUP_SECRET); session.removeAttribute(AccountSession.MFA_SETUP_AT);
            User refreshed = current(principal);
            session.setAttribute(AccountSession.SECURITY_VERSION, refreshed.getSecurityVersion());
            redirect.addFlashAttribute("recoveryCodes", recoveryCodes);
            redirect.addFlashAttribute("success", "Authenticator verification is enabled. Save your recovery codes now.");
        } catch (IllegalArgumentException exception) { redirect.addFlashAttribute("error", exception.getMessage()); }
        return "redirect:/settings#mfa";
    }

    @PostMapping("/settings/mfa/disable")
    public String disableMfa(@RequestParam String currentPassword, @RequestParam String code,
                             Principal principal, HttpSession session, HttpServletRequest request,
                             RedirectAttributes redirect) {
        Long userId = current(principal).getId();
        if (!allowFactorAttempt(userId, request)) {
            redirect.addFlashAttribute("error", "Too many verification attempts. Please wait before trying again.");
            return "redirect:/settings#mfa";
        }
        try {
            long version = security.disableMfa(userId, currentPassword, code);
            session.setAttribute(AccountSession.SECURITY_VERSION, version);
            redirect.addFlashAttribute("success", "Authenticator verification was disabled and other sessions were signed out.");
        } catch (IllegalArgumentException exception) { redirect.addFlashAttribute("error", exception.getMessage()); }
        return "redirect:/settings#mfa";
    }

    @PostMapping("/settings/mfa/recovery-codes")
    public String recoveryCodes(@RequestParam String currentPassword, @RequestParam String code,
                                Principal principal, HttpServletRequest request, RedirectAttributes redirect) {
        Long userId = current(principal).getId();
        if (!allowFactorAttempt(userId, request)) {
            redirect.addFlashAttribute("error", "Too many verification attempts. Please wait before trying again.");
            return "redirect:/settings#mfa";
        }
        try {
            redirect.addFlashAttribute("recoveryCodes",
                    security.regenerateRecoveryCodes(userId, currentPassword, code));
            redirect.addFlashAttribute("success", "New recovery codes generated. Previous codes no longer work.");
        } catch (IllegalArgumentException exception) { redirect.addFlashAttribute("error", exception.getMessage()); }
        return "redirect:/settings#mfa";
    }

    @PostMapping("/settings/sessions/revoke")
    public String revokeSessions(Principal principal, HttpSession session, RedirectAttributes redirect) {
        long version = security.revokeOtherSessions(current(principal).getId());
        session.setAttribute(AccountSession.SECURITY_VERSION, version);
        redirect.addFlashAttribute("success", "All other sessions were signed out.");
        return "redirect:/settings#sessions";
    }

    @PostMapping("/settings/delete")
    public String delete(@RequestParam String confirmation, @RequestParam String currentPassword,
                         @RequestParam(required = false) String code, Principal principal,
                         HttpSession session, HttpServletRequest request, RedirectAttributes redirect) {
        User user = current(principal);
        if (!"DELETE".equals(confirmation)) {
            redirect.addFlashAttribute("error", "Type DELETE exactly to confirm account deletion.");
            return "redirect:/settings#danger";
        }
        if (!allowFactorAttempt(user.getId(), request)) {
            redirect.addFlashAttribute("error", "Too many verification attempts. Please wait before trying again.");
            return "redirect:/settings#danger";
        }
        if (!security.authorizeSensitiveAction(user.getId(), currentPassword, code)) {
            redirect.addFlashAttribute("error", user.isMfaEnabled()
                    ? "The password or authenticator/recovery code is invalid." : "Current password is incorrect.");
            return "redirect:/settings#danger";
        }
        try { deletion.schedule(user.getId()); }
        catch (IllegalStateException exception) {
            redirect.addFlashAttribute("error", "The confirmation email could not be sent, so deletion was not scheduled.");
            return "redirect:/settings#danger";
        }
        session.invalidate();
        return "redirect:/login?deletionScheduled=true";
    }

    @GetMapping(value = "/settings/export", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> export(Principal principal) {
        User user = current(principal);
        List<Map<String, Object>> rules = alertRules
                .findByUserAndIsActiveTrueOrderByStockAsset_TickerSymbolAscIntervalAscPatternFamilyAscTradeSignalAsc(user)
                .stream().map(this::ruleExport).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", Instant.now().toString()); data.put("email", user.getEmail());
        data.put("firstName", user.getFirstName()); data.put("lastName", user.getLastName());
        data.put("createdAt", user.getCreatedAt()); data.put("theme", user.getThemePreference());
        data.put("mfaEnabled", user.isMfaEnabled()); data.put("activeAlertRules", rules);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=stockwatch-account-data.json").body(data);
    }

    private Map<String, Object> ruleExport(AlertRule rule) {
        Map<String, Object> item = new LinkedHashMap<>(); item.put("symbol", rule.getStockAsset().getTickerSymbol());
        item.put("interval", rule.getInterval()); item.put("family", rule.getPatternFamily());
        item.put("signal", rule.getTradeSignal()); item.put("createdAt", rule.getCreatedAt()); return item;
    }
    private User current(Principal principal) {
        return users.findByEmailIgnoreCase(principal.getName()).orElseThrow(() -> new IllegalArgumentException("Account not found."));
    }
    private boolean allowFactorAttempt(Long userId, HttpServletRequest request) {
        String client = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        return rateLimiter.tryAcquire("settings-factor:user:" + userId, 8, Duration.ofMinutes(10))
                && rateLimiter.tryAcquire("settings-factor:client:" + client, 20, Duration.ofMinutes(10));
    }
}
