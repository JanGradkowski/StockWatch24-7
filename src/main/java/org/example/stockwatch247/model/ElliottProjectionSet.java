package org.example.stockwatch247.model;

import jakarta.persistence.*;
import org.example.stockwatch247.model.enums.ElliottProjectionSetStatus;
import org.example.stockwatch247.model.enums.ElliottSignalStage;

import java.time.LocalDateTime;

@Entity
@Table(name = "elliott_projection_sets", uniqueConstraints = @UniqueConstraint(
        name = "uq_elliott_projection_set_revision",
        columnNames = {"alert_event_id", "elliott_stage", "stage_revision"}))
public class ElliottProjectionSet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_event_id", nullable = false)
    private AlertEvent alertEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "elliott_stage", nullable = false, length = 24)
    private ElliottSignalStage stage;

    @Column(name = "stage_revision", nullable = false)
    private int stageRevision;

    @Column(name = "development_key", length = 192)
    private String developmentKey;

    @Column(name = "source_timestamp", nullable = false)
    private long sourceTimestamp;

    @Column(name = "source_price", nullable = false)
    private double sourcePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ElliottProjectionSetStatus status;

    @Column(name = "last_evaluated_timestamp")
    private Long lastEvaluatedTimestamp;

    @Column(name = "evaluated_candle_count", nullable = false)
    private int evaluatedCandleCount;

    @Column(name = "resolution_timestamp")
    private Long resolutionTimestamp;

    @Column(name = "resolution_reason", length = 255)
    private String resolutionReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public AlertEvent getAlertEvent() { return alertEvent; }
    public ElliottSignalStage getStage() { return stage; }
    public int getStageRevision() { return stageRevision; }
    public String getDevelopmentKey() { return developmentKey; }
    public long getSourceTimestamp() { return sourceTimestamp; }
    public double getSourcePrice() { return sourcePrice; }
    public ElliottProjectionSetStatus getStatus() { return status; }
    public Long getLastEvaluatedTimestamp() { return lastEvaluatedTimestamp; }
    public int getEvaluatedCandleCount() { return evaluatedCandleCount; }
    public Long getResolutionTimestamp() { return resolutionTimestamp; }
    public String getResolutionReason() { return resolutionReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setAlertEvent(AlertEvent value) { alertEvent = value; }
    public void setStage(ElliottSignalStage value) { stage = value; }
    public void setStageRevision(int value) { stageRevision = value; }
    public void setDevelopmentKey(String value) { developmentKey = value; }
    public void setSourceTimestamp(long value) { sourceTimestamp = value; }
    public void setSourcePrice(double value) { sourcePrice = value; }
    public void setStatus(ElliottProjectionSetStatus value) { status = value; }
    public void setLastEvaluatedTimestamp(Long value) { lastEvaluatedTimestamp = value; }
    public void setEvaluatedCandleCount(int value) { evaluatedCandleCount = value; }
    public void setResolutionTimestamp(Long value) { resolutionTimestamp = value; }
    public void setResolutionReason(String value) { resolutionReason = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
