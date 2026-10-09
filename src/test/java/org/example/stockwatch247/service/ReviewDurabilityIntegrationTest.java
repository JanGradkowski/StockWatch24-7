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
    @Autowired AnalysisCheckpointStore checkpoints;

    @Test void realLocalSmtpAcceptanceClearsPayloadAndPersistsReceipt() throws Exception {
        String key="smtp-sink:"+UUID.randomUUID();
        var received=new java.util.concurrent.CompletableFuture<String>();
        try(var server=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            var sink=java.util.concurrent.CompletableFuture.runAsync(() -> {
                try(var socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    var input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                    var output=new java.io.PrintWriter(socket.getOutputStream(),true,java.nio.charset.StandardCharsets.UTF_8);
                    output.print("220 localhost test SMTP\r\n");output.flush();
                    for(String line;(line=input.readLine())!=null;) {
                        if(line.startsWith("DATA")) {
                            output.print("354 send message\r\n");output.flush();
                            var body=new StringBuilder();
                            for(String part;(part=input.readLine())!=null&&!part.equals(".");)body.append(part).append('\n');
                            received.complete(body.toString());output.print("250 accepted\r\n");
                        } else if(line.startsWith("QUIT")) {output.print("221 closing\r\n");output.flush();break;}
                        else output.print("250 OK\r\n");
                        output.flush();
                    }
                } catch(Exception failure) {received.completeExceptionally(failure);throw new java.util.concurrent.CompletionException(failure);}
            });
            var sender=new org.springframework.mail.javamail.JavaMailSenderImpl();
            sender.setHost(server.getInetAddress().getHostAddress());sender.setPort(server.getLocalPort());
            sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout","2000");
            sender.getJavaMailProperties().setProperty("mail.smtp.timeout","2000");
            sender.getJavaMailProperties().setProperty("mail.smtp.localhost","stockwatch.local");
            @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(sender);
            var outbox=new EmailOutboxService(jdbc,crypto,mapper,provider,manager);
            var message=new SimpleMailMessage();message.setFrom("sender@example.invalid");message.setTo("sink@example.invalid");
            message.setSubject("Local delivery acceptance");message.setText("Local sink only");
            outbox.enqueue(message,null,Duration.ofMinutes(5),key,"INITIAL");
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select delivered_at is not null and ciphertext is null and attempts=1 from email_outbox where deduplication_key=?",Boolean.class,key)).isTrue();
            String body=received.get(5,java.util.concurrent.TimeUnit.SECONDS);
            UUID outboxId=jdbc.queryForObject("select id from email_outbox where deduplication_key=?",UUID.class,key);
            assertThat(body).contains("Local sink only","Message-ID: <"+outboxId+"@stockwatch.local>");
            sink.get(5,java.util.concurrent.TimeUnit.SECONDS);
        } finally {jdbc.update("delete from email_outbox where deduplication_key=?",key);}
    }

    @Test void checkpointSurvivesOuterRollbackAndOlderProgressCannotOverwriteIt() {
        String key=AnalysisWork.key(UUID.randomUUID());
        try {
            var tx=new TransactionTemplate(manager);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                checkpoints.write(key,new Value(20),20);
                throw new IllegalStateException("outer work yielded");
            })).isInstanceOf(IllegalStateException.class);
            checkpoints.write(key,new Value(10),10);
            assertThat(checkpoints.read(key,Value.class)).isEqualTo(new Value(20));
            checkpoints.write(key,new Value(30),30);
            assertThat(checkpoints.read(key,Value.class)).isEqualTo(new Value(30));
        } finally { jdbc.update("delete from analysis_checkpoints where cache_key=?",key); }
    }

    @Test void cancellationCutoffPreventsCatchUpFromRecreatingDiscardedWork() {
        String symbol="CANCEL"+UUID.randomUUID().toString().substring(0,8);
        Instant cutoff=Instant.parse("2026-09-30T18:24:31Z");
        try {
            // Other integration tests share this schema and may have recorded later daily runs.
            jdbc.update("delete from alert_schedule_runs where interval='DAILY' and scheduled_for > ?",cutoff.atOffset(java.time.ZoneOffset.UTC));
            jdbc.update("insert into alert_schedule_control(id,cancelled_through) values(1,?)",cutoff.atOffset(java.time.ZoneOffset.UTC));
            assertThat(jobs.enqueue(symbol,TimeInterval.DAILY,cutoff.minusSeconds(1))).isZero();
            assertThat(jobs.enqueue(symbol,TimeInterval.DAILY,cutoff)).isZero();
            assertThat(jobs.enqueueScheduledRun(TimeInterval.DAILY,cutoff.minusSeconds(1))).isZero();
            assertThat(jobs.findLatestScheduledRun(TimeInterval.DAILY)).contains(cutoff);
            assertThat(jobs.enqueue(symbol,TimeInterval.DAILY,cutoff.plusSeconds(1))).isEqualTo(1);
        } finally {
            jdbc.update("delete from alert_schedule_control where id=1");
            jdbc.update("delete from alert_check_jobs where symbol=?",symbol);
        }
    }

    @Test void simultaneousWorkersCannotClaimDifferentTechnicalChecks() throws Exception {
        String symbol="CLAIM"+UUID.randomUUID().toString().substring(0,8);
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            jobs.enqueue(symbol,TimeInterval.DAILY,Instant.now().minusSeconds(20));
            jobs.enqueue(symbol,TimeInterval.WEEKLY,Instant.now().minusSeconds(10));
            var ready=new java.util.concurrent.CountDownLatch(4);
            var start=new java.util.concurrent.CountDownLatch(1);
            var claims=new java.util.ArrayList<java.util.concurrent.Future<java.util.Optional<AlertCheckJobStore.AlertCheckJob>>>();
            for(int i=0;i<4;i++) claims.add(executor.submit(() -> {
                ready.countDown(); start.await(); return jobs.claimNext(Duration.ofMinutes(1));
            }));
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            var claimed=new java.util.ArrayList<AlertCheckJobStore.AlertCheckJob>();
            for(var result:claims) result.get(5,java.util.concurrent.TimeUnit.SECONDS).ifPresent(claimed::add);
            assertThat(claimed).hasSize(1);
            assertThat(claimed.getFirst().interval()).isEqualTo(TimeInterval.DAILY);
            assertThat(jobs.claimNext(Duration.ofMinutes(1))).isEmpty();
        } finally { jdbc.update("delete from alert_check_jobs where symbol=?",symbol); }
    }

    @Test void oldestCheckRetainsPriorityUntilCompletedIncludingWhileDeferred() {
        String first="FAIR"+UUID.randomUUID().toString().substring(0,8), second=first+"B";
        try {
            jobs.enqueue(first,TimeInterval.DAILY,Instant.now().minusSeconds(180));
            jobs.enqueue(first,TimeInterval.DAILY,Instant.now().minusSeconds(120));
            jobs.enqueue(second,TimeInterval.DAILY,Instant.now().minusSeconds(60));
            var original=jobs.claimNextForSymbol(Duration.ofMinutes(1),first).orElseThrow();
            jobs.defer(original,"checkpoint saved",Duration.ofSeconds(5));
            assertThat(jobs.claimNextForSymbol(Duration.ofMinutes(1),first)).isEmpty();
            assertThat(jobs.claimNext(Duration.ofMinutes(1))).isEmpty();
            jdbc.update("update alert_check_jobs set available_at=current_timestamp where id=?",original.id());
            var resumed=jobs.claimNextForSymbol(Duration.ofMinutes(1),first).orElseThrow();
            assertThat(resumed.id()).isEqualTo(original.id());
            assertThat(resumed.attempts()).isGreaterThan(original.attempts());
            assertThat(jdbc.queryForObject("select calculation_failures from alert_check_jobs where id=?",Integer.class,resumed.id())).isZero();
            assertThat(jobs.claimNext(Duration.ofMinutes(1))).isEmpty();
            jobs.complete(resumed);
            var next=jobs.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertThat(next.symbol()).isEqualTo(first);
            jobs.complete(next);
            var last=jobs.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertThat(last.symbol()).isEqualTo(second);
            jobs.complete(last);
        } finally { jdbc.update("delete from alert_check_jobs where symbol in (?,?)",first,second); }
    }

    @Test void aSeriesWaitingOutADataOutageDoesNotBlockOtherInstruments() {
        String broken="DEAD"+UUID.randomUUID().toString().substring(0,8), healthy=broken+"OK";
        try {
            jobs.enqueue(broken,TimeInterval.DAILY,Instant.now().minusSeconds(180));
            jobs.enqueue(broken,TimeInterval.DAILY,Instant.now().minusSeconds(120));
            jobs.enqueue(healthy,TimeInterval.DAILY,Instant.now().minusSeconds(60));
            var stuck=jobs.claimNextForSymbol(Duration.ofMinutes(1),broken).orElseThrow();
            jobs.backoff(stuck,"No candles from any provider",Duration.ofMinutes(30),48);
            // The later check for the same series still waits for its predecessor.
            assertThat(jobs.claimNextForSymbol(Duration.ofMinutes(1),broken)).isEmpty();
            var other=jobs.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertThat(other.symbol()).isEqualTo(healthy);
            // Only one technical check runs at a time, even when the backoff expires.
            jdbc.update("update alert_check_jobs set available_at=current_timestamp where id=?",stuck.id());
            assertThat(jobs.claimNext(Duration.ofMinutes(1))).isEmpty();
            jobs.complete(other);
            var resumed=jobs.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertThat(resumed.id()).isEqualTo(stuck.id());
            assertThat(jdbc.queryForObject("select yields_priority from alert_check_jobs where id=?",Boolean.class,stuck.id())).isFalse();
        } finally { jdbc.update("delete from alert_check_jobs where symbol in (?,?)",broken,healthy); }
    }

    @Test void aSeriesThatNeverReturnsDataIsEventuallyFailed() {
        String symbol="DEAD"+UUID.randomUUID().toString().substring(0,8);
        try {
            jobs.enqueue(symbol,TimeInterval.DAILY,Instant.now().minusSeconds(60));
            for (int attempt=1; attempt<=3; attempt++) {
                var job=jobs.claimNextForSymbol(Duration.ofMinutes(1),symbol).orElseThrow();
                jobs.backoff(job,"No candles",Duration.ofSeconds(1),3);
                jdbc.update("update alert_check_jobs set available_at=current_timestamp where id=?",job.id());
            }
            assertThat(jdbc.queryForObject("select status from alert_check_jobs where symbol=?",String.class,symbol)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select last_error from alert_check_jobs where symbol=?",String.class,symbol))
                    .startsWith("Market data stayed unavailable after 3 attempts");
            assertThat(jdbc.queryForObject("select calculation_failures from alert_check_jobs where symbol=?",Integer.class,symbol)).isZero();
        } finally { jdbc.update("delete from alert_check_jobs where symbol=?",symbol); }
    }

    @Test void lifecycleTransitionAndOutboxCommitTogetherAndReceiptWaitsForSmtp() {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        var sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session)null));
        var outbox = new EmailOutboxService(jdbc, crypto, mapper, provider, manager);
        var notification = new AlertNotificationService(provider,true,"alerts@example.test","UTC");
        notification.setOutbox(outbox);
        String tag = UUID.randomUUID().toString();
        Long user = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?,'unused','Lifecycle','Test') returning id",Long.class,tag+"@example.test");
        Long asset = jdbc.queryForObject("insert into stock_assets(ticker_symbol,company_name,exchange) values (?,'Lifecycle test','TEST') returning id",Long.class,"LIFE"+tag.substring(0,8));
        try {
            Long ruleId = jdbc.queryForObject("insert into alert_rules(user_id,stock_asset_id,interval,target_pattern,pattern_family,trade_signal) values (?,?,'DAILY','HAMMER','CANDLESTICK','BUY') returning id",Long.class,user,asset);
            Long eventId = jdbc.queryForObject("insert into alert_events(alert_rule_id,pattern,trade_signal,signal_candle_timestamp,confidence_score,close_price) values (?,'HAMMER','BUY',1700000000,90,100) returning id",Long.class,ruleId);
            var fixtures = new AlertNotificationServiceTest();
            var rule = fixtures.dailyRule(org.example.stockwatch247.model.enums.TradeSignal.BUY);
            var event = fixtures.trackedEvent(rule,org.example.stockwatch247.model.enums.SignalLifecycleStatus.CONFIRMED);
            event.setId(eventId); event.setResolutionCandleTimestamp(1700086400L);
            var tx = new TransactionTemplate(manager);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update("update alert_events set lifecycle_status='CONFIRMED' where id=?",eventId);
                notification.sendSignalLifecycleEmail(event);
                throw new IllegalStateException("rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("select count(*) from email_outbox where alert_event_id=?",Integer.class,eventId)).isZero();
            tx.executeWithoutResult(status -> {
                jdbc.update("update alert_events set lifecycle_status='CONFIRMED' where id=?",eventId);
                notification.sendSignalLifecycleEmail(event);
            });
            notification.sendSignalLifecycleEmail(event);
            assertThat(jdbc.queryForObject("select count(*) from email_outbox where alert_event_id=?",Integer.class,eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select follow_up_sent_at is null from alert_events where id=?",Boolean.class,eventId)).isTrue();
            verify(sender,never()).send(any(org.springframework.mail.SimpleMailMessage.class));
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select follow_up_sent_at is not null from alert_events where id=?",Boolean.class,eventId)).isTrue();
        } finally { jdbc.update("delete from users where id=?",user); jdbc.update("delete from stock_assets where id=?",asset); }
    }

    @Test void expiredEmailLeaseStillReceivesBackoffWhenNoOtherWorkerOwnsIt() {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        var sender=mock(JavaMailSender.class); when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session)null));
        var outbox=new EmailOutboxService(jdbc,crypto,mapper,provider,manager);
        String key="lease-test:"+UUID.randomUUID();
        var message=new SimpleMailMessage(); message.setFrom("from@example.test"); message.setTo("to@example.test");message.setSubject("test");message.setText("test");
        outbox.enqueue(message,null,Duration.ofMinutes(5),key,"INITIAL");
        try {
            doAnswer(invocation -> {
                jdbc.update("update email_outbox set lease_until=current_timestamp-interval '1 second' where deduplication_key=?",key);
                throw new MailSendException("simulated pause and failure");
            }).when(sender).send(any(jakarta.mail.internet.MimeMessage.class));
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select attempts=1 and owner is null and available_at > current_timestamp from email_outbox where deduplication_key=?",Boolean.class,key)).isTrue();
            verify(sender,times(1)).send(any(jakarta.mail.internet.MimeMessage.class));
        } finally { jdbc.update("delete from email_outbox where deduplication_key=?",key); }
    }

    @Test void smtpConnectionOutageStopsTheBatchAndSuppressesImmediatePolling() {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);
        var sender=mock(JavaMailSender.class);when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session)null));
        var outbox=new EmailOutboxService(jdbc,crypto,mapper,provider,manager);
        var message=new SimpleMailMessage();message.setFrom("from@example.test");message.setTo("to@example.test");message.setSubject("test");message.setText("test");
        String key="outage-test:"+UUID.randomUUID();
        outbox.enqueue(message,null,Duration.ofMinutes(5),key+":1","INITIAL");
        outbox.enqueue(message,null,Duration.ofMinutes(5),key+":2","INITIAL");
        try {
            doThrow(new MailSendException("offline",new java.net.ConnectException())).when(sender).send(any(jakarta.mail.internet.MimeMessage.class));
            outbox.deliverPending();outbox.deliverPending();
            verify(sender,times(1)).send(any(jakarta.mail.internet.MimeMessage.class));
            assertThat(jdbc.queryForObject("select sum(attempts) from email_outbox where deduplication_key like ?",Integer.class,key+"%")).isEqualTo(1);
        } finally { jdbc.update("delete from email_outbox where deduplication_key like ?",key+"%"); }
    }

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
            jobs.defer(first, "stale provider failure", Duration.ofMinutes(5));
            assertThat(jobs.renew(first, Duration.ofMinutes(1))).isFalse();
            assertThat(jdbc.queryForObject("select status from alert_check_jobs where id = ?", String.class, first.id())).isEqualTo("PROCESSING");
            assertThat(jobs.renew(second, Duration.ofMinutes(1))).isTrue();
            jobs.complete(second);
            assertThat(jdbc.queryForObject("select status from alert_check_jobs where id = ?", String.class, first.id())).isEqualTo("COMPLETED");
        } finally { jdbc.update("delete from alert_check_jobs where symbol = ?", symbol); }
    }

    @Test void providerDeferralsDoNotConsumeCalculationRetries() {
        String symbol = "JOB" + UUID.randomUUID().toString().substring(0, 12);
        try {
            jobs.enqueue(symbol, TimeInterval.DAILY, Instant.now());
            for (int i = 0; i < 4; i++) {
                var job = jobs.claimNextForSymbol(Duration.ofMinutes(1), symbol).orElseThrow();
                jobs.defer(job, "Provider offline", Duration.ofMinutes(1));
                jdbc.update("update alert_check_jobs set available_at = current_timestamp where id = ?", job.id());
            }
            for (int failure = 1; failure <= 3; failure++) {
                var job = jobs.claimNextForSymbol(Duration.ofMinutes(1), symbol).orElseThrow();
                jobs.retryOrFail(job, "Calculation failed", 3, Duration.ofSeconds(1));
                assertThat(jdbc.queryForObject("select calculation_failures from alert_check_jobs where id = ?", Integer.class, job.id())).isEqualTo(failure);
                assertThat(jdbc.queryForObject("select status from alert_check_jobs where id = ?", String.class, job.id()))
                        .isEqualTo(failure < 3 ? "PENDING" : "FAILED");
                jdbc.update("update alert_check_jobs set available_at = current_timestamp where id = ?", job.id());
            }
        } finally {
            jdbc.update("delete from alert_check_jobs where symbol = ?", symbol);
        }
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
    @Test void technicalSignalIsDeduplicatedAndReceiptedOnlyAfterSuccessfulSmtpDelivery() {
        JavaMailSender sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(call -> new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties())));
        var outbox = new EmailOutboxService(jdbc, crypto, mapper, provider, manager);
        var notification = new AlertNotificationService(provider, true, "sender@example.test", "UTC");
        notification.setOutbox(outbox);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Long userId = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?, 'unused', 'Delivery', 'Test') returning id",
                Long.class, suffix + "@example.test");
        Long assetId = null;
        Long eventId = null;
        try {
            assetId = jdbc.queryForObject("insert into stock_assets(ticker_symbol,company_name,exchange) values (?, 'Delivery test', 'TEST') returning id",
                    Long.class, "MAIL" + suffix.substring(0, 10));
            Long ruleId = jdbc.queryForObject("insert into alert_rules(user_id,stock_asset_id,interval,target_pattern,pattern_family,trade_signal) values (?,?,'DAILY','BULLISH_ENGULFING','CANDLESTICK','BUY') returning id",
                    Long.class, userId, assetId);
            eventId = jdbc.queryForObject("insert into alert_events(alert_rule_id,pattern,trade_signal,signal_candle_timestamp,confidence_score,close_price) values (?,'BULLISH_ENGULFING','BUY',1700000000,90,100) returning id",
                    Long.class, ruleId);
            var user = new org.example.stockwatch247.model.User();
            user.setId(userId);
            user.setEmail(suffix + "@example.test");
            var asset = new org.example.stockwatch247.model.StockAsset();
            asset.setId(assetId);
            asset.setTickerSymbol("MAIL" + suffix.substring(0, 10));
            var rule = new org.example.stockwatch247.model.AlertRule();
            rule.setId(ruleId);
            rule.setUser(user);
            rule.setStockAsset(asset);
            rule.setInterval(TimeInterval.DAILY);
            rule.setPatternFamily(org.example.stockwatch247.model.enums.AlertPatternFamily.CANDLESTICK);
            rule.setTradeSignal(org.example.stockwatch247.model.enums.TradeSignal.BUY);
            var event = new org.example.stockwatch247.model.AlertEvent();
            event.setId(eventId);
            var signal = new CandlePatternDetectionService.DetectedSignal(
                    org.example.stockwatch247.model.enums.CandlePattern.BULLISH_ENGULFING,
                    org.example.stockwatch247.model.enums.TradeSignal.BUY,
                    org.example.stockwatch247.model.enums.SignalStength.HIGH_CONFIDENCE,
                    90, List.of("Delivery regression"), 1700000000L, 100.0);

            assertThat(notification.sendSignalEmail(rule, signal, event)).isTrue();
            assertThat(notification.sendSignalEmail(rule, signal, event)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from email_outbox where alert_event_id = ?", Long.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select initial_email_sent_at is null from alert_events where id = ?", Boolean.class, eventId)).isTrue();
            verify(sender, never()).send(any(jakarta.mail.internet.MimeMessage.class));

            doThrow(new MailSendException("Simulated outage")).doNothing().when(sender).send(any(jakarta.mail.internet.MimeMessage.class));
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select initial_email_sent_at is null from alert_events where id = ?", Boolean.class, eventId)).isTrue();
            jdbc.update("update email_outbox set available_at = current_timestamp where alert_event_id = ?", eventId);
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select initial_email_sent_at is not null from alert_events where id = ?", Boolean.class, eventId)).isTrue();
            assertThat(jdbc.queryForObject("select delivered_at is not null and ciphertext is null and iv is null from email_outbox where alert_event_id = ?", Boolean.class, eventId)).isTrue();
            verify(sender, times(2)).send(any(jakarta.mail.internet.MimeMessage.class));
        } finally {
            if (eventId != null) jdbc.update("delete from email_outbox where alert_event_id = ?", eventId);
            jdbc.update("delete from users where id = ?", userId);
            if (assetId != null) jdbc.update("delete from stock_assets where id = ?", assetId);
        }
    }

    @Test void outlookEmailRetriesAndReceiptsWithoutBlockingItsCalculation() {
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            String suffix = UUID.randomUUID().toString();
            Long userId = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?, 'unused', 'Outlook', 'Test') returning id",
                    Long.class, suffix + "@example.test");
            Long assetId = jdbc.queryForObject("insert into stock_assets(ticker_symbol,company_name,exchange) values (?, 'Outlook test', 'TEST') returning id",
                    Long.class, "OUT" + suffix.substring(0, 8));
            Long subscriptionId = jdbc.queryForObject("insert into technical_outlook_subscriptions(user_id,stock_asset_id,interval) values (?,?,'DAILY') returning id",
                    Long.class, userId, assetId);
            Long notificationId = jdbc.queryForObject("""
                    insert into technical_outlook_notifications(subscription_id,previous_classification,current_classification,
                        previous_score,current_score,previous_candle_timestamp,current_candle_timestamp,previous_snapshot,current_snapshot,change_report)
                    values (?,'Neutral outlook','Moderate buy outlook',0,0.5,1,2,'{}','{}','{}') returning id
                    """, Long.class, subscriptionId);
            JavaMailSender sender = mock(JavaMailSender.class);
            ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(sender);
            var mailProperties = new java.util.Properties();
            mailProperties.setProperty("mail.from", "sender@example.test");
            when(sender.createMimeMessage()).thenAnswer(call -> new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(mailProperties)));
            var outbox = new EmailOutboxService(jdbc, crypto, mapper, provider, manager);
            org.springframework.test.util.ReflectionTestUtils.setField(outbox, "watchlistEmails", new WatchlistEmailPolicy(jdbc));
            var notification = new AlertNotificationService(provider, true, "sender@example.test", "UTC");
            notification.setOutbox(outbox);
            var user = new org.example.stockwatch247.model.User();
            user.setId(userId); user.setEmail(suffix + "@example.test");
            for (int i = 0; i < 2; i++) assertThat(notification.sendTechnicalOutlookChangeEmail(
                    user, "OUT" + suffix.substring(0, 8), TimeInterval.DAILY, "Changed outlook", "Report", notificationId)).isTrue();
            verify(sender, never()).send(any(SimpleMailMessage.class));
            verify(sender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
            assertThat(jdbc.queryForObject("select count(*) from email_outbox where outlook_notification_id = ?", Long.class, notificationId)).isEqualTo(1);
            doThrow(new MailSendException("Simulated outage")).doNothing().when(sender).send(any(jakarta.mail.internet.MimeMessage.class));
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select email_sent_at is null from technical_outlook_notifications where id = ?", Boolean.class, notificationId)).isTrue();
            jdbc.update("update email_outbox set available_at = current_timestamp where outlook_notification_id = ?", notificationId);
            outbox.deliverPending();
            assertThat(jdbc.queryForObject("select email_sent_at is not null from technical_outlook_notifications where id = ?", Boolean.class, notificationId)).isTrue();
            verify(sender, times(2)).send(any(jakarta.mail.internet.MimeMessage.class));
            jdbc.update("delete from technical_outlook_notifications where id = ?", notificationId);
            assertThat(jdbc.queryForObject("select count(*) from email_outbox where outlook_notification_id = ?", Long.class, notificationId)).isZero();
        });
    }

    @Test void fixtureQuarantinePreservesRealUsersAndAssetHistory() {
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            Long fake = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?, 'test-not-used', 'Watchlist', 'Test') returning id",
                    Long.class, "watchlist-" + UUID.randomUUID() + "@example.test");
            Long real = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?, 'real-password-hash', 'Watchlist', 'Test') returning id",
                    Long.class, "watchlist-" + UUID.randomUUID() + "@example.test");
            Long asset = jdbc.queryForObject("insert into stock_assets(ticker_symbol,company_name,exchange) values (?, 'Named watchlist test', 'TEST') returning id",
                    Long.class, "WL" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            jdbc.update("insert into insider_trade_subscriptions(user_id,stock_asset_id) values (?,?),(?,?)", fake, asset, real, asset);
            var script = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V72__disable_leaked_watchlist_test_follows.sql"));
            script.execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
            assertThat(jdbc.queryForObject("select active from insider_trade_subscriptions where user_id = ?", Boolean.class, fake)).isFalse();
            assertThat(jdbc.queryForObject("select active from insider_trade_subscriptions where user_id = ?", Boolean.class, real)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from stock_assets where id = ?", Long.class, asset)).isEqualTo(1);
        });
    }

    public record Value(double close) { }
}
