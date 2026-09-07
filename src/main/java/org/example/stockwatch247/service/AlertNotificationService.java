package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.ElliottStageTradePlan;
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
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.ClaimedDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Service
public class AlertNotificationService {
    private EmailOutboxService outbox;
    @Autowired
    void setOutbox(EmailOutboxService outbox) { this.outbox = outbox; }

    private void queueAccount(SimpleMailMessage message, long lifetimeSeconds) {
        if (outbox == null) send(message);
        else outbox.enqueue(message, null, java.time.Duration.ofSeconds(lifetimeSeconds));
    }

    private void queueInitial(SimpleMailMessage message, AlertEvent event) {
        if (outbox == null) send(message);
        else outbox.enqueue(message, event == null ? null : event.getId(), java.time.Duration.ofDays(7));
    }

    public boolean usesDurableDelivery() { return outbox != null; }

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean emailEnabled;
    private final String fromAddress;
    private final ZoneId signalTimeZone;
    private final AnalysisPreferencesService preferencesService;
    private final CandleRepository candleRepository;

    @Autowired
    public AlertNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider,
                                    @Value("${alerts.email.enabled:false}") boolean emailEnabled,
                                    @Value("${alerts.email.from:no-reply@stockwatch.local}") String fromAddress,
                                    @Value("${alerts.email.time-zone:${alerts.schedule.zone:Europe/Brussels}}") String signalTimeZone,
                                    AnalysisPreferencesService preferencesService,
                                    CandleRepository candleRepository) {
        this.mailSenderProvider = mailSenderProvider;
        this.emailEnabled = emailEnabled;
        this.fromAddress = fromAddress;
        this.signalTimeZone = ZoneId.of(signalTimeZone);
        this.preferencesService = preferencesService;
        this.candleRepository = candleRepository;
    }

    AlertNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider,
                             boolean emailEnabled,
                             String fromAddress,
                             String signalTimeZone) {
        this(mailSenderProvider, emailEnabled, fromAddress, signalTimeZone, null, null);
    }

    AlertNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider,
                             boolean emailEnabled,
                             String fromAddress,
                             String signalTimeZone,
                             CandleRepository candleRepository) {
        this(mailSenderProvider, emailEnabled, fromAddress, signalTimeZone, null, candleRepository);
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
        queueAccount(message, 86400);
    }

    public void sendPasswordSecurityCode(User user, String code, boolean reset) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user,
                reset ? "Reset your StockWatch password" : "Confirm your StockWatch password change");
        message.setText("Your one-time security code is:\n\n" + code
                + "\n\nIt expires in 5 minutes and can be used once. "
                + "If you did not request this, do not share the code and change your password.");
        queueAccount(message, 300);
    }

    public void sendSecurityNotice(User user, String subject, String body) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user, subject);
        message.setText(body + "\n\nIf this was not you, reset your password immediately.");
        queueAccount(message, 86400);
    }

    public void sendAccountDeletionNotice(User user, String cancellationUrl) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user, "Your StockWatch account is scheduled for deletion");
        message.setText("Your account has been disabled and is scheduled for permanent deletion in 7 days.\n\n"
                + "To cancel the deletion, open this one-time link before the deadline:\n" + cancellationUrl
                + "\n\nIf this was not you, cancel the deletion and reset your password immediately.");
        queueAccount(message, 86400);
    }

    public void sendAccountDeletedNotice(User user) {
        requireEmailDelivery();
        SimpleMailMessage message = baseMessage(user, "Your StockWatch account was deleted");
        message.setText("The seven-day cancellation period ended and your StockWatch account and associated account data were permanently deleted.");
        queueAccount(message, 86400);
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
                    + " pattern awaiting detection gate: " + signal.pattern()
                    + " on " + rule.getStockAsset().getTickerSymbol()
                : "StockWatch pattern detected: " + signal.pattern()
                    + " on " + rule.getStockAsset().getTickerSymbol();
        String eventDescription = endOfWaveC
                ? "End of Elliott correction (wave C)"
                : endOfWaveV
                ? "End of Elliott impulse (wave V)"
                : requiresNextCandleConfirmation
                ? "Potential one-candle reversal; awaiting the mandatory next-candle detection gate"
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
                        ? "POTENTIAL " + signal.tradeSignal() + " — AWAITING DETECTION GATE"
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
        queueInitial(message, lifecycleEvent);
        return true;
    }

    public boolean sendDevelopingElliottEmail(
            AlertRule rule,
            DetectedSignal signal,
            AlertEvent event,
            boolean invalidated) {
        return sendDevelopingElliottEmail(rule, signal, event, invalidated, false);
    }

    public boolean sendDevelopingElliottEmail(AlertRule rule, DetectedSignal signal, AlertEvent event, boolean invalidated, boolean initial) {
        if (rule == null || signal == null || event == null || event.getElliottSignalStage() == null) {
            throw new IllegalArgumentException("A developing Elliott stage is required for email delivery.");
        }
        String symbol = rule.getStockAsset().getTickerSymbol();
        String stage = switch (event.getElliottSignalStage()) {
            case WAVE_II_END -> "Wave II ending";
            case WAVE_III_END -> "Wave III ending";
            case WAVE_IV_END -> "Wave IV ending";
            case WAVE_V_END -> "Wave V ending";
            case CORRECTION_END -> "ABC correction ending";
        };
        String subject = invalidated
                ? "StockWatch Elliott count invalidated: " + symbol
                : "StockWatch Elliott cycle updated: " + stage + " on " + symbol;
        String body = invalidated
                ? """
                        A developing Elliott count was invalidated for %s.

                        Interval: %s
                        Last validated stage: %s
                        Resolution price: %.4f
                        Reason: %s

                        This count is closed. StockWatch will continue searching for a new valid origin and alternate count.
                        """.formatted(
                        symbol, rule.getInterval(), stage, signal.closePrice(),
                        event.getLifecycleResolutionReason() == null
                                ? "A hard Elliott structure boundary was crossed."
                                : event.getLifecycleResolutionReason())
                : """
                        A developing Elliott cycle was updated for %s.

                        Interval: %s
                        Current stage: %s
                        Expected next move: %s
                        Stage confirmation period: %s
                        Confirmation close: %.4f
                        Correction structure: %s
                        Forecast: %s
                        %s
                        Structure confidence: %d/100

                        Evidence
                        %s

                        The stop and target are rule-based projections, not guarantees or financial advice.
                        """.formatted(
                        symbol,
                        rule.getInterval(),
                        stage,
                        signal.tradeSignal(),
                        SignalPeriodFormatter.format(
                                signal.candleTimestamp(), rule.getInterval(), signalTimeZone),
                        signal.closePrice(),
                        event.getElliottCorrectionType() == null
                                ? "No completed parent correction yet" : event.getElliottCorrectionType(),
                        event.getElliottForecastLabel(),
                        elliottPossibleTradeSection(event),
                        signal.setupScore(),
                        SignalScoreBreakdown.formatEmail(signal.reasons(), signal.tradeSignal()));
        if (!isDevelopingElliottEmailEnabled(rule)) {
            System.out.println("[EMAIL DISABLED] Developing Elliott email suppressed for " + symbol + ".");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject(subject);
        message.setText(body);
        if (outbox == null) send(message);
        else outbox.enqueue(message, event.getId(), java.time.Duration.ofDays(7),
                initial ? "initial-alert:" + event.getId() : "elliott-stage:" + event.getId() + ":" + event.getElliottSignalStage() + ":" + signal.candleTimestamp() + ":" + invalidated,
                initial ? "INITIAL" : "FOLLOW_UP");
        return true;
    }

    private String elliottPossibleTradeSection(AlertEvent event) {
        if (event.getStopLossPrice() == null || event.getProfitTargetPrice() == null) {
            return "Possible-trade plan: unavailable for this retained count.";
        }
        if (event.getTradeEntryPrice() == null || event.getElliottTargetZoneLow() == null
                || event.getElliottTargetZoneHigh() == null
                || event.getElliottRequiredRewardRiskRatio() == null) {
            return """
                    Possible-trade entry: %.4f
                    Stop loss: %.4f
                    Projected price target: %.4f
                    """.formatted(event.getTradeEntryPrice(), event.getStopLossPrice(),
                    event.getProfitTargetPrice());
        }
        return """
                Possible-trade entry: %.4f
                Structural stop: %.4f
                Buffered stop loss: %.4f
                Fibonacci target zone: %.4f to %.4f
                Conservative target trigger: %.4f
                Target basis: %s
                Risk/reward: 1:%.2f (required 1:%.0f)
                Trade-plan status: %s
                """.formatted(
                event.getTradeEntryPrice(), event.getStructuralStopPrice(), event.getStopLossPrice(),
                event.getElliottTargetZoneLow(), event.getElliottTargetZoneHigh(),
                event.getProfitTargetPrice(), event.getElliottTargetBasis(),
                event.getRewardRiskRatio(), event.getElliottRequiredRewardRiskRatio(),
                event.isElliottTradeActionable()
                        ? "ACTIONABLE POSSIBLE TRADE" : "PROJECTION ONLY");
    }

    public boolean sendElliottTradePlanOutcomeEmail(ElliottStageTradePlan plan) {
        if (plan == null || plan.getAlertEvent() == null
                || plan.getAlertEvent().getAlertRule() == null || plan.getStatus() == null) {
            throw new IllegalArgumentException("A resolved Elliott trade plan is required.");
        }
        AlertRule rule = plan.getAlertEvent().getAlertRule();
        String symbol = rule.getStockAsset().getTickerSymbol();
        String outcome = switch (plan.getStatus()) {
            case TARGET_REACHED -> "TARGET REACHED";
            case STOPPED -> "STOP REACHED";
            case STRUCTURE_INVALIDATED -> "ELLIOTT COUNT INVALIDATED";
            case STAGE_COMPLETED -> "STAGE COMPLETED";
            case REVISED -> "PLAN REVISED";
            case ACTIVE -> "ACTIVE";
            case PROJECTION_ONLY -> "PROJECTION ONLY";
        };
        String body = """
                An Elliott possible-trade plan was resolved for %s.

                Interval: %s
                Elliott stage: %s
                Expected move: %s
                Outcome: %s
                Entry: %.4f
                Structural stop: %.4f
                Buffered stop loss: %.4f
                Fibonacci target zone: %.4f to %.4f
                Conservative target trigger: %.4f
                Target basis: %s
                Risk/reward: 1:%.2f (required 1:%.0f)
                Resolution period: %s
                Resolution close: %.4f
                Reason: %s

                A structural invalidation closes the Elliott count itself. A buffered-stop outcome closes only this possible-trade plan unless a hard Elliott rule was also broken. This is a rule-based projection, not financial advice.
                """.formatted(
                symbol,
                rule.getInterval(),
                plan.getStage(),
                plan.getExpectedMove(),
                outcome,
                plan.getEntryPrice(),
                plan.getStructuralStopPrice(),
                plan.getStopLossPrice(),
                plan.getTargetZoneLow(),
                plan.getTargetZoneHigh(),
                plan.getTargetTriggerPrice(),
                plan.getTargetBasis(),
                plan.getActualRewardRiskRatio(),
                plan.getRequiredRewardRiskRatio(),
                plan.getResolutionTimestamp() == null ? "Unavailable" : SignalPeriodFormatter.format(
                        plan.getResolutionTimestamp(), rule.getInterval(), signalTimeZone),
                plan.getResolutionClosePrice() == null ? plan.getEntryPrice() : plan.getResolutionClosePrice(),
                plan.getResolutionReason() == null ? outcome : plan.getResolutionReason());
        SignalLifecycleStatus preferenceStatus = plan.getStatus()
                == org.example.stockwatch247.model.enums.ElliottTradePlanStatus.TARGET_REACHED
                ? SignalLifecycleStatus.CONFIRMED : SignalLifecycleStatus.INVALIDATED;
        if (!emailEnabled || preferencesService != null
                && !preferencesService.allowsLifecycleEmail(
                rule.getUser(), preferenceStatus, rule.getInterval(), plan.getExpectedMove())) {
            System.out.println("[EMAIL DISABLED] Elliott trade outcome email suppressed for " + symbol + ".");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch Elliott trade plan: " + outcome + " on " + symbol);
        message.setText(body);
        send(message);
        return true;
    }

    private boolean isDevelopingElliottEmailEnabled(AlertRule rule) {
        return emailEnabled && (preferencesService == null || preferencesService.allowsNewSignalEmail(
                rule.getUser(), AlertPatternFamily.ELLIOTT_WAVE,
                rule.getInterval(), rule.getTradeSignal()));
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
                %s
                Setup score: %d/100
                Score model: %s
                Score meaning: hard structural rules passed; the base geometry score reflects tolerated soft Fibonacci/proportion deviations, then cross-pattern confluence from the preceding eight candles can add or subtract 10 points per other pattern family.

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
                harmonicStopSection(event),
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
        queueInitial(message, event);
        return true;
    }

    private String harmonicStopSection(AlertEvent event) {
        if (event == null || !event.hasHarmonicStopPlan()) {
            return "Structural stop plan: unavailable for this saved geometry.";
        }
        return """
                Stop plan version: %s
                Executable entry: %.4f (confirmation-candle close)
                PRZ completion price: %.4f
                Exact structural invalidation: %.4f
                Invalidation basis: %s
                Formula: %s
                Equity liquidity buffer: %.2f%% / %.4f
                Buffered executable stop: %.4f
                Entry-to-stop distance: %.2f%%
                Stop status: %s
                """.formatted(
                event.getTradePlanVersion(), event.getTradeEntryPrice(),
                event.getHarmonicEndpointPrice(), event.getStructuralStopPrice(),
                event.getHarmonicStopBasis(), event.getHarmonicStopFormula(),
                event.getHarmonicStopBufferPercent(), event.getHarmonicStopBufferAmount(),
                event.getStopLossPrice(), event.getHarmonicStopDistancePercent(),
                event.getHarmonicStopStatus());
    }

    public boolean sendHarmonicStopOutcomeEmail(AlertEvent event, Candle breachedCandle) {
        if (event == null || !event.hasHarmonicStopPlan() || event.getAlertRule() == null
                || breachedCandle == null || breachedCandle.getTimestamp() == null) {
            throw new IllegalArgumentException("A resolved harmonic stop plan is required.");
        }
        AlertRule rule = event.getAlertRule();
        String symbol = rule.getStockAsset().getTickerSymbol();
        String patternLabel = event.getPattern().name()
                .replace("HARMONIC_", "").replace('_', ' ');
        String body = """
                A harmonic structural stop was breached for %s.

                Ticker: %s
                Formation: %s
                Direction: %s
                Interval: %s
                Entry: %.4f
                PRZ completion price: %.4f
                Structural invalidation: %.4f
                Invalidation basis: %s
                Buffer: %.2f%% / %.4f
                Executable stop: %.4f
                Resolution period: %s
                Resolution candle high: %.4f
                Resolution candle low: %.4f
                Resolution candle close: %.4f
                Reason: %s

                Harmonic detection and the original saved geometry were not recalculated. This is a rule-based stop outcome, not financial advice.
                """.formatted(
                symbol, symbol, patternLabel, event.getTradeSignal(), rule.getInterval(),
                event.getTradeEntryPrice(), event.getHarmonicEndpointPrice(),
                event.getStructuralStopPrice(), event.getHarmonicStopBasis(),
                event.getHarmonicStopBufferPercent(), event.getHarmonicStopBufferAmount(),
                event.getStopLossPrice(), SignalPeriodFormatter.format(
                        breachedCandle.getTimestamp(), rule.getInterval(), signalTimeZone),
                breachedCandle.getHighPrice(), breachedCandle.getLowPrice(),
                breachedCandle.getClosePrice(), event.getHarmonicStopResolutionReason());
        if (!emailEnabled || preferencesService != null
                && !preferencesService.allowsLifecycleEmail(
                rule.getUser(), SignalLifecycleStatus.INVALIDATED,
                rule.getInterval(), event.getTradeSignal())) {
            System.out.println("[EMAIL DISABLED] Harmonic stop outcome email suppressed for " + symbol + ".");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch harmonic stop breached: "
                + patternLabel.toLowerCase() + " on " + symbol);
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
        if (rule.getPatternFamily() != AlertPatternFamily.CANDLESTICK) {
            return "";
        }
        if (!lifecycleEvent.hasCandlestickRiskRewardPlan()) {
            return """

                    Candlestick trade status: CURRENT PLAN UNAVAILABLE
                    This stored record predates the close-based candlestick trade-plan model. Retired confirmation-boundary statuses are not presented as current trade outcomes.
                    """;
        }
        if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern())) {
            String failureStatus = "If that next candle does not close " + confirmationDirection
                    + " the candidate candle close with a confirming "
                    + (signal.tradeSignal() == TradeSignal.BUY ? "green" : "red")
                    + " body, this candidate is REJECTED and never becomes a signal.";
            return """

                    Status: POTENTIAL %s — NO TRADE OPEN
                    Meaning: this is a candidate, not a detected trade.
                    Mandatory detection gate: the immediately following completed candle must have a %s body and close %s the candidate candle close at %.4f
                    %s
                    Planned stop loss: %.4f
                    Planned risk-to-reward: 1:%.0f
                    If accepted: the next candle's close becomes entry / candle 0, the sell target is calculated from that entry and stop, and candle 8 becomes the time stop.
                    Close rule: only completed-candle closes count; wick touches do not open or close a trade.
                    """.formatted(
                    signal.tradeSignal(),
                    signal.tradeSignal() == TradeSignal.BUY ? "green" : "red",
                    confirmationDirection,
                    lifecycleEvent.getConfirmationTriggerPrice(),
                    failureStatus,
                    lifecycleEvent.getStopLossPrice(),
                    lifecycleEvent.getRewardRiskRatio());
        }
        if (lifecycleEvent.getTradeEntryPrice() != null
                && lifecycleEvent.getProfitTargetPrice() != null) {
            return """

                    Status: DETECTED — TRADE OPEN
                    Meaning: the candlestick trade is active until its target, stop, or candle 8 close.
                    Trade entry: %.4f
                    Stop loss: %.4f
                    Price to sell / profit target: %.4f
                    Risk-to-reward: 1:%.0f
                    %s
                    Time stop: candle 8 after detection (the signal candle is candle 0)
                    Exit rule: completed-candle closes only; intraperiod wick touches do not close the trade.
                    Lifecycle note: CONFIRMED means the target closed successfully, INVALIDATED means the stop closed the trade, and EXPIRED means candle 8 closed the trade.
                    """.formatted(
                    lifecycleEvent.getTradeEntryPrice(),
                    lifecycleEvent.getStopLossPrice(),
                    lifecycleEvent.getProfitTargetPrice(),
                    lifecycleEvent.getRewardRiskRatio(),
                    atrCircuitBreakerDescription(lifecycleEvent));
        }
        return """

                Candlestick trade status: CURRENT PLAN INCOMPLETE
                The current risk/reward plan is missing its entry or target, so no retired boundary-based status description is substituted.
                """;
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
        boolean candlestickSignal = rule.getPatternFamily() == AlertPatternFamily.CANDLESTICK;
        if (candlestickSignal && status != SignalLifecycleStatus.DETECTED) {
            return sendCandlestickTradeLifecycleEmail(event, rule, symbol);
        }
        if (status == SignalLifecycleStatus.DETECTED) {
            throw new IllegalArgumentException("A terminal signal lifecycle status is required.");
        }
        String outcome = switch (status) {
            case CONFIRMED -> event.hasCandlestickRiskRewardPlan()
                    ? "The completed-candle close reached the profit target and the trade closed successfully."
                    : "The expected close-based follow-through occurred.";
            case REJECTED -> "The candidate failed its mandatory next-candle gate and never became a signal.";
            case INVALIDATED -> event.hasCandlestickRiskRewardPlan()
                    ? "A completed-candle close reached the configured stop loss."
                    : elliottSignal
                    ? "The stored Elliott structure stopped satisfying its hard wave rules before confirmation."
                    : "Price closed beyond the opposite lifecycle boundary before confirmation.";
            case EXPIRED -> event.hasCandlestickRiskRewardPlan()
                    ? "Neither target nor stop was reached, so the trade closed at candle 8's close."
                    : "The observation window ended without confirmation or invalidation.";
            case POTENTIAL, DETECTED -> throw new IllegalStateException("A terminal outcome is required.");
        };
        String lifecycleType = elliottSignal ? "Elliott Wave" : "Candlestick";
        String rangeLabel = elliottSignal ? "Wave structure range" : "Pattern range";
        String expectedDirection = event.getTradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "above" : "below";
        String invalidationDirection = event.getTradeSignal()
                == org.example.stockwatch247.model.enums.TradeSignal.BUY ? "below" : "above";
        String invalidationLine = event.hasCandlestickRiskRewardPlan()
                ? "Stop loss: completed-candle close at %.4f".formatted(event.getStopLossPrice())
                : elliottSignal
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
        String confirmationLine = event.hasCandlestickRiskRewardPlan()
                ? "Successful trade target: completed-candle close at %.4f".formatted(
                        event.getProfitTargetPrice())
                : immediateConfirmationRequired
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
        String tradePlanLine = event.hasCandlestickRiskRewardPlan()
                ? "Trade plan: entry %.4f | stop %.4f | target %.4f | risk-to-reward 1:%.0f"
                        .formatted(event.getTradeEntryPrice(), event.getStopLossPrice(),
                                event.getProfitTargetPrice(), event.getRewardRiskRatio())
                        + "\n" + atrCircuitBreakerDescription(event)
                : percentageLifecycleRules(event);
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
                tradePlanLine,
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

    private boolean sendCandlestickTradeLifecycleEmail(AlertEvent event,
                                                       AlertRule rule,
                                                       String symbol) {
        SignalLifecycleStatus status = event.getLifecycleStatus();
        if (status != SignalLifecycleStatus.CONFIRMED
                && status != SignalLifecycleStatus.INVALIDATED
                && status != SignalLifecycleStatus.EXPIRED) {
            throw new IllegalArgumentException("A terminal candlestick trade status is required.");
        }
        if (!completeCandlestickTradePlan(event)) {
            return sendLegacyCandlestickLifecycleNotice(event, rule, symbol);
        }

        double targetReturn = directionalReturnPercent(
                event.getTradeSignal(), event.getTradeEntryPrice(), event.getProfitTargetPrice());
        double exitReturn = directionalReturnPercent(
                event.getTradeSignal(), event.getTradeEntryPrice(), event.getResolutionClosePrice());
        Double favorableMove = status == SignalLifecycleStatus.CONFIRMED
                ? targetReturn
                : mostFavorableCompletedCloseMove(event, rule);
        String favorableLine = favorableMove == null
                ? "Most favorable completed-close move: unavailable because the completed trade path is not cached."
                : "Most favorable completed-close move: %+.2f%%".formatted(favorableMove);
        String statusMeaning = switch (status) {
            case CONFIRMED -> "CONFIRMED means the profit target closed successfully and the trade ended at its planned R:R target.";
            case INVALIDATED -> "INVALIDATED means a completed candle closed at or beyond the configured stop loss and ended the trade.";
            case EXPIRED -> "EXPIRED means neither target nor stop closed first, so candle 8's completed close ended the trade.";
            default -> throw new IllegalStateException("Unexpected candlestick trade status: " + status);
        };
        String resultReturnLine = status == SignalLifecycleStatus.CONFIRMED
                ? "Trade result return: %+.2f%% (fixed at the exact entry-to-target return, even if the resolution close overshot the target)"
                        .formatted(targetReturn)
                : "Entry-to-exit return: %+.2f%%".formatted(exitReturn);
        long entryTimestamp = event.getDetectionCandleTimestamp() == null
                ? event.getSignalCandleTimestamp()
                : event.getDetectionCandleTimestamp();
        String body = """
                Candlestick trade update for %s.

                Status: %s
                Status meaning: %s
                Pattern: %s
                Direction: %s
                Interval: %s
                Original signal period: %s

                Close-based trade plan
                Entry / candle 0: %.4f on %s
                Stop loss: %.4f
                Price to sell / profit target: %.4f
                Risk-to-reward: 1:%.0f
                %s
                Time stop: candle %d close
                Decision rule: completed-candle closes only; intraperiod wick touches do not close the trade.

                Trade resolution
                Resolution candle: %s
                Resolution candle number: %d
                Resolution close: %.4f
                %s
                Target return: %+.2f%%
                %s

                CONFIRMED = target reached successfully. INVALIDATED = stop loss hit. EXPIRED = candle 8 time stop.
                This is informational and excludes fees, slippage, taxes, dividends, and position sizing.
                """.formatted(
                symbol,
                status,
                statusMeaning,
                event.getPattern(),
                event.getTradeSignal(),
                rule.getInterval(),
                SignalPeriodFormatter.format(
                        event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone),
                event.getTradeEntryPrice(),
                SignalPeriodFormatter.format(entryTimestamp, rule.getInterval(), signalTimeZone),
                event.getStopLossPrice(),
                event.getProfitTargetPrice(),
                event.getRewardRiskRatio(),
                atrCircuitBreakerDescription(event),
                CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES,
                SignalPeriodFormatter.format(
                        event.getResolutionCandleTimestamp(), rule.getInterval(), signalTimeZone),
                event.getResolutionCandleOffset(),
                event.getResolutionClosePrice(),
                resultReturnLine,
                targetReturn,
                favorableLine);

        if (!isSignalLifecycleEmailEnabled(event)) {
            System.out.println("[EMAIL DISABLED] " + status
                    + " candlestick trade email suppressed for " + symbol + ".");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch candlestick trade " + status.name().toLowerCase(Locale.ROOT)
                + ": " + event.getPattern() + " on " + symbol);
        message.setText(body);
        send(message);
        return true;
    }

    private String atrCircuitBreakerDescription(AlertEvent event) {
        if (event == null || !Boolean.TRUE.equals(event.getAtrCircuitBreakerApplied())) {
            return "ATR circuit breaker: not activated";
        }
        String atrSource = event.getAtrCircuitBreakerValue() == null
                ? "ATR was unavailable, so the percentage cap supplied the risk distance"
                : "frozen ATR(%d) %.4f × %.2f".formatted(
                        event.getAtrCircuitBreakerPeriod(),
                        event.getAtrCircuitBreakerValue(),
                        event.getAtrCircuitBreakerMultiplier());
        return "ATR circuit breaker: ACTIVE | structural invalidation %.4f | original configured stop %.4f | %s | activation limit %.2f%%"
                .formatted(
                        event.getStructuralStopPrice(),
                        event.getPreCircuitBreakerStopPrice(),
                        atrSource,
                        event.getAtrCircuitBreakerThresholdPercent());
    }

    private boolean completeCandlestickTradePlan(AlertEvent event) {
        return event.hasCandlestickRiskRewardPlan()
                && event.getTradeEntryPrice() != null
                && event.getProfitTargetPrice() != null
                && event.getResolutionCandleTimestamp() != null
                && event.getResolutionCandleOffset() != null
                && event.getResolutionClosePrice() != null;
    }

    private Double mostFavorableCompletedCloseMove(AlertEvent event, AlertRule rule) {
        if (candleRepository == null || event.getResolutionCandleTimestamp() == null) return null;
        long entryTimestamp = event.getDetectionCandleTimestamp() == null
                ? event.getSignalCandleTimestamp()
                : event.getDetectionCandleTimestamp();
        List<Candle> tradeCandles = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanAndTimestampLessThanOrderByTimestampAsc(
                        rule.getStockAsset().getTickerSymbol(),
                        apiInterval(rule),
                        entryTimestamp,
                        event.getResolutionCandleTimestamp() + 1,
                        PageRequest.of(0, CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES))
                .stream()
                .filter(candle -> candle.getClosePrice() != null
                        && Double.isFinite(candle.getClosePrice()))
                .toList();
        if (tradeCandles.isEmpty()) return 0.0;
        double favorableClose = event.getTradeSignal() == TradeSignal.BUY
                ? tradeCandles.stream().mapToDouble(Candle::getClosePrice).max()
                        .orElse(event.getTradeEntryPrice())
                : tradeCandles.stream().mapToDouble(Candle::getClosePrice).min()
                        .orElse(event.getTradeEntryPrice());
        return Math.max(0.0, directionalReturnPercent(
                event.getTradeSignal(), event.getTradeEntryPrice(), favorableClose));
    }

    private double directionalReturnPercent(TradeSignal direction, double entry, double exit) {
        double marketReturn = entry == 0.0 ? 0.0 : ((exit - entry) / entry) * 100.0;
        return direction == TradeSignal.SELL ? -marketReturn : marketReturn;
    }

    private String apiInterval(AlertRule rule) {
        return switch (rule.getInterval()) {
            case FIFTEEN_MINUTE -> "15min";
            case ONE_HOUR -> "1h";
            case FOUR_HOUR -> "4h";
            case DAILY -> "1d";
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            case YEARLY -> "1y";
            case ALL_TIME -> "1mo";
        };
    }

    private boolean sendLegacyCandlestickLifecycleNotice(AlertEvent event,
                                                          AlertRule rule,
                                                          String symbol) {
        if (!isSignalLifecycleEmailEnabled(event)) return false;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(rule.getUser().getEmail());
        message.setSubject("StockWatch candlestick record update unavailable: "
                + event.getPattern() + " on " + symbol);
        message.setText("""
                A stored candlestick record changed for %s, but it predates the current close-based candlestick trade plan.

                Current trade status: UNAVAILABLE
                Pattern: %s
                Direction: %s
                Interval: %s
                Original signal period: %s

                The retired confirmation-boundary result is not being relabeled as CONFIRMED, INVALIDATED, or EXPIRED.
                Those current statuses require a stored entry, configured stop loss, interval R:R target, and candle 8 time stop.
                """.formatted(
                symbol,
                event.getPattern(),
                event.getTradeSignal(),
                rule.getInterval(),
                SignalPeriodFormatter.format(
                        event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone)));
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
        String body;
        if (!event.hasCandlestickRiskRewardPlan()) {
            body = """
                    Candlestick candidate update for %s.

                    Current trade status: UNAVAILABLE
                    Pattern: %s
                    Direction: %s
                    Candidate period: %s

                    This stored candidate predates the close-based candlestick trade-plan model. Retired confirmation-boundary results are not substituted for the current close-based trade statuses.
                    """.formatted(
                    symbol,
                    event.getPattern(),
                    event.getTradeSignal(),
                    SignalPeriodFormatter.format(
                            event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone));
        } else if (detected && event.getTradeEntryPrice() != null
                && event.getProfitTargetPrice() != null) {
            body = """
                     One-candle candidate accepted for %s.

                    Status: DETECTED — TRADE OPEN
                    Status meaning: the mandatory gate passed and this candle's close is entry / candle 0.
                    Pattern: %s
                    Direction: %s
                    Candidate period: %s
                    Detection candle: %s
                    Detection close: %.4f

                     The mandatory next-candle gate passed. This is now a real signal.
                     Trade entry: %.4f
                     Stop loss: %.4f
                    Price to sell / profit target: %.4f
                     Risk-to-reward: 1:%.0f
                     %s
                     Time stop: candle 8 after detection (this detection candle is candle 0)
                    Exit rule: completed-candle closes only; wick touches do not close the trade.

                     CONFIRMED means the target closed successfully, INVALIDATED means the stop was hit, and EXPIRED means candle 8 closed the trade.
                    """.formatted(
                        symbol,
                        event.getPattern(),
                        event.getTradeSignal(),
                        SignalPeriodFormatter.format(
                                event.getSignalCandleTimestamp(), rule.getInterval(), signalTimeZone),
                        gatePeriod,
                        gateClose,
                        event.getTradeEntryPrice(),
                         event.getStopLossPrice(),
                         event.getProfitTargetPrice(),
                        event.getRewardRiskRatio(),
                        atrCircuitBreakerDescription(event));
        } else if (detected) {
            body = """
                    One-candle candidate accepted for %s, but its current trade plan is incomplete.

                    Current trade status: UNAVAILABLE
                    Pattern: %s
                    Direction: %s
                    Detection candle: %s

                    No retired confirmation-boundary status is substituted.
                    """.formatted(symbol, event.getPattern(), event.getTradeSignal(), gatePeriod);
        } else {
            body = """
                     One-candle candidate rejected for %s.

                    Status: REJECTED — NO TRADE OPENED
                    Status meaning: the mandatory next-candle gate failed, so this candidate never became a detected trade.
                     Pattern candidate: %s
                    Potential direction: %s
                    Candidate period: %s
                    Gate candle: %s
                    Gate close: %.4f

                     The immediately following candle did not provide the required %s-body close %s the candidate close at %.4f.
                    Planned stop: %.4f
                    Planned risk-to-reward if accepted: 1:%.0f
                    No entry, profit target, time-stop trade, or trade return exists because the trade never opened.
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
                        event.getClosePrice(),
                        event.getStopLossPrice(),
                        event.getRewardRiskRatio());
        }

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

    public boolean sendTechnicalOutlookChangeEmail(User user,
                                                   org.example.stockwatch247.model.enums.TimeInterval interval,
                                                   String subject,
                                                   String body) {
        if (!emailEnabled || preferencesService != null
                && !preferencesService.allowsTechnicalOutlookEmail(user, interval)) {
            System.out.println("[EMAIL DISABLED] Technical outlook change email suppressed for "
                    + user.getEmail() + " " + interval + ".");
            return false;
        }
        SimpleMailMessage message = baseMessage(user, subject);
        message.setText(body);
        send(message);
        return true;
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
