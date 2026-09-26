package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.WatchlistService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@Controller
public class WatchlistController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.stockwatch247.service.WatchlistBulkService bulk;
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.stockwatch247.service.WatchlistSignalSettingsService settings;
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.stockwatch247.service.WatchlistEditorService editor;
    private final WatchlistService lists;
    private final UserRepository users;
    public WatchlistController(WatchlistService lists,UserRepository users) { this.lists=lists;this.users=users; }
    @GetMapping({"/watchlists","/watchlists/{id}"})
    public String page(@PathVariable(required=false) Long id,Principal principal,Model model) {
        if(id!=null) model.addAttribute("watchlistName", lists.name(user(principal),id));
        model.addAttribute("selectedWatchlistId",id);
        return "watchlists";
    }
    @GetMapping("/api/watchlists") @ResponseBody
    public List<WatchlistService.ListView> lists(Principal principal) { return lists.lists(user(principal)); }
    @GetMapping("/api/watchlists/{id}/settings") @ResponseBody
    public org.example.stockwatch247.service.WatchlistSignalSettingsService.SettingsView settings(Principal principal,@PathVariable long id) {
        return settings.get(user(principal),id);
    }
    @PostMapping("/api/watchlists/save") @ResponseBody
    public org.example.stockwatch247.service.WatchlistEditorService.Saved save(Principal principal,
            @RequestBody org.example.stockwatch247.service.WatchlistEditorService.SaveRequest request) {
        return editor.save(user(principal),request);
    }
    @PostMapping("/api/watchlists") @ResponseBody
    public Map<String,Long> create(Principal principal,@RequestBody Edit request) {
        return Map.of("id",lists.create(user(principal),request.name(),request.description()));
    }
    @PutMapping("/api/watchlists/{id}") @ResponseBody
    public void update(Principal principal,@PathVariable long id,@RequestBody Edit request) {
        lists.update(user(principal),id,request.name(),request.description(),request.pinned());
    }
    @DeleteMapping("/api/watchlists/{id}") @ResponseBody
    public void delete(Principal principal,@PathVariable long id) { lists.delete(user(principal),id); }
    @GetMapping("/api/watchlists/memberships/{symbol}") @ResponseBody
    public List<Long> memberships(Principal principal,@PathVariable String symbol) { return lists.memberships(user(principal),symbol); }
    @PostMapping("/api/watchlists/memberships/{symbol}") @ResponseBody
    public void attach(Principal principal,@PathVariable String symbol,@RequestBody Selection request) {
        lists.attach(user(principal),symbol,request.watchlistIds(),request.newWatchlistName());
    }
    @GetMapping("/api/watchlists/{id}/members") @ResponseBody
    public WatchlistService.MemberPage members(Principal principal,@PathVariable long id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="all") String group,@RequestParam(defaultValue="") String q) {
        return lists.members(user(principal),id,page,group,q);
    }
    @DeleteMapping("/api/watchlists/{id}/members/{symbol}") @ResponseBody
    public void remove(Principal principal,@PathVariable long id,@PathVariable String symbol) { lists.remove(user(principal),id,symbol); }
    @PostMapping("/api/watchlists/{id}/members/follows/preview") @ResponseBody
    public org.example.stockwatch247.service.WatchlistBulkService.View previewFollows(Principal principal, @PathVariable long id, @RequestBody Bulk request) {
        return bulk.preview(user(principal), id, request.symbols());
    }
    @PostMapping("/api/watchlists/{id}/members/follows") @ResponseBody
    public void applyFollows(Principal principal, @PathVariable long id, @RequestBody Bulk request) {
        bulk.apply(user(principal), id, request.symbols(), request.changes());
    }
    @PostMapping("/api/watchlists/{id}/members/remove") @ResponseBody
    public void removeMembers(Principal principal, @PathVariable long id, @RequestBody Bulk request) {
        bulk.remove(user(principal), id, request.symbols());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("error",error.getMessage())); }
    private User user(Principal principal) {
        return org.example.stockwatch247.security.CurrentAccount.find(users,principal.getName()).orElseThrow();
    }
    public record Edit(String name,String description,boolean pinned) {}
    public record Selection(List<Long> watchlistIds,String newWatchlistName) {}
    public record Bulk(List<String> symbols, List<org.example.stockwatch247.service.WatchlistSignalSettingsService.Selection> changes) {}
}
