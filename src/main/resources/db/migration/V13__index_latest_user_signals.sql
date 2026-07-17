create index if not exists ix_alert_events_rule_sent_at
    on alert_events (alert_rule_id, sent_at desc, id desc);
