package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AnalysisCheckpointStore {
    @org.springframework.beans.factory.annotation.Autowired private InfrastructureRecovery recovery = new InfrastructureRecovery();
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AnalysisCheckpointStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    <T> T read(String key, Class<T> type) {
        var rows = jdbc.query("select payload from analysis_checkpoints where cache_key=?",
                (rs, row) -> rs.getString(1), key);
        return rows.isEmpty() ? null : mapper.readValue(rows.getFirst(), type);
    }
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void write(String key, Object value, long completedNodes) {
        JobLeaseGuard.checkActive();
        jdbc.update("""
            insert into analysis_checkpoints(cache_key,payload,completed_nodes) values(?,?,?)
            on conflict(cache_key) do update set payload=excluded.payload,completed_nodes=excluded.completed_nodes,updated_at=current_timestamp
            where analysis_checkpoints.completed_nodes <= excluded.completed_nodes
            """, key, mapper.writeValueAsString(value), completedNodes);
    }
    @Scheduled(cron = "0 20 3 * * *")
    public void cleanup() {
        recovery.run("analysis-checkpoint-cleanup", () -> {
        // Age and total storage are bounded. Eviction affects speed, never recognition semantics.
        jdbc.update("delete from analysis_checkpoints where updated_at < current_timestamp - interval '7 days'");
        jdbc.update("delete from analysis_checkpoints where cache_key in (select cache_key from analysis_checkpoints order by updated_at desc offset 20000)");
        });
    }
}
