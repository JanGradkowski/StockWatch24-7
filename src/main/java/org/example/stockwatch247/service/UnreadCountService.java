package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.service.congress.CongressionalActivityService;
import org.example.stockwatch247.service.insider.InsiderActivityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * True unread totals for the dashboard and the sidebar badges. The dashboard lists only the newest few
 * notifications; these counts say how many are waiting in total, using the same rules as the archives'
 * Unread filter.
 */
@Service
public class UnreadCountService {
    private final JdbcTemplate db;
    private CongressionalActivityService congressional;
    private InsiderActivityService insider;

    public UnreadCountService(JdbcTemplate db) {
        this.db = db;
    }

    @Autowired(required = false)
    void configureActivity(CongressionalActivityService congressional, InsiderActivityService insider) {
        this.congressional = congressional;
        this.insider = insider;
    }

    /**
     * @param technical pattern signals in All signals
     * @param outlook   automated outlook changes
     * @param chart     chart alert triggers
     * @param custom    custom alert triggers
     * @param activity  insider and congressional filings
     */
    public record Counts(long technical, long outlook, long chart, long custom, long activity) {
        public static final Counts NONE = new Counts(0, 0, 0, 0, 0);

        /** Everything the Technical signals page lists: pattern signals and chart alert triggers. */
        public long signals() { return technical + chart; }

        /** The dashboard's Latest signals panel: pattern signals, outlook changes and chart alerts. */
        public long latestSignals() { return technical + outlook + chart; }
    }

    @Transactional(readOnly = true)
    public Counts counts(User user) {
        if (user == null || user.getId() == null) return Counts.NONE;
        long id = user.getId();
        long technical = count("""
                select count(*) from alert_events e join alert_rules r on r.id = e.alert_rule_id
                where r.user_id = ? and e.read_at is null and e.deleted_at is null
                """, id);
        long outlook = count("""
                select count(*) from technical_outlook_notifications n
                join technical_outlook_subscriptions s on s.id = n.subscription_id
                where s.user_id = ? and n.read_at is null
                """, id);
        long chart = count("""
                select count(*) from chart_alert_events e join chart_alerts a on a.id = e.alert_id
                where a.user_id = ? and e.read_at is null and e.deleted_at is null
                """, id);
        long custom = count("""
                select count(*) from custom_alert_events e join custom_alerts a on a.id = e.alert_id
                where a.user_id = ? and e.read_at is null and e.deleted_at is null
                """, id);
        long activity = (congressional == null ? 0 : congressional.unreadActivityCount(user))
                + (insider == null ? 0 : insider.unreadActivityCount(user));
        return new Counts(technical, outlook, chart, custom, activity);
    }

    private long count(String sql, long userId) {
        Long value = db.queryForObject(sql, Long.class, userId);
        return value == null ? 0 : value;
    }
}
