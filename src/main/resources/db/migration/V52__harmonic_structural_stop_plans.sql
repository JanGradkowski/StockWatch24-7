alter table alert_events
    add column if not exists harmonic_stop_basis varchar(255),
    add column if not exists harmonic_stop_formula varchar(255),
    add column if not exists harmonic_stop_buffer_amount double precision,
    add column if not exists harmonic_stop_buffer_percent double precision,
    add column if not exists harmonic_stop_distance_percent double precision,
    add column if not exists harmonic_stop_status varchar(32),
    add column if not exists harmonic_stop_resolution_timestamp bigint,
    add column if not exists harmonic_stop_resolution_price double precision,
    add column if not exists harmonic_stop_resolution_reason varchar(255);

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
        or (
            trade_plan_version = 'HARMONIC_STOP_V1'
            and trade_entry_price is not null
            and trade_entry_price > 0.0
            and structural_stop_price is not null
            and structural_stop_price > 0.0
            and stop_loss_price is not null
            and stop_loss_price > 0.0
            and stop_loss_mode = 'STRUCTURAL_BUFFER'
            and stop_loss_value_percent = 0.5
            and harmonic_stop_buffer_amount > 0.0
            and harmonic_stop_buffer_percent = 0.5
            and harmonic_stop_distance_percent > 0.0
            and harmonic_stop_basis is not null
            and harmonic_stop_formula is not null
            and harmonic_stop_status in ('ACTIVE', 'STOPPED')
            and profit_target_price is null
            and reward_risk_ratio is null
        )
    );

alter table alert_events
    add constraint ck_alert_event_harmonic_stop_resolution check (
        harmonic_stop_status is null
        or harmonic_stop_status = 'ACTIVE'
            and harmonic_stop_resolution_timestamp is null
            and harmonic_stop_resolution_price is null
        or harmonic_stop_status = 'STOPPED'
            and harmonic_stop_resolution_timestamp is not null
            and harmonic_stop_resolution_price is not null
            and harmonic_stop_resolution_reason is not null
    );

create index if not exists ix_alert_events_harmonic_stop_active
    on alert_events (trade_plan_version, harmonic_stop_status, signal_candle_timestamp)
    where trade_plan_version = 'HARMONIC_STOP_V1'
      and harmonic_stop_status = 'ACTIVE'
      and deleted_at is null;
