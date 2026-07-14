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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
                && (signal.pattern() == CandlePattern.ELLIOTT_BULLISH_CORRECTION
                    || signal.pattern() == CandlePattern.ELLIOTT_BEARISH_CORRECTION);
        boolean endOfWaveV = rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE
                && (signal.pattern() == CandlePattern.ELLIOTT_BULLISH_WAVE_V_END
                    || signal.pattern() == CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
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
                Signal candle date: %s
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
                formatSignalTimestamp(signal.candleTimestamp()),
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

    String formatSignalTimestamp(long timestamp) {
        ZonedDateTime dateTime = Instant.ofEpochSecond(timestamp).atZone(signalTimeZone);
        int day = dateTime.getDayOfMonth();
        String suffix = ordinalSuffix(day);
        String rest = dateTime.format(DateTimeFormatter.ofPattern("MMMM uuuu, HH:mm", Locale.ENGLISH));
        return day + suffix + " of " + rest;
    }

    private String ordinalSuffix(int day) {
        if (day >= 11 && day <= 13) {
            return "th";
        }
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
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
