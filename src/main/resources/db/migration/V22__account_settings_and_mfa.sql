alter table users add column if not exists theme_preference varchar(16) not null default 'DARK';
alter table users add column if not exists mfa_enabled boolean not null default false;
alter table users add column if not exists mfa_secret_ciphertext varchar(512);
alter table users add column if not exists mfa_secret_iv varchar(64);
alter table users add column if not exists security_version bigint not null default 0;
alter table users add column if not exists last_accepted_totp_step bigint;
alter table users add column if not exists password_changed_at timestamp without time zone;
alter table users add column if not exists deletion_requested_at timestamp without time zone;
alter table users add column if not exists deletion_cancel_token_hash varchar(64);
alter table users add column if not exists deletion_cancel_expires_at timestamp without time zone;

create table if not exists password_security_codes (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    purpose varchar(32) not null,
    code_hash varchar(255) not null,
    expires_at timestamp without time zone not null,
    last_sent_at timestamp without time zone not null,
    failed_attempts integer not null default 0,
    constraint uk_password_security_code_user_purpose unique (user_id, purpose)
);

create table if not exists mfa_recovery_codes (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    code_hash varchar(64) not null,
    used_at timestamp without time zone,
    created_at timestamp without time zone not null default current_timestamp
);
create index if not exists ix_mfa_recovery_codes_user_unused
    on mfa_recovery_codes(user_id) where used_at is null;

create table if not exists security_events (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    event_type varchar(48) not null,
    description varchar(180) not null,
    created_at timestamp without time zone not null default current_timestamp
);
create index if not exists ix_security_events_user_created
    on security_events(user_id, created_at desc);
create unique index if not exists ux_users_deletion_cancel_token
    on users(deletion_cancel_token_hash) where deletion_cancel_token_hash is not null;
