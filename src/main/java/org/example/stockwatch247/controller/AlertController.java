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
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertRuleService alertRuleService;
    private final UserRepository userRepository;

    public AlertController(AlertRuleService alertRuleService, UserRepository userRepository) {
        this.alertRuleService = alertRuleService;
        this.userRepository = userRepository;
    }

    @GetMapping("/{symbol}")
    public Map<String, Object> getAlertState(@PathVariable String symbol, Principal principal) {
        User user = currentUser(principal);
        return alertRuleService.getAlertState(user, SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @PostMapping("/{symbol}")
    public ResponseEntity<?> setAlert(@PathVariable String symbol,
                                      @RequestBody AlertToggleRequest request,
                                      Principal principal) {
        try {
            User user = currentUser(principal);
            AlertRule rule = alertRuleService.setAlert(
                    user,
                    SecurityInputValidator.requireMarketSymbol(symbol),
                    TimeInterval.valueOf(request.interval()),
                    TradeSignal.valueOf(request.signal()),
                    parsePatternFamily(request.patternFamily()),
                    request.active()
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
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found."));
    }

    private AlertPatternFamily parsePatternFamily(String value) {
        if (value == null || value.isBlank()) {
            return AlertPatternFamily.CANDLESTICK;
        }
        return AlertPatternFamily.valueOf(value);
    }

    public record AlertToggleRequest(String interval, String signal, String patternFamily, boolean active) {
    }

    public record AlertCheckRequest(String interval, String signal, String patternFamily) {
    }
}
