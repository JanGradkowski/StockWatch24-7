drop index if exists ix_alert_events_pending_sell_refinement;

alter table alert_events
    drop constraint if exists ck_alert_event_sell_signal_stage,
    drop column if exists sell_signal_stage,
    drop column if exists sell_confirmation_atr,
    drop column if exists sell_confirmation_buffer_atr,
    drop column if exists sell_retest_level_price,
    drop column if exists sell_breakdown_candle_timestamp,
    drop column if exists sell_breakdown_close_price,
    drop column if exists sell_breakdown_low_price,
    drop column if exists sell_retest_candle_timestamp,
    drop column if exists sell_retest_close_price,
    drop column if exists sell_continuation_candle_timestamp,
    drop column if exists sell_continuation_close_price,
    drop column if exists sell_stage_resolution_reason,
    drop column if exists sell_stage_updated_at,
    drop column if exists sell_stage_email_sent_at;
