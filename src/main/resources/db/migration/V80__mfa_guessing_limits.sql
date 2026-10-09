-- Wrong authenticator codes are counted per account over a rolling day. Reaching the limit
-- pauses code entry for an hour, so a stolen password cannot be paired with unlimited guessing.
alter table users add column mfa_failure_count integer not null default 0;
alter table users add column mfa_failure_window_started_at timestamptz;
alter table users add column mfa_paused_until timestamptz;
