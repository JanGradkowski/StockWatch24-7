-- Chart alert triggers join the All signals archive, where a user can delete them like other signals.
-- A deleted trigger keeps its row (so the same candle never triggers again) but leaves every list and count.
alter table chart_alert_events add column deleted_at timestamp without time zone;

create index ix_chart_alert_events_unread on chart_alert_events (alert_id)
    where read_at is null and deleted_at is null;
