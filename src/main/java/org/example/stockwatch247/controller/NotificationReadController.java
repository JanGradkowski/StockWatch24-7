package org.example.stockwatch247.controller;

import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.CurrentAccount;
import org.example.stockwatch247.service.NotificationReadService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;

@RestController
@RequestMapping("/api/notifications")
public class NotificationReadController {
    private final UserRepository users;
    private final NotificationReadService notifications;

    public NotificationReadController(UserRepository users, NotificationReadService notifications) {
        this.users = users;
        this.notifications = notifications;
    }

    @PostMapping("/read-all")
    public ReadResult markAllRead(Principal principal,
            @RequestParam(defaultValue="ALL") NotificationReadService.Scope scope,
            @RequestParam(required=false) Long watchlistId) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var user = CurrentAccount.find(users, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new ReadResult(notifications.markAllRead(user, scope, watchlistId));
    }

    public record ReadResult(int updated) {}

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<?> invalidScope() {
        return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error", "Choose a valid notification scope and watchlist."));
    }
}
