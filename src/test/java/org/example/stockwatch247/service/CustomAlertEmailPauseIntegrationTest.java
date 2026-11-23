package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Custom alert emails are alert emails: "Pause all alert emails" stops them and they carry one-click unsubscribe. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false"})
class CustomAlertEmailPauseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SecurityCryptoService crypto;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired NotificationEmailLinks links;

    @Test
    void aPausedAccountGetsNoCustomAlertEmailAndAnActiveOneGetsTheUnsubscribeHeaders() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        var sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
        var outbox = new EmailOutboxService(jdbc, crypto, mapper, provider, manager);
        ReflectionTestUtils.setField(outbox, "links", links);
        String tag = UUID.randomUUID().toString();
        String email = tag + "@example.test";
        Long user = jdbc.queryForObject("""
                insert into users(email,password_hash,first_name,last_name,notification_emails_paused)
                values (?,'unused','Custom','Pause',true) returning id
                """, Long.class, email);
        String paused = "custom-alert:" + tag + ":1";
        String active = "custom-alert:" + tag + ":2";
        try {
            outbox.enqueue(message(email), null, Duration.ofMinutes(5), paused, "INITIAL");
            outbox.deliverPending();
            verify(sender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
            assertThat(jdbc.queryForObject("select expired_at is not null and ciphertext is null and delivered_at is null "
                    + "from email_outbox where deduplication_key=?", Boolean.class, paused)).isTrue();

            jdbc.update("update users set notification_emails_paused=false where id=?", user);
            outbox.enqueue(message(email), null, Duration.ofMinutes(5), active, "INITIAL");
            outbox.deliverPending();
            var captor = org.mockito.ArgumentCaptor.forClass(jakarta.mail.internet.MimeMessage.class);
            verify(sender, times(1)).send(captor.capture());
            assertThat(captor.getValue().getHeader("List-Unsubscribe", null)).isEqualTo("<" + links.unsubscribeUrl(user) + ">");
            assertThat(captor.getValue().getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");
        } finally {
            jdbc.update("delete from email_outbox where deduplication_key in (?,?)", paused, active);
            jdbc.update("delete from users where id=?", user);
        }
    }

    private static SimpleMailMessage message(String to) {
        var message = new SimpleMailMessage();
        message.setFrom("alerts@example.test");
        message.setTo(to);
        message.setSubject("StockWatch custom alert: 30% below the high");
        message.setText("UNH matched your custom alert.");
        return message;
    }
}
