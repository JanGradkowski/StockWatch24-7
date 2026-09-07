package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false"})
class ReviewDurabilityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired HistoricalSignalCacheService cache;
    @Autowired CandleRevisionService revisions;
    @Autowired AlertCheckJobStore jobs;
    @Autowired SecurityCryptoService crypto;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;

    @Test void correctionDuringCalculationCannotBePublishedUnderTheNewGeneration() {
        String symbol = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        try {
            jdbc.update("insert into candles(symbol,time_interval,timestamp,open_price,high_price,low_price,close_price,volume) values (?,'1d',1,10,12,8,10,100)", symbol);
            long original = revisions.generation(symbol, "1d");
            AtomicInteger runs = new AtomicInteger();
            var result = cache.getOrCompute(HistoricalSignalCacheService.Family.CANDLESTICK, symbol, "1d", "race", "factory", Value.class, () -> {
                double close = jdbc.queryForObject("select close_price from candles where symbol = ?", Double.class, symbol);
                if (runs.incrementAndGet() == 1) jdbc.update("update candles set close_price = 11 where symbol = ?", symbol);
                return new Value(close);
            });
            assertThat(result.close()).isEqualTo(11);
            assertThat(runs).hasValue(2);
            assertThat(revisions.generation(symbol, "1d")).isGreaterThan(original);
            assertThat(cache.getOrCompute(HistoricalSignalCacheService.Family.CANDLESTICK, symbol, "1d", "race", "factory", Value.class,
                    () -> { throw new AssertionError("Fresh entry was not reused"); })).isEqualTo(result);
            jdbc.update("delete from candles where symbol = ?", symbol);
            assertThat(revisions.generation(symbol, "1d")).isGreaterThan(original + 1);
        } finally {
            jdbc.update("delete from candles where symbol = ?", symbol);
            jdbc.update("delete from candle_revisions where symbol = ?", symbol);
            jdbc.update("delete from historical_signal_cache where symbol = ?", symbol);
        }
    }

    @Test void expiredWorkerCannotAcknowledgeOrRescheduleTheNewOwnersClaim() {
        String symbol = "JOB" + UUID.randomUUID().toString().substring(0, 12);
        try {
            jobs.enqueue(symbol, TimeInterval.DAILY, Instant.now().minusSeconds(30));
            var first = jobs.claimNextForSymbol(Duration.ofMinutes(1), symbol).orElseThrow();
            jdbc.update("update alert_check_jobs set lease_until = current_timestamp - interval '1 second' where id = ?", first.id());
            var second = jobs.claimNextForSymbol(Duration.ofMinutes(1), symbol).orElseThrow();
            jobs.complete(first);
            jobs.retryOrFail(first, "stale", 5, Duration.ofSeconds(1));
            assertThat(jobs.renew(first, Duration.ofMinutes(1))).isFalse();
            assertThat(jdbc.queryForObject("select status from alert_check_jobs where id = ?", String.class, first.id())).isEqualTo("PROCESSING");
            assertThat(jobs.renew(second, Duration.ofMinutes(1))).isTrue();
            jobs.complete(second);
            assertThat(jdbc.queryForObject("select status from alert_check_jobs where id = ?", String.class, first.id())).isEqualTo("COMPLETED");
        } finally { jdbc.update("delete from alert_check_jobs where symbol = ?", symbol); }
    }

    @Test void encryptedOutboxRollsBackWithItsBusinessTransactionAndRetriesSmtpFailures() {
        JavaMailSender sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(call -> new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
        var outbox = new EmailOutboxService(jdbc, crypto, mapper, provider, manager);
        var transactions = new TransactionTemplate(manager);
        var message = new SimpleMailMessage(); message.setFrom("review@example.com"); message.setTo("recipient@example.com");
        message.setSubject("Review " + UUID.randomUUID()); message.setText("Secret code 12345678");
        long before = jdbc.queryForObject("select count(*) from email_outbox", Long.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            outbox.enqueue(message, null, Duration.ofMinutes(5)); throw new IllegalArgumentException("Simulated rollback");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from email_outbox", Long.class)).isEqualTo(before);
        outbox.enqueue(message, null, Duration.ofMinutes(5));
        UUID id = jdbc.queryForObject("select id from email_outbox where delivered_at is null order by created_at desc limit 1", UUID.class);
        try {
            assertThat(jdbc.queryForObject("select ciphertext from email_outbox where id = ?", String.class, id)).doesNotContain("12345678");
            doThrow(new MailSendException("Simulated SMTP outage")).doNothing().when(sender).send(any(jakarta.mail.internet.MimeMessage.class));
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select delivered_at is null and attempts = 1 from email_outbox where id = ?", Boolean.class, id)).isTrue();
            jdbc.update("update email_outbox set available_at = current_timestamp where id = ?", id);
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select delivered_at is not null and ciphertext is null and iv is null from email_outbox where id = ?", Boolean.class, id)).isTrue();
            verify(sender, times(2)).send(any(jakarta.mail.internet.MimeMessage.class));
        } finally { jdbc.update("delete from email_outbox where id = ?", id); }
    }
    public record Value(double close) { }
}
