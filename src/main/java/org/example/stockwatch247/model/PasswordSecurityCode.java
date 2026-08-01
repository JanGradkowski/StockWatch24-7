package org.example.stockwatch247.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "password_security_codes", uniqueConstraints =
        @UniqueConstraint(name = "uk_password_security_code_user_purpose", columnNames = {"user_id", "purpose"}))
public class PasswordSecurityCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 32)
    private String purpose;
    @Column(name = "code_hash", nullable = false)
    private String codeHash;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getLastSentAt() { return lastSentAt; }
    public void setLastSentAt(LocalDateTime lastSentAt) { this.lastSentAt = lastSentAt; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
}
