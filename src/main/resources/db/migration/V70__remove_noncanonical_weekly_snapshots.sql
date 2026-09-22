-- Yahoo can append live day-sized quotes to 1wk responses. They must be removed,
-- not moved to Monday: doing so would overwrite real weekly OHLC with daily OHLC.
-- Candle-delete triggers advance cache generations; saved signals remain untouched.
with removed as (
    delete from candles
    where time_interval = '1wk'
      and timestamp <> extract(epoch from (
          date_trunc('week', to_timestamp(timestamp) at time zone 'UTC') at time zone 'UTC'
      ))::bigint
    returning symbol
)
delete from market_data_sync_state
where time_interval = '1wk' and symbol in (select symbol from removed);

update market_data_history_state state
set oldest_timestamp = actual.oldest_timestamp,
    updated_at = current_timestamp
from (
    select symbol, min(timestamp) as oldest_timestamp
    from candles where time_interval = '1wk' group by symbol
) actual
where state.symbol = actual.symbol and state.time_interval = '1wk'
  and state.oldest_timestamp is distinct from actual.oldest_timestamp;

delete from market_data_history_state state
where state.time_interval = '1wk'
  and not exists (
      select 1 from candles where symbol = state.symbol and time_interval = '1wk'
  );
