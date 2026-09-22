package org.example.stockwatch247.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A retained forecast as it was known at the decision candle, never retroactively fitted. */
@Embeddable
public class ElliottProjectionRevision {
    @Column(name = "decision_timestamp", nullable = false)
    private long decisionTimestamp;
    @Column(name = "projected_path", nullable = false, columnDefinition = "text")
    private String projectedPath;
    @Column(name = "minimum_candles", nullable = false)
    private int minimumCandles;
    @Column(name = "maximum_candles", nullable = false)
    private int maximumCandles;
    @Column(name = "target_zone_low", nullable = false)
    private double targetZoneLow;
    @Column(name = "target_zone_high", nullable = false)
    private double targetZoneHigh;
    @Column(nullable = false, length = 255)
    private String reason;

    protected ElliottProjectionRevision() { }

    public ElliottProjectionRevision(long timestamp, ElliottProjectionScenario scenario, String reason) {
        this.decisionTimestamp = timestamp;
        this.projectedPath = scenario.getProjectedPath();
        this.minimumCandles = scenario.getMinimumCandles();
        this.maximumCandles = scenario.getMaximumCandles();
        this.targetZoneLow = scenario.getTargetZoneLow();
        this.targetZoneHigh = scenario.getTargetZoneHigh();
        this.reason = reason;
    }

    public long getDecisionTimestamp() { return decisionTimestamp; }
    public String getProjectedPath() { return projectedPath; }
    public int getMinimumCandles() { return minimumCandles; }
    public int getMaximumCandles() { return maximumCandles; }
    public double getTargetZoneLow() { return targetZoneLow; }
    public double getTargetZoneHigh() { return targetZoneHigh; }
    public String getReason() { return reason; }
}
