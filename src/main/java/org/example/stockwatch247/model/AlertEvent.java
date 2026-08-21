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

    @Enumerated(EnumType.STRING)
    @Column(name = "elliott_signal_stage", columnDefinition = "varchar(24)")
    private ElliottSignalStage elliottSignalStage;

    @Column(name = "elliott_endpoint_timestamp")
    private Long elliottEndpointTimestamp;

    @Column(name = "elliott_endpoint_price")
    private Double elliottEndpointPrice;

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

    public ElliottSignalStage getElliottSignalStage() {
        return elliottSignalStage;
    }

    public Long getElliottEndpointTimestamp() {
        return elliottEndpointTimestamp;
    }

    public Double getElliottEndpointPrice() {
        return elliottEndpointPrice;
    }

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

    public boolean isLifecycleTracked() {
        return confirmationWindowCandles != null
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

    public void setElliottSignalStage(ElliottSignalStage elliottSignalStage) {
        this.elliottSignalStage = elliottSignalStage;
    }

    public void setElliottEndpointTimestamp(Long elliottEndpointTimestamp) {
        this.elliottEndpointTimestamp = elliottEndpointTimestamp;
    }

    public void setElliottEndpointPrice(Double elliottEndpointPrice) {
        this.elliottEndpointPrice = elliottEndpointPrice;
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

    private String normalizedSnapshot(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 16_000) {
            throw new IllegalArgumentException("Harmonic snapshots must not exceed 16000 characters.");
        }
        return normalized;
    }
}
