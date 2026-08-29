package org.example.stockwatch247.model;

import jakarta.persistence.*;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.ElliottTradePlanStatus;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.time.LocalDateTime;

@Entity
@Table(name = "elliott_stage_trade_plans", uniqueConstraints = @UniqueConstraint(
        name = "uq_elliott_stage_trade_plan_revision",
        columnNames = {"alert_event_id", "elliott_stage", "stage_revision"}))
public class ElliottStageTradePlan {
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

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_move", nullable = false, length = 8)
    private TradeSignal expectedMove;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ElliottTradePlanStatus status;

    @Column(name = "plan_version", nullable = false, length = 32)
    private String planVersion;

    @Column(name = "entry_timestamp", nullable = false)
    private long entryTimestamp;
    @Column(name = "entry_price", nullable = false)
    private double entryPrice;
    @Column(name = "structural_stop_price", nullable = false)
    private double structuralStopPrice;
    @Column(name = "stop_loss_price", nullable = false)
    private double stopLossPrice;
    @Column(name = "stop_buffer", nullable = false)
    private double stopBuffer;
    @Column(name = "hard_invalidation_price")
    private Double hardInvalidationPrice;
    @Column(name = "hard_invalidation_side", length = 8)
    private String hardInvalidationSide;
    @Column(name = "target_midpoint", nullable = false)
    private double targetMidpoint;
    @Column(name = "target_zone_low", nullable = false)
    private double targetZoneLow;
    @Column(name = "target_zone_high", nullable = false)
    private double targetZoneHigh;
    @Column(name = "target_trigger_price", nullable = false)
    private double targetTriggerPrice;
    @Column(name = "target_basis", nullable = false, length = 255)
    private String targetBasis;
    @Column(name = "fibonacci_ratio")
    private Double fibonacciRatio;
    @Column(name = "target_zone_percent", nullable = false)
    private double targetZonePercent;
    @Column(name = "required_reward_risk_ratio", nullable = false)
    private double requiredRewardRiskRatio;
    @Column(name = "actual_reward_risk_ratio", nullable = false)
    private double actualRewardRiskRatio;
    @Column(name = "actionable", nullable = false)
    private boolean actionable;
    @Column(name = "qualification", nullable = false, length = 255)
    private String qualification;
    @Column(name = "resolution_timestamp")
    private Long resolutionTimestamp;
    @Column(name = "resolution_close_price")
    private Double resolutionClosePrice;
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
    public TradeSignal getExpectedMove() { return expectedMove; }
    public ElliottTradePlanStatus getStatus() { return status; }
    public String getPlanVersion() { return planVersion; }
    public long getEntryTimestamp() { return entryTimestamp; }
    public double getEntryPrice() { return entryPrice; }
    public double getStructuralStopPrice() { return structuralStopPrice; }
    public double getStopLossPrice() { return stopLossPrice; }
    public double getStopBuffer() { return stopBuffer; }
    public Double getHardInvalidationPrice() { return hardInvalidationPrice; }
    public String getHardInvalidationSide() { return hardInvalidationSide; }
    public double getTargetMidpoint() { return targetMidpoint; }
    public double getTargetZoneLow() { return targetZoneLow; }
    public double getTargetZoneHigh() { return targetZoneHigh; }
    public double getTargetTriggerPrice() { return targetTriggerPrice; }
    public String getTargetBasis() { return targetBasis; }
    public Double getFibonacciRatio() { return fibonacciRatio; }
    public double getTargetZonePercent() { return targetZonePercent; }
    public double getRequiredRewardRiskRatio() { return requiredRewardRiskRatio; }
    public double getActualRewardRiskRatio() { return actualRewardRiskRatio; }
    public boolean isActionable() { return actionable; }
    public String getQualification() { return qualification; }
    public Long getResolutionTimestamp() { return resolutionTimestamp; }
    public Double getResolutionClosePrice() { return resolutionClosePrice; }
    public String getResolutionReason() { return resolutionReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setAlertEvent(AlertEvent value) { alertEvent = value; }
    public void setStage(ElliottSignalStage value) { stage = value; }
    public void setStageRevision(int value) { stageRevision = value; }
    public void setExpectedMove(TradeSignal value) { expectedMove = value; }
    public void setStatus(ElliottTradePlanStatus value) { status = value; }
    public void setPlanVersion(String value) { planVersion = value; }
    public void setEntryTimestamp(long value) { entryTimestamp = value; }
    public void setEntryPrice(double value) { entryPrice = value; }
    public void setStructuralStopPrice(double value) { structuralStopPrice = value; }
    public void setStopLossPrice(double value) { stopLossPrice = value; }
    public void setStopBuffer(double value) { stopBuffer = value; }
    public void setHardInvalidationPrice(Double value) { hardInvalidationPrice = value; }
    public void setHardInvalidationSide(String value) { hardInvalidationSide = value; }
    public void setTargetMidpoint(double value) { targetMidpoint = value; }
    public void setTargetZoneLow(double value) { targetZoneLow = value; }
    public void setTargetZoneHigh(double value) { targetZoneHigh = value; }
    public void setTargetTriggerPrice(double value) { targetTriggerPrice = value; }
    public void setTargetBasis(String value) { targetBasis = value; }
    public void setFibonacciRatio(Double value) { fibonacciRatio = value; }
    public void setTargetZonePercent(double value) { targetZonePercent = value; }
    public void setRequiredRewardRiskRatio(double value) { requiredRewardRiskRatio = value; }
    public void setActualRewardRiskRatio(double value) { actualRewardRiskRatio = value; }
    public void setActionable(boolean value) { actionable = value; }
    public void setQualification(String value) { qualification = value; }
    public void setResolutionTimestamp(Long value) { resolutionTimestamp = value; }
    public void setResolutionClosePrice(Double value) { resolutionClosePrice = value; }
    public void setResolutionReason(String value) { resolutionReason = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
