alter table alert_events
    add column if not exists pre_circuit_breaker_stop_price double precision,
    add column if not exists atr_circuit_breaker_enabled boolean,
    add column if not exists atr_circuit_breaker_applied boolean,
    add column if not exists atr_circuit_breaker_value double precision,
    add column if not exists atr_circuit_breaker_period integer,
    add column if not exists atr_circuit_breaker_multiplier double precision,
    add column if not exists atr_circuit_breaker_threshold_percent double precision;

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
    );
