-- Providers return split-adjusted history, but routine refreshes only rewrite the newest
-- bars. A detected split rescales older stored bars and every price derived from them once.
create table corporate_actions (
    id bigserial primary key,
    symbol varchar(20) not null,
    time_interval varchar(16) not null,
    cutoff_timestamp bigint not null,
    factor double precision not null check (factor > 0),
    numerator integer not null check (numerator > 0),
    denominator integer not null check (denominator > 0),
    applied_at timestamptz not null default current_timestamp,
    constraint uk_corporate_action unique (symbol, time_interval, cutoff_timestamp)
);

-- Zero or malformed provider bars were previously persisted; they are refetched on demand.
delete from candles
where open_price is null or high_price is null or low_price is null or close_price is null
   or open_price <= 0 or high_price <= 0 or low_price <= 0 or close_price <= 0
   or high_price < greatest(open_price, close_price)
   or low_price > least(open_price, close_price)
   or open_price = 'NaN'::float8 or high_price = 'NaN'::float8
   or low_price = 'NaN'::float8 or close_price = 'NaN'::float8;
