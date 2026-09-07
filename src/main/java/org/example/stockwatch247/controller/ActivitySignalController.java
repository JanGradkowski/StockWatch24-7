package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.ActivitySignalDetailService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;

@Controller
public class ActivitySignalController {
    private final UserRepository userRepository;
    private final ActivitySignalDetailService detailService;

    public ActivitySignalController(
            UserRepository userRepository,
            ActivitySignalDetailService detailService) {
        this.userRepository = userRepository;
        this.detailService = detailService;
    }

    @GetMapping("/activity-signals/{source}/{deliveryId}")
    public String detail(
            @PathVariable String source,
            @PathVariable Long deliveryId,
            Model model,
            Principal principal) {
        User user = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("signal", detailService.getDetail(user, source, deliveryId));
        return "activity-signal-detail";
    }

    @GetMapping("/activity-signals/{source}/trades/{tradeId}")
    public String tradeDetail(
            @PathVariable String source,
            @PathVariable Long tradeId,
            Model model,
            Principal principal) {
        User user = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("signal", detailService.getTradeDetail(user, source, tradeId));
        return "activity-signal-detail";
    }
}
