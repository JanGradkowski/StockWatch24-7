package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AlertRuleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.stockwatch247.service.WatchlistFollowService watchlistFollows;
    private final AlertRuleService alertRuleService;
    private final UserRepository userRepository;

    public AlertController(AlertRuleService alertRuleService, UserRepository userRepository) {
        this.alertRuleService = alertRuleService;
        this.userRepository = userRepository;
    }

    @PostMapping("/testing/top-us-200")
    public ResponseEntity<?> followTemporaryTopUsCompanies(Principal principal) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "Use named watchlist index imports."));
    }

    @DeleteMapping
    public ResponseEntity<?> unfollowAllTechnicalRules(Principal principal) {
        try {
            User user = currentUser(principal);
            int unfollowedRules = alertRuleService.unfollowAllTechnicalRules(user);
            return ResponseEntity.ok(Map.of("unfollowedRules", unfollowedRules));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid unfollow request."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "The followed tickers could not be unfollowed."));
        }
    }

    @GetMapping("/{symbol}")
    public Map<String, Object> getAlertState(@PathVariable String symbol, Principal principal) {
        User user = currentUser(principal);
        return alertRuleService.getAlertState(user, SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @GetMapping("/{symbol}/elliott-cards")
    public List<AlertRuleService.ElliottWaveSignalCard> getElliottSignalCards(
            @PathVariable String symbol,
            @RequestParam TimeInterval interval,
            Principal principal) {
        User user = currentUser(principal);
        return alertRuleService.getElliottWaveSignalCards(
                user,
                SecurityInputValidator.requireMarketSymbol(symbol),
                interval
        );
    }

    @PostMapping("/{symbol}")
    public ResponseEntity<?> setAlert(@PathVariable String symbol,
                                      @RequestBody AlertToggleRequest request,
                                      Principal principal) {
        try {
            User user = currentUser(principal);
            AlertRuleService.AlertRuleChange change = parseAlertChange(request);
            if (watchlistFollows != null) return ResponseEntity.ok(watchlistFollows.apply(
                    user, SecurityInputValidator.requireMarketSymbol(symbol), List.of(change), null, null));
            AlertRule rule = alertRuleService.setAlert(
                    user,
                    SecurityInputValidator.requireMarketSymbol(symbol),
                    change.interval(),
                    change.signal(),
                    change.patternFamily(),
                    change.active()
            );
            return ResponseEntity.ok(Map.of(
                    "id", rule.getId(),
                    "symbol", rule.getStockAsset().getTickerSymbol(),
                    "interval", rule.getInterval().name(),
                    "signal", rule.getTradeSignal().name(),
                    "patternFamily", rule.getPatternFamily().name(),
                    "active", rule.isActive()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid alert request."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The alert request could not be completed."));
        }
    }

    @PutMapping("/{symbol}")
    public ResponseEntity<?> applyAlertChanges(@PathVariable String symbol,
                                               @RequestBody AlertBatchRequest request,
                                               Principal principal) {
        try {
            if (request == null || request.changes() == null) {
                throw new IllegalArgumentException("Alert changes are required.");
            }
            User user = currentUser(principal);
            String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
            List<AlertRuleService.AlertRuleChange> changes = request.changes().stream()
                    .map(this::parseAlertChange)
                    .toList();
            if (watchlistFollows != null) return ResponseEntity.ok(watchlistFollows.applyDraft(
                    user, validatedSymbol, changes, request.outlookChanges(), request.watchlistIds(), request.newWatchlistName()));
            alertRuleService.applyAlertChanges(user, validatedSymbol, changes);
            return ResponseEntity.ok(alertRuleService.getAlertState(user, validatedSymbol));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid alert changes."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "The alert changes could not be applied."));
        }
    }

    @DeleteMapping("/{symbol}")
    public ResponseEntity<?> unfollowAllTechnicalRules(@PathVariable String symbol,
                                                       Principal principal) {
        try {
            User user = currentUser(principal);
            String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
            int unfollowedRules = alertRuleService.unfollowAllTechnicalRules(user, validatedSymbol);
            return ResponseEntity.ok(Map.of(
                    "symbol", validatedSymbol,
                    "unfollowedRules", unfollowedRules
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid unfollow request."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "The company could not be unfollowed."));
        }
    }

    @DeleteMapping("/{symbol}/rules")
    public ResponseEntity<?> unfollowSelectedTechnicalRules(@PathVariable String symbol,
                                                            @RequestBody RuleSelectionRequest request,
                                                            Principal principal) {
        try {
            if (request == null) {
                throw new IllegalArgumentException("Selected rules are required.");
            }
            User user = currentUser(principal);
            String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
            int unfollowedRules = alertRuleService.unfollowSelectedTechnicalRules(
                    user, validatedSymbol, request.ruleIds(), request.outlookSubscriptionIds());
            return ResponseEntity.ok(Map.of(
                    "symbol", validatedSymbol,
                    "unfollowedRules", unfollowedRules
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid rule selection."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "The selected rules could not be deleted."));
        }
    }

    @PostMapping("/{symbol}/check")
    public ResponseEntity<?> checkLatestSignal(@PathVariable String symbol,
                                               @RequestBody AlertCheckRequest request,
                                               Principal principal) {
        try {
            User user = currentUser(principal);
            return ResponseEntity.ok(alertRuleService.checkLatestSignal(
                    user,
                    SecurityInputValidator.requireMarketSymbol(symbol),
                    TimeInterval.valueOf(request.interval()),
                    TradeSignal.valueOf(request.signal()),
                    parsePatternFamily(request.patternFamily())
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid signal-check request."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The signal check could not be completed."));
        }
    }

    private User currentUser(Principal principal) {
        return org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found."));
    }

    private AlertPatternFamily parsePatternFamily(String value) {
        if (value == null || value.isBlank()) {
            return AlertPatternFamily.CANDLESTICK;
        }
        return AlertPatternFamily.valueOf(value);
    }

    private AlertRuleService.AlertRuleChange parseAlertChange(AlertToggleRequest request) {
        if (request == null || request.interval() == null || request.signal() == null) {
            throw new IllegalArgumentException("Alert interval and signal are required.");
        }
        return new AlertRuleService.AlertRuleChange(
                TimeInterval.valueOf(request.interval()),
                TradeSignal.valueOf(request.signal()),
                parsePatternFamily(request.patternFamily()),
                request.active()
        );
    }

    public record AlertToggleRequest(String interval, String signal, String patternFamily, boolean active) {
    }

    public record AlertBatchRequest(List<AlertToggleRequest> changes, List<Long> watchlistIds, String newWatchlistName,
            List<org.example.stockwatch247.service.WatchlistFollowService.OutlookChange> outlookChanges) {
        public AlertBatchRequest(List<AlertToggleRequest> changes) { this(changes, null, null, null); }
    }

    public record RuleSelectionRequest(List<Long> ruleIds, List<Long> outlookSubscriptionIds) {
        public RuleSelectionRequest(List<Long> ruleIds) { this(ruleIds, List.of()); }
    }

    public record AlertCheckRequest(String interval, String signal, String patternFamily) {
    }
}
