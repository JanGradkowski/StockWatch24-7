package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.insider.InsiderActivityService;
import org.example.stockwatch247.service.insider.InsiderActivityService.ActivityState;
import org.example.stockwatch247.service.insider.InsiderActivityService.HistoryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/insider-activity")
public class InsiderActivityController {
    private final UserRepository userRepository;
    private final InsiderActivityService activityService;

    public InsiderActivityController(
            UserRepository userRepository,
            InsiderActivityService activityService) {
        this.userRepository = userRepository;
        this.activityService = activityService;
    }

    @GetMapping("/{symbol}/state")
    public ActivityState state(@PathVariable String symbol, Principal principal) {
        return activityService.getState(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @GetMapping("/{symbol}/history")
    public HistoryResponse history(@PathVariable String symbol, Principal principal) {
        return activityService.getHistory(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @PostMapping("/{symbol}/history/refresh")
    public HistoryResponse refreshHistory(@PathVariable String symbol, Principal principal) {
        return activityService.refreshHistory(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @PutMapping("/{symbol}/subscription")
    public ActivityState subscription(
            @PathVariable String symbol,
            @RequestBody SubscriptionRequest request,
            Principal principal) {
        if (request == null || request.active() == null) {
            throw new IllegalArgumentException("An active state is required.");
        }
        return activityService.setFollowing(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol),
                request.active());
    }

    private User requireUser(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "The signed-in account no longer exists."));
    }

    public record SubscriptionRequest(Boolean active) {
    }
}
