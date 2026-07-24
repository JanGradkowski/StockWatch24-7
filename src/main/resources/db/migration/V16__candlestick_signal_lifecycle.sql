alter table alert_events
    add column if not exists lifecycle_status varchar(16) not null default 'DETECTED',
    add column if not exists pattern_high double precision,
    add column if not exists pattern_low double precision,
    add column if not exists confirmation_trigger_price double precision,
    add column if not exists invalidation_price double precision,
    add column if not exists confirmation_window_candles integer,
    add column if not exists resolution_candle_timestamp bigint,
    add column if not exists resolution_candle_offset integer,
    add column if not exists resolution_close_price double precision,
    add column if not exists lifecycle_updated_at timestamp without time zone,
    add column if not exists follow_up_sent_at timestamp without time zone;

alter table alert_events
    drop constraint if exists ck_alert_event_lifecycle_status;

alter table alert_events
    add constraint ck_alert_event_lifecycle_status
        check (lifecycle_status in ('DETECTED', 'CONFIRMED', 'INVALIDATED', 'EXPIRED'));

alter table alert_events
    drop constraint if exists ck_alert_event_confirmation_window;

alter table alert_events
    add constraint ck_alert_event_confirmation_window
        check (confirmation_window_candles is null
               or confirmation_window_candles between 1 and 20);

create index if not exists ix_alert_events_pending_lifecycle
    on alert_events (alert_rule_id, signal_candle_timestamp)
    where lifecycle_status = 'DETECTED'
      and confirmation_window_candles is not null;
