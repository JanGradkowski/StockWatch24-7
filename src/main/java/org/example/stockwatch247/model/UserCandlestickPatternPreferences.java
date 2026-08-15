package org.example.stockwatch247.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "user_candlestick_pattern_preferences", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_candlestick_pattern_preferences_user", columnNames = "user_id"))
public class UserCandlestickPatternPreferences {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "profile_version", nullable = false, length = 64)
    private String profileVersion;
    @Column(name = "preferences_payload", nullable = false, columnDefinition = "text")
    private String preferencesPayload;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getProfileVersion() { return profileVersion; }
    public String getPreferencesPayload() { return preferencesPayload; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUser(User user) { this.user = user; }
    public void setProfileVersion(String value) { this.profileVersion = value; }
    public void setPreferencesPayload(String value) { this.preferencesPayload = value; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
