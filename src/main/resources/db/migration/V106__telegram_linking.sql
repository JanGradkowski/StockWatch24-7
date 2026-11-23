-- Linking a Telegram chat: the settings page creates a one-time start token and shows it as a t.me link. Only the
-- token's SHA-256 is stored. The page polls this request by its id (never the token) until the bot has seen /start.
create table telegram_link_requests (
    id uuid primary key,
    token_hash char(64) not null unique,
    user_id bigint not null references users(id) on delete cascade,
    created_at timestamptz not null default current_timestamp,
    expires_at timestamptz not null,
    used_at timestamptz,
    channel_id bigint references alert_channels(id) on delete set null
);
create index telegram_link_requests_user on telegram_link_requests(user_id, created_at);

-- One instance at a time asks Telegram for updates (/start, /stop, a blocked bot); the offset survives restarts,
-- so a failover neither loses nor replays updates.
create table telegram_bot_state (
    id smallint primary key default 1 check (id = 1),
    next_update_id bigint not null default 0,
    lease_owner uuid,
    lease_until timestamptz
);
insert into telegram_bot_state(id) values (1);
