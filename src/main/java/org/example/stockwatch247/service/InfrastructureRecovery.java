package org.example.stockwatch247.service;

import org.springframework.stereotype.Component;

@Component
public class InfrastructureRecovery {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InfrastructureRecovery.class);
    private final RetryBackoff database = new RetryBackoff();
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    public boolean ready() { return database.ready(); }
    public void succeeded() { database.succeeded(); }
    public void run(String queue, Runnable operation) {
        if (!ready()) return;
        try { operation.run(); }
        catch (RuntimeException failure) { if (!failed(failure, queue)) throw failure; }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void probeRecovery() {
        if (!database.recovering() || !database.ready()) return;
        try {
            jdbc.queryForObject("select 1", Integer.class);
            database.succeeded();
            log.info("Database connection recovered; background queues may resume.");
        } catch (RuntimeException failure) { if (!failed(failure, "recovery-probe")) throw failure; }
    }
    public boolean failed(Throwable failure, String queue) {
        if (!isDatabaseUnavailable(failure)) return false;
        log.warn("Database work deferred: queue={} retryInSeconds={} failureType={}",
                queue, database.failed(), failure.getClass().getSimpleName());
        return true;
    }
    public static boolean isDatabaseUnavailable(Throwable failure) {
        for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause()) {
            if (failure instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08")
                    || java.util.Set.of("57P01", "57P02", "57P03").contains(sql.getSQLState()))) return true;
            if (failure instanceof org.springframework.dao.DataAccessResourceFailureException
                    || failure instanceof org.springframework.dao.TransientDataAccessResourceException
                    || failure instanceof org.springframework.dao.RecoverableDataAccessException
                    || failure instanceof java.sql.SQLTransientConnectionException
                    || failure instanceof java.sql.SQLNonTransientConnectionException) return true;
        }
        return false;
    }
}
