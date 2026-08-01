package org.example.stockwatch247.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @GetMapping("/")
    public String viewLandingPage() {
        return "index";
    }

    @GetMapping("/about")
    public String viewAboutPage() {
        return "about";
    }
}
