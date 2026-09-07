package org.example.stockwatch247.model;

import jakarta.persistence.*;
import org.example.stockwatch247.model.enums.ElliottProjectionScenarioStatus;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.time.LocalDateTime;

@Entity
@Table(name = "elliott_projection_scenarios", uniqueConstraints = @UniqueConstraint(
        name = "uq_elliott_projection_scenario_key",
        columnNames = {"projection_set_id", "scenario_key"}))
public class ElliottProjectionScenario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projection_set_id", nullable = false)
    private ElliottProjectionSet projectionSet;

    @Column(name = "scenario_key", nullable = false, length = 48)
    private String scenarioKey;
    @Column(nullable = false, length = 96)
    private String label;
    @Column(nullable = false, length = 255)
    private String description;
    @Column(name = "display_rank", nullable = false)
    private int displayRank;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_move", nullable = false, length = 8)
    private TradeSignal expectedMove;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ElliottProjectionScenarioStatus status;

    @Column(name = "initial_confidence", nullable = false)
    private int initialConfidence;
    @Column(name = "current_confidence", nullable = false)
    private int currentConfidence;
    @Column(name = "projected_path", nullable = false, columnDefinition = "text")
    private String projectedPath;
    @Column(name = "target_zone_low", nullable = false)
    private double targetZoneLow;
    @Column(name = "target_zone_high", nullable = false)
    private double targetZoneHigh;
    @Column(name = "target_midpoint", nullable = false)
    private double targetMidpoint;
    @Column(name = "target_basis", nullable = false, length = 255)
    private String targetBasis;
    @Column(name = "minimum_candles", nullable = false)
    private int minimumCandles;
    @Column(name = "maximum_candles", nullable = false)
    private int maximumCandles;
    @Column(name = "hard_invalidation_price")
    private Double hardInvalidationPrice;
    @Column(name = "hard_invalidation_side", length = 8)
    private String hardInvalidationSide;
    @Column(name = "scenario_invalidation_price")
    private Double scenarioInvalidationPrice;
    @Column(name = "scenario_invalidation_side", length = 8)
    private String scenarioInvalidationSide;
    @Column(name = "evidence", nullable = false, columnDefinition = "text")
    private String evidence;
    @Column(name = "evaluation_note", length = 255)
    private String evaluationNote;
    @Column(name = "resolution_timestamp")
    private Long resolutionTimestamp;
    @Column(name = "resolution_reason", length = 255)
    private String resolutionReason;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public ElliottProjectionSet getProjectionSet() { return projectionSet; }
    public String getScenarioKey() { return scenarioKey; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public int getDisplayRank() { return displayRank; }
    public TradeSignal getExpectedMove() { return expectedMove; }
    public ElliottProjectionScenarioStatus getStatus() { return status; }
    public int getInitialConfidence() { return initialConfidence; }
    public int getCurrentConfidence() { return currentConfidence; }
    public String getProjectedPath() { return projectedPath; }
    public double getTargetZoneLow() { return targetZoneLow; }
    public double getTargetZoneHigh() { return targetZoneHigh; }
    public double getTargetMidpoint() { return targetMidpoint; }
    public String getTargetBasis() { return targetBasis; }
    public int getMinimumCandles() { return minimumCandles; }
    public int getMaximumCandles() { return maximumCandles; }
    public Double getHardInvalidationPrice() { return hardInvalidationPrice; }
    public String getHardInvalidationSide() { return hardInvalidationSide; }
    public Double getScenarioInvalidationPrice() { return scenarioInvalidationPrice; }
    public String getScenarioInvalidationSide() { return scenarioInvalidationSide; }
    public String getEvidence() { return evidence; }
    public String getEvaluationNote() { return evaluationNote; }
    public Long getResolutionTimestamp() { return resolutionTimestamp; }
    public String getResolutionReason() { return resolutionReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setProjectionSet(ElliottProjectionSet value) { projectionSet = value; }
    public void setScenarioKey(String value) { scenarioKey = value; }
    public void setLabel(String value) { label = value; }
    public void setDescription(String value) { description = value; }
    public void setDisplayRank(int value) { displayRank = value; }
    public void setExpectedMove(TradeSignal value) { expectedMove = value; }
    public void setStatus(ElliottProjectionScenarioStatus value) { status = value; }
    public void setInitialConfidence(int value) { initialConfidence = value; }
    public void setCurrentConfidence(int value) { currentConfidence = value; }
    public void setProjectedPath(String value) { projectedPath = value; }
    public void setTargetZoneLow(double value) { targetZoneLow = value; }
    public void setTargetZoneHigh(double value) { targetZoneHigh = value; }
    public void setTargetMidpoint(double value) { targetMidpoint = value; }
    public void setTargetBasis(String value) { targetBasis = value; }
    public void setMinimumCandles(int value) { minimumCandles = value; }
    public void setMaximumCandles(int value) { maximumCandles = value; }
    public void setHardInvalidationPrice(Double value) { hardInvalidationPrice = value; }
    public void setHardInvalidationSide(String value) { hardInvalidationSide = value; }
    public void setScenarioInvalidationPrice(Double value) { scenarioInvalidationPrice = value; }
    public void setScenarioInvalidationSide(String value) { scenarioInvalidationSide = value; }
    public void setEvidence(String value) { evidence = value; }
    public void setEvaluationNote(String value) { evaluationNote = value; }
    public void setResolutionTimestamp(Long value) { resolutionTimestamp = value; }
    public void setResolutionReason(String value) { resolutionReason = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
