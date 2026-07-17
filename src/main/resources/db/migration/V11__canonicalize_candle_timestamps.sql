-- Twelve Data may timestamp end-of-day candles at the exchange-session time,
-- while Yahoo is normalized to midnight UTC. Keep the most recently persisted
-- value for each logical candle, then move every higher-interval candle onto a
-- single canonical UTC boundary.
with normalized as (
    select id,
           row_number() over (
               partition by symbol, time_interval,
                   case time_interval
                       when '1d' then extract(epoch from (
                           date_trunc('day', to_timestamp(timestamp) at time zone 'UTC') at time zone 'UTC'
                       ))::bigint
                       when '1wk' then extract(epoch from (
                           date_trunc('week', to_timestamp(timestamp) at time zone 'UTC') at time zone 'UTC'
                       ))::bigint
                       when '1mo' then extract(epoch from (
                           date_trunc('month', to_timestamp(timestamp) at time zone 'UTC') at time zone 'UTC'
                       ))::bigint
                   end
               order by id desc
           ) as duplicate_rank
    from candles
    where time_interval in ('1d', '1wk', '1mo')
)
delete from candles candle
using normalized
where candle.id = normalized.id
  and normalized.duplicate_rank > 1;

update candles
set timestamp = case time_interval
    when '1d' then extract(epoch from (
        date_trunc('day', to_timestamp(timestamp) at time zone 'UTC') at time zone 'UTC'
    ))::bigint
    when '1wk' then extract(epoch from (
        date_trunc('week', to_timestamp(timestamp) at time zone 'UTC') at time zone 'UTC'
    ))::bigint
    when '1mo' then extract(epoch from (
        date_trunc('month', to_timestamp(timestamp) at time zone 'UTC') at time zone 'UTC'
    ))::bigint
    else timestamp
end
where time_interval in ('1d', '1wk', '1mo');

update market_data_history_state state
set oldest_timestamp = actual.oldest_timestamp,
    updated_at = current_timestamp
from (
    select symbol, time_interval, min(timestamp) as oldest_timestamp
    from candles
    group by symbol, time_interval
) actual
where state.symbol = actual.symbol
  and state.time_interval = actual.time_interval;
