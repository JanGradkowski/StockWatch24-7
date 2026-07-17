-- Older versions could mark a short/empty Twelve Data page as the permanent
-- beginning even when Yahoo still had older daily candles. Re-evaluate daily
-- boundaries once with the corrected provider fallback logic.
delete from market_data_history_state
where time_interval = '1d';
