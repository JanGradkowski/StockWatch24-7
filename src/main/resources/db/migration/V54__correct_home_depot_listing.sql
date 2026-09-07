update stock_assets
set company_name = 'The Home Depot, Inc.',
    exchange = 'NYSE',
    currency = 'USD',
    mic_code = 'XNYS',
    country = 'US',
    instrument_type = 'EQUITY'
where upper(ticker_symbol) = 'HD';

insert into asset_provider_symbols
    (stock_asset_id, provider, provider_symbol, mic_code, resolution_source, verified_at)
select id, 'YAHOO_FINANCE', 'HD', 'XNYS', 'CURATED_US_UNIVERSE', current_timestamp
from stock_assets
where upper(ticker_symbol) = 'HD'
on conflict (stock_asset_id, provider) do update
set provider_symbol = excluded.provider_symbol,
    mic_code = excluded.mic_code,
    resolution_source = excluded.resolution_source,
    verified_at = excluded.verified_at;

