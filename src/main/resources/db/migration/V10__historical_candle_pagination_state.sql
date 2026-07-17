create table if not exists market_data_history_state (
    symbol varchar(20) not null,
    time_interval varchar(16) not null,
    oldest_timestamp bigint,
    end_reached boolean not null default false,
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (symbol, time_interval)
);
