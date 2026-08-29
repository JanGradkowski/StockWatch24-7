alter table alert_events
    add column if not exists elliott_development_key varchar(192),
    add column if not exists elliott_developing boolean not null default false,
    add column if not exists elliott_correction_type varchar(64),
    add column if not exists elliott_forecast_label varchar(128),
    add column if not exists elliott_stage_updated_at timestamp,
    add column if not exists elliott_structure_snapshot text,
    add column if not exists elliott_transition_history text;

alter table alert_events
    drop constraint if exists ck_alert_event_elliott_signal_stage;

alter table alert_events
    add constraint ck_alert_event_elliott_signal_stage
        check (elliott_signal_stage is null or elliott_signal_stage in (
            'WAVE_II_END', 'WAVE_III_END', 'WAVE_IV_END',
            'WAVE_V_END', 'CORRECTION_END'
        ));

create index if not exists ix_alert_events_elliott_development
    on alert_events (alert_rule_id, elliott_development_key)
    where elliott_development_key is not null and deleted_at is null;

create index if not exists ix_alert_events_elliott_stage_updated
    on alert_events (elliott_stage_updated_at desc)
    where elliott_development_key is not null and deleted_at is null;
