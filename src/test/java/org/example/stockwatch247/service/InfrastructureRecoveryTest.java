package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class InfrastructureRecoveryTest {
    @Test void cooldownDoesNotSpinAndSuccessfulProbeResetsIt() {
        var time = new AtomicLong();
        var gate = new RetryBackoff(time::get);
        assertThat(gate.ready()).isTrue();
        long delay = gate.failed();
        assertThat(gate.ready()).isFalse();
        time.addAndGet(delay * 1_000_000_000L);
        assertThat(gate.ready()).isTrue();
        assertThat(gate.failed()).isBetween(31L, 35L);
        gate.succeeded();
        assertThat(gate.ready()).isTrue();
        assertThat(gate.failed()).isBetween(16L, 20L);
    }
    @Test void mailDiagnosticsInspectNestedFailuresWithoutExposingProviderText() {
        var failure = new org.springframework.mail.MailSendException("secret recipient", new java.net.SocketTimeoutException("secret"));
        assertThat(MailFailureCategory.of(failure)).isEqualTo(MailFailureCategory.NETWORK_TIMEOUT);
        assertThat(MailFailureCategory.of(new org.springframework.mail.MailAuthenticationException("secret")))
                .isEqualTo(MailFailureCategory.AUTHENTICATION);
        assertThat(InfrastructureRecovery.isDatabaseUnavailable(new org.springframework.jdbc.CannotGetJdbcConnectionException("offline"))).isTrue();
        assertThat(InfrastructureRecovery.isDatabaseUnavailable(new IllegalArgumentException("bad calculation"))).isFalse();
        assertThat(InfrastructureRecovery.isDatabaseUnavailable(new IllegalStateException(
                new java.sql.SQLException("connection closed", "08006")))).isTrue();
        assertThat(InfrastructureRecovery.isDatabaseUnavailable(new java.sql.SQLException("server restarting", "57P01"))).isTrue();
        assertThat(InfrastructureRecovery.isDatabaseUnavailable(new java.sql.SQLException("duplicate event", "23505"))).isFalse();
    }
    @Test void interruptedAnalysisCannotContinuePublishing() {
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(JobLeaseGuard::checkActive).isInstanceOf(AnalysisDeferredException.class); }
        finally { Thread.interrupted(); }
    }
}
