create table if not exists elliott_projection_sets (
    id bigserial primary key,
    alert_event_id bigint not null references alert_events(id) on delete cascade,
    elliott_stage varchar(24) not null,
    stage_revision integer not null,
    development_key varchar(192),
    source_timestamp bigint not null,
    source_price double precision not null,
    status varchar(24) not null,
    last_evaluated_timestamp bigint,
    evaluated_candle_count integer not null default 0,
    resolution_timestamp bigint,
    resolution_reason varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_elliott_projection_set_revision
        unique (alert_event_id, elliott_stage, stage_revision),
    constraint ck_elliott_projection_set_stage check (elliott_stage in (
        'WAVE_II_END', 'WAVE_III_END', 'WAVE_IV_END', 'WAVE_V_END', 'CORRECTION_END')),
    constraint ck_elliott_projection_set_status check (status in (
        'ACTIVE', 'COMPLETED', 'SUPERSEDED', 'INVALIDATED')),
    constraint ck_elliott_projection_set_source check (
        source_price > 0 and evaluated_candle_count >= 0)
);

create table if not exists elliott_projection_scenarios (
    id bigserial primary key,
    projection_set_id bigint not null references elliott_projection_sets(id) on delete cascade,
    scenario_key varchar(48) not null,
    label varchar(96) not null,
    description varchar(255) not null,
    display_rank integer not null,
    expected_move varchar(8) not null,
    status varchar(24) not null,
    initial_confidence integer not null,
    current_confidence integer not null,
    projected_path text not null,
    target_zone_low double precision not null,
    target_zone_high double precision not null,
    target_midpoint double precision not null,
    target_basis varchar(255) not null,
    minimum_candles integer not null,
    maximum_candles integer not null,
    hard_invalidation_price double precision,
    hard_invalidation_side varchar(8),
    scenario_invalidation_price double precision,
    scenario_invalidation_side varchar(8),
    evidence text not null,
    evaluation_note varchar(255),
    resolution_timestamp bigint,
    resolution_reason varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_elliott_projection_scenario_key unique (projection_set_id, scenario_key),
    constraint ck_elliott_projection_scenario_move check (expected_move in ('BUY', 'SELL')),
    constraint ck_elliott_projection_scenario_status check (status in (
        'ACTIVE', 'DISFAVORED', 'INVALIDATED', 'COMPLETED', 'SUPERSEDED')),
    constraint ck_elliott_projection_scenario_values check (
        display_rank between 1 and 3
        and initial_confidence between 0 and 100
        and current_confidence between 0 and 100
        and target_zone_low > 0 and target_zone_high >= target_zone_low
        and target_midpoint between target_zone_low and target_zone_high
        and minimum_candles > 0 and maximum_candles >= minimum_candles)
);

create index if not exists ix_elliott_projection_sets_open
    on elliott_projection_sets (status, source_timestamp)
    where status = 'ACTIVE';

create index if not exists ix_elliott_projection_sets_event
    on elliott_projection_sets (alert_event_id, source_timestamp desc);

create index if not exists ix_elliott_projection_scenarios_set
    on elliott_projection_scenarios (projection_set_id, display_rank);
