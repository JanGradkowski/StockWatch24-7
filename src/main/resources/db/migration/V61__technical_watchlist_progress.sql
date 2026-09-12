alter table technical_outlook_subscriptions
    add column current_classification varchar(64),
    add column current_score double precision,
    add column current_price double precision,
    add column last_change_candle_timestamp bigint,
    add column last_change_previous_classification varchar(64),
    add column last_change_price double precision,
    add column last_change_score double precision,
    add column tracking_started_at timestamp without time zone;

-- Preserve the latest evaluated state without recalculating every followed ticker.
update technical_outlook_subscriptions s
set current_classification = s.baseline_snapshot::jsonb #>> '{headlineScore,classification}',
    current_score = (s.baseline_snapshot::jsonb #>> '{headlineScore,normalizedScore}')::double precision,
    current_price = (
        select (c ->> 'close')::double precision
        from jsonb_array_elements(s.baseline_snapshot::jsonb -> 'candles') c
        where (c ->> 'timestamp')::bigint = s.last_candle_timestamp limit 1
    )
where s.baseline_snapshot is not null;

-- Older subscriptions did not retain a re-follow/settings-reset timestamp.
-- Only recover a change at the current baseline candle, whose association is certain.
-- Other legacy baselines start with unknown change history, never an invented return.
update technical_outlook_subscriptions s
set last_change_candle_timestamp = n.current_candle_timestamp,
    last_change_previous_classification = n.previous_classification,
    last_change_price = s.current_price,
    last_change_score = n.current_score
from technical_outlook_notifications n
where n.subscription_id = s.id
  and n.current_candle_timestamp = s.last_candle_timestamp
  and n.current_classification = s.current_classification
  and n.created_at >= s.updated_at;

create index ix_technical_outlook_watchlist
    on technical_outlook_subscriptions (user_id, stock_asset_id) where is_active = true;
create index ix_technical_outlook_notifications_subscription_latest
    on technical_outlook_notifications (subscription_id, current_candle_timestamp desc, id desc);
