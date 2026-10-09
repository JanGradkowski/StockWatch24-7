-- Forgot-password responses must take the same time whether or not an account exists.
-- The request is queued here and a worker issues the code out of band.
create table password_reset_requests (
    id bigserial primary key,
    email varchar(254) not null,
    client_key varchar(64) not null,
    created_at timestamptz not null default current_timestamp
);
create index ix_password_reset_requests_created on password_reset_requests (created_at, id);
