alter table stock_assets
    add column if not exists instrument_type varchar(20) not null default 'EQUITY';

update stock_assets
set instrument_type = 'INDEX'
where ticker_symbol like '^%';
