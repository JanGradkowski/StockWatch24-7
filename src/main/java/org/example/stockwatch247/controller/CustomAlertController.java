package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.CurrentAccount;
import org.example.stockwatch247.service.customalert.CustomAlertPreview;
import org.example.stockwatch247.service.customalert.CustomAlertService;
import org.example.stockwatch247.service.customalert.CustomAlertTriggerChart;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** The Custom alerts pages and their JSON API. */
@Controller
public class CustomAlertController {
    private final CustomAlertService alerts;
    private final CustomAlertPreview preview;
    private final CustomAlertTriggerChart triggerChart;
    private final UserRepository users;
    private final tools.jackson.databind.ObjectMapper json;

    public CustomAlertController(CustomAlertService alerts, CustomAlertPreview preview, CustomAlertTriggerChart triggerChart,
                                 UserRepository users, tools.jackson.databind.ObjectMapper json) {
        this.alerts = alerts;
        this.preview = preview;
        this.triggerChart = triggerChart;
        this.users = users;
        this.json = json;
    }

    // ------------------------------------------------------------------ pages

    @GetMapping("/custom-alerts")
    public String listPage(Model model, Principal principal) {
        User user = user(principal);
        model.addAttribute("maximumActive", alerts.maximumActive());
        // The guide starts open while the user has fewer than two alerts.
        model.addAttribute("alertCount", alerts.count(user));
        return "custom-alerts";
    }

    /** The newest triggers an alert's page lists; older ones stay in the database and in the user's data export. */
    static final int ALERT_PAGE_TRIGGERS = 500;

    /** One alert: its rule, how its triggers did, the stocks it watches and every trigger, newest first. */
    @GetMapping("/custom-alerts/{id}")
    public String alertPage(@PathVariable long id, @RequestParam(defaultValue = "") String ticker, Model model, Principal principal) {
        User user = user(principal);
        CustomAlertService.ArchiveHeader header = alerts.archiveHeader(user, id, null);
        List<CustomAlertService.TriggerRow> triggers = alerts.triggers(user, id, ALERT_PAGE_TRIGGERS);
        model.addAttribute("alert", header.alert());
        model.addAttribute("performance", header.performance());
        model.addAttribute("triggers", triggers);
        model.addAttribute("triggersCapped", triggers.size() >= ALERT_PAGE_TRIGGERS);
        model.addAttribute("firstResult", firstResult(triggers, header.alert().outcomeCandles()));
        // The table's checkpoint columns: 1, 5, 10 and 20 candles, those inside the follow-up window (the last three).
        List<Integer> checkpoints = java.util.stream.Stream.of(1, 5, 10, 20).filter(candles -> candles <= header.alert().outcomeCandles()).toList();
        List<Integer> shown = checkpoints.subList(Math.max(0, checkpoints.size() - 3), checkpoints.size());
        model.addAttribute("checkpoints", shown);
        // When each trigger still measured gets its next result: the close that ends its next checkpoint (none once
        // that day has passed, while the data catches up).
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Map<Long, java.time.LocalDate> nextResults = new java.util.HashMap<>();
        triggers.forEach(trigger -> {
            Integer mark = trigger.nextMark(shown);
            if (mark == null) return;
            java.time.LocalDate day = windowEnd(java.time.Instant.ofEpochSecond(trigger.candleTimestamp()).atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                    trigger.interval(), mark);
            if (!day.isBefore(today)) nextResults.put(trigger.id(), day);
        });
        model.addAttribute("nextResults", nextResults);
        model.addAttribute("alertJson", json.writeValueAsString(header.alert()));
        model.addAttribute("ticker", ticker.length() <= 20 && ticker.matches("[A-Za-z0-9.^=_,\\s-]*") ? ticker.trim().toUpperCase() : "");
        return "custom-alert";
    }

    /** The builder, optionally started from a template, for a stock or a watchlist, or from a chart alert. */
    @GetMapping("/custom-alerts/new")
    public String newPage(@RequestParam(required = false) String template, @RequestParam(required = false) String symbol,
                          @RequestParam(required = false) Long watchlistId, @RequestParam(required = false) Long chartAlert,
                          Model model, Principal principal) {
        user(principal);
        model.addAttribute("alertId", null);
        model.addAttribute("startTemplate", template != null && template.matches("[a-z0-9-]{1,40}") ? template : null);
        model.addAttribute("startSymbol", symbol != null && symbol.matches("[A-Za-z0-9.^=_-]{1,20}") ? symbol.toUpperCase() : null);
        model.addAttribute("startWatchlist", watchlistId);
        model.addAttribute("startChartAlert", chartAlert);
        return "custom-alert-builder";
    }

