package org.example.stockwatch247.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "technical_outlook_notifications", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"subscription_id", "current_candle_timestamp"})
})
public class TechnicalOutlookNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private TechnicalOutlookSubscription subscription;

    @Column(name = "previous_classification", nullable = false, length = 64)
    private String previousClassification;
    @Column(name = "current_classification", nullable = false, length = 64)
    private String currentClassification;
    @Column(name = "previous_score", nullable = false)
    private double previousScore;
    @Column(name = "current_score", nullable = false)
    private double currentScore;
    @Column(name = "previous_candle_timestamp", nullable = false)
    private long previousCandleTimestamp;
    @Column(name = "current_candle_timestamp", nullable = false)
    private long currentCandleTimestamp;
    @Column(name = "previous_snapshot", nullable = false, columnDefinition = "text")
    private String previousSnapshot;
    @Column(name = "current_snapshot", nullable = false, columnDefinition = "text")
    private String currentSnapshot;
    @Column(name = "change_report", nullable = false, columnDefinition = "text")
    private String changeReport;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "email_sent_at")
    private LocalDateTime emailSentAt;
    @Column(name = "read_at")
    private LocalDateTime readAt;

    public Long getId() { return id; }
    public TechnicalOutlookSubscription getSubscription() { return subscription; }
    public String getPreviousClassification() { return previousClassification; }
    public String getCurrentClassification() { return currentClassification; }
    public double getPreviousScore() { return previousScore; }
    public double getCurrentScore() { return currentScore; }
    public long getPreviousCandleTimestamp() { return previousCandleTimestamp; }
    public long getCurrentCandleTimestamp() { return currentCandleTimestamp; }
    public String getPreviousSnapshot() { return previousSnapshot; }
    public String getCurrentSnapshot() { return currentSnapshot; }
    public String getChangeReport() { return changeReport; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getEmailSentAt() { return emailSentAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public boolean isRead() { return readAt != null; }

    public void setId(Long id) { this.id = id; }
    public void setSubscription(TechnicalOutlookSubscription value) { this.subscription = value; }
    public void setPreviousClassification(String value) { this.previousClassification = value; }
    public void setCurrentClassification(String value) { this.currentClassification = value; }
    public void setPreviousScore(double value) { this.previousScore = value; }
    public void setCurrentScore(double value) { this.currentScore = value; }
    public void setPreviousCandleTimestamp(long value) { this.previousCandleTimestamp = value; }
    public void setCurrentCandleTimestamp(long value) { this.currentCandleTimestamp = value; }
    public void setPreviousSnapshot(String value) { this.previousSnapshot = value; }
    public void setCurrentSnapshot(String value) { this.currentSnapshot = value; }
    public void setChangeReport(String value) { this.changeReport = value; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public void setEmailSentAt(LocalDateTime value) { this.emailSentAt = value; }
    public void setReadAt(LocalDateTime value) { this.readAt = value; }
}

