package org.example.stockwatch247.model;

import jakarta.persistence.*;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.time.LocalDateTime;
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

    @Column(name = "confidence_reasons", columnDefinition = "text")
    private String confidenceReasonsPayload;

    @Column(name = "close_price")
    private Double closePrice;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

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
}
