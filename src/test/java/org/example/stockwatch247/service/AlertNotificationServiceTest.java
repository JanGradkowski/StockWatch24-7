package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertNotificationServiceTest {

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
                "Direction confidence: 91/100",
                "bullish body engulfs",
                "Signal candle date: 10th of July 2026, 11:45");
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
                CandlePattern.ELLIOTT_BULLISH_CORRECTION,
                TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE,
                88,
                List.of("wave C ended with bullish reversal confirmation"),
                1_789_000_000L,
                195.25);

        service.sendSignalEmail(rule, signal);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("Elliott wave C completed", "BUY", "SAP.DE");
        assertThat(messageCaptor.getValue().getText()).contains("End of Elliott correction (wave C)");
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
                CandlePattern.ELLIOTT_BULLISH_WAVE_V_END,
                TradeSignal.SELL,
                SignalStength.HIGH_CONFIDENCE,
                88,
                List.of("bullish wave V ended"),
                1_789_000_000L,
                195.25);

        service.sendSignalEmail(rule, signal);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("Elliott wave V completed", "SELL", "SAP.DE");
        assertThat(messageCaptor.getValue().getText()).contains("End of Elliott impulse (wave V)");
    }
}
