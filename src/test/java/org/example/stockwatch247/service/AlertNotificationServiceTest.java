package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.ClaimedDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertNotificationServiceTest {

    @Test
    void congressionalDisclosureEmailExplainsTradeAndDisclosureDates() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        ClaimedDelivery delivery = new ClaimedDelivery(
                7L,
                1,
                "customer@example.com",
                "AAPL",
                "Example Representative",
                "House",
                "PURCHASE",
                "$15,001 - $50,000",
                LocalDate.of(2026, 6, 29),
                LocalDate.of(2026, 7, 18),
                "Apple Inc. - Common Stock",
                "https://disclosures-clerk.house.gov/public_disc/example.pdf");

        service.sendCongressionalTradeEmail(delivery);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getTo()).containsExactly("customer@example.com");
        assertThat(message.getSubject()).contains("purchase", "AAPL");
        assertThat(message.getText()).contains(
                "Example Representative",
                "Reported value: $15,001 - $50,000",
                "Transaction date: 2026-06-29",
                "Disclosure date: 2026-07-18",
                "public disclosure date, not the day the trade occurred",
                "not financial advice");
    }

    @Test
    void sendsDetectedSignalWithScoreAndReasonsThroughConfiguredMailSender() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("SAP.DE");
        asset.setCompanyName("SAP SE");
        asset.setExchange("XETRA");
        asset.setCurrency("EUR");
        User user = new User();
        user.setEmail("customer@example.com");
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(AlertPatternFamily.CANDLESTICK);
        rule.setTradeSignal(TradeSignal.BUY);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.BULLISH_ENGULFING,
                TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE,
                91,
                List.of("bullish body engulfs the previous bearish body"),
                ZonedDateTime.of(2026, 7, 10, 11, 45, 0, 0, ZoneId.of("Europe/Brussels")).toEpochSecond(),
                195.25
        );

        service.sendSignalEmail(rule, signal);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getTo()).containsExactly("customer@example.com");
        assertThat(message.getSubject()).contains("BULLISH_ENGULFING", "SAP.DE");
        assertThat(message.getText()).contains(
                "Heuristic setup score: 91/100",
                "bullish body engulfs",
                "Research horizon: 10\u201330 trading sessions",
                "Historical evaluation window only \u2014 not a recommended holding period",
                "Signal candle period: 10 Jul 2026");
    }

    @Test
    void detectedCandlestickEmailExplainsAdditiveLifecycleBoundaries() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.HAMMER,
                TradeSignal.BUY,
                SignalStength.MEDIUM_CONFIDENCE,
                80,
                List.of("validated hammer"),
                Instant.parse("2026-07-20T00:00:00Z").getEpochSecond(),
                100.0
        );
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.DETECTED);

        service.sendSignalEmail(rule, signal, event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getText()).contains(
                "Lifecycle status: DETECTED",
                "close above 105.0000",
                "close below 95.0000",
                "Observation window: 3 completed daily candles",
                "One CONFIRMED, INVALIDATED, or EXPIRED follow-up will be sent");
    }

    @Test
    void sendsTerminalLifecycleFollowUpEmail() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.CONFIRMED);
        event.setPattern(CandlePattern.HAMMER);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-21T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(1);
        event.setResolutionClosePrice(106.0);

        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("confirmed", "HAMMER", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Status: CONFIRMED",
                "expected close-based follow-through occurred",
                "Confirmation trigger: close above 105.0000",
                "Resolution candle number: 1",
                "Resolution close: 106.0000",
                "not a recommendation");
    }

    @Test
    void identifiesEndOfWaveCInElliottEmailSubjectAndBody() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("SAP.DE");
        User user = new User();
        user.setEmail("customer@example.com");
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.WEEKLY);
        rule.setPatternFamily(AlertPatternFamily.ELLIOTT_WAVE);
        rule.setTradeSignal(TradeSignal.BUY);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.ELLIOTT_BULLISH_EXPANDED_FLAT_CORRECTION,
                TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE,
                88,
                List.of("expanded-flat wave C ended with bullish reversal confirmation"),
                Instant.parse("2026-07-13T00:00:00Z").getEpochSecond(),
                195.25);

        service.sendSignalEmail(rule, signal);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("Elliott wave C completed", "BUY", "SAP.DE");
        assertThat(messageCaptor.getValue().getText()).contains(
                "End of Elliott correction (wave C)",
                "Signal candle period: 13\u201317 Jul 2026");
        assertThat(messageCaptor.getValue().getText()).doesNotContain("Research horizon:");
    }

    @Test
    void identifiesEndOfWaveVInElliottEmailSubjectAndBody() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("SAP.DE");
        User user = new User();
        user.setEmail("customer@example.com");
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.MONTHLY);
        rule.setPatternFamily(AlertPatternFamily.ELLIOTT_WAVE);
        rule.setTradeSignal(TradeSignal.SELL);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END,
                TradeSignal.SELL,
                SignalStength.HIGH_CONFIDENCE,
                88,
                List.of("bullish wave V ended as a truncated fifth"),
                Instant.parse("2026-07-01T00:00:00Z").getEpochSecond(),
                195.25);

        service.sendSignalEmail(rule, signal);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("Elliott wave V completed", "SELL", "SAP.DE");
        assertThat(messageCaptor.getValue().getText()).contains(
                "End of Elliott impulse (wave V)",
                "Signal candle period: July 2026");
        assertThat(messageCaptor.getValue().getText()).doesNotContain("Research horizon:");
    }

    private AlertRule dailyRule(TradeSignal direction) {
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        User user = new User();
        user.setEmail("customer@example.com");
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(AlertPatternFamily.CANDLESTICK);
        rule.setTradeSignal(direction);
        return rule;
    }

    private AlertEvent trackedEvent(AlertRule rule, SignalLifecycleStatus status) {
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(CandlePattern.HAMMER);
        event.setTradeSignal(rule.getTradeSignal());
        event.setSignalCandleTimestamp(Instant.parse("2026-07-20T00:00:00Z").getEpochSecond());
        event.setLifecycleStatus(status);
        event.setPatternHigh(105.0);
        event.setPatternLow(95.0);
        event.setConfirmationTriggerPrice(
                rule.getTradeSignal() == TradeSignal.BUY ? 105.0 : 95.0);
        event.setInvalidationPrice(
                rule.getTradeSignal() == TradeSignal.BUY ? 95.0 : 105.0);
        event.setConfirmationWindowCandles(3);
        return event;
    }
}
