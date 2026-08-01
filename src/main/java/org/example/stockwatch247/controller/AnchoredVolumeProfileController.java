package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AnchoredVolumeProfileService;
import org.example.stockwatch247.service.AnchoredVolumeProfileService.ProfileResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/stocks")
public class AnchoredVolumeProfileController {
    private final UserRepository userRepository;
    private final AnchoredVolumeProfileService profileService;

    public AnchoredVolumeProfileController(
            UserRepository userRepository,
            AnchoredVolumeProfileService profileService) {
        this.userRepository = userRepository;
        this.profileService = profileService;
    }

    @GetMapping("/{symbol}/anchored-volume-profile")
    public ProfileResponse profile(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam long anchor,
            Principal principal) {
        return profileService.getProfile(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol),
                SecurityInputValidator.requireInterval(interval),
                anchor);
    }

    private User requireUser(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "The signed-in account no longer exists."));
    }
}
