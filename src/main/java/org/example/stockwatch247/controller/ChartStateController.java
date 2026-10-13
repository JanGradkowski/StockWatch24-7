package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.CurrentAccount;
import org.example.stockwatch247.service.ChartStateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Saves and restores the chart workspace (drawings, indicator layouts and templates) for the signed-in account. */
@RestController
@RequestMapping("/api/chart-state")
public class ChartStateController {
    private final ChartStateService state;
    private final UserRepository users;

    public ChartStateController(ChartStateService state, UserRepository users) {
        this.state = state;
        this.users = users;
    }

    @GetMapping("/drawings")
    public Map<String, Object> drawings(@RequestParam String symbol, Principal principal) {
        return Map.of("drawings", state.drawings(userId(principal), symbol));
    }

    @PutMapping(value = "/drawings/{drawingId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChartStateService.StoredDrawing saveDrawing(@PathVariable String drawingId, @RequestParam String symbol,
                                                       @RequestParam(required = false) Integer version,
                                                       @RequestBody String payload, Principal principal) {
        return state.saveDrawing(userId(principal), symbol, drawingId, payload, version);
    }

    @DeleteMapping("/drawings/{drawingId}")
    public ResponseEntity<Void> deleteDrawing(@PathVariable String drawingId, @RequestParam String symbol, Principal principal) {
        state.deleteDrawing(userId(principal), symbol, drawingId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/drawings")
    public Map<String, Integer> deleteAllDrawings(@RequestParam String symbol, Principal principal) {
        return Map.of("deleted", state.deleteAllDrawings(userId(principal), symbol));
    }

    @PostMapping(value = "/drawings/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Integer> importDrawings(@RequestParam String symbol, @RequestBody JsonNode body, Principal principal) {
        JsonNode list = body.path("drawings");
        if (!list.isArray()) throw new IllegalArgumentException("Send the drawings as a list.");
        List<JsonNode> documents = new ArrayList<>();
        list.forEach(documents::add);
        return Map.of("imported", state.importDrawings(userId(principal), symbol, documents));
    }

    @GetMapping("/layouts/{scope}")
    public ResponseEntity<ChartStateService.StoredLayout> layout(@PathVariable String scope, Principal principal) {
        ChartStateService.StoredLayout layout = state.layout(userId(principal), scope);
        return layout == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(layout);
    }

    @PutMapping(value = "/layouts/{scope}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChartStateService.StoredLayout saveLayout(@PathVariable String scope, @RequestBody String payload, Principal principal) {
        return state.saveLayout(userId(principal), scope, payload);
    }

    @GetMapping("/templates")
    public Map<String, Object> templates(@RequestParam String kind, @RequestParam(required = false) String subject, Principal principal) {
        return Map.of("templates", state.templates(userId(principal), kind, subject));
    }

    @PutMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChartStateService.StoredTemplate saveTemplate(@RequestParam String kind, @RequestParam String subject,
                                                         @RequestParam String name, @RequestBody String payload, Principal principal) {
        return state.saveTemplate(userId(principal), kind, subject, name, payload);
    }

    @DeleteMapping("/templates/{templateId}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable long templateId, Principal principal) {
        state.deleteTemplate(userId(principal), templateId);
        return ResponseEntity.noContent().build();
    }

    private long userId(Principal principal) {
        User user = principal == null ? null : CurrentAccount.find(users, principal.getName()).orElse(null);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user.getId();
    }
}
