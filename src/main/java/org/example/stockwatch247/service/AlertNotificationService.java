package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.ZoneId;

@Service
public class AlertNotificationService {
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean emailEnabled;
    private final String fromAddress;
    private final ZoneId signalTimeZone;

    public AlertNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider,
                                    @Value("${alerts.email.enabled:false}") boolean emailEnabled,
                                    @Value("${alerts.email.from:no-reply@stockwatch.local}") String fromAddress,
                                    @Value("${alerts.email.time-zone:${alerts.schedule.zone:Europe/Brussels}}") String signalTimeZone) {
        this.mailSenderProvider = mailSenderProvider;
        this.emailEnabled = emailEnabled;
        this.fromAddress = fromAddress;
        this.signalTimeZone = ZoneId.of(signalTimeZone);
    }

    public void sendVerificationEmail(User user, String verificationUrl) {
        if (!emailEnabled) {
            throw new IllegalStateException("Email delivery is not configured.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("Verify your StockWatch 24/7 account");
        message.setText("Verify your email address by opening this one-time link:\n\n" + verificationUrl
                + "\n\nIf you did not create this account, you can ignore this email.");
        send(message);
    }

    public void sendSignalEmail(AlertRule rule, DetectedSignal signal) {
        boolean endOfWaveC = rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE
                && isElliottCorrection(signal.pattern());
        boolean endOfWaveV = rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE
                && isElliottWaveVEnd(signal.pattern());
        String subject = endOfWaveC
                ? "StockWatch Elliott wave C completed: " + signal.tradeSignal()
                    + " on " + rule.getStockAsset().getTickerSymbol()
                : endOfWaveV
                ? "StockWatch Elliott wave V completed: " + signal.tradeSignal()
                    + " on " + rule.getStockAsset().getTickerSymbol()
                : "StockWatch pattern detected: " + signal.pattern()
                    + " on " + rule.getStockAsset().getTickerSymbol();
        String body = """
                %s technical pattern was detected for %s.

                Pattern family: %s
                Pattern: %s
                Signal event: %s
                Direction classification: %s
                Direction confidence label: %s
                Direction confidence: %d/100
                Backtested context: high-confidence classifications are strongest over roughly 30 daily candles.
                Reasons: %s
                Interval: %s
                Signal candle period: %s
                Close price: %.2f
                """.formatted(
                "A",
                rule.getStockAsset().getTickerSymbol(),
                rule.getPatternFamily(),
                signal.pattern(),
                endOfWaveC ? "End of Elliott correction (wave C)"
                        : endOfWaveV ? "End of Elliott impulse (wave V)" : "Technical pattern confirmation",
                signal.tradeSignal(),
                signal.strength(),
                signal.confidenceScore(),
                signal.reasons().isEmpty() ? "not available" : String.join("; ", signal.reasons()),
                rule.getInterval(),
                SignalPeriodFormatter.format(signal.candleTimestamp(), rule.getInterval(), signalTimeZone),
                signal.closePrice()
        );

        if (!emailEnabled) {
            System.out.println("[EMAIL DISABLED] Signal email suppressed for "
                    + rule.getStockAsset().getTickerSymbol() + ".");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject(subject);
        message.setText(body);
        send(message);
    }

    private boolean isElliottCorrection(CandlePattern pattern) {
        return pattern != null
                && pattern.name().startsWith("ELLIOTT_")
                && pattern.name().endsWith("CORRECTION");
    }

    private boolean isElliottWaveVEnd(CandlePattern pattern) {
        return pattern != null
                && pattern.name().startsWith("ELLIOTT_")
                && pattern.name().endsWith("WAVE_V_END");
    }

    private void send(SimpleMailMessage message) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("Email delivery is not configured.");
        }
        try {
            mailSender.send(message);
        } catch (MailException e) {
            throw new IllegalStateException("Email could not be sent.", e);
        }
    }
}
