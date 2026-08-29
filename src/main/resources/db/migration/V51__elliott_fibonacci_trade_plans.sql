alter table alert_events
    add column if not exists elliott_target_mid_price double precision,
    add column if not exists elliott_target_zone_low double precision,
    add column if not exists elliott_target_zone_high double precision,
    add column if not exists elliott_target_basis varchar(255),
    add column if not exists elliott_required_reward_risk_ratio double precision,
    add column if not exists elliott_trade_actionable boolean,
    add column if not exists elliott_trade_plan_status varchar(32),
    add column if not exists elliott_trade_resolution_timestamp bigint,
    add column if not exists elliott_trade_resolution_close double precision,
    add column if not exists elliott_trade_resolution_reason varchar(255);

alter table alert_events
    drop constraint if exists ck_alert_event_risk_reward_plan;

alter table alert_events
    add constraint ck_alert_event_risk_reward_plan check (
        trade_plan_version is null
        or (
            trade_plan_version = 'CANDLE_RR_V1'
            and stop_loss_price is not null
            and reward_risk_ratio in (2.0, 3.0, 4.0)
            and confirmation_window_candles = 8
        )
        or (
            trade_plan_version = 'CANDLE_RR_V2'
            and stop_loss_price is not null
            and structural_stop_price is not null
            and stop_loss_mode in ('STRUCTURAL_BUFFER', 'FIXED_ENTRY_PERCENT')
            and stop_loss_value_percent between 0.0 and 50.0
            and reward_risk_ratio between 0.1 and 20.0
            and confirmation_window_candles = 8
        )
        or (
            trade_plan_version = 'CANDLE_RR_V3'
            and stop_loss_price is not null
            and structural_stop_price is not null
            and pre_circuit_breaker_stop_price is not null
            and stop_loss_mode in ('STRUCTURAL_BUFFER', 'FIXED_ENTRY_PERCENT')
            and stop_loss_value_percent between 0.0 and 50.0
            and reward_risk_ratio between 0.1 and 20.0
            and confirmation_window_candles = 8
            and atr_circuit_breaker_enabled is not null
            and atr_circuit_breaker_applied is not null
            and atr_circuit_breaker_period between 2 and 500
            and atr_circuit_breaker_multiplier between 0.1 and 10.0
            and atr_circuit_breaker_threshold_percent between 1.0 and 95.0
            and (atr_circuit_breaker_value is null or atr_circuit_breaker_value > 0.0)
        )
        or (
            trade_plan_version in ('ELLIOTT_NEXT_WAVE_V1', 'ELLIOTT_FIB_RR_V2')
            and trade_entry_price is not null
            and stop_loss_price is not null
            and structural_stop_price is not null
            and profit_target_price is not null
            and reward_risk_ratio > 0.0
        )
    );

create table if not exists elliott_stage_trade_plans (
    id bigserial primary key,
    alert_event_id bigint not null references alert_events(id) on delete cascade,
    elliott_stage varchar(24) not null,
    stage_revision integer not null,
    expected_move varchar(8) not null,
    status varchar(32) not null,
    plan_version varchar(32) not null,
    entry_timestamp bigint not null,
    entry_price double precision not null,
    structural_stop_price double precision not null,
    stop_loss_price double precision not null,
    stop_buffer double precision not null,
    hard_invalidation_price double precision,
    hard_invalidation_side varchar(8),
    target_midpoint double precision not null,
    target_zone_low double precision not null,
    target_zone_high double precision not null,
    target_trigger_price double precision not null,
    target_basis varchar(255) not null,
    fibonacci_ratio double precision,
    target_zone_percent double precision not null,
    required_reward_risk_ratio double precision not null,
    actual_reward_risk_ratio double precision not null,
    actionable boolean not null,
    qualification varchar(255) not null,
    resolution_timestamp bigint,
    resolution_close_price double precision,
    resolution_reason varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_elliott_stage_trade_plan_revision
        unique (alert_event_id, elliott_stage, stage_revision),
    constraint ck_elliott_stage_trade_plan_stage check (elliott_stage in (
        'WAVE_II_END', 'WAVE_III_END', 'WAVE_IV_END', 'WAVE_V_END', 'CORRECTION_END')),
    constraint ck_elliott_stage_trade_plan_move check (expected_move in ('BUY', 'SELL')),
    constraint ck_elliott_stage_trade_plan_status check (status in (
        'ACTIVE', 'PROJECTION_ONLY', 'TARGET_REACHED', 'STOPPED',
        'STRUCTURE_INVALIDATED', 'STAGE_COMPLETED', 'REVISED')),
    constraint ck_elliott_stage_trade_plan_prices check (
        entry_price > 0 and structural_stop_price > 0 and stop_loss_price > 0
        and stop_buffer > 0 and target_midpoint > 0 and target_zone_low > 0
        and target_zone_high >= target_zone_low and target_trigger_price > 0
        and target_zone_percent > 0 and required_reward_risk_ratio > 0
        and actual_reward_risk_ratio > 0)
);

create index if not exists ix_elliott_stage_trade_plans_open
    on elliott_stage_trade_plans (status, entry_timestamp)
    where status in ('ACTIVE', 'PROJECTION_ONLY');

create index if not exists ix_elliott_stage_trade_plans_event
    on elliott_stage_trade_plans (alert_event_id, stage_revision);
