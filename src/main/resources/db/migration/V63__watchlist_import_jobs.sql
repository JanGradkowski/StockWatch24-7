create table watchlist_imports (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    watchlist_id bigint not null references watchlists(id) on delete cascade,
    settings text not null,
    created_at timestamptz not null default current_timestamp
);
create table watchlist_import_items (
    id bigserial primary key,
    import_id bigint not null references watchlist_imports(id) on delete cascade,
    symbol varchar(20) not null,
    metadata text not null,
    status varchar(16) not null default 'PENDING' check(status in ('PENDING','PROCESSING','DONE','FAILED')),
    lease_until timestamptz,
    error varchar(500),
    unique(import_id,symbol)
);
create index ix_watchlist_import_work on watchlist_import_items(status,lease_until,id);
create index ix_watchlist_import_owner on watchlist_imports(user_id,id);
