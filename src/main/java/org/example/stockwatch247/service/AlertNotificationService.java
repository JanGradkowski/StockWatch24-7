package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.CongressionalTradeType;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.ClaimedDelivery;
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

    public void sendPasswordSecurityCode(User user, String code, boolean reset) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user,
                reset ? "Reset your StockWatch password" : "Confirm your StockWatch password change");
        message.setText("Your one-time security code is:\n\n" + code
                + "\n\nIt expires in 5 minutes and can be used once. "
                + "If you did not request this, do not share the code and change your password.");
        send(message);
    }

    public void sendSecurityNotice(User user, String subject, String body) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user, subject);
        message.setText(body + "\n\nIf this was not you, reset your password immediately.");
        send(message);
    }

    public void sendAccountDeletionNotice(User user, String cancellationUrl) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user, "Your StockWatch account is scheduled for deletion");
        message.setText("Your account has been disabled and is scheduled for permanent deletion in 7 days.\n\n"
                + "To cancel the deletion, open this one-time link before the deadline:\n" + cancellationUrl
                + "\n\nIf this was not you, cancel the deletion and reset your password immediately.");
        send(message);
    }

    public void sendAccountDeletedNotice(User user) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user, "Your StockWatch account was deleted");
        message.setText("The seven-day cancellation period ended and your StockWatch account and associated account data were permanently deleted.");
        send(message);
    }

    private SimpleMailMessage baseMessage(User user, String subject) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject(subject);
        return message;
    }

    private void requireEmailDelivery() {
        if (!emailEnabled) {
            throw new IllegalStateException("Email delivery is not configured.");
        }
    }

    public void sendSignalEmail(AlertRule rule, DetectedSignal signal) {
        sendSignalEmail(rule, signal, null);
    }

    public void sendSignalEmail(AlertRule rule, DetectedSignal signal, AlertEvent lifecycleEvent) {
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
        String eventDescription = endOfWaveC
                ? "End of Elliott correction (wave C)"
                : endOfWaveV
                ? "End of Elliott impulse (wave V)"
                : lifecycleEvent != null && lifecycleEvent.isLifecycleTracked()
                ? "Validated candlestick pattern; close-based lifecycle tracking started"
                : "Validated candlestick pattern";
        String researchHorizonSection = CandlestickHorizonGuidance
                .forSignal(rule.getPatternFamily(), rule.getInterval())
                .map(guidance -> """

                        Research horizon: %s
                        Horizon context: %s
                        Horizon note: %s""".formatted(
                        guidance.label(),
                        guidance.summary(),
                        guidance.disclaimer()
                ))
                .orElse("");
        String lifecycleSection = lifecycleEvent != null && lifecycleEvent.isLifecycleTracked()
                ? """

                        Lifecycle status: DETECTED
                        Confirmation rule: a subsequent completed candle must close %s %.4f
                        Invalidation rule: a subsequent completed candle must close %s %.4f first
                        Observation window: %d completed %s candles
                        Lifecycle note: DETECTED remains the original alert. One CONFIRMED, INVALIDATED, or EXPIRED follow-up will be sent.
                        """.formatted(
                        signal.tradeSignal() == org.example.stockwatch247.model.enums.TradeSignal.BUY
                                ? "above"
                                : "below",
                        lifecycleEvent.getConfirmationTriggerPrice(),
                        signal.tradeSignal() == org.example.stockwatch247.model.enums.TradeSignal.BUY
                                ? "below"
                                : "above",
                        lifecycleEvent.getInvalidationPrice(),
                        lifecycleEvent.getConfirmationWindowCandles(),
                        rule.getInterval().name().toLowerCase()
                )
                : "";
        String scoreVersion = signal.pattern() != null && signal.pattern().name().startsWith("ELLIOTT_")
                ? ElliottWaveDetectionService.SETUP_SCORE_VERSION
                : CandlePatternDetectionService.SETUP_SCORE_VERSION;
        String scoreNote = CandlePatternDetectionService.SETUP_SCORE_VERSION.equals(scoreVersion)
                ? "experimental technical confluence; V4 has not demonstrated stable out-of-sample "
                        + "predictive ordering and is not a probability of profit"
                : "technical confluence, not a probability of profit";
        String scoreBreakdown = SignalScoreBreakdown.formatEmail(signal.reasons(), signal.tradeSignal());
        String body = """
                %s technical pattern was detected for %s.

                Pattern family: %s
                Pattern: %s
                Signal event: %s
                Direction classification: %s
                Setup strength: %s
                Heuristic setup score: %d/100
                Score model: %s
                Score note: %s.

                Score breakdown
                %s

                Interval: %s%s%s
                Signal candle period: %s
                Close price: %.2f
                """.formatted(
                "A",
                rule.getStockAsset().getTickerSymbol(),
                rule.getPatternFamily(),
                signal.pattern(),
                eventDescription,
                signal.tradeSignal(),
                setupStrengthLabel(signal.strength()),
                signal.setupScore(),
                scoreVersion,
                scoreNote,
                scoreBreakdown,
                rule.getInterval(),
                researchHorizonSection,
                lifecycleSection,
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

    public void sendSignalLifecycleEmail(AlertEvent event) {
        if (event == null || !event.isLifecycleTracked()) {
            throw new IllegalArgumentException("A tracked candlestick event is required.");
        }
        SignalLifecycleStatus status = event.getLifecycleStatus();
        if (status == SignalLifecycleStatus.DETECTED) {
            throw new IllegalArgumentException("A terminal candlestick lifecycle status is required.");
        }

        AlertRule rule = event.getAlertRule();
        String symbol = rule.getStockAsset().getTickerSymbol();
        String statusLabel = status.name();
        String outcome = switch (status) {
            case CONFIRMED -> "The expected close-based follow-through occurred.";
            case INVALIDATED -> "Price closed beyond the opposite pattern boundary before confirmation.";
            case EXPIRED -> "The observation window ended without confirmation or invalidation.";
            case DETECTED -> throw new IllegalStateException("DETECTED is not a terminal outcome.");
        };
        String expectedDirection = event.getTradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "above" : "below";
        String invalidationDirection = event.getTradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "below" : "above";
        String body = """
                Candlestick lifecycle update for %s.

                Status: %s
                Outcome: %s
                Pattern: %s
                Direction classification: %s
                Interval: %s
                Original signal period: %s
                Pattern range: %.4f to %.4f
                Confirmation trigger: close %s %.4f
                Invalidation boundary: close %s %.4f
                Observation window: %d completed candles
                Resolution candle: %s
                Resolution candle number: %d
                Resolution close: %.4f

                This lifecycle update describes the observed price action after a detected setup.
                It is informational and is not a recommendation, price target, or guarantee.
                """.formatted(
                symbol,
                statusLabel,
                outcome,
                event.getPattern(),
                event.getTradeSignal(),
                rule.getInterval(),
                SignalPeriodFormatter.format(
                        event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone),
                event.getPatternLow(),
                event.getPatternHigh(),
                expectedDirection,
                event.getConfirmationTriggerPrice(),
                invalidationDirection,
                event.getInvalidationPrice(),
                event.getConfirmationWindowCandles(),
                SignalPeriodFormatter.format(
                        event.getResolutionCandleTimestamp(), rule.getInterval(), signalTimeZone),
                event.getResolutionCandleOffset(),
                event.getResolutionClosePrice()
        );

        if (!emailEnabled) {
            System.out.println("[EMAIL DISABLED] " + statusLabel
                    + " lifecycle email suppressed for " + symbol + ".");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch pattern " + statusLabel.toLowerCase()
                + ": " + event.getPattern() + " on " + symbol);
        message.setText(body);
        send(message);
    }

    public void sendCongressionalTradeEmail(ClaimedDelivery delivery) {
        String transactionLabel;
        try {
            transactionLabel = CongressionalTradeType.valueOf(delivery.transactionType()).getLabel();
        } catch (IllegalArgumentException exception) {
            transactionLabel = delivery.transactionType();
        }
        String subject = "StockWatch congressional "
                + transactionLabel.toLowerCase()
                + " disclosed: "
                + delivery.ticker();
        String sourceLine = delivery.sourceUrl() == null || delivery.sourceUrl().isBlank()
                ? "Official filing link: unavailable"
                : "Official filing: " + delivery.sourceUrl();
        String assetLine = delivery.assetName() == null || delivery.assetName().isBlank()
                ? ""
                : "\nReported asset: " + delivery.assetName();
        String body = """
                A new congressional transaction disclosure was observed for %s.

                Member: %s
                Chamber: %s
                Activity: %s
                Reported value: %s
                Transaction date: %s
                Disclosure date: %s%s
                %s

                This alert is based on the public disclosure date, not the day the trade occurred.
                Congressional disclosures may be filed up to 45 days after a transaction.
                Data is provided for informational and research purposes only and is not financial advice.
                """.formatted(
                delivery.ticker(),
                delivery.memberName(),
                delivery.chamber(),
                transactionLabel,
                delivery.amountRange(),
                delivery.transactionDate(),
                delivery.disclosureDate(),
                assetLine,
                sourceLine);

        if (!emailEnabled) {
            System.out.println("[EMAIL DISABLED] Congressional activity email suppressed for "
                    + delivery.ticker() + ".");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(delivery.recipientEmail());
        message.setSubject(subject);
        message.setText(body);
        send(message);
    }

    public void sendInsiderTradeEmail(InsiderTradeDelivery delivery) {
        if (delivery == null || delivery.getTrade() == null
                || delivery.getSubscription() == null) {
            throw new IllegalArgumentException("An insider trade delivery is required.");
        }
        InsiderTrade trade = delivery.getTrade();
        String symbol = trade.getTickerSymbol();
        String activity = trade.getTransactionType().getLabel();
        String role = trade.getOwnerRole() == null || trade.getOwnerRole().isBlank()
                ? "Role not reported"
                : trade.getOwnerRole();
        String price = trade.getTransactionPrice() == null
                ? "Not reported"
                : trade.getTransactionPrice().stripTrailingZeros().toPlainString();
        String shares = trade.getShares() == null
                ? "Not reported"
                : trade.getShares().stripTrailingZeros().toPlainString();
        String source = trade.getSourceUrl() == null || trade.getSourceUrl().isBlank()
                ? "SEC filing link: unavailable"
                : "SEC filing: " + trade.getSourceUrl();
        String body = """
                A new corporate insider transaction filing was observed for %s.

                Insider: %s
                Role: %s
                Activity: %s
                Shares: %s
                Filed transaction price: %s
                Effective date: %s (SEC filing-date fallback)
                Filing date: %s
                %s

                StockWatch tracks filed open-market purchases and sales only.
                Data provided by API Ninjas. Each free-tier check merges the 10 latest
                rows into the stored archive and may miss larger bursts between checks.
                Any displayed return is calculated separately from the filed transaction price
                to the latest completed daily close and is not a realized portfolio return.
                Informational and research use only; not financial advice.
                """.formatted(
                symbol,
                trade.getInsiderName(),
                role,
                activity,
                shares,
                price,
                trade.getTransactionDate(),
                trade.getFilingDate(),
                source);

        if (!emailEnabled) {
            System.out.println("[EMAIL DISABLED] Insider activity email suppressed for "
                    + symbol + ".");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(delivery.getSubscription().getUser().getEmail());
        message.setSubject("StockWatch insider " + activity.toLowerCase()
                + " filed: " + symbol);
        message.setText(body);
        send(message);
    }

    public boolean isEmailDeliveryEnabled() {
        return emailEnabled;
    }

    private String setupStrengthLabel(SignalStength strength) {
        return switch (strength) {
            case HIGH_CONFIDENCE -> "High confluence";
            case MEDIUM_CONFIDENCE -> "Moderate confluence";
            case LOW_CONFIDENCE -> "Low confluence";
            case WEAK_IGNORE -> "Minimal confluence";
        };
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
