package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.VirtualTradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
public class VirtualTradeController {
    private final UserRepository userRepository;
    private final VirtualTradeService virtualTradeService;

    public VirtualTradeController(UserRepository userRepository,
                                  VirtualTradeService virtualTradeService) {
        this.userRepository = userRepository;
        this.virtualTradeService = virtualTradeService;
    }

    @GetMapping("/virtual-trades")
    public String archive(@RequestParam(defaultValue = "date") String sort,
                          @RequestParam(defaultValue = "desc") String direction,
                          Principal principal,
                          Model model) {
        User user = requireUser(principal);
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("archive", virtualTradeService.archive(user, sort, direction));
        return "virtual-trades";
    }

    @GetMapping("/virtual-trades/{tradeId}")
    public String detail(@PathVariable Long tradeId, Principal principal, Model model) {
        User user = requireUser(principal);
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("virtualTrade", virtualTradeService.detail(user, tradeId));
        return "virtual-trade";
    }

    @PostMapping("/virtual-trades/{tradeId}/delete")
    public String deleteFromArchive(@PathVariable Long tradeId,
                                    @RequestParam(defaultValue = "date") String sort,
                                    @RequestParam(defaultValue = "desc") String direction,
                                    Principal principal,
                                    RedirectAttributes redirectAttributes) {
        User user = requireUser(principal);
        try {
            virtualTradeService.delete(user, tradeId);
            redirectAttributes.addFlashAttribute("virtualTradeDeleteMessage", "Virtual trade deleted.");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("virtualTradeDeleteError", exception.getMessage());
        }
        redirectAttributes.addAttribute("sort", sort);
        redirectAttributes.addAttribute("direction", "asc".equalsIgnoreCase(direction) ? "asc" : "desc");
        return "redirect:/virtual-trades";
    }

    @GetMapping("/api/virtual-trades")
    @ResponseBody
    public java.util.List<VirtualTradeService.TradeView> companyTrades(@RequestParam String symbol,
                                                                       Principal principal) {
        return virtualTradeService.companyTrades(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @PostMapping("/api/virtual-trades/{symbol}")
    @ResponseBody
    public VirtualTradeService.TradeView create(@PathVariable String symbol,
                                                @RequestBody VirtualTradeService.CreateCommand command,
                                                Principal principal) {
        return virtualTradeService.create(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol),
                command);
    }

    @PostMapping("/api/virtual-trades/{tradeId}/close")
    @ResponseBody
    public VirtualTradeService.TradeView close(@PathVariable Long tradeId, Principal principal) {
        return virtualTradeService.close(requireUser(principal), tradeId);
    }

    @PostMapping("/api/virtual-trades/{tradeId}/delete")
    @ResponseBody
    public ResponseEntity<Void> delete(@PathVariable Long tradeId, Principal principal) {
        virtualTradeService.delete(requireUser(principal), tradeId);
        return ResponseEntity.noContent().build();
    }

    private User requireUser(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