    /**
     * Roughly when the oldest trigger still being measured completes its follow-up window: that many candles of its
     * interval after its own candle (weekdays for daily candles; market holidays can push it a day or two later).
     */
    static java.time.LocalDate firstResult(List<CustomAlertService.TriggerRow> triggers, int candles) {
        return triggers.stream().filter(trigger -> !"COMPLETE".equals(trigger.outcomeStatus()))
                .map(trigger -> windowEnd(java.time.Instant.ofEpochSecond(trigger.candleTimestamp()).atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                        trigger.interval(), candles))
                .min(java.util.Comparator.naturalOrder()).orElse(null);
    }

    private static java.time.LocalDate windowEnd(java.time.LocalDate candle, org.example.stockwatch247.model.enums.TimeInterval interval, int candles) {
        return switch (interval) {
            case WEEKLY -> candle.plusWeeks(candles);
            case MONTHLY -> candle.plusMonths(candles);
            default -> {
                java.time.LocalDate day = candle;
                for (int left = candles; left > 0; ) {
                    day = day.plusDays(1);
                    if (day.getDayOfWeek().getValue() <= 5) left--;
                }
                yield day;
            }
        };
    }

    @GetMapping("/custom-alerts/{id}/edit")
    public String editPage(@PathVariable long id, Model model, Principal principal) {
        alerts.requireOwned(user(principal), id);
        model.addAttribute("alertId", id);
        return "custom-alert-builder";
    }

    @GetMapping("/custom-alerts/triggers/{id}")
    public String triggerPage(@PathVariable long id, @RequestParam(defaultValue = "") String returnTo, Model model, Principal principal) {
        User user = user(principal);
        CustomAlertService.TriggerView trigger = alerts.trigger(user, id);
        model.addAttribute("trigger", trigger);
        model.addAttribute("place", alerts.place(user, id));
        model.addAttribute("alertPerformance", alerts.performance(user, trigger.alertId()));
        model.addAttribute("triggerEvidence", json.writeValueAsString(trigger.evidence()));
        if (returnTo.length() <= 1024 && returnTo.matches("^/(signals|custom-alerts(/\\d+)?)(\\?[^\\r\\n#]*)?$")) {
            model.addAttribute("returnUrl", returnTo);
        }
        return "custom-alert-trigger";
    }

    // ------------------------------------------------------------------ API

    @GetMapping("/api/custom-alerts")
    @ResponseBody
    public List<CustomAlertService.AlertView> list(Principal principal) {
        return alerts.list(user(principal));
    }

    @GetMapping("/api/custom-alerts/catalog")
    @ResponseBody
    public CustomAlertService.Catalog catalog() {
        return alerts.catalog();
    }

    @PostMapping(value = "/api/custom-alerts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public CustomAlertService.AlertView create(@RequestBody CustomAlertService.SaveCommand command, Principal principal) {
        return alerts.create(user(principal), command);
    }

    @GetMapping("/api/custom-alerts/{id}")
    @ResponseBody
    public CustomAlertService.AlertView get(@PathVariable long id, Principal principal) {
        return alerts.get(user(principal), id);
    }

    @PutMapping(value = "/api/custom-alerts/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public CustomAlertService.AlertView update(@PathVariable long id, @RequestBody CustomAlertService.SaveCommand command,
                                               Principal principal) {
        return alerts.update(user(principal), id, command);
    }

