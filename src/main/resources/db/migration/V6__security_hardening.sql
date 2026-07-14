alter table users
    add column if not exists verification_last_sent_at timestamp without time zone;

-- These fields were never wired into an MFA flow. Remove the dormant plaintext
-- secret storage; a future MFA implementation must use encrypted secret material.
alter table users drop column if exists two_factor_secret;
alter table users drop column if exists is_2fa_enabled;

create unique index if not exists ux_users_verification_token_hash
    on users (verification_token_hash)
    where verification_token_hash is not null;

create table if not exists security_resource_locks (
    lock_name varchar(64) primary key
);

insert into security_resource_locks (lock_name)
values ('alert-stock-quota')
on conflict (lock_name) do nothing;
