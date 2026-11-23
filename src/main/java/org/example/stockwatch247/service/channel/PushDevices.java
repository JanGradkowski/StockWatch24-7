package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.security.GuardedHttpClient;
import org.example.stockwatch247.security.OutboundTargetPolicy;
import org.example.stockwatch247.service.UserInputException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * This browser's push subscriptions. The endpoint must be on a known push service and the keys must be a P-256 point
 * and a 16-byte secret. One browser belongs to one account: subscribing it from another account moves it there.
 */
@Service
public class PushDevices {
    private final ChannelStore store;
    private final ChannelSettingsService settings;
    private final WebPushSender sender;
    private final GuardedHttpClient http;

    public record Subscription(String endpoint, String p256dh, String auth) { }

    public PushDevices(ChannelStore store, ChannelSettingsService settings, WebPushSender sender, GuardedHttpClient http) {
        this.store = store;
        this.settings = settings;
        this.sender = sender;
        this.http = http;
    }

    public boolean available() {
        return settings.available(ChannelType.PUSH);
    }

    /**
     * Saves the subscription for the account. {@code announce}: the owner turned notifications on just now, so the
     * device is recorded as a new channel; a page quietly re-sending a known device is not announced.
     */
    @Transactional
    public ChannelStore.Channel subscribe(User user, Subscription subscription, String userAgent, boolean announce) {
        if (!available()) throw new UserInputException("This kind of channel is not set up on this server.");
        URI endpoint = validate(subscription);
        String lookup = "push:" + endpoint;
        String hash = store.lookupHash(ChannelType.PUSH, lookup);
        ChannelStore.Channel mine = null;
        for (ChannelStore.Channel existing : store.byLookup(ChannelType.PUSH, hash)) {
            if (existing.userId() == user.getId()) mine = existing;
            else store.delete(existing);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("endpoint", endpoint.toString());
        config.put("p256dh", subscription.p256dh().strip());
        config.put("auth", subscription.auth().strip());
        if (mine != null) {
            config.put("hideDetails", String.valueOf("true".equals(String.valueOf(store.config(mine).get("hideDetails")))));
            store.replaceConfig(mine, config, null, null);
            if (mine.status() != ChannelStatus.ACTIVE && mine.status() != ChannelStatus.PAUSED) store.activate(mine);
            return store.byId(mine.id()).orElseThrow();
        }
        config.put("hideDetails", "false");
        String name = deviceName(userAgent);
        ChannelStore.Channel created = store.create(new ChannelStore.NewChannel(user.getId(), ChannelType.PUSH, name, name,
                "push:" + endpoint.getHost().toLowerCase(Locale.ROOT), ChannelStatus.ACTIVE, settings.defaultKinds(user),
                settings.defaultDirections(user), null, config, lookup));
        if (announce) settings.announce(user, created);
        return created;
    }

    public Optional<ChannelStore.Channel> find(User user, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return Optional.empty();
        URI uri;
        try {
            uri = http.policy().validateUrl(endpoint);
        } catch (OutboundTargetPolicy.TargetRejected rejected) {
            return Optional.empty();
        }
        return store.byLookup(ChannelType.PUSH, store.lookupHash(ChannelType.PUSH, "push:" + uri)).stream()
                .filter(channel -> channel.userId() == user.getId()).findFirst();
    }

    public boolean unsubscribe(User user, String endpoint) {
        var channel = find(user, endpoint);
        channel.ifPresent(store::delete);
        return channel.isPresent();
    }

    public void hideDetails(User user, String endpoint, boolean hide) {
        ChannelStore.Channel channel = find(user, endpoint)
                .orElseThrow(() -> new UserInputException("Turn notifications on for this device first."));
        Map<String, Object> config = new LinkedHashMap<>(store.config(channel));
        config.put("hideDetails", String.valueOf(hide));
        store.replaceConfig(channel, config, null, null);
    }

    private URI validate(Subscription subscription) {
        URI endpoint;
        try {
            endpoint = http.policy().validateUrl(subscription.endpoint());
        } catch (OutboundTargetPolicy.TargetRejected rejected) {
            throw new UserInputException("This browser's push address is not supported.");
        }
        if (!endpoint.getScheme().equals("https") || !sender.allowed(endpoint)) {
            throw new UserInputException("This browser's push address is not supported.");
        }
        try {
            WebPushCrypto.publicKey(WebPushCrypto.base64Url(subscription.p256dh()));
            if (WebPushCrypto.base64Url(subscription.auth()).length != 16) throw new IllegalArgumentException("The push secret is not 16 bytes.");
        } catch (RuntimeException invalid) {
            throw new UserInputException("This browser sent unusable push keys. Turn notifications off and on again.");
        }
        return endpoint;
    }

    /** "Chrome on Android", from the browser's User-Agent; just a label the owner can rename. */
    static String deviceName(String userAgent) {
        String agent = userAgent == null ? "" : userAgent;
        String browser = agent.contains("Edg/") ? "Edge" : agent.contains("OPR/") ? "Opera" : agent.contains("Firefox/") ? "Firefox"
                : agent.contains("Chrome/") || agent.contains("CriOS/") ? "Chrome" : agent.contains("Safari/") ? "Safari" : "Browser";
        String system = agent.contains("iPhone") ? "iPhone" : agent.contains("iPad") ? "iPad" : agent.contains("Android") ? "Android"
                : agent.contains("Windows") ? "Windows" : agent.contains("Mac OS X") ? "Mac" : agent.contains("Linux") ? "Linux" : null;
        return system == null ? browser : browser + " on " + system;
    }
}
