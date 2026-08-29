package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.ClaimedDelivery;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertNotificationServiceTest {

    @Test
    void developingElliottEmailIncludesStageCorrectionStopAndNextWaveTarget() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        rule.setPatternFamily(AlertPatternFamily.ELLIOTT_WAVE);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.ELLIOTT_BULLISH_WAVE_II_END, TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE, 86, List.of("Wave I and II subdivisions validated."),
                Instant.parse("2026-07-20T00:00:00Z").getEpochSecond(), 106.0);
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(signal.pattern());
        event.setTradeSignal(signal.tradeSignal());
        event.setSignalCandleTimestamp(signal.candleTimestamp());
        event.setElliottSignalStage(ElliottSignalStage.WAVE_II_END);
        event.setElliottCorrectionType("Corrective A-B-C");
        event.setElliottForecastLabel("Projected Wave III");
        event.setStopLossPrice(98.0);
        event.setProfitTargetPrice(138.0);

        service.sendDevelopingElliottEmail(rule, signal, event, false);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("Wave II ending", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Current stage: Wave II ending",
                "Correction structure: Corrective A-B-C",
                "Stop loss: 98.0000",
                "Projected price target: 138.0000",
                "Projected Wave III");
    }

    @Test
    void completedCorrectionEmailUsesAbcStageNameAndValidatedStructure() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        rule.setPatternFamily(AlertPatternFamily.ELLIOTT_WAVE);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.ELLIOTT_BULLISH_CORRECTION, TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE, 88, List.of("Wave C is motive (5)."),
                Instant.parse("2026-07-20T00:00:00Z").getEpochSecond(), 128.0);
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setElliottSignalStage(ElliottSignalStage.CORRECTION_END);
        event.setElliottCorrectionType("Flat 3-3-5");
        event.setElliottForecastLabel("Projected primary-trend resumption toward Wave V");
        event.setStopLossPrice(124.0);
        event.setProfitTargetPrice(155.0);

        service.sendDevelopingElliottEmail(rule, signal, event, false);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("ABC correction ending", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Current stage: ABC correction ending",
                "Correction structure: Flat 3-3-5",
                "Projected primary-trend resumption toward Wave V");
    }

    @Test
    void harmonicEmailContainsCompletionConfirmationGeometryRatiosAndScoreMeaning() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        rule.setPatternFamily(AlertPatternFamily.HARMONIC_FORMATION);
        long endpoint = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
        long confirmation = Instant.parse("2026-07-21T00:00:00Z").getEpochSecond();
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.HARMONIC_GARTLEY, TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE, 94,
                List.of("Soft ratio compliance +94/100: all hard rules passed"),
                confirmation, 121.75);
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(signal.pattern());
        event.setTradeSignal(signal.tradeSignal());
        event.setSignalCandleTimestamp(confirmation);
        event.setHarmonicEndpointTimestamp(endpoint);
        event.setHarmonicEndpointPrice(120.5);
        event.setHarmonicPointsSnapshot("X|1752796800|100.0000000000|LOW\nD|1753056000|120.5000000000|LOW");
        event.setHarmonicMeasurementsSnapshot("AD_XA|0.7860000000\nB_XA|0.6180000000");
        event.setTradePlanVersion(HarmonicStopPlanPolicy.VERSION);
        event.setTradeEntryPrice(121.75);
        event.setStructuralStopPrice(100.0);
        event.setStopLossPrice(99.5);
        event.setHarmonicStopBasis("Point X / 1.0 XA");
        event.setHarmonicStopFormula("Internal Gartley invalidation at Point X");
        event.setHarmonicStopBufferPercent(.5);
        event.setHarmonicStopBufferAmount(.5);
        event.setHarmonicStopDistancePercent(18.2752);
        event.setHarmonicStopStatus("ACTIVE");

        service.sendSignalEmail(rule, signal, event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("harmonic gartley", "BUY", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Ticker: AAPL",
                "Formation: GARTLEY",
                "Interval: DAILY",
                "Completion point: D",
                "Completion price: 120.5000",
                "Confirmation candle close: 121.7500",
                "Executable entry: 121.7500",
                "Exact structural invalidation: 100.0000",
                "Equity liquidity buffer: 0.50% / 0.5000",
                "Buffered executable stop: 99.5000",
                "Setup score: 94/100",
                "hard structural rules passed",
                "- B/XA: 0.6180",
                "Score model: HARMONIC_V3");
    }

    @Test
    void harmonicStopOutcomeEmailExplainsTheObservedBreachWithoutInventingATarget() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        rule.setPatternFamily(AlertPatternFamily.HARMONIC_FORMATION);
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(CandlePattern.HARMONIC_CRAB);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(100L);
        event.setTradePlanVersion(HarmonicStopPlanPolicy.VERSION);
        event.setTradeEntryPrice(85.0);
        event.setHarmonicEndpointPrice(83.0);
        event.setStructuralStopPrice(80.0);
        event.setStopLossPrice(79.6);
        event.setHarmonicStopBasis("2.0 XA extension");
        event.setHarmonicStopBufferPercent(.5);
        event.setHarmonicStopBufferAmount(.4);
        event.setHarmonicStopStatus("STOPPED");
        event.setHarmonicStopResolutionReason(
                "The completed candle's low 79.5000 breached the buffered harmonic stop 79.6000.");
        Candle breach = new Candle("AAPL", "1d", 101L, 81, 82, 79.5, 80.0, 1_000L);

        service.sendHarmonicStopOutcomeEmail(event, breach);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("harmonic stop breached", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Formation: CRAB", "Entry: 85.0000", "Structural invalidation: 80.0000",
                "Invalidation basis: 2.0 XA extension", "Executable stop: 79.6000",
                "Resolution candle low: 79.5000", "not financial advice");
        assertThat(messageCaptor.getValue().getText()).doesNotContain("Profit target", "Risk-to-reward");
    }

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
                "Score model: CANDLE_V4_EXPERIMENTAL",
                "has not demonstrated stable out-of-sample predictive ordering",
                "Score breakdown\n- Evidence\n  - Observation: Bullish body engulfs",
                "Research horizon: 10 trading sessions",
                "Historical evaluation window only \u2014 not a recommended holding period",
                "Signal candle period: 10 Jul 2026");
    }

    @Test
    void signalEmailPresentsScoredEvidenceAsNestedBullets() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.SELL);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.BEARISH_HARAMI,
                TradeSignal.SELL,
                SignalStength.MEDIUM_CONFIDENCE,
                54,
                List.of(
                        "Pattern quality +24/25: all mandatory Bearish harami geometry and prior-trend "
                                + "rules passed (geometry 25/25, trend 23/25); established uptrend across "
                                + "5 completed pre-pattern candles",
                        "Trend indicators +0/20: weekly profile: EMA(8)/EMA(21) order was not aligned "
                                + "with the signal; fast EMA slope was not aligned with the signal; "
                                + "slow EMA slope was not aligned with the signal; "
                                + "MACD(8,21,5) line/signal was not aligned with the signal; "
                                + "MACD histogram change was not aligned with the signal",
                        "Momentum +15/15: weekly profile: RSI(10) was 71.3 "
                                + "(+5/5 for directional reversal location); RSI change was aligned with the signal"
                ),
                Instant.parse("2026-07-20T00:00:00Z").getEpochSecond(),
                100.0
        );

        service.sendSignalEmail(rule, signal);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getText()).contains(
                "- Pattern quality: 24/25 (partial support)",
                "  - Pattern rules: All mandatory Bearish harami geometry and prior-trend rules passed",
                "  - Prior trend: Established uptrend across 5 completed pre-pattern candles.",
                "- Trend indicators: 0/20 (no supporting points)",
                "  - Indicator profile: Weekly settings were used.",
                "  - EMA(8) vs EMA(21): EMA(8)/EMA(21) order did not support the bearish direction.",
                "  - EMA(8) slope: Fast EMA slope did not support the bearish direction.",
                "  - EMA(21) slope: Slow EMA slope did not support the bearish direction.",
                "  - MACD(8, 21, 5) histogram: MACD histogram change did not support the bearish direction.",
                "- Momentum: 15/15 (full score)",
                "  - RSI(10) level [5/5 pts]: RSI(10) was 71.3.",
                "  - RSI(10) change: RSI change supported the bearish direction."
        );
    }

    @Test
    void potentialCandlestickEmailExplainsThePlannedCloseBasedTrade() {
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
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.POTENTIAL);
        event.setConfirmationTriggerPrice(100.0);
        applyCandidateTradePlan(event, 95.0, 2.0);

        service.sendSignalEmail(rule, signal, event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getText()).contains(
                "Potential one-candle reversal",
                "POTENTIAL BUY — AWAITING DETECTION GATE",
                "NO TRADE OPEN",
                "must have a green body and close above the candidate candle close at 100.0000",
                "candidate is REJECTED and never becomes a signal",
                "Planned stop loss: 95.0000",
                "Planned risk-to-reward: 1:2",
                "next candle's close becomes entry / candle 0",
                "only completed-candle closes count");
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
        event.setDetectionCandleTimestamp(Instant.parse("2026-07-21T00:00:00Z").getEpochSecond());
        event.setDetectionClosePrice(100.0);
        applyOpenTradePlan(event, 100.0, 95.0, 110.0, 2.0);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-22T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(1);
        event.setResolutionClosePrice(111.0);

        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("confirmed", "HAMMER", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Status: CONFIRMED",
                "CONFIRMED means the profit target closed successfully",
                "Entry / candle 0: 100.0000",
                "Stop loss: 95.0000",
                "Price to sell / profit target: 110.0000",
                "Risk-to-reward: 1:2",
                "Time stop: candle 8 close",
                "Resolution candle number: 1",
                "Resolution close: 111.0000",
                "Trade result return: +10.00% (fixed at the exact entry-to-target return",
                "Most favorable completed-close move: +10.00%",
                "CONFIRMED = target reached successfully");
    }

    @Test
    void includesThePersistedRiskRewardPlanInDetectionAndSuccessfulExitEmails() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        DetectedSignal signal = new DetectedSignal(
                CandlePattern.BULLISH_ENGULFING,
                TradeSignal.BUY,
                SignalStength.HIGH_CONFIDENCE,
                88,
                List.of("validated bullish engulfing"),
                Instant.parse("2026-07-20T00:00:00Z").getEpochSecond(),
                100.0);
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.DETECTED);
        event.setPattern(signal.pattern());
        event.setTradeEntryPrice(100.0);
        event.setStopLossPrice(95.0);
        event.setProfitTargetPrice(110.0);
        event.setRewardRiskRatio(2.0);
        event.setTradePlanVersion("CANDLE_RR_V1");
        event.setConfirmationTriggerPrice(110.0);
        event.setInvalidationPrice(95.0);
        event.setConfirmationWindowCandles(8);

        service.sendSignalEmail(rule, signal, event);
        event.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-24T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(4);
        event.setResolutionClosePrice(110.0);
        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues().get(0).getText()).contains(
                "Trade entry: 100.0000",
                "Stop loss: 95.0000",
                "Price to sell / profit target: 110.0000",
                "Risk-to-reward: 1:2",
                "Time stop: candle 8",
                "completed-candle closes only");
        assertThat(messageCaptor.getAllValues().get(1).getText()).contains(
                "CONFIRMED means the profit target closed successfully",
                "Entry / candle 0: 100.0000",
                "Stop loss: 95.0000",
                "Price to sell / profit target: 110.0000",
                "Resolution candle number: 4",
                "Resolution close: 110.0000",
                "Trade result return: +10.00%",
                "Most favorable completed-close move: +10.00%");
    }

    @Test
    void explainsThatFailedNextCandleGateRejectsTheCandidateRatherThanInvalidatingASignal() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.SELL);
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.REJECTED);
        event.setPattern(CandlePattern.HANGING_MAN);
        event.setClosePrice(100.0);
        event.setConfirmationTriggerPrice(100.0);
        applyCandidateTradePlan(event, 105.0, 2.0);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-21T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(1);
        event.setResolutionClosePrice(101.0);

        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("candidate rejected", "HANGING_MAN", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Status: REJECTED — NO TRADE OPENED",
                "Potential direction: SELL",
                "required red-body close below the candidate close at 100.0000",
                "candidate never became a detected trade",
                "Planned stop: 105.0000",
                "No entry, profit target, time-stop trade, or trade return exists");
    }

    @Test
    void explainsThatAPassedNextCandleGateCreatesTheRealSignal() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.BUY);
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.DETECTED);
        event.setPattern(CandlePattern.HAMMER);
        event.setDetectionCandleTimestamp(Instant.parse("2026-07-21T00:00:00Z").getEpochSecond());
        event.setDetectionClosePrice(101.0);
        applyOpenTradePlan(event, 101.0, 95.0, 113.0, 2.0);

        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("signal detected", "HAMMER", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Status: DETECTED — TRADE OPEN",
                "mandatory next-candle gate passed",
                "This is now a real signal",
                "Detection close: 101.0000",
                "Trade entry: 101.0000",
                "Stop loss: 95.0000",
                "Price to sell / profit target: 113.0000",
                "Time stop: candle 8",
                "wick touches do not close the trade");
    }

    @Test
    void invalidatedEmailIncludesStopExitReturnAndBestCompletedCloseMove() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels", candleRepository);
        AlertRule rule = dailyRule(TradeSignal.BUY);
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.INVALIDATED);
        event.setPattern(CandlePattern.BULLISH_ENGULFING);
        applyOpenTradePlan(event, 100.0, 95.0, 110.0, 2.0);
        long entry = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
        long favorable = Instant.parse("2026-07-21T00:00:00Z").getEpochSecond();
        long exit = Instant.parse("2026-07-22T00:00:00Z").getEpochSecond();
        event.setDetectionCandleTimestamp(entry);
        event.setDetectionClosePrice(100.0);
        event.setResolutionCandleTimestamp(exit);
        event.setResolutionCandleOffset(2);
        event.setResolutionClosePrice(94.0);
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampGreaterThanAndTimestampLessThanOrderByTimestampAsc(
                "AAPL", "1d", entry, exit + 1,
                PageRequest.of(0, CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES)))
                .thenReturn(List.of(
                        candle(favorable, 104.0),
                        candle(exit, 94.0)));

        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("invalidated", "BULLISH_ENGULFING", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Status: INVALIDATED",
                "configured stop loss and ended the trade",
                "Stop loss: 95.0000",
                "Entry-to-exit return: -6.00%",
                "Most favorable completed-close move: +4.00%",
                "INVALIDATED = stop loss hit");
    }

    @Test
    void expiredEmailIncludesCandleEightExitAndBestCompletedCloseMove() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        CandleRepository candleRepository = mock(CandleRepository.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels", candleRepository);
        AlertRule rule = dailyRule(TradeSignal.SELL);
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.EXPIRED);
        event.setPattern(CandlePattern.BEARISH_ENGULFING);
        applyOpenTradePlan(event, 100.0, 105.0, 90.0, 2.0);
        long entry = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
        long favorable = Instant.parse("2026-07-23T00:00:00Z").getEpochSecond();
        long exit = Instant.parse("2026-07-28T00:00:00Z").getEpochSecond();
        event.setDetectionCandleTimestamp(entry);
        event.setDetectionClosePrice(100.0);
        event.setResolutionCandleTimestamp(exit);
        event.setResolutionCandleOffset(8);
        event.setResolutionClosePrice(102.0);
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampGreaterThanAndTimestampLessThanOrderByTimestampAsc(
                "AAPL", "1d", entry, exit + 1,
                PageRequest.of(0, CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES)))
                .thenReturn(List.of(
                        candle(favorable, 94.0),
                        candle(exit, 102.0)));

        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("expired", "BEARISH_ENGULFING", "AAPL");
        assertThat(messageCaptor.getValue().getText()).contains(
                "Status: EXPIRED",
                "candle 8's completed close ended the trade",
                "Time stop: candle 8 close",
                "Entry-to-exit return: -2.00%",
                "Most favorable completed-close move: +6.00%",
                "EXPIRED = candle 8 time stop");
    }

    @Test
    void labelsElliottWaveLifecycleFollowUpAndStructuralRange() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        AlertNotificationService service = new AlertNotificationService(
                provider, true, "alerts@stockwatch.test", "Europe/Brussels");
        AlertRule rule = dailyRule(TradeSignal.SELL);
        rule.setPatternFamily(AlertPatternFamily.ELLIOTT_WAVE);
        rule.setInterval(TimeInterval.WEEKLY);
        AlertEvent event = trackedEvent(rule, SignalLifecycleStatus.INVALIDATED);
        event.setPattern(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
        event.setPatternHigh(125.0);
        event.setPatternLow(94.0);
        event.setConfirmationTriggerPrice(112.0);
        event.setInvalidationPrice(125.0);
        event.setConfirmationWindowCandles(10);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-27T00:00:00Z").getEpochSecond());
        event.setResolutionCandleOffset(2);
        event.setResolutionClosePrice(126.0);

        service.sendSignalLifecycleEmail(event);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getText()).contains(
                "Elliott Wave lifecycle update",
                "Wave structure range: 94.0000 to 125.0000",
                "Confirmation trigger: close below 112.0000",
                "Structural invalidation boundary: 125.0000",
                "Observation window: 10 completed weekly candles");
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
        AlertEvent lifecycleEvent = trackedEvent(rule, SignalLifecycleStatus.DETECTED);
        lifecycleEvent.setPattern(signal.pattern());
        lifecycleEvent.setConfirmationTriggerPrice(180.0);
        lifecycleEvent.setInvalidationPrice(null);
        lifecycleEvent.setConfirmationWindowCandles(10);
        lifecycleEvent.setElliottEndpointPrice(205.0);

        service.sendSignalEmail(rule, signal, lifecycleEvent);

        org.mockito.ArgumentCaptor<SimpleMailMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("Elliott wave V completed", "SELL", "SAP.DE");
        assertThat(messageCaptor.getValue().getText()).contains(
                "End of Elliott impulse (wave V)",
                "Signal candle period: July 2026",
                "Latest Wave V/C endpoint: 205.0000",
                "ordinary Wave V/C extensions revise the endpoint",
                "without another detection email");
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
        event.setConfirmationWindowCandles(10);
        return event;
    }

    private void applyCandidateTradePlan(AlertEvent event, double stop, double rewardRisk) {
        event.setStopLossPrice(stop);
        event.setRewardRiskRatio(rewardRisk);
        event.setTradePlanVersion(CandlestickSignalLifecyclePolicy.RISK_REWARD_VERSION);
        event.setConfirmationWindowCandles(CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES);
    }

    private void applyOpenTradePlan(AlertEvent event,
                                    double entry,
                                    double stop,
                                    double target,
                                    double rewardRisk) {
        applyCandidateTradePlan(event, stop, rewardRisk);
        event.setTradeEntryPrice(entry);
        event.setProfitTargetPrice(target);
        event.setConfirmationTriggerPrice(target);
        event.setInvalidationPrice(stop);
    }

    private Candle candle(long timestamp, double close) {
        return new Candle("AAPL", "1d", timestamp, close, close, close, close, 1_000L);
    }
}
