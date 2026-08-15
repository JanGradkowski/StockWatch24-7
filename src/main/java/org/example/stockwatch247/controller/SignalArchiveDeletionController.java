package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.SignalArchiveDeletionService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

@Controller
public class SignalArchiveDeletionController {
    private final UserRepository userRepository;
    private final SignalArchiveDeletionService deletionService;

    public SignalArchiveDeletionController(
            UserRepository userRepository,
            SignalArchiveDeletionService deletionService) {
        this.userRepository = userRepository;
        this.deletionService = deletionService;
    }

    @PostMapping("/signals/delete")
    public String deleteTechnicalSignals(
            @RequestParam(required = false) List<Long> signalIds,
            @RequestParam(required = false) Long singleSignalId,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page,
            Principal principal,
            RedirectAttributes redirectAttributes) {
        User user = currentUser(principal);
        if (user == null) {
            return "redirect:/login";
        }
        Collection<Long> selectedIds = singleSignalId == null ? signalIds : List.of(singleSignalId);
        try {
            int deleted = deletionService.deleteTechnicalSignals(user, selectedIds);
            redirectAttributes.addFlashAttribute("signalDeleteMessage", deletionMessage(deleted, false));
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("signalDeleteError", exception.getMessage());
        }
        addArchiveLocation(redirectAttributes, sort, direction, page);
        return "redirect:/signals";
    }

    @PostMapping("/activity-signals/delete")
    public String deleteActivitySignals(
            @RequestParam(required = false) List<String> signalKeys,
            @RequestParam(required = false) String singleSignalKey,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page,
            Principal principal,
            RedirectAttributes redirectAttributes) {
        User user = currentUser(principal);
        if (user == null) {
            return "redirect:/login";
        }
        Collection<String> selectedKeys = singleSignalKey == null ? signalKeys : List.of(singleSignalKey);
        try {
            int deleted = deletionService.deleteActivitySignals(user, selectedKeys);
            redirectAttributes.addFlashAttribute("signalDeleteMessage", deletionMessage(deleted, true));
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("signalDeleteError", exception.getMessage());
        }
        addArchiveLocation(redirectAttributes, sort, direction, page);
        return "redirect:/activity-signals";
    }

    private User currentUser(Principal principal) {
        return principal == null
                ? null
                : userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
    }

    private void addArchiveLocation(
            RedirectAttributes redirectAttributes,
            String sort,
            String direction,
            int page) {
        redirectAttributes.addAttribute("sort", sort == null ? "date" : sort);
        redirectAttributes.addAttribute("direction", "asc".equalsIgnoreCase(direction) ? "asc" : "desc");
        redirectAttributes.addAttribute("page", Math.max(0, page));
    }

    private String deletionMessage(int deleted, boolean activity) {
        String label = activity ? "activity signal" : "signal";
        return deleted + " " + label + (deleted == 1 ? "" : "s") + " deleted.";
    }
}
