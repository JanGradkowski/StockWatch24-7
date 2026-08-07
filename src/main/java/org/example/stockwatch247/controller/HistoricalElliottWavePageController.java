package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.HistoricalElliottWaveService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
public class HistoricalElliottWavePageController {
    private final UserRepository userRepository;
    private final HistoricalElliottWaveService historicalElliottWaveService;

    public HistoricalElliottWavePageController(
            UserRepository userRepository,
            HistoricalElliottWaveService historicalElliottWaveService) {
        this.userRepository = userRepository;
        this.historicalElliottWaveService = historicalElliottWaveService;
    }

    @GetMapping("/stock/{symbol}/elliott-waves/{interval}/{stage}/{endpointTimestamp}")
    public String historicalElliottWaveDetail(
            @PathVariable String symbol,
            @PathVariable String interval,
            @PathVariable ElliottSignalStage stage,
            @PathVariable long endpointTimestamp,
            @RequestParam(required = false) String cycleKey,
            Principal principal,
            Model model,
            HttpServletResponse response) {
        String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        model.addAttribute("firstName", currentUser == null ? "Trader" : currentUser.getFirstName());
        model.addAttribute("wave", historicalElliottWaveService.findDetail(
                validatedSymbol,
                interval,
                stage,
                endpointTimestamp,
                cycleKey
        ));
        model.addAttribute("returnUrl", "/stock/" + validatedSymbol + "#general");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return "historical-elliott-detail";
    }
}
