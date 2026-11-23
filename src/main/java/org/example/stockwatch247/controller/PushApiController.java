package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.channel.ChannelSettingsService;
import org.example.stockwatch247.service.channel.PushDevices;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This browser's push subscription, from the Alert delivery page and the quiet re-sync on every page. Signed-in only,
 * with the ordinary CSRF token in the X-CSRF-TOKEN header: no endpoint here is exempt.
 */
@RestController
public class PushApiController {
    public record SubscriptionBody(String endpoint, Map<String, String> keys, Boolean enable) { }

    public record DeviceBody(String endpoint, Boolean hideDetails) { }

    private final UserRepository users;
    private final PushDevices devices;
    private final ChannelSettingsService settings;
    private final org.example.stockwatch247.service.channel.ChannelStore store;

    public PushApiController(UserRepository users, PushDevices devices, ChannelSettingsService settings,
                             org.example.stockwatch247.service.channel.ChannelStore store) {
        this.users = users;
        this.devices = devices;
        this.settings = settings;
        this.store = store;
    }

    @PostMapping("/api/push/subscriptions")
    public Map<String, Object> subscribe(@RequestBody SubscriptionBody body, Principal principal,
                                         @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        User user = current(principal);
        if (body.keys() == null) throw new UserInputException("This browser sent unusable push keys. Turn notifications off and on again.");
        var channel = devices.subscribe(user, new PushDevices.Subscription(body.endpoint(), body.keys().get("p256dh"),
                body.keys().get("auth")), userAgent, Boolean.TRUE.equals(body.enable()));
        return state(channel.publicId().toString(), channel.status().name(),
                "true".equals(String.valueOf(store.config(channel).get("hideDetails"))));
    }

    @PostMapping("/api/push/subscriptions/remove")
    public Map<String, Object> remove(@RequestBody DeviceBody body, Principal principal) {
        return Map.of("removed", devices.unsubscribe(current(principal), body.endpoint()));
    }

    @PostMapping("/api/push/subscriptions/details")
    public Map<String, Object> details(@RequestBody DeviceBody body, Principal principal) {
        devices.hideDetails(current(principal), body.endpoint(), Boolean.TRUE.equals(body.hideDetails()));
        return Map.of("hideDetails", Boolean.TRUE.equals(body.hideDetails()));
    }

    @PostMapping("/api/push/test")
    public Map<String, Object> test(@RequestBody DeviceBody body, Principal principal) {
        User user = current(principal);
        var channel = devices.find(user, body.endpoint())
                .orElseThrow(() -> new UserInputException("Turn notifications on for this device first."));
        var result = settings.test(user, channel);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("outcome", result.outcome().name());
        return answer;
    }

    private static Map<String, Object> state(String id, String status, boolean hideDetails) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", id);
        state.put("status", status);
        state.put("hideDetails", hideDetails);
        return state;
    }

    private User current(Principal principal) {
        if (!devices.available()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return org.example.stockwatch247.security.CurrentAccount.find(users, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
