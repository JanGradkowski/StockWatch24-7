package org.example.stockwatch247.service;

import org.example.stockwatch247.controller.NotificationEmailController;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEmailLinksTest {
    private final SecurityCryptoService crypto = new SecurityCryptoService("a-test-key-that-is-long-and-unique-for-tests");
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationEmailLinks links = new NotificationEmailLinks(crypto, users, "https://stockwatch.example/");

    @Test
    void theSignedLinkNamesOnlyTheAccountIdAndVerifies() {
        var query = UriComponentsBuilder.fromUriString(links.unsubscribeUrl(42)).build().getQueryParams();

        assertThat(links.unsubscribeUrl(42)).startsWith("https://stockwatch.example/email/unsubscribe?u=42&t=");
        assertThat(links.verifies(query.getFirst("u"), query.getFirst("t"))).isTrue();
        assertThat(links.verifies("43", query.getFirst("t"))).isFalse();
        assertThat(links.verifies("42", "forged")).isFalse();
        assertThat(links.verifies("not-a-number", query.getFirst("t"))).isFalse();
        assertThat(links.verifies(null, null)).isFalse();
    }

    @Test
    void oneClickPostPausesTheAccountAndAForgedOneDoesNothing() {
        User user = new User();
        user.setId(42L);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        var controller = new NotificationEmailController(links);
        var query = UriComponentsBuilder.fromUriString(links.unsubscribeUrl(42)).build().getQueryParams();

        var forged = new ExtendedModelMap();
        controller.unsubscribe("42", "forged", forged);
        assertThat(forged.get("done")).isEqualTo(false);
        verify(users, never()).save(user);

        var model = new ExtendedModelMap();
        assertThat(controller.unsubscribe("42", query.getFirst("t"), model)).isEqualTo("email-unsubscribe");
        assertThat(model.get("done")).isEqualTo(true);
        assertThat(user.isNotificationEmailsPaused()).isTrue();
    }
}
