package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.TechnicalOutlookService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;

@Controller
@RequestMapping
public class TechnicalOutlookController {
    private final UserRepository userRepository;
    private final TechnicalOutlookService outlookService;

    public TechnicalOutlookController(UserRepository userRepository,
                                      TechnicalOutlookService outlookService) {
        this.userRepository = userRepository;
        this.outlookService = outlookService;
    }

    @GetMapping("/stock/{symbol}/technical-outlook")
    public String page(@PathVariable String symbol, Model model, Principal principal) {
        User user = requireUser(principal);
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("symbol", SecurityInputValidator.requireMarketSymbol(symbol));
        return "technical-outlook";
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook")
    @ResponseBody
    public TechnicalOutlookService.OutlookView outlook(@PathVariable String symbol,
                                                       @RequestParam(defaultValue = "1d") String interval,
                                                       Principal principal) {
        return outlookService.getOutlook(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol),
                SecurityInputValidator.requireInterval(interval));
    }

    private User requireUser(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("An authenticated user is required.");
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user was not found."));
    }
}
