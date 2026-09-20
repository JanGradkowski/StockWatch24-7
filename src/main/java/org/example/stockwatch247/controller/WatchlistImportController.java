package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlists")
public class WatchlistImportController {
    private final WatchlistImportService imports;
    private final WatchlistIndexCatalog catalog;
    private final UserRepository users;
    public WatchlistImportController(WatchlistImportService imports,WatchlistIndexCatalog catalog,UserRepository users) { this.imports=imports;this.catalog=catalog;this.users=users; }
    @GetMapping("/indexes") public List<WatchlistIndexCatalog.PresetView> indexes() { return catalog.all(); }
    @PostMapping("/imports/preview") public WatchlistImportService.Preview preview(Principal principal,@RequestParam(required=false) Long watchlistId,@RequestBody WatchlistImportService.ImportRequest request) { return imports.preview(user(principal),watchlistId,request); }
    @PostMapping("/{id}/imports") public Map<String,Long> enqueue(Principal principal,@PathVariable long id,@RequestBody WatchlistImportService.ImportRequest request) { return Map.of("id",imports.enqueue(user(principal),id,request)); }
    @GetMapping("/imports") public List<WatchlistImportService.JobView> recent(Principal principal) { return imports.recent(user(principal)); }
    @GetMapping("/imports/{id}") public WatchlistImportService.JobView status(Principal principal,@PathVariable long id) { return imports.status(user(principal),id); }
    @PostMapping("/imports/{id}/retry") public void retry(Principal principal,@PathVariable long id) { imports.retry(user(principal),id); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("error",error.getMessage())); }
    private User user(Principal principal) { return org.example.stockwatch247.security.CurrentAccount.find(users,principal.getName()).orElseThrow(); }
}
