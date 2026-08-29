package org.example.stockwatch247.model;

import jakarta.persistence.*;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "alert_events", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"alert_rule_id", "pattern", "signal_candle_timestamp"})
})
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_rule_id", nullable = false)
    private AlertRule alertRule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(64)")
    private CandlePattern pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_signal", nullable = false)
    private TradeSignal tradeSignal;

    @Column(name = "signal_candle_timestamp", nullable = false)
    private Long signalCandleTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_strength")
    private SignalStength signalStrength;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Column(name = "factory_confidence_score")
    private Integer factoryConfidenceScore;

    @Column(name = "analysis_profile_version", length = 32)
    private String analysisProfileVersion;

    @Column(name = "analysis_profile_snapshot", columnDefinition = "text")
    private String analysisProfileSnapshot;

    @Column(name = "elliott_v1_eligibility_score")
    private Integer elliottV1EligibilityScore;

    @Column(name = "score_version", nullable = false, length = 32)
    private String scoreVersion = "LEGACY_UNVERSIONED";

    @Column(name = "confidence_reasons", columnDefinition = "text")
    private String confidenceReasonsPayload;

    @Column(name = "close_price")
    private Double closePrice;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    @Column(name = "initial_email_sent_at")
    private LocalDateTime initialEmailSentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, columnDefinition = "varchar(16)")
    private SignalLifecycleStatus lifecycleStatus = SignalLifecycleStatus.DETECTED;

    @Column(name = "pattern_high")
    private Double patternHigh;

    @Column(name = "pattern_low")
    private Double patternLow;

    @Column(name = "confirmation_trigger_price")
    private Double confirmationTriggerPrice;

    @Column(name = "invalidation_price")
    private Double invalidationPrice;

    @Column(name = "trade_entry_price")
    private Double tradeEntryPrice;

    @Column(name = "stop_loss_price")
    private Double stopLossPrice;

    @Column(name = "profit_target_price")
    private Double profitTargetPrice;

    @Column(name = "reward_risk_ratio")
    private Double rewardRiskRatio;

    @Column(name = "trade_plan_version", length = 32)
    private String tradePlanVersion;

    @Column(name = "structural_stop_price")
    private Double structuralStopPrice;

    @Column(name = "stop_loss_mode", length = 32)
    private String stopLossMode;

    @Column(name = "stop_loss_value_percent")
    private Double stopLossValuePercent;

    @Column(name = "pre_circuit_breaker_stop_price")
    private Double preCircuitBreakerStopPrice;

    @Column(name = "atr_circuit_breaker_enabled")
    private Boolean atrCircuitBreakerEnabled;

    @Column(name = "atr_circuit_breaker_applied")
    private Boolean atrCircuitBreakerApplied;

    @Column(name = "atr_circuit_breaker_value")
    private Double atrCircuitBreakerValue;

    @Column(name = "atr_circuit_breaker_period")
    private Integer atrCircuitBreakerPeriod;

    @Column(name = "atr_circuit_breaker_multiplier")
    private Double atrCircuitBreakerMultiplier;

    @Column(name = "atr_circuit_breaker_threshold_percent")
    private Double atrCircuitBreakerThresholdPercent;

    @Column(name = "confirmation_window_candles")
    private Integer confirmationWindowCandles;

    @Column(name = "lifecycle_confirmation_percent")
    private Double lifecycleConfirmationPercent;

    @Column(name = "lifecycle_invalidation_percent")
    private Double lifecycleInvalidationPercent;

    @Column(name = "detection_candle_timestamp")
    private Long detectionCandleTimestamp;

    @Column(name = "detection_close_price")
    private Double detectionClosePrice;

    @Column(name = "resolution_candle_timestamp")
    private Long resolutionCandleTimestamp;

    @Column(name = "resolution_candle_offset")
    private Integer resolutionCandleOffset;

    @Column(name = "resolution_close_price")
    private Double resolutionClosePrice;

    @Column(name = "lifecycle_updated_at")
    private LocalDateTime lifecycleUpdatedAt;

    @Column(name = "follow_up_sent_at")
    private LocalDateTime followUpSentAt;

    @Column(name = "elliott_cycle_key", length = 192)
    private String elliottCycleKey;

    @Column(name = "elliott_development_key", length = 192)
    private String elliottDevelopmentKey;

    @Column(name = "elliott_developing", nullable = false)
    private Boolean elliottDeveloping = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "elliott_signal_stage", columnDefinition = "varchar(24)")
    private ElliottSignalStage elliottSignalStage;

    @Column(name = "elliott_endpoint_timestamp")
    private Long elliottEndpointTimestamp;

    @Column(name = "elliott_endpoint_price")
    private Double elliottEndpointPrice;

    @Column(name = "elliott_correction_type", length = 64)
    private String elliottCorrectionType;

    @Column(name = "elliott_forecast_label", length = 128)
    private String elliottForecastLabel;

    @Column(name = "elliott_target_mid_price")
    private Double elliottTargetMidPrice;

    @Column(name = "elliott_target_zone_low")
    private Double elliottTargetZoneLow;

    @Column(name = "elliott_target_zone_high")
    private Double elliottTargetZoneHigh;

    @Column(name = "elliott_target_basis", length = 255)
    private String elliottTargetBasis;

    @Column(name = "elliott_required_reward_risk_ratio")
    private Double elliottRequiredRewardRiskRatio;

    @Column(name = "elliott_trade_actionable")
    private Boolean elliottTradeActionable;

    @Column(name = "elliott_trade_plan_status", length = 32)
    private String elliottTradePlanStatus;

    @Column(name = "elliott_trade_resolution_timestamp")
    private Long elliottTradeResolutionTimestamp;

    @Column(name = "elliott_trade_resolution_close")
    private Double elliottTradeResolutionClose;

    @Column(name = "elliott_trade_resolution_reason", length = 255)
    private String elliottTradeResolutionReason;

    @Column(name = "elliott_stage_updated_at")
    private LocalDateTime elliottStageUpdatedAt;

    @Column(name = "elliott_structure_snapshot", columnDefinition = "text")
    private String elliottStructureSnapshot;

    @Column(name = "elliott_transition_history", columnDefinition = "text")
    private String elliottTransitionHistory;

    @Column(name = "elliott_terminal_anchor_timestamp")
    private Long elliottTerminalAnchorTimestamp;

    @Column(name = "lifecycle_anchor_candle_timestamp")
    private Long lifecycleAnchorCandleTimestamp;

    @Column(name = "lifecycle_resolution_reason", length = 255)
    private String lifecycleResolutionReason;

    @Column(name = "harmonic_endpoint_timestamp")
    private Long harmonicEndpointTimestamp;

    @Column(name = "harmonic_endpoint_price")
    private Double harmonicEndpointPrice;

    @Column(name = "harmonic_points_snapshot", columnDefinition = "text")
    private String harmonicPointsSnapshot;

    @Column(name = "harmonic_measurements_snapshot", columnDefinition = "text")
    private String harmonicMeasurementsSnapshot;

    @Column(name = "harmonic_stop_basis", length = 255)
    private String harmonicStopBasis;

    @Column(name = "harmonic_stop_formula", length = 255)
    private String harmonicStopFormula;

    @Column(name = "harmonic_stop_buffer_amount")
    private Double harmonicStopBufferAmount;

    @Column(name = "harmonic_stop_buffer_percent")
    private Double harmonicStopBufferPercent;

    @Column(name = "harmonic_stop_distance_percent")
    private Double harmonicStopDistancePercent;

    @Column(name = "harmonic_stop_status", length = 32)
    private String harmonicStopStatus;

    @Column(name = "harmonic_stop_resolution_timestamp")
    private Long harmonicStopResolutionTimestamp;

    @Column(name = "harmonic_stop_resolution_price")
    private Double harmonicStopResolutionPrice;

    @Column(name = "harmonic_stop_resolution_reason", length = 255)
    private String harmonicStopResolutionReason;

    public Long getId() {
        return id;
    }

    public AlertRule getAlertRule() {
        return alertRule;
    }

    public CandlePattern getPattern() {
        return pattern;
    }

    public TradeSignal getTradeSignal() {
        return tradeSignal;
    }

    public Long getSignalCandleTimestamp() {
        return signalCandleTimestamp;
    }

    public SignalStength getSignalStrength() {
        return signalStrength;
    }

    public Integer getConfidenceScore() {
        return confidenceScore;
    }

    public Integer getFactoryConfidenceScore() { return factoryConfidenceScore; }
    public String getAnalysisProfileVersion() { return analysisProfileVersion; }
    public String getAnalysisProfileSnapshot() { return analysisProfileSnapshot; }

    public Integer getElliottV1EligibilityScore() {
        return elliottV1EligibilityScore;
    }

    public String getScoreVersion() {
        return scoreVersion == null || scoreVersion.isBlank()
                ? "LEGACY_UNVERSIONED"
                : scoreVersion;
    }

    public List<String> getConfidenceReasons() {
        if (confidenceReasonsPayload == null || confidenceReasonsPayload.isBlank()) {
            return List.of();
        }
        return confidenceReasonsPayload.lines()
                .map(String::trim)
                .filter(reason -> !reason.isEmpty())
                .toList();
    }

    public Double getClosePrice() {
        return closePrice;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public LocalDateTime getInitialEmailSentAt() { return initialEmailSentAt; }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public SignalLifecycleStatus getLifecycleStatus() {
        return lifecycleStatus == null ? SignalLifecycleStatus.DETECTED : lifecycleStatus;
    }

    public Double getPatternHigh() {
        return patternHigh;
    }

    public Double getPatternLow() {
        return patternLow;
    }

    public Double getConfirmationTriggerPrice() {
        return confirmationTriggerPrice;
    }

    public Double getInvalidationPrice() {
        return invalidationPrice;
    }

    public Double getTradeEntryPrice() { return tradeEntryPrice; }
    public Double getStopLossPrice() { return stopLossPrice; }
    public Double getProfitTargetPrice() { return profitTargetPrice; }
    public Double getRewardRiskRatio() { return rewardRiskRatio; }
    public String getTradePlanVersion() { return tradePlanVersion; }
    public Double getStructuralStopPrice() { return structuralStopPrice; }
    public String getStopLossMode() { return stopLossMode; }
    public Double getStopLossValuePercent() { return stopLossValuePercent; }
    public Double getPreCircuitBreakerStopPrice() { return preCircuitBreakerStopPrice; }
    public Boolean getAtrCircuitBreakerEnabled() { return atrCircuitBreakerEnabled; }
    public Boolean getAtrCircuitBreakerApplied() { return atrCircuitBreakerApplied; }
    public Double getAtrCircuitBreakerValue() { return atrCircuitBreakerValue; }
    public Integer getAtrCircuitBreakerPeriod() { return atrCircuitBreakerPeriod; }
    public Double getAtrCircuitBreakerMultiplier() { return atrCircuitBreakerMultiplier; }
    public Double getAtrCircuitBreakerThresholdPercent() { return atrCircuitBreakerThresholdPercent; }

    public Integer getConfirmationWindowCandles() {
        return confirmationWindowCandles;
    }

    public Double getLifecycleConfirmationPercent() { return lifecycleConfirmationPercent; }
    public Double getLifecycleInvalidationPercent() { return lifecycleInvalidationPercent; }

    public Long getDetectionCandleTimestamp() { return detectionCandleTimestamp; }
    public Double getDetectionClosePrice() { return detectionClosePrice; }

    public Long getResolutionCandleTimestamp() {
        return resolutionCandleTimestamp;
    }

    public Integer getResolutionCandleOffset() {
        return resolutionCandleOffset;
    }

    public Double getResolutionClosePrice() {
        return resolutionClosePrice;
    }

    public LocalDateTime getLifecycleUpdatedAt() {
        return lifecycleUpdatedAt;
    }

    public LocalDateTime getFollowUpSentAt() {
        return followUpSentAt;
    }

    public String getElliottCycleKey() {
        return elliottCycleKey;
    }

    public String getElliottDevelopmentKey() { return elliottDevelopmentKey; }
    public boolean isElliottDeveloping() { return Boolean.TRUE.equals(elliottDeveloping); }

    public ElliottSignalStage getElliottSignalStage() {
        return elliottSignalStage;
    }

    public Long getElliottEndpointTimestamp() {
        return elliottEndpointTimestamp;
    }

    public Double getElliottEndpointPrice() {
        return elliottEndpointPrice;
    }

    public String getElliottCorrectionType() { return elliottCorrectionType; }
    public String getElliottForecastLabel() { return elliottForecastLabel; }
    public Double getElliottTargetMidPrice() { return elliottTargetMidPrice; }
    public Double getElliottTargetZoneLow() { return elliottTargetZoneLow; }
    public Double getElliottTargetZoneHigh() { return elliottTargetZoneHigh; }
    public String getElliottTargetBasis() { return elliottTargetBasis; }
    public Double getElliottRequiredRewardRiskRatio() { return elliottRequiredRewardRiskRatio; }
    public boolean isElliottTradeActionable() { return Boolean.TRUE.equals(elliottTradeActionable); }
    public String getElliottTradePlanStatus() { return elliottTradePlanStatus; }
    public Long getElliottTradeResolutionTimestamp() { return elliottTradeResolutionTimestamp; }
    public Double getElliottTradeResolutionClose() { return elliottTradeResolutionClose; }
    public String getElliottTradeResolutionReason() { return elliottTradeResolutionReason; }
    public LocalDateTime getElliottStageUpdatedAt() { return elliottStageUpdatedAt; }
    public String getElliottStructureSnapshot() { return elliottStructureSnapshot; }
    public String getElliottTransitionHistory() { return elliottTransitionHistory; }

    public Long getElliottTerminalAnchorTimestamp() {
        return elliottTerminalAnchorTimestamp;
    }

    public Long getLifecycleAnchorCandleTimestamp() {
        return lifecycleAnchorCandleTimestamp;
    }

    public Long getLifecycleEvaluationAnchorTimestamp() {
        return lifecycleAnchorCandleTimestamp == null
                ? signalCandleTimestamp
                : lifecycleAnchorCandleTimestamp;
    }

    public String getLifecycleResolutionReason() {
        return lifecycleResolutionReason;
    }

    public Long getHarmonicEndpointTimestamp() { return harmonicEndpointTimestamp; }
    public Double getHarmonicEndpointPrice() { return harmonicEndpointPrice; }
    public String getHarmonicPointsSnapshot() { return harmonicPointsSnapshot; }
    public String getHarmonicMeasurementsSnapshot() { return harmonicMeasurementsSnapshot; }
    public String getHarmonicStopBasis() { return harmonicStopBasis; }
    public String getHarmonicStopFormula() { return harmonicStopFormula; }
    public Double getHarmonicStopBufferAmount() { return harmonicStopBufferAmount; }
    public Double getHarmonicStopBufferPercent() { return harmonicStopBufferPercent; }
    public Double getHarmonicStopDistancePercent() { return harmonicStopDistancePercent; }
    public String getHarmonicStopStatus() { return harmonicStopStatus; }
    public Long getHarmonicStopResolutionTimestamp() { return harmonicStopResolutionTimestamp; }
    public Double getHarmonicStopResolutionPrice() { return harmonicStopResolutionPrice; }
    public String getHarmonicStopResolutionReason() { return harmonicStopResolutionReason; }

    public boolean isLifecycleTracked() {
        return elliottDevelopmentKey != null || confirmationWindowCandles != null
                && patternHigh != null
                && patternLow != null
                && confirmationTriggerPrice != null
                && (invalidationPrice != null || isElliottSignal());
    }

    public boolean isElliottSignal() {
        return pattern != null && pattern.name().startsWith("ELLIOTT_");
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setAlertRule(AlertRule alertRule) {
        this.alertRule = alertRule;
    }

    public void setPattern(CandlePattern pattern) {
        this.pattern = pattern;
    }

    public void setTradeSignal(TradeSignal tradeSignal) {
        this.tradeSignal = tradeSignal;
    }

    public void setSignalCandleTimestamp(Long signalCandleTimestamp) {
        this.signalCandleTimestamp = signalCandleTimestamp;
    }

    public void setSignalStrength(SignalStength signalStrength) {
        this.signalStrength = signalStrength;
    }

    public void setConfidenceScore(Integer confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public void setFactoryConfidenceScore(Integer value) { this.factoryConfidenceScore = value; }
    public void setAnalysisProfileVersion(String value) { this.analysisProfileVersion = value; }
    public void setAnalysisProfileSnapshot(String value) { this.analysisProfileSnapshot = value; }

    public void setElliottV1EligibilityScore(Integer elliottV1EligibilityScore) {
        this.elliottV1EligibilityScore = elliottV1EligibilityScore;
    }

    public void setInitialEmailSentAt(LocalDateTime value) { this.initialEmailSentAt = value; }

    public void setScoreVersion(String scoreVersion) {
        if (scoreVersion == null || scoreVersion.isBlank()) {
            this.scoreVersion = "LEGACY_UNVERSIONED";
            return;
        }
        String normalized = scoreVersion.trim();
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("Score version must not exceed 32 characters.");
        }
        this.scoreVersion = normalized;
    }

    public void setConfidenceReasons(List<String> confidenceReasons) {
        if (confidenceReasons == null || confidenceReasons.isEmpty()) {
            confidenceReasonsPayload = null;
            return;
        }
        List<String> normalizedReasons = confidenceReasons.stream()
                .filter(reason -> reason != null && !reason.isBlank())
                .map(reason -> reason.replace('\r', ' ').replace('\n', ' ').trim())
                .toList();
        confidenceReasonsPayload = normalizedReasons.isEmpty()
                ? null
                : String.join("\n", normalizedReasons);
    }

    public void setClosePrice(Double closePrice) {
        this.closePrice = closePrice;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    public void markDeleted(Instant deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }

    public void setLifecycleStatus(SignalLifecycleStatus lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus == null
                ? SignalLifecycleStatus.DETECTED
                : lifecycleStatus;
    }

    public void setPatternHigh(Double patternHigh) {
        this.patternHigh = patternHigh;
    }

    public void setPatternLow(Double patternLow) {
        this.patternLow = patternLow;
    }

    public void setConfirmationTriggerPrice(Double confirmationTriggerPrice) {
        this.confirmationTriggerPrice = confirmationTriggerPrice;
    }

    public void setInvalidationPrice(Double invalidationPrice) {
        this.invalidationPrice = invalidationPrice;
    }

    public void setTradeEntryPrice(Double value) { this.tradeEntryPrice = value; }
    public void setStopLossPrice(Double value) { this.stopLossPrice = value; }
    public void setProfitTargetPrice(Double value) { this.profitTargetPrice = value; }
    public void setRewardRiskRatio(Double value) { this.rewardRiskRatio = value; }
    public void setTradePlanVersion(String value) { this.tradePlanVersion = value; }
    public void setStructuralStopPrice(Double value) { this.structuralStopPrice = value; }
    public void setStopLossMode(String value) { this.stopLossMode = value; }
    public void setStopLossValuePercent(Double value) { this.stopLossValuePercent = value; }
    public void setPreCircuitBreakerStopPrice(Double value) { this.preCircuitBreakerStopPrice = value; }
    public void setAtrCircuitBreakerEnabled(Boolean value) { this.atrCircuitBreakerEnabled = value; }
    public void setAtrCircuitBreakerApplied(Boolean value) { this.atrCircuitBreakerApplied = value; }
    public void setAtrCircuitBreakerValue(Double value) { this.atrCircuitBreakerValue = value; }
    public void setAtrCircuitBreakerPeriod(Integer value) { this.atrCircuitBreakerPeriod = value; }
    public void setAtrCircuitBreakerMultiplier(Double value) { this.atrCircuitBreakerMultiplier = value; }
    public void setAtrCircuitBreakerThresholdPercent(Double value) { this.atrCircuitBreakerThresholdPercent = value; }

    public boolean hasCandlestickRiskRewardPlan() {
        return ("CANDLE_RR_V1".equals(tradePlanVersion)
                || "CANDLE_RR_V2".equals(tradePlanVersion)
                || "CANDLE_RR_V3".equals(tradePlanVersion))
                && stopLossPrice != null
                && rewardRiskRatio != null;
    }

    public void setConfirmationWindowCandles(Integer confirmationWindowCandles) {
        this.confirmationWindowCandles = confirmationWindowCandles;
    }

    public void setLifecycleConfirmationPercent(Double value) { this.lifecycleConfirmationPercent = value; }
    public void setLifecycleInvalidationPercent(Double value) { this.lifecycleInvalidationPercent = value; }

    public void setDetectionCandleTimestamp(Long value) { this.detectionCandleTimestamp = value; }
    public void setDetectionClosePrice(Double value) { this.detectionClosePrice = value; }

    public void setResolutionCandleTimestamp(Long resolutionCandleTimestamp) {
        this.resolutionCandleTimestamp = resolutionCandleTimestamp;
    }

    public void setResolutionCandleOffset(Integer resolutionCandleOffset) {
        this.resolutionCandleOffset = resolutionCandleOffset;
    }

    public void setResolutionClosePrice(Double resolutionClosePrice) {
        this.resolutionClosePrice = resolutionClosePrice;
    }

    public void setLifecycleUpdatedAt(LocalDateTime lifecycleUpdatedAt) {
        this.lifecycleUpdatedAt = lifecycleUpdatedAt;
    }

    public void setFollowUpSentAt(LocalDateTime followUpSentAt) {
        this.followUpSentAt = followUpSentAt;
    }

    public void setElliottCycleKey(String elliottCycleKey) {
        this.elliottCycleKey = elliottCycleKey;
    }

    public void setElliottDevelopmentKey(String value) { this.elliottDevelopmentKey = value; }
    public void setElliottDeveloping(boolean value) { this.elliottDeveloping = value; }

    public void setElliottSignalStage(ElliottSignalStage elliottSignalStage) {
        this.elliottSignalStage = elliottSignalStage;
    }

    public void setElliottEndpointTimestamp(Long elliottEndpointTimestamp) {
        this.elliottEndpointTimestamp = elliottEndpointTimestamp;
    }

    public void setElliottEndpointPrice(Double elliottEndpointPrice) {
        this.elliottEndpointPrice = elliottEndpointPrice;
    }

    public void setElliottCorrectionType(String value) {
        this.elliottCorrectionType = normalizedText(value, 64);
    }

    public void setElliottForecastLabel(String value) {
        this.elliottForecastLabel = normalizedText(value, 128);
    }

    public void setElliottTargetMidPrice(Double value) { this.elliottTargetMidPrice = value; }
    public void setElliottTargetZoneLow(Double value) { this.elliottTargetZoneLow = value; }
    public void setElliottTargetZoneHigh(Double value) { this.elliottTargetZoneHigh = value; }
    public void setElliottTargetBasis(String value) { this.elliottTargetBasis = normalizedText(value, 255); }
    public void setElliottRequiredRewardRiskRatio(Double value) { this.elliottRequiredRewardRiskRatio = value; }
    public void setElliottTradeActionable(boolean value) { this.elliottTradeActionable = value; }
    public void setElliottTradePlanStatus(String value) { this.elliottTradePlanStatus = normalizedText(value, 32); }
    public void setElliottTradeResolutionTimestamp(Long value) { this.elliottTradeResolutionTimestamp = value; }
    public void setElliottTradeResolutionClose(Double value) { this.elliottTradeResolutionClose = value; }
    public void setElliottTradeResolutionReason(String value) { this.elliottTradeResolutionReason = normalizedText(value, 255); }

    public void setElliottStageUpdatedAt(LocalDateTime value) { this.elliottStageUpdatedAt = value; }
    public void setElliottStructureSnapshot(String value) {
        this.elliottStructureSnapshot = normalizedLargeText(value, "Elliott structure snapshots");
    }

    public void appendElliottTransition(String value) {
        String normalized = normalizedLargeText(value, "Elliott transition entries");
        if (normalized == null) return;
        String combined = elliottTransitionHistory == null || elliottTransitionHistory.isBlank()
                ? normalized : elliottTransitionHistory + "\n" + normalized;
        this.elliottTransitionHistory = normalizedLargeText(combined, "Elliott transition history");
    }

    public void setElliottTerminalAnchorTimestamp(Long elliottTerminalAnchorTimestamp) {
        this.elliottTerminalAnchorTimestamp = elliottTerminalAnchorTimestamp;
    }

    public void setLifecycleAnchorCandleTimestamp(Long lifecycleAnchorCandleTimestamp) {
        this.lifecycleAnchorCandleTimestamp = lifecycleAnchorCandleTimestamp;
    }

    public void setLifecycleResolutionReason(String lifecycleResolutionReason) {
        if (lifecycleResolutionReason == null || lifecycleResolutionReason.isBlank()) {
            this.lifecycleResolutionReason = null;
            return;
        }
        String normalized = lifecycleResolutionReason.trim();
        this.lifecycleResolutionReason = normalized.length() <= 255
                ? normalized
                : normalized.substring(0, 255);
    }

    public void setHarmonicEndpointTimestamp(Long value) { this.harmonicEndpointTimestamp = value; }
    public void setHarmonicEndpointPrice(Double value) { this.harmonicEndpointPrice = value; }
    public void setHarmonicPointsSnapshot(String value) { this.harmonicPointsSnapshot = normalizedSnapshot(value); }
    public void setHarmonicMeasurementsSnapshot(String value) { this.harmonicMeasurementsSnapshot = normalizedSnapshot(value); }
    public void setHarmonicStopBasis(String value) { this.harmonicStopBasis = normalizedText(value, 255); }
    public void setHarmonicStopFormula(String value) { this.harmonicStopFormula = normalizedText(value, 255); }
    public void setHarmonicStopBufferAmount(Double value) { this.harmonicStopBufferAmount = value; }
    public void setHarmonicStopBufferPercent(Double value) { this.harmonicStopBufferPercent = value; }
    public void setHarmonicStopDistancePercent(Double value) { this.harmonicStopDistancePercent = value; }
    public void setHarmonicStopStatus(String value) { this.harmonicStopStatus = normalizedText(value, 32); }
    public void setHarmonicStopResolutionTimestamp(Long value) { this.harmonicStopResolutionTimestamp = value; }
    public void setHarmonicStopResolutionPrice(Double value) { this.harmonicStopResolutionPrice = value; }
    public void setHarmonicStopResolutionReason(String value) { this.harmonicStopResolutionReason = normalizedText(value, 255); }

    public boolean hasHarmonicStopPlan() {
        return "HARMONIC_STOP_V1".equals(tradePlanVersion)
                && tradeEntryPrice != null && structuralStopPrice != null && stopLossPrice != null;
    }

    private String normalizedSnapshot(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 16_000) {
            throw new IllegalArgumentException("Harmonic snapshots must not exceed 16000 characters.");
        }
        return normalized;
    }

    private String normalizedText(String value, int maximumLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= maximumLength
                ? normalized : normalized.substring(0, maximumLength);
    }

    private String normalizedLargeText(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 32_000) {
            throw new IllegalArgumentException(label + " must not exceed 32000 characters.");
        }
        return normalized;
    }
}
