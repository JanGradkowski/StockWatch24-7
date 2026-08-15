alter table alert_events
    add column if not exists detection_candle_timestamp bigint,
    add column if not exists detection_close_price double precision;

-- Permit the new states before converting any existing rows. PostgreSQL
-- enforces check constraints during each update inside this migration.
alter table alert_events
    drop constraint if exists ck_alert_event_lifecycle_status;

alter table alert_events
    add constraint ck_alert_event_lifecycle_status
        check (lifecycle_status in (
            'POTENTIAL',
            'DETECTED',
            'REJECTED',
            'CONFIRMED',
            'INVALIDATED',
            'EXPIRED'
        ));

update alert_events
set lifecycle_status = 'REJECTED'
where lifecycle_status = 'UNCONFIRMED';

-- The previous implementation could classify a failed mandatory first candle
-- as INVALIDATED. Under the two-stage model it never became a signal, so keep
-- the gate candle details but classify the candidate as REJECTED.
update alert_events
set lifecycle_status = 'REJECTED'
where lifecycle_status = 'INVALIDATED'
  and resolution_candle_offset = 1
  and pattern in ('HAMMER', 'INVERTED_HAMMER', 'HANGING_MAN', 'SHOOTING_STAR');

update alert_events
set lifecycle_status = 'POTENTIAL'
where lifecycle_status = 'DETECTED'
  and pattern in ('HAMMER', 'INVERTED_HAMMER', 'HANGING_MAN', 'SHOOTING_STAR');

update alert_events
set detection_candle_timestamp = resolution_candle_timestamp,
    detection_close_price = resolution_close_price,
    lifecycle_anchor_candle_timestamp = resolution_candle_timestamp,
    lifecycle_status = 'DETECTED',
    confirmation_trigger_price = case when trade_signal = 'BUY' then pattern_high else pattern_low end,
    confirmation_window_candles = 10,
    resolution_candle_timestamp = null,
    resolution_candle_offset = null,
    resolution_close_price = null,
    lifecycle_resolution_reason = null
where lifecycle_status = 'CONFIRMED'
  and resolution_candle_offset = 1
  and pattern in ('HAMMER', 'INVERTED_HAMMER', 'HANGING_MAN', 'SHOOTING_STAR');

drop index if exists ix_alert_events_pending_lifecycle;

create index if not exists ix_alert_events_pending_lifecycle
    on alert_events (alert_rule_id, signal_candle_timestamp)
    where lifecycle_status in ('POTENTIAL', 'DETECTED')
      and confirmation_window_candles is not null;
