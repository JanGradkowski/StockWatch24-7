    package org.example.stockwatch247.model;
    import com.fasterxml.jackson.annotation.JsonIgnore;
    import jakarta.persistence.*;
    import java.time.LocalDateTime;
    import java.util.ArrayList;
    import java.util.List;

    @Entity
    @Table(name = "users") // "user" is a reserved keyword in Postgres, always pluralize it!
    public class User {

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
    }
