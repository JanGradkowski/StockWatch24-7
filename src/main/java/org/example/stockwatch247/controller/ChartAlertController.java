package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.CurrentAccount;
import org.example.stockwatch247.service.ChartAlertService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Alerts on chart drawings and indicators, and their triggers. */
@RestController
public class ChartAlertController {
    private final ChartAlertService alerts;
    private final UserRepository users;

    public ChartAlertController(ChartAlertService alerts, UserRepository users) {
        this.alerts = alerts;
        this.users = users;
    }

    @GetMapping("/api/chart-alerts")
    public Map<String, List<ChartAlertService.AlertView>> list(@RequestParam String symbol, Principal principal) {
        return Map.of("alerts", alerts.list(userId(principal), symbol));
    }

    /** Every chart alert of the account, across stocks (the Custom alerts page lists them). */
    @GetMapping("/api/chart-alerts/all")
    public Map<String, List<ChartAlertService.AlertView>> listAll(Principal principal) {
        return Map.of("alerts", alerts.listAll(userId(principal)));
    }

    @GetMapping("/api/chart-alerts/{id}")
    public ChartAlertService.AlertView get(@PathVariable long id, Principal principal) {
        return alerts.get(userId(principal), id);
    }

    @PostMapping(value = "/api/chart-alerts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChartAlertService.AlertView create(@RequestBody ChartAlertService.CreateCommand command, Principal principal) {
        return alerts.create(userId(principal), command);
    }

    /** Switches an alert on or off, or replaces its name and shape (the drawing moved). */
    @PatchMapping(value = "/api/chart-alerts/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChartAlertService.AlertView update(@PathVariable long id, @RequestBody JsonNode body, Principal principal) {
        long userId = userId(principal);
        ChartAlertService.AlertView view = null;
        if (body.has("label") || body.has("definition")) {
            view = alerts.update(userId, id, body.has("label") ? body.path("label").asString() : null,
                    body.has("definition") ? body.path("definition") : null);
        }
        if (body.path("active").isBoolean()) view = alerts.setActive(userId, id, body.path("active").asBoolean());
        if (view == null) throw new IllegalArgumentException("Nothing to change.");
        return view;
    }

    @DeleteMapping("/api/chart-alerts/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Principal principal) {
        alerts.delete(userId(principal), id);
        return ResponseEntity.noContent().build();
    }

    /** Removes the alerts of a drawing that was deleted from the chart. */
    @DeleteMapping("/api/chart-alerts")
    public Map<String, Integer> deleteForSource(@RequestParam String symbol, @RequestParam String source, Principal principal) {
        return Map.of("deleted", alerts.deleteForSource(userId(principal), symbol, source));
    }

    /** A trigger opened from Latest signals: the stock chart shows its candle. */
    @GetMapping("/api/chart-alerts/events/{id}")
    public ChartAlertService.LatestChartAlertView event(@PathVariable long id, Principal principal) {
        return alerts.event(userId(principal), id);
    }

    /** The chart page marks the trigger read once it is shown (opening a page never changes anything). */
    @PostMapping("/api/chart-alerts/events/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable long id, Principal principal) {
        alerts.markRead(userId(principal), id);
        return ResponseEntity.noContent().build();
    }

    private long userId(Principal principal) {
        User user = principal == null ? null : CurrentAccount.find(users, principal.getName()).orElse(null);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user.getId();
    }
}
