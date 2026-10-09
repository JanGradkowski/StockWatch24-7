-- Trades now enter at the open of the candle after the signal instead of the signal close,
-- which was never tradable for alerts sent after the session. A plan is stored provisionally at
-- the reference close, then filled at the next open, or marked not filled when that open is
-- already beyond the stop or the target. Earlier plan versions keep their original semantics.
alter table alert_events
    add column trade_fill_status varchar(16),
    add column trade_entry_timestamp bigint;
alter table alert_events add constraint ck_alert_event_trade_fill_status check (
    trade_fill_status is null or trade_fill_status in ('AWAITING_FILL', 'FILLED', 'NOT_FILLED'));

alter table elliott_stage_trade_plans
    add column fill_status varchar(16);
alter table elliott_stage_trade_plans add constraint ck_elliott_stage_trade_plan_fill check (
    fill_status is null or fill_status in ('AWAITING_FILL', 'FILLED', 'NOT_FILLED'));
alter table elliott_stage_trade_plans drop constraint ck_elliott_stage_trade_plan_status;
alter table elliott_stage_trade_plans add constraint ck_elliott_stage_trade_plan_status check (status in (
    'ACTIVE', 'PROJECTION_ONLY', 'TARGET_REACHED', 'STOPPED', 'TIME_STOPPED',
    'STRUCTURE_INVALIDATED', 'STAGE_COMPLETED', 'REVISED', 'NOT_FILLED'));

alter table alert_events drop constraint if exists ck_alert_event_risk_reward_plan;
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
            and harmonic_stop_status in ('ACTIVE', 'STOPPED', 'TIME_STOPPED')
            and profit_target_price is null
            and reward_risk_ratio is null
        )
        or (trade_plan_version = 'CANDLE_RR_V4' and stop_loss_price > 0
            and structural_stop_price > 0 and reward_risk_ratio > 0)
        or (trade_plan_version in ('ELLIOTT_FIB_RR_V3', 'HARMONIC_TRADE_V2')
            and trade_entry_price > 0 and stop_loss_price > 0 and profit_target_price > 0
            and trade_actionable is not null and trade_qualification is not null
            and trade_horizon_candles > 0)
        or (trade_plan_version = 'CANDLE_RR_V5' and stop_loss_price > 0
            and structural_stop_price > 0 and reward_risk_ratio > 0
            and trade_fill_status is not null
            and (trade_fill_status = 'AWAITING_FILL') = (trade_entry_timestamp is null))
        or (trade_plan_version in ('ELLIOTT_FIB_RR_V4', 'HARMONIC_TRADE_V3')
            and trade_entry_price > 0 and stop_loss_price > 0 and profit_target_price > 0
            and trade_actionable is not null and trade_qualification is not null
            and trade_horizon_candles > 0
            and trade_fill_status is not null
            and (trade_fill_status = 'AWAITING_FILL') = (trade_entry_timestamp is null))
    );

alter table alert_events drop constraint if exists ck_alert_event_harmonic_stop_resolution;
alter table alert_events
    add constraint ck_alert_event_harmonic_stop_resolution check (
        harmonic_stop_status is null
        or harmonic_stop_status in ('ACTIVE', 'PROJECTION_ONLY')
            and harmonic_stop_resolution_timestamp is null
            and harmonic_stop_resolution_price is null
        or harmonic_stop_status in ('STOPPED', 'TIME_STOPPED', 'TARGET_REACHED', 'NOT_FILLED')
            and harmonic_stop_resolution_timestamp is not null
            and harmonic_stop_resolution_price is not null
            and harmonic_stop_resolution_reason is not null
    );

create index ix_harmonic_trade_v3_plans_active on alert_events (signal_candle_timestamp)
    where trade_plan_version = 'HARMONIC_TRADE_V3' and harmonic_stop_status = 'ACTIVE';
