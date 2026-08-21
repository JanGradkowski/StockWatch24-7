package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.ElliottWaveDrilldownService;
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
public class ElliottWaveDrilldownController {
    private final ElliottWaveDrilldownService drilldownService;
    private UserRepository userRepository;
    private ElliottWavePreferencesService preferencesService;

    public ElliottWaveDrilldownController(ElliottWaveDrilldownService drilldownService) {
        this.drilldownService = drilldownService;
    }

    @Autowired(required = false)
    void configureElliottWavePreferences(UserRepository userRepository,
                                         ElliottWavePreferencesService preferencesService) {
        this.userRepository = userRepository;
        this.preferencesService = preferencesService;
    }

    @GetMapping("/{symbol}/elliott-waves/drilldown")
    public ElliottWaveDrilldownService.DrilldownView drillDown(
            @PathVariable String symbol,
            @RequestParam String parentInterval,
            @RequestParam String parentLabel,
            @RequestParam long parentStart,
            @RequestParam long parentEnd,
            @RequestParam double parentStartPrice,
            @RequestParam double parentEndPrice,
            @RequestParam(required = false) Long asOfExclusive,
            Principal principal) {
        TimeInterval interval = "1mo".equals(parentInterval) ? TimeInterval.MONTHLY : TimeInterval.WEEKLY;
        return drilldownService.drillDown(symbol, parentInterval, parentLabel,
                parentStart, parentEnd, parentStartPrice, parentEndPrice, asOfExclusive,
                principal == null || userRepository == null || preferencesService == null
                        ? null
                        : userRepository.findByEmailIgnoreCase(principal.getName())
                                .map(user -> preferencesService.get(user).profile(interval).rules())
                                .orElse(null));
    }

    public ElliottWaveDrilldownService.DrilldownView drillDown(
            String symbol, String parentInterval, String parentLabel, long parentStart, long parentEnd,
            double parentStartPrice, double parentEndPrice, Long asOfExclusive) {
        return drillDown(symbol, parentInterval, parentLabel, parentStart, parentEnd,
                parentStartPrice, parentEndPrice, asOfExclusive, null);
    }
}
