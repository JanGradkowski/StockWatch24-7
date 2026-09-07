package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Duration;
import java.util.UUID;

/** SMTP runs outside account/event transactions. Sensitive bodies are encrypted and erased on completion. */
@Service
public class EmailOutboxService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.stockwatch247.service.BackgroundJobDispatcher dispatcher;

    @org.springframework.beans.factory.annotation.Value("${email-outbox.worker-enabled:true}")
    private boolean workerEnabled = true;
    private final JdbcTemplate jdbc;
    private final SecurityCryptoService crypto;
    private final ObjectMapper mapper;
    private final ObjectProvider<JavaMailSender> senders;
    private final TransactionTemplate transactions;

    public EmailOutboxService(JdbcTemplate jdbc, SecurityCryptoService crypto, ObjectMapper mapper,
                              ObjectProvider<JavaMailSender> senders, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.crypto = crypto; this.mapper = mapper; this.senders = senders;
        this.transactions = new TransactionTemplate(manager);
    }

    @Transactional
    public void enqueue(SimpleMailMessage message, Long eventId, Duration lifetime) {
        enqueue(message, eventId, lifetime, eventId == null ? null : "initial-alert:" + eventId, "INITIAL");
    }

    @Transactional
    public void enqueue(SimpleMailMessage message, Long eventId, Duration lifetime, String deduplicationKey, String receipt) {
        if (senders.getIfAvailable() == null) throw new IllegalStateException("Email delivery is not configured.");
        UUID id = UUID.randomUUID();
        try {
            var payload = new Payload(message.getFrom(), message.getTo(), message.getSubject(), message.getText());
            var encrypted = crypto.encrypt(mapper.writeValueAsString(payload), "email-outbox:" + id);
            jdbc.update("""
                insert into email_outbox(id, deduplication_key, ciphertext, iv, alert_event_id, expires_at, receipt)
                values (?, ?, ?, ?, ?, current_timestamp + (? * interval '1 second'), ?)
                on conflict (deduplication_key) do nothing
                """, id, deduplicationKey,
                    encrypted.ciphertext(), encrypted.iv(), eventId, lifetime.toSeconds(), receipt);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not queue email.", e);
        }
    }

    @Scheduled(fixedDelayString = "${email-outbox.worker-delay-ms:1000}")
    public void dispatchPending() { dispatcher.submit("email", this::deliverPending); }

    public void deliverPending() {
        if (!workerEnabled) return;
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) return;
        // A bounded batch prevents this worker monopolizing the scheduler. SMTP timeouts are configured.
        for (int i = 0; i < 20; i++) {
            UUID owner = UUID.randomUUID();
            var claims = jdbc.query("""
                with candidate as (
                    select id from email_outbox where delivered_at is null and expired_at is null
                    and expires_at > current_timestamp and available_at <= current_timestamp
                    and (lease_until is null or lease_until < current_timestamp)
                    order by available_at for update skip locked limit 1
                ) update email_outbox e set owner = ?, lease_until = current_timestamp + interval '2 minutes',
                    attempts = attempts + 1 from candidate where e.id = candidate.id
                returning e.id, e.ciphertext, e.iv, e.alert_event_id, e.receipt
                """, (rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getObject(4, Long.class), rs.getString(5)), owner);
            if (claims.isEmpty()) break;
            Claim claim = claims.getFirst();
            try {
                Payload payload = mapper.readValue(crypto.decrypt(claim.body(), claim.iv(),
                        "email-outbox:" + claim.id()), Payload.class);
                var message = sender.createMimeMessage();
                var helper = new MimeMessageHelper(message, "UTF-8");
                helper.setFrom(payload.from()); helper.setTo(payload.to());
                helper.setSubject(payload.subject()); helper.setText(payload.text());
                message.saveChanges();
                message.setHeader("Message-ID", "<" + claim.id() + "@stockwatch.local>");
                sender.send(message);
                transactions.executeWithoutResult(status -> {
                    int updated = jdbc.update("""
                        update email_outbox set delivered_at = current_timestamp, ciphertext = null, iv = null,
                        owner = null, lease_until = null where id = ? and owner = ? and lease_until > current_timestamp
                        """, claim.id(), owner);
                    if (updated == 1 && claim.eventId() != null) jdbc.update("""
                        update alert_events set
                        initial_email_sent_at = case when ? = 'INITIAL' then coalesce(initial_email_sent_at, current_timestamp) else initial_email_sent_at end,
                        follow_up_sent_at = case when ? = 'FOLLOW_UP' then current_timestamp else follow_up_sent_at end where id = ?
                        """, claim.receipt(), claim.receipt(), claim.eventId());
                });
            } catch (Exception failure) {
                // Do not persist/log provider exceptions: they can contain recipient addresses or message bodies.
                jdbc.update("""
                    update email_outbox set owner = null, lease_until = null,
                    available_at = current_timestamp + (least(3600, 15 * power(2, least(attempts, 8))) * interval '1 second')
                    where id = ? and owner = ? and lease_until > current_timestamp
                    """, claim.id(), owner);
            }
        }
        jdbc.update("""
            update email_outbox set expired_at = current_timestamp, ciphertext = null, iv = null, owner = null,
            lease_until = null where delivered_at is null and expired_at is null and expires_at <= current_timestamp
            and (lease_until is null or lease_until <= current_timestamp)
            """);
        jdbc.update("delete from email_outbox where coalesce(delivered_at, expired_at) < current_timestamp - interval '30 days'");
    }

    public record Payload(String from, String[] to, String subject, String text) { }
    private record Claim(UUID id, String body, String iv, Long eventId, String receipt) { }
}
