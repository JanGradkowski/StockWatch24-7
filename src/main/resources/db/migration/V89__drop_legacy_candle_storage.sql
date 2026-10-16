-- The unique constraint uk_candles_symbol_interval_timestamp already indexes (symbol, time_interval,
-- timestamp), and PostgreSQL scans it backwards for newest-first reads, so this second index only
-- doubled the write cost of every candle upsert on the largest table.
drop index if exists ix_candles_symbol_interval_timestamp;

-- candle_data was the first candle store. Since V1 the application reads and writes only candles;
-- nothing has used this table.
drop table if exists candle_data;
