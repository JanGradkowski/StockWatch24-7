create table user_analysis_preferences (
    id bigserial primary key,
    user_id bigint not null,
    profile_version varchar(32) not null,
    preferences_payload text not null,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_user_analysis_preferences_user unique (user_id),
    constraint fk_user_analysis_preferences_user
        foreign key (user_id) references users(id) on delete cascade
);

alter table alert_events
    add column if not exists factory_confidence_score integer,
    add column if not exists analysis_profile_version varchar(32),
    add column if not exists analysis_profile_snapshot text,
    add column if not exists initial_email_sent_at timestamp,
    add column if not exists lifecycle_confirmation_percent double precision,
    add column if not exists lifecycle_invalidation_percent double precision;

update alert_events
set initial_email_sent_at = sent_at
where initial_email_sent_at is null;

alter table alert_events
    add constraint ck_alert_events_factory_confidence_score
        check (factory_confidence_score is null or factory_confidence_score between 0 and 100),
    add constraint ck_alert_events_lifecycle_confirmation_percent
        check (lifecycle_confirmation_percent is null or lifecycle_confirmation_percent between 0 and 50),
    add constraint ck_alert_events_lifecycle_invalidation_percent
        check (lifecycle_invalidation_percent is null or lifecycle_invalidation_percent between 0 and 50);
