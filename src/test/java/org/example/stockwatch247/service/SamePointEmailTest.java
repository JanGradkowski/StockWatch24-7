package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A check's new signal emails: held until it ends, then one email for each point with several new signals. */
class SamePointEmailTest {
    private static final long CONFIRMATION = 1_794_960_000L;
    private static final long D_POINT = 1_794_787_200L;

    @Test
    void anOpenBatchHoldsNewSignalEmailsButNotLifecycleEmailsAndReleasesALoneSignal() {
        EmailOutboxService outbox = mock(EmailOutboxService.class);
        AlertNotificationService service = service(outbox);
        AlertRule rule = rule(AlertPatternFamily.CANDLESTICK);
        AlertEvent detected = event(rule, 1L, CandlePattern.HAMMER, 80, null);
        AlertEvent confirmed = event(rule, 2L, CandlePattern.HAMMER, 80, null);
        confirmed.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
        confirmed.setResolutionCandleTimestamp(CONFIRMATION + 86_400L);
        DetectedSignal signal = new DetectedSignal(CandlePattern.HAMMER, TradeSignal.BUY, SignalStength.HIGH_CONFIDENCE,
                80, List.of(), CONFIRMATION, 100.0);

        try (var batch = service.openSamePointBatch()) {
            assertThat(batch).isNotNull();
            assertThat(service.sendSignalEmail(rule, signal, detected)).isTrue();
            service.sendSignalLifecycleEmail(confirmed);
        }

        verify(outbox).enqueueHeld(any(), eq(1L), any(Duration.class), eq("initial-alert:1"), eq("INITIAL"),
                eq(AlertNotificationService.SAME_POINT_HOLD));
        verify(outbox, never()).enqueueHeld(any(), eq(2L), any(), anyString(), anyString(), any());
        verify(outbox).release(List.of("initial-alert:1"));
        // Outside a check nothing waits.
        service.sendSignalEmail(rule, signal, event(rule, 3L, CandlePattern.HAMMER, 80, null));
        verify(outbox).enqueue(any(), eq(3L), any(Duration.class));
    }

    @Test
    void signalsAtOnePointAreCombinedHighestScoreFirstAndOthersGoOutAlone() {
        EmailOutboxService outbox = mock(EmailOutboxService.class);
        AlertNotificationService service = service(outbox);
        AlertRule rule = rule(AlertPatternFamily.HARMONIC_FORMATION);
        AlertEvent abcd = event(rule, 1L, CandlePattern.HARMONIC_AB_CD, 93, D_POINT);
        AlertEvent alternate = event(rule, 2L, CandlePattern.HARMONIC_ALTERNATE_AB_CD, 99, D_POINT);
        AlertEvent elsewhere = event(rule, 3L, CandlePattern.HARMONIC_SHARK, 81, D_POINT - 86_400L);

        service.sendSamePoint(List.of(new SamePointEmailBatch.Held(abcd, "initial-alert:1"),
                new SamePointEmailBatch.Held(alternate, "initial-alert:2"),
                new SamePointEmailBatch.Held(elsewhere, "initial-alert:3")));

        verify(outbox).combineHeld(eq(List.of("initial-alert:2", "initial-alert:1")), any());
        verify(outbox).release(List.of("initial-alert:3"));
    }

    @Test
    void theCombinedEmailListsEachSignalAndTheSettingsLinksOnce() {
        AlertNotificationService service = service(mock(EmailOutboxService.class));
        NotificationEmailLinks links = mock(NotificationEmailLinks.class);
        User account = new User();
        account.setId(7L);
        when(links.baseUrl()).thenReturn("https://stockwatch.test");
        when(links.account("customer@example.com")).thenReturn(Optional.of(account));
        when(links.unsubscribeUrl(7L)).thenReturn("https://stockwatch.test/unsubscribe/7");
        ReflectionTestUtils.setField(service, "links", links);
        String settings = "Manage alert emails: https://stockwatch.test" + NotificationEmailLinks.MANAGE_PATH
                + "\nStop all alert emails: https://stockwatch.test/unsubscribe/7\n";
        AlertEvent alternate = event(rule(AlertPatternFamily.HARMONIC_FORMATION), 2L,
                CandlePattern.HARMONIC_ALTERNATE_AB_CD, 99, D_POINT);
        var from = "alerts@stockwatch.test";
        var to = new String[] {"customer@example.com"};

        var message = service.samePointMessage(alternate, List.of(
                new EmailOutboxService.Payload(from, to, "StockWatch harmonic alternate ab=cd: Sell/Short on KO",
                        "Alternate body\n\nView in StockWatch: https://stockwatch.test/alerts/signals/2\n" + settings),
                new EmailOutboxService.Payload(from, to, "StockWatch harmonic ab=cd: Sell/Short on KO",
                        "AB=CD body\n\nView in StockWatch: https://stockwatch.test/alerts/signals/1\n" + settings)));

        assertThat(message.getSubject()).isEqualTo("StockWatch: 2 signals at the same point on KO");
        assertThat(message.getTo()).containsExactly(to);
        String text = message.getText();
        assertThat(text).startsWith("These 2 signals on KO (The Coca-Cola Company) end at the same point (");
        assertThat(text).contains("- StockWatch harmonic alternate ab=cd: Sell/Short on KO\n- StockWatch harmonic ab=cd: Sell/Short on KO",
                "1. StockWatch harmonic alternate ab=cd", "Alternate body", "2. StockWatch harmonic ab=cd", "AB=CD body",
                "/alerts/signals/2", "/alerts/signals/1");
        assertThat(text.split("Manage alert emails:", -1)).hasSize(2);
        assertThat(text.split("Stop all alert emails:", -1)).hasSize(2);
        assertThat(text.stripTrailing()).endsWith("Stop all alert emails: https://stockwatch.test/unsubscribe/7");
    }

    private static AlertNotificationService service(EmailOutboxService outbox) {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
        AlertNotificationService service = new AlertNotificationService(provider, true, "alerts@stockwatch.test", "UTC");
        service.setOutbox(outbox);
        return service;
    }

    private static AlertRule rule(AlertPatternFamily family) {
        User user = new User();
        user.setId(5L);
        user.setEmail("customer@example.com");
        StockAsset asset = new StockAsset();
        asset.setId(9L);
        asset.setTickerSymbol("KO");
        asset.setCompanyName("The Coca-Cola Company");
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(family);
        rule.setTradeSignal(family == AlertPatternFamily.HARMONIC_FORMATION ? TradeSignal.SELL : TradeSignal.BUY);
        return rule;
    }

    private static AlertEvent event(AlertRule rule, long id, CandlePattern pattern, int score, Long dPoint) {
        AlertEvent event = new AlertEvent();
        event.setId(id);
        event.setAlertRule(rule);
        event.setPattern(pattern);
        event.setTradeSignal(rule.getTradeSignal());
        event.setSignalCandleTimestamp(CONFIRMATION);
        event.setConfidenceScore(score);
        event.setHarmonicEndpointTimestamp(dPoint);
        event.setPatternHigh(105.0);
        event.setPatternLow(95.0);
        event.setConfirmationTriggerPrice(105.0);
        event.setInvalidationPrice(95.0);
        event.setConfirmationWindowCandles(10);
        return event;
    }
}
