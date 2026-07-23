alter table stock_assets
    add column if not exists mic_code varchar(12),
    add column if not exists country varchar(100),
    add column if not exists figi varchar(32),
    add column if not exists isin varchar(16);

create table if not exists asset_provider_symbols (
    id bigserial primary key,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    provider varchar(32) not null,
    provider_symbol varchar(64) not null,
    mic_code varchar(12),
    resolution_source varchar(32) not null,
    verified_at timestamp with time zone not null default current_timestamp,
    constraint uk_asset_provider unique (stock_asset_id, provider)
);

create index if not exists ix_provider_symbol_lookup
    on asset_provider_symbols (provider, upper(provider_symbol), mic_code);
