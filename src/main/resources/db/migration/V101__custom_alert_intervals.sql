-- A custom alert can be checked after the close of several intervals (daily and weekly, say) instead of one. The
-- alert keeps one rule; each interval has its own state per stock, and each trigger says which interval's candle
-- it came from (a weekly and a daily candle can share a timestamp, so the interval is part of a trigger's key).

alter table custom_alerts add column intervals varchar(16)[];
update custom_alerts set intervals = array[interval];
alter table custom_alerts alter column intervals set not null;
alter table custom_alerts add constraint custom_alerts_intervals_check
    check (cardinality(intervals) between 1 and 3 and intervals <@ array['DAILY', 'WEEKLY', 'MONTHLY']::varchar(16)[]);
drop index if exists ix_custom_alerts_active;
alter table custom_alerts drop column interval;
create index ix_custom_alerts_active on custom_alerts using gin (intervals) where active;

alter table custom_alert_states add column interval varchar(16);
update custom_alert_states s set interval = a.intervals[1] from custom_alerts a where a.id = s.alert_id;
alter table custom_alert_states alter column interval set not null;
alter table custom_alert_states add constraint custom_alert_states_interval_check check (interval in ('DAILY', 'WEEKLY', 'MONTHLY'));
alter table custom_alert_states drop constraint custom_alert_states_pkey;
alter table custom_alert_states add primary key (alert_id, stock_asset_id, interval);

alter table custom_alert_events add column interval varchar(16);
update custom_alert_events e set interval = a.intervals[1] from custom_alerts a where a.id = e.alert_id;
alter table custom_alert_events alter column interval set not null;
alter table custom_alert_events add constraint custom_alert_events_interval_check check (interval in ('DAILY', 'WEEKLY', 'MONTHLY'));
alter table custom_alert_events drop constraint uq_custom_alert_event;
alter table custom_alert_events add constraint uq_custom_alert_event unique (alert_id, stock_asset_id, interval, candle_timestamp);
