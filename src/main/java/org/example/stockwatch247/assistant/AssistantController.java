package org.example.stockwatch247.assistant;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.CurrentAccount;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;

/** The assistant panel's endpoints. Refusals answer with a code the panel explains in the user's language. */
@RestController
public class AssistantController {
    private final AssistantService assistant;
    private final UserRepository users;

    public AssistantController(AssistantService assistant, UserRepository users) {
        this.assistant = assistant;
        this.users = users;
    }

    @PostMapping(value = "/api/assistant/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AssistantService.Answer chat(@RequestBody AssistantService.ChatCommand command, Principal principal) {
        return assistant.chat(user(principal), AppLanguage.of(LocaleContextHolder.getLocale()), command);
    }

    @PostMapping(value = "/api/assistant/feedback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> feedback(@RequestBody AssistantService.FeedbackCommand command, Principal principal) {
        assistant.feedback(user(principal), command);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AssistantService.RefusedException.class)
    public ResponseEntity<Map<String, String>> refused(AssistantService.RefusedException refused) {
        HttpStatus status = switch (refused.refusal()) {
            case ACCOUNT_MINUTE, ACCOUNT_DAY, SITE_MINUTE, SITE_DAY, MODEL_BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var response = ResponseEntity.status(status);
        if (refused.retryAfterSeconds() > 0) response.header(HttpHeaders.RETRY_AFTER, Long.toString(refused.retryAfterSeconds()));
        return response.body(Map.of("code", refused.refusal().name()));
    }

    private User user(Principal principal) {
        User user = principal == null ? null : CurrentAccount.find(users, principal.getName()).orElse(null);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user;
    }
}
