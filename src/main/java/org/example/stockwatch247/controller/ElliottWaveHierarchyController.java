package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.ElliottWaveHierarchyService;
import org.example.stockwatch247.service.ElliottWavePreferencesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/stocks")
public class ElliottWaveHierarchyController {
    private final ElliottWaveHierarchyService hierarchyService;
    private UserRepository userRepository;
    private ElliottWavePreferencesService preferencesService;

    public ElliottWaveHierarchyController(ElliottWaveHierarchyService hierarchyService) {
        this.hierarchyService = hierarchyService;
    }

    @Autowired(required = false)
    void configurePreferences(UserRepository userRepository,
                              ElliottWavePreferencesService preferencesService) {
        this.userRepository = userRepository;
        this.preferencesService = preferencesService;
    }

    @GetMapping("/{symbol}/elliott-waves/hierarchy")
    public ElliottWaveHierarchyService.HierarchyView hierarchy(
            @PathVariable String symbol,
            @RequestParam(required = false) Long asOfExclusive,
            Principal principal) {
        ElliottWaveDetectionService.DetectionRules monthlyRules = null;
        ElliottWaveDetectionService.DetectionRules weeklyRules = null;
        ElliottWaveDetectionService.DetectionRules dailyRules = null;
        if (principal != null && userRepository != null && preferencesService != null) {
            var user = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
            if (user != null) {
                var preferences = preferencesService.get(user);
                monthlyRules = preferences.profile(TimeInterval.MONTHLY).rules();
                weeklyRules = preferences.profile(TimeInterval.WEEKLY).rules();
                dailyRules = preferences.profile(TimeInterval.DAILY).rules();
            }
        }
        return hierarchyService.build(symbol, asOfExclusive, monthlyRules, weeklyRules, dailyRules);
    }
}
