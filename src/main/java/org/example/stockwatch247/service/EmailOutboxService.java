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
import java.util.List;
import java.util.UUID;

/** SMTP runs outside account/event transactions. Sensitive bodies are encrypted and erased on completion. */
@Service
public class EmailOutboxService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmailOutboxService.class);
    @org.springframework.beans.factory.annotation.Autowired
    private WatchlistEmailPolicy watchlistEmails;
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.stockwatch247.service.BackgroundJobDispatcher dispatcher;

    @org.springframework.beans.factory.annotation.Value("${email-outbox.worker-enabled:true}")
    private boolean workerEnabled = true;
    private final JdbcTemplate jdbc;
    private final SecurityCryptoService crypto;
    private final ObjectMapper mapper;
    private final ObjectProvider<JavaMailSender> senders;
    private final TransactionTemplate transactions;
    private final RetryBackoff smtpBackoff = new RetryBackoff();

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
        enqueueNotification(message, eventId, null, lifetime, deduplicationKey, receipt);
    }

    @Transactional
    public void enqueueOutlook(SimpleMailMessage message, long notificationId, Duration lifetime) {
        enqueueNotification(message, null, notificationId, lifetime, "outlook:" + notificationId, "INITIAL");
    }

    private void enqueueNotification(SimpleMailMessage message, Long eventId, Long outlookId,
                                     Duration lifetime, String deduplicationKey, String receipt) {
        enqueueNotification(message, eventId, outlookId, lifetime, deduplicationKey, receipt, null);
    }

    private void enqueueNotification(SimpleMailMessage message, Long eventId, Long outlookId,
                                     Duration lifetime, String deduplicationKey, String receipt, String digestReceipts) {
        if (senders.getIfAvailable() == null) throw new IllegalStateException("Email delivery is not configured.");
        UUID id = UUID.randomUUID();
        try {
            var payload = new Payload(message.getFrom(), message.getTo(), message.getSubject(), message.getText());
            var encrypted = crypto.encrypt(mapper.writeValueAsString(payload), "email-outbox:" + id);
            int inserted = jdbc.update("""
                insert into email_outbox(id, deduplication_key, ciphertext, iv, alert_event_id, outlook_notification_id, expires_at, receipt,
                    digest_receipts)
                values (?, ?, ?, ?, ?, ?, current_timestamp + (? * interval '1 second'), ?, ?)
                on conflict (deduplication_key) do nothing
                """, id, deduplicationKey,
                    encrypted.ciphertext(), encrypted.iv(), eventId, outlookId, lifetime.toSeconds(), receipt, digestReceipts);
            if (inserted > 0 && eventId != null) {
                log.info("Technical signal email queued: eventId={} receipt={} outboxId={}", eventId, receipt, id);
            }
            if (inserted > 0 && outlookId != null) {
                log.info("Technical outlook email queued: notificationId={} outboxId={}", outlookId, id);
            }
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not queue email.", e);
        }
    }

    /**
     * Holds an alert email for the account's next summary instead of sending it now. The key is the one the
     * email would have had in the outbox, so a retried check holds it once, and an email that already went
     * out on its own is not held again.
     */
    @Transactional
    public void hold(SimpleMailMessage message, long userId, Long eventId, Long outlookId, String deduplicationKey, String receipt) {
        if (senders.getIfAvailable() == null) throw new IllegalStateException("Email delivery is not configured.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from email_outbox where deduplication_key = ?)",
                Boolean.class, deduplicationKey))) return;
        try {
            var payload = new Payload(message.getFrom(), message.getTo(), message.getSubject(), message.getText());
            var encrypted = crypto.encrypt(mapper.writeValueAsString(payload), "email-digest:" + deduplicationKey);
            jdbc.update("""
                insert into email_digest_items(user_id, deduplication_key, alert_event_id, outlook_notification_id, receipt, ciphertext, iv)
                values (?, ?, ?, ?, ?, ?, ?) on conflict (deduplication_key) do nothing
                """, userId, deduplicationKey, eventId, outlookId, receipt, encrypted.ciphertext(), encrypted.iv());
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not queue email.", e);
        }
    }

    @Scheduled(fixedDelayString = "${email-outbox.worker-delay-ms:1000}")
    public void dispatchPending() { dispatcher.submit("email", this::deliverPending); }

    @Scheduled(fixedDelayString = "${email-digest.delay-ms:60000}", initialDelayString = "${email-digest.initial-delay-ms:30000}")
    public void dispatchDigests() { dispatcher.submit("email", this::flushDigests); }

    /**
     * Turns held emails into summaries: for accounts whose checks have been quiet for {@code quiet-seconds}
     * (a daily run's signals arrive within minutes), whose oldest held email has waited
     * {@code max-wait-seconds}, or that switched the summary off. Each summary is queued in the outbox in the
     * transaction that deletes its items.
     */
    public int flushDigests() {
        if (!workerEnabled) return 0;
        List<Long> due = jdbc.queryForList("""
            select i.user_id from email_digest_items i join users u on u.id = i.user_id group by i.user_id
            having max(i.created_at) <= current_timestamp - (? * interval '1 second')
                or min(i.created_at) <= current_timestamp - (? * interval '1 second')
                or bool_or(not u.alert_email_digest)
            limit 100
            """, Long.class, digestQuietSeconds, digestMaxWaitSeconds);
        int queued = 0;
        for (Long userId : due) {
            if (Boolean.TRUE.equals(transactions.execute(status -> flushDigest(userId)))) queued++;
        }
        return queued;
    }

    private boolean flushDigest(long userId) {
        List<HeldItem> items = jdbc.query("""
            select id, deduplication_key, alert_event_id, outlook_notification_id, receipt, ciphertext, iv
            from email_digest_items where user_id = ? order by id for update skip locked limit 200
            """, (rs, row) -> new HeldItem(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class),
                rs.getObject(4, Long.class), rs.getString(5), rs.getString(6), rs.getString(7)), userId);
        if (items.isEmpty()) return false;
        List<Payload> payloads = new java.util.ArrayList<>();
        List<DigestReceipt> receipts = new java.util.ArrayList<>();
        for (HeldItem item : items) {
            // The list settings may have changed while the email waited, as they may for a queued email.
            if (watchlistEmails != null && (item.eventId() != null && !watchlistEmails.allowsQueuedEvent(item.eventId())
                    || item.outlookId() != null && !watchlistEmails.allowsQueuedOutlook(item.outlookId()))) continue;
            payloads.add(mapper.readValue(crypto.decrypt(item.ciphertext(), item.iv(), "email-digest:" + item.key()), Payload.class));
            receipts.add(new DigestReceipt(item.eventId(), item.outlookId(), item.receipt()));
        }
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("delete from email_digest_items where id = any(?)");
            statement.setArray(1, connection.createArrayOf("bigint", items.stream().map(HeldItem::id).toArray(Long[]::new)));
            return statement;
        });
        if (payloads.isEmpty()) return false;
        enqueueNotification(digestMessage(userId, payloads), null, null, Duration.ofDays(7),
                "digest:" + userId + ":" + items.getFirst().id(), "DIGEST", mapper.writeValueAsString(receipts));
        return true;
    }

    /**
     * One email for the held ones: a list of their subjects, then each email in full, then the email settings
     * links once. A single held email goes out as it was. Held emails are already in the recipient's language;
     * the summary's own lines are translated here.
     */
    SimpleMailMessage digestMessage(long userId, List<Payload> payloads) {
        Payload first = payloads.getFirst();
        java.util.Locale locale = languages == null || first.to() == null || first.to().length == 0
                ? java.util.Locale.ENGLISH : languages.localeForEmail(first.to()[0]);
        java.util.function.UnaryOperator<String> translate = text ->
                org.example.stockwatch247.i18n.ServerMessages.translate(text, locale);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(first.from());
        message.setTo(first.to());
        StringBuilder text = new StringBuilder();
        if (payloads.size() == 1) {
            message.setSubject(first.subject());
            text.append(first.text().stripTrailing());
        } else {
            message.setSubject(translate.apply("StockWatch summary: " + payloads.size() + " alerts"));
            text.append(translate.apply("This summary collects " + payloads.size() + " alerts from your latest checks."))
                    .append("\n\n");
            for (Payload payload : payloads) text.append("- ").append(payload.subject()).append('\n');
            for (int index = 0; index < payloads.size(); index++) {
                Payload payload = payloads.get(index);
                text.append('\n').append("=".repeat(60)).append('\n')
                        .append(index + 1).append(". ").append(payload.subject()).append("\n\n")
                        .append(payload.text().stripTrailing()).append('\n');
            }
        }
        if (links != null) {
            text.append("\n\n")
                    .append(translate.apply("Manage alert emails: " + links.baseUrl() + NotificationEmailLinks.MANAGE_PATH)).append('\n')
                    .append(translate.apply("Stop all alert emails: " + links.unsubscribeUrl(userId))).append('\n');
        }
        message.setText(text.toString());
        return message;
    }

    public void deliverPending() {
        if (!workerEnabled || !smtpBackoff.ready()) return;
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
                returning e.id, e.ciphertext, e.iv, e.alert_event_id, e.receipt, e.outlook_notification_id, e.attempts,
                    e.deduplication_key, e.digest_receipts
                """, (rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getObject(4, Long.class), rs.getString(5), rs.getObject(6, Long.class), rs.getInt(7),
                    rs.getString(8), rs.getString(9)), owner);
            if (claims.isEmpty()) break;
            Claim claim = claims.getFirst();
            long started = System.nanoTime();
            boolean accepted = false;
            try {
                if (watchlistEmails != null && (claim.eventId() != null && !watchlistEmails.allowsQueuedEvent(claim.eventId())
                        || claim.outlookId() != null && !watchlistEmails.allowsQueuedOutlook(claim.outlookId()))) {
                    jdbc.update("update email_outbox set expired_at=current_timestamp,ciphertext=null,iv=null,owner=null,lease_until=null where id=? and owner=?",claim.id(),owner);
                    continue;
                }
                Payload payload = mapper.readValue(crypto.decrypt(claim.body(), claim.iv(),
                        "email-outbox:" + claim.id()), Payload.class);
                // Alert and notification emails stop when the account paused them, and carry the
                // one-click unsubscribe headers mailbox providers show next to the sender.
                var account = claim.notification() && links != null && payload.to() != null && payload.to().length > 0
                        ? links.account(payload.to()[0]) : java.util.Optional.<org.example.stockwatch247.model.User>empty();
                if (account.isPresent() && account.get().isNotificationEmailsPaused()) {
                    jdbc.update("update email_outbox set expired_at=current_timestamp,ciphertext=null,iv=null,owner=null,lease_until=null where id=? and owner=?",claim.id(),owner);
                    continue;
                }
                // Generate the stable ID directly. The default updateMessageID
                // resolves the local host, even when its random ID is overwritten.
                var message = new jakarta.mail.internet.MimeMessage(sender.createMimeMessage().getSession()) {
                    @Override protected void updateMessageID() throws jakarta.mail.MessagingException {
                        setHeader("Message-ID", "<" + claim.id() + "@" + messageIdDomain + ">");
                    }
                };
                var helper = new MimeMessageHelper(message, "UTF-8");
                helper.setFrom(payload.from()); helper.setTo(payload.to());
                helper.setSubject(payload.subject()); helper.setText(payload.text());
                if (account.isPresent()) {
                    message.setHeader("List-Unsubscribe", "<" + links.unsubscribeUrl(account.get().getId()) + ">");
                    message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                }
                message.saveChanges();
                // Recheck after payload/policy work or a process pause, before touching SMTP.
                if (jdbc.update("update email_outbox set lease_until=current_timestamp + interval '2 minutes' "
                        + "where id=? and owner=? and lease_until > current_timestamp", claim.id(), owner) != 1) {
                    defer(claim, owner);
                    continue;
                }
                sender.send(message);
                accepted = true;
                smtpBackoff.succeeded();
                transactions.executeWithoutResult(status -> {
                    int updated = jdbc.update("""
                        update email_outbox set delivered_at = current_timestamp, ciphertext = null, iv = null,
                        owner = null, lease_until = null where id = ? and owner = ? and lease_until > current_timestamp
                        """, claim.id(), owner);
                    if (updated != 1) throw new IllegalStateException("SMTP accepted but delivery ownership expired.");
                    if (updated == 1 && claim.eventId() != null) recordEventReceipt(claim.eventId(), claim.receipt());
                    if (updated == 1 && claim.outlookId() != null) recordOutlookReceipt(claim.outlookId());
                    if (updated == 1 && claim.digestReceipts() != null) {
                        for (var receipt : mapper.readValue(claim.digestReceipts(), DigestReceipt[].class)) {
                            if (receipt.eventId() != null) recordEventReceipt(receipt.eventId(), receipt.receipt());
                            if (receipt.outlookId() != null) recordOutlookReceipt(receipt.outlookId());
                        }
                    }
                });
                log.info("Email accepted by SMTP: outboxId={} eventId={} outlookId={} receipt={}",
                        claim.id(), claim.eventId(), claim.outlookId(), claim.receipt());
            } catch (Exception failure) {
                MailFailureCategory category = accepted ? MailFailureCategory.DATABASE_ACKNOWLEDGEMENT : MailFailureCategory.of(failure);
                long cooldown = category.providerOutage() ? smtpBackoff.failed() : 0;
                log.warn("Email delivery deferred: outboxId={} eventId={} outlookId={} attempt={} category={} smtpAccepted={} elapsedMs={} providerRetryInSeconds={}",
                        claim.id(), claim.eventId(), claim.outlookId(), claim.attempts(), category, accepted,
                        (System.nanoTime() - started) / 1_000_000, cooldown);
                // Owner fencing is sufficient here: an expired, unclaimed attempt still needs backoff.
                // If another worker reclaimed it, its different owner prevents this write.
                defer(claim, owner);
                if (InfrastructureRecovery.isDatabaseUnavailable(failure))
                    throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
                if (category.providerOutage()) break;
            }
        }
    }

    /**
     * Erases bodies whose delivery window has passed (the worker already skips them) and removes old
     * receipts. Hourly is enough; running it with every one-second delivery poll only added writes.
     */
    @Scheduled(cron = "${email-outbox.cleanup-cron:0 5 * * * *}")
    public void expireAndPurge() {
        try {
            jdbc.update("""
                update email_outbox set expired_at = current_timestamp, ciphertext = null, iv = null, owner = null,
                lease_until = null where delivered_at is null and expired_at is null and expires_at <= current_timestamp
                and (lease_until is null or lease_until <= current_timestamp)
                """);
            jdbc.update("delete from email_outbox where coalesce(delivered_at, expired_at) < current_timestamp - interval '30 days'");
        } catch (RuntimeException failure) {
            log.warn("Email outbox cleanup deferred to the next run: {}", failure.getClass().getSimpleName());
        }
    }

    private void recordEventReceipt(long eventId, String receipt) {
        jdbc.update("""
            update alert_events set
            initial_email_sent_at = case when ? = 'INITIAL' then coalesce(initial_email_sent_at, current_timestamp) else initial_email_sent_at end,
            follow_up_sent_at = case when ? = 'FOLLOW_UP' then current_timestamp else follow_up_sent_at end where id = ?
            """, receipt, receipt, eventId);
    }

    private void recordOutlookReceipt(long notificationId) {
        jdbc.update("""
            update technical_outlook_notifications set email_sent_at = coalesce(email_sent_at, current_timestamp)
            where id = ?
            """, notificationId);
    }

    private void defer(Claim claim, UUID owner) {
        jdbc.update("""
            update email_outbox set owner = null, lease_until = null,
            available_at = current_timestamp + ((least(3600, 15 * power(2, least(attempts, 8))) + floor(random() * 6)) * interval '1 second')
            where id = ? and owner = ? and delivered_at is null
            """, claim.id(), owner);
    }

    public record Payload(String from, String[] to, String subject, String text) { }
    private record HeldItem(long id, String key, Long eventId, Long outlookId, String receipt, String ciphertext, String iv) { }
    record DigestReceipt(Long eventId, Long outlookId, String receipt) { }
    private record Claim(UUID id, String body, String iv, Long eventId, String receipt, Long outlookId, int attempts,
                         String deduplicationKey, String digestReceipts) {
        /** Alert and notification emails, as opposed to account and security messages. */
        boolean notification() {
            return eventId != null || outlookId != null || digestReceipts != null
                    || deduplicationKey != null && deduplicationKey.startsWith("chart-alert:");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.example.stockwatch247.i18n.LanguagePreferenceService languages;
    @org.springframework.beans.factory.annotation.Value("${email-digest.quiet-seconds:600}")
    private long digestQuietSeconds = 600;
    @org.springframework.beans.factory.annotation.Value("${email-digest.max-wait-seconds:3600}")
    private long digestMaxWaitSeconds = 3600;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NotificationEmailLinks links;

    /** Message-IDs name the public site, not a made-up local host that spam filters distrust. */
    private String messageIdDomain = "stockwatch.local";

    @org.springframework.beans.factory.annotation.Value("${app.public-base-url:http://localhost:8080}")
    void setPublicBaseUrl(String publicBaseUrl) {
        try {
            String host = java.net.URI.create(publicBaseUrl.trim()).getHost();
            if (host != null && !host.isBlank()) messageIdDomain = host;
        } catch (IllegalArgumentException invalid) {
            // Keep the fallback domain; production configuration validation reports a bad base URL.
        }
    }
}
