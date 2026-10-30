-- An optional stop loss and target for a demo trade. An open trade with either closes by itself on the
-- first completed candle of its interval that reaches a level; exit_reason records why a trade closed.
-- source_alert_event_id links a trade opened from a signal's own plan to that signal.
alter table virtual_trades
    add column stop_price numeric(24, 8),
    add column target_price numeric(24, 8),
    add column exit_reason varchar(16),
    add column source_alert_event_id bigint references alert_events(id) on delete set null,
    add constraint ck_virtual_trade_plan_prices check ((stop_price is null or stop_price > 0) and (target_price is null or target_price > 0)),
    add constraint ck_virtual_trade_exit_reason check (exit_reason is null or exit_reason in ('MANUAL', 'STOP', 'TARGET'));

create index virtual_trades_open_plans on virtual_trades(id)
    where status = 'TRACKING' and deleted_at is null and (stop_price is not null or target_price is not null);
