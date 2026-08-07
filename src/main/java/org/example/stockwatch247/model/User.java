    package org.example.stockwatch247.model;
    import com.fasterxml.jackson.annotation.JsonIgnore;
    import jakarta.persistence.*;
    import java.time.LocalDateTime;
    import java.util.ArrayList;
    import java.util.List;

    @Entity
    @Table(name = "users") // "user" is a reserved keyword in Postgres, always pluralize it!
    public class User {

        public static final String DEFAULT_ELLIOTT_MOTIVE_COLOR = "#3B82F6";
        public static final String DEFAULT_ELLIOTT_CORRECTIVE_COLOR = "#A855F7";

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, unique = true)
        private String email;

        @Column(nullable = false)
        private String passwordHash;

        @Column(name = "first_name", nullable = false, length = 100)
        private String firstName;
        @Column(name = "last_name", nullable = false, length = 100)
        private String lastName;

        @Column(name = "verification_token_hash", length = 64)
        private String verificationTokenHash;

        @Column(name = "verification_expires_at")
        private LocalDateTime verificationExpiresAt;

        @Column(name = "verification_last_sent_at")
        private LocalDateTime verificationLastSentAt;


        @Column(name = "is_verified", nullable = false)
        private boolean isVerified = false;

        @Column(name = "created_at", updatable = false)
        private LocalDateTime createdAt = LocalDateTime.now();

        @Column(name = "theme_preference", nullable = false, length = 16)
        private String themePreference = "DARK";

        @Column(name = "elliott_motive_color", nullable = false, length = 7)
        private String elliottMotiveColor = DEFAULT_ELLIOTT_MOTIVE_COLOR;

        @Column(name = "elliott_corrective_color", nullable = false, length = 7)
        private String elliottCorrectiveColor = DEFAULT_ELLIOTT_CORRECTIVE_COLOR;

        @Column(name = "mfa_enabled", nullable = false)
        private boolean mfaEnabled;

        @JsonIgnore
        @Column(name = "mfa_secret_ciphertext", length = 512)
        private String mfaSecretCiphertext;

        @JsonIgnore
        @Column(name = "mfa_secret_iv", length = 64)
        private String mfaSecretIv;

        @JsonIgnore
        @Column(name = "security_version", nullable = false)
        private long securityVersion;

        @JsonIgnore
        @Column(name = "last_accepted_totp_step")
        private Long lastAcceptedTotpStep;

        @JsonIgnore
        @Column(name = "password_changed_at")
        private LocalDateTime passwordChangedAt;

        @JsonIgnore
        @Column(name = "deletion_requested_at")
        private LocalDateTime deletionRequestedAt;

        @JsonIgnore
        @Column(name = "deletion_cancel_token_hash", length = 64)
        private String deletionCancelTokenHash;

        @JsonIgnore
        @Column(name = "deletion_cancel_expires_at")
        private LocalDateTime deletionCancelExpiresAt;

        @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<AlertRule> alertRules = new ArrayList<>();

        public Long getId() {
            return id;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public String getEmail() {
            return email;
        }

        @JsonIgnore
        public String getPasswordHash() {
            return passwordHash;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public void setVerified(boolean verified) {
            isVerified = verified;
        }
        public boolean isVerified() {
            return isVerified;
        }

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        @JsonIgnore
        public String getVerificationTokenHash() {
            return verificationTokenHash;
        }

        public void setVerificationTokenHash(String verificationTokenHash) {
            this.verificationTokenHash = verificationTokenHash;
        }

        @JsonIgnore
        public LocalDateTime getVerificationExpiresAt() {
            return verificationExpiresAt;
        }

        public void setVerificationExpiresAt(LocalDateTime verificationExpiresAt) {
            this.verificationExpiresAt = verificationExpiresAt;
        }

        @JsonIgnore
        public LocalDateTime getVerificationLastSentAt() {
            return verificationLastSentAt;
        }

        public void setVerificationLastSentAt(LocalDateTime verificationLastSentAt) {
            this.verificationLastSentAt = verificationLastSentAt;
        }

        @JsonIgnore
        public List<AlertRule> getAlertRules() {
            return alertRules;
        }

        public String getThemePreference() { return themePreference; }
        public void setThemePreference(String themePreference) { this.themePreference = themePreference; }
        public String getElliottMotiveColor() { return elliottMotiveColor; }
        public void setElliottMotiveColor(String value) { this.elliottMotiveColor = value; }
        public String getElliottCorrectiveColor() { return elliottCorrectiveColor; }
        public void setElliottCorrectiveColor(String value) { this.elliottCorrectiveColor = value; }
        public boolean isMfaEnabled() { return mfaEnabled; }
        public void setMfaEnabled(boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }
        public String getMfaSecretCiphertext() { return mfaSecretCiphertext; }
        public void setMfaSecretCiphertext(String value) { this.mfaSecretCiphertext = value; }
        public String getMfaSecretIv() { return mfaSecretIv; }
        public void setMfaSecretIv(String value) { this.mfaSecretIv = value; }
        public long getSecurityVersion() { return securityVersion; }
        public void setSecurityVersion(long securityVersion) { this.securityVersion = securityVersion; }
        public Long getLastAcceptedTotpStep() { return lastAcceptedTotpStep; }
        public void setLastAcceptedTotpStep(Long value) { this.lastAcceptedTotpStep = value; }
        public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
        public void setPasswordChangedAt(LocalDateTime value) { this.passwordChangedAt = value; }
        public LocalDateTime getDeletionRequestedAt() { return deletionRequestedAt; }
        public void setDeletionRequestedAt(LocalDateTime value) { this.deletionRequestedAt = value; }
        public String getDeletionCancelTokenHash() { return deletionCancelTokenHash; }
        public void setDeletionCancelTokenHash(String value) { this.deletionCancelTokenHash = value; }
        public LocalDateTime getDeletionCancelExpiresAt() { return deletionCancelExpiresAt; }
        public void setDeletionCancelExpiresAt(LocalDateTime value) { this.deletionCancelExpiresAt = value; }
    }
