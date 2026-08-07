alter table alert_events
    add column if not exists elliott_cycle_key varchar(192),
    add column if not exists elliott_signal_stage varchar(24),
    add column if not exists elliott_endpoint_timestamp bigint,
    add column if not exists elliott_endpoint_price double precision,
    add column if not exists elliott_terminal_anchor_timestamp bigint,
    add column if not exists lifecycle_anchor_candle_timestamp bigint,
    add column if not exists lifecycle_resolution_reason varchar(255);

alter table alert_events
    drop constraint if exists ck_alert_event_elliott_signal_stage;

alter table alert_events
    add constraint ck_alert_event_elliott_signal_stage
        check (elliott_signal_stage is null
               or elliott_signal_stage in ('WAVE_V_END', 'CORRECTION_END'));

create index if not exists ix_alert_events_elliott_cycle_stage
    on alert_events (alert_rule_id, elliott_cycle_key, elliott_signal_stage)
    where elliott_cycle_key is not null;
