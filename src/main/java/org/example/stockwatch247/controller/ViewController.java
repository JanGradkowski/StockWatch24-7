package org.example.stockwatch247.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    /** Signed-in visitors go to their dashboard instead of the sign-in pitch. */
    @GetMapping("/")
    public String viewLandingPage(java.security.Principal principal) {
        return principal == null ? "index" : "redirect:/home";
    }

    @GetMapping("/about")
    public String viewAboutPage() {
        return "about";
    }
}
