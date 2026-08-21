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
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.ClaimedDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AnalysisPreferencesService preferencesService;

    @Autowired
    public AlertNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider,
                                    @Value("${alerts.email.enabled:false}") boolean emailEnabled,
                                    @Value("${alerts.email.from:no-reply@stockwatch.local}") String fromAddress,
                                    @Value("${alerts.email.time-zone:${alerts.schedule.zone:Europe/Brussels}}") String signalTimeZone,
                                    AnalysisPreferencesService preferencesService) {
        this.mailSenderProvider = mailSenderProvider;
        this.emailEnabled = emailEnabled;
        this.fromAddress = fromAddress;
        this.signalTimeZone = ZoneId.of(signalTimeZone);
        this.preferencesService = preferencesService;
    }

    AlertNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider,
                             boolean emailEnabled,
                             String fromAddress,
                             String signalTimeZone) {
        this(mailSenderProvider, emailEnabled, fromAddress, signalTimeZone, null);
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

    public boolean sendSignalEmail(AlertRule rule, DetectedSignal signal) {
        return sendSignalEmail(rule, signal, null);
    }

    public boolean sendSignalEmail(AlertRule rule, DetectedSignal signal, AlertEvent lifecycleEvent) {
        if (rule.getPatternFamily() == AlertPatternFamily.HARMONIC_FORMATION) {
            return sendHarmonicSignalEmail(rule, signal, lifecycleEvent);
        }
        boolean requiresNextCandleConfirmation = rule.getPatternFamily() == AlertPatternFamily.CANDLESTICK
                && CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern());
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
                : requiresNextCandleConfirmation
                ? "StockWatch potential " + signal.tradeSignal().name().toLowerCase()
                    + " pattern awaiting confirmation: " + signal.pattern()
                    + " on " + rule.getStockAsset().getTickerSymbol()
                : "StockWatch pattern detected: " + signal.pattern()
                    + " on " + rule.getStockAsset().getTickerSymbol();
        String eventDescription = endOfWaveC
                ? "End of Elliott correction (wave C)"
                : endOfWaveV
                ? "End of Elliott impulse (wave V)"
                : requiresNextCandleConfirmation
                ? "Potential one-candle reversal; awaiting the immediately following completed candle"
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
        String lifecycleSection = detectedLifecycleSection(rule, signal, lifecycleEvent);
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
                requiresNextCandleConfirmation ? "A potential" : "A",
                rule.getStockAsset().getTickerSymbol(),
                rule.getPatternFamily(),
                signal.pattern(),
                eventDescription,
                requiresNextCandleConfirmation
                        ? "POTENTIAL " + signal.tradeSignal() + " — AWAITING CONFIRMATION"
                        : signal.tradeSignal(),
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

        if (!isSignalEmailEnabled(rule, signal)) {
            System.out.println("[EMAIL DISABLED] Signal email suppressed for "
                    + rule.getStockAsset().getTickerSymbol() + ".");
            return false;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject(subject);
        message.setText(body);
        send(message);
        return true;
    }

    private boolean sendHarmonicSignalEmail(
            AlertRule rule,
            DetectedSignal signal,
            AlertEvent event) {
        if (event == null || event.getHarmonicEndpointTimestamp() == null
                || event.getHarmonicPointsSnapshot() == null) {
            throw new IllegalArgumentException("A harmonic geometry snapshot is required for email delivery.");
        }
        String symbol = rule.getStockAsset().getTickerSymbol();
        String patternLabel = signal.pattern().name()
                .replace("HARMONIC_", "")
                .replace('_', ' ');
        String endpointLabel = signal.pattern() == CandlePattern.HARMONIC_SHARK ? "C" : "D";
        String body = """
                A confirmed harmonic formation was detected for %s.

                Ticker: %s
                Pattern family: Harmonic Formation
                Formation: %s
                Direction: %s
                Interval: %s
                Completion point: %s
                Completion period: %s
                Completion price: %.4f
                Confirmation / signal period: %s
                Confirmation candle close: %.4f
                Geometry score: %d/100
                Score model: %s
                Score meaning: hard structural rules passed; deductions come only from tolerated soft Fibonacci/proportion deviations.

                Formation points
                %s

                Measured ratios
                %s

                Score evidence
                %s

                This alert describes a completed geometric setup. The score is not a probability of profit,
                price target, or recommendation.
                """.formatted(
                symbol,
                symbol,
                patternLabel,
                signal.tradeSignal(),
                rule.getInterval(),
                endpointLabel,
                SignalPeriodFormatter.format(
                        event.getHarmonicEndpointTimestamp(), rule.getInterval(), signalTimeZone),
                event.getHarmonicEndpointPrice(),
                SignalPeriodFormatter.format(signal.candleTimestamp(), rule.getInterval(), signalTimeZone),
                signal.closePrice(),
                signal.setupScore(),
                HarmonicPatternDetectionService.RULE_VERSION,
                formatHarmonicPoints(event.getHarmonicPointsSnapshot(), rule),
                formatHarmonicMeasurements(event.getHarmonicMeasurementsSnapshot()),
                SignalScoreBreakdown.formatEmail(signal.reasons(), signal.tradeSignal())
        );

        if (!isSignalEmailEnabled(rule, signal)) {
            System.out.println("[EMAIL DISABLED] Harmonic signal email suppressed for " + symbol + ".");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch harmonic " + patternLabel.toLowerCase()
                + ": " + signal.tradeSignal() + " on " + symbol);
        message.setText(body);
        send(message);
        return true;
    }

    private String formatHarmonicPoints(String snapshot, AlertRule rule) {
        if (snapshot == null || snapshot.isBlank()) return "- Unavailable";
        StringBuilder formatted = new StringBuilder();
        for (String line : snapshot.lines().toList()) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4) continue;
            try {
                long timestamp = Long.parseLong(fields[1]);
                double price = Double.parseDouble(fields[2]);
                if (!formatted.isEmpty()) formatted.append('\n');
                formatted.append("- ").append(fields[0]).append(": ")
                        .append(String.format(java.util.Locale.ROOT, "%.4f", price))
                        .append(" on ")
                        .append(SignalPeriodFormatter.format(
                                timestamp, rule.getInterval(), signalTimeZone))
                        .append(" (").append(fields[3]).append(')');
            } catch (NumberFormatException ignored) {
                // A malformed optional snapshot line must not suppress the alert.
            }
        }
        return formatted.isEmpty() ? "- Unavailable" : formatted.toString();
    }

    private String formatHarmonicMeasurements(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return "- Unavailable";
        StringBuilder formatted = new StringBuilder();
        for (String line : snapshot.lines().toList()) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 2) continue;
            try {
                double ratio = Double.parseDouble(fields[1]);
                if (!formatted.isEmpty()) formatted.append('\n');
                formatted.append("- ").append(fields[0].replace('_', '/'))
                        .append(": ")
                        .append(String.format(java.util.Locale.ROOT, "%.4f", ratio));
            } catch (NumberFormatException ignored) {
                // Keep the rest of the persisted measurement snapshot readable.
            }
        }
        return formatted.isEmpty() ? "- Unavailable" : formatted.toString();
    }

    private String detectedLifecycleSection(
            AlertRule rule,
            DetectedSignal signal,
            AlertEvent lifecycleEvent) {
        if (lifecycleEvent == null || !lifecycleEvent.isLifecycleTracked()) {
            return "";
        }
        String confirmationDirection = signal.tradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "above" : "below";
        if (rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE) {
            String structuralRule = lifecycleEvent.getInvalidationPrice() == null
                    ? "the hard Elliott structure rules; ordinary Wave V/C extensions revise the endpoint"
                    : "the hard Elliott structure boundary at %.4f".formatted(
                            lifecycleEvent.getInvalidationPrice());
            String percentageRules = percentageLifecycleRules(lifecycleEvent);
            return """

                    Lifecycle status: DETECTED
                    Confirmation rule: a subsequent completed candle must close %s %.4f
                    Latest Wave V/C endpoint: %.4f
                    Invalidation rule: %s
                    %s
                    Observation window: %d completed %s candles after the latest endpoint revision
                    Lifecycle note: endpoint revisions redraw the same cycle without another detection email. One CONFIRMED, INVALIDATED, or EXPIRED follow-up will be sent.
                    """.formatted(
                    confirmationDirection,
                    lifecycleEvent.getConfirmationTriggerPrice(),
                    lifecycleEvent.getElliottEndpointPrice(),
                    structuralRule,
                    percentageRules,
                    lifecycleEvent.getConfirmationWindowCandles(),
                    rule.getInterval().name().toLowerCase()
            );
        }
        if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern())) {
            String failureStatus = "If that next candle does not close " + confirmationDirection
                    + " the candidate candle close with a confirming "
                    + (signal.tradeSignal() == TradeSignal.BUY ? "green" : "red")
                    + " body, this candidate is REJECTED and never becomes a signal.";
            return """

                    Lifecycle status: POTENTIAL %s
                    This is not a signal yet.
                    Mandatory detection gate: the immediately following completed candle must have a %s body and close %s the candidate candle close at %.4f
                    %s
                    If accepted, the setup becomes DETECTED and its %d-candle outcome window starts from that next candle's close.
                    """.formatted(
                    signal.tradeSignal(),
                    signal.tradeSignal() == TradeSignal.BUY ? "green" : "red",
                    confirmationDirection,
                    lifecycleEvent.getConfirmationTriggerPrice(),
                    failureStatus,
                    lifecycleEvent.getConfirmationWindowCandles());
        }
        String invalidationDirection = signal.tradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "below" : "above";
        return """

                Lifecycle status: DETECTED
                Confirmation rule: a subsequent completed candle must close %s %.4f
                Invalidation rule: a subsequent completed candle must close %s %.4f first
                %s
                Observation window: %d completed %s candles
                Lifecycle note: DETECTED remains the original alert. One CONFIRMED, INVALIDATED, or EXPIRED follow-up will be sent.
                """.formatted(
                confirmationDirection,
                lifecycleEvent.getConfirmationTriggerPrice(),
                invalidationDirection,
                lifecycleEvent.getInvalidationPrice(),
                percentageLifecycleRules(lifecycleEvent),
                lifecycleEvent.getConfirmationWindowCandles(),
                rule.getInterval().name().toLowerCase()
        );
    }

    private String percentageLifecycleRules(AlertEvent event) {
        String measurementAnchor = CandlestickSignalLifecyclePolicy
                .requiresNextCandleConfirmation(event.getPattern())
                && event.getDetectionCandleTimestamp() != null
                ? "detection close"
                : "signal close";
        String confirmation = event.getLifecycleConfirmationPercent() == null
                ? "No additional percentage confirmation move."
                : "Confirmation also requires a %.2f%% favorable move from the %s."
                        .formatted(event.getLifecycleConfirmationPercent(), measurementAnchor);
        String invalidation = event.getLifecycleInvalidationPercent() == null
                ? "No additional percentage invalidation move."
                : "A %.2f%% adverse move from the %s also invalidates."
                        .formatted(event.getLifecycleInvalidationPercent(), measurementAnchor);
        return confirmation + "\n" + invalidation;
    }

    public boolean sendSignalLifecycleEmail(AlertEvent event) {
        if (event == null || !event.isLifecycleTracked()) {
            throw new IllegalArgumentException("A tracked signal event is required.");
        }
        SignalLifecycleStatus status = event.getLifecycleStatus();
        if (status == SignalLifecycleStatus.POTENTIAL) {
            throw new IllegalArgumentException("A terminal signal lifecycle status is required.");
        }

        AlertRule rule = event.getAlertRule();
        String symbol = rule.getStockAsset().getTickerSymbol();
        String statusLabel = status.name();
        boolean elliottSignal = rule.getPatternFamily() == AlertPatternFamily.ELLIOTT_WAVE;
        boolean immediateConfirmationRequired =
                CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(event.getPattern());
        if (immediateConfirmationRequired
                && (status == SignalLifecycleStatus.DETECTED
                || status == SignalLifecycleStatus.REJECTED)) {
            return sendCandidateGateLifecycleEmail(event, status, rule, symbol);
        }
        if (status == SignalLifecycleStatus.DETECTED) {
            throw new IllegalArgumentException("A terminal signal lifecycle status is required.");
        }
        String outcome = switch (status) {
            case CONFIRMED -> "The expected close-based follow-through occurred.";
            case REJECTED -> "The candidate failed its mandatory next-candle gate and never became a signal.";
            case INVALIDATED -> elliottSignal
                    ? "The stored Elliott structure stopped satisfying its hard wave rules before confirmation."
                    : "Price closed beyond the opposite lifecycle boundary before confirmation.";
            case EXPIRED -> "The observation window ended without confirmation or invalidation.";
            case POTENTIAL, DETECTED -> throw new IllegalStateException("A terminal outcome is required.");
        };
        String lifecycleType = elliottSignal ? "Elliott Wave" : "Candlestick";
        String rangeLabel = elliottSignal ? "Wave structure range" : "Pattern range";
        String expectedDirection = event.getTradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "above" : "below";
        String invalidationDirection = event.getTradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "below" : "above";
        String invalidationLine = elliottSignal
                ? event.getInvalidationPrice() == null
                ? "Invalidation rule: hard Elliott structure rules (no fixed price boundary)"
                : "Structural invalidation boundary: %.4f".formatted(event.getInvalidationPrice())
                : "Invalidation boundary: close %s %.4f".formatted(
                        invalidationDirection, event.getInvalidationPrice());
        String directionClassification = immediateConfirmationRequired
                ? switch (status) {
                    case CONFIRMED -> "CONFIRMED " + event.getTradeSignal();
                    case INVALIDATED -> "INVALIDATED " + event.getTradeSignal();
                    case EXPIRED -> "EXPIRED " + event.getTradeSignal();
                    case POTENTIAL, DETECTED, REJECTED ->
                            throw new IllegalStateException("A terminal detected-signal outcome is required.");
                }
                : event.getTradeSignal().name();
        String confirmationLine = immediateConfirmationRequired
                ? "Outcome confirmation trigger: close %s %.4f".formatted(
                        expectedDirection, event.getConfirmationTriggerPrice())
                : "Confirmation trigger: close %s %.4f".formatted(
                        expectedDirection, event.getConfirmationTriggerPrice());
        String measurementLine = immediateConfirmationRequired
                ? "Result measurement start: detection candle close on %s at %.4f".formatted(
                        SignalPeriodFormatter.format(
                                event.getDetectionCandleTimestamp(), rule.getInterval(), signalTimeZone),
                        event.getDetectionClosePrice())
                : "Result measurement start: original signal candle close";
        String resolutionReason = event.getLifecycleResolutionReason() == null
                ? ""
                : "\nResolution reason: " + event.getLifecycleResolutionReason();
        String body = """
                %s lifecycle update for %s.

                Status: %s
                Outcome: %s
                Pattern: %s
                Direction classification: %s
                Interval: %s
                Original signal period: %s
                %s: %.4f to %.4f
                %s
                %s
                %s
                Observation window: %d completed %s candles
                Resolution candle: %s
                Resolution candle number: %d
                Resolution close: %.4f
                %s%s

                This lifecycle update describes the observed price action after a detected setup.
                It is informational and is not a recommendation, price target, or guarantee.
                """.formatted(
                lifecycleType,
                symbol,
                statusLabel,
                outcome,
                event.getPattern(),
                directionClassification,
                rule.getInterval(),
                SignalPeriodFormatter.format(
                        event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone),
                rangeLabel,
                event.getPatternLow(),
                event.getPatternHigh(),
                confirmationLine,
                invalidationLine,
                percentageLifecycleRules(event),
                event.getConfirmationWindowCandles(),
                rule.getInterval().name().toLowerCase(),
                SignalPeriodFormatter.format(
                        event.getResolutionCandleTimestamp(), rule.getInterval(), signalTimeZone),
                event.getResolutionCandleOffset(),
                event.getResolutionClosePrice(),
                measurementLine,
                resolutionReason
        );

        if (!isSignalLifecycleEmailEnabled(event)) {
            System.out.println("[EMAIL DISABLED] " + statusLabel
                    + " lifecycle email suppressed for " + symbol + ".");
            return false;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch pattern " + statusLabel.toLowerCase()
                + ": " + event.getPattern() + " on " + symbol);
        message.setText(body);
        send(message);
        return true;
    }

    private boolean sendCandidateGateLifecycleEmail(AlertEvent event,
                                                    SignalLifecycleStatus status,
                                                    AlertRule rule,
                                                    String symbol) {
        boolean detected = status == SignalLifecycleStatus.DETECTED;
        long gateTimestamp = detected
                ? event.getDetectionCandleTimestamp()
                : event.getResolutionCandleTimestamp();
        double gateClose = detected
                ? event.getDetectionClosePrice()
                : event.getResolutionClosePrice();
        String gatePeriod = SignalPeriodFormatter.format(
                gateTimestamp, rule.getInterval(), signalTimeZone);
        String body = detected
                ? """
                    One-candle candidate accepted for %s.

                    Status: DETECTED
                    Pattern: %s
                    Direction: %s
                    Candidate period: %s
                    Detection candle: %s
                    Detection close: %.4f

                    The mandatory next-candle gate passed. This is now a real signal.
                    Result measurement starts from this detection close.
                    Outcome window: %d completed %s candles
                    Confirmation trigger: %.4f
                    Invalidation boundary: %.4f
                    %s

                    The signal can now become CONFIRMED, INVALIDATED, or EXPIRED.
                    """.formatted(
                        symbol,
                        event.getPattern(),
                        event.getTradeSignal(),
                        SignalPeriodFormatter.format(
                                event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone),
                        gatePeriod,
                        gateClose,
                        event.getConfirmationWindowCandles(),
                        rule.getInterval().name().toLowerCase(),
                        event.getConfirmationTriggerPrice(),
                        event.getInvalidationPrice(),
                        percentageLifecycleRules(event))
                : """
                    One-candle candidate rejected for %s.

                    Status: REJECTED
                    Pattern candidate: %s
                    Potential direction: %s
                    Candidate period: %s
                    Gate candle: %s
                    Gate close: %.4f

                    The immediately following candle did not provide the required %s-body close %s the candidate close at %.4f.
                    The candidate never became a signal, so no outcome window or result is calculated.
                    """.formatted(
                        symbol,
                        event.getPattern(),
                        event.getTradeSignal(),
                        SignalPeriodFormatter.format(
                                event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone),
                        gatePeriod,
                        gateClose,
                        event.getTradeSignal() == TradeSignal.BUY ? "green" : "red",
                        event.getTradeSignal() == TradeSignal.BUY ? "above" : "below",
                        event.getClosePrice());

        if (!isSignalLifecycleEmailEnabled(event)) {
            System.out.println("[EMAIL DISABLED] " + status
                    + " candidate-gate email suppressed for " + symbol + ".");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch " + (detected ? "signal detected" : "candidate rejected")
                + ": " + event.getPattern() + " on " + symbol);
        message.setText(body);
        send(message);
        return true;
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

        if (!emailEnabled || preferencesService != null
                && !preferencesService.allowsCongressionalEmail(delivery.userId())) {
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

        if (!emailEnabled || preferencesService != null
                && !preferencesService.allowsInsiderEmail(delivery.getSubscription().getUser())) {
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

    public boolean isSignalEmailEnabled(AlertRule rule, DetectedSignal signal) {
        return emailEnabled && (preferencesService == null || preferencesService.allowsNewSignalEmail(
                rule.getUser(), rule.getPatternFamily(), rule.getInterval(), signal.tradeSignal()));
    }

    public boolean isSignalLifecycleEmailEnabled(AlertEvent event) {
        if (!emailEnabled || event == null || event.getAlertRule() == null) return false;
        AlertRule rule = event.getAlertRule();
        return preferencesService == null || preferencesService.allowsLifecycleEmail(
                rule.getUser(), event.getLifecycleStatus(), rule.getInterval(), event.getTradeSignal());
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
