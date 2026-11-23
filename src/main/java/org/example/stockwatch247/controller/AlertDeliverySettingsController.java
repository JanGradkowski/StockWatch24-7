package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.channel.AlertKind;
import org.example.stockwatch247.service.channel.ChannelSettingsService;
import org.example.stockwatch247.service.channel.ChannelStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Alert delivery tab's forms. Channels are addressed by their public id; one that is not the signed-in account's
 * answers 404, as if it did not exist. Every action redirects back with a message.
 */
@Controller
public class AlertDeliverySettingsController {
    static final String PAGE = "redirect:/settings/alert-delivery";

    private final UserRepository users;
    private final ChannelStore store;
    private final ChannelSettingsService channels;

    public AlertDeliverySettingsController(UserRepository users, ChannelStore store, ChannelSettingsService channels) {
        this.users = users;
        this.store = store;
        this.channels = channels;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.example.stockwatch247.service.channel.TelegramLinks telegram;
    @org.springframework.beans.factory.annotation.Autowired
    private SettingsController settingsPage;

    /**
     * A new Telegram link. The page is rendered in this response, not after a redirect: the link holds a one-time
     * token, which must not sit in the session store as a flash attribute.
     */
    @PostMapping("/settings/alert-delivery/telegram/link")
    public String linkTelegram(org.springframework.ui.Model model, Principal principal, jakarta.servlet.http.HttpSession session,
                               jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                               RedirectAttributes redirect) {
        User user = current(principal);
        if (telegram == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            model.addAttribute("telegramLink", telegram.create(user));
        } catch (UserInputException invalid) {
            redirect.addFlashAttribute("error", invalid.getMessage());
            return PAGE + "#add-telegram";
        }
        response.setHeader("Cache-Control", "no-store");
        request.setAttribute("settingsTab", "delivery");
        return settingsPage.page(model, principal, session, request);
    }

    /** Polled by the page while it waits for Start in Telegram. */
    @org.springframework.web.bind.annotation.GetMapping(value = "/api/alert-delivery/telegram/link-status/{id}",
            produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> telegramLinkStatus(@PathVariable String id, Principal principal) {
        User user = current(principal);
        if (telegram == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        UUID requestId;
        try {
            requestId = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var status = telegram.status(user, requestId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("state", status.state().name());
        if (status.channel() != null) body.put("channel", status.channel().toString());
        return body;
    }

    @PostMapping("/settings/alert-delivery/{id}/confirm")
    public String confirmTelegram(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        User user = current(principal);
        ChannelStore.Channel channel = owned(user, id);
        if (telegram != null && channel.status() == org.example.stockwatch247.service.channel.ChannelStatus.PENDING) {
            telegram.confirm(user, channel);
            redirect.addFlashAttribute("success", "Telegram chat " + channel.label() + " linked. Your alerts will arrive there.");
        }
        return anchor(channel);
    }

    @PostMapping("/settings/alert-delivery/{id}/reject")
    public String rejectTelegram(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        User user = current(principal);
        ChannelStore.Channel channel = owned(user, id);
        if (telegram != null && channel.status() == org.example.stockwatch247.service.channel.ChannelStatus.PENDING) {
            telegram.reject(user, channel);
            redirect.addFlashAttribute("success", "The Telegram chat was refused and removed.");
        }
        return PAGE + "#delivery-channels";
    }

    @PostMapping("/settings/alert-delivery/discord")
    public String addDiscord(@RequestParam String webhookUrl, @RequestParam(required = false) String label,
                             Principal principal, RedirectAttributes redirect) {
        User user = current(principal);
        try {
            ChannelStore.Channel channel = channels.addDiscord(user, webhookUrl, label);
            redirect.addFlashAttribute("success", "Discord channel " + channel.label() + " added. Send a test to check it.");
            return anchor(channel);
        } catch (UserInputException invalid) {
            // The pasted URL is a credential: it is never put back into the form.
            redirect.addFlashAttribute("error", invalid.getMessage());
            return PAGE + "#add-discord";
        }
    }

    @PostMapping("/settings/alert-delivery/{id}/test")
    public String test(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        User user = current(principal);
        ChannelStore.Channel channel = owned(user, id);
        var result = channels.test(user, channel);
        switch (result.outcome()) {
            case SENT -> redirect.addFlashAttribute("success", "Test sent to " + channel.label() + ". Check that it arrived.");
            case NOT_AVAILABLE -> redirect.addFlashAttribute("error", "This kind of channel is not set up on this server.");
            case TOO_MANY -> redirect.addFlashAttribute("error", "Too many tests. Wait a few minutes and try again.");
            case GONE -> redirect.addFlashAttribute("error", "This channel no longer exists, so it was stopped. Add it again to keep using it.");
            case SLOW_DOWN -> redirect.addFlashAttribute("error", "The service asked StockWatch to slow down. Try the test again in a minute.");
            case BLOCKED -> redirect.addFlashAttribute("error", "The address points to a private network, which StockWatch can't reach.");
            case FAILED -> redirect.addFlashAttribute("error", "The test did not arrive (" + result.code() + "). Check the channel and try again.");
        }
        return anchor(channel);
    }

    @PostMapping("/settings/alert-delivery/{id}/pause")
    public String pause(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        ChannelStore.Channel channel = owned(current(principal), id);
        if (channels.pause(channel)) {
            redirect.addFlashAttribute("success", "Channel paused. Alerts that were waiting for it were dropped.");
        }
        return anchor(channel);
    }

    @PostMapping("/settings/alert-delivery/{id}/resume")
    public String resume(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        ChannelStore.Channel channel = owned(current(principal), id);
        if (channels.resume(channel)) redirect.addFlashAttribute("success", "Channel resumed.");
        return anchor(channel);
    }

    @PostMapping("/settings/alert-delivery/{id}/update")
    public String update(@PathVariable String id, @RequestParam(required = false) String label,
                         @RequestParam(required = false) List<String> kinds,
                         @RequestParam(required = false) List<String> directions,
                         @RequestParam(required = false) String language,
                         @RequestParam(required = false) String preset,
                         Principal principal, RedirectAttributes redirect) {
        User user = current(principal);
        ChannelStore.Channel channel = owned(user, id);
        Set<AlertKind> chosen = EnumSet.noneOf(AlertKind.class);
        if (kinds != null) {
            for (String kind : kinds) {
                AlertKind parsed = AlertKind.parse(kind);
                if (parsed == null) throw new UserInputException("Unknown kind of alert.");
                chosen.add(parsed);
            }
        }
        Set<String> sides = directions == null ? Set.of() : new LinkedHashSet<>(directions);
        try {
            channels.update(user, channel, label, chosen, sides, language, "email".equals(preset));
            redirect.addFlashAttribute("success", "Channel saved.");
        } catch (UserInputException invalid) {
            redirect.addFlashAttribute("error", invalid.getMessage());
        }
        return anchor(channel);
    }

    @PostMapping("/settings/alert-delivery/{id}/remove")
    public String remove(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        channels.remove(owned(current(principal), id));
        redirect.addFlashAttribute("success", "Channel removed.");
        return PAGE + "#delivery-channels";
    }

    @PostMapping("/settings/alert-delivery/pause-all")
    public String pauseAll(Principal principal, RedirectAttributes redirect) {
        int paused = store.pauseAll(current(principal).getId());
        redirect.addFlashAttribute("success", paused == 0 ? "No channel was active." : "All channels paused.");
        return PAGE + "#delivery-channels";
    }

    @PostMapping("/settings/alert-delivery/resume-all")
    public String resumeAll(Principal principal, RedirectAttributes redirect) {
        int resumed = store.resumeAll(current(principal).getId());
        redirect.addFlashAttribute("success", resumed == 0 ? "No channel was paused." : "Paused channels resumed.");
        return PAGE + "#delivery-channels";
    }

    static String anchor(ChannelStore.Channel channel) {
        return PAGE + "#channel-" + channel.publicId();
    }

    ChannelStore.Channel owned(User user, String id) {
        UUID publicId;
        try {
            publicId = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return store.owned(user.getId(), publicId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The signed-in account; with alert channels switched off on this server the whole tab is absent. */
    User current(Principal principal) {
        if (!channels.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return org.example.stockwatch247.security.CurrentAccount.find(users, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
