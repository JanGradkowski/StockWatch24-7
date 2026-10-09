-- A job that is waiting out a provider/data outage must not hold every other
-- instrument behind it. Checkpoint continuations keep priority; outage backoffs yield it.
alter table alert_check_jobs add column yields_priority boolean not null default false;
alter table alert_check_jobs add column deferrals integer not null default 0;

-- Instruments must have returned real candles before anyone can follow them.
alter table stock_assets add column market_data_verified_at timestamptz;
-- Listing metadata is reused for a period instead of spending provider calls on every page view.
alter table stock_assets add column metadata_refreshed_at timestamptz;
update stock_assets asset set market_data_verified_at = current_timestamp
where exists (select 1 from candles c where c.symbol = asset.ticker_symbol and c.close_price > 0);
