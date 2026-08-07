-- Yahoo Finance may append a live, day-sized quote to a response declared as
-- monthly data. Older import code preserved that quote's calendar-day timestamp,
-- producing multiple fake 1mo candles inside one month. Genuine cached monthly
-- candles are normalized to UTC midnight on the first day of their month.
delete from candles
where time_interval = '1mo'
  and extract(day from (to_timestamp(timestamp) at time zone 'UTC')) <> 1;

-- Allow the cleaned monthly cache to be refreshed immediately after deployment.
delete from market_data_sync_state
where time_interval = '1mo';
