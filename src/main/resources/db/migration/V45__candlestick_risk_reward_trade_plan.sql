alter table alert_events
    add column if not exists trade_entry_price double precision,
    add column if not exists stop_loss_price double precision,
    add column if not exists profit_target_price double precision,
    add column if not exists reward_risk_ratio double precision,
    add column if not exists trade_plan_version varchar(32);

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
    );