    @DeleteMapping("/api/custom-alerts/{id}")
    @ResponseBody
    public ResponseEntity<Void> delete(@PathVariable long id, Principal principal) {
        alerts.delete(user(principal), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/custom-alerts/{id}/active")
    @ResponseBody
    public CustomAlertService.AlertView active(@PathVariable long id, @RequestParam boolean value, Principal principal) {
        return alerts.setActive(user(principal), id, value);
    }

    @PostMapping("/api/custom-alerts/{id}/email")
    @ResponseBody
    public CustomAlertService.AlertView email(@PathVariable long id, @RequestParam boolean value, Principal principal) {
        return alerts.setEmail(user(principal), id, value);
    }

    @PostMapping("/api/custom-alerts/{id}/pinned")
    @ResponseBody
    public CustomAlertService.AlertView pinned(@PathVariable long id, @RequestParam boolean value, Principal principal) {
        return alerts.setPinned(user(principal), id, value);
    }

    @PostMapping("/api/custom-alerts/{id}/duplicate")
    @ResponseBody
    public CustomAlertService.AlertView duplicate(@PathVariable long id, @RequestParam(required = false) String interval,
                                                  Principal principal) {
        return alerts.duplicate(user(principal), id, interval);
    }

    @GetMapping("/api/custom-alerts/{id}/states")
    @ResponseBody
    public List<CustomAlertService.StockStateView> states(@PathVariable long id, Principal principal) {
        return alerts.states(user(principal), id);
    }

    /** Stops the alert for one stock of a list it covers in full, or brings the stock back. */
    @PostMapping("/api/custom-alerts/{id}/stocks/{symbol}")
    @ResponseBody
    public CustomAlertService.AlertView stock(@PathVariable long id, @PathVariable String symbol, @RequestParam boolean stopped,
                                              Principal principal) {
        return alerts.setExcluded(user(principal), id, symbol, stopped);
    }

    @PostMapping(value = "/api/custom-alerts/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public CustomAlertPreview.PreviewView preview(@RequestBody CustomAlertPreview.PreviewCommand command, Principal principal) {
        return preview.preview(user(principal), command);
    }

    @GetMapping("/api/custom-alerts/for-stock/{symbol}")
    @ResponseBody
    public List<CustomAlertService.StockAlertView> forStock(@PathVariable String symbol, Principal principal) {
        return alerts.forStock(user(principal), symbol);
    }

    public record StockChange(List<Long> apply, List<Long> remove, List<Long> watchlistIds, String newWatchlistName) { }

    @PutMapping(value = "/api/custom-alerts/for-stock/{symbol}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<CustomAlertService.StockAlertView> applyToStock(@PathVariable String symbol, @RequestBody StockChange change,
                                                                Principal principal) {
        if (change == null) throw new IllegalArgumentException("Nothing to change.");
        return alerts.applyToStock(user(principal), symbol, change.apply(), change.remove(), change.watchlistIds(), change.newWatchlistName());
    }

    @GetMapping("/api/custom-alerts/for-watchlist/{watchlistId}")
    @ResponseBody
    public List<CustomAlertService.WatchlistAlertView> forWatchlist(@PathVariable long watchlistId, Principal principal) {
        return alerts.forWatchlist(user(principal), watchlistId);
    }

    @GetMapping("/api/custom-alerts/for-watchlist/{watchlistId}/members")
    @ResponseBody
    public Map<String, List<Map<String, Object>>> memberAlerts(@PathVariable long watchlistId, @RequestParam List<String> symbols,
                                                               Principal principal) {
        if (symbols.size() > 100) throw new IllegalArgumentException("Too many symbols.");
        return alerts.memberAlerts(user(principal), watchlistId, symbols);
    }

    @PostMapping("/api/custom-alerts/{id}/watchlists/{watchlistId}")
    @ResponseBody
    public CustomAlertService.AlertView watchlist(@PathVariable long id, @PathVariable long watchlistId, @RequestParam boolean applied,
                                                  Principal principal) {
        return alerts.setWatchlist(user(principal), id, watchlistId, applied);
    }

    /** The candles, indicator lines, patterns and filings the trigger page draws. */
    @GetMapping("/api/custom-alerts/triggers/{id}/chart")
    @ResponseBody
    public CustomAlertTriggerChart.ChartView triggerChart(@PathVariable long id, Principal principal) {
        return triggerChart.chart(user(principal), id);
    }

    @PostMapping("/api/custom-alerts/triggers/{id}/read")
    @ResponseBody
    public ResponseEntity<Void> read(@PathVariable long id, @RequestParam(defaultValue = "true") boolean read, Principal principal) {
        alerts.setRead(user(principal), id, read);
        return ResponseEntity.noContent().build();
    }

    public record TriggerSelection(List<Long> ids) { }

    @PostMapping(value = "/api/custom-alerts/{id}/triggers/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Integer> deleteTriggers(@PathVariable long id, @RequestBody TriggerSelection selection, Principal principal) {
        return Map.of("deleted", alerts.deleteTriggers(user(principal), id, selection == null ? null : selection.ids()));
    }

    @PostMapping("/api/custom-alerts/read-all")
    @ResponseBody
    public Map<String, Integer> readAll(@RequestParam(required = false) Long alertId, Principal principal) {
        return Map.of("updated", alerts.markAllRead(user(principal), alertId));
    }

    private User user(Principal principal) {
        User user = principal == null ? null : CurrentAccount.find(users, principal.getName()).orElse(null);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user;
    }
}
