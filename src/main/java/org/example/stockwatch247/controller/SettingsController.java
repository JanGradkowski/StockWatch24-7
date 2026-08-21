package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TimeInterval;
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
import org.springframework.util.MultiValueMap;

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
    private final AnalysisPreferencesService analysisPreferences;
    private final SignalScoringPreferencesService scoringPreferences;
    private final CandlestickPatternPreferencesService candlestickPatternPreferences;
    private final ElliottWavePreferencesService elliottWavePreferences;
    private final HarmonicPatternPreferencesService harmonicPatternPreferences;

    public SettingsController(UserRepository users, AlertRuleRepository alertRules,
                              AccountSecurityService security, PasswordSecurityCodeService passwordCodes,
                              AccountDeletionService deletion, TotpService totp,
                              RequestRateLimiter rateLimiter,
                              AnalysisPreferencesService analysisPreferences,
                              SignalScoringPreferencesService scoringPreferences,
                              CandlestickPatternPreferencesService candlestickPatternPreferences,
                              ElliottWavePreferencesService elliottWavePreferences,
                              HarmonicPatternPreferencesService harmonicPatternPreferences) {
        this.users = users; this.alertRules = alertRules; this.security = security;
        this.passwordCodes = passwordCodes; this.deletion = deletion; this.totp = totp;
        this.rateLimiter = rateLimiter;
        this.analysisPreferences = analysisPreferences;
        this.scoringPreferences = scoringPreferences;
        this.candlestickPatternPreferences = candlestickPatternPreferences;
        this.elliottWavePreferences = elliottWavePreferences;
        this.harmonicPatternPreferences = harmonicPatternPreferences;
    }

    @GetMapping({"/settings", "/settings/appearance", "/settings/analysis-alerts", "/settings/detection",
            "/settings/scoring", "/settings/candlestick-patterns", "/settings/elliott-waves",
            "/settings/harmonic-formations"})
    public String page(Model model, Principal principal, HttpSession session, HttpServletRequest request) {
        User user = current(principal);
        String settingsTab = request.getRequestURI().endsWith("/appearance") ? "appearance"
                : request.getRequestURI().endsWith("/analysis-alerts") ? "analysis"
                : request.getRequestURI().endsWith("/scoring") ? "scoring"
                : request.getRequestURI().endsWith("/candlestick-patterns") ? "candlestick-patterns"
                : request.getRequestURI().endsWith("/elliott-waves") ? "elliott-waves"
                : request.getRequestURI().endsWith("/harmonic-formations") ? "harmonic-formations"
                : request.getRequestURI().endsWith("/detection") ? "detection" : "general";
        model.addAttribute("settingsTab", settingsTab);
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("user", user);
        model.addAttribute("securityEvents", security.recentEvents(user.getId()));
        if ("analysis".equals(settingsTab) || "detection".equals(settingsTab)) {
            model.addAttribute("analysisPreferences", analysisPreferences.get(user));
        }
        if ("scoring".equals(settingsTab)) {
            model.addAttribute("scoringPreferences", scoringPreferences.get(user));
        }
        if ("candlestick-patterns".equals(settingsTab)) {
            model.addAttribute("candlestickPatternPreferences", candlestickPatternPreferences.get(user));
            model.addAttribute("trendRequirements", CandlestickPatternPreferencesService.TrendRequirement.values());
        }
        if ("elliott-waves".equals(settingsTab)) {
            model.addAttribute("elliottWavePreferences", elliottWavePreferences.get(user));
        }
        if ("harmonic-formations".equals(settingsTab)) {
            model.addAttribute("harmonicPatternPreferences", harmonicPatternPreferences.get(user));
        }
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

    @PostMapping("/settings/analysis-alerts")
    public String analysisAlerts(@RequestParam MultiValueMap<String, String> form,
                                 Principal principal,
                                 RedirectAttributes redirect) {
        try {
            analysisPreferences.save(current(principal), form);
            redirect.addFlashAttribute("success", "Analysis and alert preferences applied to future signals.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings/analysis-alerts";
    }

    @PostMapping("/settings/analysis-alerts/reset")
    public String resetAnalysisAlerts(@RequestParam(defaultValue = "all") String scope,
                                      Principal principal,
                                      RedirectAttributes redirect) {
        User user = current(principal);
        try {
            if ("all".equalsIgnoreCase(scope)) {
                analysisPreferences.resetAll(user);
                redirect.addFlashAttribute("success", "All analysis and alert preferences were restored to factory settings.");
            } else {
                TimeInterval interval = TimeInterval.valueOf(scope.trim().toUpperCase());
                analysisPreferences.resetInterval(user, interval);
                redirect.addFlashAttribute("success", scope.substring(0, 1).toUpperCase()
                        + scope.substring(1).toLowerCase() + " settings were restored to factory values.");
            }
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", "Choose a valid factory-reset scope.");
        }
        return "redirect:/settings/analysis-alerts";
    }

    @PostMapping("/settings/detection")
    public String detection(@RequestParam MultiValueMap<String, String> form,
                            Principal principal,
                            RedirectAttributes redirect) {
        try {
            analysisPreferences.updateDetectionRules(current(principal), form);
            redirect.addFlashAttribute("success", "Candlestick detection rules applied to future detections.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings/detection";
    }

    @PostMapping("/settings/detection/reset")
    public String resetDetection(@RequestParam(defaultValue = "all") String scope,
                                 Principal principal,
                                 RedirectAttributes redirect) {
        try {
            TimeInterval interval = "all".equalsIgnoreCase(scope)
                    ? null
                    : TimeInterval.valueOf(scope.trim().toUpperCase());
            analysisPreferences.resetDetectionRules(current(principal), interval);
            redirect.addFlashAttribute("success", interval == null
                    ? "All candlestick detection rules were restored to factory settings."
                    : interval.name().substring(0, 1)
                    + interval.name().substring(1).toLowerCase()
                    + " candlestick detection rules were restored to factory settings.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", "Choose a valid factory-reset scope.");
        }
        return "redirect:/settings/detection";
    }

    @PostMapping("/settings/scoring")
    public String scoring(@RequestParam MultiValueMap<String, String> form,
                          Principal principal,
                          RedirectAttributes redirect) {
        try {
            scoringPreferences.save(current(principal), form);
            redirect.addFlashAttribute("success", "Scoring changes applied. Every included profile totals 100 points.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings/scoring";
    }

    @PostMapping("/settings/scoring/reset")
    public String resetScoring(@RequestParam(defaultValue = "all") String family,
                               @RequestParam(required = false) String interval,
                               Principal principal,
                               RedirectAttributes redirect) {
        try {
            AlertPatternFamily selectedFamily = "all".equalsIgnoreCase(family)
                    ? null : AlertPatternFamily.valueOf(family.trim().toUpperCase());
            TimeInterval selectedInterval = interval == null || interval.isBlank()
                    ? null : TimeInterval.valueOf(interval.trim().toUpperCase());
            scoringPreferences.reset(current(principal), selectedFamily, selectedInterval);
            redirect.addFlashAttribute("success", selectedFamily == null
                    ? "All scoring profiles were restored to factory settings."
                    : "The selected scoring profile was restored to factory settings.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", "Choose a valid scoring profile to reset.");
        }
        return "redirect:/settings/scoring";
    }

    @PostMapping("/settings/candlestick-patterns")
    public String candlestickPatterns(@RequestParam MultiValueMap<String, String> form,
                                      Principal principal, RedirectAttributes redirect) {
        try {
            candlestickPatternPreferences.save(current(principal), form);
            redirect.addFlashAttribute("success", "Candlestick pattern definitions applied to future detections.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings/candlestick-patterns";
    }

    @PostMapping("/settings/candlestick-patterns/reset")
    public String resetCandlestickPatterns(@RequestParam(defaultValue = "all") String pattern,
                                           Principal principal, RedirectAttributes redirect) {
        try {
            CandlePattern selected = "all".equalsIgnoreCase(pattern) ? null
                    : CandlePattern.valueOf(pattern.trim().toUpperCase());
            candlestickPatternPreferences.reset(current(principal), selected);
            redirect.addFlashAttribute("success", selected == null
                    ? "All candlestick pattern definitions were restored to factory settings."
                    : "The selected candlestick pattern was restored to factory settings.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", "Choose a valid candlestick pattern to reset.");
        }
        return "redirect:/settings/candlestick-patterns";
    }

    @PostMapping("/settings/elliott-waves")
    public String elliottWaves(@RequestParam MultiValueMap<String, String> form,
                               Principal principal,
                               RedirectAttributes redirect) {
        try {
            elliottWavePreferences.save(current(principal), form);
            redirect.addFlashAttribute("success",
                    "Weekly and monthly Elliott Wave definitions applied to future detections.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings/elliott-waves";
    }

    @PostMapping("/settings/elliott-waves/reset")
    public String resetElliottWaves(@RequestParam(defaultValue = "all") String interval,
                                    Principal principal,
                                    RedirectAttributes redirect) {
        try {
            TimeInterval selected = "all".equalsIgnoreCase(interval) ? null
                    : TimeInterval.valueOf(interval.trim().toUpperCase());
            elliottWavePreferences.reset(current(principal), selected);
            redirect.addFlashAttribute("success", selected == null
                    ? "All Elliott Wave definitions were restored to factory settings."
                    : selected.name().substring(0, 1)
                    + selected.name().substring(1).toLowerCase()
                    + " Elliott Wave definitions were restored to factory settings.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", "Choose a valid Elliott Wave interval to reset.");
        }
        return "redirect:/settings/elliott-waves";
    }

    @PostMapping("/settings/harmonic-formations")
    public String harmonicFormations(@RequestParam MultiValueMap<String, String> form,
                                     Principal principal,
                                     RedirectAttributes redirect) {
        try {
            harmonicPatternPreferences.save(current(principal), form);
            redirect.addFlashAttribute("success",
                    "Harmonic Formation definitions applied to future detections and reconstructed overlays.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings/harmonic-formations";
    }

    @PostMapping("/settings/harmonic-formations/reset")
    public String resetHarmonicFormations(@RequestParam(defaultValue = "all") String pattern,
                                          Principal principal,
                                          RedirectAttributes redirect) {
        try {
            org.example.stockwatch247.model.enums.HarmonicPatternType selected = "all".equalsIgnoreCase(pattern)
                    ? null : org.example.stockwatch247.model.enums.HarmonicPatternType.valueOf(
                    pattern.trim().toUpperCase());
            harmonicPatternPreferences.reset(current(principal), selected);
            redirect.addFlashAttribute("success", selected == null
                    ? "All Harmonic Formation definitions were restored to factory settings."
                    : selected.displayName() + " definitions were restored to factory settings.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", "Choose a valid Harmonic Formation profile to reset.");
        }
        return "redirect:/settings/harmonic-formations";
    }

    @PostMapping("/settings/theme")
    public String theme(@RequestParam String theme, Principal principal) {
        security.updateTheme(current(principal).getId(), theme);
        return "redirect:/settings/appearance?appearanceSaved=true";
    }

    @PostMapping("/settings/appearance")
    public String appearance(@RequestParam String theme,
                             @RequestParam String elliottMotiveColor,
                             @RequestParam String elliottCorrectiveColor,
                             @RequestParam String elliottSubwaveColor,
                             @RequestParam(required = false) String harmonicFormationColor,
                             Principal principal,
                             RedirectAttributes redirect) {
        try {
            User user = current(principal);
            String harmonicColor = harmonicFormationColor == null || harmonicFormationColor.isBlank()
                    ? user.getHarmonicFormationColor() : harmonicFormationColor;
            security.updateAppearance(user.getId(), theme,
                    elliottMotiveColor, elliottCorrectiveColor, elliottSubwaveColor,
                    harmonicColor);
            redirect.addFlashAttribute("success", "Appearance preferences applied.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings/appearance";
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
        data.put("elliottMotiveColor", user.getElliottMotiveColor());
        data.put("elliottCorrectiveColor", user.getElliottCorrectiveColor());
        data.put("elliottSubwaveColor", user.getElliottSubwaveColor());
        data.put("harmonicFormationColor", user.getHarmonicFormationColor());
        data.put("analysisPreferences", analysisPreferences.get(user));
        data.put("signalScoringPreferences", scoringPreferences.get(user));
        data.put("candlestickPatternPreferences", candlestickPatternPreferences.get(user));
        data.put("elliottWavePreferences", elliottWavePreferences.get(user));
        data.put("harmonicPatternPreferences", harmonicPatternPreferences.get(user));
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
