-- Yahoo Finance could return monthly bars for interval=1wk&range=max. Those bars
-- were stored under the requested 1wk label, so existing weekly cache rows cannot
-- be trusted or reliably separated from genuine weekly rows. Candle prices are a
-- provider-derived cache and will be repopulated by the normal synchronization path.
delete from candles
where time_interval = '1wk';

-- Permit an immediate refill after deployment instead of observing a successful
-- synchronization cooldown left behind by the invalid cache.
delete from market_data_sync_state
where time_interval = '1wk';
