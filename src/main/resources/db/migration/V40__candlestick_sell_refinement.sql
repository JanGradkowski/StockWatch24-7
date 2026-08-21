alter table alert_events
    add column if not exists sell_signal_stage varchar(32),
    add column if not exists sell_confirmation_atr double precision,
    add column if not exists sell_confirmation_buffer_atr double precision,
    add column if not exists sell_retest_level_price double precision,
    add column if not exists sell_breakdown_candle_timestamp bigint,
    add column if not exists sell_breakdown_close_price double precision,
    add column if not exists sell_breakdown_low_price double precision,
    add column if not exists sell_retest_candle_timestamp bigint,
    add column if not exists sell_retest_close_price double precision,
    add column if not exists sell_continuation_candle_timestamp bigint,
    add column if not exists sell_continuation_close_price double precision,
    add column if not exists sell_stage_resolution_reason varchar(255),
    add column if not exists sell_stage_updated_at timestamp without time zone,
    add column if not exists sell_stage_email_sent_at timestamp without time zone;

alter table alert_events
    drop constraint if exists ck_alert_event_sell_signal_stage;

alter table alert_events
    add constraint ck_alert_event_sell_signal_stage
        check (sell_signal_stage is null or sell_signal_stage in (
            'SETUP_DETECTED',
            'BREAKDOWN_CONFIRMED',
            'RETESTING',
            'CONTINUATION_CONFIRMED',
            'BREAKDOWN_ONLY',
            'RETEST_RECLAIMED',
            'RETEST_UNRESOLVED',
            'SETUP_REJECTED',
            'SETUP_INVALIDATED',
            'SETUP_EXPIRED'
        ));

create index if not exists ix_alert_events_pending_sell_refinement
    on alert_events (alert_rule_id, signal_candle_timestamp)
    where sell_signal_stage in ('BREAKDOWN_CONFIRMED', 'RETESTING')
      and deleted_at is null;
