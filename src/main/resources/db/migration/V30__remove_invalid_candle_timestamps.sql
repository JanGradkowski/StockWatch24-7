-- Yahoo chart responses can contain a trailing quote with a null timestamp.
-- Older parsing converted that null to Unix epoch zero; weekly/monthly
-- canonicalization then shifted it into December 1969. These are provider
-- sentinel rows, not historical candles, and cannot be used as API cursors.
delete from market_data_sync_state sync_state
using (
    select distinct symbol, time_interval
    from candles
    where timestamp <= 0
) invalid
where sync_state.symbol = invalid.symbol
  and sync_state.time_interval = invalid.time_interval;

delete from market_data_history_state history_state
using (
    select distinct symbol, time_interval
    from candles
    where timestamp <= 0
) invalid
where history_state.symbol = invalid.symbol
  and history_state.time_interval = invalid.time_interval;

delete from candles
where timestamp <= 0;

alter table candles
    add constraint ck_candles_positive_timestamp check (timestamp > 0);
