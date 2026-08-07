alter table alert_events
    add column if not exists read_at timestamp without time zone;

create index if not exists ix_alert_events_unread_by_rule
    on alert_events (alert_rule_id)
    where read_at is null;
