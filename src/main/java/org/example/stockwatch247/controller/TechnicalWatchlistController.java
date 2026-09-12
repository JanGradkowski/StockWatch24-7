package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.CurrentAccount;
import org.example.stockwatch247.service.TechnicalOutlookTrackingService;
import org.example.stockwatch247.service.TechnicalWatchlistService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Controller
public class TechnicalWatchlistController {
    private final UserRepository users;
    private final TechnicalWatchlistService watchlist;
    private final TechnicalOutlookTrackingService tracking;

    public TechnicalWatchlistController(UserRepository users, TechnicalWatchlistService watchlist,
                                        TechnicalOutlookTrackingService tracking) {
        this.users = users;
        this.watchlist = watchlist;
        this.tracking = tracking;
    }

    @GetMapping("/technical-watchlist")
    public String page(Principal principal, Model model) {
        model.addAttribute("firstName", user(principal).getFirstName());
        return "technical-watchlist";
    }

    @GetMapping("/api/technical-watchlist")
    @ResponseBody
    public TechnicalWatchlistService.WatchlistView watchlist(Principal principal) {
        return watchlist.watchlist(user(principal));
    }

    @GetMapping("/api/technical-watchlist/changes")
    @ResponseBody
    public List<TechnicalOutlookTrackingService.LatestOutlookChangeView> changes(
            Principal principal, @RequestParam(defaultValue = "all") String interval) {
        TimeInterval selected = switch (interval) {
            case "all" -> null;
            case "1d" -> TimeInterval.DAILY;
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> throw new IllegalArgumentException("Choose All, Daily, Weekly, or Monthly.");
        };
        return tracking.latestFollowed(user(principal), selected);
    }

    @DeleteMapping("/api/technical-watchlist/{symbol}")
    @ResponseBody
    public TechnicalWatchlistService.UnfollowResult unfollow(Principal principal, @PathVariable String symbol) {
        return watchlist.unfollow(user(principal), symbol);
    }

    @DeleteMapping("/api/technical-watchlist")
    @ResponseBody
    public TechnicalWatchlistService.UnfollowResult unfollowAll(Principal principal) {
        return watchlist.unfollow(user(principal), null);
    }

    private User user(Principal principal) {
        if (principal == null) throw new IllegalStateException("An authenticated user is required.");
        return CurrentAccount.find(users, principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user was not found."));
    }
}
