package org.example.stockwatch247.controller;

import org.example.stockwatch247.service.NotificationEmailLinks;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The unsubscribe link in alert emails. Opening it asks for confirmation; a POST stops the emails,
 * which is also what mailbox providers send for the one-click {@code List-Unsubscribe-Post} header
 * (RFC 8058). The signed link is the authorization, so neither a session nor a CSRF token is needed.
 */
@Controller
public class NotificationEmailController {
    private final NotificationEmailLinks links;

    public NotificationEmailController(NotificationEmailLinks links) {
        this.links = links;
    }

    @GetMapping("/email/unsubscribe")
    public String confirm(@RequestParam(required = false) String u, @RequestParam(required = false) String t, Model model) {
        boolean valid = links.verifies(u, t);
        model.addAttribute("valid", valid);
        model.addAttribute("done", false);
        if (valid) {
            model.addAttribute("u", u);
            model.addAttribute("t", t);
        }
        return "email-unsubscribe";
    }

    @PostMapping("/email/unsubscribe")
    public String unsubscribe(@RequestParam(required = false) String u, @RequestParam(required = false) String t, Model model) {
        boolean done = links.verifies(u, t) && links.setPaused(Long.parseLong(u), true);
        model.addAttribute("valid", done);
        model.addAttribute("done", done);
        return "email-unsubscribe";
    }
}
